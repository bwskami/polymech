package com.mss.polymech.client.gui.machine.editor;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Tab;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;

/**
 * 编辑器主题 —— Blockbench 风格的<strong>暗色扁平</strong>配色。
 *
 * <p>照抄 Blockbench 的观感要点：深灰面板、1px 深色分隔、几乎无圆角、
 * 行高很紧、选中用蓝色、坐标轴用红绿蓝。</p>
 *
 * <p><b>为什么收在一个类里：</b>颜色和"面板长什么样"如果散落在各处，
 * 改一次配色要翻几十个调用点，而且必然漏掉几个（本项目已经因为"同一个结构抄 4 遍"
 * 吃过亏）。所以这里只定义<b>一次</b>，所有面板/标题/行都从这里取。</p>
 *
 * <h3>【硬性约定】编辑器家族的界面只能用这里的样式</h3>
 *
 * <p>凡是 {@code client.gui.machine.editor} 包下的界面（{@code MachineEditorScreen}、
 * {@code MachinePickerScreen}、以及以后新增的任何界面、弹窗），
 * <b>在显示之前必须调用 {@link #flatize(UIElement)}</b>，把整棵 UI 树里的
 * Button / TextField / Tab / Toggle 全部换成这里的扁平样式。</p>
 *
 * <p><b>为什么写成硬性约定：</b>LDLib2 的控件默认用的是原版贴图（凸起按钮
 * {@code Sprites.RECT_RD_DARK}、圆角输入框 {@code RECT_RD_SOLID}、原版页签
 * {@code TAB_DARK}、原版勾选框 {@code RECT_DARK}）。只要漏掉一次 flatize，
 * 界面上就会冒出一堆原版风格组件，和整体暗色扁平风完全不搭 ——
 * 这个错已经犯过两次（顶栏按钮、弹窗按钮）。</p>
 *
 * <p>对应离线断言在 {@code verifyMachineEditorModel}（M12）：
 * 每个编辑器家族界面都必须有 {@code EditorTheme.flatize(...)} 调用，漏了直接 FAIL。</p>
 */
public final class EditorTheme {

