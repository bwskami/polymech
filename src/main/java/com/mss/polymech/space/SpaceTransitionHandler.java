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
        double[] earthReal = RealAstroData.EARTH.realPositionAt(SpaceWorld.j2000Seconds());
        double pxReal = SpaceWorld.toReal(player.getX());
        double pyReal = SpaceWorld.toReal(player.getY());
        double pzReal = SpaceWorld.toReal(player.getZ());
        double dx = pxReal - earthReal[0];
        double dy = pyReal - earthReal[1];
        double dz = pzReal - earthReal[2];
        double entryRadius = RealAstroData.EARTH.radiusMeters() + RealAstroData.EARTH.carmenLineHeightMeters() - 1.0;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq <= entryRadius * entryRadius) {
            if (PENDING.containsKey(id)) return;
            setCooldown(id, player.tickCount);

            double[] worldPos = EarthSpaceMapping.spaceToWorld(pxReal, pyReal, pzReal, SpaceWorld.j2000Seconds());
            int planetX = (int) worldPos[0];
            int planetZ = (int) worldPos[2];
            int surfaceY = PlanetDimensions.surfaceY(player, 3, planetX, planetZ);

            ServerLevel overworld = player.server.overworld();
            SpacePreloader.preload(overworld, new BlockPos(planetX, surfaceY, planetZ));
            Vec3 pos = new Vec3(planetX + 0.5, surfaceY, planetZ + 0.5);
            sendSync(player, pos);
            PENDING.put(id, new PendingTransition(Level.OVERWORLD, pos, 3,
                    player.tickCount + DELAY_TICKS));
        }
    }

    private static boolean executePending(ServerPlayer player, UUID id) {
        PendingTransition pending = PENDING.get(id);
        if (pending == null) return false;
        if (player.tickCount < pending.executeTick()) return true;

        PENDING.remove(id);
        if (pending.planetIndex() >= 0) {
            PlanetDimensions.teleportToPlanetSurface(player, pending.planetIndex(),
                    (int) pending.pos().x(), (int) pending.pos().z());
        } else {
            ServerLevel space = player.server.getLevel(PlanetDimensions.SPACE);
            if (space == null) return true;
            DimensionTransition transition = new DimensionTransition(
                    space, pending.pos(), player.getDeltaMovement(), player.getYRot(), player.getXRot(),
                    DimensionTransition.DO_NOTHING);
            player.changeDimension(transition);
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
