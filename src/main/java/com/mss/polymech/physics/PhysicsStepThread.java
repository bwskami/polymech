package com.mss.polymech.physics;

import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static ScheduledExecutorService executor;
    private static Runnable task;

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
        if (WORLDS.isEmpty()) {
            stop();
        }
    }

    private static void startIfNeeded() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PolyMech-Physics-Step");
            t.setDaemon(true);
            return t;
        });
        task = () -> {
            if (paused()) {
                return;
            }
            for (Long handle : WORLDS) {
                try {
                    NativePhysics.worldStep(handle);
                } catch (Throwable t) {
                    LOGGER.error("[PolyMech] 物理步进异常（world={}）", handle, t);
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
