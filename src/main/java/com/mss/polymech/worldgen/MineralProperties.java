package com.mss.polymech.worldgen;

import javax.annotation.Nullable;
import java.util.Map;

/*
 * 真实矿物学属性：莫氏硬度、密度、晶系、成因类型。
 * <p>
 * 当前矿物系统已有化学式，但缺少区分矿物的物理/地质属性。
 * 本表作为数据驱动补充，未列出的矿物使用{@link #DEFAULT}兜底，
 * 避免因为某个矿物漏配而让 tooltip 或生成逻辑崩溃。
 * </p>
 */
public final class MineralProperties {

    public enum CrystalSystem {
        CUBIC,
        TETRAGONAL,
        HEXAGONAL,
        ORTHORHOMBIC,
        MONOCLINIC,
        TRICLINIC,
        AMORPHOUS,
        UNKNOWN
    }

    public enum OreGenesis {
        MAGMATIC,
        HYDROTHERMAL,
        SEDIMENTARY,
        METAMORPHIC,
        WEATHERING,
        PLACER,
        EVAPORITE,
        VOLCANIC_HYDROTHERMAL
    }

    public record MineralData(
            double mohs,
            double densityGcm3,
            CrystalSystem crystalSystem,
            OreGenesis genesis
    ) {
    }

    public static final MineralData DEFAULT = new MineralData(6.0, 4.0, CrystalSystem.UNKNOWN, OreGenesis.HYDROTHERMAL);

