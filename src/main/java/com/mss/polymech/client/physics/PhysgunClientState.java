package com.mss.polymech.client.physics;

import com.mss.polymech.network.PhysgunBeamPacket;
import com.mss.polymech.physics.PhysgunBeamShape;
import com.mss.polymech.util.TimedSamples;
import org.joml.Quaterniond;

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
 * 按下右键瞬间客户端先自己算一条（{@link #grab}），让光束立刻出现；
 * 一旦服务端的包到达（{@link #applyServer}），就<b>永久改由服务端驱动</b>直到松手。
 * 这条"预测先顶上、权威到达后接管"的做法，正是为了修掉第一版的实机症状：
 * 客户端自己的求交落空（服务端 Rapier 命中、客户端镜子体没命中）⇒ 客户端这边什么都没有
 * ⇒ <b>抓住了却完全没有光束</b>。所以"打不中也要把 START_DRAG 发上去"是必须的
 * （见 {@code PhysgunInputHandler.onMousePress}）。
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

        /**
         * 起点 / 终点 / 竿尖的<b>带时间戳快照环</b>（通用件，见 {@link TimedSamples}）。
         *
         * <p>这就是"线抽抽、框也抽抽"的根治处。老写法是
         * {@code lerp(prevEnd, end, partialTick)}，它默认"包的到达与 tick 边界对齐"；
         * 而包是每 tick 到的、帧在任意相位渲染 ⇒ 每 tick 凭空跳一整个采样间隔。
         * 框的中心正是同一个 {@code renderEnd} 的输出，所以线和框<b>必然一起抽</b>
         * —— 这也是为什么以前分开修两边都修不好。</p>
         */
        private final TimedSamples startSamples = new TimedSamples(3, 64, INTERP_DELAY_SECONDS);
        private final TimedSamples endSamples = new TimedSamples(3, 64, INTERP_DELAY_SECONDS);
        private final TimedSamples tipSamples = new TimedSamples(3, 64, INTERP_DELAY_SECONDS);

        /**
         * <b>目标朝向</b>（客户端编辑、服务端执行）—— 牵引枪的旋转控制。
         *
         * <p>{@link #hasOrientation} 为 false 表示"还没开始转"：这时<b>不发</b>拖拽包，
         * 服务端也不做角伺服（{@code Grab.orientation == null}），物体保持原来的自由摆动。
         * 第一次旋转时才从镜子体的当前朝向起步（见 {@code PhysgunInputHandler.ensureOrientation}），
         * 这样"抓住 → 按旋转键 → 动鼠标"不会让物体先跳一下。</p>
         */
        private final Quaterniond orientation = new Quaterniond();
        private boolean hasOrientation;
        /** 有未发送的拖拽变化（朝向或距离）⇒ 客户端 tick 时发一包。 */
        private boolean dragDirty;
        /**
         * 持握距离是否由<b>本客户端</b>驱动（滚轮改过）。
         *
         * <p>服务端的权威包每 {@code BROADCAST_INTERVAL_TICKS} tick 带一次距离回声，
         * 而客户端滚轮可以在这个窗口里改好几次 —— 若回声无条件覆盖，滚轮就会"回弹"
         * （而且回弹的不只是光束：服务端的目标点也跟着来回跳）。所以一旦本地驱动过距离，
         * 就不再接受回声，直到松手。服务端那边仍然会把距离钳在 {@code [2, RANGE]}，
         * 客户端钳的是同一个区间，两边不会分裂。</p>
         */
        private boolean clientDistance;

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

        // ==================== 旋转控制（目标朝向 + 持握距离） ====================

        /** 目标朝向（只在 {@link #hasOrientation()} 为 true 时有意义）。<b>可以改</b>它。 */
        public Quaterniond orientation() {
            return orientation;
        }

        public boolean hasOrientation() {
            return hasOrientation;
        }

        /** 设定目标朝向（首次会顺带把"已开始转"置真并标脏）。 */
        public void setOrientation(Quaterniond q) {
            orientation.set(q);
            hasOrientation = true;
            dragDirty = true;
        }

        /** 鼠标转过之后调：标脏，让客户端 tick 发包。 */
        public void markOrientationDirty() {
            dragDirty = true;
        }

        /** 滚轮改过距离之后调：标脏。 */
        public void markDragDirty() {
            dragDirty = true;
        }

        public boolean dragDirty() {
            return dragDirty;
        }

        public void clearDragDirty() {
            dragDirty = false;
        }

        /** 滚轮改距离（调用方负责钳位）。 */
        public void setHoldDistance(double distance) {
            this.holdDistance = distance;
            this.clientDistance = true;
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

        /**
         * 插值后的起点（相机相对交给渲染器去减，这里给世界坐标）。
         *
         * <p>优先走<b>时间戳插值</b>（见 {@link TimedSamples}）；样本不足（刚抓上、还没攒够两拍）
         * 时退回旧的"两拍 lerp"—— 那样最多抽一两拍，而不是因为样本没攒够就整条光束不动。</p>
         */
        public void renderStart(float partialTick, double[] out) {
            if (!startSamples.evaluate(out)) {
                lerp(prevStart, start, partialTick, out);
            }
        }

        /** 插值后的终点：两个包之间只有 20Hz 的信息，所以要按帧补平。 */
        public void renderEnd(float partialTick, double[] out) {
            if (!endSamples.evaluate(out)) {
                lerp(prevEnd, end, partialTick, out);
            }
        }

        /** 插值后的"虚拟竿尖"：渲染时用它算受力弯曲（见 {@link PhysgunBeamShape#control}）。 */
        public void renderTip(float partialTick, double[] out) {
            if (!hasTip) {
                renderEnd(partialTick, out);
                return;
            }
            if (!tipSamples.evaluate(out)) {
                lerp(tipPrev, tip, partialTick, out);
            }
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
            // ★ 打时间戳：这就是"通用方法"的入口 —— 谁写端点谁就记一拍。
            //   两个 setter（本地预测 / 服务端权威）都汇到这里，所以不会漏。
            startSamples.push(start[0], start[1], start[2]);
            endSamples.push(end[0], end[1], end[2]);
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
                // 竿尖也是"按 tick 走、按帧渲染"的量 ⇒ 同样要打时间戳
                tipSamples.push(tip[0], tip[1], tip[2]);
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

    /**
     * 渲染延迟（秒）——<b>由发包间隔推出，不写死</b>。
     *
     * <p>时间戳插值要求"渲染时刻永远被相邻两拍包住"，所以延迟必须
     * {@code ≥ 2 × 发包间隔}。把这个关系写成式子而不是注释，是因为这里正好是最容易出错的地方：
     * 照抄天空那个 0.10 s 而忘了牵引枪的发包间隔比天空稀，就会得到"延迟刚好等于一个间隔"
     * ⇒ 稍有网络抖动就越过最新样本、被夹住不动 ⇒ <b>又开始抽</b>。</p>
     *
     * <p>⚠️ 改了 {@link PhysgunBeamPacket#BROADCAST_INTERVAL_TICKS}，这里自动跟着变。</p>
     */
    private static final double INTERP_DELAY_SECONDS =
            2.0 * PhysgunBeamPacket.BROADCAST_INTERVAL_TICKS / 20.0;

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
            // 清掉"旋转会话"：下次抓（哪怕是同一个体）要重新从体的当前朝向起步，
            // 否则松手期间物体自由转动之后，再按旋转键会拿旧朝向当基准 ⇒ 啪地跳一下。
            beam.hasOrientation = false;
            beam.dragDirty = false;
            beam.clientDistance = false;
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
        // 距离：本地驱动过就听本地的（见 clientDistance 的注释），否则听服务端回声
        if (!beam.clientDistance && holdDistance > 0.0) {
            beam.holdDistance = holdDistance;
        }
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
