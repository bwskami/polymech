package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.item.WrenchItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import org.joml.Matrix4f;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class WrenchOverlayRenderer {

    private static final float OFFSET = 0.01F;
    private static final float LINE_WIDTH = 0.02F;
    private static final int LINE_COLOR = 0xFFFFFFFF;

    @SubscribeEvent
    public static void onRenderHighlight(RenderHighlightEvent.Block event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        if (!player.getMainHandItem().is(ModItems.WRENCH.get())
                && !player.getOffhandItem().is(ModItems.WRENCH.get())) {
            return;
        }

        HitResult hitResult = mc.hitResult;
        if (!(hitResult instanceof BlockHitResult blockHitResult)) return;

        BlockPos pos = blockHitResult.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock)) return;

        Direction face = blockHitResult.getDirection();
        event.setCanceled(true);

        PoseStack poseStack = event.getPoseStack();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        poseStack.pushPose();
        poseStack.translate(
                (double) pos.getX() - event.getCamera().getPosition().x(),
                (double) pos.getY() - event.getCamera().getPosition().y(),
                (double) pos.getZ() - event.getCamera().getPosition().z()
        );

        Matrix4f matrix = poseStack.last().pose();

        Direction[] axes = WrenchItem.getFaceAxes(face, player.getDirection());
        renderGrid(matrix, face, axes);

        poseStack.popPose();
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static void renderGrid(Matrix4f matrix, Direction face, Direction[] axes) {
        float a = (float) ((LINE_COLOR >> 24) & 0xFF) / 255.0F;
        float r = (float) ((LINE_COLOR >> 16) & 0xFF) / 255.0F;
        float g = (float) ((LINE_COLOR >> 8)  & 0xFF) / 255.0F;
        float b = (float) (LINE_COLOR         & 0xFF) / 255.0F;

        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float hw = LINE_WIDTH / 2.0F;

        for (int i = 1; i <= 2; i++) {
            float t = i / 3.0F;
            addLine(buf, matrix, face, axes, t, 0, t, 1, hw, r, g, b, a);
            addLine(buf, matrix, face, axes, 0, t, 1, t, hw, r, g, b, a);
        }

        addLine(buf, matrix, face, axes, 0, 0, 1, 0, hw, r, g, b, a);
        addLine(buf, matrix, face, axes, 1, 0, 1, 1, hw, r, g, b, a);
        addLine(buf, matrix, face, axes, 1, 1, 0, 1, hw, r, g, b, a);
        addLine(buf, matrix, face, axes, 0, 1, 0, 0, hw, r, g, b, a);

        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void addLine(BufferBuilder buf, Matrix4f matrix, Direction face, Direction[] axes,
                                float u1, float v1, float u2, float v2,
                                float hw, float r, float g, float b, float a) {
        float[] p1 = facePos(face, axes, u1, v1, OFFSET);
        float[] p2 = facePos(face, axes, u2, v2, OFFSET);

        float dx = p2[0] - p1[0];
        float dy = p2[1] - p1[1];
        float dz = p2[2] - p1[2];

        var normal = face.getNormal();
        float crossX = dy * normal.getZ() - dz * normal.getY();
        float crossY = dz * normal.getX() - dx * normal.getZ();
        float crossZ = dx * normal.getY() - dy * normal.getX();
        float crossLen = (float) Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
        if (crossLen < 1e-6F) return;

        float px = crossX / crossLen * hw;
        float py = crossY / crossLen * hw;
        float pz = crossZ / crossLen * hw;

        buf.addVertex(matrix, p1[0] + px, p1[1] + py, p1[2] + pz).setColor(r, g, b, a);
        buf.addVertex(matrix, p2[0] + px, p2[1] + py, p2[2] + pz).setColor(r, g, b, a);
        buf.addVertex(matrix, p2[0] - px, p2[1] - py, p2[2] - pz).setColor(r, g, b, a);
        buf.addVertex(matrix, p1[0] - px, p1[1] - py, p1[2] - pz).setColor(r, g, b, a);
    }

    private static float[] facePos(Direction face, Direction[] axes, float u, float v, float offset) {
        Direction right = axes[0];
        Direction up = axes[1];
        var normal = face.getNormal();

        float cx = (normal.getX() != 0) ? (normal.getX() > 0 ? 1 : 0)
                : (right.getStepX() < 0 || up.getStepX() < 0 ? 1 : 0);
        float cy = (normal.getY() != 0) ? (normal.getY() > 0 ? 1 : 0)
                : (right.getStepY() < 0 || up.getStepY() < 0 ? 1 : 0);
        float cz = (normal.getZ() != 0) ? (normal.getZ() > 0 ? 1 : 0)
                : (right.getStepZ() < 0 || up.getStepZ() < 0 ? 1 : 0);

        float x = cx + normal.getX() * offset + u * right.getStepX() + v * up.getStepX();
        float y = cy + normal.getY() * offset + u * right.getStepY() + v * up.getStepY();
        float z = cz + normal.getZ() * offset + u * right.getStepZ() + v * up.getStepZ();

        return new float[]{x, y, z};
    }
}
