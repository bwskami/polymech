package com.mss.polymech;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.client.gui.FluidTankScreen;
import com.mss.polymech.client.model.conveyor.ConveyorModelLoader;
import com.mss.polymech.client.model.pipe.PipeModelLoader;
import com.mss.polymech.client.renderer.ConveyorItemRenderer;
import com.mss.polymech.entity.ModEntities;
import com.mss.polymech.menu.ModMenuTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

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
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.FLUID_TANK_MENU.get(), FluidTankScreen::new);
    }

    @SubscribeEvent
    static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(PipeModelLoader.ID, PipeModelLoader.INSTANCE);
        event.register(ConveyorModelLoader.ID, ConveyorModelLoader.INSTANCE);
    }

    @SubscribeEvent
    static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.CONVEYOR_ITEM.get(), ConveyorItemRenderer::new);
    }
}
