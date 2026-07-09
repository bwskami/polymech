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
        // 先加载颜色配置
        ColorConfigLoader.load();
        
        // 使用 translucent 支持半透明混合（而不是 cutoutMipped）
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.SMALL_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BIG_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.HUGE_PIPE.get(), RenderType.translucent());
        
        // 青铜管道
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRONZE_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRONZE_SMALL_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRONZE_BIG_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRONZE_HUGE_PIPE.get(), RenderType.translucent());
        
        // 不锈钢管道
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.STAINLESS_STEEL_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.STAINLESS_STEEL_SMALL_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.STAINLESS_STEEL_BIG_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.STAINLESS_STEEL_HUGE_PIPE.get(), RenderType.translucent());
        
        // 黄铜管道
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRASS_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRASS_SMALL_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRASS_BIG_PIPE.get(), RenderType.translucent());
        ItemBlockRenderTypes.setRenderLayer(ModBlocks.BRASS_HUGE_PIPE.get(), RenderType.translucent());
        
        Polymech.LOGGER.info("Pipe render layers configured to TRANSLUCENT!");
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        // 收集所有需要染色的物品（包括普通物品和方块物品）
        java.util.Set<Item> itemsToRegister = new java.util.HashSet<>();
        
        // 添加 ModItems 中的所有物品
        for (var entry : ModItems.ITEMS.getEntries()) {
            itemsToRegister.add(entry.get());
        }
        
        // 添加所有管道方块对应的物品
        itemsToRegister.add(ModBlocks.PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.SMALL_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BIG_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.HUGE_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRONZE_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRONZE_SMALL_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRONZE_BIG_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRONZE_HUGE_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.STAINLESS_STEEL_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.STAINLESS_STEEL_SMALL_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.STAINLESS_STEEL_BIG_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.STAINLESS_STEEL_HUGE_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRASS_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRASS_SMALL_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRASS_BIG_PIPE.get().asItem());
        itemsToRegister.add(ModBlocks.BRASS_HUGE_PIPE.get().asItem());
        
        // 移除 AIR
        itemsToRegister.remove(Items.AIR);
        
        event.register((stack, tintIndex) -> {
            Item item = stack.getItem();
            Integer[] colors = null;
            
            // 先尝试从物品缓存获取
            colors = ColorConfigLoader.getColors(item);
            
            // 如果是方块物品且没有直接映射，从方块缓存获取
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
        event.register((state, level, pos, tintIndex) -> {
            Block block = state.getBlock();
            Integer[] colors = ColorConfigLoader.getColors(block);
            if (colors != null && tintIndex >= 0 && tintIndex < colors.length) {
                return colors[tintIndex] != null ? colors[tintIndex] : 0xFFFFFFFF;
            }
            return 0xFFFFFFFF;
        }, 
            // 普通管道
            ModBlocks.PIPE.get(), ModBlocks.SMALL_PIPE.get(), ModBlocks.BIG_PIPE.get(), ModBlocks.HUGE_PIPE.get(),
            // 青铜管道
            ModBlocks.BRONZE_PIPE.get(), ModBlocks.BRONZE_SMALL_PIPE.get(), ModBlocks.BRONZE_BIG_PIPE.get(), ModBlocks.BRONZE_HUGE_PIPE.get(),
            // 不锈钢管道
            ModBlocks.STAINLESS_STEEL_PIPE.get(), ModBlocks.STAINLESS_STEEL_SMALL_PIPE.get(), ModBlocks.STAINLESS_STEEL_BIG_PIPE.get(), ModBlocks.STAINLESS_STEEL_HUGE_PIPE.get(),
            // 黄铜管道
            ModBlocks.BRASS_PIPE.get(), ModBlocks.BRASS_SMALL_PIPE.get(), ModBlocks.BRASS_BIG_PIPE.get(), ModBlocks.BRASS_HUGE_PIPE.get()
        );
    }
}