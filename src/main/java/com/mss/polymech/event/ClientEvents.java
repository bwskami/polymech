package com.mss.polymech.event;

import com.mss.polymech.Polymech;
import com.mss.polymech.texture_data.ColorConfigLoader;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.block.ModBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 先加载颜色配置
        ColorConfigLoader.load();
        
        // 使用 translucent 支持半透明混合（而不是 cutoutMipped）
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.SMALL_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BIG_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.HUGE_PIPE.get(), RenderType.translucent());
        
        Polymech.LOGGER.info("Pipe render layers configured to TRANSLUCENT!");
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

    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        ColorConfigLoader.load();
        event.register((state, level, pos, tintIndex) -> {
            Block block = state.getBlock();
            Integer[] colors = ColorConfigLoader.getColors(block);
            if (colors != null && tintIndex >= 0 && tintIndex < colors.length) {
                return colors[tintIndex] != null ? colors[tintIndex] : 0xFFFFFFFF;
            }
            return 0xFFFFFFFF;
        }, ModBlocks.PIPE.get(), ModBlocks.SMALL_PIPE.get(), ModBlocks.BIG_PIPE.get(), ModBlocks.HUGE_PIPE.get());
    }
}