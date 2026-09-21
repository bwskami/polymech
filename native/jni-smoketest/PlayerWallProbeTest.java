import com.mss.polymech.physics.NativePhysics;

/**
 * 「玩家慢速靠近方块/船被弹飞」的<b>离线单变量实验</b>（对应 docs/mps-clone-plan.md §21）。
 *
 * <p>为什么要有它：§21 原来的第一嫌疑是"位置读回减了两次中心偏移"，需要开游戏才能验。
 * 但那条嫌疑的<b>前提本身</b>是错的（详见下方"约定"一节），而真正的现象
 * ——"自己的速度链在接触里把玩家蹬飞"——完全可以在<b>不启动游戏</b>的情况下复现或排除：
 * 这条链只碰原生刚体（{@code PlayerPhysicsBody.velocityChain} 跑在物理步进线程上），
 * 不碰任何 Minecraft 对象。</p>
 *
 * <h2>约定（本测试与生产代码逐条对齐，不是近似）</h2>
 * <ul>
 *   <li><b>我们的约定</b>：刚体原点 = <b>碰撞箱中心</b>；碰撞体挂在局部 {@code (0,0,0)}
 *       （原生 {@code colliderAttachCuboid*} 没有局部平移参数，见
 *       {@code native/polymech-physics/src/lib.rs} 的 {@code ColliderBuilder::cuboid} 后无 {@code .translation()}）。
 *       故 {@code PlayerPhysicsBody.create} 收到的是 {@code 脚底 + centerOffset}，
 *       {@code ClientPhysics} 读回时必须 {@code -centerOffset}（<b>减一次</b>）。</li>
 *   <li><b>space 的约定</b>（{@code PhysicalEntity:83}）：刚体原点 = <b>脚底</b>；
 *       碰撞体挂在局部 {@code (0, halfHeight, 0)}。所以它读回时直接 {@code setPos(pos)}。
 *       两者把盒子放在世界里的位置<b>完全相同</b>，是等价约定，不是"少减一次"。</li>
 *   <li>因此本实验里夹具按<b>我们的</b>约定摆：脚底 y=1.0 ⇒ 刚体 y=1.9（halfHeight 0.9）。</li>
 * </ul>
 *
 * <h2>单变量</h2>
 * <p>场景固定为「站在地形上，水平走向一堵墙」，逐个改变<b>一个</b>量：</p>
 * <ol>
 *   <li>{@code own.x}（走近墙的水平输入速度）：1.0 m/s（"慢速靠近"）与 4.317 m/s（步行）；</li>
 *   <li>{@code own.y}（原版那一格位移带来的垂直输入）：0 与 +1.6 —— +1.6 是
 *       {@code NativeSmokeTest.smokePlayerRig} 已实测"会把玩家一路蹬上天"的值
 *       （y 2.90 → 8.84）。若 {@code own.y=0} 时链子也弹人，根因在求解器接触；
 *       若只有 {@code own.y>0} 才弹，根因就在"原版给了正的 movement.y"。</li>
 * </ol>
 *
 * <p>用法：{@code java -cp <classes> PlayerWallProbeTest <原生库绝对路径|留空>}。
 * 它是一份<b>实验</b>而非 pass/fail 回归，永远以退出码 0 结束（除非抛异常）。</p>
 */
public class PlayerWallProbeTest {

    private static final double DT = 0.01;
    private static final double HALF_W = 0.3;
    private static final double HALF_H = 0.9;
    /** 墙的 +x 面所在的方块坐标（体素格 x=4 的左表面）。 */
    private static final double WALL_FACE_X = 4.0;
    /** 地形顶面（体素格 y=0 的上表面）。 */
    private static final double FLOOR_TOP_Y = 1.0;
    private static final double START_X = 0.5;

