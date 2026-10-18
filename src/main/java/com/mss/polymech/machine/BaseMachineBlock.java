package com.mss.polymech.machine;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.machine.common.MachineGeometry;
import com.mss.polymech.machine.common.MachineRegistry;
import com.mss.polymech.machine.definition.MachineDefinition;
import com.mss.polymech.machine.definition.MachineDefinitionManager;
import com.mss.polymech.powergrid.GridNodeBlock;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public abstract class BaseMachineBlock extends BaseEntityBlock implements GridNodeBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /**
     * 获取主方块对应的侧面方块引用。
     * <p>单方块机器返回 null。</p>
     */
    @Nullable
    public DeferredBlock<?> getSideBlock() {
        return null;
    }

    /**
     * 获取主方块实体类型。
     */
    @Nullable
    public abstract BlockEntityType<?> getMachineBlockEntityType();

    /**
     * 获取侧面方块实体类型，用于统一的能力注册等场景。
     * <p>单方块机器返回 null。</p>
     */
    @Nullable
    public BlockEntityType<?> getSideBlockEntityType() {
        return null;
    }

    /**
     * 本机器的定义 id，对应 {@code data/poly_mech/machine/<id>.json}。
     * <p>单方块机器（电池、小型蒸汽锅炉等）没有结构定义，返回 null。</p>
     */
    @Nullable
    public String getMachineId() {
        return null;
    }

    /** 本机器的结构定义；没有定义（单方块机器）时返回 null。 */
    @Nullable
    public MachineDefinition definition() {
        String id = getMachineId();
        return id == null ? null : MachineDefinitionManager.get(id);
    }

    /**
     * 获取侧面方块相对于主方块的本地坐标偏移数组。
     * <p>来自 JSON 定义（{@code side_offsets}）；单方块机器返回空数组。</p>
     */
    public Vec3i[] getSideOffsets() {
        MachineDefinition def = definition();
        return def == null ? new Vec3i[0] : def.sideOffsets();
    }

    /**
     * 获取填充区域，返回二维数组，每个元素为{min, max}的Vec3i对。
     * <p>来自 JSON 定义（{@code fill_regions}）；无填充区域时返回 null。</p>
     */
    @Nullable
    public Vec3i[][] getFillRegions() {
        MachineDefinition def = definition();
        return def == null ? null : def.fillRegions();
    }

    // ========== 侧面方块代理配置 ==========

    /**
     * 代理 IO 方向：INPUT 被动接收外部输入；
     * OUTPUT 由机器 BE 周期性主动向外推送。
     */
    public enum ProxyIO {
        INPUT,
        OUTPUT
    }

    /**
     * 物品代理声明：暴露的内部槽位索引 + IO 方向 + 有效面 + <b>按面的槽位</b>。
     * 槽位索引直接指向主方块 BE 的 itemStackHandler，
     * 能力层会将其包装为过滤视图，外部设备只能看到/操作这些槽位。
     * <p>
     * {@code faces}（本地坐标，facing=NORTH）声明代理仅生效的面：
     * null 或空数组表示六面全通；否则只有声明的面才暴露能力/参与主动推送，
     * 管道自动铺设也会吸附到首个声明面所对的格子。
     * </p>
     * <h4>槽位归属是「面」而不是「方块」</h4>
     * 同一个舱室方块的不同面可以代理<strong>不同的槽位</strong>
     * （例：北面只暴露燃料槽 2、上面只暴露灰烬槽 5）。
     * {@code faceSlots} 的键是<strong>本地方向</strong>（与 {@code faces} 同一套坐标），
     * 值是那一面暴露的槽位下标；<strong>没列出的面回退到 {@code slots}</strong>。
     * 只想按面配置时把 {@code slots} 留空、只写 {@code faceSlots} 即可。
     */
    public record ItemProxy(int[] slots, ProxyIO io, @Nullable Direction[] faces,
                            @Nullable Map<Direction, int[]> faceSlots) {
        public ItemProxy(int[] slots, ProxyIO io) {
            this(slots, io, null, null);
        }

        public ItemProxy(int[] slots, ProxyIO io, @Nullable Direction[] faces) {
            this(slots, io, faces, null);
        }

        /** 世界方向 side 是否允许访问该代理（未声明面时六面全通；null 查询面视为通过） */
        public boolean allowsWorldFace(@Nullable Direction worldSide, Direction facing) {
            if (faces == null || faces.length == 0) return true;
            if (worldSide == null) return true;
            for (Direction f : faces) {
                if (MachineGeometry.rotateDirection(f, facing) == worldSide) return true;
            }
            return false;
        }

        /**
         * 世界方向 side 实际暴露的槽位：优先取 {@code faceSlots} 里该面的配置，
         * 没配就回退到 {@code slots}。
         * <p>这里调 {@link MachineGeometry#rotateDirection} 而不是外层类的同名静态方法：
         * 后者会触发 {@code BaseMachineBlock.<clinit>}（注册表依赖），离线校验会炸。</p>
         */
        public int[] slotsFor(@Nullable Direction worldSide, Direction facing) {
            int[] per = faceSlotsFor(worldSide, facing);
            return per != null ? per : slots;
        }

        /** 该面在 {@code faceSlots} 里的配置；没配返回 null（与「配了空数组」区分）。 */
        @Nullable
        public int[] faceSlotsFor(@Nullable Direction worldSide, Direction facing) {
            if (worldSide == null || faceSlots == null || faceSlots.isEmpty()) return null;
            for (Map.Entry<Direction, int[]> e : faceSlots.entrySet()) {
                if (MachineGeometry.rotateDirection(e.getKey(), facing) == worldSide) {
                    return e.getValue() == null ? new int[0] : e.getValue();
                }
            }
            return null;
        }
    }

    /**
     * 流体代理声明：暴露的逻辑储罐索引 + IO 方向 + 有效面 + <b>按面的储罐</b>。
     * 储罐索引指向 BE 的逻辑储罐列表（{@code BaseIOBlockEntity#getFluidTank(int)}），
     * 支持多进多出的任意储罐数量；单个位置可暴露多个储罐（能力层拼接为组合 handler）。
     * <p>
     * {@code faces} 语义同 {@link ItemProxy}：null/空=六面全通，否则仅声明面生效。
     * {@code faceTanks} 语义同 {@link ItemProxy#faceSlots()}：按面覆盖，未列出回退 {@code tanks}。
     * </p>
     */
    public record FluidProxy(int[] tanks, ProxyIO io, @Nullable Direction[] faces,
                             @Nullable Map<Direction, int[]> faceTanks) {
        public FluidProxy(int[] tanks, ProxyIO io) {
            this(tanks, io, null, null);
        }

        public FluidProxy(int[] tanks, ProxyIO io, @Nullable Direction[] faces) {
            this(tanks, io, faces, null);
        }

        /** 世界方向 side 是否允许访问该代理（未声明面时六面全通；null 查询面视为通过） */
        public boolean allowsWorldFace(@Nullable Direction worldSide, Direction facing) {
            if (faces == null || faces.length == 0) return true;
            if (worldSide == null) return true;
            for (Direction f : faces) {
                if (MachineGeometry.rotateDirection(f, facing) == worldSide) return true;
            }
            return false;
        }

        /** 世界方向 side 实际暴露的储罐：优先按面配置，没配回退 {@code tanks}。 */
        public int[] tanksFor(@Nullable Direction worldSide, Direction facing) {
            int[] per = faceTanksFor(worldSide, facing);
            return per != null ? per : tanks;
        }

        /** 该面在 {@code faceTanks} 里的配置；没配返回 null（与「配了空数组」区分）。 */
        @Nullable
        public int[] faceTanksFor(@Nullable Direction worldSide, Direction facing) {
            if (worldSide == null || faceTanks == null || faceTanks.isEmpty()) return null;
            for (Map.Entry<Direction, int[]> e : faceTanks.entrySet()) {
                if (MachineGeometry.rotateDirection(e.getKey(), facing) == worldSide) {
                    return e.getValue() == null ? new int[0] : e.getValue();
                }
            }
            return null;
        }
    }

    /**
     * 将本地坐标的方向（facing=NORTH 声明）旋转为实际世界方向。
     */
    public static Direction rotateDirection(Direction local, Direction facing) {
        return MachineGeometry.rotateDirection(local, facing);
    }

    /**
     * 获取指定本地偏移位置的物品代理声明（槽位 + IO 方向 + 有效面）。
     * <p>来自 JSON 定义的 {@code item_proxies}；返回 null 表示该位置不代理物品能力（纯占位）。</p>
     *
     * @param relativeOffset 侧面方块相对于主方块的本地偏移（未旋转）
     */
    @Nullable
    public ItemProxy getItemProxy(Vec3i relativeOffset) {
        MachineDefinition def = definition();
        return def == null ? null : def.itemProxy(relativeOffset);
    }

    /**
     * 获取指定本地偏移位置的流体代理声明（储罐 + IO 方向 + 有效面）。
     * <p>来自 JSON 定义的 {@code fluid_proxies}；返回 null 表示该位置不代理流体能力（纯占位）。</p>
     *
     * @param relativeOffset 侧面方块相对于主方块的本地偏移（未旋转）
     */
    @Nullable
    public FluidProxy getFluidProxy(Vec3i relativeOffset) {
        MachineDefinition def = definition();
        return def == null ? null : def.fluidProxy(relativeOffset);
    }

    /**
     * 枚举所有侧面方块的本地偏移（填充区域展开 + sideOffsets），
     * 供 BE 遍历代理配置使用。
     */
    public List<Vec3i> enumerateLocalOffsets() {
        return MachineGeometry.enumerateLocalOffsets(getSideOffsets(), getFillRegions());
    }

    /**
     * 判断本地偏移是否属于机器结构（主方块/填充区域/侧面偏移）。
     * 供 BE 主动输出时跳过结构内部相邻方块。
     */
    public boolean isLocalPartOfStructure(Vec3i local) {
        return MachineGeometry.containsLocal(local, getSideOffsets(), getFillRegions());
    }

    /**
     * {@link #rotateVec3i} 的逆运算：将世界坐标偏移转回本地坐标（未旋转）。
     */
    public static Vec3i unrotateVec3i(Vec3i offset, Direction facing) {
        return MachineGeometry.unrotate(offset, facing);
    }

    // ========== MachineRegistry 委托 ==========

    /**
     * 根据机器ID获取对应的主方块实例。
     */
    @Nullable
    public static Block getMachineBlock(String machineId) {
        BaseMachineBlock block = MachineRegistry.getMachineBlock(machineId);
        return block;
    }

    /**
     * 获取所有已注册的大型机器ID。
     */
    public static Collection<String> getMachineIds() {
        return MachineRegistry.getMachineIds();
    }

    // ========== 电网节点 ==========

    /**
     * 机器默认提供一个电网节点（ID=0，位于主方块中心），
     * 支持线轴拉线连接与电力接入。需要多节点的机器子类可覆盖此方法。
     */
    @Override
    public Map<Integer, Vec3> getNodePositions(BlockState state) {
        return Map.of(0, new Vec3(0.5, 0.5, 0.5));
    }

    /**
     * 手持流体容器（桶/通用流体单元）右键方块：
     * 通过方块流体能力直接与机器储罐交互（支持部分转移，不吞流体）。
     * 未发生流体交互时放行，继续走默认交互（打开 GUI）。
     * <p>大型机器和单方块机器共用。</p>
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            if (FluidUtil.interactWithFluidHandler(player, hand, level, pos, hitResult.getDirection())) {
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * 机器被破坏时掉落物品并清理侧面方块（若有）。
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseIOBlockEntity machineBE) {
                Containers.dropContents(level, pos, machineBE.getItems());
            }
            DeferredBlock<?> sideBlock = getSideBlock();
            if (sideBlock != null) {
                BlockPos[] sidePositions = getSidePositions(state, pos);
                for (BlockPos sidePos : sidePositions) {
                    if (level.getBlockState(sidePos).is(sideBlock.get())) {
                        level.destroyBlock(sidePos, false);
                    }
                }
            }
            WorldPowerGrid.get((ServerLevel) level).removeNode(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ========== 通用方块行为 ==========

    protected BaseMachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /**
     * 放置后向客户端同步一次方块实体数据。
     * <p>
     * 单方块机器依赖此同步让刚放置的机器 GUI 立刻拿到数据；
     * 大型机器覆写了 {@code setPlacedBy} 以放置侧面方块，其内部已自带同步。
     * </p>
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()) {
            level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
        }
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rot) {
        return state.setValue(FACING, rot.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * 计算全部侧方块的世界坐标（按朝向旋转、去重、排除主方块自身）。
     */
    public BlockPos[] getSidePositions(BlockState state, BlockPos pos) {
        return MachineGeometry.sidePositions(pos, state.getValue(FACING), getSideOffsets(), getFillRegions());
    }

    /**
     * 打开机器 UI，供侧面方块右键时委托给主方块。
     * 默认使用原版 MenuProvider；使用 LDLib2 的机器子类应重写此方法。
     */
    public void openMachineUI(ServerPlayer player, BlockPos pos) {
        if (player.level().getBlockEntity(pos) instanceof MenuProvider menuProvider) {
            player.openMenu(menuProvider, pos);
        }
    }

    /**
     * Shift+右键打开面配置 UI（独立屏幕），普通右键打开机器 UI。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof BaseIOBlockEntity machine) {
                    com.mss.polymech.ClientHooks.sideConfigOpener.accept(pos.immutable(), machine);
                }
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide()) {
            openMachineUI((ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 把本地偏移（facing=NORTH 声明）旋转到实际朝向。
     * <p>实现集中在 {@link MachineGeometry}，此处保留静态入口供既有调用点使用。</p>
     */
    public static Vec3i rotateVec3i(Vec3i offset, Direction facing) {
        return MachineGeometry.rotate(offset, facing);
    }
}
