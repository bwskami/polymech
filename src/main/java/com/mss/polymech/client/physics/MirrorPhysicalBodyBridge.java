package com.mss.polymech.client.physics;

import com.mss.polymech.mps.physical.physical_body.ClientPhysicalBody;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.world.level.block.Block;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P0 镜像桥：把主动栈 {@link ClientPhysicsWorld.ClientBody} 镜像成 mps {@link PhysicalBody}，
 * 并 {@code addPhysicalBody} 进 mps {@link ClientPhysicalWorld}，让扫掠器与探针统一读 mps 层。
 *
 * <h2>坐标约定映射（P0 最大风险，务必仔细）</h2>
 * 两套物理体系的方块局部坐标约定<b>名字相同、基准不同</b>，镜像桥在这里显式换算：
 * <ul>
 *   <li><b>主动栈</b>（0.1.3）：刚体原点取<b>区域最小角</b>（整数），
 *       {@code BlockEntry.dx/dy/dz} =「方块 − 区域最小角」，方块世界位置 =
 *       {@code tickX/Y/Z + (dx,dy,dz)}（块占 {@code [x,x+1)}，中心在 {@code dx+0.5}）；</li>
 *   <li><b>mps</b>（0.5.0 同形克隆）：方块局部坐标 =「<b>中心在原点</b> −64..63」
 *       （{@code fillBlockState}/{@code getBlockStateInt} 内部 {@code +64}），
 *       方块世界位置 = {@code getPos() + (x,y,z)}（同样块占 {@code [x,x+1)}）。</li>
 * </ul>
 *
 * <p><b>换算（恒等映射 + 保持刚体原点，不是自创近似）</b>：验收要求
 * {@code mps.getPos() == ClientBody.tickX/Y/Z} —— 即<b>不搬动刚体原点</b>（仍落在区域最小角）。
 * 于是两种坐标系的整数格一一对应：mps 局部坐标 = {@code dx/dy/dz} 原样传入，
 * {@code fillBlockState} 内部的 {@code +64} 完成「中心原点」的存储；扫掠器的
 * {@code blockCenter = getPos() + R·(x+0.5)} 与主动栈渲染/碰撞的方块世界中心逐格一致。
 * 正常不会越界（建体区域 ≤ {@code PhysicsBodyTracker.MAX_REGION}=32 格 ⊂ [−64,63]）；
 * {@code fillBlockState} 内部仍会按 [−64,63] 钳制，作为兜底。</p>
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>{@code CREATE}：新建 mps {@link ClientPhysicalBody}（运动学镜像，本地推不动）→
 *       {@code fillBlockState} 全部方块 → {@code addPhysicalBody}；</li>
 *   <li>{@code UPDATE}（{@code applyUpdate} 位姿/速度）：{@code setPos}/{@code setRotation}/
 *       {@code setLinSpeed}/{@code setAngSpeed}；</li>
 *   <li>{@code REMOVE}：从 mps {@link ClientPhysicalWorld} {@code removePhysicalBody}。</li>
 * </ul>
 *
 * <p>方块编辑（破坏/放置）服务端以<b>同 id 整包重发 CREATE</b>（见
 * {@code PhysicsBodyTracker#refresh}），所以 {@link #onCreate} 对已存在的 id 做「先摘旧、再建新」，
 * 保证镜像体素与服务端快照始终一致。</p>
 */
public final class MirrorPhysicalBodyBridge {

    /** 主动栈 bodyId → mps 镜像体（按 id 反查，id 是主动栈唯一的权威键）。 */
    private static final Map<Long, PhysicalBody> MIRRORS = new ConcurrentHashMap<>();

    private MirrorPhysicalBodyBridge() {
    }

    /** CREATE：新建 mps 镜像体 + 填满方块 + 加进 mps ClientPhysicalWorld。 */
    public static void onCreate(ClientPhysicsWorld.ClientBody body) {
        if (body == null) {
            return;
        }
        long id = body.id();

        // 方块编辑以同 id 重发 CREATE：先摘掉旧镜像，避免旧体素残留。
        PhysicalBody old = MIRRORS.remove(id);
        if (old != null) {
            ClientPhysicalWorld w = ClientPhysicalWorld.getPhysicalWorld();
            if (w != null) {
                w.removePhysicalBody(old);
            }
        }

        ClientPhysicalWorld world = ClientPhysicalWorld.getPhysicalWorld();
        if (world == null) {
            // 本维度没有 mps 物理世界（WorldID == null 的维度）⇒ 没有镜像目标，跳过。
            return;
        }

        // 刚体原点保持 = 主动栈最小角（tickX/Y/Z）；方块局部坐标 = dx/dy/dz（恒等映射，见类注释）。
        Vector3d pos = new Vector3d(body.tickX(), body.tickY(), body.tickZ());
        Quaterniond rotation = new Quaterniond(body.qx(), body.qy(), body.qz(), body.qw());
        ClientPhysicalBody mirror = new ClientPhysicalBody(world.getLevel(), pos, rotation, new UUID(id, id));

        // 先 fillBlockState 全部方块，再 addPhysicalBody（契约：addPhysicalBody 前体素已就位）。
        for (ClientPhysicsWorld.BlockEntry e : body.blocks()) {
            mirror.fillBlockState(e.dx(), e.dy(), e.dz(), e.dx(), e.dy(), e.dz(), Block.getId(e.state()));
        }

        // 镜像体带速度：位姿一致之外，速度也同步（探针/载速与接触语义用）。
        mirror.setLinSpeed(new Vector3d(body.vx(), body.vy(), body.vz()));
        mirror.setAngSpeed(new Vector3d(body.avx(), body.avy(), body.avz()));

        if (world.addPhysicalBody(mirror)) {
            MIRRORS.put(id, mirror);
        }
    }

    /** UPDATE：把位姿/速度同步到 mps 镜像体。 */
    public static void onUpdate(ClientPhysicsWorld.ClientBody body) {
        if (body == null) {
            return;
        }
        PhysicalBody mirror = MIRRORS.get(body.id());
        if (mirror == null) {
            return; // CREATE 尚未镜像（或本维度无 mps 世界），跳过。
        }
        mirror.setPos(new Vector3d(body.tickX(), body.tickY(), body.tickZ()));
        mirror.setRotation(new Quaterniond(body.qx(), body.qy(), body.qz(), body.qw()));
        mirror.setLinSpeed(new Vector3d(body.vx(), body.vy(), body.vz()));
        mirror.setAngSpeed(new Vector3d(body.avx(), body.avy(), body.avz()));
    }

    /** REMOVE：从 mps ClientPhysicalWorld 摘除镜像体。 */
    public static void onRemove(long id) {
        PhysicalBody mirror = MIRRORS.remove(id);
        if (mirror == null) {
            return;
        }
        ClientPhysicalWorld world = ClientPhysicalWorld.getPhysicalWorld();
        if (world != null) {
            world.removePhysicalBody(mirror);
        }
    }

    /** 退出/换维度：摘除全部镜像体（与 {@code ClientPhysicsWorld.clear()} 同生命周期）。 */
    public static void clear() {
        ClientPhysicalWorld world = ClientPhysicalWorld.getPhysicalWorld();
        for (PhysicalBody mirror : MIRRORS.values()) {
            if (world != null) {
                world.removePhysicalBody(mirror);
            }
        }
        MIRRORS.clear();
    }
}
