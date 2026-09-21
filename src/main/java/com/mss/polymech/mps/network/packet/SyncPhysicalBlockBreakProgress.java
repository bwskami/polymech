package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.client.PhysicalBodyInteractionClient;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 物理体方块挖掘进度（服务端 → 客户端）—— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalBlockBreakProgress} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 告诉客户端"这一格已经被挖掉多少了"，用来画裂纹。
 *
 * <h2>为什么进度必须由服务端算（照 space 0.1.3）</h2>
 * 破坏进度取决于工具、附魔、方块硬度与玩家状态，这些<b>权威都在服务端</b>。
 * 客户端本地推一份只能算"手感"，一旦与服务端不同步，
 * 就会出现"裂纹满了但方块没掉"或反之。所以：
 * <ul>
 *   <li>裂纹按<b>服务端</b>进度画（见 {@code PhysicalBodyInteractionClient}）；</li>
 *   <li>进度为 <b>{@code -1}</b> 是<b>终止信号</b>：方块已破坏或中止，客户端要立刻清掉裂纹
 *       —— 这就是为什么字段是 float 而不是 0..1 的无符号比例。</li>
 * </ul>
 *
 * <p>坐标是<b>物理体局部坐标</b>（相对 128³ 投影盒中心），不是世界坐标 ——
 * 船上方块在世界里没有稳定位置。</p>
 */
public record SyncPhysicalBlockBreakProgress(UUID physicalBodyId, BlockPos localPos, float progress)
        implements CustomPacketPayload {

    public static final Type<SyncPhysicalBlockBreakProgress> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_block_break_progress"));
    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalBlockBreakProgress> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalBlockBreakProgress::encode, SyncPhysicalBlockBreakProgress::decode);

    private static void encode(SyncPhysicalBlockBreakProgress packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.physicalBodyId);
        buffer.writeBlockPos(packet.localPos);
        buffer.writeFloat(packet.progress);
    }

    private static SyncPhysicalBlockBreakProgress decode(FriendlyByteBuf buffer) {
        return new SyncPhysicalBlockBreakProgress(buffer.readUUID(), buffer.readBlockPos(), buffer.readFloat());
    }

    public static void handle(SyncPhysicalBlockBreakProgress packet, IPayloadContext context) {
        context.enqueueWork(() -> PhysicalBodyInteractionClient.syncMiningProgress(
                packet.physicalBodyId, packet.localPos, packet.progress));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
