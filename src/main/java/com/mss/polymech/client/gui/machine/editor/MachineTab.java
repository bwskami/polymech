package com.mss.polymech.client.gui.machine.editor;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.ScrollerMode;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scene;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.SplitView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TabView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TreeList;
import com.lowdragmc.lowdraglib2.gui.util.ITreeNode;

import java.util.ArrayList;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.utils.virtuallevel.TrackedDummyWorld;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.lowdragmc.lowdraglib2.utils.data.BlockPosFace;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.mss.polymech.client.AnimatedOutline;
import com.mss.polymech.client.BoxOutlineRenderer;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.BuildAnimationSuppressible;
import com.mss.polymech.machine.common.MachineRegistry;
import com.mss.polymech.machine.definition.MachineDefinition;
import com.mss.polymech.machine.definition.MachineDefinitionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * 机器结构 3D 编辑器 —— 给<strong>整合包作者</strong>用的图形化机器定义编辑器。
 *
 * <h3>3D 视口用 LDLib2 自带的 {@link Scene}</h3>
 * {@code Scene}（= {@code WorldSceneRenderer} + {@code TrackedDummyWorld}）自带
 * 真实方块渲染、射线拾取、拖拽旋转、滚轮缩放，点格通过
 * {@code setOnSelected(BiConsumer<BlockPos, Direction>)} 回调（已核对 2.2.26 源码）。
 * 不自己写投影 —— 那是重复造轮子，本项目之前犯过、已删。
 *
 * <h3>编辑对象就是机器定义本身，不引入新语法</h3>
 * 形状与语义都来自 {@code data/poly_mech/machine/<id>.json}（经
 * {@code MachineDefinitionManager} 加载、{@code BaseMachineBlock} 查表）：
 * <pre>
 *   "side_offsets": [[0,3,0],[0,3,2],[0,4,2]]          ← 形状（大机器用 fill_regions 区间）
 *   "item_proxies": [{"at":[0,1,-2],"slots":[2],"io":"input"}]   ← 语义
 * </pre>
 * 本界面读进 {@link MachineStructureSnapshot}，3D 里点格编辑，
 * 保存时写回<strong>同一份 JSON</strong>（{@code config} 覆盖立即生效，或写回仓库源码）。
 * 不再生成 Java 源码——那正是"在游戏里写 Java 有风险"的根源。
 * 价值 = 免去"在脑子里算 facing=NORTH 本地坐标"，而不是换掉声明方式。
 * <p>区间仍是区间：在填充区间里抠掉一格会把区间切成最多 6 块，不会退化成逐格列表。</p>
 *
 * <h3>所见即所得：不拿原版方块冒充</h3>
 * <ul>
 *   <li><b>控制器格</b>放<b>真实主方块</b>：它的方块实体由 Geckolib 渲染，所以视口里
 *       显示的就是这台机器<b>原本的样子</b>。</li>
 *   <li><b>侧边/舱室格</b>放<b>真实侧边方块</b>：{@code LargeMachineSideBlock.getRenderShape()}
 *       是 {@code INVISIBLE}，它本身就是全透明方块，视口里就不该被金块/彩玻璃顶替。
 *       透明格靠线框才看得见结构；侧边方块有完整碰撞箱，射线照样点得中。</li>
 * </ul>
 *
 * <h3>舱室与开口面必须看得见（本版重点）</h3>
 * 以前所有非控制器格都是同一种白框，<b>舱室和外壳分不出来</b>，开口面只存在于文字里。
 * 现在按 {@link EditorCellVisuals.Kind} 分色（外壳白 / 物品舱室橙 / 流体舱室蓝 / 两者紫），
 * 并把 {@code faces} 声明的面画成<strong>贴在那一面上的彩色面片 + 朝外箭头</strong>
 * （几何在 {@link FaceQuad}）。六面全通时六面都贴。
 *
 * <h3>给整合包作者的功能</h3>
 * 四个页签：结构（加/删格、区间工具、撤销重做）、舱室（舱室列表、槽位/储罐、IO、
 * 六个朝向面勾选、说明）、显示（图例、开口面开关、只看舱室）、保存（体检 + 保存/复制/重载）。
 * 体检见 {@link EditorValidator}：把"能写但会静默失效"的写法直接写成中文。
 */
final class MachineTab implements EditorTab {

    /** 所属窗口（标签栏、内容区都由它管）。 */
    private final EditorWorkspaceScreen workspace;
    /** 这个标签的内容根元素（窗口切换标签时只换挂载，不重建它）。 */
    private final UIElement root;
    /** 界面只建一次；之后切回来只刷新。 */
    private boolean built;

    /** 大纲树每层缩进的像素宽（cube 正好落在父文件夹名字下方）。 */
    private static final int TREE_INDENT = 16;

    /** 普通填充格的白色半透明填充透明度（只描线框分不出实心结构，所以补体积感）。 */
    private static final float FILL_ALPHA = 0.30f;
    /**
     * 线框方条半宽 —— 照抄全模组约定值 {@code 0.06F}
     * （{@code MachinePreviewRenderer} 蓝图、{@code PipePreviewRenderer} 管道预览、
     * {@code ConveyorPreviewRenderer} 都是 0.06，{@code NodeSelectionRenderer} 是 1/16≈0.0625）。
     * 这是 {@link BoxOutlineRenderer} 的实心方条棱半宽，不是 1px 细线，别自己发明数值。
     */
    private static final float OUTLINE_WIDTH = 0.06f;
    /** 开口面箭头方条半宽（比线框粗一点才显眼）。 */
    private static final float ARROW_WIDTH = 0.07f;
    /** 悬停格预览框颜色（青）。 */
    private static final int COLOR_HOVER = 0xFF40E0FF;
    /** 已选中格高亮框颜色（亮黄），与悬停区分。 */
    private static final int COLOR_SELECTED = 0xFFFFE040;
    /** 悬停/选中框的填充面透明度（跟管道连接预览一致）。 */
    private static final float HOVER_FACE_ALPHA = 0.22f;

    private final MachineStructureSnapshot snapshot;
    /**
     * 当前项目的历史栈 —— <b>按 machineId 取</b>，切项目再切回来历史还在。
     *
     * <p>切项目其实是新开一个 Screen 实例，所以栈不能存在实例字段里
     * （见 {@link EditorHistories}）。</p>
     */
    private UndoStack history() {
        return EditorHistories.of(snapshot.machineId());
    }
    private EditorScene scene;
    private TrackedDummyWorld dummy;

    /** 鼠标当前格的预览框（chase 平滑，照抄管道连接预览的手感）。 */
    private AnimatedOutline hoverOutline;
    /** 已选中格的高亮框。 */
    private AnimatedOutline selectedOutline;

    // ---- 工具（像 Blockbench：先选工具，再在 3D 里点）----
    /** 当前工具，决定"点一下"的语义。 */
    private EditorTool tool = EditorTool.SELECT;
    /** 工具按钮（用于高亮当前工具）。 */
    private final Map<EditorTool, Button> toolButtons = new EnumMap<>(EditorTool.class);
    /** 工具提示行（"现在点下去会发生什么"）。 */
    private Label toolHintLabel;

    // ---- 按面槽位编辑（一个舱室方块的不同面分别代理不同槽位）----
    /** 该面物品槽位输入框。 */
    private TextField faceSlotField;
    /** 该面流体储罐输入框。 */
    private TextField faceTankField;
    /** 提示"当前编辑的是哪一面"。 */
    private Label faceSlotHint;
    /** 列出已配置的各面。 */
    private Label faceSlotListLabel;

    /** 控制器格放的真实主方块状态（显示原本的样子）。 */
    private final BlockState mainState;
    /** 其余格放的真实侧边方块状态（INVISIBLE，全透明）。 */
    private final BlockState sideState;

    // ---- 文字 ----
    private Label summaryLabel;
    private Label detailLabel;
    private Label statusLabel;
    private Label coordLabel;
    private Label regionStateLabel;
    private Label validationLabel;
    /** 体检详情文本（Label 绑定它 —— 体检本身仍只在 refreshValidation() 里跑，不每 tick 重算）。 */
    private String validationText = "";
    private Label legendLabel;

    // ---- 输入 ----
    private TextField indexField;
    private TextField noteField;

    // ---- 朝向面勾选（上/下/北/南/西/东）----
    private final Map<Direction, Toggle> faceToggles = new EnumMap<>(Direction.class);
    /** 程序化同步勾选状态时置 true，避免回调把刚读出来的值再写回去。 */
    private boolean syncingUi = false;

    // ---- 动态列表容器 ----
    private UIElement proxyListBox;
    private UIElement regionListBox;

    /**
     * 大纲树 —— 直接用 LDLib2 的 {@link TreeList}，不自己搓缩进/箭头/多选。
     *
     * <p>官方 agent 指南的组件速查表就把它列为「树形列表」的正解：<b>缩进和箭头显示层级、
     * 单选/多选（Ctrl 与 Shift）、折叠展开都是内建的</b>。之前拿 VirtualScrollerView 把树拍平
     * 再手算缩进，等于把这三件事各写一遍（还得自己维护 Shift 区间、折叠集合、点击命中）。</p>
     */
    private TreeList<OutlineNode> treeList;

    /**
     * 大纲树的一个节点。
     *
     * <p>文件夹节点带 {@code group}，cube 节点带 {@code cube}；两者都带
     * {@link MachineStructureSnapshot.GroupPath}，所以嵌套到第 N 层也能定位。
     * 节点是<b>临时对象</b>：每次重建整棵树，用默认的身份相等即可 ——
     * TreeList 的选择/展开状态在 {@code setRoot} 时本来就会清空，重建后由
     * {@link #applyTreeSelection} 重新灌入。</p>
     */
    private static final class OutlineNode implements ITreeNode<String, Void> {
        private final OutlineNode parent;
        private final List<OutlineNode> children = new ArrayList<>();
        private final int dimension;
        private final MachineStructureSnapshot.GroupPath path;
        private final MachineDefinition.Group group;
        private final MachineDefinition.Cube cube;
        private final String key;

        OutlineNode(OutlineNode parent, int dimension,
                    MachineStructureSnapshot.GroupPath path,
                    MachineDefinition.Group group, MachineDefinition.Cube cube, String key) {
            this.parent = parent;
            this.dimension = dimension;
            this.path = path;
            this.group = group;
            this.cube = cube;
            this.key = key;
        }

        @Override public int getDimension() { return dimension; }
        @Override public String getKey() { return key; }
        @Override public Void getContent() { return null; }
        @Override public OutlineNode getParent() { return parent; }
        @Override public List<OutlineNode> getChildren() { return children; }

        /** 是不是组头行。刻意不让人用 {@code group() != null} 判断 —— cube 行也带所属组。 */
        boolean isGroup() { return cube == null; }
        OutlineNode parent() { return parent; }
        List<OutlineNode> children() { return children; }
        MachineStructureSnapshot.GroupPath path() { return path; }
        MachineDefinition.Group group() { return group; }
        MachineDefinition.Cube cube() { return cube; }
    }

    /**
     * 正在重建树：{@code setRoot} 内部会以"空选择"回调一次 {@code onSelectedChanged}，
     * 那不是用户操作，必须挡掉 —— 否则回调里再 {@code refreshText()} 就会无限递归。
     */
    private boolean rebuildingTree = false;
    /** 编辑器主体容器（扁平化按钮时作为遍历根）。 */
    private UIElement bodyElement;
    /** 大纲里选中的文件夹路径（null = 没选）。 */
    private MachineStructureSnapshot.GroupPath selectedGroup;
    /** 被折叠起来的文件夹路径（Blockbench 的 v / > 就是干这个的）。 */
    private final java.util.Set<MachineStructureSnapshot.GroupPath> collapsedGroups = new java.util.HashSet<>();
    /** 大纲里多选的 cube（Ctrl 切换 / Shift 区间；「+ 组」把这一批一起移交）。 */
    private final java.util.LinkedHashSet<MachineDefinition.Cube> selectedCubes = new java.util.LinkedHashSet<>();
    /** 渲染期异常只弹/记一次，避免刷屏。 */
    private boolean renderFailed = false;
    /** 有没有未保存的改动（关闭前要提示）。 */
    private boolean dirty = false;
    /** 这台机器缺 geo 模型 — 此时不能把真实核心方块塞进虚拟世界（Geckolib 会抛异常崩客户端）。 */
    private boolean coreModelMissing = false;
    /** 文件夹改名输入框。 */
    private TextField groupNameField;
    /**
     * 真正可改名的组行（null = 当前没有选中文件夹行）。
     *
     * <p>必须和 {@link #selectedGroup} 分开：{@code selectedGroup} 是"当前组上下文"
     * （点 cube 时也会被设成它所属的组，供「+ 组」/计数用），而改名只该作用于
     * <b>用户亲手选中的文件夹行</b>。混用一个字段就会出现"选中 cube 却在改组名"。</p>
     */
    private MachineStructureSnapshot.GroupPath renameTarget;
    private Button renameButton;

    /**
     * 按当前选中同步「改名」按钮与输入框。只在选中变化时调，别放进刷新链（否则会清掉正在输入的字）。
     *
     * <p>可改名的对象有两种：<b>文件夹行</b>（改组名）和<b>恰好一个 cube</b>（改 cube 名）。
     * 其余情况（没选 / 多选 cube / 混选）一律禁用。</p>
     */
    private void updateRenameControls() {
        var single = singleCube();
        boolean can = renameTarget != null || single != null;
        if (renameButton != null) renameButton.setActive(can);
        if (groupNameField == null) return;
        if (renameTarget != null) {
            var g = snapshot.groupAt(renameTarget);
            groupNameField.setText(g == null ? "" : g.name());
        } else {
            groupNameField.setText(single == null || single.name() == null ? "" : single.name());
        }
    }

    /** 恰好选中一个 cube 时返回它，否则 null。 */
    @Nullable
    private MachineDefinition.Cube singleCube() {
        return selectedCubes.size() == 1 ? selectedCubes.iterator().next() : null;
    }
    /** 右侧「变换」面板的六个数值框（位置 / 尺寸 × XYZ）。 */
    private TextField posXField, posYField, posZField;
    private TextField sizeXField, sizeYField, sizeZField;
    private UIElement validationBox;

    // ---- 显示开关 ----
    private boolean showFaces = true;
    private boolean onlyProxies = false;

    // ---- 选中 / 区间 ----
    /** 当前选中的本地坐标（null=未选）。 */
    private Vec3i selected;
    /** 上次点中的命中面，"在此方向加一格"用。 */
    private Direction lastFace = Direction.UP;
    /** 区间工具的两个角（点选格子后按"设为起点/终点"记下来）。 */
    private Vec3i regionA;
    private Vec3i regionB;

    private Button undoButton;
    /** 「历史」列表：能退回去的每一步（点一下退到那一步）。 */
    private UIElement historyBox;
    private Button redoButton;

