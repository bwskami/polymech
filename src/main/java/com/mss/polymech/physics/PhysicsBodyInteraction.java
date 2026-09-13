package com.mss.polymech.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物理体的交互：<b>全部在投影维度里跑原版逻辑</b>（对应 space/MPS 的
 * {@code ProjectionManager#handleInteraction}）。
 *
 * <p>为什么必须这样：物理体的方块不在世界里，走原版路径什么都打不到；
 * 而我们自己"改方块缓存"的旧做法没有掉落物、没有音效、开不了箱子。
 * 把方块放进投影维度之后，那边是<b>真实方块 + 真实方块实体</b>，
 * 于是 {@code useItemOn} / {@code useWithoutItem} / 破坏逻辑都能直接复用 ——
 * 箱子会开、按钮会响、破坏有掉落物。</p>
 *
 * <p><b>掉落物的搬运</b>：原版会把掉落物喷在投影维度的格里，而玩家在另一个维度 ——
 * 看不见也捡不到。所以这里先把掉落算出来、用刚体的姿态把方块中心变换到世界坐标，
 * 再在<b>物理体所在世界</b>喷出来。</p>
 */
public final class PhysicsBodyInteraction {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PolyMech/Physics/Interaction");

    /**
     * 生存模式的挖掘进度（每玩家一条）。
     *
     * <p><b>为什么必须自己算</b>：原版的挖掘时间由 {@code ServerPlayerGameMode} 累积、
     * 客户端 {@code MultiPlayerGameMode.continueDestroyBlock} 驱动 —— 两者都建立在
     * "方块在世界里、原版射线打得中"之上。物理体的方块不在世界，这条链路整条都走不到，
     * 于是"点一下就碎"。这里照 space 0.1.3 的做法自己在<b>投影维度</b>里累积
     * {@code state.getDestroyProgress(...)}：客户端按住左键时每 tick 发一次破坏包，
     * 累满 1.0 才真的破坏。</p>
     *
     * <p>切换目标 / 超过 {@link #MINING_TIMEOUT_TICKS} 没继续打就丢弃进度（与 space 的
     * {@code tick - lastTick > 2} 一致）。</p>
     */
    private static final Map<UUID, Mining> MINING = new HashMap<>();

    /**
     * 该玩家上一次广播过挖掘进度的格子 {@code {bodyId, dx, dy, dz}}。
     *
     * <p>换目标时要用它把旧格子的裂纹清掉（对应原版换目标时的 {@code ABORT_DESTROY_BLOCK}
     * → {@code ClientboundBlockDestructionPacket(pos, -1)}）。</p>
     */
    private static final Map<UUID, long[]> LAST_CELL = new HashMap<>();

    /**
     * 破坏后的停顿截止 tick（原版 {@code MultiPlayerGameMode.destroyDelay = 5}）。
     *
     * <p>原版这个停顿在客户端（{@code continueDestroyBlock} 开头 {@code destroyDelay > 0} 就直接返回），
     * 服务端只按时间校验节奏。我们把它也放到服务端算一份，免得客户端每 tick 灌包就能挖得更快。</p>
     */
    private static final Map<UUID, Integer> COOLDOWN_UNTIL = new HashMap<>();

    /** 破坏一格后的停顿（tick），与原版 {@code destroyDelay} 一致。 */
    private static final int BREAK_DELAY_TICKS = 5;

    /** 停手多久丢弃进度（tick）。2 与 space 一致：够容忍网络抖动，又不会"隔一秒接着挖"。 */
    private static final int MINING_TIMEOUT_TICKS = 2;

    private record Mining(long bodyId, int dx, int dy, int dz, float value, int lastTick) {
    }

    /** 玩家下线/换维度：丢掉进度（否则回来看见残留的裂纹）。 */
    public static void forget(ServerPlayer player) {
        COOLDOWN_UNTIL.remove(player.getUUID());
        Mining mining = MINING.remove(player.getUUID());
        if (mining != null) {
            broadcastProgress(mining.bodyId(), mining.dx(), mining.dy(), mining.dz(), -1.0F);
        }
        // 最后广播过的那一格也要清（他可能正挖着就下线了，别人屏幕上还留着裂纹）
        long[] last = LAST_CELL.remove(player.getUUID());
        if (last != null && (mining == null || last[0] != mining.bodyId()
                || last[1] != mining.dx() || last[2] != mining.dy() || last[3] != mining.dz())) {
            broadcastProgress(last[0], (int) last[1], (int) last[2], (int) last[3], -1.0F);
        }
    }

    /**
     * 每服务端 tick：把"停手"的进度清掉并广播。
     *
     * <p><b>为什么必须扫一遍</b>：玩家停止挖掘时不会发任何包（我们的协议里"松手"没有独立动作），
     * 只有服务端自己知道"超过 {@link #MINING_TIMEOUT_TICKS} 没继续"。不扫的话，
     * 客户端那张"哪一格挖到几分"的表永远停在最后一次进度上 —— 表现就是
     * <b>裂纹永久卡在那一格</b>（挖掘者自己和旁边的人都一样）。</p>
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (MINING.isEmpty()) {
            return;
        }
        int now = server.getTickCount();
        java.util.Iterator<Map.Entry<UUID, Mining>> it = MINING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Mining> entry = it.next();
            Mining mining = entry.getValue();
            if (now - mining.lastTick() > MINING_TIMEOUT_TICKS) {
                it.remove();
                broadcastProgress(mining.bodyId(), mining.dx(), mining.dy(), mining.dz(), -1.0F);
            }
        }
        // 停手后 LAST_CELL 也清掉（下一格自然会在换目标时重新登记）
        if (!LAST_CELL.isEmpty()) {
            LAST_CELL.keySet().removeIf(uuid -> !MINING.containsKey(uuid));
        }
        // 停顿计时过期的条目清掉（本来也不会涨）
        if (!COOLDOWN_UNTIL.isEmpty()) {
            COOLDOWN_UNTIL.entrySet().removeIf(e -> now >= e.getValue());
        }
    }

    private PhysicsBodyInteraction() {
    }

    /**
     * 破坏物理体上的一块。
     *
     * <p>顺序照原版 {@code ServerPlayerGameMode#destroyBlock}：{@code playerWillDestroy} →
     * 算掉落 → {@code onDestroyedByPlayer(dropBlock=false)} → {@code destroy} → 工具损耗。
     * 传 {@code dropBlock=false} 是故意的：掉落物由我们自己搬到世界侧，避免在投影维度里白喷一份。</p>
     */
    public static boolean breakBlock(ServerPlayer player, long bodyId, int dx, int dy, int dz) {
        ServerLevel proj = ProjectionManager.level();
        int slot = ProjectionManager.slotOf(bodyId);
        if (proj == null || slot < 0) {
            return false;
        }
        BlockPos pos = ProjectionManager.toProjection(slot, dx, dy, dz);
        BlockState state = proj.getBlockState(pos);
        if (state.isAir()) {
            // 投影里已经是空气 —— 但客户端的方块表里可能还有这一格，那样射线会**永远停在这格**：
            // 客户端每 tick 对着空气发包，服务端每次都在这里早退，后面的方块再也挖不动。
            // 表现就是"第一格破了之后手一直在挥、后面的格子纹丝不动"。
            // 两种不同步都要修，否则玩家会永久卡住：
            //   ① 缓存里还有、投影里没了（地皮与缓存不同步）→ 按投影校正缓存并重发快照；
            //   ② 缓存里也没了（客户端快照过期/丢包）→ 给该玩家重发整份快照。
            MINING.remove(player.getUUID());
            boolean inCache = PhysicsBodyTracker.blocksOf(bodyId).stream()
                    .anyMatch(e -> e.dx() == dx && e.dy() == dy && e.dz() == dz);
            if (inCache) {
                // 半径 2：一次把这些"缓存有、投影没有"的假方块清掉一片，
                // 否则按住左键时每 5 tick 才清掉一格，玩家会觉得"半天挖不动"。
                PhysicsBodyTracker.syncFromProjection(bodyId, dx, dy, dz, 2);
                LOGGER.info("[PolyMech] 破坏请求落在空气上：体 {} 格 ({},{},{}) 缓存仍有 → 已按投影校正并重发快照",
                        bodyId, dx, dy, dz);
            } else {
                PhysicsBodyTracker.sendAllTo(player);
                LOGGER.info("[PolyMech] 破坏请求落在空气上：体 {} 格 ({},{},{}) 缓存也无 → 已给 {} 重发体快照",
                        bodyId, dx, dy, dz, player.getName().getString());
            }
            return false;
        }
        BlockEntity be = proj.getBlockEntity(pos);
        // 用**真实**主手物品：下面的 mineBlock 要真的扣耐久（复制一份就白扣了）
        ItemStack tool = player.getMainHandItem();

        // ② 挖掘时间：生存模式按硬度累积进度，累满才破坏（创造仍然点一下就走完）。
        //    注意这里**在真正破坏之前**返回 —— 客户端每 tick 发一次包，所以进度是每 tick 涨一次。
        if (!player.isCreative()) {
            int tick = player.getServer() == null ? player.tickCount : player.getServer().getTickCount();
            // 换目标：把**上一格**的裂纹清掉。
            // 原版换目标时会发 ABORT_DESTROY_BLOCK，服务端据此把旧格子的破坏进度清成 -1
            // （ClientboundBlockDestructionPacket(pos, -1)）；我们这套协议里没有 ABORT 动作，
            // 不补这一下，旧格子的裂纹就会一直挂在客户端的进度表里（"准心移开，旧裂纹不消失"）。
            long[] last = LAST_CELL.get(player.getUUID());
            if (last == null || last[0] != bodyId || last[1] != dx || last[2] != dy || last[3] != dz) {
                if (last != null) {
                    broadcastProgress(last[0], (int) last[1], (int) last[2], (int) last[3], -1.0F);
                }
                LAST_CELL.put(player.getUUID(), new long[]{bodyId, dx, dy, dz});
            }
            Mining mining = MINING.get(player.getUUID());
            // 刚破完一格的停顿期内不累积进度（原版 destroyDelay）
            Integer until = COOLDOWN_UNTIL.get(player.getUUID());
            if (until != null) {
                if (tick < until) {
                    return true;
                }
                COOLDOWN_UNTIL.remove(player.getUUID());
            }
            // 同一 tick 收到多个包（网络抖动/误点）只算一次，与 space 的 attackThrottle 一致
            if (mining != null && mining.bodyId() == bodyId && mining.dx() == dx
                    && mining.dy() == dy && mining.dz() == dz && mining.lastTick() == tick) {
                return true;
            }
            if (mining == null || mining.bodyId() != bodyId || mining.dx() != dx
                    || mining.dy() != dy || mining.dz() != dz
                    || tick - mining.lastTick() > MINING_TIMEOUT_TICKS) {
                mining = new Mining(bodyId, dx, dy, dz, 0.0F, tick);
            }
            float gain = state.getDestroyProgress(player, proj, pos);
            float value = mining.value() + gain;
            if (value < 1.0F) {
                MINING.put(player.getUUID(), new Mining(bodyId, dx, dy, dz, value, tick));
                broadcastProgress(bodyId, dx, dy, dz, value);
                return true; // 还没挖穿：这一下算"正在挖"
            }
            MINING.remove(player.getUUID());
            // 破完这格：进入原版 destroyDelay 的 5 tick 停顿（下一格从停顿结束后才开始累积）
            COOLDOWN_UNTIL.put(player.getUUID(), tick + BREAK_DELAY_TICKS);
        } else {
            MINING.remove(player.getUUID());
        }

        // 原版的两条掉落规则，缺一条就会出怪事：
        //   ① 创造模式不掉落（此前"创造挖方块还给东西"就是漏了这条）；
        //   ② 需要正确工具才掉落（`canHarvestBlock`）—— 空手挖石头不掉圆石靠的是它。
        boolean creative = player.isCreative();
        boolean canHarvest = state.canHarvestBlock(proj, pos, player);
        boolean shouldDrop = !creative && canHarvest;

        // ① 掉落物先算出来（原版 API：吃工具附魔/精准采集等）
        List<ItemStack> drops = new ArrayList<>();
        if (shouldDrop) {
            drops.addAll(Block.getDrops(state, proj, pos, be, player, tool));
        }
        // ② 容器内容物自己收进掉落列表；**无论掉不掉都要清空** ——
        //    不清的话原版会在"投影维度那一格"把内容物喷出来，玩家在另一个维度看不见也捡不到。
        if (be instanceof Container container) {
            if (shouldDrop) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty()) {
                        drops.add(stack.copy());
                    }
                }
            }
            container.clearContent();
        }

        // ③ 破坏（dropBlock=false：不让原版在投影维度里喷掉落物）
        BlockState destroyed = state.getBlock().playerWillDestroy(proj, pos, state, player);
        boolean removed = destroyed.onDestroyedByPlayer(proj, pos, player, false, proj.getFluidState(pos));
        if (removed) {
            destroyed.getBlock().destroy(proj, pos, destroyed);
        }
        proj.removeBlockEntity(pos);
        // ④ 工具损耗与统计（创造模式不扣耐久，与原版一致）
        if (!creative) {
            tool.mineBlock(proj, destroyed, pos, player);
        }
        // ④b 经验：照 NeoForge 的 BlockDropsEvent 算（experience = processBlockExperience(等级, 工具, state.getExpDrop(…))，
        //     精准采集给 0、时运影响数量这些都在里面）。原版这条链路挂在 Block#dropResources 上，
        //     而我们为了把掉落物搬出投影维度绕过了它 —— 结果就是"挖矿有掉落、却永远不给经验"。
        //     这里只算数值，真正的生成放到 ⑤ 之后、和掉落物同一处（物理体在世界里的对应位置）。
        int experience = 0;
        if (shouldDrop) {
            experience = net.minecraft.world.item.enchantment.EnchantmentHelper.processBlockExperience(
                    proj, tool, destroyed.getExpDrop(proj, pos, be, player, tool));
        }

        // ⑤ 掉落物搬到物理体所在世界的对应位置
        double[] world = PhysicsBodyTracker.localToWorld(bodyId, dx, dy, dz);
        if (world != null) {
            BlockPos at = BlockPos.containing(world[0], world[1], world[2]);
            for (ItemStack stack : drops) {
                Block.popResource(player.level(), at, stack);
            }
            // 经验球跟掉落物同一处生成。award 会自己按位置的邻近球合并，是原版行为。
            if (experience > 0) {
                net.minecraft.world.entity.ExperienceOrb.award((ServerLevel) player.level(),
                        new Vec3(world[0], world[1], world[2]), experience);
            }
            // 破坏粒子 + 音效：levelEvent 2001 = PARTICLE_DESTROY_BLOCK（客户端会同时放破坏音）。
            // 用事件而不是自己 playSound，是因为它还负责喷碎块粒子 —— 破坏最少要"看得见"。
            player.level().levelEvent(net.minecraft.world.level.block.LevelEvent.PARTICLES_DESTROY_BLOCK,
                    at, Block.getId(destroyed));
        }
        // 挖穿了：把裂纹清掉（进度包 < 0）
        broadcastProgress(bodyId, dx, dy, dz, -1.0F);

        // ⑥ 用投影的现状校正缓存（方块没了 → 从快照里删掉、重建碰撞体、重发客户端）。
        //    半径 1：门/床被破坏时相邻格的支撑状态也会变。
        PhysicsBodyTracker.syncAroundFromProjection(bodyId, dx, dy, dz, 1);
        return true;
    }

    /**
     * 广播某人挖某格的进度（该维度全部玩家）——所有客户端都要看见裂纹，
     * 不只挖掘者自己（原版的 {@code ClientboundBlockDestructionPacket} 也是发给周围玩家的）。
     */
    private static void broadcastProgress(long bodyId, int dx, int dy, int dz, float progress) {
        ServerLevel level = PhysicsBodyTracker.levelOf(bodyId);
        if (level == null) {
            return;
        }
        PhysicsBodyTracker.broadcast(new com.mss.polymech.network.PhysicsBodyBreakProgressPacket(
                bodyId, dx, dy, dz, progress), level);
    }

    /**
     * 使用 / 放置：先在投影里跑原版 {@code useItemOn} / {@code useWithoutItem}，
     * 都没消耗这次交互、手上又是方块物品时，再按原版 {@link BlockPlaceContext} 放置。
     *
     * @param hand 0 = 主手，1 = 副手
     * @return 是否发生了交互
     */
    public static boolean useOrPlace(ServerPlayer player, long bodyId, int dx, int dy, int dz,
                                     int faceX, int faceY, int faceZ, int hand, boolean sneaking) {
        ServerLevel proj = ProjectionManager.level();
        int slot = ProjectionManager.slotOf(bodyId);
        if (proj == null || slot < 0) {
            return false;
        }
        BlockPos pos = ProjectionManager.toProjection(slot, dx, dy, dz);
        BlockState state = proj.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        InteractionHand interactionHand =
                hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack stack = player.getItemInHand(interactionHand);
        Direction face = Direction.getNearest(faceX, faceY, faceZ);
        if (face == null) {
            face = Direction.UP;
        }
        // 命中点在投影世界里构造：原版 useOn 需要它来算朝向/落点
        Vec3 hitVec = Vec3.atCenterOf(pos).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);

        boolean changed = false;
        // 潜行时原版不触发"使用"，直接走放置
        if (!sneaking) {
            ItemInteractionResult useResult = state.useItemOn(stack, proj, player, interactionHand, hit);
            if (useResult.consumesAction()) {
                changed = true;
            } else {
                InteractionResult plain = state.useWithoutItem(proj, player, hit);
                if (plain.consumesAction()) {
                    changed = true;
                }
            }
        }

        if (!changed && stack.getItem() instanceof BlockItem blockItem) {
            BlockPlaceContext ctx = new BlockPlaceContext(
                    new UseOnContext(proj, player, interactionHand, stack, hit));
            // 走原版 BlockItem.place，别自己 setBlock —— 它负责：
            //   · getStateForPlacement（朝向按玩家朝/点击面算，此前"朝向和原版不一样"就是漏了它）
            //   · setPlacedBy（门的另一半、床尾、告示牌…多格方块全靠它，此前"门被截掉"就是漏了它）
            //   · canSurvive / canPlace 校验（所以"红石粉上不能放红石粉"才是对的）
            //   · 音效、创造模式不消耗物品
            InteractionResult placed = blockItem.place(ctx);
            if (placed.consumesAction()) {
                BlockPos placedPos = ctx.getClickedPos();
                BlockPos start = ProjectionManager.startOf(slot);
                PhysicsBodyTracker.syncAroundFromProjection(bodyId,
                        placedPos.getX() - start.getX(),
                        placedPos.getY() - start.getY(),
                        placedPos.getZ() - start.getZ(), 1);
                changed = true;
            }
        }

        if (changed) {
            // 交互可能改了命中格及其相邻格的方块状态（拉杆、门、按钮…），按投影校正回缓存。
            // 半径 1 是为了覆盖门的另一半这类"连带改动"。
            PhysicsBodyTracker.syncAroundFromProjection(bodyId, dx, dy, dz, 1);
        }
        return changed;
    }
}
