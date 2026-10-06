package com.mss.polymech.space;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/**
 * <b>服务端（及通用代码）"瞄准射线的起点"的唯一取法</b>。
 *
 * <p>服务端<b>读不到客户端相机</b>，所以不能像客户端那样直接取
 * {@code gameRenderer.getMainCamera().getPosition()}（见
 * {@code com.mss.polymech.client.physics.ClientViewOrigin}）。
 * 它能做的是：拿同步来的 {@code facing/left} 走<b>同一份算式</b>重算锚点 ——
 * {@link SpacePlayerData#cameraAnchor}。这就是本类存在的唯一理由：
 * 让"起点"这个概念在两端同源，而不是一边读相机、一边读眼睛。</p>
 *
 * <h2>为什么非改不可（2026-10-06）</h2>
 * 太空第一人称相机离眼睛实测 <b>1.313~1.430 格</b>（主世界 0.000），
 * 而服务端 {@code SpaceBlockPlacement}（放方块）与 {@code PhysgunItem}（牵引枪抓取/光束起点）
 * 原先都从 {@code player.getEyePosition()} 出发 ⇒ 与准星平行错开一格多 ⇒
 * <b>放错格/放错面、抓错点</b>，且"只有太空不对"。
 *
 * <p>与 {@code SpacePlayerData.cameraAnchor} 的注释一样，这里诚实记录残留误差：
 * 服务端用的是 ≤2 tick 前的朝向样本，重算出的锚点与客户端当帧相机差
 * "头部偏移被转过多少"，量级约 0.01~0.22 格，比它替掉的 1.3~1.4 格系统性错位小一个量级。</p>
 */
public final class SpaceAimOrigin {

    private SpaceAimOrigin() {
    }

    /**
     * 射线起点：太空里 = 相机锚点；其它维度 / 未初始化 = 眼睛（与旧行为逐位一致）。
     */
    public static Vec3 of(Entity entity) {
        return of(entity, 1.0F);
    }

    /** {@code partialTick} 版（服务端 tick 里用 1.0F；实体插值调用方自己给）。 */
    public static Vec3 of(Entity entity, float partialTick) {
        Vector3d anchor = SpacePlayerData.cameraAnchor(entity, partialTick, entity.getEyeHeight(), new Vector3d());
        if (anchor != null) {
            return new Vec3(anchor.x, anchor.y, anchor.z);
        }
        return entity.getEyePosition(partialTick);
    }

    /**
     * 该起点是否真的来自"相机锚点"（而不是退回的眼睛）。
     *
     * <p>诊断用：太空里应为 {@code true}。若为 {@code false}，说明同步的朝向还没到
     * （刚进太空的第一帧）或锚点算不出来 —— 那种情况下起点会退回眼睛，
     * 也就是本次修复之前的行为。</p>
     */
    public static boolean isCameraAnchored(Entity entity, float partialTick) {
        return SpacePlayerData.cameraAnchor(entity, partialTick, entity.getEyeHeight(), new Vector3d()) != null;
    }
}
