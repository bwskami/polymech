package com.mss.polymech.block.entity;

import com.mss.polymech.api.material.ConveyorMaterial;
import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import com.mss.polymech.client.model.conveyor.BakedConveyorModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 传送带方块实体 —— 数据驱动高性能传输引擎（异星工场式）。
 * <p>
 * 物品不再使用世界实体，而是以 {@link BeltItem 物品包}（ItemStack + 进度）形式
 * 直接存储在 BE 内部有序列表中，性能特征：
 * </p>
 * <ul>
 *   <li><b>零实体扫描</b>：移动/排队/交接全部基于本 BE 的数组操作，
 *       仅在低频拾取掉落物时做一次 AABB 扫描</li>
 *   <li><b>双端确定性模拟</b>：服务端权威 tick，客户端按相同规则本地推进，
 *       纯移动过程<b>零网络包</b>；仅在物品构成变化（增/删/耗尽）时发送快照</li>
 *   <li><b>余进度连续交接</b>：物品到达带尾时以溢出进度进入下一格，跨格零停顿</li>
 *   <li><b>能力缓存</b>：前方容器的 ItemHandler 用 {@link BlockCapabilityCache} 缓存，
 *       方块变化自动失效，避免每 tick 能力查询</li>
 *   <li><b>注入指数退避</b>：前方容器满时重试间隔倍增（上限 40 tick），疏通后立即恢复</li>
 *   <li><b>下一格缓存</b>：目标传送带 BE 引用缓存，方块改动/低频复检时刷新</li>
 *   <li><b>物品包合并</b>：同种物品合并成包（容量由材质等级决定），减少包数量</li>
 * </ul>
 */
public class ConveyorBlockEntity extends BlockEntity {

    /** 排队间距（格）：同带相邻物品包的最小间隔 */
    public static final double PACKAGE_PITCH = 0.4D;

    /**
     * 直连入场检查区间：目标格 [0, 该值) 有包则拒绝（同物可合并）。
     * 与间距对齐：入场进度≈0，前车至少在该值处才放包进入，间距恒 ≥ PITCH。
     */
    public static final double DIRECT_ENTRY_CHECK = PACKAGE_PITCH;

    /** 转弯（侧入）时物品进入目标格的进度 */
    public static final double SIDE_ENTRY_PROGRESS = 0.5D;

    /**
     * 转弯入场检查区间：目标格 [0, 该值) 有包则拒绝。
     * = 入场进度 + 间距：侧入包落在 0.5 处，前车至少在 0.9 处，间距恒 ≥ PITCH。
     */
    public static final double SIDE_ENTRY_CHECK = SIDE_ENTRY_PROGRESS + PACKAGE_PITCH;

    /** 浮点容差 */
    static final double EPSILON = 1.0E-6D;

    /** 拾取掉落物的水平半径 */
    static final double PICKUP_RADIUS = 0.35D;

    /** 拾取扫描降频间隔（tick） */
    static final int PICKUP_INTERVAL = 4;

    /** 低频校准同步间隔（tick）：兜底修正客户端漂移 */
    static final int SYNC_INTERVAL = 40;

    /** 低频存档脏标记间隔（tick） */
    static final int DIRTY_INTERVAL = 40;

    /** 下一格缓存空结果的复检间隔（tick） */
    private static final int NEXT_RECHECK_INTERVAL = 20;

    /** 容器注入失败退避上限（tick） */
    private static final int MAX_INSERT_BACKOFF = 40;

    /** 本格内的物品包列表，按 progress 升序 */
    final ArrayList<BeltItem> items = new ArrayList<>();

    /** 所属线路（运行时统一驱动组织，不持久化；null=尚未组线） */
    @Nullable
    TransportLine lineRef;

    /** 容器注入失败退避计时 */
    private int insertBackoff;

    /** 结构变化标志（物品包增/删/耗尽）→ 需要快照同步 */
    boolean needsSync;

    /** 前方容器能力缓存（服务端） */
    @Nullable
    private BlockCapabilityCache<IItemHandler, @Nullable Direction> containerCache;
    @Nullable
    private BlockPos containerCachePos;
    @Nullable
    private Direction containerCacheSide;

    /** 下一格传送带缓存 */
    @Nullable
    private BlockPos nextPosCache;
    @Nullable
    private ConveyorBlockEntity nextBeCache;
    private long nextRecheckTime;

    /** 材质缓存（避免每 tick 注册表反查，setBlockState 时刷新） */
    @Nullable
    private ConveyorMaterial materialCache;

