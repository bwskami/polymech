package com.mss.polymech.space;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * "太空移动接管"的<b>门 + 自检</b> —— 用户 2026-10-12 指令的落地处：
 * <b>主世界的移动必须与原版逐位一致；太空那套只在太空生效。</b>
 *
 * <h2>门（唯一判据，两句话）</h2>
 * <pre>
 *   1) 原版维度（主世界 / 下界 / 末地）：<b>载速探针一律不跑</b>（那是纯太空功能）；
 *   2) 任何维度：<b>本维度没有物理体 ⇒ 载速探针与碰撞扫掠都不跑</b>。
 * </pre>
 *
 * <p>第 2 条不是"顺手加的门"，它才是主世界走路"顿一下"的根因修复：
 * 探针测的是"被移动的物理体带着走"的载速，而它的<b>立足点就是客户端地形碰撞体</b>
 * （{@code ClientPhysicalWorld.terrainNeeded()} 也是"有物理体才建"）。没有物理体时地面不存在，
 * 探针每 5 个子步会攒出一整段自由落体（-g·5·dt），而 {@code PhysicalEntityProbe.onPhysicsStep}
 * 的"已着地就不扣重力"分支会把它原样当成载速，÷20 回灌进 {@code travel} —— 那正是主世界
 * 走路偶尔"顿一下"的来源。所以"没有物理体"必须同时关掉探针（与地形同一判据）。</p>
 *
 * <h2>自检（傻瓜式 PASS/FAIL，不用你判断任何物理量）</h2>
 * 每 10 秒一行日志：
 * <pre>
 *   [移动门] 维度=... 原版=是 太空系统=否 物理体=无 ⇒ 门=关 | 生效 探针=0 扫掠=0 | 被拦 ... | 判据=PASS(...)
 * </pre>
 * 判据只有一条：<b>本维度没有物理体时，"生效"次数必须为 0</b>；有物理体时门必须为"开"。
 *
 * <p>碰撞扫掠与探针的门不同：扫掠在<b>有物理体</b>时任何维度都要跑 —— 否则你会穿过自己抠出来的
 * 建筑/飞船（那是 mod 功能，不是太空移动 hack）。探针则严格只在太空系统维度跑。</p>
 */
public final class SpaceMovementGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Space/MovementGate");

    /** 自检打印间隔（毫秒）。 */
    private static final long REPORT_INTERVAL_MS = 10_000L;

    private static final int PROBE_ACTIVE = 0;
    private static final int SWEEP_ACTIVE = 1;
    private static final int PROBE_BLOCKED = 2;
    private static final int SWEEP_BLOCKED = 3;
    private static final int SLOTS = 4;

    private static final Map<ResourceKey<Level>, AtomicLongArray> BY_DIM = new ConcurrentHashMap<>();
    private static volatile long lastReportMs;

    private SpaceMovementGate() {
    }

    /** 载速探针的门：只在太空系统维度、且本维度真的有物理体时才允许。 */
    public static boolean probeOpen(Level level, boolean hasPhysicalBodies) {
        return level != null
                && hasPhysicalBodies
                && !PlanetDimensions.isVanillaDimension(level);
    }

    /** 碰撞扫掠的门：有物理体就允许（任何维度）—— 有东西要撞才扫。 */
    public static boolean sweepOpen(Level level, boolean hasPhysicalBodies) {
        return level != null && hasPhysicalBodies;
    }

    private static AtomicLongArray counters(Level level) {
        // 键用 ResourceKey（值语义、无字符串分配）：这几个钩子每 tick 都会调一次。
        return BY_DIM.computeIfAbsent(level.dimension(), k -> new AtomicLongArray(SLOTS));
    }

    public static void noteProbeActive(Level level) {
        counters(level).incrementAndGet(PROBE_ACTIVE);
    }

    public static void noteSweepActive(Level level) {
        counters(level).incrementAndGet(SWEEP_ACTIVE);
    }

    public static void noteProbeBlocked(Level level) {
        counters(level).incrementAndGet(PROBE_BLOCKED);
    }

    public static void noteSweepBlocked(Level level) {
        counters(level).incrementAndGet(SWEEP_BLOCKED);
    }

    /**
     * 判据（<b>唯一一条</b>）：本维度没有物理体时，"生效"次数必须为 0；有物理体时门必须为开。
     *
     * <p>供日志自检与游戏内 {@code /polymech physics status} 共用，两处口径不可能分叉。</p>
     */
    public static String verdict(Level level, boolean hasPhysicalBodies, long probeActive, long sweepActive) {
        if (level == null) {
            return "PASS（无维度）";
        }
        if (!hasPhysicalBodies) {
            return (probeActive == 0 && sweepActive == 0)
                    ? "PASS（本维度没有物理体 ⇒ 太空移动一次都没生效，移动=原版）"
                    : "★FAIL（本维度没有物理体，探针/扫掠却生效过：" + probeActive + "/" + sweepActive + "）";
        }
        if (!sweepOpen(level, true)) {
            return "★FAIL（有物理体但扫掠门是关的：会穿过自己抠的体）";
        }
        if (PlanetDimensions.isVanillaDimension(level)) {
            return "PASS（原版维度：载速探针关、扫掠按「有物理体」开）";
        }
        return probeOpen(level, true)
                ? "PASS（太空系统 + 有物理体 ⇒ 门开）"
                : "★FAIL（太空系统有物理体却没开门）";
    }

    /** 当前维度的累计生效次数（探针, 扫掠）—— 供命令回执打印。 */
    public static long[] activeCounts(Level level) {
        AtomicLongArray c = counters(level);
        return new long[]{c.get(PROBE_ACTIVE), c.get(SWEEP_ACTIVE)};
    }

    /**
     * 每 10 秒一行 PASS/FAIL。
     *
     * @param hasPhysicalBodies 调用方（客户端 tick）给出的"当前维度有没有物理体"
     */
    public static void reportIfDue(Level level, boolean hasPhysicalBodies) {
        long now = System.currentTimeMillis();
        if (now - lastReportMs < REPORT_INTERVAL_MS) {
            return;
        }
        lastReportMs = now;
        if (level == null) {
            return;
        }

        AtomicLongArray c = counters(level);
        long probeActive = c.get(PROBE_ACTIVE);
        long sweepActive = c.get(SWEEP_ACTIVE);
        long probeBlocked = c.get(PROBE_BLOCKED);
        long sweepBlocked = c.get(SWEEP_BLOCKED);

        boolean vanilla = PlanetDimensions.isVanillaDimension(level);
        boolean spaceSystem = PlanetDimensions.isSpaceSystem(level);
        boolean probeGate = probeOpen(level, hasPhysicalBodies);
        boolean sweepGate = sweepOpen(level, hasPhysicalBodies);
        String verdict = verdict(level, hasPhysicalBodies, probeActive, sweepActive);

        LOGGER.info("[移动门] 维度={} 原版={} 太空系统={} 物理体={} ⇒ 探针门={} 扫掠门={} | 生效 探针={} 扫掠={} | 被拦 探针={} 扫掠={} | 判据={}",
                level.dimension().location(), vanilla ? "是" : "否", spaceSystem ? "是" : "否",
                hasPhysicalBodies ? "有" : "无", probeGate ? "开" : "关", sweepGate ? "开" : "关",
                probeActive, sweepActive, probeBlocked, sweepBlocked, verdict);
    }
}
