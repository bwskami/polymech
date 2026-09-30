package com.mss.polymech.mps.kelvin.event;

import com.mss.polymech.mps.kelvin.network.packet.SyncCelestialBodyCreate;
import com.mss.polymech.mps.kelvin.physical.CelestialBodyForce;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Aircraft;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ServerCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent.Pre;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/**
 * 物理体 ↔ 天体 的桥 —— <b>与
 * {@code org.cn_grass_block.kelvin.event.PhysicalBodySpaceEvent} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责（三件事，缺一件"船进太空"就不成立）</h2>
 * <ol>
 *   <li><b>镜像</b>：太空维度里的每个物理体都要有一个对应的
 *       {@link Aircraft} 天体，并发给客户端 —— 否则<b>船在太空里别人看不见</b>
 *       （客户端只认 {@code ClientSpaceWorld} 里的天体，不认物理体）。</li>
 *   <li><b>受力镜像</b>：把物理体刚体上的力（推进器、缆绳…）转成
 *       {@link CelestialBodyForce} 加到天体上，名字前缀 {@code physical_body:}
 *       —— 于是<b>船能用自己的推进器在轨道上机动</b>，而不是只有天体引力。</li>
 *   <li><b>越界升空</b>：物理体在地表维度升到 {@code CelestialWorld.Height} 以上时，
 *       用 {@link ServerCelestialWorld#getSpacePosFromWorldPos} 换算到太空坐标，
 *       再 {@code dimensionLeapPhysicalBody} 把它<b>搬进太空维度</b>并登记为 Aircraft
 *       —— 这就是"从行星表面飞出去"的实现。</li>
 * </ol>
 *
 * <h2>为什么要"拉回"（第 (b) 段的冲量）</h2>
 * 天体位置由 kelvin 的 N 体积分推进，而物理体由 Rapier 推进，<b>两者是两套求解器</b>。
 * 若不施加约束，同一个"船"的两份表示会越飘越远（客户端看到的 Aircraft 与服务端
 * 物理体的位置分叉）。所以当距离超过 {@link #SYNC_DISTANCE_THRESHOLD} 时，
 * 按超出量成比例地给物理体一个冲量把它<b>拉向天体</b> ——
 * 本质是一个软约束（弹簧），增益 {@link #SYNC_IMPULSE_GAIN} 就是弹簧刚度。
 * 它不追求零误差，只保证两支不会散开。
 *
 * <h2>两个方向的写法差别（别对称地改）</h2>
 * <ul>
 *   <li><b>姿态只单向同步</b>：{@code aircraft.rotateTo(physicalBody.getRotation())}
 *       —— 天体跟随物理体。反过来会把船的姿态锁死在天体积分结果上。</li>
 *   <li><b>力的镜像要跳过 {@code aircraft:} 前缀</b>：那些力是<b>从天体这边</b>
 *       加进去的（例如引力/推进），再镜像回去就形成自激回路（力越加越大）。</li>
 * </ul>
 *
 * <h2>为什么在 {@code ServerTickEvent.Post} 而不是 LevelTick</h2>
 * 这一段要遍历<b>所有</b>太空世界（与玩家在哪个维度无关）——
 * 船进了太空就必须一直被镜像，哪怕没有玩家在太空维度里。
 * 而第二段（越界升空）是<b>逐维度</b>的事，所以用 {@code LevelTickEvent.Pre}。</p>
 */
public class PhysicalBodySpaceEvent {

    /** 超过这个距离就施加回拉冲量（软约束，见类注释）。 */
    private static final double SYNC_DISTANCE_THRESHOLD = 10.0;
    /** 回拉增益 = 弹簧刚度：冲量 = (距离 − 阈值) × 增益。 */
    private static final double SYNC_IMPULSE_GAIN = 100.0;

