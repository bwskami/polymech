package com.mss.polymech.tooltip;

import com.mojang.datafixers.util.Either;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.fluid.ChemicalFluid;
import com.mss.polymech.fluid.ElementFluid;
import com.mss.polymech.fluid.FluidInfo;
import com.mss.polymech.fluid.ModChemicalFluids;
import com.mss.polymech.fluid.ModElementFluids;
import com.mss.polymech.fluid.ModElements;
import com.mss.polymech.item.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组tooltip管理中心（参考GregTech Modern的TooltipsHandler）。
 * <p>
 * 所有tooltip追加逻辑统一在此处维护，包括：
 * <ol>
 *   <li>装有化学流体/熔融金属/等离子体的容器（桶、流体单元、储罐等）：化学式、物态、温度、危险警示；</li>
 *   <li>模组材料物品（锭、粉、板等材料形态）与材料存储块：化学式；</li>
 *   <li>原版矿物相关物品（矿石方块、粗矿、锭/粒、金属存储块、煤/钻石/石英等）：化学式。</li>
 * </ol>
 * 机器、流体单元本体、管道等功能性物品不在化学式查找表中，不会显示。
 * 化学式数字统一使用Unicode下标（如H₂SO₄），元素符号按元素周期表配色染色
 * （见{@link ElementColors}）；按住Shift额外显示成分饼图（左侧图例+右侧饼图，
 * 见{@link CompositionPieTooltipComponent}）。
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

    /**
     * 化学式提示行：元素符号按元素周期表配色染色，数字转Unicode下标
     * （下标数字跟随其前方元素的颜色），括号与分隔符保持黄色，无"化学式"前缀（与GTM一致）。
     */
    public static Component formulaComponent(String formula) {
        MutableComponent result = Component.empty();
        int i = 0, n = formula.length();
        while (i < n) {
            char c = formula.charAt(i);
            if (Character.isUpperCase(c)) {
                int start = i++;
                if (i < n && Character.isLowerCase(formula.charAt(i))) i++; // 第二字母小写，如Fe
                String symbol = formula.substring(start, i);
                StringBuilder digits = new StringBuilder();
                while (i < n && Character.isDigit(formula.charAt(i))) digits.append(formula.charAt(i++));
                int color = ElementColors.getColor(symbol);
                result.append(Component.literal(symbol).withStyle(style -> style.withColor(color)));
                if (digits.length() > 0) {
                    // 下标数字跟随元素颜色，如Fe₃中的₃与Fe同色
                    result.append(Component.literal(toSubscript(digits.toString()))
                            .withStyle(style -> style.withColor(color)));
                }
            } else {
                result.append(Component.literal(String.valueOf(c)).withStyle(ChatFormatting.YELLOW));
                i++;
            }
        }
        return result;
    }

    // ========== 化学式解析与成分百分比 ==========

    /**
     * 解析化学式为元素符号→原子数映射（保持出现顺序）。
     * 支持括号分组乘数（如Ca3(PO4)2）与'.'/'·'/'-'等分隔符（如NH3.H2O）。
     */
    public static Map<String, Integer> parseFormula(String formula) {
        Deque<Map<String, Integer>> stack = new ArrayDeque<>();
        Map<String, Integer> root = new LinkedHashMap<>();
        stack.push(root);
        int i = 0, n = formula.length();
        while (i < n) {
            char c = formula.charAt(i);
            if (c == '(' || c == '[') {
                stack.push(new LinkedHashMap<>());
                i++;
            } else if (c == ')' || c == ']') {
                i++;
                StringBuilder digits = new StringBuilder();
                while (i < n && Character.isDigit(formula.charAt(i))) digits.append(formula.charAt(i++));
                int mult = digits.length() > 0 ? Integer.parseInt(digits.toString()) : 1;
                Map<String, Integer> group = stack.size() > 1 ? stack.pop() : stack.peek();
                Map<String, Integer> target = stack.peek();
                group.forEach((sym, cnt) -> target.merge(sym, cnt * mult, Integer::sum));
            } else if (Character.isUpperCase(c)) {
                int start = i++;
                if (i < n && Character.isLowerCase(formula.charAt(i))) i++;
                String symbol = formula.substring(start, i);
                StringBuilder digits = new StringBuilder();
                while (i < n && Character.isDigit(formula.charAt(i))) digits.append(formula.charAt(i++));
                int count = digits.length() > 0 ? Integer.parseInt(digits.toString()) : 1;
                stack.peek().merge(symbol, count, Integer::sum);
            } else {
                i++; // 跳过'.'、'·'、'-'等分隔符与其它未知字符
            }
        }
        // 防御：未闭合的括号内容也并入结果
        while (stack.size() > 1) {
            Map<String, Integer> group = stack.pop();
            Map<String, Integer> target = stack.peek();
            group.forEach((sym, cnt) -> target.merge(sym, cnt, Integer::sum));
        }
        return root;
    }

    /** 客户端是否按住Shift（仅客户端有效） */
    private static boolean isShiftDown() {
        return FMLEnvironment.dist == Dist.CLIENT && Screen.hasShiftDown();
    }

    // ========== 成分饼图缓存（ItemTooltipEvent与RenderTooltipEvent.GatherComponents同帧先后触发） ==========

    /** 最近一次计算饼图数据的物品栈 */
    private static ItemStack lastPieStack = ItemStack.EMPTY;
    /** 最近一次计算的饼图切片（按质量占比降序） */
    private static List<CompositionPieTooltipComponent.Slice> lastPieSlices = List.of();

    /**
     * 供RenderTooltipEvent.GatherComponents调用：悬停物品有成分饼图数据（按住Shift）时
     * 返回饼图组件（可选携带分子结构式，由客户端绘制在饼图右侧），
     * 由调用方插入tooltip元素列表；否则返回null。
     */
    public static CompositionPieTooltipComponent getCompositionPie(ItemStack stack, MoleculeStructure structure) {
        if (!isShiftDown() || stack.isEmpty() || lastPieSlices.isEmpty()) return null;
        if (!ItemStack.matches(stack, lastPieStack)) return null;
        return new CompositionPieTooltipComponent(lastPieSlices, structure);
    }

    // ========== 分子结构式缓存（与饼图同机制：ItemTooltipEvent缓存，GatherComponents同帧消费） ==========

    /** 最近一次命中结构式注册表的物品栈 */
    private static ItemStack lastStructureStack = ItemStack.EMPTY;
    /** 最近一次命中的分子结构 */
    private static MoleculeStructure lastStructure;

    /** 化学式已登记结构式且按住Shift时缓存，供同帧GatherComponents插入 */
    private static void cacheStructure(String formula, ItemStack stack) {
        MoleculeStructure structure = MoleculeStructures.get(formula);
        if (structure != null && isShiftDown()) {
            lastStructureStack = stack;
            lastStructure = structure;
        }
    }

    /**
     * 供RenderTooltipEvent.GatherComponents调用：悬停物品已登记分子结构式（按住Shift）时
     * 返回结构数据，由调用方插入tooltip元素列表；否则返回null。
     */
    public static MoleculeStructure getMoleculeStructure(ItemStack stack) {
        if (!isShiftDown() || stack.isEmpty() || lastStructure == null) return null;
        if (!ItemStack.matches(stack, lastStructureStack)) return null;
        return lastStructure;
    }

    /**
     * RenderTooltipEvent.GatherComponents监听器（游戏总线事件，由PolymechClient在客户端
     * 手动注册到NeoForge.EVENT_BUS）：按住Shift悬停时插入成分饼图组件（参考GregTech样式），
     * 已登记结构式的物质其结构式随饼图组件一并携带，客户端绘制在饼图右侧；
     * 无饼图数据但有结构式时退回单独的结构式组件。
     */
    public static void onGatherTooltipComponents(RenderTooltipEvent.GatherComponents event) {
        MoleculeStructure structure = getMoleculeStructure(event.getItemStack());
        CompositionPieTooltipComponent pie = getCompositionPie(event.getItemStack(), structure);
        if (pie != null) {
            event.getTooltipElements().add(Either.right(pie));
        } else if (structure != null) {
            event.getTooltipElements().add(Either.right(new CompositionStructureTooltipComponent(structure)));
        }
    }

    /**
     * 在化学式行之后追加成分信息：
     * 未按住Shift时显示灰色提示；按住Shift时缓存各元素质量占比切片，
     * 由RenderTooltipEvent.GatherComponents插入饼图组件渲染
     * （左侧图例+右侧饼图，参考GregTech样式）。单元素化学式不显示。
     */
    private static void appendComposition(List<Component> tooltip, int index, String formula, ItemStack stack) {
        Map<String, Integer> counts = parseFormula(formula);
        record Entry(String symbol, double mass) {}
        List<Entry> entries = new ArrayList<>();
        double totalMass = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            ModElements element = ElementColors.bySymbol(e.getKey());
            if (element == null) continue;
            double mass = element.getAtomicMass() * e.getValue();
            entries.add(new Entry(e.getKey(), mass));
            totalMass += mass;
        }
        if (entries.size() <= 1 || totalMass <= 0) return;
        if (isShiftDown()) {
            entries.sort(Comparator.comparingDouble(Entry::mass).reversed());
            List<CompositionPieTooltipComponent.Slice> slices = new ArrayList<>();
            for (Entry entry : entries) {
                double pct = entry.mass() / totalMass * 100.0;
                slices.add(new CompositionPieTooltipComponent.Slice(
                        entry.symbol(), ElementColors.getColor(entry.symbol()), pct));
            }
            lastPieStack = stack;
            lastPieSlices = slices;
            // 饼图组件在RenderTooltipEvent.GatherComponents中插入，此处不再追加文本行
        } else {
            tooltip.add(index, Component.translatable("tooltip.poly_mech.formula.shift_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
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
        // 有机物：糖（蔗糖）
        registerFormula(Items.SUGAR, "C12H22O11");
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
            appendFluidInfo(tooltip, fluidInfo, stack);
            return;
        }

        // 2. 模组材料物品（锭/粉/板/存储块等）或已登记化学式的物品（原版矿物等）
        String formula = lookupFormula(stack.getItem());
        if (formula != null && !formula.isEmpty()) {
            tooltip.add(1, formulaComponent(formula));
            appendComposition(tooltip, 2, formula, stack);
            cacheStructure(formula, stack);
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

    /** 流体完整信息：化学式（含成分饼图）+ 物态 + 温度 + 危险警示 */
    private static void appendFluidInfo(List<Component> tooltip, FluidInfo info, ItemStack stack) {
        // 化学式（元素染色、下标数字，不带前缀）；混合物等无化学式物质跳过此行
        String formula = info.getFormula();
        int insertIndex = 1;
        if (!formula.isEmpty()) {
            tooltip.add(insertIndex, formulaComponent(formula));
            insertIndex++;
            appendComposition(tooltip, insertIndex, formula, stack);
            insertIndex++;
            cacheStructure(formula, stack);
        }
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
