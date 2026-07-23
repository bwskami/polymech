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
import com.mss.polymech.item.BlueprintToolItem;
import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class MachinePreviewRenderer {

    private static final int COLOR_VALID = 0xFF00FF00;
    private static final int COLOR_INVALID = 0xFFFF0000;
    private static final int COLOR_MAIN = 0xFF00FFFF;
    private static final int COLOR_FILL_VALID = 0x4000FF00;
    private static final int COLOR_FILL_INVALID = 0x40FF0000;
    private static final int COLOR_BOUNDS = 0xFFFFFF00;

    private static final float LINE_WIDTH = 0.06F;
    private static final float FACE_ALPHA = 0.25F;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;
        if (player.isShiftKeyDown()) return;

        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;

        BlockPos clickedPos = blockHitResult.getBlockPos();
        if (mc.level.isEmptyBlock(clickedPos)) return;

        BlockPos targetPos = clickedPos.relative(blockHitResult.getDirection());

        BaseMachineBlock machineBlock;
        Direction facing;
        BlockState previewState;

        Item heldItem = player.getMainHandItem().getItem();

        if (heldItem instanceof BlueprintToolItem) {
            String machineId = BlueprintToolItem.getSelectedMachineId();
            if (machineId == null) return;
            Block block = BaseMachineBlock.getMachineBlock(machineId);
            if (!(block instanceof BaseMachineBlock mb)) return;
            machineBlock = mb;
            facing = player.getDirection().getOpposite();
        } else if (heldItem instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            if (!(block instanceof BaseMachineBlock mb)) return;
            machineBlock = mb;
            facing = blockHitResult.getDirection().getOpposite();
            if (facing.getAxis().isVertical()) {
                facing = player.getDirection().getOpposite();
            }
        } else {
            return;
        }

        previewState = machineBlock.defaultBlockState().setValue(BaseMachineBlock.FACING, facing);
        renderMachinePreview(event, mc, machineBlock, previewState, targetPos);
    }

    private static void renderMachinePreview(RenderLevelStageEvent event, Minecraft mc,
                                              BaseMachineBlock machineBlock, BlockState previewState, BlockPos targetPos) {
        BlockPos[] sidePositions = machineBlock.getSidePositions(previewState, targetPos);

        boolean canPlace = canPlaceMachine(mc, targetPos, sidePositions);
        int mainColor = canPlace ? COLOR_MAIN : COLOR_INVALID;

        PoseStack poseStack = event.getPoseStack();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();

        renderBlockOutline(poseStack, event.getCamera(), targetPos, mainColor);

        for (BlockPos sidePos : sidePositions) {
            boolean sideValid = mc.level.isEmptyBlock(sidePos) || mc.level.getBlockState(sidePos).canBeReplaced();
            int sideColor = sideValid ? COLOR_VALID : COLOR_INVALID;
            renderBlockOutline(poseStack, event.getCamera(), sidePos, sideColor);
        }

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
        renderBoundsOutline(poseStack, event.getCamera(), boundsMinX, boundsMinY, boundsMinZ, boundsMaxX + 1, boundsMaxY + 1, boundsMaxZ + 1, COLOR_BOUNDS);

        Vec3i[][] fillRegions = machineBlock.getFillRegions();
        if (fillRegions != null) {
            for (Vec3i[] region : fillRegions) {
                Vec3i min = region[0];
                Vec3i max = region[1];
                renderFillRegion(poseStack, event.getCamera(), targetPos, previewState, min, max, canPlace);
            }
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    private static boolean canPlaceMachine(Minecraft mc, BlockPos mainPos, BlockPos[] sidePositions) {
        if (mc.level == null) return false;
        if (!mc.level.isEmptyBlock(mainPos) && !mc.level.getBlockState(mainPos).canBeReplaced()) {
            return false;
        }
        for (BlockPos sidePos : sidePositions) {
            if (!mc.level.isEmptyBlock(sidePos) && !mc.level.getBlockState(sidePos).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }

    private static void renderFillRegion(PoseStack poseStack, net.minecraft.client.Camera camera,
                                          BlockPos origin, BlockState state, Vec3i min, Vec3i max, boolean valid) {
        int minX = Math.min(min.getX(), max.getX());
        int minY = Math.min(min.getY(), max.getY());
        int minZ = Math.min(min.getZ(), max.getZ());
        int maxX = Math.max(min.getX(), max.getX());
        int maxY = Math.max(min.getY(), max.getY());
        int maxZ = Math.max(min.getZ(), max.getZ());

        Direction facing = state.getValue(BaseMachineBlock.FACING);

        int fillColor = valid ? COLOR_FILL_VALID : COLOR_FILL_INVALID;
        int outlineColor = valid ? COLOR_VALID : COLOR_INVALID;

        float a = (float) ((fillColor >> 24) & 0xFF) / 255.0F;
        float r = (float) ((fillColor >> 16) & 0xFF) / 255.0F;
        float g = (float) ((fillColor >> 8) & 0xFF) / 255.0F;
        float b = (float) (fillColor & 0xFF) / 255.0F;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Vec3i rotated = rotateVec3i(new Vec3i(x, y, z), facing);
                    BlockPos pos = origin.offset(rotated);

                    poseStack.pushPose();
                    poseStack.translate(
                            (double) pos.getX() - camera.getPosition().x(),
                            (double) pos.getY() - camera.getPosition().y(),
                            (double) pos.getZ() - camera.getPosition().z()
                    );

                    Matrix4f matrix = poseStack.last().pose();
                    renderCubeFace(matrix, 0, 0, 0, 1, 1, 1, r, g, b, a);
                    renderCubeWireframe(matrix, 0, 0, 0, 1, 1, 1, outlineColor);

                    poseStack.popPose();
                }
            }
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

    private static void renderBlockOutline(PoseStack poseStack, net.minecraft.client.Camera camera, BlockPos pos, int color) {
        poseStack.pushPose();
        poseStack.translate(
                (double) pos.getX() - camera.getPosition().x(),
                (double) pos.getY() - camera.getPosition().y(),
                (double) pos.getZ() - camera.getPosition().z()
        );

        Matrix4f matrix = poseStack.last().pose();
        renderCubeWireframe(matrix, 0, 0, 0, 1, 1, 1, color);

        poseStack.popPose();
    }

    private static void renderBoundsOutline(PoseStack poseStack, net.minecraft.client.Camera camera,
                                              float x1, float y1, float z1,
                                              float x2, float y2, float z2, int color) {
        poseStack.pushPose();
        poseStack.translate(
                -camera.getPosition().x(),
                -camera.getPosition().y(),
                -camera.getPosition().z()
        );

        Matrix4f matrix = poseStack.last().pose();
        renderCubeWireframe(matrix, x1, y1, z1, x2, y2, z2, color);

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

        addThickLine(buf, matrix, x1, y1, z1, x2, y1, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z1, x2, y1, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z2, x1, y1, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y1, z2, x1, y1, z1, hw, r, g, b, a);

        addThickLine(buf, matrix, x1, y2, z1, x2, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y2, z1, x2, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y2, z2, x1, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y2, z2, x1, y2, z1, hw, r, g, b, a);

        addThickLine(buf, matrix, x1, y1, z1, x1, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z1, x2, y2, z1, hw, r, g, b, a);
        addThickLine(buf, matrix, x2, y1, z2, x2, y2, z2, hw, r, g, b, a);
        addThickLine(buf, matrix, x1, y1, z2, x1, y2, z2, hw, r, g, b, a);

        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void renderCubeFace(Matrix4f matrix, float x1, float y1, float z1,
                                        float x2, float y2, float z2, float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);

        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);

        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);

        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);

        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);

        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);

        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void addThickLine(BufferBuilder buf, Matrix4f matrix,
                                      float x1, float y1, float z1,
                                      float x2, float y2, float z2,
                                      float hw, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;

        boolean xMajor = Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= Math.abs(dz);
        boolean yMajor = Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz);

        float p1x = 0, p1y = 0, p1z = 0;
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

        buf.addVertex(matrix, x1 - p1x, y1 - p1y, z1 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p1x, y1 + p1y, z1 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p1x, y2 + p1y, z2 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p1x, y2 - p1y, z2 - p1z).setColor(r, g, b, a);

        buf.addVertex(matrix, x1 + p1x, y1 + p1y, z1 + p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 - p1x, y1 - p1y, z1 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p1x, y2 - p1y, z2 - p1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p1x, y2 + p1y, z2 + p1z).setColor(r, g, b, a);

        buf.addVertex(matrix, x1 - p2x, y1 - p2y, z1 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + p2x, y1 + p2y, z1 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p2x, y2 + p2y, z2 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p2x, y2 - p2y, z2 - p2z).setColor(r, g, b, a);

        buf.addVertex(matrix, x1 + p2x, y1 + p2y, z1 + p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 - p2x, y1 - p2y, z1 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 - p2x, y2 - p2y, z2 - p2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + p2x, y2 + p2y, z2 + p2z).setColor(r, g, b, a);
    }
}
