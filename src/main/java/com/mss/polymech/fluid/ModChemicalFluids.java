package com.mss.polymech.fluid;

import com.mss.polymech.Polymech;
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
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 化学物质流体注册中心。
 * <p>
 * 参考GregTech Modern的做法：流体只注册FluidType与Fluid本体（source/flowing），
 * <b>不注册对应的流体方块</b>（{@link BaseFlowingFluid.Properties}不调用block(...)），
 * 因此这些化学液体无法像原版水那样被桶倒出放置在世界中，但仍可正常参与
 * 储罐、管道、流体单元等所有基于FluidStack的交互，并拥有各自的桶物品作为物品形态。
 * </p>
 */
public class ModChemicalFluids {

    /** 流体贴图：黑白水模板（16x512动画，原版water_still转灰度，ping-pong帧序），tint染色是乘法混合，贴图必须黑白才能正确显色。
     *  流体不可放置（无方块形态），flow贴图永远不会被渲染，still/flowing共用同一模板 */
    private static final ResourceLocation FLUID_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/material_sets/fluid/fluid_water");

    /** 化学流体类型延迟注册器 */
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES, Polymech.MOD_ID);

    /** 化学流体延迟注册器 */
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, Polymech.MOD_ID);

    /** 化学流体桶延迟注册器 */
    public static final DeferredRegister.Items BUCKETS =
            DeferredRegister.createItems(Polymech.MOD_ID);

    private static final Map<ChemicalFluid, Entry> ENTRIES = new EnumMap<>(ChemicalFluid.class);

    static {
        for (ChemicalFluid chem : ChemicalFluid.values()) {
            Entry entry = new Entry(chem);
            ENTRIES.put(chem, entry);

            entry.type = FLUID_TYPES.register(chem.getId(), () -> createFluidType(chem));
            entry.source = FLUIDS.register(chem.getId(), () -> new BaseFlowingFluid.Source(entry.properties()));
            entry.flowing = FLUIDS.register(chem.getId() + "_flowing",
                    () -> new BaseFlowingFluid.Flowing(entry.properties()));
            // 所有化学流体都注册桶物品（气体/等离子体也可用桶盛装；流体无方块形态，
            // 所以桶只是物品形态，不会在世界中放置流体方块）
            entry.bucket = BUCKETS.register(chem.getId() + "_bucket",
                    () -> new ChemicalBucketItem(entry.source.get(),
                            new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1), chem));
        }
    }

    /** 单个化学流体的注册条目（各holder在静态初始化中回填） */
    private static final class Entry {
        final ChemicalFluid chem;
        DeferredHolder<FluidType, FluidType> type;
        DeferredHolder<Fluid, FlowingFluid> source;
        DeferredHolder<Fluid, FlowingFluid> flowing;
        DeferredItem<ChemicalBucketItem> bucket;
        @Nullable
        private BaseFlowingFluid.Properties properties;

        Entry(ChemicalFluid chem) {
            this.chem = chem;
        }

        /**
         * 流体属性（惰性构建，注册触发时所有holder均已就绪）。
         * 注意：不调用block(...)，即流体无方块形态、不可放置。
         */
        BaseFlowingFluid.Properties properties() {
            if (properties == null) {
                properties = new BaseFlowingFluid.Properties(type, source, flowing)
                        .slopeFindDistance(2)
                        .levelDecreasePerBlock(2);
                // 气体/等离子体无桶，不设置bucket
                if (bucket != null) {
                    properties.bucket(bucket);
                }
            }
            return properties;
        }
    }

    /** 创建化学流体的FluidType：物理参数来自定义，客户端按流体颜色对通用黑白贴图染色 */
    private static FluidType createFluidType(ChemicalFluid chem) {
        // 数据驱动：根据密度数值自动判断。气体密度小于空气(ChemicalFluid.AIR)时
        // 在Forge中取负密度，使其isLighterThanAir()为true并倒置桶模型；
        // 密度大于等于空气的气体保持正密度，桶不翻转。
        int density = chem.getState() == ChemicalFluid.State.GAS
                        && chem.getDensity() < ChemicalFluid.AIR.getDensity()
                ? -Math.max(1, chem.getDensity())
                : chem.getDensity();
        return new FluidType(FluidType.Properties.create()
                .descriptionId("fluid." + Polymech.MOD_ID + "." + chem.getId())
                .temperature(chem.getTemperature())
                .density(density)
                .viscosity(chem.getViscosity())) {
            @SuppressWarnings("removal")
            @Override
            public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                consumer.accept(new IClientFluidTypeExtensions() {
                    // 贴图必须指向贴图集（blocks atlas）中真实存在的资源，否则
                    // DynamicFluidContainerModel渲染流体单元时会因getSprite返回null而崩溃
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
                        return chem.getColor();
                    }
                });
            }
        };
    }

    // ========== 查询接口 ==========

    /** 获取指定化学物质的源流体 */
    public static Fluid getSource(ChemicalFluid chem) {
        return ENTRIES.get(chem).source.get();
    }

    /** 获取指定化学物质的桶物品；气体/等离子体无桶，返回空气 */
    public static Item getBucket(ChemicalFluid chem) {
        DeferredItem<ChemicalBucketItem> bucket = ENTRIES.get(chem).bucket;
        return bucket == null ? Items.AIR : bucket.get();
    }

    /** Fluid→化学物质 查找表（注册完成后惰性构建） */
    @Nullable
    private static volatile Map<Fluid, ChemicalFluid> fluidLookup;

    /**
     * 根据流体实例（source或flowing）反查化学物质定义；非化学流体返回null。
     */
    @Nullable
    public static ChemicalFluid byFluid(Fluid fluid) {
        Map<Fluid, ChemicalFluid> lookup = fluidLookup;
        if (lookup == null) {
            lookup = new IdentityHashMap<>();
            for (Entry entry : ENTRIES.values()) {
                lookup.put(entry.source.get(), entry.chem);
                lookup.put(entry.flowing.get(), entry.chem);
            }
            fluidLookup = lookup;
        }
        return lookup.get(fluid);
    }

    // ========== 注册入口 ==========

    /**
     * 将所有化学流体相关内容注册到事件总线。
     */
    public static void register(IEventBus modEventBus) {
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        BUCKETS.register(modEventBus);
    }
}
