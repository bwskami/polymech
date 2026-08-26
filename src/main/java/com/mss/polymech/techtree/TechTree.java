package com.mss.polymech.techtree;

import com.mss.polymech.Polymech;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 科技树注册中心 + 图推导 + 分层布局。
 * <p>
 * 设计要点（呼应“动态、不硬编码”）：
 * <ul>
 *   <li>节点分散声明，连线由每个节点的 {@code prerequisites} 在运行时推导，不存整张硬编码图。</li>
 *   <li>布局按 {@code tier}(列) 与 {@code category}(同层内排序) 自动计算，无需手写坐标。</li>
 *   <li>新增机器/科技只需调用 {@link #register(TechNode)} 或 {@link #machine(String, int, String, Component...)}，
 *       整棵树自动拓展；addon / 数据包亦可借此注册节点。</li>
 * </ul>
 */
public final class TechTree {

    /** 节点布局尺寸与间距（像素）。 */
    public static final int NODE_W = 150;
    public static final int NODE_H = 64;
    public static final int COL_GAP = 90;
    public static final int ROW_GAP = 36;

    private static final Map<String, TechNode> NODES = new LinkedHashMap<>();

    private TechTree() {
    }

    public static void register(TechNode node) {
        if (NODES.containsKey(node.id())) {
            Polymech.LOGGER.warn("TechTree: duplicate node id '{}', overwriting", node.id());
        }
        NODES.put(node.id(), node);
    }

    @Nullable
    public static TechNode get(String id) {
        return NODES.get(id);
    }

    public static List<TechNode> all() {
        return new ArrayList<>(NODES.values());
    }

    public static boolean isEmpty() {
        return NODES.isEmpty();
    }

    /**
     * 机器节点便捷构造：图标从对应机器物品懒加载，{@code machineId = id}，
     * 标题默认取 {@code block.<modid>.<id>}。
     */
    public static TechNode.Builder machine(String machineId, int tier, String category, Component... steps) {
        return TechNode.builder(machineId)
                .machineId(machineId)
                .tier(tier)
                .category(category)
                .title(Component.translatable("block." + Polymech.MOD_ID + "." + machineId))
                .icon(() -> machineIcon(machineId))
                .steps(steps);
    }

    private static ItemStack machineIcon(String machineId) {
        try {
            var rl = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, machineId);
            var item = BuiltInRegistries.ITEM.get(rl);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return new ItemStack(item);
            }
        } catch (Exception ignored) {
            // 物品尚未解析时静默回退
        }
        return ItemStack.EMPTY;
    }

    /**
     * 推导连线：每个节点的每条前置若存在，生成一条 前置 -> 节点 的边。
     */
    public static List<Edge> buildEdges() {
        List<Edge> edges = new ArrayList<>();
        for (TechNode node : NODES.values()) {
            for (String pre : node.prerequisites()) {
                if (NODES.containsKey(pre)) {
                    edges.add(new Edge(pre, node.id()));
                } else {
                    Polymech.LOGGER.warn("TechTree: node '{}' references missing prerequisite '{}'",
                            node.id(), pre);
                }
            }
        }
        return edges;
    }

    /**
     * 分层布局：tier 决定列，同层按 category 再按注册序排序决定行。
     * 返回每个节点左上角坐标与画布尺寸。
     */
    public static Layout computeLayout() {
        Map<Integer, List<TechNode>> byTier = new TreeMap<>();
        for (TechNode n : NODES.values()) {
            byTier.computeIfAbsent(n.tier(), k -> new ArrayList<>()).add(n);
        }

        Map<String, int[]> pos = new LinkedHashMap<>();
        int maxRows = 0;
        int col = 0;
        for (var entry : byTier.entrySet()) {
            List<TechNode> nodes = entry.getValue();
            nodes.sort(Comparator.comparing(TechNode::category));
            int row = 0;
            for (TechNode n : nodes) {
                int x = col * (NODE_W + COL_GAP);
                int y = row * (NODE_H + ROW_GAP);
                pos.put(n.id(), new int[]{x, y});
                row++;
            }
            maxRows = Math.max(maxRows, nodes.size());
            col++;
        }

        int canvasW = Math.max(1, byTier.size()) * (NODE_W + COL_GAP);
        int canvasH = Math.max(1, maxRows) * (NODE_H + ROW_GAP);
        return new Layout(pos, canvasW, canvasH);
    }

    public record Edge(String from, String to) {
    }

    public record Layout(Map<String, int[]> positions, int canvasWidth, int canvasHeight) {
        public int[] posOf(String id) {
            return positions.get(id);
        }
    }

    /**
     * 播种示例科技树。仅作演示：展示“声明节点 + 前置”即可动态成树。
     * <p>
     * 后续可改为：遍历 {@code MachineRegistry} 自动为每个机器生成节点，
     * 再由配方/手工声明前置，进一步减少硬编码。
     * </p>
     */
    public static void bootstrap() {
        if (!isEmpty()) {
            return; // 防止重复播种（如模组重载）
        }

        // —— 抽象研究节点（无对应机器）——
        TechNode.builder("tech_steam")
                .title(Component.translatable("techtree.poly_mech.tech_steam"))
                .icon(() -> new ItemStack(net.minecraft.world.item.Items.COAL))
                .tier(0).category("base")
                .description(Component.translatable("techtree.poly_mech.tech_steam.desc"))
                .build();

        TechNode.builder("tech_electric")
                .title(Component.translatable("techtree.poly_mech.tech_electric"))
                .icon(() -> new ItemStack(net.minecraft.world.item.Items.REDSTONE))
                .tier(2).category("base")
                .prerequisite("tech_steam")
                .description(Component.translatable("techtree.poly_mech.tech_electric.desc"))
                .build();

        // —— 机器节点（图标自动取自对应机器物品）——
        machine("small_steam_boiler", 1, "steam",
                        Component.translatable("techtree.poly_mech.step.place_and_power"))
                .prerequisite("tech_steam").build();

        machine("horizontal_steam_boiler", 1, "steam",
                        Component.translatable("techtree.poly_mech.step.place_and_power"))
                .prerequisite("small_steam_boiler").build();

        machine("beehive_coke_oven", 1, "steam",
                        Component.translatable("techtree.poly_mech.step.place_and_power"))
                .prerequisite("tech_steam").build();

        machine("primitive_blast_furnace", 2, "steam",
                        Component.translatable("techtree.poly_mech.step.place_and_power"))
                .prerequisite("beehive_coke_oven").build();

        machine("steam_hammer", 2, "steam",
                        Component.translatable("techtree.poly_mech.step.place_and_power"))
                .prerequisite("horizontal_steam_boiler").build();

        machine("battery", 3, "electric",
                        Component.translatable("techtree.poly_mech.step.place_and_power"))
                .prerequisite("tech_electric").build();
    }
}
