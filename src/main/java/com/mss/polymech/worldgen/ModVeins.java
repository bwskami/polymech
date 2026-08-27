package com.mss.polymech.worldgen;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 矿脉系统：数据驱动的矿物聚集体（群峦式分布 + 格雷式组成）。
 * <p>
 * 分布逻辑（群峦式跨区块生成）：
 * <ul>
 *   <li>无网格锚点，也不依赖放置修饰器。{@link OreVeinFeature}每区块执行一次，
 *       扫描附近的候选中心区块，按(世界种子, 区块坐标, 矿脉种子)确定性掷骰：
 *       每rarity个中心区块平均出一条矿脉，中心位置区块内随机、高度在
 *       minY~maxY间随机。椭球可横跨多个区块，各区块只放置落在自己内部的部分，
 *       整条矿脉在区块边界无缝衔接</li>
 *   <li>岩种约束为两级过滤：放置期先检查矿脉中心岩种，
 *       中心落在错误岩区的骰子整体作废；放行后替换期再逐方块检查宿主——
 *       矿体只在允许的宿主岩内生长，岩区边界穿过矿体时沿边界自然裁切</li>
 *   <li>矿体形状：密度采样椭球——椭球范围内每个方块以density概率成矿</li>
 * </ul>
 * </p>
 * <p>
 * <b>矿脉表来源（照抄原则）：</b>
 * <ul>
 *   <li>群峦传说(1.21)自带矿脉：rarity/高度/宿主岩完全照抄其
 *       data/tfc/worldgen/configured_feature/vein/*.json，
 *       组成矿物按格雷四角色（主/次/夹层/零星）映射</li>
 *   <li>格雷独有矿物（群峦没有的）：宿主岩按真实矿床学配置
 *       （斑岩铜矿→酸性火成岩、铬铁矿→基性岩、蒸发岩→沉积岩等），
 *       组成照抄格雷矿脉定义（data/gtceu/gtceu/ore_vein/*.json）</li>
 * </ul>
 * 岩种名：沉积8岩=石灰岩/页岩/白垩岩/硅质岩/粘土岩/砾岩/白云岩/凝灰岩，
 * 火成7岩=花岗岩/玄武岩/流纹岩/英安岩/闪长岩/辉长岩/安山岩。
 * </p>
 *
 * @see OreVeinFeature
 */
public final class ModVeins {

    /** 地表/地下指示物配置（群峦式） */
    public record VeinIndicator(
            int surfaceRarity, int depth, int undergroundRarity, int undergroundCount, String mineral,
            int indicatorRadius, float indicatorDensity
    ) {
        public static VeinIndicator surface(String m, int r) {
            return new VeinIndicator(r, 35, 1, 0, m, 3, 0.15F);
        }
        public static VeinIndicator surface(String m, int r, int radius, float density) {
            return new VeinIndicator(r, 35, 1, 0, m, radius, density);
        }
        public static VeinIndicator deep(String m, int c) {
            return new VeinIndicator(0, 35, 1, c, m, 0, 0F);
        }
        public static VeinIndicator underground(String m, int c) {
            return new VeinIndicator(0, 35, 1, c, m, 0, 0F);
        }
        public static VeinIndicator none() {
            return new VeinIndicator(0, 0, 1, 0, "", 0, 0F);
        }
    }

    /*
     * 单条矿脉定义。
     *
     * @param id 矿脉ID（配置/放置特征共用）
     * @param rarity 稀有度：平均每rarity个区块出现一条该矿脉（群峦语义）
     * @param minY 矿脉中心高度下限
     * @param maxY 矿脉中心高度上限
     * @param sizeMultiplier 矿种/矿床类型倍率；实际大小 = 基础区间 [BASE_SIZE_MIN..BASE_SIZE_MAX] × 倍率
     * @param density 成矿密度：椭球内每个方块被替换的概率（0~1）
     * @param primary 下层主矿矿物名
     * @param secondary 上层次矿矿物名
     * @param between 中间夹层伴生矿物名（可null）
     * @param sporadic 全域零星散布矿物名（可null）
     * @param allowedRocks 允许的宿主岩种名集合；空集表示不限岩种
     * @param indicator 地表/地下指示物配置（可为none()）
     */
    public record VeinDefinition(
            String id,
            int rarity,
            int minY,
            int maxY,
            int sizeMin,
            int sizeMax,
            float density,
            String primary,
            String secondary,
            @Nullable String between,
            @Nullable String sporadic,
            Set<String> allowedRocks,
            VeinIndicator indicator
    ) {
        /** 基础区间 × 矿种倍率：用 BASE_SIZE_MIN/MAX 推算 min/max，无indicator */
        public VeinDefinition(
                String id, int rarity, int minY, int maxY,
                float sizeMultiplier, float density,
                String primary, String secondary,
                @Nullable String between, @Nullable String sporadic,
                Set<String> allowedRocks) {
            this(id, rarity, minY, maxY,
                    Math.max(2, Math.round(BASE_SIZE_MIN * sizeMultiplier)),
                    Math.max(2, Math.round(BASE_SIZE_MAX * sizeMultiplier)),
                    density, primary, secondary, between, sporadic, allowedRocks, VeinIndicator.none());
        }

        /** 基础区间 × 矿种倍率 + indicator */
        public VeinDefinition(
                String id, int rarity, int minY, int maxY,
                float sizeMultiplier, float density,
                String primary, String secondary,
                @Nullable String between, @Nullable String sporadic,
                Set<String> allowedRocks, VeinIndicator indicator) {
            this(id, rarity, minY, maxY,
                    Math.max(2, Math.round(BASE_SIZE_MIN * sizeMultiplier)),
                    Math.max(2, Math.round(BASE_SIZE_MAX * sizeMultiplier)),
                    density, primary, secondary, between, sporadic, allowedRocks, indicator);
        }

        /** 显示/兼容用的名义大小（取区间中点） */
        public int size() { return (sizeMin + sizeMax) / 2; }
    }