    /** 漏斗用 ItemHandler */
    private final ConveyorItemHandler itemHandler = new ConveyorItemHandler();

    public ConveyorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONVEYOR.get(), pos, state);
    }

    // ========== 材质参数 ==========

    /**
     * 材质查询（带缓存）。
     * <p>
     * fromBlock 需要注册表 getKey + 字符串比对，是每格每 tick 的热路径，
     * 因此首次解析后缓存，仅在 setBlockState 时失效。
     * </p>
     */
    public ConveyorMaterial getMaterial() {
        ConveyorMaterial material = materialCache;
        if (material == null) {
            material = ConveyorMaterial.fromBlock(getBlockState().getBlock());
            materialCache = material;
        }
        return material;
    }

    /** 每 tick 前进的格数 */
    public double getBeltSpeed() {
        return getMaterial().getBeltSpeed();
    }

    /** 物品包容量上限 */
    public int getStackLimit() {
        return getMaterial().getStackLimit();
    }

    /** 终点等待位：1 - speed（阻塞时停在此处，疏通后一步到达格尾） */
    public static double getEndWait(double speed) {
        return 1.0D - speed;
    }

    // ========== ModelData ==========

    public ModelData getModelData() {
        ConveyorType type = getBlockState().getValue(ConveyorBlock.TYPE);
        return ModelData.builder()
                .with(BakedConveyorModel.CONVEYOR_TYPE, type)
                .build();
    }

    // ========== 网络调试仪支持 ==========

    /**
     * 所属线路的身份标识（决定高亮颜色）；无线路时返回 -1。
     * <p>
     * 使用<b>稳定键</b>（线首坐标 + 朝向 + 材质）而非线路对象身份哈希：
     * 线路对象在区块装卸/拓扑变化时会重建，对象哈希会导致高亮颜色跳变；
     * 稳定键保证同一条线在重建前后颜色一致。
     * </p>
     */
    public int getLineId() {
        TransportLine line = lineRef;
        if (line == null || line.isEmpty()) return -1;
        ConveyorBlockEntity head = line.head();
        BlockPos headPos = head.getBlockPos();
        Direction headFacing = head.getBlockState().getValue(ConveyorBlock.FACING);
        long h = headPos.asLong();
        h = h * 31L + headFacing.get3DDataValue();
        h = h * 31L + line.getMaterial().getName().hashCode();
        return (int) (h ^ (h >>> 32));
    }

    /** 本格是否为所属线路的线首 */
    public boolean isLineHead() {
        TransportLine line = lineRef;
        return line != null && !line.isEmpty() && line.head() == this;
    }

    // ========== Tick（线路统一驱动） ==========

    /**
     * tick 入口：线路化驱动分发。
     * <p>
     * 本格所属线路的<b>线首</b>负责驱动整条线（服务端权威 + 客户端确定性模拟），
     * 其余成员 tick 直接空转（零开销）；未组线或断链时惰性重建。
     * </p>
     */
    public static void tick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity be) {
        TransportLine line = be.lineRef;
        if (line == null || line.isEmpty()) {
            be.refreshLine();
            line = be.lineRef;
        }
        if (line == null || line.isEmpty()) return;

        if (line.head() == be) {
            // 线首：驱动整条线
            line.tick(level);
        } else {
            // 空转成员 O(1) 自愈检测：线路失效（不含本格 / 线首已移除 /
            // 线首已脱离本线）时惰性重建——兜住挖掉线首等所有断链场景，
            // 避免成员永久空转、物品只靠低频快照瞬移
            ConveyorBlockEntity head = line.head();
            if (!line.contains(be) || head == null || head.isRemoved() || head.lineRef != line) {
                be.refreshLine();
            }
        }
    }

    /**
     * 尝试将物品包移出本格：容器注入 → 移交下一格 → 弹出掉落物。
     *
     * @return true 表示物品包已离开本格（调用方应移除）
     */
    boolean tryHandoff(Level level, BlockPos pos, BlockState state,
                       Direction facing, ConveyorType type, BeltItem item,
                       double newProgress, boolean server) {
        // 1. 前方容器注入（仅服务端、仅水平带）
        if (server && type == ConveyorType.HORIZONTAL && tryInsertIntoContainer(level, pos, facing, item)) {
            return true;
        }

        // 2. 下一格传送带：余进度连续交接
        ConveyorBlockEntity next = getNextConveyor(level, pos, facing, type);
        if (next != null) {
            Direction nextFacing = next.getBlockState().getValue(ConveyorBlock.FACING);
            boolean sideEntry = nextFacing != facing;
            double entryProgress = sideEntry
                    ? SIDE_ENTRY_PROGRESS
                    : Math.max(0.0D, newProgress - 1.0D);

            // 来源方向（从来源格指向目标格），供客户端转弯平滑旋转
            Direction sourceDir = Direction.getNearest(
                    next.worldPosition.getX() - worldPosition.getX(),
                    next.worldPosition.getY() - worldPosition.getY(),
                    next.worldPosition.getZ() - worldPosition.getZ());
            return next.acceptIncoming(item.getStack(), entryProgress, sideEntry, sourceDir);
        }

        // 3. 无下一格：服务端弹出为掉落物；客户端原地等待快照
        if (server) {
            ejectAsItemEntity(level, pos, facing, item);
            return true;
        }
        return false;
    }

    /**
     * 接收来自前一格传送带的物品包（双端规则一致，保证确定性）。
     * <p>
     * 入口检查：直连时目标格 [0, {@link #DIRECT_ENTRY_CHECK}) 区域必须空闲
     * （或可合并同物包）；转弯时 [0, {@link #SIDE_ENTRY_CHECK}) 必须空闲且不合并。
     * </p>
     *
     * @param sourceDir 来源方向（从来源格指向本格），转弯时记录到包上供客户端平滑旋转
     */
    public boolean acceptIncoming(ItemStack incoming, double entryProgress, boolean sideEntry,
                                  Direction sourceDir) {
        if (level == null) return false;
        boolean server = !level.isClientSide();

        int limit = getStackLimit();
        double checkMax = sideEntry ? SIDE_ENTRY_CHECK : DIRECT_ENTRY_CHECK;

        BeltItem mergeTarget = null;
        for (BeltItem item : items) {
            if (item.getProgress() < checkMax) {
                if (!sideEntry && item.canMerge(incoming, limit)) {
                    mergeTarget = item;
                    break;
                }
                return false; // 入口区域被占用且无法合并
            }
        }

        if (mergeTarget != null) {
            // 直连合并：包汇入入口处同物包，无需新增位置
            mergeTarget.merge(incoming, limit);
        } else {
            BeltItem created = new BeltItem(incoming.copy(), Math.min(entryProgress, 0.99D));
            created.setEntryDir(sideEntry ? (byte) sourceDir.get3DDataValue() : BeltItem.NO_ENTRY_TURN);
            insertSorted(created);
        }
        if (server) {
            setChanged();
            needsSync = true;
        }

        // 立即级联驱动接收线：若接收线本 tick 已先驱动过（BE tick 顺序不定），
        // 新包会原地停一 tick，表现为“经过别的传送带卡一下”；级联驱动让新包
        // 即刻启程，且双端行为与 BE tick 顺序无关（包上有每 tick 一步印记兼底）
        TransportLine recvLine = lineRef;
        if (recvLine != null && !recvLine.isEmpty()) {
            recvLine.tick(level);
        }
        return true;
    }

    // ========== 容器注入（带缓存与指数退避） ==========

    /**
     * 尝试把物品包注入前方容器（服务端）。
     * <p>
     * 能装多少装多少；一点都装不进时启用指数退避（4→8→…→40 tick），
     * 疏通后立即重置，维持吞吐节奏。
     * </p>
     *
     * @return true 表示包内物品已全部注入（调用方应移除包）
     */
    boolean tryInsertIntoContainer(Level level, BlockPos pos, Direction facing, BeltItem item) {
        if (insertBackoff > 0) {
            insertBackoff--;
            return false;
        }

        IItemHandler handler = getContainerHandler((ServerLevel) level, pos, facing);
        if (handler == null) return false;

        ItemStack stack = item.getStack();
        int inserted = insertAll(handler, stack);
        if (inserted <= 0) {
            insertBackoff = insertBackoff == 0 ? 4 : Math.min(insertBackoff * 2, MAX_INSERT_BACKOFF);
            return false;
        }

        item.shrink(inserted);
        insertBackoff = 0;
        setChanged();
        return item.isEmpty();
    }

    /**
     * 获取前方容器的 ItemHandler（能力缓存，方块变化自动失效）。
     */
    @Nullable
    IItemHandler getContainerHandler(ServerLevel level, BlockPos pos, Direction facing) {
        BlockPos containerPos = pos.relative(facing);
        BlockState frontState = level.getBlockState(containerPos);

        // 前方是传送带时不走能力注入，由跨带移交直接转移
        if (frontState.getBlock() instanceof ConveyorBlock) return null;
        if (!frontState.hasBlockEntity()) return null;

        Direction side = facing.getOpposite();
        if (containerCache == null
                || !containerPos.equals(containerCachePos)
                || side != containerCacheSide) {
            containerCachePos = containerPos;
            containerCacheSide = side;
            containerCache = BlockCapabilityCache.create(
                    Capabilities.ItemHandler.BLOCK, level, containerPos, side,
                    () -> !isRemoved(), () -> {});
        }

        IItemHandler handler = containerCache.getCapability();
        if (handler == null) {
            // 侧面拒绝暴露能力时兜底查一次无方向能力
            handler = level.getCapability(Capabilities.ItemHandler.BLOCK, containerPos, null);
        }
        return handler;
    }

    /** 把整个栈尽可能插入容器，返回实际插入数量 */
    static int insertAll(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < handler.getSlots() && !remaining.isEmpty(); i++) {
            remaining = handler.insertItem(i, remaining, false);
        }
        return stack.getCount() - remaining.getCount();
    }

    /** 把物品包弹出为掉落物 */
    void ejectAsItemEntity(Level level, BlockPos pos, Direction facing, BeltItem item) {
        double ejectX = pos.getX() + 0.5 + facing.getStepX() * 0.6;
        double ejectZ = pos.getZ() + 0.5 + facing.getStepZ() * 0.6;
        double ejectY = pos.getY() + 4.0 / 16.0 + 0.3;

        ItemEntity entity = new ItemEntity(level, ejectX, ejectY, ejectZ, item.getStack().copy());
        entity.setDeltaMovement(facing.getStepX() * 0.15, 0.2, facing.getStepZ() * 0.15);
        entity.setPickUpDelay(10);
        level.addFreshEntity(entity);
    }

    // ========== 拾取掉落物（降频扫描） ==========

    /**
     * 拾取传送带起点附近的掉落物（每 {@link #PICKUP_INTERVAL} tick 一次）。
     * <p>
     * 优先整组拾取并合并进起点同物包；入口区域被异物品包占用时拒绝；
     * 每次调用最多拾取一个掉落物（整线拾取由 {@link TransportLine} 统一扫描调度）。
     * </p>
     */
    boolean tryPickupItems(Level level, BlockPos pos) {
        AABB pickupBox = new AABB(
                pos.getX() + 0.5 - PICKUP_RADIUS, pos.getY() + 0.02, pos.getZ() + 0.5 - PICKUP_RADIUS,
                pos.getX() + 0.5 + PICKUP_RADIUS, pos.getY() + 1.3, pos.getZ() + 0.5 + PICKUP_RADIUS
        );

        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, pickupBox,
                item -> item.isAlive() && !item.getItem().isEmpty());
        if (drops.isEmpty()) return false;

        ItemEntity drop = drops.getFirst();
        ItemStack dropStack = drop.getItem();
        int limit = getStackLimit();

        // 起点区域检查：可合并则记录，否则拒绝
        BeltItem mergeTarget = null;
        for (BeltItem item : items) {
            if (item.getProgress() < PACKAGE_PITCH) {
                if (item.canMerge(dropStack, limit)) {
                    mergeTarget = item;
                    break;
                }
                return false; // 入口被异物品包占用
            }
        }

        if (mergeTarget != null) {
            int remaining = mergeTarget.merge(dropStack, limit);
            if (remaining == 0) {
                drop.discard();
            } else {
                dropStack.setCount(remaining);
            }
        } else {
            int take = Math.min(dropStack.getCount(), limit);
            insertSorted(new BeltItem(dropStack.copyWithCount(take), 0.0D));
            if (take >= dropStack.getCount()) {
                drop.discard();
            } else {
                dropStack.setCount(dropStack.getCount() - take);
            }
        }

        setChanged();
        return true;
    }

    // ========== 下一格缓存与寻路 ==========

    /**
     * 获取下一格传送带 BE（带缓存）。
     * <p>
     * 已解析的目标用 BE 引用恒等校验（O(1)）；空结果每
     * {@link #NEXT_RECHECK_INTERVAL} tick 复检一次，及时感知新放置的传送带。
     * </p>
     */
    @Nullable
    ConveyorBlockEntity getNextConveyor(Level level, BlockPos pos, Direction facing, ConveyorType type) {
        // 已缓存目标：引用仍然有效则直接返回
        if (nextBeCache != null && nextPosCache != null) {
            if (!nextBeCache.isRemoved() && level.getBlockEntity(nextPosCache) == nextBeCache) {
                return nextBeCache;
            }
            nextBeCache = null;
            nextPosCache = null;
        }

        // 空结果低频复检
        if (level.getGameTime() < nextRecheckTime) {
            return null;
        }
        nextRecheckTime = level.getGameTime() + NEXT_RECHECK_INTERVAL;

        BlockPos nextPos = findNextConveyor(level, pos, facing, type);
        if (nextPos != null && level.getBlockEntity(nextPos) instanceof ConveyorBlockEntity nextBE) {
            nextPosCache = nextPos;
            nextBeCache = nextBE;
            return nextBE;
        }
        return null;
    }

    @Nullable
    static BlockPos findNextConveyor(Level level, BlockPos pos, Direction facing, ConveyorType type) {
        return switch (type) {
            case UP -> findNextFromUp(level, pos, facing);
            case DOWN -> findNextFromDown(level, pos, facing);
            default -> findNextFromHorizontal(level, pos, facing);
        };
    }

    static BlockPos findNextFromUp(Level level, BlockPos pos, Direction facing) {
        BlockPos upper = pos.relative(facing).above();
        BlockState upperState = level.getBlockState(upper);
        if (upperState.getBlock() instanceof ConveyorBlock
                && upperState.getValue(ConveyorBlock.FACING) == facing) {
            return upper;
        }
        return null;
    }

    static BlockPos findNextFromDown(Level level, BlockPos pos, Direction facing) {
        BlockPos front = pos.relative(facing);

        BlockState frontState = level.getBlockState(front);
        if (frontState.getBlock() instanceof ConveyorBlock
                && frontState.getValue(ConveyorBlock.FACING) == facing
                && frontState.getValue(ConveyorBlock.TYPE) != ConveyorType.DOWN) {
            return front;
        }

        BlockPos lower = front.below();
        BlockState lowerState = level.getBlockState(lower);
        if (lowerState.getBlock() instanceof ConveyorBlock
                && lowerState.getValue(ConveyorBlock.FACING) == facing) {
            return lower;
        }

        return null;
    }

    static BlockPos findNextFromHorizontal(Level level, BlockPos pos, Direction facing) {
        BlockPos front = pos.relative(facing);

        // 1. 同层前方同朝向（水平链，或转上坡/下坡）
        BlockState frontState = level.getBlockState(front);
        if (frontState.getBlock() instanceof ConveyorBlock
                && frontState.getValue(ConveyorBlock.FACING) == facing) {
            return front;
        }

        // 2. 前方下层同朝向（水平→下坡）
        BlockPos frontBelow = front.below();
        BlockState frontBelowState = level.getBlockState(frontBelow);
        if (frontBelowState.getBlock() instanceof ConveyorBlock
                && frontBelowState.getValue(ConveyorBlock.FACING) == facing) {
            return frontBelow;
        }

        // 3. 前方任意传送带（侧向馈入）
        if (frontState.getBlock() instanceof ConveyorBlock) {
            return front;
        }

        return null;
    }

    @Override
    public void setBlockState(BlockState state) {
        super.setBlockState(state);
        // 朝向/类型/材质变化 → 全部缓存失效
        nextBeCache = null;
        nextPosCache = null;
        nextRecheckTime = 0;
        containerCache = null;
        containerCachePos = null;
        containerCacheSide = null;
        materialCache = null;
    }

    // ========== 线路生命周期 ==========

    @Override
    public void onLoad() {
        super.onLoad();
        // 加载后尝试与已加载邻居组线（服务端/客户端各自组建镜像）
        refreshLine();
    }

    @Override
    public void onChunkUnloaded() {
        // 铁律：卸载路径只除名，绝不访问邻居区块（防卡死）
        TransportLine line = lineRef;
        if (line != null) {
            line.removeMember(this);
        }
        lineRef = null;
    }

    /** 拆除方块时调用：从线路除名，断链后半段由各自 tick 惰性重建 */
    public void detachFromLine() {
        leaveLine();
    }

    /** 从所属线路除名（幂等） */
    void leaveLine() {
        TransportLine line = lineRef;
        if (line != null) {
            line.removeMember(this);
        }
        lineRef = null;
    }

    /**
     * 重建所属线路（幂等）：从本格向头部找已加载线首，向后收集直连成员，
     * 统一脱离旧线路后并入新线路；所有传送带（含孤立带）都进线路。
     */
    public void refreshLine() {
        Level lvl = level;
        if (lvl == null || isRemoved()) return;

        // 1. 向头部方向找已加载线首
        ConveyorBlockEntity head = this;
        HashSet<BlockPos> visited = new HashSet<>();
        visited.add(this.worldPosition);
        while (true) {
            ConveyorBlockEntity prev = findLoadedDirectPrev(lvl, head);
            if (prev == null || !visited.add(prev.worldPosition)) break;
            head = prev;
        }

        // 2. 已就绪短路：线首在线且包含本格
        TransportLine existing = head.lineRef;
        if (existing != null && !existing.isEmpty()
                && existing.head() == head && existing.contains(this)) {
            return;
        }

        // 3. 从线首向后收集已加载直连成员
        ArrayList<ConveyorBlockEntity> chain = new ArrayList<>();
        chain.add(head);
        ConveyorBlockEntity cur = head;
        while (true) {
            ConveyorBlockEntity next = findLoadedDirectNext(lvl, cur);
            if (next == null || chain.contains(next)) break;
            chain.add(next);
            cur = next;
        }

        // 4. 脱离旧线路后统一加入新线路（拓扑变化低频，新建对象开销可忽略）
        for (ConveyorBlockEntity m : chain) {
            m.leaveLine();
        }
        TransportLine line = new TransportLine(head.getMaterial());
        for (ConveyorBlockEntity m : chain) {
            line.addMember(m);
        }
    }

    // ========== 线路组网寻路（非阻塞，绝不强制加载区块） ==========

    /** 区块是否已加载（非阻塞，绝不强制加载） */
    static boolean isChunkLoaded(Level level, BlockPos pos) {
        return level.hasChunkAt(pos);
    }

    /**
     * 已加载的直连下一格（同材质、同朝向，与 {@link #findNextConveyor} 规则一致，
     * 但不含侧向馈入）。
     */
    @Nullable
    static ConveyorBlockEntity findLoadedDirectNext(Level level, ConveyorBlockEntity be) {
        BlockPos pos = be.worldPosition;
        BlockState state = be.getBlockState();
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);
        ConveyorMaterial mat = be.getMaterial();

        switch (type) {
            case UP: {
                BlockPos upper = pos.relative(facing).above();
                if (!isChunkLoaded(level, upper)) return null;
                if (isDirectMatch(level, upper, facing, mat)) {
                    return (ConveyorBlockEntity) level.getBlockEntity(upper);
                }
                return null;
            }
            case DOWN: {
                BlockPos front = pos.relative(facing);
                if (isChunkLoaded(level, front)) {
                    BlockState frontState = level.getBlockState(front);
                    if (frontState.getBlock() instanceof ConveyorBlock
                            && frontState.getValue(ConveyorBlock.FACING) == facing
                            && frontState.getValue(ConveyorBlock.TYPE) != ConveyorType.DOWN
                            && ConveyorMaterial.fromBlock(frontState.getBlock()) == mat) {
                        return (ConveyorBlockEntity) level.getBlockEntity(front);
                    }
                }
                BlockPos lower = front.below();
                if (isChunkLoaded(level, lower) && isDirectMatch(level, lower, facing, mat)) {
                    return (ConveyorBlockEntity) level.getBlockEntity(lower);
                }
                return null;
            }
            default: {
                BlockPos front = pos.relative(facing);
                if (isChunkLoaded(level, front) && isDirectMatch(level, front, facing, mat)) {
                    return (ConveyorBlockEntity) level.getBlockEntity(front);
                }
                BlockPos frontBelow = front.below();
                if (isChunkLoaded(level, frontBelow) && isDirectMatch(level, frontBelow, facing, mat)) {
                    return (ConveyorBlockEntity) level.getBlockEntity(frontBelow);
                }
                return null;
            }
        }
    }

    /**
     * 已加载的直连上一格：候选位置为后方同层/上方/下方，
     * 验证候选的直连下一格是本格且同材质同朝向。
     */
    @Nullable
    static ConveyorBlockEntity findLoadedDirectPrev(Level level, ConveyorBlockEntity be) {
        Direction facing = be.getBlockState().getValue(ConveyorBlock.FACING);
        Direction back = facing.getOpposite();
        BlockPos pos = be.worldPosition;
        ConveyorMaterial mat = be.getMaterial();

        BlockPos[] candidates = {
                pos.relative(back),
                pos.relative(back).above(),
                pos.relative(back).below()
        };
        for (BlockPos cand : candidates) {
            if (!isChunkLoaded(level, cand)) continue;
            BlockState cs = level.getBlockState(cand);
            if (!(cs.getBlock() instanceof ConveyorBlock)) continue;
            if (cs.getValue(ConveyorBlock.FACING) != facing) continue;
            if (ConveyorMaterial.fromBlock(cs.getBlock()) != mat) continue;
            if (isDirectNext(level, cand, pos, facing)) {
                return (ConveyorBlockEntity) level.getBlockEntity(cand);
            }
        }
        return null;
    }

    /** 候选位置是否为同材质同朝向的传送带 */
    private static boolean isDirectMatch(Level level, BlockPos pos, Direction facing, ConveyorMaterial mat) {
        BlockState s = level.getBlockState(pos);
        return s.getBlock() instanceof ConveyorBlock
                && s.getValue(ConveyorBlock.FACING) == facing
                && ConveyorMaterial.fromBlock(s.getBlock()) == mat;
    }

    /** fromPos 的直连下一格是否为 targetPos（与 findNext 规则一致，不含侧向馈入） */
    private static boolean isDirectNext(Level level, BlockPos fromPos, BlockPos targetPos, Direction facing) {
        BlockState fromState = level.getBlockState(fromPos);
        ConveyorType type = fromState.getValue(ConveyorBlock.TYPE);
        BlockPos front = fromPos.relative(facing);
        if (!isChunkLoaded(level, front)) return false;

        switch (type) {
            case UP:
                return targetPos.equals(front.above());
            case DOWN: {
                BlockState frontState = level.getBlockState(front);
                if (frontState.getBlock() instanceof ConveyorBlock
                        && frontState.getValue(ConveyorBlock.FACING) == facing
                        && frontState.getValue(ConveyorBlock.TYPE) != ConveyorType.DOWN) {
                    return targetPos.equals(front);
                }
                return targetPos.equals(front.below());
            }
            default: {
                BlockState frontState = level.getBlockState(front);
                if (frontState.getBlock() instanceof ConveyorBlock
                        && frontState.getValue(ConveyorBlock.FACING) == facing) {
                    return targetPos.equals(front);
                }
                return targetPos.equals(front.below());
            }
        }
    }

    // ========== 玩家交互 API ==========

    /**
     * 起点放入物品（每次 1 个）：合并进起点同物包，或新建物品包。
     *
     * @return true 表示成功放入
     */
    public boolean insertStack(ItemStack stack) {
        if (level == null || level.isClientSide()) return false;

        int limit = getStackLimit();
        for (BeltItem item : items) {
            if (item.getProgress() < PACKAGE_PITCH) {
                if (item.canMerge(stack, limit)) {
                    item.merge(stack.copyWithCount(1), limit);
                    setChanged();
                    needsSync = true;
                    syncToClient();
                    return true;
                }
                return false; // 入口被异物品包占用
            }
        }

        insertSorted(new BeltItem(stack.copyWithCount(1), 0.0D));
        setChanged();
        needsSync = true;
        syncToClient();
        return true;
    }

    /**
     * 空手取出：优先取瞄准点附近（0.8 格内）的包，否则取终点附近（进度最大）的包。
     *
     * @return 取出的整个物品包；无物品时返回 EMPTY
     */
    public ItemStack pickupNear(double hitX, double hitZ) {
        if (level == null || level.isClientSide()) return ItemStack.EMPTY;

        BlockState state = getBlockState();
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        BeltItem target = null;
        double closestDist = 0.8D;
        double[] p = new double[3];
        for (BeltItem item : items) {
            computeItemPosition(worldPosition, facing, type, item.getProgress(), p);
            double dx = p[0] - hitX;
            double dz = p[2] - hitZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < closestDist) {
                closestDist = dist;
                target = item;
            }
        }

        // 瞄准点附近没有物品：取终点附近的包
        if (target == null && !items.isEmpty()) {
            target = items.get(items.size() - 1);
        }
        if (target == null) return ItemStack.EMPTY;

        ItemStack result = target.getStack().copy();
        items.remove(target);
        setChanged();
        syncToClient();
        return result;
    }

    /** 拆除方块时：把本格所有物品包弹出为掉落物 */
    public void ejectAllItems() {
        if (level == null || level.isClientSide()) return;
        Direction facing = getBlockState().getValue(ConveyorBlock.FACING);
        for (BeltItem item : items) {
            ejectAsItemEntity(level, worldPosition, facing, item);
        }
        items.clear();
        setChanged();
    }

    /** 客户端渲染读取的物品包列表（只读） */
    public List<BeltItem> getItemsForRender() {
        return items;
    }

    /**
     * 计算物品包在指定格内的世界坐标（渲染与交互共用）。
     * 结果写入 out 数组（避免每次调用分配）。
     */
    public static void computeItemPosition(BlockPos conveyorPos, Direction facing,
                                           ConveyorType type, double progress, double[] out) {
        out[0] = conveyorPos.getX() + 0.5 + facing.getStepX() * (progress - 0.5);
        out[2] = conveyorPos.getZ() + 0.5 + facing.getStepZ() * (progress - 0.5);
        out[1] = switch (type) {
            case UP -> conveyorPos.getY() + (4.0 + 16.0 * progress) / 16.0;
            case DOWN -> conveyorPos.getY() + (20.0 - 16.0 * progress) / 16.0;
            default -> conveyorPos.getY() + 4.0 / 16.0;
        };
    }

    // ========== 内部工具 ==========

    /** 按 progress 升序插入（列表规模小，线性定位足够） */
    void insertSorted(BeltItem item) {
        int i = items.size();
        while (i > 0 && items.get(i - 1).getProgress() > item.getProgress()) {
            i--;
        }
        items.add(i, item);
    }

    // ========== 同步 ==========

    /** 发送 BE 数据包（含物品包快照）给客户端 */
    public void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    // ========== NBT ==========

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (BeltItem item : items) {
            list.add(item.save(new CompoundTag(), registries));
        }
        tag.put("Items", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boolean client = level != null && level.isClientSide();

        // 客户端：保留旧快照用于插值对齐
        List<BeltItem> oldItems = client ? new ArrayList<>(items) : null;

        items.clear();
        if (tag.contains("Items", Tag.TAG_LIST)) {
            ListTag list = tag.getList("Items", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                BeltItem item = BeltItem.load(list.getCompound(i), registries);
                // 防御：钳制进度到 [0, 0.99]，防止越界存档数据
                item.setProgress(Mth.clamp(item.getProgress(), 0.0D, 0.99D));
                items.add(item);
            }
            items.sort(Comparator.comparingDouble(BeltItem::getProgress));
        }

        // 客户端快照对齐：新包 prevProgress = 旧包 progress（按索引对齐），
        // 避免校正瞬间位置跳变
        if (client && oldItems != null) {
            int n = Math.min(items.size(), oldItems.size());
            for (int i = 0; i < n; i++) {
                items.get(i).setPrevProgress(oldItems.get(i).getProgress());
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
        requestModelDataUpdate();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ========== 漏斗交互 (IItemHandler) ==========

    @Nullable
    public IItemHandler getItemHandler(@Nullable Direction side) {
        return itemHandler;
    }

    /**
     * 内置 ItemHandler：起点注入（每次 1 个，可合并进起点包）、终点提取（按数量扣减）。
     */
    private class ConveyorItemHandler implements IItemHandler {

        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            BeltItem item = findItemAtEnd();
            return item != null ? item.getStack().copy() : ItemStack.EMPTY;
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            if (level == null || level.isClientSide()) return stack;

            int limit = getStackLimit();

            // 起点区域检查：可合并则记录，被异物品包占用则拒绝
            BeltItem mergeTarget = null;
            for (BeltItem item : items) {
                if (item.getProgress() < PACKAGE_PITCH) {
                    if (item.canMerge(stack, limit)) {
                        mergeTarget = item;
                        break;
                    }
                    return stack;
                }
            }

            if (!simulate) {
                if (mergeTarget != null) {
                    mergeTarget.merge(stack.copyWithCount(1), limit);
                } else {
                    insertSorted(new BeltItem(stack.copyWithCount(1), 0.0D));
                }
                setChanged();
                syncToClient();
            }

            // 每次调用只接受 1 个。余量基于原始栈计算，不篡改入参，防止刷物。
            ItemStack remainder = stack.copy();
            remainder.shrink(1);
            return remainder;
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (level == null || level.isClientSide()) return ItemStack.EMPTY;

            BeltItem item = findItemAtEnd();
            if (item == null) return ItemStack.EMPTY;

            int toExtract = Math.min(amount, item.getCount());
            ItemStack result = item.getStack().copyWithCount(toExtract);

            if (!simulate) {
                item.shrink(toExtract);
                if (item.isEmpty()) {
                    items.remove(item);
                }
                setChanged();
                syncToClient();
            }

            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return getStackLimit();
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return true;
        }

        /** 找到终点等待区的物品包（进度最大且已到达终点等待位） */
        @Nullable
        private BeltItem findItemAtEnd() {
            double endWait = getEndWait(getBeltSpeed());
            for (int i = items.size() - 1; i >= 0; i--) {
                BeltItem item = items.get(i);
                if (item.getProgress() >= endWait - EPSILON) {
                    return item;
                }
            }
            return null;
        }
    }
}
