package com.mss.polymech;

import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.command.ModCommands;
import com.mss.polymech.fluid.FluidCellFluidHandler;
import com.mss.polymech.fluid.ModChemicalFluids;
import com.mss.polymech.fluid.ModElementFluids;
import com.mss.polymech.fluid.ModFluidBuckets;
import com.mss.polymech.fluid.ModFluids;
import com.mss.polymech.tooltip.ModTooltipCenter;
import com.mss.polymech.techtree.TechTree;
import com.mss.polymech.item.FluidCellItem;
import com.mss.polymech.item.ModCreativeModeTabs;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.entity.ModEntities;
import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.boiler.AbstractSteamBoilerBlockEntity;
import com.mss.polymech.machine.common.MachineRegistry;
import com.mss.polymech.machine.common.MultiTankFluidHandler;
import com.mss.polymech.machine.common.SlotFilteredItemHandler;
import com.mss.polymech.menu.ModMenuTypes;
import com.mss.polymech.pipenet.PipeFluidHandler;
import com.mss.polymech.recipe.ModRecipeTypes;
import com.mss.polymech.worldgen.ModFeatures;
import com.mss.polymech.block.entity.ConveyorBlockEntity;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.network.BatteryTogglePacket;
import com.mss.polymech.network.SideConfigPacket;
import com.mss.polymech.network.AutoEjectPacket;
import com.mss.polymech.network.BatchConfigPacket;
import com.mss.polymech.network.ConveyorPlacementPacket;
import com.mss.polymech.network.PipePlacementPacket;
import com.mss.polymech.network.MachinePlacementPacket;
import com.mss.polymech.network.MachineTogglePacket;
import com.mss.polymech.network.SetCellCapacityPacket;
import com.mss.polymech.network.SpaceTransitionSyncPacket;
import com.mss.polymech.space.SpaceTransitionHandler;
import com.mss.polymech.network.TeleportToPlanetPacket;
import com.mss.polymech.network.WireSyncPacket;
import com.mss.polymech.network.ClampMeterMeasurementPacket;
import com.mss.polymech.machine.production.BatteryBlockEntity;
import com.mss.polymech.powergrid.MachineEnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/*
 * Poly Mech模組主类，负责模组的初始化和生命周期管理。
 * <p>
 * 该类是模組的入口点，由NeoForge在模組加载时自动实例化。
 * 主要职责包括：
 * <ul>
 *   <li>注册所有游戏内容（物品、方块、实体等）</li>
 *   <li>配置网络通信协议</li>
 *   <li>处理模組生命周期事件</li>
 *   <li>注册创造模式标签页</li>
 * </ul>
 * </p>
 * 
 * <h2>初始化顺序：</h2>
 * <ol>
 *   <li>构造函数执行，注册各类内容到事件总线</li>
 *   <li>FMLCommonSetupEvent触发，执行通用设置</li>
 *   <li>ServerStartingEvent触发，服务端启动完成</li>
 * </ol>
 * 
 * @see ModItems
 * @see ModBlocks
 * @see Config
 */
@Mod(Polymech.MOD_ID)
public class Polymech {
    /** 模組唯一标识符，必须与META-INF/neoforge.mods.toml中的值匹配 */
    public static final String MOD_ID = "poly_mech";
    
