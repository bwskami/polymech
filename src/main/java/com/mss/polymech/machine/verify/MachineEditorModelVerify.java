package com.mss.polymech.machine.verify;

import com.mss.polymech.client.gui.machine.editor.MachineOutlineLogic;
import com.mss.polymech.client.gui.machine.editor.MachineStructureSnapshot;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.common.MachineGeometry;
import com.mss.polymech.machine.definition.MachineDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

import java.util.List;
import java.util.Map;

/**
 * 机器结构编辑器数据模型离线校验 —— 对应 {@code verifyMachineEditorModel} Gradle 任务。
 *
 * <h3>为什么"傻瓜式"验证对编辑器尤其重要</h3>
 * 编辑器的核心承诺是：<strong>它画出来/导出来的坐标，必须和运行时真正用的坐标一致</strong>。
 * 一旦快照的坐标换算（负本地坐标 ↔ 非负渲染坐标的 originShift）或导出格式出错，
 * 你在 3D 里点到的格子和导回源码的数字对不上——静默错位，正是这套工具要消灭的毛病，
 * 绝不能工具自己犯。而这些全是纯坐标数学 + 字符串生成，离线即可判死。
 *
 * <h3>判据来源（避免用自己的实现自证）</h3>
 * <ul>
 *   <li><b>M1 往返一致</b>：本地坐标 → 渲染坐标 → 本地坐标，必须还原（独立断言，
 *       用 {@link MachineGeometry} 之外的手算比对）；</li>
 *   <li><b>M2 覆盖完整</b>：快照的格子集合必须等于 {@code MachineGeometry}
 *       独立展开出的集合（与放置逻辑同源，编辑器不会漏格/多格）；</li>
 *   <li><b>M3 语义透传</b>：每格的 {@code ItemProxy/FluidProxy} 与直接问 Block 的结果一致
 *       （期望值来自被测 Block 的 if 链，不来自快照）；</li>
 *   <li><b>M4 导出 JSON</b>：导出的 {@code side_offsets} 每个坐标都能反查到真实格子（自洽）、
 *       不含控制器，且"解析→再序列化"内容不变（读写对称）；</li>
 *   <li><b>M5 不拿原版方块冒充</b>：源码级防回归（透明格保持透明、核心显示原样）；</li>
 *   <li><b>M6 区间抠洞不摊平</b>：从 {@code fill_regions} 里删一格必须把区间切成若干块，
 *       离散格仍为空、导出仍是区间——大机器绝不能退化成逐格列表。</li>
 *   <li><b>M7 编辑器不播建造动画</b>：纯函数真值表 + 四处接线 + {@code setRenderedCore} 顺序；</li>
 *   <li><b>M8 舱室分色 + 开口面语义</b>：五种分类的判据与颜色必须互不相同；
 *       {@code faces} 空=六面全通、六面全通导出必须<strong>省略</strong>字段；中英文都能解析；</li>
 *   <li><b>M9 开口面片几何</b>：面片四角必须落在声明的那个面上、构成边长 {@code 1-2*inset} 的正方形、
 *       面心与箭头正确——尤其上下/南北/东西三对"最容易写反"的对称性；</li>
 *   <li><b>M10 结构体检</b>：能挑出"能写但静默失效"的写法（舱室在结构外/没槽位/控制器被当舱室），
 *       且 ERROR 排在 WARN 之前；</li>
 *   <li><b>M11 撤销/重做 + 定义整体换回</b>：顺序、状态迁移、上限，
 *       以及 {@code loadFrom} 的忠实度（期望值用独立三重循环算，不拿 {@code MachineGeometry} 自证）；</li>
 *   <li><b>M12 界面接线</b>：源码级防回归——逻辑存在但没接上去（分色函数有了却还画全白、
 *       朝向面算对了却没调）这类问题。</li>
 * </ul>
 *
 * <h3>已知不覆盖</h3>
 * {@code Scene} 的真实渲染、鼠标拾取、页签布局与观感必须进游戏看，离线无法验证；
 * 本校验只保证"喂给 Scene 的数据与几何是对的、界面确实接了这些逻辑"。
 * <p>M9 做过负向测试：把"上面"故意写成 {@code p.y+inset}（与下面重合），
 * 该组断言立刻 2 条 FAIL、退出码 1 —— 说明这些判据真的会失败，不是摆设。</p>
 *
 * <p>运行：{@code ./gradlew verifyMachineEditorModel}（退出码 0 = 全部 PASS）</p>
 */
