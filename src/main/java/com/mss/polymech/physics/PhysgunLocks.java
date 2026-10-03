package com.mss.polymech.physics;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 牵引枪的<b>钉住（LOCK）</b>—— 照参考 {@code PhysicsStaffServerHandler} 里 {@code locks} 那一套。
 *
 * <h2>参考怎么做（逐字取证）</h2>
 * <pre>
 *   private final Map&lt;UUID, Lock&gt; locks = new Object2ObjectOpenHashMap&lt;&gt;();
 *   private record Lock(UUID subLevel, &#64;Nullable PhysicsConstraintHandle handle) { ... }
 *
 *   toggleLock(uuid):  已有 ⇒ locks.remove + handle.remove()（解锁）
 *                      没有 ⇒ addConstraint(...) 后 locks.put(uuid, new Lock(uuid, handle))（钉住）
 *   addConstraint:     pipeline.addConstraint(null, subLevel,
 *                          new FixedConstraintConfiguration(subLevel.logicalPose().position(),
 *                                                           ...rotationPoint(), ...orientation()))
 *                      —— 关节一端写 null = <b>锚在世界</b>，位置/朝向取体<b>当前</b>位姿
 *   drag(...):         this.removeLock(subLevel);       // ★ 拖一个被钉住的体会**自动解锁**
 *   save/load:         只存 UUID 集合；读回来时 handle = null
 *   applyLockIfNeeded: handle 为 null/失效 ⇒ 重新 addConstraint（<b>惰性重建</b>）
 *   setLocksDirty:     setDirty(true)（存盘）+ 广播 PhysicsStaffLocksPacket
 * </pre>
 *
 * <h2>我们的对应物</h2>
 * <ul>
 *   <li>键从"子级 UUID"换成<b>物理体 id</b>（我们这套体系里体就是 {@code PhysicsBodyTracker} 的 long id）；</li>
 *   <li>{@code addConstraint(null, ...)} 里的 <b>null 在 Rapier 里不存在</b> —— 见
 *       {@code native/polymech-physics/src/lib.rs} 的 {@code constraint_map} 注释：
 *       Rapier 0.34 的约束构建器直接索引刚体集合，传 invalid 句柄会 panic。
 *       所以原生侧把"世界"这个锚<b>显式建出来</b>（一个 FIXED 锚体，建在体当前位姿上），
 *       语义与参考完全一致：仍然是"固定约束"，只是补上了缺的隐式地面体；</li>
 *   <li>约束句柄换成原生返回的 {@code long} 约束 id；</li>
 *   <li>持久化存<b>体 id 集合</b>，读回来时 id 记 {@link #PENDING} ⇒ 由
 *       {@link #applyPending()} 惰性重建（对应参考的 {@code applyLockIfNeeded}）。</li>
 * </ul>
 *
 * <h2>为什么"钉住"是约束而不是 {@code bodySetBodyType(FIXED)}</h2>
 * 后者是<b>殊途同归</b>：体变成静态、质量无穷，能实现"不动"，但它改的是体的<b>类型</b>，
 * 解锁时得记住原来是什么类型，而且静态体不再参与正常的求解路径。参考用的是约束，
 * 我们也用约束 —— 这就是为什么先去把这个原生缺口补上，而不是在 Java 侧找个"效果一样"的写法。
 *
 * <h2>客户端可见性（已如实记录的遗留）</h2>
 * 参考还会广播 {@code PhysicsStaffLocksPacket} 让<b>客户端</b>知道谁被钉住了。
 * 查过它的唯一用途：{@code PhysicsStaffClientHandler} 里用它<b>选音效</b>
 * （已锁 ⇒ {@code STAFF_UNLOCK}，否则 {@code STAFF_LOCK}/{@code STAFF_IGNITE}），
 * <b>没有</b>任何视觉指示。我们的音效目前还是原版 conduit 占位音
 * （见 {@code docs/physgun-rotation.md} 的遗留清单），所以这一步先不做这个包 ——
 * 它换的只是两个占位音之间的选择。已在文档里记为遗留。
 */
public final class PhysgunLocks extends SavedData {

    public static final String NAME = "polymech_physgun_locks";

    /** NBT 里存"被钉住的体 id"列表的键。 */
    private static final String KEY_IDS = "locked_body_ids";

    /** 约束 id 的占位值：存档里带回来的条目还没重建出原生约束。 */
    public static final long PENDING = 0L;

    public static final SavedData.Factory<PhysgunLocks> FACTORY =
            new SavedData.Factory<>(PhysgunLocks::new, PhysgunLocks::load);

    /** 体 id → 原生约束 id；{@link #PENDING} 表示"还没重建"。 */
    private final Map<Long, Long> locks = new LinkedHashMap<>();

    // ==================== 锁定判据（程序判定 PASS/FAIL，实机验收用） ====================

    /**
     * 钉住后多少 tick 量一次漂移。
     *
     * <p>取 40（2 秒）：约束是硬约束，漂移在**几个 tick 内**就该归零；
     * 2 秒足够覆盖"松手瞬间还带着残余速度"的情况，又不至于让玩家等太久。</p>
     */
    public static final int VERIFY_TICKS = 40;

    /** 漂移阈值（格）：离线探针里 100 步漂移是 2.5e-13，这里给 0.01 留足余量（含碰撞/接触求解）。 */
    public static final double DRIFT_PASS = 0.01;

    /** 待量的判据（**不持久化** —— 它只是"下一次验收用的一次性读数"）。 */
    private final Map<Long, Check> checks = new LinkedHashMap<>();

    private record Check(UUID playerId, double x, double y, double z, long startTick) {
    }

    /**
     * 钉住成功后调：在体当前位置打一个"锚点"，{@link #VERIFY_TICKS} tick 后自动量漂移并打 PASS/FAIL。
     *
     * <p>为什么必须由程序判：LOCK 的实机表现是"物体不动"，而**没锁的物体也可能本来就不动**
     * （正躺在地上）。光靠眼睛看，"锁定生效"与"它本来就没动"分不开。这条判据把
     * 约束到底建没建出来（{@code 约束 id}）、漂移多少（绝对格数）、当前速度都打出来 ——
     * 因果则由下一步"解锁后它掉下去"（L2）来闭合。</p>
     */
    public void armLockVerdict(ServerPlayer sp, long bodyId) {
        double[] pos = PhysicsBodyTracker.positionOf(bodyId);
        if (pos == null) {
            return;
        }
        checks.put(bodyId, new Check(sp.getUUID(), pos[0], pos[1], pos[2],
                sp.server.overworld().getGameTime()));
    }

    /** 每服务端 tick：惰性重建 + 到点量漂移。 */
    public void tick(net.minecraft.server.MinecraftServer server) {
        applyPending();
        if (checks.isEmpty()) {
            return;
        }
        long now = server.overworld().getGameTime();
        Iterator<Map.Entry<Long, Check>> it = checks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Check> entry = it.next();
            Check check = entry.getValue();
            if (now - check.startTick() < VERIFY_TICKS) {
                continue;
            }
            it.remove();
            long bodyId = entry.getKey();
            ServerPlayer sp = server.getPlayerList().getPlayer(check.playerId());
            if (sp == null) {
                continue;                   // 玩家下线了 ⇒ 这条读数没人看，丢掉
            }
            double[] pos = PhysicsBodyTracker.positionOf(bodyId);
            if (pos == null) {
                tell(sp, String.format(java.util.Locale.ROOT,
                        "[牵引枪·锁定判据] 物理体 #%d 已不存在 ⇒ 无法判定", bodyId), ChatFormatting.YELLOW);
                continue;
            }
            double dx = pos[0] - check.x();
            double dy = pos[1] - check.y();
            double dz = pos[2] - check.z();
            double drift = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double[] motion = PhysicsBodyTracker.motionOf(bodyId);
            double speed = motion == null ? Double.NaN
                    : Math.sqrt(motion[0] * motion[0] + motion[1] * motion[1] + motion[2] * motion[2]);
            boolean constraintLive = isLocked(bodyId);
            boolean pass = constraintLive && drift <= DRIFT_PASS;
            tell(sp, String.format(java.util.Locale.ROOT,
                            "[牵引枪·锁定判据] %d tick(%.1f 秒) 漂移 %.4f 格（阈值 %.2f）⇒ %s；"
                                    + "约束=%s，当前速度 %.3f m/s",
                            VERIFY_TICKS, VERIFY_TICKS / 20.0, drift, DRIFT_PASS,
                            pass ? "PASS" : "FAIL",
                            constraintLive ? "已生效" : "★未生效(约束 id=" + locks.get(bodyId) + ")", speed),
                    pass ? ChatFormatting.GREEN : ChatFormatting.RED);
        }
    }

    /** 一条读数（命令输出用）：锁定状态 + 当前位置/速度。 */
    public record Snapshot(long bodyId, long constraint, double x, double y, double z, double speed) {
    }

    /** 给 `/polymech physics locks` 用的只读快照（绝对坐标，不用相机相对量）。 */
    public java.util.List<Snapshot> snapshot() {
        java.util.List<Snapshot> out = new java.util.ArrayList<>();
        for (Map.Entry<Long, Long> entry : locks.entrySet()) {
            long bodyId = entry.getKey();
            double[] pos = PhysicsBodyTracker.positionOf(bodyId);
            double[] motion = PhysicsBodyTracker.motionOf(bodyId);
            double speed = motion == null ? Double.NaN
                    : Math.sqrt(motion[0] * motion[0] + motion[1] * motion[1] + motion[2] * motion[2]);
            out.add(new Snapshot(bodyId, entry.getValue(),
                    pos == null ? Double.NaN : pos[0],
                    pos == null ? Double.NaN : pos[1],
                    pos == null ? Double.NaN : pos[2],
                    speed));
        }
        return out;
    }

    private static void tell(ServerPlayer sp, String message, ChatFormatting color) {
        sp.sendSystemMessage(net.minecraft.network.chat.Component.literal(message)
                .withStyle(color));
    }

    public static PhysgunLocks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** 是否处于"已钉住"状态（约束真的建出来了）。 */
    public boolean isLocked(long bodyId) {
        Long constraint = locks.get(bodyId);
        return constraint != null && constraint > 0L;
    }

    /** 表里是否有这个体的条目（含"待重建"）。 */
    public boolean hasEntry(long bodyId) {
        return locks.containsKey(bodyId);
    }

    /** 已钉住的体数（诊断 / 命令用）。 */
    public int size() {
        return locks.size();
    }

    /**
     * 切换钉住状态 —— 照参考的 {@code toggleLock(uuid)}。
     *
     * @return 切换<b>之后</b>是否处于钉住状态（true = 刚钉住，false = 刚解锁）
     */
    public boolean toggle(long bodyId) {
        if (locks.containsKey(bodyId)) {
            unlock(bodyId);
            return false;
        }
        locks.put(bodyId, PhysicsBodyTracker.addFixedConstraint(bodyId));
        setDirty();
        return isLocked(bodyId);
    }

    /** 解锁：删原生约束 + 删条目。没锁过就是空操作。 */
    public void unlock(long bodyId) {
        Long constraint = locks.remove(bodyId);
        if (constraint != null && constraint > 0L) {
            PhysicsBodyTracker.removeConstraint(bodyId, constraint);
        }
        setDirty();
    }

    /**
     * 与 {@link #unlock} 的区别：<b>不管现在锁没锁都先清干净</b>。
     *
     * <p>给"开始拖"用（照参考 {@code drag(...)} 里的 {@code removeLock(subLevel)}）：
     * 拖着的东西必须是自由的，否则弹簧力和约束会互相顶。</p>
     */
    public boolean removeLock(long bodyId) {
        if (!locks.containsKey(bodyId)) {
            return false;
        }
        unlock(bodyId);
        return true;
    }

    /** 体没了（被拆/换维度）⇒ 丢掉条目（原生那边刚体删除时已自动清了约束与锚体）。 */
    public void forget(long bodyId) {
        if (locks.remove(bodyId) != null) {
            setDirty();
        }
    }

    /**
     * 惰性重建 —— 照参考的 {@code applyLockIfNeeded}。
     *
     * <p>每个服务端 tick 调一次：只为"条目在、约束还没建/已失效"的体重建。
     * 正常情况下这张表要么是空的、要么全是有效的，所以开销就是一次空遍历。
     * 需要它的场景：<b>读档</b>（{@code load} 里 handle 只能是 {@link #PENDING}）、
     * 以及体被重建后旧约束随刚体一起消失了。</p>
     */
    public void applyPending() {
        if (locks.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<Long, Long> entry : locks.entrySet()) {
            long bodyId = entry.getKey();
            long constraint = entry.getValue();
            if (constraint > 0L) {
                continue;
            }
            if (!PhysicsBodyTracker.isTracked(bodyId)) {
                continue;               // 体还没恢复出来（或已经没了）⇒ 下个 tick 再说
            }
            entry.setValue(PhysicsBodyTracker.addFixedConstraint(bodyId));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // 存成 long[]（而不是 ListTag<LongTag>）：更紧凑，而且避开 1.21.1 里
        // ListTag 那批 getLong(i)/getInt(i) 逐个被移除带来的版本差异。
        long[] ids = new long[locks.size()];
        int i = 0;
        for (long id : locks.keySet()) {
            ids[i++] = id;
        }
        tag.putLongArray(KEY_IDS, ids);
        return tag;
    }

    public static PhysgunLocks load(CompoundTag tag, HolderLookup.Provider registries) {
        PhysgunLocks data = new PhysgunLocks();
        // 只记"待重建"：约束句柄是原生运行时句柄，重启后一定失效（照参考 loadLocks 用 null）
        for (long id : tag.getLongArray(KEY_IDS)) {
            data.locks.put(id, PENDING);
        }
        return data;
    }
}
