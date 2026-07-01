package com.mss.polymech.event;

import com.mss.polymech.Polymech;
import com.mss.polymech.texture_data.ColorConfigLoader;
import com.mss.polymech.item.ModItems;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ColorConfigLoader::load);
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register((stack, tintIndex) -> {
            Item item = stack.getItem();
            Integer[] colors = ColorConfigLoader.getColors(item);
            if (colors != null && tintIndex >= 0 && tintIndex < colors.length) {
                return colors[tintIndex] != null ? colors[tintIndex] : 0xFFFFFFFF;
            }
            return 0xFFFFFFFF;
        }, ModItems.ITEMS.getEntries().stream()
                .map(entry -> entry.get())
                .toArray(Item[]::new));
    }

}