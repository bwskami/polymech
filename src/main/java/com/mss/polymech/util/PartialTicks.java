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

    /** 关卡渲染之外的场合（tick / 更新逻辑）用客户端计时器。 */
    public static float level() {
        return of(Minecraft.getInstance().getTimer());
    }
}
