package com.mss.polymech.client.space;

import com.mss.polymech.Polymech;
import com.mss.polymech.space.SpacePlayerData;
import com.mss.polymech.util.PartialTicks;
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
 * 两者是同一个前向的两个来源，理论上必须重合 ⇒ 夹角 {@code <= 1.0°} 记 PASS，否则 FAIL。
 * 眼-相机距一并报出（第一人称应≈0；第三人称必然>0，那属于正常，不是本判据的 FAIL 项）。</p>
 *
 * <p><b>第二条判据（2026-10-06 加）：锚点复算 == 真实相机位置。</b>
 * 服务端（{@code SpaceBlockPlacement} 放方块 / {@code PhysgunItem} 牵引枪）<b>读不到相机</b>，
 * 只能拿同步来的 {@code facing/left} 走 {@link SpacePlayerData#cameraAnchor} 重算同一个锚点。
 * 这一项就用<b>真实相机</b>去验那条算式（读真实相机与真实实体，能 FAIL）：
 * 偏差 ≈ 0.000 才叫"两端同源"；算式一旦退回眼睛，它会立刻变成 1.31~1.43 格。
 * 这正是"右键放置不放我瞄准的那个面 / 放到别处"的量化判据。</p>
 *
 * <p><b>怎么用</b>：主世界应全 PASS（锚点项显示 {@code —}，因为主世界不走锚点）；
 * 太空里若出现 FAIL，且 {@code 夹角}随姿态/滚转变化，
 * 就证实"两条路分叉"，且分叉大小直接给出治本修的门槛。修好后必须全 PASS。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SpaceViewBasisProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Space/ViewBasis");

    /**
     * PASS 门槛（度）：两个前向的夹角。
     *
     * <p>为什么是 1.0 而不是 0.5：太空里 {@code roll≈±180°} 时，姿态四元数是 float、
     * {@code facing} 是 double，实测残差约 <b>0.53°</b>（2026-10-06 读数），属精度噪声而非分叉。
     * 真正会咬人的是<b>起点</b>（{@code 眼-相机距}，太空实测 1.313 格），那一项见
     * {@code PhysicsBodyInteractionClient#viewOrigin}。</p>
     */
    private static final double PASS_DEG = 1.0;

    /**
     * 「锚点复算 == 真实相机」的 PASS 门槛（格）。
     *
     * <p>为什么是 0.01：客户端相机与锚点算式用的是<b>同一个</b>部分刻
     * （{@code PartialTicks.forDisplay()} = 渲染最后一帧的 alpha，也是相机 {@code setup} 用的那个）
     * 和同一份算式（{@link SpacePlayerData#cameraAnchor}），所以两者应当逐位接近，
     * 只差 float 往返误差。留 0.01 格给四元数 float 精度。</p>
     */
    private static final double ANCHOR_PASS = 0.01;

    /** 采样间隔（tick）：每 10 tick（0.5s）一行，够看清随姿态的变化。 */
    private static final int INTERVAL = 10;

    private static int tick;
    private static double worstDeg = -1.0;
    private static double worstAnchorDist = -1.0;

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
        boolean anglePass = deg <= PASS_DEG;

        // ---- 判据 2：锚点复算（服务端唯一能走的那条路）== 真实相机 ----
        // 服务端读不到相机，只能拿同步的 facing/left 重算；这一项直接拿真实相机验它。
        org.joml.Vector3d anchor = SpacePlayerData.cameraAnchor(mc.player, PartialTicks.forDisplay(),
                mc.player.getEyeHeight(), new org.joml.Vector3d());
        double anchorDist = Double.NaN;
        if (anchor != null) {
            double dx = anchor.x - camPos.x;
            double dy = anchor.y - camPos.y;
            double dz = anchor.z - camPos.z;
            anchorDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (anchorDist > worstAnchorDist) {
                worstAnchorDist = anchorDist;
            }
        }
        boolean anchorPass = anchor == null || anchorDist <= ANCHOR_PASS;
        boolean pass = anglePass && anchorPass;

        LOGGER.info("[PolyMech] [视线基] {} 夹角={}°{} 最差夹角={}° 眼-相机距={} 锚点-相机距={}{} | "
                        + "玩家 yaw/pitch={}/{} 相机 yaw/pitch/roll={}/{}/{} | 维度={} 创造飞行={} 视角={} 相机脱离实体={}",
                pass ? "PASS" : "FAIL",
                fmt(deg), anglePass ? "" : "(FAIL)",
                fmt(worstDeg), fmt(eyeCamDist),
                Double.isNaN(anchorDist) ? "—" : fmt(anchorDist),
                Double.isNaN(anchorDist) ? "" : (anchorPass ? "(PASS)" : "(FAIL 锚点算式退回/漂移)"),
                fmt(mc.player.getYRot()), fmt(mc.player.getXRot()),
                fmt(cam.getYRot()), fmt(cam.getXRot()), fmt(cam.getRoll()),
                mc.level.dimension().location(),
                mc.player.getAbilities().flying,
                mc.options.getCameraType().isFirstPerson() ? "第一人称" : "第三人称",
                cam.isDetached());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
