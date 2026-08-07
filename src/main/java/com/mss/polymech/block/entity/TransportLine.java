package com.mss.polymech.block.entity;

import com.mss.polymech.api.material.ConveyorMaterial;
import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 传送带线路 —— 同材质、同朝向、首尾直连（含坡道）的连续传送带组成的运行时统一驱动组织。
 * <p>
 * 设计要点（对应高性能目标）：
 * </p>
 * <ul>
 *   <li><b>纯运行时对象，不持久化</b>：物品仍按格存储在各自 BE（NBT 格式零变化）；
 *       线路只统一驱动、统一拾取、统一同步调度</li>
 *   <li><b>头部驱动</b>：仅线首 BE 的 tick 执行整线驱动，其余成员 tick 空转；
 *       线首卸载后由剩余成员中的第一个自动接任</li>
 *   <li><b>线内零检查搬家</b>：包跨格时直接换列表（线内直连已由组网保证），
 *       无入口检查、无交接延迟、无 acceptIncoming 开销</li>
 *   <li><b>合并拾取</b>：整条线一个大 AABB 扫描一次，按沿线投影定位到具体格</li>
 *   <li><b>双端确定性（Create 同款）</b>：客户端组建相同线路镜像，与服务端逐 tick
 *       执行同一套驱动代码，移动零网络包、无周期校准快照；仅结构变化（增/删/合并）
 *       发一次快照，双端位置自然一致</li>
 *   <li><b>拆线安全</b>：区块卸载仅除名、绝不访问邻居区块（防卡死）；
 *       断链后半段成员惰性重建（下个 tick 自动刷新）</li>
 * </ul>
 */
class TransportLine {

    /** 成员列表（线首 → 线尾），全部已加载 */
    private final ArrayList<ConveyorBlockEntity> members = new ArrayList<>();

    /** 线路材质（全部成员同材质） */
    private final ConveyorMaterial material;

    /** 拾取扫描冷却 */
    private int pickupCooldown;

    /** 低频脏标记计时 */
    private int dirtyTimer = ConveyorBlockEntity.DIRTY_INTERVAL;

    // 驱动用复用缓冲区（避免每 tick 分配）
    private BeltItem[] flatBuf = new BeltItem[16];
    private int[] ownerBuf = new int[16];
    private int[] orderBuf = new int[16];
    private int[] tmpBuf = new int[16];

    TransportLine(ConveyorMaterial material) {
        this.material = material;
    }

    /** 线路材质 */
    ConveyorMaterial getMaterial() {
        return material;
    }

    // ========== 成员管理 ==========

    boolean isEmpty() {
        return members.isEmpty();
    }

    @Nullable
    ConveyorBlockEntity head() {
        return members.isEmpty() ? null : members.get(0);
    }

    boolean contains(ConveyorBlockEntity be) {
        return members.contains(be);
    }

    void addMember(ConveyorBlockEntity be) {
        members.add(be);
        be.lineRef = this;
    }

    /**
     * 除名一个成员（区块卸载/拆除/重建时）。
     * <p>
     * 断链规则：被移除成员之后的所有成员一并脱离本线（lineRef 置空），
     * 由它们下个 tick 惰性重建；前半段继续由本线驱动。
     * 该方法<b>绝不访问邻居区块</b>，可安全用于卸载路径。
     * </p>
     */
    void removeMember(ConveyorBlockEntity be) {
        int idx = members.indexOf(be);
        if (idx < 0) return;
        members.remove(idx);
        for (int i = idx; i < members.size(); i++) {
            ConveyorBlockEntity m = members.get(i);
            m.lineRef = null;
        }
        members.subList(idx, members.size()).clear();
        be.lineRef = null;
    }

    // ========== Tick（仅线首调用） ==========

    void tick(Level level) {
        // 防御：剔除已移除成员（区块卸载兜底）
        for (int i = members.size() - 1; i >= 0; i--) {
            if (members.get(i).isRemoved()) {
                removeMember(members.get(i));
            }
        }
        if (members.isEmpty()) return;
        if (level.isClientSide()) {
            tickClient(level);
        } else {
            tickServer(level);
        }
    }

