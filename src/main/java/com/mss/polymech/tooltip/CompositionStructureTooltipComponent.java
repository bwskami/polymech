package com.mss.polymech.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * 分子结构式tooltip组件（common侧数据载体）。
 * <p>
 * 按住Shift查看已登记结构式的物质（见{@link MoleculeStructures}）时，由
 * {@link net.neoforged.neoforge.client.event.RenderTooltipEvent.GatherComponents}
 * 插入tooltip元素列表，客户端侧由对应的ClientTooltipComponent
 * 渲染为GT风格键线式结构图。
 * </p>
 *
 * @param structure 分子结构数据
 */
public record CompositionStructureTooltipComponent(MoleculeStructure structure) implements TooltipComponent {
}