    MachineTab(EditorWorkspaceScreen workspace, String machineId, BaseMachineBlock block) {
        this.workspace = workspace;
        this.root = buildShell();
        this.snapshot = MachineStructureSnapshot.of(machineId, block);
        // 多开：这个项目之前开过就把草稿恢复回来（未保存的改动切回来还在）
        var draft = EditorDrafts.get(machineId);
        if (draft != null) this.snapshot.loadFrom(draft);
        this.mainState = block.defaultBlockState();
        var side = block.getSideBlock();
        this.sideState = side != null ? side.get().defaultBlockState() : this.mainState;
    }

    /**
     * 这个标签的内容骨架。
     *
     * <p><b>这里没有顶栏</b>：标签栏由 {@link EditorWorkspaceScreen} 统一画一次。
     * 每个界面各贴一条标签栏，正是上一版"点加号标签栏就没了"的根因。</p>
     */
    private static UIElement buildShell() {
        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100));
        root.addClass("panel_bg");
        EditorTheme.style(root, EditorTheme.CLS_BG);

        var body = new UIElement();
        body.layout(l -> l.widthPercent(100).heightPercent(100)
                .flexDirection(FlexDirection.ROW).gapAll(6).paddingAll(6));
        body.setId("editor_body");
        root.addChild(body);
        return root;
    }

    // ==================== EditorTab ====================

    @Override
    public String tabId() {
        return snapshot.machineId();
    }

    @Override
    public String tabTitle() {
        return snapshot.machineId();
    }

    @Override
    public UIElement tabView() {
        return root;
    }

    @Override
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 成为当前标签：首次进来建界面，之后只刷新。
     *
     * <p>以前是 {@code init()} —— 因为以前切项目就是换一个 Screen；现在切标签只换挂载。</p>
     */
    @Override
    public void onShown() {
        if (built) {
            refreshText();
            return;
        }
        // 某台机器的定义可能让构建过程抛异常（不该因此把游戏崩掉）。
        try {
            build();
            built = true;
        } catch (Throwable t) {
            renderFailed = true;
            com.mss.polymech.Polymech.LOGGER.error(
                    "[结构编辑器] 打开 {} 失败", snapshot.machineId(), t);
            showFatalDialog(t);
        }
    }

    /** 被切走：存草稿（未保存的改动切回来还在）。 */
    @Override
    public void onHidden() {
        stashDraft();
    }

    // onClosed 不用做事：草稿与历史由窗口（EditorWorkspaceScreen.doClose）统一丢弃。

    /** 把异常原因弹成对话框（照 LDLib2 自己的 Dialog.showNotification 用法）。 */
    private void showFatalDialog(Throwable t) {
        String msg = t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : ": " + t.getMessage());
        try {
            var d = com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog
                    .showNotification("机器异常", msg + "\n\n详见游戏日志", null);
            d.show(workspace.getModularUI());
            // 同一套扁平暗色（Dialog 的框架是原版贴图，必须在 show 之后换）
            EditorTheme.styleDialog(d);
        } catch (Throwable ignored) {
            status("打开失败：" + msg);
        }
    }

    private void build() {
        var body = workspace.getModularUI().getElementById("editor_body");
        if (!(body instanceof UIElement b)) return;
        b.clearAllChildren();
        bodyElement = b;

        // ---- 左：LDLib2 Scene ----
        scene = new EditorScene();
        coreModelMissing = !geoModelExists(snapshot.machineId());
        dummy = rebuildDummy();
        scene.createScene(dummy)
                .setTickWorld(false)
                .setRenderedCore(snapshot.renderPositions())
                // 放的是真实方块（含真实方块实体），DummyWorld 不容忍后台线程访问，
                // 所以缓存编译必须留在主线程——LDLib2 官方给的口子就是 syncCompile。
                .useCacheBuffer(true)
                .syncCompile(true)
                .setClipContext(ClipContext.Block.VISUAL, ClipContext.Fluid.NONE);
        scene.setOnSelected(this::onCellSelected);   // lombok @Setter，非链式
        // Scene 用 afterWorldRender 画 hover/选中框，而且"命中哪一格"(lastHoverPosFace)
        // 就是在它里面算的，点击选择依赖它——所以只能包一层，不能顶掉。
        scene.getRenderer().setAfterWorldRender(r -> {
            // 渲染期异常同样不许崩游戏（每帧都会跑，所以只记一次）
            try {
                scene.renderBlockOverLay(r);
                drawStructureOutline();
                drawProxyFaces();
            } catch (Throwable t) {
                if (!renderFailed) {
                    renderFailed = true;
                    com.mss.polymech.Polymech.LOGGER.error(
                            "[结构编辑器] {} 渲染失败", snapshot.machineId(), t);
                    showFatalDialog(t);
                }
            }
        });
        scene.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        // 必须在 setRenderedCore 之后：DummyWorld 拿 core 当方块过滤器，
        // 过滤器更新前查方块实体会被挡掉返回 null，标记就漏了。
        suppressBuildAnimations();

        // ---- 右：操作面板（四个页签）----
        var right = new UIElement();
        right.layout(l -> l.widthPercent(100).heightPercent(100)
                .flexDirection(FlexDirection.COLUMN).gapAll(3).paddingAll(4));
        right.addClass("panel_bg");
        EditorTheme.style(right, EditorTheme.CLS_PANEL);

        // ---- 左：大纲（结构树）—— 照 Blockbench 把 outliner 放左边常驻 ----
        // 这样"结构长什么样"不用切页签就能一直看到，右侧留给工具与属性。
        var left = new UIElement();
        left.layout(l -> l.widthPercent(100).heightPercent(100).minHeight(0)
                .flexDirection(FlexDirection.COLUMN).gapAll(2).paddingAll(4));
        EditorTheme.style(left, EditorTheme.CLS_PANEL);
        left.addChild(EditorTheme.sectionTitle("大纲"));
        left.addChild(EditorTheme.divider());


        // 大纲 = TreeList（官方组件）：缩进/箭头/折叠/Ctrl+Shift 多选全是内建的。
        // 样式（选中行底色）写在 lss 的 tree-list 里，这里不碰颜色。
        treeList = new TreeList<>();
        treeList.layout(l -> l.widthPercent(100));   // 高度随内容长，滚动交给外面的 ScrollerView
        treeList.setStaticTree(true);                 // 结构只在 rebuildTree 时换，不用每 tick 重查子节点
        treeList.setSupportMultipleSelection(true);   // Ctrl 切换 / Shift 区间（Blockbench 的 outliner 同款）
        treeList.setClickToExpand(false);             // 点行 = 选中；展开用箭头或双击
        treeList.setDoubleClickToExpand(true);
        treeList.setFlattenRoot(true);                // 合成根不占一行
        treeList.setNodeUISupplier(this::createOutlineRow);
        treeList.setOnSelectedChanged(this::onOutlineSelectionChanged);
        // 稳定 id：UI 自动化测试（UIScenario）靠选择器定位，别依赖层级路径
        treeList.setId("me_outline");

        // TreeList 自己不滚动 —— 它的源码注释与指南都是让它嵌在 ScrollerView 里用。
        // 少了这层包装，超过面板高度的行会被直接裁掉（而且不报错）。
        //
        // 注意必须加在 viewContainer 上，不能直接 addChild 到 ScrollerView：
        // ScrollerView 自己的子节点是 verticalContainer + horizontalScroller（ROW 布局），
        // 直接 addChild 会让树变成它们的兄弟 —— 不在滚动内容里（所以没有滑条），
        // 还会被排到 scrollbar 后面挤到面板最底下。
        var treeScroll = vScroller();
        treeScroll.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        treeScroll.viewContainer.addChild(treeList);
        EditorTheme.style(treeScroll, EditorTheme.CLS_SUNKEN);

        // 文件夹改名：点大纲里的文件夹头 → 名字填进来 → 改完点「改名」
        var renameRow = row();
        groupNameField = new TextField();
        groupNameField.layout(l -> l.flex(1).height(15));
        var btnRename = new Button();
        btnRename.setText(Component.literal("改名"));
        btnRename.setOnClick(e -> renameSelectedGroup());
        btnRename.layout(l -> l.width(30).height(15));
        renameButton = btnRename;
        renameRow.addChildren(groupNameField, btnRename);

        // 大纲底部：加 cube / 新建组 + 「选中占用 / 总格数」
        var treeBar = row();
        var btnAddCube = new Button();
        btnAddCube.setId("me_add_cube");
        btnAddCube.setText(Component.literal("+ cube"));
        btnAddCube.setOnClick(e -> addNeighbor());
        btnAddCube.layout(l -> l.flex(1).height(15));
        var btnAddGroup = new Button();
        btnAddGroup.setId("me_add_group");
        btnAddGroup.setText(Component.literal("+ 组"));
        btnAddGroup.setOnClick(e -> newGroupFromSelection());
        btnAddGroup.layout(l -> l.flex(1).height(15));
        var treeCount = new Label();
        treeCount.setId("me_tree_count");
        treeCount.layout(l -> l.width(56).height(15));
        bindText(treeCount, this::treeCountText);
        treeBar.addChildren(btnAddCube, btnAddGroup, treeCount);

        left.addChildren(renameRow, treeScroll, treeBar);

        // ---- 工具栏：放在中间视口**上方**（照 Blockbench，不在侧栏）----
        var toolbar = new UIElement();
        toolbar.layout(l -> l.widthPercent(100).height(20).gapAll(2)
                .flexDirection(FlexDirection.ROW));
        toolButtons.clear();
        for (EditorTool t : EditorTool.values()) {
            var btn = new Button();
            btn.setText(Component.literal(t.label()));
            btn.setOnClick(e -> setTool(t));
            // 工具说明放 tooltip，不再单独占一行（用户要求：别啰嗦、别占地方）
            btn.style(s -> s.tooltips(t.hint()));
            btn.layout(l -> l.flex(1).heightPercent(100));
            toolButtons.put(t, btn);
            toolbar.addChild(btn);
        }

        // 工具栏 + 视口合成中间一栏（工具栏在上，视口占满剩余高度）
        var centerBox = new UIElement();
        centerBox.layout(l -> l.widthPercent(100).heightPercent(100).minHeight(0)
                .flexDirection(FlexDirection.COLUMN).gapAll(2));
        centerBox.addChildren(toolbar, scene);
        toolHintLabel = new Label();
        EditorTheme.style(toolHintLabel, EditorTheme.CLS_DIM);
        toolHintLabel.layout(l -> l.widthPercent(100).height(12));
        bindText(toolHintLabel, this::toolHintText);

        summaryLabel = new Label();
        EditorTheme.style(summaryLabel, EditorTheme.CLS_TITLE);
        EditorTheme.wrapText(summaryLabel);
        // 现在这个 Label 还要装"选中格详情"（最多 3 行），高度给足免得被裁
        summaryLabel.layout(l -> l.widthPercent(100).height(40));
        // 数据绑定：LDLib2 每 tick 拉一次 buildSummaryText()，状态变了自动更新。
        // 这样"顶部概要"不在刷新链路里，新增显示项时不会再漏刷。
        bindText(summaryLabel, this::buildSummaryText);
        detailLabel = new Label();
        EditorTheme.style(detailLabel, EditorTheme.CLS_TITLE);
        EditorTheme.wrapText(detailLabel);
        detailLabel.layout(l -> l.widthPercent(100).minHeight(16));
        statusLabel = new Label();
        // 不设初始文字，Label 会显示 LDLib2 的占位符 "Label!"（用户截图里那几个）
        statusLabel.setId("me_status");
        statusLabel.setText(Component.literal(""));
        EditorTheme.style(statusLabel, EditorTheme.CLS_ACCENT);
        statusLabel.layout(l -> l.widthPercent(100).height(14));

        var tabs = new TabView();
        // minHeight(0) 是必须的，不是装饰：flex 项的默认 min-height:auto 会拒绝缩到
        // 内容高度以下，于是"舱室"这种内容很长的页签会把整个右栏撑高、
        // 把下面的状态栏和"关闭编辑器"顶出屏幕外（用户实测：关闭按钮找不到了）。
        // 归零之后页签容器才能缩到可用高度，多出来的内容由页签内部的 ScrollerView 自己滚动。
        tabs.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        // ===== 通用滚动：一处生效，所有页签（含以后新加的）都受益 =====
        //
        // TabView 构造时给内容容器的**只有** flexGrow(1)，也就是 flex: 1 1 auto ——
        // flex-basis:auto 表示"基准高度取内容高度"，所以内容再长它也只是跟着长高，
        // 永远不会滚动（这就是为什么只有"舱室"这种长页签会顶出格，短页签看起来正常）。
        //
        // 改成 flex(1)（= flex:1 1 0%，基准 0）后，它的高度被钉成"剩余可用空间"，
        // 里面的 ScrollerView 才拿得到确定高度、才会真正出现滚动条。
        // 照抄 LDLib2 自己的做法：UIDebugger 里就有一行
        //   tabView.tabContentContainer.getLayout().flex(1);
        //
        // 切记：不要用 setOverflowVisible(false) 去"挡住"——那只是把内容裁掉，
        // 滚动条也跟着没了（上一版就是这么错的）。
        tabs.tabContentContainer(c -> {
            c.layout(l -> l.flex(1).minHeight(0));
            // 内容区默认是 Sprites.BORDER_THICK_RT1（原版厚边框），换成扁平面板色
        });
        tabs.addTab(new Tab().setText("结构"), structureTab());
        tabs.addTab(new Tab().setText("舱室"), proxyTab());
        tabs.addTab(new Tab().setText("显示"), viewTab());
        tabs.addTab(new Tab().setText("保存"), saveTab());

        var btnClose = new Button();
        btnClose.setText(Component.literal("关闭编辑器"));
        btnClose.setOnClick(e -> workspace.quit());
        // flexShrink(0)：状态栏和关闭按钮永远不许被页签挤扁/挤出屏幕
        btnClose.layout(l -> l.widthPercent(100).height(18).flexShrink(0));
        statusLabel.layout(l -> l.widthPercent(100).height(14).flexShrink(0));

        // 右栏只保留：工具条 / 概要(含选中详情) / 变换 / 页签 / 状态 / 关闭
        // —— 去掉了独立的"工具提示"和"详情"两行（提醒语不占纵向空间，改成按钮 tooltip）
        right.addChildren(summaryLabel, transformSection(), tabs, statusLabel, btnClose);
        // 两栏都可拖动。布局照 Blockbench：左 = 详情/工具，中间 = 视口，右 = 大纲。
        // 嵌套两次：外层 [内层 | 大纲]，内层 [详情 | 视口]。
        var innerSplit = new SplitView.Horizontal();
        innerSplit.setPercentage(26);        // 左侧详情页的宽度
        innerSplit.left(right);
        innerSplit.right(centerBox);
        innerSplit.layout(l -> l.widthPercent(100).heightPercent(100));

        var outerSplit = new SplitView.Horizontal();
        outerSplit.setPercentage(80);        // 大纲放右边，占 20%
        outerSplit.left(innerSplit);
        outerSplit.right(left);
        outerSplit.layout(l -> l.widthPercent(100).heightPercent(100));

        b.addChildren(outerSplit);
        setTool(EditorTool.SELECT);
        refreshText();
    }

    /** 切换工具：更新按钮高亮与状态栏提示（Blockbench 的工具条手感）。 */
    private void setTool(EditorTool t) {
        tool = t;
        applyToolStyles();
    }

    /**
     * 工具按钮的选中态：<b>蓝底 + 黑字</b>（Blockbench 的"编辑模式"就是蓝底黑字）。
     *
     * <p>必须单独一个方法、并在 {@link EditorTheme#flatize} <b>之后</b>调用：
     * flatize 会把所有按钮涂成中性深灰，如果先涂再 flatize 就被覆盖了。</p>
     */
    private void applyToolStyles() {
        for (var e : toolButtons.entrySet()) {
            var btn = e.getValue();
            boolean active = e.getKey() == tool;
            // Active state is a CLASS (styling lives in lss: .me-tool-active).
            // State stays in code, appearance stays in the stylesheet.
            btn.setText(Component.literal(e.getKey().label()));
            if (active) {
                if (!btn.hasClass(EditorTheme.CLS_TOOL_ACTIVE)) {
                    btn.addClass(EditorTheme.CLS_TOOL_ACTIVE);
                }
            } else {
                btn.removeClass(EditorTheme.CLS_TOOL_ACTIVE);
            }
        }
    }
    /**
     * 关掉/离开当前项目前的统一入口：<b>有未保存的改动就先问</b>。
     * <p>没改动就直接执行 after。</p>
     */
    private void requestCloseCurrent(Runnable after) {
        if (!dirty) {
            after.run();
            return;
        }
        try {
            var d = new com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog();
            d.setTitle("有未保存的改动");
            var info = new Label();
            info.setText(Component.literal(snapshot.machineId()
                    + " 还有没保存的改动。关掉的话这些改动会丢（草稿只保留到关掉为止）。"));
            EditorTheme.style(info, EditorTheme.CLS_TITLE);
            EditorTheme.wrapText(info);
            info.layout(l -> l.widthPercent(100).minHeight(16));
            d.addContent(info);

            var r = row();
            var save = new Button();
            save.setText(Component.literal("保存并关闭"));
            save.setOnClick(e -> {
                saveJson(false);
                if (!dirty) after.run();
            });
            save.layout(l -> l.flex(1).height(18));

            var discard = new Button();
            discard.setText(Component.literal("直接关闭"));
            discard.setOnClick(e -> after.run());
            discard.layout(l -> l.flex(1).height(18));

            r.addChildren(save, discard);
            d.addContent(r);

            // 弹窗也是这个界面的一部分：同样走扁平化，不许出现原版按钮
            d.show(workspace.getModularUI());
            // 必须在 show 之后：Dialog 的标题栏/确认按钮是 show() 时才装配出来的
            EditorTheme.styleDialog(d);
        } catch (Throwable t) {
            // 弹窗都起不来就别拦着了（草稿还在）
            com.mss.polymech.Polymech.LOGGER.warn("[结构编辑器] 未保存提示弹不出来，直接放行", t);
            after.run();
        }
    }

    // ==================== 标签生命周期 ====================

    /**
     * 关掉这个标签前先问一句（有未保存改动时）。
     *
     * <p>标签是「丢弃」语义：确认关闭后草稿由窗口删掉，所以这里不再存草稿。</p>
     */
    @Override
    public void requestClose(Runnable onConfirmed) {
        requestCloseCurrent(onConfirmed);
    }

    /** 把当前编辑内容存进草稿（切标签 / 关窗口时都会调）。 */
    private void stashDraft() {
        EditorDrafts.put(snapshot.machineId(), snapshot.toDefinition());
    }

    // ==================== 右侧「变换」面板（照 Blockbench）====================

    /**
     * Blockbench 那块「位置 / 尺寸」数值面板：X/Y/Z 三行分色，每行两个输入框。
     *
     * <p>为什么值得单独做一块：cube 的本质就是 {@code from/to}，
     * 所以"位置" = {@code from}、"尺寸" = {@code to - from + 1}。
     * 用数字直接改比"在 3D 里点点点"精确得多，也是 Blockbench 的核心操作方式。</p>
     */
    private UIElement transformSection() {
        var box = columnBox();
        box.addChild(EditorTheme.sectionTitle("变换（选中 cube）"));
        box.addChild(EditorTheme.divider());

        // 表头：空一格（放轴标）+ 位置 + 尺寸
        var head = row();
        var pad = new Label();
        pad.setText(Component.literal(""));
        pad.layout(l -> l.width(12).height(12));
        var hPos = new Label();
        hPos.setText(Component.literal("位置"));
        EditorTheme.style(hPos, EditorTheme.CLS_DIM);
        hPos.layout(l -> l.flex(1).height(12));
        var hSize = new Label();
        hSize.setText(Component.literal("尺寸"));
        EditorTheme.style(hSize, EditorTheme.CLS_DIM);
        hSize.layout(l -> l.flex(1).height(12));
        head.addChildren(pad, hPos, hSize);
        box.addChild(head);

        String[] names = {"X", "Y", "Z"};
        String[] colors = EditorTheme.CLS_AXIS;
        for (int axis = 0; axis < 3; axis++) {
            var r = row();
            var tag = new Label();
            tag.setText(Component.literal(names[axis]));
            EditorTheme.style(tag, colors[axis]);
            tag.layout(l -> l.width(12).height(15));

            // LDLib2 自带数值限制：非数字/越界直接拒绝输入，不用我们手工 parse + 报错
            var pos = new TextField().setNumbersOnlyInt(-512, 512);
            // minWidth(0)：TextField 有最小宽度，flex(1) 收不下去会把整行撑出面板
            pos.layout(l -> l.flex(1).height(15).minWidth(0));
            var size = new TextField().setNumbersOnlyInt(1, 64);
            size.layout(l -> l.flex(1).height(15).minWidth(0));
            r.addChildren(tag, pos, size);
            box.addChild(r);

            switch (axis) {
                case 0 -> { posXField = pos; sizeXField = size; }
                case 1 -> { posYField = pos; sizeYField = size; }
                default -> { posZField = pos; sizeZField = size; }
            }
        }

        var apply = new Button();
        apply.setText(Component.literal("应用变换"));
        apply.setOnClick(e -> applyTransform());
        apply.layout(l -> l.widthPercent(100).height(18));
        box.addChild(apply);
        return box;
    }

    /** 把选中 cube 的位置/尺寸填进数值框（选中变化时调用）。 */
    private void syncTransformFields() {
        if (posXField == null) return;
        var cube = selected == null ? null : snapshot.cubeAt(selected);
        if (cube == null) {
            for (var f : new TextField[]{posXField, posYField, posZField,
                    sizeXField, sizeYField, sizeZField}) {
                f.setText("");
            }
            return;
        }
        posXField.setText(String.valueOf(cube.from().getX()));
        posYField.setText(String.valueOf(cube.from().getY()));
        posZField.setText(String.valueOf(cube.from().getZ()));
        sizeXField.setText(String.valueOf(cube.maxX() - cube.minX() + 1));
        sizeYField.setText(String.valueOf(cube.maxY() - cube.minY() + 1));
        sizeZField.setText(String.valueOf(cube.maxZ() - cube.minZ() + 1));
    }

    /** 读数值框 → 改 cube。位置是 from，尺寸是 to-from+1（所以最小 1）。 */
    private void applyTransform() {
        if (selected == null) {
            status("先选中一个 cube（大纲或视口里点一个）");
            return;
        }
        var cube = snapshot.cubeAt(selected);
        if (cube == null) {
            status("选中格不属于任何 cube");
            return;
        }
        try {
            int px = Integer.parseInt(posXField.getRawText().trim());
            int py = Integer.parseInt(posYField.getRawText().trim());
            int pz = Integer.parseInt(posZField.getRawText().trim());
            int sx = Math.max(1, Integer.parseInt(sizeXField.getRawText().trim()));
            int sy = Math.max(1, Integer.parseInt(sizeYField.getRawText().trim()));
            int sz = Math.max(1, Integer.parseInt(sizeZField.getRawText().trim()));

            var nf = new Vec3i(px, py, pz);
            // 换算收在 Cube.ofSize 里，好让它被离线断言钉住（尺寸含两端：to = from + 尺寸 - 1）
            var nt = MachineDefinition.Cube.ofSize(nf, sx, sy, sz).to();
            if (nf.equals(cube.from()) && nt.equals(cube.to())) {
                status("位置与尺寸都没变");
                return;
            }
            mutate("改尺寸 " + vecStr(nf), () -> snapshot.updateCube(cube, nf, nt));
            selected = nf;
            syncFieldsFromSelection();
            status("已应用 " + vecStr(nf) + " 尺寸 " + sx + "×" + sy + "×" + sz);
        } catch (NumberFormatException e) {
            status("位置 / 尺寸必须是整数");
        }
    }

    // ==================== 页签 1：结构 ====================


    private ScrollerView structureTab() {
        var sc = tabScroller();

        coordLabel = new Label();
        coordLabel.layout(l -> l.widthPercent(100).height(14));
        // 同样走数据绑定：坐标显示不再依赖"有人记得调 refreshText"
        bindText(coordLabel, this::coordText);

        var btnAddFront = new Button();
        btnAddFront.setText(Component.literal("在命中面相邻处加一格"));
        btnAddFront.setOnClick(e -> addNeighbor());
        btnAddFront.layout(l -> l.widthPercent(100).height(18));

        var btnRemove = new Button();
        btnRemove.setText(Component.literal("删除选中格"));
        btnRemove.setOnClick(e -> removeSelected());
        btnRemove.layout(l -> l.widthPercent(100).height(18));

        undoButton = new Button();
        undoButton.setOnClick(e -> undo());
        undoButton.layout(l -> l.widthPercent(100).height(18));

        redoButton = new Button();
        redoButton.setOnClick(e -> redo());
        redoButton.layout(l -> l.widthPercent(100).height(18));

        var undoRow = row();
        undoRow.addChildren(undoButton, redoButton);
        undoButton.setId("me_undo");
        redoButton.setId("me_redo");
        undoButton.layout(l -> l.flex(1).height(18));
        redoButton.layout(l -> l.flex(1).height(18));

        historyBox = columnBox();
        historyBox.setId("me_history");

        var regionHint = new Label();
        regionHint.setText(Component.literal("—— 大机器用区间，别逐格点 ——\n"
                + "选一格 → 设为起点；再选一格 → 设为终点；然后创建区间。"));
        regionHint.layout(l -> l.widthPercent(100).height(28));

        var btnSetA = new Button();
        btnSetA.setText(Component.literal("把选中格设为起点 A"));
        btnSetA.setOnClick(e -> setRegionCorner(true));
        btnSetA.layout(l -> l.widthPercent(100).height(18));

        var btnSetB = new Button();
        btnSetB.setText(Component.literal("把选中格设为终点 B"));
        btnSetB.setOnClick(e -> setRegionCorner(false));
        btnSetB.layout(l -> l.widthPercent(100).height(18));

        var btnCreateRegion = new Button();
        btnCreateRegion.setText(Component.literal("创建区间 A → B"));
        btnCreateRegion.setOnClick(e -> createRegion());
        btnCreateRegion.layout(l -> l.widthPercent(100).height(18));

        regionStateLabel = new Label();
        regionStateLabel.layout(l -> l.widthPercent(100).height(14));
        bindText(regionStateLabel, this::regionStateText);

        regionListBox = columnBox();

        var clearBtn = new Button();
        clearBtn.setText(Component.literal("清空全部区间"));
        clearBtn.setOnClick(e -> mutate("清空填充区间", snapshot::clearFillRegions));
        clearBtn.layout(l -> l.widthPercent(100).height(18));

        sc.viewContainer.addChildren(coordLabel, btnAddFront, btnRemove, undoRow, historyBox,
                regionHint, btnSetA, btnSetB, btnCreateRegion, regionStateLabel,
                regionListBox, clearBtn);
        return sc;
    }

    /**
     * 重建结构树：文件夹（group）→ cube。
     *
     * <p>口径来自用户：<b>一个方块 = 一个 cube</b>，文件夹对应 Blockbench 的 bone。
     * 树里点一个 cube 就选中那一格 —— 和 3D 视口里点选是同一套选择状态，
     * 所以两个方向看到的永远是同一个格。</p>
     *
     * <p>数据来自 {@code snapshot.groups()}，可编辑：点文件夹头选中它（配合上面的「改名」），
     * 点 cube 选中那一格（与 3D 视口共用同一个 {@code selected}），行尾「x」整个删掉该 cube。</p>
     */

    private void rebuildTree() {
        if (treeList == null || rebuildingTree) return;
        rebuildingTree = true;
        try {
            // setRoot 会清空选择与展开状态，所以顺序是：
            // 记下用户折叠了谁 → 换根 → 按 collapsedGroups 重新展开 → 灌回选中
            syncCollapseFromTree();
            var root = buildOutlineTree();
            treeList.setRoot(root);
            treeList.expandAllNodesIf(root,
                    n -> n.parent() == null || !collapsedGroups.contains(n.path()));
            applyTreeSelection(root);
        } finally {
            rebuildingTree = false;
        }
    }

    /**
     * 按快照的深度优先顺序建出整棵树（合成根 dimension 0，靠 {@code setFlattenRoot} 不显示）。
     *
     * <p>顺序与 Blockbench 的大纲一致：组头在前，紧跟它<b>自己直接挂的</b> cube，再排子组。</p>
     */
    private OutlineNode buildOutlineTree() {
        var root = new OutlineNode(null, 0, MachineStructureSnapshot.GroupPath.ROOT, null, null, "root");
        // 顺序由 MachineOutlineLogic 决定（纯函数，可离线断言）；这里只把"拍平的行"还原成树：
        // 栈顶永远是"当前可能当父节点的那个组"，-depth 变浅就弹栈。
        var stack = new ArrayList<OutlineNode>();
        stack.add(root);
        int cubeSeq = 0;
        for (var row : MachineOutlineLogic.rows(snapshot)) {
            while (stack.size() > 1 && stack.get(stack.size() - 1).getDimension() >= row.depth()) {
                stack.remove(stack.size() - 1);
            }
            var parent = stack.get(stack.size() - 1);
            var node = new OutlineNode(parent, row.depth(), row.path(), row.group(), row.cube(),
                    row.isGroup() ? "g" + row.path() : "c" + row.path() + "#" + (cubeSeq++));
            parent.children.add(node);
            if (row.isGroup()) stack.add(node);
        }
        return root;
    }

    /** 把用户刚在树里点的展开/折叠记进 {@code collapsedGroups}（换根会清空树的展开状态）。 */
    private void syncCollapseFromTree() {
        var root = treeList.getRoot();
        if (root != null) syncCollapseFromTree(root);
    }

    private void syncCollapseFromTree(OutlineNode n) {
        if (n.isGroup() && !n.children.isEmpty()) {
            if (treeList.isNodeExpanded(n)) collapsedGroups.remove(n.path());
            else collapsedGroups.add(n.path());
        }
        for (var c : n.children) syncCollapseFromTree(c);
    }

    /** 把模型里的选中态灌回树（换根会清空选择；notify=false 防递归）。 */
    private void applyTreeSelection(OutlineNode root) {
        var nodes = new ArrayList<OutlineNode>();
        collectSelectedNodes(root, nodes);
        if (!nodes.isEmpty()) treeList.setSelected(nodes, false);
    }

    private void collectSelectedNodes(OutlineNode n, List<OutlineNode> out) {
        // 有 cube 选中时不再点亮文件夹行，否则一个 cube 会同时"高亮它的组"
        boolean pick = n.cube() != null
                ? selectedCubes.contains(n.cube())
                : n.isGroup() && n.path().equals(renameTarget);
        if (pick) out.add(n);
        for (var c : n.children) collectSelectedNodes(c, out);
    }

    /**
     * TreeList 按需回调：造一行的<b>内容</b>。
     * <p>缩进和展开箭头由 TreeList 自己画（{@code createNodeUI} 里就是
     * {@code arrow + nodeUISupplier.apply(node)}），这里只放"这一行是什么"。</p>
     */
    private UIElement createOutlineRow(OutlineNode n) {
        // 不能用 row()：它写死 widthPercent(100)，塞进 TreeList 的行容器里会横向溢出
        var line = new UIElement();
        // 行类型做成 class：自动化测试用 .me-outline-group / .me-outline-cube 计数，
        // 不用去猜 TreeList 内部包装层长什么样
        line.addClass(n.cube() == null ? "me-outline-group" : "me-outline-cube");
        line.layout(l -> l.flex(1).gapAll(2).flexDirection(FlexDirection.ROW));

        if (n.isGroup()) {
            var head = new Label();
            head.setText(Component.literal(MachineOutlineLogic.rowLabel(n.group(), null)));
            head.layout(l -> l.flex(1).height(16));
            EditorTheme.text(head, EditorTheme.TEXT);
            line.addChild(head);
            return line;
        }

        var c = n.cube();
        var btn = new Label();
        btn.setText(Component.literal(MachineOutlineLogic.rowLabel(null, c)));
        btn.style(s -> s.tooltips(vecStr(c.from())
                + (c.isUnit() ? "" : " -> " + vecStr(c.to()))));
        btn.layout(l -> l.flex(1).height(16));
        EditorTheme.text(btn, EditorTheme.TEXT);

        var del = new Button();
        del.setText(Component.literal("x"));
        del.addClass(EditorTheme.CLS_DANGER);
        del.setOnClick(e -> {
            e.stopPropagation();   // 别让这一下顺带把行也选中（行容器的 CLICK 会冒泡）
            mutate("删 cube " + vecStr(c.from()), () -> snapshot.removeCube(c));
            selectedCubes.remove(c);
            status("已删除 cube " + vecStr(c.from()));
        });
        del.layout(l -> l.width(14).height(16));

        line.addChildren(btn, del);
        return line;
    }

    /**
     * 大纲选中项变化。
     *
     * <p><b>Ctrl 切换 / Shift 区间由 TreeList 算好</b>（{@code setSupportMultipleSelection(true)}），
     * 这里只做映射：选中的 cube 全进 {@code selectedCubes}；{@code selected}（3D 高亮与变换面板
     * 用的"主选中"）取<b>最后点的那一个</b>；只选中一个文件夹时它就是 {@code selectedGroup}。</p>
     */
    private void onOutlineSelectionChanged(java.util.Set<OutlineNode> sel) {
        // setRoot 内部会以空选择回调一次，那不是用户操作
        if (rebuildingTree) return;

        // 决策全在纯函数里（M20 可直接断言）：选中 cube 时 renameTarget 一定是 null，
        // 所以「改名」永远不会误伤组名。
        var rows = new ArrayList<MachineOutlineLogic.Row>();
        for (var n : sel) {
            rows.add(new MachineOutlineLogic.Row(n.path(), n.group(), n.cube(), 0));
        }
        var pick = MachineOutlineLogic.resolveSelect(rows, snapshot);

        selectedCubes.clear();
        selectedCubes.addAll(pick.cubes());
        renameTarget = pick.renameTarget();
        selectedGroup = pick.groupContext();

        var cube = pick.primary();
        if (cube != null) {
            selected = cube.from();
            syncFieldsFromSelection();
        }
        updateRenameControls();
        refreshText();

        String msg;
        if (cube != null) {
            msg = selectedCubes.size() > 1
                    ? "已选中 " + selectedCubes.size() + " 个 cube（Ctrl/Shift 继续多选）"
                    : "已选中 " + vecStr(cube.from());
        } else if (renameTarget != null) {
            var g = snapshot.groupAt(renameTarget);
            msg = g == null ? "已选中" : "已选中文件夹「" + g.name() + "」";
        } else {
            msg = "已选中";
        }
        status(msg);
    }

    /** 大纲底部的计数：「选中占用/总格数」，例如 30/163。多选时是这一批的格子数之和。 */
    private String treeCountText() {
        long total = 0;
        for (var g : snapshot.groups()) total += g.volume();
        long sel = 0;
        if (!selectedCubes.isEmpty()) {
            for (var c : selectedCubes) sel += c.volume();
        } else if (selectedGroup != null) {
            var g = snapshot.groupAt(selectedGroup);
            if (g != null) sel = g.volume();
        }
        return sel + "/" + total;
    }

    /**
     * 用当前选中新建一个组（schema 3：组可嵌套）。
     *
     * <h3>新组建在哪一层</h3>
     * <ul>
     *   <li>选了 cube → 建在<b>最浅那个 cube 所在组的同级</b>（父级 = 那个组的父级）；</li>
     *   <li>只选了文件夹 → 建在它<b>里面</b>（子组，这是造嵌套组的入口）；</li>
     *   <li>什么都没选 → 顶层。</li>
     * </ul>
     *
     * <h3>缩进冲突</h3>
     * 选中的 cube 来自比新组更深/更浅的层时，移交会改变它的层级。
     * 这种"结构悄悄被拍平/加深"的事不能无声发生，所以先把会换层的 cube 列出来问一次，
     * 确认才动手（与 Blockbench 里拖动 cube 换层级一样，是显式操作）。
     */
    private void newGroupFromSelection() {
        // 落点/冲突的判定全在纯函数里（可离线断言真行为），这里只负责弹窗或执行
        var plan = MachineOutlineLogic.planNewGroup(snapshot, selectedCubes, selectedGroup);
        if (plan == null) {
            status("选中的 cube 已经不在结构里了");
            return;
        }
        String name = "组 " + (snapshot.countGroups() + 1);
        if (plan.needsConfirm()) {
            confirmIndentConflict(name, plan.parent(), plan.cubes(), plan.conflicts());
        } else {
            applyNewGroup(name, plan.parent(), plan.cubes());
        }
    }

    /** 真正建组并移交（一次 mutate = 一个撤销点）。 */
    private void applyNewGroup(String name, MachineStructureSnapshot.GroupPath parent,
                               List<MachineDefinition.Cube> cubes) {
        // 用一格数组把 mutate 里算出来的新组路径带出来（lambda 不能写外部局部变量）
        var createdRef = new MachineStructureSnapshot.GroupPath[1];
        mutate("新建组 " + name, () -> {
            var created = snapshot.addGroup(parent, name);
            if (created == null) return;
            createdRef[0] = created;
            for (var c : cubes) snapshot.moveCubeToGroup(c, created);
        });
        selectedCubes.clear();
        // 建完就选中新组，接着就能改名/继续往里面加（不然还要再找一遍）
        selectedGroup = createdRef[0] != null ? createdRef[0] : (parent.isRoot() ? null : parent);
        renameTarget = createdRef[0];
        updateRenameControls();
        status("新建「" + name + "」" + (cubes.isEmpty() ? "" : "，" + cubes.size() + " 个 cube 已移交"));
    }

    /**
     * 缩进冲突确认弹窗：列出"移交后会换层"的 cube，确认才动手。
     * <p>弹窗起不来（极端情况）时按"确认"处理并记日志 —— 与未保存提示同一套兜底。</p>
     */
    private void confirmIndentConflict(String name, MachineStructureSnapshot.GroupPath parent,
                                       List<MachineDefinition.Cube> cubes,
                                       List<MachineDefinition.Cube> conflicts) {
        try {
            var d = new com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog();
            d.setTitle("新建组会改变缩进");
            var info = new Label();
            var sb = new StringBuilder();
            sb.append("新组「").append(name).append("」会建在第 ")
                    .append(parent.depth() + 1).append(" 层。下列 cube 会从原来的层级移到这一层：");
            for (var c : conflicts) {
                var p = snapshot.cubePath(c);
                sb.append('\n').append(vecStr(c.from()))
                        .append("（原第 ").append(p == null ? "?" : String.valueOf(p.depth())).append(" 层）");
            }
            info.setText(Component.literal(sb.toString()));
            EditorTheme.style(info, EditorTheme.CLS_TITLE);
            EditorTheme.wrapText(info);
            info.layout(l -> l.widthPercent(100).minHeight(16));
            d.addContent(info);

            var r = row();
            var ok = new Button();
            ok.setText(Component.literal("确认移交"));
            ok.setOnClick(e -> applyNewGroup(name, parent, cubes));
            ok.layout(l -> l.flex(1).height(18));
            var cancel = new Button();
            cancel.setText(Component.literal("取消"));
            cancel.setOnClick(e -> status("已取消新建组"));
            cancel.layout(l -> l.flex(1).height(18));
            r.addChildren(ok, cancel);
            d.addContent(r);

            d.show(workspace.getModularUI());
            EditorTheme.styleDialog(d);
        } catch (Throwable t) {
            com.mss.polymech.Polymech.LOGGER.warn("[结构编辑器] 缩进冲突弹窗弹不出来，直接移交", t);
            applyNewGroup(name, parent, cubes);
        }
    }

    /**
     * 改当前选中对象的名字：<b>文件夹行改组名，单个 cube 改 cube 名</b>（schema 4 起 cube 也有名字）。
     *
     * <p>cube 的名字留空 = 去掉名字，大纲退回显示 "cube"。</p>
     */
    private void renameSelectedGroup() {
        if (renameTarget == null) {
            renameCubeSelected();
            return;
        }
        var g = snapshot.groupAt(renameTarget);
        if (g == null) {
            status("选中的文件夹已不存在");
            return;
        }
        String name = groupNameField == null ? "" : groupNameField.getRawText().trim();
        if (name.isEmpty()) {
            status("名字不能为空");
            return;
        }
        if (name.equals(g.name())) {
            status("名字没变");
            return;
        }
        var path = renameTarget;
        mutate("改组名 " + name, () -> snapshot.renameGroup(path, name));
        renameTarget = path;              // 改名不改变路径，保持选中
        updateRenameControls();
        status("已改名为「" + name + "」");
    }

    /** 给当前选中的那个 cube 改名（空名字 = 去掉名字）。 */
    private void renameCubeSelected() {
        var cube = singleCube();
        if (cube == null) {
            status(selectedCubes.size() > 1 ? "一次只能改一个 cube 的名字" : "先在大纲里点一个文件夹行或一个 cube");
            return;
        }
        String name = groupNameField == null ? "" : groupNameField.getRawText().trim();
        if (name.equals(cube.name() == null ? "" : cube.name())) {
            status("名字没变");
            return;
        }
        var renamedRef = new MachineDefinition.Cube[1];
        mutate("改 cube 名 " + (name.isEmpty() ? "(清除)" : name),
                () -> renamedRef[0] = snapshot.renameCube(cube, name));
        var renamed = renamedRef[0];
        if (renamed == null) {
            status("这个 cube 已经不在结构里了");
            return;
        }
        // Cube 是 record，名字变了就是另一个实例 —— 选中集合必须换成新的，否则大纲选不中
        selectedCubes.clear();
        selectedCubes.add(renamed);
        updateRenameControls();
        status(name.isEmpty() ? "已去掉名字" : "已改名为「" + name + "」");
    }

    // ==================== 页签 2：舱室 ====================

    private ScrollerView proxyTab() {
        var sc = tabScroller();

        var listHint = new Label();
        listHint.setText(Component.literal("舱室"));
        listHint.layout(l -> l.widthPercent(100).height(14));

        proxyListBox = columnBox();

        var setHint = new Label();
        setHint.setText(Component.literal("槽位"));
        setHint.layout(l -> l.widthPercent(100).height(26));

        indexField = new TextField();
        indexField.layout(l -> l.widthPercent(100).height(16));

        var btnItemIn = roleButton("物品·输入", BaseMachineBlock.ProxyIO.INPUT, true);
        var btnItemOut = roleButton("物品·输出", BaseMachineBlock.ProxyIO.OUTPUT, true);
        var btnFluidIn = roleButton("流体·输入", BaseMachineBlock.ProxyIO.INPUT, false);
        var btnFluidOut = roleButton("流体·输出", BaseMachineBlock.ProxyIO.OUTPUT, false);
        var btnCasing = new Button();
        btnCasing.setText(Component.literal("还原为普通外壳"));
        btnCasing.setOnClick(e -> {
            if (selected == null) {
                status("先点选一格");
                return;
            }
            mutate("清除角色 " + vecStr(selected), () -> snapshot.setRole(selected, null, null));
            status("已还原为外壳 " + vecStr(selected));
        });
        btnCasing.layout(l -> l.widthPercent(100).height(18));

        var ioRow = row();
        ioRow.addChildren(btnItemIn, btnItemOut);
        btnItemIn.layout(l -> l.flex(1).height(18));
        btnItemOut.layout(l -> l.flex(1).height(18));
        var ioRow2 = row();
        ioRow2.addChildren(btnFluidIn, btnFluidOut);
        btnFluidIn.layout(l -> l.flex(1).height(18));
        btnFluidOut.layout(l -> l.flex(1).height(18));

        var faceHint = new Label();
        faceHint.setText(Component.literal("开口面"));
        faceHint.layout(l -> l.widthPercent(100).height(24));

        // 6 个朝向面勾选，3 个一行（面板窄，挤不下 6 个一行）
        var faceRows = columnBox();
        UIElement currentRow = row();
        int i = 0;
        for (Direction d : ProxyFaces.ORDER) {
            var t = new Toggle();
            t.setText(ProxyFaces.cn(d));
            t.layout(l -> l.widthPercent(33).height(14));
            t.setOnToggleChanged(on -> {
                if (!syncingUi) applyFaceToggles();
            });
            faceToggles.put(d, t);
            currentRow.addChild(t);
            if (++i % 3 == 0) {
                faceRows.addChild(currentRow);
                currentRow = row();
            }
        }
        if (i % 3 != 0) faceRows.addChild(currentRow);

        var faceBtnRow = row();
        var btnAllFaces = new Button();
        btnAllFaces.setText(Component.literal("六面全通"));
        btnAllFaces.setOnClick(e -> setAllFaces(true));
        btnAllFaces.layout(l -> l.flex(1).height(18));
        var btnNoFaces = new Button();
        btnNoFaces.setText(Component.literal("只留命中面"));
        btnNoFaces.setOnClick(e -> setAllFaces(false));
        btnNoFaces.layout(l -> l.flex(1).height(18));
        faceBtnRow.addChildren(btnAllFaces, btnNoFaces);

        var noteHint = new Label();
        noteHint.setText(Component.literal("说明"));
        noteHint.layout(l -> l.widthPercent(100).height(14));

        noteField = new TextField();
        noteField.layout(l -> l.widthPercent(100).height(16));

        var btnApplyNote = new Button();
        btnApplyNote.setText(Component.literal("保存说明"));
        btnApplyNote.setOnClick(e -> applyNote());
        btnApplyNote.layout(l -> l.widthPercent(100).height(18));

        // ---- 按面槽位：一个舱室方块的不同面可以分别代理不同槽位 ----
        // 当前面 = 在 3D 里最后点的那一面（用「朝向面」工具点九宫格会更新它）
        var faceSlotHint = new Label();
        faceSlotHint.setText(Component.literal("—— 按面槽位（先选舱室格，再用「朝向面」点九宫格选面）——\n"
                + "该面槽位/储罐：如 2 或 0,1；留空 = 该面不暴露"));
        faceSlotHint.layout(l -> l.widthPercent(100).height(32));

        faceSlotField = new TextField();
        faceSlotField.layout(l -> l.widthPercent(100).height(16));

        faceTankField = new TextField();
        faceTankField.layout(l -> l.widthPercent(100).height(16));

        var faceSlotRow = row();
        var btnSetFace = new Button();
        btnSetFace.setText(Component.literal("写入该面"));
        btnSetFace.setOnClick(e -> applyFaceSlots());
        btnSetFace.layout(l -> l.flex(1).height(18));
        var btnClearFace = new Button();
        btnClearFace.setText(Component.literal("清除该面配置"));
        btnClearFace.setOnClick(e -> clearFaceSlots());
        btnClearFace.layout(l -> l.flex(1).height(18));
        faceSlotRow.addChildren(btnSetFace, btnClearFace);

        faceSlotListLabel = new Label();
        faceSlotListLabel.setText(Component.literal("（该格还没有按面配置）"));
        faceSlotListLabel.layout(l -> l.widthPercent(100).height(40));

        sc.viewContainer.addChildren(listHint, proxyListBox, setHint, indexField,
                ioRow, ioRow2, btnCasing, faceHint, faceRows, faceBtnRow,
                noteHint, noteField, btnApplyNote,
                faceSlotHint, faceSlotField, faceTankField, faceSlotRow, faceSlotListLabel);
        return sc;
    }

    // ==================== 页签 3：显示 ====================

    private ScrollerView viewTab() {
        var sc = tabScroller();

        legendLabel = new Label();
        legendLabel.layout(l -> l.widthPercent(100).height(88));
        bindText(legendLabel, EditorCellVisuals::legendText);

        var tglFaces = new Toggle();
        tglFaces.setText("显示开口面");
        tglFaces.setOn(true);
        tglFaces.setOnToggleChanged(on -> {
            showFaces = on;
            status(on ? "已显示开口面" : "已隐藏开口面");
        });
        tglFaces.layout(l -> l.widthPercent(100).height(14));

        var tglOnly = new Toggle();
        tglOnly.setText("只看舱室（隐藏外壳线框）");
        tglOnly.setOn(false);
        tglOnly.setOnToggleChanged(on -> {
            onlyProxies = on;
            status(on ? "只画舱室" : "画全部格子");
        });
        tglOnly.layout(l -> l.widthPercent(100).height(14));

        var help = new Label();
        help.setText(Component.literal("操作：\n"
                + "  · 左键拖拽 = 旋转视角\n"
                + "  · 滚轮 = 缩放\n"
                + "  · 左键点格 = 选中它\n"
                + "  · 选中后到「结构」页可加格/删格，到「舱室」页可设成舱室\n"
                + "  · 一切改动都在「保存」页写回 JSON"));
        help.layout(l -> l.widthPercent(100).height(80));

        sc.viewContainer.addChildren(legendLabel, tglFaces, tglOnly, help);
        return sc;
    }

    // ==================== 页签 4：保存 ====================

    private ScrollerView saveTab() {
        var sc = tabScroller();

        var valHint = new Label();
        valHint.setText(Component.literal("体检（保存前先看这里）："));
        valHint.layout(l -> l.widthPercent(100).height(14));

        validationBox = columnBox();

        validationLabel = new Label();
        validationLabel.layout(l -> l.widthPercent(100).height(60));
        bindText(validationLabel, () -> validationText);

        var btnCheck = new Button();
        btnCheck.setText(Component.literal("重新体检"));
        btnCheck.setOnClick(e -> {
            refreshValidation();
            status("体检完成");
        });
        btnCheck.layout(l -> l.widthPercent(100).height(18));

        var btnSaveConfig = new Button();
        btnSaveConfig.setText(Component.literal("保存并立即生效"));
        btnSaveConfig.setOnClick(e -> saveJson(false));
        btnSaveConfig.layout(l -> l.widthPercent(100).height(20));

        var btnSaveSource = new Button();
        btnSaveSource.setText(Component.literal("写回源码（开发环境）"));
        btnSaveSource.setOnClick(e -> saveJson(true));
        btnSaveSource.layout(l -> l.widthPercent(100).height(20));

        var btnCopyJson = new Button();
        btnCopyJson.setText(Component.literal("复制 JSON 到剪贴板"));
        btnCopyJson.setOnClick(e -> copyJson());
        btnCopyJson.layout(l -> l.widthPercent(100).height(18));

        var btnReload = new Button();
        btnReload.setText(Component.literal("丢弃改动，重新读盘"));
        btnReload.setOnClick(e -> reloadDefinitions());
        btnReload.layout(l -> l.widthPercent(100).height(18));

        var saveHint = new Label();
        saveHint.setText(Component.literal("「保存并立即生效」写 config/poly_mech/machines/，"
                + "覆盖内置默认，进游戏立刻生效；\n「写回源码」写仓库 data 目录，需要重新构建。"));
        saveHint.layout(l -> l.widthPercent(100).height(28));

        sc.viewContainer.addChildren(valHint, validationBox, validationLabel, btnCheck,
                btnSaveConfig, btnSaveSource, btnCopyJson, btnReload, saveHint);
        return sc;
    }

    // ==================== 小工具：布局 ====================

    /**
     * 一个页签的内容容器 —— <b>页签页面的结构只在这里定义一次</b>。
     *
     * <p>每个页签都是一列"占满宽度的行"，所以必须<strong>显式</strong>把
     * {@code viewContainer} 设成 {@code COLUMN}：{@link ScrollerView} 的
     * {@code viewContainer} 只是个普通 {@code UIElement}，构造时<strong>没有设 flex 方向</strong>，
     * 而 taffy 的默认方向是 <b>ROW</b>。不设的话，页里每个 {@code widthPercent(100)} 的子元素
     * 会被<strong>并排</strong>摆开 —— 横向溢出、看起来所有控件全叠在一起。</p>
     *
     * <p>这不是猜测：LDLib2 自己的 {@code TabView} 也是显式设方向的
     * （{@code tabScroller.viewContainer(v -> v.layout(l -> l.flexDirection(ROW)))}），
     * 而且滚动模式同样要显式给（{@code TabView} 给的是 HORIZONTAL）。</p>
     *
     * <p>之所以收在这一个方法里：之前 4 个页签各写一遍 {@code new ScrollerView()}，
     * 结果<strong>4 个都忘了设方向</strong>。结构只留一处，就不可能只改对 3 个。</p>
     */
    private static ScrollerView tabScroller() {
        var sc = vScroller();
        // 页签页要占满页签内容区（父级高度是明确的），所以用百分比高度
        sc.layout(l -> l.widthPercent(100).heightPercent(100).minHeight(0));
        return sc;
    }
    /**
     * 竖排滚动容器的<strong>唯一</strong>构造点（页签页 / 左侧大纲共用）。
     *
     * <p>{@code ScrollerView} 的 {@code viewContainer} 是普通 UIElement，
     * 构造时没设 flex 方向（taffy 默认 ROW），滚动模式也必须显式给 ——
     * 这两件事漏一次就会重演"控件全叠在一起 / 内容被裁掉"。
     * 所以只留这一个出口，不给第二处抄错的机会。</p>
     */
    private static ScrollerView vScroller() {
        return flatScroll(new ScrollerView());
    }

    /**
     * 把 ScrollerView（含 {@code VirtualScrollerView}）的原版外观抹平。
     *
     * <p>抽成一个方法是因为"漏一次就重演原版灰框"—— 大纲迁到虚拟列表时就是漏了这几行，
     * 结果整个面板变回原版灰底。</p>
     */
    private static <T extends ScrollerView> T flatScroll(T sc) {
        // ScrollerView 默认 viewPort 贴图是 Sprites.BORDER（原版九宫格描边）
        sc.viewPort(v -> v.style(s -> s.backgroundTexture(
                com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY)));
        sc.viewContainer(v -> v.layout(l -> l.widthPercent(100)
                .flexDirection(FlexDirection.COLUMN).gapAll(2)));
        sc.scrollerStyle(s -> s.mode(ScrollerMode.BOTH)
                .verticalScrollDisplay(com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay.AUTO)
                .horizontalScrollDisplay(com.lowdragmc.lowdraglib2.gui.ui.data.ScrollDisplay.AUTO));
        EditorTheme.styleScrollbars(sc);
        return sc;
    }

    /** 竖排容器（不写 lambda 的参数类型，避免依赖 LDLib2 内部类型名）。 */
    private static UIElement columnBox() {
        var e = new UIElement();
        e.layout(l -> l.widthPercent(100)
                .flexDirection(FlexDirection.COLUMN).gapAll(2));
        return e;
    }

    private static UIElement row() {
        var r = new UIElement();
        r.layout(l -> l.widthPercent(100).gapAll(2)
                .flexDirection(FlexDirection.ROW));
        return r;
    }

    /**
     * 把一个 Label 绑到"每 tick 拉一次"的文本源（LDLib2 数据绑定）。
     *
     * <p>显示类文字不该进 {@code refreshText()} 那条手工刷新链 —— 那条链每加一个显示项
     * 就得记得多刷一处，漏了就是"数据变了界面不动"。绑定之后只要数据源还在，标签自己会跟上。</p>
     */
    private static void bindText(Label label, java.util.function.Supplier<String> text) {
        label.bindDataSource(com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource.of(
                () -> Component.literal(text.get())));
    }

    private Button roleButton(String text, BaseMachineBlock.ProxyIO io, boolean item) {
        var btn = new Button();
        btn.setText(Component.literal(text));
        btn.setOnClick(e -> {
            if (selected == null) {
                status("先点选一格");
                return;
            }
            int[] nums = parseNums(indexField.getRawText());
            if (nums.length == 0) {
                status("请先填槽位/储罐下标（如 2 或 0,1）");
                return;
            }
            // 勾选框 → JSON 的 faces：六面全通时给 null（导出省略该字段）
            Direction[] faces = ProxyFaces.toArray(currentFaceSet());
            mutate(item ? "设物品角色" : "设流体角色", () -> {
                if (item) {
                    snapshot.setRole(selected, new BaseMachineBlock.ItemProxy(nums, io, faces), null);
                } else {
                    snapshot.setRole(selected, null, new BaseMachineBlock.FluidProxy(nums, io, faces));
                }
            });
            status((item ? "物品" : "流体") + (io == BaseMachineBlock.ProxyIO.INPUT ? "输入" : "输出")
                    + " 已设置：" + vecStr(selected));
        });
        return btn;
    }

    // ==================== 操作 ====================

    /**
     * 所有会改动结构的操作都走这里：先记撤销点，再改，再刷新。
     *
     * @param label 这一步干了什么（历史面板要显示，例如 "加格 (1,2,3)"）
     */
    private void mutate(String label, Runnable change) {
        history().record(label, snapshot.toDefinition());
        change.run();
        dirty = true;
        refreshScene();
    }

    /** 撤销一步；历史面板点某一项时也走它（连点几次就是退几步）。 */
    private void undo() {
        var step = history().undo(snapshot.toDefinition());
        if (step == null) {
            status("没有可撤销的操作");
            return;
        }
        snapshot.loadFrom(step.definition());
        dirty = true;
        refreshScene();
        status("已撤销：" + step.label());
    }

    /** 连撤 n 步（历史列表点击用）。一次只刷新一次，别 n 次。 */
    private void undoSteps(int n) {
        var cur = snapshot.toDefinition();
        MachineDefinition target = null;
        for (int i = 0; i < n; i++) {
            var step = history().undo(cur);
            if (step == null) break;
            target = step.definition();
            cur = target;
        }
        if (target == null) return;
        snapshot.loadFrom(target);
        dirty = true;
        refreshScene();
        status("已回退 " + n + " 步");
    }

    /**
     * 重画「历史」列表：能退回去的每一步一行，最近的在上；点第 i 行 = 连撤 i 步。
     *
     * <p>只显示"撤销 N"的话，作者不知道退到的到底是哪一步 —— 这个列表就是让历史可见。</p>
     */
    private void rebuildHistoryList() {
        if (historyBox == null) return;
        historyBox.clearAllChildren();
        var labels = history().undoLabels();
        if (labels.isEmpty()) {
            var empty = new Label();
            empty.setText(Component.literal("—"));
            empty.layout(l -> l.widthPercent(100).height(14));
            EditorTheme.text(empty, EditorTheme.TEXT_DIM);
            historyBox.addChild(empty);
            return;
        }
        int shown = Math.min(labels.size(), 12);
        for (int i = 0; i < shown; i++) {
            final int steps = i + 1;
            var btn = new Button();
            btn.setText(Component.literal(steps + ". " + labels.get(i)));
            btn.layout(l -> l.widthPercent(100).height(14));
            btn.setOnClick(e -> undoSteps(steps));
            historyBox.addChild(btn);
        }
    }

    private void redo() {
        var step = history().redo(snapshot.toDefinition());
        if (step == null) {
            status("没有可重做的操作");
            return;
        }
        snapshot.loadFrom(step.definition());
        dirty = true;
        refreshScene();
        status("已重做：" + step.label());
    }

    /**
     * 撤销 / 重做的键盘快捷键：<b>Ctrl+Z</b> 撤销、<b>Ctrl+Shift+Z</b> 或 <b>Ctrl+Y</b> 重做。
     *
     * <p>以前只能在「结构」页签里点按钮，作者根本不知道有撤销这回事。</p>
     */
    /** 快捷键：窗口把按键先交给当前标签（Ctrl+Z 之类）。 */
    @Override
    public boolean handleKey(int keyCode, int scanCode, int modifiers) {
        return handleHistoryKey(keyCode, modifiers);
    }

    private boolean handleHistoryKey(int keyCode, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0
                || (modifiers & GLFW.GLFW_MOD_SUPER) != 0;      // mac 的 Cmd
        if (!ctrl) return false;
        // 正在输入框里打字时不抢键：那里 Ctrl+Z 该是文本撤销
        var ui = workspace.getModularUI();
        var focused = ui == null ? null : ui.getFocusedElement();
        if (focused instanceof TextField || focused instanceof TextArea) return false;
        if (keyCode == GLFW.GLFW_KEY_Z) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) redo();
            else undo();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        return false;
    }

    /**
     * 3D 里点了一下 —— <b>按当前工具决定语义</b>（Blockbench 的做法）。
     *
     * <p>以前这里只有"选中"一种行为，其余操作全塞在右侧按钮里（"在命中面相邻处加一格"
     * 这种）。工具化之后，同一支左键在不同工具下分别表示加格/删格/改朝向面/定区间，
     * 作者不用记"哪个按钮对应哪个动作"。</p>
     */
    private void onCellSelected(BlockPos renderPos, Direction face) {
        var cell = snapshot.cellAtRenderPos(renderPos);
        // 加格工具允许点在空格上（它的语义是"点到的这一面外侧加一格"）
        if (cell == null) {
            if (tool == EditorTool.ADD) {
                lastFace = face;
                addNeighborAt(snapshot.toLocal(renderPos).offset(face.getNormal()));
                return;
            }
            status("点在结构外");
            return;
        }
        lastFace = face;
        switch (tool) {
            case SELECT -> {
                selected = cell.local();
                syncCubeSelectionFromCell();
                syncFieldsFromSelection();
                refreshText();
                status("已选中 " + vecStr(selected));
            }
            case ADD -> addNeighborAt(cell.local().offset(face.getNormal()));
            case REMOVE -> {
                selected = cell.local();
                removeSelected();
            }
            case FACE -> applyFaceClick(cell.local(), face);
            case REGION -> {
                selected = cell.local();
                // 交替定 A / B，两次点击就够，不用再去按按钮
                if (regionA == null || (regionA != null && regionB != null)) {
                    regionA = cell.local();
                    regionB = null;
                    status("起点 A = " + vecStr(regionA));
                } else {
                    regionB = cell.local();
                    status("终点 B = " + vecStr(regionB) + "（按「创建区间」生成）");
                }
            }
            case ROLE -> {
                selected = cell.local();
                cycleRole(cell.local());
            }
        }
    }

    /**
     * 朝向面工具：点九宫格把该面设为舱室朝向面；<b>Shift+点</b>则在保留已有面的前提下再加一个面。
     *
     * <p>用户要求：「多个面的情况，就是 shift 再点九宫格，可以在那一格再创建一个面」。</p>
     */
    private void applyFaceClick(Vec3i local, Direction worldFace) {
        if (!snapshot.hasCell(local)) {
            status("这一格不在结构里，先加格");
            return;
        }
        if (local.equals(Vec3i.ZERO)) {
            status("控制器格不能当舱室");
            return;
        }
        // 编辑器里主方块是默认朝向（facing=NORTH），所以本地方向 == 世界方向；
        // 仍走一次反查，免得以后编辑器支持旋转时这里悄悄错位。
        Direction localFace = ProxyFaces.localFace(worldFace, Direction.NORTH);
        boolean add = Minecraft.getInstance().player != null
                && Minecraft.getInstance().player.isShiftKeyDown();
        var cell = snapshot.cellAtRenderPos(snapshot.toRenderPos(local));
        var existing = cell == null ? null : cell.itemProxy();
        var existingFluid = cell == null ? null : cell.fluidProxy();
        if (existing == null && existingFluid == null) {
            status("这一格还不是舱室，请先用「舱室角色」工具把它设成舱室");
            return;
        }
        java.util.Set<Direction> faces = new java.util.LinkedHashSet<>(snapshot.openFaces(local));
        if (add) {
            if (!faces.add(localFace)) {
                status("这一面已经是开口面了：" + ProxyFaces.cn(localFace));
                return;
            }
        } else {
            faces.clear();
            faces.add(localFace);
        }
        Direction[] arr = ProxyFaces.toArray(faces);
        mutate("改开口面 " + vecStr(local), () -> snapshot.setFaces(local, arr));
        selected = local;
        syncFieldsFromSelection();
        refreshText();
        status((add ? "已加开口面 " : "开口面 = ") + ProxyFaces.label(arr));
    }

    /** 舱室角色工具：外壳 → 物品 → 流体 → 物品+流体 循环。 */
    private void cycleRole(Vec3i local) {
        if (local.equals(Vec3i.ZERO)) {
            status("控制器格不能当舱室");
            return;
        }
        var cell = snapshot.cellAtRenderPos(snapshot.toRenderPos(local));
        if (cell == null) return;
        boolean item = cell.itemProxy() != null;
        boolean fluid = cell.fluidProxy() != null;
        if (!item && !fluid) {
            mutate("设为物品舱 " + vecStr(local), () -> snapshot.setRoleKind(local, true, false));
            status("→ 物品舱室（记得在「舱室」页签填槽位）");
        } else if (item && !fluid) {
            mutate("设为流体舱 " + vecStr(local), () -> snapshot.setRoleKind(local, false, true));
            status("→ 流体舱室（记得在「舱室」页签填储罐）");
        } else if (!item && fluid) {
            mutate("设为物品+流体 " + vecStr(local), () -> snapshot.setRoleKind(local, true, true));
            status("→ 物品+流体舱室");
        } else {
            mutate("清除舱室 " + vecStr(local), () -> snapshot.setRoleKind(local, false, false));
            status("→ 恢复成普通外壳格");
        }
        syncFieldsFromSelection();
        refreshText();
    }

    /**
     * 主选中格变化后，把大纲多选收敛到"包含这一格的那个 cube"。
     *
     * <p>视口点选与按钮加格都走它，保证大纲高亮、变换面板、3D 选中三处永远是同一格。</p>
     */
    private void syncCubeSelectionFromCell() {
        selectedCubes.clear();
        var cube = selected == null ? null : snapshot.cubeAt(selected);
        if (cube != null) selectedCubes.add(cube);
        selectedGroup = snapshot.cubePath(cube);
        renameTarget = null;
        updateRenameControls();
    }

    /** 在指定本地坐标加一格（工具与按钮共用）。 */
    private void addNeighborAt(Vec3i target) {
        if (snapshot.hasCell(target)) {
            status("这一格已经在结构里：" + vecStr(target));
            return;
        }
        mutate("加格 " + vecStr(target), () -> snapshot.addCell(target));
        selected = target;
        syncCubeSelectionFromCell();
        syncFieldsFromSelection();
        refreshText();
        status("已加格 " + vecStr(target));
    }

    private void addNeighbor() {
        Vec3i base = selected != null ? selected : Vec3i.ZERO;
        Vec3i target = base.offset(lastFace.getNormal());
        if (snapshot.hasCell(target)) {
            status("相邻格已存在：" + vecStr(target));
            return;
        }
        mutate("加格 " + vecStr(target), () -> snapshot.addCell(target));
        selected = target;
        syncCubeSelectionFromCell();
        syncFieldsFromSelection();
        refreshText();
        status("已加格 " + vecStr(target));
    }

    private void removeSelected() {
        if (selected == null) {
            status("先点选一格");
            return;
        }
        if (selected.equals(Vec3i.ZERO)) {
            status("控制器不能删除");
            return;
        }
        if (!snapshot.hasCell(selected)) {
            status("该格不在结构中");
            return;
        }
        boolean inRegion = snapshot.isInFillRegion(selected);
        Vec3i target = selected;
        mutate("删格 " + vecStr(target), () -> snapshot.removeCell(target));
        selected = null;
        syncCubeSelectionFromCell();
        syncFieldsFromSelection();
        refreshText();
        status((inRegion ? "已从区间抠掉 " : "已删除 ") + vecStr(target));
    }

    private void setRegionCorner(boolean isA) {
        if (selected == null) {
            status("先点选一格");
            return;
        }
        if (isA) regionA = selected;
        else regionB = selected;
        status((isA ? "起点 A = " : "终点 B = ") + vecStr(selected));
    }

    private void createRegion() {
        if (regionA == null || regionB == null) {
            status("先分别选一格设为起点 A 和终点 B");
            return;
        }
        Vec3i a = regionA, bb = regionB;
        mutate("加填充区间", () -> snapshot.addFillRegion(a, bb));
        status("已创建区间 " + vecStr(a) + " → " + vecStr(bb));
    }

    private void setAllFaces(boolean all) {
        if (selected == null) {
            status("先点选一格");
            return;
        }
        var cell = snapshot.cellAt(selected);
        if (cell == null || (cell.itemProxy() == null && cell.fluidProxy() == null)) {
            status("这一格还不是舱室");
            return;
        }
        Set<Direction> target = all ? EnumSet.allOf(Direction.class) : EnumSet.of(lastFace);
        syncingUi = true;
        for (Direction d : ProxyFaces.ORDER) {
            var t = faceToggles.get(d);
            if (t != null) t.setOn(target.contains(d));
        }
        syncingUi = false;
        applyFaceToggles();
    }

    /** 勾选框 → 写回当前选中格的开口面。 */
    private void applyFaceToggles() {
        if (syncingUi || selected == null) return;
        var cell = snapshot.cellAt(selected);
        if (cell == null) return;
        Direction[] faces = ProxyFaces.toArray(currentFaceSet());
        if (cell.itemProxy() == null && cell.fluidProxy() == null) {
            status("这一格还不是舱室：先设成物品/流体舱室");
            return;
        }
        mutate("改舱室的面配置", () -> {
            if (cell.itemProxy() != null) {
                snapshot.setRole(selected, new BaseMachineBlock.ItemProxy(
                        cell.itemProxy().slots(), cell.itemProxy().io(), faces), cell.fluidProxy());
            } else {
                snapshot.setRole(selected, null, new BaseMachineBlock.FluidProxy(
                        cell.fluidProxy().tanks(), cell.fluidProxy().io(), faces));
            }
        });
        status("开口面：" + ProxyFaces.label(faces));
    }

    private void applyNote() {
        if (selected == null) {
            status("先点选一格");
            return;
        }
        String text = noteField.getRawText();
        mutate("改说明 " + vecStr(selected), () -> snapshot.setNote(selected, text));
        status(text == null || text.isBlank() ? "已清除说明" : "已保存说明");
    }

    private Set<Direction> currentFaceSet() {
        Set<Direction> out = EnumSet.noneOf(Direction.class);
        for (var e : faceToggles.entrySet()) {
            if (e.getValue().isOn()) out.add(e.getKey());
        }
        return out;
    }

    // ==================== 刷新 ====================

    /** 结构变化后：同步 DummyWorld 方块、重建渲染核心、刷新文字。 */
    private void refreshScene() {
        dummy.clear();
        for (var cell : snapshot.cells()) {
            dummy.setBlockAndUpdate(snapshot.toRenderPos(cell.local()), stateFor(cell));
        }
        scene.setRenderedCore(snapshot.renderPositions());
        suppressBuildAnimations();
        refreshText();
    }

    /**
     * 让编辑器里的机器模型<strong>不播建造动画</strong>。
     *
     * <h3>为什么需要</h3>
     * 编辑器每次打开都会在 DummyWorld 里新建方块实体，它们的"建造动画已播过"标记是初始值 false，
     * 于是每次打开都要把机器的建造动画重播一遍——可这里只是想看一眼结构长什么样，不是真实放置。
     * 用 {@link BuildAnimationSuppressible} 标记后直接显示机器的正常状态。
     *
     * <p>调用时机必须在 {@code scene.setRenderedCore(...)} 之后：
     * {@code TrackedDummyWorld.getBlockEntity} 会拿 core 当方块过滤器，
     * 过滤器更新前新加的格子会被挡掉、返回 null，标记就漏了。</p>
     */
    private void suppressBuildAnimations() {
        for (var cell : snapshot.cells()) {
            var be = dummy.getBlockEntity(snapshot.toRenderPos(cell.local()));
            if (be instanceof BuildAnimationSuppressible suppressible) {
                suppressible.setBuildAnimationSuppressed(true);
            }
        }
    }

    private TrackedDummyWorld rebuildDummy() {
        // 虚拟世界做成"满亮度"：模型的明暗不再由单一方向决定（原版是按面烘焙：
        // 顶面最亮、底面最暗），整体对比度低，形状依然看得清 —— 用户要的"夜视式"观感。
        var w = new TrackedDummyWorld() {
            @Override
            public int getBrightness(net.minecraft.world.level.LightLayer type, BlockPos pos) {
                return 15;
            }
        };
        for (var cell : snapshot.cells()) {
            w.setBlockAndUpdate(snapshot.toRenderPos(cell.local()), stateFor(cell));
        }
        return w;
    }

    /**
     * 这台机器有没有 geo 模型。
     *
     * <p>没有的话编辑器必须降级：真实核心方块是 Geckolib 的方块实体渲染器，
     * 缺模型时 Geckolib 会抛 {@code Unable to find model}，直接崩客户端
     * （日志里 filling_unit 就是这么崩的）。</p>
     */
    private static boolean geoModelExists(String machineId) {
        try {
            var loc = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    com.mss.polymech.Polymech.MOD_ID, "geo/block/" + machineId + ".geo.json");
            return Minecraft.getInstance().getResourceManager().getResource(loc).isPresent();
        } catch (Exception e) {
            return true;   // 查不了就当有，别误伤本来正常的机器
        }
    }

    /** 坐标行文字（数据绑定的数据源）。 */
    private String coordText() {
        return selected == null ? "" : vecStr(selected);
    }

    /**
     * 顶部概要文字（概要 + 选中格详情）。
     *
     * <p>单独抽出来是因为它走<b>数据绑定</b>（见 {@code build()} 里的
     * {@code bindDataSource}）—— 绑定源只需要一个返回值的方法，
     * 不需要知道界面在哪、什么时候刷新。</p>
     */
    private String buildSummaryText() {
        var sb = new StringBuilder(snapshot.summary());
        if (selected != null) {
            var cell = snapshot.cellAt(selected);
            if (cell != null) {
                sb.append('\n').append(vecStr(cell.local())).append(' ')
                        .append(EditorCellVisuals.kindOf(cell).label());
                String d = cell.describe();
                if (d != null && !d.isBlank()) sb.append('\n').append(d);
            } else {
                sb.append("\n选中格已失效");
            }
        }
        return sb.toString();
    }

    /** 顶部文字 + 动态列表 + 勾选状态。 */
    private void refreshText() {
        if (summaryLabel == null) return;
        // 概要与坐标都走数据绑定，这里不再手工刷它们
        if (undoButton != null) {
            undoButton.setText(Component.literal("撤销 " + history().undoDepth()));
            redoButton.setText(Component.literal("重做 " + history().redoDepth()));
            // 没得撤/没得重做就把按钮变灰，别让人点了才被告知
            undoButton.setActive(history().canUndo());
            redoButton.setActive(history().canRedo());
        }
        rebuildHistoryList();
        rebuildProxyList();
        rebuildRegionList();
        rebuildTree();
        syncTransformFields();
        // 每次刷新都把按钮刷成扁平深色：动态列表会重建按钮，在这里统一收口就不会漏
        EditorTheme.flatize(bodyElement);
        // 工具按钮的"蓝底黑字"选中态必须在 flatize 之后重新上，否则会被涂回中性灰
        applyToolStyles();
        refreshValidation();
    }

    /** 选中格变化时把输入控件同步过去（不要每次刷新都写，否则会打断正在输入的人）。 */
    private void syncFieldsFromSelection() {
        var cell = selected == null ? null : snapshot.cellAt(selected);
        syncingUi = true;
        try {
            if (indexField != null) {
                String nums = "";
                if (cell != null && cell.itemProxy() != null) nums = numsText(cell.itemProxy().slots());
                else if (cell != null && cell.fluidProxy() != null) nums = numsText(cell.fluidProxy().tanks());
                indexField.setText(nums);
            }
            if (noteField != null) {
                String note = selected == null ? null : snapshot.noteAt(selected);
                noteField.setText(note == null ? "" : note);
            }
            Set<Direction> open = cell == null ? EnumSet.noneOf(Direction.class) : snapshot.openFaces(cell.local());
            for (Direction d : ProxyFaces.ORDER) {
                var t = faceToggles.get(d);
                if (t != null) t.setOn(open.contains(d));
            }
            // 按面槽位区：当前面 + 该面的值 + 已配面一览
            Direction face = currentFace();
            if (faceSlotHint != null) {
                String kindCn = cell == null ? "—" : EditorCellVisuals.colorName(EditorCellVisuals.kindOf(cell));
                faceSlotHint.setText(Component.literal("按面槽位（当前面 = " + ProxyFaces.cn(face)
                        + "，用「朝向面」工具点九宫格可换面）\n这一格：" + kindCn
                        + "。留空 = 该面不暴露；「清除该面配置」= 回退方块级默认"));
            }
            if (faceSlotField != null) {
                int[] v = cell == null || cell.itemProxy() == null ? null
                        : cell.itemProxy().faceSlotsFor(face, Direction.NORTH);
                faceSlotField.setText(v == null ? "" : numsText(v));
            }
            if (faceTankField != null) {
                int[] v = cell == null || cell.fluidProxy() == null ? null
                        : cell.fluidProxy().faceTanksFor(face, Direction.NORTH);
                faceTankField.setText(v == null ? "" : numsText(v));
            }
            if (faceSlotListLabel != null) {
                faceSlotListLabel.setText(Component.literal(faceSlotSummary(cell)));
            }
        } finally {
            syncingUi = false;
        }
    }

    /** 列出这一格已配置的按面槽位/储罐（没配过的面不列）。 */
    private String faceSlotSummary(@Nullable MachineStructureSnapshot.Cell cell) {
        if (cell == null) return "（先选中一格舱室）";
        StringBuilder sb = new StringBuilder();
        if (cell.itemProxy() != null && cell.itemProxy().faceSlots() != null) {
            for (var e : cell.itemProxy().faceSlots().entrySet()) {
                sb.append("物品 ").append(ProxyFaces.cn(e.getKey())).append(" = ")
                        .append(numsText(e.getValue())).append('\n');
            }
        }
        if (cell.fluidProxy() != null && cell.fluidProxy().faceTanks() != null) {
            for (var e : cell.fluidProxy().faceTanks().entrySet()) {
                sb.append("流体 ").append(ProxyFaces.cn(e.getKey())).append(" = ")
                        .append(numsText(e.getValue())).append('\n');
            }
        }
        return sb.isEmpty() ? "（还没有任何按面配置，全部走方块级默认）" : sb.toString();
    }

    private void rebuildProxyList() {
        if (proxyListBox == null) return;
        proxyListBox.clearAllChildren();
        var list = snapshot.proxyCells();
        if (list.isEmpty()) {
            var empty = new Label();
            empty.setText(Component.literal("（还没有舱室。选中一格后到上面设成物品/流体舱室）"));
            empty.layout(l -> l.widthPercent(100).height(26));
            proxyListBox.addChild(empty);
            return;
        }
        for (var cell : list) {
            var btn = new Button();
            btn.setText(Component.literal(proxyLine(cell)));
            btn.style(s -> s.tooltips(proxyTip(cell)));
            btn.setOnClick(e -> {
                selected = cell.local();
                syncFieldsFromSelection();
                refreshText();
                status("已选中 " + vecStr(cell.local()));
            });
            btn.layout(l -> l.widthPercent(100).height(16));
            proxyListBox.addChild(btn);
        }
    }

    /**
     * 舱室列表一行 —— 只写「类型 · IO · 槽位」。
     * <p>开口面等细节放 tooltip（用户要求：列表里别堆信息）。</p>
     */
    private String proxyLine(MachineStructureSnapshot.Cell cell) {
        var kind = EditorCellVisuals.kindOf(cell);
        StringBuilder sb = new StringBuilder(kind.label());
        if (cell.itemProxy() != null) {
            sb.append(" · ").append(ioCn(cell.itemProxy().io()))
                    .append(" · 槽").append(numsText(cell.itemProxy().slots()));
        }
        if (cell.fluidProxy() != null) {
            sb.append(" · ").append(ioCn(cell.fluidProxy().io()))
                    .append(" · 罐").append(numsText(cell.fluidProxy().tanks()));
        }
        return sb.toString();
    }

    /** 舱室列表一行的悬停详情（开口面 + 坐标）。 */
    private String proxyTip(MachineStructureSnapshot.Cell cell) {
        return vecStr(cell.local()) + "　面=" + ProxyFaces.label(
                cell.itemProxy() != null ? cell.itemProxy().faces()
                        : (cell.fluidProxy() != null ? cell.fluidProxy().faces() : null));
    }

    /**
     * 文件夹列表（原来的"区间列表"）。
     *
     * <p>schema 2 之后结构只有一种东西：<b>文件夹 + cube</b>。所以这里列出文件夹
     * （名字 / cube 数 / 总格数 / 删除），下面结构树里再看每个 cube。
     * 两处不再是"两套视图"——同一个 groups 的摘要与明细。</p>
     */
    private void rebuildRegionList() {
        if (regionListBox == null) return;
        regionListBox.clearAllChildren();
        var list = snapshot.allGroups();
        if (list.isEmpty()) {
            var empty = new Label();
            empty.setText(Component.literal("（还没有文件夹，全靠在视口里一格一格加）"));
            empty.layout(l -> l.widthPercent(100).height(14));
            regionListBox.addChild(empty);
            return;
        }
        // 递归列出全部组（含子组），按层级缩进 —— 与结构树是同一份数据的两种粒度
        for (var e : list) {
            var path = e.path();
            var g = e.group();
            var line = row();
            line.addChild(EditorTheme.indent(e.depth() - 1, TREE_INDENT));
            var label = new Label();
            label.setText(Component.literal(g.name()
                    + "　" + g.cubes().size() + " cube / " + g.volume() + " 格"
                    + (g.groups().isEmpty() ? "" : " · " + g.groups().size() + " 子组")));
            label.layout(l -> l.flex(1).height(14));
            var del = new Button();
            del.setText(Component.literal("删"));
            del.setOnClick(e2 -> {
                mutate("删组 " + path, () -> snapshot.removeGroup(path));
                status("已删除文件夹「" + g.name() + "」");
            });
            del.layout(l -> l.width(20).height(14));
            line.addChildren(label, del);
            regionListBox.addChild(line);
        }
    }

    /** 起终点显示（走数据绑定，每 tick 拉一次，不进手工刷新链）。 */
    private String regionStateText() {
        return "起点 A=" + (regionA == null ? "未设" : vecStr(regionA))
                + "　终点 B=" + (regionB == null ? "未设" : vecStr(regionB));
    }

    /** 当前工具提示（走数据绑定）。 */
    private String toolHintText() {
        return "工具：" + tool.label() + " —— " + tool.hint();
    }

    private void refreshValidation() {
        if (validationBox == null) return;
        var issues = EditorValidator.check(snapshot);
        validationBox.clearAllChildren();
        int errors = EditorValidator.errorsOf(issues).size();

        var head = new Label();
        head.setText(Component.literal(errors == 0 ? "✔ 无问题" : "✖ " + errors + " 个问题"));
        EditorTheme.style(head, errors == 0 ? EditorTheme.CLS_GOOD : EditorTheme.CLS_BAD);
        head.layout(l -> l.widthPercent(100).height(14));
        validationBox.addChild(head);

        if (validationLabel == null) return;
        if (issues.isEmpty()) {
            validationText = "";
            return;
        }

        // 同一种问题合并成一行（"落在填充区间内 ×12"），只列前 4 类。
        // 原来一条一格列 20 多行，既啰嗦、又会把下面的按钮压住。
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var issue : issues) {
            String s = issue.message();
            String kind = s.contains(")") ? s.substring(s.indexOf(')') + 1).trim() : s;
            if (kind.isEmpty()) kind = s;
            counts.merge(kind, 1, Integer::sum);
        }
        var sb = new StringBuilder();
        int shown = 0;
        for (var e : counts.entrySet()) {
            if (shown++ >= 4) break;
            sb.append(e.getKey());
            if (e.getValue() > 1) sb.append(" ×").append(e.getValue());
            sb.append('\n');
        }
        int more = counts.size() - Math.min(4, counts.size());
        if (more > 0) sb.append("…还有 ").append(more).append(" 类");
        validationText = sb.toString().trim();
    }

    // ==================== 保存 / 重载 ====================

    /**
     * 保存为 JSON。
     *
     * @param toSource true=写回仓库源码 {@code src/main/resources/data/poly_mech/machine/}
     *                 （开发环境；需重新构建才进 classpath，不会立即生效）；
     *                 false=写 {@code config/poly_mech/machines/} 并立即重载生效
     */
    private void saveJson(boolean toSource) {
        var issues = EditorValidator.errorsOf(EditorValidator.check(snapshot));
        if (!issues.isEmpty()) {
            status("有 " + issues.size() + " 个必须先修的问题（见「保存」页体检）");
            return;
        }
        try {
            var file = MachineDefinitionManager.save(snapshot.toDefinition(), toSource);
            dirty = false;
            status((toSource ? "已写入源码（需重新构建）: " : "已保存并立即生效: ") + file.getFileName());
        } catch (Exception ex) {
            status("保存失败：" + ex.getMessage());
            com.mss.polymech.Polymech.LOGGER.error("[机器编辑器] 保存 {} 失败", snapshot.machineId(), ex);
        }
    }

    /** 导出 JSON 文本到日志 + 剪贴板（想手工贴进仓库时用）。 */
    private void copyJson() {
        String json = snapshot.exportJson();
        com.mss.polymech.Polymech.LOGGER.info("[机器编辑器] {} 定义 JSON：\n{}", snapshot.machineId(), json);
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(json);
            status("JSON 已复制到剪贴板 + 日志");
        } catch (Exception ignored) {
            status("复制到剪贴板失败（日志里仍有）");
        }
    }

    /** 丢弃改动，重新读盘上的定义（改完 config JSON 后不用重启）。 */
    private void reloadDefinitions() {
        MachineDefinitionManager.reload();
        var errs = MachineDefinitionManager.errors();
        var block = MachineRegistry.getMachineBlock(snapshot.machineId());
        if (block != null) {
            var def = block.definition();
            snapshot.loadFrom(def == null ? MachineDefinition.EMPTY : def);
            history().clear();
            selected = null;
            syncCubeSelectionFromCell();
            syncFieldsFromSelection();
            refreshScene();
        }
        status(errs.isEmpty()
                ? "已重载 " + MachineDefinitionManager.sources().size() + " 台机器定义"
                : "重载完成，但有 " + errs.size() + " 个定义报错（见日志）");
    }

    private void status(String msg) {
        if (statusLabel != null) statusLabel.setText(Component.literal(msg));
    }

    // ==================== 每格放真实方块（不冒充） ====================

    /**
     * 控制器格 = 真实主方块（Geckolib 渲染它原本的样子）；
     * 其余格 = 真实侧边方块（{@code RenderShape.INVISIBLE}，本身就是全透明方块）。
     */
    private BlockState stateFor(MachineStructureSnapshot.Cell cell) {
        // 缺 geo 模型时，核心方块也用外壳方块占位 —— 不能让 Geckolib 去加载不存在的模型
        if (cell.controller() && coreModelMissing) return sideState;
        return cell.controller() ? mainState : sideState;
    }

    // ==================== 线框 + 填充 + 开口面 ====================

    /**
     * 画格子：<b>半透明实心方块 + 通用线框</b>，按格子类别分色。
     *
     * <h3>为什么外壳格要填半透明白色</h3>
     * 侧边/舱室方块真实是 {@code RenderShape.INVISIBLE}（全透明），只描线框时
     * 一堆线叠在一起<strong>分不出哪里是实心结构</strong>。所以普通填充格补一层
     * 白色半透明方块（{@link BoxOutlineRenderer#addCorner} 传 {@code hw=0.5}
     * 正好是一个整格实心立方体），线框负责轮廓、填充负责体积感。
     *
     * <h3>线框统一走 {@link BoxOutlineRenderer}</h3>
     * 这是全模组标准化的线框类（实心方条棱 + 角点立方体衔接，
     * 任何视角都看不到十字截面开口），蓝图预览（{@code MachinePreviewRenderer}）与
     * 管道连接预览（{@code PipePreviewRenderer} → {@link AnimatedOutline}）用的都是它。
     * 之前编辑器自己拿 {@code DEBUG_LINES} 画 1px 细线是重复造轮子，观感也和别处不一致，已删。
     */
    private void drawStructureOutline() {
        var poseStack = new PoseStack();
        Matrix4f mat = poseStack.last().pose();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 填充格集合：填充与线框两轮都要用（"相邻同种方块之间不画"靠它判定）
        var filled = new java.util.HashSet<Vec3i>();
        if (!onlyProxies) {
            for (var cell : snapshot.cells()) {
                if (EditorCellVisuals.kindOf(cell) == EditorCellVisuals.Kind.STRUCTURE) {
                    filled.add(cell.local());
                }
            }
        }

        // 第一轮：普通填充格的白色半透明实心块。
        // 只提交「外露面」+ 只提交正面（背面剔除），所以一整块实心区域在任何视线方向上
        // 只有一层 alpha —— 这就是 MC 玻璃的做法（去掉相邻面 + 不画背面），
        // 否则相邻格之间的内面也会混合，越叠越白。见 CellFillFaces。
        if (!filled.isEmpty()) {
            // 填充参与深度测试：核心方块该挡住就挡住（"显示原本的样子"），
            // 但不写深度，免得挡住后面要画的线框。
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableCull();
            // —— 解决"cube 的面和模型的面重合时交替闪烁"（z-fighting）——
            // 把填充沿深度往相机方向拉一点点，重合处永远是 cube 赢。
            // 用 polygon offset 而不是关掉深度测试：这样 cube 之间谁前谁后仍然正确，
            // 关掉深度测试会让后面的格盖住前面的格，玻璃感就废了。
            RenderSystem.polygonOffset(-1.0F, -1.0F);
            RenderSystem.enablePolygonOffset();
            var fillBuf = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (Vec3i local : filled) {
                var p = snapshot.toRenderPos(local);
                for (Direction d : CellFillFaces.outerFaces(local, filled)) {
                    for (float[] v : CellFillFaces.outwardCorners(p, d)) {
                        fillBuf.addVertex(mat, v[0], v[1], v[2]).setColor(1f, 1f, 1f, FILL_ALPHA);
                    }
                }
            }
            AnimatedOutline.drawIfNotEmpty(fillBuf);
            RenderSystem.disablePolygonOffset();
            // 线框恢复「穿透可见」：被核心大模型挡住也要能看见结构
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
        }

        // 第二轮：线框（控制器绿 / 外壳白 / 物品舱室橙 / 流体舱室蓝 / 两者紫）。
        // 普通填充格只画「外棱」：相邻同种方块之间那条棱是平的接缝，画出来就是
        // 一格一格的网格线；不画才像一整块（无缝玻璃）。见 CellFillFaces.outerEdges。
        var edgeBuf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (var cell : snapshot.cells()) {
            var kind = EditorCellVisuals.kindOf(cell);
            // "只看舱室"时把外壳线框省掉，避免大机器糊成一片
            if (onlyProxies && !kind.isProxy() && !cell.controller()) continue;
            var rp = snapshot.toRenderPos(cell.local());
            if (kind == EditorCellVisuals.Kind.STRUCTURE && !filled.isEmpty()) {
                float hw = OUTLINE_WIDTH / 2f;   // addBar 收的是半宽（appendBox 内部也 /2）
                for (var e : CellFillFaces.outerEdges(rp, cell.local(), filled)) {
                    BoxOutlineRenderer.addBar(edgeBuf, mat, e.x1(), e.y1(), e.z1(), e.x2(), e.y2(), e.z2(),
                            hw, chan(kind.outlineColor(), 16), chan(kind.outlineColor(), 8),
                            chan(kind.outlineColor(), 0), chan(kind.outlineColor(), 24));
                }
            } else {
                appendCellOutline(edgeBuf, mat, rp, kind.outlineColor(), OUTLINE_WIDTH);
            }
        }
        // 开口面箭头（从面中心朝外），让"朝哪边开"在没贴面片时也看得出来
        if (showFaces) {
            for (var cell : snapshot.proxyCells()) {
                int argb = EditorCellVisuals.kindOf(cell).faceColor();
                var p = snapshot.toRenderPos(cell.local());
                for (Direction d : snapshot.openFaces(cell.local())) {
                    float[] c = FaceQuad.center(p, d);
                    float[] tip = FaceQuad.normalTip(p, d, 0.35f);
                    BoxOutlineRenderer.addBar(edgeBuf, mat, c[0], c[1], c[2], tip[0], tip[1], tip[2],
                            ARROW_WIDTH, chan(argb, 16), chan(argb, 8), chan(argb, 0), 1f);
                }
            }
        }
        AnimatedOutline.drawIfNotEmpty(edgeBuf);

        // 第三轮：鼠标当前格的预览框（chase 平滑，和管道连接预览同一套手感）
        drawHoverPreview(mat);

        // 第四轮：朝向面工具的九宫格（画在悬停的那一面上，扳手同款几何）
        if (tool.usesFaceGrid()) {
            drawFaceGrid(mat);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /**
     * 鼠标当前指向的那一格画一个预览框 —— 就是管道/电线连接预览那套
     * （{@link AnimatedOutline}：chase 指数平滑滑动 + 淡入 + 半透明面 + 亮边线）。
     *
     * <p>这样在点下去之前就知道"我要操作的是哪一格"，不用靠猜。
     * 已经选中的格子用另一种颜色，避免和悬停混淆。</p>
     */
    private void drawHoverPreview(Matrix4f mat) {
        var hovered = scene == null ? null : scene.hoverPosFace();
        var hoverCell = hovered == null ? null : snapshot.cellAtRenderPos(hovered.pos());
        // 悬停格没有、或者它就是已选中的格子时，不额外画（已选中另有高亮）
        if (hoverCell != null && !hoverCell.local().equals(selected)) {
            if (hoverOutline == null) {
                hoverOutline = new AnimatedOutline(boxOf(hovered.pos()), COLOR_HOVER,
                        OUTLINE_WIDTH, HOVER_FACE_ALPHA, tickNow());
            } else {
                hoverOutline.chase(boxOf(hovered.pos()), COLOR_HOVER,
                        OUTLINE_WIDTH, HOVER_FACE_ALPHA);
            }
        }
        // 已选中的格子：常驻高亮框，颜色区别于悬停
        if (selected != null && snapshot.hasCell(selected)) {
            if (selectedOutline == null) {
                selectedOutline = new AnimatedOutline(boxOf(snapshot.toRenderPos(selected)), COLOR_SELECTED,
                        OUTLINE_WIDTH, HOVER_FACE_ALPHA, tickNow());
            } else {
                selectedOutline.chase(boxOf(snapshot.toRenderPos(selected)), COLOR_SELECTED,
                        OUTLINE_WIDTH, HOVER_FACE_ALPHA);
            }
        } else {
            selectedOutline = null;
        }

        long tick = tickNow();
        if (hoverOutline == null && selectedOutline == null) return;

        var faceBuf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (selectedOutline != null) selectedOutline.appendFaces(faceBuf, mat, tick);
        if (hoverOutline != null) hoverOutline.appendFaces(faceBuf, mat, tick);
        AnimatedOutline.drawIfNotEmpty(faceBuf);

        var edgeBuf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        if (selectedOutline != null) selectedOutline.appendEdges(edgeBuf, mat, tick);
        if (hoverOutline != null) hoverOutline.appendEdges(edgeBuf, mat, tick);
        AnimatedOutline.drawIfNotEmpty(edgeBuf);

        if (selectedOutline != null) selectedOutline.tickChase();
        if (hoverOutline != null) hoverOutline.tickChase();
    }

    /**
     * 朝向面工具的<b>九宫格</b> —— 画在鼠标悬停的那一面上，几何与扳手完全一致
     * （{@link FaceGrid}：3×3 分格、1/3 与 2/3 分隔线、面内 (u,v) → 世界坐标）。
     *
     * <p>用法（用户要求）：<b>点九宫格</b>把该面设为舱室朝向面；<b>Shift+点</b>再加一个面，
     * 于是同一个舱室方块可以有多个朝向面、各自代理不同槽位。</p>
     */
    private void drawFaceGrid(Matrix4f mat) {
        var hovered = scene == null ? null : scene.hoverPosFace();
        if (hovered == null) return;
        var cell = snapshot.cellAtRenderPos(hovered.pos());
        if (cell == null) return;

        Direction face = hovered.facing();
        BlockPos p = hovered.pos();
        // 已声明的开口面高亮成该舱室的颜色，未声明的画白色 —— 一眼看出哪面已开
        boolean alreadyOpen = snapshot.openFaces(cell.local()).contains(
                ProxyFaces.localFace(face, Direction.NORTH));
        int argb = alreadyOpen ? EditorCellVisuals.kindOf(cell).faceColor() : 0xFFFFFFFF;
        float r = chan(argb, 16), g = chan(argb, 8), b = chan(argb, 0), a = 0.95f;
        float hw = OUTLINE_WIDTH / 2f;
        float off = 0.004f;   // 贴着面，别和方块表面打架

        var buf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // 两条横线 + 两条竖线（1/3、2/3）
        for (float t : FaceGrid.dividerPositions()) {
            float[] a1 = FaceGrid.point(p, face, 0f, t, off);
            float[] a2 = FaceGrid.point(p, face, 1f, t, off);
            BoxOutlineRenderer.addBar(buf, mat, a1[0], a1[1], a1[2], a2[0], a2[1], a2[2], hw, r, g, b, a);
            float[] b1 = FaceGrid.point(p, face, t, 0f, off);
            float[] b2 = FaceGrid.point(p, face, t, 1f, off);
            BoxOutlineRenderer.addBar(buf, mat, b1[0], b1[1], b1[2], b2[0], b2[1], b2[2], hw, r, g, b, a);
        }
        // 外框（向内缩半个线宽，避免膨胀到面外）
        float e = hw / 1f;
        float[][] frame = {
                {e, e, 1f - e, e}, {1f - e, e, 1f - e, 1f - e},
                {1f - e, 1f - e, e, 1f - e}, {e, 1f - e, e, e}};
        for (float[] f : frame) {
            float[] q1 = FaceGrid.point(p, face, f[0], f[1], off);
            float[] q2 = FaceGrid.point(p, face, f[2], f[3], off);
            BoxOutlineRenderer.addBar(buf, mat, q1[0], q1[1], q1[2], q2[0], q2[1], q2[2], hw, r, g, b, a);
        }
        AnimatedOutline.drawIfNotEmpty(buf);
    }

    /**
     * 把输入框里的下标写到<b>当前面</b>的按面配置上（{@code face_slots}/{@code face_tanks}）。
     *
     * <p>用户要求：「代理的槽位不要按照方块来，而是按照面来，这样就能一个舱室的一个方块的
     * 两个或多个面分别代理不同的槽位了」。</p>
     *
     * <p>语义区分（这是本功能的关键，别混）：</p>
     * <ul>
     *   <li><b>留空</b> = 这一面<strong>什么都不暴露</strong>（写空数组）；</li>
     *   <li><b>没配过</b> = 回退到方块级 {@code slots} 默认值（用「清除该面配置」回到这个状态）。</li>
     * </ul>
     */
    private void applyFaceSlots() {
        if (selected == null) {
            status("先点选一格舱室");
            return;
        }
        var cell = snapshot.cellAt(selected);
        if (cell == null || (cell.itemProxy() == null && cell.fluidProxy() == null)) {
            status("这一格不是舱室");
            return;
        }
        Direction face = currentFace();
        boolean any = false;
        if (cell.itemProxy() != null && faceSlotField != null) {
            int[] slots = parseNums(faceSlotField.getRawText());
            mutate("改物品槽 " + vecStr(selected), () -> snapshot.setItemFaceSlots(selected, face, slots));
            any = true;
        }
        if (cell.fluidProxy() != null && faceTankField != null) {
            int[] tanks = parseNums(faceTankField.getRawText());
            mutate("改流体罐 " + vecStr(selected), () -> snapshot.setFluidFaceTanks(selected, face, tanks));
            any = true;
        }
        if (!any) {
            status("这一格没有可配的舱室类型");
            return;
        }
        syncFieldsFromSelection();
        status("已写入 " + ProxyFaces.cn(face) + " 面的按面配置");
    }

    /** 清掉当前面的按面配置 → 该面回退到方块级默认槽位。 */
    private void clearFaceSlots() {
        if (selected == null) {
            status("先点选一格舱室");
            return;
        }
        Direction face = currentFace();
        Vec3i sel = selected;
        mutate("清除该面的槽位", () -> {
            snapshot.setItemFaceSlots(sel, face, null);
            snapshot.setFluidFaceTanks(sel, face, null);
        });
        syncFieldsFromSelection();
        status("已清除 " + ProxyFaces.cn(face) + " 面的按面配置（回退到方块级默认）");
    }

    /** 当前编辑哪一面 = 3D 里最后点的那一面（「朝向面」工具点九宫格会更新它）。 */
    private Direction currentFace() {
        return ProxyFaces.localFace(lastFace, Direction.NORTH);
    }

    /** 一格的整格包围盒（微膨胀防 Z-fighting，与管道预览一致）。 */
    private static net.minecraft.world.phys.AABB boxOf(BlockPos pos) {
        return new net.minecraft.world.phys.AABB(pos).inflate(0.002);
    }

    private static long tickNow() {
        var level = Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    /** 用通用线框类画一个整格方框（12 条实心方条棱 + 8 个角点）。 */
    private static void appendCellOutline(VertexConsumer buf, Matrix4f mat, BlockPos p,
                                          int argb, float hw) {
        BoxOutlineRenderer.appendBox(buf, mat,
                p.getX(), p.getY(), p.getZ(), p.getX() + 1f, p.getY() + 1f, p.getZ() + 1f,
                hw, chan(argb, 16), chan(argb, 8), chan(argb, 0), chan(argb, 24));
    }

    private static float chan(int argb, int shift) {
        return ((argb >> shift) & 0xFF) / 255f;
    }

    /**
     * 把每个舱室的开口面画成<strong>贴在那一面上的半透明彩色面片</strong>。
     * <p>这是"舱室朝向面显示"的核心：光看线框分不出北面和南面，贴上色片就一目了然。
     * 六面全通时六面都贴（与能力层判据一致）。</p>
     */
    private void drawProxyFaces() {
        if (!showFaces) return;
        var poseStack = new PoseStack();
        Matrix4f mat = poseStack.last().pose();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();   // 面片朝向随意，别让背面被剔掉
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var buf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (var cell : snapshot.proxyCells()) {
            int argb = EditorCellVisuals.kindOf(cell).faceColor();
            float a = 0.45f;
            float r = ((argb >> 16) & 0xFF) / 255f;
            float g = ((argb >> 8) & 0xFF) / 255f;
            float b = (argb & 0xFF) / 255f;
            var p = snapshot.toRenderPos(cell.local());
            for (Direction d : snapshot.openFaces(cell.local())) {
                for (float[] v : FaceQuad.corners(p, d, 0.15f)) {
                    buf.addVertex(mat, v[0], v[1], v[2]).setColor(r, g, b, a);
                }
            }
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    // ==================== 文字工具 ====================

    private static String vecStr(Vec3i v) {
        return "(" + v.getX() + ", " + v.getY() + ", " + v.getZ() + ")";
    }

    private static String ioCn(BaseMachineBlock.ProxyIO io) {
        return io == BaseMachineBlock.ProxyIO.INPUT ? "输入" : "输出";
    }

    private static String numsText(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        return sb.append("]").toString();
    }

    private static int regionVolume(MachineStructureSnapshot.Region r) {
        return (Math.abs(r.to().getX() - r.from().getX()) + 1)
                * (Math.abs(r.to().getY() - r.from().getY()) + 1)
                * (Math.abs(r.to().getZ() - r.from().getZ()) + 1);
    }

    private static int[] parseNums(String s) {
        if (s == null || s.isBlank()) return new int[0];
        var out = new java.util.ArrayList<Integer>();
        for (String p : s.split("[,，\\s]+")) {
            try {
                out.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] res = new int[out.size()];
        for (int i = 0; i < res.length; i++) res[i] = out.get(i);
        return res;
    }

    // ==================== Scene 子类：暴露悬停格 ====================

    /**
     * 继承 LDLib2 的 {@link Scene}，<strong>只为了把 {@code protected} 的
     * {@code lastHoverPosFace} 暴露出来</strong>。
     *
     * <p>鼠标当前格的预览框需要知道"现在指着哪一格"，而 2.2.26 的 {@code Scene}
     * 把这个字段声明为 {@code protected} 且<strong>没有 public getter</strong>
     * （已核对源码：只有 {@code setOnSelected} 回调，那要等点击之后才触发）。
     * 所以用最小侵入的继承把它读出来，而不是去反射或重写射线拾取
     * ——后者会和 {@code Scene} 自己的 hover 计算打架。</p>
     */
    private static final class EditorScene extends Scene {
        /** 当前鼠标指向的方块与面；没指向任何方块时为 null。 */
        @Nullable
        BlockPosFace hoverPosFace() {
            return lastHoverPosFace;
        }
    }
}
