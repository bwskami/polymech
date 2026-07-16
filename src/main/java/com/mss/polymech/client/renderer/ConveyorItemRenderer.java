package com.mss.polymech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mss.polymech.entity.ConveyorItemEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * 传送带物品实体的渲染器。
 * <p>
 * 位置完全由 Entity 的位置插值系统驱动（xOld→x via partialTick），
 * 渲染器只负责物品模型的旋转和缩放。
 * </p>
 */
public class ConveyorItemRenderer extends EntityRenderer<ConveyorItemEntity> {

    private final ItemRenderer itemRenderer;

    public ConveyorItemRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = Minecraft.getInstance().getItemRenderer();
        this.shadowRadius = 0.0F;
        this.shadowStrength = 0.0F;
    }

    @Override
    public void render(ConveyorItemEntity entity, float entityYaw, float partialTick,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer,
                       int packedLight) {
        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) return;

        // 位置由 Entity 每 tick setPos 维护，渲染器自动 partialTick 插值
        // 此处只处理旋转和缩放

        float spinAngle = (entity.tickCount * 3.0F + partialTick * 3.0F) % 360.0F;

        poseStack.pushPose();
        BakedModel model = itemRenderer.getModel(stack, entity.level(), null, 0);
        poseStack.translate(0.0, 0.05, 0.0);
        poseStack.scale(1.5F, 1.5F, 1.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(spinAngle));

        boolean useBlockLight = !model.usesBlockLight();
        itemRenderer.render(stack, ItemDisplayContext.GROUND,
                false, poseStack, buffer, packedLight,
                OverlayTexture.NO_OVERLAY, model);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull ConveyorItemEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png");
    }
}