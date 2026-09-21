package com.mss.polymech.physics;

import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 物理步进线程：**按墙钟 10ms 定步进**，与客户端/服务端 tick（20Hz）解耦。
 *
 * <p>抄 space/MPS 的关键一条（其 {@code ClientCollisionPhysicalThread} /
 * {@code ServerCollisionPhysicalThread}）：</p>
 * <pre>
 * core_tick_speed = 100;  core_tick_time = 0.01;
 * service.scheduleAtFixedRate(stepTask, 0, 1000 / core_tick_speed, MILLISECONDS);
 * stepTask = () -> { world.setTickTime(0.01); world.up(); world.step(); };
 * </pre>
 *
 * <p><b>为什么必须这样</b>：原来我们在每个客户端 tick 里一次性走 5 个子步 ——
 * 模拟时间上也是 100Hz，但<b>状态每秒只更新 20 次</b>（5 步全挤在同一个 tick 边界）。
 * 渲染手里永远只有 20Hz 的信息量，于是出现"频率快、幅度小"的抖。
 * 独立线程后状态是真正 100Hz 更新的，渲染直接取当前值就够平滑。</p>
 *
 * <p>线程安全：原生层每个世界都通过 {@code with_world(...)} 持互斥，
 * 主线程的读写与本线程的步进自动串行，不会数据竞争。</p>
 */
public final class PhysicsStepThread {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/Step");

    /** 步进周期（毫秒）与固定步长（秒），与各物理世界的 worldSetTimestep 一致。 */
    private static final long PERIOD_MS = 10L;
    public static final double STEP_SECONDS = 0.01;

    private static final Set<Long> WORLDS = ConcurrentHashMap.newKeySet();

    /**
     * 每个世界"步进之后"要跑的动作（对应 space 的 {@code RapierWorld.tickListeners}）。
     *
     * <p>为什么需要：玩家的双刚体必须在**每个 100Hz 子步之后**重设速度
     * （{@link PlayerPhysicsBody#afterStep}），而不是等 20Hz 的 tick ——
     * 一个 MC tick 里物理走 5 个子步，只在 tick 边界重设的话子步之间速度会漂。</p>
     *
     * <p>这些动作跑在<b>物理步进线程</b>上，只允许碰原生刚体，不能访问 Minecraft 对象。</p>
     */
    private static final Map<Long, Set<Runnable>> POST_STEP = new ConcurrentHashMap<>();

    /**
     * 两次步进的间隔超过它就判定"步进被阻塞"（毫秒）。
     *
     * <p>正常周期是 10ms。超过 40ms 就意味着模拟在这段时间里<b>整段停住</b> ——
     * 玩家看到的就是"正在飞的物理体每隔几秒顿一下"。</p>
     */
    private static final long STALL_WARN_MS = 40L;
    /** 同类告警的最小间隔（纳秒），避免刷屏。 */
    private static final long STALL_WARN_INTERVAL_NANOS = 3_000_000_000L;

    private static long lastStartNanos;
    private static long lastStallWarnNanos;
    private static volatile long stallCount;
    private static volatile long maxStallMs;

    private static ScheduledExecutorService executor;
    private static Runnable task;

    /** 累计"步进被阻塞 ≥ {@link #STALL_WARN_MS} ms"的次数（诊断用）。 */
    public static long stallCount() {
        return stallCount;
    }

    /** 观测到的最长步进间隔（毫秒，诊断用）。 */
    public static long maxStallMs() {
        return maxStallMs;
    }

    /** 客户端暂停（单机 ESC）：由客户端设置。 */
    private static volatile BooleanSupplier clientPaused = () -> false;

    private PhysicsStepThread() {
    }

    public static void setClientPausedSupplier(BooleanSupplier supplier) {
        clientPaused = supplier == null ? () -> false : supplier;
    }

    /** 注册一个物理世界并确保线程已启动。 */
    public static synchronized void add(long world) {
        if (world <= 0) {
            return;
        }
        WORLDS.add(world);
        startIfNeeded();
    }

    /** 注销世界；没有世界了就停线程（避免空转）。 */
    public static synchronized void remove(long world) {
        WORLDS.remove(world);
        POST_STEP.remove(world);
        if (WORLDS.isEmpty()) {
            stop();
        }
    }

