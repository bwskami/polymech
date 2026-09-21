package com.mss.polymech.client.space;

import com.mojang.math.Axis;
import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 太空姿态仪（人工地平线）：进入太空维度时叠加在视野上，无需戴头盔。
 *
 * <p><b>为什么需要它</b>：纯 6DOF 第一人称最大的致晕源是"迷向"——深空没有地面、没有地平线，
 * 玩家看不出自己是正的、歪的、还是倒的。给一个<b>跟着世界参考方向转的人工地平线 + 俯仰梯</b>，
 * 视觉上立刻有"哪个方向是下"的锚点，迷向感大幅下降。</p>
 *
 * <p><b>参考"上"</b>：取 {@link LevelAssist} 的平滑参考（有重力取重力、贴船取甲板面、
 * 深空默认取世界竖直），与滚转回正共用同一个参考，于是"HUD 说哪里是上"和"辅助把你往哪扶"永远一致。</p>
 *
 * <h3>设计取向：克制、不抢戏</h3>
 * <p>HUD 是辅助，不该让玩家盯着它看。因此：</p>
 * <ul>
 *   <li><b>情境化淡入淡出</b>：整块 HUD 的强度随"姿态偏离程度"平滑变化——平飞时压到约 20% 几乎隐去，
 *       一旦压杆/抬头偏离，才在零点几秒内升到全亮。<b>需要它的时候它亮，不需要的时候它让路。</b>
 *       切换飞行辅助时会短暂提到高亮度以便看清提示。</li>
 *   <li><b>去掉抢戏内容</b>：键位提示不再常驻（移到别处／按需显示）；读数精简为一行；
 *       飞行辅助状态只在切换后的两秒内浮现然后隐去。</li>
 *   <li><b>细而诚实</b>：按<b>物理像素</b>画线（1 物理像素的梯子/弧，2 像素的地平线与机身符号），
 *       与 GUI 缩放无关；不加粗、不做多层"发光"（低分辨率下那只是一圈糊边）。</li>
 *   <li><b>单色 + 明度分级</b>：整块 HUD 一个色相（青白），主次只靠明度——真实 HUD 是单色荧光粉；
 *       混色才显玩具。地平线沿长度做亮度渐隐，是元素自身的明度过渡，不是光晕。</li>
 * </ul>
 *
 * <p><b>几何</b>：地平线（中央缺口 + 两端下折小钩 + 两端渐隐）、俯仰梯（5° 极淡短线 / 10° 中线 /
 * 30° 长线带数字，数字落在缺口里随梯子一起转）、上方连续滚转弧 + 分级刻度 + 滑动指针、
 * 屏幕正中固定机身符号。</p>
 *
 * <p><b>绘制方式</b>：注册为 {@link LayeredDraw.Layer}，全部走 {@link GuiGraphics#fill}/
 * {@link GuiGraphics#drawString} 原版管线。地平线+俯仰梯整组用 pose
 * {@code mulPose(Axis.ZP.rotationDegrees(tilt))} 一起倾斜，组内一律是水平矩形。</p>
 */
public final class SpaceAttitudeOverlay implements LayeredDraw.Layer {

    public static final SpaceAttitudeOverlay INSTANCE = new SpaceAttitudeOverlay();

    // ── 单色系：同一色相，只用明度分级 ──
    private static final int HI = 0xFFE6FAFF;    // 地平线 / 30° 梯 / 机身符号 / 滚转弧主刻度
    private static final int MID = 0xFFA8D8E8;   // 10° 梯
    private static final int LO = 0xFF6E9FB2;    // 5° 梯 / 次要刻度
    private static final int TEXT = 0xFFCFE9F4;  // 读数数值
    private static final int LABEL = 0xFF8FA6B2; // 状态文字里的说明部分（暗）
    private static final int OK = 0xFF9BF0AB;    // 开启/生效
    private static final int CAUTION = 0xFFF2C46A; // 关闭

    // ── 线宽（**物理像素**，与 GUI 缩放无关）──
    private static final float W_THIN = 1.0f;
    private static final float W_MAIN = 2.0f;

    // ── 布局（GUI 像素基准，按屏幕高度自适应）──
    private static final float BASE_PX_PER_DEG = 3.0f;
    private static final float BASE_HORIZON_HALF = 124.0f;
    private static final float BASE_HORIZON_GAP = 26.0f;
    private static final float BASE_HORIZON_HOOK = 6.0f;
    private static final float BASE_MINOR_HALF = 28.0f;
    private static final float BASE_MED_HALF = 52.0f;
    private static final float BASE_MAJOR_HALF = 80.0f;
    private static final float BASE_LADDER_GAP = 13.0f;
    private static final float POLE_PITCH_DEG = 62.0f;
    private static final float BASE_BANK_R = 146.0f;

    // ── 淡入淡出 ──
    /** 平飞时的最低强度（几乎隐去，但仍留一点"哪里是下"的锚点）。 */
    private static final float FADE_MIN = 0.20f;
    /** 强度收敛速度（每秒）。 */
    private static final float FADE_SPEED = 3.5f;
    /** 姿态偏离多少度算"完全需要 HUD"。 */
    private static final float FADE_FULL_DEG = 35.0f;

    // ── 运行状态（INSTANCE 单例，跨帧保留）──
    private float fade = 0.0f;
    private long lastFrameMs = 0L;

    private SpaceAttitudeOverlay() {
    }

    @Override
    public void render(@NotNull GuiGraphics g, @NotNull DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameRenderer == null) return;
        if (!mc.level.dimension().equals(PlanetDimensions.SPACE)) return;
        if (mc.options.hideGui || mc.player.isScoping() || mc.screen != null) return;

        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        if (w <= 0 || h <= 0) return; // 窗口最小化时 GUI 尺寸为 0
        float gs = (float) Math.max(1.0, mc.getWindow().getGuiScale());

        int cx = w / 2;
        int cy = h / 2;
        float ui = Mth.clamp(h / 420.0f, 0.72f, 1.35f);
        float pxPerDeg = BASE_PX_PER_DEG * ui;

        // ── 参考"上"（世界系）→ 相机系 ──
        Vector3f refUp = new Vector3f();
        LevelAssist.getReferenceUp(refUp);
        refUp.normalize();
        Camera camera = mc.gameRenderer.getMainCamera();
        Quaternionf viewRot = new Quaternionf(camera.rotation()).conjugate();
        Vector3f v = new Vector3f(refUp).rotate(viewRot); // x 右、y 上、z 向后

        float upX = v.x;
        float upY = v.y;
        float upLen = (float) Math.sqrt(upX * upX + upY * upY);
        float pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-v.z, -1.0f, 1.0f))); // + = 抬头
        float sux = upLen > 1e-4f ? upX / upLen : 0.0f;   // 世界 up 屏幕方向（x 右、y 下）
        float suy = upLen > 1e-4f ? -upY / upLen : -1.0f;
        float tiltDeg = (float) Math.toDegrees(Math.atan2(sux, -suy)); // 地平线倾角，level = 0
        float bankDeg = -tiltDeg;                                      // 玩家语义：右压杆为正

        // ── 强度：只作用于**几何符号**（地平线/俯仰梯/滚转弧/机身符号）──
        // 平飞时压到 FADE_MIN 几乎隐去，姿态偏离时升到全亮。
        // 底部状态文字**不参与**淡出：玩家需要随时确认自己开了什么。
        long now = Util.getMillis();
        float dt = lastFrameMs == 0L ? 0.016f : Mth.clamp((now - lastFrameMs) / 1000.0f, 0.0f, 0.1f);
        lastFrameMs = now;

        float relevance = Math.max(Math.abs(bankDeg) / FADE_FULL_DEG,
                Math.max(0.0f, Math.abs(pitch) - 10.0f) / FADE_FULL_DEG);
        float target = FADE_MIN + (1.0f - FADE_MIN) * Mth.clamp(relevance, 0.0f, 1.0f);
        fade += (target - fade) * Mth.clamp(dt * FADE_SPEED, 0.0f, 1.0f);

        int cHi = fade(HI, fade);
        int cMid = fade(MID, fade);
        int cLo = fade(LO, fade);
        int cArc = fade(HI, fade * 0.75f);

        // ── 1. 地平线 + 俯仰梯：整组按滚转倾斜 ──
        boolean pole = upLen < 0.03f || Math.abs(pitch) > POLE_PITCH_DEG;
        if (!pole) {
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(tiltDeg));

            // 地平线：两端渐隐（明度过渡，不是光晕）+ 中央缺口 + 两端下折小钩
            float hz = pitch * pxPerDeg;
            float hHalf = BASE_HORIZON_HALF * ui;
            float hGap = BASE_HORIZON_GAP * ui;
            gradientBar(g, gs, hz, hHalf, hGap, W_MAIN, cHi, 0.34f);
            float hook = BASE_HORIZON_HOOK * ui;
            vBar(g, gs, hz, -hHalf, hz + hook, W_MAIN, cHi);
            vBar(g, gs, hz, hHalf, hz + hook, W_MAIN, cHi);

            // 俯仰梯：5° 极淡短 / 10° 中 / 30° 长且带数字
            float lGap = BASE_LADDER_GAP * ui;
            for (int lp = -90; lp <= 90; lp += 5) {
                if (lp == 0) continue;
                float y = -(lp - pitch) * pxPerDeg;
                if (Math.abs(y) > cy - 24) continue;
                boolean major = lp % 30 == 0;
                boolean med = !major && lp % 10 == 0;
                if (!major && !med) {
                    hBar(g, gs, y, BASE_MINOR_HALF * ui, lGap, W_THIN, cLo);
                    continue;
                }
                hBar(g, gs, y, (major ? BASE_MAJOR_HALF : BASE_MED_HALF) * ui, lGap, W_THIN,
                        major ? cHi : cMid);
                if (major) {
                    String label = String.valueOf(Math.abs(lp));
                    g.drawString(font, label, -font.width(label) / 2,
                            Math.round(y) - font.lineHeight / 2, cHi, true);
                }
            }
            g.pose().popPose();
        } else {
            drawPoleCue(g, font, cx, cy, sux, suy, upLen, v.z, ui, gs, cHi);
        }

        // ── 2. 机身符号（屏幕正中固定）+ 中心点 ──
        drawAircraft(g, cx, cy, ui, gs, cHi);
        // ── 3. 滚转刻度 ──
        drawBankScale(g, cx, cy, ui, bankDeg, gs, cArc, cLo);
        // ── 4. 读数（常亮）──
        drawReadout(g, font, cx, cy, ui, pitch, bankDeg);
    }

    // ==================== HUD 符号 ====================

    /**
     * 沿长度渐隐的水平线（中央留缺口）：越靠外越淡。
     * 这是元素自身的明度过渡，用来让长线两端"化开"，而不是给线加光晕。
     */
    private static void gradientBar(GuiGraphics g, float gs, float yLocal, float half, float gap,
                                    float devWidth, int color, float edgeFactor) {
        final int segs = 12;
        for (int i = 0; i < segs; i++) {
            float t0 = i / (float) segs;
            float t1 = (i + 1) / (float) segs;
            float f = 1.0f - (1.0f - edgeFactor) * ((t0 + t1) * 0.5f);
            int c = fade(color, f);
            float x0 = gap + (half - gap) * t0;
            float x1 = gap + (half - gap) * t1;
            hSeg(g, gs, yLocal, x0, x1, devWidth, c);
            hSeg(g, gs, yLocal, -x1, -x0, devWidth, c);
        }
    }

    /** 组内水平刻度线（中央留缺口），线宽按物理像素给。 */
    private static void hBar(GuiGraphics g, float gs, float yLocal, float half, float gap,
                             float devWidth, int color) {
        int hDev = Math.max(1, Math.round(half * gs));
        int gpDev = Mth.clamp(Math.round(gap * gs), 0, hDev);
        if (gpDev > 0 && gpDev < hDev) {
            hSeg(g, gs, yLocal, gap, half, devWidth, color);
            hSeg(g, gs, yLocal, -half, -gap, devWidth, color);
        } else {
            hSeg(g, gs, yLocal, -half, half, devWidth, color);
        }
    }

    /** 组内水平线段（GUI 像素给 x，物理像素给线宽）。 */
    private static void hSeg(GuiGraphics g, float gs, float yLocal, float x0, float x1,
                             float devWidth, int color) {
        int w = Math.max(1, Math.round(devWidth));
        int top = -(w / 2);
        int bottom = top + w;
        int xd0 = Math.round(Math.min(x0, x1) * gs);
        int xd1 = Math.round(Math.max(x0, x1) * gs);
        if (xd1 <= xd0) return;
        g.pose().pushPose();
        g.pose().translate(0.0f, yLocal, 0.0f);
        g.pose().scale(1.0f / gs, 1.0f / gs, 1.0f);
        g.fill(xd0, top, xd1, bottom, color);
        g.pose().popPose();
    }

    /** 组内垂直线段（地平线两端下折小钩）。 */
    private static void vBar(GuiGraphics g, float gs, float yFrom, float xLocal, float yTo,
                             float devWidth, int color) {
        int w = Math.max(1, Math.round(devWidth));
        int left = -(w / 2);
        int right = left + w;
        g.pose().pushPose();
        g.pose().translate(xLocal, Math.min(yFrom, yTo), 0.0f);
        g.pose().scale(1.0f / gs, 1.0f / gs, 1.0f);
        int hDev = Math.max(1, Math.round(Math.abs(yTo - yFrom) * gs));
        g.fill(left, 0, right, hDev, color);
        g.pose().popPose();
    }

    /** 屏幕正中固定的机身符号（经典 waterline：中段留空、两端下折）+ 中心点。 */
    private static void drawAircraft(GuiGraphics g, int cx, int cy, float ui, float gs, int color) {
        float span = 18.0f * ui;
        float inner = 4.0f * ui;
        float stub = 5.0f * ui;
        line(g, gs, cx - span, cy, cx - inner, cy, W_MAIN, color);
        line(g, gs, cx - span, cy, cx - span, cy + stub, W_MAIN, color);
        line(g, gs, cx + inner, cy, cx + span, cy, W_MAIN, color);
        line(g, gs, cx + span, cy, cx + span, cy + stub, W_MAIN, color);
        // 中心点（1 物理像素）
        g.fill(cx, cy, cx + 1, cy + 1, color);
    }

    /** 上方滚转刻度：连续弧 + 分级刻度 + 随滚转滑动的指针。 */
    private static void drawBankScale(GuiGraphics g, int cx, int cy, float ui, float bankDeg,
                                      float gs, int arcColor, int tickColor) {
        int R = Math.round(Math.min(cy - 36.0f, BASE_BANK_R * ui));
        if (R < 70) return;

        float prevX = 0, prevY = 0;
        for (int a = -60; a <= 60; a += 4) {
            float rad = (float) Math.toRadians(a);
            float x = cx + (float) Math.sin(rad) * R;
            float y = cy - (float) Math.cos(rad) * R;
            if (a > -60) line(g, gs, prevX, prevY, x, y, W_THIN, tickColor);
            prevX = x;
            prevY = y;
        }
        for (int d = -60; d <= 60; d += 15) {
            float rad = (float) Math.toRadians(d);
            float sx = (float) Math.sin(rad);
            float cz = (float) Math.cos(rad);
            boolean major = d % 30 == 0;
            float r0 = R - (major ? 7.0f : 4.0f) * ui;
            float r1 = R + (major ? 5.0f : 3.0f) * ui;
            line(g, gs, cx + sx * r0, cy - cz * r0, cx + sx * r1, cy - cz * r1,
                    W_THIN, major ? arcColor : tickColor);
        }
        float rad = (float) Math.toRadians(Mth.clamp(bankDeg, -60.0f, 60.0f));
        float sx = (float) Math.sin(rad);
        float cz = (float) Math.cos(rad);
        float rTip = R - 2.0f * ui;
        float rBase = R - 11.0f * ui;
        float tipX = cx + sx * rTip, tipY = cy - cz * rTip;
        float baseX = cx + sx * rBase, baseY = cy - cz * rBase;
        float perpX = cz, perpY = sx;
        float bw = 4.5f * ui;
        line(g, gs, tipX, tipY, baseX + perpX * bw, baseY + perpY * bw, W_THIN, arcColor);
        line(g, gs, tipX, tipY, baseX - perpX * bw, baseY - perpY * bw, W_THIN, arcColor);
    }

    /** 地平线远出视野：中上部一个小的"上/下 + 箭头"提示。 */
    private static void drawPoleCue(GuiGraphics g, Font font, int cx, int cy,
                                    float sux, float suy, float upLen, float refUpZ,
                                    float ui, float gs, int color) {
        String word = refUpZ < 0 ? "上" : "下";
        g.pose().pushPose();
        g.pose().translate(cx, cy - 54.0f * ui, 0);
        g.pose().scale(1.6f, 1.6f, 1.0f);
        g.drawString(font, word, -font.width(word) / 2, -font.lineHeight / 2, color, true);
        g.pose().popPose();

        float r0 = 30.0f * ui;
        float r1 = 96.0f * ui;
        drawChevron(g, gs, cx + sux * r0, cy + suy * r0, cx + sux * r1, cy + suy * r1, color);
    }

    /**
     * 底部读数：<b>常亮</b>，不参与几何符号的淡入淡出。
     * <p>第一行是姿态数值；第二行是三项辅助状态 —— 玩家需要随时确认自己开了什么，
     * 所以它们必须一直可见，不能像之前那样只在切换后短暂浮现。</p>
     */
    private static void drawReadout(GuiGraphics g, Font font, int cx, int cy, float ui,
                                    float pitch, float bankDeg) {
        int y = cy + Math.round(84.0f * ui);
        String att = String.format("俯仰 %+d°   滚转 %+d°", Math.round(pitch), Math.round(bankDeg));
        g.drawString(font, att, Math.round(cx - font.width(att) / 2f), y, TEXT, true);

        boolean assist = LevelAssist.isFlightAssistEnabled();
        boolean manual = LevelAssist.isManualLeveling();
        boolean auto = LevelAssist.hasActiveReference();

        String k1 = "V 飞行辅助 ", v1 = assist ? "开" : "关";
        String k2 = "   X 回正 ", v2 = manual ? "进行中" : "就绪";
        String k3 = "   自动回正 ", v3 = auto ? "生效" : "关闭";

        int total = font.width(k1) + font.width(v1) + font.width(k2) + font.width(v2)
                + font.width(k3) + font.width(v3);
        int x = cx - total / 2;
        int y2 = y + font.lineHeight + 4;
        x = draw(g, font, k1, x, y2, LABEL);
        x = draw(g, font, v1, x, y2, assist ? OK : CAUTION);
        x = draw(g, font, k2, x, y2, LABEL);
        x = draw(g, font, v2, x, y2, manual ? OK : TEXT);
        x = draw(g, font, k3, x, y2, LABEL);
        draw(g, font, v3, x, y2, auto ? TEXT : LABEL);
    }

    /** 画一段文字并返回下一段的起始 x（用于拼一行多色状态）。 */
    private static int draw(GuiGraphics g, Font font, String text, int x, int y, int color) {
        g.drawString(font, text, x, y, color, true);
        return x + font.width(text);
    }

    // ==================== 基础绘制 ====================

    /** 按物理像素宽画一条屏幕空间线段。 */
    private static void line(GuiGraphics g, float gs, float x1, float y1, float x2, float y2,
                             float devWidth, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int w = Math.max(1, Math.round(devWidth));
        int top = -(w / 2);
        int bottom = top + w;
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0.0f);
        g.pose().mulPose(Axis.ZP.rotationDegrees((float) Math.toDegrees(Math.atan2(dy, dx))));
        g.pose().scale(1.0f / gs, 1.0f / gs, 1.0f);
        g.fill(0, top, Math.max(1, (int) Math.ceil(len * gs)), bottom, color);
        g.pose().popPose();
    }

    /** 方向箭头（主线 + 两条尾翼）。 */
    private static void drawChevron(GuiGraphics g, float gs, float fx, float fy, float tx, float ty,
                                    int color) {
        float dx = tx - fx;
        float dy = ty - fy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        float ux = dx / len;
        float uy = dy / len;
        final float c = 0.9063f; // cos(25°)
        final float s = 0.4226f; // sin(25°)
        float bx = ux * c - uy * s;
        float by = ux * s + uy * c;
        float wx = ux * c + uy * s;
        float wy = -ux * s + uy * c;
        float wing = Math.min(13f, len * 0.45f);
        line(g, gs, fx, fy, tx, ty, W_MAIN, color);
        line(g, gs, tx, ty, tx - bx * wing, ty - by * wing, W_MAIN, color);
        line(g, gs, tx, ty, tx - wx * wing, ty - wy * wing, W_MAIN, color);
    }

    /** 把颜色的 alpha 按系数缩放（用于整体淡入淡出）。 */
    private static int fade(int color, float f) {
        int a = (color >>> 24) & 0xFF;
        int na = Mth.clamp(Math.round(a * f), 0, 255);
        return (color & 0x00FFFFFF) | (na << 24);
    }
}
