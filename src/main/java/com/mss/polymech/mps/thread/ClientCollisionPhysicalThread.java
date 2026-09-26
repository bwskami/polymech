package com.mss.polymech.mps.thread;

import com.mss.polymech.Config;
import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 客户端碰撞物理线程 —— <b>与
 * {@code org.polaris2023.mps.thread.ClientCollisionPhysicalThread} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 以固定频率（默认 100Hz）推进<b>客户端</b>物理世界
 * （{@code setTickTime → up() → step()}），从而推动物理体的运动学镜像。
 *
 * <h2>为什么客户端也要跑一个真物理世界（照 space 0.1.3）</h2>
 * 客户端的镜像体是 <b>KINEMATIC_POSITION</b>（位姿由服务端批量包驱动），
 * 但这不代表客户端可以不跑求解器：<b>玩家自己的碰撞体</b>在客户端也要参与求解，
 * 否则"站在船上"的贴合、被船推动的观感都会迟一帧甚至穿模。
 * 两端用<b>同一个 dt</b>（都来自配置）才能保持一致。
 *
 * <h2>几个必须保留的细节</h2>
 * <ul>
 *   <li><b>线程名固定 + 撞名检查</b>：这个线程是<b>客户端生命周期</b>的，
 *       换世界/重连时会再发一次 {@code SyncPhysicalThreadStart}。
 *       不检查就会累积多个线程、把物理世界推进多次（速度翻倍）。</li>
 *   <li><b>单机暂停时停步</b>：单机按 Esc 时服务端不 tick，
 *       客户端若还在跑，镜像会走到服务端前面，恢复后突然回拉。</li>
 *   <li><b>整个 step 包 try/catch 并打印</b>：这是<b>独立线程</b>，
 *       异常逃出去会静默杀死调度任务 —— 表现是"物理突然全停"，且没有任何日志。</li>
 * </ul>
 */
public abstract class ClientCollisionPhysicalThread {

    private static ScheduledExecutorService stepScheduledExecutorService;
    private static Runnable stepTask;
    public static int core_tick_speed = 100;
    public static double core_tick_time = 0.01;
    public static boolean pause = false;
    private static int tick_record;
    private static long last_time;
    /** 上一秒实际跑了多少步（诊断用，应接近 {@link #core_tick_speed}）。 */
    public static int tick;

    public static void startThread() {
        core_tick_speed = Config.CORE_TICK_SPEED.get();
        core_tick_time = Config.CORE_TICK_TIME.get();
        pause = false;

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        boolean alreadyRunning = false;
        for (long threadId : threadMXBean.getAllThreadIds()) {
            var info = threadMXBean.getThreadInfo(threadId);
            if (info != null && "ClientCollisionPhysicalThread-step".equals(info.getThreadName())) {
                alreadyRunning = true;
                break;
            }
        }

        if (alreadyRunning) {
            // 这不是故障，是**预期**的重发：space 0.1.3 的线程同样跨维度常驻，
            // 只在整个客户端退出世界时 stop（org.deep_space_studio.space.client.ClientWorldCleanup 只挂 LoggingOut），
            // 而服务端每次 EntityJoinLevelEvent（含换维度）都会重发 SyncPhysicalThreadStart ⇒ 第二次必然撞名。
            // space 在这里打的也是 ERROR（"Can't create new thread! There is a thread with the same name…"）；
            // 我们保留完全相同的"拒绝重复启动"语义，只把级别降为 WARN，免得它掩盖真正的错误。
            Polymech.LOGGER.warn("[MPS] [Physic] [step] 已存在同名线程，忽略本次重复启动"
                    + "（换维度会重发启动包，而步进线程跨维度常驻；只有真的起两个才会把物理世界每步推进两次）");
            return;
        }

        stepScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("ClientCollisionPhysicalThread-step");
            return t;
        });
        stepTask = () -> {
            boolean run = !pause;
            if (ServerLifecycleHooks.getCurrentServer() != null
                    && ServerLifecycleHooks.getCurrentServer().isSingleplayer()
                    && Minecraft.getInstance().isPaused()) {
                run = false;
            }
            if (!run) {
                return;
            }
            try {
                ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
                if (physicalWorld == null) {
                    return;
                }
                physicalWorld.setTickTime(core_tick_time);
                physicalWorld.up();
                physicalWorld.step();
            } catch (Exception e) {
                // 独立线程里逃出去的异常会静默杀死调度任务（见类注释）
                e.printStackTrace();
            }
        };

        int tickRunTime = 1000 / core_tick_speed;
        if (tickRunTime != 0) {
            stepScheduledExecutorService.scheduleAtFixedRate(stepTask, 0L, tickRunTime, TimeUnit.MILLISECONDS);
            Polymech.LOGGER.info("[MPS] [Physic] [step] 客户端碰撞物理线程已启动：{}Hz，dt={}s",
                    core_tick_speed, core_tick_time);
        } else {
            Polymech.LOGGER.error("[MPS] [Physic] [step] 周期为 0，无法创建客户端碰撞物理线程！请把 coreTickSpeed 调小。");
        }
    }

    public static void stopThread() {
        if (stepScheduledExecutorService != null) {
            stepScheduledExecutorService.shutdown();
            stepScheduledExecutorService = null;
        }
        stepTask = null;
        core_tick_speed = 100;
        core_tick_time = 0.01;
        pause = false;
    }
}
