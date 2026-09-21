package com.mss.polymech.mps.kelvin;

import com.mss.polymech.Config;
import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.space_world.ServerSpaceWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.SpaceWorld;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Timer;
import java.util.TimerTask;

/**
 * 天体物理线程 —— <b>与 {@code org.cn_grass_block.kelvin.OrbitPhysicalThread}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 以<b>固定频率</b>（默认 100Hz，由配置决定）驱动所有 {@link ServerSpaceWorld} 的
 * 天体推进：{@code up()} flush 操作队列 → {@code step(core_tick_time)} 积分一步。
 *
 * <h2>为什么必须独立成线程（照 space 0.1.3 的架构决定）</h2>
 * 天体轨道积分是<b>刚性问题</b>（靠近恒星的浅椭圆轨道需要小步长才稳定），
 * 而 MC 主线程 20Hz 且会随 TPS 抖动、卡顿时直接掉步。
 * 把天体放在自己 100Hz 的固定节拍上：
 * <ol>
 *   <li>轨道精度与主线程 TPS 解耦 —— 卡服不会让行星飞出去；</li>
 *   <li>{@code core_tick_time} 是<b>固定 dt</b>，积分器可以假定步长恒定，
 *       不必做变步长补偿。</li>
 * </ol>
 * 代价就是全套跨线程纪律：天体的一切写操作必须<b>入队</b>（见
 * {@code CelestialBody} 的 {@code *Direct} 约定），这里在 {@code step} 前
 * 统一 {@code up()} flush —— 即"改动交接给拥有它的线程"。<b>别把这个 {@code up()}
 * 拿掉</b>，否则游戏线程入队的天体改动会延迟一整步才生效。
 *
 * <h2>几处看似奇怪但必须保留的细节</h2>
 * <ul>
 *   <li><b>重名线程检查</b>：{@link Timer} 的线程名固定为 {@code OrbitPhysicalThread}。
 *       重复 {@code startThread} 会产生两个同样的线程、天体被<b>双倍推进</b>
 *       （每步算两次 = 轨道速度翻倍）。所以先扫全 JVM 线程名，撞名就直接放弃启动。</li>
 *   <li><b>{@code core_tick_time == 0} 时抬成 1.0</b>：dt 为 0 会让积分器原地不动，
 *       天体"冻住"且看不出错。宁可给一个明显不对的大步长，也不要静默冻结。</li>
 *   <li><b>单机暂停</b>：集成服务器 + 客户端暂停（Esc 菜单）时停步 ——
 *       否则玩家开菜单时行星还在飞，回来位置全变了。</li>
 *   <li><b>{@code getAllSpaceWorld()} 返回副本</b>：本线程正在遍历时，游戏线程可能
 *       正在 {@code newSpaceWorld} 登记新维度，直接遍历原 Map 会
 *       {@code ConcurrentModificationException}。</li>
 * </ul>
 *
 * <h2>速度旋钮 = 改 {@code core_tick_time}，不是改每周期步数（照 space）</h2>
 * 线程周期（{@code 1000 / core_tick_speed} 毫秒）是<b>现实</b>心跳，{@code core_tick_time}
 * 是<b>每个心跳推进多少模拟秒</b>。于是
 * <pre>模拟时间流速 = core_tick_time × core_tick_speed</pre>
 * 调速度就是乘这个 dt —— 一条命令即可（space 的 {@code /space physical orbit speed <倍率>}，
 * 见 {@code SpaceModCommand.orbitSpeed()}）：
 * <pre>OrbitPhysicalThread.core_tick_time = CORE_TICK_TIME.get() * multiplier;</pre>
 * <b>为什么偏偏用这种方式</b>：dt 本来就是"一步走多久"，把它当倍率用，
 * 速度与 CPU 开销<b>无关</b>（永远是每周期一步，几万倍也一样）；而"每周期多跑几步"
 * 会让 CPU 正比于倍率，几千倍就不可能跑到实时。
 * 本类<b>曾经</b>实现的就是子步方案（{@code timeScale} + 每周期 N 步 + CPU 预算），
 * 结果就是"开两百倍速看不出效果"——实测被 CPU 预算截断，倍率名存实亡。
 * 代价是积分精度：配置注释写明"目标速度为 1.0 时该值应为 1/tick_speed"，
 * 也就是<b>大于基准的 dt 是速度 hack，不是真实轨道</b>。space 接受这个代价，
 * 因为它是<b>验收/演示</b>用的旋钮，不是正常游玩路径；我们照抄，并把这个代价写进命令回执。
 *
 * <p><b>与 space 的两处有意的加固（行为不变）</b>：{@code core_tick_time} 与
 * {@code pause} 在 space 里是普通静态字段，却由<b>命令线程写、本线程读</b>；
 * 我们加上 {@code volatile}。这不是设计差异，是补上原版的可见性缺口 ——
 * 一个数据竞争和一条错日志一样，都不属于架构。</p>
 *
 * <p><b>与本项目的一处有意差异（已记录）</b>：space 里
 * {@code Tick_RunTime != 0} 分支打的是 {@code info("...Can't create new thread!
 * TickRunTime is zero!")} —— 日志文案写反了（成功路径却报"无法创建线程"）。
 * 我们只修正<b>文案</b>，判定条件与调度行为完全照抄。日志不是架构。</p>
 *
 * <p><b>另一处原样保留的不对称</b>：字段初值 {@code core_tick_time = 0.72}，
 * 而 {@link #stopThread()} 复位成 {@code 0.01}。两者都照抄 space ——
 * 实际运行中 {@link #startThread()} 一定会用配置值覆盖，只有当"没启动线程却去读
 * {@code core_tick_time}"时才会看到这个初值（{@code Meteoroid} 的烧蚀计算会读它）。</p>
 */
