package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.util.PipePathCalculator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class PipePreviewRenderer {
    
    private static BlockPos startPos = null;
    private static PipeIdentifier startPipeId = null;
    
    private static final int COLOR_A_POINT = 0xFF00FF00;
    private static final int COLOR_B_POINT = 0xFFFF0000;
    
    private static final float LINE_WIDTH = 0.06F;
    /** 路径框半透明面透明度 */
    private static final float FACE_ALPHA = 0.25F;

    /** 动画框集合：位置 -> 框（新建淡入，复用 chase 滑动，Create outliner 同款手感） */
    private static final Map<BlockPos, AnimatedOutline> outlines = new HashMap<>();
    
    public static void setStartPos(BlockPos pos, PipeIdentifier pipeId) {
        startPos = pos;
        startPipeId = pipeId;
    }
    
    public static BlockPos getStartPos() {
        return startPos;
    }
    
    public static PipeIdentifier getStartPipeId() {
        return startPipeId;
    }
    
    public static void clearStartPos() {
        startPos = null;
        startPipeId = null;
    }
    
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        
        Item heldItem = player.getMainHandItem().getItem();
        PipeIdentifier pipeId = getPipeId(heldItem);
        
        if (pipeId == null) {
            return;
        }

        if (player.isShiftKeyDown()) return;
        
        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;
        
        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;
        
        BlockPos targetPos = PipeInputHandler.getEndpointPosition(mc.level, blockHitResult);
        boolean containerEndpoint = targetPos.equals(clickedPos);
        
        // 容器/机器端点不需要支撑检查；普通铺设位置需有邻接支撑
        if (!containerEndpoint && !hasAdjacentSupport(mc.level, targetPos)) {
            return;
        }
        
        int available = player.isCreative() ? Integer.MAX_VALUE : player.getMainHandItem().getCount();

        long tick = mc.level.getGameTime();

        int pathColor = getPathColor(pipeId.size());

        // 收集本帧目标框：位置 -> 颜色（路径逐格一个框，起点/终点独立配色）
        Map<BlockPos, Integer> targets = new LinkedHashMap<>();
        if (startPos != null) {
            // 与服务端一致：路径端点先经过代理面吸附解析（接线锚点不变）
            BlockPos pathStart = PipePathCalculator.resolveEndpoint(mc.level, startPos, targetPos);
            BlockPos pathEnd = PipePathCalculator.resolveEndpoint(mc.level, targetPos, startPos);
            List<BlockPos> path = PipePathCalculator.calculatePath(mc.level, pathStart, pathEnd);

            int emptyCount = 0;
            for (BlockPos pos : path) {
                if (mc.level != null && mc.level.isEmptyBlock(pos)) {
                    emptyCount++;
                }
            }

            boolean canAfford = emptyCount <= available;

            targets.put(startPos, COLOR_A_POINT);

            if (!path.isEmpty() && canAfford) {
                for (int i = 0; i < path.size(); i++) {
                    BlockPos pos = path.get(i);
                    if (pos.equals(pathStart)) continue;

                    if (pos.equals(pathEnd)) {
                        targets.put(pos, COLOR_B_POINT);
                    } else {
                        targets.put(pos, pathColor);
                    }
                }
            }
        } else {
            targets.put(targetPos, COLOR_A_POINT);
        }

        // 同步动画框集合：新建触发淡入，复用目标位置触发 chase 滑动，消失的移除
        outlines.keySet().removeIf(key -> !targets.containsKey(key));
        for (Map.Entry<BlockPos, Integer> t : targets.entrySet()) {
            AnimatedOutline o = outlines.get(t.getKey());
            if (o == null) {
                outlines.put(t.getKey(), new AnimatedOutline(boxOf(t.getKey()), t.getValue(), LINE_WIDTH, FACE_ALPHA, tick));
            } else {
                o.chase(boxOf(t.getKey()), t.getValue(), LINE_WIDTH, FACE_ALPHA);
            }
        }

        PoseStack poseStack = event.getPoseStack();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();

        poseStack.pushPose();
        Vec3 cam = event.getCamera().getPosition();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();

        // chase 动画：每帧向目标位置指数平滑追赶（帧率无关，Create outliner 同款滑动）
        for (AnimatedOutline o : outlines.values())
            o.tickChase();

        // 第一轮：半透明面
        BufferBuilder faceBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (AnimatedOutline o : outlines.values())
            o.appendFaces(faceBuf, matrix, tick);
        AnimatedOutline.drawIfNotEmpty(faceBuf);

        // 第二轮：亮边线（实心方条棱 + 角点立方体，任何视角完全闭合）
        BufferBuilder edgeBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (AnimatedOutline o : outlines.values())
            o.appendEdges(edgeBuf, matrix, tick);
        AnimatedOutline.drawIfNotEmpty(edgeBuf);

        poseStack.popPose();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    /** 方块整格包围盒（微膨胀防 Z-fighting） */
    private static AABB boxOf(BlockPos pos) {
        return new AABB(pos).inflate(0.002);
    }
    
    private static int getPathColor(PipeBlock.PipeSize size) {
        return switch (size) {
            case SMALL -> 0xFF00FFFF;
            case BIG   -> 0xFFFF00FF;
            case HUGE  -> 0xFF0080FF;
            default    -> 0xFFFFFF00;
        };
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
    
    public static boolean hasAdjacentSupport(net.minecraft.world.level.Level level, BlockPos pos) {
        if (level == null) return false;
        for (Direction dir : Direction.values()) {
            if (!level.isEmptyBlock(pos.relative(dir))) {
                return true;
            }
        }
        return false;
    }
}
