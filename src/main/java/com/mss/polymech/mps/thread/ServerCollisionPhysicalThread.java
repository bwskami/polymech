package com.mss.polymech.mps.thread;

import com.mss.polymech.Config;
import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 服务端碰撞物理线程 —— <b>与
 * {@code org.polaris2023.mps.thread.ServerCollisionPhysicalThread} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * <b>两个</b>独立线程：
 * <ol>
 *   <li><b>{@code -step}</b>（默认 100Hz）：遍历所有服务端物理世界，
 *       {@code setTickTime → up() → step()} —— 这是真正的物理推进；</li>
 *   <li><b>{@code -chunk}</b>（固定 50ms = 20Hz）：遍历所有物理世界调
 *       {@code chunkManager.tick()} —— 地形兴趣点的增删（带迟滞）。</li>
 * </ol>
 *
 * <h2>为什么物理不放在服务端 tick 里（照 space 0.1.3）</h2>
 * MC 主线程是 20Hz 且会随 TPS 抖动/掉步。物理放在 100Hz 固定节拍上：
 * <ol>
 *   <li>碰撞与堆叠的稳定性与 TPS 解耦 —— 卡服不会让船穿模或炸开；</li>
 *   <li>{@code core_tick_time} 是<b>固定 dt</b>，求解器可假定步长恒定。</li>
 * </ol>
 * 代价是严格的跨线程纪律：<b>改变世界的操作必须入队</b>（{@code up()} 在 step 前 flush），
 * 这就是 {@code RapierWorld} 的操作缓冲存在的原因。
 *
 * <h2>为什么 chunk 任务是独立线程而不是并进 step</h2>
 * 两者节奏不同（20Hz vs 100Hz）且代价不同：地形扫描比一步求解贵得多。
 * 并进 step 会让 100Hz 的节拍被偶发的区块扫描拖慢 —— 而物理节拍的<b>稳定性</b>
 * 恰恰是它存在的理由。分线程后即使 chunk 扫得慢，step 依然准点。
 *
 * <h2>必须保留的细节</h2>
 * <ul>
 *   <li><b>两个线程各自撞名检查</b>：重复 startThread 会累积线程、
 *       把物理推进多次（速度翻倍）。</li>
 *   <li><b>单机暂停时停步</b>（同上：防止恢复后回拉）。</li>
 *   <li><b>整段 try/catch + printStackTrace</b>：独立线程里异常逃出去会静默杀死调度。</li>
 * </ul>
 */
public abstract class ServerCollisionPhysicalThread {

    private static ScheduledExecutorService stepScheduledExecutorService;
    private static Runnable stepTask;
    private static ScheduledExecutorService chunkManagerScheduledExecutorService;
    private static Runnable chunkManagerTask;
    public static int core_tick_speed = 100;
    public static double core_tick_time = 0.01;
    public static boolean pause = false;
    private static int tick_record;
    private static long last_time;
    public static int tick;

    public static void startThread() {
        core_tick_speed = Config.CORE_TICK_SPEED.get();
        core_tick_time = Config.CORE_TICK_TIME.get();
        pause = false;

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        boolean stepAlreadyRunning = false;
        boolean chunkAlreadyRunning = false;
        for (long threadId : threadMXBean.getAllThreadIds()) {
            var info = threadMXBean.getThreadInfo(threadId);
            if (info == null) {
                continue;
            }
            if ("ServerCollisionPhysicalThread-step".equals(info.getThreadName())) {
                stepAlreadyRunning = true;
            }
            if ("ServerCollisionPhysicalThread-chunk".equals(info.getThreadName())) {
                chunkAlreadyRunning = true;
            }
        }

        if (stepAlreadyRunning) {
            Polymech.LOGGER.error("[MPS] [Physic] [step] 已存在同名线程，拒绝重复启动！");
        } else {
            stepScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("ServerCollisionPhysicalThread-step");
                return t;
            });
            stepTask = () -> {
                if (!shouldRun()) {
                    return;
                }
                try {
                    for (ServerPhysicalWorld serverPhysicalWorld : ServerPhysicalWorld.getAllPhysicalWorld()) {
                        serverPhysicalWorld.setTickTime(core_tick_time);
                        serverPhysicalWorld.up();
                        serverPhysicalWorld.step();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            };
            int tickRunTime = 1000 / core_tick_speed;
            if (tickRunTime != 0) {
                stepScheduledExecutorService.scheduleAtFixedRate(stepTask, 0L, tickRunTime, TimeUnit.MILLISECONDS);
                Polymech.LOGGER.info("[MPS] [Physic] [step] 服务端碰撞物理线程已启动：{}Hz，dt={}s",
                        core_tick_speed, core_tick_time);
            } else {
                Polymech.LOGGER.error("[MPS] [Physic] [step] 周期为 0，无法创建线程！请把 coreTickSpeed 调小。");
            }
        }

        if (chunkAlreadyRunning) {
            Polymech.LOGGER.error("[MPS] [Physic] [chunk] 已存在同名线程，拒绝重复启动！");
        } else {
            chunkManagerScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setName("ServerCollisionPhysicalThread-chunk");
                return t;
            });
            chunkManagerTask = () -> {
                if (!shouldRun()) {
                    return;
                }
                try {
                    for (ServerPhysicalWorld serverPhysicalWorld : ServerPhysicalWorld.getAllPhysicalWorld()) {
                        serverPhysicalWorld.getChunkManager().tick();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            };
            // 固定 50ms（20Hz）：与物理 step 的节奏解耦（见类注释）
            chunkManagerScheduledExecutorService.scheduleAtFixedRate(chunkManagerTask, 0L, 50L, TimeUnit.MILLISECONDS);
            Polymech.LOGGER.info("[MPS] [Physic] [chunk] 服务端地形兴趣点线程已启动（20Hz）");
        }
    }

    /** 单机 + 客户端暂停时不推进（防止恢复后回拉）。 */
    private static boolean shouldRun() {
        if (pause) {
            return false;
        }
        return ServerLifecycleHooks.getCurrentServer() == null
                || !ServerLifecycleHooks.getCurrentServer().isSingleplayer()
                || !Minecraft.getInstance().isPaused();
    }

    public static void stopThread() {
        if (stepScheduledExecutorService != null) {
            stepScheduledExecutorService.shutdown();
            stepScheduledExecutorService = null;
        }
        stepTask = null;
        if (chunkManagerScheduledExecutorService != null) {
            chunkManagerScheduledExecutorService.shutdown();
            chunkManagerScheduledExecutorService = null;
        }
        chunkManagerTask = null;
        core_tick_speed = 100;
        core_tick_time = 0.01;
        pause = false;
    }
}
