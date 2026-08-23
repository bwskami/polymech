package com.mss.polymech.fluid;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.texture_data.MaterialColorConfig;
import com.mss.polymech.item.ChemicalBucketItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 元素类流体注册中心：熔融金属 + 全118元素等离子体。
 * <p>
 * 与{@link ModChemicalFluids}相同的注册模式：流体只注册FluidType与Fluid本体，
 * <b>不注册流体方块</b>（不可放置）；熔融金属为液体故注册桶，等离子体无桶。
 * </p>
 * <ul>
 *   <li>熔融金属：{@link MaterialRegistry}中每种材料一条（id: molten_{材料名}），
 *       温度≈熔点，颜色=金属锭底色（从colors.json读取），便于在流体单元中区分。</li>
 *   <li>等离子体：{@link ModElements}全118元素各一条（id: {元素名}_plasma），
 *       与熔融金属区分：亮度更高、饱和度更低（金属=底色大幅降饱和+提亮，非金属=低饱和黄金分割色相）。</li>
 * </ul>
 */
public class ModElementFluids {

    /** 流体贴图：黑白岩浆模板（16x320动画，原版lava_still转灰度，ping-pong帧序），tint染色是乘法混合，贴图必须黑白才能正确显色。
     *  流体不可放置（无方块形态），flow贴图永远不会被渲染，still/flowing共用同一模板 */
    private static final ResourceLocation FLUID_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/material_sets/fluid/fluid_molten");

    /** 熔融金属熔点（开尔文，近似真实值） */
    private static final Map<String, Integer> MELTING_POINTS = Map.ofEntries(
            Map.entry("steel", 1650),
            Map.entry("aluminium", 933),
            Map.entry("nickel", 1728),
            Map.entry("tin", 505),
            Map.entry("zinc", 693),
            Map.entry("silver", 1235),
            Map.entry("lead", 601),
            Map.entry("iron", 1811),
            Map.entry("copper", 1358),
            Map.entry("gold", 1337),
            Map.entry("sulfur", 388),
            Map.entry("graphite", 3823),
            Map.entry("saltpeter", 607),
            Map.entry("sylvite", 1043),
            Map.entry("salt", 1074),
            Map.entry("cinnabar", 853),
            Map.entry("cryolite", 1289),
            Map.entry("borax", 1013),
            Map.entry("chromium", 2180),
            Map.entry("titanium", 1941),
            Map.entry("tungsten", 3695),
            Map.entry("platinum", 2041),
            Map.entry("osmium", 3306),
            Map.entry("iridium", 2719),
            Map.entry("palladium", 1828),
            Map.entry("cobalt", 1768),
            Map.entry("manganese", 1519),
            Map.entry("molybdenum", 2896),
            Map.entry("silicon", 1687),
            Map.entry("bismuth", 544),
            Map.entry("antimony", 904),
            Map.entry("gallium", 303),
            Map.entry("indium", 430),
            Map.entry("tantalum", 3290),
            Map.entry("niobium", 2750),
            Map.entry("vanadium", 2183),
            Map.entry("neodymium", 1297),
            Map.entry("beryllium", 1560),
            Map.entry("europium", 1099),
            Map.entry("samarium", 1345),
            Map.entry("yttrium", 1799),
            Map.entry("rhodium", 2237),
            Map.entry("ruthenium", 2607),
            Map.entry("thorium", 2023),
            Map.entry("uranium", 1405),
            Map.entry("plutonium", 913),
            Map.entry("brass", 1173),
            Map.entry("bronze", 1223),
            Map.entry("invar", 1700),
            Map.entry("cupronickel", 1515),
            Map.entry("stainless_steel", 1683),
            Map.entry("electrum", 1285),
            Map.entry("lithium", 454),
            Map.entry("sodium", 371),
            Map.entry("potassium", 337),
            Map.entry("rubidium", 312),
            Map.entry("caesium", 302),
            Map.entry("francium", 300),
            Map.entry("magnesium", 923),
            Map.entry("calcium", 1115),
            Map.entry("strontium", 1050),
            Map.entry("barium", 1000),
            Map.entry("radium", 973),
            Map.entry("scandium", 1814),
            Map.entry("hafnium", 2506),
            Map.entry("zirconium", 2128),
            Map.entry("rhenium", 3459),
            Map.entry("cadmium", 594),
            Map.entry("lanthanum", 1193),
            Map.entry("cerium", 1068),
            Map.entry("praseodymium", 1208),
            Map.entry("promethium", 1315),
            Map.entry("gadolinium", 1585),
            Map.entry("terbium", 1629),
            Map.entry("dysprosium", 1680),
            Map.entry("holmium", 1734),
            Map.entry("erbium", 1802),
            Map.entry("thulium", 1818),
            Map.entry("ytterbium", 1097),
            Map.entry("lutetium", 1925),
            Map.entry("actinium", 1323),
            Map.entry("protactinium", 1841),
            Map.entry("neptunium", 917),
            Map.entry("americium", 1449)
    );