    @SubscribeEvent
    public static void onServerTick(Post event) {
        for (ServerSpaceWorld spaceWorld : ServerSpaceWorld.getAllSpaceWorld()) {
            ServerPhysicalWorld serverPhysicalWorld =
                    ServerPhysicalWorld.getPhysicalWorld(spaceWorld.WorldID);
            if (serverPhysicalWorld == null) {
                continue;
            }

            // (a) 镜像：没有对应 Aircraft 的物理体补一个
            for (PhysicalBody physicalBody : serverPhysicalWorld.getAllPhysicalBody()) {
                boolean hasAircraft = false;
                for (CelestialBody celestialBody : spaceWorld.getAllCelestialBody()) {
                    if (celestialBody instanceof Aircraft aircraft) {
                        hasAircraft = hasAircraft
                                || aircraft.getPhysicalBody().getUuid().equals(physicalBody.getUuid());
                    }
                }
                if (!hasAircraft) {
                    Aircraft aircraft = new Aircraft(physicalBody);
                    spaceWorld.putCelestialBody(aircraft);
                    PacketDistributor.sendToAllPlayers(new SyncCelestialBodyCreate(aircraft));
                }
            }

            // (b) 回拉 + 姿态 + 受力镜像
            for (CelestialBody celestialBody : spaceWorld.getAllCelestialBody()) {
                if (!(celestialBody instanceof Aircraft aircraft)) {
                    continue;
                }
                PhysicalBody physicalBody = aircraft.getPhysicalBody();
                if (physicalBody == null) {
                    continue;
                }

                // ★★ 2026-09-27（① 缩放方案 S2）：**边界换算**。
                //   `aircraft.getPos()` 是 kelvin 的**米**坐标，`physicalBody.getPos()` 是方块维度的**格**坐标。
                //   恒等约定（1 格 = 1 米）下两者同值，所以这个混算一直没暴露；一旦切成 1 格 = 10000 米，
                //   这里的差值会整体错 10⁴ 倍 ⇒ 弹簧会以 10⁴ 倍的幅度拽物理体。
                //   照 0.0.6 的做法**在边界上换算**（`ShipManger.java:122-124`：把 kelvin 的量换算回方块口径再喂给船）：
                Vector3d bodyPos = physicalBody.getPos();
                Vector3d toAircraft = SpaceWorld.toGame(aircraft.getPos().x, aircraft.getPos().y, aircraft.getPos().z)
                        .sub(bodyPos);
                // 阈值也按同一因子重标（它表达的是"物理距离"，不能随格子大小变形）。
                // 增益不用改：冲量 ∝ Δv，而 Δv 的换算已经包含在 (距离 − 阈值) 这一项里了。
                double blockPerMeter = SpaceWorld.toMc(1.0);
                double threshold = SYNC_DISTANCE_THRESHOLD * blockPerMeter;
                double distance = toAircraft.length();
                if (distance > threshold) {
                    double magnitude = (distance - threshold) * SYNC_IMPULSE_GAIN;
                    physicalBody.applyImpulse(toAircraft.normalize().mul(magnitude));
                }

                // 姿态单向：天体跟随物理体（见类注释）
                aircraft.rotateTo(physicalBody.getRotation());

                for (RigidBody.Force force : physicalBody.getRigidBody().getForcesSnapshot()) {
                    String name = force.getName();
                    // 跳过天体侧加进去的力，否则自激（见类注释）
                    if (name == null || !name.startsWith("aircraft:")) {
                        // ★ 边界换算（同 (b) 段）：物理体的力是"格·kg/s²"，kelvin 的力是"米·kg/s²"（牛顿）。
                        //   照 `ShipManger.java:122-124` 的同一个方向：MPS ⇒ kelvin 要**乘回** ZOOM。
                        Vector3d f = force.getForce();
                        aircraft.addForce(new CelestialBodyForce(
                                "physical_body:" + (name == null ? "unknown" : name),
                                SpaceWorld.toSpace(f.x, f.y, f.z), force.getRemainingTime()));
                    }
                }
            }
        }
    }

    /** 地表维度：物理体升过 {@code CelestialWorld.Height} 就换算坐标、搬进太空维度。 */
    @SubscribeEvent
    public static void onWorldTick(Pre event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        ServerCelestialWorld celestialWorld = ServerCelestialWorld.getCelestialWorld(serverLevel);
        if (celestialWorld == null) {
            return;
        }
        ServerPhysicalWorld serverPhysicalWorld = ServerPhysicalWorld.getPhysicalWorld(serverLevel);
        if (serverPhysicalWorld == null) {
            return;
        }

        for (PhysicalBody serverPhysicalBody : serverPhysicalWorld.getAllPhysicalBody()) {
            if (serverPhysicalBody.getPos().y <= celestialWorld.Height) {
                continue;
            }
            ServerSpaceWorld spaceWorld = ServerSpaceWorld.getSpaceWorld(celestialWorld.SpaceWorldID);
            ServerPhysicalWorld spaceServerPhysicalWorld =
                    ServerPhysicalWorld.getPhysicalWorld(celestialWorld.SpaceWorldID);
            if (spaceWorld == null || spaceServerPhysicalWorld == null) {
                continue;
            }

            // 地表坐标 → 太空坐标（与 CelestialWorld 的换算互为逆运算）
            // ★ 边界换算（同 (b) 段）：`getSpacePosFromWorldPos` 给的是**米**，而目标物理世界用的是
            //   太空维度的**格**（缩放约定下 1 格 = 10000 米）—— 少这一下，船会落到 10⁴ 倍远处。
            Vector3d spacePosReal = celestialWorld.getSpacePosFromWorldPos(serverPhysicalBody.getPos(), 1.0F);
            Vector3d spacePos = SpaceWorld.toGame(spacePosReal.x, spacePosReal.y, spacePosReal.z);
            // 线速度/角速度**原样带过去**：地表↔太空那套映射是各向异性的（水平 1 格 = 200.2 m、
            // 竖直 1 格 = 9.9 m，见 SpaceMappingProbe 第 4 节），不存在一个正确的速度换算；
            // 0.0.6 在同类跃迁里是**清零**（`ShipManger.java:67-75` 的 `new Vector3d()`、玩家 `:48`）。
            // 这里保持与 0.1.3 克隆同形（原样带），要改成清零是一个**玩法决定**，单列。
            if (serverPhysicalWorld.dimensionLeapPhysicalBody(
                    serverPhysicalBody,
                    spaceServerPhysicalWorld,
                    spacePos,
                    serverPhysicalBody.getRotation(),
                    serverPhysicalBody.getLinvel(),
                    serverPhysicalBody.getAngvel())) {
                Aircraft aircraft = new Aircraft(serverPhysicalBody);
                spaceWorld.putCelestialBody(aircraft);
                PacketDistributor.sendToAllPlayers(new SyncCelestialBodyCreate(aircraft));
            }
        }
    }
}
