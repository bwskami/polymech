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
    public static final DeferredItem<Item> TEST_INGOT =
            ITEMS.register("test_ingot", () -> new Item(new Item.Properties()));
    //这一行以下写锭（单质）
    public static final DeferredItem<Item> ALUMINIUM_INGOT =
            ITEMS.register("aluminium_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> NICKEL_INGOT =
            ITEMS.register("nickel_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> TIN_INGOT =
            ITEMS.register("tin_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> ZINC_INGOT =
            ITEMS.register("zinc_ingot", () -> new Item(new Item.Properties()));
    //这一行以下写锭（合金）
    public static final DeferredItem<Item> BRASS_INGOT =
            ITEMS.register("brass_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> BRONZE_INGOT =
            ITEMS.register("bronze_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> IVAR_INGOT =
            ITEMS.register("ivar_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> CUPRONICKEL_INGOT =
            ITEMS.register("cupronickel_ingot", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> STAINLESS_STEEL_INGOT =
            ITEMS.register("stainless_steel_ingot", () -> new Item(new Item.Properties()));

    //这一行以下写粗矿
    public static final DeferredItem<Item> TEST_RAW =
            ITEMS.register("test_raw", () -> new Item(new Item.Properties()));

    public static  void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
