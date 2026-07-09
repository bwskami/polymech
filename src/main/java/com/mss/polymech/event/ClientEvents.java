package com.mss.polymech.event;

import com.mss.polymech.Polymech;
import com.mss.polymech.texture_data.ColorConfigLoader;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.block.ModBlocks;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
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
        ColorConfigLoader.load();
        
        for (var pipe : ModBlocks.PIPE_BLOCKS) {
            ItemBlockRenderTypes.setRenderLayer(pipe.get(), RenderType.translucent());
        }
        
        Polymech.LOGGER.info("Pipe render layers configured to TRANSLUCENT!");
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        java.util.Set<Item> itemsToRegister = new java.util.HashSet<>();
        
        for (var entry : ModItems.ITEMS.getEntries()) {
            itemsToRegister.add(entry.get());
        }
        
        for (var pipe : ModBlocks.PIPE_BLOCKS) {
            itemsToRegister.add(pipe.get().asItem());
        }
        
        itemsToRegister.remove(Items.AIR);
        
        event.register((stack, tintIndex) -> {
            Item item = stack.getItem();
            Integer[] colors = ColorConfigLoader.getColors(item);
            
            if (colors == null && item instanceof BlockItem blockItem) {
                colors = ColorConfigLoader.getColors(blockItem.getBlock());
            }
            
            if (colors != null && tintIndex >= 0 && tintIndex < colors.length) {
                return colors[tintIndex] != null ? colors[tintIndex] : 0xFFFFFFFF;
            }
            return 0xFFFFFFFF;
        }, itemsToRegister.toArray(new Item[0]));
    }

    @SubscribeEvent
    public static void registerBlockColors(RegisterColorHandlersEvent.Block event) {
        ColorConfigLoader.load();
        Block[] pipeBlocks = ModBlocks.PIPE_BLOCKS.stream()
                .map(holder -> holder.get())
                .toArray(Block[]::new);
        
        event.register((state, level, pos, tintIndex) -> {
            Block block = state.getBlock();
            Integer[] colors = ColorConfigLoader.getColors(block);
            if (colors != null && tintIndex >= 0 && tintIndex < colors.length) {
                return colors[tintIndex] != null ? colors[tintIndex] : 0xFFFFFFFF;
            }
            return 0xFFFFFFFF;
        }, pipeBlocks);
    }
}