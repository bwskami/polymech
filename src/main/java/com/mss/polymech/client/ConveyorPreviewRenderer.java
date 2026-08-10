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

    /** 动画框集合：身份 -> 框（"start"/"end" 固定键位置变化时 chase 滑动，Integer 中间格索引新格从上一格扩展生长） */
    private static final Map<Object, AnimatedOutline> outlines = new HashMap<>();

    /** 本帧目标框描述 */
    private record OutlineTarget(AABB box, int color, float faceAlpha) {}

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

        // 收集本帧目标框：身份 -> 目标框
        // 身份设计：起点/终点用固定键 "start"/"end"（位置变化时同框 chase 平滑滑动），
        // 中间格按路径顺序索引（新格从上一格位置扩展生长，Create 同款动态铺设）
        Map<Object, OutlineTarget> targets = new LinkedHashMap<>();
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

                targets.put("start", new OutlineTarget(boxOf(startPos), COLOR_A_POINT, FACE_ALPHA));

                if (!path.isEmpty() && canAfford) {
                    int idx = 0;
                    for (BlockPos pos : path) {
                        if (pos.equals(startPos)) continue;

                        if (pos.equals(targetPos)) {
                            targets.put("end", new OutlineTarget(boxOf(pos), COLOR_B_POINT, FACE_ALPHA));
                        } else {
                            targets.put(idx++, new OutlineTarget(boxOf(pos), COLOR_PATH, FACE_ALPHA));
                        }
                    }
                }
            }
        } else {
            targets.put("start", new OutlineTarget(boxOf(targetPos), COLOR_A_POINT, FACE_ALPHA));
        }

        // 同步动画框集合：身份复用（chase 滑动/变色），新建触发扩展生长 + 淡入，消失的移除
        outlines.keySet().removeIf(key -> !targets.containsKey(key));
        AABB prevBox = null; // 路径顺序上一格的目标框（新中间格从它扩展生长）
        for (Map.Entry<Object, OutlineTarget> t : targets.entrySet()) {
            AnimatedOutline o = outlines.get(t.getKey());
            OutlineTarget tt = t.getValue();
            if (o == null) {
                AABB initial = tt.box();
                if (t.getKey() instanceof Integer && prevBox != null) {
                    initial = prevBox; // 新中间格：从上一格位置平滑滑到本格，形成逐格扩展
                }
                outlines.put(t.getKey(), new AnimatedOutline(initial, tt.box(), tt.color(), LINE_WIDTH, tt.faceAlpha(), tick));
            } else {
                o.chase(tt.box(), tt.color(), LINE_WIDTH, tt.faceAlpha());
            }
            if (!"end".equals(t.getKey())) {
                prevBox = tt.box();
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