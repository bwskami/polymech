package com.mss.polymech;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.client.model.conveyor.ConveyorModelLoader;
import com.mss.polymech.client.model.pipe.PipeModelLoader;
import com.mss.polymech.client.renderer.ConveyorItemRenderer;
import com.mss.polymech.client.renderer.MachineGeoRenderer;
import com.mss.polymech.entity.ModEntities;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.machine.common.MachineRegistry;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.model.DynamicFluidContainerModel;
import net.neoforged.neoforge.client.event.InputEvent;

@Mod(value = Polymech.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class PolymechClient {
    public PolymechClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(com.mss.polymech.client.BlueprintInputHandler.BLUEPRINT_CANCEL_KEY);
        event.register(com.mss.polymech.client.BlueprintInputHandler.BLUEPRINT_CYCLE_MODE_KEY);
        event.register(com.mss.polymech.client.BlueprintInputHandler.BLUEPRINT_CYCLE_AXIS_KEY);
    }

    @SubscribeEvent
    static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(PipeModelLoader.ID, PipeModelLoader.INSTANCE);
        event.register(ConveyorModelLoader.ID, ConveyorModelLoader.INSTANCE);
    }

    /*
     * 注册流体单元（四种规格）的流体层颜色。
     * <p>
     * NeoForge的fluid_container模型不会自动染色，需要注册
     * DynamicFluidContainerModel.Colors：tintIndex==1时返回所含流体的tint颜色。
     * </p>
     */
    @SubscribeEvent
    static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(new DynamicFluidContainerModel.Colors(),
                ModItems.ALL_FLUID_CELLS.stream().map(def -> (net.minecraft.world.item.Item) def.get())
                        .toArray(net.minecraft.world.item.Item[]::new));
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.CONVEYOR_ITEM.get(), ConveyorItemRenderer::new);

        // 自动注册所有大型机器的方块实体渲染器（从 MachineRegistry 遍历）
        for (MachineRegistry.MachineEntry entry : MachineRegistry.getEntries()) {
            var beSupplier = entry.mainBlockEntity();
            if (beSupplier == null) continue;

            ResourceLocation modelPath, texturePath, animationPath;
            if ("filling_unit".equals(entry.id())) {
                // FillingUnit 不使用 block/ 子目录约定
                modelPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/filling_unit.geo.json");
                texturePath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/filling_unit.png");
                animationPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/filling_unit.animation.json");
            } else if ("gas_turbine_generator".equals(entry.id())) {
                // 纹理不在 block/ 子目录
                modelPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/gas_turbine_generator.geo.json");
                texturePath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gas_turbine_generator/gas_turbine_generator.png");
                animationPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/gas_turbine_generator.animation.json");
            } else if ("steam_duplex_mineral_jig".equals(entry.id())) {
                // 纹理不在 block/ 子目录
                modelPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/steam_duplex_mineral_jig.geo.json");
                texturePath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/steam_duplex_mineral_jig/steam_duplex_mineral_jig.png");
                animationPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/steam_duplex_mineral_jig.animation.json");
            } else {
                String id = entry.id();
                modelPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/" + id + ".geo.json");
                texturePath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/" + id + "/" + id + ".png");
                animationPath = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/" + id + ".animation.json");
            }

            event.registerBlockEntityRenderer(
                    (net.minecraft.world.level.block.entity.BlockEntityType) beSupplier.get(),
                    ctx -> new MachineGeoRenderer<>(ctx, modelPath, texturePath, animationPath));
        }
    }
}