    /** 注册"该世界每次步进之后"要跑的动作（幂等：重复注册同一个实例只留一份）。 */
    public static void addPostStep(long world, Runnable action) {
        if (world <= 0 || action == null) {
            return;
        }
        POST_STEP.computeIfAbsent(world, k -> ConcurrentHashMap.newKeySet()).add(action);
    }

    /** 注销步进后动作。 */
    public static void removePostStep(long world, Runnable action) {
        Set<Runnable> set = POST_STEP.get(world);
        if (set != null) {
            set.remove(action);
            if (set.isEmpty()) {
                POST_STEP.remove(world);
            }
        }
    }

    private static void startIfNeeded() {
        if (executor != null) {
            return;
        }
        // ⚠️ 必须归零：`lastStartNanos` 是静态字段，线程从"停止"到"重新启动"之间
        // （退出世界 → 主菜单 → 再进世界）会跨过一次真实的长时间间隔。若不归零，
        // 新线程的第一步会把"玩家在主菜单待的时间"记成"步进被阻塞"——
        // 实测报出过 `间隔 112739 ms`，而 112.7 秒正好是那次主菜单停留时长。
        // 探针本身骗人比没有探针更糟：它会让人去追一个不存在的卡死。
        lastStartNanos = 0L;
        lastStallWarnNanos = 0L;
        stallCount = 0L;
        maxStallMs = 0L;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PolyMech-Physics-Step");
            t.setDaemon(true);
            return t;
        });
        task = () -> {
            long startNanos = System.nanoTime();
            // 间隔（上一次任务开始 → 这一次任务开始）：既能抓到"上一次跑太久"，
            // 也能抓到"这个线程根本没被调度"。暂停时不统计，避免恢复后误报。
            long gapMs = lastStartNanos == 0L ? 0L : (startNanos - lastStartNanos) / 1_000_000L;
            lastStartNanos = startNanos;
            if (paused()) {
                return;
            }
            for (Long handle : WORLDS) {
                try {
                    NativePhysics.worldStep(handle);
                } catch (Throwable t) {
                    LOGGER.error("[PolyMech] 物理步进异常（world={}）", handle, t);
                }
                // 步进后动作（玩家双刚体的速度继承链）：space 的 RapierWorld.tickListeners 等价物
                Set<Runnable> actions = POST_STEP.get(handle);
                if (actions != null) {
                    for (Runnable action : actions) {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            LOGGER.error("[PolyMech] 步进后动作异常（world={}）", handle, t);
                        }
                    }
                }
            }
            long workMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (gapMs >= STALL_WARN_MS || workMs >= STALL_WARN_MS) {
                stallCount++;
                maxStallMs = Math.max(maxStallMs, Math.max(gapMs, workMs));
                if (startNanos - lastStallWarnNanos > STALL_WARN_INTERVAL_NANOS) {
                    lastStallWarnNanos = startNanos;
                    // 间隔大 = 线程没被调度（或上一次跑太久）；耗时长 = 卡在世界的互斥锁上
                    //（同一世界里主线程正在做长操作，比如建区块体素碰撞体/重建物理体碰撞体）。
                    LOGGER.warn("[PolyMech] 物理步进被阻塞：间隔 {} ms / 本次耗时 {} ms（世界 {} 个，累计 {} 次，最长 {} ms）"
                                    + " —— 这段时间模拟是停住的，画面上就是「顿一下」",
                            gapMs, workMs, WORLDS.size(), stallCount, maxStallMs);
                }
            }
        };
        executor.scheduleAtFixedRate(task, 0L, PERIOD_MS, TimeUnit.MILLISECONDS);
        LOGGER.info("[PolyMech] 物理步进线程已启动：{}ms/步（{}Hz）", PERIOD_MS, 1000 / PERIOD_MS);
    }

    private static synchronized void stop() {
        if (executor != null) {
            executor.shutdown();
            executor = null;
            task = null;
            // 同 startIfNeeded：停止后不能留着上一次的时间基准（见那里的注释）
            lastStartNanos = 0L;
            LOGGER.info("[PolyMech] 物理步进线程已停止（无物理世界）");
        }
    }

    private static boolean paused() {
        if (clientPaused.getAsBoolean()) {
            return true;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isPaused();
    }
}
