package com.mss.polymech;

import com.mss.polymech.client.model.conveyor.ConveyorModelLoader;
import com.mss.polymech.client.model.pipe.PipeModelLoader;
import com.mss.polymech.client.renderer.ConveyorItemRenderer;
import com.mss.polymech.entity.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
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
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        // 注册蓝图工具的快捷键
        com.mss.polymech.client.BlueprintInputHandler.OPEN_MULTIBLOCK_MENU_KEY.getKey();
        event.register(com.mss.polymech.client.BlueprintInputHandler.OPEN_MULTIBLOCK_MENU_KEY);
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
