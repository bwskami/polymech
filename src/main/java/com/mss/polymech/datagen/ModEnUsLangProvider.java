package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.ConveyorMaterial;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.fluid.ChemicalFluid;
import com.mss.polymech.fluid.ModElements;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.powergrid.GridWireType;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

import java.util.LinkedHashMap;
import java.util.Map;

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

            // 宝石/晶体（仅宝石材料，见GemMaterials）
            var gemItem = ModItems.getMaterialItem(ModItemTypes.GEM, materialName);
            if (gemItem != null) {
                String displayName = formatMaterialName(materialName) + " Gem";
                add(gemItem.get(), displayName);
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
            
            // 线材
            var wireItem = ModItems.getMaterialItem(ModItemTypes.WIRE, materialName);
            if (wireItem != null) {
                String displayName = formatMaterialName(materialName) + " Wire";
                add(wireItem.get(), displayName);
            }

        }

        // 真实矿物：矿石方块（全部岩种变体）与粗矿物（raw_{mineral}）
        for (com.mss.polymech.worldgen.ModMinerals.MineralDefinition def : com.mss.polymech.worldgen.ModMinerals.getDefinitions()) {
            String mineralName = formatMaterialName(def.mineral());
            var oreSet = ModBlocks.MINERAL_ORES.get(def.mineral());
            if (oreSet != null) {
                for (var variantEntry : oreSet.byRock().entrySet()) {
                    String host = variantEntry.getKey();
                    String name = switch (host) {
                        case "stone" -> mineralName + " Ore";
                        case "deepslate" -> "Deepslate " + mineralName + " Ore";
                        case "netherrack" -> "Netherrack " + mineralName + " Ore";
                        case "end_stone" -> "End Stone " + mineralName + " Ore";
                        default -> mineralName + " " + formatMaterialName(host) + " Ore";
                    };
                    add(variantEntry.getValue().get(), name);
                }
            }
            var rawItem = ModItems.getRawMineral(def.mineral());
            if (rawItem != null) {
                add(rawItem.get(), "Raw " + mineralName);
            }
            // 矿物加工中间产物：粉碎矿/洗净矿（煤炭等直接产物不加工）
            if (def.kind() != com.mss.polymech.worldgen.ModMinerals.ProductKind.COAL) {
                var crushed = ModItems.getMineralItem(com.mss.polymech.api.item.ModItemTypes.CRUSHED, def.mineral());
                var purified = ModItems.getMineralItem(com.mss.polymech.api.item.ModItemTypes.PURIFIED, def.mineral());
                if (crushed != null) add(crushed.get(), mineralName + " Crushed");
                if (purified != null) add(purified.get(), mineralName + " Purified");
            }
        }
        

        
        add(ModItems.WRENCH.get(), "Wrench");
        add(ModItems.PROSPECTOR.get(), "Prospector");
        add("gui.poly_mech.prospector.title", "Prospector");
        add("gui.poly_mech.prospector.hint", "Rock types (base color) + mineral ores (overlay). Red box = your chunk.");
        add("gui.poly_mech.prospector.legend", "Depth: white dot = shallow, gray dot = middle, black dot = deep");
        
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

        // 区域岩石（21种，贴图取自TerraFirmaCraft）
        add(ModBlocks.ROCKS.get("limestone").get(), "Limestone");
        add(ModBlocks.ROCKS.get("shale").get(), "Shale");
        add(ModBlocks.ROCKS.get("chalk").get(), "Chalk");
        add(ModBlocks.ROCKS.get("chert").get(), "Chert");
        add(ModBlocks.ROCKS.get("claystone").get(), "Claystone");
        add(ModBlocks.ROCKS.get("conglomerate").get(), "Conglomerate");
        add(ModBlocks.ROCKS.get("dolomite").get(), "Dolomite");
        add(ModBlocks.ROCKS.get("tuff").get(), "Tuff");
        add(ModBlocks.ROCKS.get("granite").get(), "Granite");
        add(ModBlocks.ROCKS.get("basalt").get(), "Basalt");
        add(ModBlocks.ROCKS.get("rhyolite").get(), "Rhyolite");
        add(ModBlocks.ROCKS.get("dacite").get(), "Dacite");
        add(ModBlocks.ROCKS.get("diorite").get(), "Diorite");
        add(ModBlocks.ROCKS.get("gabbro").get(), "Gabbro");
        add(ModBlocks.ROCKS.get("andesite").get(), "Andesite");
        add(ModBlocks.ROCKS.get("marble").get(), "Marble");
        add(ModBlocks.ROCKS.get("gneiss").get(), "Gneiss");
        add(ModBlocks.ROCKS.get("schist").get(), "Schist");
        add(ModBlocks.ROCKS.get("slate").get(), "Slate");
        add(ModBlocks.ROCKS.get("phyllite").get(), "Phyllite");
        add(ModBlocks.ROCKS.get("quartzite").get(), "Quartzite");

        // 勘探命令套件（世界生成测试工具）
        add("command.poly_mech.rock.predicted", "Predicted rock type here: %s");
        add("command.poly_mech.rock.actual", "Actual block underfoot: %s at %s");
        add("command.poly_mech.rock.none", "No rock found within 64 blocks below (only air or fluid)");
        add("command.poly_mech.veins.header", "=== PolyMech Vein Definitions ===");
        add("command.poly_mech.veins.entry", "- %s: avg 1/%d chunks, Y %d~%d, size %d, density %s, hosts: %s");
        add("command.poly_mech.veins.shape", "  Shape: %s");
        add("command.poly_mech.veins.composition", "  Primary %s / Secondary %s / Between %s / Sporadic %s");
        add("command.poly_mech.scan.result", "%s: %d blocks, nearest %s");
        add("command.poly_mech.scan.total", "Total: %d ore blocks");
        add("command.poly_mech.scan.none", "No PolyMech ores found in scan range");
        add("command.poly_mech.scan.unloaded", "(%d columns skipped: chunks not loaded)");
        add("command.poly_mech.find.found", "Nearest %s ore: %d blocks away %s");
        add("command.poly_mech.find.none", "No %s ore found within %d blocks");
        add("command.poly_mech.find.invalid", "Unknown ore material: %s (valid: %s)");
        add("command.poly_mech.expose.done", "Removed %d blocks within a cubic radius of %d around you");
        add("command.poly_mech.vein.cassiterite", "Cassiterite Vein");
        add("command.poly_mech.vein.sphalerite", "Sphalerite Vein");
        add("command.poly_mech.vein.galena", "Galena Vein");
        add("command.poly_mech.vein.bauxite", "Bauxite Vein");
        add("command.poly_mech.vein.laterite", "Laterite Vein");
        add("command.poly_mech.vein.wolframite", "Wolframite Vein");
        add(ModBlocks.HORIZONTAL_STEAM_BOILER.mainBlock().get(), "Horizontal Steam Boiler");

        // 电网（真实电线电网系统）
        add(ModBlocks.CONNECTOR.get(), "Connector");
        add(ModBlocks.CONCRETE_POLE.get(), "Concrete Pole");
        // Wire spools (data-driven: shared material names, insulated variants prefixed)
        for (GridWireType wireType : GridWireType.values()) {
            String name = formatMaterialName(wireType.metalName())
                    + (wireType.isInsulated() ? " Insulated Wire Spool" : " Wire Spool");
            add("item.poly_mech." + wireType.spoolItemName(), name);
        }
        add(ModItems.EMPTY_SPOOL.get(), "Empty Spool");
        add(ModItems.WIRE_CUTTER.get(), "Wire Cutter");
        add(ModItems.CLAMP_METER.get(), "Clamp Meter");
        // Connector tooltip
        add("tooltip.poly_mech.connector.node", "Power grid access point, can connect wires");
        add("tooltip.poly_mech.connector.stack", "Right-click a placed connector to stack up to 4");
        add("tooltip.poly_mech.connector.wire", "Use a wire spool to connect into the grid");
        // Wire spool electrical tooltip
        add("tooltip.poly_mech.wire.tier", "Voltage Tier: %s");
        add("tooltip.poly_mech.wire.max_voltage", "Max Voltage: %d FE/t");
        add("tooltip.poly_mech.wire.max_amperage", "Max Amperage: %d A");
        add("tooltip.poly_mech.wire.max_power", "Max Power: %d FE/t");
        add("tooltip.poly_mech.wire.resistance", "Loss Resistance: %s Ω/block");
        add("tooltip.poly_mech.wire.loss_note", "Wire loss = current² × total resistance (accumulates with length)");
        add("tooltip.poly_mech.wire.max_length", "Max Span: %d blocks");
        add("tooltip.poly_mech.wire.insulated", "Insulated");

        // Wire Cutter
        add("tooltip.poly_mech.wire_cutter", "Right-click a wire to inspect and cut it");
        add("gui.poly_mech.wire_cutter.length", "Length: %s blocks");
        add("gui.poly_mech.wire_cutter.total_resistance", "Total Resistance: %s Ω");
        add("gui.poly_mech.wire_cutter.nodes", "Nodes: %s ⇔ %s");
        add("gui.poly_mech.wire_cutter.hint", "Right-click to cut");

        // Clamp Meter
        add("tooltip.poly_mech.clamp_meter", "Aim at a wire and right-click to measure");
        add("gui.poly_mech.clamp_meter.prompt", "Aim at a wire and right-click to measure");
        add("gui.poly_mech.clamp_meter.wire", "Wire: %s");
        add("gui.poly_mech.clamp_meter.measuring", "Measuring...");
        add("gui.poly_mech.clamp_meter.voltage", "Voltage: %d FE/t");
        add("gui.poly_mech.clamp_meter.current", "Current: %s A");
        add("gui.poly_mech.clamp_meter.power", "Power: %s FE/t");

        // Battery
        add(ModBlocks.BATTERY.get(), "Battery");
        add(ModBlocks.CREATIVE_BATTERY.get(), "Creative Battery");
        add("gui.poly_mech.battery.energy", "Energy: %d / %d FE");
        add("gui.poly_mech.battery.voltage", "Voltage Tier: %s (%d FE/t)");
        add("gui.poly_mech.battery.grid_voltage", "Grid Voltage: %d FE/t");
        add("gui.poly_mech.battery.rated_voltage", "Rated Voltage: %s (%d FE/t)");
        add("gui.poly_mech.battery.input_rate", "Input Rate: %d FE/t");
        add("gui.poly_mech.battery.output_rate", "Output Rate: %d FE/t");
        add("gui.poly_mech.battery.tooltip_enable", "Click to toggle power");
        add("gui.poly_mech.battery.energy_stored", "Energy: %s %s");
        add("gui.poly_mech.battery.input_rate_u", "Input Rate: %s %s/t");
        add("gui.poly_mech.battery.output_rate_u", "Output Rate: %s %s/t");
        add("gui.poly_mech.battery.energy_tab", "Click to switch unit (current: %s)");

        // Voltage Tiers
        add("voltage_tier.poly_mech.ulv", "ULV");
        add("voltage_tier.poly_mech.lv", "LV");
        add("voltage_tier.poly_mech.mv", "MV");
        add("voltage_tier.poly_mech.hv", "HV");
        add("voltage_tier.poly_mech.ev", "EV");
        add("voltage_tier.poly_mech.iv", "IV");
        add("voltage_tier.poly_mech.luv", "LuV");
        add("voltage_tier.poly_mech.zpm", "ZPM");
        add("voltage_tier.poly_mech.uv", "UV");
        add("voltage_tier.poly_mech.uhv", "UHV");

        // Side Config
        add("gui.poly_mech.side_config.title", "Side Config");
        add("gui.poly_mech.side_config.config_type", "Config Type: %s");
        add("gui.poly_mech.side_config.eject", "Auto-Eject: %s");
        add("gui.poly_mech.side_config.eject_on", "On");
        add("gui.poly_mech.side_config.eject_off", "Off");
        add("gui.poly_mech.side_config.no_eject", "No Auto-Eject");
        add("gui.poly_mech.side_config.auto_eject", "Auto-Eject");
        add("gui.poly_mech.side_config.clear", "Clear Sides");
        add("gui.poly_mech.side_config.clear_all", "Clear all sides on all types");
        add("gui.poly_mech.side_config.increment", "Increment");
        add("gui.poly_mech.side_config.cannot_eject", "This type cannot be auto-ejected");
        add("gui.poly_mech.side_config.tab", "Open Side Config");
        add("gui.poly_mech.side_config.tab_energy", "Energy");
        add("gui.poly_mech.side_config.tab_item", "Item");
        add("gui.poly_mech.side_config.tab_fluid", "Fluid");
        add("gui.poly_mech.side_config.none", "None");
        add("gui.poly_mech.side_config.in", "Input");
        add("gui.poly_mech.side_config.out", "Output");
        add("gui.poly_mech.side_config.face.up", "Top");
        add("gui.poly_mech.side_config.face.down", "Bottom");
        add("gui.poly_mech.side_config.face.north", "North");
        add("gui.poly_mech.side_config.face.south", "South");
        add("gui.poly_mech.side_config.face.east", "East");
        add("gui.poly_mech.side_config.face.west", "West");
                add("gui.poly_mech.side_config.back", "Back");
        add("gui.poly_mech.side_config.close", "Close");
        add("gui.poly_mech.side_config.bottom_label", "SLOTS");

        // 线轴交互提示
        add("message.poly_mech.wire_spool.cancelled", "Selection Cleared");
        add("message.poly_mech.wire_spool.selected", "Selected node: %s");
        add("message.poly_mech.wire_spool.same_node", "Cannot connect a node to itself!");
        add("message.poly_mech.wire_spool.already_connected", "These nodes are already connected!");
        add("message.poly_mech.wire_spool.too_far", "Too far away! Maximum length: %s blocks");
        add("message.poly_mech.wire_spool.connected", "Wire connected!");
        add("message.poly_mech.empty_spool.disconnected", "Disconnected %d wire(s)");
        add("message.poly_mech.empty_spool.no_wire", "No wires connected here");
        add("message.poly_mech.wire_cutter.cut", "Wire connection cut");

        // 卧式蒸汽锅炉 GUI 翻译
        add("gui.poly_mech.input_liquid", "Input Liquid");
        add("gui.poly_mech.fuel", "Fuel");
        add("gui.poly_mech.output_liquid", "Output Liquid");
        add("gui.poly_mech.output_ash", "Ash");
        add("gui.poly_mech.button.enable", "Enable");
        add("gui.poly_mech.button.disable", "Disable");

        // Boiler / processing machine status and progress-bar hover tooltips
        add("gui.poly_mech.status.running", "Running");
        add("gui.poly_mech.status.stopped", "Stopped");
        add("gui.poly_mech.status.idle", "Idle");
        add("gui.poly_mech.machine.generation", "Generation: %d /t");
        add("gui.poly_mech.machine.progress", "Progress: %d / %d");
        add("gui.poly_mech.boiler.tooltip.temperature", "Temperature: %d K / %d K");
        add("gui.poly_mech.boiler.tooltip.steam_output", "Steam Output: %d mB/t");
        add("gui.poly_mech.boiler.tooltip.water_level", "Water Level: %d / %d mB");
        add("gui.poly_mech.boiler.tooltip.steam", "Steam: %d / %d mB");
        add("gui.poly_mech.boiler.tooltip.steam_rate", "Steam Rate: %d mB/t");
        add("gui.poly_mech.boiler.temperature", "Temperature: %d K");
        add("gui.poly_mech.boiler.efficiency", "Expected Efficiency: %d mB/t");
        add("gui.poly_mech.boiler.burn_time", "Burn Time: %d s");

        // 蒸汽流体
        add("fluid.poly_mech.steam", "Steam");
        add("item.poly_mech.steam_bucket", "Steam Bucket");
        add("fluid.poly_mech.petroleum", "Petroleum");
        add("item.poly_mech.petroleum_bucket", "Petroleum Bucket");
        add("block.poly_mech.petroleum", "Petroleum");

        // 化学流体（真实存在的化学物质，不可放置）
        // 英文名称作为翻译源数据写在datagen侧，运行时一律通过翻译键解析
        Map<String, String> chemicalNames = new LinkedHashMap<>();
        // 酸 / 碱 / 氧化剂（液体）
        chemicalNames.put("sulfuric_acid", "Sulfuric Acid");
        chemicalNames.put("nitric_acid", "Nitric Acid");
        chemicalNames.put("hydrochloric_acid", "Hydrochloric Acid");
        chemicalNames.put("hydrofluoric_acid", "Hydrofluoric Acid");
        chemicalNames.put("hydrogen_peroxide", "Hydrogen Peroxide");
        chemicalNames.put("sodium_hydroxide", "Sodium Hydroxide Solution");
        chemicalNames.put("ammonia_water", "Ammonia Water");
        chemicalNames.put("acetic_acid", "Acetic Acid");
        // 有机溶剂（液体）
        chemicalNames.put("ethanol", "Ethanol");
        chemicalNames.put("methanol", "Methanol");
        chemicalNames.put("acetone", "Acetone");
        chemicalNames.put("glycerol", "Glycerol");
        chemicalNames.put("benzene", "Benzene");
        chemicalNames.put("toluene", "Toluene");
        chemicalNames.put("phenol", "Phenol");
        chemicalNames.put("nitrobenzene", "Nitrobenzene");
        // 单质 / 氧化物（液体）
        chemicalNames.put("bromine", "Bromine");
        chemicalNames.put("mercury", "Mercury");
        chemicalNames.put("sulfur_trioxide", "Sulfur Trioxide");
        // 气体
        chemicalNames.put("hydrogen", "Hydrogen");
        chemicalNames.put("nitrogen", "Nitrogen");
        chemicalNames.put("oxygen", "Oxygen");
        chemicalNames.put("chlorine", "Chlorine");
        chemicalNames.put("ammonia", "Ammonia");
        chemicalNames.put("methane", "Methane");
        chemicalNames.put("propane", "Propane");
        chemicalNames.put("butane", "Butane");
        chemicalNames.put("carbon_dioxide", "Carbon Dioxide");
        chemicalNames.put("carbon_monoxide", "Carbon Monoxide");
        chemicalNames.put("sulfur_dioxide", "Sulfur Dioxide");
        chemicalNames.put("hydrogen_sulfide", "Hydrogen Sulfide");
        chemicalNames.put("helium", "Helium");
        chemicalNames.put("argon", "Argon");
        // 无机酸 / 盐溶液 / 混合液
        chemicalNames.put("acidic_osmium_solution", "Acidic Osmium Solution");
        chemicalNames.put("aqua_regia", "Aqua Regia");
        chemicalNames.put("diluted_hydrochloric_acid", "Diluted Hydrochloric Acid");
        chemicalNames.put("diluted_sulfuric_acid", "Diluted Sulfuric Acid");
        chemicalNames.put("phosphoric_acid", "Phosphoric Acid");
        chemicalNames.put("phthalic_acid", "Phthalic Acid");
        chemicalNames.put("formic_acid", "Formic Acid");
        chemicalNames.put("hypochlorous_acid", "Hypochlorous Acid");
        chemicalNames.put("fluoroantimonic_acid", "Fluoroantimonic Acid");
        chemicalNames.put("nitration_mixture", "Nitration Mixture");
        chemicalNames.put("sulfuric_copper_solution", "Sulfuric Copper Solution");
        chemicalNames.put("sulfuric_nickel_solution", "Sulfuric Nickel Solution");
        chemicalNames.put("sodium_persulfate", "Sodium Persulfate");
        chemicalNames.put("rhodium_sulfate", "Rhodium Sulfate");
        chemicalNames.put("titanium_tetrachloride", "Titanium Tetrachloride");
        chemicalNames.put("iron_ii_chloride", "Iron II Chloride");
        chemicalNames.put("iron_iii_chloride", "Iron III Chloride");
        chemicalNames.put("ammonium_formate", "Ammonium Formate");
        // 有机单体 / 中间体
        chemicalNames.put("formaldehyde", "Formaldehyde");
        chemicalNames.put("formamide", "Formamide");
        chemicalNames.put("chloromethane", "Chloromethane");
        chemicalNames.put("dichloroethane", "Dichloroethane");
        chemicalNames.put("glycolonitrile", "Glycolonitrile");
        chemicalNames.put("dimethylamine", "Dimethylamine");
        chemicalNames.put("diethylenetriamine", "Diethylenetriamine");
        chemicalNames.put("diethylenetriamine_pentaacetonitrile", "Diethylenetriamine Pentaacetonitrile");
        chemicalNames.put("ethane", "Ethane");
        chemicalNames.put("ethylene", "Ethylene");
        chemicalNames.put("propene", "Propene");
        chemicalNames.put("butene", "Butene");
        chemicalNames.put("butadiene", "Butadiene");
        chemicalNames.put("cumene", "Cumene");
        chemicalNames.put("vinyl_chloride", "Vinyl Chloride");
        chemicalNames.put("tetrafluoroethylene", "Tetrafluoroethylene");
        chemicalNames.put("dimethyldichlorosilane", "Dimethyldichlorosilane");
        chemicalNames.put("epichlorohydrin", "Epichlorohydrin");
        chemicalNames.put("hydrogen_cyanide", "Hydrogen Cyanide");
        chemicalNames.put("glyceryl_trinitrate", "Glyceryl Trinitrate");
        chemicalNames.put("lead_zinc_solution", "Lead Zinc Solution");
        chemicalNames.put("indium_concentrate", "Indium Concentrate");
        // 聚合物
        chemicalNames.put("epoxy", "Epoxy");
        chemicalNames.put("reinforced_epoxy_resin", "Reinforced Epoxy Resin");
        chemicalNames.put("polyethylene", "Polyethylene");
        chemicalNames.put("polytetrafluoroethylene", "Polytetrafluoroethylene");
        chemicalNames.put("polyvinyl_chloride", "Polyvinyl Chloride");
        chemicalNames.put("polybenzimidazole", "Polybenzimidazole");
        chemicalNames.put("polycaprolactam", "Polycaprolactam");
        chemicalNames.put("polyphenylene_sulfide", "Polyphenylene Sulfide");
        // 熔融金属 / 合金
        chemicalNames.put("copper", "Molten Copper");
        chemicalNames.put("gold", "Molten Gold");
        chemicalNames.put("iron", "Molten Iron");
        chemicalNames.put("annealed_copper", "Molten Annealed Copper");
        chemicalNames.put("wrought_iron", "Molten Wrought Iron");
        chemicalNames.put("steel", "Molten Steel");
        chemicalNames.put("arsenic", "Molten Arsenic");
        chemicalNames.put("carbon", "Molten Carbon");
        chemicalNames.put("battery_alloy", "Molten Battery Alloy");
        chemicalNames.put("bismuth_bronze", "Molten Bismuth Bronze");
        chemicalNames.put("black_bronze", "Molten Black Bronze");
        chemicalNames.put("cobalt_brass", "Molten Cobalt Brass");
        chemicalNames.put("kanthal", "Molten Kanthal");
        chemicalNames.put("magnalium", "Molten Magnalium");
        chemicalNames.put("manganese_phosphide", "Molten Manganese Phosphide");
        chemicalNames.put("nichrome", "Molten Nichrome");
        chemicalNames.put("osmiridium", "Molten Osmiridium");
        chemicalNames.put("potin", "Molten Potin");
        chemicalNames.put("rose_gold", "Molten Rose Gold");
        chemicalNames.put("soldering_alloy", "Molten Soldering Alloy");
        chemicalNames.put("sterling_silver", "Molten Sterling Silver");
        chemicalNames.put("tin_alloy", "Molten Tin Alloy");
        chemicalNames.put("ultimet", "Molten Ultimet");
        chemicalNames.put("vanadium_gallium", "Molten Vanadium Gallium");
        chemicalNames.put("vanadium_steel", "Molten Vanadium Steel");
        chemicalNames.put("niobium_titanium", "Molten Niobium Titanium");
        chemicalNames.put("borosilicate_glass", "Molten Borosilicate Glass");
        chemicalNames.put("glass", "Molten Glass");
        // 超导材料 / 特种陶瓷
        chemicalNames.put("gallium_arsenide", "Molten Gallium Arsenide");
        chemicalNames.put("indium_gallium_phosphide", "Molten Indium Gallium Phosphide");
        chemicalNames.put("nickel_zinc_ferrite", "Molten Nickel Zinc Ferrite");
        chemicalNames.put("magnesium_diboride", "Molten Magnesium Diboride");
        chemicalNames.put("yttrium_barium_cuprate", "Molten Yttrium Barium Cuprate");
        chemicalNames.put("mercury_barium_calcium_cuprate", "Molten Mercury Barium Calcium Cuprate");
        chemicalNames.put("uranium_triplatinum", "Molten Uranium Triplatinum");
        chemicalNames.put("samarium_iron_arsenic_oxide", "Molten Samarium Iron Arsenic Oxide");
        chemicalNames.put("indium_tin_barium_titanium_cuprate", "Molten Indium Tin Barium Titanium Cuprate");
        // 特殊液体
        chemicalNames.put("ice", "Ice");
        chemicalNames.put("oil", "Oil");
        chemicalNames.put("raw_oil", "Raw Oil");
        chemicalNames.put("heavy_oil", "Heavy Oil");
        chemicalNames.put("light_oil", "Light Oil");
        chemicalNames.put("naphtha", "Naphtha");
        chemicalNames.put("sulfuric_naphtha", "Sulfuric Naphtha");
        chemicalNames.put("light_fuel", "Light Fuel");
        chemicalNames.put("sulfuric_light_fuel", "Sulfuric Light Fuel");
        chemicalNames.put("lightly_hydro_cracked_light_fuel", "Lightly Hydro Cracked Light Fuel");
        chemicalNames.put("lightly_steam_cracked_light_fuel", "Lightly Steam Cracked Light Fuel");
        chemicalNames.put("severely_hydro_cracked_light_fuel", "Severely Hydro Cracked Light Fuel");
        chemicalNames.put("severely_steam_cracked_light_fuel", "Severely Steam Cracked Light Fuel");
        chemicalNames.put("lightly_hydro_cracked_naphtha", "Lightly Hydro Cracked Naphtha");
        chemicalNames.put("lightly_steam_cracked_naphtha", "Lightly Steam Cracked Naphtha");
        chemicalNames.put("severely_hydro_cracked_naphtha", "Severely Hydro Cracked Naphtha");
        chemicalNames.put("severely_steam_cracked_naphtha", "Severely Steam Cracked Naphtha");
        chemicalNames.put("heavy_fuel", "Heavy Fuel");
        chemicalNames.put("sulfuric_heavy_fuel", "Sulfuric Heavy Fuel");
        chemicalNames.put("lightly_hydro_cracked_heavy_fuel", "Lightly Hydro Cracked Heavy Fuel");
        chemicalNames.put("lightly_steam_cracked_heavy_fuel", "Lightly Steam Cracked Heavy Fuel");
        chemicalNames.put("severely_hydro_cracked_heavy_fuel", "Severely Hydro Cracked Heavy Fuel");
        chemicalNames.put("severely_steam_cracked_heavy_fuel", "Severely Steam Cracked Heavy Fuel");
        chemicalNames.put("diesel", "Diesel");
        chemicalNames.put("cetane_boosted_diesel", "Cetane Boosted Diesel");
        chemicalNames.put("lpg", "LPG");
        chemicalNames.put("lubricant", "Lubricant");
        chemicalNames.put("creosote", "Creosote");
        chemicalNames.put("biomass", "Biomass");
        chemicalNames.put("fermented_biomass", "Fermented Biomass");
        chemicalNames.put("cracked_bauxite_slurry", "Cracked Bauxite Slurry");
        chemicalNames.put("concrete", "Concrete");
        chemicalNames.put("glue", "Glue");
        chemicalNames.put("milk", "Milk");
        chemicalNames.put("seed_oil", "Seed Oil");
        chemicalNames.put("liquid_air", "Liquid Air");
        chemicalNames.put("rubber", "Rubber");
        chemicalNames.put("silicone_rubber", "Silicone Rubber");
        chemicalNames.put("styrene_butadiene_rubber", "Styrene Butadiene Rubber");
        chemicalNames.put("uranium_235", "Molten Uranium 235");
        chemicalNames.put("uranium_238", "Molten Uranium 238");
        chemicalNames.put("plutonium_239", "Molten Plutonium 239");
        chemicalNames.put("plutonium_241", "Molten Plutonium 241");
        // 气体
        chemicalNames.put("air", "Air");
        chemicalNames.put("nitric_oxide", "Nitric Oxide");
        chemicalNames.put("nitrogen_dioxide", "Nitrogen Dioxide");
        chemicalNames.put("nitrous_oxide", "Nitrous Oxide");
        chemicalNames.put("dinitrogen_tetroxide", "Dinitrogen Tetroxide");
        chemicalNames.put("nitrosyl_chloride", "Nitrosyl Chloride");
        chemicalNames.put("monochloramine", "Monochloramine");
        chemicalNames.put("fluorine", "Fluorine");
        chemicalNames.put("neon", "Neon");
        chemicalNames.put("krypton", "Krypton");
        chemicalNames.put("xenon", "Xenon");
        chemicalNames.put("radon", "Radon");
        chemicalNames.put("deuterium", "Deuterium");
        chemicalNames.put("tritium", "Tritium");
        chemicalNames.put("helium_3", "Helium 3");
        chemicalNames.put("sulfuric_gas", "Sulfuric Gas");
        chemicalNames.put("refinery_gas", "Refinery Gas");
        chemicalNames.put("natural_gas", "Natural Gas");
        chemicalNames.put("coal_gas", "Coal Gas");
        chemicalNames.put("wood_gas", "Wood Gas");
        chemicalNames.put("hydro_cracked_butadiene", "Hydro Cracked Butadiene");
        chemicalNames.put("hydro_cracked_butane", "Hydro Cracked Butane");
        chemicalNames.put("hydro_cracked_butene", "Hydro Cracked Butene");
        chemicalNames.put("hydro_cracked_ethane", "Hydro Cracked Ethane");
        chemicalNames.put("hydro_cracked_ethylene", "Hydro Cracked Ethylene");
        chemicalNames.put("hydro_cracked_propane", "Hydro Cracked Propane");
        chemicalNames.put("hydro_cracked_propene", "Hydro Cracked Propene");
        chemicalNames.put("steam_cracked_butadiene", "Steam Cracked Butadiene");
        chemicalNames.put("steam_cracked_butane", "Steam Cracked Butane");
        chemicalNames.put("steam_cracked_butene", "Steam Cracked Butene");
        chemicalNames.put("steam_cracked_ethane", "Steam Cracked Ethane");
        chemicalNames.put("steam_cracked_ethylene", "Steam Cracked Ethylene");
        chemicalNames.put("steam_cracked_propane", "Steam Cracked Propane");
        chemicalNames.put("steam_cracked_propene", "Steam Cracked Propene");
        chemicalNames.put("lightly_hydro_cracked_gas", "Lightly Hydro Cracked Gas");
        chemicalNames.put("lightly_steam_cracked_gas", "Lightly Steam Cracked Gas");
        chemicalNames.put("severely_hydro_cracked_gas", "Severely Hydro Cracked Gas");
        chemicalNames.put("severely_steam_cracked_gas", "Severely Steam Cracked Gas");
        chemicalNames.put("uranium_hexafluoride", "Uranium Hexafluoride");
        chemicalNames.put("enriched_uranium_hexafluoride", "Enriched Uranium Hexafluoride");
        chemicalNames.put("depleted_uranium_hexafluoride", "Depleted Uranium Hexafluoride");
        for (ChemicalFluid chem : ChemicalFluid.values()) {
            String name = chemicalNames.get(chem.getId());
            add("fluid.poly_mech." + chem.getId(), name);
            // 所有化学流体都有桶（气体/等离子体为不可放置的桶）
            add("item.poly_mech." + chem.getId() + "_bucket", name + " Bucket");
        }

        // 熔融金属（每种材料一条，温度≈熔点，带桶）
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            String name = "Molten " + formatMaterialName(materialName);
            add("fluid.poly_mech.molten_" + materialName, name);
            add("item.poly_mech.molten_" + materialName + "_bucket", name + " Bucket");
        }

        // 等离子体（周期表全118元素，也有可盛装桶）
        for (ModElements element : ModElements.values()) {
            String plasmaName = formatMaterialName(element.getId()) + " Plasma";
            add("fluid.poly_mech." + element.getId() + "_plasma", plasmaName);
            add("item.poly_mech." + element.getId() + "_plasma_bucket", plasmaName + " Bucket");
        }

        // 金属存储块（仅有锭的材料，键为材料名）
        for (var entry : ModBlocks.MATERIAL_BLOCKS.entrySet()) {
            add(entry.getValue().get(), formatMaterialName(entry.getKey()) + " Block");
        }
        // tooltip管理中心：物态 / 温度 / 危险警示（化学式直接由ModTooltipCenter渲染，无需翻译键）
        add("tooltip.poly_mech.fluid.state_liquid", "State: Liquid");
        add("tooltip.poly_mech.fluid.state_gas", "State: Gas");
        add("tooltip.poly_mech.fluid.state_plasma", "State: Plasma");
        add("tooltip.poly_mech.fluid.temperature", "Temperature: %d K");
        add("tooltip.poly_mech.hazardous", "⚠ Hazardous");
        // 化学式成分百分比（Shift显示）
        add("tooltip.poly_mech.formula.shift_hint", "Hold SHIFT to show composition");
        add("tooltip.poly_mech.formula.composition", "Composition: ");
        add("tooltip.poly_mech.mineral.properties", "Mohs: %s | Density: %s g/cm³ | Crystal: %s | Genesis: %s");
        add("tooltip.poly_mech.mineral.process", "Process: %s");
        add("tooltip.poly_mech.crystal.cubic", "Cubic");
        add("tooltip.poly_mech.crystal.tetragonal", "Tetragonal");
        add("tooltip.poly_mech.crystal.hexagonal", "Hexagonal");
        add("tooltip.poly_mech.crystal.orthorhombic", "Orthorhombic");
        add("tooltip.poly_mech.crystal.monoclinic", "Monoclinic");
        add("tooltip.poly_mech.crystal.triclinic", "Triclinic");
        add("tooltip.poly_mech.crystal.amorphous", "Amorphous");
        add("tooltip.poly_mech.crystal.unknown", "Unknown");
        add("tooltip.poly_mech.genesis.magmatic", "Magmatic");
        add("tooltip.poly_mech.genesis.hydrothermal", "Hydrothermal");
        add("tooltip.poly_mech.genesis.sedimentary", "Sedimentary");
        add("tooltip.poly_mech.genesis.metamorphic", "Metamorphic");
        add("tooltip.poly_mech.genesis.weathering", "Weathering");
        add("tooltip.poly_mech.genesis.placer", "Placer");
        add("tooltip.poly_mech.genesis.evaporite", "Evaporite");
        add("tooltip.poly_mech.genesis.volcanic_hydrothermal", "Volcanic-Hydrothermal");

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
        add("itemGroup.mineral_tab", "Ploy Mech:Minerals");
        add("itemGroup.pipe_tab", "Ploy Mech:Pipes and Logistics");
        add("itemGroup.tool_tab", "Ploy Mech:Tool");
        add("itemGroup.fluid_cell_tab", "Ploy Mech:Fluid Cells");
        add("itemGroup.bucket_tab", "Ploy Mech:Fluid Buckets");
    }

    private String formatMaterialName(String name) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                // 下划线转为空格，下一个单词首字母大写（stainless_steel -> Stainless Steel）
                result.append(' ');
                capitalizeNext = true;
            } else {
                result.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return result.toString();
    }

    private String buildDisplayName(PipeMaterial material, PipeBlock.PipeSize size) {
        // 铁为默认材质，不加材料前缀
        String materialName = material == PipeMaterial.IRON ? ""
                : formatMaterialName(material.getName()) + " ";
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