package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 物理体上方块的**增量同步** —— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalBodyBlockUpdate} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 服务端改了物理体某个 64³ 子区块的方块后，把那一块发过来，客户端替换掉自己那份快照。
 *
 * <h2>为什么是这个形态（照 space 0.1.3，逐条都有理由）</h2>
 * <ul>
 *   <li><b>按 64³ 子区块整块替换，而不是逐格增量</b>：物理体一格的改动会连带影响
 *       碰撞体与渲染烘焙，整块替换让客户端只需重建一格；而体量（64³ = 26 万格）在网络上是
 *       问题 —— 所以下面有 RLE。</li>
 *   <li><b>RLE 行程编码</b>（{@link #compress} / {@link #decompress}）：物理体绝大部分是空气或
 *       同一种方块，RLE 后往往几十个 int 就能表达一整块。末尾额外放一个
 *       {@code [xSize,ySize,zSize]} 作为"尺寸哨兵"，否则解码方无从知道网格形状。</li>
 *   <li><b>坐标用 {@code writeByte}</b>：子块索引只在 -1/0（见 {@code PhysicalBody} 的 2×2×2 布局），
 *       一个字节足够 —— 这也是"物理体坐标是 -64..63 中心制"的连带好处。</li>
 *   <li><b>在 {@code ctx.enqueueWork} 里改客户端世界</b>：网络线程不能碰世界状态。</li>
 * </ul>
 *
 * <p><b>与 MPS 的唯一差异</b>：命名空间用本项目的 {@code poly_mech}（不占用 space 的
 * {@code space:} 命名空间；两端都是我们自己，这样也不会与真正装了 space 的环境撞名）。
 * 另外补了一个空世界保护 —— MPS 在 {@code getPhysicalWorld()} 为 null 时会 NPE，
 * 而我们不让"包比世界先到"这种时序问题崩掉客户端。</p>
 */
public record SyncPhysicalBodyBlockUpdate(UUID uuid, Vector3i pos, int[][][] data) implements CustomPacketPayload {

    public static final Type<SyncPhysicalBodyBlockUpdate> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_body_block"));

    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalBodyBlockUpdate> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalBodyBlockUpdate::encode, SyncPhysicalBodyBlockUpdate::decode);

    public static void encode(SyncPhysicalBodyBlockUpdate packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.uuid);
        Vector3i pos = packet.pos;
        buffer.writeByte(pos.x);
        buffer.writeByte(pos.y);
        buffer.writeByte(pos.z);
        List<int[]> compressed = compress(packet.data);
        buffer.writeInt(compressed.size());
        for (int[] run : compressed) {
            buffer.writeVarIntArray(run);
        }
    }

    /**
     * 打平成 (x,y,z) 顺序后做 RLE；末尾附 {@code [xSize,ySize,zSize]} 作为尺寸哨兵。
     */
    public static List<int[]> compress(int[][][] src) {
        int xSize = src.length;
        int ySize = src[0].length;
        int zSize = src[0][0].length;
        int[] flat = new int[xSize * ySize * zSize];
        int idx = 0;
        for (int[][] slice : src) {
            for (int y = 0; y < ySize; y++) {
                System.arraycopy(slice[y], 0, flat, idx, zSize);
                idx += zSize;
            }
        }

        List<int[]> out = new ArrayList<>();
        int last = flat[0];
        int count = 1;
        for (int i = 1; i < flat.length; i++) {
            if (flat[i] == last) {
                count++;
            } else {
                out.add(new int[]{last, count});
                last = flat[i];
                count = 1;
            }
        }
        out.add(new int[]{last, count});
        out.add(new int[]{xSize, ySize, zSize});
        return out;
    }

    public static SyncPhysicalBodyBlockUpdate decode(FriendlyByteBuf buffer) {
        UUID uuid = buffer.readUUID();
        Vector3i pos = new Vector3i(buffer.readByte(), buffer.readByte(), buffer.readByte());
        List<int[]> runs = new ArrayList<>();
        int size = buffer.readInt();
        for (int i = 0; i < size; i++) {
            runs.add(buffer.readVarIntArray());
        }
        return new SyncPhysicalBodyBlockUpdate(uuid, pos, decompress(runs));
    }

    /** RLE 还原成 {@code int[xSize][ySize][zSize]}。 */
    public static int[][][] decompress(List<int[]> rle) {
        int[] size = rle.getLast();
        int xSize = size[0];
        int ySize = size[1];
        int zSize = size[2];
        int limit = rle.size() - 1;
        int[] flat = new int[xSize * ySize * zSize];
        int idx = 0;
        for (int i = 0; i < limit; i++) {
            int[] run = rle.get(i);
            int value = run[0];
            int count = run[1];
            for (int c = 0; c < count; c++) {
                flat[idx++] = value;
            }
        }
        int[][][] out = new int[xSize][ySize][zSize];
        idx = 0;
        for (int x = 0; x < xSize; x++) {
            for (int y = 0; y < ySize; y++) {
                System.arraycopy(flat, idx, out[x][y], 0, zSize);
                idx += zSize;
            }
        }
        return out;
    }

    public static void handle(SyncPhysicalBodyBlockUpdate packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientPhysicalWorld world = ClientPhysicalWorld.getPhysicalWorld();
            if (world == null) {
                // 客户端物理世界还没建好（包先到）——丢弃即可，下一次全量同步会补上
                return;
            }
            PhysicalBody physicalBody = world.getPhysicalBody(packet.uuid);
            if (physicalBody != null) {
                physicalBody.setBlockStateChunk(packet.pos.x, packet.pos.y, packet.pos.z, packet.data);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
