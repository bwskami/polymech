package com.mss.polymech.powergrid;

import com.mss.polymech.Polymech;
import com.mss.polymech.network.WireSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * 世界级电网数据。
 * <p>
 * 以SavedData持久化存储每个维度中由真实电线（{@link GridConnection}）连接的节点网络，
 * 并通过连通性分析驱动功率分配：
 * <ul>
 *   <li><b>连接管理</b>：connect/disconnect/removeNode，结构变化时自动向客户端同步电线渲染数据</li>
 *   <li><b>功率分配</b>：按连通组件（BFS）把发电机功率（FE/t）分配给同组件内的消费者；
 *       未连入电网的发电机功率被丢弃，未被电网覆盖的消费者得不到供电</li>
 *   <li><b>电压等级（范围制）</b>：每个连通组件的电网电压 = max(发电机输出电压)，
 *       等级由 {@link VoltageTier} 按 (min, max] 区间归类；
 *       电线电压上限不足（任意数值）时过压熔断，总电流超限时过载熔断</li>
 *   <li><b>线损</b>：组件内总电阻 R = Σ(连接电阻×长度)，总电流 I = 功率/电压，
 *       线损 P_loss = I²R，有效电压 V_eff = V - I×R（电压降），
 *       线损消耗后的功率才分配给消费者</li>
 *   <li><b>全局电池</b>：全维度统一的储电缓冲（FE），发电富余时充电、不足时放电</li>
 * </ul>
 * 每个维度独立实例，由 {@link #get(ServerLevel)} 获取。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID)
public class WorldPowerGrid extends SavedData {

    private static final Map<ServerLevel, WorldPowerGrid> INSTANCE = new WeakHashMap<>();

    // ========== 连接数据 ==========

    /** 全部电线连接（线性存储，连接量级小，直接扫描足够） */
    private final List<GridConnection> connections = new ArrayList<>();

    /** 邻接表：节点 → 与其相连的连接（供连通性BFS） */
    private final Map<GridNode, List<GridConnection>> adjacency = new HashMap<>();

    // ========== 电气接入 ==========

    /** 发电机：节点 → 发电量供给器 + 实际输出反馈 + 输出电压 */
    private final Map<GridNode, GeneratorEntry> generators = new HashMap<>();

    /** 消费者：节点 → 需求供给器 + 接收回调 + 额定电压 */
    private final Map<GridNode, ConsumerEntry> consumers = new HashMap<>();

    // ========== 电池（全局缓冲，FE） ==========

    private final int maxBatteryCapacity = 100000;
    private int storedEnergy;

    // ========== 分配缓存 ==========

    /** 每5 tick刷新一次组件供电比例 */
    private static final int ALLOCATION_INTERVAL = 5;
    private int tickCounter = 0;
    private final Map<GridNode, Double> nodeSupplyRatio = new HashMap<>();
    /** 每个节点所在连通组件的电网有效电压（FE/t，扣除线损电压降后） */
    private final Map<GridNode, Integer> nodeVoltage = new HashMap<>();
    private int lastTotalGenerated = 0;
    private int lastTotalDemand = 0;

    private final ServerLevel level;

    private WorldPowerGrid(ServerLevel level) {
        this.level = level;
    }

    // ==================== 实例获取 ====================

    /**
     * 获取指定维度的电网（懒加载，维度数据在首次访问时创建）。
     */
    public static WorldPowerGrid get(ServerLevel level) {
        return INSTANCE.computeIfAbsent(level, l -> {
            String dimKey = l.dimension().location().toString().replace(':', '_').replace('/', '_');
            return l.getDataStorage().computeIfAbsent(
                    new SavedData.Factory<WorldPowerGrid>(
                            () -> new WorldPowerGrid(l),
                            (tag, registries) -> load(l, tag, registries)),
                    "polymech_powergrid_" + dimKey);
        });
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                get(level).tick();
            }
        }
    }

    /** 玩家进入服务器时全量同步电网连接（供客户端渲染电线） */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            WorldPowerGrid grid = get(level);
            PacketDistributor.sendToPlayer(player, new WireSyncPacket(new ArrayList<>(grid.connections), false, true));
        }
    }

    /** 玩家切换维度时全量同步新维度的电网连接（客户端维度切换会清空渲染缓存） */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = (ServerLevel) player.level();
            WorldPowerGrid grid = get(level);
            PacketDistributor.sendToPlayer(player, new WireSyncPacket(new ArrayList<>(grid.connections), false, true));
        }
    }

    // ==================== 连接管理 ====================

    /**
     * 在两个节点之间建立电线连接。
     * <p>
     * 校验项：节点存在（方块为电网方块）、非自身连接、未重复连接、不超过最大拉线长度。
     * 成功后向维度内所有玩家广播同步包。
     * </p>
     *
     * @return 是否连接成功
     */
    public boolean connect(GridNode node1, GridNode node2, GridWireType wireType) {
        if (node1.equals(node2))
            return false;
        if (isConnected(node1, node2))
            return false;

        BlockPos pos1 = node1.sourcePos();
        BlockPos pos2 = node2.sourcePos();
        double distance = Math.sqrt(pos1.distSqr(pos2));
        if (distance > wireType.getMaxLength())
            return false;
        if (distance < 0.5)
            return false;

        GridConnection connection = new GridConnection(node1, node2, wireType, distance);
        connections.add(connection);
        adjacency.computeIfAbsent(node1, n -> new ArrayList<>()).add(connection);
        adjacency.computeIfAbsent(node2, n -> new ArrayList<>()).add(connection);
        setDirty();

        PacketDistributor.sendToPlayersInDimension(level, new WireSyncPacket(List.of(connection), false, false));
        return true;
    }

    /** 断开两节点之间的连接 */
    public void disconnect(GridNode node1, GridNode node2) {
        List<GridConnection> toRemove = new ArrayList<>();
        for (GridConnection c : connections) {
            if ((c.node1().equals(node1) && c.node2().equals(node2))
                    || (c.node1().equals(node2) && c.node2().equals(node1))) {
                toRemove.add(c);
            }
        }
        if (toRemove.isEmpty())
            return;

        connections.removeAll(toRemove);
        List<GridConnection> l1 = adjacency.get(node1);
        if (l1 != null) l1.removeAll(toRemove);
        List<GridConnection> l2 = adjacency.get(node2);
        if (l2 != null) l2.removeAll(toRemove);
        setDirty();

        PacketDistributor.sendToPlayersInDimension(level, new WireSyncPacket(toRemove, true, false));
    }

    /**
     * 移除方块位置上的全部节点及其连接（方块被破坏时调用）。
     */
    public void removeNode(BlockPos pos) {
        if (connections.isEmpty())
            return;
        List<GridConnection> toRemove = new ArrayList<>();
        for (GridConnection c : connections) {
            if (c.touches(pos))
                toRemove.add(c);
        }
        removeConnections(toRemove);
    }

    /**
     * 移除方块位置上的单个节点及其连接（同格多体方块敲掉其中一个个体时调用）。
     */
    public void removeNode(GridNode node) {
        if (connections.isEmpty())
            return;
        List<GridConnection> toRemove = new ArrayList<>();
        for (GridConnection c : connections) {
            if (c.touches(node))
                toRemove.add(c);
        }
        removeConnections(toRemove);
    }

    /** 从连接表/邻接表中移除指定连接并同步客户端 */
    private void removeConnections(List<GridConnection> toRemove) {
        if (toRemove.isEmpty())
            return;

        connections.removeAll(toRemove);
        adjacency.entrySet().removeIf(e -> {
            e.getValue().removeAll(toRemove);
            return e.getValue().isEmpty();
        });
        setDirty();

        PacketDistributor.sendToPlayersInDimension(level, new WireSyncPacket(toRemove, true, false));
    }

    /** 统计给定方块位置上的连接数量（EmptySpoolItem拆线提示用） */
    public int countConnectionsAt(BlockPos pos) {
        int count = 0;
        for (GridConnection c : connections) {
            if (c.touches(pos))
                count++;
        }
        return count;
    }

    /** 是否已存在连接 */
    public boolean isConnected(GridNode node1, GridNode node2) {
        List<GridConnection> l = adjacency.get(node1);
        if (l == null)
            return false;
        for (GridConnection c : l) {
            if (c.node1().equals(node2) || c.node2().equals(node2))
                return true;
        }
        return false;
    }

    /** 获取节点关联的全部连接 */
    public List<GridConnection> getConnections(GridNode node) {
        return adjacency.getOrDefault(node, List.of());
    }

    /** 全部连接（只读视图） */
    public List<GridConnection> getAllConnections() {
        return Collections.unmodifiableList(connections);
    }

    /** 全部节点 */
    public Set<GridNode> getNodes() {
        return Collections.unmodifiableSet(adjacency.keySet());
    }

    // ==================== 电气接入 ====================

    /**
     * 在指定节点注册发电机。
     *
     * @param node                 接入电网的节点（该方块必须是电网方块）
     * @param generatedSupplier    每tick最大发电量（FE）
     * @param actualOutputCallback 实际输出反馈（每tick被电网消费的功率，含线损承担；孤立/空载时回调0，供发电机按真实传输量扣储能）
     * @param voltageSupplier      输出电压（FE/t，即电压数值）
     * @param adaptiveOutput       自适应输出：电压与功率自动匹配直接相连线缆的承受能力（创造电池用）
     */
    public void registerGenerator(GridNode node, Supplier<Integer> generatedSupplier,
                                  IntConsumer actualOutputCallback, Supplier<Integer> voltageSupplier,
                                  boolean adaptiveOutput) {
        generators.put(node, new GeneratorEntry(generatedSupplier, actualOutputCallback, voltageSupplier, adaptiveOutput));
    }

    /**
     * 在指定节点注册发电机（无实际输出反馈，兼容外部FE适配器等）。
     */
    public void registerGenerator(GridNode node, Supplier<Integer> generatedSupplier, Supplier<Integer> voltageSupplier) {
        registerGenerator(node, generatedSupplier, null, voltageSupplier, false);
    }

    /**
     * 在指定节点注册发电机（默认电压取等级上限）。
     */
    public void registerGenerator(GridNode node, Supplier<Integer> generatedSupplier, int defaultVoltage) {
        registerGenerator(node, generatedSupplier, null, () -> defaultVoltage, false);
    }

    public void unregisterGenerator(GridNode node) {
        generators.remove(node);
    }

    /**
     * 在指定节点注册消费者。
     *
     * @param node             接入电网的节点
     * @param demandSupplier   每tick需求（FE）
     * @param receiveCallback  收到电力时的回调（传入实际分配量）
     * @param requiredVoltage  额定电压（FE/t），电网电压低于此值时降效运行
     */
    public void registerConsumer(GridNode node, Supplier<Integer> demandSupplier, Consumer<Integer> receiveCallback, int requiredVoltage) {
        consumers.put(node, new ConsumerEntry(node, demandSupplier, receiveCallback, requiredVoltage));
    }

    /**
     * 在指定节点注册消费者（默认额定电压 = LV 128 FE/t）。
     */
    public void registerConsumer(GridNode node, Supplier<Integer> demandSupplier, Consumer<Integer> receiveCallback) {
        registerConsumer(node, demandSupplier, receiveCallback, VoltageTier.LV.getMaxVoltage());
    }

    public void unregisterConsumer(GridNode node) {
        consumers.remove(node);
    }

    // ==================== 功率分配 ====================

    /**
     * 每tick驱动：定期（每 {@link #ALLOCATION_INTERVAL} tick）刷新组件供电比例与电压，
     * 每tick按比例向消费者供电（含电压降效修正）。
     */
    public void tick() {
        if (++tickCounter >= ALLOCATION_INTERVAL) {
            tickCounter = 0;
            allocate();
        }

        for (ConsumerEntry c : consumers.values()) {
            try {
                int demand = Math.max(0, c.demandSupplier.get());
                double ratio = nodeSupplyRatio.getOrDefault(c.node, 0.0);

                // 电压降效：电网电压 < 额定电压时，实际供电比例打折
                int gridV = nodeVoltage.getOrDefault(c.node, 0);
                if (gridV > 0 && c.requiredVoltage > gridV) {
                    ratio *= (double) gridV / c.requiredVoltage;
                }

                c.receiveCallback.accept((int) Math.floor(demand * ratio));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 构建连接器虚拟桥接表：从已有电线的端点中发现连接器，与其贴附面方块的电网节点相连。
     * <p>
     * 桥接为虚拟零电阻连接（不渲染、不持久化、不参与熔断）：
     * 连接器贴在蓄电池等电网方块上时，等价于把该方块的接线点引出到连接器。
     * 从 adjacency（已接线的节点）出发逐格发现，旧存档/新放置均立即生效；
     * 连接器贴在连接器上时继续向背后迭代桥接（链式）。
     * </p>
     */
    private Map<GridNode, List<GridConnection>> buildVirtualBridges() {
        Map<GridNode, List<GridConnection>> virtualAdjacency = new HashMap<>();
        Set<BlockPos> processed = new HashSet<>();
        Deque<GridNode> queue = new ArrayDeque<>(adjacency.keySet());
        while (!queue.isEmpty()) {
            GridNode node = queue.poll();
            BlockPos pos = node.sourcePos();
            if (!processed.add(pos))
                continue; // 同一格只处理一次（一次桥接全部个体）

            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof ConnectorBlock))
                continue;
            // 连接器贴附面背后的方块
            Direction facing = state.getValue(ConnectorBlock.FACING);
            BlockPos behind = pos.relative(facing.getOpposite());
            BlockState behindState = level.getBlockState(behind);
            if (!(behindState.getBlock() instanceof GridNodeBlock gridBlock))
                continue;
            Map<Integer, Vec3> behindNodes = gridBlock.getNodePositions(behindState);
            if (behindNodes.isEmpty())
                continue;

            int count = state.getValue(ConnectorBlock.COUNT);
            for (int i = 0; i < count; i++) {
                GridNode connNode = new GridNode(i, pos);
                for (int id : behindNodes.keySet()) {
                    GridNode behindNode = new GridNode(id, behind);
                    GridConnection virtual = new GridConnection(connNode, behindNode, null, 0);
                    virtualAdjacency.computeIfAbsent(connNode, n -> new ArrayList<>()).add(virtual);
                    virtualAdjacency.computeIfAbsent(behindNode, n -> new ArrayList<>()).add(virtual);
                }
            }
            // 背后方块若是连接器，其节点继续向背后桥接（链式贴附）
            if (behindState.getBlock() instanceof ConnectorBlock) {
                for (int id : behindNodes.keySet())
                    queue.add(new GridNode(id, behind));
            }
        }
        return virtualAdjacency;
    }

    /**
     * 连通性功率分配：
     * <ol>
     *   <li>BFS划分连通组件，计算组件电压 = max(发电机输出电压)</li>
     *   <li>自适应发电机按直接相连线缆逐线通道供电（每线独立电压/容量），非自适应电源按组件电压</li>
     *   <li>线损计算：总电流 = Σ(通道电流) + 非自适应电流，线损 = I²×总电阻，有效电压 = V - I×总电阻</li>
     *   <li>熔断检查（逐线独立）：每根线按自身承受电压/电流判断过压/过载 → 熔断应力最大的1条连接</li>
     *   <li>能量守恒：供给端实际提供 = 实际消费 + 线损；发电机按真实传输量反馈扣能，
     *       未被消费的发电富余充入全局电池，全局电池按缺口放电</li>
     *   <li>按组件供电能力（扣除线损）给组件内消费者设定比例</li>
     * </ol>
     */
    private void allocate() {
        nodeSupplyRatio.clear();
        nodeVoltage.clear();
        lastTotalGenerated = 0;
        lastTotalDemand = 0;

        // 需求与发电快照（每tick实时值）
        Map<GridNode, Integer> demandByNode = new HashMap<>();
        int totalDemand = 0;
        for (ConsumerEntry c : consumers.values()) {
            try {
                int d = Math.max(0, c.demandSupplier.get());
                demandByNode.put(c.node, d);
                totalDemand += d;
            } catch (Throwable ignored) {}
        }
        Map<GridNode, Integer> genByNode = new HashMap<>();
        int totalGenerated = 0;
        for (Map.Entry<GridNode, GeneratorEntry> e : generators.entrySet()) {
            try {
                int g = Math.max(0, e.getValue().outputSupplier().get());
                if (g > 0) {
                    genByNode.put(e.getKey(), g);
                    totalGenerated += g;
                }
            } catch (Throwable ignored) {}
        }
        lastTotalGenerated = totalGenerated;
        lastTotalDemand = totalDemand;
        Polymech.LOGGER.debug("[Grid] allocate: generators={} consumers={} connections={} gen={} demand={}",
                generators.size(), consumers.size(), connections.size(), totalGenerated, totalDemand);

        // ===== 连接器虚拟桥接：贴附面电网方块节点 ↔ 连接器个体节点（虚拟零电阻连接，不持久化） =====
        Map<GridNode, List<GridConnection>> virtualAdjacency = buildVirtualBridges();

        // 全局电池预调度：缺电时先预扣放电额度（按组件需求占比分配，未消费部分结算时退回）
        int batteryDischarge = 0;
        if (totalGenerated < totalDemand) {
            int deficit = totalDemand - totalGenerated;
            batteryDischarge = Math.min(deficit, storedEnergy);
            storedEnergy -= batteryDischarge;
        }

        // 组件级守恒结算累计量
        double surplusAccum = 0;        // 发电机实际输出未被消费的富余（充入全局电池）
        double batteryConsumedAccum = 0; // 全局电池实际放电量

        // 组件划分（BFS）
        Set<GridNode> visited = new HashSet<>();
        GridConnection fuseTarget = null;
        double fuseStress = 0;

        for (GridNode start : adjacency.keySet()) {
            if (visited.contains(start))
                continue;

            List<GridNode> component = new ArrayList<>();
            List<GridConnection> compConnections = new ArrayList<>();
            Deque<GridNode> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                GridNode n = queue.poll();
                component.add(n);
                // 真实电线：参与线损/熔断，计入 compConnections
                for (GridConnection c : adjacency.getOrDefault(n, List.of())) {
                    if (!compConnections.contains(c)) compConnections.add(c);
                    GridNode other = c.node1().equals(n) ? c.node2() : c.node1();
                    if (visited.add(other))
                        queue.add(other);
                }
                // 虚拟桥接：只用于连通性扩展，不参与线损/熔断
                for (GridConnection c : virtualAdjacency.getOrDefault(n, List.of())) {
                    GridNode other = c.node1().equals(n) ? c.node2() : c.node1();
                    if (visited.add(other))
                        queue.add(other);
                }
            }

            // 组件电压与发电：自适应发电机按直接相连线缆逐线通道供电（每根线独立匹配）
            int compVoltage = 0;
            int adaptiveGen = 0; // 自适应电源通道功率总和
            int nonAdaptiveP = 0; // 非自适应电源总功率（按组件电压走线）
            List<Channel> channels = new ArrayList<>(); // 自适应通道：线缆+通道电压+通道功率
            Map<GridConnection, Integer> wireVoltage = new HashMap<>(); // 每根线承受的电压（过压判断用）
            for (GridNode n : component) {
                GeneratorEntry entry = generators.get(n);
                if (entry == null) continue;
                int declaredV;
                try { declaredV = Math.max(0, entry.voltageSupplier().get()); } catch (Throwable ignored) { continue; }
                int declaredP = genByNode.getOrDefault(n, 0);

                if (entry.adaptiveOutput()) {
                    // 收集直接相连线缆：经虚拟桥接可达的第一跳真实电线
                    Set<GridNode> bridge = new HashSet<>();
                    Deque<GridNode> bq = new ArrayDeque<>();
                    bridge.add(n);
                    bq.add(n);
                    while (!bq.isEmpty()) {
                        GridNode bn = bq.poll();
                        for (GridConnection v : virtualAdjacency.getOrDefault(bn, List.of())) {
                            GridNode other = v.node1().equals(bn) ? v.node2() : v.node1();
                            if (bridge.add(other))
                                bq.add(other);
                        }
                    }
                    List<GridConnection> wires = new ArrayList<>();
                    Set<GridConnection> seenWires = new HashSet<>();
                    for (GridNode bn : bridge) {
                        for (GridConnection c : adjacency.getOrDefault(bn, List.of())) {
                            if (seenWires.add(c))
                                wires.add(c);
                        }
                    }
                    if (wires.isEmpty()) {
                        // 无直接线缆：不输出（电能无处去）
                        genByNode.put(n, 0);
                        continue;
                    }
                    // 逐线通道：每根线独立通道，电压 = min(声明电压, 线耐压)，容量 = 通道电压×线载流
                    long totalCap = 0;
                    for (GridConnection c : wires) {
                        int cv = Math.min(declaredV, c.wireType().getMaxVoltage());
                        totalCap += (long) cv * c.wireType().getMaxAmperage();
                    }
                    if (totalCap <= 0) {
                        genByNode.put(n, 0);
                        continue;
                    }
                    // 声明功率按各通道容量比例拆分，且每通道不超自身容量（输出给线，不是给连接器）
                    int channelSum = 0;
                    for (GridConnection c : wires) {
                        int cv = Math.min(declaredV, c.wireType().getMaxVoltage());
                        int cap = cv * c.wireType().getMaxAmperage();
                        int p = (int) Math.min(cap, (long) declaredP * cap / totalCap);
                        channels.add(new Channel(n, c, cv, p));
                        channelSum += p;
                        // 该线承受电压：取所有经过它的通道电压最大值
                        wireVoltage.merge(c, cv, Math::max);
                        compVoltage = Math.max(compVoltage, cv);
                    }
                    adaptiveGen += channelSum;
                    genByNode.put(n, channelSum);
                } else {
                    compVoltage = Math.max(compVoltage, declaredV);
                    nonAdaptiveP += declaredP;
                }
            }
            int compGenerated = adaptiveGen + nonAdaptiveP;
            int compDemand = 0;
            for (GridNode n : component) {
                Integer d = demandByNode.get(n);
                if (d != null)
                    compDemand += d;
            }
            if (compGenerated > 0 || compDemand > 0) {
                Polymech.LOGGER.debug("[Grid] 组件@{}: V={} gen={}(adaptive={},nonAdaptive={}) demand={} nodes={} wires={}",
                        start, compVoltage, compGenerated, adaptiveGen, nonAdaptiveP, compDemand,
                        component.size(), compConnections.size());
            }

            // 全局电池配额：只补本组件实际缺口（按需求占比近似，钳制到缺口，避免跨组件污染）
            int compGap = Math.max(0, compDemand - compGenerated);
            int batteryShare = totalDemand > 0 && compGap > 0
                    ? (int) Math.min(compGap, Math.floor((long) batteryDischarge * compDemand / totalDemand))
                    : 0;
            double compSupply = compGenerated + batteryShare;

            // ===== 线损计算（逐线电流口径）：I_线 = 通道功率/通道电压（自适应），I_非 = 非自适应功率/组件电压 =====
            // 组件总电阻 = Σ(每段连接电阻×长度)
            double compResistance = 0;
            for (GridConnection conn : compConnections) {
                compResistance += conn.getResistance();
            }
            double nonAdaptiveCurrent = compVoltage > 0 ? (nonAdaptiveP + batteryShare) / (double) compVoltage : 0;

            // ===== 自适应通道按线剩余容量压缩：线已被非自适应电源/全局电池占用时自动让位降载 =====
            if (!channels.isEmpty()) {
                int compMaxA = 0;
                for (GridConnection conn : compConnections) {
                    compMaxA += conn.wireType().getMaxAmperage();
                }
                double nonAdaptivePerAmp = compMaxA > 0 ? nonAdaptiveCurrent / compMaxA : 0;
                List<Channel> compressed = new ArrayList<>();
                Map<GridNode, Integer> genAfter = new HashMap<>();
                for (GridConnection conn : compConnections) {
                    GridWireType wt = conn.wireType();
                    double lineUsed = nonAdaptivePerAmp * wt.getMaxAmperage();
                    double chCurrent = 0;
                    for (Channel ch : channels) {
                        if (ch.wire().equals(conn) && ch.voltage() > 0)
                            chCurrent += ch.power() / (double) ch.voltage();
                    }
                    if (chCurrent <= 0)
                        continue;
                    // 剩余容量不足时按比例压缩（线已占满则通道功率归零）
                    double factor = lineUsed >= wt.getMaxAmperage()
                            ? 0.0
                            : Math.min(1.0, (wt.getMaxAmperage() - lineUsed) / chCurrent);
                    for (Channel ch : channels) {
                        if (ch.wire().equals(conn)) {
                            int np = (int) Math.floor(ch.power() * factor);
                            compressed.add(new Channel(ch.node(), conn, ch.voltage(), np));
                            genAfter.merge(ch.node(), np, Integer::sum);
                        }
                    }
                }
                channels = compressed;
                adaptiveGen = 0;
                for (Channel ch : channels) adaptiveGen += ch.power();
                compGenerated = adaptiveGen + nonAdaptiveP;
                genAfter.forEach(genByNode::put);
            }

            double adaptiveCurrentSum = 0;
            for (Channel ch : channels) {
                adaptiveCurrentSum += ch.voltage() > 0 ? ch.power() / (double) ch.voltage() : 0;
            }
            double totalCurrent = adaptiveCurrentSum + nonAdaptiveCurrent;
            double lineLoss = totalCurrent * totalCurrent * compResistance;
            double effSupply = Math.max(0, compSupply - lineLoss);
            int effVoltage = compVoltage > 0 && totalCurrent > 0
                    ? Math.max(0, (int) Math.floor(compVoltage - totalCurrent * compResistance))
                    : compVoltage;
            for (GridNode n : component) {
                nodeVoltage.put(n, effVoltage);
            }

            double ratio = compDemand > 0 ? Math.min(1.0, effSupply / compDemand) : 1.0;
            for (GridNode n : component) {
                if (consumers.containsKey(n))
                    nodeSupplyRatio.put(n, ratio);
            }

            // ===== 能量守恒结算：供给端实际提供 = 实际消费 + 线损（不超过供给能力） =====
            double actualConsumed = Math.min(effSupply, compDemand);
            double consumedFromSupply = Math.min(compSupply, actualConsumed + lineLoss);
            double genConsumed = compSupply > 0 ? consumedFromSupply * compGenerated / compSupply : 0;
            double battConsumed = consumedFromSupply - genConsumed;

            // 发电机实际输出反馈（按各发电机功率占比分摊，含线损承担）
            if (genConsumed > 0 && compGenerated > 0) {
                for (GridNode n : component) {
                    Integer g = genByNode.get(n);
                    GeneratorEntry entry = generators.get(n);
                    if (g != null && g > 0 && entry != null && entry.actualOutputCallback() != null) {
                        try {
                            entry.actualOutputCallback().accept((int) Math.floor(genConsumed * g / compGenerated));
                        } catch (Throwable ignored) {}
                    }
                }
            }
            // 富余：发电机有能力输出但未被消费的部分 → 充入全局电池
            surplusAccum += Math.max(0, compGenerated - genConsumed);
            batteryConsumedAccum += battConsumed;

            // ===== 熔断检查（逐线独立）：每根线按自身承受电压/电流判断 =====
            if (compVoltage > 0 && !compConnections.isEmpty()) {
                int compMaxAmperage = 0;
                for (GridConnection conn : compConnections) {
                    compMaxAmperage += conn.wireType().getMaxAmperage();
                }
                double nonAdaptivePerAmp = compMaxAmperage > 0 ? nonAdaptiveCurrent / compMaxAmperage : 0;
                for (GridConnection conn : compConnections) {
                    GridWireType wt = conn.wireType();
                    // 过压（每根线独立）：该线承受电压 = 自适应通道电压（非通道线则组件电压）
                    int lineV = wireVoltage.getOrDefault(conn, compVoltage);
                    if (lineV > wt.getMaxVoltage()) {
                        double stress = (double) lineV / wt.getMaxVoltage();
                        if (stress > fuseStress) {
                            fuseStress = stress;
                            fuseTarget = conn;
                        }
                    }
                    // 过载（每根线独立）：线电流 = 自适应通道电流 + 非自适应电流按载流比例分摊
                    double lineCurrent = nonAdaptivePerAmp * wt.getMaxAmperage();
                    for (Channel ch : channels) {
                        if (ch.wire().equals(conn) && ch.voltage() > 0) {
                            lineCurrent += ch.power() / (double) ch.voltage();
                        }
                    }
                    if (lineCurrent > wt.getMaxAmperage()) {
                        double stress = lineCurrent / Math.max(1, wt.getMaxAmperage());
                        if (stress > fuseStress) {
                            fuseStress = stress;
                            fuseTarget = conn;
                        }
                    }
                }
            }
        }

        // 未接入任何组件的孤立发电机：实际输出清零（不空转耗能）
        for (Map.Entry<GridNode, GeneratorEntry> e : generators.entrySet()) {
            if (!visited.contains(e.getKey())) {
                GeneratorEntry g = e.getValue();
                if (g.actualOutputCallback() != null) {
                    try { g.actualOutputCallback().accept(0); } catch (Throwable ignored) {}
                }
            }
        }

        // 全局电池结算：退回未消费的放电额度 + 充入组件级发电富余
        storedEnergy += (int) Math.floor(batteryDischarge - batteryConsumedAccum + surplusAccum);
        storedEnergy = Math.max(0, Math.min(maxBatteryCapacity, storedEnergy));
        setDirty();

        // 执行熔断（每次 allocate 最多熔断1条）
        if (fuseTarget != null) {
            disconnect(fuseTarget.node1(), fuseTarget.node2());
            broadcastFuseMessage();
        }
    }

    /** 向维度内所有玩家广播熔断警告 */
    private void broadcastFuseMessage() {
        Component msg = Component.literal("\u00a7e[\u7535\u7f51] \u7535\u7ebf\u56e0\u8fc7\u538b\u6216\u8fc7\u8f7d\u5df2\u7194\u65ad\uff0c\u8bf7\u7528\u7a7a\u7ebf\u8f74\u91cd\u65b0\u63a5\u7ebf\uff01");
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(msg, false);
        }
    }

    private int getStored() {
        return storedEnergy;
    }

    // ==================== 统计信息 ====================

    public int getLastTotalGenerated() { return lastTotalGenerated; }
    public int getLastTotalDemand() { return lastTotalDemand; }
    public int getCurrentStoredEnergy() { return storedEnergy; }
    public int getMaxBatteryCapacity() { return maxBatteryCapacity; }

    /** 获取指定节点所在连通组件的电网电压（FE/t） */
    public int getNodeVoltage(GridNode node) {
        return nodeVoltage.getOrDefault(node, 0);
    }

    // ==================== 持久化 ====================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("StoredEnergy", storedEnergy);

        ListTag connectionList = new ListTag();
        for (GridConnection c : connections) {
            connectionList.add(c.write(new CompoundTag()));
        }
        tag.put("Connections", connectionList);
        return tag;
    }

    public static WorldPowerGrid load(ServerLevel level, CompoundTag tag, HolderLookup.Provider registries) {
        WorldPowerGrid grid = new WorldPowerGrid(level);
        grid.storedEnergy = tag.getInt("StoredEnergy");

        ListTag connectionList = tag.getList("Connections", Tag.TAG_COMPOUND);
        for (int i = 0; i < connectionList.size(); i++) {
            GridConnection c = GridConnection.read(connectionList.getCompound(i));
            grid.connections.add(c);
            grid.adjacency.computeIfAbsent(c.node1(), n -> new ArrayList<>()).add(c);
            grid.adjacency.computeIfAbsent(c.node2(), n -> new ArrayList<>()).add(c);
        }
        return grid;
    }

    /** 消费者条目 */
    private record ConsumerEntry(GridNode node, Supplier<Integer> demandSupplier, Consumer<Integer> receiveCallback, int requiredVoltage) {}

    /** 发电机条目：最大输出供给器 + 实际输出反馈（null=无反馈）+ 输出电压供给器 + 自适应输出标记 */
    private record GeneratorEntry(Supplier<Integer> outputSupplier, IntConsumer actualOutputCallback,
                                  Supplier<Integer> voltageSupplier, boolean adaptiveOutput) {}

    /**
     * 自适应发电机逐线通道：一条直接相连线缆 = 一个独立供电通道。
     * 通道电压 = min(发电机声明电压, 线耐压)，通道功率 ≤ 通道电压 × 线载流，
     * 且在线缆剩余容量不足时被压缩（自动让位给非自适应电源）；
     * 每条线独立结算电流与熔断，多等级线缆互不拖累。
     */
    private record Channel(GridNode node, GridConnection wire, int voltage, int power) {}
}