    private void tickServer(Level level) {
        List<ConveyorBlockEntity> changed = new ArrayList<>();

        // 1. 拾取掉落物（整线一次扫描，降频；拾取成功后额外延迟一轮）
        if (++pickupCooldown >= ConveyorBlockEntity.PICKUP_INTERVAL) {
            pickupCooldown = 0;
            List<ConveyorBlockEntity> picked = pickup(level);
            if (!picked.isEmpty()) {
                changed.addAll(picked);
                pickupCooldown = -ConveyorBlockEntity.PICKUP_INTERVAL;
            }
        }

        // 2. 统一驱动
        drive(level, changed, true);

        // 3. 同步：仅结构变化（增/删/合并）时发快照（Create 同款）。
        // 双端逐 tick 执行同一套 drive，纯移动零包且位置自然一致；
        // 周期性校准快照反而会周期性瞬移物品（长带上“一会顿一下”的元凶）
        for (ConveyorBlockEntity be : changed) {
            be.needsSync = false;
            be.setChanged();
            be.syncToClient();
        }

        // 4. 低频脏标记（纯移动不值得每 tick 标脏，进度回滚不会刷物）
        if (hasItems() && --dirtyTimer <= 0) {
            dirtyTimer = ConveyorBlockEntity.DIRTY_INTERVAL;
            for (ConveyorBlockEntity be : members) {
                if (!be.items.isEmpty()) be.setChanged();
            }
        }
    }

    private void tickClient(Level level) {
        // 客户端镜像拾取：与服务端同一套 tryPickupItems 规则（同降频、同入口门、
        // 同整堆吞入），双端规则一致 → 确定性不受影响。
        // 必须本地拾取：服务端拾取快照只发给 BE 跟踪范围内的客户端，SP 内置服务器
        // 下可能收不到，客户端若不自己拾取，屏幕上掉落物会永远躺在带子上不被吸
        // 走（短带/孤立带尤其明显——shift 右键单放的带子）。
        if (++pickupCooldown >= ConveyorBlockEntity.PICKUP_INTERVAL) {
            pickupCooldown = 0;
            // 拾取成功额外延迟一轮（与服务端 tickServer 完全一致的节奏，
            // 避免快照到达时双端包数分歧）；客户端 pickup 返回的
            // changed 列表仅用于服务端同步，这里只借用其扫描逻辑
            if (!pickup(level).isEmpty()) {
                pickupCooldown = -ConveyorBlockEntity.PICKUP_INTERVAL;
            }
        }
        drive(level, null, false);
    }

    private boolean hasItems() {
        for (ConveyorBlockEntity be : members) {
            if (!be.items.isEmpty()) return true;
        }
        return false;
    }

    // ========== 统一驱动（双端共用，确定性规则） ==========

