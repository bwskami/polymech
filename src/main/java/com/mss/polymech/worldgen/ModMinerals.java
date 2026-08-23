package com.mss.polymech.worldgen;

import com.mss.polymech.Polymech;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/*
 * 真实矿物系统：数据驱动的矿物定义表（格雷∪群峦写实矿物全集）。
 * <p>
 * 现实中的金属几乎不以单质形式存在于自然界，而是以矿物（化合物）形式存在：
 * 锡来自锡石(SnO₂)、铅来自方铅矿(PbS)、铝来自铝土矿(Al₂O₃)。
 * 矿物清单照抄两大写实参考：<b>群峦传说(TFC)</b>与<b>格雷科技(GregTech CEu)</b>，
 * 仅剔除格雷的虚构矿物（naquadah/electrotine）。
 * </p>
 *
 * <h2>岩石版本矿石（格雷式）：</h2>
 * <p>
 * 每种矿物在每种宿主岩中都有独立方块（群峦式"看岩认矿"）：
 * <ul>
 *   <li>石头矿：{mineral}_ore（原版石头底图，兼容保留）</li>
 *   <li>深层矿：deepslate_{mineral}_ore（原版深板岩底图染色——群峦无深板岩岩石）</li>
 *   <li>岩种矿：{mineral}_{rock}_ore（21种群峦岩石，每种岩石一个变体）</li>
 * </ul>
 * 全部由{@link com.mss.polymech.block.ModBlocks}数据驱动批量注册。
 * </p>
 *
 * <h2>矿石贴图结构（OOP准则：底图不染色）：</h2>
 * <pre>
 * 第0层  岩石底图（群峦岩石贴图原样，不染色；深板岩底图染色）
 * 第1层  矿石底图  block/ore/{set}/ore.png          —— 格雷ore.png，主色染色
 * 第2层  矿石阴影  block/ore/{set}/ore_shadow.png   —— 格雷ore_layer2.png，辅色染色
 * 第3层  矿石高光  block/ore/{set}/ore_highlight.png—— 格雷底图最亮像素提取，不染色白色光泽
 * </pre>
 *
 * <h2>产物类型：</h2>
 * <ul>
 *   <li>METAL：掉落粗矿物raw_{mineral}，破碎/跳汰选矿→金属粉→冶炼→锭</li>
 *   <li>GEM：直接掉落宝石{metal}_gem（群峦式宝石矿）</li>
 *   <li>DUST：直接掉落粉末{metal}_dust（硫磺/石墨/石膏等非金属矿）</li>
 *   <li>COAL：直接掉落原版煤炭（烟煤/褐煤/油砂）</li>
 * </ul>
 *
 * @see ModVeins 矿脉系统（矿物聚集体）
 * @see ModRocks 岩种系统
 */
public final class ModMinerals {

    /** 开采等级（决定needs_stone_tool/needs_iron_tool标签） */
    public enum ToolTier { STONE, IRON }

    /** 产物类型（决定矿石掉落物与加工链） */
    public enum ProductKind { METAL, GEM, DUST, COAL }

    /*
     * 单条矿物定义。
     *
     * @param mineral 矿物ID（方块/物品/配色/标签/语言统一使用）
     * @param metal 产物材料名：METAL→金属粉材料，GEM→宝石材料，
     *        DUST→粉末材料，COAL→忽略（掉原版煤）。必须在材料系统中存在。
     * @param kind 产物类型
     * @param tier 开采等级
     * @param oreSet 矿石外观图标集（照抄格雷MaterialIconSet名，
     *        经{@link #oreTextureFolder}按格雷parentIconset回退链解析到贴图文件夹）
     * @param formula 化学式（展示用途）
     * @param oreShape 矿石形态编号 1~4（普通/层状/斜向小块分段/大块状）。
     *        只区分不同矿物的外观、不代表丰度；与矿物硬度/结晶习性对应，
     *        使矿石贴图多样、避免审美疲劳。block/ore/ore{shape} 与
     *        item/material_sets/raw_ore/raw{shape} 共用同一编号。
     * @param scatterSize 散矿矿脉大小（scatterCount为0时无效）
     * @param scatterCount 散矿每区块生成次数；0表示仅由矿脉生成
     * @param scatterMinY 散矿生成高度下限（三角形分布）
     * @param scatterMaxY 散矿生成高度上限（三角形分布）
     */
    public record MineralDefinition(
            String mineral,
            String metal,
            ProductKind kind,
            ToolTier tier,
            String oreSet,
            String formula,
            int oreShape,
            int scatterSize,
            int scatterCount,
            int scatterMinY,
            int scatterMaxY
    ) {
        /** 石头矿方块注册名：{mineral}_ore */
        public String stoneOreName() {
            return mineral + "_ore";
        }

        /** 深层矿方块注册名：deepslate_{mineral}_ore */
        public String deepslateOreName() {
            return "deepslate_" + mineral + "_ore";
        }

        /** 岩种矿方块注册名：{mineral}_{rock}_ore */
        public String rockOreName(String rock) {
            return mineral + "_" + rock + "_ore";
        }

        /** 粗矿物物品注册名：raw_{mineral}（仅METAL产物类型使用） */
        public String rawItemName() {
            return "raw_" + mineral;
        }

        /** 是否保留散矿生成（砂矿保底） */
        public boolean hasScatter() {
            return scatterCount > 0;
        }
    }

