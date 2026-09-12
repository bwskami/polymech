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

        SpacePreloader.preload(space, new BlockPos((int) spaceX, (int) spaceY, (int) spaceZ));
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
