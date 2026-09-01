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
        if (!isTeleportable(planetIndex)) return;
        ServerLevel current = (ServerLevel) player.level();
        ServerLevel target = current.getServer().getLevel(dimension(planetIndex));
        if (target == null) {
            // 某些情况下 levels map 的键可能尚未建立？从 getAllLevels 里按维度兜底查找一次。
            for (ServerLevel level : current.getServer().getAllLevels()) {
                if (level.dimension().equals(dimension(planetIndex))) {
                    target = level;
                    break;
                }
            }
        }
        if (target == null) {
            Polymech.LOGGER.warn("Teleporter: destination level {} is not loaded", dimension(planetIndex).location());
            return;
        }
        BlockPos spawn = target.getSharedSpawnPos();
        int x = spawn.getX();
        int z = spawn.getZ();
        // 必须先加载/生成出生点所在区块，否则 Level#getHeight 对未加载区块会直接返回 minBuildHeight，
        // 玩家就会被传到地底。ServerLevel#getChunk 会同步生成该区块。
        target.getChunk(x >> 4, z >> 4);
        int y = target.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        Vec3 pos = new Vec3(x + 0.5, y, z + 0.5);
        DimensionTransition transition = new DimensionTransition(
                target, pos, player.getDeltaMovement(), player.getYRot(), player.getXRot(),
                DimensionTransition.DO_NOTHING);
        player.changeDimension(transition);
    }

    /** 天体表面重力（以地球为 1.0）；未知维度/主世界返回 1.0。 */
    public static float gravity(int planetIndex) {
        return PLANET_GRAVITY.getOrDefault(planetIndex, 1.0f);
    }

    /** 按维度返回表面重力（以地球为 1.0）；未知维度返回 1.0。 */
    public static float gravity(ResourceKey<Level> level) {
        Integer idx = DIMENSION_TO_PLANET.get(level);
        return idx == null ? 1.0f : gravity(idx);
    }
}
