package com.mss.polymech.mps.physical.physical_world;

import com.mss.polymech.mps.network.packet.SyncPhysicalBodyBlockUpdate;
import com.mss.polymech.mps.physical.manger.PhysicalChunkManager;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_body.ServerPhysicalBody;
import com.mss.polymech.mps.rapier.helper.ColliderBody;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端物理世界 —— <b>与
 * {@code org.polaris2023.mps.physical.physical_world.ServerPhysicalWorld} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 在 {@link PhysicalWorld} 之上补三件<b>只在服务端成立</b>的事：
 * <ol>
 *   <li><b>按维度 id 的静态注册表</b>（{@link #PhysicalWorlds}）——
 *       让"这个维度有没有物理世界"变成 O(1) 查表；</li>
 *   <li><b>加体时把方块增量推给客户端</b>（{@link #addPhysicalBody}）——
 *       2×2×2 个 64³ 子块各发一份，客户端才有东西可渲染；</li>
 *   <li><b>跨维度搬迁</b>（{@link #dimensionLeapPhysicalBody}）——
 *       这是"地表 ↔ 太空"切换的底层动作。</li>
 * </ol>
 *
 * <h2>为什么"加体"要立刻推方块（照 space 0.1.3）</h2>
 * 客户端建出运动学镜像时是<b>空壳</b>（{@code SyncPhysicalBodyCreate} 只带位姿）。
 * 方块状态<b>不会自己过去</b> —— 必须由服务端在同一个时机把这些子块推过去。
 * 这里发的是 {@code x,y,z ∈ [−1,0]} 八个子块，正是 128³ 地皮被切成
 * 2×2×2 后的全部（{@code ProjectionManager} 里的 {@code chunkOffset = 1} 与之对应）。
 *
 * <h2>跨维度搬迁的六步为什么不换顺序</h2>
 * <ol>
 *   <li>{@code physicalBodies.remove} —— 先从旧世界的集合摘掉，
 *       否则 {@code addPhysicalBody} 会因"已存在"而拒绝；</li>
 *   <li>逐个 {@code extractColliderBody}（复制进分离竞技场再从世界移除）+ 解除碰撞体反查 ——
 *       反查表必须先清，否则会留下指向已移除碰撞体的僵尸条目；</li>
 *   <li>{@code extractRigidBody} —— 刚体最后摘（碰撞体挂在它上面）；</li>
 *   <li>{@code setLevel}/{@code setPhysicalWorld} —— 换归属；</li>
 *   <li>{@code physicalWorld.addPhysicalBody} —— 在新世界重建（会走上面那条推方块的分支）；</li>
 *   <li>最后才写回位姿与速度 —— <b>必须在重建之后</b>，
 *       否则刚体创建时的初值会把这些覆盖掉。</li>
 * </ol>
 * 判据用 {@code !physicalWorld.level.equals(this.level)}：<b>同维度搬迁直接返回 false</b>，
 * 否则会把自己摘掉再插回、白白重建一次刚体（碰撞体句柄变了，客户端镜像会跳）。
 */
public class ServerPhysicalWorld extends PhysicalWorld {

    private static final Map<ResourceLocation, ServerPhysicalWorld> PhysicalWorlds = new HashMap<>();
    protected final PhysicalChunkManager chunkManager;

    private ServerPhysicalWorld(ResourceLocation level, Vector3d g) {
        super(level, g);
        this.chunkManager = new PhysicalChunkManager(level, this);
    }

    public static void init() {
        PhysicalWorlds.clear();
    }

    public static ServerPhysicalWorld getPhysicalWorld(ResourceLocation resourceLocation) {
        return PhysicalWorlds.get(resourceLocation);
    }

    public static ServerPhysicalWorld getPhysicalWorld(Level level) {
        return getPhysicalWorld(level.dimension().location());
    }

    public static List<ServerPhysicalWorld> getAllPhysicalWorld() {
        return new ArrayList<>(PhysicalWorlds.values());
    }

    /** 加体：先走基类（挂刚体/碰撞体），再给服务端物理体补推方块增量包。 */
    @Override
    public boolean addPhysicalBody(PhysicalBody physicalBody) {
        if (!super.addPhysicalBody(physicalBody)) {
            return false;
        }
        if (physicalBody instanceof ServerPhysicalBody) {
            ServerLevel serverLevel = this.getServerLevel();
            if (serverLevel == null) {
                return true;
            }
            for (int x = -1; x <= 0; x++) {
                for (int y = -1; y <= 0; y++) {
                    for (int z = -1; z <= 0; z++) {
                        PacketDistributor.sendToPlayersInDimension(serverLevel,
                                new SyncPhysicalBodyBlockUpdate(physicalBody.getUuid(), new Vector3i(x, y, z),
                                        physicalBody.getBlockStateChunkInt(x, y, z)));
                    }
                }
            }
        }
        return true;
    }

    private ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, this.level));
    }

    public static ServerPhysicalWorld newPhysicalWorld(ResourceLocation serverLevel, Vector3d g) {
        ServerPhysicalWorld serverPhysicalWorld = new ServerPhysicalWorld(serverLevel, g);
        PhysicalWorlds.put(serverLevel, serverPhysicalWorld);
        return serverPhysicalWorld;
    }

    /** 跨维度搬迁：位姿/转速沿用当前值。 */
    public boolean dimensionLeapPhysicalBody(PhysicalBody physicalBody, PhysicalWorld physicalWorld) {
        return this.dimensionLeapPhysicalBody(physicalBody, physicalWorld, physicalBody.getPos(),
                physicalBody.getRotation(), physicalBody.getLinvel(), physicalBody.getAngvel());
    }

    /** 跨维度搬迁的六步（顺序见类注释）。同维度直接返回 false。 */
    public boolean dimensionLeapPhysicalBody(PhysicalBody physicalBody, PhysicalWorld physicalWorld,
                                             Vector3d pos, Quaterniond rotate, Vector3d linvel, Vector3d angvel) {
        if (physicalWorld.level.equals(this.level) || !this.physicalBodies.remove(physicalBody)) {
            return false;
        }

        ColliderBody[][][] colliderBodies = physicalBody.getColliderBodies();
        if (colliderBodies != null) {
            for (ColliderBody[][] layer : colliderBodies) {
                for (ColliderBody[] row : layer) {
                    for (ColliderBody collider : row) {
                        if (collider != null) {
                            this.physicalBodyByCollider.remove(collider);
                            this.extractColliderBody(collider);
                        }
                    }
                }
            }
        }

        RigidBody rigidBody = physicalBody.getRigidBody();
        if (rigidBody != null) {
            this.extractRigidBody(rigidBody);
        }

        physicalBody.setLevel(physicalWorld.getLevel());
        physicalBody.setPhysicalWorld(physicalWorld);
        physicalWorld.addPhysicalBody(physicalBody);
        // 位姿与速度必须在重建之后写回（见类注释）
        physicalBody.setPos(pos);
        physicalBody.setRotation(rotate);
        physicalBody.setLinSpeed(linvel);
        physicalBody.setAngSpeed(angvel);
        return true;
    }

    public PhysicalChunkManager getChunkManager() {
        return this.chunkManager;
    }
}
