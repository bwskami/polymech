package com.mss.polymech.mps.physical.physical_body;

import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 客户端镜像物理体 —— <b>与
 * {@code org.polaris2023.mps.physical.physical_body.ClientPhysicalBody} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 客户端那份"服务端物理体的影子"：位置由服务端权威，本地只负责**当一棵推不动的碰撞墙**，
 * 并让站在上面的玩家被带着走。
 *
 * <h2>为什么是这个形态（照 space 0.1.3，逐条都有理由）</h2>
 * <ol>
 *   <li><b>运动学体</b>（构造传 {@code kinematic = true} → {@code KINEMATIC_POSITION}）：
 *       无限质量，本地谁也推不动它。若建成动态体，玩家一顶就会在本地被推开，
 *       随后被服务端同步/位置修正拽回 —— <b>那股"拽回"就是把玩家弹飞的冲量</b>。
 *       运动学体没有这个问题。</li>
 *   <li><b>位姿经插值目标推进，不是 {@code setPos}</b>：{@link #onMoveSync} 只记
 *       "从当前位姿 → 新目标"，{@link #tickMovePos} 每个物理子步推进 {@link #MOVE_STEP}（0.2）。
 *       直接 {@code setPos} 会把服务端 20Hz 的包变成位置台阶，运动的船一顿一顿；
 *       而 {@code setNextKinematicPosition} 让求解器能读出**运动速度** —— 站在船上的玩家
 *       才会被带走（瞬移的运动学体推不动任何东西）。</li>
 *   <li><b>插值接在世界的 tick listener 上</b>（{@link #attachToWorld}），不是客户端 tick：
 *       物理子步是 100Hz，接在 20Hz 的 tick 上会让插值只在四分之一的时间推进。</li>
 *   <li><b>{@link #onRemovedFromWorld} 必须摘掉 listener</b>：世界对象可能比体活得久，
 *       忘了摘就是"已销毁的体还在被推进"。</li>
 * </ol>
 *
 * <h2>尚未移植（不猜、不写半成品）</h2>
 * space 那份 493 行里有约一半是**渲染**（{@code render} / {@code buildRegionVertexBuffer} /
 * {@code buildFrictionBuffer} / 每 64³ 子块一份 {@code VertexBuffer} / 视锥剔除），
 * 它依赖 space 自己的 {@code SpaceModVertexFormats} 与整套渲染管线 —— 那属于本项目单列的
 * 客户端渲染项（对应文档 F7：按子区块烘焙 + 视锥剔除），将在渲染轮次里一起做。
 * <b>本类先把决定物理行为的核心写完整</b>：镜像体、运动学插值、生命周期。
 */
public class ClientPhysicalBody extends PhysicalBody {

    /** 一次同步的插值起点/终点，以及推进进度。 */
    private final Vector3d syncFrom = new Vector3d();
    private final Vector3d syncTo = new Vector3d();
    private double syncProgress = 1.0;

    /**
     * 每个物理子步推进的插值比例 —— 与 MPS 的 {@code MOVE_STEP} 同值。
     * <p>配合 100Hz 子步，一个 20Hz 的服务端包在 5 个子步内被平滑走完。</p>
     */
    private static final double MOVE_STEP = 0.2;

    private final Runnable moveTick = this::tickMovePos;

    public ClientPhysicalBody(Vector3d pos, Quaterniond rotation) {
        super(pos, rotation);
    }

    public ClientPhysicalBody(Vector3d pos, Quaterniond rotation, UUID uuid) {
        super(pos, rotation);
        this.uuid = uuid;
    }

    /** 客户端镜像体专用构造：{@code kinematic = true}（见类注释第 1 条）。 */
    public ClientPhysicalBody(ResourceLocation level, Vector3d pos, Quaterniond rotation, UUID uuid) {
        super(level, pos, rotation, uuid, true);
    }

    @Override
    public void attachToWorld(PhysicalWorld physicalWorld) {
        super.attachToWorld(physicalWorld);
        physicalWorld.addTickListener(this.moveTick);
    }

    @Override
    public void onRemovedFromWorld() {
        PhysicalWorld world = this.getPhysicalWorld();
        if (world != null) {
            world.removeTickListener(this.moveTick);
        }
        super.onRemovedFromWorld();
    }

    /**
     * 服务端位姿到达：记下"从当前位姿插值到新目标"。
     *
     * <p>起点取**当前实际位姿**而不是上一次目标 —— 包可能丢/迟到，从实际位姿起步才不会跳。</p>
     */
    @Override
    public void onMoveSync(Vector3d target) {
        Vector3d current = this.getRigidBody().getPos();
        this.syncFrom.set(current != null && current.isFinite() ? current : target);
        this.syncTo.set(target);
        this.syncProgress = 0.0;
    }

    /** 每个物理子步推进一次插值（由世界的 tick listener 调用）。 */
    private void tickMovePos() {
        if (this.syncProgress >= 1.0) {
            return;
        }
        this.syncProgress = Math.min(1.0, this.syncProgress + MOVE_STEP);
        Vector3d pos = new Vector3d(this.syncFrom).lerp(this.syncTo, this.syncProgress);
        this.setNextKinematicPosition(pos);
    }
}
