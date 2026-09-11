package com.mss.polymech.item;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;

/**
 * 太空头盔：戴在头部时，太空维度的 HUD 会显示视野中星球的名字与距离
 * （见 {@code SpaceHelmetHudOverlay}）。
 */
public class SpaceHelmetItem extends ArmorItem {

    public SpaceHelmetItem(Holder<ArmorMaterial> material, Properties properties) {
        super(material, Type.HELMET, properties);
    }

    /** 目标是否正戴着太空头盔。 */
    public static boolean isWorn(LivingEntity entity) {
        return entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof SpaceHelmetItem;
    }
}