    /** 基础矿脉半径区间（无倍率时的默认大小） */
    public static final int BASE_SIZE_MIN = 7;
    public static final int BASE_SIZE_MAX = 11;

    /*
     * 矿脉几何形态（格雷/群峦式矿床形态的简化）。
     * <ul>
     *   <li>ELLIPSOID：通用透镜状/囊状矿体（默认）</li>
     *   <li>LAYER：沉积/蒸发岩层状矿体，横向大、纵向薄</li>
     *   <li>PIPE：金伯利岩管/热液筒状矿体，纵向长、横向小</li>
     *   <li>DIKE：岩墙式矿脉，沿单一水平方向拉长</li>
     *   <li>DISSEMINATED：斑岩式浸染状矿体，大而稀疏</li>
     * </ul>
     */
    public enum VeinShape { ELLIPSOID, LAYER, PIPE, DIKE, DISSEMINATED }

    private static final Map<String, VeinShape> VEIN_SHAPES = Map.ofEntries(
            Map.entry("diamond", VeinShape.PIPE),
            Map.entry("emerald", VeinShape.PIPE),
            Map.entry("montane_cassiterite", VeinShape.PIPE),
            Map.entry("montane_native_silver", VeinShape.PIPE),
            Map.entry("bituminous_coal", VeinShape.LAYER),
            Map.entry("lignite", VeinShape.LAYER),
            Map.entry("gypsum", VeinShape.LAYER),
            Map.entry("saltpeter", VeinShape.LAYER),
            Map.entry("sylvite", VeinShape.LAYER),
            Map.entry("borax", VeinShape.LAYER),
            Map.entry("ruby_marble_belt", VeinShape.LAYER),
            Map.entry("garnet_dike", VeinShape.DIKE),
            Map.entry("copper_porphyry", VeinShape.DISSEMINATED),
            Map.entry("molybdenum", VeinShape.DISSEMINATED),
            Map.entry("uranium", VeinShape.DISSEMINATED)
    );

    /** 取矿脉形态；未配置回退标准椭球 */
    public static VeinShape shapeOf(String veinId) {
        return VEIN_SHAPES.getOrDefault(veinId, VeinShape.ELLIPSOID);
    }

    /** TFC project=true：沉积/层状矿脉投影到地表，矿体跟随地形起伏 */
    private static final Set<String> PROJECT_TO_SURFACE_IDS = Set.of(
            "bituminous_coal", "lignite", "salts", "ruby_marble_belt");

    /** TFC project_offset=true：投影矿脉加一个确定性随机水平偏移 */
    private static final Set<String> PROJECT_OFFSET_IDS = Set.of(
            "bituminous_coal", "lignite", "salts", "ruby_marble_belt");

    public static boolean projectToSurface(String id) {
        return PROJECT_TO_SURFACE_IDS.contains(id);
    }

    public static boolean projectOffset(String id) {
        return PROJECT_OFFSET_IDS.contains(id);
    }

    // ===== 宿主岩分组（群峦岩石名） =====
    /** 群峦沉积8岩 */
    private static final Set<String> SED8 = Set.of(
            "limestone", "shale", "chalk", "chert", "claystone", "conglomerate", "dolomite", "tuff");
    /** 群峦酸性火成4岩 */
    private static final Set<String> IGNEOUS4 = Set.of("andesite", "basalt", "dacite", "rhyolite");
    /** 群峦火成7岩 */
    private static final Set<String> IGNEOUS7 = Set.of(
            "andesite", "basalt", "dacite", "diorite", "gabbro", "granite", "rhyolite");
    /** 群峦变质6岩（黝铜矿带） */
    private static final Set<String> METAMORPHIC6 = Set.of(
            "gneiss", "marble", "phyllite", "quartzite", "schist", "slate");

