package com.mss.polymech.client.physics;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * <b>客户端"瞄准射线的起点"的唯一取法 = 相机位置</b>（不是 {@code player.getEyePosition()}）。
 *
 * <h2>为什么（2026-10-06 实机定位，数字钉在这里）</h2>
 * 准星画的是<b>相机</b>的视线，而射线若从<b>玩家眼睛</b>出发，两者就只是
 * <b>平行错开</b>（方向一样、起点不同）——表现正是"瞄不准 / 得点它旁边的空处才生效"。
 * 实测（{@code [视线基]} 判据，{@code SpaceViewBasisProbe}）：
 *
 * <table>
 *   <tr><th></th><th>主世界</th><th>太空</th></tr>
 *   <tr><td>夹角（方向）</td><td>≈0</td><td>0.53°（roll≈±180° 时四元数 float 残差，非分叉）</td></tr>
 *   <tr><td><b>眼-相机距（起点）</b></td><td><b>0.000</b></td><td><b>1.313 ~ 1.430</b> ⚠️</td></tr>
 * </table>
 *
 * <p>太空那一格多的错位来自 {@code SpaceCameraMixin}：第一人称相机也被改成"身体坐标系里的头"
 * （身体 6DOF 翻转时"头上的点"不在世界 +Y 上），算式见
 * {@link com.mss.polymech.space.SpacePlayerData#cameraAnchor}。</p>
 *
 * <h2>上游依据（照抄优先）</h2>
 * space 的 {@code org.polaris2023.mps.client.PhysicalBodyInteractionClient}：
 * <pre>Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();</pre>
 * 本项目内同形的克隆件 {@code com.mss.polymech.mps.client.PhysicalBodyInteractionClient} 逐字相同。
 *
 * <p><b>不要改回 {@code player.getEyePosition()}</b>：主世界看不出差别（0.000），
 * 太空会整体错开一格多，且"只有太空不准"这条实机现象会立刻回来。</p>
 *
 * <p>方向仍用 {@code player.getViewVector(pt)}（{@code [视线基]} 证明它与
 * {@code camera.getLookVector()} 只差 0.53° 的精度残差，同源）。</p>
 */
public final class ClientViewOrigin {

    private ClientViewOrigin() {
    }

    /**
     * 瞄准射线起点：相机位置；相机还没建立（首帧 / 无 gameRenderer）时退回眼睛。
     *
     * @param player 调用方的玩家（仅用于退回；正常路径不读它）
     */
    public static Vec3 of(Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer != null && mc.player != null) {
            return mc.gameRenderer.getMainCamera().getPosition();
        }
        return player.getEyePosition();
    }
}
