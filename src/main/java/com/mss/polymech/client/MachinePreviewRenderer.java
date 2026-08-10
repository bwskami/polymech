package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.model.MachineGeoModel;
import com.mss.polymech.item.BlueprintToolItem;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.production.HorizontalSteamBoilerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class MachinePreviewRenderer {

    private static final int COLOR_INVALID = 0xFFFF0000;
    private static final int COLOR_BOUNDS = 0xFFFFFF00;

    private static final float LINE_WIDTH = 0.06F;
    /** 无效位置红框半透明面透明度 */
    private static final float FACE_ALPHA = 0.25F;

    /** 动画框集合：key -> 框（BlockPos=无效位置红框，"bounds"=整体边界黄框；新建淡入，复用 chase 滑动） */
    private static final Map<Object, AnimatedOutline> outlines = new HashMap<>();

    /** 本帧目标框描述 */
    private record OutlineTarget(Object key, AABB box, int color, float faceAlpha) {}


    private static final Map<BlockEntityType<?>, BlockEntity> tempBeCache = new HashMap<>();
    @SuppressWarnings("rawtypes")
    private static final Map<BlockEntityType<?>, GeoBlockRenderer> ghostRendererCache = new HashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static GeoBlockRenderer getOrCreateGhostRenderer(BlockEntityType<?> beType, BlockEntity tempBe) {
        return ghostRendererCache.computeIfAbsent(beType, type -> {
            BlockEntityRenderer<?> original = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(tempBe);
            if (!(original instanceof GeoBlockRenderer<?> gbr)) return null;
            return createGhostRenderer(gbr);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends BlockEntity & GeoAnimatable> GeoBlockRenderer<T> createGhostRenderer(GeoBlockRenderer<T> gbr) {
        GeoModel<T> origModel = gbr.getGeoModel();
        if (origModel instanceof MachineGeoModel machineModel) {
            MachineGeoModel<T> ghostModel = machineModel.withRenderType(
                    (animatable, texture) -> RenderType.entityCutout(texture));
            return new GeoBlockRenderer<>(ghostModel) {};
        }
        return null;
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
        if (!(heldItem instanceof BlueprintToolItem) && !(heldItem instanceof BlockItem)) {
            if (BlueprintPreviewState.isActive()) BlueprintPreviewState.exit();
            return;
        }

        if (heldItem instanceof BlueprintToolItem) {
            if (BlueprintPreviewState.isActive()) {
                String machineId = BlueprintPreviewState.getMachineId();
                if (machineId == null) return;
                Block block = BaseMachineBlock.getMachineBlock(machineId);
                if (!(block instanceof BaseMachineBlock machineBlock)) return;

                Direction facing = BlueprintPreviewState.getFacing();
                BlockPos targetPos = BlueprintPreviewState.getTargetPos();
                BlockState previewState = machineBlock.defaultBlockState().setValue(BaseMachineBlock.FACING, facing);
                renderMachinePreview(event, mc, machineBlock, previewState, targetPos);
                return;
            }

            if (player.isShiftKeyDown()) return;

            String machineId = BlueprintToolItem.getSelectedMachineId();
            if (machineId == null) return;
            Block block = BaseMachineBlock.getMachineBlock(machineId);
            if (!(block instanceof BaseMachineBlock mb)) return;

            HitResult hitResult = mc.hitResult;
            if (!(hitResult instanceof BlockHitResult blockHitResult)) return;
            BlockPos clickedPos = blockHitResult.getBlockPos();
            if (mc.level.isEmptyBlock(clickedPos)) return;
            BlockPos targetPos = clickedPos.relative(blockHitResult.getDirection());
            Direction facing = player.getDirection().getOpposite();

            BlockState previewState = mb.defaultBlockState().setValue(BaseMachineBlock.FACING, facing);
            renderMachinePreview(event, mc, mb, previewState, targetPos);
            return;
        }

        if (player.isShiftKeyDown()) return;

        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;
        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;
        BlockPos targetPos = clickedPos.relative(blockHitResult.getDirection());

        BlockItem blockItem = (BlockItem) heldItem;
        Block block = blockItem.getBlock();
        if (!(block instanceof BaseMachineBlock machineBlock)) return;

        Direction facing = blockHitResult.getDirection().getOpposite();
        if (facing.getAxis().isVertical()) {
            facing = player.getDirection().getOpposite();
        }

        BlockState previewState = machineBlock.defaultBlockState().setValue(BaseMachineBlock.FACING, facing);
        renderMachinePreview(event, mc, machineBlock, previewState, targetPos);
    }

    private static void renderMachinePreview(RenderLevelStageEvent event, Minecraft mc,
                                              BaseMachineBlock machineBlock, BlockState previewState, BlockPos targetPos) {
        BlockPos[] sidePositions = machineBlock.getSidePositions(previewState, targetPos);

        PoseStack poseStack = event.getPoseStack();
        renderGhostModel(poseStack, event.getCamera(), machineBlock, previewState, targetPos);
        // ★ 立即 flush 虚影模型（entityCutout 写深度、深度测试开启）：确保模型先画完，
        // 之后框渲染保持深度测试开启，与模型的遮挡关系严格按空间前后判定（符合透视）
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();

        long tick = mc.level.getGameTime();
        List<OutlineTarget> targets = new ArrayList<>();

        // 只在主位置被阻挡时渲染红色边框 + 半透明面
        if (isBlocked(mc, targetPos)) {
            targets.add(new OutlineTarget(targetPos, boxOf(targetPos), COLOR_INVALID, FACE_ALPHA));
        }

        // 只渲染被阻挡的侧面方块位置（棱 + 半透明面）
        for (BlockPos sidePos : sidePositions) {
            if (isBlocked(mc, sidePos)) {
                targets.add(new OutlineTarget(sidePos, boxOf(sidePos), COLOR_INVALID, FACE_ALPHA));
            }
        }

        // 整体边界黄框（无面）
        int boundsMinX = targetPos.getX(), boundsMinY = targetPos.getY(), boundsMinZ = targetPos.getZ();
        int boundsMaxX = targetPos.getX(), boundsMaxY = targetPos.getY(), boundsMaxZ = targetPos.getZ();
        for (BlockPos sidePos : sidePositions) {
            boundsMinX = Math.min(boundsMinX, sidePos.getX());
            boundsMinY = Math.min(boundsMinY, sidePos.getY());
            boundsMinZ = Math.min(boundsMinZ, sidePos.getZ());
            boundsMaxX = Math.max(boundsMaxX, sidePos.getX());
            boundsMaxY = Math.max(boundsMaxY, sidePos.getY());
            boundsMaxZ = Math.max(boundsMaxZ, sidePos.getZ());
        }
        targets.add(new OutlineTarget("bounds", new AABB(boundsMinX, boundsMinY, boundsMinZ,
                boundsMaxX + 1, boundsMaxY + 1, boundsMaxZ + 1), COLOR_BOUNDS, 0));

        // 只渲染被阻挡的填充区域方块（棱 + 半透明面）
        Vec3i[][] fillRegions = machineBlock.getFillRegions();
        if (fillRegions != null) {
            Direction facing = previewState.getValue(BaseMachineBlock.FACING);
            for (Vec3i[] region : fillRegions) {
                collectFillTargets(targets, mc, targetPos, facing, region[0], region[1]);
            }
        }

        renderOutlines(event, targets, tick);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void renderGhostModel(PoseStack poseStack, net.minecraft.client.Camera camera,
                                          BaseMachineBlock machineBlock, BlockState state, BlockPos pos) {
        BlockEntityType<?> beType = machineBlock.getMachineBlockEntityType();

        BlockEntity tempBe = tempBeCache.computeIfAbsent(beType, type -> {
            BlockEntity be = type.create(BlockPos.ZERO, machineBlock.defaultBlockState());
            if (be != null) be.setBlockState(machineBlock.defaultBlockState());
            return be;
        });
        if (tempBe == null) return;
        // 标记为蓝图预览虚影，跳过建造动画
        if (tempBe instanceof HorizontalSteamBoilerBlockEntity boiler) {
            boiler.isGhostPreview = true;
        }
        tempBe.setBlockState(state);

        try {
            GeoBlockRenderer ghostRenderer = getOrCreateGhostRenderer(beType, tempBe);
            if (ghostRenderer == null) return;

            MultiBufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

            poseStack.pushPose();
            poseStack.translate(
                    (double) pos.getX() - camera.getPosition().x(),
                    (double) pos.getY() - camera.getPosition().y(),
                    (double) pos.getZ() - camera.getPosition().z()
            );

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            // 虚影必须写深度：entityCutout 的 WriteMaskState 未显式设置，flush（endBatch）时沿用全局 depthMask。
            // 写深度后模型面间互相遮挡（外壳挡住内部，恢复幽灵预览效果），且框的深度测试可与模型正确穿插。
            RenderSystem.depthMask(true);

            ghostRenderer.render(tempBe, 0, poseStack, bufferSource, 0xF000F0, 0);

            poseStack.popPose();
        } catch (Exception ignored) {
        }
    }

    private static Vec3i rotateVec3i(Vec3i offset, Direction facing) {
        int x = offset.getX();
        int z = offset.getZ();
        return switch (facing) {
            case NORTH -> new Vec3i(x, offset.getY(), z);
            case SOUTH -> new Vec3i(-x, offset.getY(), -z);
            case EAST -> new Vec3i(-z, offset.getY(), x);
            case WEST -> new Vec3i(z, offset.getY(), -x);
            default -> offset;
        };
    }

    /** 方块整格包围盒（微膨胀防 Z-fighting） */
    private static AABB boxOf(BlockPos pos) {
        return new AABB(pos).inflate(0.002);
    }

    /** 位置是否被不可替换方块阻挡 */
    private static boolean isBlocked(Minecraft mc, BlockPos pos) {
        return mc.level != null && !mc.level.isEmptyBlock(pos) && !mc.level.getBlockState(pos).canBeReplaced();
    }

    /** 收集填充区域内被阻挡的方块位置（棱 + 半透明面） */
    private static void collectFillTargets(List<OutlineTarget> targets, Minecraft mc, BlockPos origin,
                                           Direction facing, Vec3i min, Vec3i max) {
        int minX = Math.min(min.getX(), max.getX());
        int minY = Math.min(min.getY(), max.getY());
        int minZ = Math.min(min.getZ(), max.getZ());
        int maxX = Math.max(min.getX(), max.getX());
        int maxY = Math.max(min.getY(), max.getY());
        int maxZ = Math.max(min.getZ(), max.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Vec3i rotated = rotateVec3i(new Vec3i(x, y, z), facing);
                    BlockPos pos = origin.offset(rotated);
                    if (isBlocked(mc, pos)) {
                        targets.add(new OutlineTarget(pos, boxOf(pos), COLOR_INVALID, FACE_ALPHA));
                    }
                }
            }
        }
    }

    /**
     * 渲染动画框集合：新建触发淡入，复用目标触发 chase 滑动，消失的移除。
     * 两轮提交：先半透明面、再亮边线，保证边线清晰可读。
     */
    private static void renderOutlines(RenderLevelStageEvent event, List<OutlineTarget> targets, long tick) {
        // 同步动画框集合：新建淡入，复用 chase，消失的移除
        outlines.keySet().removeIf(key -> targets.stream().noneMatch(t -> t.key().equals(key)));
        for (OutlineTarget t : targets) {
            AnimatedOutline o = outlines.get(t.key());
            if (o == null) {
                outlines.put(t.key(), new AnimatedOutline(t.box(), t.color(), LINE_WIDTH, t.faceAlpha(), tick));
            } else {
                o.chase(t.box(), t.color(), LINE_WIDTH, t.faceAlpha());
            }
        }

        PoseStack poseStack = event.getPoseStack();
        // 深度测试保持开启：虚影模型已先 flush 写深度，框在模型/方块前方的部分通过测试显示，
        // 后方的部分被深度遮挡——空间穿插关系完全符合透视
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
}
