package com.mss.polymech.client;

import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.item.SpaceHelmetItem;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 太空头盔 HUD：佩戴 {@link SpaceHelmetItem} 且位于太空维度时，
 * 在视野中每个天体的屏幕投影位置旁显示 名称 + 距离。
 * <p>
 * 投影与太空渲染保持一致：view = camera.rotation().conjugate()，
 * 垂直视场角 70°（与 SpaceRenderer/Skybox 相同）。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class SpaceHelmetHudOverlay {

    /** 与太空维度渲染一致的垂直 FOV。 */
    private static final float VFOV_DEG = 70.0f;
    /** 屏幕边缘裁剪余量（NDC 单位）。 */
    private static final float MARGIN = 1.05f;
    /** 最多同时显示的天体数（按距离近到远）。 */
    private static final int MAX_LABELS = 8;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIST = 0xFF7FDFFF;
    private static final int COLOR_MARKER = 0xFF00E5FF;

    private record Entry(RealAstroData body, double dist, int sx, int sy) {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameRenderer == null) return;
        boolean inSpace = mc.level.dimension().equals(PlanetDimensions.SPACE);
        // ★ 行星地表也要显示。判据与 SpaceRenderer/原版天空屏蔽用的是同一个：
        // "这个维度对应一个 ClientCelestialWorld" ⇒ 我站在某颗行星上。
        com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld celestialWorld =
                inSpace ? null : com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld.getCelestialWorld();
        if (!inSpace && celestialWorld == null) return;
        if (mc.options.hideGui || mc.player.isScoping()) return;
        if (!SpaceHelmetItem.isWorn(mc.player)) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        // 世界方向 → 视图空间：乘 camera.rotation() 的共轭（与 GameRenderer 构建 view 相同）
        Quaternionf viewRot = new Quaternionf(camera.rotation()).conjugate();
        // ★ 坐标系与朝向：
        //  - 太空维度：玩家的 MC 坐标**就是**游戏坐标，直接比；
        //  - 地表维度：玩家的 MC 坐标只是"某颗球面上的局部坐标"，必须先用 CelestialWorld
        //    换算到宇宙系（与 SpaceRenderer 同一套映射），朝向还要再叠一层
        //    getRotateFromWorldPos —— 合成顺序与渲染器完全一致
        //    （rel_view = cameraRot ∘ spaceRotation ∘ rel），否则方位会整体偏。
        double camX = camPos.x;
        double camY = camPos.y;
        double camZ = camPos.z;
        Quaternionf spaceRot = new Quaternionf();
        if (celestialWorld != null) {
            org.joml.Vector3d p = celestialWorld.getSpacePosFromWorldPos(camPos, 1.0f);
            camX = SpaceWorld.toMc(p.x);
            camY = SpaceWorld.toMc(p.y);
            camZ = SpaceWorld.toMc(p.z);
            org.joml.Quaterniond q = celestialWorld.getRotateFromWorldPos(camPos, 1.0f);
            spaceRot.set((float) q.x, (float) q.y, (float) q.z, (float) q.w);
        }

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (w <= 0 || h <= 0) return; // 窗口最小化时 GUI 尺寸为 0，避免 NaN 投影
        float aspect = (float) w / (float) h;
        float tanHalf = (float) Math.tan(Math.toRadians(VFOV_DEG) * 0.5);

        List<Entry> entries = new ArrayList<>();
        for (RealAstroData body : RealAstroData.BODIES) {
            // ★ 口径统一（§30.5）：用**渲染口径** `renderPos`（真实三维、含真实 Y），
            //   而不是方块口径 `gamePosMc`（Y 被压平）—— 否则 HUD 会把倾斜轨道上的天体
            //   标在黄道面里，和屏幕上看到的位置对不上。
            //   再经 `toMc` 换成"当前约定下的显示单位"（ZOOM 模式=格；恒等模式=米），
            //   所以这一改在翻 `identityMode` 开关前后都正确（今天零行为变化）。
            double[] rp = SpaceWorld.renderPos(body);
            double[] gp = {SpaceWorld.toMc(rp[0]), SpaceWorld.toMc(rp[1]), SpaceWorld.toMc(rp[2])};
            double bx = gp[0], by = gp[1], bz = gp[2];
            // 距离用 double（数值可达数亿格，float 精度不够）
            double dx = bx - camX;
            double dy = by - camY;
            double dz = bz - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            Vector3f rel = new Vector3f((float) dx, (float) dy, (float) dz);
            if (celestialWorld != null) {
                // 先把宇宙系方向转到"相机所在世界的朝向"，再进视图空间（顺序与渲染器一致）
                rel.rotate(spaceRot);
            }
            rel.rotate(viewRot);
            // 视图空间：x 右，y 上，z 向后；相机看向 -z
            if (rel.z() > -0.5f) continue;
            float ndcX = rel.x() / (-rel.z()) / (tanHalf * aspect);
            float ndcY = rel.y() / (-rel.z()) / tanHalf;
            if (ndcX < -MARGIN || ndcX > MARGIN || ndcY < -MARGIN || ndcY > MARGIN) continue;

            int sx = (int) ((ndcX + 1.0f) * 0.5f * w);
            int sy = (int) ((1.0f - (ndcY + 1.0f) * 0.5f) * h);
            entries.add(new Entry(body, dist, sx, sy));
        }

        if (entries.isEmpty()) return;
        entries.sort(Comparator.comparingDouble(Entry::dist));
        if (entries.size() > MAX_LABELS) entries = entries.subList(0, MAX_LABELS);

        for (Entry e : entries) {
            int sx = e.sx();
            int sy = e.sy();
            // 十字标记
            g.fill(sx - 2, sy, sx + 3, sy + 1, COLOR_MARKER);
            g.fill(sx, sy - 2, sx + 1, sy + 3, COLOR_MARKER);
            // 文本（右侧偏移）
            String name = e.body().name();
            String distText = formatDistance(e.dist());
            int tx = sx + 8;
            g.drawString(font, name, tx, sy - 5, COLOR_TEXT, true);
            g.drawString(font, distText, tx, sy + 5, COLOR_DIST, true);
        }
    }

    /** 距离格式化：亿格 / 万格 / 格。 */
    private static String formatDistance(double d) {
        if (d >= 1e8) return String.format("%.2f亿格", d / 1e8);
        if (d >= 1e4) return String.format("%.1f万格", d / 1e4);
        return String.format("%.0f格", d);
    }

    private SpaceHelmetHudOverlay() {
    }
}
