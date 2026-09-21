package com.mss.polymech.physics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 方块密度表（kg/m³）—— 物理体质量的真来源。
 *
 * <p><b>为什么要有它</b>：Rapier 的碰撞体默认密度是 {@code 1.0}，也就是"一格方块 = 1 kg"。
 * 石头、铁块、羊毛一样重，而且<b>比 50 kg 的玩家还轻</b> ——
 * 于是任何物理体都"一碰就飞、太容易被推动"。</p>
 *
 * <p>本表按"1 方块 ≈ 1 m³"给出真实量级的密度，于是
 * <b>总质量 = Σ(单块密度)</b>：一格石头 ≈ 2500 kg（人推不动）、一格钢 ≈ 7800 kg、
 * 一格羊毛 ≈ 200 kg（还推得动）。材质差异直接变成手感差异。</p>
 *
 * <p><b>体素碰撞体只能有一个密度</b>，所以上层传的是**平均密度**
 * （Σ 单块密度 / 块数）：总质量 = 块数 × 平均 = Σ 单块密度，正好是我们想要的；
 * 转动惯量则按"均匀密度"的近似算，够用。</p>
 *
 * <p>查表顺序：原版方块标签 → 方块 id 关键词（覆盖没有标签的模组方块）→ 默认（石头级）。
 * 结果按 Block 缓存，热路径只有一次 HashMap 查询。</p>
 */
public final class BlockDensity {

    /** 未知方块按石头算（kg/m³）。宁可偏重：偏轻会回到"一碰就飞"。 */
    public static final double DEFAULT = 2500.0;

    private static final Map<Block, Double> CACHE = new ConcurrentHashMap<>();

    private BlockDensity() {
    }

    /** 单个方块的密度（kg/m³）；空气为 0。 */
    public static double of(BlockState state) {
        if (state == null || state.isAir()) {
            return 0.0;
        }
        return CACHE.computeIfAbsent(state.getBlock(), BlockDensity::compute);
    }

    /** 一组方块的总质量（kg）= Σ 单块密度。 */
    public static double mass(List<BlockState> states) {
        double sum = 0.0;
        for (BlockState state : states) {
            sum += of(state);
        }
        return sum;
    }

    /** 一组方块的平均密度；空列表返回 0。 */
    public static double meanDensity(List<BlockState> states) {
        if (states.isEmpty()) {
            return 0.0;
        }
        return mass(states) / states.size();
    }

    /**
     * 把一组方块的平均密度套到碰撞体上（总质量 = Σ 单块密度）。
     *
     * @return 该碰撞体最终的质量（kg）；原生层不支持密度时返回 -1
     */
    public static double apply(long world, long collider, List<BlockState> states) {
        if (world <= 0 || collider <= 0 || states.isEmpty() || !PhysicsNatives.hasTier1()) {
            return -1.0;
        }
        double density = meanDensity(states);
        if (!NativePhysics.colliderSetDensity(world, collider, density)) {
            return -1.0;
        }
        return density * states.size();
    }

    // ==================== 查表 ====================

    private static double compute(Block block) {
        BlockState state = block.defaultBlockState();
        // ── ① 原版标签（由特到泛）──
        if (state.is(BlockTags.WOOL)) {
            return 200.0;          // 羊毛/布
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS)
                || state.is(BlockTags.REPLACEABLE)) {
            return 300.0;          // 植物
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS)
                || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_STAIRS)) {
            return 600.0;          // 木材
        }
        if (state.is(BlockTags.ICE) || state.is(BlockTags.SNOW)) {
            return 900.0;
        }
        if (state.is(BlockTags.SAND) || state.is(BlockTags.DIRT)) {
            return 1600.0;         // 沙/土/砾石/黏土
        }
        // 矿石不走标签（1.21.1 没有 BlockTags.ORES），交给下面的 id 关键词

        // ── ② 方块 id 关键词（模组方块通常没有标签）──
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath().toLowerCase(Locale.ROOT);
        if (containsAny(id, "wool", "cloth", "fabric", "fiber")) {
            return 200.0;
        }
        if (containsAny(id, "leaves", "sapling", "flower", "crop", "grass", "bush", "vine")) {
            return 300.0;
        }
        if (containsAny(id, "planks", "log", "wood", "_slab_wood", "bamboo")) {
            return 600.0;
        }
        if (containsAny(id, "glass", "ice", "snow")) {
            return 900.0;
        }
        if (containsAny(id, "dirt", "sand", "gravel", "clay", "mud", "soil")) {
            return 1600.0;
        }
        // 金属：模组里的铁/钢/铜/铅/镍/锌/锡/青铜/黄铜，名字里几乎一定带这些词
        if (containsAny(id, "iron", "steel", "copper", "lead", "nickel", "zinc", "tin",
                "bronze", "brass", "titanium", "aluminium", "aluminum", "tungsten", "silver",
                "gold", "platinum", "osmium", "invar", "electrum")) {
            if (id.contains("ore")) {
                return 3000.0;     // 金属矿石（含石头）
            }
            return 7800.0;         // 金属块
        }
        if (containsAny(id, "obsidian", "netherite", "ancient_debris")) {
            return 3000.0;
        }
        if (id.contains("ore")) {
            return 3000.0;         // 普通矿石：石头 + 少量金属
        }
        // ── ③ 默认：石头级 ──
        return DEFAULT;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
