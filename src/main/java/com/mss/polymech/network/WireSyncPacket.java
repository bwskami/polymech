package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.client.renderer.ClientWireCache;
import com.mss.polymech.powergrid.GridConnection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 电线连接同步包（服务端 → 客户端）。
 * <p>
 * 服务端电网结构变化（连接/断开/方块移除）或玩家登录时发送，
 * 客户端据此更新 {@link ClientWireCache} 渲染缓存。
 * </p>
 *
 * @param connections 连接列表（增量或全量）
 * @param remove      true=移除这些连接；false=添加这些连接
 * @param fullSync    true=全量覆盖（登录时发送的完整电网快照）
 */
public record WireSyncPacket(List<GridConnection> connections, boolean remove, boolean fullSync) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<WireSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "wire_sync"));

    /**
     * 手写编解码器：VarInt 数量前缀 + 逐条连接编码，避免不同版本 ByteBufCodecs.list 重载差异。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, WireSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WireSyncPacket decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            List<GridConnection> conns = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                conns.add(GridConnection.STREAM_CODEC.decode(buf));
            }
            return new WireSyncPacket(conns, buf.readBoolean(), buf.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, WireSyncPacket packet) {
            buf.writeVarInt(packet.connections().size());
            for (GridConnection c : packet.connections()) {
                GridConnection.STREAM_CODEC.encode(buf, c);
            }
            buf.writeBoolean(packet.remove());
            buf.writeBoolean(packet.fullSync());
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端收到包时更新电线渲染缓存（仅客户端执行） */
    public static void handle(WireSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 先把缓存锚定到当前客户端世界：登录全量包先于首个渲染帧到达，若不先锚定，
            // 渲染器的ensureLevel会把它当成维度切换清掉刚同步的数据
            // （症状：重进存档时连接仍生效但电线看不见）
            if (context.player().level() instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel)
                ClientWireCache.ensureLevel(clientLevel);
            if (packet.fullSync()) {
                ClientWireCache.replaceAll(packet.connections());
            } else if (packet.remove()) {
                ClientWireCache.removeAll(packet.connections());
            } else {
                ClientWireCache.addAll(packet.connections());
            }
        });
    }
}
