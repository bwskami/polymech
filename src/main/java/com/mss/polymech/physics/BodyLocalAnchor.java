package com.mss.polymech.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * <b>「抓点」这个概念本身</b>：一个固定在<b>刚体局部空间</b>里的点，外加它在哪个刚体上。
 *
 * <h2>为什么必须是一个类型，而不是到处传世界坐标</h2>
 * 世界坐标是<b>派生量</b>：{@code world = 体位姿 · localAnchor}。
 * 把它当成一等公民在网络上传来传去，会带来两个<b>必然</b>的后果（两个都实测撞过）：
 *
 * <ol>
 *   <li><b>混叠</b>：服务端只能按自己的 tick 采样体位姿 ⇒ 发出的是 20Hz 的世界点。而物体被拖着
 *       在地面上走时，接触解算的抖动是 100Hz 量级（见 {@code mps-clone-plan} 第 22 节那条
 *       已记录、已拍板"接受"的穿透锯齿）。<b>20Hz 采样 100Hz 就是混叠</b> ⇒ 玩家一动，
 *       光束末端与抓取框就开始抽；玩家不动时物体静止 ⇒ Δ=0 ⇒ 看着像"修好了"。
 *       这正是"玩家不动时没有抽搐，一动就有了"的根因。</li>
 *   <li><b>延迟</b>：客户端再对这些世界点插值，就必须付出 ≥2 拍（100ms）的渲染延迟，
 *       否则相位一乱就每拍跳一整个采样间隔（见 {@code docs/render-interpolation.md}）。</li>
 * </ol>
 *
 * <p>参考实现（Create Aeronautics 的 {@code physics_staff}）存的就是<b>局部抓点</b>，
 * 每帧用客户端自己的位姿变换到世界：
 * {@code PhysicsStaffClientHandler:134/239} 收发的都是 {@code dragLocalAnchor}；
 * {@code :374-384} 渲染时 {@code subLevel.renderPose(pt).transformPosition(interpolatedBeamEnd)}。
 * 于是世界点是逐帧从<b>连续量</b>（客户端自己的位姿）算出来的 —— 结构上不可能混叠，也不需要延迟。</p>
 *
 * <p>本类就是那个概念：网络上只传它，世界点由 {@link #toWorld} 现算。</p>
 *
 * @param bodyId 抓的是哪个物理体（{@code PhysicsBodyTracker} 的 long id；两端同一张表）
 * @param x      抓点在<b>体局部</b>空间的位置（格）
 * @param y      同上
 * @param z      同上
 */
public record BodyLocalAnchor(long bodyId, double x, double y, double z) {

    /**
     * 用某个位姿把它变成世界点 —— <b>全项目唯一的一处换算</b>。
     *
     * <p>服务端、客户端预测、客户端渲染都走这一处，所以三边不可能各算一套
     * （本项目在"同一概念两份实现"上吃过亏）。</p>
     *
     * @param bodyPos 刚体位置（世界，格）
     * @param bodyRot 刚体姿态
     * @param out     输出（可与入参共用）
     */
    public Vector3d toWorld(Vector3d bodyPos, Quaterniond bodyRot, Vector3d out) {
        return bodyRot.transform(new Vector3d(x, y, z), out).add(bodyPos);
    }

    /** 便利重载（自己分配输出）。 */
    public Vector3d toWorld(Vector3d bodyPos, Quaterniond bodyRot) {
        return toWorld(bodyPos, bodyRot, new Vector3d());
    }

    /** 体是否一致（客户端收到权威包时用来判断"要不要重建这条光束"）。 */
    public boolean sameBody(long otherBodyId) {
        return bodyId == otherBodyId;
    }
}
