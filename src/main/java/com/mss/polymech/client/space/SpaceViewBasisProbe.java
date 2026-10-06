package com.mss.polymech.client.space;

import com.mss.polymech.Polymech;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * <b>[视线基] 判据</b>：玩家实体的"视线"与相机的真实前向是不是同一个方向。
 *
 * <p><b>为什么要有它（2026-10-06 定位"就太空不准"）</b>：太空里"朝向"有<b>两条路</b>——
 * 相机走 {@code SpacePlayerData.mcRotation(facing, left, roll)}（刚体姿态基，见
 * {@code SpaceCameraMixin} 注入 {@code Camera.setup} 的 TAIL），玩家实体走
 * {@code SpaceTurnMixin} 用鼠标累加的 yaw/pitch。而挖掘/牵引枪/线缆剪这些射线读的是
 * {@code player.getViewVector()}（<b>实体那条路</b>）。两条路一旦分叉，
 * <b>准星指的</b>和<b>射线走的</b>就不是一条 ⇒ 表现"主世界准、太空瞄不准、时好时坏"。
 * 这正是 {@code docs/space-input-basis.md} 警告过的"同一个概念存在分叉"。</p>
 *
 * <p><b>判据（绝对量，与相机怎么动无关）</b>：{@code angle(player.getViewVector, camera.getLookVector)}。
 * 两者是同一个前向的两个来源，理论上必须重合 ⇒ 夹角 {@code <= 0.5°} 记 PASS，否则 FAIL。
 * 眼-相机距一并报出（第一人称应≈0；第三人称必然>0，那属于正常，不是本判据的 FAIL 项）。</p>
 *
 * <p><b>怎么用</b>：主世界应全 PASS；太空里若出现 FAIL，且 {@code 夹角}随姿态/滚转变化，
 * 就证实"两条路分叉"，且分叉大小直接给出治本修的门槛。修好后必须全 PASS。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SpaceViewBasisProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Space/ViewBasis");

    /** PASS 门槛（度）：两个前向的夹角。 */
    private static final double PASS_DEG = 0.5;

    /** 采样间隔（tick）：每 10 tick（0.5s）一行，够看清随姿态的变化。 */
    private static final int INTERVAL = 10;

    private static int tick;
    private static double worstDeg = -1.0;

    private SpaceViewBasisProbe() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameRenderer == null) {
            return;
        }
        if (++tick % INTERVAL != 0) {
            return;
        }
        Camera cam = mc.gameRenderer.getMainCamera();
        Vector3f look = cam.getLookVector();
        if (look == null || look.lengthSquared() < 1.0E-8F) {
            return;
        }

        Vec3 view = mc.player.getViewVector(1.0F);
        double dot = view.x * look.x + view.y * look.y + view.z * look.z;
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double deg = Math.toDegrees(Math.acos(dot));

        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 camPos = cam.getPosition();
        double eyeCamDist = eye.distanceTo(camPos);

        if (deg > worstDeg) {
            worstDeg = deg;
        }
        boolean pass = deg <= PASS_DEG;

        LOGGER.info("[PolyMech] [视线基] {} 夹角={}° 最差夹角={}° 眼-相机距={} | "
                        + "玩家 yaw/pitch={}/{} 相机 yaw/pitch/roll={}/{}/{} | 维度={} 创造飞行={} 第三人称={}",
                pass ? "PASS" : "FAIL",
                fmt(deg), fmt(worstDeg), fmt(eyeCamDist),
                fmt(mc.player.getYRot()), fmt(mc.player.getXRot()),
                fmt(cam.getYRot()), fmt(cam.getXRot()), fmt(cam.getRoll()),
                mc.level.dimension().location(),
                mc.player.getAbilities().flying,
                !cam.isDetached());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
