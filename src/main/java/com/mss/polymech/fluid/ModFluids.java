package com.mss.polymech.fluid;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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

    // ========== 蒸汽流体 ==========

    /** 蒸汽流体类型（气态，比水轻） */
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

    /** 蒸汽源流体 */
    public static final Supplier<FlowingFluid> STEAM_SOURCE =
            FLUIDS.register("steam_source", () -> new BaseFlowingFluid.Source(ModFluids.STEAM_PROPERTIES));

    /** 蒸汽流动流体 */
    public static final Supplier<FlowingFluid> STEAM_FLOWING =
            FLUIDS.register("steam_flowing", () -> new BaseFlowingFluid.Flowing(ModFluids.STEAM_PROPERTIES));

    /** 蒸汽方块 */
    public static final DeferredBlock<LiquidBlock> STEAM_BLOCK =
            FLUID_BLOCKS.register("steam", () -> new LiquidBlock(STEAM_SOURCE.get(),
                    BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).noLootTable()));

    /** 蒸汽桶 */
    public static final DeferredItem<BucketItem> STEAM_BUCKET =
            FLUID_BUCKET_ITEMS.register("steam_bucket", () -> new BucketItem(STEAM_SOURCE.get(),
                    new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

    /** 蒸汽流体属性 */
    public static final BaseFlowingFluid.Properties STEAM_PROPERTIES = new BaseFlowingFluid.Properties(
            STEAM_FLUID_TYPE, STEAM_SOURCE, STEAM_FLOWING)
            .bucket(STEAM_BUCKET)
            .block(STEAM_BLOCK)
            .slopeFindDistance(2)
            .levelDecreasePerBlock(1);

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
