import com.mss.polymech.client.renderer.RenderPassGuard;
import com.mss.polymech.physics.PhysgunBeamShape;

/**
 * 牵引枪渲染的**离线判据**：{@link PhysgunBeamShape}（纯数学）+ {@link RenderPassGuard}（纯状态机），
 * 两者都零 MC 依赖。
 *
 * <h2>为什么这些必须离线判</h2>
 * "看起来像不像"当然只能实机看，但<b>下面这些错了就一定是坏了</b>，而且实机只表现为
 * "光束怪异"（抖得离谱 / 细成一条 / 转到某个角度就消失 / 松手不消失），根因全是数学：
 * <ol>
 *   <li>曲线段数（太长会卡、太短会看出折角）；</li>
 *   <li>贝塞尔：两端必须<b>正好</b>落在枪口与物体上，且 t 从 0 到 1 单调推进（不折返）；</li>
 *   <li>"虚拟竿尖"的软弹簧：必须收敛、必须稳定、落后量必须可见但有上限
 *       （这就是"钓鱼竿受力弯曲"的来源；2026-09-29 用户把"酷似闪电"的节点抖动换成了它）；</li>
 *   <li>相机朝向正交基：必须单位且两两垂直，<b>相机落在光束轴上时不能出 NaN</b>
 *       （否则顶点变 NaN ⇒ 整批网格被丢弃 ⇒ "光束突然消失"）；</li>
 *   <li>四边形：角点到轴线的距离必须正好是半宽（不然线宽会随视角忽粗忽细）；</li>
 *   <li>正对相机的方块（端点光斑 / lock 标记）：必须真的正对相机，且在"相机在正上方/正中心"时也不退化；</li>
 *   <li><b>悬停格的面几何</b>（第 8 节）：格占 {@code [x, x+1)}（最小角约定）。这一条是拿真实 bug 换来的
 *       —— 之前"面放在格心 ±0.495"，于是棋盘整体平移半格、飘到体轮廓之外；</li>
 *   <li>强度曲线：抓住时必须收敛到 1 且不超调，松手后必须在参考阈值处被移除
 *       （第一版是硬切，用户看到的"廉价"里就有这一条）；</li>
 *   <li><b>渲染通道守卫</b>（第 10 节）：2026-09-29 右键抓取<b>直接崩客户端</b>，
 *       报的是不好读的 {@code IllegalStateException: Not building!}。根因是共享
 *       {@code BufferSource} 同一时刻只允许一个"非 fixed"通道在写，我先取了两个通道
 *       （BEAM + OVERLAY），取第二个时第一个被提前 flush，回头再往第一个写就炸。
 *       这一节把"重入必须立刻抛一条能读懂的异常"钉成判据。</li>
 * </ol>
 *
 * <p>参考实现：{@code Simulated-Project-main/.../physics_staff/PhysicsStaffClientHandler.java}
 * 的 {@code PhysicsBeam}（节点链 + {@code LineOutline} 真实线宽）与
 * {@code PhysicsStaffRenderHandler}（棋盘悬停框）—— 但**节点链那套"闪电"形状已被用户否掉**
 * （辨识度太高、会与航空学撞车），现在是"贝塞尔 + 软弹簧受力弯曲"。本探针第 0 节把形状参数
 * 当<b>数值锚点</b>钉住：以后谁改了常数，这里会 FAIL 而不是"悄悄变了个手感"。</p>
 */
public final class PhysgunBeamShapeProbe {

