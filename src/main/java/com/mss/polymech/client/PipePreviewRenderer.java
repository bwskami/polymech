package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.util.PipePathCalculator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import org.joml.Matrix4f;

import java.util.List;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class PipePreviewRenderer {
    
    private static BlockPos startPos = null;
    private static String startPipeType = null;
    
    // 不同管道类型的颜色配置
    private static final int COLOR_A_POINT = 0xFF00FF00;   // 绿色 - A点
    private static final int COLOR_B_POINT = 0xFFFF0000;   // 红色 - B点
    
    // 线条宽度（方块单位）
    private static final float LINE_WIDTH = 0.06F;
    
    public static void setStartPos(BlockPos pos, String pipeType) {
        startPos = pos;
        startPipeType = pipeType;
    }
    
    public static BlockPos getStartPos() {
        return startPos;
    }
    
    public static String getStartPipeType() {
        return startPipeType;
    }
    
    public static void clearStartPos() {
        startPos = null;
        startPipeType = null;
    }
    
    @SubscribeEvent
    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        
        // 获取当前手持的管道类型
        Item heldItem = player.getMainHandItem().getItem();
        String pipeType = getPipeType(heldItem);
        
        if (pipeType == null) {
            return;
        }
        
        // Shift 按下时不显示预览（普通放置模式）
        if (player.isShiftKeyDown()) return;
        
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;
        
        // 计算应该放置管道的位置（与 PipeInputHandler 一致）
        BlockPos targetPos = getPlacementPosition(blockHitResult);
        
        PoseStack poseStack = event.getPoseStack();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();  // 禁用背面剔除，确保所有面都可见
        
        // 根据管道类型选择路径颜色
        int pathColor = getPathColor(pipeType);
        
        if (startPos != null) {
            // A点已选定，显示A点、B点和路径
            List<BlockPos> path = PipePathCalculator.calculatePath(startPos, targetPos);
            
            // 渲染A点（绿色）
            renderBlockOutline(poseStack, event, startPos, COLOR_A_POINT);
            
            // 渲染路径和B点
            if (!path.isEmpty()) {
                for (int i = 0; i < path.size(); i++) {
                    BlockPos pos = path.get(i);
                    if (pos.equals(startPos)) continue; // A点已经单独渲染
                    
                    if (pos.equals(targetPos)) {
                        // B点用红色
                        renderBlockOutline(poseStack, event, pos, COLOR_B_POINT);
                    } else {
                        // 中间路径用管道类型对应的颜色
                        renderBlockOutline(poseStack, event, pos, pathColor);
                    }
                }
            }
        } else {
            // 未选定A点，显示当前目标方块预览（绿色）
            renderBlockOutline(poseStack, event, targetPos, COLOR_A_POINT);
        }
        
        RenderSystem.enableCull();  // 恢复背面剔除
        RenderSystem.enableDepthTest();
    }
    
    /**
     * 根据管道类型获取路径颜色
     */
    private static int getPathColor(String pipeType) {
        return switch (pipeType) {
            case "pipe" -> 0xFFFFFF00;      // 黄色 - 普通管道
            case "small_pipe" -> 0xFF00FFFF; // 青色 - 小管道
            case "big_pipe" -> 0xFFFF00FF;   // 品红 - 大管道
            case "huge_pipe" -> 0xFF0080FF;  // 橙色 - 超大管道
            default -> 0xFFFFFF00;
        };
    }
    
    /**
     * 根据物品获取管道类型
     */
    private static String getPipeType(Item item) {
        if (item == ModBlocks.PIPE.asItem()) return "pipe";
        if (item == ModBlocks.SMALL_PIPE.asItem()) return "small_pipe";
        if (item == ModBlocks.BIG_PIPE.asItem()) return "big_pipe";
        if (item == ModBlocks.HUGE_PIPE.asItem()) return "huge_pipe";
        return null;
    }
    
    /**
     * 获取方块放置位置
     * 与正常放置方块时的逻辑一致：点击方块表面时，放置在表面的外侧
     */
    private static BlockPos getPlacementPosition(BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        return pos.relative(hitResult.getDirection());
    }

    private static void renderBlockOutline(PoseStack poseStack, RenderHighlightEvent.Block event, BlockPos pos, int color) {
        poseStack.pushPose();
        poseStack.translate(
                (double) pos.getX() - event.getCamera().getPosition().x(),
                (double) pos.getY() - event.getCamera().getPosition().y(),
                (double) pos.getZ() - event.getCamera().getPosition().z()
        );
        
        Matrix4f matrix = poseStack.last().pose();
        renderCubeWireframe(matrix, 0, 0, 0, 1, 1, 1, color);
        
        poseStack.popPose();
    }
    
    private static void renderCubeWireframe(Matrix4f matrix, float x1, float y1, float z1, 
                                            float x2, float y2, float z2, int color) {
        float a = (float) ((color >> 24) & 0xFF) / 255.0F;
        float r = (float) ((color >> 16) & 0xFF) / 255.0F;
        float g = (float) ((color >> 8) & 0xFF) / 255.0F;
        float b = (float) (color & 0xFF) / 255.0F;
        
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        
        float hw = LINE_WIDTH / 2.0F;
        
        // 12条边线，每条用Quad绘制以支持线宽
        // 底面
        addThickLine(buf, matrix, x1, y1, z1, x2, y1, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z1, x2, y1, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z2, x1, y1, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y1, z2, x1, y1, z1, hw, r, g, b, a);
        
        // 顶面
        addThickLine(buf, matrix, x1, y2, z1, x2, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y2, z1, x2, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y2, z2, x1, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y2, z2, x1, y2, z1, hw, r, g, b, a);
        
        // 垂直边
        addThickLine(buf, matrix, x1, y1, z1, x1, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z1, x2, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z2, x2, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y1, z2, x1, y2, z2, hw, r, g, b, a);
        
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }
    
    /**
     * 绘制有宽度的线条（使用两个垂直的Quad形成十字形）
     */
    private static void addThickLine(BufferBuilder buf, Matrix4f matrix, 
                                     float x1, float y1, float z1, 
                                     float x2, float y2, float z2,
                                     float hw, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        
        // 确定线条的主要方向
        boolean xMajor = Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz);
        boolean yMajor = Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz);
        
        // 第一个Quad的扩展方向
        float p1x = 0, p1y = 0, p1z = 0;
        // 第二个Quad的扩展方向（与第一个垂直）
        float p2x = 0, p2y = 0, p2z = 0;
        
        if (xMajor) {
            p1y = hw;
            p2z = hw;
        } else if (yMajor) {
            p1x = hw;
            p2z = hw;
        } else {
            p1x = hw;
            p2y = hw;
        }
        
        // 绘制第一个Quad（双面）
        buf.addVertex(matrix, x1 - p1x, y1 - p1y, z1 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p1x, y1 + p1y, z1 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p1x, y2 + p1y, z2 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p1x, y2 - p1y, z2 - p1z).setColor(r, g, b, a);
        
        // 绘制第一个Quad的反向面（确保双面可见）
        buf.addVertex(matrix, x1 + p1x, y1 + p1y, z1 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 - p1x, y1 - p1y, z1 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p1x, y2 - p1y, z2 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p1x, y2 + p1y, z2 + p1z).setColor(r, g, b, a);
        
        // 绘制第二个Quad（双面）
        buf.addVertex(matrix, x1 - p2x, y1 - p2y, z1 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p2x, y1 + p2y, z1 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p2x, y2 + p2y, z2 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p2x, y2 - p2y, z2 - p2z).setColor(r, g, b, a);
        
        // 绘制第二个Quad的反向面
        buf.addVertex(matrix, x1 + p2x, y1 + p2y, z1 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 - p2x, y1 - p2y, z1 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p2x, y2 - p2y, z2 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p2x, y2 + p2y, z2 + p2z).setColor(r, g, b, a);
    }
}
