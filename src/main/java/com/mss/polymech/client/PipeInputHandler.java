package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.network.PipePlacementPacket;
import com.mss.polymech.util.PipePathCalculator;
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

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class PipeInputHandler {
    
    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        
        Item heldItem = player.getMainHandItem().getItem();
        PipeIdentifier pipeId = getPipeId(heldItem);
        
        if (pipeId == null) {
            PipePreviewRenderer.clearStartPos();
            return;
        }
        
        if (event.getAction() != 1) return;
        if (event.getButton() != 1) return;
        
        if (player.isShiftKeyDown()) return;
        
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;
        
        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;
        
        BlockPos targetPos = getEndpointPosition(mc.level, blockHitResult);
        boolean containerEndpoint = targetPos.equals(clickedPos);
        
        // 容器/机器端点不需要支撑检查；普通铺设位置需有邻接支撑
        if (!containerEndpoint && !PipePreviewRenderer.hasAdjacentSupport(mc.level, targetPos)) {
            return;
        }
        
        if (PipePreviewRenderer.getStartPos() == null) {
            PipePreviewRenderer.setStartPos(targetPos, pipeId);
        } else {
            BlockPos startPos = PipePreviewRenderer.getStartPos();
            PipeIdentifier startPipeId = PipePreviewRenderer.getStartPipeId();
            
            if (startPipeId.equals(pipeId)) {
                int available = player.isCreative() ? Integer.MAX_VALUE : player.getMainHandItem().getCount();
                
                // 与服务端一致：路径端点先经过代理面吸附解析（接线锚点不变）
                BlockPos pathStart = PipePathCalculator.resolveEndpoint(mc.level, startPos, targetPos);
                BlockPos pathEnd = PipePathCalculator.resolveEndpoint(mc.level, targetPos, startPos);
                java.util.List<BlockPos> path = PipePathCalculator.calculatePath(mc.level, pathStart, pathEnd);
                int emptyCount = 0;
                for (BlockPos pos : path) {
                    if (mc.level != null && mc.level.isEmptyBlock(pos)) {
                        emptyCount++;
                    }
                }
                
                if (emptyCount <= available) {
                    PacketDistributor.sendToServer(new PipePlacementPacket(
                            startPos, targetPos,
                            pipeId.material().getName(),
                            pipeId.size().getName()));
                }
            }
            
            PipePreviewRenderer.clearStartPos();
        }
    }
    
    private static PipeIdentifier getPipeId(Item item) {
        for (var materialEntry : ModBlocks.PIPE_TABLE.entrySet()) {
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                if (item == sizeEntry.getValue().get().asItem()) {
                    return new PipeIdentifier(materialEntry.getKey(), sizeEntry.getKey());
                }
            }
        }
        return null;
    }
    
    /**
     * 计算铺设端点位置：
     * 点击的方块是流体锚点（流体代理侧面方块/储罐/机器主方块）
     * → 直接选取方块所在格子作为端点；
     * 其他情况与正常放置逻辑一致：放置在点击面的外侧。
     */
    public static BlockPos getEndpointPosition(net.minecraft.world.level.Level level, BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        if (PipePathCalculator.isFluidAnchor(level, pos)) {
            return pos;
        }
        return pos.relative(hitResult.getDirection());
    }
}
