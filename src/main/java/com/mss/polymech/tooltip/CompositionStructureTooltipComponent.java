package com.mss.polymech.tooltip;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

import java.util.List;

/**
 * 分子/离子结构式tooltip组件（common侧数据载体）。
 * <p>
 * 按住Shift查看已登记结构式的物质（见{@link MoleculeStructures}）时，由
 * {@link net.neoforged.neoforge.client.event.RenderTooltipEvent.GatherComponents}
 * 插入tooltip元素列表，客户端侧由对应的ClientTooltipComponent
 * 渲染为GT风格键线式结构图。离子化合物（见{@link IonFormulas}）会携带多个
 * 离子的结构，客户端水平并排渲染，且每个离子结构外围绘制黄色离子括号
 * "[ ]"并在右上角标注电荷上标（如[H₂F]⁺、[SbF₆]⁻）。
 * </p>
 *
 * @param structures 结构式数据列表（主化学式结构在前，各离子结构在后；非空）
 */
public record CompositionStructureTooltipComponent(List<StructureEntry> structures) implements TooltipComponent {

    /**
     * 单个结构式条目。
     *
     * @param structure 结构式数据
     * @param charge    离子电荷（如+1、-2；0=中性分子，不画离子括号）
     * @param polymer   是否为聚合物重复单元（true时画通高"[ ]"大括号+右下角"n"，
     *                  并按structure中的锚点画穿出括号的链延续键，见{@link PolymerFormulas}）
     */
    public record StructureEntry(MoleculeStructure structure, int charge, boolean polymer) {
    }
}
