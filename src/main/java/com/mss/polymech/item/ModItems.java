package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Polymech.MOD_ID);

    public static final DeferredItem<Item> STEEL_INGOT =
            ITEMS.register("steel_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> TEST_ITEM1 =
            ITEMS.register("test_item1", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> TEST_ITEM2 =
            ITEMS.register("test_item2", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> TEST_ITEM3 =
            ITEMS.register("test_item3", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> TEST_RAW =
            ITEMS.register("test_raw", () -> new Item(new Item.Properties()));

    public static  void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
