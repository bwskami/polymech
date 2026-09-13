package com.mss.polymech.client.physics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物理体渲染：把服务端同步来的方块快照画出来。
 *
 * <p><b>做法</b>（思路来自 space/MPS 的"烘焙一次、每帧廉价重放"，但走的是我们已验证的渲染通道）：
 * 每个物理体缓存一份"已经算好的四边形"—— 模型查找、邻面剔除、染色、光照取样
 * 只在方块集合变化时做一次；每帧只做 {@code pose 平移 + putBulkData}，
 * 不再每帧调用 {@code renderBatched}（那里面有模型查找、quad 遍历、光照位置取样）。</p>
 *
 * <p>为什么不用 GL 顶点缓冲：那需要自己管理矩阵/着色器/渲染状态，写错了直接看不见 ——
 * 已经踩过一次。这里改成"缓存数据、复用现成通道"，把风险压到零。</p>
 *
 * <p>已知简化（与 space 一致）：光值固定 240（满天光），移动后的建筑光照不随位置变化。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PhysicsBodyRenderer {

    private static final float[] ROT = new float[4];

    /** bodyId → 缓存的四边形。 */
    private static final Map<Long, CachedBody> CACHE = new HashMap<>();

    private PhysicsBodyRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (ClientPhysicsWorld.size() == 0) {
            if (!CACHE.isEmpty()) {
                CACHE.clear();
            }
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        PoseStack pose = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();

        Set<Long> live = new HashSet<>();
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            // 照 MPS：直接取刚体**当前**状态（步进线程 100Hz 更新），不做插值。
            // 在 20Hz 网络包之间插值只有 20Hz 的信息量，正是"一抽一抽"的来源。
            double[] livePos = new double[3];
            float[] liveRot = new float[4];
            boolean hasLive = ClientPhysics.liveTransform(body.id(), livePos, liveRot);
            double bodyX = hasLive ? livePos[0] : body.renderX(partialTick);
            double bodyY = hasLive ? livePos[1] : body.renderY(partialTick);
            double bodyZ = hasLive ? livePos[2] : body.renderZ(partialTick);

            pose.pushPose();
            pose.translate(bodyX - cam.x, bodyY - cam.y, bodyZ - cam.z);
            if (hasLive) {
                pose.mulPose(new Quaternionf(liveRot[0], liveRot[1], liveRot[2], liveRot[3]));
            } else {
                body.renderRotation(partialTick, ROT);
                pose.mulPose(new Quaternionf(ROT[0], ROT[1], ROT[2], ROT[3]));
            }

            live.add(body.id());
            CachedBody cached = cacheFor(body, level);
            if (cached != null) {
                for (CachedQuad quad : cached.quads()) {
                    // renderBatched 的等价写法：在 pose 原点绘制模型，因此逐方块平移
                    pose.pushPose();
                    pose.translate(quad.dx(), quad.dy(), quad.dz());
                    buffers.getBuffer(quad.type()).putBulkData(pose.last(), quad.quad(),
                            quad.r(), quad.g(), quad.b(), 1.0F, 240, OverlayTexture.NO_OVERLAY);
                    pose.popPose();
                }
            }
            // 方块实体（箱子/熔炉/告示牌…）：它们的方块模型是空的（builtin/entity），
            // 只烘方块模型就会整个透明 —— 必须就着同一份 pose 调原版 BE 渲染器。
            renderBlockEntities(pose, buffers, body, level, partialTick);
            pose.popPose();
        }
        buffers.endBatch();

        if (CACHE.size() > live.size()) {
            CACHE.keySet().removeIf(id -> !live.contains(id));
        }
        // 方块实体实例缓存按体清理：刚体没了就没人再引用那些 BE 了
        if (!BE_CACHE.isEmpty()) {
            BE_CACHE.keySet().removeIf(key -> !live.contains(key >>> 30));
        }
    }

    // ==================== 方块实体渲染 ====================

    /** bodyId+局部坐标 → (NBT 实例指纹, 渲染用方块实体)。 */
    private static final Map<Long, CachedBlockEntity> BE_CACHE = new HashMap<>();

    private record CachedBlockEntity(int tagFingerprint, net.minecraft.world.level.block.entity.BlockEntity be) {
    }

    /**
     * 就着物理体的 pose 渲染它的方块实体。
     *
     * <p>做法照 space/MPS 的 {@code ClientPhysicalBody}：把服务端发来的 NBT 用
     * {@code BlockEntity.loadStatic} 还原成实例、把 {@code level} 指向客户端世界，
     * 然后直接调原版 {@code BlockEntityRenderer}（满天光 15728880、无叠加层）。</p>
     *
     * <p>实例按 {@code (bodyId, dx, dy, dz)} 缓存，用 NBT 对象身份做失效判据 ——
     * 服务端重发快照时是新的对象，缓存自动重建；否则每帧 loadStatic 太浪费。</p>
     */
    private static void renderBlockEntities(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers,
                                            ClientPhysicsWorld.ClientBody body, Level level, float partialTick) {
        var entries = body.blockEntities();
        if (entries.isEmpty()) {
            return;
        }
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        for (ClientPhysicsWorld.BlockEntityEntry entry : entries) {
            BlockState state = stateAt(body, entry.dx(), entry.dy(), entry.dz());
            if (state == null) {
                continue;
            }
            long key = beKey(body.id(), entry.dx(), entry.dy(), entry.dz());
            int fp = System.identityHashCode(entry.tag());
            CachedBlockEntity cached = BE_CACHE.get(key);
            if (cached == null || cached.tagFingerprint() != fp) {
                BlockPos pos = new BlockPos(entry.dx(), entry.dy(), entry.dz());
                net.minecraft.world.level.block.entity.BlockEntity be =
                        net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                                pos, state, entry.tag(), level.registryAccess());
                if (be == null) {
                    continue;
                }
                cached = new CachedBlockEntity(fp, be);
                BE_CACHE.put(key, cached);
            }
            net.minecraft.world.level.block.entity.BlockEntity be = cached.be();
            be.setLevel(level);
            var renderer = dispatcher.getRenderer(be);
            if (renderer == null) {
                continue;
            }
            pose.pushPose();
            pose.translate(entry.dx(), entry.dy(), entry.dz());
            renderer.render(be, partialTick, pose, buffers, 15728880, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    private static BlockState stateAt(ClientPhysicsWorld.ClientBody body, int dx, int dy, int dz) {
        for (ClientPhysicsWorld.BlockEntry e : body.blocks()) {
            if (e.dx() == dx && e.dy() == dy && e.dz() == dz) {
                return e.state();
            }
        }
        return null;
    }

    private static long beKey(long bodyId, int dx, int dy, int dz) {
        return (bodyId << 30) ^ (((long) dx & 0x3FF) << 20) ^ (((long) dy & 0x3FF) << 10) ^ ((long) dz & 0x3FF);
    }

    // ==================== 缓存 ====================

    private static CachedBody cacheFor(ClientPhysicsWorld.ClientBody body, Level level) {
        long fp = fingerprint(body);
        CachedBody cached = CACHE.get(body.id());
        if (cached != null && cached.fingerprint() == fp) {
            return cached;
        }
        List<CachedQuad> quads = bake(body, level);
        CachedBody rebuilt = new CachedBody(fp, quads);
        CACHE.put(body.id(), rebuilt);
        return rebuilt;
    }

    /**
     * 指纹：列表实例 + 方块数。
     * <p>服务端改方块会重发 CREATE（客户端换新的 List 实例）；本地预测破坏是原地 removeIf（实例不变、数量变）。</p>
     */
    private static long fingerprint(ClientPhysicsWorld.ClientBody body) {
        // 用修订号而不是"列表实例 + 方块数"：拉杆/按钮/门这类"方块数不变、只变状态"的改动
        // 也必须让缓存失效，否则画面永远停在旧状态（看起来像"按了没反应"）。
        return body.revision();
    }

    /** 一次性把所有方块展开成四边形（模型查找 / 邻面剔除 / 染色 / 光照都在这里做完）。 */
    private static List<CachedQuad> bake(ClientPhysicsWorld.ClientBody body, Level level) {
        Minecraft mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockRenderer();
        var blockColors = mc.getBlockColors();
        RandomSource random = RandomSource.create(114514L);

        // 邻面剔除需要知道"同一物理体里有没有这个方块"
        Map<Long, BlockState> cells = new HashMap<>();
        for (ClientPhysicsWorld.BlockEntry entry : body.blocks()) {
            cells.put(cellKey(entry.dx(), entry.dy(), entry.dz()), entry.state());
        }

        List<CachedQuad> out = new ArrayList<>();
        for (ClientPhysicsWorld.BlockEntry entry : body.blocks()) {
            BlockState state = entry.state();
            if (state.isAir()) {
                continue;
            }
            RenderType type = ItemBlockRenderTypes.getChunkRenderType(state);
            BakedModel model = dispatcher.getBlockModel(state);
            BlockPos localPos = new BlockPos(entry.dx(), entry.dy(), entry.dz());

            for (Direction dir : Direction.values()) {
                BlockState neighbour = cells.get(cellKey(entry.dx() + dir.getStepX(),
                        entry.dy() + dir.getStepY(), entry.dz() + dir.getStepZ()));
                if (neighbour != null && neighbour.isCollisionShapeFullBlock(level, BlockPos.ZERO)) {
                    continue; // 被实心邻居挡住的面不发
                }
                for (BakedQuad quad : model.getQuads(state, dir, random)) {
                    out.add(cached(type, quad, state, blockColors, level, localPos, entry));
                }
            }
            for (BakedQuad quad : model.getQuads(state, null, random)) {
                out.add(cached(type, quad, state, blockColors, level, localPos, entry));
            }
        }
        return out;
    }

    private static CachedQuad cached(RenderType type, BakedQuad quad, BlockState state,
                                     net.minecraft.client.color.block.BlockColors blockColors,
                                     Level level, BlockPos localPos,
                                     ClientPhysicsWorld.BlockEntry entry) {
        int color = quad.isTinted()
                ? blockColors.getColor(state, level, localPos, quad.getTintIndex())
                : 0xFFFFFF;
        float r = (color >> 16 & 0xFF) / 255.0F;
        float g = (color >> 8 & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        return new CachedQuad(type, quad, r, g, b, entry.dx(), entry.dy(), entry.dz());
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) (x + 1024) << 42) | ((long) (y + 1024) << 21) | (long) (z + 1024);
    }

    private record CachedQuad(RenderType type, BakedQuad quad, float r, float g, float b,
                              int dx, int dy, int dz) {
    }

    private record CachedBody(long fingerprint, List<CachedQuad> quads) {
    }

    /** 离开世界时清空客户端物理体与缓存，避免残留。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientPhysicsWorld.clear();
            CACHE.clear();
            BE_CACHE.clear();
        }
    }

    /** 客户端初始化：把同步包接到客户端注册表。 */
    @EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {

        private Setup() {
        }

        @SubscribeEvent
        static void onClientSetup(FMLClientSetupEvent event) {
            PhysicsClientHooks.bodySyncConsumer = ClientPhysicsWorld::accept;
            PhysicsClientHooks.bodyBlockEntityConsumer = ClientPhysicsWorld::acceptBlockEntities;
            PhysicsClientHooks.bodyMoveBatchConsumer = ClientPhysicsWorld::acceptMoveBatch;
            // space 式"物理替代移动"：由客户端物理世界接管本地玩家的位移
            PhysicsClientHooks.movementDriver = (entity, delta) ->
                    entity instanceof net.minecraft.client.player.LocalPlayer player
                            && ClientPhysics.drive(player, delta);
        }
    }
}
