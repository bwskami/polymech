package com.mss.polymech.client.physics;

import com.mss.polymech.physics.PhysgunBeamShape;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 牵引枪光束的<b>客户端状态</b>：每个玩家一条，含节点链、端点插值与淡出强度。
 *
 * <h2>为什么是"按玩家"的一张表（照参考）</h2>
 * 参考的 {@code PhysicsStaffClientHandler} 里就是 {@code Map<UUID, PhysicsBeam> beams}：
 * 服务端把"谁抓着、光束两端在哪"用 {@code PhysicsStaffBeamPacket} 广播给附近的人，
 * 于是<b>别人的牵引枪光束你也能看见</b>（GMod 里这是常识，单人测试时看不出来，
 * 但它决定了这套东西是"一把枪"还是"一个玩家动作"）。
 *
 * <h2>为什么端点是世界坐标，而不是"体 id + 局部抓点"</h2>
 * 第二种更省包，但要先把服务端的 {@code PhysicalBody}(UUID) 映射到客户端认得的
 * {@code PhysicsBodyTracker} long id —— 那要跨两个 id 空间（UUID ↔ ProjectionManager 槽位 ↔ long id）。
 * 参考选择了直接发世界端点（{@code PhysicsStaffBeamPacket(uuid, start, end)}），
 * 这里跟它一致：<b>少一层映射，就少一类"光束指向别的体/不显示"的错</b>；
 * 代价是每 2 tick 一个小包，而端点用上一帧→这一帧插值补平（{@link #renderEnd}）。
 *
 * <h2>本地预测 vs 服务端权威</h2>
 * 开火瞬间客户端先自己算一条（{@link #grab}），让光束立刻出现；
 * 一旦服务端的包到达（{@link #applyServer}），就<b>永久改由服务端驱动</b>直到松手。
 * 这条"预测先顶上、权威到达后接管"的做法，正是为了修掉第一版的实机症状：
 * 客户端自己的求交落空（服务端 Rapier 命中、客户端镜子体没命中）⇒
 * 连 {@code startUsingItem} 都没调 ⇒ <b>抓住了却完全没有光束</b>。
 */
public final class PhysgunClientState {

    /** playerId → 光束。只从客户端主线程读写（渲染 + 客户端 tick + 包处理都在主线程）。 */
    private static final Map<UUID, Beam> BEAMS = new LinkedHashMap<>();

    private PhysgunClientState() {
    }

    /** 一条光束。除 {@link #intensity()} 外都不该被外部改，所以字段私有 + 只暴露读取。 */
    public static final class Beam {

        private final UUID playerId;
        private long bodyId;
        private double holdDistance;
        private final double[] start = new double[3];
        private final double[] prevStart = new double[3];
        private final double[] end = new double[3];
        private final double[] prevEnd = new double[3];
        private boolean hasEndpoints;
        private boolean serverDriven;
        private boolean released;
        private double intensity;
        /**
         * "虚拟竿尖" + 它的速度：用软弹簧追真实抓点，两者之差就是<b>受力方向与大小</b>
         * （甩视角时最大）—— 即钓鱼竿弯曲的成因。见
         * {@link PhysgunBeamShape#lagStep} 与 {@link PhysgunBeamShape#control}。
         */
        private final double[] tip = new double[3];
        private final double[] tipPrev = new double[3];
        private final double[] tipVel = new double[3];
        private boolean hasTip;

        Beam(UUID playerId, long bodyId, double holdDistance) {
            this.playerId = playerId;
            this.bodyId = bodyId;
            this.holdDistance = holdDistance;
        }

        public UUID playerId() {
            return playerId;
        }

        public long bodyId() {
            return bodyId;
        }

        public double holdDistance() {
            return holdDistance;
        }

        public double intensity() {
            return intensity;
        }

        public boolean released() {
            return released;
        }

        public boolean hasEndpoints() {
            return hasEndpoints;
        }

        /** 是否已由服务端权威接管（接管后本地预测不再写端点）。 */
        public boolean serverDriven() {
            return serverDriven;
        }

        public int nodeCount() {
            return hasEndpoints ? PhysgunBeamShape.segments(length()) : 0;
        }

        /** 当前长度（格）：按真实端点到枪口算。 */
        public double length() {
            double dx = end[0] - start[0];
            double dy = end[1] - start[1];
            double dz = end[2] - start[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** 插值后的起点（相机相对交给渲染器去减，这里给世界坐标）。 */
        public void renderStart(float partialTick, double[] out) {
            lerp(prevStart, start, partialTick, out);
        }

        /** 插值后的终点：两个包之间只有 20Hz 的信息，所以要按帧补平。 */
        public void renderEnd(float partialTick, double[] out) {
            lerp(prevEnd, end, partialTick, out);
        }

        /** 插值后的"虚拟竿尖"：渲染时用它算受力弯曲（见 {@link PhysgunBeamShape#control}）。 */
        public void renderTip(float partialTick, double[] out) {
            if (!hasTip) {
                renderEnd(partialTick, out);
                return;
            }
            lerp(tipPrev, tip, partialTick, out);
        }

        void setEndpoints(double sx, double sy, double sz, double ex, double ey, double ez) {
            if (hasEndpoints) {
                copy(start, prevStart);
                copy(end, prevEnd);
            } else {
                // 第一条：prev 直接对齐，避免从 (0,0,0) 插值出一根"从世界原点飞过来"的光束
                prevStart[0] = sx;
                prevStart[1] = sy;
                prevStart[2] = sz;
                prevEnd[0] = ex;
                prevEnd[1] = ey;
                prevEnd[2] = ez;
            }
            start[0] = sx;
            start[1] = sy;
            start[2] = sz;
            end[0] = ex;
            end[1] = ey;
            end[2] = ez;
            hasEndpoints = true;
        }

        /** 本地预测（只在服务端权威到达前有效）。 */
        void setPredictedEndpoints(double sx, double sy, double sz, double ex, double ey, double ez) {
            if (!serverDriven) {
                setEndpoints(sx, sy, sz, ex, ey, ez);
            }
        }

        /** 服务端权威端点：一旦到达就永久接管（直到这条光束被释放并移除）。 */
        void applyServerEndpoints(double sx, double sy, double sz, double ex, double ey, double ez) {
            serverDriven = true;
            released = false;
            setEndpoints(sx, sy, sz, ex, ey, ez);
        }

        void tick() {
            if (hasEndpoints) {
                // 虚拟竿尖：软弹簧追真实抓点。追不上的那一截就是"受力弯曲"的成因。
                if (!hasTip) {
                    // 第一条：直接对齐，避免从 (0,0,0) 弹出一根弯曲的怪线
                    copy(end, tip);
                    copy(end, tipPrev);
                    tipVel[0] = 0.0;
                    tipVel[1] = 0.0;
                    tipVel[2] = 0.0;
                    hasTip = true;
                }
                copy(tip, tipPrev);
                PhysgunBeamShape.lagStep(tip, tipVel, end,
                        PhysgunBeamShape.LAG_K, PhysgunBeamShape.LAG_D, TICK_SECONDS, LAG_SUBSTEPS);
            }
            intensity = PhysgunBeamShape.intensityStep(intensity, released);
        }

        boolean finished() {
            return PhysgunBeamShape.finished(intensity, released);
        }
    }

    /** 客户端 tick 的时长（秒）：弹簧按它积分（1/20 秒）。 */
    private static final double TICK_SECONDS = 0.05;
    /** 弹簧积分的子步数：ω·dt = 12.6×0.05 = 0.63，再分 4 步就很准且稳。 */
    private static final int LAG_SUBSTEPS = 4;

    // ==================== 写入（客户端主线程） ====================

    /** 开火瞬间的本地预测：让光束立刻出现，不用等服务端包回来。 */
    public static void grab(UUID player, long bodyId, double holdDistance,
                            double sx, double sy, double sz, double ex, double ey, double ez) {
        Beam beam = ensure(player, bodyId, holdDistance);
        beam.setPredictedEndpoints(sx, sy, sz, ex, ey, ez);
    }

    /**
     * 服务端权威：谁（player）抓着哪个体（bodyId，可 -1 = 未知）、光束两端在世界哪里。
     *
     * @param released true = 松手了（让这条光束开始淡出，而不是硬切）
     */
    public static void applyServer(UUID player, long bodyId, double holdDistance,
                                   double sx, double sy, double sz,
                                   double ex, double ey, double ez, boolean released) {
        if (released) {
            release(player);
            return;
        }
        Beam beam = ensure(player, bodyId, holdDistance);
        beam.applyServerEndpoints(sx, sy, sz, ex, ey, ez);
    }

    /** 松手：不立刻删，交给强度曲线淡出（照参考的 intensity 衰减）。 */
    public static void release(UUID player) {
        Beam beam = BEAMS.get(player);
        if (beam != null) {
            beam.released = true;
        }
    }

    /** 立刻删掉（换维度 / 玩家离开 / 重进世界）。 */
    public static void remove(UUID player) {
        BEAMS.remove(player);
    }

    /** 全部清空（离开世界）。 */
    public static void clear() {
        BEAMS.clear();
    }

    // ==================== 每 tick / 每帧 ====================

    /** 客户端 tick：推进节点抖动与强度，并回收已经淡完的光束。 */
    public static void tick() {
        for (Beam beam : BEAMS.values()) {
            beam.tick();
        }
        BEAMS.values().removeIf(Beam::finished);
    }

    /** 渲染遍历用（不要在遍历时改这张表）。 */
    public static Collection<Beam> beams() {
        return BEAMS.values();
    }

    public static Beam beam(UUID player) {
        return BEAMS.get(player);
    }

    public static boolean isGrabbed(UUID player) {
        Beam beam = BEAMS.get(player);
        return beam != null && !beam.finished();
    }

    public static long grabbedBodyId(UUID player) {
        Beam beam = BEAMS.get(player);
        return beam == null ? -1L : beam.bodyId;
    }

    public static double holdDistance(UUID player) {
        Beam beam = BEAMS.get(player);
        return beam == null ? 3.0 : beam.holdDistance;
    }

    /** 表里有几条（诊断/探针用）。 */
    public static int size() {
        return BEAMS.size();
    }

    private static Beam ensure(UUID player, long bodyId, double holdDistance) {
        Beam beam = BEAMS.get(player);
        if (beam == null || (bodyId > 0L && beam.bodyId != bodyId)) {
            // 换了目标体：整条重来（端点由调用方马上写入）
            beam = new Beam(player, bodyId, holdDistance);
            BEAMS.put(player, beam);
            return beam;
        }
        beam.bodyId = bodyId > 0L ? bodyId : beam.bodyId;
        beam.holdDistance = holdDistance > 0.0 ? holdDistance : beam.holdDistance;
        beam.released = false;
        return beam;
    }

    private static void copy(double[] from, double[] to) {
        to[0] = from[0];
        to[1] = from[1];
        to[2] = from[2];
    }

    private static void lerp(double[] from, double[] to, float t, double[] out) {
        out[0] = from[0] + (to[0] - from[0]) * t;
        out[1] = from[1] + (to[1] - from[1]) * t;
        out[2] = from[2] + (to[2] - from[2]) * t;
    }
}
