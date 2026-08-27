package com.mss.polymech.worldgen;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public final class ModVeins {

    public record VeinIndicator(int surfaceRarity, int depth, int undergroundRarity, int undergroundCount, String mineral) {
        public static VeinIndicator surface(String m, int r) { return new VeinIndicator(r, 35, 1, 0, m); }
        public static VeinIndicator deep(String m, int c) { return new VeinIndicator(0, 35, 1, c, m); }
        public static VeinIndicator underground(String m, int c) { return new VeinIndicator(0, 35, 1, c, m); }
        public static VeinIndicator none() { return new VeinIndicator(0, 0, 1, 0, ""); }
    }

    public record VeinDefinition(
            String id, int rarity, int minY, int maxY, int sizeMin, int sizeMax,
            float density, String primary, String secondary,
            @Nullable String between, @Nullable String sporadic,
            Set<String> allowedRocks, VeinIndicator indicator) {
        public VeinDefinition(String id, int rarity, int minY, int maxY, int size,
                float density, String p, String s, @Nullable String b, @Nullable String sp, Set<String> r) {
            this(id, rarity, minY, maxY, size, size, density, p, s, b, sp, r, VeinIndicator.none()); }
        public VeinDefinition(String id, int rarity, int minY, int maxY, float mul,
                float density, String p, String s, @Nullable String b, @Nullable String sp, Set<String> r) {
            this(id, rarity, minY, maxY, Math.max(2, Math.round(BASE_SIZE_MIN * mul)),
                    Math.max(2, Math.round(BASE_SIZE_MAX * mul)), density, p, s, b, sp, r, VeinIndicator.none()); }
        public VeinDefinition(String id, int rarity, int minY, int maxY, float mul,
                float density, String p, String s, @Nullable String b, @Nullable String sp, Set<String> r, VeinIndicator i) {
            this(id, rarity, minY, maxY, Math.max(2, Math.round(BASE_SIZE_MIN * mul)),
                    Math.max(2, Math.round(BASE_SIZE_MAX * mul)), density, p, s, b, sp, r, i); }
        public int size() { return (sizeMin + sizeMax) / 2; }
    }

    public static final int BASE_SIZE_MIN = 5;
    public static final int BASE_SIZE_MAX = 9;

    public enum VeinShape { ELLIPSOID, LAYER, PIPE, DIKE, DISSEMINATED }

    private static final Map<String, VeinShape> VEIN_SHAPES = Map.ofEntries(
            Map.entry("amethyst", VeinShape.LAYER),
            Map.entry("bituminous_coal", VeinShape.LAYER),
            Map.entry("borax", VeinShape.LAYER),
            Map.entry("cassiterite", VeinShape.PIPE),
            Map.entry("copper_porphyry", VeinShape.DISSEMINATED),
            Map.entry("diamond", VeinShape.PIPE),
            Map.entry("emerald", VeinShape.PIPE),
            Map.entry("garnet_dike", VeinShape.DIKE),
            Map.entry("gypsum", VeinShape.LAYER),
            Map.entry("lignite", VeinShape.LAYER),
            Map.entry("molybdenum", VeinShape.DISSEMINATED),
            Map.entry("native_silver", VeinShape.PIPE),
            Map.entry("opal", VeinShape.LAYER),
            Map.entry("ruby_marble_belt", VeinShape.LAYER),
            Map.entry("saltpeter", VeinShape.LAYER),
            Map.entry("sulfur_deep", VeinShape.LAYER),
            Map.entry("sylvite", VeinShape.LAYER),
            Map.entry("uranium", VeinShape.DISSEMINATED)
    );

    public static VeinShape shapeOf(String id) { return VEIN_SHAPES.getOrDefault(id, VeinShape.ELLIPSOID); }

    private static final Set<String> SED8 = Set.of("limestone", "shale", "chalk", "chert", "claystone", "conglomerate", "dolomite", "tuff");
    private static final Set<String> IGNEOUS4 = Set.of("andesite", "basalt", "dacite", "rhyolite");
    private static final Set<String> IGNEOUS7 = Set.of("andesite", "basalt", "dacite", "diorite", "gabbro", "granite", "rhyolite");
    private static final Set<String> METAMORPHIC6 = Set.of("gneiss", "marble", "phyllite", "quartzite", "schist", "slate");

    public static final List<VeinDefinition> DEFINITIONS = List.of(
            // cassiterite
            new VeinDefinition(
                    "cassiterite", 2, 80, 300, 0.85F, 0.4F,
                    "cassiterite", "cassiterite", null, "tin",
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.surface("cassiterite", 12)),
            // gabbro_garnierite
            new VeinDefinition(
                    "gabbro_garnierite", 20, -64, 16, 1.3F, 0.6F,
                    "garnierite", "garnierite", null, null,
                    Set.of("gabbro"), VeinIndicator.deep("garnierite", 7)),
            // garnierite
            new VeinDefinition(
                    "garnierite", 25, -48, 24, 0.85F, 0.3F,
                    "garnierite", "garnierite", null, "cobalt",
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.underground("garnierite", 5)),
            // native_silver
            new VeinDefinition(
                    "native_silver", 15, -40, 280, 1.15F, 0.4F,
                    "native_silver", "native_silver", null, "silver",
                    Set.of("diorite", "gneiss", "granite", "schist"), VeinIndicator.underground("native_silver", 9)),
            // bismuthinite
            new VeinDefinition(
                    "bismuthinite", 40, -48, 220, 1.15F, 0.4F,
                    "bismuthinite", "bismuthinite", null, null,
                    Set.of("chalk", "chert", "claystone", "conglomerate", "diorite", "dolomite", "gabbro", "granite", "limestone", "shale", "tuff"), VeinIndicator.underground("bismuthinite", 4)),
            // sphalerite
            new VeinDefinition(
                    "sphalerite", 40, -48, 220, 1.15F, 0.4F,
                    "sphalerite", "sphalerite", null, null,
                    Set.of("andesite", "basalt", "dacite", "diorite", "gabbro", "granite", "rhyolite"), VeinIndicator.underground("sphalerite", 5)),
            // tetrahedrite
            new VeinDefinition(
                    "tetrahedrite", 30, -30, 270, 1.15F, 0.4F,
                    "tetrahedrite", "tetrahedrite", null, null,
                    METAMORPHIC6, VeinIndicator.underground("tetrahedrite", 4)),
            // malachite
            new VeinDefinition(
                    "malachite", 35, -30, 100, 1.15F, 0.4F,
                    "malachite", "malachite", null, null,
                    Set.of("chalk", "dolomite", "limestone", "marble"), VeinIndicator.underground("malachite", 4)),
            // native_copper
            new VeinDefinition(
                    "native_copper", 20, 40, 300, 0.85F, 0.25F,
                    "native_copper", "native_copper", null, null,
                    IGNEOUS4, VeinIndicator.surface("native_copper", 14)),
            // native_gold
            new VeinDefinition(
                    "native_gold", 90, 0, 70, 0.85F, 0.25F,
                    "native_gold", "native_gold", null, "gold",
                    IGNEOUS7, VeinIndicator.underground("native_gold", 3)),
            // rich_native_gold
            new VeinDefinition(
                    "rich_native_gold", 50, -48, 24, 1.4F, 0.5F,
                    "native_gold", "native_gold", null, null,
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.deep("native_gold", 4)),
            // hematite
            new VeinDefinition(
                    "hematite", 35, 10, 250, 0.85F, 0.4F,
                    "hematite", "hematite", null, null,
                    IGNEOUS4, VeinIndicator.surface("hematite", 24)),
            // magnetite
            new VeinDefinition(
                    "magnetite", 50, 10, 250, 0.85F, 0.4F,
                    "magnetite", "magnetite", null, null,
                    SED8, VeinIndicator.surface("magnetite", 24)),
            // limonite
            new VeinDefinition(
                    "limonite", 50, 10, 250, 0.85F, 0.4F,
                    "limonite", "limonite", null, null,
                    SED8, VeinIndicator.surface("limonite", 24)),
            // copper_porphyry
            new VeinDefinition(
                    "copper_porphyry", 30, -24, 48, 1.4F, 0.35F,
                    "chalcopyrite", "bornite", "chalcocite", "copper",
                    Set.of("diorite", "granite", "andesite", "dacite"), VeinIndicator.underground("chalcopyrite", 4)),
            // galena
            new VeinDefinition(
                    "galena", 45, -16, 48, 1.3F, 0.5F,
                    "galena", "galena", "native_silver", "lead",
                    Set.of("limestone", "dolomite", "shale"), VeinIndicator.underground("galena", 4)),
            // bauxite
            new VeinDefinition(
                    "bauxite", 40, 0, 80, 1.6F, 0.5F,
                    "bauxite", "bauxite", null, "aluminium",
                    Set.of("limestone", "claystone", "shale"), VeinIndicator.underground("bauxite", 4)),
            // nickel_sulfide
            new VeinDefinition(
                    "nickel_sulfide", 40, 0, 70, 1.15F, 0.25F,
                    "garnierite", "pentlandite", "cobaltite", "nickel",
                    Set.of("gabbro", "basalt"), VeinIndicator.underground("garnierite", 4)),
            // chromite
            new VeinDefinition(
                    "chromite", 30, -48, -8, 1.3F, 0.4F,
                    "chromite", "ilmenite", "magnetite", "iron",
                    Set.of("gabbro", "basalt"), VeinIndicator.deep("chromite", 6)),
            // pgm
            new VeinDefinition(
                    "pgm", 40, -48, -10, 1.15F, 0.25F,
                    "cooperite", "platinum", "palladium", "bornite",
                    Set.of("gabbro", "basalt"), VeinIndicator.deep("cooperite", 6)),
            // molybdenum
            new VeinDefinition(
                    "molybdenum", 20, -16, 32, 1.15F, 0.25F,
                    "molybdenite", "wulfenite", "powellite", "molybdenum",
                    Set.of("granite", "rhyolite"), VeinIndicator.underground("molybdenite", 5)),
            // wolframite
            new VeinDefinition(
                    "wolframite", 60, -40, 16, 1.15F, 0.35F,
                    "wolframite", "scheelite", null, "tungstate",
                    Set.of("granite", "gneiss"), VeinIndicator.underground("wolframite", 4)),
            // manganese
            new VeinDefinition(
                    "manganese", 20, -32, 24, 1.3F, 0.45F,
                    "grossular", "spessartine", "pyrolusite", "tantalite",
                    Set.of("schist", "quartzite", "gneiss"), VeinIndicator.underground("grossular", 5)),
            // stibnite
            new VeinDefinition(
                    "stibnite", 40, -16, 32, 1.15F, 0.3F,
                    "stibnite", "stibnite", null, "native_gold",
                    Set.of("granite", "gneiss", "quartzite"), VeinIndicator.underground("stibnite", 4)),
            // rare_earth
            new VeinDefinition(
                    "rare_earth", 40, -32, 16, 1.15F, 0.25F,
                    "bastnasite", "monazite", null, "neodymium",
                    Set.of("granite", "gneiss"), VeinIndicator.underground("bastnasite", 5)),
            // uranium
            new VeinDefinition(
                    "uranium", 40, -48, -8, 1.15F, 0.3F,
                    "pitchblende", "uraninite", null, "plutonium_239",
                    Set.of("granite", "gneiss", "schist"), VeinIndicator.deep("pitchblende", 6)),
            // beryllium
            new VeinDefinition(
                    "beryllium", 30, -24, 32, 0.85F, 0.3F,
                    "beryllium", "thorium", null, "emerald",
                    Set.of("granite", "rhyolite"), VeinIndicator.underground("beryllium", 5)),
            // graphite
            new VeinDefinition(
                    "graphite", 20, -30, 60, 1.15F, 0.4F,
                    "graphite", "graphite", null, null,
                    Set.of("gneiss", "marble", "quartzite", "schist"), VeinIndicator.underground("graphite", 5)),
            // cinnabar
            new VeinDefinition(
                    "cinnabar", 14, -48, 280, 0.85F, 0.6F,
                    "cinnabar", "cinnabar", null, null,
                    Set.of("gneiss", "phyllite", "quartzite", "schist"), VeinIndicator.surface("cinnabar", 14)),
            // sulfur_deep
            new VeinDefinition(
                    "sulfur_deep", 4, -56, -40, 0.85F, 0.25F,
                    "sulfur", "sulfur", null, null,
                    Set.of("diorite", "gabbro", "gneiss", "granite", "marble", "phyllite", "quartzite", "schist", "slate"), VeinIndicator.deep("sulfur", 6)),
            // tuff_sulfur
            new VeinDefinition(
                    "tuff_sulfur", 2, 40, 200, 0.85F, 0.45F,
                    "sulfur", "sulfur", null, null,
                    Set.of("tuff"), VeinIndicator.surface("sulfur", 12)),
            // gypsum
            new VeinDefinition(
                    "gypsum", 70, 40, 100, 1.0F, 0.3F,
                    "gypsum", "gypsum", null, null,
                    SED8, VeinIndicator.surface("gypsum", 14)),
            // borax
            new VeinDefinition(
                    "borax", 40, 40, 100, 1.0F, 0.2F,
                    "borax", "borax", null, null,
                    Set.of("claystone", "limestone", "shale"), VeinIndicator.surface("borax", 14)),
            // cryolite
            new VeinDefinition(
                    "cryolite", 16, -48, 0, 0.85F, 0.7F,
                    "cryolite", "cryolite", null, null,
                    Set.of("diorite", "granite"), VeinIndicator.deep("cryolite", 6)),
            // saltpeter
            new VeinDefinition(
                    "saltpeter", 110, 40, 100, 1.15F, 0.4F,
                    "saltpeter", "saltpeter", null, null,
                    SED8, VeinIndicator.surface("saltpeter", 14)),
            // sylvite
            new VeinDefinition(
                    "sylvite", 60, 40, 100, 1.15F, 0.35F,
                    "sylvite", "sylvite", null, "trona",
                    Set.of("chert", "claystone", "shale"), VeinIndicator.surface("sylvite", 14)),
            // bituminous_coal
            new VeinDefinition(
                    "bituminous_coal", 210, 0, 40, 1.4F, 0.9F,
                    "bituminous_coal", "bituminous_coal", null, null,
                    SED8, VeinIndicator.surface("bituminous_coal", 14)),
            // lignite
            new VeinDefinition(
                    "lignite", 160, 10, 50, 1.15F, 0.85F,
                    "lignite", "lignite", null, null,
                    SED8, VeinIndicator.surface("lignite", 14)),
            // lapis_lazuli
            new VeinDefinition(
                    "lapis_lazuli", 30, -20, 80, 1.3F, 0.12F,
                    "lapis_lazuli", "lazurite", "sodalite", "calcite",
                    Set.of("limestone", "marble"), VeinIndicator.underground("lapis_lazuli", 5)),
            // amethyst
            new VeinDefinition(
                    "amethyst", 25, 40, 60, 0.7F, 0.2F,
                    "amethyst", "amethyst", null, null,
                    Set.of("chalk", "chert", "claystone", "conglomerate", "dolomite", "gneiss", "limestone", "marble", "phyllite", "quartzite", "schist", "shale", "slate", "tuff"), VeinIndicator.surface("amethyst", 14)),
            // opal
            new VeinDefinition(
                    "opal", 25, 40, 60, 0.7F, 0.2F,
                    "opal", "opal", null, null,
                    Set.of("andesite", "basalt", "chalk", "chert", "claystone", "conglomerate", "dacite", "dolomite", "limestone", "rhyolite", "shale", "tuff"), VeinIndicator.surface("opal", 14)),
            // diamond
            new VeinDefinition(
                    "diamond", 30, -64, 100, 0.7F, 0.15F,
                    "diamond", "diamond", null, "olivine",
                    Set.of("gabbro"), VeinIndicator.deep("diamond", 4)),
            // emerald
            new VeinDefinition(
                    "emerald", 80, -64, 100, 0.7F, 0.15F,
                    "emerald", "emerald", null, null,
                    Set.of("diorite", "gabbro", "granite"), VeinIndicator.deep("emerald", 5)),
            // deep_ruby
            new VeinDefinition(
                    "deep_ruby", 80, -48, -8, 1.15F, 0.2F,
                    "ruby", "ruby", null, null,
                    Set.of("marble"), VeinIndicator.deep("ruby", 5)),
            // ruby_marble_belt
            new VeinDefinition(
                    "ruby_marble_belt", 16, -32, 4, 1.4F, 0.45F,
                    "ruby", "ruby", "calcite", null,
                    Set.of("andesite", "basalt", "dacite", "diorite", "gabbro", "gneiss", "granite", "marble", "phyllite", "quartzite", "rhyolite", "schist", "slate"), VeinIndicator.underground("ruby", 5)),
            // pyrite
            new VeinDefinition(
                    "pyrite", 16, -50, 70, 0.85F, 0.35F,
                    "pyrite", "pyrite", null, null,
                    IGNEOUS7, VeinIndicator.underground("pyrite", 5)),
            // sapphire
            new VeinDefinition(
                    "sapphire", 60, -32, 16, 1.15F, 0.25F,
                    "almandine", "sapphire", "pyrope", "green_sapphire",
                    Set.of("gneiss", "schist", "phyllite", "marble"), VeinIndicator.underground("sapphire", 5)),
            // garnet_dike
            new VeinDefinition(
                    "garnet_dike", 40, -10, 50, 1.3F, 0.45F,
                    "red_garnet", "yellow_garnet", "andradite", "opal",
                    Set.of("schist", "gneiss", "marble"), VeinIndicator.underground("red_garnet", 4)),
            // topaz_greisen
            new VeinDefinition(
                    "topaz_greisen", 60, -16, 32, 0.85F, 0.25F,
                    "topaz", "blue_topaz", null, "quartzite",
                    Set.of("granite", "rhyolite", "dacite"), VeinIndicator.underground("topaz", 5)),
            // apatite
            new VeinDefinition(
                    "apatite", 40, 10, 80, 1.15F, 0.25F,
                    "apatite", "tricalcium_phosphate", null, "pyrochlore",
                    Set.of("limestone", "marble", "claystone"), VeinIndicator.underground("apatite", 4)),
            // salts
            new VeinDefinition(
                    "salts", 50, 30, 70, 1.3F, 0.2F,
                    "rock_salt", "salt", "spodumene", "lepidolite",
                    Set.of("shale", "claystone", "limestone", "chalk"), VeinIndicator.underground("rock_salt", 4)),
            // mica
            new VeinDefinition(
                    "mica", 20, -24, 16, 1.15F, 0.25F,
                    "kyanite", "mica", "bauxite", "pollucite",
                    Set.of("gneiss", "schist", "phyllite", "quartzite"), VeinIndicator.underground("kyanite", 5)),
            // mineral_sand
            new VeinDefinition(
                    "mineral_sand", 80, 15, 60, 1.4F, 0.2F,
                    "basaltic_mineral_sand", "granitic_mineral_sand", "fullers_earth", "gypsum",
                    Set.of("conglomerate", "shale", "claystone", "chalk"), VeinIndicator.surface("basaltic_mineral_sand", 20)),
            // placer
            new VeinDefinition(
                    "placer", 80, 30, 60, 1.3F, 0.4F,
                    "cassiterite_sand", "garnet_sand", "asbestos", "diatomite",
                    Set.of("conglomerate", "chert", "claystone"), VeinIndicator.surface("cassiterite_sand", 20)),
            // oilsands
            new VeinDefinition(
                    "oilsands", 40, 30, 80, 1.3F, 0.3F,
                    "oilsands", "oilsands", null, null,
                    Set.of("conglomerate", "shale", "claystone"), VeinIndicator.surface("oilsands", 20)),
            // redstone
            new VeinDefinition(
                    "redstone", 60, -48, 0, 1.3F, 0.2F,
                    "redstone", "redstone", null, "cinnabar",
                    Set.of("schist", "gneiss", "phyllite", "slate"), VeinIndicator.deep("redstone", 5)),
            // olivine
            new VeinDefinition(
                    "olivine", 20, -8, 32, 1.15F, 0.25F,
                    "olivine", "magnesite", "bentonite", "glauconite_sand",
                    Set.of("basalt", "gabbro"), VeinIndicator.underground("olivine", 5)),
            // talc_zeolite
            new VeinDefinition(
                    "talc_zeolite", 25, -16, 32, 1.15F, 0.3F,
                    "talc", "zeolite", "calcite", "magnesite",
                    Set.of("marble", "schist", "phyllite", "dolomite", "basalt", "andesite"), VeinIndicator.underground("talc", 4)),
            // alunite
            new VeinDefinition(
                    "alunite", 40, 0, 64, 0.85F, 0.25F,
                    "alunite", "alunite", null, null,
                    Set.of("rhyolite", "dacite"), VeinIndicator.underground("alunite", 4)),
            // realgar
            new VeinDefinition(
                    "realgar", 30, -32, 32, 0.85F, 0.3F,
                    "realgar", "realgar", "sulfur", null,
                    Set.of("rhyolite", "dacite", "andesite"), VeinIndicator.underground("realgar", 4)),
            // barite
            new VeinDefinition(
                    "barite", 40, -16, 40, 1.15F, 0.25F,
                    "barite", "barite", "calcite", "galena",
                    Set.of("limestone", "dolomite", "shale"), VeinIndicator.underground("barite", 4)),
            // goethite
            new VeinDefinition(
                    "goethite", 60, 0, 64, 0.7F, 0.3F,
                    "goethite", "goethite", null, "vanadium_magnetite",
                    SED8, VeinIndicator.underground("goethite", 4)),
            // banded_iron
            new VeinDefinition(
                    "banded_iron", 40, -32, 24, 1.3F, 0.4F,
                    "vanadium_magnetite", "magnetite", "iron", "iron",
                    Set.of("gneiss", "quartzite", "schist"), VeinIndicator.deep("vanadium_magnetite", 5))
    );

    private ModVeins() {}
    public static List<VeinDefinition> getDefinitions() { return DEFINITIONS; }
}