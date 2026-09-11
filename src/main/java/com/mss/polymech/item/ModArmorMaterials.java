package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.List;

/**
 * 太空装备材料。1.21 起 ArmorMaterial 是数据注册表，
 * 材质贴图位于 assets/poly_mech/textures/models/armor/space_suit_layer_1.png。
 */
public final class ModArmorMaterials {

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, Polymech.MOD_ID);

    /** 太空服材料：目前只用到头盔，其余部位留作扩展。 */
    public static final Holder<ArmorMaterial> SPACE_SUIT = ARMOR_MATERIALS.register("space_suit",
            () -> new ArmorMaterial(
                    Util.make(new EnumMap<>(ArmorItem.Type.class), map -> {
                        map.put(ArmorItem.Type.BOOTS, 2);
                        map.put(ArmorItem.Type.LEGGINGS, 4);
                        map.put(ArmorItem.Type.CHESTPLATE, 6);
                        map.put(ArmorItem.Type.HELMET, 3);
                        map.put(ArmorItem.Type.BODY, 6);
                    }),
                    15,
                    SoundEvents.ARMOR_EQUIP_NETHERITE,
                    () -> Ingredient.of(Items.IRON_INGOT),
                    List.of(new ArmorMaterial.Layer(
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "space_suit"))),
                    2.0f,
                    0.0f));

    private ModArmorMaterials() {
    }
}
