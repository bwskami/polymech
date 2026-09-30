package com.mss.polymech.physics;

/**
 * 牵引枪光束的 <b>纯几何 / 纯数值</b> 内核 —— 无 Minecraft、无 joml 依赖，所以能离线钉死
 * （{@code native/jni-smoketest/PhysgunBeamShapeProbe.java}）。
 *
 * <h2>为什么要有这个类（而不是把数学写在渲染器里）</h2>
 * 光束的"形状"全是纯数学：曲线采样、受力弯曲的弹簧、线宽、端点光斑、悬停格的面几何。
 * 抽出来就能在没起客户端时判对错（本项目在 {@code PhysgunSpring} 上已经用过同一招）。
 *
 * <h2>形状的演化（三轮，都是用户实机反馈驱动的）</h2>
 * <ol>
 *   <li>第一版：{@code RenderType.lines()} 的 1px 直线 ⇒ 用户"太廉价"；</li>
 *   <li>第二版：照参考（Simulated {@code PhysicsStaff} 的 {@code PhysicsBeam}）做<b>节点链 +
 *       随机游走抖动</b>，做出"能量绳"；</li>
 *   <li><b>2026-09-29 用户定调：那一版"酷似闪电"，辨识度太高、会跟航空学撞车 ⇒
 *       改成"一般曲线"，并且要像钓鱼竿那样随你移动画面而弯曲。</b>于是节点链整段删掉，
 *       换成<b>二次贝塞尔 + 软弹簧虚拟竿尖</b>（下一节）。</li>
 * </ol>
 *
 * <h2>现在的形状：贝塞尔 + "受力弯曲"（{@link #control} / {@link #lagStep}）</h2>
 * <pre>
 *   虚拟竿尖 tip  ──软弹簧(K=160, D=15)──▶  真实抓点 end      // 每 tick 推进一步
 *   受力向量 bend = end − tip（按模长限幅 3 格）              // 你甩视角时它最大
 *   控制点     c  = (start+end)/2 + bend×1.5 + 垂直基础弓形    // 竿身朝受力方向鼓
 *   曲线       B(t)= (1−t)²·start + 2(1−t)t·c + t²·end        // 两端始终咬住
 *   线宽       w(t)= w₀·(1 − 0.55t)                           // 越靠近竿尖越细
 * </pre>
 * 关键性质：<b>两端永远咬住枪口与物体</b>（弯的只有中间），所以不会出现"光束脱开物体"；
 * 而"甩视角 ⇒ 抓点跳走 ⇒ 竿尖落后 ⇒ 弯曲变大"就是用户要的钓鱼竿手感。
 *
 * <h2>强度（照参考的 {@code intensity} / {@code extension}）</h2>
 * 抓住后强度按 {@code lerp(0.5, x, 1)} 爬到 1；松手后每客户端 tick 乘 {@code 0.6}，
 * 低于 {@code 0.4} 整条移除 —— 也就是 1.0 → 0.6 → 消失，一个很快但有尾迹的"收束"，
 * 而不是硬切。
 */
public final class PhysgunBeamShape {

    // ==================== 曲线（贝塞尔）====================

    /** 采样间距（格）：0.75 格一段就够平滑，段数也可控。 */
    public static final double SEG_SPACING = 0.75;
    /** 最少 / 最多采样段数。 */
    public static final int MIN_SEGMENTS = 6;
    public static final int MAX_SEGMENTS = 48;

    // ==================== "钓鱼竿"软弹簧（虚拟竿尖追真实抓点）====================
    //
    // 2026-09-29 用户定调：不要航空学那种"酷似闪电"的节点抖动（辨识度太高、会撞车），
    // 要"一般曲线"，而且**会随你移动画面而改变，像钓鱼竿受力弯曲**。
    // 做法：让一个"虚拟竿尖"用软弹簧去追真实抓点 —— 你一甩视角，抓点瞬间跳走、虚拟竿尖还在原地，
    // 两者的差 (真实抓点 − 虚拟竿尖) 就是"受力的方向与大小"，拿它去鼓贝塞尔的控制点 ⇒
    // 竿身朝受力方向弯；停下后弹簧归位，曲线慢慢回直。
    // 两端（枪口 / 物体）**始终咬住**，弯的只有中间 ⇒ 不会出现"光束脱开物体"。