    private static int fails = 0;

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "[ OK ] " : "[FAIL] ") + what + "    " + detail);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] args) {
        System.out.println("================ 牵引枪光束几何离线判据 ================");
        System.out.printf("参数：采样间距=%.2f 段数=%d..%d 收细=%.2f%n",
                PhysgunBeamShape.SEG_SPACING, PhysgunBeamShape.MIN_SEGMENTS,
                PhysgunBeamShape.MAX_SEGMENTS, PhysgunBeamShape.TAPER);
        System.out.printf("      竿尖弹簧 K=%.0f D=%.0f 落后上限=%.1f 弯曲增益=%.2f 基础弓形=%.2f/格(≤%.2f)%n",
                PhysgunBeamShape.LAG_K, PhysgunBeamShape.LAG_D, PhysgunBeamShape.LAG_MAX,
                PhysgunBeamShape.BEND_GAIN, PhysgunBeamShape.BOW_PER_BLOCK, PhysgunBeamShape.BOW_MAX);
        System.out.printf("      松手衰减=%.2f 移除阈值=%.2f 爬升率=%.2f%n",
                PhysgunBeamShape.RELEASE_DECAY, PhysgunBeamShape.REMOVE_BELOW, PhysgunBeamShape.GROW_RATE);
        System.out.println();

        // ---------- 0) 数值锚点 ----------
        System.out.println("--- 0) 数值锚点（形状参数）---");
        check("采样间距 = 0.75 格（曲线段长；越小越平滑、段数越多）",
                PhysgunBeamShape.SEG_SPACING == 0.75, "实得 " + PhysgunBeamShape.SEG_SPACING);
        check("最少段数 = 6", PhysgunBeamShape.MIN_SEGMENTS == 6, "实得 " + PhysgunBeamShape.MIN_SEGMENTS);
        check("最多段数 = 48（长光束也不会把顶点数撑爆）",
                PhysgunBeamShape.MAX_SEGMENTS == 48, "实得 " + PhysgunBeamShape.MAX_SEGMENTS);
        check("竿尖弹簧刚度 K = 160（ω≈12.6 ⇒ 约 0.3 秒归位）",
                PhysgunBeamShape.LAG_K == 160.0, "实得 " + PhysgunBeamShape.LAG_K);
        check("竿尖阻尼 D = 15（ζ≈0.6 ⇒ 有一点回弹但不过冲成振荡）",
                PhysgunBeamShape.LAG_D == 15.0, "实得 " + PhysgunBeamShape.LAG_D);
        check("落后量上限 = 3 格（一次大瞬移不至于把曲线拉成麻花）",
                PhysgunBeamShape.LAG_MAX == 3.0, "实得 " + PhysgunBeamShape.LAG_MAX);
        check("弯曲增益 = 1.5", PhysgunBeamShape.BEND_GAIN == 1.5, "实得 " + PhysgunBeamShape.BEND_GAIN);
        check("末端收细 = 55%（竿形：根粗尖细）",
                PhysgunBeamShape.TAPER == 0.55, "实得 " + PhysgunBeamShape.TAPER);
        check("松手衰减 = 0.6（照参考 intensity *= .6f）",
                PhysgunBeamShape.RELEASE_DECAY == 0.6, "实得 " + PhysgunBeamShape.RELEASE_DECAY);
        check("移除阈值 = 0.4（照参考 removeIf(intensity < .4f)）",
                PhysgunBeamShape.REMOVE_BELOW == 0.4, "实得 " + PhysgunBeamShape.REMOVE_BELOW);
        System.out.println();

        // ---------- 1) 段数 ----------
        System.out.println("--- 1) 曲线段数（长度 → 段数）---");
        check("长度 0 ⇒ 最少 6 段（退化也不为 0，避免除零/画不出）",
                PhysgunBeamShape.segments(0.0) == 6, "实得 " + PhysgunBeamShape.segments(0.0));
        check("负长度 ⇒ 最少 6 段", PhysgunBeamShape.segments(-5.0) == 6,
                "实得 " + PhysgunBeamShape.segments(-5.0));
        check("长度 3 格 ⇒ 6 段（4 段被抬到下限）",
                PhysgunBeamShape.segments(3.0) == 6, "实得 " + PhysgunBeamShape.segments(3.0));
        check("长度 10 格 ⇒ 13 段（0.75 格一段）",
                PhysgunBeamShape.segments(10.0) == 13, "实得 " + PhysgunBeamShape.segments(10.0));
        check("长度 100 格 ⇒ 48 段（被上限截住）",
                PhysgunBeamShape.segments(100.0) == 48, "实得 " + PhysgunBeamShape.segments(100.0));
        boolean segMono = true;
        int prevSeg = -1;
        for (double len = 1.0; len <= 200.0; len += 1.0) {
            int c = PhysgunBeamShape.segments(len);
            if (c < prevSeg) {
                segMono = false;
                break;
            }
            prevSeg = c;
        }
        check("段数随长度单调不减（1..200 格全扫）", segMono, "扫描 200 个长度");
        System.out.println();

        // ---------- 2) 收细（竿形）----------
        System.out.println("--- 2) 收细 taper()（越靠近竿尖越细）---");
        check("根部 t=0 ⇒ 1.0（原宽）", near(PhysgunBeamShape.taper(0.0), 1.0),
                String.format("%.4f", PhysgunBeamShape.taper(0.0)));
        check("尖端 t=1 ⇒ 0.45（收细 55%）", near(PhysgunBeamShape.taper(1.0), 0.45),
                String.format("%.4f", PhysgunBeamShape.taper(1.0)));
        check("中点 t=0.5 ⇒ 0.725", near(PhysgunBeamShape.taper(0.5), 0.725),
                String.format("%.4f", PhysgunBeamShape.taper(0.5)));
        boolean taperMono = true;
        double lastW = 2.0;
        for (int i = 0; i <= 10; i++) {
            double w = PhysgunBeamShape.taper(i / 10.0);
            if (w > lastW + 1e-12) {
                taperMono = false;
            }
            lastW = w;
        }
        check("沿曲线单调变细（不会中途变粗）", taperMono, "扫了 11 个 t");
        check("t 越界被夹住（不会算出负宽度）",
                near(PhysgunBeamShape.taper(-3.0), 1.0) && near(PhysgunBeamShape.taper(9.0), 0.45),
                String.format("%.3f / %.3f", PhysgunBeamShape.taper(-3.0), PhysgunBeamShape.taper(9.0)));
        System.out.println();

        // ---------- 3) 贝塞尔 ----------
        System.out.println("--- 3) 贝塞尔 bezier() ---");
        double[] start = {0, 0, 0};
        double[] end = {10, 0, 0};
        double[] ctrl = {5, 0, 0};
        double[] out = new double[3];
        PhysgunBeamShape.bezier(0.0, start, ctrl, end, out);
        check("t=0 ⇒ 正好在起点", near(out[0], 0.0) && near(out[1], 0.0), fmt(out));
        PhysgunBeamShape.bezier(1.0, start, ctrl, end, out);
        check("t=1 ⇒ 正好在终点", near(out[0], 10.0), fmt(out));
        double[] ctrlMid = {5, 0, 0};
        PhysgunBeamShape.bezier(0.5, start, ctrlMid, end, out);
        check("控制点 = 中点时，t=0.5 落在 (5,0,0)（无弯 ⇒ 退化成直线）",
                near(out[0], 5.0) && near(out[1], 0.0), fmt(out));
        double[] ctrlUp = {5, 3, 0};
        PhysgunBeamShape.bezier(0.5, start, ctrlUp, end, out);
        check("控制点抬高 3 ⇒ 曲线中点抬高 1.5（(1−t)²+2(1−t)t+t² 的权重 0.5×3）",
                near(out[1], 1.5), fmt(out));
        boolean t0to1 = true;
        double prevX = -1.0;
        for (int i = 0; i <= 20; i++) {
            PhysgunBeamShape.bezier(i / 20.0, start, ctrlUp, end, out);
            if (out[0] < prevX - 1e-9) {
                t0to1 = false;
            }
            prevX = out[0];
        }
        check("t 从 0 到 1 时沿光束单调推进（不会折返）", t0to1, "扫了 21 个 t");
        System.out.println();

        // ---------- 4) 控制点：受力弯曲 ----------
        System.out.println("--- 4) 控制点 control()：受力弯曲 ---");
        double[] p0 = {0, 0, 0};
        double[] p1 = {10, 0, 0};
        double[] tipOn = {10, 0, 0};        // 竿尖已追上 ⇒ 无受力
        double[] c = new double[3];
        PhysgunBeamShape.control(p0, p1, tipOn, 10.0, c);
        // 基础弓形：10 格 × 0.02 = 0.2（正好是上限）
        check("竿尖追平（无受力）⇒ 控制点 ≈ 中点 + 基础弓形（10 格 ⇒ 0.2 封顶）",
                near(c[0], 5.0) && Math.abs(Math.hypot(c[1], c[2]) - 0.2) < 1e-9,
                fmt(c));
        double[] tipLag = {9, 0, 0};        // 竿尖落后 1 格（物体被拉向 +X）
        PhysgunBeamShape.control(p0, p1, tipLag, 10.0, c);
        check("竿尖落后 1 格 ⇒ 控制点沿受力方向偏 1×1.5 = 1.5 格",
                Math.abs((c[0] - 5.0) - 1.5) < 1e-9, fmt(c));
        double[] tipFar = {-50, 0, 0};      // 落后 60 格（夸张瞬移）
        PhysgunBeamShape.control(p0, p1, tipFar, 10.0, c);
        check("落后量按模长限幅到 3 格（⇒ 控制点偏移 = 3×1.5 = 4.5，不会拉成麻花）",
                Math.abs((c[0] - 5.0) - 4.5) < 1e-9, fmt(c));
        // 受力方向与光束垂直时，弯曲应当完全是横向的
        double[] tipSide = {10, 1, 0};
        PhysgunBeamShape.control(p0, p1, tipSide, 10.0, c);
        check("受力垂直于光束 ⇒ 控制点横向偏移（x 不变）",
                Math.abs(c[0] - 5.0) < 1e-9 && Math.abs(c[1] + 1.5) < 1e-9, fmt(c));
        check("基础弓形只走横向：两端咬住 ⇒ 控制点的 x 必须正好是 5（弓形不沿光束方向）",
                near(c[0], 5.0), fmt(c));
        double[] degenerate = {3, 3, 3};
        PhysgunBeamShape.control(degenerate, degenerate, degenerate, 0.0, c);
        check("两端点重合 ⇒ 不出 NaN", finite(c), fmt(c));
        System.out.println();

        // ---------- 4b) 竿尖软弹簧 ----------
        System.out.println("--- 4b) 竿尖软弹簧 lagStep()（钓鱼竿的\"软\"）---");
        double[] tip = {0, 0, 0};
        double[] vel = {0, 0, 0};
        double[] target = {5, 0, 0};
        PhysgunBeamShape.lagStep(tip, vel, target, PhysgunBeamShape.LAG_K, PhysgunBeamShape.LAG_D, 0.05, 4);
        check("一步（0.05 秒）后竿尖朝目标移动了、但远没到（这就是\"落后\"）",
                tip[0] > 0.05 && tip[0] < 2.0, String.format("x=%.4f", tip[0]));
        double worstLag = 0.0;
        for (int i = 0; i < 4; i++) {
            PhysgunBeamShape.lagStep(tip, vel, target, PhysgunBeamShape.LAG_K, PhysgunBeamShape.LAG_D, 0.05, 4);
            worstLag = Math.max(worstLag, Math.abs(target[0] - tip[0]));
        }
        check("0.25 秒后落后量已经明显缩小（跑到一半以上）",
                tip[0] > 2.5, String.format("x=%.4f 落后=%.4f", tip[0], target[0] - tip[0]));
        for (int i = 0; i < 20; i++) {
            PhysgunBeamShape.lagStep(tip, vel, target, PhysgunBeamShape.LAG_K, PhysgunBeamShape.LAG_D, 0.05, 4);
        }
        check("1.25 秒后收敛到目标（误差 < 1%）",
                Math.abs(tip[0] - 5.0) < 0.05, String.format("x=%.6f", tip[0]));
        // 突然瞬移：落后量应当可见但不失控
        double[] tip2 = {0, 0, 0};
        double[] vel2 = {0, 0, 0};
        double[] jump = {20, 0, 0};
        PhysgunBeamShape.lagStep(tip2, vel2, jump, PhysgunBeamShape.LAG_K, PhysgunBeamShape.LAG_D, 0.05, 4);
        double firstLag = jump[0] - tip2[0];
        check("目标瞬移 20 格时，竿尖落后量在开头最大（这正是甩视角时的弯曲来源）",
                firstLag > 15.0 && firstLag <= 20.0, String.format("落后=%.4f", firstLag));
        check("弹簧数值稳定（没有 NaN / 无穷）", finite(tip2) && finite(vel2),
                String.format("%.4f / %.4f", tip2[0], vel2[0]));
        System.out.println();

        // ---------- 5) 正交基（十字截面用）----------
        System.out.println("--- 5) 相机朝向正交基 frame() ---");
        double[] a = {0, 0, 0};
        double[] b = {10, 0, 0};
        double[] cam = {5, 5, 0};
        double[] side = new double[3];
        double[] up = new double[3];
        PhysgunBeamShape.frame(a, b, cam, side, up);
        check("side 是单位向量", near(norm(side), 1.0), String.format("|side| = %.9f", norm(side)));
        check("up 是单位向量", near(norm(up), 1.0), String.format("|up| = %.9f", norm(up)));
        check("side ⊥ 光束方向", near(dot(side, new double[]{1, 0, 0}), 0.0),
                String.format("dot = %.9f", dot(side, new double[]{1, 0, 0})));
        check("up ⊥ 光束方向", near(dot(up, new double[]{1, 0, 0}), 0.0),
                String.format("dot = %.9f", dot(up, new double[]{1, 0, 0})));
        check("side ⊥ up（两个面才是真正的十字，不会重合成一条线）",
                near(dot(side, up), 0.0), String.format("dot = %.9f", dot(side, up)));
        check("从上往下看：side 指向世界 +Z（相机面朝向正确）",
                near(side[2], 1.0) || near(side[2], -1.0), fmt(side));

        double[] camOnAxis = {5, 0, 0};      // 相机正好落在光束轴上（第一版会在这里出 NaN）
        PhysgunBeamShape.frame(a, b, camOnAxis, side, up);
        check("相机在光束轴上 ⇒ 仍是单位正交基、不出 NaN",
                finite(side) && finite(up) && near(norm(side), 1.0) && near(norm(up), 1.0)
                        && near(dot(side, up), 0.0),
                fmt(side) + " / " + fmt(up));

        double[] zero = {3, 3, 3};           // 退化：两端点重合
        PhysgunBeamShape.frame(zero, zero, cam, side, up);
        check("两端点重合 ⇒ 仍返回单位正交基、不出 NaN",
                finite(side) && finite(up) && near(norm(side), 1.0) && near(norm(up), 1.0),
                fmt(side) + " / " + fmt(up));
        System.out.println();

        // ---------- 6) 四边形（线宽）----------
        System.out.println("--- 6) 线段四边形 quad()（真实线宽）---");
        double[] q = new double[12];
        PhysgunBeamShape.quad(new double[]{0, 0, 0}, new double[]{10, 0, 0}, new double[]{0, 1, 0}, 0.05, q);
        check("角点 0 = a + n·hw = (0, 0.05, 0)",
                near(q[0], 0.0) && near(q[1], 0.05) && near(q[2], 0.0), corner(q, 0));
        check("角点 3 = b + n·hw = (10, 0.05, 0)",
                near(q[9], 10.0) && near(q[10], 0.05) && near(q[11], 0.0), corner(q, 3));
        double minD = Double.MAX_VALUE;
        double maxD = 0.0;
        for (int i = 0; i < 4; i++) {
            double d = distToXAxis(q[i * 3], q[i * 3 + 1], q[i * 3 + 2]);
            minD = Math.min(minD, d);
            maxD = Math.max(maxD, d);
        }
        check("四个角点到轴线的距离都 = 半宽 0.05（线宽不随视角变化）",
                Math.abs(minD - 0.05) < 1e-12 && Math.abs(maxD - 0.05) < 1e-12,
                String.format("[%.9f, %.9f]", minD, maxD));
        double area = quadArea(q);
        check("四边形面积 = 长 × 宽 = 10 × 0.1 = 1.0",
                Math.abs(area - 1.0) < 1e-9, String.format("面积 %.9f", area));
        System.out.println();

        // ---------- 7) 正对相机的方块（端点光斑）----------
        System.out.println("--- 7) 端点光斑 billboard() ---");
        double[] bb = new double[12];
        PhysgunBeamShape.billboard(new double[]{0, 0, 0}, new double[]{0, 0, 10}, 0.25, bb);
        boolean inPlane = true;
        double cd = 0.0;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(bb[i * 3 + 2]) > 1e-12) {
                inPlane = false;
            }
            cd = Math.hypot(Math.hypot(bb[i * 3], bb[i * 3 + 1]), bb[i * 3 + 2]);
        }
        check("相机在 +Z：四个角点都落在 z=0 平面（正对相机）", inPlane, corner(bb, 0) + " …");
        check("角点到中心的距离 = 半边长×√2 = 0.3536",
                Math.abs(cd - 0.25 * Math.sqrt(2.0)) < 1e-12, String.format("%.6f", cd));
        double sideLen = dist(bb, 0, 1);
        check("边长 = 2×半边长 = 0.5", Math.abs(sideLen - 0.5) < 1e-12, String.format("%.6f", sideLen));

        PhysgunBeamShape.billboard(new double[]{0, 0, 0}, new double[]{0, 10, 0}, 0.25, bb);
        boolean upOk = true;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(bb[i * 3 + 1]) > 1e-12) {
                upOk = false;
            }
        }
        check("相机在正上方（视线 ∥ 世界上方向）⇒ 不退化，角点仍在一个平面内", upOk, corner(bb, 0));
        check("相机与中心重合 ⇒ 不出 NaN", finite(bb), corner(bb, 0) + " …");
        System.out.println();

        // ---------- 8) 悬停格的面几何（2026-09-29 半格 bug 的判据）----------
        // 背景：这里原来判的是"棋盘奇偶"（checker(ix,iy)），而"面放在哪儿"是渲染器手写的，
        // 写成了"格心 ± 0.495" ⇒ 棋盘整体平移半格、飘到体轮廓之外（用户截图里框外的蓝方块）。
        // 现在几何抽成纯函数 cellFace()，并且判据直接钉住"块占 [x, x+1)"这条约定。
        System.out.println("--- 8) 悬停格的面几何 cellFace()（块占 [x, x+1)）---");
        double[] cell = {2, 3, 5};
        double inset = 0.005;
        double[] xyz = new double[12];
        double[] uv = new double[8];
        PhysgunBeamShape.cellFace(0, 1, cell, inset, xyz, uv);
        boolean planeOk = true;
        boolean spanOk = true;
        for (int k = 0; k < 4; k++) {
            if (Math.abs(xyz[k * 3] - (2 + 1 - inset)) > 1e-12) {
                planeOk = false;
            }
            double y = xyz[k * 3 + 1];
            double z = xyz[k * 3 + 2];
            if (y < 3 - 1e-12 || y > 4 + 1e-12 || z < 5 - 1e-12 || z > 6 + 1e-12) {
                spanOk = false;
            }
        }
        check("+X 面在平面 x = c+1−inset = 2.995 上（不是格心约定下的 2.495）", planeOk, corner(xyz, 0) + " …");
        check("+X 面在 y/z 上覆盖 [c, c+1]（块占 [x, x+1)，不是 [x−0.5, x+0.5]）", spanOk, corner(xyz, 0) + " …");
        PhysgunBeamShape.cellFace(0, -1, cell, inset, xyz, uv);
        check("−X 面在平面 x = c+inset = 2.005 上（两个面都在格里，不会跑到隔壁格）",
                Math.abs(xyz[0] - (2 + inset)) < 1e-12, corner(xyz, 0));
        double faceArea = quadArea(xyz);
        check("面的面积 = 1.0（面在**切向铺满整格**，只在法线方向内缩 0.005 ⇒ 不会与方块面 z-fight）",
                Math.abs(faceArea - 1.0) < 1e-9, String.format("%.6f", faceArea));
        boolean orderOk = true;
        for (int k = 0; k < 4; k++) {
            // 顺序必须是绕圈的相邻角点：相邻间距都 = 1；若写成了对角线（蝴蝶结），会出现 √2 ≈ 1.414
            if (Math.abs(dist(xyz, k, (k + 1) % 4) - 1.0) > 1e-9) {
                orderOk = false;
            }
        }
        check("相邻角点间距都 = 1.0 ⇒ 顺序是 u0v0→u1v0→u1v1→u0v1 的绕圈，不是蝴蝶结", orderOk,
                String.format("%.6f %.6f %.6f %.6f", dist(xyz, 0, 1), dist(xyz, 1, 2), dist(xyz, 2, 3), dist(xyz, 3, 0)));
        PhysgunBeamShape.cellFace(1, 1, cell, inset, xyz, uv);
        check("+Y 面在平面 y = 3.995 上（三根轴逐一看，避免只有 X 写对）",
                Math.abs(xyz[1] - (3 + 1 - inset)) < 1e-12, corner(xyz, 0));
        check("UV 与角点同序 = (0,0)(1,0)(1,1)(0,1)（贴图不翻、不扭曲）",
                near(uv[0], 0.0) && near(uv[1], 0.0) && near(uv[2], 1.0) && near(uv[3], 0.0)
                        && near(uv[4], 1.0) && near(uv[5], 1.0) && near(uv[6], 0.0) && near(uv[7], 1.0),
                String.format("(%.0f,%.0f)(%.0f,%.0f)(%.0f,%.0f)(%.0f,%.0f)",
                        uv[0], uv[1], uv[2], uv[3], uv[4], uv[5], uv[6], uv[7]));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                PhysgunBeamShape.cellFace(axis, sign, cell, inset, xyz, uv);
                for (int k = 0; k < 4; k++) {
                    minX = Math.min(minX, xyz[k * 3]);
                    maxX = Math.max(maxX, xyz[k * 3]);
                    minY = Math.min(minY, xyz[k * 3 + 1]);
                    maxY = Math.max(maxY, xyz[k * 3 + 1]);
                    minZ = Math.min(minZ, xyz[k * 3 + 2]);
                    maxZ = Math.max(maxZ, xyz[k * 3 + 2]);
                }
            }
        }
        check("6 个面的并集 = 整格 [c, c+1]³（各面只在法线方向内缩、切向铺满 ⇒ 并集正好是格子边界）",
                Math.abs(minX - 2.0) < 1e-9 && Math.abs(maxX - 3.0) < 1e-9
                        && Math.abs(minY - 3.0) < 1e-9 && Math.abs(maxY - 4.0) < 1e-9
                        && Math.abs(minZ - 5.0) < 1e-9 && Math.abs(maxZ - 6.0) < 1e-9,
                String.format("x[%.4f,%.4f] y[%.4f,%.4f] z[%.4f,%.4f]", minX, maxX, minY, maxY, minZ, maxZ));
        System.out.println();

        // ---------- 9) 强度曲线 ----------
        System.out.println("--- 9) 强度曲线（抓住爬升 / 松手淡出）---");
        check("抓住时从 0 ⇒ 0.5（lerp(0.5, 0, 1)）",
                near(PhysgunBeamShape.intensityStep(0.0, false), 0.5),
                String.format("%.4f", PhysgunBeamShape.intensityStep(0.0, false)));
        check("抓住时从 0.5 ⇒ 0.75", near(PhysgunBeamShape.intensityStep(0.5, false), 0.75),
                String.format("%.4f", PhysgunBeamShape.intensityStep(0.5, false)));
        check("抓住时已到 1 就不再超调（<= 1）",
                PhysgunBeamShape.intensityStep(1.0, false) <= 1.0,
                String.format("%.4f", PhysgunBeamShape.intensityStep(1.0, false)));
        double x = 0.0;
        for (int i = 0; i < 40; i++) {
            x = PhysgunBeamShape.intensityStep(x, false);
        }
        check("抓住 40 tick 后收敛到 1（不是慢慢爬）", near(x, 1.0), String.format("%.6f", x));
        check("松手：1.0 ⇒ 0.6（照参考 ×0.6）",
                near(PhysgunBeamShape.intensityStep(1.0, true), 0.6),
                String.format("%.4f", PhysgunBeamShape.intensityStep(1.0, true)));
        check("松手：0.6 ⇒ 0.36 < 阈值 0.4 ⇒ 该移除",
                PhysgunBeamShape.finished(PhysgunBeamShape.intensityStep(0.6, true), true),
                String.format("0.36 vs 阈值 %.2f", PhysgunBeamShape.REMOVE_BELOW));
        check("还在抓着时永不自动移除", !PhysgunBeamShape.finished(0.05, false), "intensity=0.05, released=false");
        check("强度不会变负", PhysgunBeamShape.intensityStep(0.0, true) >= 0.0,
                String.format("%.4f", PhysgunBeamShape.intensityStep(0.0, true)));
        System.out.println();

        // ---------- 10) 渲染通道守卫（2026-09-29 实机崩溃换来的判据）----------
        // 背景：共享 BufferSource 同一时刻只允许一个"非 fixed"通道在写（第二个 getBuffer 会把第一个
        // flush ⇒ 随后写入抛 Not building!）。守卫要在"重入"那一刻就抛一条能读懂的异常。
        System.out.println("--- 10) 渲染通道守卫 RenderPassGuard ---");
        RenderPassGuard.reset();
        check("初始没有开着的通道", RenderPassGuard.current() == null,
                String.valueOf(RenderPassGuard.current()));
        RenderPassGuard.open("A");
        check("开 A 之后 current()=A", "A".equals(RenderPassGuard.current()),
                String.valueOf(RenderPassGuard.current()));
        RenderPassGuard.close("A");
        check("关 A 之后回到空闲", RenderPassGuard.current() == null,
                String.valueOf(RenderPassGuard.current()));

        RenderPassGuard.reset();
        RenderPassGuard.open("A");
        String reentry = null;
        try {
            RenderPassGuard.open("B");
        } catch (IllegalStateException e) {
            reentry = e.getMessage();
        }
        check("A 还开着就开 B ⇒ 抛异常（这就是实机上 'Not building!' 的那一步）", reentry != null,
                reentry == null ? "没抛！" : "抛了");
        check("异常信息点名两个通道（A / B），否则又得靠猜", reentry != null
                && reentry.contains("A") && reentry.contains("B"),
                reentry == null ? "n/a" : "含 A/B");
        check("异常信息指向根因（提到 BufferSource），让人知道是共享缓冲而不是顶点写错",
                reentry != null && reentry.contains("BufferSource"), reentry == null ? "n/a" : "含 BufferSource");
        check("抛异常后状态被清空（fail-open：不让一帧的异常连坐后面每一帧）",
                RenderPassGuard.current() == null, String.valueOf(RenderPassGuard.current()));

        RenderPassGuard.reset();
        String closeNothing = null;
        try {
            RenderPassGuard.close("A");
        } catch (IllegalStateException e) {
            closeNothing = e.getMessage();
        }
        check("关一个没开的通道 ⇒ 抛异常（防止 endBatch 与 getBuffer 配错对）", closeNothing != null,
                closeNothing == null ? "没抛！" : "抛了");

        RenderPassGuard.reset();
        RenderPassGuard.open("A");
        String wrongClose = null;
        try {
            RenderPassGuard.close("B");
        } catch (IllegalStateException e) {
            wrongClose = e.getMessage();
        }
        check("开 A 却关 B ⇒ 抛异常", wrongClose != null, wrongClose == null ? "没抛！" : "抛了");
        check("关错通道后状态同样清空", RenderPassGuard.current() == null,
                String.valueOf(RenderPassGuard.current()));

        RenderPassGuard.reset();
        RenderPassGuard.open("A");
        RenderPassGuard.close("A");
        RenderPassGuard.open("B");
        RenderPassGuard.close("B");
        check("正确用法（一个通道一趟、顺序开关）不抛任何异常且回到空闲",
                RenderPassGuard.current() == null, String.valueOf(RenderPassGuard.current()));
        System.out.println();

        System.out.println(fails == 0 ? "==== 光束几何判据 PASS ====" : ("==== 有 " + fails + " 项 FAIL ===="));
        if (fails != 0) {
            System.exit(1);
        }
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static boolean finite(double[] v) {
        for (double d : v) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return false;
            }
        }
        return true;
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double dot(double[] p, double[] qv) {
        return p[0] * qv[0] + p[1] * qv[1] + p[2] * qv[2];
    }

    /** 点到 X 轴（过原点、方向 +X）的距离。 */
    private static double distToXAxis(double x, double y, double z) {
        return Math.hypot(y, z);
    }

    /** 12 分量角点组成的四边形面积（两个三角形之和）。 */
    private static double quadArea(double[] q) {
        double[] c0 = {q[0], q[1], q[2]};
        double[] c1 = {q[3], q[4], q[5]};
        double[] c2 = {q[6], q[7], q[8]};
        double[] c3 = {q[9], q[10], q[11]};
        double[] e1 = {c1[0] - c0[0], c1[1] - c0[1], c1[2] - c0[2]};
        double[] e2 = {c2[0] - c0[0], c2[1] - c0[1], c2[2] - c0[2]};
        double[] e3 = {c3[0] - c0[0], c3[1] - c0[1], c3[2] - c0[2]};
        return 0.5 * norm(cross(e1, e2)) + 0.5 * norm(cross(e2, e3));
    }

    private static double[] cross(double[] p, double[] qv) {
        return new double[]{p[1] * qv[2] - p[2] * qv[1], p[2] * qv[0] - p[0] * qv[2],
                p[0] * qv[1] - p[1] * qv[0]};
    }

    private static double dist(double[] q, int i, int j) {
        return Math.sqrt(Math.pow(q[i * 3] - q[j * 3], 2) + Math.pow(q[i * 3 + 1] - q[j * 3 + 1], 2)
                + Math.pow(q[i * 3 + 2] - q[j * 3 + 2], 2));
    }

    private static String corner(double[] v, int i) {
        return String.format(java.util.Locale.ROOT, "(%.6f, %.6f, %.6f)", v[i * 3], v[i * 3 + 1], v[i * 3 + 2]);
    }

    private static String fmt(double[] v) {
        return String.format(java.util.Locale.ROOT, "(%.6f, %.6f, %.6f)", v[0], v[1], v[2]);
    }
}