    /*
     * 格雷图标集→矿石贴图文件夹回退表。
     * <p>
     * 完全复刻格雷MaterialIconSet的parentIconset回退链：
     * metallic系无独立矿石贴图→回退dull；gem系→回退diamond；
     * certus→回退quartz；sand/wood→回退fine。
     * 基础贴图集共8个：dull/rough/fine/flint/quartz/lignite/lapis/diamond
     * （素材取自GregTech CEu，LGPL-3.0，见TEXTURE_CREDITS.md）。
     * </p>
     */
    private static final Map<String, String> ORE_SET_FALLBACK = Map.ofEntries(
            Map.entry("dull", "dull"),
            Map.entry("rough", "rough"),
            Map.entry("fine", "fine"),
            Map.entry("flint", "flint"),
            Map.entry("quartz", "quartz"),
            Map.entry("lignite", "lignite"),
            Map.entry("lapis", "lapis"),
            Map.entry("diamond", "diamond"),
            Map.entry("metallic", "dull"),
            Map.entry("magnetic", "dull"),
            Map.entry("shiny", "dull"),
            Map.entry("bright", "dull"),
            Map.entry("radioactive", "dull"),
            Map.entry("emerald", "diamond"),
            Map.entry("ruby", "diamond"),
            Map.entry("gem_horizontal", "diamond"),
            Map.entry("gem_vertical", "diamond"),
            Map.entry("opal", "diamond"),
            Map.entry("glass", "diamond"),
            Map.entry("netherstar", "diamond"),
            Map.entry("certus", "quartz"),
            Map.entry("sand", "fine"),
            Map.entry("wood", "fine"),
            Map.entry("paper", "fine"),
            Map.entry("powder", "fine")
    );

    /** 图标集名→矿石贴图文件夹（未知集回退dull，与格雷未知集回退DULL一致） */
    public static String oreTextureFolder(String iconSet) {
        return ORE_SET_FALLBACK.getOrDefault(iconSet, "dull");
    }

