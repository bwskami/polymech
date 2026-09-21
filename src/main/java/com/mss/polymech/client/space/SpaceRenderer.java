package com.mss.polymech.client.space;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mss.polymech.client.gui.widget.planet.PlanetLighting;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderObject;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderObjectFactory;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderParams;
import com.mss.polymech.client.gui.widget.planet.StarGlowRenderer;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import net.minecraft.client.renderer.GameRenderer;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SpaceRenderer {

    private static final float SPACE_FAR_PLANE = 1.0e13f;
    private static final float SPACE_NEAR_PLANE = 1000.0f;
    private static final float FOV_DEG = 70.0f;

    /** 实验开关：true = 天空盒和星球用完全相同的 view/坐标系渲染（在 SpaceRenderer 里统一画）。 */
    private static final boolean UNIFIED_SKYBOX_WITH_PLANETS = false;

    /** 调试：六面纯色天空盒（与 SpaceDimensionEffects 的颜色约定一致）。 */
    private static final int[] DEBUG_SKY_FACE_COLORS = new int[] {
            0xFF00FF00, 0xFFFFFF00, 0xFFFF00FF, 0xFF00FFFF, 0xFFFF0000, 0xFF0000FF
    };
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f TMP_REL = new Vector3f();
    private static final Vector3f TMP_CAM = new Vector3f();

    /** AFTER_SKY 阶段绘制的星球数据，供 AFTER_PARTICLES 阶段的后处理（泛光 / 大气）使用。 */
    private static Matrix4f spaceView;
    private static Matrix4f spaceProj;
    private static List<PlanetRenderObject> spaceBodies = List.of();
    private static double spaceCamRealX;
    private static double spaceCamRealY;
    private static double spaceCamRealZ;
    private static float spacePartialTick;
    private static boolean spaceFrameDrawn;
    /** 本帧画面里有没有恒星：没有时跳过泛光 pass，省一次全屏绘制。 */
    private static boolean spaceHasStars;
    /**
     * 后处理用的深度快照（AFTER_PARTICLES 时的主深度）。
     *
     * <p>{@link net.minecraft.client.renderer.PostPass#process} 写回主缓冲时会调
     * {@code RenderTarget.clear}，无条件清颜色、useDepth 时还清深度。泛光与大气两条链路
     * 都要 blit 回主缓冲，先跑的那条会把主深度抹掉；云/天气也需要主深度。
     * 所以开跑前先快照一份，结束后再写回主缓冲。</p>
     *
     * <p>大气散射拿它反解距离；恒星泛光只拿它与 {@link #spaceSkyDepthSnapshot} 比对，
     * 判断像素是不是 MC 几何体，不用它算距离（见 {@link SpaceStarBloomRenderer} 类注释）。</p>
     */
    private static RenderTarget spaceDepthSnapshot;

    /**
     * AFTER_SKY 星球层画完、MC 地形还没开始画时的主深度副本。
     *
     * <p>泛光需要区分「星球」和「MC 几何体」，但 AFTER_PARTICLES 时主深度是混合投影的：
     * 星球用 spaceProj（near=1000m / far=1e13m）写入，地形/实体/粒子随后用 MC 自己的投影写入，
     * 两者深度区间互相重叠（地球 8.88e6m → 0.9998874，443m 处的方块 → 0.9998871），
     * 单看一个值分不出来源。留下这份底之后逐像素比较就够了：
     * {@code depthNow < skyDepth} 当且仅当该像素有 MC 几何体通过深度测试、真正画了上去。</p>
     *
     * <p>space mod 不需要这一步，因为它让两套投影共用同一对 near/far
     * （mixin 改 getDepthFar 为 FarCompress*4，太空投影取 setPerspective(4194304, 0.05)），
     * 于是 {@code max(1 - mainDepth, spaceDepth)} 天然就是「更近的那个表面」。
     * 本项目没这套约定，只能用双快照换。</p>
     */
    private static RenderTarget spaceSkyDepthSnapshot;
    /** {@link #spaceSkyDepthSnapshot} 的深度纹理 id，每帧在 AFTER_SKY 刷新；本帧没有恒星时为 0。 */
    private static int spaceSkyDepthTexture;

    private SpaceRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        boolean inSpace = mc.level.dimension().equals(PlanetDimensions.SPACE);
        // ★ 地表维度也要画天体（space 的 SpaceRenderer 在**所有**维度都跑）：
        // "这个维度对应一个 ClientCelestialWorld" 就等于"我站在某颗行星上"，
        // 此时应按那颗行星在宇宙里的真实位置与朝向看到别的天体。
        // 数据层早已就位（CelestialWorld 的 getSpacePosFromWorldPos/getRotateFromWorldPos），
        // 缺的一直只是这条渲染分支（见 docs/mps-clone-plan.md §27.1）。
        ClientCelestialWorld celestialWorld = inSpace ? null : ClientCelestialWorld.getCelestialWorld();
        if (!inSpace && celestialWorld == null) return;
        // 窗口最小化：跳过自定义太空渲染。此时窗口宽高可能为 0（宽高比 NaN、
        // 投影矩阵全 NaN），且后台的 shader pass / 深度 blit 是历史卡死高发区
        // （配套修复见 WindowUpdateDisplayMixin：最小化时保留 GLFW 事件泵）。
        if (isWindowIconified(mc)) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            renderSpaceBodies(event, celestialWorld);
        } else if (inSpace && event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            // 大气/泛光后处理绑定太空维度的深度快照与投影约定，地表先不接
            renderSpacePostEffects();
        }
    }

    /**
     * AFTER_SKY：绘制天体本体/云层/光环/日冕，并记录本帧数据供大气后处理。
     *
     * @param celestialWorld 非 null = 当前在<b>地表维度</b>，相机帧要按该行星的宇宙位姿换算；
     *                       null = 太空维度，保持原来的线性 {@code ZOOM} 换算（已验收路径）
     */
    private static void renderSpaceBodies(RenderLevelStageEvent event, ClientCelestialWorld celestialWorld) {
        Minecraft mc = Minecraft.getInstance();
        // S3 作用域：压缩只在**太空维度**生效（地表天空走宇宙系真实位姿 + 另一套相机帧，先不混）。
        // enabled=false 时这里恒 false ⇒ 下面接进来的压缩对画面零影响。
        RenderCompression.active = RenderCompression.enabled && celestialWorld == null;
        Camera camera = event.getCamera();
        var camPos = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        // 星球 view：跟随完整相机旋转（含 roll），与 MC 主世界渲染一致
        // （GameRenderer.renderLevel 用 camera.rotation().conjugate() 构建 view）。
        // 天空背景由 SpaceDimensionEffects 走 MC 原生天空盒管线绘制（地表就是原版天空）。
        var cameraRot = new org.joml.Quaternionf(camera.rotation()).conjugate();
        // ★ 相机在"宇宙坐标系"里的位置与朝向：
        //   - 太空维度：MC 坐标 × ZOOM（原样，无额外旋转）；
        //   - 地表维度：CelestialWorld 把"站在球面上的相机"换算成宇宙坐标，
        //     并再叠一层朝向（地表的上/前与宇宙坐标系的上/前不是同一套轴）。
        double camRealX;
        double camRealY;
        double camRealZ;
        Quaternionf spaceRotation = new Quaternionf();
        if (celestialWorld != null) {
            org.joml.Vector3d p = celestialWorld.getSpacePosFromWorldPos(camPos, partialTick);
            org.joml.Quaterniond q = celestialWorld.getRotateFromWorldPos(camPos, partialTick);
            camRealX = p.x;
            camRealY = p.y;
            camRealZ = p.z;
            spaceRotation.set((float) q.x, (float) q.y, (float) q.z, (float) q.w);
        } else {
            camRealX = SpaceWorld.toReal(camPos.x);
            camRealY = SpaceWorld.toReal(camPos.y);
            camRealZ = SpaceWorld.toReal(camPos.z);
        }
        Matrix4f view = new Matrix4f().rotation(cameraRot).mul(new Matrix4f().rotation(spaceRotation));
        float aspect = (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        if (!(aspect > 0.0f) || !Float.isFinite(aspect)) return; // 宽高为 0 时兜底（不应发生）
        Matrix4f spaceProj = new Matrix4f().perspective((float) Math.toRadians(FOV_DEG), aspect,
                SPACE_NEAR_PLANE,
                // 压缩启用时 far 收紧到 FAR×2：压缩把所有天体压进 (NEAR, FAR)，
                // far 若仍是 1e13，near/far 比 1e10 会让深度精度白瞎（压缩就白做了）。
                // FAR×2 而不是 FAR：`exp(-巨大)` 下溢到 0 ⇒ "无穷远"压缩后**恰好等于 FAR**，
                // far 若正好 = FAR，最远的天体会正好落在远平面上被裁掉（space 取 FAR×2 就是这个原因）。
                RenderCompression.active ? (float) (RenderCompression.FAR * 2.0) : SPACE_FAR_PLANE);
        // 天空盒投影：同 FOV，near/far 覆盖 r=1000 立方体即可（不影响屏幕方向，只影响深度）。
        Matrix4f skyProj = new Matrix4f().perspective((float) Math.toRadians(FOV_DEG), aspect, 0.05f, 2000.0f);

        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.set(view);
        RenderSystem.applyModelViewMatrix();

        try {
            // 直接用 MC 原生天空盒（维度 effects），不再手画 cubemap。
            RenderSystem.setProjectionMatrix(spaceProj, VertexSorting.DISTANCE_TO_ORIGIN);
            // 星球 BASE 层不透明：确认深度测试/写入开启（天空盒绘制后依赖它恢复，这里显式兜底）。
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.clearDepth(1.0f);
            RenderSystem.clear(0x100, false);
            // 自转相位用存档世界时间（gameTime），不用真实时间：
            // 真实 J2000 秒(~8e8) × 自转速度 的相位在每次启动时近乎随机，
            // 星球每次进游戏都换一面（会被误认为"贴图种子变了"）。
            // 世界时间保证同一存档相位连续、可复现。
            double seconds = Minecraft.getInstance().level.getGameTime() / 20.0
                    + partialTick / 20.0;

            // ★ 照 space 的 SpaceRenderer.init（0.1.3 第 101-106 行）：确认显示世界存在后，
            // 在**消费任何位置之前**先把网络缓冲区（bufferSpaceWorld）的位姿搬进显示世界。
            //
            // 为什么必须是这一步、且必须在这里：天体位姿由 SyncCelestialBodyMoveBatch 每 tick
            // 写进**缓冲区**（入队 moveTo），显示世界只有这一处同步点。缺了它，下面的
            // refreshPositions() → SpaceWorld.gamePos() → kelvinPos() 读到的就永远是
            // SyncCelestialBodyCreate 时的快照。
            // 症状极具误导性：服务端积分在动、/polymech kelvin 里的数字在变，
            // 而天上的星球纹丝不动（因为 kelvinPos 优先读显示世界）。
            //
            // 空判定与 space 一致（它在 init 里先判 spaceWorld != null 才调用；
            // syncMoveData 内部不判空 —— 不照抄这个判空会 NPE）。
            if (ClientSpaceWorld.getSpaceWorld() != null) {
                ClientSpaceWorld.syncMoveData();
            }

            // 天体位置刷新必须在排序之前：排序按到相机的距离，用的就是这些位置。
            // ★ 地表维度用"宇宙系真实三维位置"（与相机**同源**）：gamePos 会把日心天体的 Y
            // 压成 0，而地表的相机带真实 Y ⇒ 那样所有天体会在天空里连成一条线（实测现象）。
            // 太空维度保持原样（那里 Y 必须压平，否则天体飞出玩家可飞的维度高度）。
            if (celestialWorld != null) {
                PlanetRenderObjectFactory.refreshPositionsFromCelestial(partialTick);
            } else {
                // 未启用 kelvin 权威时这是一次等同赋值（见 refreshPositions 的注释）。
                PlanetRenderObjectFactory.refreshPositions();
            }

            // 按相机距离从远到近排序：不透明 BASE 可减少 overdraw，
            // 半透明 CLOUD/RING/日冕按正确顺序混合。
            List<PlanetRenderObject> bodies = new ArrayList<>(PlanetRenderObjectFactory.bodies());
            // ★ 地表维度：**绝不能画脚下这颗星自己**。
            // 相机就贴在它的球面上（甚至落在球内）：画出来要么被它的背面糊满整个天幕
            //（背face剔除后就是"一片黑"，正是实测火星那张），要么把地表盖住。
            // space 的可见性过滤同样排除"当前所在天体"。
            if (celestialWorld != null) {
                String selfId = celestialWorld.celestialBody.getName();
                bodies.removeIf(o -> selfId.equalsIgnoreCase(o.planetName()));
            }
            bodies.sort(Comparator.comparingDouble(
                    (PlanetRenderObject o) -> bodyDistanceSq(o, camRealX, camRealY, camRealZ)).reversed());
            double simTime = seconds;
            // 地表天空的投影探针（1 秒一行）：把最近两颗天体按**本帧真正用的** view/proj
            // 投影成 NDC 打出来。"黑屏"于是可判：w<=0 = 在相机后面（旋转错），
            // |ndc|>1 = 在视锥外（朝向/位置错），都在范围内却看不见 = 被别的东西挡住。
            surfaceSkyDiag(bodies, view, spaceProj, camRealX, camRealY, camRealZ, celestialWorld != null);

            // 每颗行星独立计算“该行星指向太阳”的平行光。
            // 太阳位于原点，若仍用相机位置算全局光向，远处行星的晨昏线会明显错误。
            // 每帧只算一次，BASE/CLOUD/ATMO/RING/日冕各 pass 共用同一组光照参数。
            List<PlanetRenderParams> bodyParams = new ArrayList<>(bodies.size());
            for (PlanetRenderObject obj : bodies) {
                bodyParams.add(paramsForBody(view, spaceProj, obj,
                        camRealX, camRealY, camRealZ, partialTick, simTime));
            }

            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).render(bodyParams.get(i));
            }

            // CLOUD 半透明层：与 GUI 星图一致，先画云，再让大气壳覆盖在云上。
            RenderSystem.depthMask(false);
            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).renderClouds(bodyParams.get(i));
            }

            // 大气壳（GPU rim）：太阳与带大气的行星都走这里；屏幕空间散射负责更柔和的外晕。
            RenderSystem.depthMask(false);
            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).renderAtmosphere(bodyParams.get(i));
            }

            // RING 半透明 pass：与 GUI 星图一致（不写深度）。
            RenderSystem.depthMask(false);
            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).renderRings(bodyParams.get(i));
            }

            // 恒星 3D 日冕光晕：depthMask(false) 且深度测试开启，可被前景行星正确遮挡。
            drawSunGlows(view, bodies, camRealX, camRealY, camRealZ);

            // 记录本帧数据，AFTER_PARTICLES 阶段再做屏幕空间泛光 + 大气散射。
            // 这样后处理不会在 AFTER_SKY 阶段清空主深度缓冲，避免破坏后续地形的深度遮挡。
            boolean hasStars = false;
            for (PlanetRenderObject obj : bodies) {
                if (obj.visual().isGlowing()) {
                    hasStars = true;
                    break;
                }
            }
            spaceView = view;
            SpaceRenderer.spaceProj = spaceProj;
            spaceBodies = bodies;
            spaceCamRealX = camRealX;
            spaceCamRealY = camRealY;
            spaceCamRealZ = camRealZ;
            spacePartialTick = partialTick;
            spaceFrameDrawn = true;
            spaceHasStars = hasStars;

            // 星球层到此为止、MC 地形还没画 —— 这是留深度底唯一可行的时机。
            // 没有恒星就不留：泛光是这份底唯一的消费者，能省掉一次全屏 blit。
            spaceSkyDepthTexture = hasStars ? captureSkyDepth(mc.getMainRenderTarget()) : 0;

        } finally {
            mvs.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            // ★ 压缩只在本帧的**太空渲染调用内**有效。
            // 不在这里关掉的话：离开太空维度（或本帧根本没进 renderSpaceBodies）时
            // `active` 会**留在 true**，于是 GUI 星图 / fallback 行星渲染也会吃到压缩 ——
            // 它们各有自己的尺度，那是错的画面。（"作用域"必须显式收窄到调用范围内。）
            RenderCompression.active = false;
        }
    }

    /**
     * AFTER_PARTICLES：在 MC 地形/实体/粒子之后叠加屏幕空间特效，匹配 space mod 的原有时序。
     *
     * <p>顺序与 space mod 的 {@code RenderEffectAfter} 一致：先恒星泛光，再行星大气散射。
     * 天体数据只上传一次，两条链路共用同一个 UBO。</p>
     */
    private static long surfaceSkyDiagMs = 0L;

    /**
     * 地表天空投影探针（1 秒一行，只在地表维度打）。
     *
     * <p>为什么需要它：地表天空"黑屏"有三种完全不同的原因，
     * 而它们在人眼里长得一模一样。把最近两颗天体按<b>本帧真正用的</b> view/proj
     * 投影成 NDC 就能一次分开：</p>
     * <ul>
     *   <li>{@code w <= 0} ⇒ 天体在相机<b>后面</b> —— 朝向（spaceRotation）合成错了；</li>
     *   <li>{@code |ndc| > 1} ⇒ 在视锥<b>外</b> —— 相机位置/朝向偏了；</li>
     *   <li>都在范围内却看不见 ⇒ 被别的东西挡住（例如脚下那颗星自己的球面）。</li>
     * </ul>
     */
    private static void surfaceSkyDiag(List<PlanetRenderObject> bodies, Matrix4f view, Matrix4f proj,
                                       double camX, double camY, double camZ, boolean surface) {
        if (!surface) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - surfaceSkyDiagMs < 1000L) {
            return;
        }
        surfaceSkyDiagMs = now;
        Matrix4f vp = new Matrix4f(proj).mul(view);
        StringBuilder sb = new StringBuilder();
        // ⚠️ 列表按距离**从远到近**排（overdraw 顺序），所以要取**末尾**三个才是最近的。
        // 第一版取 get(0) 打的是冥王星/冥卫一（最远），根本说明不了问题 —— 记下来别再犯。
        int n = bodies.size();
        for (int i = Math.max(0, n - 3); i < n; i++) {
            PlanetRenderObject o = bodies.get(i);
            double dx = o.posX() - camX;
            double dy = o.posY() - camY;
            double dz = o.posZ() - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            org.joml.Vector4f p = new org.joml.Vector4f((float) dx, (float) dy, (float) dz, 1.0f);
            vp.transform(p);
            float w = p.w;
            // 角直径（度）+ 在 1083px/70° 屏上的像素：**"看不见"到底是不是亚像素**，一眼可判
            double angDeg = Math.toDegrees(2.0 * o.radius() / Math.max(1.0, dist));
            sb.append(String.format(java.util.Locale.ROOT,
                    "  %s: 距=%.3e 角直径=%.4f° ≈%.2fpx w=%.2e ndc=(%.2f, %.2f)",
                    o.planetName(), dist, angDeg, angDeg / 70.0 * 1083.0, w,
                    w != 0.0f ? p.x / w : Float.NaN, w != 0.0f ? p.y / w : Float.NaN));
        }
        com.mss.polymech.Polymech.LOGGER.info("[Kelvin] [地表天空] 相机(宇宙系)=({}, {}, {}){}",
                String.format(java.util.Locale.ROOT, "%.3e", camX),
                String.format(java.util.Locale.ROOT, "%.3e", camY),
                String.format(java.util.Locale.ROOT, "%.3e", camZ), sb);
    }

    private static void renderSpacePostEffects() {
        if (!spaceFrameDrawn) return;
        if (spaceBodies == null || spaceView == null || spaceProj == null) return;
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        try {
            CelestialBodyDataBuffer.get().update(spaceBodies, spaceCamRealX, spaceCamRealY, spaceCamRealZ);
            int depthTexture = snapshotDepth(mainTarget);
            try {
                if (spaceHasStars && spaceSkyDepthTexture != 0) {
                    SpaceStarBloomRenderer.get().render(spaceView, spaceProj, spacePartialTick,
                            depthTexture, spaceSkyDepthTexture);
                }
                SpaceAtmosphereRenderer.get().render(spaceView, spaceProj, spacePartialTick, depthTexture);
            } finally {
                restoreDepth(mainTarget);
            }
        } finally {
            spaceFrameDrawn = false;
            // 深度纹理 id 只在本帧有效，清掉以免下一帧误用陈值。
            spaceSkyDepthTexture = 0;
        }
    }

    /**
     * 在星球层画完、MC 地形还没开始画时把主深度复制一份作为「底」，返回其深度纹理 id。
     *
     * @see #spaceSkyDepthSnapshot
     */
    private static int captureSkyDepth(RenderTarget mainTarget) {
        if (spaceSkyDepthSnapshot == null) {
            spaceSkyDepthSnapshot = new TextureTarget(mainTarget.width, mainTarget.height, true, false);
            // 同 snapshotDepth：两边深度附件格式不一致时 glBlitFramebuffer 会静默失败。
            if (mainTarget.isStencilEnabled()) {
                spaceSkyDepthSnapshot.enableStencil();
            }
        } else if (spaceSkyDepthSnapshot.width != mainTarget.width || spaceSkyDepthSnapshot.height != mainTarget.height) {
            spaceSkyDepthSnapshot.resize(mainTarget.width, mainTarget.height, false);
        }
        // blit 走的是拷贝路径而不是片元管线，理论上不受 depthMask 影响；
        // 这里显式置 true 只是为了排除驱动差异（drawSunGlows 的 finally 已经把它恢复成 true）。
        RenderSystem.depthMask(true);
        spaceSkyDepthSnapshot.copyDepthFrom(mainTarget);
        // copyDepthFrom 会把 GL_FRAMEBUFFER 解绑到默认窗口缓冲。这里必须绑回主缓冲，
        // 否则紧接着的地形会画到窗口上而不是主 FBO。
        mainTarget.bindWrite(false);
        return spaceSkyDepthSnapshot.getDepthTextureId();
    }

    /** 把主缓冲深度 blit 到快照缓冲，返回快照的深度纹理 id。 */
    private static int snapshotDepth(RenderTarget mainTarget) {
        if (spaceDepthSnapshot == null) {
            spaceDepthSnapshot = new TextureTarget(mainTarget.width, mainTarget.height, true, false);
            // 与 PostChain.addTempTarget 一致：主缓冲开了 stencil 就必须跟着开。
            // 两边的深度附件格式不一致时 glBlitFramebuffer 会静默失败（GL_INVALID_OPERATION），
            // 快照里永远是创建时清出来的 1.0，大气散射就会把整屏当成无穷远天空。
            if (mainTarget.isStencilEnabled()) {
                spaceDepthSnapshot.enableStencil();
            }
        } else if (spaceDepthSnapshot.width != mainTarget.width || spaceDepthSnapshot.height != mainTarget.height) {
            spaceDepthSnapshot.resize(mainTarget.width, mainTarget.height, false);
        }
        spaceDepthSnapshot.copyDepthFrom(mainTarget);
        // copyDepthFrom 会把 GL_FRAMEBUFFER 解绑到默认窗口缓冲，这里必须绑回主缓冲。
        mainTarget.bindWrite(false);
        return spaceDepthSnapshot.getDepthTextureId();
    }

    /** 把快照深度写回主缓冲，保证后续云/天气仍有正确的深度遮挡。 */
    private static void restoreDepth(RenderTarget mainTarget) {
        if (spaceDepthSnapshot == null) return;
        mainTarget.copyDepthFrom(spaceDepthSnapshot);
        mainTarget.bindWrite(false);
    }

    /** GUI 阶段暂不绘制额外元素，保持太空画面干净；后续可在此挂接太空 HUD。 */
    public static void onRenderGui(RenderGuiEvent.Post event) {
    }

    private static PlanetRenderParams paramsForBody(Matrix4f view, Matrix4f proj,
                                                     PlanetRenderObject obj,
                                                     double camRealX, double camRealY, double camRealZ,
                                                     float partialTick, double simTime) {
        PlanetLighting lighting = new PlanetLighting();
        double ldx = -obj.posX();
        double ldy = -obj.posY();
        double ldz = -obj.posZ();
        double llen = Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz);
        if (llen < 1e-5) {
            ldx = 0;
            ldy = 1;
            ldz = 0;
        } else {
            ldx /= llen;
            ldy /= llen;
            ldz /= llen;
        }
        lighting.updateGlobal((float) ldx, (float) ldy, (float) ldz, 1.0f);
        return new PlanetRenderParams(view, proj, camRealX, camRealY, camRealZ,
                partialTick, simTime, lighting, null);
    }

    /** 窗口是否已最小化（iconified）：GLFW 属性只在事件循环中更新。 */
    private static boolean isWindowIconified(Minecraft mc) {
        long handle = mc.getWindow().getWindow();
        return handle != 0L
                && org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED)
                == org.lwjgl.glfw.GLFW.GLFW_TRUE;
    }

    private static double bodyDistanceSq(PlanetRenderObject body,
                                         double camX, double camY, double camZ) {
        double dx = body.posX() - camX;
        double dy = body.posY() - camY;
        double dz = body.posZ() - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 恒星 3D 日冕：相机空间公告板，与 GUI 星图共用 {@link StarGlowRenderer}。 */
    private static void drawSunGlows(Matrix4f view, List<PlanetRenderObject> bodies,
                                     double camRealX, double camRealY, double camRealZ) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();

        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
        try {
            for (int i = 0; i < bodies.size(); i++) {
                PlanetRenderObject body = bodies.get(i);
                if (!body.visual().isGlowing()) continue;
                float[] base = body.visual().baseColor();
                if (base == null) continue;
                TMP_REL.set((float) (body.posX() - camRealX),
                        (float) (body.posY() - camRealY),
                        (float) (body.posZ() - camRealZ));
                view.transformPosition(TMP_REL, TMP_CAM);
                // 相机空间 -Z 为前方，日冕中心在相机后方或极近处时跳过。
                if (TMP_CAM.z > -(float) body.radius()) continue;
                float baseR = (float) body.radius();
                float gr = base[0] * 0.65f + 0.35f;
                float gg = base[1] * 0.65f + 0.35f;
                float gb = base[2] * 0.65f + 0.35f;
                StarGlowRenderer.drawGlowHalo(IDENTITY,
                        TMP_CAM.x, TMP_CAM.y, TMP_CAM.z,
                        baseR * 1.00f, baseR * 2.40f, 0.35f,
                        gr, gg, gb);
            }
        } finally {
            mvs.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }
}