    /**
     * 一次驱动整条线的所有物品包。
     * <p>
     * 把所有包按线内坐标（成员序号 + 格内进度）全局排序后从尾到头推进：
     * 每包最多前进一个步长，且不得超过前方包减 {@link ConveyorBlockEntity#PACKAGE_PITCH}；
     * 跨格直接换列表（线内零检查）；线尾队首包到达终点时执行终点动作
     * （容器注入 / 移交下一格 / 弹出）。
     * </p>
     *
     * @param changed 服务端收集构成变化的成员（客户端传 null）
     * @param server  true=服务端（执行容器注入/弹出），false=客户端（仅移交）
     */
    private void drive(Level level, List<ConveyorBlockEntity> changed, boolean server) {
        int n = members.size();
        if (n == 0) return;

        int total = 0;
        for (int i = 0; i < n; i++) {
            total += members.get(i).items.size();
        }
        if (total == 0) return;

        ensureCapacity(total);
        int k = 0;
        for (int i = 0; i < n; i++) {
            for (BeltItem it : members.get(i).items) {
                flatBuf[k] = it;
                ownerBuf[k] = i;
                k++;
            }
        }
        for (int i = 0; i < total; i++) {
            orderBuf[i] = i;
        }
        mergeSort(orderBuf, tmpBuf, 0, total);

        double speed = material.getBeltSpeed();
        double lineLength = n;
        long now = level.getGameTime();

        for (int pos = total - 1; pos >= 0; pos--) {
            int o = orderBuf[pos];
            BeltItem item = flatBuf[o];
            int own = ownerBuf[o];

            // 印记：本 tick 刚创建（跨线交接）的包下一 tick 再起步，
            // 双端节奏确定、与 BE tick 顺序无关（绝不被双驱动/提前起步）
            if (item.getLastDrivenTick() == now) {
                continue;
            }
            item.setPrevProgress(item.getProgress());
            item.setPrevSideOffset(item.getSideOffset());

            double global = own + item.getProgress();
            double aheadLimit = (pos == total - 1)
                    ? lineLength - ConveyorBlockEntity.EPSILON
                    : (ownerBuf[orderBuf[pos + 1]] + flatBuf[orderBuf[pos + 1]].getProgress())
                            - ConveyorBlockEntity.PACKAGE_PITCH;
            double newGlobal = Math.min(global + speed, aheadLimit);
            // 间距已被破坏（入场/快照对齐等残留）时只等待、绝不倒退，
            // 避免物品被往回抽扯产生拉扯抖动；前车前进后间距自然恢复
            if (newGlobal < global) {
                newGlobal = global;
            }

            boolean isTail = pos == total - 1;
            if (isTail && newGlobal >= lineLength - ConveyorBlockEntity.EPSILON) {
                // 线尾队首包完整走到格尾边界才尝试交接（下一格起点与格尾同一位置，渲染零跳变）
                ConveyorBlockEntity tail = members.get(n - 1);
                double newProgress = newGlobal - (n - 1);
                if (tryTailHandoff(level, tail, item, newProgress, server)) {
                    tail.items.remove(item);
                    markChanged(changed, tail);
                }
                // 目标不可达（转弯入口被占用 / 前方方块阻挡 / 容器已满）：
                // 停在当前位置下 tick 重试（终点等待，绝不回退拉扯）
                continue;
            }

            if (newGlobal > global) {
                double moved = newGlobal - global;
                // sideOffset 线性快速收敛（替代 Create 的 moved×6 指数式）：
                // 指数式在慢速带上每 tick 收敛量 = |side|×moved×6 极小，
                // 轨迹呈圆弧形、迟迟不进中线；改为每 tick 固定收敛
                // SIDE_OFFSET_CONVERGE_STEP（与带速无关），轨迹近直线，
                // 0.5 起点 2 tick 即到中心。被阻塞（moved=0）时不收敛。
                double side = item.getSideOffset();
                if (side != 0.0D) {
                    double step = Math.copySign(
                            Math.min(Math.abs(side), ConveyorBlockEntity.SIDE_OFFSET_CONVERGE_STEP), -side);
                    item.setSideOffset(side + step);
                }

                int newOwner = (int) Math.floor(newGlobal);
                if (newOwner != own) {
                    // 线内跨格：直接换列表，无入口检查（前方包已让位）。
                    // 注意：跨格是<b>纯移动</b>，绝不 markChanged/发同步包（Create 同款：
                    // 移动零网络包）。流动中的带子上跨格几乎每 tick 都在发生，
                    // 若每次跨格都发快照，网络延迟会把客户端周期性回拉 = 持续顿挫
                    double newProgress = newGlobal - newOwner;
                    ConveyorBlockEntity from = members.get(own);
                    ConveyorBlockEntity to = members.get(newOwner);
                    from.items.remove(item);
                    // 渲染插值连续性：prev 换算到新格坐标 = newProgress - 本 tick 实际位移
                    // （被前车部分阻塞时实际位移 < 理论步长；可为小负值，几何上就是
                    // 来源格出口边 = 新格入口边同一点）；渲染器不钳制，跨边界帧间零跳变
                    item.setPrevProgress(newProgress - moved);
                    item.setProgress(newProgress);
                    // 跨格后横向偏移清零：侧入滑行早已结束（连续多 tick 收敛），
                    // 清零防止快照残留的微小偏移被带入后续格（双端同规则，确定）
                    item.setSideOffset(0.0D);
                    to.insertSorted(item);
                    ownerBuf[o] = newOwner;
                } else {
                    item.setProgress(newGlobal - own);
                }
            }
        }
    }

