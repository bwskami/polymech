package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.network.PipePlacementPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
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
        
        if (!player.getMainHandItem().is(ModBlocks.PIPE.asItem())) {
            PipePreviewRenderer.clearStartPos();
            return;
        }
        
        // 只处理右键点击 (button 1 = 右键)
        if (event.getAction() != 1) return;
        if (event.getButton() != 1) return;
        
        // Shift + 右键跳过（用于普通放置）
        if (player.isShiftKeyDown()) return;
        
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;
        
        // 计算应该放置管道的位置
        BlockPos targetPos = getPlacementPosition(blockHitResult);
        
        if (PipePreviewRenderer.getStartPos() == null) {
            // 第一次右键：设置A点
            PipePreviewRenderer.setStartPos(targetPos);
        } else {
            // 第二次右键：确认B点，发送铺设请求
            BlockPos startPos = PipePreviewRenderer.getStartPos();
            PacketDistributor.sendToServer(new PipePlacementPacket(startPos, targetPos));
            PipePreviewRenderer.clearStartPos();
        }
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
