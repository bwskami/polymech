package com.mss.polymech.util;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 「部分刻」（partial tick，渲染插值系数）的**唯一取法**。
 *
 * <h2>为什么要有这个类（§7.12 的教训）</h2>
 * {@code DeltaTracker} 上有两个名字极像、含义完全不同的方法。运行时字节码
 * （{@code build/moddev/artifacts/neoforge-21.1.228-merged.jar} → {@code DeltaTracker$Timer}）：
 *
 * <pre>
 *   public float getGameTimeDeltaTicks()        // → getfield deltaTicks
 *   public float getGameTimeDeltaPartialTick(Z) // → getfield deltaTickResidual（或 pausedDeltaTickResidual）
 * </pre>
 *
 * 两者在 {@code advanceGameTime(long)} 里是这样算出来的：
 *
 * <pre>
 *   deltaTicks        = 本帧耗时 / 每刻目标耗时      // ≈ 0.02~0.08（500fps 时）
 *   deltaTickResidual += deltaTicks;                 // 累积到 ≥1 就吐出一整个 tick
 *   deltaTickResidual -= (int) deltaTickResidual;    // 余量 ∈ [0,1) ← 这才是部分刻
 * </pre>
 *
 * 也就是说：
 * <ul>
 *   <li>{@code getGameTimeDeltaTicks()} = <b>本帧</b>走过的时间（一帧的步长），不是插值系数；</li>
 *   <li>{@code getGameTimeDeltaPartialTick(z)} = <b>距上次 tick 的累积余量</b>，即原版渲染插值用的系数。</li>
 * </ul>
 *
 * 原版实体渲染取的就是后者：{@code LevelRenderer.renderEntity} →
 * {@code Mth.lerp(pt, entity.xOld, entity.getX())}，pt 来自
 * {@code DeltaTracker.getGameTimeDeltaPartialTick(!isEntityFrozen(entity))}。
 *
 * <p><b>用错会怎样</b>：把 {@code getGameTimeDeltaTicks()} 当 pt 喂给
 * {@code Mth.lerp(pt, xo, x)}，pt≈0.04 ⇒ 渲染位置≈**上一刻的位置**，
 * 且只在 tick 边界才变 ⇒ 20Hz 台阶。表现就是"框/线跟着移动抽搐、且比人慢一整刻"。
 * 2026-10-05 修的就是这个：{@code PlayerColliderRender}、{@code PhysicsBodyRenderer}、
 * {@code PhysgunBeamRenderer}、{@code SpaceRenderer} 四处都取错了；
 * 而 {@code PhysgunTarget}、{@code ClientPhysics} 两处本来就取对了 —— 同一概念两份实现，
 * 于是画面里"有的东西平滑、有的东西抽"。现在全部收敛到这里。
 */
public final class PartialTicks {
    private PartialTicks() {
    }

    /**
     * 原版渲染插值用的部分刻。
     *
     * <p>{@code false} 与 {@code LevelRenderer} 里对普通实体（非 frozen）的取法一致，
     * 也与本工程既有的 {@code PhysgunTarget}/{@code ClientPhysics} 两处逐字相同。
     */
    public static float of(DeltaTracker tracker) {
        return tracker.getGameTimeDeltaPartialTick(false);
    }

    /** 渲染阶段事件带的 DeltaTracker 就是这一次关卡渲染用的那个（与 {@link #level()} 同一个对象）。 */
    public static float of(RenderLevelStageEvent event) {
        return of(event.getPartialTick());
    }

    /**
     * 最近一帧渲染用的部分刻（由渲染器每帧 {@link #recordFrame} 记下）。
     *
     * <p><b>为什么需要它</b>：位姿在<b>样本不足</b>时会退回"两拍 lerp"
     * （{@code prev + (cur - prev) * partialTick}），那条路<b>吃 alpha</b>。渲染器用当帧 alpha，
     * 玩法侧若用别的口径（例如写死 {@code 1.0F}，或换成 {@code liveTransform} 的"此刻"刚体），
     * 就会算出<b>不同的位姿</b> ⇒ 射线打在画面之前/之后 ⇒ 表现正是
     * "瞄着显示的位置打不中 / 得点它旁边的空处才能挖"（2026-10-06 实机定位）。
     * 目前 {@link #of(RenderLevelStageEvent)} 与 {@link #level()} 恰好是同一个对象，
     * 但把"画面最后一帧的 alpha"显式记下来，才能让这条耦合不再依赖"恰好相同"。</p>
     */
    private static volatile float lastFrame = Float.NaN;

    /** 渲染器每帧调一次，记下这一帧的部分刻。 */
    public static void recordFrame(float partialTick) {
        lastFrame = partialTick;
    }

    /**
     * 玩法侧"瞄准<b>画面上</b>那一格"时用它：与画面最后一帧<b>同一个 alpha</b>。
     * 还没有帧记录过（刚启动）时退回 {@link #level()}。
     */
    public static float forDisplay() {
        float f = lastFrame;
        return Float.isNaN(f) ? level() : f;
    }

    /** 关卡渲染之外的场合（tick / 更新逻辑）用客户端计时器。 */
    public static float level() {
        return of(Minecraft.getInstance().getTimer());
    }
}