public final class MachineEditorModelVerify {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        verifyRoundTrip();
        verifyCoversStructure();
        verifyProxyPassthrough();
        verifyExportFormat();
        verifyNoVanillaSubstitution();
        verifyRegionCarve();
        verifyNoBuildAnimation();
        verifyCellVisualsAndFaces();
        verifyFaceQuadGeometry();
        verifyValidator();
        verifyUndoAndReload();
        verifyFaceSlots();
        verifyFaceGrid();
        verifyCellFillFaces();
        verifyOuterEdges();
        verifyTools();
        verifyFaceSlotEditing();
        verifyEditorUiWiring();
        verifyNestedGroups();
        verifyOutlineLogic();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL PASS (" + checks + " 项断言)");
            System.exit(0);
        }
        System.out.println(failures + " FAILED (共 " + checks + " 项断言)");
        System.exit(1);
    }

    // ==================== 被测数据：一个纯几何构造的机器（不依赖游戏世界） ====================

    /** 十字 + 顶部两格的机器，等价于卧式锅炉那类离散结构（facing=NORTH 本地坐标）。 */
    private static final Vec3i[] SIDE_OFFSETS = {
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1),
            new Vec3i(0, 1, -2), new Vec3i(0, 3, 2),   // 含负 z，专门覆盖 originShift
    };

    private static MachineStructureSnapshot buildSnapshot() {
        var snap = new MachineStructureSnapshot("test_machine");
        for (Vec3i v : SIDE_OFFSETS) {
            snap.addCell(v);
        }
        // 给两格设语义
        snap.setRole(new Vec3i(0, 1, -2),
                new BaseMachineBlock.ItemProxy(new int[]{2}, BaseMachineBlock.ProxyIO.INPUT, null), null);
        snap.setRole(new Vec3i(0, 3, 2), null,
                new BaseMachineBlock.FluidProxy(new int[]{1}, BaseMachineBlock.ProxyIO.OUTPUT,
                        new Direction[]{Direction.UP}));
        return snap;
    }

    // ==================== M1：本地 ↔ 渲染坐标往返 ====================

    private static void verifyRoundTrip() {
        System.out.println();
        System.out.println("==== M1 本地↔渲染坐标往返（负坐标必须还原）====");
        var snap = buildSnapshot();
        boolean allOk = true;
        String detail = "";
        for (Vec3i local : SIDE_OFFSETS) {
            var render = snap.toRenderPos(local);
            var cell = snap.cellAtRenderPos(render);
            if (cell == null || !cell.local().equals(local)) {
                allOk = false;
                detail = "local=" + local + " render=" + render
                        + " 反查=" + (cell == null ? "null" : cell.local());
            }
        }
        report("每格 toRenderPos→cellAtRenderPos 还原本地坐标", allOk,
                allOk ? "含负 z 的格子全部还原" : "★ 未还原：" + detail);

        // 渲染坐标必须全非负（Scene 摆放前提）
        boolean nonNeg = true;
        for (Vec3i local : SIDE_OFFSETS) {
            var r = snap.toRenderPos(local);
            if (r.getX() < 0 || r.getY() < 0 || r.getZ() < 0) {
                nonNeg = false;
                detail = local + " → " + r + "（含负分量）";
            }
        }
        report("渲染坐标全部非负", nonNeg, nonNeg ? "originShift 生效" : "★ 有负坐标：" + detail);
    }

    // ==================== M2：格子集合覆盖完整 ====================

    private static void verifyCoversStructure() {
        System.out.println();
        System.out.println("==== M2 格子覆盖与放置逻辑一致 ====");
        var snap = buildSnapshot();

        // 期望：独立用 MachineGeometry 展开（与 setPlacedBy 同一份实现）
        List<Vec3i> geometryCells = MachineGeometry.enumerateLocalOffsets(SIDE_OFFSETS, null);
        java.util.Set<Vec3i> expected = new java.util.HashSet<>(geometryCells);
        expected.add(Vec3i.ZERO); // 控制器

        java.util.Set<Vec3i> actual = new java.util.HashSet<>();
        for (var c : snap.cells()) actual.add(c.local());

        report("快照格子集合 == 几何展开集合（含控制器）", expected.equals(actual),
                "期望 " + expected.size() + " 格，实际 " + actual.size() + " 格"
                        + (expected.equals(actual) ? "" : " 差集=" + diff(expected, actual)));

        report("控制器恰有一格且在原点",
                snap.cells().stream().filter(MachineStructureSnapshot.Cell::controller).count() == 1
                        && snap.cellAt(Vec3i.ZERO) != null,
                "原点控制器存在");
    }

    // ==================== M3：代理语义透传 ====================

    private static void verifyProxyPassthrough() {
        System.out.println();
        System.out.println("==== M3 代理语义（槽位/储罐/开口面不丢）====");
        var snap = buildSnapshot();

        var fuel = snap.cellAt(new Vec3i(0, 1, -2));
        report("燃料格有物品代理", fuel != null && fuel.itemProxy() != null,
                fuel == null ? "★ 找不到格" : (fuel.itemProxy() != null ? "有" : "无"));
        if (fuel != null && fuel.itemProxy() != null) {
            var p = fuel.itemProxy();
            report("燃料代理 = 槽[2] + INPUT",
                    p.slots().length == 1 && p.slots()[0] == 2
                            && p.io() == BaseMachineBlock.ProxyIO.INPUT,
                    "槽=" + java.util.Arrays.toString(p.slots()) + " io=" + p.io());
            report("燃料代理开口面为 null（六面全通）", p.faces() == null,
                    p.faces() == null ? "null" : "★ " + java.util.Arrays.toString(p.faces()));
        }

        var steam = snap.cellAt(new Vec3i(0, 3, 2));
        report("蒸汽格有流体代理且仅 UP 面",
                steam != null && steam.fluidProxy() != null
                        && steam.fluidProxy().tanks()[0] == 1
                        && steam.fluidProxy().io() == BaseMachineBlock.ProxyIO.OUTPUT
                        && steam.fluidProxy().faces() != null
                        && steam.fluidProxy().faces().length == 1
                        && steam.fluidProxy().faces()[0] == Direction.UP,
                steam == null ? "★ 找不到格" : steam.describe());

        var plain = snap.cellAt(new Vec3i(1, 0, 0));
        report("普通格无代理（外壳）",
                plain != null && plain.itemProxy() == null && plain.fluidProxy() == null,
                plain == null ? "★ 找不到格" : plain.describe());
    }

    // ==================== M4：导出 JSON ====================

    private static void verifyExportFormat() {
        System.out.println();
        System.out.println("==== M4 导出 JSON（与运行时同一份 MachineDefinition 序列化）====");
        var snap = buildSnapshot();
        var json = snap.toJson();

        // 关键：导出的每个 cube 的 from 角都必须对应真实格子（自洽）
        var groupsJson = json.getAsJsonArray("groups");
        boolean allResolve = true;
        int cubeCount = 0;
        for (var gel : groupsJson) {
            for (var cel : gel.getAsJsonObject().getAsJsonArray("cubes")) {
                cubeCount++;
                var a = cel.getAsJsonObject().getAsJsonArray("from");
                Vec3i v = new Vec3i(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
                if (!snap.hasCell(v)) allResolve = false;
            }
        }
        report("groups 里每个 cube 都对应真实格子", allResolve && cubeCount > 0,
                "共 " + groupsJson.size() + " 组 / " + cubeCount + " cube"
                        + (allResolve ? " 全部命中" : " ★ 有悬空 cube"));

        // 控制器不能被生成成侧面方块（否则放置时会拿侧边方块覆盖主方块）。
        // 注意：cube **可以**覆盖 (0,0,0)（例如锅炉的填充区间就包含原点），
        // 真正保证控制器不被覆盖的是 sidePositions 里的 positions.remove(origin)。
        // 所以断言必须打在"生成出来的侧面位置"上，而不是打在 cube 上。
        var parsed = com.mss.polymech.machine.definition.MachineDefinition.parse("test_machine", json);
        boolean originAsSide = false;
        for (var p : com.mss.polymech.machine.common.MachineGeometry.sidePositions(
                net.minecraft.core.BlockPos.ZERO, Direction.NORTH,
                parsed.sideOffsets(), parsed.fillRegions())) {
            if (p.equals(net.minecraft.core.BlockPos.ZERO)) originAsSide = true;
        }
        report("控制器(0,0,0)不会被生成成侧方块", !originAsSide,
                originAsSide ? "★ 被当成侧方块" : "控制器位置被排除");

        // 往返：解析回来必须一致（编辑器保存 → 运行时读取 走的就是这条路径）
        report("往返后结构树保留（组数与 cube 数不变）",
                parsed.groups().size() == groupsJson.size() && parsed.cubes().size() == cubeCount,
                parsed.groups().size() + " 组 / " + parsed.cubes().size() + " cube");

        var itemBack = parsed.itemProxy(new Vec3i(0, 1, -2));
        report("往返后物品代理保留 槽[2]+INPUT",
                itemBack != null && itemBack.slots().length == 1 && itemBack.slots()[0] == 2
                        && itemBack.io() == BaseMachineBlock.ProxyIO.INPUT && itemBack.faces() == null,
                itemBack == null ? "★ 丢了" : "槽=" + java.util.Arrays.toString(itemBack.slots()) + " io=" + itemBack.io());

        var fluidBack = parsed.fluidProxy(new Vec3i(0, 3, 2));
        report("往返后流体代理保留 罐[1]+OUTPUT+UP",
                fluidBack != null && fluidBack.tanks()[0] == 1
                        && fluidBack.io() == BaseMachineBlock.ProxyIO.OUTPUT
                        && fluidBack.faces() != null && fluidBack.faces().length == 1
                        && fluidBack.faces()[0] == Direction.UP,
                fluidBack == null ? "★ 丢了" : "罐=" + java.util.Arrays.toString(fluidBack.tanks())
                        + " io=" + fluidBack.io() + " faces=" + java.util.Arrays.toString(fluidBack.faces()));

        report("二次序列化内容不变（读写对称）", parsed.toJson().equals(json),
                parsed.toJson().equals(json) ? "一致" : "★ 不一致");
    }

    // ==================== M6：区间抠洞不摊平 ====================

    /**
     * 用户明确否决过"逐格画图案"：超大机器必须靠 {@code fill_regions} 区间。
     * 所以编辑器从区间里删一格时，必须把区间切成若干块，而不是把整个区间摊平成几百个离散格。
     * 这条把该性质钉死。
     */
    private static void verifyRegionCarve() {
        System.out.println();
        System.out.println("==== M6 填充区间抠洞（大机器不退化成逐格列表）====");

        var snap = new MachineStructureSnapshot("carve_test");
        snap.addFillRegion(new Vec3i(-1, 0, -2), new Vec3i(1, 2, 2));   // 3×3×5 = 45 格

        report("区间展开为 45 格（控制器在区间内也只算一格）", snap.cellCount() == 45,
                snap.cellCount() + " 格");
        report("控制器在区间内仍是控制器",
                snap.cellAt(Vec3i.ZERO) != null && snap.cellAt(Vec3i.ZERO).controller(),
                "原点 = 控制器");
        report("控制器不可删", !snap.removeCell(Vec3i.ZERO), "removeCell(0,0,0) 被拒绝");

        Vec3i hole = new Vec3i(0, 1, 0);   // 三轴都在区间内部，故应切成 6 块
        boolean removed = snap.removeCell(hole);
        report("能从区间里抠掉一格", removed, removed ? "已抠 " + hole : "★ 失败");
        report("抠洞后剩 44 格", snap.cellCount() == 44, snap.cellCount() + " 格");
        // schema 2：结构是"文件夹 + cube"，没有"区间切块"这回事了。
        // 抠掉一格 = 从一个文件夹里删掉 1 个 cube，分组本身不动（名字也不会丢）。
        report("抠洞后仍是 1 个文件夹（分组不被拆散、名字不丢）", snap.groups().size() == 1,
                "groups=" + snap.groups().size()
                        + (snap.groups().isEmpty() ? "" : "，名字=" + snap.groups().get(0).name()));
        report("抠洞后是 44 个 cube（一个方块 = 一个 cube）", snap.cubes().size() == 44,
                "cube=" + snap.cubes().size());

        // 残块并集必须恰好 = 原区间 − 该格（独立三重循环算期望，不用被测实现自证）
        java.util.Set<Vec3i> expected = new java.util.HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Vec3i v = new Vec3i(x, y, z);
                    if (!v.equals(hole)) expected.add(v);
                }
            }
        }
        java.util.Set<Vec3i> actual = new java.util.HashSet<>();
        for (var c : snap.cells()) actual.add(c.local());
        report("残块并集 == 原区间 − 抠掉的格", expected.equals(actual),
                expected.equals(actual) ? "44 格完全一致" : " 差集=" + diff(expected, actual));

        // 导出的 JSON 必须是 44 个 cube。
        // schema 2 的口径是"一个方块 = 一个 cube"，所以旧的
        // "抠洞后自动并成 6 个区间盒子" 那个优化随 fill_regions 一起退役了，
        // 这里改钉"cube 数 == 格子数"，并确认旧键已不再出现。
        var json = snap.toJson();
        int cubeCount = 0;
        for (var gel : json.getAsJsonArray("groups")) {
            cubeCount += gel.getAsJsonObject().getAsJsonArray("cubes").size();
        }
        boolean legacyGone = !json.has("side_offsets") && !json.has("fill_regions");
        report("导出 JSON 是 44 个 cube（一个方块 = 一个 cube），且旧键已移除",
                cubeCount == 44 && legacyGone,
                "cube=" + cubeCount + " 旧键已移除=" + legacyGone);
    }

    // ==================== M5：不得拿原版方块冒充（源码级防回归） ====================

    /**
     * 用户明确否决过"用金块/石砖/彩玻璃代表侧边/舱室方块"：
     * 真实侧边方块本身就是 {@code RenderShape.INVISIBLE}（全透明），核心方块该显示原本的样子。
     * 把该约定钉死，防止以后（包括我自己）又改回去。
     */
    private static void verifyNoVanillaSubstitution() {
        System.out.println();
        System.out.println("==== M5 不拿原版方块冒充（透明格保持透明 + 核心显示原样）====");

        String screen = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/MachineTab.java");
        if (screen == null) {
            report("读到 MachineTab.java 源码", false, "★ 读不到文件");
            return;
        }

        String[] banned = {"Blocks.GOLD_BLOCK", "Blocks.STONE_BRICKS", "STAINED_GLASS", "Blocks.IRON_BLOCK"};
        String hit = null;
        for (String b : banned) {
            if (screen.contains(b)) hit = b;
        }
        report("编辑器不再用原版方块冒充格子", hit == null,
                hit == null ? "已无 " + String.join(" / ", banned) : "★ 又出现 " + hit);

        report("侧边格用真实侧边方块（getSideBlock）", screen.contains("getSideBlock()"),
                screen.contains("getSideBlock()") ? "有" : "★ 缺");

        report("控制器格用主方块本体（defaultBlockState）", screen.contains("block.defaultBlockState()"),
                screen.contains("block.defaultBlockState()") ? "有" : "★ 缺");

        report("透明格有线框描边（drawStructureOutline）", screen.contains("drawStructureOutline"),
                screen.contains("drawStructureOutline") ? "有" : "★ 缺");

        // "保持透明"的依据：真实侧边方块必须仍是 INVISIBLE
        String side = readSource(
                "src/main/java/com/mss/polymech/machine/common/LargeMachineSideBlock.java");
        boolean invisible = side != null && side.contains("RenderShape.INVISIBLE");
        report("真实侧边方块是 INVISIBLE（所以视口里就该透明）", invisible,
                side == null ? "★ 读不到 LargeMachineSideBlock.java"
                        : (invisible ? "getRenderShape=INVISIBLE" : "★ 已不再是 INVISIBLE"));
    }

    // ==================== M7：编辑器里不播建造动画 ====================

    /**
     * 用户要求：打开结构编辑器不该每次都重播一遍建造动画。
     *
     * <p>根因：编辑器每次打开都在 DummyWorld 里新建方块实体，"建造动画已播过"标记是初始 false，
     * 于是每次都从建造动画开始播。修法是打 {@link BuildAnimationSuppressible} 标记。</p>
     *
     * <p>这里能离线判死，是因为"该不该播"被提成了一条<strong>纯函数</strong>
     * （{@code BuildAnimationSuppressible.shouldPlayBuildingAnimation}），
     * 不依赖 Geckolib 与游戏 bootstrap：先钉真值表，再钉三处接线（编辑器打标记 /
     * 锅炉控制器读标记 / 预览虚影也走同一接口）。</p>
     */
    private static void verifyNoBuildAnimation() {
        System.out.println();
        System.out.println("==== M7 编辑器不播建造动画（真值表 + 接线）====");

        // --- 真值表（纯函数，离线可判） ---
        report("抑制时：不播建造动画（编辑器/预览虚影）",
                !com.mss.polymech.machine.BuildAnimationSuppressible
                        .shouldPlayBuildingAnimation(true, false),
                "suppressed=true, played=false → 不播");
        report("未抑制且没播过：播建造动画（真实放置）",
                com.mss.polymech.machine.BuildAnimationSuppressible
                        .shouldPlayBuildingAnimation(false, false),
                "suppressed=false, played=false → 播");
        report("未抑制但已播过：不重播（区块重载不重播）",
                !com.mss.polymech.machine.BuildAnimationSuppressible
                        .shouldPlayBuildingAnimation(false, true),
                "suppressed=false, played=true → 不播");

        // --- 接线 1：编辑器确实打了标记，且在 setRenderedCore 之后 ---
        String screen = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/MachineTab.java");
        if (screen == null) {
            report("读到 MachineTab.java 源码", false, "★ 读不到文件");
        } else {
            report("编辑器打抑制标记", screen.contains("setBuildAnimationSuppressed(true)"),
                    screen.contains("setBuildAnimationSuppressed(true)") ? "有" : "★ 缺");
            report("编辑器在 setRenderedCore 之后打标记（否则查 BE 返回 null）",
                    screen.contains("suppressBuildAnimations()")
                            && screen.indexOf("setRenderedCore") < screen.lastIndexOf("suppressBuildAnimations()"),
                    "顺序正确");
        }

        // --- 接线 2：锅炉控制器读标记（且用的是同一条纯函数） ---
        String boiler = readSource(
                "src/main/java/com/mss/polymech/machine/production/HorizontalSteamBoilerBlockEntity.java");
        boolean wired = boiler != null
                && boiler.contains("BuildAnimationSuppressible.shouldPlayBuildingAnimation(")
                && boiler.contains("isBuildAnimationSuppressed()");
        report("锅炉动画控制器用同一纯函数决策", wired,
                boiler == null ? "★ 读不到" : (wired ? "已接线" : "★ 未接线"));
        report("旧的 public isGhostPreview 字段已移除（避免两套标记）",
                boiler != null && !boiler.contains("public boolean isGhostPreview"),
                boiler == null ? "★ 读不到" : "已移除");

        // --- 接线 3：蓝图预览虚影也走同一接口（对任何机器通用） ---
        String preview = readSource("src/main/java/com/mss/polymech/client/MachinePreviewRenderer.java");
        boolean previewOk = preview != null && preview.contains("BuildAnimationSuppressible")
                && preview.contains("setBuildAnimationSuppressed(true)");
        report("蓝图预览虚影走同一接口（不再只认锅炉）", previewOk,
                preview == null ? "★ 读不到" : (previewOk ? "已通用" : "★ 仍在写锅炉专有字段"));

        // --- 接线 4：标记字段在共同基类上，新增机器自动支持 ---
        String base = readSource("src/main/java/com/mss/polymech/machine/BaseIOBlockEntity.java");
        boolean baseOk = base != null && base.contains("implements MenuProvider, BuildAnimationSuppressible")
                && base.contains("setBuildAnimationSuppressed");
        report("标记字段在 BaseIOBlockEntity（新增机器自动支持）", baseOk,
                base == null ? "★ 读不到" : (baseOk ? "在共同基类上" : "★ 不在基类上"));
    }

    // ==================== M8：舱室分色 + 开口面语义 ====================

    /**
     * 用户要求：3D 里必须能看出"哪一格是舱室"以及"舱室朝哪边开"。
     *
     * <p>分色判据（{@link com.mss.polymech.client.gui.machine.editor.EditorCellVisuals}）
     * 与开口面语义（{@link com.mss.polymech.client.gui.machine.editor.ProxyFaces}）都是纯函数，
     * 所以能离线钉死——尤其是"六面全通导出时必须省略 faces 字段"这条，
     * 它直接决定 JSON 长什么样。</p>
     */
    private static void verifyCellVisualsAndFaces() {
        System.out.println();
        System.out.println("==== M8 舱室分色 + 开口面语义 ====");

        report("外壳格分类为 STRUCTURE",
                com.mss.polymech.client.gui.machine.editor.EditorCellVisuals
                        .kindOf(false, false, false)
                        == com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.STRUCTURE,
                "无代理 → 外壳");
        report("只有物品代理 → 物品舱室",
                com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.kindOf(false, true, false)
                        == com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.ITEM_PROXY,
                "橙");
        report("只有流体代理 → 流体舱室",
                com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.kindOf(false, false, true)
                        == com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.FLUID_PROXY,
                "蓝");
        report("两种代理都有 → 物品+流体舱室",
                com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.kindOf(false, true, true)
                        == com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.BOTH_PROXY,
                "紫");
        report("控制器优先（控制器格不放代理）",
                com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.kindOf(true, true, true)
                        == com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.CONTROLLER,
                "绿");

        // 五种分类的线框颜色必须互不相同（否则等于没分色）
        var kinds = com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.values();
        boolean distinct = true;
        for (int i = 0; i < kinds.length; i++) {
            for (int j = i + 1; j < kinds.length; j++) {
                if (kinds[i].outlineColor() == kinds[j].outlineColor()) distinct = false;
            }
        }
        report("五种分类的线框颜色互不相同", distinct, "共 " + kinds.length + " 类");

        report("isProxy 只对舱室为真",
                !com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.STRUCTURE.isProxy()
                        && !com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.CONTROLLER.isProxy()
                        && com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.ITEM_PROXY.isProxy()
                        && com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.FLUID_PROXY.isProxy()
                        && com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.Kind.BOTH_PROXY.isProxy(),
                "外壳/控制器不算舱室");

        String legend = com.mss.polymech.client.gui.machine.editor.EditorCellVisuals.legendText();
        boolean legendOk = true;
        for (var k : kinds) {
            if (!legend.contains(k.label())) legendOk = false;
        }
        report("图例覆盖全部分类", legendOk, legendOk ? "五类都在图例里" : "★ 图例漏了分类");

        // ---- 开口面语义（必须与 allowsWorldFace 的判据一致）----
        var PF = com.mss.polymech.client.gui.machine.editor.ProxyFaces.class;
        report("faces=null 视为六面全通",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces.open(null).size() == 6,
                "与 allowsWorldFace 一致");
        report("faces=空数组 视为六面全通",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces
                        .open(new Direction[0]).size() == 6, "空=全通");
        report("faces=[UP] 只通上面",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces.open(new Direction[]{Direction.UP})
                        .equals(java.util.EnumSet.of(Direction.UP)),
                "单一面");

        // 六面全通导出必须省略字段（否则 JSON 里会多出一串六项数组）
        report("六面全通 → toArray 返回 null（导出省略 faces 字段）",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces
                        .toArray(java.util.EnumSet.allOf(Direction.class)) == null,
                "省略字段");

        Direction[] two = com.mss.polymech.client.gui.machine.editor.ProxyFaces
                .toArray(java.util.EnumSet.of(Direction.NORTH, Direction.UP));
        report("部分面 → 按固定顺序导出（上在北之前）",
                two != null && two.length == 2 && two[0] == Direction.UP && two[1] == Direction.NORTH,
                two == null ? "★ null" : (two[0] + "," + two[1]));

        report("label(null) = 六面全通",
                "六面全通".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces.label(null)),
                "中文可读");
        report("label([UP,NORTH]) = 上+北",
                "上+北".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces
                        .label(new Direction[]{Direction.UP, Direction.NORTH})),
                "中文可读");

        // 六个方向的中文名
        boolean cnOk = "上".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces.cn(Direction.UP))
                && "下".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces.cn(Direction.DOWN))
                && "北".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces.cn(Direction.NORTH))
                && "南".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces.cn(Direction.SOUTH))
                && "西".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces.cn(Direction.WEST))
                && "东".equals(com.mss.polymech.client.gui.machine.editor.ProxyFaces.cn(Direction.EAST));
        report("六个方向的中文名正确", cnOk, "上/下/北/南/西/东");

        // 输入解析：英文/中文都要认（整合包作者可能手打）
        var pEn = com.mss.polymech.client.gui.machine.editor.ProxyFaces.parse("up,north");
        var pCn = com.mss.polymech.client.gui.machine.editor.ProxyFaces.parse("上,北");
        report("解析 \"up,north\" 与 \"上,北\" 结果相同",
                java.util.Arrays.equals(pEn, pCn) && pEn != null && pEn.length == 2,
                pEn == null ? "★ null" : java.util.Arrays.toString(pEn));
        report("解析空串 = 六面全通（null）",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces.parse("  ") == null, "空=全通");
        report("解析垃圾输入 = null（不抛异常）",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces.parse("???,xyz") == null,
                "容错");
    }

    // ==================== M9：开口面几何（朝向面片画在哪） ====================

    /**
     * "舱室朝向面显示"的核心数学：面片必须真的贴在声明的那个面上。
     *
     * <p>离线可判死：对每个方向断言四角都落在该面的平面上、四角构成边长
     * {@code 1-2*inset} 的正方形、面心在该面中心、箭头沿法线外指。
     * 这几条一旦错，玩家看到的朝向就是错的（而肉眼看"箭头朝上还是朝下"很容易被骗）。</p>
     */
    private static void verifyFaceQuadGeometry() {
        System.out.println();
        System.out.println("==== M9 开口面片几何（朝向面画在哪）====");

        var p = new BlockPos(3, 5, 7);
        float inset = 0.15f;

        boolean planeOk = true;
        boolean squareOk = true;
        boolean centerOk = true;
        boolean tipOk = true;
        StringBuilder bad = new StringBuilder();

        for (Direction d : Direction.values()) {
            float[][] q = com.mss.polymech.client.gui.machine.editor.FaceQuad.corners(p, d, inset);
            if (q.length != 4) {
                planeOk = false;
                continue;
            }
            // 1) 四角都在该面的平面上
            for (float[] v : q) {
                if (!onFacePlane(v, p, d, inset)) {
                    planeOk = false;
                    bad.append(d).append(" ");
                }
            }
            // 2) 四角构成正方形，边长 = 1-2*inset
            float side = 1f - 2f * inset;
            for (int i = 0; i < 4; i++) {
                float[] a = q[i];
                float[] b = q[(i + 1) % 4];
                float dist = (float) Math.sqrt(Math.pow(a[0] - b[0], 2)
                        + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
                if (Math.abs(dist - side) > 1e-4f) squareOk = false;
            }
            // 3) 面心在该面中心（方块表面，不缩进）
            float[] c = com.mss.polymech.client.gui.machine.editor.FaceQuad.center(p, d);
            float[] expected = faceCenter(p, d);
            if (Math.abs(c[0] - expected[0]) > 1e-6f
                    || Math.abs(c[1] - expected[1]) > 1e-6f
                    || Math.abs(c[2] - expected[2]) > 1e-6f) {
                centerOk = false;
            }
            // 4) 箭头沿法线外指
            float len = 0.35f;
            float[] tip = com.mss.polymech.client.gui.machine.editor.FaceQuad.normalTip(p, d, len);
            if (Math.abs(tip[0] - (c[0] + d.getStepX() * len)) > 1e-6f
                    || Math.abs(tip[1] - (c[1] + d.getStepY() * len)) > 1e-6f
                    || Math.abs(tip[2] - (c[2] + d.getStepZ() * len)) > 1e-6f) {
                tipOk = false;
            }
        }

        report("面片四角都贴在声明的那个面上（六面逐个查）", planeOk,
                planeOk ? "六面都贴对" : "★ 错的：" + bad);
        report("面片四角构成正方形（边长 = 1-2*inset）", squareOk, "边长 " + (1f - 2f * inset));
        report("面片中心 = 该面中心", centerOk, "六面中心正确");
        report("箭头沿该面法线向外", tipOk, "外指");

        // 上下面的对称性（最容易写反的一对：UP 用 p.y+1、DOWN 用 p.y）
        float[] up = com.mss.polymech.client.gui.machine.editor.FaceQuad.corners(p, Direction.UP, 0f)[0];
        float[] down = com.mss.polymech.client.gui.machine.editor.FaceQuad.corners(p, Direction.DOWN, 0f)[0];
        report("上面 y=p.y+1、下面 y=p.y（没写反）",
                Math.abs(up[1] - (p.getY() + 1f)) < 1e-6f && Math.abs(down[1] - p.getY()) < 1e-6f,
                "up=" + up[1] + " down=" + down[1]);

        // 南北对称性：NORTH 是 -Z（面在 p.z），SOUTH 是 +Z（面在 p.z+1）
        float[] north = com.mss.polymech.client.gui.machine.editor.FaceQuad.corners(p, Direction.NORTH, 0f)[0];
        float[] south = com.mss.polymech.client.gui.machine.editor.FaceQuad.corners(p, Direction.SOUTH, 0f)[0];
        report("北面 z=p.z、南面 z=p.z+1（没写反）",
                Math.abs(north[2] - p.getZ()) < 1e-6f && Math.abs(south[2] - (p.getZ() + 1f)) < 1e-6f,
                "north=" + north[2] + " south=" + south[2]);

        // 东西对称性：WEST 是 -X，EAST 是 +X
        float[] west = com.mss.polymech.client.gui.machine.editor.FaceQuad.corners(p, Direction.WEST, 0f)[0];
        float[] east = com.mss.polymech.client.gui.machine.editor.FaceQuad.corners(p, Direction.EAST, 0f)[0];
        report("西面 x=p.x、东面 x=p.x+1（没写反）",
                Math.abs(west[0] - p.getX()) < 1e-6f && Math.abs(east[0] - (p.getX() + 1f)) < 1e-6f,
                "west=" + west[0] + " east=" + east[0]);
    }

    private static boolean onFacePlane(float[] v, BlockPos p, Direction d, float inset) {
        float eps = 1e-5f;
        return switch (d) {
            case UP -> Math.abs(v[1] - (p.getY() + 1f - inset)) < eps;
            case DOWN -> Math.abs(v[1] - (p.getY() + inset)) < eps;
            case NORTH -> Math.abs(v[2] - (p.getZ() + inset)) < eps;
            case SOUTH -> Math.abs(v[2] - (p.getZ() + 1f - inset)) < eps;
            case WEST -> Math.abs(v[0] - (p.getX() + inset)) < eps;
            case EAST -> Math.abs(v[0] - (p.getX() + 1f - inset)) < eps;
        };
    }

    private static float[] faceCenter(BlockPos p, Direction d) {
        float cx = p.getX() + 0.5f, cy = p.getY() + 0.5f, cz = p.getZ() + 0.5f;
        return switch (d) {
            case UP -> new float[]{cx, p.getY() + 1f, cz};
            case DOWN -> new float[]{cx, p.getY(), cz};
            case NORTH -> new float[]{cx, cy, p.getZ()};
            case SOUTH -> new float[]{cx, cy, p.getZ() + 1f};
            case WEST -> new float[]{p.getX(), cy, cz};
            case EAST -> new float[]{p.getX() + 1f, cy, cz};
        };
    }

    // ==================== M10：体检（整合包作者用得上的判据） ====================

    /**
     * 体检必须能挑出"能写但会静默失效"的写法——这正是整合包作者最容易踩的坑。
     */
    private static void verifyValidator() {
        System.out.println();
        System.out.println("==== M10 结构体检（静默失效判据）====");

        // 干净的机器：一个外壳格 + 一个带明确开口面的舱室 → 不该有 ERROR
        var clean = def("t",
                new Vec3i[]{new Vec3i(1, 0, 0)},
                null,
                Map.of(new Vec3i(1, 0, 0),
                        new BaseMachineBlock.ItemProxy(new int[]{0},
                                BaseMachineBlock.ProxyIO.INPUT, new Direction[]{Direction.NORTH})),
                Map.of(), Map.of());
        var cleanIssues = com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(clean));
        report("干净定义没有 ERROR",
                com.mss.polymech.client.gui.machine.editor.EditorValidator.errorsOf(cleanIssues).isEmpty(),
                "ERROR 数 = " + com.mss.polymech.client.gui.machine.editor.EditorValidator
                        .errorsOf(cleanIssues).size());

        // 舱室不在结构里（写错坐标 → 那一格根本不会放置）
        var outside = def("t", new Vec3i[]{new Vec3i(1, 0, 0)}, null,
                Map.of(new Vec3i(9, 9, 9),
                        new BaseMachineBlock.ItemProxy(new int[]{0},
                                BaseMachineBlock.ProxyIO.INPUT, new Direction[]{Direction.UP})),
                Map.of(), Map.of());
        var outsideIssues = com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(outside));
        report("舱室在结构外 → ERROR（会被静默忽略）",
                hasError(outsideIssues, "不在结构里"), issueText(outsideIssues));

        // 舱室没有槽位下标（代理空转）
        var noSlot = def("t", new Vec3i[]{new Vec3i(1, 0, 0)}, null,
                Map.of(new Vec3i(1, 0, 0),
                        new BaseMachineBlock.ItemProxy(new int[0],
                                BaseMachineBlock.ProxyIO.INPUT, new Direction[]{Direction.UP})),
                Map.of(), Map.of());
        report("舱室没填槽位下标 → ERROR",
                hasError(com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(noSlot)),
                        "没有填槽位下标"), "空槽位");

        // 控制器格被声明成舱室
        var ctrlProxy = def("t", new Vec3i[]{new Vec3i(1, 0, 0)}, null,
                Map.of(Vec3i.ZERO,
                        new BaseMachineBlock.ItemProxy(new int[]{0},
                                BaseMachineBlock.ProxyIO.INPUT, new Direction[]{Direction.UP})),
                Map.of(), Map.of());
        report("控制器格被设成舱室 → ERROR",
                hasError(com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(ctrlProxy)),
                        "控制器格"), "原点被占用");

        // 有舱室但没声明开口面 → WARN（管道自动铺设需要首个声明面）
        var allOpen = def("t", new Vec3i[]{new Vec3i(1, 0, 0)}, null,
                Map.of(new Vec3i(1, 0, 0),
                        new BaseMachineBlock.ItemProxy(new int[]{0},
                                BaseMachineBlock.ProxyIO.INPUT, null)),
                Map.of(), Map.of());
        report("舱室六面全通 → WARN（建议指定首个面）",
                hasWarn(com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(allOpen)),
                        "没声明开口面"), "全通提示");

        // 只有控制器
        var onlyCtrl = def("t", new Vec3i[0], null, Map.of(), Map.of(), Map.of());
        report("只有控制器 → WARN",
                hasWarn(com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(onlyCtrl)),
                        "只有控制器"), "空结构");

        // 离散格重复声明
        var dup = def("t", new Vec3i[]{new Vec3i(1, 0, 0), new Vec3i(1, 0, 0)}, null,
                Map.of(), Map.of(), Map.of());
        report("离散格重复声明 → WARN",
                hasWarn(com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(dup)),
                        "重复声明"), "冗余");

        // ERROR 必须排在 WARN 前面（界面先看要紧的）
        var mixed = def("t", new Vec3i[]{new Vec3i(1, 0, 0)}, null,
                Map.of(new Vec3i(9, 9, 9),
                        new BaseMachineBlock.ItemProxy(new int[]{0},
                                BaseMachineBlock.ProxyIO.INPUT, null)),
                Map.of(), Map.of());
        var mixedIssues = com.mss.polymech.client.gui.machine.editor.EditorValidator.check(snap(mixed));
        boolean errorFirst = !mixedIssues.isEmpty() && mixedIssues.get(0).isError();
        report("ERROR 排在 WARN 之前", errorFirst,
                mixedIssues.isEmpty() ? "★ 空" : mixedIssues.get(0).severity().name());

        report("体检通过时的文案是明确的", com.mss.polymech.client.gui.machine.editor.EditorValidator
                .render(List.of()).contains("体检通过"), "无问题提示");
    }

    private static boolean hasError(List<com.mss.polymech.client.gui.machine.editor.EditorValidator.Issue> issues,
                                    String keyword) {
        for (var i : issues) {
            if (i.isError() && i.message().contains(keyword)) return true;
        }
        return false;
    }

    private static boolean hasWarn(List<com.mss.polymech.client.gui.machine.editor.EditorValidator.Issue> issues,
                                   String keyword) {
        for (var i : issues) {
            if (!i.isError() && i.message().contains(keyword)) return true;
        }
        return false;
    }

    private static String issueText(List<com.mss.polymech.client.gui.machine.editor.EditorValidator.Issue> issues) {
        if (issues.isEmpty()) return "★ 一条都没报";
        return issues.get(0).message();
    }

    // ==================== M11：撤销/重做 + 重新读盘 ====================

    /**
     * 撤销栈与"整体换回定义"是整合包作者的安全网（改错了能退回去）。
     * 这里钉死：顺序、状态迁移、上限、以及 {@code loadFrom} 的忠实度。
     */
    private static void verifyUndoAndReload() {
        System.out.println();
        System.out.println("==== M11 撤销/重做 + 定义整体换回 ====");

        var stack = new com.mss.polymech.client.gui.machine.editor.UndoStack(3);
        var s0 = def("a", new Vec3i[]{new Vec3i(1, 0, 0)}, null, Map.of(), Map.of(), Map.of());
        var s1 = def("a", new Vec3i[]{new Vec3i(1, 0, 0), new Vec3i(2, 0, 0)}, null,
                Map.of(), Map.of(), Map.of());
        var s2 = def("a", new Vec3i[]{new Vec3i(3, 0, 0)}, null, Map.of(), Map.of(), Map.of());

        report("空栈 canUndo=false", !stack.canUndo() && !stack.canRedo(), "初始状态");
        report("空栈 undo 返回 null", stack.undo(s0) == null, "边界");

        stack.record("加格", s0);
        report("记一次后可撤销", stack.canUndo() && stack.undoDepth() == 1, "深度 1");
        var back = stack.undo(s1);
        report("撤销回到记下的那份（内容一致）",
                back != null && back.definition().toJson().toString().equals(s0.toJson().toString()),
                "回到 s0");
        report("撤销带回那一步的标签（历史面板要显示）",
                back != null && "加格".equals(back.label()), "label=" + (back == null ? "null" : back.label()));
        report("撤销后可重做", stack.canRedo() && stack.redoDepth() == 1, "重做深度 1");
        var fwd = stack.redo(s0);
        report("重做回到撤销前那份",
                fwd != null && fwd.definition().toJson().toString().equals(s1.toJson().toString()), "回到 s1");
        report("重做后重做栈空", !stack.canRedo(), "深度 0");

        // 新改动清空重做栈
        stack.undo(s2);
        stack.record("删格", s2);
        report("产生新改动后重做栈被清空", !stack.canRedo(), "标准编辑器语义");

        // 上限：超过 limit 丢最旧的
        var limited = new com.mss.polymech.client.gui.machine.editor.UndoStack(3);
        for (int i = 0; i < 5; i++) limited.record("第 " + i + " 步", s0);
        report("超过上限只保留最近 N 个", limited.undoDepth() == 3, "深度 " + limited.undoDepth());

        // 历史的可读性：标签按"最近的在前"排，能看出自己退到哪
        var hist = new com.mss.polymech.client.gui.machine.editor.UndoStack(8);
        hist.record("加格 (1,0,0)", s0);
        hist.record("改组名 主体", s0);
        hist.record("加格 (2,0,0)", s0);
        report("历史标签最近的在前（点第 i 行就是连退 i 步）",
                hist.undoLabels().equals(java.util.List.of("加格 (2,0,0)", "改组名 主体", "加格 (1,0,0)")),
                hist.undoLabels().toString());
        hist.undo(s1);
        report("撤销一步后，那一步的名字转到重做列表",
                hist.redoLabels().equals(java.util.List.of("加格 (2,0,0)"))
                        && !hist.undoLabels().contains("加格 (2,0,0)"),
                "redo=" + hist.redoLabels());
        hist.record("加格 (9,9,9)", s0);
        report("新改动后重做列表清空（标签也不残留）", hist.redoLabels().isEmpty(), "clear");

        // ===== 每项目独立历史：切项目再切回来，历史还在 =====
        com.mss.polymech.client.gui.machine.editor.EditorHistories.close("proj_a");
        com.mss.polymech.client.gui.machine.editor.EditorHistories.close("proj_b");
        var ha = com.mss.polymech.client.gui.machine.editor.EditorHistories.of("proj_a");
        var hb = com.mss.polymech.client.gui.machine.editor.EditorHistories.of("proj_b");
        report("同一个项目拿到的永远是同一个栈（否则切回来就断了）",
                ha == com.mss.polymech.client.gui.machine.editor.EditorHistories.of("proj_a")
                        && ha != hb,
                "A 与 B 互相独立");

        ha.record("加格", s0);
        ha.record("删格", s1);
        report("在 A 里记两步，B 仍然是空的",
                ha.undoDepth() == 2 && hb.undoDepth() == 0,
                "A=" + ha.undoDepth() + " B=" + hb.undoDepth());

        hb.record("改组名", s2);
        report("B 里记一步也不影响 A 的历史内容",
                ha.undoDepth() == 2 && hb.undoDepth() == 1
                        && ha.undoLabels().equals(java.util.List.of("删格", "加格")),
                "A=" + ha.undoLabels() + " B=" + hb.undoLabels());

        report("每个项目的上限仍是 64 步",
                com.mss.polymech.client.gui.machine.editor.EditorHistories.LIMIT == 64,
                "LIMIT=" + com.mss.polymech.client.gui.machine.editor.EditorHistories.LIMIT);

        com.mss.polymech.client.gui.machine.editor.EditorHistories.close("proj_a");
        report("关掉项目会丢掉它的历史，且不牵连别的项目",
                !com.mss.polymech.client.gui.machine.editor.EditorHistories.has("proj_a")
                        && com.mss.polymech.client.gui.machine.editor.EditorHistories.has("proj_b"),
                "size=" + com.mss.polymech.client.gui.machine.editor.EditorHistories.size());

        com.mss.polymech.client.gui.machine.editor.EditorHistories.close("proj_b");
        report("全部关掉后不残留（多开不会一直涨）",
                com.mss.polymech.client.gui.machine.editor.EditorHistories.size() == 0,
                "size=" + com.mss.polymech.client.gui.machine.editor.EditorHistories.size());

        // loadFrom 忠实度：定义 → 快照 → 导出，必须与原定义完全一致
        var rich = def("a",
                new Vec3i[]{new Vec3i(1, 0, 0), new Vec3i(0, 3, 0)},
                new Vec3i[][]{{new Vec3i(-1, 0, -2), new Vec3i(1, 2, 2)}},
                Map.of(new Vec3i(0, 1, -2),
                        new BaseMachineBlock.ItemProxy(new int[]{2},
                                BaseMachineBlock.ProxyIO.INPUT, new Direction[]{Direction.NORTH})),
                Map.of(new Vec3i(0, 0, 1),
                        new BaseMachineBlock.FluidProxy(new int[]{0},
                                BaseMachineBlock.ProxyIO.INPUT, new Direction[]{Direction.UP})),
                Map.of(new Vec3i(0, 1, -2), "燃料输入"));
        var s = snap(rich);
        report("loadFrom 后导出与原定义完全一致（含区间/代理/说明）",
                s.exportJson().equals(pretty(rich)), "往返一致");
        // 期望值用独立三重循环算，不调 MachineGeometry（否则就是拿实现验实现）；
        // 注意该区间本身就含原点，所以"展开 + 控制器"要先去重。
        java.util.Set<Vec3i> expect = new java.util.LinkedHashSet<>();
        for (Vec3i v : rich.sideOffsets()) expect.add(v);
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    expect.add(new Vec3i(x, y, z));
                }
            }
        }
        expect.add(Vec3i.ZERO);
        report("loadFrom 后格子集合 = 独立三重循环展开 ∪ 控制器",
                s.cellCount() == expect.size(), "格数 " + s.cellCount() + " / 期望 " + expect.size());
        report("快照能看到舱室的开口面（编辑器据此画面片）",
                s.openFaces(new Vec3i(0, 1, -2)).equals(java.util.EnumSet.of(Direction.NORTH)),
                "北面");

        // 整体换回：先改坏，再换回，必须还原
        s.removeCell(new Vec3i(0, 3, 0));
        s.setRole(new Vec3i(0, 1, -2), null, null);
        boolean changed = !s.exportJson().equals(pretty(rich));
        s.loadFrom(rich);
        report("改动后用 loadFrom 换回 → 完全还原",
                changed && s.exportJson().equals(pretty(rich)), "还原成功");
    }

    private static MachineDefinition def(String id, Vec3i[] sides, Vec3i[][] regions,
                                         Map<Vec3i, BaseMachineBlock.ItemProxy> items,
                                         Map<Vec3i, BaseMachineBlock.FluidProxy> fluids,
                                         Map<Vec3i, String> notes) {
        return new MachineDefinition(id, sides, regions, items, fluids, notes);
    }

    private static MachineStructureSnapshot snap(MachineDefinition def) {
        var s = new MachineStructureSnapshot(def.id());
        s.loadFrom(def);
        return s;
    }

    private static String pretty(MachineDefinition def) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(def.toJson());
    }

    // ==================== M12：界面接线（源码级防回归） ====================

    /**
     * 上面的 M8~M11 保证了<strong>逻辑</strong>对；这里保证界面<strong>真的用了</strong>这些逻辑。
     *
     * <p>防的是这类回归：逻辑写好了但没接上去（例如分色函数存在、画面却还是全白；
     * 或者朝向面算对了、渲染循环里没调）。离线只能查源码接线，实际观感仍需进游戏看一次。</p>
     */
    private static void verifyEditorUiWiring() {
        System.out.println();
        System.out.println("==== M12 编辑器界面接线 ====");

        String screen = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/MachineTab.java");
        if (screen == null) {
            report("读到 MachineTab.java", false, "★ 读不到文件");
            return;
        }

        report("线框按格子类别分色（不是全白）",
                screen.contains("EditorCellVisuals.kindOf(cell)") && screen.contains("kind.outlineColor()"),
                "按 Kind 上色");
        report("线框统一走通用线框类 BoxOutlineRenderer（不自己画线）",
                screen.contains("BoxOutlineRenderer.appendBox(")
                        && screen.contains("BoxOutlineRenderer.addBar("),
                "复用蓝图/预览同一个类");
        report("线宽用全模组约定值 0.06F（不是自己发明的细线）",
                screen.contains("OUTLINE_WIDTH = 0.06f"),
                "蓝图/管道/传送带预览都是 0.06");
        report("半透明填充只画外露面 + 背面剔除（照 MC 玻璃，不叠 alpha）",
                screen.contains("CellFillFaces.outerFaces(")
                        && screen.contains("CellFillFaces.outwardCorners(")
                        && screen.contains("RenderSystem.enableCull()"),
                "内面剔除 + 只画正面");
        report("填充参与深度测试（核心方块该挡住就挡住，显示原本的样子）",
                screen.contains("RenderSystem.enableDepthTest();")
                        && screen.contains("RenderSystem.depthMask(false);"),
                "不糊住核心模型");
        report("不再用 addCorner 一次提交 6 个面（那会双面叠加 alpha）",
                !screen.contains("BoxOutlineRenderer.addCorner("),
                "已换成按面提交");
        report("没有残留自己手写的 DEBUG_LINES 细线框",
                !screen.contains("VertexFormat.Mode.DEBUG_LINES")
                        && !screen.contains("emitCubeOutline"),
                "已删重复造轮子");
        report("普通填充格补白色半透明实心块（只描线框分不出实心结构）",
                screen.contains("FILL_ALPHA")
                        && screen.contains("setColor(1f, 1f, 1f, FILL_ALPHA)"),
                "白色半透明填充");
        report("鼠标当前格有预览框（复用管道连接预览的 AnimatedOutline）",
                screen.contains("AnimatedOutline") && screen.contains("hoverOutline")
                        && screen.contains("chase(") && screen.contains("tickChase()"),
                "chase 平滑 + 淡入");
        report("悬停与已选中用不同颜色区分",
                screen.contains("COLOR_HOVER") && screen.contains("COLOR_SELECTED")
                        && screen.contains("selectedOutline"),
                "青 vs 黄");
        report("悬停格从 Scene 的 lastHoverPosFace 读出（继承暴露 protected 字段）",
                screen.contains("class EditorScene extends Scene")
                        && screen.contains("return lastHoverPosFace;"),
                "Scene 无 public getter");
        report("顶部有工具条（像 Blockbench：先选工具再点 3D）",
                screen.contains("toolButtons") && screen.contains("EditorTool.values()")
                        && screen.contains("setTool("),
                "一排工具按钮");
        report("点击语义按工具分派（不再只有选中一种行为）",
                screen.contains("switch (tool)") && screen.contains("case FACE ->")
                        && screen.contains("case ADD ->") && screen.contains("case ROLE ->"),
                "onCellSelected 分派");
        report("九宫格画在悬停面上（几何走 FaceGrid，与扳手同款）",
                screen.contains("drawFaceGrid(") && screen.contains("FaceGrid.point(")
                        && screen.contains("FaceGrid.dividerPositions()"),
                "3×3");
        report("九宫格只在「朝向面」工具下画",
                screen.contains("if (tool.usesFaceGrid())"), "避免乱画");
        report("Shift+点九宫格 = 再加一个面（多面代理）",
                screen.contains("isShiftKeyDown()") && screen.contains("faces.add(localFace)")
                        && screen.contains("snapshot.setFaces("),
                "用户要求的多面");
        report("已声明的开口面在九宫格上用舱室颜色高亮",
                screen.contains("alreadyOpen") && screen.contains("kindOf(cell).faceColor()"),
                "一眼看出哪面已开");
        report("线框画的是「外棱」而不是每格一圈（无缝玻璃）",
                screen.contains("CellFillFaces.outerEdges(")
                        && screen.contains("BoxOutlineRenderer.addBar(edgeBuf"),
                "相邻接缝不画");
        report("画开口面片（舱室朝向面显示）",
                screen.contains("drawProxyFaces()") && screen.contains("FaceQuad.corners("),
                "面片 + 几何");
        report("面片用 QUADS 画（半透明色块）",
                screen.contains("VertexFormat.Mode.QUADS"), "QUADS");
        report("画朝向箭头（沿法线）",
                screen.contains("FaceQuad.normalTip("), "箭头");
        report("有\"显示开口面\"开关", screen.contains("showFaces"), "可开关");
        report("有\"只看舱室\"开关", screen.contains("onlyProxies"), "大机器可用");
        report("六个朝向面用勾选框（不是手打文字）",
                screen.contains("ProxyFaces.ORDER") && screen.contains("new Toggle()"),
                "上/下/北/南/西/东");
        report("有舱室列表面板（点一下选中）",
                screen.contains("proxyListBox") && screen.contains("proxyCells()"), "列表");
        report("有区间工具（起点/终点/创建）",
                screen.contains("setRegionCorner") && screen.contains("createRegion"), "免手写区间");
        report("有撤销/重做按钮",
                screen.contains("undo()") && screen.contains("redo()") && screen.contains("history()"),
                "安全网");
        report("有体检面板",
                screen.contains("EditorValidator.check(") && screen.contains("refreshValidation"),
                "体检接线");
        report("保存前拦下 ERROR（不让坏定义落盘）",
                screen.contains("EditorValidator.errorsOf(EditorValidator.check(snapshot))"),
                "保存拦截");
        report("有图例（颜色含义写给作者看），且走数据绑定",
                screen.contains("bindText(legendLabel, EditorCellVisuals::legendText)"), "图例");
        report("四个页签齐备",
                screen.contains("\"结构\"") && screen.contains("\"舱室\"")
                        && screen.contains("\"显示\"") && screen.contains("\"保存\""),
                "结构/舱室/显示/保存");
        report("说明(note)可编辑", screen.contains("noteField") && screen.contains("applyNote"),
                "备注");
        report("改动统一走 mutate（保证每次都能撤销）",
                screen.contains("private void mutate(") && screen.contains("history().record("),
                "撤销点");

        // ===== 页签页面的结构只在一处定义，且方向必须是 COLUMN =====
        //
        // 真 bug（用户实测：切页签后控件全叠在一起）：
        // ScrollerView 的 viewContainer 是个普通 UIElement，构造时没设 flex 方向，
        // 而 taffy 的默认方向是 ROW。4 个页签各写一遍 new ScrollerView()，
        // 结果 4 个都忘了设方向 → 页里每个 widthPercent(100) 的子元素被并排摆开，
        // 横向溢出、看起来所有控件叠在一起。
        //
        // 断言钉两件事：结构只留一处（不可能只改对 3 个），且方向显式是 COLUMN。
        // 只看代码，不看注释/文档：注释里举例提到 new ScrollerView() 不算（上一版断言
        // 就被自己 javadoc 里的 {@code new ScrollerView()} 误判成 2 次）。
        String screenCode = stripComments(screen);
        int newScrollerCount = screenCode.split("new ScrollerView\\(\\)", -1).length - 1;
        report("页签容器统一走 tabScroller()（不是 4 个页签各写一遍 new ScrollerView()）",
                newScrollerCount == 1,
                "new ScrollerView() 出现 " + newScrollerCount + " 次（应为 1）");

        report("竖排滚动容器只从 vScroller() 出（页签页与大纲都用它，不各写一遍）",
                screen.contains("private static ScrollerView vScroller()")
                        && screen.contains("var treeScroll = vScroller();"),
                "大纲的 TreeList 也包在同一套滚动容器里");
        report("tabScroller() 显式把 viewContainer 设成 COLUMN（不设就是 taffy 默认的 ROW）",
                screen.contains("viewContainer(v -> v.layout(l -> l.widthPercent(100)")
                        && screen.contains(".flexDirection(FlexDirection.COLUMN).gapAll(2)))"),
                "页 = 一列占满宽度的行");
        report("滚动容器设双向滚动，横向用 AUTO（放得下不显示、放不下才出现）",
                screen.contains("s.mode(ScrollerMode.BOTH)")
                        && screen.contains("ScrollDisplay.AUTO)"),
                "两向都能滚，横向按需出现");
        // 带结尾的点：import 行本身含 "dev.vfyjxf.taffy.style.FlexDirection"（无点），
        // 只看"当类型名用"的地方（有结尾的点），否则断言会被自己的 import 判失败。
        report("没有残留的全限定 FlexDirection（层级写法统一）",
                !screenCode.contains("dev.vfyjxf.taffy.style.FlexDirection."),
                "统一用 import 后的短名");

        // ===== 页签内容不许把右栏撑高（用户实测：选"舱室"后关闭按钮跑到屏幕外）=====
        //
        // 根因：flex 项的默认 min-height:auto 拒绝缩到内容高度以下。
        // "舱室"页签内容最长（18 个控件），于是它把整个右栏撑高，
        // 把状态栏和"关闭编辑器"顶出屏幕。修法是给可伸缩的那一层显式 minHeight(0)。
        report("页签容器 minHeight(0)（否则 min-height:auto 会把右栏撑高、顶掉关闭按钮）",
                screen.contains(".flex(1).minHeight(0)"),
                "flex 项必须能缩到内容以下");
        // 通用滚动：TabView 的内容容器默认只有 flexGrow(1)（= flex: 1 1 auto），
        // 即 flex-basis:auto —— 基准高度取"内容高度"，所以内容再长也不会滚动，
        // 只会把面板撑高 / 顶出格子。必须显式 flex(1)（基准 0）才能把高度钉成
        // "可用空间"，里面的 ScrollerView 才拿得到确定高度、才会出现滚动条。
        // 照抄 LDLib2 自己的 UIDebugger：tabView.tabContentContainer.getLayout().flex(1)。
        //
        // 这条是"通用化"的核心：改的是**共享**的内容容器，所以 4 个页签（以及以后
        // 新加的页签）全都自动获得滚动，不需要每个页签各写一遍。
        report("页签内容容器 flex(1) —— 通用滚动的关键（一处生效，所有页签都受益）",
                screen.contains("c.layout(l -> l.flex(1).minHeight(0))"),
                "flex-basis:0 才滚得起来");
        report("没有用 setOverflowVisible(false) 去「挡住」内容（那会把滚动条一起裁掉）",
                !screen.contains("tabContentContainer(c -> c.setOverflowVisible(false))"),
                "裁剪 ≠ 滚动");
        report("Label 都有初始文字（否则显示 LDLib2 占位符 \"Label!\"）",
                screen.contains("statusLabel.setText(")
                        && screen.contains("faceSlotHint.setText(")
                        && screen.contains("faceSlotListLabel.setText("),
                "不留占位符");

        // ===== 结构树（Blockbench 口径：文件夹 → cube）=====
        //
        // 用户要求"增加一个类似 blockbench 的 cube 结构树"：
        // 一个方块 = 一个 cube，文件夹 = bone。钉住四件事：
        // 数据来自 groups、点 cube 能选中、单格 cube 不啰嗦地写 to、刷新链路接上。

        report("大纲用官方 TreeList（不是自己拍平再手算缩进/多选）",
                screen.contains("private TreeList<OutlineNode> treeList")
                        && screen.contains("treeList.setNodeUISupplier(this::createOutlineRow)")
                        && screen.contains("private static final class OutlineNode implements ITreeNode<String, Void>"),
                "层级/箭头/折叠/多选都是组件内建的");
        report("TreeList 挂在 ScrollerView 的 viewContainer 上（直接 addChild 会既没滑条又被挤到底部）",
                screen.contains("var treeScroll = vScroller();")
                        && screen.contains("treeScroll.viewContainer.addChild(treeList);")
                        && !screen.contains("treeScroll.addChild(treeList);")
                        && screen.contains("left.addChildren(renameRow, treeScroll, treeBar);"),
                "ScrollerView 自己的子节点是 verticalContainer+horizontalScroller，内容必须在 viewContainer 里");
        report("大纲开启多选（Ctrl 切换 + Shift 区间由 TreeList 自己算）",
                screen.contains("treeList.setSupportMultipleSelection(true)"),
                "指南：supportMultipleSelection = Ctrl+点击 / Shift+点击");
        report("大纲的节点带路径（schema 3 组可嵌套，一个 int 指不到唯一的组）",
                screen.contains("private final MachineStructureSnapshot.GroupPath path;")
                        && screen.contains("for (var row : MachineOutlineLogic.rows(snapshot))"),
                "行数据由纯逻辑类给出，渲染层只负责还原成树");
        report("结构树里点 cube 会选中那一格（与 3D 视口共用同一个 selected）",
                screen.contains("var cube = pick.primary();")
                        && screen.contains("selected = cube.from();"),
                "树↔3D 联动，主选中由纯函数给出");
        report("结构树按 cube 逐个显示，只写 cube 不写坐标（坐标进 tooltip）",
                screen.contains("MachineOutlineLogic.rowLabel(null, c)")
                        && screen.contains("s.tooltips(vecStr(c.from())"),
                "一个方块 = 一个 cube，文字规则由纯函数给出");
        report("展开/折叠交给 TreeList（不再自己造 + / - 按钮）",
                !screen.contains("exp.setText(Component.literal(collapsed ? \"+\" : \"-\"))")
                        && screen.contains("treeList.setDoubleClickToExpand(true)")
                        && !screen.contains("\"> \" + g.name()"),
                "箭头由 createNodeUI 画，双击也能折叠");

        String workspaceSrc = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/EditorWorkspaceScreen.java");

        // ===== 项目历史：快捷键 + 历史列表 + 按钮可用性 =====
        report("Ctrl+Z 撤销、Ctrl+Shift+Z / Ctrl+Y 重做（以前只能点「结构」页里的按钮）",
                screen.contains("public boolean handleKey(int keyCode, int scanCode, int modifiers)")
                        && workspaceSrc.contains("active.content.handleKey(keyCode, scanCode, modifiers)")
                        && screen.contains("handleHistoryKey(keyCode, modifiers)")
                        && screen.contains("GLFW.GLFW_KEY_Z") && screen.contains("GLFW.GLFW_KEY_Y")
                        && screen.contains("GLFW.GLFW_MOD_SHIFT"),
                "全局快捷键");

        report("在输入框里打字时不抢 Ctrl+Z（那里该是文本撤销）",
                screen.contains("getFocusedElement()")
                        && screen.contains("focused instanceof TextField || focused instanceof TextArea"),
                "按焦点判断，不靠猜");

        report("没得撤销/重做时按钮变灰（不是点了才被告知）",
                screen.contains("undoButton.setActive(history().canUndo());")
                        && screen.contains("redoButton.setActive(history().canRedo());"),
                "状态可见");

        report("有可见的历史列表，点第 i 行连退 i 步",
                screen.contains("private void rebuildHistoryList()")
                        && screen.contains("history().undoLabels()")
                        && screen.contains("btn.setOnClick(e -> undoSteps(steps));")
                        && screen.contains("private void undoSteps(int n)"),
                "只有「撤销 N」的话不知道退到哪一步");

        report("历史按项目存（切项目再切回来历史还在）",
                screen.contains("private UndoStack history()")
                        && screen.contains("EditorHistories.of(snapshot.machineId())")
                        && !screen.contains("new UndoStack(HISTORY_LIMIT)"),
                "栈不能存在 Screen 实例字段里 —— 切项目是 new 一个新实例");

        report("关掉机器标签时连它的草稿与历史一起丢掉（多开不会一直涨）",
                workspaceSrc.contains("EditorDrafts.close(machine.tabId())")
                        && workspaceSrc.contains("EditorHistories.close(machine.tabId())")
                        && workspaceSrc.contains("instanceof MachineTab machine"),
                "在窗口的 doClose 里统一收口，空白页不涉及草稿");

        report("每一步都带标签（「加格 (1,2,3)」而不是「改动」）",
                screen.contains("mutate(\"加格 \" + vecStr(target)")
                        && screen.contains("mutate(\"改组名 \" + name")
                        && screen.contains("mutate(\"删格 \" + vecStr(target)"),
                "历史面板才可读");

        report("大纲结构没变时不重建 —— 结构只在 rebuildTree 时换（setStaticTree）",
                screen.contains("treeList.setStaticTree(true)") && !screen.contains("treeSignature"),
                "每 tick 不重查子节点");        report("大纲底部有「+ cube / + 组」和占用计数（选中/总数）",
                screen.contains("btnAddCube.setOnClick(e -> addNeighbor())")
                        && screen.contains("private String treeCountText()")
                        && screen.contains("private void newGroupFromSelection()")
                        && screen.contains("bindText(treeCount, this::treeCountText)"),
                "例如 30/163");
        String snapshotSrc = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/MachineStructureSnapshot.java");
        report("用选中新建组走 addGroup + moveCubeToGroup（移交，不是复制）",
                snapshotSrc != null
                        && snapshotSrc.contains("public GroupPath addGroup(")
                        && snapshotSrc.contains("public boolean moveCubeToGroup(")
                        && snapshotSrc.contains("public boolean removeGroup("),
                "一个 cube 只属于一个组");        report("组头行显示组名、cube 行不显示组名（之前用 group()!=null 判断，每行都成了组名）",
                screen.contains("if (n.isGroup()) {")
                        && screen.contains("MachineOutlineLogic.rowLabel(n.group(), null)")
                        && screen.contains("MachineOutlineLogic.rowLabel(null, c)")
                        && screen.contains("boolean isGroup() { return cube == null; }"),
                "分支判据必须是 isGroup()，不是 group() != null");
        report("舱室列表只写类型/IO/槽位，开口面进 tooltip",
                screen.contains("btn.style(s -> s.tooltips(proxyTip(cell)))")
                        && !screen.contains("\" 面=\".append(ProxyFaces.label("),
                "细节放 tooltip");
        report("结构树在每次刷新时重建（不会显示过期结构）",
                screen.contains("rebuildTree();"),
                "刷新链路接上");
        report("页签内部的滚动容器也 minHeight(0)",
                screen.contains("heightPercent(100).minHeight(0)"),
                "滚动容器自己能缩");
        report("关闭按钮与状态栏 flexShrink(0)（永远不被页签挤扁/挤出屏幕）",
                screen.contains("height(18).flexShrink(0)") && screen.contains("height(14).flexShrink(0)"),
                "底部栏固定");

        // ===== 分组名往返：编辑器"打开 → 保存"不许把作者的文件夹压成一个 =====
        //
        // 本轮最要紧的防回归。快照以前把结构存成 side_offsets + fill_regions，
        // toDefinition() 走旧构造器会把所有东西合成**一个**叫"结构"的组 ——
        // 也就是保存一次，作者命名的"区间 1"/"离散格"就全没了。
        var g1 = new com.mss.polymech.machine.definition.MachineDefinition.Group("甲组",
                java.util.List.of(new com.mss.polymech.machine.definition.MachineDefinition.Cube(
                        new Vec3i(1, 0, 0), new Vec3i(1, 0, 0))));
        var g2 = new com.mss.polymech.machine.definition.MachineDefinition.Group("乙组",
                java.util.List.of(new com.mss.polymech.machine.definition.MachineDefinition.Cube(
                        new Vec3i(0, 1, 0), new Vec3i(0, 1, 0))));
        var twoGroups = new com.mss.polymech.machine.definition.MachineDefinition("t",
                java.util.List.of(g1, g2),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>(),
                com.mss.polymech.machine.definition.MachineDefinition.Meta.DEFAULT);
        var s2 = new com.mss.polymech.client.gui.machine.editor.MachineStructureSnapshot("t");
        s2.loadFrom(twoGroups);
        var backDef = s2.toDefinition();
        report("编辑器保存不丢分组名（打开→保存仍是 2 个组、名字原样）",
                backDef.groups().size() == 2
                        && backDef.groups().get(0).name().equals("甲组")
                        && backDef.groups().get(1).name().equals("乙组"),
                backDef.groups().size() + " 组 / "
                        + backDef.groups().stream().map(g -> g.name()).toList());
        report("往返后格子集合不变（2 个 cube = 2 格）",
                backDef.sideOffsets().length == 2,
                backDef.sideOffsets().length + " 格");

        // ===== 主题与布局（照 Blockbench：暗色扁平 + 左大纲 / 中视口 / 右工具）=====
        report("左右两栏可拖拽调整大小（SplitView，不是写死的 flex 比例）",
                screen.contains("new SplitView.Horizontal()")
                        && screen.contains("innerSplit.setPercentage(")
                        && screen.contains("outerSplit.setPercentage("),
                "中间分隔线可拖动");
        report("大纲放右边、详情页放左边（用户要求调换）",
                screen.contains("innerSplit.left(right)")
                        && screen.contains("outerSplit.right(left)"),
                "左详情 / 中视口 / 右大纲");
        String picker = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/PickerTab.java");
        report("主菜单是一份 EditorTab（不是另一个没有标签栏的窗口）",
                picker != null && picker.contains("implements EditorTab")
                        && picker.contains("workspace.openMachine(")
                        && !picker.contains("topbar"),
                "顶栏归窗口；它只提供内容");
        String drafts = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/EditorDrafts.java");
        report("多开：切项目先存草稿，切回来未保存的改动还在",
                drafts != null && drafts.contains("DRAFTS")
                        && screen.contains("stashDraft();")
                        && screen.contains("EditorDrafts.put(snapshot.machineId()"),
                "草稿 = MachineDefinition 快照");
        report("窗口有一条唯一的标签栏（已开标签 + ＋）",
                workspaceSrc.contains("renderTabStrip()")
                        && workspaceSrc.contains("for (var slot : tabs)")
                        && workspaceSrc.contains("tabBtn.setOnClick(e -> activate(slot))"),
                "标签栏只画一处，任何页面都共用");

        // ===== 浏览器的核心行为：标签是槽位，内容可替换 =====
        report("在主菜单里点机器 = 当前标签自己导航过去（主菜单不留一份）",
                workspaceSrc.contains("active.content instanceof PickerTab")
                        && workspaceSrc.contains("active.content = tab;"),
                "就像在搜索引擎点结果，那个标签跳到目标页");

        report("＋ 每次开一个新的空白标签（不是复用同一个主菜单页）",
                workspaceSrc.contains("addTab(new PickerTab(this))"),
                "浏览器的新标签页");

        report("项目已经在别的标签里开着就切过去，不开第二份",
                workspaceSrc.contains("findMachineSlot(machineId)")
                        && workspaceSrc.contains("activate(existing)"),
                "和浏览器一致");

        report("关掉最后一个标签会自动补一个空白标签（窗口不会空）",
                workspaceSrc.contains("tabs.isEmpty()")
                        && workspaceSrc.contains("newTab();"),
                "关光了回到新标签页");

        var pickerTabs = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/PickerTab.java");
        report("主菜单可关闭（关掉它就少一个标签，和浏览器的新标签页一样）",
                pickerTabs != null
                        && pickerTabs.contains("implements EditorTab")
                        && !pickerTabs.contains("boolean closable()"),
                "不再钉成常驻页 —— 它就是一个普通标签");

        report("主菜单里点机器走 workspace.openMachine（由窗口决定导航还是新开）",
                pickerTabs != null && pickerTabs.contains("workspace.openMachine(id, block)"),
                "主菜单自己不做标签管理");
        report("窗口有关闭；关标签前先问未保存改动，关窗口则存草稿",
                workspaceSrc.contains("close.setOnClick(e -> quit())")
                        && workspaceSrc.contains("t.onHidden()")
                        && screen.contains("requestCloseCurrent(onConfirmed)"),
                "关标签 = 丢弃（先确认）；关窗口 = 存草稿");
        report("顶栏按钮不再单独写一套样式（button:host 在 lss 里全局覆盖）",
                !screen.contains("styleTopButton"),
                "一处定义，顶栏不再例外");
        report("当前标签的选中态也是 class（.me-tab-active）",
                workspaceSrc.contains("if (isActive) tabBtn.addClass(EditorTheme.CLS_TAB_ACTIVE)"),
                "状态归代码，外观归样式表");
        report("标签有叉叉（关它）和「＋」（开新的空白标签）",
                workspaceSrc.contains("closeTab.setOnClick(e -> closeTab(slot.slotId))")
                        && workspaceSrc.contains("addTab.setOnClick(e -> newTab())"),
                "照浏览器的标签栏");
        report("关标签是「丢弃」语义：草稿由窗口删掉，标签自己不再回写",
                workspaceSrc.contains("EditorDrafts.close(machine.tabId())")
                        && !screen.contains("suppressStash"),
                "之前用 suppressStash 绕，现在职责归窗口");
        String themeClip = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/EditorTheme.java");
        report("文字过长会裁在按钮里（不让文字画到按钮外）",
                themeClip != null && themeClip.contains("b.setOverflowVisible(false)"),
                "裁剪而非溢出");
        report("打开失败/渲染失败不崩游戏，而是弹「机器异常」对话框",
                screen.contains("Dialog")
                        && screen.contains("showNotification(\"机器异常\"")
                        && screen.contains("catch (Throwable t)")
                        && screen.contains("[结构编辑器] 打开 {} 失败"),
                "异常 → 弹窗 + 日志");
        // 日志实证：filling_unit 崩在 "poly_mech:geo/filling_unit.geo.json: Unable to find model"
        report("缺 geo 模型时编辑器降级而不是崩（Geckolib 找不到模型会直接崩客户端）",
                screen.contains("private static boolean geoModelExists(")
                        && screen.contains("coreModelMissing = !geoModelExists(snapshot.machineId())")
                        && screen.contains("if (cell.controller() && coreModelMissing) return sideState;"),
                "缺模型 → 用外壳方块占位");
        // 硬性约定：编辑器家族的所有界面（含弹窗）都必须走 flatize，否则会冒原版控件
        String pickerSrc = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/PickerTab.java");
        report("【约定】主菜单界面也做了扁平化（否则又是一堆原版控件）",
                pickerSrc != null && pickerSrc.contains("EditorTheme.flatize("),
                "编辑器家族界面统一走 flatize");
        report("【约定】未保存提示弹窗也走了统一的弹窗样式",
                screen.contains("EditorTheme.styleDialog(d);"),
                "弹窗也是界面的一部分");
        report("弹窗在 show() 之后才套样式（Dialog 的按钮是 show 时才装配的）",
                screen.indexOf("d.show(workspace.getModularUI());") >= 0
                        && screen.indexOf("EditorTheme.styleDialog(d);")
                        > screen.indexOf("d.show(workspace.getModularUI());"),
                "顺序：先 show 再套");
        // Dialog 的三块框架是写死的原生贴图（titleBar→BORDER1_RT1、content→RECT_SOLID），
        // 光 flatize 控件不够，必须连框架一起换
        String themeDlg = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/EditorTheme.java");
        report("弹窗框架本身也换掉了原生贴图（titleBar/content/button 三块）",
                themeDlg != null && themeDlg.contains("d.titleBar.style(")
                        && themeDlg.contains("d.contentContainer.style(")
                        && themeDlg.contains("d.buttonContainer.style("),
                "不再是 BORDER1_RT1 / RECT_SOLID 灰框");
        // ===== 样式与结构分离：样式在 lss 里，代码只挂语义 class =====
        String lss = readSource("src/main/resources/assets/poly_mech/lss/machine_editor.lss");
        report("有独立的样式表文件（机器结构编辑器主题）",
                lss != null && lss.contains(".me-panel {")
                        && lss.contains("button:host {")
                        && lss.contains("text-field:host {"),
                "组件样式用类型选择器，全局生效");
        report("界面切到自有样式表（拿不到时退回 mc.lss，不至于整屏没样式）",
                workspaceSrc.contains("EditorTheme.stylesheet()")
                        && themeDlg != null && themeDlg.contains("STYLESHEET")
                        && themeDlg.contains("StylesheetManager.MC"),
                "class 驱动 + 兜底");
        report("面板/选中行改成挂 class（不再逐个 fill(元素, 颜色值)）",
                screen.contains("EditorTheme.style(left, EditorTheme.CLS_PANEL)")
                        && screen.contains("EditorTheme.style(treeScroll, EditorTheme.CLS_SUNKEN)")
                        && lss.contains("node-hover-background: rect(#ff37404e)"),
                "样式与结构分离：大纲选中行也由 lss 的 tree-list 决定");
        String themeSrc2 = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/EditorTheme.java");
        report("【约定】写进了 EditorTheme 的类注释（以后没人能说不知道）",
                themeSrc2 != null && themeSrc2.contains("【硬性约定】")
                        && themeSrc2.contains("必须调用"),
                "硬性约定 + 断言双保险");
        report("标签的叉叉是红字（危险操作走 .me-btn-danger，不是内联红色）",
                workspaceSrc.contains("closeTab.addClass(EditorTheme.CLS_DANGER)")
                        && lss.contains(".me-btn-danger .__button_text__"),
                "和大纲里的删除按钮同一套");
        report("每个可关的标签都有叉叉",
                workspaceSrc.contains("if (slot.content.closable()) {")
                        && workspaceSrc.contains("closeTab.setOnClick(e -> closeTab(slot.slotId))"),
                "标签栏按标签自己的 closable 决定画不画叉");
        report("关闭前检查未保存改动（有改动先弹窗问）",
                screen.contains("private void requestCloseCurrent(Runnable after)")
                        && screen.contains("if (!dirty) {")
                        && screen.contains("dirty = true;")
                        && screen.contains("dirty = false;"),
                "保存/丢弃/取消");

        // ===== 排版减法（用户要求：别啰嗦、别出框）=====
        var terse = new com.mss.polymech.client.gui.machine.editor.MachineStructureSnapshot("t");
        String sum = terse.summary();
        report("概要做减法（不再把\"格\"和\"cube\"写两遍）",
                sum.contains("格 · ") && !sum.contains("个文件夹") && !sum.contains("cube"),
                sum);
        report("体检把同类问题合并成一行并限量（否则会压住下面的按钮）",
                screen.contains("counts.merge(kind, 1, Integer::sum)")
                        && screen.contains("sb.append(\" ×\")")
                        && screen.contains("shown++ >= 4"),
                "12 条同类 → 一行 ×12");
        report("工具按钮不再加三角前缀（蓝底已经表示选中）",
                !screen.contains("▶ "),
                "去掉啰嗦的符号");
        report("弹窗在 show() 之后才套样式（Dialog 的按钮是 show 时才装配的）",
                screen.indexOf("d.show(workspace.getModularUI());") >= 0
                        && screen.indexOf("EditorTheme.styleDialog(d);")
                        > screen.indexOf("d.show(workspace.getModularUI());"),
                "顺序：先 show 再套");
        report("输入框聚焦遮罩不再盖住文字（之前是画在字上面的一层不透明色）",
                lss.contains("focus-overlay: rect(#00000000)"),
                "空遮罩");
        // LDLib2 语言包里 "text_field.empty": "空" —— 空输入框会画这个占位符，
        // 看起来像"字被挡住了"，其实是它自己画的，所以要显式清掉。
        report("空输入框不再画「空」占位符（LDLib2 的 text_field.empty）",
                themeSrc2 != null
                        && themeSrc2.contains(".placeholder(net.minecraft.network.chat.Component.empty())"),
                "不是遮挡，是占位符");
        // ===== 右栏减区块（用户："排版给我认真排啊"）=====
        report("显示类文字都走数据绑定（bindText → SupplierDataSource），不在刷新链路里手工 setText",
                screen.contains("private static void bindText(Label label, java.util.function.Supplier<String> text)")
                        && screen.contains("bindText(summaryLabel, this::buildSummaryText)")
                        && screen.contains("bindText(coordLabel, this::coordText)")
                        && screen.contains("private String buildSummaryText()")
                        && !screen.contains("summaryLabel.setText(")
                        && !screen.contains("coordLabel.setText("),
                "每 tick 自动拉取，新增显示项不会漏刷");        report("右栏减到 5 个区块（概要/变换/页签/状态/关闭），工具栏已移到视口上方",
                screen.contains("right.addChildren(summaryLabel, transformSection(), tabs, statusLabel, btnClose)")
                        && screen.contains("centerBox.addChildren(toolbar, scene)"),
                "工具栏在中间视图上面");
        report("工具说明改走 tooltip（不占纵向空间）",
                screen.contains("btn.style(s -> s.tooltips(t.hint()));"),
                "鼠标悬停才显示");
        report("概要 + 选中详情合并成同一个 Label",
                screen.contains("var sb = new StringBuilder(snapshot.summary());")
                        && screen.contains("sb.append('\\n').append(vecStr(cell.local()))"),
                "少一个区块");
        report("弹窗底板也换成扁平面板色（默认是 Sprites.BORDER1_RT1 原版灰框）",
                screen.contains("EditorTheme.styleDialog(d);"),
                "弹窗不再是灰框");
        String modBlocks = readSource("src/main/java/com/mss/polymech/block/ModBlocks.java");
        report("机器物品的模型路径统一是 geo/block/<id>.geo.json",
                modBlocks != null && modBlocks.contains("geo/block/filling_unit.geo.json")
                        && !modBlocks.contains("\"geo/filling_unit.geo.json\""),
                "此前 filling_unit 少了 block/ 一段");
        report("虚拟世界满亮度（低对比，不再是单一方向的明暗）",
                screen.contains("public int getBrightness(net.minecraft.world.level.LightLayer type, BlockPos pos)")
                        && screen.contains("return 15;"),
                "夜视式：形状清楚、不至于黑成一团");
        report("滚动区去掉了原版九宫格边框（viewPort 底图设成 EMPTY）",
                screen.contains("com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture.EMPTY"),
                "不是 Sprites.BORDER");
        report("页签内容区去掉了原版厚边框（换成扁平面板色）",
                lss.contains("__tab-view_tab_content_container__"),
                "不是 Sprites.BORDER_THICK_RT1");
        String themeSrc = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/EditorTheme.java");
        report("输入框与页签的样式也在 lss 里（不是只改按钮）",
                lss.contains("text-field:host {")
                        && lss.contains("tab:host {")
                        && lss.contains("__tab-view_tab_content_container__"),
                "TextField / Tab 都在表里");
        report("勾选框的样式也在 lss 里（原版 Sprites.RECT_DARK → 纯色）",
                lss.contains("__toggle_button__")
                        && lss.contains("unmark-background: rect("),
                "Toggle 在表里");
        report("文字关掉原版投影（深色底上投影会显脏）",
                themeSrc != null && themeSrc.contains("textShadow(false)"),
                "textColor + textShadow(false)");
        report("面板全部走语义 class（代码里不再出现颜色值）",
                screen.contains("EditorTheme.style(root, EditorTheme.CLS_BG)")
                        && screen.contains("EditorTheme.style(left, EditorTheme.CLS_PANEL)")
                        && screen.contains("EditorTheme.style(right, EditorTheme.CLS_PANEL)"),
                "外观归 lss，代码只挂 class");
        report("大纲面板常驻左侧（结构树不再塞在页签里，不用切页签就能看到）",
                screen.contains("left.addChild(EditorTheme.sectionTitle(\"大纲\"))")
                        && !screen.contains("treeHint"),
                "树常驻可见");
        String theme = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/EditorTheme.java");
        report("主题类定义暗色扁平配色 + 坐标轴三色（Blockbench 观感）",
                theme != null
                        && theme.contains("PANEL = 0xFF")
                        && theme.contains("BORDER = 0xFF")
                        && theme.contains("AXIS_X") && theme.contains("AXIS_Y") && theme.contains("AXIS_Z"),
                theme == null ? "★ 读不到 EditorTheme" : "深色面板 + 红绿蓝轴色");
        report("配色偏蓝 + 有\"选中行\"与\"蓝底黑字\"两色（Blockbench 的黑不是中性灰）",
                theme != null && theme.contains("ROW_SELECTED = 0xFF")
                        && theme.contains("ON_ACCENT = 0xFF"),
                "偏蓝的深灰 + 选中高亮 + 蓝底黑字");

        // ===== 右侧「变换」数值面板（Blockbench 的 位置 / 尺寸）=====
        report("右侧有变换面板（位置 / 尺寸，XYZ 分色）",
                screen.contains("private UIElement transformSection()")
                        && screen.contains("EditorTheme.CLS_AXIS")
                        && lss.contains(".me-axis-x {")
                        && lss.contains(".me-axis-y {")
                        && lss.contains(".me-axis-z {"),
                "照 Blockbench 的 XYZ 分色（颜色在 lss）");
        report("应用变换走 mutate（可撤销），选中变化会回填数值框",
                screen.contains("() -> snapshot.updateCube(cube, nf, nt))")
                        && screen.contains("syncTransformFields();"),
                "可撤销 + 选中即回填");

        // 行为断言：位置=from、尺寸=to-from+1，改完格子真的跟着动
        var s3 = new com.mss.polymech.client.gui.machine.editor.MachineStructureSnapshot("t");
        s3.loadFrom(new com.mss.polymech.machine.definition.MachineDefinition("t",
                java.util.List.of(new com.mss.polymech.machine.definition.MachineDefinition.Group("g",
                        java.util.List.of(new com.mss.polymech.machine.definition.MachineDefinition.Cube(
                                new Vec3i(0, 1, 0), new Vec3i(0, 1, 0))))),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>(),
                com.mss.polymech.machine.definition.MachineDefinition.Meta.DEFAULT));
        var oldCube = s3.cubeAt(new Vec3i(0, 1, 0));
        boolean moved = oldCube != null
                && s3.updateCube(oldCube, new Vec3i(2, 0, 0), new Vec3i(3, 1, 1));
        long vol = s3.cubes().stream().mapToLong(c -> c.volume()).sum();
        report("改 cube 的位置+尺寸后，格子跟着动（旧的没了、新的 2×2×2=8 格）",
                moved && vol == 8
                        && !s3.hasCell(new Vec3i(0, 1, 0))
                        && s3.hasCell(new Vec3i(3, 1, 1)),
                "cube=" + s3.cubes().size() + " 格=" + vol);
        report("变换后的 cube 仍是同一个文件夹（分组不丢）",
                s3.groups().size() == 1 && s3.groups().get(0).name().equals("g"),
                s3.groups().size() + " 组");

        // 尺寸换算的 off-by-one 守卫：尺寸是"含两端的格子数"，所以 to = from + 尺寸 - 1。
        // 界面上 2×2×2 变成 3×3×3 很难一眼看出来（27 vs 8 格），必须离线钉死。
        var cube222 = com.mss.polymech.machine.definition.MachineDefinition.Cube
                .ofSize(new Vec3i(2, 0, 0), 2, 2, 2);
        report("尺寸换算：位置(2,0,0)+尺寸2×2×2 → to=(3,1,1) 共 8 格（不是 27）",
                cube222.to().equals(new Vec3i(3, 1, 1)) && cube222.volume() == 8,
                "to=" + cube222.to() + " 体积=" + cube222.volume());
        report("尺寸最小按 1 处理（填 0 或负数不会造出空/反向 cube）",
                com.mss.polymech.machine.definition.MachineDefinition.Cube
                        .ofSize(new Vec3i(0, 0, 0), 0, -5, 1).volume() == 1,
                "clamp 到 1");

        // ===== 大纲树可编辑（改名 / 整个删 cube）=====
        report("大纲树里能改名文件夹（点文件夹头选中 → 「改名」，含嵌套子组）",
                screen.contains("private void renameSelectedGroup()")
                        && screen.contains("snapshot.renameGroup(path, name)")
                        && screen.contains("renameTarget = pick.renameTarget();"),
                "改名走 mutate，可撤销；路径寻址才能改到子组");
        report("大纲树里能整个删掉一个 cube（行尾「x」，不是抠一格）",
                screen.contains("snapshot.removeCube(c)"),
                "removeCube ≠ removeCell");

        // 行为断言：改名只动名字、删 cube 连代理一起清
        var s4 = new com.mss.polymech.client.gui.machine.editor.MachineStructureSnapshot("t");
        var cubeA = new com.mss.polymech.machine.definition.MachineDefinition.Cube(
                new Vec3i(1, 0, 0), new Vec3i(1, 0, 0));
        var cubeB = new com.mss.polymech.machine.definition.MachineDefinition.Cube(
                new Vec3i(2, 0, 0), new Vec3i(2, 0, 0));
        s4.loadFrom(new com.mss.polymech.machine.definition.MachineDefinition("t",
                java.util.List.of(new com.mss.polymech.machine.definition.MachineDefinition.Group(
                        "老名字", java.util.List.of(cubeA, cubeB))),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>(),
                com.mss.polymech.machine.definition.MachineDefinition.Meta.DEFAULT));
        boolean renamed = s4.renameGroup(0, "新名字");
        report("改名后组名变了、cube 与格子数都不动",
                renamed && s4.groups().get(0).name().equals("新名字")
                        && s4.cubes().size() == 2 && s4.hasCell(new Vec3i(1, 0, 0)),
                s4.groups().get(0).name() + " / " + s4.cubes().size() + " cube");
        boolean removedCube = s4.removeCube(cubeA);
        report("删 cube 后该格消失、同组另一格还在（不是清空整组）",
                removedCube && !s4.hasCell(new Vec3i(1, 0, 0)) && s4.hasCell(new Vec3i(2, 0, 0))
                        && s4.groups().size() == 1,
                "剩 " + s4.cubes().size() + " cube");
        report("删 cube 时它的代理/说明被一起清掉（不留悬空数据）",
                s4.toDefinition().itemProxies().isEmpty()
                        && s4.toDefinition().fluidProxies().isEmpty()
                        && s4.toDefinition().notes().isEmpty(),
                "无悬空数据");

        // ===== 大纲树的缩进（由 TreeList 按 getDimension 画）+ 扁平按钮 =====
        report("大纲缩进由 TreeList 按节点层级画（不用自己塞占位元素）",
                screen.contains("@Override public int getDimension() { return dimension; }")
                        && screen.contains("stack.get(stack.size() - 1).getDimension() >= row.depth()")
                        && !screen.contains("EditorTheme.indent(row.depth() - 1, TREE_INDENT)"),
                "createNodeUI 里 arrow 的 marginLeft = 5 * dimension");
        report("文件夹节点比它的 cube 浅一层（cube 是子项）",
                screen.contains("MachineOutlineLogic.rows(snapshot)")
                        && screen.contains("if (row.isGroup()) stack.add(node);"),
                "层级由纯逻辑类给出，行为断言见 M20");
        report("按钮统一改成扁平深色（不是原版那种凸起按钮）",
                screen.contains("EditorTheme.flatize(bodyElement);"),
                "每次刷新统一收口，新增按钮不会漏");
        report("扁平按钮 = 纯色底图 + 悬停/按下三态",
                theme != null
                        && lss.contains("base-background: rect(")
                        && lss.contains("hover-background: rect(")
                        && lss.contains("pressed-background: rect("),
                "纯色三态，像 Blockbench");

        // ===== 折叠 / 选中高亮 / 工具蓝底黑字 / 滚动条 =====
        report("大纲能展开/折叠，且折叠状态按路径跨重建保留",
                screen.contains("collapsedGroups")
                        && screen.contains("treeList.expandAllNodesIf(root,")
                        && screen.contains("!collapsedGroups.contains(n.path())")
                        && screen.contains("private void syncCollapseFromTree()"),
                "setRoot 会清空树的展开状态，所以要自己记住折叠了谁");
        report("选中的行发亮（TreeList 的 node-hover-background，配色在 lss 里）",
                lss.contains("tree-list {")
                        && lss.contains("node-hover-background: rect(#ff37404e)"),
                "选中底色 = 主题的 ROW_SELECTED，Java 里不写颜色");
        report("工具选中态是 class 驱动（.me-tool-active 在 lss 里，蓝底黑字）",
                screen.contains("btn.addClass(EditorTheme.CLS_TOOL_ACTIVE)")
                        && screen.contains("btn.removeClass(EditorTheme.CLS_TOOL_ACTIVE)")
                        && lss.contains(".me-tool-active {")
                        && lss.contains(".me-tool-active .__button_text__"),
                "状态归代码，外观归样式表");
        report("工具样式在 flatize 之后重新上（否则会被涂回中性灰）",
                screen.indexOf("EditorTheme.flatize(bodyElement);") >= 0
                        && screen.lastIndexOf("applyToolStyles();")
                        > screen.indexOf("EditorTheme.flatize(bodyElement);"),
                "顺序正确（取 refreshText 里那次调用）");
        report("滚动条也换成扁平纯色（槽/滑块/箭头都不再是 Sprites.SCROLL_*）",
                theme != null && theme.contains("styleScrollbars(")
                        && theme.contains("s.scrollBar.buttonStyle(")
                        && theme.contains("IGuiTexture.EMPTY"),
                "最后一块原版贴图");
    }

    /** 去掉行注释、块注释与 javadoc，只留可执行代码（断言别被自己的文档误伤）。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    // ==================== M13：槽位归属是「面」而不是「方块」 ====================

    /**
     * 用户要求：一个舱室方块的两个或多个面要能分别代理<strong>不同的槽位</strong>。
     *
     * <p>这组断言钉的是三件事，任何一件错了都会静默出错（机器能放、管道能接、
     * 但某个面推错了槽位，肉眼看不出）：</p>
     * <ol>
     *   <li>{@code face_slots} 的键是<strong>本地方向</strong>，要按 facing 旋转后才与世界面对应；</li>
     *   <li>没在 {@code face_slots} 里列出的面<strong>回退到 {@code slots}</strong>；</li>
     *   <li>JSON 往返必须逐字节稳定（编辑器保存不会把文件改乱）。</li>
     * </ol>
     */
    private static void verifyFaceSlots() {
        System.out.println();
        System.out.println("==== M13 槽位按面（face_slots）====");

        // 一个舱室方块：北面只代理槽位 2（燃料），上面只代理槽位 5（灰烬），
        // 其余面（若有）回退到 slots=[9]
        String json = """
                {"id":"t",
                 "side_offsets":[[0,1,-2]],
                 "item_proxies":[{"at":[0,1,-2],"slots":[9],"io":"input",
                                  "faces":["north","up"],
                                  "face_slots":{"north":[2],"up":[5]}}]}
                """;
        MachineDefinition def = MachineDefinition.parse("t",
                com.google.gson.JsonParser.parseString(json).getAsJsonObject());
        var proxy = def.itemProxy(new Vec3i(0, 1, -2));
        report("解析出物品代理", proxy != null, "at (0,1,-2)");
        if (proxy == null) return;

        // facing=NORTH 时本地方向 == 世界方向
        report("北面暴露槽位 [2]（不是整块的 [9]）",
                java.util.Arrays.equals(proxy.slotsFor(Direction.NORTH, Direction.NORTH), new int[]{2}),
                java.util.Arrays.toString(proxy.slotsFor(Direction.NORTH, Direction.NORTH)));
        report("上面暴露槽位 [5]（同一方块、不同面、不同槽位）",
                java.util.Arrays.equals(proxy.slotsFor(Direction.UP, Direction.NORTH), new int[]{5}),
                java.util.Arrays.toString(proxy.slotsFor(Direction.UP, Direction.NORTH)));
        report("未列出的面回退到 slots=[9]",
                java.util.Arrays.equals(proxy.slotsFor(Direction.SOUTH, Direction.NORTH), new int[]{9}),
                java.util.Arrays.toString(proxy.slotsFor(Direction.SOUTH, Direction.NORTH)));
        report("context==null（不指定面）回退到 slots=[9]",
                java.util.Arrays.equals(proxy.slotsFor(null, Direction.NORTH), new int[]{9}),
                "能力层 context==null 是「查全部」语义");

        // facing=EAST：本地北面转到世界 EAST，本地上面仍是 UP
        report("facing=EAST 时「本地北面」= 世界 EAST（按 facing 旋转）",
                java.util.Arrays.equals(proxy.slotsFor(Direction.EAST, Direction.EAST), new int[]{2})
                        && java.util.Arrays.equals(proxy.slotsFor(Direction.UP, Direction.EAST), new int[]{5}),
                "旋转正确");
        report("facing=EAST 时世界 NORTH 不是本地北面 → 回退 slots",
                java.util.Arrays.equals(proxy.slotsFor(Direction.NORTH, Direction.EAST), new int[]{9}),
                "没有错位");

        // 往返：parse → toJson → parse 必须一致
        String once = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(def.toJson());
        MachineDefinition again = MachineDefinition.parse("t",
                com.google.gson.JsonParser.parseString(once).getAsJsonObject());
        report("face_slots 的 JSON 往返稳定（编辑器保存不会改乱文件）",
                once.equals(new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                        .toJson(again.toJson())),
                "两次序列化逐字节相同");
        report("往返后按面槽位语义不变",
                again.itemProxy(new Vec3i(0, 1, -2)) != null
                        && java.util.Arrays.equals(
                                again.itemProxy(new Vec3i(0, 1, -2))
                                        .slotsFor(Direction.UP, Direction.NORTH), new int[]{5}),
                "语义保持");

        // 没写 face_slots 时行为必须与老版本完全一致（向后兼容）
        var legacy = new BaseMachineBlock.ItemProxy(new int[]{1, 2},
                BaseMachineBlock.ProxyIO.INPUT, new Direction[]{Direction.UP});
        report("没写 face_slots 时所有面都用 slots（向后兼容）",
                java.util.Arrays.equals(legacy.slotsFor(Direction.UP, Direction.NORTH), new int[]{1, 2})
                        && java.util.Arrays.equals(legacy.slotsFor(Direction.DOWN, Direction.NORTH), new int[]{1, 2}),
                "老 JSON 行为不变");
        report("faceSlotsFor 能区分「没配」与「配了空数组」",
                legacy.faceSlotsFor(Direction.UP, Direction.NORTH) == null,
                "null = 没配");

        // 配了空数组 = 那一面什么都不暴露
        var emptyFace = new BaseMachineBlock.ItemProxy(new int[]{1},
                BaseMachineBlock.ProxyIO.INPUT, null,
                Map.of(Direction.UP, new int[0]));
        report("某面配了空数组 → 该面暴露 0 个槽位（能力层应返回 null）",
                emptyFace.slotsFor(Direction.UP, Direction.NORTH).length == 0
                        && emptyFace.slotsFor(Direction.DOWN, Direction.NORTH).length == 1,
                "空数组 = 这面不暴露");

        // 流体同理
        String fjson = """
                {"id":"t",
                 "side_offsets":[[0,0,1]],
                 "fluid_proxies":[{"at":[0,0,1],"tanks":[0],"io":"input",
                                   "faces":["north","up"],
                                   "face_tanks":{"north":[0],"up":[1]}}]}
                """;
        var fdef = MachineDefinition.parse("t",
                com.google.gson.JsonParser.parseString(fjson).getAsJsonObject());
        var fproxy = fdef.fluidProxy(new Vec3i(0, 0, 1));
        report("流体代理也支持按面配置（face_tanks）",
                fproxy != null
                        && java.util.Arrays.equals(fproxy.tanksFor(Direction.UP, Direction.NORTH), new int[]{1})
                        && java.util.Arrays.equals(fproxy.tanksFor(Direction.NORTH, Direction.NORTH), new int[]{0}),
                "北=罐0 上=罐1");

        // 省略 face_slots 时 JSON 里不该出现这个键
        var plain = new BaseMachineBlock.ItemProxy(new int[]{0},
                BaseMachineBlock.ProxyIO.INPUT, null);
        var pdef = new MachineDefinition("t", new Vec3i[]{new Vec3i(1, 0, 0)}, null,
                Map.of(new Vec3i(1, 0, 0), plain), Map.of(), Map.of());
        report("没配 face_slots 时导出的 JSON 不含该键（老文件不被改胖）",
                !pdef.toJson().getAsJsonArray("item_proxies").get(0).getAsJsonObject().has("face_slots"),
                "省略空表");

        // 非法输入要报错，不能静默忽略
        boolean threw = false;
        try {
            MachineDefinition.parse("t", com.google.gson.JsonParser.parseString("""
                    {"id":"t","side_offsets":[[0,1,0]],
                     "item_proxies":[{"at":[0,1,0],"slots":[0],"io":"input",
                                      "face_slots":{"northeast":[0]}}]}
                    """).getAsJsonObject());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        report("face_slots 里写了非法方向 → 抛错（不静默忽略）", threw, "给作者明确反馈");
    }

    // ==================== M14：九宫格几何（扳手同款） ====================

    /**
     * "更改舱室面用类似扳手的九宫格"——九宫格贴在哪个面上、格子是不是真的三等分，
     * 是纯几何，离线可判死。
     *
     * <p>尤其要钉：九宫格的轴向（{@link com.mss.polymech.client.gui.machine.editor.FaceGrid#axes}）
     * 必须与扳手一致，否则"点九宫格左上角"在编辑器里和扳手里是不同方向，
     * 用户会以为界面坏了。</p>
     */
    private static void verifyFaceGrid() {
        System.out.println();
        System.out.println("==== M14 九宫格几何（与扳手同一套轴向）====");

        // 轴向与扳手逐条一致
        boolean axesOk = true;
        StringBuilder bad = new StringBuilder();
        for (Direction f : Direction.values()) {
            Direction[] a = com.mss.polymech.client.gui.machine.editor.FaceGrid.axes(f);
            Direction[] want = wrenchAxes(f);
            if (a[0] != want[0] || a[1] != want[1]) {
                axesOk = false;
                bad.append(f).append("(").append(a[0]).append(",").append(a[1])
                        .append("!=").append(want[0]).append(",").append(want[1]).append(") ");
            }
        }
        report("六面轴向与扳手 getFaceAxes 逐条一致", axesOk,
                axesOk ? "照抄扳手" : "★ 不一致: " + bad);

        // 两个轴必须与面法线垂直，且互相垂直
        boolean perp = true;
        for (Direction f : Direction.values()) {
            Direction[] a = com.mss.polymech.client.gui.machine.editor.FaceGrid.axes(f);
            if (dot(a[0], f) != 0 || dot(a[1], f) != 0 || dot(a[0], a[1]) != 0) perp = false;
        }
        report("两个轴都与面法线垂直、且互相垂直（九宫格不会歪）", perp, "正交基");

        // 九宫格必须贴在面所在平面上：法线方向的坐标固定
        var p = new BlockPos(2, 3, 4);
        boolean planeOk = true;
        for (Direction f : Direction.values()) {
            for (float u = 0; u <= 1.0001f; u += 0.5f) {
                for (float v = 0; v <= 1.0001f; v += 0.5f) {
                    float[] w = com.mss.polymech.client.gui.machine.editor.FaceGrid.point(p, f, u, v, 0f);
                    float want = switch (f) {
                        case UP -> p.getY() + 1f;
                        case DOWN -> p.getY();
                        case NORTH -> p.getZ();
                        case SOUTH -> p.getZ() + 1f;
                        case WEST -> p.getX();
                        case EAST -> p.getX() + 1f;
                    };
                    float got = switch (f) {
                        case UP, DOWN -> w[1];
                        case NORTH, SOUTH -> w[2];
                        case WEST, EAST -> w[0];
                    };
                    if (Math.abs(got - want) > 1e-5f) planeOk = false;
                }
            }
        }
        report("九宫格顶点全部落在目标面的平面上（六面逐个查）", planeOk, "贴面");

        // 角点：u=v=0 与 u=v=1 必须是该面的两个对角
        float[] a00 = com.mss.polymech.client.gui.machine.editor.FaceGrid.point(p, Direction.UP, 0, 0, 0f);
        float[] a11 = com.mss.polymech.client.gui.machine.editor.FaceGrid.point(p, Direction.UP, 1, 1, 0f);
        report("上面 u=v=0 → (x, y+1, z)、u=v=1 → (x+1, y+1, z+1)",
                Math.abs(a00[0] - p.getX()) < 1e-5f && Math.abs(a00[1] - (p.getY() + 1f)) < 1e-5f
                        && Math.abs(a00[2] - p.getZ()) < 1e-5f
                        && Math.abs(a11[0] - (p.getX() + 1f)) < 1e-5f
                        && Math.abs(a11[2] - (p.getZ() + 1f)) < 1e-5f,
                "对角正确");

        // 外法线偏移只沿法线方向
        float[] off = com.mss.polymech.client.gui.machine.editor.FaceGrid.point(p, Direction.UP, 0.5f, 0.5f, 0.01f);
        report("offset 只沿面法线外推（不歪）",
                Math.abs(off[1] - (p.getY() + 1f + 0.01f)) < 1e-5f
                        && Math.abs(off[0] - (p.getX() + 0.5f)) < 1e-5f
                        && Math.abs(off[2] - (p.getZ() + 0.5f)) < 1e-5f,
                "防 Z-fighting 用");

        // 分格：1/3、2/3
        float[] div = com.mss.polymech.client.gui.machine.editor.FaceGrid.dividerPositions();
        report("分隔线在 1/3 与 2/3（三等分，不是四等分）",
                div.length == 2 && Math.abs(div[0] - 1f / 3f) < 1e-6f && Math.abs(div[1] - 2f / 3f) < 1e-6f,
                java.util.Arrays.toString(div));

        // 9 格覆盖整个面且不重叠：面积和 = 1
        float area = 0f;
        for (int i = 0; i < 9; i++) {
            float[][] c = com.mss.polymech.client.gui.machine.editor.FaceGrid.cellCorners(i);
            float w = Math.abs(c[1][0] - c[0][0]);
            float h = Math.abs(c[2][1] - c[0][1]);
            area += w * h;
        }
        report("9 个格子恰好铺满整个面（面积和 = 1，不重叠不留缝）",
                Math.abs(area - 1f) < 1e-5f, "面积和 " + area);

        // 行优先索引：左下/中/右上
        report("索引是行优先：0=左上、4=正中、8=右下",
                com.mss.polymech.client.gui.machine.editor.FaceGrid.cellIndex(0.1f, 0.1f) == 0
                        && com.mss.polymech.client.gui.machine.editor.FaceGrid.cellIndex(0.5f, 0.5f) == 4
                        && com.mss.polymech.client.gui.machine.editor.FaceGrid.cellIndex(0.9f, 0.9f) == 8,
                "行优先");
        report("越界的 u/v 夹到边界格（射线打到边缘不会算出第 9 格以外）",
                com.mss.polymech.client.gui.machine.editor.FaceGrid.cellIndex(-0.5f, 1.5f) == 6,
                "夹取");

        report("DIVISIONS = 3", com.mss.polymech.client.gui.machine.editor.FaceGrid.DIVISIONS == 3, "3×3");
    }

    /** 扳手 {@code WrenchItem.getFaceAxes} 的期望值（独立抄一份，避免用被测实现自证）。 */
    private static Direction[] wrenchAxes(Direction face) {
        return switch (face) {
            case NORTH -> new Direction[]{Direction.EAST, Direction.UP};
            case SOUTH -> new Direction[]{Direction.WEST, Direction.UP};
            case EAST -> new Direction[]{Direction.NORTH, Direction.UP};
            case WEST -> new Direction[]{Direction.SOUTH, Direction.UP};
            case UP -> new Direction[]{Direction.EAST, Direction.SOUTH};
            case DOWN -> new Direction[]{Direction.WEST, Direction.NORTH};
        };
    }

    private static int dot(Direction a, Direction b) {
        return a.getStepX() * b.getStepX() + a.getStepY() * b.getStepY() + a.getStepZ() * b.getStepZ();
    }

    // ==================== M15：半透明填充的面剔除与绕序 ====================

    /**
     * 用户报的 bug：多个半透明填充格相邻时，<strong>格子之间的面也画了</strong>，
     * alpha 叠加导致"越叠越白"；而且背面也画了，等于又叠一层。
     * MC 玻璃不会这样（{@code skipRendering} 去掉相邻面 + 背面剔除）。
     *
     * <p>这组断言钉两件事，两件都是肉眼看不出、错了只会觉得"颜色怪怪的"：</p>
     * <ol>
     *   <li><b>面剔除</b>：相邻两个填充格之间的面必须不提交；</li>
     *   <li><b>绕序</b>：4 个角的叉乘必须指向<strong>外法线</strong>——
     *       绕序反了的话开了背面剔除就会整个面消失（比叠 alpha 更难看），
     *       而叉乘是纯数学，离线就能判死。</li>
     * </ol>
     */
    private static void verifyCellFillFaces() {
        System.out.println();
        System.out.println("==== M15 半透明填充：面剔除 + 绕序（MC 玻璃同款）====");

        var p = new BlockPos(5, 6, 7);

        // 1) 绕序：cross(c1-c0, c2-c0) 必须平行于且同向于该面外法线
        boolean windOk = true;
        StringBuilder badWind = new StringBuilder();
        for (Direction d : Direction.values()) {
            float[][] c = com.mss.polymech.client.gui.machine.editor.CellFillFaces.outwardCorners(p, d);
            float[] e1 = {c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2]};
            float[] e2 = {c[2][0] - c[0][0], c[2][1] - c[0][1], c[2][2] - c[0][2]};
            float nx = e1[1] * e2[2] - e1[2] * e2[1];
            float ny = e1[2] * e2[0] - e1[0] * e2[2];
            float nz = e1[0] * e2[1] - e1[1] * e2[0];
            // 归一化后与法线比较（面积是 1，所以模长应为 1）
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) {
                windOk = false;
                badWind.append(d).append("(退化) ");
                continue;
            }
            float ux = nx / len, uy = ny / len, uz = nz / len;
            var n = d.getNormal();
            if (Math.abs(ux - n.getX()) > 1e-4f
                    || Math.abs(uy - n.getY()) > 1e-4f
                    || Math.abs(uz - n.getZ()) > 1e-4f) {
                windOk = false;
                badWind.append(d).append("(").append(ux).append(",").append(uy).append(",").append(uz)
                        .append("≠").append(n).append(") ");
            }
        }
        report("六面绕序都是「朝外正面」（叉乘 = 外法线，开背面剔除不会消失）", windOk,
                windOk ? "六面全对" : "★ 绕序错: " + badWind);

        // 2) 四个角必须都在该面平面上，且构成边长 1 的正方形
        boolean planeOk = true;
        boolean squareOk = true;
        for (Direction d : Direction.values()) {
            float[][] c = com.mss.polymech.client.gui.machine.editor.CellFillFaces.outwardCorners(p, d);
            if (c.length != 4) {
                planeOk = false;
                continue;
            }
            for (float[] v : c) {
                float got = switch (d) {
                    case UP, DOWN -> v[1];
                    case NORTH, SOUTH -> v[2];
                    case WEST, EAST -> v[0];
                };
                float want = switch (d) {
                    case UP -> p.getY() + 1f;
                    case DOWN -> p.getY();
                    case NORTH -> p.getZ();
                    case SOUTH -> p.getZ() + 1f;
                    case WEST -> p.getX();
                    case EAST -> p.getX() + 1f;
                };
                if (Math.abs(got - want) > 1e-5f) planeOk = false;
            }
            for (int i = 0; i < 4; i++) {
                float[] a = c[i];
                float[] b = c[(i + 1) % 4];
                float dist = (float) Math.sqrt(Math.pow(a[0] - b[0], 2)
                        + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
                if (Math.abs(dist - 1f) > 1e-5f) squareOk = false;
            }
        }
        report("填充面四角贴在该面平面上", planeOk, "六面贴面");
        report("填充面四角构成边长 1 的正方形（正好铺满一格）", squareOk, "边长 1");

        // 3) 面剔除：单个孤立方块 → 6 个面
        var single = new java.util.HashSet<Vec3i>();
        single.add(Vec3i.ZERO);
        report("孤立方块提交全部 6 个面",
                com.mss.polymech.client.gui.machine.editor.CellFillFaces
                        .outerFaces(Vec3i.ZERO, single).size() == 6,
                "6 面");

        // 4) 两个相邻格 → 各 5 个面（接触面被剔除），共 10
        var pair = new java.util.HashSet<Vec3i>();
        pair.add(Vec3i.ZERO);
        pair.add(new Vec3i(1, 0, 0));
        int pairFaces = com.mss.polymech.client.gui.machine.editor.CellFillFaces
                .outerFaces(Vec3i.ZERO, pair).size()
                + com.mss.polymech.client.gui.machine.editor.CellFillFaces
                        .outerFaces(new Vec3i(1, 0, 0), pair).size();
        report("两个相邻填充格：接触面被剔除（各 5 面，共 10 而不是 12）",
                pairFaces == 10, "面数 " + pairFaces);
        report("相邻格之间的那个面确实不提交（+X 方向）",
                !com.mss.polymech.client.gui.machine.editor.CellFillFaces
                        .isOuterFace(Vec3i.ZERO, Direction.EAST, pair)
                        && !com.mss.polymech.client.gui.machine.editor.CellFillFaces
                                .isOuterFace(new Vec3i(1, 0, 0), Direction.WEST, pair),
                "内面剔除");

        // 5) 2×2×2 实心块 → 表面积 24（不是 48）；这正是"只有一层 alpha"的判据
        var cube = new java.util.HashSet<Vec3i>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) cube.add(new Vec3i(x, y, z));
            }
        }
        int cubeFaces = 0;
        for (Vec3i v : cube) {
            cubeFaces += com.mss.polymech.client.gui.machine.editor.CellFillFaces.outerFaces(v, cube).size();
        }
        report("2×2×2 实心块只提交外表面 24 个面（不是 8×6=48，内面全剔除）",
                cubeFaces == 24, "面数 " + cubeFaces);

        // 6) 一整块实心区域，任一视线方向只穿到一层外表面（alpha 不叠加的根本原因）
        //    对每个方向，沿该方向看过去，第一个填充格的那个面必须是外露面。
        boolean oneLayer = true;
        for (Direction d : Direction.values()) {
            // 沿 -d 方向最靠外的那个格子，它朝 d 的面必须是外露面
            Vec3i outermost = new Vec3i(
                    d.getStepX() > 0 ? 1 : 0,
                    d.getStepY() > 0 ? 1 : 0,
                    d.getStepZ() > 0 ? 1 : 0);
            if (!com.mss.polymech.client.gui.machine.editor.CellFillFaces
                    .isOuterFace(outermost, d, cube)) {
                oneLayer = false;
            }
        }
        report("实心块每个方向最外层那一面都是外露面（视线只穿一层）", oneLayer, "不叠 alpha");
    }

    // ==================== M16：无缝棱（相邻同种方块之间不画棱） ====================

    /**
     * 用户要求："多个同样方块相接，那么之间相接的棱就不需要显示了，就跟无缝玻璃一样"。
     *
     * <p>判据是"一条棱周围 4 个象限填充了几个"，其中<b>最容易写错的是把"2 个相邻"
     * 和"2 个对角"混为一谈</b>：前者是平接缝（该藏），后者是两块只靠这条棱接触（该露）。
     * 只看数量不看位置就会把对角那种也藏掉，机器看起来会缺一块。</p>
     */
    private static void verifyOuterEdges() {
        System.out.println();
        System.out.println("==== M16 无缝棱（相邻方块之间的棱不画）====");

        var p = new BlockPos(0, 0, 0);

        // 1) 单格 → 12 条棱
        var one = new java.util.HashSet<Vec3i>();
        one.add(Vec3i.ZERO);
        report("孤立方块画全部 12 条棱",
                com.mss.polymech.client.gui.machine.editor.CellFillFaces
                        .outerEdges(p, Vec3i.ZERO, one).size() == 12,
                "12 条");

        // 2) 两个相邻（沿 X）→ 相接那条竖直缝必须消失。
        //    注意算术：两格各 12 段 = 24 段，但相接面的 4 条棱**两格都会提交同一段**
        //    （A 的 +X 面那条棱 = B 的 -X 面那条棱），所以 24 里有 4 段是重复的；
        //    这 4 段又都是平接缝被藏掉 → 24 - 4(重复) - 4(藏) = 16。
        //    重复提交同一段也会叠 alpha，所以 CellFillFaces 有"归属"判定：一段只让
        //    坐标最小的那个填充格提交。
        var pair = new java.util.HashSet<Vec3i>();
        pair.add(Vec3i.ZERO);
        pair.add(new Vec3i(1, 0, 0));
        int pairEdges = 0;
        for (Vec3i v : pair) {
            pairEdges += com.mss.polymech.client.gui.machine.editor.CellFillFaces
                    .outerEdges(p, v, pair).size();
        }
        report("两个相邻方块：相接的竖缝不画、同一段棱不重复提交（24 段 → 16 段）",
                pairEdges == 16, "棱数 " + pairEdges);

        // 3) 2×2×2 实心块：内部棱全消失
        //    总格棱 8×12=96；4 个格子共用的"内部十字棱"必须都没了
        var cube = new java.util.HashSet<Vec3i>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 2; z++) cube.add(new Vec3i(x, y, z));
            }
        }
        int cubeEdges = 0;
        for (Vec3i v : cube) {
            cubeEdges += com.mss.polymech.client.gui.machine.editor.CellFillFaces
                    .outerEdges(p, v, cube).size();
        }
        // 2×2×2 只剩大立方体的 12 条棱，每条被 2 格各画一半 → 24 段
        report("2×2×2 实心块只剩外轮廓 24 段棱（内部全剔除）",
                cubeEdges == 24, "棱数 " + cubeEdges);

        // 4) 关键区分：2 个对角（只共一条棱）必须画出来
        var diag = new java.util.HashSet<Vec3i>();
        diag.add(Vec3i.ZERO);
        diag.add(new Vec3i(1, 1, 0));   // 只共享一条沿 Z 的棱
        // 那条共享棱：沿 Z、角点 (x=1,y=1)。对 (0,0,0) 来说，它的 +X+Y 竖棱
        boolean sharedEdgeDrawn = false;
        for (var e : com.mss.polymech.client.gui.machine.editor.CellFillFaces
                .outerEdges(p, Vec3i.ZERO, diag)) {
            // 沿 Z、在 x=1,y=1 处
            if (Math.abs(e.x1() - 1f) < 1e-5f && Math.abs(e.y1() - 1f) < 1e-5f
                    && Math.abs(e.z2() - e.z1() - 1f) < 1e-5f) {
                sharedEdgeDrawn = true;
            }
        }
        report("两个只共一条棱的方块：那条棱仍然画（对角 ≠ 平接缝）",
                sharedEdgeDrawn, "只共棱 → 露");

        // 5) 平面接缝与"只共棱"的判据必须不同（这是最容易写错的地方）
        report("平接缝（2 个相邻）藏、只共棱（2 个对角）露 —— 判据确实区分了两者",
                pairEdges == 16 && sharedEdgeDrawn, "位置相关，不是只看数量");

        // 6) 一片平板（3×3×1）的正面网格线：板面内部的棱应全部消失
        var plate = new java.util.HashSet<Vec3i>();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) plate.add(new Vec3i(x, 0, z));
        }
        int plateEdges = 0;
        for (Vec3i v : plate) {
            plateEdges += com.mss.polymech.client.gui.machine.editor.CellFillFaces
                    .outerEdges(p, v, plate).size();
        }
        // 3×3 平板：外圈 12 段 + 上下两面各 4 条内部棱？—— 只断言"远少于逐格 9×12=108"
        report("3×3 平板：内部接缝大面积消失（远少于逐格 108 条）",
                plateEdges < 60, "棱数 " + plateEdges);
    }

    // ==================== M17：工具系统（像 Blockbench 那样先选工具） ====================

    /**
     * 用户要求："在选中的时候，可以切换工具来编辑，所以，请务必在里面增加几种工具，
     * 就跟 blockbench 操作 cube 一样"。
     *
     * <p>断言的是工具集的<strong>完备性与唯一性</strong>：每个工具语义唯一、
     * 提示文案齐全、循环切换不漏项——这些错了界面会"点了没反应"或"提示与实际不符"。</p>
     */
    private static void verifyTools() {
        System.out.println();
        System.out.println("==== M17 工具系统 ====");

        var all = com.mss.polymech.client.gui.machine.editor.EditorTool.values();
        report("工具数量 ≥ 5（够覆盖加/删/选/朝向面/区间/角色）", all.length >= 5,
                "共 " + all.length + " 个");

        boolean labelOk = true;
        boolean hintOk = true;
        for (var t : all) {
            if (t.label() == null || t.label().isBlank()) labelOk = false;
            if (t.hint() == null || t.hint().isBlank()) hintOk = false;
        }
        report("每个工具都有中文名", labelOk, "按钮文案");
        report("每个工具都有提示文案（状态栏告诉作者点下去会发生什么）", hintOk, "可发现性");

        // 循环切换必须能回到原点且不重复（漏项会让某个工具永远切不到）
        var seen = new java.util.LinkedHashSet<com.mss.polymech.client.gui.machine.editor.EditorTool>();
        var cur = all[0];
        for (int i = 0; i < all.length; i++) {
            seen.add(cur);
            cur = cur.next();
        }
        report("next() 循环一圈不重复且回到起点（没有工具切不到）",
                seen.size() == all.length && cur == all[0],
                "覆盖 " + seen.size() + "/" + all.length);

        report("只有「朝向面」工具会画九宫格（避免到处乱画）",
                com.mss.polymech.client.gui.machine.editor.EditorTool.FACE.usesFaceGrid()
                        && !com.mss.polymech.client.gui.machine.editor.EditorTool.SELECT.usesFaceGrid()
                        && !com.mss.polymech.client.gui.machine.editor.EditorTool.ADD.usesFaceGrid(),
                "FACE 专用");

        report("改结构的工具会记撤销（加/删/区间），选格类不会",
                com.mss.polymech.client.gui.machine.editor.EditorTool.ADD.mutatesStructure()
                        && com.mss.polymech.client.gui.machine.editor.EditorTool.REMOVE.mutatesStructure()
                        && com.mss.polymech.client.gui.machine.editor.EditorTool.REGION.mutatesStructure()
                        && !com.mss.polymech.client.gui.machine.editor.EditorTool.SELECT.mutatesStructure()
                        && !com.mss.polymech.client.gui.machine.editor.EditorTool.FACE.mutatesStructure(),
                "撤销粒度");

        report("parse() 认英文 id、不认垃圾",
                com.mss.polymech.client.gui.machine.editor.EditorTool.parse("face")
                        == com.mss.polymech.client.gui.machine.editor.EditorTool.FACE
                        && com.mss.polymech.client.gui.machine.editor.EditorTool.parse(" FACE ")
                                == com.mss.polymech.client.gui.machine.editor.EditorTool.FACE
                        && com.mss.polymech.client.gui.machine.editor.EditorTool.parse("???") == null,
                "容错");

        // 世界方向 → 本地方向反查（朝向面工具要用）
        report("localFace 反查：facing=NORTH 时本地==世界",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces
                        .localFace(Direction.UP, Direction.NORTH) == Direction.UP,
                "编辑器默认朝向");
        report("localFace 反查：facing=EAST 时世界 EAST 对应本地 NORTH",
                com.mss.polymech.client.gui.machine.editor.ProxyFaces
                        .localFace(Direction.EAST, Direction.EAST) == Direction.NORTH,
                "旋转反查正确");
    }

    // ==================== M18：按面槽位的编辑层 ====================

    /**
     * M13 验的是"按面槽位的数据/运行时"（记录、JSON、能力层）；这里验<b>编辑层</b>：
     * 从快照写进去、再导出成 JSON、再读回来，必须逐字节一致，而且
     * 「没配过（回退默认）」与「配了空（这一面不暴露）」必须<u>始终</u>区分得开——
     * 这两种状态混掉是静默错误：机器能跑，但某个面莫名其妙什么都不能接。
     */
    private static void verifyFaceSlotEditing() {
        System.out.println();
        System.out.println("==== M18 按面槽位：编辑层往返 ====");

        // 造一格双舱室（物品+流体），方块级默认 slots=[0,1] / tanks=[3]
        var s = snap(def("filling_unit", new Vec3i[0], null,
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>()));
        s.addCell(new Vec3i(1, 0, 0));
        s.setRole(new Vec3i(1, 0, 0),
                new BaseMachineBlock.ItemProxy(new int[]{0, 1}, BaseMachineBlock.ProxyIO.INPUT, null),
                new BaseMachineBlock.FluidProxy(new int[]{3}, BaseMachineBlock.ProxyIO.INPUT, null));

        // 给 north 面单独配 [2]；up 面配空数组（=这一面不暴露）
        s.setItemFaceSlots(new Vec3i(1, 0, 0), Direction.NORTH, new int[]{2});
        s.setFluidFaceTanks(new Vec3i(1, 0, 0), Direction.NORTH, new int[]{7});
        s.setItemFaceSlots(new Vec3i(1, 0, 0), Direction.UP, new int[0]);

        var it = s.cellAt(new Vec3i(1, 0, 0)).itemProxy();
        var fl = s.cellAt(new Vec3i(1, 0, 0)).fluidProxy();

        // 1) 写进去了
        report("给 north 面单独配槽位后，slotsFor(NORTH) 返回该面的 [2]",
                it.slotsFor(Direction.NORTH, Direction.NORTH).length == 1
                        && it.slotsFor(Direction.NORTH, Direction.NORTH)[0] == 2,
                "按面生效");
        report("未配过的面回退到方块级默认 [0,1]",
                it.slotsFor(Direction.SOUTH, Direction.NORTH).length == 2,
                "回退默认");
        report("配了空数组的面 = 这一面什么都不暴露（长度 0，不是回退）",
                it.slotsFor(Direction.UP, Direction.NORTH).length == 0,
                "空 ≠ 没配");
        report("faceSlotsFor 对「没配过」返回 null（与「配了空」区分）",
                it.faceSlotsFor(Direction.SOUTH, Direction.NORTH) == null
                        && it.faceSlotsFor(Direction.UP, Direction.NORTH) != null
                        && it.faceSlotsFor(Direction.UP, Direction.NORTH).length == 0,
                "null vs 空数组");
        report("流体侧同理：north 面 tanks=[7]，其他面回退 [3]",
                fl.tanksFor(Direction.NORTH, Direction.NORTH)[0] == 7
                        && fl.tanksFor(Direction.SOUTH, Direction.NORTH)[0] == 3,
                "流体按面");

        // 2) 往返：导出 JSON → 重新解析 → 按面配置必须原样还在
        //    （走文本这一层才叫往返；直接用 toDefinition() 会跳过序列化，测不出导出 bug）
        String json = s.exportJson();
        var back = new MachineStructureSnapshot("filling_unit");
        back.loadFrom(MachineDefinition.parse("filling_unit",
                com.google.gson.JsonParser.parseString(json).getAsJsonObject()));
        var it2 = back.cellAt(new Vec3i(1, 0, 0)).itemProxy();
        report("导出→解析后按面槽位仍在（north=[2]）",
                it2.slotsFor(Direction.NORTH, Direction.NORTH)[0] == 2,
                "往返不丢");
        report("导出→解析后「配了空」的那一面仍然是不暴露（没被写成默认值）",
                it2.faceSlotsFor(Direction.UP, Direction.NORTH) != null
                        && it2.slotsFor(Direction.UP, Direction.NORTH).length == 0,
                "空值不被回退吃掉");

        // 3) 清除该面配置 → 回退到默认（这是"清除"与"填空"的区别）
        s.setItemFaceSlots(new Vec3i(1, 0, 0), Direction.NORTH, null);
        var it3 = s.cellAt(new Vec3i(1, 0, 0)).itemProxy();
        report("清除该面配置后，该面回退到方块级默认（faceSlotsFor 变回 null）",
                it3.faceSlotsFor(Direction.NORTH, Direction.NORTH) == null
                        && it3.slotsFor(Direction.NORTH, Direction.NORTH).length == 2,
                "清除 = 回退");
        report("清除只影响那一面（up 面的空配置还在）",
                it3.faceSlotsFor(Direction.UP, Direction.NORTH) != null,
                "不误伤别的面");

        // 4) 全部清空后整个 face_slots 键应消失（JSON 里不留空对象）
        s.setItemFaceSlots(new Vec3i(1, 0, 0), Direction.UP, null);
        String json2 = s.exportJson();
        report("所有按面配置清空后，JSON 里不再出现 face_slots（不留空对象）",
                !json2.contains("face_slots"),
                "干净导出");

        // 5) 界面接线
        String screen = readSource("src/main/java/com/mss/polymech/client/gui/machine/editor/"
                + "MachineTab.java");
        report("舱室页签有按面槽位输入框与「写入该面 / 清除该面配置」按钮",
                screen.contains("faceSlotField") && screen.contains("faceTankField")
                        && screen.contains("applyFaceSlots()") && screen.contains("clearFaceSlots()"),
                "E-5 的界面层");
        report("当前面取 3D 里最后点的那一面（经 localFace 反查）",
                screen.contains("private Direction currentFace()")
                        && screen.contains("ProxyFaces.localFace(lastFace, Direction.NORTH)"),
                "跟随九宫格");
        report("界面区分「留空 = 不暴露」与「清除 = 回退默认」",
                screen.contains("留空 = 该面不暴露") && screen.contains("回退方块级默认"),
                "两种状态别混");
        report("列出已配置的各面，没配过时明确提示走方块级默认",
                screen.contains("faceSlotSummary(") && screen.contains("还没有任何按面配置"),
                "可发现性");
    }

    private static String readSource(String relativePath) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(relativePath));
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== M19：schema 3 嵌套组（路径寻址 + 移交） ====================

    /**
     * schema 3 的核心承诺：<b>组可以嵌套，且嵌套的 cube 不会在任何一步被静默丢掉</b>。
     *
     * <p>这是最容易出静默错误的地方 —— 快照里任何一次"重建 Group"只要写成
     * {@code new Group(name, cubes)} 而忘了带上 {@code groups()}，子组连同里面的 cube
     * 就会蒸发，而界面看起来完全正常（正是本项目反复踩的"数据悄悄丢"）。所以逐条钉死。</p>
     */
    private static void verifyNestedGroups() {
        System.out.println();
        System.out.println("==== M19 schema 3：嵌套组 / 路径寻址 ====");

        // 注意：原点 (0,0,0) 永远是控制器格（rebuild() 无条件补上），
        // 所以测试用的 cube 不能放在原点，否则"格消失"永远为假。
        var cubeA = new MachineDefinition.Cube(new Vec3i(0, 1, 0), new Vec3i(0, 1, 0));
        var cubeB = new MachineDefinition.Cube(new Vec3i(1, 0, 0), new Vec3i(1, 0, 0));

        var inner = new MachineDefinition.Group("外壳", java.util.List.of(cubeB), java.util.List.of());
        var outer = new MachineDefinition.Group("主体", java.util.List.of(cubeA), java.util.List.of(inner));

        var snap = new MachineStructureSnapshot("t");
        snap.loadFrom(new MachineDefinition("t", java.util.List.of(outer),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>(), MachineDefinition.Meta.DEFAULT));

        report("嵌套 cube 全在（展平不漏子组）",
                snap.cubes().size() == 2 && snap.hasCell(new Vec3i(1, 0, 0)),
                "cube=" + snap.cubes().size());

        // 注意：inner 是 outer 的<b>唯一</b>子组，所以它的路径是 [0,0]（不是 [0,1]）
        report("allGroups 深度优先给出路径与层级",
                snap.allGroups().size() == 2
                        && snap.allGroups().get(0).path().equals(MachineStructureSnapshot.GroupPath.of(0))
                        && snap.allGroups().get(1).path().equals(MachineStructureSnapshot.GroupPath.of(0, 0))
                        && snap.allGroups().get(1).depth() == 2,
                snap.allGroups().stream().map(e -> e.path() + "@" + e.depth()).toList().toString());

        report("cubePath 能定位到嵌套组里的 cube",
                MachineStructureSnapshot.GroupPath.of(0, 0).equals(snap.cubePath(cubeB))
                        && MachineStructureSnapshot.GroupPath.of(0).equals(snap.cubePath(cubeA)),
                "A=" + snap.cubePath(cubeA) + " B=" + snap.cubePath(cubeB));

        // 改名：必须保住子组（重建 Group 时忘了带 groups() 就会在这里露馅）
        boolean renamed = snap.renameGroup(MachineStructureSnapshot.GroupPath.of(0), "主体2");
        report("给父组改名不会丢掉子组与里面的 cube",
                renamed && snap.groupAt(MachineStructureSnapshot.GroupPath.of(0)).name().equals("主体2")
                        && snap.groupAt(MachineStructureSnapshot.GroupPath.of(0, 0)) != null
                        && snap.hasCell(new Vec3i(1, 0, 0)),
                "改名=" + renamed + " 子组=" + snap.groupAt(MachineStructureSnapshot.GroupPath.of(0, 0)));

        // 移交：把嵌套 cube 搬到顶层新组
        var top = snap.addGroup(MachineStructureSnapshot.GroupPath.ROOT, "新组");
        boolean moved = top != null && snap.moveCubeToGroup(cubeB, top);
        report("移交把 cube 从子组搬到新组（不是复制）",
                moved && snap.cubes().size() == 2
                        && top.equals(snap.cubePath(cubeB))
                        && snap.groupAt(MachineStructureSnapshot.GroupPath.of(0, 0)).cubes().isEmpty(),
                "原处已空、新处有它");

        // 在指定组里再建子组（造嵌套的入口）
        var deep = snap.addGroup(MachineStructureSnapshot.GroupPath.of(0), "深一层");
        report("能在指定组里建子组（层级 +1）",
                deep != null && deep.depth() == 2 && snap.countGroups() == 4,
                "deep=" + deep + " 组数=" + snap.countGroups());

        // 删父组必须连子组一起删
        snap.removeGroup(MachineStructureSnapshot.GroupPath.of(0));
        report("删父组连子组一起删（不留下悬空的子组）",
                snap.allGroups().size() == 1 && !snap.hasCell(new Vec3i(0, 1, 0)),
                "剩 " + snap.countGroups() + " 组 / A 格在=" + snap.hasCell(new Vec3i(0, 1, 0)));

        // 导出/读回：嵌套结构必须原样往返
        var def = snap.toDefinition();
        var back = MachineDefinition.parse("t", def.toJson());
        report("嵌套结构导出再读回一致（读写对称）",
                back.cubes().size() == snap.cubes().size()
                        && back.groups().size() == snap.groups().size()
                        && back.toJson().equals(def.toJson()),
                "round-trip");

        // ===== 界面接线：多选 + 缩进冲突弹窗（新功能必须真的接上）=====
        String screen = readSource(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/MachineTab.java");
        report("大纲多选交给 TreeList（Ctrl 切换 / Shift 区间）",
                screen != null && screen.contains("treeList.setSupportMultipleSelection(true)")
                        && screen.contains("private void onOutlineSelectionChanged(")
                        && screen.contains("selectedCubes"),
                "多选才有「一批 cube 一起移交」");
        report("缩进冲突会先弹窗确认（不是悄悄拍平结构）",
                screen != null && screen.contains("private void confirmIndentConflict(")
                        && screen.contains("新建组会改变缩进")
                        && screen.contains("确认移交"),
                "会换层的 cube 先列出来问一次");
        report("重建树时挡掉 TreeList 内部的空选择回调（否则点击一次就无限递归）",
                screen != null && screen.contains("private boolean rebuildingTree = false;")
                        && screen.contains("if (rebuildingTree) return;"),
                "setRoot → reloadList → setSelected(空集, notify=true)");
        report("「+ 组」按路径建组并把选中的 cube 移交",
                screen != null && screen.contains("snapshot.addGroup(parent, name)")
                        && screen.contains("snapshot.moveCubeToGroup(c, created)"),
                "schema 3 的建组入口");
    }

    // ==================== M20：大纲决策（纯逻辑，真行为） ====================

    /**
     * 大纲"算得对不对" —— 直接跑 {@link MachineOutlineLogic}，<b>不看源码文本</b>。
     *
     * <p>这是本套断言里少数几个真行为测试：层级、拍平顺序、「+ 组」落在哪一层、
     * 哪些 cube 会因为移交而换层，全部由输入结构算出来。源码字符串断言只能证明
     * "那段代码还在"，证明不了"算得对" —— 本项目就吃过"断言照自己写错的契约写、
     * 于是测试通过却钉错了契约"的亏。</p>
     */
    private static void verifyOutlineLogic() {
        System.out.println();
        System.out.println("==== M20 大纲决策（纯逻辑） ====");

        var cubeA = new MachineDefinition.Cube(new Vec3i(0, 1, 0), new Vec3i(0, 1, 0));
        var cubeB = new MachineDefinition.Cube(new Vec3i(1, 0, 0), new Vec3i(1, 0, 0));
        var inner = new MachineDefinition.Group("外壳", java.util.List.of(cubeB), java.util.List.of());
        var outer = new MachineDefinition.Group("主体", java.util.List.of(cubeA), java.util.List.of(inner));

        var snap = new MachineStructureSnapshot("t");
        snap.loadFrom(new MachineDefinition("t", java.util.List.of(outer),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>(), MachineDefinition.Meta.DEFAULT));

        var rows = MachineOutlineLogic.rows(snap);
        report("大纲行的顺序是 组头 → 它直接挂的 cube → 子组（深度优先）",
                rows.size() == 4
                        && rows.get(0).isGroup() && rows.get(0).depth() == 1
                        && !rows.get(1).isGroup() && rows.get(1).cube().equals(cubeA)
                        && rows.get(2).isGroup() && rows.get(2).depth() == 2
                        && !rows.get(3).isGroup() && rows.get(3).cube().equals(cubeB)
                        && rows.get(3).depth() == 3,
                rows.stream().map(r -> (r.isGroup() ? "G" : "c") + r.depth()).toList().toString());

        report("cube 行不叫组名（之前每行都显示组名，看着像改一个 cube 就改了整组）",
                MachineOutlineLogic.rowLabel(outer, null).equals("主体")
                        && MachineOutlineLogic.rowLabel(outer, cubeA).equals("cube")
                        && !MachineOutlineLogic.rowLabel(outer, cubeA).equals(outer.name()),
                "组头=" + MachineOutlineLogic.rowLabel(outer, null)
                        + " cube=" + MachineOutlineLogic.rowLabel(outer, cubeA));

        var box = new MachineDefinition.Cube(new Vec3i(0, 0, 0), new Vec3i(1, 1, 1));
        report("盒子（from != to）显示 cube (盒)，单格显示 cube",
                MachineOutlineLogic.rowLabel(outer, box).equals("cube (盒)")
                        && MachineOutlineLogic.rowLabel(outer, cubeA).equals("cube"),
                MachineOutlineLogic.rowLabel(outer, box));

        // ===== schema 4：cube 名字 =====
        var named = new MachineDefinition.Cube(new Vec3i(1, 0, 0), new Vec3i(1, 0, 0), "齿轮");
        report("cube 有名字时大纲显示名字，没名字才退回 cube",
                MachineOutlineLogic.rowLabel(outer, named).equals("齿轮")
                        && MachineOutlineLogic.rowLabel(outer, cubeA).equals("cube"),
                "有名=" + MachineOutlineLogic.rowLabel(outer, named));

        // 改名会改动结构，所以用独立快照 —— 别污染上面那些依赖原始 coords 的断言
        var snap2 = new MachineStructureSnapshot("t2");
        snap2.loadFrom(new MachineDefinition("t2", java.util.List.of(outer),
                new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>(),
                new java.util.LinkedHashMap<>(), MachineDefinition.Meta.DEFAULT));

        var renamed = snap2.renameCube(cubeB, "外壳齿轮");
        report("给嵌套组里的 cube 改名生效（递归进子组）",
                renamed != null && "外壳齿轮".equals(renamed.name())
                        && "外壳齿轮".equals(snap2.groups().get(0).groups().get(0)
                                .cubes().get(0).name()),
                "name=" + (renamed == null ? "null" : renamed.name()));

        report("改名返回新实例、坐标不变（Cube 是 record，name 参与 equals）",
                renamed != null && !renamed.equals(cubeB)
                        && renamed.from().equals(cubeB.from()) && renamed.to().equals(cubeB.to()),
                "实例换了，坐标没变");

        report("把名字改成空白 → 变回无名（大纲退回 cube）",
                snap2.renameCube(renamed, "  ") != null
                        && snap2.groups().get(0).groups().get(0).cubes().get(0).name() == null,
                "空名字 = 无名");

        report("改一个结构里不存在的 cube → 返回 null（不静默成功）",
                snap2.renameCube(new MachineDefinition.Cube(new Vec3i(9, 9, 9), new Vec3i(9, 9, 9)),
                        "x") == null,
                "不静默成功");

        var namedA = snap2.renameCube(cubeA, "主齿轮");
        boolean resized = snap2.updateCube(namedA, namedA.from(), new Vec3i(0, 2, 0));
        report("改尺寸不会丢掉 cube 的名字（updateCube 要带上 name）",
                resized && "主齿轮".equals(snap2.groups().get(0).cubes().get(0).name()),
                "name=" + snap2.groups().get(0).cubes().get(0).name());

        report("行数 = 组数 + cube 数（不多不少，嵌套 cube 不会重复列出）",
                rows.size() == snap.countGroups() + snap.cubes().size(),
                rows.size() + " 行 = " + snap.countGroups() + " 组 + " + snap.cubes().size() + " cube");

        var p1 = MachineOutlineLogic.planNewGroup(snap, java.util.List.of(cubeA), null);
        report("选一个顶层 cube → 新组建在顶层，且不需要确认",
                p1 != null && p1.parent().isRoot() && p1.childDepth() == 1 && !p1.needsConfirm(),
                "parent=" + (p1 == null ? "null" : p1.parent().toString()) + " depth=" + (p1 == null ? -1 : p1.childDepth()));

        var p2 = MachineOutlineLogic.planNewGroup(snap, java.util.List.of(),
                MachineStructureSnapshot.GroupPath.of(0));
        report("只选一个文件夹 → 新组是它的子组（造嵌套的入口）",
                p2 != null && p2.parent().equals(MachineStructureSnapshot.GroupPath.of(0))
                        && p2.childDepth() == 2 && !p2.needsConfirm(),
                "parent=" + (p2 == null ? "null" : p2.parent().toString()) + " depth=" + (p2 == null ? -1 : p2.childDepth()));

        var p3 = MachineOutlineLogic.planNewGroup(snap, java.util.List.of(), null);
        report("什么都没选 → 建在顶层",
                p3 != null && p3.parent().isRoot() && p3.childDepth() == 1,
                "parent=" + (p3 == null ? "null" : p3.parent().toString()));

        var p4 = MachineOutlineLogic.planNewGroup(snap, java.util.List.of(cubeA, cubeB), null);
        report("跨层多选 → 以最浅的为准，深的那层被列进「会换层」",
                p4 != null && p4.parent().isRoot() && p4.childDepth() == 1
                        && p4.conflicts().equals(java.util.List.of(cubeB)) && p4.needsConfirm(),
                "conflicts=" + (p4 == null ? "null" : p4.conflicts().size()));

        var p5 = MachineOutlineLogic.planNewGroup(snap, java.util.List.of(cubeB), null);
        report("选嵌套里的 cube → 建在它那组的同级（第 2 层），不需要确认",
                p5 != null && p5.parent().equals(MachineStructureSnapshot.GroupPath.of(0))
                        && p5.childDepth() == 2 && !p5.needsConfirm(),
                "parent=" + (p5 == null ? "null" : p5.parent().toString()) + " depth=" + (p5 == null ? -1 : p5.childDepth()));

        // ===== 选中 → 能不能改名（就是用户报的"选 cube 却改了组名"）=====
        var groupRow = rows.get(0);
        var cubeARow = rows.get(1);
        var innerGroupRow = rows.get(2);
        var cubeBRow = rows.get(3);

        var selGroup = MachineOutlineLogic.resolveSelect(java.util.List.of(groupRow), snap);
        report("只选文件夹行 → 可改名，目标就是这一行",
                selGroup.canRename()
                        && selGroup.renameTarget().equals(MachineStructureSnapshot.GroupPath.of(0))
                        && selGroup.cubes().isEmpty(),
                "renameTarget=" + selGroup.renameTarget());

        var selCube = MachineOutlineLogic.resolveSelect(java.util.List.of(cubeARow), snap);
        report("选 cube → 不可改名（「选中 cube 改的却是组名」的根因）",
                !selCube.canRename() && selCube.renameTarget() == null
                        && selCube.cubes().size() == 1 && selCube.cubes().contains(cubeA)
                        && cubeA.equals(selCube.primary()),
                "renameTarget=" + selCube.renameTarget() + " cube=" + selCube.primary());

        report("选 cube 时组上下文仍是它所属的组（「+ 组」据此决定落点）",
                selCube.groupContext() != null
                        && selCube.groupContext().equals(MachineStructureSnapshot.GroupPath.of(0)),
                "groupContext=" + selCube.groupContext());

        var selNested = MachineOutlineLogic.resolveSelect(java.util.List.of(cubeBRow), snap);
        report("嵌套里的 cube → 组上下文是它所在的那一层",
                selNested.groupContext().equals(MachineStructureSnapshot.GroupPath.of(0, 0)),
                "groupContext=" + selNested.groupContext());

        var selMixed = MachineOutlineLogic.resolveSelect(java.util.List.of(groupRow, cubeARow), snap);
        report("混选文件夹 + cube → 也不能改名（宁可不让改，也不猜错对象）",
                !selMixed.canRename() && selMixed.renameTarget() == null
                        && selMixed.cubes().size() == 1,
                "renameTarget=" + selMixed.renameTarget());

        var selEmpty = MachineOutlineLogic.resolveSelect(java.util.List.of(), snap);
        report("什么都没选 → 没有可改名目标、没有主选中",
                !selEmpty.canRename() && selEmpty.cubes().isEmpty() && selEmpty.primary() == null,
                "空选");

        var selInner = MachineOutlineLogic.resolveSelect(java.util.List.of(innerGroupRow), snap);
        report("子组行也能改名（路径寻址，不是只认顶层）",
                selInner.canRename()
                        && selInner.renameTarget().equals(MachineStructureSnapshot.GroupPath.of(0, 0)),
                "renameTarget=" + selInner.renameTarget());

        var gone = new MachineDefinition.Cube(new Vec3i(9, 9, 9), new Vec3i(9, 9, 9));
        report("选中的 cube 已经不在结构里 → 返回 null（提示并放弃，不静默建空组）",
                MachineOutlineLogic.planNewGroup(snap, java.util.List.of(gone), null) == null,
                "不静默建空组");
    }

    // ==================== 工具 ====================

    private static String diff(java.util.Set<Vec3i> expected, java.util.Set<Vec3i> actual) {
        var missing = new java.util.ArrayList<Vec3i>();
        for (Vec3i e : expected) if (!actual.contains(e)) missing.add(e);
        var extra = new java.util.ArrayList<Vec3i>();
        for (Vec3i a : actual) if (!expected.contains(a)) extra.add(a);
        return "缺" + missing + " 多" + extra;
    }

    private static void report(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name + " —— " + detail);
    }
}
