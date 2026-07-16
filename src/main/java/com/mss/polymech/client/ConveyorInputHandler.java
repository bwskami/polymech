package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.network.ConveyorPlacementPacket;
import com.mss.polymech.util.ConveyorPathCalculator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 传送带铺设客户端输入处理。
 * <p>
 * 手持传送带物品时右键：
 * <ul>
 *   <li>第一次右键：记录起始位置</li>
 *   <li>第二次右键（同一物品）：计算路径并发送到服务端铺设</li>
 * </ul>
 * 只能在同 Y 层铺设（平面）。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class ConveyorInputHandler {

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        Item heldItem = player.getMainHandItem().getItem();
        if (heldItem != ModBlocks.CONVEYOR.get().asItem()) {
            ConveyorPreviewRenderer.clearStartPos();
            return;
        }

        if (event.getAction() != 1) return;
        if (event.getButton() != 1) return;

        if (player.isShiftKeyDown()) return;

        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;

        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;

        BlockPos targetPos = clickedPos.relative(blockHitResult.getDirection());

        if (!ConveyorPreviewRenderer.hasAdjacentSupport(mc.level, targetPos)) {
            return;
        }

        if (ConveyorPreviewRenderer.getStartPos() == null) {
            ConveyorPreviewRenderer.setStartPos(targetPos);
        } else {
            BlockPos startPos = ConveyorPreviewRenderer.getStartPos();

            // 只有同 Y 层才能铺设
            if (startPos.getY() != targetPos.getY()) {
                ConveyorPreviewRenderer.clearStartPos();
                return;
            }

            int available = player.isCreative() ? Integer.MAX_VALUE : player.getMainHandItem().getCount();

            List<BlockPos> path = ConveyorPathCalculator.calculatePath(startPos, targetPos);
            int emptyCount = 0;
            for (BlockPos pos : path) {
                if (mc.level != null && (mc.level.isEmptyBlock(pos) || mc.level.getBlockState(pos).canBeReplaced())) {
                    emptyCount++;
                }
            }

            if (emptyCount <= available) {
                PacketDistributor.sendToServer(new ConveyorPlacementPacket(startPos, targetPos));
            }

            ConveyorPreviewRenderer.clearStartPos();
        }
    }
}