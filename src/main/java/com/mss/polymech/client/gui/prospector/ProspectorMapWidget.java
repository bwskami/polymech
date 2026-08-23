package com.mss.polymech.client.gui.prospector;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IBindable;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.mss.polymech.prospecting.ProspectorScan;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/*
 * 探矿地图widget：绘制岩石类型底图 + 矿物矿石叠加点的彩色网格。
 * <p>
 * 实现{@link IBindable}接收服务端扫描结果（stringS2C同步），
 * 解码后交给内部的{@link ProspectorMapTexture}绘制。
 * 每个单元格对应一个方块列，与格雷探矿仪同精度。
 * </p>
 */
public class ProspectorMapWidget extends UIElement implements IBindable<String> {

    /** 岩石显示色（顺序与ModRocks.ROCK_TYPES一致，取群峦贴图近似主色） */
    private static final int[] ROCK_COLORS = {
            0xFFC8C4B0, // limestone
            0xFF5A5A5E, // shale
            0xFFE8E4D8, // chalk
            0xFF6A6258, // chert
            0xFF8A6A5A, // claystone
            0xFF9A8A78, // conglomerate
            0xFFC0B8A0, // dolomite
            0xFF7A7A76, // tuff
            0xFFC89888, // granite
            0xFF48484A, // basalt
            0xFFB89080, // rhyolite
            0xFFA09088, // dacite
            0xFF9A9A98, // diorite
            0xFF5A5A56, // gabbro
            0xFF7E7E7C, // andesite
            0xFFF0F0F0, // marble
            0xFF8A8A84, // gneiss
            0xFF7A8078, // schist
            0xFF5E6A7A, // slate
            0xFF6E7E6E, // phyllite
            0xFFE8E0D0  // quartzite
    };

    /** 矿物显示色（顺序与ModMinerals.DEFINITIONS一致，取配色辅色以在岩石底图上可见） */
    private static final int[] MINERAL_COLORS = {
            0xFF8A6A4A, // cassiterite
            0xFF9A7040, // sphalerite
            0xFFC07848, // bauxite
            0xFF8A8A9A, // galena
            0xFFE0E0F0, // native_silver
            0xFF8ABA72, // garnierite
            0xFFA090A8, // cobaltite
            0xFF808088  // wolframite
    };

    private final ProspectorMapTexture texture = new ProspectorMapTexture();

    public ProspectorMapWidget() {
        style(s -> s.backgroundTexture(texture));
    }

    @Override
    public String getValue() {
        return "";
    }

    @Override
    public ProspectorMapWidget setValue(String value) {
        texture.setResult(ProspectorScan.Result.decode(value));
        return this;
    }

    /** 内部地图纹理：按(rockTypes, oreMinerals)逐列填色，叠加区块网格线与中心高亮 */
    private static class ProspectorMapTexture implements IGuiTexture {
        private ProspectorScan.Result result;

        void setResult(ProspectorScan.Result result) {
            this.result = result;
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public void draw(GuiGraphics graphics, float mouseX, float mouseY,
                         float x, float y, float width, float height, float partialTicks) {
            if (result == null || width <= 0 || height <= 0) return;

            int gridSize = result.gridSize;
            float cell = height / gridSize;

            for (int i = 0; i < result.rockTypes.length; i++) {
                int cx = i % gridSize;
                int cz = i / gridSize;
                int px = (int) (x + cx * cell);
                int py = (int) (y + cz * cell);
                int cs = (int) Math.ceil(cell) + 1; // +1避免列间缝隙

                int rock = result.rockTypes[i];
                int rockColor = (rock >= 0 && rock < ROCK_COLORS.length) ? ROCK_COLORS[rock] : 0xFF333333;
                graphics.fill(px, py, px + cs, py + cs, rockColor);

                int ore = result.oreMinerals[i];
                if (ore >= 0 && ore < MINERAL_COLORS.length) {
                    graphics.fill(px, py, px + cs, py + cs, MINERAL_COLORS[ore]);
                }
            }

            // 区块边界线（每16列一条）
            for (int line = 0; line <= gridSize; line += 16) {
                int lx = (int) (x + line * cell);
                int ly = (int) (y + line * cell);
                graphics.fill(lx, (int) y, lx + 1, (int) (y + height), 0xFF000000);
                graphics.fill((int) x, ly, (int) (x + width), ly + 1, 0xFF000000);
            }

            // 中心区块高亮（红色边框）
            int center = gridSize / 2 - 8; // 中心16列的起始格
            int bx = (int) (x + center * cell);
            int by = (int) (y + center * cell);
            int bs = (int) (16 * cell);
            graphics.fill(bx, by, bx + bs, by + 1, 0xFFFF0000);
            graphics.fill(bx, by + bs, bx + bs, by + bs + 1, 0xFFFF0000);
            graphics.fill(bx, by, bx + 1, by + bs, 0xFFFF0000);
            graphics.fill(bx + bs, by, bx + bs + 1, by + bs, 0xFFFF0000);
        }
    }
}
