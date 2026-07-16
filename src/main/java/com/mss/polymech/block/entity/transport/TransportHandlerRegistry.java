package com.mss.polymech.block.entity.transport;

import com.mss.polymech.block.ConveyorType;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * 传送带运输策略注册表。
 * <p>
 * 单例模式，按 ConveyorType 缓存对应的策略实例。
 * 遵循策略模式 + 工厂模式的组合，将策略选择逻辑集中管理。
 * </p>
 */
public final class TransportHandlerRegistry {

    /** 单例实例 */
    private static final TransportHandlerRegistry INSTANCE = new TransportHandlerRegistry();

    /** 策略缓存映射 */
    private final Map<ConveyorType, IItemTransportHandler> handlerMap;

    private TransportHandlerRegistry() {
        handlerMap = new EnumMap<>(ConveyorType.class);
        handlerMap.put(ConveyorType.HORIZONTAL, new HorizontalTransportHandler());
        handlerMap.put(ConveyorType.UP, new UpSlopeTransportHandler());
        handlerMap.put(ConveyorType.DOWN, new DownSlopeTransportHandler());
    }

    /**
     * 获取对应传送带类型的运输策略。
     *
     * @param type 传送带类型
     * @return 对应的运输策略，不会为 null
     * @throws IllegalArgumentException 如果类型未注册
     */
    @NotNull
    public static IItemTransportHandler getHandler(ConveyorType type) {
        IItemTransportHandler handler = INSTANCE.handlerMap.get(type);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for conveyor type: " + type);
        }
        return handler;
    }
}