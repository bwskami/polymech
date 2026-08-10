package com.mss.polymech.client.renderer;

import com.mss.polymech.powergrid.GridConnection;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客户端电线连接缓存。
 * <p>
 * 仅保存渲染所需的最小数据（连接 + 电线类型），由 {@link com.mss.polymech.network.WireSyncPacket}
 * 在收到服务端同步时更新，由 {@link WireRenderer} 每帧读取渲染。
 * 线程安全（网络线程与渲染线程分离）。
 * </p>
 */
public final class ClientWireCache {

    private static final List<GridConnection> CONNECTIONS = new CopyOnWriteArrayList<>();

    /** 上次同步所属的世界（维度切换时自动清空） */
    private static ClientLevel cachedLevel;

    private ClientWireCache() {}

    /**
     * 全量覆盖（登录同步）。
     */
    public static void replaceAll(List<GridConnection> connections) {
        CONNECTIONS.clear();
        for (GridConnection c : connections) {
            if (!CONNECTIONS.contains(c)) {
                CONNECTIONS.add(c);
            }
        }
    }

    /** 增量添加（新连接） */
    public static void addAll(List<GridConnection> connections) {
        for (GridConnection c : connections) {
            if (!CONNECTIONS.contains(c)) {
                CONNECTIONS.add(c);
            }
        }
    }

    /** 增量移除（断开/方块破坏） */
    public static void removeAll(List<GridConnection> connections) {
        CONNECTIONS.removeAll(connections);
    }

    /** 当前世界变化时清空缓存（维度切换后等待服务端重新全量同步） */
    public static void ensureLevel(ClientLevel level) {
        if (cachedLevel != level) {
            cachedLevel = level;
            CONNECTIONS.clear();
        }
    }

    /** 全部连接（只读迭代） */
    public static List<GridConnection> getAll() {
        return CONNECTIONS;
    }
}