    /*
     * 全部矿物定义（格雷∪群峦写实矿物全集）。
     * <p>
     * 分类：金属矿物→石榴石族→宝石→非金属→煤系红石。
     * 化学式与配色取自格雷材料数据（color/secondaryColor）与真实矿物学。
     * </p>
     */
    private static final List<MineralDefinition> DEFINITIONS = List.of(
            new MineralDefinition("native_copper", "copper", ProductKind.METAL, ToolTier.STONE, "metallic", "Cu", 1, 8, 3, 0, 80),
            new MineralDefinition("malachite", "copper", ProductKind.METAL, ToolTier.STONE, "lapis", "Cu2CO3(OH)2", 2, 0, 0, 0, 0),
            new MineralDefinition("chalcopyrite", "copper", ProductKind.METAL, ToolTier.STONE, "dull", "CuFeS2", 3, 0, 0, 0, 0),
            new MineralDefinition("bornite", "copper", ProductKind.METAL, ToolTier.STONE, "rough", "Cu5FeS4", 3, 0, 0, 0, 0),
            new MineralDefinition("chalcocite", "copper", ProductKind.METAL, ToolTier.STONE, "emerald", "Cu2S", 3, 0, 0, 0, 0),
            new MineralDefinition("tetrahedrite", "copper", ProductKind.METAL, ToolTier.STONE, "dull", "Cu3FeSb3S8", 3, 0, 0, 0, 0),
            new MineralDefinition("copper", "copper", ProductKind.METAL, ToolTier.STONE, "metallic", "Cu", 3, 0, 0, 0, 0),
            new MineralDefinition("hematite", "iron", ProductKind.METAL, ToolTier.STONE, "dull", "Fe2O3", 2, 0, 0, 0, 0),
            new MineralDefinition("magnetite", "iron", ProductKind.METAL, ToolTier.STONE, "metallic", "Fe3O4", 4, 0, 0, 0, 0),
            new MineralDefinition("limonite", "iron", ProductKind.METAL, ToolTier.STONE, "dull", "FeO(OH)", 2, 0, 0, 0, 0),
            new MineralDefinition("goethite", "iron", ProductKind.METAL, ToolTier.STONE, "metallic", "FeO(OH)", 2, 0, 0, 0, 0),
            new MineralDefinition("vanadium_magnetite", "vanadium", ProductKind.METAL, ToolTier.IRON, "metallic", "(Fe,V)3O4", 2, 0, 0, 0, 0),
            new MineralDefinition("iron", "iron", ProductKind.METAL, ToolTier.STONE, "metallic", "Fe", 4, 0, 0, 0, 0),
            new MineralDefinition("cassiterite", "tin", ProductKind.METAL, ToolTier.STONE, "rough", "SnO2", 1, 9, 4, -16, 64),
            new MineralDefinition("cassiterite_sand", "tin", ProductKind.METAL, ToolTier.STONE, "sand", "SnO2", 1, 0, 0, 0, 0),
            new MineralDefinition("tin", "tin", ProductKind.METAL, ToolTier.STONE, "metallic", "Sn", 1, 0, 0, 0, 0),
            new MineralDefinition("sphalerite", "zinc", ProductKind.METAL, ToolTier.STONE, "dull", "ZnS", 3, 8, 3, -16, 48),
            new MineralDefinition("galena", "lead", ProductKind.METAL, ToolTier.IRON, "metallic", "PbS", 3, 0, 0, 0, 0),
            new MineralDefinition("lead", "lead", ProductKind.METAL, ToolTier.IRON, "metallic", "Pb", 4, 0, 0, 0, 0),
            new MineralDefinition("native_silver", "silver", ProductKind.METAL, ToolTier.IRON, "shiny", "Ag", 3, 0, 0, 0, 0),
            new MineralDefinition("silver", "silver", ProductKind.METAL, ToolTier.IRON, "shiny", "Ag", 3, 0, 0, 0, 0),
            new MineralDefinition("native_gold", "gold", ProductKind.METAL, ToolTier.IRON, "shiny", "Au", 3, 0, 0, 0, 0),
            new MineralDefinition("gold", "gold", ProductKind.METAL, ToolTier.IRON, "shiny", "Au", 3, 0, 0, 0, 0),
            new MineralDefinition("bismuthinite", "bismuth", ProductKind.METAL, ToolTier.STONE, "dull", "Bi2S3", 3, 0, 0, 0, 0),
            new MineralDefinition("garnierite", "nickel", ProductKind.METAL, ToolTier.IRON, "metallic", "Ni3Si2O5(OH)4", 2, 0, 0, 0, 0),
            new MineralDefinition("pentlandite", "nickel", ProductKind.METAL, ToolTier.IRON, "dull", "(Ni,Fe)9S8", 3, 0, 0, 0, 0),
            new MineralDefinition("nickel", "nickel", ProductKind.METAL, ToolTier.IRON, "metallic", "Ni", 1, 0, 0, 0, 0),
            new MineralDefinition("cobaltite", "cobalt", ProductKind.METAL, ToolTier.IRON, "metallic", "CoAsS", 4, 0, 0, 0, 0),
            new MineralDefinition("cobalt", "cobalt", ProductKind.METAL, ToolTier.IRON, "metallic", "Co", 1, 0, 0, 0, 0),
            new MineralDefinition("bauxite", "aluminium", ProductKind.METAL, ToolTier.STONE, "dull", "Al2O3.nH2O", 2, 0, 0, 0, 0),
            new MineralDefinition("alunite", "aluminium", ProductKind.METAL, ToolTier.STONE, "metallic", "KAl3(SO4)2(OH)6", 2, 0, 0, 0, 0),
            new MineralDefinition("aluminium", "aluminium", ProductKind.METAL, ToolTier.STONE, "metallic", "Al", 1, 0, 0, 0, 0),
            new MineralDefinition("wolframite", "tungsten", ProductKind.METAL, ToolTier.IRON, "flint", "(Fe,Mn)WO4", 4, 0, 0, 0, 0),
            new MineralDefinition("scheelite", "tungsten", ProductKind.METAL, ToolTier.IRON, "dull", "CaWO4", 4, 0, 0, 0, 0),
            new MineralDefinition("tungstate", "tungsten", ProductKind.METAL, ToolTier.IRON, "dull", "Li2WO4", 4, 0, 0, 0, 0),
            new MineralDefinition("stibnite", "antimony", ProductKind.METAL, ToolTier.IRON, "metallic", "Sb2S3", 3, 0, 0, 0, 0),
            new MineralDefinition("realgar", "sulfur", ProductKind.DUST, ToolTier.STONE, "emerald", "As4S4", 3, 0, 0, 0, 0),
            new MineralDefinition("pyrolusite", "manganese", ProductKind.METAL, ToolTier.IRON, "dull", "MnO2", 3, 0, 0, 0, 0),
            new MineralDefinition("chromite", "chromium", ProductKind.METAL, ToolTier.IRON, "metallic", "FeCr2O4", 4, 0, 0, 0, 0),
            new MineralDefinition("ilmenite", "titanium", ProductKind.METAL, ToolTier.IRON, "metallic", "FeTiO3", 4, 0, 0, 0, 0),
            new MineralDefinition("molybdenite", "molybdenum", ProductKind.METAL, ToolTier.IRON, "metallic", "MoS2", 3, 0, 0, 0, 0),
            new MineralDefinition("powellite", "molybdenum", ProductKind.METAL, ToolTier.IRON, "dull", "CaMoO4", 1, 0, 0, 0, 0),
            new MineralDefinition("wulfenite", "molybdenum", ProductKind.METAL, ToolTier.IRON, "dull", "PbMoO4", 1, 0, 0, 0, 0),
            new MineralDefinition("molybdenum", "molybdenum", ProductKind.METAL, ToolTier.IRON, "shiny", "Mo", 4, 0, 0, 0, 0),
            new MineralDefinition("tantalite", "tantalum", ProductKind.METAL, ToolTier.IRON, "metallic", "(Fe,Mn)Ta2O6", 4, 0, 0, 0, 0),
            new MineralDefinition("pyrochlore", "niobium", ProductKind.METAL, ToolTier.IRON, "metallic", "(Na,Ca)2Nb2O6(OH,F)", 4, 0, 0, 0, 0),
            new MineralDefinition("cooperite", "platinum", ProductKind.METAL, ToolTier.IRON, "metallic", "(Pt,Pd,Ni)S", 1, 0, 0, 0, 0),
            new MineralDefinition("platinum", "platinum", ProductKind.METAL, ToolTier.IRON, "shiny", "Pt", 1, 0, 0, 0, 0),
            new MineralDefinition("palladium", "palladium", ProductKind.METAL, ToolTier.IRON, "shiny", "Pd", 1, 0, 0, 0, 0),
            new MineralDefinition("pitchblende", "uranium", ProductKind.METAL, ToolTier.IRON, "dull", "U3O8", 4, 0, 0, 0, 0),
            new MineralDefinition("uraninite", "uranium", ProductKind.METAL, ToolTier.IRON, "metallic", "UO2", 4, 0, 0, 0, 0),
            new MineralDefinition("thorium", "thorium", ProductKind.METAL, ToolTier.IRON, "shiny", "Th", 4, 0, 0, 0, 0),
            new MineralDefinition("plutonium_239", "plutonium", ProductKind.METAL, ToolTier.IRON, "radioactive", "Pu", 4, 0, 0, 0, 0),
            new MineralDefinition("beryllium", "beryllium", ProductKind.METAL, ToolTier.IRON, "metallic", "Be", 4, 0, 0, 0, 0),
            new MineralDefinition("spodumene", "lithium", ProductKind.METAL, ToolTier.STONE, "dull", "LiAlSi2O6", 1, 0, 0, 0, 0),
            new MineralDefinition("lepidolite", "lithium", ProductKind.METAL, ToolTier.STONE, "fine", "KLi2Al(Al,Si)3O10(F,OH)2", 2, 0, 0, 0, 0),
            new MineralDefinition("lithium", "lithium", ProductKind.METAL, ToolTier.STONE, "metallic", "Li", 1, 0, 0, 0, 0),
            new MineralDefinition("pollucite", "caesium", ProductKind.METAL, ToolTier.STONE, "dull", "(Cs,Na)2Al2Si4O12.2H2O", 1, 0, 0, 0, 0),
            new MineralDefinition("bastnasite", "neodymium", ProductKind.METAL, ToolTier.IRON, "fine", "(Ce,La)(CO3)F", 4, 0, 0, 0, 0),
            new MineralDefinition("monazite", "neodymium", ProductKind.METAL, ToolTier.IRON, "diamond", "(Ce,La,Nd)PO4", 4, 0, 0, 0, 0),
            new MineralDefinition("neodymium", "neodymium", ProductKind.METAL, ToolTier.IRON, "metallic", "Nd", 4, 0, 0, 0, 0),
            new MineralDefinition("almandine", "garnet", ProductKind.GEM, ToolTier.STONE, "dull", "Fe3Al2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("andradite", "garnet", ProductKind.GEM, ToolTier.STONE, "ruby", "Ca3Fe2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("grossular", "garnet", ProductKind.GEM, ToolTier.STONE, "ruby", "Ca3Al2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("pyrope", "garnet", ProductKind.GEM, ToolTier.STONE, "ruby", "Mg3Al2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("spessartine", "garnet", ProductKind.GEM, ToolTier.STONE, "ruby", "Mn3Al2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("red_garnet", "garnet", ProductKind.GEM, ToolTier.STONE, "ruby", "(Fe,Mg,Mn)3Al2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("yellow_garnet", "garnet", ProductKind.GEM, ToolTier.STONE, "ruby", "(Ca,Fe,Mg)3(Al,Fe)2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("amethyst", "amethyst", ProductKind.GEM, ToolTier.STONE, "ruby", "SiO2", 4, 0, 0, 0, 0),
            new MineralDefinition("diamond", "diamond", ProductKind.GEM, ToolTier.IRON, "diamond", "C", 4, 0, 0, 0, 0),
            new MineralDefinition("emerald", "emerald", ProductKind.GEM, ToolTier.IRON, "emerald", "Be3Al2Si6O18", 4, 0, 0, 0, 0),
            new MineralDefinition("lapis_lazuli", "lapis_lazuli", ProductKind.GEM, ToolTier.STONE, "lapis", "(Na,Ca)8(AlSiO4)6(S,SO4,Cl)2", 1, 0, 0, 0, 0),
            new MineralDefinition("lazurite", "lapis_lazuli", ProductKind.GEM, ToolTier.STONE, "lapis", "(Na,Ca)8(AlSiO4)6(S,SO4,Cl)2", 1, 0, 0, 0, 0),
            new MineralDefinition("sodalite", "lapis_lazuli", ProductKind.GEM, ToolTier.STONE, "lapis", "Na8(AlSiO4)6Cl2", 1, 0, 0, 0, 0),
            new MineralDefinition("opal", "opal", ProductKind.GEM, ToolTier.STONE, "opal", "SiO2.nH2O", 1, 0, 0, 0, 0),
            new MineralDefinition("pyrite", "pyrite", ProductKind.DUST, ToolTier.STONE, "rough", "FeS2", 3, 0, 0, 0, 0),
            new MineralDefinition("ruby", "ruby", ProductKind.GEM, ToolTier.IRON, "ruby", "Al2O3", 4, 0, 0, 0, 0),
            new MineralDefinition("sapphire", "sapphire", ProductKind.GEM, ToolTier.IRON, "emerald", "Al2O3", 4, 0, 0, 0, 0),
            new MineralDefinition("green_sapphire", "green_sapphire", ProductKind.GEM, ToolTier.IRON, "gem_horizontal", "Al2O3", 4, 0, 0, 0, 0),
            new MineralDefinition("topaz", "topaz", ProductKind.GEM, ToolTier.STONE, "gem_horizontal", "Al2SiO4(F,OH)2", 4, 0, 0, 0, 0),
            new MineralDefinition("blue_topaz", "topaz", ProductKind.GEM, ToolTier.STONE, "gem_horizontal", "Al2SiO4(F,OH)2", 4, 0, 0, 0, 0),
            new MineralDefinition("apatite", "apatite", ProductKind.GEM, ToolTier.STONE, "diamond", "Ca5(PO4)3(F,Cl,OH)", 4, 0, 0, 0, 0),
            new MineralDefinition("olivine", "olivine", ProductKind.DUST, ToolTier.STONE, "ruby", "(Mg,Fe)2SiO4", 1, 0, 0, 0, 0),
            new MineralDefinition("quartzite", "quartz", ProductKind.GEM, ToolTier.STONE, "quartz", "SiO2", 4, 0, 0, 0, 0),
            new MineralDefinition("nether_quartz", "quartz", ProductKind.GEM, ToolTier.STONE, "quartz", "SiO2", 4, 0, 0, 0, 0),
            new MineralDefinition("certus_quartz", "certus_quartz", ProductKind.GEM, ToolTier.STONE, "certus", "SiO2", 4, 0, 0, 0, 0),
            new MineralDefinition("sulfur", "sulfur", ProductKind.DUST, ToolTier.STONE, "dull", "S", 1, 0, 0, 0, 0),
            new MineralDefinition("graphite", "graphite", ProductKind.DUST, ToolTier.STONE, "dull", "C", 2, 0, 0, 0, 0),
            new MineralDefinition("saltpeter", "saltpeter", ProductKind.DUST, ToolTier.STONE, "fine", "KNO3", 2, 0, 0, 0, 0),
            new MineralDefinition("sylvite", "sylvite", ProductKind.DUST, ToolTier.STONE, "fine", "KCl", 2, 0, 0, 0, 0),
            new MineralDefinition("salt", "salt", ProductKind.DUST, ToolTier.STONE, "fine", "NaCl", 2, 0, 0, 0, 0),
            new MineralDefinition("rock_salt", "salt", ProductKind.DUST, ToolTier.STONE, "fine", "NaCl", 2, 0, 0, 0, 0),
            new MineralDefinition("gypsum", "gypsum", ProductKind.DUST, ToolTier.STONE, "dull", "CaSO4.2H2O", 2, 0, 0, 0, 0),
            new MineralDefinition("cinnabar", "cinnabar", ProductKind.DUST, ToolTier.STONE, "emerald", "HgS", 3, 0, 0, 0, 0),
            new MineralDefinition("cryolite", "cryolite", ProductKind.DUST, ToolTier.STONE, "dull", "Na3AlF6", 1, 0, 0, 0, 0),
            new MineralDefinition("borax", "borax", ProductKind.DUST, ToolTier.STONE, "dull", "Na2B4O7.10H2O", 2, 0, 0, 0, 0),
            new MineralDefinition("calcite", "calcite", ProductKind.DUST, ToolTier.STONE, "dull", "CaCO3", 1, 0, 0, 0, 0),
            new MineralDefinition("barite", "barite", ProductKind.DUST, ToolTier.STONE, "dull", "BaSO4", 2, 0, 0, 0, 0),
            new MineralDefinition("magnesite", "magnesium", ProductKind.DUST, ToolTier.STONE, "rough", "MgCO3", 2, 0, 0, 0, 0),
            new MineralDefinition("asbestos", "asbestos", ProductKind.DUST, ToolTier.STONE, "dull", "Mg3Si2O5(OH)4", 2, 0, 0, 0, 0),
            new MineralDefinition("mica", "mica", ProductKind.DUST, ToolTier.STONE, "fine", "KAl2(AlSi3O10)(F,OH)2", 2, 0, 0, 0, 0),
            new MineralDefinition("talc", "talc", ProductKind.DUST, ToolTier.STONE, "fine", "Mg3Si4O10(OH)2", 2, 0, 0, 0, 0),
            new MineralDefinition("soapstone", "talc", ProductKind.DUST, ToolTier.STONE, "rough", "Mg3Si4O10(OH)2", 2, 0, 0, 0, 0),
            new MineralDefinition("kyanite", "kyanite", ProductKind.DUST, ToolTier.STONE, "flint", "Al2SiO5", 2, 0, 0, 0, 0),
            new MineralDefinition("diatomite", "diatomite", ProductKind.DUST, ToolTier.STONE, "dull", "SiO2", 2, 0, 0, 0, 0),
            new MineralDefinition("bentonite", "bentonite", ProductKind.DUST, ToolTier.STONE, "rough", "(Na,Ca)(Al,Mg)2Si4O10.nH2O", 2, 0, 0, 0, 0),
            new MineralDefinition("fullers_earth", "fullers_earth", ProductKind.DUST, ToolTier.STONE, "fine", "(Mg,Al)2Si4O10.nH2O", 2, 0, 0, 0, 0),
            new MineralDefinition("glauconite_sand", "potassium", ProductKind.DUST, ToolTier.STONE, "sand", "(K,Na)(Fe,Al,Mg)2(Si,Al)4O10(OH)2", 2, 0, 0, 0, 0),
            new MineralDefinition("zeolite", "zeolite", ProductKind.DUST, ToolTier.STONE, "dull", "Na2Al2Si3O10.nH2O", 1, 0, 0, 0, 0),
            new MineralDefinition("trona", "sodium", ProductKind.DUST, ToolTier.STONE, "metallic", "Na3(CO3)(HCO3).2H2O", 2, 0, 0, 0, 0),
            new MineralDefinition("tricalcium_phosphate", "phosphate", ProductKind.DUST, ToolTier.STONE, "flint", "Ca3(PO4)2", 1, 0, 0, 0, 0),
            new MineralDefinition("basaltic_mineral_sand", "iron", ProductKind.DUST, ToolTier.STONE, "sand", "Fe3O4", 1, 0, 0, 0, 0),
            new MineralDefinition("granitic_mineral_sand", "iron", ProductKind.DUST, ToolTier.STONE, "sand", "Fe2O3", 1, 0, 0, 0, 0),
            new MineralDefinition("garnet_sand", "garnet", ProductKind.GEM, ToolTier.STONE, "sand", "(Fe,Mg)3Al2Si3O12", 1, 0, 0, 0, 0),
            new MineralDefinition("bituminous_coal", "coal", ProductKind.COAL, ToolTier.STONE, "lignite", "C", 2, 0, 0, 0, 0),
            new MineralDefinition("lignite", "coal", ProductKind.COAL, ToolTier.STONE, "lignite", "C", 2, 0, 0, 0, 0),
            new MineralDefinition("oilsands", "coal", ProductKind.COAL, ToolTier.STONE, "sand", "C", 2, 0, 0, 0, 0),
            new MineralDefinition("redstone", "redstone", ProductKind.DUST, ToolTier.IRON, "rough", "", 3, 0, 0, 0, 0)
    );

    private ModMinerals() {
    }

    /** 获取全部矿物定义（只读） */
    public static List<MineralDefinition> getDefinitions() {
        return DEFINITIONS;
    }

    /** 判断矿物名是否存在 */
    public static boolean hasMineral(String mineralName) {
        return getDefinition(mineralName) != null;
    }

    /** 按矿物名查找定义；不存在返回null */
    @Nullable
    public static MineralDefinition getDefinition(String mineralName) {
        for (MineralDefinition def : DEFINITIONS) {
            if (def.mineral().equals(mineralName)) return def;
        }
        return null;
    }

    /** 按产出材料查找首个矿物（如 tin → cassiterite）；不存在返回null */
    @Nullable
    public static MineralDefinition getDefinitionByMetal(String metalName) {
        for (MineralDefinition def : DEFINITIONS) {
            if (def.metal().equals(metalName)) return def;
        }
        return null;
    }

    // ========== 世界生成资源定位（散矿砂矿保底） ==========

    /** 散矿配置特征ID：poly_mech:{mineral}_ore */
    public static ResourceLocation configuredFeatureId(String mineral) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, mineral + "_ore");
    }

    /** 散矿放置特征ID：poly_mech:{mineral}_ore */
    public static ResourceLocation placedFeatureId(String mineral) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, mineral + "_ore");
    }

    /** 散矿生物群系修饰器ID：poly_mech:add_{mineral}_ore */
    public static ResourceLocation biomeModifierId(String mineral) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "add_" + mineral + "_ore");
    }
}
