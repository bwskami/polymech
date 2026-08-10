package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mss.polymech.Polymech;
import com.mss.polymech.item.ConveyorItem;
import com.mss.polymech.util.ConveyorPathCalculator;
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

/**
 * 传送带铺设预览渲染器。
 * <p>
 * 手持传送带物品时，在目标方块上显示半透明预览框：
 * <ul>
 *   <li>未选起点：绿色框标注当前指向的放置位置</li>
 *   <li>已选起点：绿色框标注起点，红色框标注终点，黄色框标注路径</li>
 * </ul>
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class ConveyorPreviewRenderer {

    private static BlockPos startPos = null;

    private static final int COLOR_A_POINT = 0xFF00FF00;
    private static final int COLOR_B_POINT = 0xFFFF0000;
    private static final int COLOR_PATH = 0xFFFFFF00;

    private static final float LINE_WIDTH = 0.06F;
    /** 路径框半透明面透明度 */
    private static final float FACE_ALPHA = 0.25F;

    /** 动画框集合：位置 -> 框（新建淡入，复用 chase 滑动，Create outliner 同款手感） */
    private static final Map<BlockPos, AnimatedOutline> outlines = new HashMap<>();

    public static void setStartPos(BlockPos pos) {
        startPos = pos;
    }

    public static BlockPos getStartPos() {
        return startPos;
    }

    public static void clearStartPos() {
        startPos = null;
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
        if (!(heldItem instanceof ConveyorItem)) {
            return;
        }

        if (player.isShiftKeyDown()) return;

        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;

        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;

        BlockPos targetPos = clickedPos.relative(blockHitResult.getDirection());

        // 检查目标位置在同一 Y 层且有相邻支撑
        if (!hasAdjacentSupport(mc.level, targetPos)) {
            return;
        }

        int available = player.isCreative() ? Integer.MAX_VALUE : player.getMainHandItem().getCount();

        long tick = mc.level.getGameTime();

        // 收集本帧目标框：位置 -> 颜色（路径逐格一个框，起点/终点独立配色）
        Map<BlockPos, Integer> targets = new LinkedHashMap<>();
        if (startPos != null) {
            // 起点和目标必须在同一 Y 层
            if (startPos.getY() == targetPos.getY()) {
                List<BlockPos> path = ConveyorPathCalculator.calculatePath(startPos, targetPos);

                int emptyCount = 0;
                for (BlockPos pos : path) {
                    if (mc.level != null && (mc.level.isEmptyBlock(pos) || mc.level.getBlockState(pos).canBeReplaced())) {
                        emptyCount++;
                    }
                }

                boolean canAfford = emptyCount <= available;

                targets.put(startPos, COLOR_A_POINT);

                if (!path.isEmpty() && canAfford) {
                    for (BlockPos pos : path) {
                        if (pos.equals(startPos)) continue;

                        if (pos.equals(targetPos)) {
                            targets.put(pos, COLOR_B_POINT);
                        } else {
                            targets.put(pos, COLOR_PATH);
                        }
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

    /**
     * 检查目标位置是否有相邻支撑。
     * 至少一个相邻面有非空方块即可。
     */
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