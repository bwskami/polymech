package com.mss.polymech.tooltip;

import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.fluid.ChemicalFluid;
import com.mss.polymech.fluid.ElementFluid;
import com.mss.polymech.fluid.FluidInfo;
import com.mss.polymech.fluid.ModChemicalFluids;
import com.mss.polymech.fluid.ModElementFluids;
import com.mss.polymech.item.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组tooltip管理中心（参考GregTech Modern的TooltipsHandler）。
 * <p>
 * 所有tooltip追加逻辑统一在此处维护，包括：
 * <ol>
 *   <li>装有化学流体/熔融金属/等离子体的容器（桶、流体单元、储罐等）：化学式（黄色下标）、物态、温度、危险警示；</li>
 *   <li>模组材料物品（锭、粉、板等材料形态）与材料存储块：黄色下标化学式；</li>
 *   <li>原版矿物相关物品（矿石方块、粗矿、锭/粒、金属存储块、煤/钻石/石英等）：黄色下标化学式。</li>
 * </ol>
 * 机器、流体单元本体、管道等功能性物品不在化学式查找表中，不会显示。
 * 化学式数字统一使用Unicode下标（如H₂SO₄），与GTM及化学惯例一致。
 * </p>
 */
public class ModTooltipCenter {

    /** 注册到游戏事件总线（在模组构造阶段调用） */
    public static void register() {
        NeoForge.EVENT_BUS.register(ModTooltipCenter.class);
    }

    // ========== 化学式数字下标格式化（参考GTM FormattingUtil.toSmallDownNumbers） ==========

    private static final int SUBSCRIPT_BASE = '\u2080'; // ₀
    private static final char DIGIT_BASE = '0';

    /** 将字符串中的数字转换为Unicode下标数字，如 "H2SO4" -> "H₂SO₄" */
    public static String toSubscript(String string) {
        char[] chars = string.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int relative = chars[i] - DIGIT_BASE;
            if (relative >= 0 && relative <= 9) {
                chars[i] = (char) (SUBSCRIPT_BASE + relative);
            }
        }
        return new String(chars);
    }

    /** 化学式提示行：黄色、下标数字、无"化学式"前缀（与GTM一致） */
    public static Component formulaComponent(String formula) {
        return Component.literal(toSubscript(formula)).withStyle(ChatFormatting.YELLOW);
    }

    // ========== 通用化学式注册表（物品 → 化学式） ==========

    /** 额外化学式注册表：任何物品/方块物品都可以在此登记化学式 */
    private static final Map<Item, String> EXTRA_FORMULAS = new IdentityHashMap<>();

    /**
     * 为指定物品登记化学式（如材料存储方块、其它模组的矿石等）。
     * 重复登记时后者覆盖前者。
     */
    public static void registerFormula(Item item, String formula) {
        EXTRA_FORMULAS.put(item, formula);
    }

    /** 原版矿物/单质相关物品的化学式（静态登记） */
    static {
        // 粗矿
        registerFormula(Items.RAW_IRON, "Fe");
        registerFormula(Items.RAW_COPPER, "Cu");
        registerFormula(Items.RAW_GOLD, "Au");
        // 锭
        registerFormula(Items.IRON_INGOT, "Fe");
        registerFormula(Items.COPPER_INGOT, "Cu");
        registerFormula(Items.GOLD_INGOT, "Au");
        // 粒
        registerFormula(Items.IRON_NUGGET, "Fe");
        registerFormula(Items.GOLD_NUGGET, "Au");
        // 矿石方块（含深板岩变体）
        registerFormula(Items.IRON_ORE, "Fe");
        registerFormula(Items.DEEPSLATE_IRON_ORE, "Fe");
        registerFormula(Items.COPPER_ORE, "Cu");
        registerFormula(Items.DEEPSLATE_COPPER_ORE, "Cu");
        registerFormula(Items.GOLD_ORE, "Au");
        registerFormula(Items.DEEPSLATE_GOLD_ORE, "Au");
        registerFormula(Items.NETHER_GOLD_ORE, "Au");
        // 金属存储块
        registerFormula(Items.IRON_BLOCK, "Fe");
        registerFormula(Items.COPPER_BLOCK, "Cu");
        registerFormula(Items.GOLD_BLOCK, "Au");
        // 其它单质
        registerFormula(Items.COAL, "C");
        registerFormula(Items.COAL_BLOCK, "C");
        registerFormula(Items.DIAMOND, "C");
        registerFormula(Items.DIAMOND_BLOCK, "C");
        registerFormula(Items.QUARTZ, "SiO2");
        registerFormula(Items.QUARTZ_BLOCK, "SiO2");
    }

    // ========== tooltip事件入口 ==========

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) return;
        List<Component> tooltip = event.getToolTip();

        // 1. 容器物品携带化学物质/熔融金属/等离子体（桶、流体单元、储罐、其它模组容器等）
        FluidInfo fluidInfo = findFluidInfo(stack);
        if (fluidInfo != null) {
            appendFluidInfo(tooltip, fluidInfo);
            return;
        }

        // 2. 模组材料物品（锭/粉/板/存储块等）或已登记化学式的物品（原版矿物等）
        String formula = lookupFormula(stack.getItem());
        if (formula != null && !formula.isEmpty()) {
            tooltip.add(1, formulaComponent(formula));
        }
    }

    /** 查找容器物品内的化学物质或元素流体（熔融金属/等离子体）；无则返回null */
    private static FluidInfo findFluidInfo(ItemStack stack) {
        FluidStack contained = FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY);
        if (contained.isEmpty()) return null;
        ChemicalFluid chem = ModChemicalFluids.byFluid(contained.getFluid());
        if (chem != null) return chem;
        ElementFluid elementFluid = ModElementFluids.byFluid(contained.getFluid());
        return elementFluid;
    }

    /** 物品化学式查找：先查通用注册表，再查模组材料反查表，最后查材料存储块 */
    private static String lookupFormula(Item item) {
        String extra = EXTRA_FORMULAS.get(item);
        if (extra != null) return extra;
        String materialName = ModItems.getMaterialOf(item);
        if (materialName == null && item instanceof BlockItem blockItem) {
            // 材料存储块（如 steel_block）：经方块反查材料名
            materialName = ModBlocks.getMaterialOfBlock(blockItem.getBlock());
        }
        if (materialName != null) {
            return MaterialRegistry.getFormula(materialName);
        }
        return null;
    }

    /** 流体完整信息：化学式 + 物态 + 温度 + 危险警示 */
    private static void appendFluidInfo(List<Component> tooltip, FluidInfo info) {
        // 化学式（黄色下标，不带前缀）
        tooltip.add(1, formulaComponent(info.getFormula()));
        // 物态：液体 / 气体 / 等离子体
        String stateKey = switch (info.getState()) {
            case LIQUID -> "tooltip.poly_mech.fluid.state_liquid";
            case GAS -> "tooltip.poly_mech.fluid.state_gas";
            case PLASMA -> "tooltip.poly_mech.fluid.state_plasma";
        };
        tooltip.add(Component.translatable(stateKey).withStyle(ChatFormatting.GRAY));
        // 温度（开尔文）
        tooltip.add(Component.translatable("tooltip.poly_mech.fluid.temperature", info.getTemperature())
                .withStyle(ChatFormatting.GRAY));
        // 危险物质警示
        if (info.isHazardous()) {
            tooltip.add(Component.translatable("tooltip.poly_mech.hazardous").withStyle(ChatFormatting.RED));
        }
    }
}