    /*
     * 全部矿脉定义。
     * <p>
     * 前半=群峦矿脉照抄（id含群峦原名；rarity/min_y/max_y/宿主岩原样，
     * sizeMultiplier由群峦size折算倍率，density照抄），
     * 后半=格雷独有矿物的矿脉（宿主岩按真实矿床学，组成照抄格雷）。
     * </p>
     */
    public static final List<VeinDefinition> DEFINITIONS = List.of(
            // ==================== 群峦照抄：金属矿脉 ====================
            // 高山锡石脉（群峦montane_cassiterite：r2 y80~300 闪长/辉长/花岗岩）
            new VeinDefinition("montane_cassiterite", 2, 80, 300, 0.85F, 0.4F,
                    "cassiterite", "cassiterite", null, "tin",
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.surface("cassiterite", 64, 3, 0.12F)),
            // 辉长岩硅镁镍矿（群峦gabbro_garnierite：r20 y-80~0 纯辉长岩，大脉）
            new VeinDefinition("gabbro_garnierite", 20, -64, 16, 1.3F, 0.6F,
                    "garnierite", "garnierite", null, null,
                    Set.of("gabbro"), VeinIndicator.deep("garnierite", 7)),
            // 普通硅镁镍矿（群峦normal_garnierite：r25 y-80~0 深成岩）
            new VeinDefinition("normal_garnierite", 25, -48, 24, 0.85F, 0.3F,
                    "garnierite", "garnierite", null, "cobalt",
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.underground("garnierite", 5)),
            // 普通自然银（群峦normal_native_silver：r25 y-80~20 片麻/花岗岩带）
            // 比铋矿更浅，玩家Y-30附近开始遇到
            new VeinDefinition("normal_native_silver", 25, -30, 40, 1.15F, 0.6F,
                    "native_silver", "native_silver", null, "silver",
                    Set.of("diorite", "gneiss", "granite", "schist"), VeinIndicator.underground("native_silver", 5)),
            // 高山自然银（群峦montane_native_silver：r7 y90~280）
            new VeinDefinition("montane_native_silver", 7, 90, 280, 0.7F, 0.2F,
                    "native_silver", "native_silver", null, null,
                    Set.of("diorite", "granite"), VeinIndicator.surface("native_silver", 64, 3, 0.12F)),
            // 普通辉铋矿（群峦normal_bismuthinite：r45 y-80~20 深成岩，大脉）
            // 比闪锌矿更浅，玩家Y-40附近开始遇到
            new VeinDefinition("normal_bismuthinite", 45, -40, 30, 1.4F, 0.6F,
                    "bismuthinite", "bismuthinite", null, null,
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.underground("bismuthinite", 4)),
            // 地表辉铋矿（群峦surface_bismuthinite：r48 y40~100 沉积8岩）
            new VeinDefinition("surface_bismuthinite", 48, 40, 100, 0.85F, 0.3F,
                    "bismuthinite", "bismuthinite", null, null,
                    SED8, VeinIndicator.surface("bismuthinite", 72, 3, 0.12F)),
            // 高山辉铋矿（群峦montane_bismuthinite：r24 y100~220 沉积8岩）
            new VeinDefinition("montane_bismuthinite", 24, 100, 220, 0.85F, 0.3F,
                    "bismuthinite", "bismuthinite", null, null,
                    SED8, VeinIndicator.surface("bismuthinite", 64, 3, 0.12F)),
            // 普通闪锌矿（群峦normal_sphalerite：r45 y-80~20 深成岩，大脉）
            // 比铋矿更深，玩家Y-60附近开始遇到
            new VeinDefinition("normal_sphalerite", 45, -60, 10, 1.4F, 0.6F,
                    "sphalerite", "sphalerite", null, null,
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.underground("sphalerite", 5)),
            // 地表闪锌矿（群峦surface_sphalerite：r40 y40~100 酸性火成4岩）
            new VeinDefinition("surface_sphalerite", 40, 40, 100, 0.85F, 0.3F,
                    "sphalerite", "sphalerite", null, null,
                    IGNEOUS4, VeinIndicator.surface("sphalerite", 72, 3, 0.12F)),
            // 高山闪锌矿（群峦montane_sphalerite：r20 y100~220 酸性火成4岩）
            new VeinDefinition("montane_sphalerite", 20, 100, 220, 0.85F, 0.3F,
                    "sphalerite", "sphalerite", null, null,
                    IGNEOUS4, VeinIndicator.surface("sphalerite", 64, 3, 0.12F)),
            // 普通黝铜矿（群峦normal_tetrahedrite：r40 y-30~70 变质6岩）
            new VeinDefinition("normal_tetrahedrite", 40, -30, 70, 1.3F, 0.5F,
                    "tetrahedrite", "tetrahedrite", null, null,
                    METAMORPHIC6, VeinIndicator.underground("tetrahedrite", 4)),
            // 高山黝铜矿（群峦montane_tetrahedrite：r3 y90~270 变质6岩）
            new VeinDefinition("montane_tetrahedrite", 3, 90, 270, 0.85F, 0.25F,
                    "tetrahedrite", "tetrahedrite", null, null,
                    METAMORPHIC6, VeinIndicator.surface("tetrahedrite", 32, 3, 0.12F)),
            // 普通孔雀石（群峦normal_malachite：r45 y-30~70 碳酸盐岩）
            new VeinDefinition("normal_malachite", 45, -30, 70, 1.3F, 0.5F,
                    "malachite", "malachite", null, null,
                    Set.of("chalk", "dolomite", "limestone", "marble"), VeinIndicator.underground("malachite", 4)),
            // 地表孔雀石（群峦surface_malachite：r48 y40~100 碳酸盐岩）
            new VeinDefinition("surface_malachite", 48, 40, 100, 0.85F, 0.25F,
                    "malachite", "malachite", null, null,
                    Set.of("chalk", "dolomite", "limestone", "marble"), VeinIndicator.surface("malachite", 72, 3, 0.12F)),
            // 高山孔雀石（群峦montane_malachite：r11 y40~300 碳酸盐岩）
            new VeinDefinition("montane_malachite", 11, 40, 300, 0.85F, 0.25F,
                    "malachite", "malachite", null, null,
                    Set.of("chalk", "dolomite", "limestone", "marble"), VeinIndicator.surface("malachite", 64, 3, 0.12F)),
            // 地表自然铜（群峦surface_native_copper：r36 y40~100 酸性火成4岩）
            new VeinDefinition("surface_native_copper", 36, 40, 100, 0.85F, 0.25F,
                    "native_copper", "native_copper", null, null,
                    IGNEOUS4, VeinIndicator.surface("native_copper", 72, 3, 0.12F)),
            // 高山自然铜（群峦montane_native_copper：r16 y100~300 酸性火成4岩）
            new VeinDefinition("montane_native_copper", 16, 100, 300, 0.85F, 0.25F,
                    "native_copper", "native_copper", null, null,
                    IGNEOUS4, VeinIndicator.surface("native_copper", 64, 3, 0.12F)),
            // 普通自然金（群峦normal_native_gold：r90 y0~70 火成7岩）
            new VeinDefinition("normal_native_gold", 90, 0, 70, 0.85F, 0.25F,
                    "native_gold", "native_gold", null, "gold",
                    IGNEOUS7, VeinIndicator.underground("native_gold", 3)),
            // 富自然金（群峦rich_native_gold：r50 y-80~20 深成岩，大脉）
            new VeinDefinition("rich_native_gold", 50, -40, 20, 1.4F, 0.5F,
                    "native_gold", "native_gold", null, null,
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.deep("native_gold", 4)),
            // 地表赤铁矿（群峦surface_hematite：r45 y10~90 酸性火成4岩）
            new VeinDefinition("surface_hematite", 45, 10, 90, 0.85F, 0.4F,
                    "hematite", "hematite", null, null,
                    IGNEOUS4, VeinIndicator.surface("hematite", 72, 3, 0.12F)),
            // 高山赤铁矿（群峦montane_hematite：r25 y90~250 酸性火成4岩）
            new VeinDefinition("montane_hematite", 25, 90, 250, 0.85F, 0.4F,
                    "hematite", "hematite", null, null,
                    IGNEOUS4, VeinIndicator.surface("hematite", 56, 3, 0.12F)),
            // 地表磁铁矿（群峦surface_magnetite：r90 y10~90 沉积8岩）
            new VeinDefinition("surface_magnetite", 90, 10, 90, 0.85F, 0.4F,
                    "magnetite", "magnetite", null, null,
                    SED8, VeinIndicator.surface("magnetite", 72, 3, 0.12F)),
            // 高山磁铁矿（群峦montane_magnetite：r45 y90~250 沉积8岩）
            new VeinDefinition("montane_magnetite", 45, 90, 250, 0.85F, 0.4F,
                    "magnetite", "magnetite", null, null,
                    SED8, VeinIndicator.surface("magnetite", 56, 3, 0.12F)),
            // 地表褐铁矿（群峦surface_limonite：r90 y10~90 沉积8岩）
            new VeinDefinition("surface_limonite", 90, 10, 90, 0.85F, 0.4F,
                    "limonite", "limonite", null, null,
                    SED8, VeinIndicator.surface("limonite", 72, 3, 0.12F)),
            // 高山褐铁矿（群峦montane_limonite：r45 y90~250 沉积8岩）
            new VeinDefinition("montane_limonite", 45, 90, 250, 0.85F, 0.4F,
                    "limonite", "limonite", null, null,
                    SED8, VeinIndicator.surface("limonite", 56, 3, 0.12F)),

            // ==================== 格雷独有矿物：斑岩/热液金属矿脉 ====================
            // 斑岩铜矿：酸性火成岩（真实矿床学：斑岩铜矿+次生富集带；组成照抄格雷copper_vein）
            new VeinDefinition("copper_porphyry", 30, -24, 48, 1.4F, 0.35F,
                    "chalcopyrite", "bornite", "chalcocite", "copper",
                    Set.of("diorite", "granite", "andesite", "dacite"), VeinIndicator.underground("chalcopyrite", 4)),
            // 方铅矿脉：碳酸盐岩MVT型（格雷galena_vein：方铅/银/铅）
            new VeinDefinition("galena", 45, -16, 48, 1.3F, 0.5F,
                    "galena", "galena", "native_silver", "lead",
                    Set.of("limestone", "dolomite", "shale"), VeinIndicator.underground("galena", 4)),
            // 铝土矿：沉积岩风化壳（格雷bauxite脉；零星伴生铝元素矿）
            new VeinDefinition("bauxite", 40, 0, 80, 1.6F, 0.5F,
                    "bauxite", "bauxite", null, "aluminium",
                    Set.of("limestone", "claystone", "shale"), VeinIndicator.underground("bauxite", 4)),
            // 硫化镍矿：基性岩（格雷nickel脉：硅镁镍/镍黄铁/辉钴）
            new VeinDefinition("nickel_sulfide", 40, 0, 70, 1.15F, 0.25F,
                    "garnierite", "pentlandite", "cobaltite", "nickel",
                    Set.of("gabbro", "basalt"), VeinIndicator.underground("garnierite", 4)),
            // 铬铁矿：基性岩层状侵入体（布什维尔德型：铬铁/钛铁/磁铁）
            // 深层基性岩专属，集中在最底层
            new VeinDefinition("chromite", 30, -48, -20, 1.3F, 0.4F,
                    "chromite", "ilmenite", "magnetite", "iron",
                    Set.of("gabbro", "basalt"), VeinIndicator.deep("chromite", 6)),
            // 铂族矿：基性岩（格雷sheldonite脉：硫铂/铂/钯）
            // 比铬铁矿更深，超基性岩专属
            new VeinDefinition("pgm", 40, -64, -30, 1.15F, 0.25F,
                    "cooperite", "platinum", "palladium", "bornite",
                    Set.of("gabbro", "basalt"), VeinIndicator.deep("cooperite", 6)),
            // 花岗岩型钼矿：酸性火成岩（格雷molybdenum脉：辉钼/钼华/钼铅）
            new VeinDefinition("molybdenum", 20, -16, 32, 1.15F, 0.25F,
                    "molybdenite", "wulfenite", "powellite", "molybdenum",
                    Set.of("granite", "rhyolite"), VeinIndicator.underground("molybdenite", 5)),
            // 钨锡热液脉：花岗岩+片麻岩（格雷scheelite脉：白钨/钨酸锂；主矿沿用黑钨）
            new VeinDefinition("wolframite", 60, -40, 16, 1.15F, 0.35F,
                    "wolframite", "scheelite", null, "tungstate",
                    Set.of("granite", "gneiss"), VeinIndicator.underground("wolframite", 4)),
            // 锰矿脉：变质岩（格雷manganese脉：钙铝榴/锰铝榴/软锰/钽铁）
            new VeinDefinition("manganese", 20, -32, 24, 1.3F, 0.45F,
                    "grossular", "spessartine", "pyrolusite", "tantalite",
                    Set.of("schist", "quartzite", "gneiss"), VeinIndicator.underground("grossular", 5)),
            // 锑矿脉：花岗岩热液（辉锑矿+零星自然金）
            new VeinDefinition("stibnite", 40, -16, 32, 1.15F, 0.3F,
                    "stibnite", "stibnite", null, "native_gold",
                    Set.of("granite", "gneiss", "quartzite"), VeinIndicator.underground("stibnite", 4)),
            // 稀土矿脉：花岗岩+片麻岩（格雷bastnasite/monazite脉）
            new VeinDefinition("rare_earth", 40, -32, 16, 1.15F, 0.25F,
                    "bastnasite", "monazite", null, "neodymium",
                    Set.of("granite", "gneiss"), VeinIndicator.underground("bastnasite", 5)),
            // 铀矿脉：花岗岩/变质岩（格雷pitchblende脉：沥青铀/晶质铀/微量钚）
            // 最深层之一，比铬铁矿更深
            new VeinDefinition("uranium", 40, -56, -16, 1.15F, 0.3F,
                    "pitchblende", "uraninite", null, "plutonium_239",
                    Set.of("granite", "gneiss", "schist"), VeinIndicator.deep("pitchblende", 6)),
            // 铍矿脉：花岗岩伟晶岩（格雷beryllium脉：铍/钍/祖母绿）
            new VeinDefinition("beryllium", 30, -24, 32, 0.85F, 0.3F,
                    "beryllium", "thorium", null, "emerald",
                    Set.of("granite", "rhyolite"), VeinIndicator.underground("beryllium", 5)),

            // ==================== 群峦照抄：非金属矿脉 ====================
            // 石墨（群峦graphite：r20 y-30~60 变质4岩）
            new VeinDefinition("graphite", 20, -30, 60, 1.15F, 0.4F,
                    "graphite", "graphite", null, null,
                    Set.of("gneiss", "marble", "quartzite", "schist"), VeinIndicator.underground("graphite", 5)),
            // 普通朱砂（群峦normal_cinnabar：r14 y-70~10 变质4岩）
            new VeinDefinition("normal_cinnabar", 14, -48, 24, 0.85F, 0.6F,
                    "cinnabar", "cinnabar", null, null,
                    Set.of("gneiss", "phyllite", "quartzite", "schist"), VeinIndicator.underground("cinnabar", 4)),
            // 高山朱砂（群峦montane_cinnabar：r14 y120~280 变质4岩）
            new VeinDefinition("montane_cinnabar", 14, 120, 280, 0.7F, 0.6F,
                    "cinnabar", "cinnabar", null, null,
                    Set.of("gneiss", "phyllite", "quartzite", "schist"), VeinIndicator.surface("cinnabar", 64, 3, 0.12F)),
            // 深部硫磺（群峦sulfur：r4 y-64~-45 变质+深成岩盘状）
            // Y范围拉宽，避免集中在同一层
            new VeinDefinition("sulfur_deep", 4, -80, -40, 0.85F, 0.25F,
                    "sulfur", "sulfur", null, null,
                    Set.of("diorite", "gabbro", "gneiss", "granite", "marble",
                            "phyllite", "quartzite", "schist", "slate"), VeinIndicator.deep("sulfur", 6)),
            // 凝灰岩硫磺（群峦tuff_sulfur：r2 y40~200 纯凝灰岩）
            new VeinDefinition("tuff_sulfur", 2, 40, 200, 0.85F, 0.45F,
                    "sulfur", "sulfur", null, null,
                    Set.of("tuff"), VeinIndicator.surface("sulfur", 64, 3, 0.12F)),
            // 石膏（群峦gypsum：r70 y40~100 沉积8岩盘状）
            new VeinDefinition("gypsum", 70, 40, 100, 1.0F, 0.3F,
                    "gypsum", "gypsum", null, null,
                    SED8, VeinIndicator.surface("gypsum", 72, 3, 0.12F)),
            // 硼砂（群峦borax：r40 y40~100 粘土/石灰/页岩）
            new VeinDefinition("borax", 40, 40, 100, 1.0F, 0.2F,
                    "borax", "borax", null, null,
                    Set.of("claystone", "limestone", "shale"), VeinIndicator.surface("borax", 72, 3, 0.12F)),
            // 冰晶石（群峦cryolite：r16 y-70~-10 闪长/花岗岩）
            // 范围加宽，与TFC的-70~-10对齐
            new VeinDefinition("cryolite", 16, -70, -10, 0.85F, 0.7F,
                    "cryolite", "cryolite", null, null,
                    Set.of("diorite", "granite"), VeinIndicator.deep("cryolite", 6)),
            // 硝石（群峦saltpeter：r110 y40~100 沉积8岩）
            new VeinDefinition("saltpeter", 110, 40, 100, 1.15F, 0.4F,
                    "saltpeter", "saltpeter", null, null,
                    SED8, VeinIndicator.surface("saltpeter", 72, 3, 0.12F)),
            // 钾石盐+天然碱（群峦sylvite：r60 y40~100 蒸发岩）
            new VeinDefinition("sylvite", 60, 40, 100, 1.15F, 0.35F,
                    "sylvite", "sylvite", null, "trona",
                    Set.of("chert", "claystone", "shale"), VeinIndicator.surface("sylvite", 72, 3, 0.12F)),
            // 烟煤（群峦bituminous_coal：r210 y-35~-12 沉积8岩，大煤层）
            new VeinDefinition("bituminous_coal", 210, 0, 40, 1.4F, 0.9F,
                    "bituminous_coal", "bituminous_coal", null, null,
                    SED8, VeinIndicator.surface("bituminous_coal", 72, 3, 0.12F)),
            // 褐煤（群峦lignite：r160 y-20~-8 沉积8岩）
            new VeinDefinition("lignite", 160, 10, 50, 1.15F, 0.85F,
                    "lignite", "lignite", null, null,
                    SED8, VeinIndicator.surface("lignite", 72, 3, 0.12F)),
            // ==================== 群峦照抄：宝石矿脉 ====================
            // 青金石：碳酸盐岩（群峦lapis_lazuli + 格雷青金脉组成：蓝金/方钠/方解）
            new VeinDefinition("lapis_lazuli", 30, -20, 80, 1.3F, 0.12F,
                    "lapis_lazuli", "lazurite", "sodalite", "calcite",
                    Set.of("limestone", "marble"), VeinIndicator.underground("lapis_lazuli", 5)),
            // 紫水晶（群峦amethyst：r25 y40~60 沉积+部分变质岩盘状）
            new VeinDefinition("amethyst", 25, 40, 60, 0.7F, 0.2F,
                    "amethyst", "amethyst", null, null,
                    Set.of("chalk", "chert", "claystone", "conglomerate", "dolomite",
                            "gneiss", "limestone", "marble", "phyllite", "quartzite",
                            "schist", "shale", "slate", "tuff"), VeinIndicator.surface("amethyst", 72, 3, 0.12F)),
            // 蛋白石（群峦opal：r25 y40~60 火成+沉积岩盘状）
            new VeinDefinition("opal", 25, 40, 60, 0.7F, 0.2F,
                    "opal", "opal", null, null,
                    Set.of("andesite", "basalt", "chalk", "chert", "claystone",
                            "conglomerate", "dacite", "dolomite", "limestone",
                            "rhyolite", "shale", "tuff"), VeinIndicator.surface("opal", 72, 3, 0.12F)),
            // 钻石（群峦diamond：r30 管状，辉长岩金伯利岩管；零星橄榄石）
            new VeinDefinition("diamond", 30, -64, 100, 0.7F, 0.15F,
                    "diamond", "diamond", null, "olivine",
                    Set.of("gabbro"), VeinIndicator.deep("diamond", 4)),
            // 祖母绿（群峦emerald：r80 管状，深成3岩）
            new VeinDefinition("emerald", 80, -64, 100, 0.7F, 0.15F,
                    "emerald", "emerald", null, null,
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.deep("emerald", 5)),
            // 深部红宝石（群峦deep_ruby：r80 y-70~-10 大理岩）
            // 范围加宽，与TFC的-70~-10对齐
            new VeinDefinition("deep_ruby", 80, -70, -10, 1.15F, 0.2F,
                    "ruby", "ruby", null, null,
                    Set.of("marble"), VeinIndicator.deep("ruby", 5)),
            // 红宝石大理岩带（群峦ruby_marble_belt：r16 y-40~-4 大盘状，夹层方解石）
            // rarity调高避免产出过于密集
            new VeinDefinition("ruby_marble_belt", 25, -40, -4, 1.1F, 0.45F,
                    "ruby", "ruby", "calcite", null,
                    Set.of("andesite", "basalt", "dacite", "diorite", "gabbro", "gneiss",
                            "granite", "marble", "phyllite", "quartzite", "rhyolite",
                            "schist", "slate"), VeinIndicator.underground("ruby", 5)),
            // 黄铁矿"假金矿"（群峦fake_native_gold：r16 y-50~70 火成7岩）
            new VeinDefinition("pyrite", 16, -50, 70, 0.85F, 0.35F,
                    "pyrite", "pyrite", null, null,
                    IGNEOUS7, VeinIndicator.underground("pyrite", 5)),

            // ==================== 格雷独有矿物：宝石/非金属矿脉 ====================
            // 蓝宝石脉：变质岩（格雷sapphire脉：铁铝榴/蓝宝/钙铁榴/绿蓝宝）
            new VeinDefinition("sapphire", 60, -32, 16, 1.15F, 0.25F,
                    "almandine", "sapphire", "pyrope", "green_sapphire",
                    Set.of("gneiss", "schist", "phyllite", "marble"), VeinIndicator.underground("sapphire", 5)),
            // 石榴石岩脉：变质岩（红榴/黄榴/钙铁榴/蛋白石；紫水晶另有群峦晶洞脉）
            new VeinDefinition("garnet_dike", 40, -10, 50, 1.3F, 0.45F,
                    "red_garnet", "yellow_garnet", "andradite", "opal",
                    Set.of("schist", "gneiss", "marble"), VeinIndicator.underground("red_garnet", 4)),
            // 黄玉云英岩：酸性火成岩（格雷topaz脉：蓝黄玉/黄玉/石英岩）
            new VeinDefinition("topaz_greisen", 60, -16, 32, 0.85F, 0.25F,
                    "topaz", "blue_topaz", null, "quartzite",
                    Set.of("granite", "rhyolite", "dacite"), VeinIndicator.underground("topaz", 5)),
            // 磷灰石脉：碳酸盐岩（格雷apatite脉：磷灰/磷酸三钙/烧绿石）
            new VeinDefinition("apatite", 40, 10, 80, 1.15F, 0.25F,
                    "apatite", "tricalcium_phosphate", null, "pyrochlore",
                    Set.of("limestone", "marble", "claystone"), VeinIndicator.underground("apatite", 4)),
            // 盐矿脉：沉积岩蒸发岩（格雷salts脉：岩盐/盐/锂云母/锂辉石）
            new VeinDefinition("salts", 50, 30, 70, 1.3F, 0.2F,
                    "rock_salt", "salt", "spodumene", "lepidolite",
                    Set.of("shale", "claystone", "limestone", "chalk"), VeinIndicator.underground("rock_salt", 4)),
            // 云母脉：变质岩伟晶岩（格雷mica脉：蓝晶/云母/铝土/铯榴）
            new VeinDefinition("mica", 20, -24, 16, 1.15F, 0.25F,
                    "kyanite", "mica", "bauxite", "pollucite",
                    Set.of("gneiss", "schist", "phyllite", "quartzite"), VeinIndicator.underground("kyanite", 5)),
            // 矿砂脉：近岸沉积（格雷mineral_sand脉：玄武/花岗岩矿砂/漂白土/石膏）
            new VeinDefinition("mineral_sand", 80, 15, 60, 1.4F, 0.2F,
                    "basaltic_mineral_sand", "granitic_mineral_sand", "fullers_earth", "gypsum",
                    Set.of("conglomerate", "shale", "claystone", "chalk"), VeinIndicator.surface("basaltic_mineral_sand", 64, 3, 0.12F)),
            // 古砂矿：砾岩（格雷garnet_tin脉：砂锡/石榴石砂/石棉/硅藻土）
            new VeinDefinition("placer", 80, 30, 60, 1.3F, 0.4F,
                    "cassiterite_sand", "garnet_sand", "asbestos", "diatomite",
                    Set.of("conglomerate", "chert", "claystone"), VeinIndicator.surface("cassiterite_sand", 64, 3, 0.12F)),
            // 油砂：沉积岩（格雷oilsands脉）
            new VeinDefinition("oilsands", 40, 30, 80, 1.3F, 0.3F,
                    "oilsands", "oilsands", null, null,
                    Set.of("conglomerate", "shale", "claystone"), VeinIndicator.surface("oilsands", 64, 3, 0.12F)),
            // 红石脉：深部变质岩（格雷redstone脉：红石/红宝石/朱砂）
            new VeinDefinition("redstone", 60, -48, 0, 1.3F, 0.2F,
                    "redstone", "redstone", null, "cinnabar",
                    Set.of("schist", "gneiss", "phyllite", "slate"), VeinIndicator.deep("redstone", 5)),
            // 橄榄石脉：基性岩（格雷olivine脉：橄榄/菱镁/膨润/海绿石）
            new VeinDefinition("olivine", 20, -8, 32, 1.15F, 0.25F,
                    "olivine", "magnesite", "bentonite", "glauconite_sand",
                    Set.of("basalt", "gabbro"), VeinIndicator.underground("olivine", 5)),
            // 滑石脉：变质碳酸盐岩（滑石/皂石/菱镁）
            new VeinDefinition("talc_vein", 30, -16, 32, 1.15F, 0.3F,
                    "talc", "soapstone", null, "magnesite",
                    Set.of("marble", "schist", "phyllite", "dolomite"), VeinIndicator.underground("talc", 4)),
            // 沸石：玄武岩杏仁孔（沸石/方解石）
            new VeinDefinition("zeolite", 30, -16, 32, 0.85F, 0.3F,
                    "zeolite", "zeolite", null, "calcite",
                    Set.of("basalt", "andesite"), VeinIndicator.underground("zeolite", 4)),
            // 明矾石：火山蚀变岩（流纹/英安岩）
            new VeinDefinition("alunite", 40, 0, 64, 0.85F, 0.25F,
                    "alunite", "alunite", null, null,
                    Set.of("rhyolite", "dacite"), VeinIndicator.underground("alunite", 4)),
            // 雄黄：火山热液（雄黄/硫磺）
            new VeinDefinition("realgar", 30, -32, 32, 0.85F, 0.3F,
                    "realgar", "realgar", "sulfur", null,
                    Set.of("rhyolite", "dacite", "andesite"), VeinIndicator.underground("realgar", 4)),
            // 重晶石：碳酸盐岩MVT伴生（重晶/方解/零星方铅）
            new VeinDefinition("barite", 40, -16, 40, 1.15F, 0.25F,
                    "barite", "barite", "calcite", "galena",
                    Set.of("limestone", "dolomite", "shale"), VeinIndicator.underground("barite", 4)),
            // 针铁矿：褐铁矿风化带零星（并入地表褐铁矿脉无空位，独立小脉）
            new VeinDefinition("goethite", 60, 0, 64, 0.7F, 0.3F,
                    "goethite", "goethite", null, "vanadium_magnetite",
                    SED8, VeinIndicator.underground("goethite", 4)),
            // 钒磁铁矿：深部带状铁矿（磁铁矿+钒）
            new VeinDefinition("banded_iron", 40, -32, 24, 1.3F, 0.4F,
                    "vanadium_magnetite", "magnetite", "iron", "iron",
                    Set.of("gneiss", "quartzite", "schist"), VeinIndicator.deep("vanadium_magnetite", 5)),
            // 下界石英/赛特斯石英：本模组暂无下界矿脉，仅注册方块不生成
            // 钍/锂/钕/锡/铅等元素矿：作为伴生矿出现在上述各脉的sporadic/between槽位
            // 为保矿物表全量可生成，补一条深成稀有元素富集脉（花岗岩+变质岩）
            new VeinDefinition("rare_elements", 80, -48, -8, 0.85F, 0.25F,
                    "thorium", "lithium", "neodymium", "tin",
                    Set.of("granite", "gneiss", "schist", "quartzite"), VeinIndicator.deep("thorium", 5))
    );

    private ModVeins() {
    }

    public static List<VeinDefinition> getDefinitions() {
        return DEFINITIONS;
    }
}
