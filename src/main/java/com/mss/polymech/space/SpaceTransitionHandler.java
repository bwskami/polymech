package com.mss.polymech.space;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.network.SpaceTransitionSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地球 ↔ 太空无缝切换（参考 space mod 的 PositionZoom + 取消加载画面思路）。
 */
public final class SpaceTransitionHandler {

    private static final int COOLDOWN_TICKS = 80;
    private static final int DELAY_TICKS = 2;
    private static final double SURFACE_TO_SPACE_SCALE = 0.01;
    private static final double EARTH_SPAWN_ALTITUDE_REAL =
            RealAstroData.EARTH.radiusMeters() + RealAstroData.EARTH.atmosphereHeightMeters() + 10_000.0;

    /**
     * 待执行切换。
     * <ul>
     *   <li>planetIndex &gt;= 0 且 pos == null：进入行星影子维度表面（{@code toSurface}）；</li>
     *   <li>planetIndex &gt;= 0 且 pos != null：进入指定地表坐标（地球捕获）；</li>
     *   <li>pos != null 且 planetIndex &lt; 0：进入太空维度指定坐标。</li>
     * </ul>
     */
    private record PendingTransition(ResourceKey<Level> targetDim, Vec3 pos, int planetIndex, int executeTick) {
    }

    private static final Map<UUID, PendingTransition> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> COOLDOWN = new ConcurrentHashMap<>();

    /**
     * 每个玩家**最近一次升空**时"相对地球的单位方向"——只给回程诊断用。
     *
     * <p><b>为什么必须有它</b>：回程那一行打印的是玩家的<b>输入绝对坐标</b>，而去程那行打印的是
     * <b>映射算出来的目标点</b>；玩家被放到目标点后若没动过，两行必然"逐位一致"——
     * 那是<b>同义反复</b>，判不出映射对不对（§31.28 的旧判据就是这样；归档日志 09-21 18:10 那一对即是）。
     * 真正能自动判的是两条：</p>
     * <ol>
     *   <li><b>回程落点是否回到起飞点</b>：{@code 落点(x,z)} vs {@code 玩家地表(x,z)}，±2 格；</li>
     *   <li><b>回程方向是否与去程同一条径线</b>：夹角 ≤5° —— 地球在两次之间已经走过几十万公里
     *       （每 tick 108.2 km），绝对坐标根本不可直接比。</li>
     * </ol>
     */
    private static final Map<UUID, double[]> LAST_ASCENT_DIR = new ConcurrentHashMap<>();

    /** 单位化；长度 ~0 时返回 null（调用方判空，不制造 NaN）。 */
    private static double[] unit(double x, double y, double z) {
        double n = Math.sqrt(x * x + y * y + z * z);
        return n < 1.0e-9 ? null : new double[]{x / n, y / n, z / n};
    }

    private static double norm(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private SpaceTransitionHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();

        if (executePending(player, id)) return;
        if (player.tickCount < COOLDOWN.getOrDefault(id, 0)) return;

        ResourceKey<Level> dim = player.level().dimension();
        if (PlanetDimensions.SPACE.equals(dim)) {
            tickInSpace(player, id);
        } else if (dim == Level.OVERWORLD) {
            tickOnEarth(player, id);
        }
    }

