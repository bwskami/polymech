package com.mss.polymech.mps.kelvin.network.event;

import com.mss.polymech.mps.kelvin.network.packet.SyncCelestialBodyCreate;
import com.mss.polymech.mps.kelvin.network.packet.SyncCelestialWorldCreate;
import com.mss.polymech.mps.kelvin.network.packet.SyncSpaceWorldCreate;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.celestial_world.CelestialWorld;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ServerCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import com.mss.polymech.mps.space.network.ReliableCreateSender;import com.mss.polymech.mps.space.network.packet.SyncCreateEnd;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 玩家加入维度时的完整状态下发 —— <b>与
 * {@code org.cn_grass_block.kelvin.network.event.SpaceWorldUpdateSyncEvent} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 玩家进入一个维度时，把这个维度的<b>全部客户端宇宙状态</b>按依赖顺序发一遍，
 * 最后发一个 {@link SyncCreateEnd} 摘要让客户端自检。
 *
 * <h2>发送顺序是依赖顺序，不能换</h2>
 * <pre>
 *   1. SyncSpaceWorldCreate        先有世界，天体才有地方放
 *   2. SyncCelestialBodyCreate ×N  每个天体一份（显示 + 缓冲两份对象）
 *   3. SyncCelestialWorldCreate    地表维度参数；客户端要<b>按名字在太空世界里找行星</b>，
 *                                  所以必须排在天体之后
 *   4. SyncCreateEnd               摘要核对
 * </pre>
 * 第 3 步的依赖是硬的：客户端处理它时会
 * {@code ClientSpaceWorld.getSpaceWorld().getCelestialBody(bodyName) instanceof Planet}，
 * 天体还没送到就查不到，地表参数会被静默丢弃。
 *
 * <h2>用 {@code EntityJoinLevelEvent} 而不是"登录事件"</h2>
 * 换维度（地表 ↔ 太空）也会触发本事件，于是<b>换维度后的重发不需要额外机制</b>。
 * 这正是 space 用它取代"旧做法：换维度后延迟 2 秒补发"的原因 ——
 * 延迟补发是在赌客户端什么时候准备好了，而这里是"每次进入维度都重新同步一遍"，
 * 配合 {@link ReliableCreateSender} 的重发，最终一致。
 *
 * <h2>三处兜底（都是必要的，不是防御性编程）</h2>
 * <ul>
 *   <li><b>地表维度回退到所属太空世界</b>：玩家站在行星地表时自身维度不是太空维度，
 *       但天上要看到的行星来自所属宇宙（同 {@code CelestialBodyMoveSyncEvent}）。</li>
 *   <li><b>{@code ServerSpaceWorld} 为 null 也要发一个"没有太空世界"的包</b>：
 *       客户端可能刚从一个太空维度走出来，本地还留着旧世界；
 *       不发这一份，它就会继续渲染已经不存在的宇宙（对应
 *       {@code SyncSpaceWorldCreate} 的 {@code init()} 分支）。</li>
 *   <li><b>没有天体世界也要发 {@code SyncCelestialWorldCreate()}（空构造）</b>：
 *       同理，让客户端 {@code ClientCelestialWorld.init()} 清掉上一颗行星的重力与换算参数。
 *       少了它，走出地表后重力仍是那颗行星的。</li>
 * </ul>
 *
 * <p>全部走 {@link ReliableCreateSender#send}（普通优先级，5 次重发）。
 * {@code SyncCreateEnd} 里的 {@code hasPhysicalWorld} 把物理世界的有无也纳入对账。</p>
 *
 * <p><b>与本项目的一处差异（已记录）</b>：space 用
 * {@code ServerPhysicalWorld.getPhysicalWorld(level) != null} 判断物理世界有无。
 * 克隆层的服务端物理世界（{@code ProjectionManager → ServerPhysicalBody →
 * ServerPhysicalWorld}）按 {@code docs/mps-clone-plan.md} §11/§12 的结论
 * <b>暂缓</b>（它与项目既有的投影/体层是重复能力，S6 才二选一），
 * 所以这里改用<b>项目既有的</b>物理世界入口
 * {@link PhysicsWorldManager#terrainIfPresent} 回答同一个问题 ——
 * 该字段只参与客户端自检日志，语义完全一致。</p>
 */
public class SpaceWorldUpdateSyncEvent {

    @SubscribeEvent
    public static void onPlayerLoggedIn(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            ServerLevel serverLevel = serverPlayer.serverLevel();
            ServerSpaceWorld serverSpaceWorld = ServerSpaceWorld.getSpaceWorld(serverLevel);
            ServerCelestialWorld serverCelestialWorld = ServerCelestialWorld.getCelestialWorld(serverLevel);
            if (serverSpaceWorld == null && serverCelestialWorld != null) {
                serverSpaceWorld = ServerSpaceWorld.getSpaceWorld(serverCelestialWorld.SpaceWorldID);
            }

            // 1) 世界（可能为 null → 客户端 init()）
            ServerSpaceWorld syncSpaceWorld = serverSpaceWorld;
            ReliableCreateSender.send(serverPlayer, id -> new SyncSpaceWorldCreate(id, syncSpaceWorld));

            // 2) 天体
            if (syncSpaceWorld != null) {
                for (CelestialBody celestialBody : syncSpaceWorld.getAllCelestialBody()) {
                    ReliableCreateSender.send(serverPlayer, id -> new SyncCelestialBodyCreate(id, celestialBody));
                }
            }

            // 3) 地表维度参数（必须在天体之后，见类注释）
            if (serverCelestialWorld != null) {
                CelestialWorld.posShadowData posShadowData = serverCelestialWorld.getPosShadowData();
                ReliableCreateSender.send(serverPlayer, id -> new SyncCelestialWorldCreate(
                        id,
                        serverCelestialWorld.celestialBody.getName(),
                        serverCelestialWorld.WorldID,
                        serverCelestialWorld.SpaceWorldID,
                        posShadowData.center,
                        posShadowData.rotate,
                        posShadowData.longitude_length,
                        serverCelestialWorld.G,
                        serverCelestialWorld.Height,
                        serverCelestialWorld.MinY));
            } else {
                ReliableCreateSender.send(serverPlayer, SyncCelestialWorldCreate::new);
            }

            // 4) 摘要对账
            boolean hasSpace = syncSpaceWorld != null;
            int bodyCount = syncSpaceWorld == null ? 0 : syncSpaceWorld.getAllCelestialBody().size();
            String celestialName = serverCelestialWorld == null ? null : serverCelestialWorld.celestialBody.getName();
            // 对账字段必须由**客户端将要检查的那套子系统**产出：
            // SyncCreateEnd.verify 读的是 ClientPhysicalWorld（克隆层），
            // 所以这里也必须读克隆层的 ServerPhysicalWorld。
            // 早先这里读项目既有的 PhysicsWorldManager.terrainIfPresent ——
            // 两套子系统只要有一处不同步就会误报"切世界数据同步不完整"。
            boolean hasPhysical = ServerPhysicalWorld.getPhysicalWorld(serverLevel) != null;
            ReliableCreateSender.send(serverPlayer,
                    id -> new SyncCreateEnd(hasSpace, bodyCount, celestialName, hasPhysical));
        }
    }
}
