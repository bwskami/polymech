package com.mss.polymech.mps.kelvin;

import com.mss.polymech.Polymech;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 天体位置日志 —— 给"人在游戏里、日志在外"的验收用（用户明确要求的抓取方式）。
 *
 * <h2>为什么需要它（每条对应一次踩坑）</h2>
 * <ol>
 *   <li><b>存档往返</b>（进/出世界、换维度）：天体位姿必须<b>接着走</b>，不能回到静态
 *       J2000、也不能瞬移。人在游戏里没法一边看天一边抄数字，所以周期性把
 *       {@code kelvin=}（积分位置）与 {@code gamePos=}（渲染真正用的值）写进日志。</li>
 *   <li><b>传送落点</b>：落点必须在目标行星<b>当前</b>位置的上方（行星现在会动）。
 *       所以换维度时立刻打一行，并附<b>离玩家最近的三颗天体 + 距离</b> ——
 *       这样"落在哪、离目标多远"不需要额外提问就能从日志读出来。</li>
 *   <li><b>时间比自证</b>：{@link OrbitPhysicalThread#simulatedSeconds} 是模拟时钟，
 *       与日志时间戳（墙钟）对比即可验证 1× 是不是真的 1×。</li>
 * </ol>
 *
 * <h2>为什么挂在客户端 tick 上</h2>
 * 渲染取的是<b>客户端显示世界</b>（{@link SpaceWorld#gamePos} 里那个优先来源），
 * 而显示世界每帧由 {@code SpaceRenderer} 的 {@code syncMoveData()} 从网络缓冲区搬过来。
 * 挂在客户端 = 打出来的就是"玩家眼睛看到的那个值"，而不是服务端积分侧的另一个真相；
 * 两者曾经不一致过（见 {@code docs/mps-clone-plan.md} 第 25 节），所以这里同时打印来源。
 *
 * <p>节流：周期 5 秒一行（3 颗天体），换维度时额外立刻一行。不是每 tick —— 天体日志
 * 每 tick 打会把日志淹掉（本项目已经吃过 {@code [物理驱动入参]} 每 tick 刷屏的亏）。</p>
 */
public final class KelvinDiagnostics {

    private KelvinDiagnostics() {
    }

    /** 周期日志间隔（毫秒）。 */
    private static final long PERIOD_MS = 5000L;

    /** 周期日志里固定打印的天体（彼此拉开，漂移一眼可见）。 */
    private static final String[] PERIODIC_BODIES = {"earth", "mars", "jupiter"};

    private static long lastLogMs = 0L;
    private static ResourceLocation lastDimension = null;
    private static boolean announcedWorld = false;

    /** 由 {@code ClientPhysicsDriver} 每客户端 tick 调用（任何维度都会跑）。 */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            // 退回主菜单/未进世界：下次进世界时要重新报一次"从无到有"
            if (announcedWorld) {
                Polymech.LOGGER.info("[Kelvin] [位置日志] 已退出世界（此后天体位置日志暂停）");
                announcedWorld = false;
            }
            lastDimension = null;
            return;
        }

        ResourceLocation dim = mc.level.dimension().location();
        long now = System.currentTimeMillis();
        boolean changed = !dim.equals(lastDimension);
        if (!announcedWorld) {
            changed = true;
            announcedWorld = true;
        }
        if (!changed && now - lastLogMs < PERIOD_MS) {
            return;
        }
        lastLogMs = now;
        lastDimension = dim;

        if (changed) {
            Polymech.LOGGER.info("[Kelvin] [位置日志] ★换维度/进世界 → {}（玩家 {}）{}",
                    dim, fmtVec(player.getX(), player.getY(), player.getZ()),
                    nearBodies(player));
        }
        logBodies(dim, player);
    }

    /** 一行表头 + 每颗天体一行：积分位置（米）与渲染用的游戏坐标（格）。 */
    private static void logBodies(ResourceLocation dim, LocalPlayer player) {
        double dt = OrbitPhysicalThread.core_tick_time;
        double base = com.mss.polymech.Config.CORE_TICK_TIME.get();
        Polymech.LOGGER.info("[Kelvin] [位置日志] 维={} 玩家={} 模拟t={}s dt={}s(={}×) 步/秒={} 暂停={} 来源={}",
                dim, fmtVec(player.getX(), player.getY(), player.getZ()),
                String.format("%.1f", OrbitPhysicalThread.simulatedSeconds),
                String.format("%.3f", dt),
                String.format("%.2f", base == 0.0 ? 1.0 : dt / base),
                OrbitPhysicalThread.tick, OrbitPhysicalThread.pause, SpaceWorld.kelvinPosSource());
        for (String id : PERIODIC_BODIES) {
            RealAstroData data = RealAstroData.byId(id);
            if (data == null) {
                continue;
            }
            double[] gp = SpaceWorld.gamePos(data);
            Polymech.LOGGER.info("[Kelvin] [位置日志]   {} gamePos(格)=({}, {}, {})",
                    id, String.format("%.1f", SpaceWorld.toMc(gp[0])),
                    String.format("%.1f", SpaceWorld.toMc(gp[1])),
                    String.format("%.1f", SpaceWorld.toMc(gp[2])));
        }
    }

    /**
     * 离玩家最近的三颗天体（按 {@link SpaceWorld#gamePos} 的格坐标算）。
     *
     * <p>传送落点测试全靠这一项：落点若正确，日志里"最近的天体"就是刚传过去那颗、
     * 且距离应在"半径 ~ 2.2×半径"量级；若落在空处，距离会明显不对。</p>
     */
    private static String nearBodies(LocalPlayer player) {
        record Hit(String id, double distMc, double[] gp) {
        }
        List<Hit> hits = new ArrayList<>();
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (RealAstroData data : RealAstroData.BODIES) {
            double[] gp = SpaceWorld.gamePos(data);
            double x = SpaceWorld.toMc(gp[0]);
            double y = SpaceWorld.toMc(gp[1]);
            double z = SpaceWorld.toMc(gp[2]);
            double d = Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
            hits.add(new Hit(data.id(), d, new double[]{x, y, z}));
        }
        hits.sort(Comparator.comparingDouble(Hit::distMc));
        StringBuilder sb = new StringBuilder("  最近天体:");
        for (int i = 0; i < Math.min(3, hits.size()); i++) {
            Hit h = hits.get(i);
            sb.append(String.format("%n  %s 距离=%.0f 格 于=(%.0f, %.0f, %.0f)",
                    h.id(), h.distMc(), h.gp()[0], h.gp()[1], h.gp()[2]));
        }
        return sb.toString();
    }

    private static String fmtVec(double x, double y, double z) {
        return String.format("(%.1f, %.1f, %.1f)", x, y, z);
    }
}
