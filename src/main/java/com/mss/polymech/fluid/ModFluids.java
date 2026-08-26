package com.mss.polymech.fluid;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import com.mss.polymech.item.ChemicalBucketItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.function.Consumer;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * 模组流体注册中心。
 */
public class ModFluids {

    /** NeoForge 流体类型延迟注册器 */
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.FLUID_TYPES, Polymech.MOD_ID);

    /** 流体延迟注册器 */
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, Polymech.MOD_ID);

    /** 流体方块延迟注册器 */
    public static final DeferredRegister.Blocks FLUID_BLOCKS =
            DeferredRegister.createBlocks(Polymech.MOD_ID);

    /** 流体桶物品延迟注册器 */
    public static final DeferredRegister.Items FLUID_BUCKET_ITEMS =
            DeferredRegister.createItems(Polymech.MOD_ID);

    // ========== 蒸汽（假流体）：保留 FluidStack，但无方块、无桶 ==========

    /** 蒸汽流体类型（气态，比空气轻） */
    public static final DeferredHolder<FluidType, FluidType> STEAM_FLUID_TYPE =
            FLUID_TYPES.register("steam", () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid.poly_mech.steam")
                    .density(-100)        // 比空气轻
                    .viscosity(200)       // 低粘度
                    .temperature(373)     // 100°C = 373K
                    .canSwim(false)
                    .supportsBoating(false)
                    .motionScale(0.002)   // 飘浮效果
            ) {
                @SuppressWarnings("removal")
                @Override
                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                    consumer.accept(new IClientFluidTypeExtensions() {
                        private static final ResourceLocation STEAM_STILL =
                                ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/steam_still");
                        private static final ResourceLocation STEAM_FLOW =
                                ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/steam_flow");

                        @Override
                        public ResourceLocation getStillTexture() {
                            return STEAM_STILL;
                        }

                        @Override
                        public ResourceLocation getFlowingTexture() {
                            return STEAM_FLOW;
                        }
                    });
                }
            });

    /** 蒸汽源流体（仅内部 FluidStack，不可放置） */
    public static final Supplier<FlowingFluid> STEAM_SOURCE =
            FLUIDS.register("steam_source", () -> new BaseFlowingFluid.Source(ModFluids.STEAM_PROPERTIES));

    /** 蒸汽流动流体（仅内部 FluidStack，不可放置） */
    public static final Supplier<FlowingFluid> STEAM_FLOWING =
            FLUIDS.register("steam_flowing", () -> new BaseFlowingFluid.Flowing(ModFluids.STEAM_PROPERTIES));

    /** 蒸汽桶（假流体桶）：可盛放蒸汽但不产生/放置世界流体方块，与化学流体桶一致 */
    public static final DeferredItem<ChemicalBucketItem> STEAM_BUCKET =
            FLUID_BUCKET_ITEMS.register("steam_bucket", () -> new ChemicalBucketItem(STEAM_SOURCE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    /** 蒸汽流体属性：不注册block，保留桶物品；蒸汽仍为不可放置的假流体 */
    public static final BaseFlowingFluid.Properties STEAM_PROPERTIES = new BaseFlowingFluid.Properties(
            STEAM_FLUID_TYPE, STEAM_SOURCE, STEAM_FLOWING)
            .bucket(STEAM_BUCKET)
            .slopeFindDistance(2)
            .levelDecreasePerBlock(2);

    /** 蒸汽的tooltip元数据 */
    public static final FluidInfo STEAM_INFO = new FluidInfo() {
        @Override public String getFormula() { return "H2O"; }
        @Override public ChemicalFluid.State getState() { return ChemicalFluid.State.GAS; }
        @Override public int getTemperature() { return 373; }
        @Override public boolean isHazardous() { return true; }
    };

    // ========== 石油（真流体）：有方块、有桶、可野外生成 ==========

    /** 石油流体类型（液态，比水轻而比空气重） */
    public static final DeferredHolder<FluidType, FluidType> OIL_FLUID_TYPE =
            FLUID_TYPES.register("petroleum", () -> new FluidType(FluidType.Properties.create()
                    .descriptionId("fluid.poly_mech.petroleum")
                    .density(850)
                    .viscosity(3000)
                    .temperature(300)
                    .canSwim(false)
                    .supportsBoating(false)
                    .motionScale(0.001)
            ) {
                @SuppressWarnings("removal")
                @Override
                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                    consumer.accept(new IClientFluidTypeExtensions() {
                        private static final ResourceLocation OIL_STILL =
                                ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/petroleum_still");
                        private static final ResourceLocation OIL_FLOW =
                                ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "block/petroleum_flow");

                        @Override
                        public ResourceLocation getStillTexture() {
                            return OIL_STILL;
                        }

                        @Override
                        public ResourceLocation getFlowingTexture() {
                            return OIL_FLOW;
                        }
                    });
                }
            });

    /** 石油源流体 */
    public static final Supplier<FlowingFluid> OIL_SOURCE =
            FLUIDS.register("petroleum_source", () -> new BaseFlowingFluid.Source(ModFluids.OIL_PROPERTIES));

    /** 石油流动流体 */
    public static final Supplier<FlowingFluid> OIL_FLOWING =
            FLUIDS.register("petroleum_flowing", () -> new BaseFlowingFluid.Flowing(ModFluids.OIL_PROPERTIES));

    /** 石油方块（真流体，可放置/生成） */
    public static final DeferredBlock<LiquidBlock> OIL_BLOCK =
            FLUID_BLOCKS.register("petroleum", () -> new LiquidBlock(OIL_SOURCE.get(),
                    BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).noLootTable()));

    /** 石油桶 */
    public static final DeferredItem<BucketItem> OIL_BUCKET =
            FLUID_BUCKET_ITEMS.register("petroleum_bucket", () -> new BucketItem(OIL_SOURCE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    /** 石油流体属性：真实流体，带桶和方块 */
    public static final BaseFlowingFluid.Properties OIL_PROPERTIES = new BaseFlowingFluid.Properties(
            OIL_FLUID_TYPE, OIL_SOURCE, OIL_FLOWING)
            .bucket(OIL_BUCKET)
            .block(OIL_BLOCK)
            .slopeFindDistance(3)
            .levelDecreasePerBlock(2);

    /** 石油的tooltip元数据 */
    public static final FluidInfo PETROLEUM_INFO = new FluidInfo() {
        @Override public String getFormula() { return "C16H34"; }
        @Override public ChemicalFluid.State getState() { return ChemicalFluid.State.LIQUID; }
        @Override public int getTemperature() { return 295; }
        @Override public boolean isHazardous() { return true; }
    };

    // ========== 注册入口 ==========

    /**
     * 将所有流体相关内容注册到事件总线。
     */
    public static void register(IEventBus modEventBus) {
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        FLUID_BLOCKS.register(modEventBus);
        FLUID_BUCKET_ITEMS.register(modEventBus);
    }
}