    /** 模組日志记录器 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /*
     * 模組构造函数，由NeoForge在模組加载时调用。
     * <p>
     * FML会自动注入IEventBus和ModContainer参数。
     * 在此处注册所有游戏内容和事件监听器。
     * </p>
     * 
     * @param modEventBus 模組事件总线，用于注册生命周期事件
     * @param modContainer 模組容器，用于注册配置等
     */
    public Polymech(IEventBus modEventBus, ModContainer modContainer) {
        // 注册通用设置事件监听器
        modEventBus.addListener(this::commonSetup);
        
        // 注册游戏内容
        ModItems.register(modEventBus);
        ModDataComponents.register(modEventBus);
        ModBlocks.register(modEventBus);
        // 世界生成：自定义Feature（矿脉/岩层）
        ModFeatures.register(modEventBus);
        ModFluids.register(modEventBus);
        ModChemicalFluids.register(modEventBus);
        ModElementFluids.register(modEventBus);
        ModEntities.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModRecipeTypes.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        
        // 注册网络数据包处理器
        modEventBus.addListener(this::registerPayloads);

        // 注册能力（漏斗交互）
        modEventBus.addListener(this::registerCapabilities);

        // 注册NeoForge事件总线（用于服务器事件等）
        // 注意：只有当此类包含@SubscribeEvent注解的方法时才需要此行
        NeoForge.EVENT_BUS.register(this);

        // 地球 ↔ 太空无缝切换
        NeoForge.EVENT_BUS.register(SpaceTransitionHandler.class);

        // 勘探命令套件（/polymech rock|veins|scan|find|expose，世界生成测试工具）
        NeoForge.EVENT_BUS.addListener(ModCommands::register);

        // tooltip管理中心（化学流体信息 + 材料/原版矿物化学式）
        ModTooltipCenter.register();

        // 注册创造模式标签页内容事件
        modEventBus.addListener(this::addCreative);

        // 注册模組配置
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    /*
     * 通用设置事件处理器。
     * <p>
     * 在模組加载的早期阶段执行，用于执行不依赖于客户端/服务端的初始化代码。
     * </p>
     * 
     * @param event 通用设置事件
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        // 科技树：播种示例节点（声明式，动态推导连线与布局）
        TechTree.bootstrap();
    }

    /*
     * 注册网络数据包处理器。
     * <p>
     * 定义客户端到服务器的网络通信协议。
     * </p>
     * 
     * @param event 数据包注册事件
     */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(
                PipePlacementPacket.TYPE,
                PipePlacementPacket.STREAM_CODEC,
                PipePlacementPacket::handle
        );
        registrar.playToServer(
                ConveyorPlacementPacket.TYPE,
                ConveyorPlacementPacket.STREAM_CODEC,
                ConveyorPlacementPacket::handle
        );
        registrar.playToServer(
                MachinePlacementPacket.TYPE,
                MachinePlacementPacket.STREAM_CODEC,
                MachinePlacementPacket::handle
        );
        registrar.playToServer(
                MachineTogglePacket.TYPE,
                MachineTogglePacket.STREAM_CODEC,
                MachineTogglePacket::handle
        );
        registrar.playToServer(
                SetCellCapacityPacket.TYPE,
                SetCellCapacityPacket.STREAM_CODEC,
                SetCellCapacityPacket::handle
        );
        registrar.playToServer(
                BatteryTogglePacket.TYPE,
                BatteryTogglePacket.STREAM_CODEC,
                BatteryTogglePacket::handle
        );
        registrar.playToServer(
                SideConfigPacket.TYPE,
                SideConfigPacket.STREAM_CODEC,
                SideConfigPacket::handle
        );
        registrar.playToServer(
                AutoEjectPacket.TYPE,
                AutoEjectPacket.STREAM_CODEC,
                AutoEjectPacket::handle
        );
        registrar.playToServer(
                BatchConfigPacket.TYPE,
                BatchConfigPacket.STREAM_CODEC,
                BatchConfigPacket::handle
        );
        registrar.playToServer(
                TeleportToPlanetPacket.TYPE,
                TeleportToPlanetPacket.STREAM_CODEC,
                TeleportToPlanetPacket::handle
        );
        // 电网电线连接同步（服务端 → 客户端，登录/连接变化时推送渲染数据）
        registrar.playToClient(
                WireSyncPacket.TYPE,
                WireSyncPacket.STREAM_CODEC,
                WireSyncPacket::handle
        );
        // 钳形表测量结果（服务端 → 客户端）
        registrar.playToClient(
                ClampMeterMeasurementPacket.TYPE,
                ClampMeterMeasurementPacket.STREAM_CODEC,
                ClampMeterMeasurementPacket::handle
        );
        // 无缝切换位置同步（服务端 → 客户端）
        registrar.playToClient(
                SpaceTransitionSyncPacket.TYPE,
                SpaceTransitionSyncPacket.STREAM_CODEC,
                SpaceTransitionSyncPacket::handle
        );
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.CONVEYOR.get(),
                ConveyorBlockEntity::getItemHandler
        );