    /** 弹簧刚度（rad²/s²）：ω ≈ 12.6 rad/s ⇒ 约 0.3 秒归位。 */
    public static final double LAG_K = 160.0;
    /** 阻尼（rad/s）：ζ ≈ 0.6 ⇒ 有一点回弹，但不过冲成振荡。 */
    public static final double LAG_D = 15.0;
    /** 落后量上限（格）：一次大瞬移不至于把曲线拉成麻花。 */
    public static final double LAG_MAX = 3.0;
    /** 弯曲增益：控制点 = 中点 + 落后量 × 这个系数。 */
    public static final double BEND_GAIN = 1.5;
    /** 静止时也留一点弓形（"一般曲线"，不是死直线）：每格多少，封顶 {@link #BOW_MAX}。 */
    public static final double BOW_PER_BLOCK = 0.02;
    public static final double BOW_MAX = 0.20;
    /** 末端收细比例（0 = 根 → 1 = 尖，宽度乘 (1 − 这个数 × t)）。 */
    public static final double TAPER = 0.55;

    /** 松手后每个客户端 tick 的强度衰减（参考 0.6）。 */
    public static final double RELEASE_DECAY = 0.6;
    /** 低于这个强度就整条移除（参考 0.4）。 */
    public static final double REMOVE_BELOW = 0.4;
    /** 抓住后的强度爬升率（参考 lerp(0.5, x, 1) ⇒ 每 tick 补一半差值）。 */
    public static final double GROW_RATE = 0.5;

    private PhysgunBeamShape() {
    }

    /** 这条光束要采样几段（按长度定，6..48）。 */
    public static int segments(double length) {
        if (!(length > 0.0) || Double.isNaN(length) || Double.isInfinite(length)) {
            return MIN_SEGMENTS;
        }
        return (int) Math.max(MIN_SEGMENTS, Math.min(MAX_SEGMENTS, Math.round(length / SEG_SPACING)));
    }

    /**
     * "虚拟竿尖"的软弹簧推进（半隐式欧拉，内部再分 {@code substeps} 个子步保证稳）。
     *
     * @param tip    虚拟竿尖位置（原地更新）
     * @param vel    虚拟竿尖速度（原地更新）
     * @param target 真实抓点
     */
    public static void lagStep(double[] tip, double[] vel, double[] target,
                               double k, double d, double dt, int substeps) {
        int n = Math.max(1, substeps);
        double h = dt / n;
        for (int s = 0; s < n; s++) {
            for (int i = 0; i < 3; i++) {
                double a = (target[i] - tip[i]) * k - vel[i] * d;
                vel[i] += a * h;
                tip[i] += vel[i] * h;
            }
        }
    }

    /** 末端的收细比例（0 = 根、1 = 尖）。 */
    public static double taper(double t) {
        double x = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
        return 1.0 - TAPER * x;
    }

    /** 二次贝塞尔取点：{@code (1−t)²·p0 + 2(1−t)t·c + t²·p1}。 */
    public static void bezier(double t, double[] p0, double[] c, double[] p1, double[] out) {
        double u = 1.0 - t;
        double a = u * u;
        double b = 2.0 * u * t;
        double e = t * t;
        for (int i = 0; i < 3; i++) {
            out[i] = a * p0[i] + b * c[i] + e * p1[i];
        }
    }