    /** 线尾终点动作：复用线尾成员 BE 的既有交接逻辑（含容器注入/跨带移交/弹出） */
    private boolean tryTailHandoff(Level level, ConveyorBlockEntity tail, BeltItem item,
                                   double newProgress, boolean server) {
        BlockState state = tail.getBlockState();
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);
        return tail.tryHandoff(level, tail.getBlockPos(), state, facing, type, item, newProgress, server);
    }

    /** 无装箱归并排序（orderBuf 按线内坐标升序） */
    private void mergeSort(int[] arr, int[] tmp, int l, int r) {
        if (r - l <= 1) return;
        int m = (l + r) >>> 1;
        mergeSort(arr, tmp, l, m);
        mergeSort(arr, tmp, m, r);
        int i = l;
        int j = m;
        int k = l;
        while (i < m && j < r) {
            double ki = ownerBuf[arr[i]] + flatBuf[arr[i]].getProgress();
            double kj = ownerBuf[arr[j]] + flatBuf[arr[j]].getProgress();
            if (ki <= kj) {
                tmp[k++] = arr[i++];
            } else {
                tmp[k++] = arr[j++];
            }
        }
        while (i < m) {
            tmp[k++] = arr[i++];
        }
        while (j < r) {
            tmp[k++] = arr[j++];
        }
        System.arraycopy(tmp, l, arr, l, r - l);
    }

    private void ensureCapacity(int total) {
        if (flatBuf.length < total) {
            flatBuf = new BeltItem[total];
            ownerBuf = new int[total];
            orderBuf = new int[total];
            tmpBuf = new int[total];
        }
    }

    private static void markChanged(List<ConveyorBlockEntity> changed, ConveyorBlockEntity be) {
        if (changed != null && !changed.contains(be)) {
            changed.add(be);
        }
    }

    // ========== 拾取掉落物（整线一次扫描） ==========

    /**
     * 整条线一个大 AABB 扫描掉落物，按沿线投影定位到具体格，
     * 逐格执行该格原有的入口拾取逻辑（放入该格起点）。
     */
    private List<ConveyorBlockEntity> pickup(Level level) {
        List<ConveyorBlockEntity> changed = new ArrayList<>();
        int n = members.size();
        if (n == 0) return changed;

        ConveyorBlockEntity head = members.get(0);
        ConveyorBlockEntity tail = members.get(n - 1);
        double minX = Math.min(head.getBlockPos().getX(), tail.getBlockPos().getX())
                - 0.5 - ConveyorBlockEntity.PICKUP_RADIUS;
        double maxX = Math.max(head.getBlockPos().getX(), tail.getBlockPos().getX())
                + 0.5 + ConveyorBlockEntity.PICKUP_RADIUS;
        double minZ = Math.min(head.getBlockPos().getZ(), tail.getBlockPos().getZ())
                - 0.5 - ConveyorBlockEntity.PICKUP_RADIUS;
        double maxZ = Math.max(head.getBlockPos().getZ(), tail.getBlockPos().getZ())
                + 0.5 + ConveyorBlockEntity.PICKUP_RADIUS;
        double minY = Math.min(head.getBlockPos().getY(), tail.getBlockPos().getY()) + 0.02;
        double maxY = Math.max(head.getBlockPos().getY(), tail.getBlockPos().getY()) + 1.3;

        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class,
                new AABB(minX, minY, minZ, maxX, maxY, maxZ),
                item -> item.isAlive() && !item.getItem().isEmpty());
        if (drops.isEmpty()) return changed;

        double[] prog = new double[1];
        for (ItemEntity drop : drops) {
            int idx = projectToMember(drop, prog);
            if (idx < 0) continue;
            ConveyorBlockEntity be = members.get(idx);
            if (be.tryPickupItems(level, be.getBlockPos())) {
                markChanged(changed, be);
            }
        }
        return changed;
    }

    /**
     * 把掉落物投影到线内坐标。
     *
     * @param outProgress [0] = 格内进度
     * @return 成员序号；不在线内拾取范围时返回 -1
     */
    private int projectToMember(ItemEntity drop, double[] outProgress) {
        ConveyorBlockEntity head = members.get(0);
        Direction facing = head.getBlockState().getValue(ConveyorBlock.FACING);
        double stepX = facing.getStepX();
        double stepZ = facing.getStepZ();
        double along = drop.getX() * stepX + drop.getZ() * stepZ;
        // 线首中心的沿线坐标：必须先加 0.5 取块中心再乘方向步长。
        // （旧式 pos*step + 0.5 在负向朝 NORTH/WEST 时符号错误，
        // 投影整体偏移一个成员，掉落物永远定不到本格 → 拾取失效）
        double headCenter = (head.getBlockPos().getX() + 0.5) * stepX
                + (head.getBlockPos().getZ() + 0.5) * stepZ;
        double offset = along - headCenter;
        int idx = (int) Math.floor(offset + 0.5);
        if (idx < 0 || idx >= members.size()) return -1;
        double progress = offset - idx + 0.5;
        if (progress < 0.0D || progress > 1.0D) return -1;

        ConveyorBlockEntity be = members.get(idx);
        double y = drop.getY();
        if (y < be.getBlockPos().getY() + 0.02 || y > be.getBlockPos().getY() + 1.3) {
            return -1;
        }

        outProgress[0] = progress;
        return idx;
    }
}
