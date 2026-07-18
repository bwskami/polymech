package com.mss.polymech.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mss.polymech.Polymech;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class MultiblockSelectionScreen extends Screen {
    private static final ResourceLocation BACKGROUND_TEXTURE = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/multiblock_selection.png");

    private static final int GUI_WIDTH = 256;
    private static final int GUI_HEIGHT = 200;

    public MultiblockSelectionScreen() {
        super(Component.translatable("gui.poly_mech.multiblock_selection.title"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // 添加一些示例按钮，代表不同的多方块机器
        int buttonWidth = 200;
        int buttonHeight = 20;
        int spacing = 25;

        // 添加一个示例多方块机器选择按钮
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.poly_mech.multiblock_selection.large_chemical_reactor"),
                button -> selectMultiblockMachine("large_chemical_reactor"))
                .pos(centerX - buttonWidth / 2, centerY - 50)
                .size(buttonWidth, buttonHeight)
                .build());

        // 添加另一个示例按钮
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.poly_mech.multiblock_selection.implosion_compressor"),
                button -> selectMultiblockMachine("implosion_compressor"))
                .pos(centerX - buttonWidth / 2, centerY - 50 + spacing)
                .size(buttonWidth, buttonHeight)
                .build());

        // 添加第三个示例按钮
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.poly_mech.multiblock_selection.pyrolyze_oven"),
                button -> selectMultiblockMachine("pyrolyze_oven"))
                .pos(centerX - buttonWidth / 2, centerY - 50 + spacing * 2)
                .size(buttonWidth, buttonHeight)
                .build());

        // 添加关闭按钮
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.poly_mech.multiblock_selection.close"),
                button -> onClose())
                .pos(centerX - buttonWidth / 2, centerY - 50 + spacing * 4)
                .size(buttonWidth, buttonHeight)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTicks);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        int centerX = this.width / 2;
        int titleY = this.height / 2 - 80;

        // 渲染标题
        guiGraphics.drawCenteredString(
                font,
                Component.translatable("gui.poly_mech.multiblock_selection.title"),
                centerX,
                titleY,
                0xFFFFFF
        );

        // 绘制背景边框
        int guiX = centerX - GUI_WIDTH / 2;
        int guiY = this.height / 2 - GUI_HEIGHT / 2;
        renderGuiBackground(guiGraphics, guiX, guiY);
    }

    private void renderGuiBackground(GuiGraphics guiGraphics, int x, int y) {
        RenderSystem.enableBlend();
        // 绘制半透明背景
        guiGraphics.fillGradient(x, y, x + GUI_WIDTH, y + GUI_HEIGHT, 0xC0101010, 0xD0101010);
        RenderSystem.disableBlend();
    }

    private void selectMultiblockMachine(String machineType) {
        // 这里将会发送消息到服务器，告诉它玩家选择了哪个多方块机器
        Polymech.LOGGER.info("Selected multiblock machine: {}", machineType);

        // 关闭GUI
        onClose();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }
}
