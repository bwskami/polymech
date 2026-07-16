package com.mss.polymech;

import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.item.ModCreativeModeTabs;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.entity.ModEntities;
import com.mss.polymech.menu.ModMenuTypes;
import com.mss.polymech.network.PipePlacementPacket;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
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
 * Poly Mech模组主类，负责模组的初始化和生命周期管理。
 * <p>
 * 该类是模组的入口点，由NeoForge在模组加载时自动实例化。
 * 主要职责包括：
 * <ul>
 *   <li>注册所有游戏内容（物品、方块、实体等）</li>
 *   <li>配置网络通信协议</li>
 *   <li>处理模组生命周期事件</li>
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
    /** 模组唯一标识符，必须与META-INF/neoforge.mods.toml中的值匹配 */
    public static final String MOD_ID = "poly_mech";
    
    /** 模组日志记录器 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /*
     * 模组构造函数，由NeoForge在模组加载时调用。
     * <p>
     * FML会自动注入IEventBus和ModContainer参数。
     * 在此处注册所有游戏内容和事件监听器。
     * </p>
     * 
     * @param modEventBus 模组事件总线，用于注册生命周期事件
     * @param modContainer 模组容器，用于注册配置等
     */
    public Polymech(IEventBus modEventBus, ModContainer modContainer) {
        // 注册通用设置事件监听器
        modEventBus.addListener(this::commonSetup);
        
        // 注册游戏内容
        ModItems.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModEntities.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        
        // 注册网络数据包处理器
        modEventBus.addListener(this::registerPayloads);

        // 注册NeoForge事件总线（用于服务器事件等）
        // 注意：只有当此类包含@SubscribeEvent注解的方法时才需要此行
        NeoForge.EVENT_BUS.register(this);

        // 注册创造模式标签页内容事件
        modEventBus.addListener(this::addCreative);

        // 注册模组配置
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    /*
     * 通用设置事件处理器。
     * <p>
     * 在模组加载的早期阶段执行，用于执行不依赖于客户端/服务端的初始化代码。
     * </p>
     * 
     * @param event 通用设置事件
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");

        // 示例：读取配置并输出日志
        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
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
        LOGGER.info("HELLO from server starting");
    }
}