        // 流体储罐流体能力（供管道管网作为端点交互）
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.FLUID_TANK.get(),
                (tankBE, side) -> tankBE.getFluidHandler()
        );

        // 管道流体能力：无 BlockEntity，转发给世界级管网（WorldPipeNet）
        Block[] pipeBlocks = ModBlocks.PIPE_BLOCKS.stream()
                .map(deferred -> (Block) deferred.get())
                .toArray(Block[]::new);
        event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, blockEntity, context) -> {
            if (level.isClientSide()) return null;
            return new PipeFluidHandler((ServerLevel) level, pos, context);
        }, pipeBlocks);

        // 通过MachineRegistry统一注册所有大型机器的物品和流体能力
        for (MachineRegistry.MachineEntry entry : MachineRegistry.getEntries()) {
            var mainBE = entry.mainBlockEntity();
            var sideBE = entry.sideBlockEntity();
            if (mainBE == null || sideBE == null) continue;

            // ===== 主方块: 能量能力（FE） =====
            event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, blockEntity, context) -> {
                if (blockEntity instanceof BaseIOBlockEntity be) {
                    // 面配置检查：有方向查询时，该面必须配置了能量 IO
                    if (context != null && !be.isSideConfigAllowed(context, com.mss.polymech.machine.SideConfig.CapabilityType.ENERGY)) {
                        return null;
                    }
                    return be.getEnergyStorage();
                }
                return null;
            }, entry.mainBlock().get());

            // ===== 主方块: 物品能力 =====
            event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, blockEntity, context) -> {
                if (blockEntity instanceof BaseIOBlockEntity be) {
                    if (context == null) return be.getItemStackHandler();
                    return null; // 主方块自己不通过方向暴露物品IO，由侧面仓处理
                }
                return null;
            }, entry.mainBlock().get());

            // ===== 侧面方块: Block定义槽位/方向 → 能力层包装 =====

            // 侧面方块物品能力
            event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, blockEntity, context) -> {
                if (blockEntity instanceof BaseIOSideBlockEntity sideEntity) {
                    BlockPos parentPos = sideEntity.getParentPos();
                    if (parentPos != null) {
                        Block parentBlock = level.getBlockState(parentPos).getBlock();
                        if (parentBlock instanceof BaseMachineBlock machineBlock) {
                            Vec3i offset = new Vec3i(pos.getX() - parentPos.getX(), pos.getY() - parentPos.getY(), pos.getZ() - parentPos.getZ());
                            Direction facing = level.getBlockState(parentPos).getValue(BaseMachineBlock.FACING);
                            Vec3i local = BaseMachineBlock.unrotateVec3i(offset, facing);
                            // Block 声明位置→物品代理（槽位 + IO 方向 + 有效面）
                            var proxy = machineBlock.getItemProxy(local);
                            if (proxy == null) return null;
                            // 声明了有效面时，仅允许从指定面访问（context==null 查询不过滤）
                            if (!proxy.allowsWorldFace(context, facing)) return null;
                            // 面配置检查：父方块的 ITEM 面配置必须允许该方向
                            var parent = sideEntity.getParentBlock();
                            if (parent instanceof BaseIOBlockEntity be) {
                                if (context != null && !be.isSideConfigAllowed(context, com.mss.polymech.machine.SideConfig.CapabilityType.ITEM)) {
                                    return null;
                                }
                                return new SlotFilteredItemHandler(be.getItemStackHandler(), proxy.slots());
                            }
                        }
                    }
                }
                return null;
            }, entry.sideBlock().get());

            // 侧面方块流体能力
            event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, blockEntity, context) -> {
                if (blockEntity instanceof BaseIOSideBlockEntity sideEntity) {
                    BlockPos parentPos = sideEntity.getParentPos();
                    if (parentPos != null) {
                        Block parentBlock = level.getBlockState(parentPos).getBlock();
                        if (parentBlock instanceof BaseMachineBlock machineBlock) {
                            Vec3i offset = new Vec3i(pos.getX() - parentPos.getX(), pos.getY() - parentPos.getY(), pos.getZ() - parentPos.getZ());
                            Direction facing = level.getBlockState(parentPos).getValue(BaseMachineBlock.FACING);
                            Vec3i local = BaseMachineBlock.unrotateVec3i(offset, facing);
                            // Block 声明位置→流体代理（储罐 + IO 方向 + 有效面）
                            var proxy = machineBlock.getFluidProxy(local);
                            if (proxy == null) return null;
                            // 声明了有效面时，仅允许从指定面访问（context==null 查询不过滤）
                            if (!proxy.allowsWorldFace(context, facing)) return null;
                            // 面配置检查：父方块的 FLUID 面配置必须允许该方向
                            var parent = sideEntity.getParentBlock();
                            if (parent instanceof BaseIOBlockEntity be) {
                                if (context != null && !be.isSideConfigAllowed(context, com.mss.polymech.machine.SideConfig.CapabilityType.FLUID)) {
                                    return null;
                                }
                                int[] tanks = proxy.tanks();
                                if (tanks.length == 0) return null;
                                IFluidHandler[] handlers = new IFluidHandler[tanks.length];
                                for (int i = 0; i < tanks.length; i++) {
                                    handlers[i] = be.getFluidTank(tanks[i]);
                                    if (handlers[i] == null) return null;
                                }
                                return handlers.length == 1 ? handlers[0] : new MultiTankFluidHandler(handlers);
                            }
                        }
                    }
                }
                return null;
            }, entry.sideBlock().get());

            // 主方块流体能力（兼容直接交互）
            event.registerBlock(Capabilities.FluidHandler.BLOCK, (level, pos, state, blockEntity, context) -> {
                if (blockEntity instanceof AbstractSteamBoilerBlockEntity boiler) {
                    Direction facing = state.getValue(BaseMachineBlock.FACING);
                    if (context == null) return boiler.getSteamOutputHandler();
                    if (context == facing.getOpposite()) return boiler.getWaterInputHandler();
                    if (context == facing) return boiler.getSteamOutputHandler();
                }
                return null;
            }, entry.mainBlock().get());
        }

        // 蓄电池能量能力
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.BATTERY.get(),
                (batteryBE, side) -> {
                    if (batteryBE instanceof BatteryBlockEntity be) {
                        return new MachineEnergyStorage(
                                be::getEnergyStored,
                                newVal -> { be.receiveCharge(newVal - be.getEnergyStored()); return be.getEnergyStored(); },
                                be.getMaxEnergy(),
                                be.getMaxChargeRate(),
                                be.getMaxDischargeRate()
                        );
                    }
                    return null;
                });
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.CREATIVE_BATTERY.get(),
                (batteryBE, side) -> {
                    if (batteryBE instanceof BatteryBlockEntity be) {
                        return new MachineEnergyStorage(
                                be::getEnergyStored,
                                newVal -> be.getEnergyStored(),
                                be.getMaxEnergy(),
                                0,
                                be.getMaxDischargeRate()
                        );
                    }
                    return null;
                });

        // 通用流体单元物品流体能力：流体内容存储在fluid_content数据组件中，
        // 生效容量尊重玩家设置的capacity_limit上限（四种规格单元均注册）
        event.registerItem(
                Capabilities.FluidHandler.ITEM,
                (stack, context) -> new FluidCellFluidHandler(stack, FluidCellItem.getMaxCapacity(stack)),
                ModItems.ALL_FLUID_CELLS.stream().map(def -> (Item) def.get()).toArray(Item[]::new)
        );

        // 桶物品流体能力：NeoForge默认只为精确BucketItem类注册FluidBucketWrapper，
        // 我们的ChemicalBucketItem等子类不会被自动覆盖，需手动注册，
        // 否则fluid_container模型读不到桶内流体 -> 流体层不渲染颜色
        java.util.List<Item> bucketItems = new java.util.ArrayList<>();
        for (var entry : ModFluidBuckets.getAll()) {
            bucketItems.add(entry.item());
        }
        if (!bucketItems.isEmpty()) {
            event.registerItem(
                    Capabilities.FluidHandler.ITEM,
                    (stack, context) -> new FluidBucketWrapper(stack),
                    bucketItems.toArray(Item[]::new)
            );
        }
    }

    /*
     * 添加创造模式标签页内容。
     * <p>
     * 注意：当前实现为空，实际内容由ModCreativeModeTabs处理。
     * 保留此方法作为扩展点。
     * </p>
     * 
     * @param event 创造模式标签页内容构建事件
     */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        // 创造模式标签页内容由ModCreativeModeTabs统一管理
    }

    /*
     * 服务器启动事件处理器。
     * <p>
     * 在服务端启动完成后触发，可用于执行服务端特定的初始化。
     * </p>
     * 
     * @param event 服务器启动事件
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // 服务端启动钩子（暂无需初始化内容）
    }
}
