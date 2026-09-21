package com.mss.polymech.mps.kelvin.network.event;

import com.mss.polymech.mps.kelvin.network.packet.SyncCelestialBodyMoveBatch;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ServerCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent.Pre;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 天体位姿的每 tick 广播 —— <b>与
 * {@code org.cn_grass_block.kelvin.network.event.CelestialBodyMoveSyncEvent} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 每个服务端维度 tick 前，把这个维度对应的太空世界里<b>所有</b>天体的位姿
 * 打包发给该维度的每个玩家。
 *
 * <h2>为什么需要"地表维度也能找到太空世界"这一层回退</h2>
 * 玩家大多数时候<b>站在行星地表</b>（如 {@code poly_mech:mars}），而不是在太空维度里。
 * 但天上要看到的行星位置来自<b>太空世界</b>（{@code poly_mech:space}）。
 * 于是：
 * <pre>
 *   ServerSpaceWorld.getSpaceWorld(level)       → 直接命中（玩家在太空里）
 *   否则 ServerCelestialWorld.getCelestialWorld(level).SpaceWorldID → 回退到所属宇宙
 * </pre>
 * 少了这条回退，站在地表的玩家永远收不到位姿更新 —— 表现是"在行星表面看天空，
 * 行星全都不动"，而一进太空就正常。这是最容易漏掉的一环。
 *
 * <h2>为什么是"每 tick 全量"而不是增量/按需</h2>
 * space 的选择是<b>简单优先</b>：天体数量是个位数到几十（本项目 20），
 * 全量一份 ≈ 1.2 KB/tick/玩家，代价可接受；换来的是<b>没有状态机</b> ——
 * 不需要记录"客户端已知哪些天体的哪个版本"，也就不会出现"漏发一次导致永久不同步"。
 * 而 {@code ClientSpaceWorld.syncMoveData()} 那层缓冲把网络抖动与渲染帧率解耦，
 * 所以"每 tick 全量"在观感上不会顿。
 *
 * <p>发包对象是 {@code serverLevel.players()}（该维度里的玩家），
 * 不是全服广播 —— 别的维度不需要这份数据。</p>
 */
public class CelestialBodyMoveSyncEvent {

    @SubscribeEvent
    public static void onWorldTick(Pre event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ServerSpaceWorld spaceWorld = ServerSpaceWorld.getSpaceWorld(serverLevel);
            ServerCelestialWorld celestialWorld = ServerCelestialWorld.getCelestialWorld(serverLevel);
            if (spaceWorld == null && celestialWorld != null) {
                // 站在行星地表：回退到它所属的太空世界（见类注释）
                spaceWorld = ServerSpaceWorld.getSpaceWorld(celestialWorld.SpaceWorldID);
            }

            if (spaceWorld != null) {
                List<SyncCelestialBodyMoveBatch.Entry> moves = new ArrayList<>();
                for (CelestialBody celestialBody : spaceWorld.getAllCelestialBody()) {
                    moves.add(new SyncCelestialBodyMoveBatch.Entry(
                            celestialBody.getName(), celestialBody.getPos(), celestialBody.getRotate()));
                }

                if (!moves.isEmpty()) {
                    for (ServerPlayer serverPlayer : serverLevel.players()) {
                        PacketDistributor.sendToPlayer(serverPlayer, new SyncCelestialBodyMoveBatch(moves));
                    }
                }
            }
        }
    }
}
