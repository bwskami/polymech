package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.api.material.PipeMaterial;
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
        
        BlockPos targetPos = getPlacementPosition(blockHitResult);
        
        if (PipePreviewRenderer.getStartPos() == null) {
            PipePreviewRenderer.setStartPos(targetPos, pipeId);
        } else {
            BlockPos startPos = PipePreviewRenderer.getStartPos();
            PipeIdentifier startPipeId = PipePreviewRenderer.getStartPipeId();
            
            if (startPipeId.equals(pipeId)) {
                int available = player.isCreative() ? Integer.MAX_VALUE : player.getMainHandItem().getCount();
                
                java.util.List<BlockPos> path = PipePathCalculator.calculatePath(startPos, targetPos);
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
     * 获取方块放置位置
     * 与正常放置方块时的逻辑一致：点击方块表面时，放置在表面的外侧
     */
    private static BlockPos getPlacementPosition(BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        // 如果点击的是方块的侧面，则放置在侧面的外侧
        // 这与 BlockItem.useOn 的行为一致
        return pos.relative(hitResult.getDirection());
    }
}
