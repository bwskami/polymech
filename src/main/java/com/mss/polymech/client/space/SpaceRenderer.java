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
        // ⚠️ 地表维度**没有原版天空**：我们所有维度都用 SpaceDimensionEffects，它的 skyType 是
        //    SkyType.NONE ⇒ LevelRenderer.renderSky 既不进 END 分支也不进 NORMAL 分支
        //    ⇒ 原版日月/星星一个都不画。所以地表天上那个太阳**只可能是本文件画的**
        //    （`bodies` 里的太阳本体 + drawSunGlows，两者都用下面这个 view）——
        //    这也是"太阳位置不对就一定是 getRotateFromWorldPos 不对"的依据。
        //    （此处旧注释写的是"地表就是原版天空"，与实际不符，已订正。）
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
                // ★ 必须带上 partialTick（2026-09-25）：太空维度这条一直用的是**无插值**的
                //   blockPos → getPos()，物理 20Hz / 渲染 60fps ⇒ 天体一个 tick 跳一次，
                //   而天体速度是真实轨道速度 × 71.8（地球每 tick 108.2 km）⇒ 近地观察时
                //   就是肉眼可见的台阶。地表那条路早就带 partialTick 了，只有这里漏了。
                PlanetRenderObjectFactory.refreshPositions(partialTick);
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
            // ★ 太空维度的"视运动"实测（用户 2026-09-25："太空维度里的星球移动还是不够流畅"）。
            //   太空这条路的病灶与地表不同：地表是姿态帧，太空是**位置压根没插值**
            //   （走 gamePos → getPos()，物理步进原始值）。这条探针把"到底有多不流畅"变成数：
            //   视运动 °/秒 + 每帧多少像素 + 插值跨度（=0 就是没插值）。
            if (celestialWorld == null) {
                spaceMotionDiag(bodies, camRealX, camRealY, camRealZ);
            }
            double simTime = seconds;
            // 地表天空的投影探针（1 秒一行）：把最近两颗天体按**本帧真正用的** view/proj
            // 投影成 NDC 打出来。"黑屏"于是可判：w<=0 = 在相机后面（旋转错），
            // |ndc|>1 = 在视锥外（朝向/位置错），都在范围内却看不见 = 被别的东西挡住。
            surfaceSkyDiag(bodies, view, spaceProj, camRealX, camRealY, camRealZ, celestialWorld != null,
                    celestialWorld, partialTick);

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

    /** 太空视运动探针的节流与上一拍样本。 */
    private static long spaceMotionDiagMs = 0L;
    private static long spaceDiagLastNanos = 0L;
    private static org.joml.Vector3d spaceDiagLastDir = null;

    /**
     * <b>太空维度</b>的视运动 / 顺滑度实测 —— <b>每帧累计</b>，每秒打一行。
     *
     * <h2>为什么要"每帧累计"</h2>
     * 只看"每秒视运动多少度"判断不了丝滑不丝滑：匀速扫过和被"跳一下"混在一起，
     * 平均速率是一样的。**丝滑度的客观判据是"每帧位移的离散程度"**：
     * 匀速平移时每帧位移几乎相等（抖动 = 最大/均 ≈ 1.0）；只要每秒混进几次跳变，
     * 抖动就会明显 &gt; 1。所以这里逐帧采样，输出：
     * <pre>
     *   每帧位移 均/最大/最小(px)  抖动=最大/均   卡帧=n/总帧   ← 卡帧 = 位移 &lt; 5% 均值
     *   样本间隔 均/最大/最小(ms)                    ← 网络拍子的抖动（这是"跳变"的来源）
     * </pre>
     *
     * <h2>三种成因怎么分开</h2>
     * <ul>
     *   <li><b>插值跨度 = 0</b> ⇒ 位置根本没插值（走的是原始 {@code getPos()} 口径）；</li>
     *   <li><b>样本间隔忽大忽小</b> ⇒ 拍子抖动；两拍 lerp 在交换相位不固定时每 tick 跳 Δ，
     *       抖动会很大 —— 这正是 2026-09-25 修掉的那个（改成时间戳插值，见 {@code CelestialBody}）；</li>
     *   <li><b>天体本身动得极快</b>（真实轨道速度 × 71.8：地球每 tick 108 km）⇒ 每帧像素数大，
     *       但抖动仍应 ≈1；这属于设计后果，不是 bug。</li>
     * </ul>
     */
    private static void spaceMotionDiag(List<PlanetRenderObject> bodies,
                                        double camX, double camY, double camZ) {
        try {
            // 玩家"正在看"的那颗 = 最近的（太空里视角基本被最近这颗占满）
            PlanetRenderObject nearest = null;
            double best = Double.MAX_VALUE;
            for (PlanetRenderObject o : bodies) {
                double d2 = bodyDistanceSq(o, camX, camY, camZ);
                if (d2 < best) {
                    best = d2;
                    nearest = o;
                }
            }
            if (nearest == null) {
                return;
            }
            double dist = Math.sqrt(best);
            org.joml.Vector3d dir = new org.joml.Vector3d(
                    nearest.posX() - camX, nearest.posY() - camY, nearest.posZ() - camZ).normalize();
            long nowNanos = System.nanoTime();

            // ===== 每帧累计：最近天体的角位移（度）=====
            // 换目标（离另一颗更近）就重新开始统计，否则两次"跳"会被算成抖动
            if (smoothLastDir != null && smoothLastBody == nearest) {
                double dDeg = Math.toDegrees(Math.acos(
                        Math.max(-1.0, Math.min(1.0, dir.dot(smoothLastDir)))));
                smoothFrames++;
                smoothSumDeg += dDeg;
                if (dDeg > smoothMaxDeg) {
                    smoothMaxDeg = dDeg;
                }
                if (smoothFrames > 1 && dDeg < smoothMinDeg) {
                    smoothMinDeg = dDeg;
                }
                if (dDeg < 0.02) {
                    smoothZeroFrames++;
                }
                if (smoothDispCount < smoothDisp.length) {
                    smoothDisp[smoothDispCount++] = dDeg;
                }
            } else {
                smoothFrames = 0;
                smoothSumDeg = 0.0;
                smoothMaxDeg = 0.0;
                smoothMinDeg = Double.MAX_VALUE;
                smoothZeroFrames = 0;
                smoothDispCount = 0;
            }
            smoothLastDir = new org.joml.Vector3d(dir);
            smoothLastBody = nearest;

            // 样本间隔的抖动（每帧读一次"最近一拍的间隔"，一秒里取 min/max/均）
            double si = Double.NaN;
            com.mss.polymech.space.RealAstroData data =
                    com.mss.polymech.space.RealAstroData.byId(nearest.planetName());
            var cw = com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld.getSpaceWorld();
            var cb = cw == null || data == null ? null : cw.getCelestialBody(data.id());
            if (cb != null) {
                si = cb.sampleIntervalSeconds();
                if (!Double.isNaN(si)) {
                    siSumMs += si * 1000.0;
                    siCount++;
                    if (si * 1000.0 > siMaxMs) {
                        siMaxMs = si * 1000.0;
                    }
                    if (si * 1000.0 < siMinMs) {
                        siMinMs = si * 1000.0;
                    }
                }
            }

            // ===== 每秒打一行 =====
            if (nowNanos - spaceMotionDiagNanos < 1_000_000_000L) {
                return;
            }
            double dtSec = spaceMotionDiagNanos == 0L ? 1.0 : (nowNanos - spaceMotionDiagNanos) / 1.0e9;
            spaceMotionDiagNanos = nowNanos;

            int fps = Math.max(1, net.minecraft.client.Minecraft.getInstance().getFps());
            double pxPerDeg = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight()
                    / (double) FOV_DEG;
            // ★ 抖动用 max/**中位数**，不用 max/均值：天体掠过时角速度本身在变（越近越快），
            //   均值会被加速段拉高、把真 bug 掩盖掉。中位数对"真实的加速"免疫，只对"跳变"敏感。
            //   这个判据的阈值由离线对拍标定：native/jni-smoketest/InterpProbe.java
            //   （旧实现抖动 4.15 / 与真值偏差 6.80px；新实现 1.09 / 0.024px）。
            double medianDeg = median(smoothDisp, smoothDispCount);
            String jitter = medianDeg > 1.0e-9
                    ? String.format(java.util.Locale.ROOT, "%.2f", smoothMaxDeg / medianDeg) : "n/a";
            String ratePerSec = smoothFrames > 0
                    ? String.format(java.util.Locale.ROOT, "%.3f", smoothSumDeg / dtSec) : "n/a";
            String siTxt = siCount > 0
                    ? String.format(java.util.Locale.ROOT, "均=%.1f 最大=%.1f 最小=%.1f",
                            siSumMs / siCount, siMaxMs, siMinMs)
                    : "n/a";
            String span = cb == null ? "n/a"
                    : String.format(java.util.Locale.ROOT, "%.3e", cb.sampleSpan());
            String angDiam = String.format(java.util.Locale.ROOT, "%.3f",
                    Math.toDegrees(2.0 * Math.atan(nearest.radius() / Math.max(1.0, dist))));

            boolean okSmooth = medianDeg > 1.0e-9 && smoothMaxDeg / medianDeg < 1.30;
            // ⚠️ SLF4J 的 {} 不支持格式说明符 ⇒ 数字全部先 String.format 拼好
            com.mss.polymech.Polymech.LOGGER.info(
                    "[Kelvin] [太空视运动] 最近={} 距离={} m 角直径={}° | 视运动={}°/秒"
                            + " | 每帧位移(px) 均={} 最大={} 最小={} 抖动(max/中位)={} 卡帧={}/{} ⇒ 顺滑={}"
                            + " | 样本间隔(ms) {} | 插值跨度={} m | 帧率={} fps",
                    nearest.planetName(),
                    String.format(java.util.Locale.ROOT, "%.3e", dist), angDiam, ratePerSec,
                    fmtPx(smoothFrames > 0 ? smoothSumDeg / smoothFrames : Double.NaN, pxPerDeg),
                    fmtPx(smoothMaxDeg, pxPerDeg),
                    smoothMinDeg == Double.MAX_VALUE ? "n/a" : fmtPx(smoothMinDeg, pxPerDeg),
                    jitter, smoothZeroFrames, smoothFrames, okSmooth ? "PASS(抖动<1.30)" : "★FAIL(有跳变)",
                    siTxt, span, fps);

            // 下一轮重新累计
            smoothFrames = 0;
            smoothSumDeg = 0.0;
            smoothMaxDeg = 0.0;
            smoothMinDeg = Double.MAX_VALUE;
            smoothZeroFrames = 0;
            smoothDispCount = 0;
            siSumMs = 0.0;
            siCount = 0;
            siMaxMs = 0.0;
            siMinMs = Double.MAX_VALUE;
        } catch (Throwable t) {
            // 诊断绝不允许影响渲染
            com.mss.polymech.Polymech.LOGGER.info("[Kelvin] [太空视运动] ★探针异常(已吞): {}", t.toString());
        }
    }

    /** 最近天体"每帧角位移"的累计（见 {@link #spaceMotionDiag}）。 */
    private static PlanetRenderObject smoothLastBody = null;
    private static org.joml.Vector3d smoothLastDir = null;
    private static int smoothFrames = 0;
    private static double smoothSumDeg = 0.0;
    private static double smoothMaxDeg = 0.0;
    private static double smoothMinDeg = Double.MAX_VALUE;
    private static int smoothZeroFrames = 0;
    /** 每帧角位移样本（用来算中位数；一秒最多几百帧，512 足够）。 */
    private static final double[] smoothDisp = new double[512];
    private static int smoothDispCount = 0;
    /** 上一行的时刻（纳秒）。 */
    private static long spaceMotionDiagNanos = 0L;
    /** 样本间隔统计（毫秒）。 */
    private static double siSumMs = 0.0;
    private static int siCount = 0;
    private static double siMaxMs = 0.0;
    private static double siMinMs = Double.MAX_VALUE;

    /** 角度（度）× 每度像素 ⇒ 像素字符串；NaN 输出 n/a。 */
    private static String fmtPx(double deg, double pxPerDeg) {
        return Double.isNaN(deg) ? "n/a"
                : String.format(java.util.Locale.ROOT, "%.2f", deg * pxPerDeg);
    }

    /** 取前 n 个样本的中位数（不改动原数组）。 */
    private static double median(double[] values, int n) {
        if (n <= 0) {
            return Double.NaN;
        }
        double[] copy = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(copy);
        return copy[n / 2];
    }

    // ★★ 时钟 / 顺滑度实测用的上一拍样本（2026-09-25 用户三问：是不是 20 分钟一天？
    //    为什么看着比原版快？为什么"跟抽帧一样"？）。全部用**实测值**回答，不靠推理。
    /** 上一拍的 System.nanoTime()。 */
    private static long diagLastNanos = 0L;
    /** 上一拍的世界 dayTime（tick）。 */
    private static long diagLastDayTime = 0L;
    /** 上一拍的位姿搬运累计次数。 */
    private static long diagLastPoseCopy = 0L;
    /** 上一拍的太阳时角（未解缠，±180° 处会回绕）。 */
    private static double diagLastHourRaw = Double.NaN;
    /** 时角解缠累计值（用来算真正的角速度）。 */
    private static double diagHourUnwrapped = 0.0;
    /** 上一拍的时角解缠累计值。 */
    private static double diagLastHourUnwrapped = Double.NaN;

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
                                       double camX, double camY, double camZ, boolean surface,
                                       ClientCelestialWorld celestialWorld, float partialTick) {
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
        // ★★ 数学自检（**刻意不用 view 矩阵**：只输出向量与角度，否则相机一转数就全变、无法验算）。
        //   用户 2026-09 要求："抓取位置与相机相对位置等能通过数学验证的信息，然后自己改出来"。
        //   要能离线验的四条：
        //     ① 观察者纬度 = asin(up·pole) 应恒定（地球自转轴=+Y，倾角待补⇒pole=(0,1,0)）；
        //     ② 黄道(≈行星轨道面) 相对地平线的倾角 = 90° − angle(up, 黄道法向)。
        //        ⚠️ 2026-09-22 订正：**只有倾角 ε=0 时它才恒定 90°**；一旦有真实黄赤交角，
        //        观察者的 up 绕的是倾斜的自转轴，于是这个角全天在 90°±ε 之间摆动 ——
        //        **摆动是正确的**（真实天空就是这样），别再把它当 bug 追。
        //     ③ 每个天体的 "高度角 = 90° − |时角|"（δ=0 时的严格关系）；
        //     ④ 太阳时角应 ≈ 时钟角 + 观察者经度（我们那条公式的预言）。
        StringBuilder chk = new StringBuilder();
        // ⚠️ 整段自检必须被 try/catch 包住：诊断代码绝不允许崩客户端
        // （2026-09-22 就因为 %d 配 double 抛 IllegalFormatConversionException，把整局打崩了）。
        try {
        net.minecraft.client.multiplayer.ClientLevel diagLevel =
                net.minecraft.client.Minecraft.getInstance().level;
        if (celestialWorld != null && celestialWorld.celestialBody != null && diagLevel != null) {
            // ⚠️ 必须是 long：下面格式串用的是 %d，传 double 会抛
            // IllegalFormatConversionException（2026-09-22 就是这样把客户端打崩的）。
            long dayTime = diagLevel.getDayTime();
            double clockDeg = Math.toDegrees(2.0 * Math.PI * ((dayTime % 24000L) - 6000L) / 24000.0);
            // 供下面的"时钟/顺滑度"块引用（在太阳那一支里赋值；那一支可能不执行 ⇒ 保持 NaN = 不判）
            double sunHourDeg = Double.NaN;     // 太阳时角实测值
            double sunSpan = Double.NaN;        // 太阳位置的插值跨度 |pos − old_pos|
            double sunAngDiamDeg = Double.NaN;  // 太阳角直径（解释"为什么看着比原版快"）
            // 太阳在宇宙系里的位置（距角自检要用；拿不到就跳过该判据）
            org.joml.Vector3d sunSpacePos = null;
            if (celestialWorld.celestialBody.spaceWorld != null) {
                com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody sunForElo =
                        celestialWorld.celestialBody.spaceWorld.getCelestialBody("sun");
                if (sunForElo != null) {
                    sunSpacePos = sunForElo.getSmoothPos(partialTick);
                }
            }
            org.joml.Vector3d center = celestialWorld.celestialBody.getSmoothPos(partialTick);
            org.joml.Vector3d up = new org.joml.Vector3d(camX, camY, camZ).sub(center);
            double upLen = up.length();
            if (upLen > 1.0) {
                up.div(upLen);
                // 极轴**不再写死 (0,1,0)**：从映射自己的 surfaceSpinRotate 反推（见
                // CelestialWorld.getSurfacePole 的推导）。今天两种实现恰好都等于 +Y，
                // 但切回物理自转（有真实黄赤交角）时 spin⁻¹·Y ≠ Y，写死就会让"东"算歪。
                org.joml.Vector3d pole = celestialWorld.getSurfacePole(partialTick);
                double latDeg = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, up.dot(pole)))));
                org.joml.Vector3d meridian = new org.joml.Vector3d(pole)
                        .sub(new org.joml.Vector3d(up).mul(up.dot(pole)));
                if (meridian.lengthSquared() > 1e-18) {
                    meridian.normalize();
                }
                String sun = "太阳(不在渲染表里)";
                StringBuilder others = new StringBuilder();
                for (PlanetRenderObject o : bodies) {
                    org.joml.Vector3d d = new org.joml.Vector3d(o.posX(), o.posY(), o.posZ())
                            .sub(camX, camY, camZ);
                    double dl = d.length();
                    if (dl < 1.0) {
                        continue;
                    }
                    d.div(dl);
                    double elev = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, d.dot(up)))));
                    String hour = "n/a";
                    org.joml.Vector3d horiz = new org.joml.Vector3d(d)
                            .sub(new org.joml.Vector3d(up).mul(d.dot(up)));
                    if (horiz.lengthSquared() > 1e-18 && meridian.lengthSquared() > 1e-18) {
                        horiz.normalize();
                        org.joml.Vector3d cr = new org.joml.Vector3d(meridian).cross(horiz);
                        hour = String.format(java.util.Locale.ROOT, "%+.2f°",
                                Math.toDegrees(Math.atan2(cr.dot(up), meridian.dot(horiz))));
                    }
                    // ★ 距角自检（**回答"其他星球的位置对不对"**，且是一条与映射/相机/姿态全都无关的
                    //   **纯几何硬不等式**）：
                    //   三角形 太阳S—观察者O—目标T。设 r_T=|ST|、r_O=|SO|，距角 e = ∠SOT。
                    //   由正弦定理 e 的最大值出现在 OT 与半径 r_T 的圆相切时 ⇒ **sin(e) ≤ r_T / r_O**
                    //   （对任意瞬时位置都严格成立，与离心率无关）。内行星因此被卡得很死：
                    //   金星 ≤ 46°、水星 ≤ 23~28°。外行星 r_T > r_O ⇒ 右端 > 1，判据自动失效（本来就没上限）。
                    //   它只用到三个"到太阳的距离"和一个夹角，**不碰任何映射约定、不碰相机** ——
                    //   所以它一旦 FAIL 就只能是天体位置/轨道数据错，排除了所有坐标约定类的嫌疑。
                    String elo = "";
                    // ⚠️ 必须用 byId：`PlanetRenderObject.planetName()` 返回的是**id**（"sun"/"mercury"…，
                    //    见该方法 javadoc"与 RealAstroData#byId(String) 一致"），不是中文名。
                    //    第一版这里写的是 byName ⇒ 查表恒为 null ⇒ 这条判据**根本不会执行**
                    //    （死判据比没有判据更糟：日志里看着"没有 FAIL"，其实是没跑）。
                    com.mss.polymech.space.RealAstroData selfData =
                            com.mss.polymech.space.RealAstroData.byId(o.planetName());
                    if (selfData != null && elev > 0.0
                            && com.mss.polymech.space.RealAstroData.SUN.equals(
                                    com.mss.polymech.space.RealAstroData.parentOf(selfData))
                            && sunSpacePos != null) {
                        org.joml.Vector3d toBody = new org.joml.Vector3d(o.posX(), o.posY(), o.posZ())
                                .sub(sunSpacePos);
                        org.joml.Vector3d toSelf = new org.joml.Vector3d(camX, camY, camZ).sub(sunSpacePos);
                        double rT = toBody.length();
                        double rO = toSelf.length();
                        if (rT > 1.0 && rO > 1.0) {
                            double elong = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                                    d.dot(new org.joml.Vector3d(sunSpacePos).sub(camX, camY, camZ).normalize())))));
                            double capDeg = rT < rO ? Math.toDegrees(Math.asin(rT / rO)) : 180.0;
                            boolean okElo = Math.sin(Math.toRadians(elong)) <= rT / rO + 0.02;
                            elo = String.format(java.util.Locale.ROOT, " 距角=%.1f°(上限%.1f°%s)",
                                    elong, capDeg, okElo ? "" : " ★超上限");
                        }
                    }
                    String entry = String.format(java.util.Locale.ROOT,
                            "%s: 高度角=%+.2f° 时角=%s%s", o.planetName(), elev, hour, elo);
                    // ⚠️ 同上：比的是 **id**（`planetName()` 返回 id）。旧代码写的是
                    //    `RealAstroData.SUN.name()`（中文"太阳"）⇒ 恒不相等 ⇒ 太阳永远被当成
                    //    "不在渲染表里"，于是每次都走下面的兜底分支（这正是日志里那句的来历）。
                    //    兜底本身是对的（太阳常由另一条着色器路径画），所以没造成错数，但判据是死的。
                    if (com.mss.polymech.space.RealAstroData.SUN.id().equals(o.planetName())) {
                        sun = entry;
                    } else if (others.length() < 460) {
                        others.append(" | ").append(entry);
                    }
                }
                // 太阳常常不在渲染表里（它由另一条着色器路径画）——那样上面那次循环就取不到它，
                // 于是"白天太阳该不该在上面"这个最关键的数反而缺了（2026-09-22 实测就是这样：
                // 日志里只写 太阳(不在渲染表里)）。这里直接从天体表补一次。
                if (sun.startsWith("太阳(") && celestialWorld.celestialBody.spaceWorld != null) {
                    com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody sunBody =
                            celestialWorld.celestialBody.spaceWorld.getCelestialBody("sun");
                    if (sunBody != null) {
                        org.joml.Vector3d d = new org.joml.Vector3d(sunBody.getSmoothPos(partialTick))
                                .sub(camX, camY, camZ);
                        double dl = d.length();
                        if (dl > 1.0) {
                            d.div(dl);
                            double elev = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, d.dot(up)))));
                            // ★★ 方位自检（2026-09-22 用户要求"傻瓜式"）：**东由物理定义**——
                            //    行星自转带动地表运动的方向 = p × up（p = 极轴）。这样判据与映射的手性无关，
                            //    如果天空被镜像，这条会直接判 FAIL。
                            //    方位角 az：0°=正北、90°=正东、180°=正南、270°=正西。
                            String azTxt = "n/a";
                            String azVerdict = "";
                            if (meridian.lengthSquared() > 1e-18) {
                                org.joml.Vector3d east = new org.joml.Vector3d(pole).cross(up);
                                if (east.lengthSquared() > 1e-18) {
                                    east.normalize();
                                    double az = Math.toDegrees(Math.atan2(d.dot(east), d.dot(meridian)));
                                    if (az < 0) az += 360.0;
                                    double decl = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, d.dot(pole)))));
                                    azTxt = String.format(java.util.Locale.ROOT,
                                            "方位=%.1f°(0=北 90=东) 赤纬=%+.2f°", az, decl);
                                    // 判据（只用"时钟+物理东"，不依赖任何映射约定）：
                                    //   上午(日出→正午) 太阳必须在**东半**(az∈(0,180))；下午必须在西半。
                                    //   太阳高度角 <5°（贴地平线）或离正午 <45 分钟（方位病态）时不判。
                                    long t = dayTime % 24000L;
                                    boolean morning = t > 0 && t < 6000;
                                    boolean afternoon = t > 6000 && t < 12000;
                                    if (elev > 5.0 && (morning || afternoon) && Math.abs(t - 6000) > 900) {
                                        boolean eastHalf = Math.sin(Math.toRadians(az)) > 0;
                                        boolean okAz = morning == eastHalf;
                                        azVerdict = okAz ? " ⇒ 方位检查=PASS"
                                                : " ⇒ ★方位检查=FAIL(" + (morning ? "上午却在西半" : "下午却在东半") + ")";
                                    }
                                }
                            }
                            String hour = "n/a";
                            org.joml.Vector3d horiz = new org.joml.Vector3d(d)
                                    .sub(new org.joml.Vector3d(up).mul(d.dot(up)));
                            if (horiz.lengthSquared() > 1e-18 && meridian.lengthSquared() > 1e-18) {
                                horiz.normalize();
                                org.joml.Vector3d cr = new org.joml.Vector3d(meridian).cross(horiz);
                                sunHourDeg = Math.toDegrees(Math.atan2(cr.dot(up), meridian.dot(horiz)));
                                hour = String.format(java.util.Locale.ROOT, "%+.2f°", sunHourDeg);
                            }
                            // 插值跨度：直接用原始两拍的距离（**不能**再用
                            // |getSmoothPos(1) − getSmoothPos(0)| —— 时间戳口径下那两次调用取同一时刻，
                            // 差值恒为 0，会变成"插值已死"的假报警）。
                            sunSpan = sunBody.sampleSpan();
                            // 太阳角直径：解释"同样 20 分钟一天，为什么我们的太阳看着快得多"
                            // （原版太阳在 r=100 处画 30 宽的方片 ⇒ 约 17°，比真实 0.53° 大 30 倍以上）
                            sunAngDiamDeg = Math.toDegrees(2.0 * Math.atan(sunBody.getRadius() / Math.max(1.0, dl)));
                            // ★ 画面自检：太阳按**本帧真正用的** view·proj 投影出来的 NDC。
                            //   为什么必须打这一项：几何判据（映射/罗盘/方位）全 PASS，画面里的
                            //   太阳却仍可能在错的一侧 —— 那就只可能是**渲染帧**错
                            //   （getRotateFromWorldPos / spaceRotation），不是映射错。
                            //   于是"看图猜"变成"看数判"：ndc.x<0 屏幕左、>0 屏幕右；|ndc|>1 视锥外。
                            String ndcTxt = "";
                            {
                                org.joml.Vector3d sp0 = sunBody.getSmoothPos(partialTick);
                                org.joml.Vector4f sp = new org.joml.Vector4f(
                                        (float) (sp0.x - camX), (float) (sp0.y - camY),
                                        (float) (sp0.z - camZ), 1.0f);
                                vp.transform(sp);
                                float ndcX = sp.w != 0.0f ? sp.x / sp.w : Float.NaN;
                                float ndcY = sp.w != 0.0f ? sp.y / sp.w : Float.NaN;
                                ndcTxt = String.format(java.util.Locale.ROOT,
                                        " 屏幕ndc=(%+.3f, %+.3f) w=%+.2e", ndcX, ndcY, sp.w);
                                // ★★ 画面对齐自检（**端到端**，把整条链一次钉死）：
                                //   预测侧：把"相机→太阳"的方向 d 经姿态帧 q 转到 MC 世界轴，
                                //           再点乘相机自己的右/上基向量 ⇒ 应该出现在屏幕的哪一侧。
                                //   实际侧：上面 vp 投影出来的 ndc 符号。
                                //   两侧必须同号。不同号 ⇒ 只可能是渲染合成顺序错
                                //   （view = rotation(cameraRot)·rotation(spaceRotation) 写反/写漏），
                                //   因为 q 本身已由"姿态帧检查"单独验过。
                                //   ⚠️ 靠近屏幕中心（|预测分量| 很小）时符号是噪声，不判。
                                if (sp.w > 0.0f && Math.abs(ndcX) < 100.0f) {
                                    // ⚠️ 相机位置要从 Camera 现取：下面罗盘判据里的 wp 声明在本块**之后**，
                                    //    在这里直接用会是"找不到符号"（本行第一版就是这么编译失败的）。
                                    net.minecraft.client.Camera cam = net.minecraft.client.Minecraft
                                            .getInstance().gameRenderer.getMainCamera();
                                    net.minecraft.world.phys.Vec3 cwp = cam.getPosition();
                                    org.joml.Vector3d dW = celestialWorld.getRotateFromWorldPos(
                                            cwp.x, cwp.y, cwp.z, partialTick)
                                            .transform(new org.joml.Vector3d(d), new org.joml.Vector3d());
                                    org.joml.Vector3f lv = cam.getLeftVector();
                                    org.joml.Vector3f uv = cam.getUpVector();
                                    double expRight = -(dW.x * lv.x + dW.y * lv.y + dW.z * lv.z);
                                    double expUp = dW.x * uv.x + dW.y * uv.y + dW.z * uv.z;
                                    boolean skipX = Math.abs(expRight) < 0.15;
                                    boolean skipY = Math.abs(expUp) < 0.15;
                                    boolean okX = skipX || (Math.signum(ndcX) == Math.signum(expRight));
                                    boolean okY = skipY || (Math.signum(ndcY) == Math.signum(expUp));
                                    ndcTxt += String.format(java.util.Locale.ROOT,
                                            " | 画面对齐: 预测右/上=%+.3f/%+.3f ⇒ %s",
                                            expRight, expUp, (okX && okY) ? "PASS"
                                                    : "★FAIL(渲染把太阳画到了帧预测的另一侧)");
                                }
                            }
                            sun = String.format(java.util.Locale.ROOT,
                                    "太阳: 高度角=%+.2f° 时角=%s %s%s%s（正午应≈+90、午夜≈−90；上午应在东半）",
                                    elev, hour, azTxt, azVerdict, ndcTxt);
                        }
                    }
                }
                // ★★ MC 罗盘对齐自检（用户硬需求：**天空必须与 MC 的东西南北对应**）。
                //    不需要相机：把"世界 +X（MC 正东）"方向上挪 1 格，映射到宇宙系后取**水平分量**，
                //    再与"物理东"（极轴 × 上方向 = 自转带动地表运动的方向）比较。
                //      = +1 ⇒ 世界的东就是"太阳升起的那一侧" ✔（要的对应成立）
                //      = −1 ⇒ 差一次镜像 ✗（就是 2026-09-22 实机里"朝南时太阳在右手边"那个现象）
                //    南北同理（南 = −(东 × 上)）。这条判据只用映射与向量，不受相机朝向影响。
                String compass = "";
                net.minecraft.world.phys.Vec3 wp = net.minecraft.client.Minecraft.getInstance()
                        .gameRenderer.getMainCamera().getPosition();
                org.joml.Vector3d s0 = celestialWorld.getSpacePosFromWorldPos(wp.x, wp.y, wp.z, partialTick);
                org.joml.Vector3d s1 = celestialWorld.getSpacePosFromWorldPos(wp.x + 1.0, wp.y, wp.z, partialTick);
                org.joml.Vector3d s2 = celestialWorld.getSpacePosFromWorldPos(wp.x, wp.y, wp.z + 1.0, partialTick);
                org.joml.Vector3d wEast = new org.joml.Vector3d(s1).sub(s0);
                org.joml.Vector3d wSouth = new org.joml.Vector3d(s2).sub(s0);
                wEast.sub(new org.joml.Vector3d(up).mul(wEast.dot(up)));
                wSouth.sub(new org.joml.Vector3d(up).mul(wSouth.dot(up)));
                if (wEast.lengthSquared() > 1e-18 && wSouth.lengthSquared() > 1e-18) {
                    wEast.normalize();
                    wSouth.normalize();
                    org.joml.Vector3d eastPhys = new org.joml.Vector3d(pole).cross(up);
                    if (eastPhys.lengthSquared() > 1e-18) {
                        eastPhys.normalize();
                        // 判据自身的南北符号订正：在 lon=0 处 east = 极轴×up = −Z， 而 east×up = (−Z)×X = −Y
                        // = **南** ⇒ 直接用 east×up 就是"南"。之前多写了一个 negate()，得到的其实是"北"，
                        // 那一行 `世界南·物理南 = −1` 是**判据的假报警**，不是数据错。
                        org.joml.Vector3d southPhys = new org.joml.Vector3d(eastPhys).cross(up);
                        double dEast = wEast.dot(eastPhys);
                        double dSouth = wSouth.dot(southPhys);
                        boolean okCompass = dEast > 0.99 && dSouth > 0.99;
                        compass = String.format(java.util.Locale.ROOT,
                                " | MC罗盘: 世界东·物理东=%+.4f 世界南·物理南=%+.4f ⇒ 罗盘对齐=%s",
                                dEast, dSouth, okCompass ? "PASS" : "★FAIL(镜像)");
                    }
                }
                // ★★ 渲染姿态帧自检（2026-09-23 新增；用户硬需求：**画面里的天空也必须和 MC 罗盘一一对应**）。
                //    为什么光有"罗盘对齐"不够：上一版只查了**映射**（世界 +X → 物理东），它报 PASS，
                //    而画面里上午的太阳仍在南边 —— 因为渲染用的**站姿四元数**当时是
                //    "把 +Y 转到径向"的最小旋转，绕天顶那一个自由度是空的、由插值约定随便选。
                //    现在 getRotateFromWorldPos 把三根轴一起钉死；这里就把它作用到
                //    物理东/上/南上，看是否落回世界 +X/+Y/+Z（渲染里就是拿这个四元数当 spaceRotation 用）。
                //      三根都对 ⇒ 天空与 MC 的东西南北一一对应 ✔
                //      东跑到 ±Y 之类 ⇒ 就是"太阳跑到南边"的成因，判 FAIL 而不是让人看图猜 ✗
                String frame = "";
                {
                    org.joml.Vector3d eP = new org.joml.Vector3d(pole).cross(up);
                    if (eP.lengthSquared() > 1e-18) {
                        eP.normalize();
                        org.joml.Vector3d sP = new org.joml.Vector3d(eP).cross(up);
                        // 与渲染**同一路径**：渲染里是把 q 当 Quaternionf 用的（见本文件
                        // spaceRotation.set(...) 那几行），连 float 截断一起验。
                        org.joml.Quaterniond qf = celestialWorld.getRotateFromWorldPos(
                                wp.x, wp.y, wp.z, partialTick);
                        org.joml.Quaternionf qRend = new org.joml.Quaternionf(
                                (float) qf.x, (float) qf.y, (float) qf.z, (float) qf.w);
                        org.joml.Vector3f xi = qRend.transform(new org.joml.Vector3f(
                                (float) eP.x, (float) eP.y, (float) eP.z));
                        org.joml.Vector3f yi = qRend.transform(new org.joml.Vector3f(
                                (float) up.x, (float) up.y, (float) up.z));
                        org.joml.Vector3f zi = qRend.transform(new org.joml.Vector3f(
                                (float) sP.x, (float) sP.y, (float) sP.z));
                        boolean okFrame = xi.x > 0.99f && Math.abs(xi.y) < 0.15f && Math.abs(xi.z) < 0.15f
                                && Math.abs(yi.x) < 0.15f && yi.y > 0.99f && Math.abs(yi.z) < 0.15f
                                && Math.abs(zi.x) < 0.15f && Math.abs(zi.y) < 0.15f && zi.z > 0.99f;
                        frame = String.format(java.util.Locale.ROOT,
                                " | 姿态帧: 物理东→世界(%+.2f,%+.2f,%+.2f) 物理上→世界(%+.2f,%+.2f,%+.2f)"
                                        + " 物理南→世界(%+.2f,%+.2f,%+.2f) ⇒ 姿态帧检查=%s",
                                xi.x, xi.y, xi.z, yi.x, yi.y, yi.z, zi.x, zi.y, zi.z,
                                okFrame ? "PASS" : "★FAIL(天空绕天顶转了/被镜像)");
                    }
                }
                // ★★ 时钟 / 顺滑度自检（2026-09-25 用户三问的**实测**回答，全是测出来的、不是推出来的）：
                //   ① 世界时钟 = ΔdayTime/Δ真实秒 ⇒ 一天多少分钟（原版 20 tick/秒 = 1200 秒 = 20 分钟）
                //   ② 天空转速 = Δ太阳时角/Δ真实秒（原版 360°/20分钟 = 0.300 °/秒）——
                //      它和 ① 应当严格对应：转速(°/秒) = 时钟(tick/秒) × 360/24000
                //   ③ 插值跨度 = |pos − old_pos|：**= 0 就是"抽帧"的真身** ——
                //      getSmoothPos 的 lerp 两端被推平 ⇒ 天体只能在网络包到达那一瞬跳一格
                //   ④ 位姿搬运次数/秒：≈ 帧率×天体数 ⇒ 每帧都在推平 old_pos（把插值杀了）；
                //      ≈ 20×天体数 ⇒ 每 tick 才搬一次，插值才能正常工作
                //   ⑤ 太阳角直径：回答"为什么同样速度看着比原版快"（原版太阳约 17°）
                String timing = "";
                {
                    long nowNanos = System.nanoTime();
                    long poseCopy = com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld
                            .getPoseCopyCount();
                    if (diagLastNanos != 0L) {
                        double dtSec = (nowNanos - diagLastNanos) / 1.0e9;
                        if (dtSec > 0.2) {
                            double tickPerSec = (dayTime - diagLastDayTime) / dtSec;
                            String dayMin = tickPerSec > 1.0e-6
                                    ? String.format(java.util.Locale.ROOT, "%.2f", 24000.0 / tickPerSec / 60.0)
                                    : "n/a";
                            // 时角解缠：时角在 ±180° 处回绕，直接相减会出现 ±360 的假跳
                            double skyRate = Double.NaN;
                            if (!Double.isNaN(sunHourDeg)) {
                                if (!Double.isNaN(diagLastHourRaw)) {
                                    double dh = sunHourDeg - diagLastHourRaw;
                                    if (dh > 180.0) {
                                        dh -= 360.0;
                                    } else if (dh < -180.0) {
                                        dh += 360.0;
                                    }
                                    diagHourUnwrapped += dh;
                                }
                                if (!Double.isNaN(diagLastHourUnwrapped)) {
                                    skyRate = (diagHourUnwrapped - diagLastHourUnwrapped) / dtSec;
                                }
                                diagLastHourRaw = sunHourDeg;
                                diagLastHourUnwrapped = diagHourUnwrapped;
                            } else {
                                diagLastHourRaw = Double.NaN;
                                diagLastHourUnwrapped = Double.NaN;
                            }
                            String tickVerdict = Math.abs(tickPerSec - 20.0) < 1.0 ? "（=原版 20 ⇒ 一天 20 分钟 ✔）"
                                    : "★（≠20 ⇒ 一天不是 20 分钟）";
                            timing = String.format(java.util.Locale.ROOT,
                                    " | 时钟: %.2f tick/秒%s 一天=%s 分钟 天空转速=%s°/秒(原版0.300)"
                                            + " | 顺滑: 插值跨度=%.3e m%s 位姿搬运=%.1f 次/秒 帧率=%d fps",
                                    tickPerSec, tickVerdict, dayMin,
                                    Double.isNaN(skyRate) ? "n/a"
                                            : String.format(java.util.Locale.ROOT, "%.3f", skyRate),
                                    sunSpan,
                                    (sunSpan == 0.0 ? " ★=0 ⇒ 插值已死(天体按包跳格=抽帧)"
                                            : " （>0 ⇒ 插值在工作，越大越顺）"),
                                    (poseCopy - diagLastPoseCopy) / dtSec,
                                    net.minecraft.client.Minecraft.getInstance().getFps());
                        }
                    }
                    diagLastNanos = nowNanos;
                    diagLastDayTime = dayTime;
                    diagLastPoseCopy = poseCopy;
                    if (!Double.isNaN(sunAngDiamDeg)) {
                        timing += String.format(java.util.Locale.ROOT,
                                " | 太阳角直径=%.3f°（我们的真实值；原版是半宽 30、画在距离 100 处的方片"
                                        + " ⇒ 2·atan(30/100)≈33°，比真实大 %.0f 倍 —— 同样 20 分钟一天，"
                                        + "原版太阳看着慢就是这个原因）",
                                sunAngDiamDeg, 33.4 / Math.max(1.0e-6, sunAngDiamDeg));
                    }
                }
                chk.append(String.format(java.util.Locale.ROOT,
                        " | 数学自检: dayTime=%d 时钟角=%+.3f° 观察者纬度=%+.4f° 黄道对地平线=%.2f°"
                                + " up=(%.6f, %.6f, %.6f) 相对行星=(%.4e, %.4e, %.4e) | %s%s%s%s%s",
                        dayTime, clockDeg, latDeg, 90.0 - Math.abs(latDeg),
                        up.x, up.y, up.z,
                        camX - center.x, camY - center.y, camZ - center.z, sun, others, compass, frame, timing));
            }
        }
        } catch (Throwable t) {
            // 自检自身出错也不许影响渲染：把异常记进同一行日志，下次一条日志就能看到。
            chk.append(" | ★数学自检异常(已吞，不影响渲染): ").append(t);
        }
        com.mss.polymech.Polymech.LOGGER.info("[Kelvin] [地表天空] 相机(宇宙系)=({}, {}, {}){}{}",
                String.format(java.util.Locale.ROOT, "%.3e", camX),
                String.format(java.util.Locale.ROOT, "%.3e", camY),
                String.format(java.util.Locale.ROOT, "%.3e", camZ), sb, chk);
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
