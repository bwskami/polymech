package com.mss.polymech.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

import java.util.List;

/**
 * 成分饼图tooltip组件（common侧数据载体）。
 * <p>
 * 按住Shift查看材料/化学物质成分时，由
 * {@link net.neoforged.neoforge.client.event.RenderTooltipEvent.GatherComponents}
 * 插入tooltip元素列表，客户端侧由对应的ClientTooltipComponent
 * 渲染为"左侧图例（百分比+彩色元素符号）+ 中间饼图"，
 * 若物质已登记分子结构式，结构式同屏绘制在饼图右侧。
 * </p>
 *
 * @param slices    饼图切片列表（按质量占比降序）
 * @param structure 可选的分子结构数据（已登记时为结构数据，否则null）
 */
public record CompositionPieTooltipComponent(List<Slice> slices, MoleculeStructure structure) implements TooltipComponent {

    /**
     * 单个饼图切片。
     *
     * @param symbol 元素符号
     * @param color  切片颜色（RGB，取自{@link ElementColors}）
     * @param pct    质量百分比（0~100）
     */
    public record Slice(String symbol, int color, double pct) {
    }
}
