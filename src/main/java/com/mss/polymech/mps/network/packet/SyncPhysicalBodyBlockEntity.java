package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 物理体方块实体同步 —— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalBodyBlockEntity} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把一个 64³ 子块里的<b>方块实体</b>（NBT）发给客户端，让箱子/机器在船上是"真的"。
 *
 * <h2>为什么要单独一个包（照 space 0.1.3）</h2>
 * {@code SyncPhysicalBodyBlockUpdate} 只发<b>方块状态调色板</b>（{@code int[][][]} id），
 * 那个压缩形式表达不了"这个位置有一个箱子、里面装着什么"。而船上的箱子必须能开、
 * 内容要一致 —— 所以方块实体只能单独发 NBT。
 * <p>两者是<b>配对</b>的：客户端先按状态包铺好方块（否则
 * {@link BlockEntity#loadStatic} 拿不到正确的 {@code BlockState}），
 * 再按本包放方块实体。所以本包必须<b>后</b>处理。</p>
 *
 * <h2>为什么用 {@code byte} 写区块坐标</h2>
 * 物理体的方块网格只有 2×2×2 个 64³ 子块（{@code chunkOffset = 1}，取值 −1/0），
 * 一个 byte 绰绰有余。这不是省字节，而是<b>把取值范围写进协议</b>：读出来只可能是小的
 * 有符号值，客户端 {@code pos * 64} 之后仍然落在局部坐标范围内。
 *
 * <p>方块实体的局部坐标直接发 {@link BlockPos}（已经是相对投影原点换算过的局部值），
 * 不在这里再做换算 —— 换算统一由 {@code ProjectionManager#readChunk} 负责。</p>
 */
public record SyncPhysicalBodyBlockEntity(UUID uuid, Vector3i pos, List<Entry> entries)
        implements CustomPacketPayload {

    public static final Type<SyncPhysicalBodyBlockEntity> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_body_block_entity"));
    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalBodyBlockEntity> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalBodyBlockEntity::encode, SyncPhysicalBodyBlockEntity::decode);

    public static void encode(SyncPhysicalBodyBlockEntity packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.uuid);
        buffer.writeByte(packet.pos.x);
        buffer.writeByte(packet.pos.y);
        buffer.writeByte(packet.pos.z);
        buffer.writeVarInt(packet.entries.size());
        for (Entry entry : packet.entries) {
            buffer.writeBlockPos(entry.pos);
            buffer.writeNbt(entry.tag);
        }
    }

    public static SyncPhysicalBodyBlockEntity decode(FriendlyByteBuf buffer) {
        UUID uuid = buffer.readUUID();
        Vector3i pos = new Vector3i(buffer.readByte(), buffer.readByte(), buffer.readByte());
        int size = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(buffer.readBlockPos(), buffer.readNbt()));
        }
        return new SyncPhysicalBodyBlockEntity(uuid, pos, entries);
    }

    public static void handle(SyncPhysicalBodyBlockEntity packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientPhysicalWorld world = ClientPhysicalWorld.getPhysicalWorld();
            if (world == null) {
                return;
            }
            PhysicalBody physicalBody = world.getPhysicalBody(packet.uuid);
            if (physicalBody == null) {
                return;
            }
            // 先清掉这个 64³ 子块里旧的方块实体，再按收到的 NBT 全部重建：
            // 服务端发的是"这个子块的完整现状"，增量式更新会留下已经拆掉的箱子
            int width = 64;
            BlockPos localMin = new BlockPos(packet.pos.x * width, packet.pos.y * width, packet.pos.z * width);
            BlockPos localMax = localMin.offset(width - 1, width - 1, width - 1);
            physicalBody.clearBlockEntities(localMin, localMax);

            for (Entry entry : packet.entries) {
                BlockPos pos = entry.pos;
                // 状态必须先存在（由 SyncPhysicalBodyBlockUpdate 铺好），否则解出来的方块实体类型是错的
                BlockState state = physicalBody.getBlockState(pos.getX(), pos.getY(), pos.getZ());
                BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, entry.tag,
                        Minecraft.getInstance().level.registryAccess());
                if (blockEntity != null) {
                    physicalBody.putBlockEntity(pos, blockEntity);
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 一条方块实体：局部坐标 + 带位置元数据的 NBT。 */
    public static record Entry(BlockPos pos, CompoundTag tag) {
    }
}
