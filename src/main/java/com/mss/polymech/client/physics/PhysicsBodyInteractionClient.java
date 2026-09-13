package com.mss.polymech.client.physics;

import com.mss.polymech.Polymech;
import com.mss.polymech.network.PhysicsBodyEditPacket;
import com.mss.polymech.physics.PhysicsRaycast;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 客户端物理体交互：破坏 / 放置物理体上的方块。
 *
 * <p>物理体的方块已被从世界移除，原版 {@code clip()} 打不到，所以这里自己做射线
 * （{@link PhysicsRaycast}）：命中最近物理体方块 → 发包给服务端 → 本地立即预测（响应无延迟），
 * 服务端校验并回发权威快照。</p>
 *
 * <p>优先级：若原版射线命中真实方块且比物理体更近，就交给原版处理（不影响正常挖方块）。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PhysicsBodyInteractionClient {

    private static final Quaternionf ROT = new Quaternionf();
    private static final Quaternionf INV = new Quaternionf();
    private static final Vector3f TMP = new Vector3f();

    private PhysicsBodyInteractionClient() {
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (ClientPhysicsWorld.size() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        Player player = mc.player;
        double reach = player.blockInteractionRange();

        Hit hit = castAgainstBodies(player.getEyePosition(), player.getViewVector(1.0F), reach);
        if (hit == null) {
            return;
        }

        // 原版命中更近 → 交给原版
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.distanceTo(player) < hit.distance) {
            return;
        }

        if (event.isAttack()) {
            ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(hit.bodyId);
            if (body != null) {
                body.predictBreak(hit.dx, hit.dy, hit.dz);
            }
            PacketDistributor.sendToServer(PhysicsBodyEditPacket.breakBlock(
                    hit.bodyId, hit.dx, hit.dy, hit.dz));
            event.setCanceled(true);
        } else if (event.isUseItem()) {
            InteractionHand hand = event.getHand();
            // <b>不做本地放置预测。</b> "这次右键是使用还是放置"只有服务端在投影维度里跑一次
            // 原版 useOn 才知道（拉杆要切换、按钮要按下、箱子要开、门要开合…）。
            // 本地一旦预测"放置"，就会先冒出一个假方块，随后被服务端快照顶回去 ——
            // 表现就是"手里拿着拉杆右键拉杆，那一格瞬间多出一个拉杆，又被旧的顶回来"，
            // 甚至闪到旁边的方块格上。放置改由服务端确认（约 1 tick），换来的是不再有假方块。
            // 左键破坏仍然预测：破坏的语义没有歧义。
            PacketDistributor.sendToServer(PhysicsBodyEditPacket.use(
                    hit.bodyId, hit.dx, hit.dy, hit.dz,
                    hit.normalX, hit.normalY, hit.normalZ,
                    hand == InteractionHand.OFF_HAND ? 1 : 0,
                    player.isShiftKeyDown()));
            event.setCanceled(true);
        }
    }

    /** 对所有物理体做射线，取最近命中。 */
    public static Hit castAgainstBodies(Vec3 origin, Vec3 dir, double reach) {
        Hit best = null;
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            double bx = body.renderX(1.0f);
            double by = body.renderY(1.0f);
            double bz = body.renderZ(1.0f);

            double[] rot = new double[4];
            float[] rotF = new float[4];
            body.renderRotation(1.0f, rotF);
            ROT.set(rotF[0], rotF[1], rotF[2], rotF[3]);
            INV.set(ROT).conjugate();

            TMP.set((float) (origin.x - bx), (float) (origin.y - by), (float) (origin.z - bz));
            INV.transform(TMP);
            double[] localOrigin = {TMP.x, TMP.y, TMP.z};

            TMP.set((float) dir.x, (float) dir.y, (float) dir.z);
            INV.transform(TMP);
            double[] localDir = {TMP.x, TMP.y, TMP.z};
            double len = Math.sqrt(localDir[0] * localDir[0] + localDir[1] * localDir[1] + localDir[2] * localDir[2]);
            if (len < 1.0e-6) {
                continue;
            }
            localDir[0] /= len;
            localDir[1] /= len;
            localDir[2] /= len;

            int[] blocks = new int[body.blocks().size() * 3];
            for (int i = 0; i < body.blocks().size(); i++) {
                ClientPhysicsWorld.BlockEntry e = body.blocks().get(i);
                blocks[i * 3] = e.dx();
                blocks[i * 3 + 1] = e.dy();
                blocks[i * 3 + 2] = e.dz();
            }

            PhysicsRaycast.Hit local = PhysicsRaycast.cast(localOrigin, localDir, reach, blocks);
            if (local == null) {
                continue;
            }
            if (best == null || local.distance() < best.distance) {
                // 法线同样要变换回世界空间（放置方向用）
                TMP.set(local.normalX(), local.normalY(), local.normalZ());
                ROT.transform(TMP);
                best = new Hit(body.id(), local.distance(), local.blockX(), local.blockY(), local.blockZ(),
                        Math.round(TMP.x), Math.round(TMP.y), Math.round(TMP.z));
            }
        }
        return best;
    }

    /** 命中的物理体方块。 */
    public record Hit(long bodyId, double distance, int dx, int dy, int dz,
                      int normalX, int normalY, int normalZ) {
    }
}