    /**
     * 编辑器家族的样式表（{@code assets/poly_mech/lss/machine_editor.lss}）。
     *
     * <p><b>样式写在 lss 里，不写在 Java 里。</b>界面代码只挂语义 class
     * （{@link #PANEL} / {@link #TITLE} 这些常量），改配色只改那一个文件 ——
     * 这样样式与结构分离，接手的人不用读一遍 Java 才能知道界面长什么样。</p>
     */
    public static final net.minecraft.resources.ResourceLocation STYLESHEET =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    com.mss.polymech.Polymech.MOD_ID, "lss/machine_editor.lss");

    // ---- 语义 class（lss 里同名定义；改这里要同步改 lss）----
    // 命名用 CLS_ 前缀，和上面那些"给 3D 渲染用的颜色值"区分开：
    // 界面样式走 class，代码里只有坐标轴颜色等确实需要 int 的地方才用常量。
    public static final String CLS_PANEL = "me-panel";
    public static final String CLS_PANEL_ALT = "me-panel-alt";
    public static final String CLS_SUNKEN = "me-sunken";
    public static final String CLS_DIVIDER = "me-divider";
    public static final String CLS_TITLE = "me-title";
    public static final String CLS_DIM = "me-dim";
    public static final String CLS_ACCENT = "me-accent";
    public static final String CLS_GOOD = "me-good";
    public static final String CLS_BAD = "me-bad";
    public static final String CLS_WRAP = "me-wrap";
    /** 当前工具的选中态（蓝底黑字，样式在 lss 里）。 */
    public static final String CLS_TOOL_ACTIVE = "me-tool-active";
    /** 当前项目标签的选中态（蓝底黑字，样式在 lss 里）。 */
    public static final String CLS_TAB_ACTIVE = "me-tab-active";
    /** 最外层背景。 */
    public static final String CLS_BG = "me-bg";
    /** 危险操作（删除）的文字色。 */
    public static final String CLS_DANGER = "me-btn-danger";
    /** 强调按钮（已打开的项目 / 选中的 cube）。 */
    public static final String CLS_BTN_ACCENT = "me-btn-accent";
    /** 坐标轴标签的 class，顺序与 XYZ 一致。 */
    public static final String[] CLS_AXIS = {"me-axis-x", "me-axis-y", "me-axis-z"};

    /**
     * 取编辑器样式表；拿不到就退回 {@code mc.lss}。
     * <p>宁可长得像原版，也不要整屏没有样式（那样连字都看不清）。</p>
     */
    public static com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet stylesheet() {
        var sheet = com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.INSTANCE
                .getStylesheetSafe(STYLESHEET);
        if (sheet == com.lowdragmc.lowdraglib2.gui.ui.style.Stylesheet.EMPTY) {
            return com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.INSTANCE
                    .getStylesheetSafe(com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager.MC);
        }
        return sheet;
    }

    /** 挂语义 class（样式在 lss 里）。 */
    public static <T extends UIElement> T style(T e, String className) {
        if (e != null && className != null) e.addClass(className);
        return e;
    }


    /** 最外层 / 视口底色（Blockbench 的深灰）。 */
    public static final int BG = 0xFF1A1D23;
    /** 常规面板。 */
    public static final int PANEL = 0xFF23272E;
    /** 次级面板：标题条、列表行交替色。 */
    public static final int PANEL_ALT = 0xFF2B3038;
    /** 输入框 / 凹陷区域（比面板更深）。 */
    public static final int SUNKEN = 0xFF1B1E23;
    /** 1px 分隔线（比面板更深，靠"更暗"而不是"更亮"来分块，Blockbench 就是这么做的）。 */
    public static final int BORDER = 0xFF14171C;

    /** 主文字（略偏冷的白）。 */
    public static final int TEXT = 0xFFD7DCE5;
    /** 次要文字（标题、提示、单位）。 */
    public static final int TEXT_DIM = 0xFF8C94A3;
    /** 选中 / 强调（Blockbench 的蓝）。 */
    public static final int ACCENT = 0xFF3B82F6;
    /** 蓝底上的字 —— Blockbench 的选中项是蓝底黑字。 */
    public static final int ON_ACCENT = 0xFF10141A;
    /** 悬停行。 */
    public static final int ROW_HOVER = 0xFF2E3440;
    /** 选中行：比面板亮一档、带点蓝（Blockbench 里被选中的那条）。 */
    public static final int ROW_SELECTED = 0xFF37404E;
    /** 正常 / 通过。 */
    public static final int GOOD = 0xFF4FAE63;
    /** 警告。 */
    public static final int WARN = 0xFFD4A73C;
    /** 错误。 */
    public static final int BAD = 0xFFD96A6A;

    /** 坐标轴三色（Blockbench 的位置/尺寸输入框就是红绿蓝）。 */
    public static final int AXIS_X = 0xFFD96A6A;
    public static final int AXIS_Y = 0xFF5FBF6A;
    public static final int AXIS_Z = 0xFF5A8FD9;

    /** 按钮底色（比面板亮一档）。 */
    public static final int BUTTON = 0xFF31363F;
    /** 按钮悬停色。 */
    public static final int BUTTON_HOVER = 0xFF3D444F;
    /** 按钮按下色。 */
    public static final int BUTTON_PRESSED = 0xFF272B32;
    /** 树形缩进每级宽度（px）。 */
    public static final int INDENT = 9;

    private EditorTheme() {
    }

    /**
     * 把一棵子树里的所有 {@link Button} 改成<strong>扁平纯色</strong>。
     *
     * <p>LDLib2 的 Button 默认用原版那种"凸起"贴图（{@code baseTexture} 是带高光的九宫格），
     * 整屏看下来就是"原版 GUI"。Blockbench 的按钮是一块纯色 + 悬停变亮 + 按下变暗，
     * 这里就干这件事：只换底图，<strong>不动文字颜色</strong>
     * （文字色是按语义上过的：强调蓝 / 删除红）。</p>
     *
     * <p>为什么用"遍历整棵树"而不是每个 new Button 后面补一行：
     * 按钮散在十几个方法里、还有每次刷新重建的列表，逐个补必然漏；
     * 一处遍历一次覆盖全部，也不会因为以后新增按钮而忘记。</p>
     */
    public static void flatize(UIElement root) {
        if (root == null) return;
        // 颜色/贴图都在 lss 里（button:host / text-field:host），代码这里只补
        // **LSS 表达不了的两件事**，边界很明确：
        //   1) 布局不变量：按钮文字不许画到按钮外面（overflow 不是样式属性）
        //   2) 组件状态：清掉空输入框的占位符（placeholder 是控件的值，不是样式）
        if (root instanceof Button b) {
            b.setOverflowVisible(false);
        }
        if (root instanceof TextField f) {
            f.textFieldStyle(s -> s.placeholder(net.minecraft.network.chat.Component.empty()));
        }
        // Tab / Toggle 的样式也已经进 lss（tab:host / .__toggle_button__ / toggle:host），
        // 这里不再重复设置。
        for (var child : root.getChildren()) {
            flatize(child);
        }
    }

    /** 一级缩进（树形结构用：cube 是文件夹的子项）。 */
    public static UIElement indent(int level, int height) {
        var sp = new UIElement();
        sp.layout(l -> l.width(INDENT * Math.max(0, level)).height(height));
        return sp;
    }

    /**
     * 把 ScrollView 的滚动条也换成扁平深色。
     *
     * <p>这是最后一块原版贴图：{@code Scroller} 内部用
     * {@code Sprites.SCROLL_CONTAINER_V/H}（槽）和 {@code Sprites.SCROLL_BAR_V/H}（滑块），
     * 还会画原版那种带三角箭头的 head/tail 按钮。这里把它们全换成纯色：
     * 槽 = 凹陷深色，滑块 = 中灰、悬停/按下变蓝，两端箭头按钮直接抹掉（扁平风不需要）。</p>
     */
    public static void styleScrollbars(ScrollerView sc) {
        if (sc == null) return;
        sc.verticalScroller(EditorTheme::flatScroller);
        sc.horizontalScroller(EditorTheme::flatScroller);
    }

    private static void flatScroller(Scroller s) {
        if (s == null) return;
        s.scrollContainer(c -> c.style(st -> st.backgroundTexture(new ColorRectTexture(SUNKEN))));
        s.scrollBar.buttonStyle(st -> st
                .baseTexture(new ColorRectTexture(BUTTON_HOVER))
                .hoverTexture(new ColorRectTexture(ACCENT))
                .pressedTexture(new ColorRectTexture(ACCENT)));
        // 两端的箭头按钮：抹成透明（纯色条不需要箭头）
        s.headButton.buttonStyle(st -> st
                .baseTexture(IGuiTexture.EMPTY)
                .hoverTexture(IGuiTexture.EMPTY)
                .pressedTexture(IGuiTexture.EMPTY));
        s.tailButton.buttonStyle(st -> st
                .baseTexture(IGuiTexture.EMPTY)
                .hoverTexture(IGuiTexture.EMPTY)
                .pressedTexture(IGuiTexture.EMPTY));
    }

    /** 纯色填充（扁平面板的基本手段）。 */
    public static void fill(UIElement e, int argb) {
        if (e == null) return;
        e.style(s -> s.background(new ColorRectTexture(argb)));
    }

    /** 上色一个 Label（顺带关掉原版的文字投影 —— 深色底上投影会显脏）。 */
    public static void text(Label l, int argb) {
        if (l == null) return;
        l.getTextStyle().textColor(argb).textShadow(false);
    }

    /**
     * 把 LDLib2 的 {@link Dialog} 也改成同一套扁平暗色。
     *
     * <p>光调 {@link #flatize} 不够：Dialog 的三块框架是**自己写死的原生贴图**
     * （{@code titleBar → Sprites.BORDER1_RT1}、{@code contentContainer → Sprites.RECT_SOLID}），
     * 不换就是截图里那个灰框。它们都是 public 字段，直接改。</p>
     *
     * <p><b>必须在 {@code show()} 之后调用</b>：标题栏里的关闭按钮和
     * {@code buttonContainer} 里的按钮是 show 时才装配出来的。</p>
     */
    public static void styleDialog(Dialog d) {
        if (d == null) return;
        d.titleBar.style(s -> s.backgroundTexture(new ColorRectTexture(PANEL_ALT)));
        d.contentContainer.style(s -> s.backgroundTexture(new ColorRectTexture(PANEL)));
        d.buttonContainer.style(s -> s.backgroundTexture(new ColorRectTexture(PANEL)));
        flatize(d);
    }

    /**
     * 让一个 Label <b>自动换行</b>（放不下就折行，而不是画出框）。
     *
     * <p>LDLib2 的 {@code TextWrap.WRAP} + {@code adaptiveHeight(true)} 就是干这个的，
     * 它自己的 {@code Dialog} 里也是这么写。配上 {@code adaptiveWidth(false)}，
     * 宽度才会老老实实跟着父级走、而不会撑开去放字。</p>
     */
    public static void wrapText(Label l) {
        if (l == null) return;
        l.textStyle(s -> s.adaptiveWidth(false)
                .textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP)
                .adaptiveHeight(true));
    }

    /** 上色一个 Button 的文字（Button 的 API 是 textStyle(...)，不是 getTextStyle()）。 */
    public static void buttonText(Button b, int argb) {
        if (b == null) return;
        b.textStyle(s -> s.textColor(argb).textShadow(false));
    }

    /**
     * 1px 分隔线（横向）。
     *
     * <p>不用"边框"API，而是拿一个 1px 高的纯色元素当分隔 —— 扁平风的边就是一条线，
     * 这样不会因为不同控件的边框半径/描边规则不一致而看起来参差。</p>
     */
    public static UIElement divider() {
        var d = new UIElement();
        d.layout(l -> l.widthPercent(100).height(1));
        fill(d, BORDER);
        return d;
    }

    /** 分组标题条（Blockbench 右侧那种 "变换" / "大纲" 小标题）。 */
    public static Label sectionTitle(String text) {
        var l = new Label();
        l.setText(net.minecraft.network.chat.Component.literal(text));
        text(l, TEXT_DIM);
        l.layout(layout -> layout.widthPercent(100).height(13).paddingAll(0));
        return l;
    }
}