    private static final Map<String, MineralData> PROPERTIES = Map.ofEntries(
            // 铜矿物
            Map.entry("native_copper", new MineralData(3.0, 8.94, CrystalSystem.CUBIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("chalcopyrite", new MineralData(3.5, 4.2, CrystalSystem.TETRAGONAL, OreGenesis.HYDROTHERMAL)),
            Map.entry("bornite", new MineralData(3.0, 5.1, CrystalSystem.ORTHORHOMBIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("chalcocite", new MineralData(2.5, 5.8, CrystalSystem.ORTHORHOMBIC, OreGenesis.SEDIMENTARY)),
            Map.entry("malachite", new MineralData(3.5, 4.0, CrystalSystem.MONOCLINIC, OreGenesis.WEATHERING)),
            Map.entry("tetrahedrite", new MineralData(3.5, 4.6, CrystalSystem.CUBIC, OreGenesis.HYDROTHERMAL)),
            // 铁矿物
            Map.entry("hematite", new MineralData(5.5, 5.3, CrystalSystem.HEXAGONAL, OreGenesis.SEDIMENTARY)),
            Map.entry("magnetite", new MineralData(6.0, 5.2, CrystalSystem.CUBIC, OreGenesis.MAGMATIC)),
            Map.entry("limonite", new MineralData(4.0, 3.8, CrystalSystem.AMORPHOUS, OreGenesis.WEATHERING)),
            Map.entry("goethite", new MineralData(5.0, 3.8, CrystalSystem.ORTHORHOMBIC, OreGenesis.WEATHERING)),
            // 锡/铅/锌
            Map.entry("cassiterite", new MineralData(6.5, 7.0, CrystalSystem.TETRAGONAL, OreGenesis.HYDROTHERMAL)),
            Map.entry("sphalerite", new MineralData(3.5, 4.0, CrystalSystem.CUBIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("galena", new MineralData(2.5, 7.6, CrystalSystem.CUBIC, OreGenesis.SEDIMENTARY)),
            // 贵金属
            Map.entry("native_silver", new MineralData(2.5, 10.5, CrystalSystem.CUBIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("native_gold", new MineralData(2.5, 19.3, CrystalSystem.CUBIC, OreGenesis.HYDROTHERMAL)),
            // 有色金属
            Map.entry("bauxite", new MineralData(3.0, 2.5, CrystalSystem.AMORPHOUS, OreGenesis.WEATHERING)),
            Map.entry("wolframite", new MineralData(5.5, 7.2, CrystalSystem.MONOCLINIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("scheelite", new MineralData(4.5, 6.1, CrystalSystem.TETRAGONAL, OreGenesis.HYDROTHERMAL)),
            Map.entry("stibnite", new MineralData(2.0, 4.6, CrystalSystem.ORTHORHOMBIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("molybdenite", new MineralData(1.5, 4.7, CrystalSystem.HEXAGONAL, OreGenesis.HYDROTHERMAL)),
            Map.entry("chromite", new MineralData(5.5, 4.8, CrystalSystem.CUBIC, OreGenesis.MAGMATIC)),
            Map.entry("ilmenite", new MineralData(6.0, 4.7, CrystalSystem.HEXAGONAL, OreGenesis.MAGMATIC)),
            Map.entry("pyrolusite", new MineralData(6.0, 4.7, CrystalSystem.TETRAGONAL, OreGenesis.SEDIMENTARY)),
            Map.entry("pentlandite", new MineralData(3.5, 5.0, CrystalSystem.CUBIC, OreGenesis.MAGMATIC)),
            Map.entry("garnierite", new MineralData(4.0, 2.7, CrystalSystem.AMORPHOUS, OreGenesis.WEATHERING)),
            // 宝石
            Map.entry("diamond", new MineralData(10.0, 3.5, CrystalSystem.CUBIC, OreGenesis.MAGMATIC)),
            Map.entry("ruby", new MineralData(9.0, 4.0, CrystalSystem.HEXAGONAL, OreGenesis.METAMORPHIC)),
            Map.entry("sapphire", new MineralData(9.0, 4.0, CrystalSystem.HEXAGONAL, OreGenesis.METAMORPHIC)),
            Map.entry("emerald", new MineralData(7.5, 2.7, CrystalSystem.HEXAGONAL, OreGenesis.HYDROTHERMAL)),
            Map.entry("amethyst", new MineralData(7.0, 2.65, CrystalSystem.HEXAGONAL, OreGenesis.HYDROTHERMAL)),
            Map.entry("quartzite", new MineralData(7.0, 2.65, CrystalSystem.HEXAGONAL, OreGenesis.METAMORPHIC)),
            Map.entry("topaz", new MineralData(8.0, 3.5, CrystalSystem.ORTHORHOMBIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("opal", new MineralData(5.5, 2.1, CrystalSystem.AMORPHOUS, OreGenesis.SEDIMENTARY)),
            Map.entry("lapis_lazuli", new MineralData(5.5, 2.7, CrystalSystem.CUBIC, OreGenesis.METAMORPHIC)),
            Map.entry("garnet", new MineralData(7.0, 3.8, CrystalSystem.CUBIC, OreGenesis.METAMORPHIC)),
            // 非金属工业矿物
            Map.entry("sulfur", new MineralData(2.0, 2.1, CrystalSystem.ORTHORHOMBIC, OreGenesis.EVAPORITE)),
            Map.entry("graphite", new MineralData(1.5, 2.2, CrystalSystem.HEXAGONAL, OreGenesis.METAMORPHIC)),
            Map.entry("salt", new MineralData(2.5, 2.2, CrystalSystem.CUBIC, OreGenesis.EVAPORITE)),
            Map.entry("rock_salt", new MineralData(2.5, 2.2, CrystalSystem.CUBIC, OreGenesis.EVAPORITE)),
            Map.entry("gypsum", new MineralData(2.0, 2.3, CrystalSystem.MONOCLINIC, OreGenesis.EVAPORITE)),
            Map.entry("calcite", new MineralData(3.0, 2.7, CrystalSystem.HEXAGONAL, OreGenesis.SEDIMENTARY)),
            Map.entry("barite", new MineralData(3.5, 4.5, CrystalSystem.ORTHORHOMBIC, OreGenesis.SEDIMENTARY)),
            Map.entry("cinnabar", new MineralData(2.5, 8.1, CrystalSystem.HEXAGONAL, OreGenesis.HYDROTHERMAL)),
            Map.entry("cryolite", new MineralData(4.0, 2.95, CrystalSystem.MONOCLINIC, OreGenesis.MAGMATIC)),
            Map.entry("borax", new MineralData(2.5, 1.7, CrystalSystem.MONOCLINIC, OreGenesis.EVAPORITE)),
            Map.entry("kyanite", new MineralData(6.5, 3.6, CrystalSystem.TRICLINIC, OreGenesis.METAMORPHIC)),
            Map.entry("mica", new MineralData(2.5, 2.8, CrystalSystem.MONOCLINIC, OreGenesis.METAMORPHIC)),
            Map.entry("talc", new MineralData(1.0, 2.7, CrystalSystem.MONOCLINIC, OreGenesis.METAMORPHIC)),
            Map.entry("zeolite", new MineralData(4.0, 2.2, CrystalSystem.MONOCLINIC, OreGenesis.VOLCANIC_HYDROTHERMAL)),
            Map.entry("diatomite", new MineralData(1.5, 2.0, CrystalSystem.AMORPHOUS, OreGenesis.SEDIMENTARY)),
            Map.entry("pyrite", new MineralData(6.0, 5.0, CrystalSystem.CUBIC, OreGenesis.HYDROTHERMAL)),
            Map.entry("olivine", new MineralData(6.5, 3.3, CrystalSystem.ORTHORHOMBIC, OreGenesis.MAGMATIC)),
            // 煤/能源
            Map.entry("bituminous_coal", new MineralData(2.5, 1.3, CrystalSystem.AMORPHOUS, OreGenesis.SEDIMENTARY)),
            Map.entry("lignite", new MineralData(2.0, 1.2, CrystalSystem.AMORPHOUS, OreGenesis.SEDIMENTARY))
    );

    private static final Map<String, String> PROCESS_ROUTES = Map.ofEntries(
            Map.entry("bauxite", "Bayer process -> Hall-Heroult electrolysis"),
            Map.entry("ilmenite", "Chlorination -> Kroll process"),
            Map.entry("wolframite", "Alkali fusion -> APT -> reduction"),
            Map.entry("scheelite", "Alkali fusion -> APT -> reduction"),
            Map.entry("pitchblende", "Leaching -> solvent extraction -> reduction"),
            Map.entry("uraninite", "Leaching -> solvent extraction -> reduction"),
            Map.entry("chalcopyrite", "Roasting -> smelting -> electrorefining"),
            Map.entry("bornite", "Roasting -> smelting -> electrorefining"),
            Map.entry("galena", "Sintering -> blast furnace -> refining"),
            Map.entry("sphalerite", "Roasting -> leaching -> electrowinning"),
            Map.entry("native_gold", "Gravity/cyanidation -> smelting"),
            Map.entry("monazite", "Alkali fusion -> solvent extraction separation"),
            Map.entry("bastnasite", "Acid/alkali cracking -> solvent extraction separation"),
            Map.entry("quartzite", "Carbothermal reduction (Si)"),
            Map.entry("nether_quartz", "Carbothermal reduction (Si)"),
            Map.entry("cassiterite", "Gravity separation -> smelting -> refining"),
            Map.entry("chromite", "Smelting -> ferrochromium"),
            Map.entry("molybdenite", "Roasting -> sublimation -> reduction")
    );

    /** 现实冶金/选矿路线简述；未登记时回退通用物理选矿+熔炼 */
    public static String processRoute(String mineralName) {
        return PROCESS_ROUTES.getOrDefault(mineralName, "Crushing -> Jigging -> Smelting/Refining");
    }

    private MineralProperties() {
    }


    public static MineralData get(String mineralName) {
        MineralData properties = PROPERTIES.get(mineralName);
        return properties != null ? properties : DEFAULT;
    }

    @Nullable
    public static MineralData getOrNull(String mineralName) {
        return PROPERTIES.get(mineralName);
    }
}