public abstract class OrbitPhysicalThread {

    private static Timer timer;
    private static TimerTask task;
    /** 每秒步数（Hz），由配置喂入。 */
    public static int core_tick_speed = 100;
    /**
     * 每步 dt（秒）。积分器与 {@code Meteoroid} 的烧蚀计算都读它。
     *
     * <p><b>它同时是速度旋钮</b>（见类注释）：{@code /polymech kelvin speed <倍率>}
     * 直接改这个字段。原生 cosmos 的 dt 是<b>逐步传入</b>的
     * （{@code cosmosWorldStep(handle, dt)}，创建时那个 dt 被忽略），
     * 所以运行中改它立刻生效，两条积分路径（原生 / 纯 Java 兜底）都吃到新值。</p>
     */
    public static volatile double core_tick_time = 0.72;
    /** 暂停开关，由 {@code /polymech kelvin pause <bool>} 改。 */
    public static volatile boolean pause = false;
    private static int tick_record;
    private static long last_time;
    /** 上一秒实际跑了多少步（诊断用：应恒等于 {@link #core_tick_speed}）。 */
    public static int tick;

    /**
     * 自线程启动以来累计推进的<b>模拟秒数</b>（每步 +dt，由本线程累加）。
     *
     * <p>它是"模拟时间"的单调时钟：与墙钟对比即可验证时间比
     * （1× 时两者应同步增长，N× 时是 N 倍），也是位置日志里判断
     * "存档往返后位置是否接着走"的参照。线程启动时归零。</p>
     */
    public static volatile double simulatedSeconds = 0.0;

    public static void startThread() {
        // 撞名即放弃：两个同名线程会把天体推进两次（见类注释）
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        for (long threadId : threadMXBean.getAllThreadIds()) {
            var info = threadMXBean.getThreadInfo(threadId);
            if (info != null && "OrbitPhysicalThread".equals(info.getThreadName())) {
                Polymech.LOGGER.error("[Kelvin] [Physics] 已存在同名线程，拒绝重复启动！"
                        + "重复启动会把天体每步推进两次。");
                return;
            }
        }

        core_tick_speed = Config.CORE_TICK_SPEED.get();
        core_tick_time = Config.CORE_TICK_TIME.get();
        pause = false;
        simulatedSeconds = 0.0;
        timer = new Timer("OrbitPhysicalThread");
        task = new TimerTask() {
            @Override
            public void run() {
                boolean run = !OrbitPhysicalThread.pause;
                // 单机 + 客户端暂停（Esc 菜单）时停步，否则开菜单期间行星仍在飞
                if (ServerLifecycleHooks.getCurrentServer() != null
                        && ServerLifecycleHooks.getCurrentServer().isSingleplayer()
                        && Minecraft.getInstance().isPaused()) {
                    run = false;
                }

                // dt 为 0 会静默冻结天体，宁可给一个明显不对的大步长
                if (OrbitPhysicalThread.core_tick_time == 0.0) {
                    OrbitPhysicalThread.core_tick_time = 1.0;
                }

                if (run) {
                    // 每周期**恰好一步**（照 space）：先 flush 游戏线程入队的改动，再积分。
                    // 速度靠 core_tick_time 放大，不靠步数 —— 见类注释。
                    for (SpaceWorld spaceWorld : ServerSpaceWorld.getAllSpaceWorld()) {
                        spaceWorld.up();
                        spaceWorld.step(OrbitPhysicalThread.core_tick_time);
                        OrbitPhysicalThread.simulatedSeconds += OrbitPhysicalThread.core_tick_time;
                    }
                }

                OrbitPhysicalThread.tick_record++;
                if (System.currentTimeMillis() >= OrbitPhysicalThread.last_time + 1000L) {
                    OrbitPhysicalThread.tick = OrbitPhysicalThread.tick_record;
                    OrbitPhysicalThread.tick_record = 0;
                    OrbitPhysicalThread.last_time = System.currentTimeMillis();
                }
            }
        };
        int Tick_RunTime = 1000 / core_tick_speed;
        if (Tick_RunTime != 0) {
            timer.scheduleAtFixedRate(task, 0L, Tick_RunTime);
            Polymech.LOGGER.info("[Kelvin] [Physics] 天体物理线程已启动：{}Hz，dt={}s（= 1× 基准 {}s）",
                    core_tick_speed, core_tick_time, Config.CORE_TICK_TIME.get());
        } else {
            // core_tick_speed > 1000 时整数除法得 0，无法成为周期
            Polymech.LOGGER.error("[Kelvin] [Physics] 周期为 0，无法创建天体物理线程！请把 coreTickSpeed 调小。");
        }
    }

    public static void stopThread() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        core_tick_speed = 100;
        core_tick_time = 0.01;
        pause = false;
        simulatedSeconds = 0.0;
    }
}