    private static long world;

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && !args[0].isBlank()) {
            System.load(args[0]);
        } else {
            System.load(new java.io.File("src/main/resources/natives/windows_amd64/polymech_physics.dll")
                    .getAbsolutePath());
        }
        System.out.println("ABI = " + NativePhysics.abiVersion());
        System.out.println();
        System.out.println("约定：刚体原点 = 碰撞箱中心（脚底 " + (FLOOR_TOP_Y) + " ⇒ 刚体 y="
                + (FLOOR_TOP_Y + HALF_H) + "）；碰撞体局部 (0,0,0)；halfExtents = ("
                + HALF_W + "," + HALF_H + "," + HALF_W + ")");
        System.out.println();

        // ── 矩阵：own.x × own.y ──
        run("A 慢速靠近（own.x=1.0, own.y=0）", 1.0, 0.0);
        run("B 步行靠近（own.x=4.317, own.y=0）", 4.317, 0.0);
        run("C 慢速 + 正垂直输入（own.x=1.0, own.y=+1.6）", 1.0, 1.6);
        run("D 步行 + 正垂直输入（own.x=4.317, own.y=+1.6）", 4.317, 1.6);
        run("E 原地不动（own.x=0, own.y=0，对照组）", 0.0, 0.0);

        // ── 关键对照：原版「起跳」是一发一次性冲量（delta.y = 0.42 → own.y = 8.4），
        //    之后每 tick 按原版重力衰减。§21 日志里 v前=(0,16.77,1.95)，而 2×0.42×20 = 16.8。
        jump("F 起跳（own.y: 8.4 → 每 tick 衰减，等效原版 delta.y=0.42 序列）", 0.0);
        jump("G 起跳 + 水平走（own.x=4.317）", 4.317);

        // ── 真根因：垂直记账（回灌的 +0.08 只在 getGravity()==0.08 时才抵消得掉）──
        verticalBookkeeping();
        deck();

        // ── 用户实测的方向律：只有"从正上方向下撞"才被弹，其他方向不会 ──
        impact();

        // ── 顶住物理体时的位置抖动（"抽搐"的物理侧幅度）──
        pressJitter();

        System.out.println();
        System.out.println("PLAYER WALL PROBE DONE");
    }

    /** 跑一个场景：4 秒（400 子步 / 80 tick），先自由走近墙，再持续顶住墙。 */
    private static void run(String title, double ownX, double ownY) {
        world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
        long terrain = -1L;
        Rig rig = null;
        try {
            NativePhysics.worldSetTimestep(world, DT);
            terrain = buildTerrain();

            // 刚体原点 = 脚底 + halfHeight（我们的约定）
            rig = new Rig(START_X, FLOOR_TOP_Y + HALF_H, 0.5);

            double bodyY0 = FLOOR_TOP_Y + HALF_H;
            double maxSpeed = 0.0;
            double maxUpSpeed = 0.0;
            double maxStepDp = 0.0;
            double maxRise = 0.0;
            double maxFall = 0.0;
            double wallContactX = Double.NaN;
            double firstBounceSpeed = 0.0;
            int suspiciousSteps = 0;
            double[] prev = new double[3];
            NativePhysics.bodyReadTranslation(world, rig.main, prev);
            boolean reachedWall = false;

            for (int step = 0; step < 400; step++) {
                // 原版每 tick 调用一次 move()（20Hz）→ own 每 5 个子步更新一次
                if (step % 5 == 0) {
                    rig.ownX = ownX;
                    rig.ownY = ownY;
                }
                rig.probeGroundResult();          // ClientPhysics.probeGround（只影响 onGround，不影响链子）
                NativePhysics.worldStep(world);

                double[] p0 = new double[3];
                double[] v0 = new double[3];
                NativePhysics.bodyReadTranslation(world, rig.main, p0);
                NativePhysics.bodyReadVelocity(world, rig.main, v0);

                double speed = Math.hypot(Math.hypot(v0[0], v0[1]), v0[2]);
                double stepDp = Math.hypot(Math.hypot(p0[0] - prev[0], p0[1] - prev[1]), p0[2] - prev[2]);
                maxSpeed = Math.max(maxSpeed, speed);
                maxUpSpeed = Math.max(maxUpSpeed, v0[1]);
                maxStepDp = Math.max(maxStepDp, stepDp);
                maxRise = Math.max(maxRise, p0[1] - bodyY0);
                maxFall = Math.min(maxFall, p0[1] - bodyY0);
                if (speed > 6.0) {
                    suspiciousSteps++;
                    if (firstBounceSpeed == 0.0) {
                        firstBounceSpeed = speed;
                    }
                }
                // 盒子 +x 面碰到墙的时刻
                double faceX = p0[0] + HALF_W;
                if (!reachedWall && faceX >= WALL_FACE_X - 0.02) {
                    reachedWall = true;
                    wallContactX = p0[0];
                }

                rig.chain();                // PlayerPhysicsBody.velocityChain 的逐行等价物

                prev[0] = p0[0];
                prev[1] = p0[1];
                prev[2] = p0[2];
            }

            double[] p = new double[3];
            double[] v = new double[3];
            NativePhysics.bodyReadTranslation(world, rig.main, p);
            NativePhysics.bodyReadVelocity(world, rig.main, v);

            System.out.println("── " + title + " ──");
            System.out.printf("  箱子 +x 面首次贴到墙（x=%.2f）：%s%n", WALL_FACE_X,
                    Double.isNaN(wallContactX) ? "从未贴到" : String.format("是（刚体 x=%.3f）", wallContactX));
            System.out.printf("  末态：pos=(%.3f, %.3f, %.3f)  vel=(%.3f, %.3f, %.3f)  |v|=%.3f%n",
                    p[0], p[1], p[2], v[0], v[1], v[2], Math.hypot(Math.hypot(v[0], v[1]), v[2]));
            System.out.printf("  全程：最大 |v|=%.3f m/s，最大 vy=%.3f m/s，最大单子步位移=%.4f 格"
                            + "（= %.2f m/s）%n",
                    maxSpeed, maxUpSpeed, maxStepDp, maxStepDp / DT);
            System.out.printf("  垂直漂移：最高 +%.3f 格 / 最低 %.3f 格（相对贴地基准 y=%.2f）%n",
                    maxRise, maxFall, bodyY0);
            System.out.printf("  |v|>6 m/s 的子步数 = %d / 400%s%n", suspiciousSteps,
                    firstBounceSpeed > 0 ? String.format("，首次出现时 |v|=%.3f", firstBounceSpeed) : "");
            System.out.println("  判定：" + verdict(suspiciousSteps, maxRise, maxSpeed));
            System.out.println();
        } catch (Throwable t) {
            System.out.println("── " + title + " ── 抛出异常：" + t);
            t.printStackTrace(System.out);
        } finally {
            if (rig != null) {
                rig.destroy();
            }
            if (terrain > 0) {
                NativePhysics.bodyDestroy(world, terrain);
            }
            NativePhysics.worldDestroy(world);
            world = 0;
        }
    }

    /**
     * 起跳对照：第 2 秒时按原版给一发跳跃冲量（{@code movement.y = 0.42} ⇒ {@code own.y = 8.4}），
     * 之后每个 tick 走原版的空中衰减 {@code own.y = (own.y − 1.6) × 0.98}
     *（1.6 = 0.08 格/tick 的重力 × 20）。<b>只发一次</b>，与"把它当成常驻速度"的链子对照。
     */
    private static void jump(String title, double ownX) {
        world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
        long terrain = -1L;
        Rig rig = null;
        try {
            NativePhysics.worldSetTimestep(world, DT);
            terrain = buildTerrain();
            rig = new Rig(START_X, FLOOR_TOP_Y + HALF_H, 0.5);

            double bodyY0 = FLOOR_TOP_Y + HALF_H;
            double maxSpeed = 0.0;
            double maxUpSpeed = 0.0;
            double apex = bodyY0;
            double ownY = 0.0;
            boolean airborne = false;
            int jumpTick = -1;

            for (int step = 0; step < 400; step++) {
                if (step % 5 == 0) {
                    int tick = step / 5;
                    if (tick == 40) {           // 第 2 秒起跳
                        ownY = 0.42 * 20.0;     // = 8.4
                        airborne = true;
                        jumpTick = tick;
                    } else if (airborne) {
                        ownY = (ownY - 1.6) * 0.98;
                        if (ownY <= 0.0) {
                            airborne = false;
                            ownY = 0.0;
                        }
                    }
                    rig.ownX = ownX;
                    rig.ownY = ownY;
                }
                rig.probeGroundResult();
                NativePhysics.worldStep(world);

                double[] p = new double[3];
                double[] v = new double[3];
                NativePhysics.bodyReadTranslation(world, rig.main, p);
                NativePhysics.bodyReadVelocity(world, rig.main, v);
                maxSpeed = Math.max(maxSpeed, Math.hypot(Math.hypot(v[0], v[1]), v[2]));
                maxUpSpeed = Math.max(maxUpSpeed, v[1]);
                apex = Math.max(apex, p[1]);

                rig.chain();
            }

            System.out.println("── " + title + " ──");
            System.out.printf("  第 %d tick 起跳（own.y=8.4）；链子写出的最大 vy=%.3f m/s，最大 |v|=%.3f m/s%n",
                    jumpTick, maxUpSpeed, maxSpeed);
            System.out.printf("  腾空高度：脚底最高 %.3f 格（贴地脚底 %.3f ⇒ 跳了 %.3f 格；原版跳约 1.25 格）%n",
                    apex - HALF_H, FLOOR_TOP_Y, (apex - HALF_H) - FLOOR_TOP_Y);
            System.out.printf("  若链子只按 1× 转发这一发冲量，vy 应≈8.4；" 
                            + "实测 %.3f ⇒ 倍数 %.2f%n", maxUpSpeed, maxUpSpeed / 8.4);
            System.out.println();
        } catch (Throwable t) {
            System.out.println("── " + title + " ── 抛出异常：" + t);
            t.printStackTrace(System.out);
        } finally {
            if (rig != null) {
                rig.destroy();
            }
            if (terrain > 0) {
                NativePhysics.bodyDestroy(world, terrain);
            }
            NativePhysics.worldDestroy(world);
            world = 0;
        }
    }

    /**
     * 垂直记账的复现 + 与线上日志对表 —— <b>本文件里最重要的一个函数</b>。
     *
     * <p>1.21.1 的调用顺序（`build/mcsrc/net/minecraft/world/entity/LivingEntity.java`）：</p>
     * <pre>
     * travel 地面分支:2326  vec35 = handleRelativeFrictionAndCalculateMovement(...)
     *                             └─ :2384 moveRelative(...)
     *                                :2386 this.move(SELF, getDeltaMovement())   ← 我们的回灌发生在这里（HEAD cancel）
     *                             └─ :2387 vec3 = getDeltaMovement()               ← 读到的就是我们的回灌值
     *                :2331  d2 -= d0        （d0 = this.getGravity()，:2221）
     *                :2341  setDeltaMovement(vec35.x*f3, d2*0.98, vec35.z*f3)
     * </pre>
     * <p>所以每 tick 的垂直递推是 {@code D' = 0.98 * (D + 注入 - g)}，其中
     * {@code 注入} 是我们 `ClientPhysics.drive()` 里的那个字面 {@code +0.08}，
     * {@code g = entity.getGravity()}。固定点 {@code D* = 49 * (注入 - g)}（格/tick）。</p>
     *
     * <p><b>对表</b>：用户 9/18 20:16 的客户端日志（维度 {@code poly_mech:space}，标注世界重力 {@code -0.0}）
     * 里 {@code own.y} 依次是 1.57 / 7.53 / 14.34 / 20.50 / 26.06 / 31.09 —— 与
     * {@code 注入=0.08, g=0} 那条递推的第 1/5/10/15/20/25 tick 逐项吻合（日志节流 250ms = 5 tick）。
     * 这就是"慢速靠近船/方块被弹飞"的根因：只要 {@code getGravity() != 0.08}，
     * 回灌就每 tick 净增 {@code 0.08 - g}，10 秒内把 {@code own.y} 顶到几十 m/s，
     * 链子再把它 ×2 写进主刚体。</p>
     */
    private static void verticalBookkeeping() {
        System.out.println("── H vanilla 垂直记账：D' = 0.98*(D + 注入 - g)，固定点 D* = 49*(注入-g) ──");
        System.out.printf("  %-34s %8s %8s %8s %8s %8s %8s   %s%n",
                "场景", "t=1", "t=5", "t=10", "t=15", "t=20", "t=25", "D* (m/s)");
        row("主世界 注入0.08 g=0.08", 0.08, 0.08);
        row("火星   注入0.08 g=0.0304", 0.08, 0.0304);
        row("太空   注入0.08 g=0      ", 0.08, 0.0);
        row("修后   注入=g   g=0.08    ", 0.08, 0.08);
        row("修后   注入=g   g=0.0304  ", 0.0304, 0.0304);
        row("修后   注入=g   g=0       ", 0.0, 0.0);
        System.out.printf("  %-34s %8s %8s %8s %8s %8s %8s%n", "↑ 用户日志实测（太空）",
                "1.57", "7.53", "14.34", "20.50", "26.06", "31.09");
        System.out.println();
    }

    private static void row(String name, double inject, double g) {
        double d = 0.0;
        StringBuilder sb = new StringBuilder();
        for (int t = 1; t <= 25; t++) {
            d = 0.98 * (d + inject - g);
            if (t == 1 || t == 5 || t == 10 || t == 15 || t == 20 || t == 25) {
                sb.append(String.format("%8.2f", d * 20.0));
            }
        }
        System.out.printf("  %-34s %s   %8.2f%n", name, sb, 49.0 * (inject - g) * 20.0);
    }

    /**
     * 站在太空的甲板上（g=0），回灌按 {@link #deckTick} 的递推**涌现**地喂给真实的物理夹具 ——
     * 也就是线上发生的事：主刚体被链子一路顶上天。
     *
     * <p>与 A–G 的区别：own 不再是我手写的常数，而是由"探地→回灌→travel 减重力"这条
     * 和原版逐行对应的递推算出来的，所以它同时验证了根因与后果。</p>
     *
     * @param fixed true = 把回灌换成 {@code getGravity()}（即修复后的行为）
     */
    private static void deckRun(boolean fixed) {
        double g = 0.0;                    // 太空：PlanetDimensions.gravity(SPACE) = 0 ⇒ getGravity() = 0
        double inject = fixed ? g : 0.08;  // 修复前是字面 0.08，修复后 = getGravity()
        world = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        long terrain = -1L;
        Rig rig = null;
        try {
            NativePhysics.worldSetTimestep(world, DT);
            terrain = buildTerrain();
            rig = new Rig(START_X, FLOOR_TOP_Y + HALF_H, 0.5);
            double bodyY0 = FLOOR_TOP_Y + HALF_H;
            double deltaY = 0.0;
            double maxSpeed = 0.0;
            double maxRise = 0.0;
            boolean grounded = true;
            for (int step = 0; step < 600; step++) {
                if (step % 5 == 0) {
                    // 与原版同序：drive() 先探地（得到本 tick 的 onGround），再决定要不要回灌
                    grounded = rig.probeGroundResult();
                    deltaY = 0.98 * (deltaY + (grounded ? inject : 0.0) - g);
                    rig.ownX = 0.0;
                    rig.ownY = deltaY * 20.0;
                }
                NativePhysics.worldStep(world);
                double[] p = new double[3];
                double[] v = new double[3];
                NativePhysics.bodyReadTranslation(world, rig.main, p);
                NativePhysics.bodyReadVelocity(world, rig.main, v);
                maxSpeed = Math.max(maxSpeed, Math.hypot(Math.hypot(v[0], v[1]), v[2]));
                maxRise = Math.max(maxRise, p[1] - bodyY0);
                rig.chain();
            }
            double[] p = new double[3];
            double[] v = new double[3];
            NativePhysics.bodyReadTranslation(world, rig.main, p);
            NativePhysics.bodyReadVelocity(world, rig.main, v);
            System.out.printf("  注入=%-6s 末态 pos.y=%.2f（起点 %.2f），vel.y=%.2f m/s；"
                            + "全程最大 |v|=%.2f m/s，最高抬起 %.2f 格%n",
                    fixed ? "g(=0)" : "0.08", p[1], bodyY0, v[1], maxSpeed, maxRise);
        } catch (Throwable t) {
            System.out.println("  抛出异常：" + t);
        } finally {
            if (rig != null) {
                rig.destroy();
            }
            if (terrain > 0) {
                NativePhysics.bodyDestroy(world, terrain);
            }
            NativePhysics.worldDestroy(world);
            world = 0;
        }
    }

    /** 站在太空甲板上 6 秒：修复前 vs 修复后。 */
    private static void deck() {
        System.out.println("── I 站在太空甲板上 6 秒（g=0；own.y 由 H 的递推涌现给出）──");
        deckRun(false);
        deckRun(true);
        System.out.println("  结论：注入=0.08 时人被顶上天（own.y 单调收敛到 78 m/s 的推力）；"
                + "注入=getGravity()=0 时纹丝不动。");
        System.out.println();
    }

    /**
     * 方向律：<b>同一接近速度下，"从正上方落到甲板" vs "水平撞向墙"</b>。
     *
     * <p>世界取太空（g=0），回灌取修复后的 {@code getGravity() == 0} ——
     * 也就是说这里先把"垂直记账"那条线摘掉，单看<b>接触本身</b>会不会弹人。
     * 于是本节能判定用户的方向律是"根因的另一面"还是"第二个独立机制"：</p>
     * <ul>
     *   <li>若竖直那一列反弹显著（≫ 接近速度）而水平那列不反弹 → 接触确实方向相关，还有第二个 bug；</li>
     *   <li>若两列都不反弹 → 方向律来自"脚下探到了东西才回灌"这一条（即已被修掉的 +0.08）。</li>
     * </ul>
     */
    private static void impact() {
        System.out.println("── J 方向律：向下落甲板 vs 水平撞墙，两种回灌各跑一遍（太空 g=0）──");
        System.out.println("     （竖直那例按原版垂直记账下落：deltaY=−k/20 起，落地后开始回灌）");
        System.out.printf("  %-8s | %-30s | %-30s%n", "名义k", "旧：注入=0.08", "修后：注入=getGravity()=0");
        System.out.printf("  %-8s | %-14s %-15s | %-14s %-15s%n", "", "向下落甲板", "水平撞墙", "向下落甲板", "水平撞墙");
        for (double k : new double[]{1.0, 4.0, 12.0}) {
            System.out.printf("  %-8.1f | %-14s %-15s | %-14s %-15s%n", k,
                    impactRun(true, k, false), impactRun(false, k, false),
                    impactRun(true, k, true), impactRun(false, k, true));
        }
        System.out.println("  读法：`vy`=全程最大向上速度，`抬x格`=相对起点最高抬起；水平那列若 vy>0 就是被顶飞。");
        System.out.println();
    }

    /**
     * @param vertical true = 从甲板上方落下；false = 悬空向墙平移（脚下没有东西 ⇒ 探地永不命中）
     * @param fixed    true = 回灌用 getGravity()（修后），false = 字面 0.08（旧）
     */
    private static String impactRun(boolean vertical, double k, boolean fixed) {
        double g = 0.0;                                  // 太空
        double inject = fixed ? g : 0.08;
        world = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        long terrain = -1L;
        Rig rig = null;
        try {
            NativePhysics.worldSetTimestep(world, DT);
            terrain = buildTerrain();
            // 竖直：甲板上方 1.2 格；水平：悬在墙中段（地板在 y=1，盒底 2.5）
            double startY = vertical ? FLOOR_TOP_Y + HALF_H + 1.2 : FLOOR_TOP_Y + HALF_H + 0.6 + 1.0;
            rig = new Rig(0.5, startY, 0.5);
            double deltaY = vertical ? -k / 20.0 : 0.0;  // 原版 delta：下落 k m/s
            double maxUp = 0.0;
            double maxRise = 0.0;
            for (int step = 0; step < 500; step++) {
                if (step % 5 == 0) {
                    boolean grounded = rig.probeGroundResult();
                    if (grounded) {
                        deltaY += inject;                // drive() 的贴地回灌
                    }
                    deltaY = 0.98 * (deltaY - g);        // travel：减重力 + 阻力
                    rig.ownX = vertical ? 0.0 : k;
                    rig.ownY = deltaY * 20.0;
                } else {
                    rig.probeGroundResult();
                }
                NativePhysics.worldStep(world);
                double[] p = new double[3];
                double[] v = new double[3];
                NativePhysics.bodyReadTranslation(world, rig.main, p);
                NativePhysics.bodyReadVelocity(world, rig.main, v);
                maxUp = Math.max(maxUp, v[1]);
                maxRise = Math.max(maxRise, p[1] - startY);
                rig.chain();
            }
            return String.format("vy=%4.1f 抬%4.1f格", maxUp, maxRise);
        } catch (Throwable t) {
            return "异常 " + t;
        } finally {
            if (rig != null) {
                rig.destroy();
            }
            if (terrain > 0) {
                NativePhysics.bodyDestroy(world, terrain);
            }
            NativePhysics.worldDestroy(world);
            world = 0;
        }
    }

    /**
     * 顶住物理体时，主刚体位置在**子步尺度**上的抖动幅度 —— "抽搐"的物理侧到底有多大。
     *
     * <p>这是判定"抽搐"根因的关键量：</p>
     * <ul>
     *   <li>若幅度只有毫米级 ⇒ 物理是稳的，镜头抽搐纯粹来自我们**关掉了原版 tick 插值**
     *       （旧的 {@code frameWriteBack}），对齐 space（只每 tick 写一次）即可；</li>
     *   <li>若幅度到厘米级 ⇒ 链子在持续接触下确有极限环，还需要在物理侧另找办法。</li>
     * </ul>
     * 取最后 1 秒（接触已稳定）统计接触法向那一轴的位置极差。世界取太空（g=0）。
     */
    private static void pressJitter() {
        System.out.println("── K 顶住接触时：速度换向 + 位置振荡 + 慢漂移（最后 1 秒）──");
        for (double k : new double[]{1.0, 4.317, 12.0}) {
            System.out.printf("  own=%-7.3f 顶地板：%s%n  %-14s顶墙  ：%s%n", k, jitter(true, k), "", jitter(false, k));
        }
        System.out.println();
    }

    private static String jitter(boolean intoFloor, double k) {
        world = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        long terrain = -1L;
        Rig rig = null;
        try {
            NativePhysics.worldSetTimestep(world, DT);
            terrain = buildTerrain();
            // 顶地板：站在地板上、own.y 持续朝下；顶墙：悬在墙中段、own.x 持续朝 +x
            double startY = intoFloor ? FLOOR_TOP_Y + HALF_H : FLOOR_TOP_Y + HALF_H + 0.6 + 1.0;
            rig = new Rig(0.5, startY, 0.5);
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            double sum = 0.0;
            int n = 0;
            double vLo = Double.MAX_VALUE;
            double vHi = -Double.MAX_VALUE;
            int flips = 0;
            int prevSign = 0;
            double prevV = 0.0;
            double first = Double.NaN;
            double last = Double.NaN;
            for (int step = 0; step < 400; step++) {
                if (step % 5 == 0) {
                    rig.ownX = intoFloor ? 0.0 : k;
                    rig.ownY = intoFloor ? -k : 0.0;
                }
                rig.probeGroundResult();
                NativePhysics.worldStep(world);
                if (step >= 300) {                       // 后 1 秒：接触已稳定
                    double[] p = new double[3];
                    double[] v = new double[3];
                    NativePhysics.bodyReadTranslation(world, rig.main, p);
                    NativePhysics.bodyReadVelocity(world, rig.main, v);
                    double axis = intoFloor ? p[1] : p[0];
                    double vAxis = intoFloor ? v[1] : v[0];
                    if (Double.isNaN(first)) {
                        first = axis;
                    }
                    last = axis;
                    lo = Math.min(lo, axis);
                    hi = Math.max(hi, axis);
                    sum += axis;
                    n++;
                    vLo = Math.min(vLo, vAxis);
                    vHi = Math.max(vHi, vAxis);
                    int sign = Double.compare(vAxis, 0.0);
                    if (sign != 0 && prevSign != 0 && sign != prevSign && Math.abs(vAxis - prevV) > 0.05) {
                        flips++;
                    }
                    prevSign = sign;
                    prevV = vAxis;
                }
                rig.chain();
            }
            double mean = n == 0 ? 0.0 : sum / n;
            // 位置"振荡"= 对均值的最大偏离；"漂移"= 首尾差。分开看，免得把慢漂移当成抖动。
            double dev = Math.max(hi - mean, mean - lo);
            double drift = last - first;
            return String.format("换向%3d/100  速度极差%6.2f m/s  位置偏离均值%6.1f mm  漂移%+7.1f mm/秒",
                    flips, vHi - vLo, dev * 1000.0, drift * 1000.0);
        } catch (Throwable t) {
            return "异常 " + t;
        } finally {
            if (rig != null) {
                rig.destroy();
            }
            if (terrain > 0) {
                NativePhysics.bodyDestroy(world, terrain);
            }
            NativePhysics.worldDestroy(world);
            world = 0;
        }
    }

    private static String verdict(int suspiciousSteps, double maxRise, double maxSpeed) {
        if (suspiciousSteps > 0) {
            return "★ 物理层<b>复现</b>了弹飞（" + suspiciousSteps + " 个子步 |v|>6 m/s）→ 可在本夹具里离线二分";
        }
        if (maxRise > 0.5) {
            return "↑ 没有瞬时弹飞，但被持续<b>抬升</b> " + String.format("%.2f", maxRise)
                    + " 格（own.y>0 被链子当成常驻推进力）";
        }
        return "✓ 稳定（最大 |v|=" + String.format("%.2f", maxSpeed) + " m/s，未离开地面）";
    }

    /** 地形：一整块地板（y=0）+ 一堵墙（x=4，y=1..3）。碰撞组与客户端 {} 相同：默认 (1,-1)。 */
    private static long buildTerrain() {
        java.util.List<Long> cells = new java.util.ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                cells.add(NativePhysics.packCell(x, 0, z));
            }
        }
        for (int y = 1; y <= 3; y++) {
            for (int z = 0; z < 10; z++) {
                cells.add(NativePhysics.packCell(4, y, z));
            }
        }
        long[] packed = new long[cells.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = cells.get(i);
        }
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED, 0, 0, 0, 0, 0, 0, 1, 0);
        long collider = NativePhysics.colliderAttachVoxels(world, body, 1, 1, 1, packed, 0.7, 0.0);
        if (collider > 0) {
            NativePhysics.colliderSetMaterial(world, collider, 0.7, 0.0, 0.02,
                    NativePhysics.RULE_AVERAGE, NativePhysics.RULE_AVERAGE);
        }
        return body;
    }

    /**
     * 生产代码的离线等价物：主 + 兄弟两个 25 kg 盒子，组 {@code (2,5)} / {@code (5,5)}，
     * 摩擦 20 / 弹性 0 / contactSkin 0.02 / 弹性取小 / gravity_scale 0 / 锁旋转 / CCD 关，
     * 以及 {@code PlayerPhysicsBody.velocityChain} 的逐行等价物。
     */
    private static final class Rig {
        final long main;
        private final long sibling;
        double ownX;
        double ownY;
        private final double[] tmp = new double[3];
        private boolean grounded;

        Rig(double x, double y, double z) {
            main = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC, x, y, z, 0, 0, 0, 1, 25.0);
            sibling = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC, x, y, z, 0, 0, 0, 1, 25.0);
            long cm = NativePhysics.colliderAttachCuboidGrouped(world, main,
                    HALF_W, HALF_H, HALF_W, 20.0, 0.0, 2, 5);
            long cs = NativePhysics.colliderAttachCuboidGrouped(world, sibling,
                    HALF_W, HALF_H, HALF_W, 20.0, 0.0, 5, 5);
            NativePhysics.colliderSetMaterial(world, cm, 20.0, 0.0, 0.02,
                    NativePhysics.RULE_AVERAGE, NativePhysics.RULE_MIN);
            NativePhysics.colliderSetMaterial(world, cs, 20.0, 0.0, 0.02,
                    NativePhysics.RULE_AVERAGE, NativePhysics.RULE_MIN);
            NativePhysics.bodyLockRotations(world, main, true);
            NativePhysics.bodyLockRotations(world, sibling, true);
            NativePhysics.bodyEnableCcd(world, main, false);
            NativePhysics.bodyEnableCcd(world, sibling, false);
            NativePhysics.bodySetGravityScale(world, main, 0.0);
            NativePhysics.bodySetGravityScale(world, sibling, 0.0);
        }

        void destroy() {
            NativePhysics.bodyDestroy(world, main);
            NativePhysics.bodyDestroy(world, sibling);
        }

        /** ClientPhysics.probeGround：从"脚底中心 + 四角"向下 0.1，查询组 (2,5)。 */
        boolean probeGroundResult() {
            double[] p = new double[3];
            if (!NativePhysics.bodyReadTranslation(world, main, p)) {
                return false;
            }
            double feet = p[1] - HALF_H;
            double[][] off = {{0, 0}, {-1, -1}, {1, 1}, {-1, 1}, {1, -1}};
            double[] out = new double[5];
            grounded = false;
            for (double[] o : off) {
                if (NativePhysics.worldCastRay(world,
                        p[0] + o[0] * HALF_W, feet, p[2] + o[1] * HALF_W,
                        0.0, -1.0, 0.0, 0.1, 2, 5, out)) {
                    grounded = true;
                    return true;
                }
            }
            return false;
        }

        /** PlayerPhysicsBody.velocityChain 的逐行等价物（含 1e-8 写入阈值与每子步兄弟清零）。 */
        void chain() {
            if (!NativePhysics.bodyReadVelocity(world, sibling, tmp)) {
                return;
            }
            // ⚠️ 这里必须与生产代码**逐行一致**：2026-09-19 试过"按放行比例缩放 own"，
            // 夹具立刻显示它把锯齿放大一倍（26.5→54.5mm，顶墙 1×own→2×own）—— 已回退。
            double tx = tmp[0] + ownX;
            double ty = tmp[1] + ownY;
            double tz = tmp[2];
            if (NativePhysics.bodyReadVelocity(world, main, tmp)) {
                double dx = tx - tmp[0];
                double dy = ty - tmp[1];
                double dz = tz - tmp[2];
                if (dx * dx + dy * dy + dz * dz > 1.0E-8) {
                    NativePhysics.bodySetMotion(world, main, tx, ty, tz, 0, 0, 0, true);
                }
            }
            NativePhysics.bodySetMotion(world, sibling, ownX, ownY, 0.0, 0, 0, 0, true);
            if (NativePhysics.bodyReadTranslation(world, main, tmp)) {
                NativePhysics.bodySetTranslation(world, sibling, tmp[0], tmp[1], tmp[2]);
            }
        }
    }
}