    /** 放射性材料（熔融态视为危险物质） */
    private static final java.util.Set<String> RADIOACTIVE_MATERIALS = java.util.Set.of(
            "thorium", "uranium", "plutonium", "neptunium", "americium", "promethium", "radium", "francium"
    );

    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES, Polymech.MOD_ID);

    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, Polymech.MOD_ID);

    public static final DeferredRegister.Items BUCKETS =
            DeferredRegister.createItems(Polymech.MOD_ID);

    /** 所有元素流体定义（熔融金属在前，等离子体在后） */
    private static final List<ElementFluid> DEFINITIONS = new ArrayList<>();

    private static final List<Entry> ENTRIES = new ArrayList<>();

    /*
     * 无熔融流体的材料：受热分解的硅酸盐/粘土类工业矿物
     * （石膏/方解石/重晶石/云母/滑石/沸石等没有真实熔融态，不注册熔融流体）
     */
    private static final java.util.Set<String> SKIP_MOLTEN = java.util.Set.of(
            "gypsum", "calcite", "barite", "asbestos", "mica", "talc",
            "kyanite", "diatomite", "bentonite", "fullers_earth",
            "zeolite", "phosphate");

    static {
        // 1. 熔融金属：每种材料一条（温度=熔点，颜色=金属锭底色）
        for (String material : MaterialRegistry.getMaterialNames()) {
            if (SKIP_MOLTEN.contains(material)) continue;
            int color = MaterialColorConfig.getBaseColor(material, 0xB0B0B0);
            int meltingPoint = MELTING_POINTS.getOrDefault(material, 1200);
            boolean hazardous = RADIOACTIVE_MATERIALS.contains(material);
            String formula = MaterialRegistry.getFormula(material);
            DEFINITIONS.add(new ElementFluid("molten_" + material, formula == null ? material : formula,
                    color, meltingPoint, 8000, 4000, ChemicalFluid.State.LIQUID, hazardous, material));
        }

        // 2. 等离子体：周期表全118元素各一条（亮度更高、饱和度更低，与熔融金属区分）
        for (ModElements element : ModElements.values()) {
            DEFINITIONS.add(new ElementFluid(element.getId() + "_plasma", element.getSymbol(),
                    plasmaColor(element), 10000, 200, 100,
                    ChemicalFluid.State.PLASMA, element.isRadioactive(), null));
        }

        // 3. 批量注册（与ModChemicalFluids相同模式：无方块、液体才有桶）
        for (ElementFluid def : DEFINITIONS) {
            Entry entry = new Entry(def);
            ENTRIES.add(entry);

            entry.type = FLUID_TYPES.register(def.getId(), () -> createFluidType(def));
            entry.source = FLUIDS.register(def.getId(), () -> new BaseFlowingFluid.Source(entry.properties()));
            entry.flowing = FLUIDS.register(def.getId() + "_flowing",
                    () -> new BaseFlowingFluid.Flowing(entry.properties()));
            if (def.isLiquid()) {
                entry.bucket = BUCKETS.register(def.getId() + "_bucket",
                        () -> new ChemicalBucketItem(entry.source.get(),
                                new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));
            }
        }
    }

    /** 单个元素流体的注册条目（各holder在静态初始化中回填） */
    private static final class Entry {
        final ElementFluid def;
        DeferredHolder<FluidType, FluidType> type;
        DeferredHolder<Fluid, FlowingFluid> source;
        DeferredHolder<Fluid, FlowingFluid> flowing;
        DeferredItem<ChemicalBucketItem> bucket;
        @Nullable
        private BaseFlowingFluid.Properties properties;

        Entry(ElementFluid def) {
            this.def = def;
        }

        /** 流体属性（惰性构建）；不调用block(...)，流体无方块形态、不可放置 */
        BaseFlowingFluid.Properties properties() {
            if (properties == null) {
                properties = new BaseFlowingFluid.Properties(type, source, flowing)
                        .slopeFindDistance(2)
                        .levelDecreasePerBlock(2);
                if (bucket != null) {
                    properties.bucket(bucket);
                }
            }
            return properties;
        }
    }

    /** 创建元素流体的FluidType：客户端按流体颜色对通用岩浆贴图染色。
     *  lightLevel>0时NeoForge的DynamicFluidContainerModel会对流体层做全亮emissive渲染（与原版岩浆桶同效果） */
    private static FluidType createFluidType(ElementFluid def) {
        return new FluidType(FluidType.Properties.create()
                .descriptionId("fluid." + Polymech.MOD_ID + "." + def.getId())
                .temperature(def.getTemperature())
                .density(def.getDensity())
                .viscosity(def.getViscosity())
                // 熔融金属与等离子体均为高温发光流体，暗处持有时流体层自发光
                .lightLevel(def.isLiquid() ? 15 : 13)) {
            @SuppressWarnings("removal")
            @Override
            public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                consumer.accept(new IClientFluidTypeExtensions() {
                    @Override
                    public ResourceLocation getStillTexture() {
                        return FLUID_TEXTURE;
                    }

                    @Override
                    public ResourceLocation getFlowingTexture() {
                        return FLUID_TEXTURE;
                    }

                    @Override
                    public int getTintColor() {
                        return def.getColor();
                    }
                });
            }
        };
    }

    /** 等离子体颜色（与熔融金属区分：亮度更高、饱和度更低）：
     *  金属元素=金属底色大幅降饱和+提亮；非金属=低饱和黄金分割色相（仍互不相同） */
    private static int plasmaColor(ModElements element) {
        Integer base = MaterialColorConfig.getBaseColorOrNull(element.getId());
        if (base != null) {
            float[] hsb = java.awt.Color.RGBtoHSB((base >> 16) & 0xFF, (base >> 8) & 0xFF, base & 0xFF, null);
            // 饱和度降至30%，亮度大幅提亮（x1.3再加0.1，封顶1.0），呈现高温电离态的白热感
            return 0xFF000000 | (java.awt.Color.HSBtoRGB(hsb[0], hsb[1] * 0.3f,
                    Math.min(1f, hsb[2] * 1.3f + 0.1f)) & 0x00FFFFFF);
        }
        float hue = (float) ((element.ordinal() * 0.6180339887) % 1.0);
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.3f, 1.0f) & 0x00FFFFFF);
    }

    // ========== 查询接口 ==========

    /** 所有元素流体定义（熔融金属+等离子体） */
    public static List<ElementFluid> getDefinitions() {
        return DEFINITIONS;
    }

    /** 获取指定元素流体的桶物品；等离子体无桶，返回空气 */
    public static Item getBucket(ElementFluid def) {
        for (Entry entry : ENTRIES) {
            if (entry.def == def) {
                return entry.bucket == null ? Items.AIR : entry.bucket.get();
            }
        }
        return Items.AIR;
    }

    /** Fluid→元素流体 查找表（注册完成后惰性构建） */
    @Nullable
    private static volatile Map<Fluid, ElementFluid> fluidLookup;

    /** 根据流体实例（source或flowing）反查元素流体定义；非元素流体返回null */
    @Nullable
    public static ElementFluid byFluid(Fluid fluid) {
        Map<Fluid, ElementFluid> lookup = fluidLookup;
        if (lookup == null) {
            lookup = new IdentityHashMap<>();
            for (Entry entry : ENTRIES) {
                lookup.put(entry.source.get(), entry.def);
                lookup.put(entry.flowing.get(), entry.def);
            }
            fluidLookup = lookup;
        }
        return lookup.get(fluid);
    }

    // ========== 注册入口 ==========

    public static void register(IEventBus modEventBus) {
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        BUCKETS.register(modEventBus);
    }
}
