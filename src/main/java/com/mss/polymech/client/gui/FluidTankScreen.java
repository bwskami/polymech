package com.mss.polymech.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.menu.FluidTankMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.joml.Matrix4f;

public class FluidTankScreen extends AbstractContainerScreen<FluidTankMenu> {
    private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/fluid_tank.png");

    private static final int TANK_X = 20;
    private static final int TANK_Y = 18;
    private static final int TANK_WIDTH = 36;
    private static final int TANK_HEIGHT = 54;

    public FluidTankScreen(FluidTankMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 82;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);

        FluidStack fluidStack = menu.getFluidStack();
        int syncAmount = menu.getFluidAmount();
        if (!fluidStack.isEmpty() && syncAmount > 0) {
            int fluidLevel = getFluidLevel(syncAmount);
            IClientFluidTypeExtensions fluidExt = IClientFluidTypeExtensions.of(fluidStack.getFluid());
            ResourceLocation stillLoc = fluidExt.getStillTexture();

            TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
            TextureAtlasSprite sprite = atlas.getSprite(stillLoc);
            float u0 = sprite.getU0();
            float v0 = sprite.getV0();
            float u1 = sprite.getU1();
            float v1 = sprite.getV1();

            int renderY = TANK_Y + TANK_HEIGHT - fluidLevel;

            graphics.enableScissor(
                    this.leftPos + TANK_X, this.topPos + renderY,
                    this.leftPos + TANK_X + TANK_WIDTH, this.topPos + renderY + fluidLevel);

            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);

            int tintColor = fluidExt.getTintColor();
            float r = (tintColor >> 16 & 0xFF) / 255.0F;
            float g = (tintColor >> 8 & 0xFF) / 255.0F;
            float b = (tintColor & 0xFF) / 255.0F;
            RenderSystem.setShaderColor(r, g, b, 1.0F);

            Matrix4f matrix = graphics.pose().last().pose();

            for (int tx = 0; tx < TANK_WIDTH; tx += 16) {
                for (int ty = 0; ty < fluidLevel; ty += 16) {
                    float x0 = this.leftPos + TANK_X + tx;
                    float y0 = this.topPos + renderY + ty;
                    float x1 = x0 + 16;
                    float y1 = y0 + 16;

                    BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                    buf.addVertex(matrix, x0, y1, 0).setUv(u0, v1);
                    buf.addVertex(matrix, x1, y1, 0).setUv(u1, v1);
                    buf.addVertex(matrix, x1, y0, 0).setUv(u1, v0);
                    buf.addVertex(matrix, x0, y0, 0).setUv(u0, v0);
                    BufferUploader.drawWithShader(buf.buildOrThrow());
                }
            }

            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.disableScissor();
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, 0x404040, false);

        int textStartX = TANK_X + TANK_WIDTH + 4;
        int syncAmount = menu.getFluidAmount();
        FluidStack fluidStack = menu.getFluidStack();

        String fluidName;
        String registryName;
        String amountText;
        if (syncAmount > 0 && !fluidStack.isEmpty()) {
            fluidName = fluidStack.getHoverName().getString();
            registryName = BuiltInRegistries.FLUID.getKey(fluidStack.getFluid()).toString();
            int percent = (int) ((float) syncAmount / menu.getCapacity() * 100);
            if (syncAmount >= 10000) {
                amountText = percent + "% (" + (syncAmount / 1000) + "B)";
            } else {
                amountText = percent + "% (" + syncAmount + "mB)";
            }
        } else {
            fluidName = "空";
            registryName = "";
            amountText = "0% (0mB)";
        }

        graphics.drawString(this.font, fluidName, textStartX, TANK_Y + 6, 0x404040, false);
        if (!registryName.isEmpty()) {
            graphics.drawString(this.font, registryName, textStartX, TANK_Y + 18, 0x808080, false);
        }
        graphics.drawString(this.font, amountText, textStartX, TANK_Y + 30, 0x808080, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (isHovering(TANK_X, TANK_Y, TANK_WIDTH, TANK_HEIGHT, mouseX, mouseY)) {
            int syncAmount = menu.getFluidAmount();
            if (syncAmount > 0) {
                FluidStack fluidStack = menu.getFluidStack();
                graphics.renderComponentTooltip(this.font,
                        java.util.List.of(
                                fluidStack.getHoverName().copy()
                                        .append(Component.literal(" (" + syncAmount + " mB)"))
                        ), mouseX, mouseY);
            }
        }

        renderTooltip(graphics, mouseX, mouseY);
    }

    private int getFluidLevel(int amount) {
        int capacity = menu.getCapacity();
        if (capacity <= 0 || amount <= 0) return 0;
        return Math.max(1, (int) ((float) amount / capacity * TANK_HEIGHT));
    }
}