    /**
     * 贝塞尔控制点 = 中点 + <b>受力方向</b>×{@link #BEND_GAIN} + 一点基础弓形。
     *
     * <p>受力方向 = {@code 真实抓点 − 虚拟竿尖}（见 {@link #lagStep} 的说明）：你甩视角时抓点瞬间
     * 跳走、虚拟竿尖还在原地，这个差就指向"物体在拉竿的方向"，竿身于是朝那一侧弯 —— 这正是
     * 钓鱼竿受力弯曲的形状；停下后它衰减回 0，只剩下面那点基础弓形。</p>
     *
     * <p>基础弓形沿一条<b>与相机无关</b>的垂直轴（光束方向 × 参考上方向），所以静止时是一条温和的
     * 曲线，而不会因为你只是转头就自己晃起来。</p>
     */
    public static void control(double[] p0, double[] p1, double[] tip, double length, double[] out) {
        double dx = p1[0] - p0[0];
        double dy = p1[1] - p0[1];
        double dz = p1[2] - p0[2];
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0e-9) {
            dx = 0.0;
            dy = 1.0;
            dz = 0.0;
            dl = 1.0;
        }
        dx /= dl;
        dy /= dl;
        dz /= dl;
        // 与光束方向不平行的参考轴
        double rx = 0.0;
        double ry = 1.0;
        double rz = 0.0;
        if (Math.abs(dy) > 0.9) {
            rx = 1.0;
            ry = 0.0;
        }
        double sx = dy * rz - dz * ry;
        double sy = dz * rx - dx * rz;
        double sz = dx * ry - dy * rx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0e-9) {
            sx = 1.0;
            sy = 0.0;
            sz = 0.0;
            sl = 1.0;
        }
        sx /= sl;
        sy /= sl;
        sz /= sl;

        // 落后向量（按模长限幅，逐轴限幅会把方向弄歪）
        double bx = p1[0] - tip[0];
        double by = p1[1] - tip[1];
        double bz = p1[2] - tip[2];
        double bl = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bl > LAG_MAX) {
            double f = LAG_MAX / bl;
            bx *= f;
            by *= f;
            bz *= f;
        }
        double bow = Math.min(BOW_MAX, Math.max(0.0, length) * BOW_PER_BLOCK);
        out[0] = 0.5 * (p0[0] + p1[0]) + bx * BEND_GAIN + sx * bow;
        out[1] = 0.5 * (p0[1] + p1[1]) + by * BEND_GAIN + sy * bow;
        out[2] = 0.5 * (p0[2] + p1[2]) + bz * BEND_GAIN + sz * bow;
    }

    /** 每个客户端 tick 的强度推进：抓住时爬向 1，松手后按 {@link #RELEASE_DECAY} 衰减。 */
    public static double intensityStep(double intensity, boolean released) {
        if (released) {
            return Math.max(0.0, intensity * RELEASE_DECAY);
        }
        return intensity + (1.0 - intensity) * GROW_RATE;
    }

    /** 是否该把这条光束整条丢掉（只对已松手的生效）。 */
    public static boolean finished(double intensity, boolean released) {
        return released && intensity < REMOVE_BELOW;
    }

    /**
     * 光束段的相机朝向正交基：{@code dir = normalize(b−a)}，{@code side ⊥ dir 且 ⊥ 视线}，
     * {@code up = dir × side}。
     *
     * <p>渲染器拿它画"十字双四边形"（side 面 + up 面）—— 只画一个面的话，
     * 视线与光束平行时会细成一条线；两个正交面才在任何角度都有厚度
     * （本项目不用 {@code glLineWidth}：核心 profile 下它被钳到 1，这正是参考
     * 自己造 {@code LineOutline} 而不是用 {@code RenderType.lines()} 的原因）。</p>
     *
     * <p>退化保护：相机正好落在光束轴上时 {@code cross(dir, toCam)} 会归零，
     * 此时退回"取 dir 最小分量对应轴"构造任意垂直向量 —— 保证始终返回单位正交基。</p>
     */
    public static void frame(double[] a, double[] b, double[] camera, double[] sideOut, double[] upOut) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0e-9) {
            dx = 0.0;
            dy = 1.0;
            dz = 0.0;
            dl = 1.0;
        }
        dx /= dl;
        dy /= dl;
        dz /= dl;

        double mx = (a[0] + b[0]) * 0.5;
        double my = (a[1] + b[1]) * 0.5;
        double mz = (a[2] + b[2]) * 0.5;
        double vx = camera[0] - mx;
        double vy = camera[1] - my;
        double vz = camera[2] - mz;
        double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (vl < 1.0e-9) {
            vx = 0.0;
            vy = 1.0;
            vz = 0.0;
            vl = 1.0;
        }
        vx /= vl;
        vy /= vl;
        vz /= vl;

        double sx = dy * vz - dz * vy;
        double sy = dz * vx - dx * vz;
        double sz = dx * vy - dy * vx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0e-6) {
            // 相机在光束轴上：随便取一个与 dir 垂直的方向（取 dir 最小分量那根轴叉乘）
            double ax = 1.0;
            double ay = 0.0;
            double az = 0.0;
            if (Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz)) {
                ax = 0.0;
                ay = 1.0;
            }
            sx = dy * az - dz * ay;
            sy = dz * ax - dx * az;
            sz = dx * ay - dy * ax;
            sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0e-9) {
                sx = 1.0;
                sy = 0.0;
                sz = 0.0;
                sl = 1.0;
            }
        }
        sx /= sl;
        sy /= sl;
        sz /= sl;

        // up = dir × side（两者都已单位化且正交 ⇒ up 也是单位向量）
        double ux = dy * sz - dz * sy;
        double uy = dz * sx - dx * sz;
        double uz = dx * sy - dy * sx;

        sideOut[0] = sx;
        sideOut[1] = sy;
        sideOut[2] = sz;
        upOut[0] = ux;
        upOut[1] = uy;
        upOut[2] = uz;
    }

    /**
     * 沿线 {@code a→b}、沿法线 {@code normal} 展开的四边形（两个三角形用同一组角点顺序，
     * 因为我们的 RenderType 关掉了剔除，正反面都能看见）。
     *
     * @param halfWidth 半宽（格）
     * @param out       写入 12 个 float：4 个角点 × (x,y,z)，顺序 a+n, a−n, b−n, b+n
     */
    public static void quad(double[] a, double[] b, double[] normal, double halfWidth, double[] out) {
        double ox = normal[0] * halfWidth;
        double oy = normal[1] * halfWidth;
        double oz = normal[2] * halfWidth;
        out[0] = a[0] + ox;
        out[1] = a[1] + oy;
        out[2] = a[2] + oz;
        out[3] = a[0] - ox;
        out[4] = a[1] - oy;
        out[5] = a[2] - oz;
        out[6] = b[0] - ox;
        out[7] = b[1] - oy;
        out[8] = b[2] - oz;
        out[9] = b[0] + ox;
        out[10] = b[1] + oy;
        out[11] = b[2] + oz;
    }

    /**
     * 正对相机的正方形（端点光斑 / 落点标记）。
     *
     * @param halfSize 半边长（格）
     * @param out      写入 12 个 float：4 个角点 × (x,y,z)
     */
    public static void billboard(double[] center, double[] camera, double halfSize, double[] out) {
        double nx = center[0] - camera[0];
        double ny = center[1] - camera[1];
        double nz = center[2] - camera[2];
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1.0e-9) {
            nx = 0.0;
            ny = 0.0;
            nz = 1.0;
        } else {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        }
        // side = worldUp × normal；worldUp 与 normal 平行时退回 (1,0,0) × normal
        double sx = 1.0 * nz - 0.0 * ny;
        double sy = 0.0 * nx - 0.0 * nz;
        double sz = 0.0 * ny - 1.0 * nx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0e-6) {
            // 视线与世界上方向平行：改用 (0,0,1) × normal = (−ny, nx, 0)
            sx = -ny;
            sy = nx;
            sz = 0.0;
            sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1.0e-9) {
                sx = 1.0;
                sy = 0.0;
                sz = 0.0;
                sl = 1.0;
            }
        }
        sx /= sl;
        sy /= sl;
        sz /= sl;
        // up = normal × side
        double ux = ny * sz - nz * sy;
        double uy = nz * sx - nx * sz;
        double uz = nx * sy - ny * sx;

        double h = halfSize;
        out[0] = center[0] - sx * h + ux * h;
        out[1] = center[1] - sy * h + uy * h;
        out[2] = center[2] - sz * h + uz * h;
        out[3] = center[0] + sx * h + ux * h;
        out[4] = center[1] + sy * h + uy * h;
        out[5] = center[2] + sz * h + uz * h;
        out[6] = center[0] + sx * h - ux * h;
        out[7] = center[1] + sy * h - uy * h;
        out[8] = center[2] + sz * h - uz * h;
        out[9] = center[0] - sx * h - ux * h;
        out[10] = center[1] - sy * h - uy * h;
        out[11] = center[2] - sz * h - uz * h;
    }

    /**
     * 悬停格的一个面：局部坐标下的 4 个角点 <b>+ 与之同序的 4 组 UV</b>。
     *
     * <p><b>格占 {@code [c, c+1)³}（最小角约定）</b> —— 证据见 {@link RayBox#blockBounds} 的注释
     * （{@code PhysicsBodyTracker.localToWorld} 用 {@code dx+0.5} 求块心等三处独立证据）。
     * 2026-09-29 之前这里（以及调用方）用的是"整数 = 块中心"，于是棋盘格整个平移了半格、
     * 跑到体的轮廓之外 —— 实机看到的就是"框外面飘着几个蓝色方块"。</p>
     *
     * <p>角点顺序固定为 {@code (u0,v0) (u1,v0) (u1,v1) (u0,v1)}，UV 同序为
     * {@code (0,0) (1,0) (1,1) (0,1)}：<b>两者必须一起改</b>，否则贴图会翻/扭曲
     * （所以这个函数一次把两个数组都填出来，而不是分成两个可能被写错的函数）。</p>
     *
     * @param axis  面的法线轴：0=X 1=Y 2=Z
     * @param sign  {@code +1} = 轴正向面（{@code c+1-inset}），{@code -1} = 负向面（{@code c+inset}）
     * @param cell  格坐标（整数，块的**最小角**）
     * @param inset 往格内缩的量（格）—— 避免与方块自身的面 z-fight
     * @param xyz12 写入 12 个 double：4 个角点 × (x,y,z)
     * @param uv8   写入 8 个 double：4 组 (u,v)
     */
    public static void cellFace(int axis, int sign, double[] cell, double inset,
                               double[] xyz12, double[] uv8) {
        int u = (axis + 1) % 3;
        int v = (axis + 2) % 3;
        double plane = sign > 0 ? cell[axis] + 1.0 - inset : cell[axis] + inset;
        double u0 = cell[u];
        double u1 = cell[u] + 1.0;
        double v0 = cell[v];
        double v1 = cell[v] + 1.0;
        double[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
        double[][] uvs = {{0.0, 0.0}, {1.0, 0.0}, {1.0, 1.0}, {0.0, 1.0}};
        for (int k = 0; k < 4; k++) {
            double[] p = {cell[0], cell[1], cell[2]};
            p[axis] = plane;
            p[u] = corners[k][0];
            p[v] = corners[k][1];
            xyz12[k * 3] = p[0];
            xyz12[k * 3 + 1] = p[1];
            xyz12[k * 3 + 2] = p[2];
            uv8[k * 2] = uvs[k][0];
            uv8[k * 2 + 1] = uvs[k][1];
        }
    }
}