    private static void tickOnEarth(ServerPlayer player, UUID id) {
        // 与 space 保持一致：超过 celestialWorld.Height（10000）才进入太空。
        if (player.getY() < 10000.0) return;
        if (PENDING.containsKey(id)) return;
        setCooldown(id, player.tickCount);

        ServerLevel space = player.server.overworld().getServer().getLevel(PlanetDimensions.SPACE);
        if (space == null) return;

        double seconds = SpaceWorld.j2000Seconds();
        double[] spaceReal = EarthSpaceMapping.worldToSpace(player.getX(), player.getY(), player.getZ(), seconds);
        double spaceX = SpaceWorld.toMc(spaceReal[0]);
        double spaceY = SpaceWorld.toMc(spaceReal[1]);
        double spaceZ = SpaceWorld.toMc(spaceReal[2]);

        // ★ 往返诊断（2026-09-27）：记下"相对地球的方向"与"半径比"，供回程那一行算夹角/落点回归。
        //   判据① 半径比 = 2.20（到达距离 2.2R，与 EarthSpaceMapping.ARRIVAL_RADIUS_FACTOR 同口径）；
        //   判据② 回程的半径比 = 1.02（卡门线捕获壳），夹角 ≤5°（同一条径线）。
        double[] earthRef = SpaceWorld.blockPos(RealAstroData.EARTH);
        double relX = spaceReal[0] - earthRef[0];
        double relY = spaceReal[1] - earthRef[1];
        double relZ = spaceReal[2] - earthRef[2];
        double[] ascDir = unit(relX, relY, relZ);
        if (ascDir != null) {
            LAST_ASCENT_DIR.put(id, ascDir);
        }
        double ascRadiusRatio = norm(relX, relY, relZ) / RealAstroData.EARTH.radiusMeters();

        // ★ 落点诊断（方案 B / S5 的验收依据，见 docs/mps-clone-plan.md §30.12）。
        //   判据：翻 `identityMode` 前后，**"宇宙系(米)"必须逐位不变**（映射本身没动），
        //   而**"目标"必须恰好 ×ZOOM**（toMc 从 ÷ZOOM 变恒等）。有这两列，
        //   "落点逐位比对"就不再靠肉眼猜，日志一 diff 即可。
        com.mss.polymech.Polymech.LOGGER.info(
                "[坐标落点] {} → 太空 | 玩家地表=({}, {}, {}) | 宇宙系(米)=({}, {}, {}) | 目标=({}, {}, {}) | 约定={}"
                        + " | 地球参考=(米)({}, {}, {}) 半径比={}（期望≈2.20）",
                player.level().dimension().location(),
                player.getX(), player.getY(), player.getZ(),
                spaceReal[0], spaceReal[1], spaceReal[2],
                spaceX, spaceY, spaceZ,
                SpaceWorld.identityMode() ? "恒等(1格=1米)" : "缩放(1格=10000米)",
                earthRef[0], earthRef[1], earthRef[2],
                String.format(java.util.Locale.ROOT, "%.4f", ascRadiusRatio));

        // ★ 预加载必须按范围守门（方案 B / S4a 的**前置**）：恒等约定下目标会到 1e11 量级，
        //   而 `(int)` 强转的上限只有 2.1e9 ⇒ **溢出**，预加载会拿到一个垃圾（别名）坐标。
        //   ZOOM 模式下 spaceX≈1.87e7 落在 int 内所以一直没暴露；翻开关前必须补上。
        //   阈值取 3.0e7：略小于深空守卫线（26 位极限 3.355e7），线内是真实区块、线外是纯虚空，
        //   而纯虚空本来就不需要预加载。
        if (Math.abs(spaceX) <= 3.0e7 && Math.abs(spaceY) <= 3.0e7 && Math.abs(spaceZ) <= 3.0e7) {
            SpacePreloader.preload(space, new BlockPos((int) spaceX, (int) spaceY, (int) spaceZ));
        } else {
            com.mss.polymech.Polymech.LOGGER.info(
                    "[坐标落点] 目标在深空，跳过区块预加载（纯虚空无需预加载）: ({}, {}, {})",
                    spaceX, spaceY, spaceZ);
        }
        Vec3 pos = new Vec3(spaceX, spaceY, spaceZ);
        sendSync(player, pos);
        PENDING.put(id, new PendingTransition(PlanetDimensions.SPACE, pos, -1,
                player.tickCount + DELAY_TICKS));
    }

