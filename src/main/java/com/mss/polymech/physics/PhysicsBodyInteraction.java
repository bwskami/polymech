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
import java.util.List;

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
            return false;
        }
        BlockEntity be = proj.getBlockEntity(pos);
        // 用**真实**主手物品：下面的 mineBlock 要真的扣耐久（复制一份就白扣了）
        ItemStack tool = player.getMainHandItem();

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

        // ⑤ 掉落物搬到物理体所在世界的对应位置
        double[] world = PhysicsBodyTracker.localToWorld(bodyId, dx, dy, dz);
        if (world != null) {
            BlockPos at = BlockPos.containing(world[0], world[1], world[2]);
            for (ItemStack stack : drops) {
                Block.popResource(player.level(), at, stack);
            }
            player.level().playSound(null, at, destroyed.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }

        // ⑥ 用投影的现状校正缓存（方块没了 → 从快照里删掉、重建碰撞体、重发客户端）。
        //    半径 1：门/床被破坏时相邻格的支撑状态也会变。
        PhysicsBodyTracker.syncAroundFromProjection(bodyId, dx, dy, dz, 1);
        return true;
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
