package com.mss.polymech.dimension;

import com.mss.polymech.Polymech;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 太阳系行星 → 维度映射。
 * <p>
 * 只包含“非气态巨行星、非恒星”的岩石/冰质天体（含卫星）。
 * 地球直接映射回主世界；气态巨行星（木星/土星/天王星/海王星）与太阳不可传送。
 * </p>
 */
public final class PlanetDimensions {

    private PlanetDimensions() {
    }

    public static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;

    /** 宇宙空间维度（无缝切换测试用）。 */
    public static final ResourceKey<Level> SPACE = key("space");

    /** 太阳系（{@code SolarSystem.createDefault()}）天体索引 → 维度。 */
    private static final Map<Integer, ResourceKey<Level>> PLANET_DIMENSIONS = createMap();

    /** 天体索引 → 表面重力（以地球重力为 1.0）。 */
    private static final Map<Integer, Float> PLANET_GRAVITY = createGravityMap();

    /** 维度 → 天体索引 反查表（用于重力等按维度生效的逻辑）。 */
    private static final Map<ResourceKey<Level>, Integer> DIMENSION_TO_PLANET = createDimensionToPlanetMap();

    private static Map<Integer, Float> createGravityMap() {
        Map<Integer, Float> map = new LinkedHashMap<>();
        map.put(1, 3.70f / 9.807f);   // 水星
        map.put(2, 8.87f / 9.807f);   // 金星
        map.put(3, 1.0f);              // 地球
        map.put(4, 1.622f / 9.807f);  // 月球
        map.put(5, 3.72076f / 9.807f);// 火星
        map.put(6, 0.0057f / 9.807f); // 火卫一
        map.put(7, 0.003f / 9.807f);  // 火卫二
        map.put(9, 1.796f / 9.807f);  // 木卫一
        map.put(10, 1.315f / 9.807f); // 木卫二
        map.put(11, 1.428f / 9.807f); // 木卫三
        map.put(12, 1.235f / 9.807f); // 木卫四
        map.put(14, 1.352f / 9.807f); // 土卫六
        map.put(15, 0.113f / 9.807f); // 土卫二
        map.put(18, 0.62f / 9.807f);  // 冥王星
        map.put(19, 0.288f / 9.807f); // 卡戎
        return Map.copyOf(map);
    }

    private static Map<ResourceKey<Level>, Integer> createDimensionToPlanetMap() {
        Map<ResourceKey<Level>, Integer> map = new LinkedHashMap<>();
        PLANET_DIMENSIONS.forEach((idx, dim) -> map.put(dim, idx));
        return Map.copyOf(map);
    }

    private static Map<Integer, ResourceKey<Level>> createMap() {
        Map<Integer, ResourceKey<Level>> map = new LinkedHashMap<>();
        map.put(1, key("mercury"));
        map.put(2, key("venus"));
        map.put(3, Level.OVERWORLD);
        map.put(4, key("moon"));
        map.put(5, key("mars"));
        map.put(6, key("phobos"));
        map.put(7, key("deimos"));
        // 8 = 木星（气态巨行星，不可传送）
        map.put(9, key("io"));
        map.put(10, key("europa"));
        map.put(11, key("ganymede"));
        map.put(12, key("callisto"));
        // 13 = 土星（气态巨行星，不可传送）
        map.put(14, key("titan"));
        map.put(15, key("enceladus"));
        // 16 = 天王星、17 = 海王星（气态巨行星，不可传送）
        map.put(18, key("pluto"));
        map.put(19, key("charon"));
        return Map.copyOf(map);
    }

    public static boolean isTeleportable(int planetIndex) {
        return PLANET_DIMENSIONS.containsKey(planetIndex);
    }

    public static ResourceKey<Level> dimension(int planetIndex) {
        return PLANET_DIMENSIONS.getOrDefault(planetIndex, Level.OVERWORLD);
    }

    public static ResourceKey<Level> key(String id) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, id));
    }

    /** 把玩家传送到指定星球维度；不可传送的索引会被忽略。传送目标为该维度的世界出生点表面。 */
    public static void teleport(ServerPlayer player, int planetIndex) {
        ServerLevel target = targetLevel(player, planetIndex);
        if (target == null) return;
        BlockPos spawn = target.getSharedSpawnPos();
        teleportToPlanetSurface(player, planetIndex, spawn.getX(), spawn.getZ());
    }

    /** 计算星球维度指定 XZ 的地表 Y（会同步生成区块）。 */
    public static int surfaceY(ServerPlayer player, int planetIndex, int x, int z) {
        ServerLevel target = targetLevel(player, planetIndex);
        if (target == null) return 64;
        target.getChunk(x >> 4, z >> 4);
        return target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
    }

    /** 把玩家传送到指定星球维度的指定 XZ 地表。 */
    public static void teleportToPlanetSurface(ServerPlayer player, int planetIndex, int x, int z) {
        int y = surfaceY(player, planetIndex, x, z);
        Vec3 pos = new Vec3(x + 0.5, y, z + 0.5);
        DimensionTransition transition = new DimensionTransition(
                targetLevel(player, planetIndex), pos, player.getDeltaMovement(), player.getYRot(), player.getXRot(),
                DimensionTransition.DO_NOTHING);
        player.changeDimension(transition);
    }

    private static ServerLevel targetLevel(ServerPlayer player, int planetIndex) {
        if (!isTeleportable(planetIndex)) return null;
        ServerLevel current = (ServerLevel) player.level();
        ServerLevel target = current.getServer().getLevel(dimension(planetIndex));
        if (target == null) {
            for (ServerLevel level : current.getServer().getAllLevels()) {
                if (level.dimension().equals(dimension(planetIndex))) {
                    target = level;
                    break;
                }
            }
        }
        if (target == null) {
            Polymech.LOGGER.warn("Teleporter: destination level {} is not loaded", dimension(planetIndex).location());
        }
        return target;
    }

    /** 天体表面重力（以地球为 1.0）；未知维度/主世界返回 1.0。 */
    public static float gravity(int planetIndex) {
        return PLANET_GRAVITY.getOrDefault(planetIndex, 1.0f);
    }

    /** 维度 → 天体索引；非星球维度返回 -1。 */
    public static int planetIndex(ResourceKey<Level> level) {
        Integer idx = DIMENSION_TO_PLANET.get(level);
        return idx == null ? -1 : idx;
    }

    /** 按维度返回表面重力（以地球为 1.0）；宇宙空间返回 0，未知维度返回 1.0。 */
    public static float gravity(ResourceKey<Level> level) {
        if (SPACE.equals(level)) return 0.0f;
        Integer idx = DIMENSION_TO_PLANET.get(level);
        return idx == null ? 1.0f : gravity(idx);
    }
}
