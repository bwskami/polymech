package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.ConveyorMaterial;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.item.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModEnUsLangProvider extends LanguageProvider {
    public ModEnUsLangProvider(PackOutput output) {
        super(output, Polymech.MOD_ID, "en_us");
    }

    @Override
    protected void addTranslations() {
        // 数据驱动的材料物品翻译
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            // 锭
            var ingotItem = ModItems.getMaterialItem(ModItemTypes.INGOT, materialName);
            if (ingotItem != null) {
                String displayName = formatMaterialName(materialName) + " Ingot";
                add(ingotItem.get(), displayName);
            }
            
            // 合金锭 这个的翻译成锭就好了，是不是合金锭只需要开发者知道就可以了
            var alloyIngotItem = ModItems.getMaterialItem(ModItemTypes.ALLOY_INGOT, materialName);
            if (alloyIngotItem != null) {
                String displayName = formatMaterialName(materialName) + " Ingot";
                add(alloyIngotItem.get(), displayName);
            }
            
            // 粒
            var nuggetItem = ModItems.getMaterialItem(ModItemTypes.NUGGET, materialName);
            if (nuggetItem != null) {
                String displayName = formatMaterialName(materialName) + " Nugget";
                add(nuggetItem.get(), displayName);
            }
            
            // 粉
            var dustItem = ModItems.getMaterialItem(ModItemTypes.DUST, materialName);
            if (dustItem != null) {
                String displayName = formatMaterialName(materialName) + " Dust";
                add(dustItem.get(), displayName);
            }
            
            // 板
            var plateItem = ModItems.getMaterialItem(ModItemTypes.PLATE, materialName);
            if (plateItem != null) {
                String displayName = formatMaterialName(materialName) + " Plate";
                add(plateItem.get(), displayName);
            }
            
            // 箔
            var foilItem = ModItems.getMaterialItem(ModItemTypes.FOIL, materialName);
            if (foilItem != null) {
                String displayName = formatMaterialName(materialName) + " Foil";
                add(foilItem.get(), displayName);
            }
            
            // 杆
            var stickItem = ModItems.getMaterialItem(ModItemTypes.STICK, materialName);
            if (stickItem != null) {
                String displayName = formatMaterialName(materialName) + " Stick";
                add(stickItem.get(), displayName);
            }
            
            // 齿轮
            var gearItem = ModItems.getMaterialItem(ModItemTypes.GEAR, materialName);
            if (gearItem != null) {
                String displayName = formatMaterialName(materialName) + " Gear";
                add(gearItem.get(), displayName);
            }
            
            // 小齿轮
            var smallGearItem = ModItems.getMaterialItem(ModItemTypes.SMALL_GEAR, materialName);
            if (smallGearItem != null) {
                String displayName = formatMaterialName(materialName) + " Small Gear";
                add(smallGearItem.get(), displayName);
            }
            
            // 弹簧
            var springItem = ModItems.getMaterialItem(ModItemTypes.SPRING, materialName);
            if (springItem != null) {
                String displayName = formatMaterialName(materialName) + " Spring";
                add(springItem.get(), displayName);
            }
            
            // 螺丝
            var screwItem = ModItems.getMaterialItem(ModItemTypes.SCREW, materialName);
            if (screwItem != null) {
                String displayName = formatMaterialName(materialName) + " Screw";
                add(screwItem.get(), displayName);
            }
            
            // 螺栓
            var boltItem = ModItems.getMaterialItem(ModItemTypes.BOLT, materialName);
            if (boltItem != null) {
                String displayName = formatMaterialName(materialName) + " Bolt";
                add(boltItem.get(), displayName);
            }
            
            // 环
            var ringItem = ModItems.getMaterialItem(ModItemTypes.RING, materialName);
            if (ringItem != null) {
                String displayName = formatMaterialName(materialName) + " Ring";
                add(ringItem.get(), displayName);
            }

        }
        

        
        add(ModItems.WRENCH.get(), "Wrench");
        
        // 添加蓝图工具的翻译
        add(ModItems.BLUEPRINT.get(), "Blueprint");
        add(ModItems.COKE.get(), "Coke");

        // 通用流体单元（四种规格）
        add(ModItems.SMALL_FLUID_CELL.get(), "Small Fluid Cell");
        add(ModItems.UNIVERSAL_FLUID_CELL.get(), "Universal Fluid Cell");
        add(ModItems.MEDIUM_FLUID_CELL.get(), "Medium Fluid Cell");
        add(ModItems.HUGE_FLUID_CELL.get(), "Huge Fluid Cell");
        add("tooltip.poly_mech.fluid_cell.empty", "Empty");
        add("tooltip.poly_mech.fluid_cell.stored", "Contains: %s (%d/%d mB)");
        add("tooltip.poly_mech.fluid_cell.limit", "Capacity limited to %d/%d mB");
        add("tooltip.poly_mech.fluid_cell.config_hint", "Sneak + Right Click: set capacity limit");
        add("gui.poly_mech.fluid_cell.config_title", "Set Capacity Limit");
        add("gui.poly_mech.fluid_cell.stored", "Stored: %d mB");
        add("gui.poly_mech.fluid_cell.max_capacity", "Type capacity: %d mB");
        add("gui.poly_mech.fluid_cell.limit_label", "Limit:");
        add("gui.poly_mech.button.confirm", "Confirm");
        add("gui.poly_mech.button.cancel", "Cancel");
        
        // 添加多方块机器选择界面的翻译
        add("gui.poly_mech.multiblock_selection.title", "Multiblock Machine Selection");
        add("gui.poly_mech.multiblock_selection.close", "←");
        add("gui.poly_mech.multiblock_selection.category_info", "Category: %s (%d machines)");
        add("gui.poly_mech.multiblock_selection.header_label", "Mode: %s | Selected: %s");
        add("gui.poly_mech.classify.by_voltage", "By Voltage");
        add("gui.poly_mech.classify.by_type", "By Type");
        add("gui.poly_mech.classify.mode_voltage", "By Voltage");
        add("gui.poly_mech.classify.mode_type", "By Type");
        add("gui.poly_mech.tier.lv", "LV");
        add("gui.poly_mech.tier.mv", "MV");
        add("gui.poly_mech.tier.hv", "HV");
        add("gui.poly_mech.tier.ev", "EV");
        add("gui.poly_mech.tier.iv", "IV");
        add("gui.poly_mech.tier.luv", "LuV");
        add("gui.poly_mech.tier.zpm", "ZPM");
        add("gui.poly_mech.tier.uv", "UV");
        add("gui.poly_mech.tier.uhv", "UHV");
        add("gui.poly_mech.tier.steam", "Steam");
        add("gui.poly_mech.type.chemical", "Chemical");
        add("gui.poly_mech.type.compression", "Compression");
        add("gui.poly_mech.type.heat", "Heat Treatment");
        add("gui.poly_mech.type.assembly", "Assembly");
        add("gui.poly_mech.type.recycling", "Recycling");
        add("gui.poly_mech.machine.large_chemical_reactor", "Large Chemical Reactor");
        add("gui.poly_mech.machine.implosion_compressor", "Implosion Compressor");
        add("gui.poly_mech.machine.pyrolyze_oven", "Pyrolyze Oven");
        add("gui.poly_mech.machine.electric_blast_furnace", "Electric Blast Furnace");
        add("gui.poly_mech.machine.vacuum_freezer", "Vacuum Freezer");
        add("gui.poly_mech.machine.assembly_line", "Assembly Line");
        add("gui.poly_mech.machine.recycler", "Recycler");

        // 添加快捷键的翻译
        add("key.poly_mech.open_multiblock_menu", "Open Multiblock Selection Menu");

        add(ModBlocks.COKE_OVEN_BRICK.get(), "Coke Oven Brick");
        add(ModBlocks.FLUID_TANK.get(), "Fluid Tank");
        add(ModBlocks.HORIZONTAL_STEAM_BOILER.mainBlock().get(), "Horizontal Steam Boiler");

        // 卧式蒸汽锅炉 GUI 翻译
        add("gui.poly_mech.input_liquid", "Input Liquid");
        add("gui.poly_mech.fuel", "Fuel");
        add("gui.poly_mech.output_liquid", "Output Liquid");
        add("gui.poly_mech.output_ash", "Ash");
        add("gui.poly_mech.button.enable", "Enable");
        add("gui.poly_mech.button.disable", "Disable");

        // 蒸汽流体
        add("fluid.poly_mech.steam", "Steam");
        add("item.poly_mech.steam_bucket", "Steam Bucket");
        add("block.poly_mech.steam", "Steam");

        // 侧面方块类型
        add("side_type.poly_mech.normal", "Machine Casing");
        add("side_type.poly_mech.fluid_input", "Fluid Input Hatch");
        add("side_type.poly_mech.fluid_output", "Fluid Output Hatch");
        add("side_type.poly_mech.item_input", "Item Input Hatch");
        add("side_type.poly_mech.item_output", "Item Output Hatch");

        for (var materialEntry : ModBlocks.PIPE_TABLE.entrySet()) {
            PipeMaterial material = materialEntry.getKey();
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                PipeBlock.PipeSize size = sizeEntry.getKey();
                String displayName = buildDisplayName(material, size);
                add(sizeEntry.getValue().get(), displayName);
            }
        }

        for (var conveyorEntry : ModBlocks.CONVEYOR_TABLE.entrySet()) {
            add(conveyorEntry.getValue().get(), buildConveyorDisplayName(conveyorEntry.getKey()));
        }

        add("itemGroup.material_tab", "Ploy Mech:Material");
        add("itemGroup.block_tab", "Ploy Mech:Block");
        add("itemGroup.pipe_tab", "Ploy Mech:Pipes and Logistics");
        add("itemGroup.tool_tab", "Ploy Mech:Tool");
        add("itemGroup.fluid_cell_tab", "Ploy Mech:Fluid Cells");
    }

    private String formatMaterialName(String name) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else {
                result.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return result.toString();
    }

    private String buildDisplayName(PipeMaterial material, PipeBlock.PipeSize size) {
        String materialName = switch (material) {
            case IRON -> "";
            case BRONZE -> "Bronze ";
            case STAINLESS_STEEL -> "Stainless Steel ";
            case BRASS -> "Brass ";
        };
        String sizeName = switch (size) {
            case SMALL -> "Small Pipe";
            case BIG   -> "Big Pipe";
            case HUGE  -> "Huge Pipe";
            default    -> "Pipe";
        };
        return materialName + sizeName;
    }

    private String buildConveyorDisplayName(ConveyorMaterial material) {
        String materialName = switch (material) {
            case IRON -> "";
            case BRONZE -> "Bronze ";
            case STAINLESS_STEEL -> "Stainless Steel ";
            case BRASS -> "Brass ";
        };
        return materialName + "Conveyor Belt";
    }
}