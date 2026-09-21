package com.mss.polymech.dimension;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ServerCelestialWorld;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.SpaceWorld;
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
import java.util.concurrent.ConcurrentHashMap;

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

    /** 标准重力加速度（m/s²）—— kelvin 的 {@code G} 是绝对值，本类返回倍数，换算用。 */
    private static final double STANDARD_GRAVITY = 9.807;

    /**
     * kelvin 权威重力（倍数）缓存。
     * <b>只在成功查到 ServerCelestialWorld 时写入</b>，所以数据包未加载完时不会缓存回退值。
     */
    private static final Map<ResourceKey<Level>, Float> KELVIN_GRAVITY = new ConcurrentHashMap<>();

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

    /**
     * 把玩家传送到太空维度中指定天体旁的"昼面观测点"。
     * <p>
     * 位置 = 天体游戏坐标（{@link SpaceWorld#gamePos}，真实缩放坐标）+ 朝太阳方向 × 2.2×半径，
     * 落点看到的是被照亮的一面，太阳在身后；天体本体在视野中占约 54°（壮观且不穿模）。
     * 太阳本身取 +X 方向。所有天体（含气态巨行星与恒星）都可传送。
     * </p>
     */
    public static boolean teleportToSpaceAbove(ServerPlayer player, int planetIndex) {
        if (planetIndex < 0 || planetIndex >= RealAstroData.BODIES.size()) return false;
        return teleportToSpaceAbove(player, RealAstroData.BODIES.get(planetIndex));
    }

    /** 按天体数据传送（数据驱动；索引空间由调用方负责换算）。 */
    public static boolean teleportToSpaceAbove(ServerPlayer player, RealAstroData body) {
        ServerLevel space = player.server.getLevel(SPACE);
        if (space == null) return false;
        if (body == null) return false;

        double[] gp = SpaceWorld.gamePosMc(body);
        double cx = gp[0], cy = gp[1], cz = gp[2];
        double radiusMc = SpaceWorld.toMc(body.radiusMeters());

        // 观测方向：指向太阳（看昼面）；太阳本身用 +X
        double dirX, dirY, dirZ;
        if (body == RealAstroData.SUN) {
            dirX = 1; dirY = 0; dirZ = 0;
        } else {
            double[] sp = SpaceWorld.gamePosMc(RealAstroData.SUN); // (0,0,0)
            double dx = sp[0] - cx, dy = sp[1] - cy, dz = sp[2] - cz;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) { dirX = 1; dirY = 0; dirZ = 0; }
            else { dirX = dx / len; dirY = dy / len; dirZ = dz / len; }
        }
        double dist = Math.max(radiusMc * 2.2, radiusMc + 150.0);
        double px = cx + dirX * dist;
        double py = cy + dirY * dist;
        double pz = cz + dirZ * dist;

        // 安全夹取（正常情况不会触发）
        py = net.minecraft.util.Mth.clamp(py,
                space.getMinBuildHeight() + 16.0, space.getMaxBuildHeight() - 16.0);

        // 面向天体中心
        double dx = cx - px, dy = cy - py, dz = cz - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        // MC 视线向量: (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));

        DimensionTransition transition = new DimensionTransition(
                space, new Vec3(px, py, pz), Vec3.ZERO, yaw, pitch, DimensionTransition.DO_NOTHING);
        player.changeDimension(transition);
        // 6DOF 姿态与 vanilla 角度同步（否则相机仍看向传送前方向）
        com.mss.polymech.space.SpacePlayerData.get(player).initFromVanilla(yaw, pitch);
        return true;
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

    /**
     * 按维度返回表面重力（以地球为 1.0）；宇宙空间返回 0，未知维度返回 1.0。
     *
     * <p><b>数据源已迁到 kelvin 的数据包驱动值</b>（见
     * {@code docs/mps-clone-plan.md} §19 的迁移第 1 步）：
     * 优先取 {@code ServerCelestialWorld.G}（来自
     * {@code data/poly_mech/space_data/space/world/<维度>.json} 的 {@code gravity}），
     * 取不到才回退到内置的 {@link #PLANET_GRAVITY} 表。</p>
     *
     * <p><b>单位换算（最容易出错的地方）</b>：{@code G} 是<b>绝对重力加速度 m/s²</b>
     * （地球 9.807、火星 3.72076），而本方法的返回语义是<b>以地球为 1.0 的倍数</b>
     * （地球 1.0）。两者必须经 {@link #STANDARD_GRAVITY} 相除，
     * 单位不换算就是"火星重力 = 地球的 3.72 倍"这种静默错误。</p>
     *
     * <p><b>为什么可以缓存、且缓存不会过期</b>：
     * {@code gravity} 在 {@code MixinEntity#getGravity} 里是<b>每实体每 tick</b> 的热路径，
     * 而 kelvin 的查找要遍历静态池（还会新建 ArrayList），不能每次都做。
     * 这里的做法是<b>只在查到了才写入缓存</b> —— 数据包还没加载完（世界加载早期、
     * 或在 kelvin 登记之前）时走回退分支且<b>什么都不缓存</b>，
     * 因此不存在"把回退值永久钉住"的风险；一旦 kelvin 就绪，此后就是一次 map 查表。</p>
     */
    public static float gravity(ResourceKey<Level> level) {
        if (SPACE.equals(level)) return 0.0f;
        Float authoritative = KELVIN_GRAVITY.get(level);
        if (authoritative != null) {
            return authoritative;
        }
        ServerCelestialWorld celestialWorld =
                ServerCelestialWorld.getCelestialWorld(level.location().toString());
        if (celestialWorld != null && celestialWorld.G > 0.0) {
            float factor = (float) (celestialWorld.G / STANDARD_GRAVITY);
            KELVIN_GRAVITY.put(level, factor);
            return factor;
        }
        Integer idx = DIMENSION_TO_PLANET.get(level);
        return idx == null ? 1.0f : gravity(idx);
    }
}
