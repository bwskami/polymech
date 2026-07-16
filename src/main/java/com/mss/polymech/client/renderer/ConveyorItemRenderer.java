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
 * 将物品显示在传送带表面，类似掉落物的渲染方式，
 * 但不继承掉落物的物理旋转逻辑。
 * 物品会做缓慢的自旋动画，并微幅上下浮动。
 * </p>
 */
public class ConveyorItemRenderer extends EntityRenderer<ConveyorItemEntity> {

    private final ItemRenderer itemRenderer;

    public ConveyorItemRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemRenderer = Minecraft.getInstance().getItemRenderer();
        this.shadowRadius = 0.0F; // 无阴影
        this.shadowStrength = 0.0F;
    }

    @Override
    public void render(ConveyorItemEntity entity, float entityYaw, float partialTick,
                       @NotNull PoseStack poseStack, @NotNull MultiBufferSource buffer,
                       int packedLight) {
        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) return;

        // 使用实体自身的 tickCount 计算旋转角度，每个实体独立旋转
        // 3度/ tick = 60度/秒，旋转一周约6秒
        float spinAngle = (entity.tickCount * 3.0F + partialTick * 3.0F) % 360.0F;

        poseStack.pushPose();

        // 物品渲染在实体位置中心
        BakedModel model = itemRenderer.getModel(stack, entity.level(), null, 0);

        // 让物品平躺（旋转使其平面朝上）
        poseStack.translate(0.0, 0.05, 0.0);
        poseStack.scale(1.5F, 1.5F, 1.5F);

        // 围绕Y轴缓慢自旋，每个实体独立角度
        poseStack.mulPose(Axis.YP.rotationDegrees(spinAngle));

        // 使用掉落物渲染方式进行渲染
        boolean useBlockLight = !model.usesBlockLight();
        itemRenderer.render(stack, ItemDisplayContext.GROUND,
                false, poseStack, buffer, packedLight,
                OverlayTexture.NO_OVERLAY, model);

        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public @NotNull ResourceLocation getTextureLocation(@NotNull ConveyorItemEntity entity) {
        // 不使用实体纹理，直接渲染 ItemStack 本身
        return ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png");
    }
}