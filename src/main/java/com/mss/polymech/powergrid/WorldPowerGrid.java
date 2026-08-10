package com.mss.polymech.powergrid;

import com.mss.polymech.Polymech;
import com.mss.polymech.network.WireSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 世界级电网数据。
 * <p>
 * 以SavedData持久化存储每个维度中由真实电线（{@link GridConnection}）连接的节点网络，
 * 并通过连通性分析驱动功率分配：
 * <ul>
 *   <li><b>连接管理</b>：connect/disconnect/removeNode，结构变化时自动向客户端同步电线渲染数据</li>
 *   <li><b>功率分配</b>：按连通组件（BFS）把发电机功率分配给同组件内的消费者；
 *       未连入电网的发电机功率被丢弃，未被电网覆盖的消费者得不到供电</li>
 *   <li><b>全局电池</b>：全维度统一的储电缓冲（沿用PowerNetworkManager的电池设计）</li>
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

    /** 发电机：节点 → 发电量供给器（FU/tick） */
    private final Map<GridNode, Supplier<Integer>> generators = new HashMap<>();

    /** 消费者：节点 → 需求供给器 + 接收回调 */
    private final Map<GridNode, ConsumerEntry> consumers = new HashMap<>();

    // ========== 电池（全局） ==========

    private final int maxBatteryCapacity = 100000;
    private int storedEnergy;

    // ========== 分配缓存 ==========

    /** 每5 tick刷新一次组件供电比例 */
    private static final int ALLOCATION_INTERVAL = 5;
    private int tickCounter = 0;
    private final Map<GridNode, Double> nodeSupplyRatio = new HashMap<>();
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
     * @param node              接入电网的节点（该方块必须是电网方块）
     * @param generatedSupplier 每tick发电量（FU）
     */
    public void registerGenerator(GridNode node, Supplier<Integer> generatedSupplier) {
        generators.put(node, generatedSupplier);
    }

    public void unregisterGenerator(GridNode node) {
        generators.remove(node);
    }

    /**
     * 在指定节点注册消费者。
     *
     * @param node            接入电网的节点
     * @param demandSupplier  每tick需求（FU）
     * @param receiveCallback 收到电力时的回调（传入实际分配量）
     */
    public void registerConsumer(GridNode node, Supplier<Integer> demandSupplier, Consumer<Integer> receiveCallback) {
        consumers.put(node, new ConsumerEntry(node, demandSupplier, receiveCallback));
    }

    public void unregisterConsumer(GridNode node) {
        consumers.remove(node);
    }

    // ==================== 功率分配 ====================

    /**
     * 每tick驱动：定期（每 {@link #ALLOCATION_INTERVAL} tick）刷新组件供电比例，
     * 每tick按比例向消费者供电。
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
                c.receiveCallback.accept((int) Math.floor(demand * ratio));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 连通性功率分配：
     * <ol>
     *   <li>BFS划分连通组件</li>
     *   <li>组件内发电与需求汇总；全局电池补充缺口/吸收富余</li>
     *   <li>按组件供电能力给组件内消费者设定比例</li>
     * </ol>
     */
    private void allocate() {
        nodeSupplyRatio.clear();
        lastTotalGenerated = 0;
        lastTotalDemand = 0;

        int totalDemand = 0;
        int totalGenerated = 0;
        Map<GridNode, Integer> demandByNode = new HashMap<>();
        for (ConsumerEntry c : consumers.values()) {
            try {
                int d = Math.max(0, c.demandSupplier.get());
                demandByNode.put(c.node, d);
                totalDemand += d;
            } catch (Throwable ignored) {}
        }
        for (Map.Entry<GridNode, Supplier<Integer>> e : generators.entrySet()) {
            try {
                int g = Math.max(0, e.getValue().get());
                if (g > 0) totalGenerated += g;
            } catch (Throwable ignored) {}
        }
        lastTotalGenerated = totalGenerated;
        lastTotalDemand = totalDemand;

        // 电池调度（全局缓冲）
        int available = totalGenerated;
        if (totalGenerated >= totalDemand) {
            int surplus = totalGenerated - totalDemand;
            int charge = Math.min(surplus, maxBatteryCapacity - storedEnergy);
            storedEnergy += charge;
            available -= charge;
        } else {
            int deficit = totalDemand - totalGenerated;
            int discharge = Math.min(deficit, storedEnergy);
            storedEnergy -= discharge;
            available += discharge;
        }
        if (storedEnergy != getStored()) {
            setDirty();
        }

        // 组件划分（BFS）
        Set<GridNode> visited = new HashSet<>();
        for (GridNode start : adjacency.keySet()) {
            if (visited.contains(start))
                continue;

            List<GridNode> component = new ArrayList<>();
            Deque<GridNode> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                GridNode n = queue.poll();
                component.add(n);
                for (GridConnection c : adjacency.getOrDefault(n, List.of())) {
                    GridNode other = c.node1().equals(n) ? c.node2() : c.node1();
                    if (visited.add(other))
                        queue.add(other);
                }
            }

            // 组件内发电/需求
            int compGenerated = 0;
            int compDemand = 0;
            for (GridNode n : component) {
                Supplier<Integer> gen = generators.get(n);
                if (gen != null) {
                    try { compGenerated += Math.max(0, gen.get()); } catch (Throwable ignored) {}
                }
                Integer d = demandByNode.get(n);
                if (d != null)
                    compDemand += d;
            }

            // 电池配额按需求占比分配
            int batteryShare = compDemand == 0 ? 0 : (int) Math.floor((storedEnergy == 0 ? 0 : storedEnergy) *
                    ((double) compDemand / Math.max(1, totalDemand)) * (totalGenerated < totalDemand ? 1 : 0));

            double compSupply = compGenerated + batteryShare;
            double ratio = compDemand > 0 ? Math.min(1.0, compSupply / compDemand) : 1.0;
            for (GridNode n : component) {
                if (consumers.containsKey(n))
                    nodeSupplyRatio.put(n, ratio);
            }
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
    private record ConsumerEntry(GridNode node, Supplier<Integer> demandSupplier, Consumer<Integer> receiveCallback) {}
}