    private static void tickInSpace(ServerPlayer player, UUID id) {
        // 玩家真实坐标（含 Y；与 gamePos 同一套"游戏宇宙"坐标系）
        double pxReal = SpaceWorld.toReal(player.getX());
        double pyReal = SpaceWorld.toReal(player.getY());
        double pzReal = SpaceWorld.toReal(player.getZ());
        double seconds = SpaceWorld.j2000Seconds();

        // 泛化卡门线捕获：接近任何可着陆天体（岩石/冰质，含卫星）即进入其影子维度。
        // 恒星与气态巨行星不可着陆，只作为太空中的景观。
        // 距离比较统一使用 gamePos（非地球天体 Y 压平），与传送/渲染坐标一致。
        for (RealAstroData body : RealAstroData.BODIES) {
            int idx = RealAstroData.indexOf(body.id());
            if (!PlanetDimensions.isTeleportable(idx)) continue;

            double[] gp = SpaceWorld.gamePos(body);
            double dx = pxReal - gp[0];
            double dy = pyReal - gp[1];
            double dz = pzReal - gp[2];
            double entryRadius = body.radiusMeters() + body.carmenLineHeightMeters() - 1.0;
            if (dx * dx + dy * dy + dz * dz > entryRadius * entryRadius) continue;

            if (PENDING.containsKey(id)) return;
            setCooldown(id, player.tickCount);

            if (body == RealAstroData.EARTH) {
                // 地球：把玩家在太空中的位置反算回主世界地表坐标，落点即接近点
                double[] worldPos = EarthSpaceMapping.spaceToWorld(pxReal, pyReal, pzReal, seconds);
                int planetX = (int) worldPos[0];
                int planetZ = (int) worldPos[2];
                int surfaceY = PlanetDimensions.surfaceY(player, 3, planetX, planetZ);

                // ★ 落点诊断（与"地表→太空"那条配对，见 §30.12）：
                //   这一条是"太空→地表"的落点，翻 `identityMode` 后**它不该变**
                //   （因为落点最终是**方块坐标** planetX/Z，由 spaceToWorld 的球面反算给出，
                //    与 ZOOM 无关）——但玩家在太空里的输入坐标 pxReal 会变，两者要一起看才算证完。
                //
                // ★★ 2026-09-27 补两项**可自动判定**的量（旧的"两行坐标逐位一致"是同义反复）：
                //    ① 半径比：回程应当 ≈1.02（卡门线捕获壳），去程 ≈2.20（到达距离）；
                //    ② 与去程的方向夹角：≤5° ⇒ PASS（同一条径线）。
                //    再配合"落点(x,z) vs 玩家地表(x,z) ±2 格"，往返一致性就不再靠肉眼 diff。
                double[] earthRef = SpaceWorld.blockPos(RealAstroData.EARTH);
                double dxE = pxReal - earthRef[0];
                double dyE = pyReal - earthRef[1];
                double dzE = pzReal - earthRef[2];
                double downRadiusRatio = norm(dxE, dyE, dzE) / RealAstroData.EARTH.radiusMeters();
                double[] downDir = unit(dxE, dyE, dzE);
                double[] upDir = LAST_ASCENT_DIR.get(id);
                double angleDeg = Double.NaN;
                if (upDir != null && downDir != null) {
                    double dot = upDir[0] * downDir[0] + upDir[1] * downDir[1] + upDir[2] * downDir[2];
                    angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
                }
                String roundTripVerdict = Double.isNaN(angleDeg) ? "n/a(本局没有去程记录)"
                        : angleDeg <= 5.0 ? "PASS(与去程同一条径线)" : "★FAIL(与去程不同径线)";
                com.mss.polymech.Polymech.LOGGER.info(
                        "[坐标落点] 太空 → {} | 太空输入(米)=({}, {}, {}) | 落点=({}, {}, {}) | 约定={}"
                                + " | 地球参考=(米)({}, {}, {}) 半径比={}（期望≈1.02）"
                                + " | 往返: 与去程方向夹角={}° ⇒ {} | 落点回位判据: 落点({},{}) vs 去程地表(见上一条)",
                        body.id(), pxReal, pyReal, pzReal, planetX, surfaceY, planetZ,
                        SpaceWorld.identityMode() ? "恒等(1格=1米)" : "缩放(1格=10000米)",
                        earthRef[0], earthRef[1], earthRef[2],
                        String.format(java.util.Locale.ROOT, "%.4f", downRadiusRatio),
                        Double.isNaN(angleDeg) ? "n/a" : String.format(java.util.Locale.ROOT, "%.3f", angleDeg),
                        roundTripVerdict, planetX, planetZ);

                ServerLevel overworld = player.server.overworld();
                SpacePreloader.preload(overworld, new BlockPos(planetX, surfaceY, planetZ));
                Vec3 pos = new Vec3(planetX + 0.5, surfaceY, planetZ + 0.5);
                sendSync(player, pos);
                PENDING.put(id, new PendingTransition(Level.OVERWORLD, pos, 3,
                        player.tickCount + DELAY_TICKS));
            } else {
                // 其它行星/卫星：落到该影子维度的世界出生点（精确落点捕获留待 M4）。
                // 非无缝路径，不发同步包：客户端由 respawn 包自行定位。
                PENDING.put(id, new PendingTransition(null, null, idx,
                        player.tickCount + DELAY_TICKS));
            }
            return;
        }
    }

    private static boolean executePending(ServerPlayer player, UUID id) {
        PendingTransition pending = PENDING.get(id);
        if (pending == null) return false;
        if (player.tickCount < pending.executeTick()) return true;

        PENDING.remove(id);
        if (pending.planetIndex() >= 0 && pending.pos() == null) {
            // 影子维度表面出生点（非地球天体）
            PlanetDimensions.teleport(player, pending.planetIndex());
        } else if (pending.planetIndex() >= 0) {
            PlanetDimensions.teleportToPlanetSurface(player, pending.planetIndex(),
                    (int) pending.pos().x(), (int) pending.pos().z());
        } else {
            ServerLevel space = player.server.getLevel(PlanetDimensions.SPACE);
            if (space == null) return true;
            DimensionTransition transition = new DimensionTransition(
                    space, pending.pos(), player.getDeltaMovement(), player.getYRot(), player.getXRot(),
                    DimensionTransition.DO_NOTHING);
            player.changeDimension(transition);
            // 服务端 6DOF 姿态与传送后角度同步（与 teleportToSpaceAbove 一致；
            // 客户端由 SpaceTravelMixin 的传送检测自行重建）
            SpacePlayerData.get(player).initFromVanilla(player.getYRot(), player.getXRot());
        }
        return true;
    }

    private static void sendSync(ServerPlayer player, Vec3 pos) {
        PacketDistributor.sendToPlayer(player,
                new SpaceTransitionSyncPacket(pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot()));
    }

    private static void setCooldown(UUID id, int tickCount) {
        COOLDOWN.put(id, tickCount + COOLDOWN_TICKS);
    }
}
