package com.mss.polymech.physics;

/**
 * 碰撞体材质常量与统一套用入口（ABI 5 / Tier 1）。
 *
 * <p><b>为什么需要集中一处</b>：此前摩擦/弹性是在八、九个 {@code colliderAttach*} 调用点上
 * 各写一遍魔数（0.6 / 0.7），而 contact skin 与摩擦/弹性**组合规则**根本没设过 ——
 * 全是 Rapier 默认值。space 0.1.3 的 {@code ColliderBody} 里
 * {@code CONTACT_SKIN = 0.02} 是每个碰撞体都带的，玩家的摩擦更是直接给到 20.0。</p>
 *
 * <p>组合规则的语义（Rapier {@code CoefficientCombineRule}）：两个接触面各自带一个系数，
 * 求解器按规则合成一个值 —— Average（默认）/ Min / Multiply / Max。它决定了
 * "玩家摩擦 20"撞上"地形摩擦 0.7"时到底算多少。</p>
 *
 * <p>原生层低于 ABI 5 时 {@link #apply} 静默跳过：摩擦/弹性仍在建体时传入，
 * 只是没有 contact skin 与自定义组合规则 —— 不会让物理层不可用。</p>
 */
public final class PhysicsMaterials {

    /**
     * contact skin 厚度（格）：Rapier 用来消接触抖动的手段，与 space 0.1.3 的
     * {@code ColliderBody.CONTACT_SKIN} 同值。
     *
     * <p>不设它的时候，两个表面在"刚好贴上"那一步会反复穿插/分离，
     * 表现为静置的物体微微发抖、姿态缓慢漂移。</p>
     */
    public static final double CONTACT_SKIN = 0.02;

    /** 摩擦组合规则：取平均（Rapier 默认）。 */
    public static final int FRICTION_RULE = NativePhysics.RULE_AVERAGE;
    /** 弹性组合规则：取平均（Rapier 默认）。 */
    public static final int RESTITUTION_RULE = NativePhysics.RULE_AVERAGE;

    /** 地形碰撞体摩擦。 */
    public static final double TERRAIN_FRICTION = 0.7;
    /** 物理体（船/建筑）碰撞体摩擦。 */
    public static final double BODY_FRICTION = 0.6;
    /** 被物理接管的普通实体摩擦。 */
    public static final double ENTITY_FRICTION = 0.6;

    /**
     * 玩家碰撞体摩擦。
     *
     * <p><b>改成 20.0，与 space 0.1.3 一致</b>（{@code ColliderBody.setFriction(20.0)} +
     * {@code setFrictionCombineRule(0)}）。之前我们保持 0.6，理由是"我们的碰撞箱还跟着 6DOF
     * 身体转，照抄 20 会把身体粘在地形上" —— 现在玩家模型已经照 space 改成
     * <b>双刚体 + 碰撞箱永不旋转 + 速度驱动</b>（见 {@link PlayerPhysicsBody}），
     * 那个前提没有了，所以取值也回到 space 的值。</p>
     */
    public static final double PLAYER_FRICTION = 20.0;

    /** 玩家弹性组合规则：space 的 {@code restitutionCombineRule(1)} = Min。 */
    public static final int PLAYER_RESTITUTION_RULE = NativePhysics.RULE_MIN;

    private PhysicsMaterials() {
    }

    /**
     * 把**玩家**材质套到碰撞体上（space 的那一套：摩擦 20 / 弹性 0 / 摩擦平均 / 弹性取小）。
     */
    public static void applyPlayer(long world, long collider) {
        if (world <= 0 || collider <= 0 || !PhysicsNatives.hasTier1()) {
            return;
        }
        NativePhysics.colliderSetMaterial(world, collider, PLAYER_FRICTION, 0.0,
                CONTACT_SKIN, NativePhysics.RULE_AVERAGE, PLAYER_RESTITUTION_RULE);
    }

    /**
     * 把材质套到刚挂上的碰撞体上。
     *
     * @param world     物理世界句柄
     * @param collider  {@code colliderAttach*} 返回的碰撞体 id（&le;0 表示创建失败，直接跳过）
     * @param friction  摩擦系数
     * @param restitution 弹性系数
     */
    public static void apply(long world, long collider, double friction, double restitution) {
        if (world <= 0 || collider <= 0 || !PhysicsNatives.hasTier1()) {
            return;
        }
        NativePhysics.colliderSetMaterial(world, collider, friction, restitution,
                CONTACT_SKIN, FRICTION_RULE, RESTITUTION_RULE);
    }
}
