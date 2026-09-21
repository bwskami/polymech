package com.mss.polymech.mps.event;

import com.mss.polymech.mps.kelvin.physical.celestial_world.ServerCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.physical.manger.ProjectionManager;
import com.mss.polymech.mps.physical.physical_world.PhysicalBodyWorldData;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import com.mss.polymech.mps.thread.ServerCollisionPhysicalThread;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.joml.Vector3d;

/**
 * 碰撞物理的服务端生命周期 —— <b>与
 * {@code org.polaris2023.mps.event.CollisionPhysicalServerAction} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 开服：为每个维度建物理世界（并算重力）→ 初始化投影维度 →
 * 从存档重建物理体 → **启动物理线程**。
 * 停服：停线程 → 把每个维度的体存回存档。
 *
 * <h2>重力的三级推导（本类最容易被忽略、也最容易错的一环）</h2>
 * <pre>
 *   太空维度       → 0                      （真空，天体引力由 kelvin 单独处理）
 *   行星地表维度   → (0, −CelestialWorld.G, 0)  ← G 是**绝对重力加速度 m/s²**
 *   其它维度       → (0, −9.8, 0)            （原版主世界/下界/末地等）
 * </pre>
 * <b>{@code G} 是绝对值不是倍数</b>（数据包 {@code world/*.json} 的 {@code gravity}：
 * 地球 9.807、火星 3.72076）。把倍数当绝对值用就是"火星重力变成地球的 3.72 倍"，
 * 而且只在行星地表才显形，极难定位。
 *
 * <h2>为什么"先建世界、再 init 投影、最后读档"，顺序不能换</h2>
 * <ol>
 *   <li>先建世界：读档要往世界里 {@code addPhysicalBody}；</li>
 *   <li>再 {@code ProjectionManager.init}：读档路径 {@code readProjectionData}
 *       要访问投影维度的地皮（{@code projectionLevel} 为 null 会 NPE）；</li>
 *   <li>最后读档：此时世界的刚体系统与投影维度都已就绪。</li>
 * </ol>
 *
 * <p>{@link EventPriority#LOW}：本逻辑依赖 kelvin 的
 * {@code OrbitPhysicalServerAction}（它用了 {@code HIGH}）已经建好
 * {@code ServerSpaceWorld}/{@code ServerCelestialWorld} —— 重力推导要读它们。
 * 优先级写反就会全部落进 {@code (0, −9.8, 0)} 这个兜底分支。</p>
 *
 * <p>地表维度还要 {@code setMinY(minBuildHeight)}：在建筑下限挂一层朝上的半空间，
 * 船掉出世界时有东西接住（太空维度不挂 —— 那里本来就该是无底的）。</p>
 *
 * <p><b>克隆差异（已记录）</b>：MPS 停服时还调 {@code PhysicalSelectionManager.clearAll()}
 * 清理选体魔杖的选择态。该子系统（选体魔杖）未移植，故这一行未落地；
 * 其余逐行同形。</p>
 */
public class CollisionPhysicalServerAction {

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerStart(ServerStartedEvent event) {
        ServerPhysicalWorld.init();
        event.getServer().getAllLevels().forEach(serverLevel -> {
            if (serverLevel.dimension().location().equals(ProjectionManager.PROJECTION_WORLD)) {
                return;
            }
            Vector3d gravity;
            if (ServerSpaceWorld.isSpaceWorld(serverLevel)) {
                gravity = new Vector3d();
            } else {
                ServerCelestialWorld celestialWorld = ServerCelestialWorld.getCelestialWorld(serverLevel);
                if (celestialWorld != null) {
                    // G 是绝对重力加速度（见类注释）
                    gravity = new Vector3d(0.0, -celestialWorld.G, 0.0);
                } else {
                    gravity = new Vector3d(0.0, -9.8, 0.0);
                }
            }

            ServerPhysicalWorld.newPhysicalWorld(serverLevel.dimension().location(), gravity);
            ServerPhysicalWorld physicalWorld = ServerPhysicalWorld.getPhysicalWorld(serverLevel);
            if (physicalWorld != null && !ServerSpaceWorld.isSpaceWorld(serverLevel)) {
                physicalWorld.setMinY((double) serverLevel.getMinBuildHeight());
            }
            // 诊断日志：重力三级推导是"静默错"的高发点（把 G 的倍数当绝对值用，
            // 只在行星地表显形且像物理 bug），所以把每个维度的结果直接打出来。
            com.mss.polymech.Polymech.LOGGER.info(
                    "[MPS] 物理世界已创建：{} 重力 = ({}, {}, {}) m/s²",
                    serverLevel.dimension().location(),
                    gravity.x, gravity.y, gravity.z);
        });

        ProjectionManager.init(event.getServer());

        event.getServer().getAllLevels().forEach(serverLevel -> {
            if (serverLevel.dimension().location().equals(ProjectionManager.PROJECTION_WORLD)) {
                return;
            }
            ServerPhysicalWorld physicalWorld = ServerPhysicalWorld.getPhysicalWorld(serverLevel);
            if (physicalWorld != null) {
                PhysicalBodyWorldData.get(serverLevel).loadBodies(physicalWorld);
            }
        });

        ServerCollisionPhysicalThread.startThread();
    }

    @SubscribeEvent
    public static void onServerStop(ServerStoppingEvent event) {
        // 先停线程再存档：天体侧同理（见 OrbitPhysicalServerAction 的类注释）
        ServerCollisionPhysicalThread.stopThread();
        event.getServer().getAllLevels().forEach(serverLevel -> {
            ServerPhysicalWorld physicalWorld = ServerPhysicalWorld.getPhysicalWorld(serverLevel);
            if (physicalWorld != null) {
                PhysicalBodyWorldData.get(serverLevel).saveBodies(physicalWorld.getAllPhysicalBody());
            }
        });
    }
}
