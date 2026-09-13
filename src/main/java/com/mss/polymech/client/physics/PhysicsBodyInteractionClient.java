package com.mss.polymech.client.physics;

import com.mss.polymech.Polymech;
import com.mss.polymech.network.PhysicsBodyEditPacket;
import com.mss.polymech.physics.PhysicsRaycast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 客户端物理体交互：破坏 / 放置物理体上的方块。
 *
 * <p>物理体的方块已被从世界移除，原版 {@code clip()} 打不到，所以这里自己做射线
 * （{@link PhysicsRaycast}）：命中最近物理体方块 → 发包给服务端 → 服务端在投影维度里跑原版逻辑。</p>
 *
 * <p><b>破坏为什么要"每 tick 发一次"</b>：原版的挖掘时间由
 * {@code Minecraft.continueAttack → MultiPlayerGameMode.continueDestroyBlock} 驱动，
 * 而它只在<span>原版射线命中真实方块</span>时才走 —— 物理体那里是空气，这条路整条断掉
 * （{@code Minecraft.continueAttack} 里 {@code hitResult.getType() == BLOCK} 的判空直接跳过）。
 * 所以按住左键的"持续挖掘"必须我们自己发：{@link #onClientTick} 每 tick 一个包，
 * 服务端据此累积硬度进度（见 {@code PhysicsBodyInteraction}）。</p>
 *
 * <p><b>本地不再预测破坏</b>：有挖掘时间之后，"点一下就消失"的预测反而是错的
 * （服务端还在累积进度）。创造模式除外 —— 那边本来就是瞬破，预测能省掉 1 tick 的延迟。</p>
 *
 * <p>优先级：若原版射线命中真实方块且比物理体更近，就交给原版处理（不影响正常挖方块）。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PhysicsBodyInteractionClient {

    private static final Quaternionf ROT = new Quaternionf();
    private static final Quaternionf INV = new Quaternionf();
    private static final Vector3f TMP = new Vector3f();

    /** 创造模式破格间隔（tick），照 {@code MultiPlayerGameMode.continueDestroyBlock} 的 destroyDelay。 */
    private static final int CREATIVE_BREAK_DELAY = 5;

    /** 破坏一格之后的停顿（tick），照原版 {@code MultiPlayerGameMode.destroyDelay = 5}。 */
    private static final int BREAK_DELAY_TICKS = 5;

    /** 剩余停顿 tick：>0 时只挥手、不发破坏包（原版在 destroyDelay 期间 return true 什么都不做）。 */
    private static int destroyDelay;

    /**
     * 上一次按左键的客户端 tick（防止"按下那一帧"和 tick 循环重复发包）。
     *
     * <p>用 -1 当"从未"哨兵，<b>不要用 {@code Long.MIN_VALUE}</b>：下面凡是要做
     * {@code tick - last} 相减的地方都会溢出成负数，判断恒为 false（踩过一次，
     * 表现就是"创造模式按住左键只在按下那一下破了一格，之后手一直挥却再也不破"）。
     * gameTime 从 0 开始，所以 -1 足够当哨兵。</p>
     */
    private static long lastAttackTick = -1L;

    /** 创造模式上一次破格的 tick（原版 destroyDelay = 5）。哨兵同上，不能用 Long.MIN_VALUE。 */
    private static long lastCreativeBreakTick = -CREATIVE_BREAK_DELAY;

    /** 正在挖的目标（打击反馈用；裂纹进度见 {@link PhysicsBodyRenderer#acceptBreakProgress}）。 */
    private static long miningBodyId = -1L;
    private static int miningDx;
    private static int miningDy;
    private static int miningDz;
    /** 已挖了几 tick（打击音效每 4 tick 一次，照原版）。 */
    private static int miningTicks;

    private PhysicsBodyInteractionClient() {
    }

    /**
     * 按下左键/右键那一下。
     *
     * <p>破坏同时也在 {@link #onClientTick} 里每 tick 发 —— 这里保留是因为"按下"必须立刻有反应，
     * 不能等到下一个 tick；用 {@link #lastAttackTick} 去重。</p>
     */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (ClientPhysicsWorld.size() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        Player player = mc.player;

        Hit hit = currentHit(player);
        if (hit == null) {
            return;
        }

        if (event.isAttack()) {
            // 创造模式：瞬破，本地可以先删（服务端的权威 patch 马上到）
            if (player.isCreative()) {
                ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(hit.bodyId());
                if (body != null) {
                    body.predictBreak(hit.dx, hit.dy, hit.dz);
                }
                // 记下本 tick 已经破过：否则同一 tick 的 tick 循环会再破一格
                // （原版是 startAttack 成功后跳过 continueAttack，等价）
                if (mc.level != null) {
                    lastCreativeBreakTick = mc.level.getGameTime();
                }
            }
            sendBreak(hit, mc);
            startMiningFeedback(hit);
            event.setCanceled(true);
        } else if (event.isUseItem()) {
            InteractionHand hand = event.getHand();
            // <b>不做本地放置预测。</b> "这次右键是使用还是放置"只有服务端在投影维度里跑一次
            // 原版 useOn 才知道（拉杆要切换、按钮要按下、箱子要开、门要开合…）。
            // 本地一旦预测"放置"，就会先冒出一个假方块，随后被服务端快照顶回去 ——
            // 表现就是"手里拿着拉杆右键拉杆，那一格瞬间多出一个拉杆，又被旧的顶回来"，
            // 甚至闪到旁边的方块格上。放置改由服务端确认（约 1 tick），换来的是不再有假方块。
            // 左键破坏只在创造模式预测（那边本来就是瞬破）；生存有挖掘时间，预测反而是错的。
            PacketDistributor.sendToServer(PhysicsBodyEditPacket.use(
                    hit.bodyId, hit.dx, hit.dy, hit.dz,
                    hit.normalX, hit.normalY, hit.normalZ,
                    hand == InteractionHand.OFF_HAND ? 1 : 0,
                    player.isShiftKeyDown()));
            event.setCanceled(true);
        }
    }

    /**
     * 按住左键时的持续挖掘：每 tick 发一次破坏包（服务端据此累积硬度进度）。
     *
     * <p>这就是"生存模式挖物理体方块是秒破"的正解所在 —— 服务端会在进度没满时只回进度、不破坏。
     * 创造模式照原版是每 5 tick 破一格（{@code MultiPlayerGameMode} 里的 {@code destroyDelay = 5}）。</p>
     *
     * <p><b>挥手也要自己补</b>：原版是 {@code Minecraft.continueAttack} 里
     * {@code continueDestroyBlock(...) && shouldSwingHand() → player.swing(MAIN_HAND)}，
     * 而 {@code continueAttack} 只在原版射线命中<b>真实方块</b>时才走进去 ——
     * 物理体那里是空气，于是按住不放时手臂一动不动（点击那一下会挥，是
     * {@code startAttack} 末尾无条件挥的）。</p>
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null
                || !mc.options.keyAttack.isDown() || mc.player.isUsingItem()) {
            resetMining();
            lastAttackTick = -1L;
            return;
        }
        Hit hit = currentHit(mc.player);
        if (hit == null) {
            resetMining();
            return;
        }

        // 挥手：与原版 same 每 tick 一次（LivingEntity#swing 自己会把动画按半程重起，
        // 所以看起来是"连续挥"而不是卡住）。
        mc.player.swing(InteractionHand.MAIN_HAND);

        long tick = mc.level.getGameTime();

        // 原版节奏：破掉一格之后 destroyDelay = 5，这 5 tick 里不累积进度（只挥手、没有打击音效）。
        // 客户端靠"刚才挖的那一格已经不在方块表里了"来判断它被破掉了。
        if (miningBodyId >= 0 && !cellExists(miningBodyId, miningDx, miningDy, miningDz)) {
            destroyDelay = BREAK_DELAY_TICKS;
            resetMining();
        }

        if (mc.player.isCreative()) {
            // 创造：原版 startDestroyBlock/continueDestroyBlock 也是每破一格设 destroyDelay = 5
            if (tick - lastCreativeBreakTick >= CREATIVE_BREAK_DELAY) {
                lastCreativeBreakTick = tick;
                ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(hit.bodyId());
                if (body != null) {
                    body.predictBreak(hit.dx, hit.dy, hit.dz);
                }
                sendBreak(hit, mc);
            }
            resetMining(); // 创造没有挖掘进度，不需要打击反馈状态
            return;
        }

        if (destroyDelay > 0) {
            destroyDelay--;   // 原版 destroyDelay：破坏后这 5 tick 不推进下一格
            resetMining();
            return;
        }
        if (tick != lastAttackTick) {
            sendBreak(hit, mc);
        }
        updateMiningFeedback(mc, hit);
    }

    /** 那一格是否还在客户端方块表里（用来判断"刚挖的那格被破掉了"）。 */
    private static boolean cellExists(long bodyId, int dx, int dy, int dz) {
        ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(bodyId);
        if (body == null) {
            return false;
        }
        for (ClientPhysicsWorld.BlockEntry entry : body.blocks()) {
            if (entry.dx() == dx && entry.dy() == dy && entry.dz() == dz) {
                return true;
            }
        }
        return false;
    }

    private static void sendBreak(Hit hit, Minecraft mc) {
        PacketDistributor.sendToServer(PhysicsBodyEditPacket.breakBlock(
                hit.bodyId, hit.dx, hit.dy, hit.dz));
        lastAttackTick = mc.level == null ? lastAttackTick : mc.level.getGameTime();
    }

    // ==================== 挖掘反馈（裂纹进度 / 打击音效与粒子） ====================

    /** 开始挖新目标：换目标就重来（与服务端的"换目标重置进度"对应）。 */
    private static void startMiningFeedback(Hit hit) {
        if (miningBodyId == hit.bodyId && miningDx == hit.dx && miningDy == hit.dy && miningDz == hit.dz) {
            return;
        }
        miningBodyId = hit.bodyId;
        miningDx = hit.dx;
        miningDy = hit.dy;
        miningDz = hit.dz;
        miningTicks = 0;
    }

    private static void updateMiningFeedback(Minecraft mc, Hit hit) {
        startMiningFeedback(hit);
        // 打击音效：原版是 destroyTicks % 4 == 0（destroyTicks 在换目标时归零），粒子每 tick 一点。
        if (miningTicks % 4 == 0) {
            playHitSound(mc, hit);
        }
        spawnHitParticles(mc, hit);
        miningTicks++;
    }

    private static void playHitSound(Minecraft mc, Hit hit) {
        BlockState state = stateOf(hit);
        if (state == null || mc.level == null || mc.player == null) {
            return;
        }
        Vec3 at = blockCenter(hit);
        BlockPos pos = BlockPos.containing(at);
        SoundType sound = state.getSoundType(mc.level, pos, mc.player);
        mc.getSoundManager().play(new SimpleSoundInstance(sound.getHitSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F,
                SoundInstance.createUnseededRandom(), at.x, at.y, at.z));
    }

    private static void spawnHitParticles(Minecraft mc, Hit hit) {
        BlockState state = stateOf(hit);
        if (state == null || mc.level == null) {
            return;
        }
        // 原版 addBlockHitEffects 会去读世界里的那一格（物理体那里是空气，读了没用），
        // ParticleEngine 也没有"传入方块状态"的公开重载 —— 所以照原版 crack() 的做法
        // 自己撒两粒 BlockParticleOption（每 tick 一点，与原版打击粒子同量级）。
        var option = new net.minecraft.core.particles.BlockParticleOption(
                net.minecraft.core.particles.ParticleTypes.BLOCK, state);
        ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(hit.bodyId);
        if (body == null) {
            return;
        }
        float[] rot = new float[4];
        body.renderRotation(1.0F, rot);
        Quaternionf q = new Quaternionf(rot[0], rot[1], rot[2], rot[3]);
        for (int i = 0; i < 2; i++) {
            TMP.set(hit.dx + RANDOM.nextFloat(), hit.dy + RANDOM.nextFloat(), hit.dz + RANDOM.nextFloat());
            q.transform(TMP);
            mc.level.addParticle(option,
                    body.renderX(1.0F) + TMP.x, body.renderY(1.0F) + TMP.y, body.renderZ(1.0F) + TMP.z,
                    0.0, 0.0, 0.0);
        }
    }

    private static final java.util.Random RANDOM = new java.util.Random();

    private static BlockState stateOf(Hit hit) {
        ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(hit.bodyId);
        if (body == null) {
            return null;
        }
        for (ClientPhysicsWorld.BlockEntry entry : body.blocks()) {
            if (entry.dx() == hit.dx && entry.dy() == hit.dy && entry.dz() == hit.dz) {
                return entry.state();
            }
        }
        return null;
    }

    /** 那一格在世界里的中心（音效/粒子用）。 */
    private static Vec3 blockCenter(Hit hit) {
        ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(hit.bodyId);
        if (body == null) {
            return Vec3.ZERO;
        }
        float[] rot = new float[4];
        body.renderRotation(1.0F, rot);
        TMP.set(hit.dx + 0.5F, hit.dy + 0.5F, hit.dz + 0.5F);
        new Quaternionf(rot[0], rot[1], rot[2], rot[3]).transform(TMP);
        return new Vec3(body.renderX(1.0F) + TMP.x, body.renderY(1.0F) + TMP.y, body.renderZ(1.0F) + TMP.z);
    }

    private static void resetMining() {
        miningBodyId = -1L;
        miningTicks = 0;
    }

    // ==================== 射线 ====================

    /**
     * 当前该由物理体接管的那一格：射线命中物理体，且比原版命中更近。
     *
     * <p>返回 null 表示"这次交互归原版"（没命中物理体，或者真实方块更近）。</p>
     */
    private static Hit currentHit(Player player) {
        Hit hit = castAgainstBodies(player.getEyePosition(), player.getViewVector(1.0F),
                player.blockInteractionRange());
        if (hit == null) {
            return null;
        }
        HitResult vanilla = Minecraft.getInstance().hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.distanceTo(player) < hit.distance) {
            return null;
        }
        return hit;
    }

    /** 对所有物理体做射线，取最近命中。 */
    public static Hit castAgainstBodies(Vec3 origin, Vec3 dir, double reach) {
        Hit best = null;
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            double bx = body.renderX(1.0f);
            double by = body.renderY(1.0f);
            double bz = body.renderZ(1.0f);

            float[] rotF = new float[4];
            body.renderRotation(1.0f, rotF);
            ROT.set(rotF[0], rotF[1], rotF[2], rotF[3]);
            INV.set(ROT).conjugate();

            TMP.set((float) (origin.x - bx), (float) (origin.y - by), (float) (origin.z - bz));
            INV.transform(TMP);
            double[] localOrigin = {TMP.x, TMP.y, TMP.z};

            TMP.set((float) dir.x, (float) dir.y, (float) dir.z);
            INV.transform(TMP);
            double[] localDir = {TMP.x, TMP.y, TMP.z};
            double len = Math.sqrt(localDir[0] * localDir[0] + localDir[1] * localDir[1] + localDir[2] * localDir[2]);
            if (len < 1.0e-6) {
                continue;
            }
            localDir[0] /= len;
            localDir[1] /= len;
            localDir[2] /= len;

            int[] blocks = new int[body.blocks().size() * 3];
            for (int i = 0; i < body.blocks().size(); i++) {
                ClientPhysicsWorld.BlockEntry e = body.blocks().get(i);
                blocks[i * 3] = e.dx();
                blocks[i * 3 + 1] = e.dy();
                blocks[i * 3 + 2] = e.dz();
            }

            PhysicsRaycast.Hit local = PhysicsRaycast.cast(localOrigin, localDir, reach, blocks);
            if (local == null) {
                continue;
            }
            if (best == null || local.distance() < best.distance) {
                // 法线同样要变换回世界空间（放置方向用）
                TMP.set(local.normalX(), local.normalY(), local.normalZ());
                ROT.transform(TMP);
                best = new Hit(body.id(), local.distance(), local.blockX(), local.blockY(), local.blockZ(),
                        Math.round(TMP.x), Math.round(TMP.y), Math.round(TMP.z));
            }
        }
        return best;
    }

    /** 命中的物理体方块。 */
    public record Hit(long bodyId, double distance, int dx, int dy, int dz,
                      int normalX, int normalY, int normalZ) {
    }
}
