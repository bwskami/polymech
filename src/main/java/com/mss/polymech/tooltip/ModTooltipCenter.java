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
import com.mss.polymech.worldgen.ModMinerals;
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
import java.util.Set;

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

    /** 将字符串中的数字转换为Unicode下标数字，如 "H2SO4" -> "H₂SO₄"（委托给无游戏依赖的{@link Subscript}） */
    public static String toSubscript(String string) {
        return Subscript.toSubscript(string);
    }

    /** 离子结构式查询键：中性式+"^电荷"后缀，如 "SbF6^-"、"Cr2O7^2-"（在molecule_smiles.json中登记） */
    public static String ionStructureKey(IonFormulas.Ion ion) {
        int mag = Math.abs(ion.charge());
        return ion.formula() + "^" + (mag == 1 ? "" : String.valueOf(mag)) + (ion.charge() > 0 ? "+" : "-");
    }

    /**
     * 化学式提示行：元素符号按元素周期表配色染色，数字转Unicode下标
     * （下标数字跟随其前方元素的颜色），括号与分隔符保持黄色，无"化学式"前缀（与GTM一致）。
     * 同位素采用质量数前置写法（如"238U"，仅数据格式），渲染时质量数置于元素符号
     * 右上角上标（显示为U²³⁸），避免后置数字被误当原子个数下标（U238会被读作238个铀）。
     */
    public static Component formulaComponent(String formula) {
        MutableComponent result = Component.empty();
        int i = 0, n = formula.length();
        while (i < n) {
            char c = formula.charAt(i);
            if (Character.isDigit(c)) {
                // 出现在元素之前的数字串=同位素质量数（元素后的数字已在下方元素分支被消费为下标），
                // 渲染为Unicode上标并置于元素符号右上角，颜色跟随元素
                int start = i;
                while (i < n && Character.isDigit(formula.charAt(i))) i++;
                String mass = formula.substring(start, i);
                if (i < n && Character.isUpperCase(formula.charAt(i))) {
                    int elStart = i++;
                    if (i < n && Character.isLowerCase(formula.charAt(i))) i++;
                    String symbol = formula.substring(elStart, i);
                    StringBuilder digits = new StringBuilder();
                    while (i < n && Character.isDigit(formula.charAt(i))) digits.append(formula.charAt(i++));
                    int color = ElementColors.getColor(symbol);
                    result.append(Component.literal(symbol).withStyle(style -> style.withColor(color)));
                    result.append(Component.literal(Subscript.toSuperscript(mass)).withStyle(style -> style.withColor(color)));
                    if (digits.length() > 0) {
                        result.append(Component.literal(toSubscript(digits.toString()))
                                .withStyle(style -> style.withColor(color)));
                    }
                } else {
                    result.append(Component.literal(mass).withStyle(ChatFormatting.YELLOW));
                }
            } else if (Character.isUpperCase(c)) {
                int start = i++;
                if (i < n && Character.isLowerCase(formula.charAt(i))) i++; // 第二字母小写，如Fe
                String symbol = formula.substring(start, i);
                StringBuilder digits = new StringBuilder();
                while (i < n && Character.isDigit(formula.charAt(i))) digits.append(formula.charAt(i++));
                int color = ElementColors.getColor(symbol);
                result.append(Component.literal(symbol).withStyle(style -> style.withColor(color)));
                if (digits.length() > 0) {
                    String seg = digits.toString();
                    // 数字段后紧跟元素时，段尾可能是下一元素的同位素质量数（如"F6238U"），
                    // 切出后退回给上方数字分支渲染为左上角上标，剩余部分才是原子个数下标
                    if (i < n && Character.isUpperCase(formula.charAt(i))) {
                        String mass = trimMassSuffix(seg);
                        seg = seg.substring(0, seg.length() - mass.length());
                        i -= mass.length();
                    }
                    if (!seg.isEmpty()) {
                        // 下标数字跟随元素颜色，如Fe₃中的₃与Fe同色
                        String finalSeg = seg;
                        result.append(Component.literal(toSubscript(finalSeg))
                                .withStyle(style -> style.withColor(color)));
                    }
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
                // 元素后数字段：既可能是该元素原子个数，也可能是下一元素的同位素质量数前置
                //（如"F6238U"中6归F、238是U质量数）。规则：若数字段后紧跟元素符号，
                // 按已知质量数集合从段尾切出质量数；否则整段为原子个数。
                int dStart = i;
                while (i < n && Character.isDigit(formula.charAt(i))) i++;
                int count = 1;
                if (i > dStart) {
                    String seg = formula.substring(dStart, i);
                    if (i < n && Character.isUpperCase(formula.charAt(i))) {
                        String mass = trimMassSuffix(seg);
                        String countPart = seg.substring(0, seg.length() - mass.length());
                        count = countPart.isEmpty() ? 1 : Integer.parseInt(countPart);
                        i = dStart + countPart.length(); // 质量数留给下一轮元素前分支消费
                    } else {
                        count = Integer.parseInt(seg);
                    }
                }
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

    /** 本项目登记的同位素质量数集合（用于解析时把元素间数字段尾部质量数切出来） */
    private static final Set<String> KNOWN_MASS_NUMBERS = Set.of("3", "235", "238", "239", "241");

    /** 从元素间数字段的尾部切出已知质量数（如"6238"->"238"），无匹配返回空串 */
    private static String trimMassSuffix(String segment) {
        for (int len = Math.min(3, segment.length()); len >= 1; len--) {
            String tail = segment.substring(segment.length() - len);
            if (KNOWN_MASS_NUMBERS.contains(tail)) return tail;
        }
        return "";
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
     * 返回饼图组件（可选携带分子/离子结构式列表，由客户端绘制在饼图右侧），
     * 由调用方插入tooltip元素列表；否则返回null。
     */
    public static CompositionPieTooltipComponent getCompositionPie(ItemStack stack, List<CompositionStructureTooltipComponent.StructureEntry> structures) {
        if (!isShiftDown() || stack.isEmpty() || lastPieSlices.isEmpty()) return null;
        if (!ItemStack.matches(stack, lastPieStack)) return null;
        return new CompositionPieTooltipComponent(lastPieSlices, structures);
    }

    // ========== 分子结构式缓存（与饼图同机制：ItemTooltipEvent缓存，GatherComponents同帧消费） ==========

    /** 最近一次命中结构式注册表的物品栈 */
    private static ItemStack lastStructureStack = ItemStack.EMPTY;
    /** 最近一次命中的结构式条目列表（主化学式结构或各多原子离子结构，含离子电荷） */
    private static List<CompositionStructureTooltipComponent.StructureEntry> lastStructures = List.of();
    /** 本次tooltip收集中的结构条目列表（null=未开始收集） */
    private static List<CompositionStructureTooltipComponent.StructureEntry> collectingStructures;

    /** 开始本次tooltip的结构式收集（按住Shift才收集） */
    private static void beginStructureCache(ItemStack stack) {
        if (!isShiftDown()) return;
        lastStructureStack = stack;
        collectingStructures = new ArrayList<>();
    }

    /** 收集主化学式的结构式（分子/化合物本体，电荷为0不画离子括号） */
    private static void collectStructure(String formula) {
        if (collectingStructures == null) return;
        MoleculeStructure structure = MoleculeStructures.get(formula);
        if (structure != null) collectingStructures.add(new CompositionStructureTooltipComponent.StructureEntry(structure, 0, false));
    }

    /** 收集离子式中各多原子离子的结构式（携带离子电荷，客户端据此画离子括号；单原子离子如K⁺无结构，跳过） */
    private static void collectIonStructures(String ionic) {
        if (collectingStructures == null) return;
        for (IonFormulas.Ion ion : IonFormulas.parse(ionic)) {
            if (ion.formula().chars().noneMatch(Character::isUpperCase)) continue;
            MoleculeStructure structure = MoleculeStructures.get(ionStructureKey(ion));
            if (structure != null) collectingStructures.add(new CompositionStructureTooltipComponent.StructureEntry(structure, ion.charge(), false));
        }
    }

    /**
     * 收集聚合物重复单元结构（按物质id查询{@link PolymerFormulas}，命中时标记polymer=true，
     * 客户端画通高"[ ]"大括号+右下角"n"）。聚合物与单体化学式常相同
     * （如聚乙烯与乙烯都是C2H4），故命中后不再叠加显示单体/中性结构。
     *
     * @return true=已命中并收集（调用方跳过普通结构收集）
     */
    private static boolean collectPolymerStructure(String id) {
        if (collectingStructures == null || id == null) return false;
        MoleculeStructure structure = PolymerFormulas.get(id);
        if (structure == null) return false;
        collectingStructures.add(new CompositionStructureTooltipComponent.StructureEntry(structure, 0, true));
        return true;
    }

    /** 结束收集：有结构时记录供同帧GatherComponents插入 */
    private static void finishStructureCache() {
        if (collectingStructures == null) return;
        lastStructures = collectingStructures.isEmpty() ? List.of() : List.copyOf(collectingStructures);
        collectingStructures = null;
    }

    /**
     * 供RenderTooltipEvent.GatherComponents调用：悬停物品已登记结构式（按住Shift）时
     * 返回结构数据列表，由调用方插入tooltip元素列表；否则返回空列表。
     */
    public static List<CompositionStructureTooltipComponent.StructureEntry> getMoleculeStructures(ItemStack stack) {
        if (!isShiftDown() || stack.isEmpty() || lastStructures.isEmpty()) return List.of();
        if (!ItemStack.matches(stack, lastStructureStack)) return List.of();
        return lastStructures;
    }

    /**
     * RenderTooltipEvent.GatherComponents监听器（游戏总线事件，由PolymechClient在客户端
     * 手动注册到NeoForge.EVENT_BUS）：按住Shift悬停时插入成分饼图组件（参考GregTech样式），
     * 已登记结构式的物质其结构式（含离子化合物的各离子结构）随饼图组件一并携带，
     * 客户端并排绘制在饼图右侧；无饼图数据但有结构式时退回单独的结构式组件。
     */
    public static void onGatherTooltipComponents(RenderTooltipEvent.GatherComponents event) {
        List<CompositionStructureTooltipComponent.StructureEntry> structures = getMoleculeStructures(event.getItemStack());
        CompositionPieTooltipComponent pie = getCompositionPie(event.getItemStack(), structures);
        if (pie != null) {
            event.getTooltipElements().add(Either.right(pie));
        } else if (!structures.isEmpty()) {
            event.getTooltipElements().add(Either.right(new CompositionStructureTooltipComponent(structures)));
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
            beginStructureCache(stack);
            // 聚合物：显示重复单元（[ ]+n）而非单体结构；未登记时退回离子/普通结构
            if (!collectPolymerStructure(lookupMaterialName(stack.getItem()))) {
                // 离子化合物：只显示各离子结构（带离子括号与电荷，与GTM一致），不叠加显示中性分子结构
                String ionic = IonFormulas.get(formula);
                if (ionic != null) collectIonStructures(ionic);
                else collectStructure(formula);
            }
            finishStructureCache();
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

    /** 物品材料名反查：先查模组材料物品，再查材料存储块；非材料物品返回null */
    private static String lookupMaterialName(Item item) {
        String materialName = ModItems.getMaterialOf(item);
        if (materialName == null && item instanceof BlockItem blockItem) {
            // 材料存储块（如 steel_block）：经方块反查材料名
            materialName = ModBlocks.getMaterialOfBlock(blockItem.getBlock());
        }
        return materialName;
    }

    /*
     * 物品化学式查找（OOP/数据驱动，新增物品自动获得化学式tooltip）。
     * <p>
     * 解析优先级：
     * <ol>
     *   <li>矿物加工产物（raw_{mineral} / {mineral}_crushed / {mineral}_purified）：
     *       自动从{@link ModMinerals.MineralDefinition#formula()}取化学式——矿物表新增即生效；</li>
     *   <li>矿石方块（全部岩种变体）：同上自动从矿物定义表取化学式；</li>
     *   <li>通用注册表（原版矿物/单质等特殊物品）；</li>
     *   <li>材料物品（锭/粉/板/宝石等）：从{@link MaterialRegistry#getFormula}取材料化学式。</li>
     * </ol>
     * 不再为每个模组物品硬编码一条tooltip登记——数据都在各自的定义表里。
     * </p>
     */
    private static String lookupFormula(Item item) {
        // 1. 矿物加工产物：矿物定义表驱动（粗矿/粉碎矿/洗净矿自动获得化学式）
        String mineralName = ModItems.getMineralOf(item);
        if (mineralName != null) {
            var def = ModMinerals.getDefinition(mineralName);
            if (def != null && !def.formula().isEmpty()) return def.formula();
        }
        // 2. 矿石方块（所有岩种变体）：矿物定义表驱动
        if (item instanceof BlockItem blockItem) {
            String oreMineral = ModBlocks.getMineralOfBlock(blockItem.getBlock());
            if (oreMineral != null) {
                var def = ModMinerals.getDefinition(oreMineral);
                if (def != null && !def.formula().isEmpty()) return def.formula();
            }
        }
        // 3. 通用注册表（原版矿物/单质等特殊物品）
        String extra = EXTRA_FORMULAS.get(item);
        if (extra != null) return extra;
        // 4. 材料物品（锭/粉/板/宝石等）：材料定义表驱动
        String materialName = lookupMaterialName(item);
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
            beginStructureCache(stack);
            // 聚合物流体（如熔融聚乙烯）：显示重复单元（[ ]+n）而非单体结构
            String polymerId = info instanceof ChemicalFluid chemFluid ? chemFluid.getId() : null;
            if (!collectPolymerStructure(polymerId)) {
                // 离子化合物：不显示离子式文字行，仅将各离子结构式（带离子括号与电荷）并入饼图右侧展示
                String ionic = IonFormulas.get(formula);
                if (ionic != null) collectIonStructures(ionic);
                else collectStructure(formula);
            }
            finishStructureCache();
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
