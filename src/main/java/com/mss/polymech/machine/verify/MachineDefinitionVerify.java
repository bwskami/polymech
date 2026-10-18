package com.mss.polymech.machine.verify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.common.MachineGeometry;
import com.mss.polymech.machine.definition.MachineDefinition;
import com.mss.polymech.machine.definition.MachineStandard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 机器定义 JSON 标准的离线校验 —— 对应 {@code verifyMachineDefinitions} Gradle 任务。
 *
 * <h3>为什么这条校验是"迁移不改行为"的保险</h3>
 * 把形状/代理从 Java 搬进 JSON 是<strong>纯搬运</strong>：搬完之后，运行时看到的格子集合、
 * 每个代理接的槽位/储罐/开口面，都必须和搬运前<strong>一模一样</strong>。
 * 这类错误一旦漏进游戏，表现是"某个面突然不导流了"——极难靠肉眼发现。
 * 所以这里把搬运前的值写死在断言里（{@link #verifyBoilerMigration}），逐个比对。
 *
 * <h3>判据来源（不用被测实现自证）</h3>
 * <ul>
 *   <li><b>D1 覆盖完整</b>：{@code ModBlocks} 里声明的每台机器都必须有 JSON，且不能有孤儿 JSON
 *       （新增机器忘了写定义 / 删了机器忘了删定义都会被抓）；</li>
 *   <li><b>D2 结构自洽</b>：代理位置必须落在结构格子上（不能指向空气）、槽位/储罐下标非负不重复、
 *       坐标不重复；枚举用独立三重循环重算，与 {@code MachineGeometry} 对比；</li>
 *   <li><b>D3 锅炉语义不变</b>：把搬运前 Java 里的值写死比对（形状、4 个代理、开口面、格子数）；</li>
 *   <li><b>D4 其余 9 台 = 十字</b>：与搬运前的 {@code crossOffsets()} 一致，且都不声明代理；</li>
 *   <li><b>D5 架构不回退</b>：源码级检查——不得再出现 Java 形状重写、{@code MachineConfig} 不得再持有形状字段。</li>
 * </ul>
 *
 * <p>运行：{@code ./gradlew verifyMachineDefinitions}（退出码 0 = 全部 PASS）</p>
 */
public final class MachineDefinitionVerify {

    private static final Path MOD_BLOCKS = Path.of("src/main/java/com/mss/polymech/block/ModBlocks.java");
    private static final Path MACHINE_PKG = Path.of("src/main/java/com/mss/polymech/machine");
    private static final Path RESOURCES_DATA = Path.of("src/main/resources/data");

    /**
     * 内置定义目录与 classpath 路径<strong>必须由 {@code Polymech.MOD_ID} 推导</strong>，不能硬编码。
     *
     * <p>这里踩过一次真实事故：本模组的 mod id 是 {@code poly_mech}（<strong>不是</strong> {@code polymech}），
     * 而定义一度被写进 {@code data/polymech/machine/}。硬编码路径的校验器照样 PASS，
     * 运行时却读不到任何定义 → {@code getSideOffsets()} 返回空数组，
     * <strong>侧面方块与舱室全都不生成</strong>。教训：断言必须由被测代码的取值推导
     * （{@code Polymech.MOD_ID} 是编译期常量，离线引用安全），不能由自己写死的路径推导。</p>
     */
    private static final String CLASSPATH_ROOT = "/data/" + Polymech.MOD_ID + "/machine/";
    private static final Path JSON_DIR = RESOURCES_DATA.resolve(Polymech.MOD_ID).resolve("machine");

    /** 搬运前 {@code MachineConfig.crossOffsets()} 的值（9 台通用机器用它）。 */
    private static final Set<Vec3i> CROSS = Set.of(
            new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0), new Vec3i(0, 0, 1), new Vec3i(0, 0, -1));

    private static final String BOILER = "horizontal_steam_boiler";

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        Map<String, MachineDefinition> defs = loadAll();
        if (defs == null) {
            System.out.println("1 FAILED (共 1 项断言)");
            System.exit(1);
        }

        verifyCoverage(defs);
        verifySchemaAndGeometry(defs);
        verifyNestedGroups();
        verifyCubeNames();
        verifyBoilerMigration(defs);
        verifyCrossMachines(defs);
        verifyNoJavaShapeLeftovers();
        verifyStandard();
        verifyManifest();
        verifyLoadTimeValidation();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL PASS (" + checks + " 项断言)");
            System.exit(0);
        }
        System.out.println(failures + " FAILED (共 " + checks + " 项断言)");
        System.exit(1);
    }

    // ==================== 载入 ====================

    /** 读全部内置 JSON。任何一份解析失败都直接判死并返回 null。 */
    private static Map<String, MachineDefinition> loadAll() {
        System.out.println("==== 载入内置机器定义 ====");
        if (!Files.isDirectory(JSON_DIR)) {
            report("内置定义目录存在", false, "★ 找不到 " + JSON_DIR);
            return null;
        }
        Map<String, MachineDefinition> defs = new java.util.LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(JSON_DIR)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (Exception e) {
            report("列出内置定义目录", false, "★ " + e.getMessage());
            return null;
        }
        boolean ok = true;
        for (Path f : files) {
            String fileId = f.getFileName().toString().replace(".json", "");
            try {
                JsonObject json = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                MachineDefinition def = MachineDefinition.parse(fileId, json);
                // 文件名 ↔ id 字段必须一致（防复制粘贴改名只改一处）
                String idField = json.has("id") ? json.get("id").getAsString() : null;
                if (!fileId.equals(idField)) {
                    report(fileId + " 的 id 字段与文件名一致", false,
                            "★ 文件名=" + fileId + " 而 id 字段=" + idField);
                    ok = false;
                }
                defs.put(fileId, def);
            } catch (Exception e) {
                report(fileId + ".json 可解析", false, "★ " + e.getMessage());
                ok = false;
            }
        }
        report("全部内置 JSON 解析通过", ok, ok ? files.size() + " 份" : "★ 有解析失败");
        return ok ? defs : null;
    }

    // ==================== D1：覆盖完整 ====================

    private static void verifyCoverage(Map<String, MachineDefinition> defs) {
        System.out.println();
        System.out.println("==== D1 每台注册机器都有定义（且无孤儿 JSON）====");

        Set<String> declared = declaredMachineIds();
        report("从 ModBlocks 扫到机器声明", !declared.isEmpty(), declared.size() + " 台：" + new TreeSet<>(declared));

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(defs.keySet());
        report("每台机器都有同名 JSON", missing.isEmpty(),
                missing.isEmpty() ? declared.size() + " 台全覆盖" : "★ 缺定义：" + missing);

        Set<String> orphan = new TreeSet<>(defs.keySet());
        orphan.removeAll(declared);
        report("没有孤儿 JSON（删了机器要删定义）", orphan.isEmpty(),
                orphan.isEmpty() ? "无" : "★ 多余：" + orphan);

        // 结构非空：经注册的机器都必须有侧面方块，否则放出来是个光杆
        List<String> emptyOnes = new ArrayList<>();
        for (String id : declared) {
            MachineDefinition d = defs.get(id);
            if (d != null && d.isEmpty()) emptyOnes.add(id);
        }
        report("经注册的机器结构非空", emptyOnes.isEmpty(),
                emptyOnes.isEmpty() ? "都有 side_offsets 或 fill_regions" : "★ 空结构：" + emptyOnes);

        // 关键：运行时是从 classpath 读内置默认的（不是从源码目录）。
        // 这里用与 MachineDefinitionManager 完全相同的路径去取，能抓到
        // "JSON 写到错目录 / 没被 processResources 打包进 build/resources" 这类只在运行时炸的错。
        List<String> notOnClasspath = new ArrayList<>();
        for (String id : declared) {
            if (MachineDefinitionVerify.class.getResource(CLASSPATH_ROOT + id + ".json") == null) {
                notOnClasspath.add(id);
            }
        }
        report("内置 JSON 在 classpath 上可读（运行时真读得到）", notOnClasspath.isEmpty(),
                notOnClasspath.isEmpty() ? declared.size() + " 份都在 " + CLASSPATH_ROOT
                        : "★ 取不到：" + notOnClasspath);

        // 并且加载器用的路径必须与上面推导出来的<strong>完全一致</strong>（这条是整条链路的枢纽，
        // 所以用精确串比对，不用"含有几个子串"的宽松匹配——事故就出在这里）。
        String mgr = readSource(MACHINE_PKG.resolve("definition/MachineDefinitionManager.java"));
        String exactRoot = "private static final String BUILTIN_ROOT = \"/data/\" + Polymech.MOD_ID + \"/machine/\";";
        boolean samePath = mgr != null && mgr.contains(exactRoot);
        report("加载器的内置路径与上面推导的一致（精确比对）", samePath,
                samePath ? "BUILTIN_ROOT = " + CLASSPATH_ROOT
                        : "★ MachineDefinitionManager 里找不到: " + exactRoot);

        // 命名空间唯一性：resources/data 下只允许 <MOD_ID>/machine/ 这一个机器定义目录。
        // 这条专治"定义写错命名空间"——不管写错成哪个 mod id 都会被抓到，
        // 而不是像硬编码路径那样"自己验自己"。
        List<String> strayDirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(RESOURCES_DATA)) {
            for (Path ns : s.filter(Files::isDirectory).toList()) {
                if (Files.isDirectory(ns.resolve("machine"))
                        && !ns.getFileName().toString().equals(Polymech.MOD_ID)) {
                    strayDirs.add(ns.getFileName() + "/machine");
                }
            }
        } catch (Exception e) {
            strayDirs.add("★ 遍历 " + RESOURCES_DATA + " 失败: " + e.getMessage());
        }
        report("没有别的命名空间下的 machine 定义（防写错 mod id）", strayDirs.isEmpty(),
                strayDirs.isEmpty() ? "只有 " + Polymech.MOD_ID + "/machine（MOD_ID=" + Polymech.MOD_ID + "）"
                        : "★ 多余目录：" + strayDirs);
    }

    /**
     * 扫 {@code ModBlocks.java} 里的机器清单。
     *
     * <p>S3 之后一台机器一行 {@code machine("id", ...)}（之前是内联的
     * {@code MachineConfig.builder("id")} 样板）。只认行首缩进后的调用，
     * 避免把注释/文档里举例的 {@code machine("id", ...)} 也算进来。</p>
     */
    private static Set<String> declaredMachineIds() {
        Set<String> ids = new LinkedHashSet<>();
        String src = readSource(MOD_BLOCKS);
        if (src == null) return ids;
        Matcher m = Pattern.compile("^\\s+machine\\(\"([a-z0-9_]+)\"", Pattern.MULTILINE).matcher(src);
        while (m.find()) ids.add(m.group(1));
        return ids;
    }

    // ==================== D2：结构自洽 ====================

    private static void verifySchemaAndGeometry(Map<String, MachineDefinition> defs) {
        System.out.println();
        System.out.println("==== D2 结构自洽（代理落在格子上 / 坐标不重复 / 枚举一致）====");

        List<String> proxyOffStructure = new ArrayList<>();
        List<String> dupOffsets = new ArrayList<>();
        List<String> proxyOnController = new ArrayList<>();
        List<String> enumMismatch = new ArrayList<>();
        List<String> dupFaces = new ArrayList<>();

        for (var e : defs.entrySet()) {
            String id = e.getKey();
            MachineDefinition d = e.getValue();

            // side_offsets 不重复
            Set<Vec3i> seen = new HashSet<>();
            for (Vec3i v : d.sideOffsets()) {
                if (!seen.add(v)) dupOffsets.add(id + " " + fmt(v));
            }

            // 代理必须落在结构上（否则这个口永远不会被放置，等于写错了坐标）
            for (Vec3i at : d.itemProxies().keySet()) {
                if (!MachineGeometry.containsLocal(at, d.sideOffsets(), d.fillRegions())) {
                    proxyOffStructure.add(id + " item " + fmt(at));
                }
                if (at.equals(Vec3i.ZERO)) proxyOnController.add(id + " item " + fmt(at));
            }
            for (Vec3i at : d.fluidProxies().keySet()) {
                if (!MachineGeometry.containsLocal(at, d.sideOffsets(), d.fillRegions())) {
                    proxyOffStructure.add(id + " fluid " + fmt(at));
                }
                if (at.equals(Vec3i.ZERO)) proxyOnController.add(id + " fluid " + fmt(at));
            }

            // 开口面不重复
            for (Vec3i at : d.itemProxies().keySet()) {
                Direction[] f = d.itemProxy(at).faces();
                if (f != null && new HashSet<>(Arrays.asList(f)).size() != f.length) {
                    dupFaces.add(id + " item " + fmt(at));
                }
            }
            for (Vec3i at : d.fluidProxies().keySet()) {
                Direction[] f = d.fluidProxy(at).faces();
                if (f != null && new HashSet<>(Arrays.asList(f)).size() != f.length) {
                    dupFaces.add(id + " fluid " + fmt(at));
                }
            }

            // 枚举与独立三重循环重算一致
            Set<Vec3i> mine = new HashSet<>(MachineGeometry.enumerateLocalOffsets(d.sideOffsets(), d.fillRegions()));
            Set<Vec3i> independent = independentCells(d.sideOffsets(), d.fillRegions());
            if (!mine.equals(independent)) {
                enumMismatch.add(id + " 缺" + minus(independent, mine) + " 多" + minus(mine, independent));
            }
        }

        report("side_offsets 无重复坐标", dupOffsets.isEmpty(), dupOffsets.isEmpty() ? "无" : "★ " + dupOffsets);
        report("代理位置都落在结构格子上", proxyOffStructure.isEmpty(),
                proxyOffStructure.isEmpty() ? "全部命中" : "★ 悬空：" + proxyOffStructure);
        report("代理不落在控制器格（(0,0,0) 是主方块，不是侧面方块）", proxyOnController.isEmpty(),
                proxyOnController.isEmpty() ? "无" : "★ " + proxyOnController);
        report("开口面声明无重复", dupFaces.isEmpty(), dupFaces.isEmpty() ? "无" : "★ " + dupFaces);
        report("枚举结果与独立展开一致", enumMismatch.isEmpty(),
                enumMismatch.isEmpty() ? defs.size() + " 台一致" : "★ " + enumMismatch);
    }

    /** 独立实现（三重循环 + 离散追加），刻意不复用 MachineGeometry。 */
    private static Set<Vec3i> independentCells(Vec3i[] offsets, Vec3i[][] regions) {
        Set<Vec3i> out = new HashSet<>();
        if (regions != null) {
            for (Vec3i[] r : regions) {
                int x0 = Math.min(r[0].getX(), r[1].getX()), x1 = Math.max(r[0].getX(), r[1].getX());
                int y0 = Math.min(r[0].getY(), r[1].getY()), y1 = Math.max(r[0].getY(), r[1].getY());
                int z0 = Math.min(r[0].getZ(), r[1].getZ()), z1 = Math.max(r[0].getZ(), r[1].getZ());
                for (int x = x0; x <= x1; x++) {
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) out.add(new Vec3i(x, y, z));
                    }
                }
            }
        }
        if (offsets != null) out.addAll(Arrays.asList(offsets));
        return out;
    }

    // ==================== D3：锅炉语义不变（迁移前后逐项比对）====================

    /**
     * 期望值<strong>写死</strong>自迁移前的 {@code HorizontalSteamBoilerBlock}
     * （{@code getSideOffsets/getFillRegions/getItemProxy/getFluidProxy}）。
     * 这些数字改了必须是有意为之，不能是搬运时抄错。
     */
    private static void verifyBoilerMigration(Map<String, MachineDefinition> defs) {
        System.out.println();
        System.out.println("==== D3 锅炉迁移前后语义一致（期望值写死自旧 Java 实现）====");

        MachineDefinition d = defs.get(BOILER);
        if (d == null) {
            report("存在锅炉定义", false, "★ 没有 " + BOILER + ".json");
            return;
        }

        // 形状：旧实现是「3 个离散侧偏移 + 1 个 3×3×5 填充区间」共 48 格。
        // schema 2 把两者统一成结构树里的 cube（一个方块 = 一个 cube），
        // 所以断言改成"这 48 格一个不少"，而不是继续检查已不存在的字段。
        Set<Vec3i> cellSet = new HashSet<>(Arrays.asList(d.sideOffsets()));
        Set<Vec3i> wantDiscrete = Set.of(new Vec3i(0, 3, 0), new Vec3i(0, 3, 2), new Vec3i(0, 4, 2));
        report("旧的 3 个离散侧偏移仍在结构里",
                cellSet.containsAll(wantDiscrete),
                "命中 " + wantDiscrete.stream().filter(cellSet::contains).count() + "/3");

        boolean regionOk = true;
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (!cellSet.contains(new Vec3i(x, y, z))) regionOk = false;
                }
            }
        }
        report("旧的填充区间 (-1,0,-2)→(1,2,2) 的 45 格仍在结构里", regionOk,
                regionOk ? "45 格齐全" : "★ 有缺失");

        // 格子数：区间 3×3×5=45 + 离散 3 = 48（离散三点都在区间外，故无重复）
        int cells = new HashSet<>(MachineGeometry.enumerateLocalOffsets(d.sideOffsets(), d.fillRegions())).size();
        report("结构共 48 格（45 区间 + 3 离散）", cells == 48, cells + " 格");

        // 世界侧方块位置：排除控制器自身 → 47
        BlockPos[] sidePos = MachineGeometry.sidePositions(
                BlockPos.ZERO, Direction.NORTH, d.sideOffsets(), d.fillRegions());
        report("放置的侧方块位置 = 47（已排除控制器自身）", sidePos.length == 47, sidePos.length + " 个");
        boolean noOrigin = Arrays.stream(sidePos).noneMatch(BlockPos.ZERO::equals);
        report("侧方块位置不含原点（不会拿侧边方块盖掉主方块）", noOrigin,
                noOrigin ? "已排除" : "★ 含 (0,0,0)");

        // 物品代理：燃料 (0,1,-2) → 槽[2] INPUT；灰烬 (0,0,-2) → 槽[5] OUTPUT
        var fuel = d.itemProxy(new Vec3i(0, 1, -2));
        report("物品 (0,1,-2) → 槽[2] INPUT（燃料）",
                fuel != null && Arrays.equals(fuel.slots(), new int[]{2})
                        && fuel.io() == BaseMachineBlock.ProxyIO.INPUT && fuel.faces() == null,
                fuel == null ? "★ 缺" : Arrays.toString(fuel.slots()) + " " + fuel.io() + " faces=" + Arrays.toString(fuel.faces()));

        var ash = d.itemProxy(new Vec3i(0, 0, -2));
        report("物品 (0,0,-2) → 槽[5] OUTPUT（灰烬）",
                ash != null && Arrays.equals(ash.slots(), new int[]{5})
                        && ash.io() == BaseMachineBlock.ProxyIO.OUTPUT && ash.faces() == null,
                ash == null ? "★ 缺" : Arrays.toString(ash.slots()) + " " + ash.io());

        report("物品代理恰好 2 个", d.itemProxies().size() == 2, d.itemProxies().size() + " 个");

        // 流体代理：水 (0,0,1) → 罐[0] INPUT；蒸汽 (0,3,0) → 罐[1] OUTPUT 仅 UP
        var water = d.fluidProxy(new Vec3i(0, 0, 1));
        report("流体 (0,0,1) → 罐[0] INPUT（水）",
                water != null && Arrays.equals(water.tanks(), new int[]{0})
                        && water.io() == BaseMachineBlock.ProxyIO.INPUT && water.faces() == null,
                water == null ? "★ 缺" : Arrays.toString(water.tanks()) + " " + water.io());

        var steam = d.fluidProxy(new Vec3i(0, 3, 0));
        report("流体 (0,3,0) → 罐[1] OUTPUT 仅 UP 面（蒸汽）",
                steam != null && Arrays.equals(steam.tanks(), new int[]{1})
                        && steam.io() == BaseMachineBlock.ProxyIO.OUTPUT
                        && steam.faces() != null && steam.faces().length == 1
                        && steam.faces()[0] == Direction.UP,
                steam == null ? "★ 缺" : Arrays.toString(steam.tanks()) + " " + steam.io()
                        + " faces=" + Arrays.toString(steam.faces()));

        report("流体代理恰好 2 个", d.fluidProxies().size() == 2, d.fluidProxies().size() + " 个");

        // 说明文字（note）必须能往返：编辑器"读定义 → 保存"若丢了注释，定义就慢慢变成天书。
        // 这里在 JSON 层验证 parse→toJson→parse 后注释仍在（快照层怎么拷注释另有 M 系列覆盖）。
        JsonObject again = JsonParser.parseString(d.toJson().toString()).getAsJsonObject();
        MachineDefinition back = MachineDefinition.parse(BOILER, again);
        List<String> lostNotes = new ArrayList<>();
        for (Vec3i at : List.of(new Vec3i(0, 1, -2), new Vec3i(0, 0, -2), new Vec3i(0, 0, 1), new Vec3i(0, 3, 0))) {
            if (back.note(at) == null || !back.note(at).equals(d.note(at))) lostNotes.add(fmt(at));
        }
        report("4 个代理的说明文字往返不丢", lostNotes.isEmpty(),
                lostNotes.isEmpty() ? "注释保留" : "★ 丢了：" + lostNotes);

        // 开口面语义：faces={UP} 的运行时含义是"只有世界 UP 方向放行"。
        // 这里刻意<strong>不</strong>调 allowsWorldFace：它转调 BaseMachineBlock.rotateDirection（静态方法），
        // 会触发 BaseMachineBlock 的静态初始化（FACING = BlockStateProperties.HORIZONTAL_FACING
        // → NoteBlockInstrument → SoundEvents → 注册表），离线未 bootstrap 直接抛 Not bootstrapped。
        // 改测它底层的旋转原语 MachineGeometry（纯坐标数学、离线安全）：
        // 竖直面（UP）与水平朝向无关，旋转后仍是 UP；水平面 NORTH 在 facing=NORTH 下仍是 NORTH。
        if (steam != null && steam.faces() != null) {
            Direction upRot = MachineGeometry.rotateDirection(Direction.UP, Direction.NORTH);
            Direction northRot = MachineGeometry.rotateDirection(Direction.NORTH, Direction.NORTH);
            report("faces={UP} 的运行时含义 = 只放行世界 UP（旋转原语正确）",
                    upRot == Direction.UP && northRot == Direction.NORTH,
                    "UP→" + upRot + " NORTH→" + northRot);
        }
    }

    // ==================== D4：其余 9 台 = 十字 ====================

    private static void verifyCrossMachines(Map<String, MachineDefinition> defs) {
        System.out.println();
        System.out.println("==== D4 其余机器 = 搬运前的 crossOffsets()（且无代理）====");

        List<String> bad = new ArrayList<>();
        List<String> withProxy = new ArrayList<>();
        int n = 0;
        for (var e : defs.entrySet()) {
            if (e.getKey().equals(BOILER)) continue;
            n++;
            MachineDefinition d = e.getValue();
            Set<Vec3i> got = new HashSet<>(Arrays.asList(d.sideOffsets()));
            boolean shapeOk = got.equals(CROSS)
                    && (d.fillRegions() == null || d.fillRegions().length == 0);
            if (!shapeOk) bad.add(e.getKey() + " " + fmtArr(d.sideOffsets()));
            if (!d.itemProxies().isEmpty() || !d.fluidProxies().isEmpty()) {
                withProxy.add(e.getKey());
            }
        }
        report("全部 = 十字四格且无填充区间", bad.isEmpty(),
                bad.isEmpty() ? n + " 台一致" : "★ " + bad);
        report("这些机器不声明代理（与搬运前一致）", withProxy.isEmpty(),
                withProxy.isEmpty() ? "无代理" : "★ 多出代理：" + withProxy);
    }

    // ==================== D5：架构不回退（源码级）====================

    /**
     * 钉死"定义只有一处"：Java 里不得再出现形状/代理重写，
     * {@code MachineConfig} 不得再持有形状字段——否则又变成两处定义、必然对不上。
     */
    // ==================== M19：机器编写规范（键词表 + 行为词表） ====================

    /**
     * 规范必须是<strong>可执行的检查</strong>，不是文档里的约定：
     * 对 10 份内置 JSON 真跑一遍 {@code MachineStandard.validate}，
     * 并用"故意写错"的样本证明它<u>确实会报错</u>（否则一个永远返回空的校验器也能全绿）。
     *
     * <p>另做一件关键的事：把行为词表与<strong>真实源码</strong>交叉核对
     * （{@code Abstract*BlockEntity} 基类真的存在），防止词表与代码脱节。</p>
     */
    private static void verifyStandard() {
        System.out.println();
        System.out.println("==== M19 机器编写规范 ====");

        var std = com.mss.polymech.machine.definition.MachineStandard.class;

        var files = new java.util.ArrayList<Path>();
        if (!Files.isDirectory(JSON_DIR)) {
            report("能定位到内置机器 JSON 目录", false, "缺 " + JSON_DIR);
            return;
        }
        try (var s = Files.list(JSON_DIR)) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
        } catch (java.io.IOException e) {
            report("能列出内置机器 JSON", false, e.toString());
            return;
        }
        report("内置机器 JSON 有 10 份", files.size() == 10, "实际 " + files.size());

        // 1) 10 份真 JSON 全部合规
        var bad = new java.util.ArrayList<String>();
        var usedBehaviors = new java.util.LinkedHashSet<String>();
        for (Path p : files) {
            String id = p.getFileName().toString().replace(".json", "");
            JsonObject json;
            try {
                json = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            } catch (Exception e) {
                bad.add(id + ": JSON 解析失败 " + e.getMessage());
                continue;
            }
            bad.addAll(com.mss.polymech.machine.definition.MachineStandard.validate(json, id));
            if (json.has("behavior") && json.get("behavior").isJsonPrimitive()) {
                usedBehaviors.add(json.get("behavior").getAsString());
            }
        }
        report("10 份内置机器 JSON 全部符合规范（无未知键 / 类型对 / 行为 id 在词表内）",
                bad.isEmpty(), bad.isEmpty() ? "全部合规" : "★ " + bad);

        // 2) 反面样本：校验器必须真的会报错（否则"全绿"没有意义）
        JsonObject ok = com.google.gson.JsonParser.parseString(
                "{\"schema_version\":3,\"side_offsets\":[[1,0,0]]}").getAsJsonObject();
        report("合规样本无问题（基线）",
                com.mss.polymech.machine.definition.MachineStandard.validate(ok, "t").isEmpty(),
                "空 = 合规");

        JsonObject typo = com.google.gson.JsonParser.parseString(
                "{\"schema_version\":3,\"side_offsets\":[[1,0,0]],\"side_offset\":[]}").getAsJsonObject();
        report("把 side_offsets 敲成 side_offset → 报「不认识的键」",
                issueMentions(com.mss.polymech.machine.definition.MachineStandard.validate(typo, "t"),
                        "不认识的键"),
                "抓拼写错误");

        JsonObject older = JsonParser.parseString(
                "{\"schema_version\":2,\"side_offsets\":[[1,0,0]]}").getAsJsonObject();
        report("旧版本 schema（2）仍被接受 —— schema 3 向后兼容，不该报噪声",
                MachineStandard.validate(older, "t").isEmpty(),
                MachineStandard.validate(older, "t").toString());

        JsonObject noSides = com.google.gson.JsonParser.parseString("{}").getAsJsonObject();
        report("缺 side_offsets → 报「缺少必填键」",
                issueMentions(com.mss.polymech.machine.definition.MachineStandard.validate(noSides, "t"),
                        "缺少必填键"),
                "必填检查");

        JsonObject oldSchema = com.google.gson.JsonParser.parseString(
                "{\"side_offsets\":[],\"schema_version\":99}").getAsJsonObject();
        report("未来版本的 schema_version（99）→ 报错（要求迁移）",
                issueMentions(com.mss.polymech.machine.definition.MachineStandard.validate(oldSchema, "t"),
                        "schema_version"),
                "版本闸门");

        JsonObject badBehavior = com.google.gson.JsonParser.parseString(
                "{\"side_offsets\":[],\"behavior\":\"steam_boiler2\"}").getAsJsonObject();
        report("行为 id 不在封闭词表里 → 报错（否则会静默变成什么都不做的机器）",
                issueMentions(com.mss.polymech.machine.definition.MachineStandard.validate(badBehavior, "t"),
                        "不在词表里"),
                "封闭词表");

        JsonObject badTier = com.google.gson.JsonParser.parseString(
                "{\"side_offsets\":[],\"tier\":-1}").getAsJsonObject();
        report("tier 为负 → 报错",
                !com.mss.polymech.machine.definition.MachineStandard.validate(badTier, "t").isEmpty(),
                "取值约束");

        JsonObject emptyStr = com.google.gson.JsonParser.parseString(
                "{\"side_offsets\":[],\"lang\":\"  \"}").getAsJsonObject();
        report("lang 是空白串 → 报错（声明了却没写等于写错）",
                !com.mss.polymech.machine.definition.MachineStandard.validate(emptyStr, "t").isEmpty(),
                "空串检查");

        // 3) 行为词表与真实源码交叉核对：每个行为声称的 BE 基类必须真的存在
        var bases = new java.util.LinkedHashSet<String>();
        try (var walk = Files.walk(MACHINE_PKG)) {
            walk.filter(p -> p.toString().endsWith("BlockEntity.java")).forEach(p -> {
                try {
                    String src = Files.readString(p);
                    var m = java.util.regex.Pattern
                            .compile("(?:abstract\\s+)?class\\s+(\\w+)\\s+extends").matcher(src);
                    while (m.find()) bases.add(m.group(1));
                } catch (Exception ignored) {
                    // 读不到就当不存在，交叉核对会报出来
                }
            });
        } catch (java.io.IOException e) {
            report("能扫描机器 BE 源码", false, e.toString());
            return;
        }
        var vocabIssues = com.mss.polymech.machine.definition.MachineStandard
                .validateVocabulary(usedBehaviors, bases);
        report("行为词表里每个 id 对应的 BE 基类都真实存在（词表没和代码脱节）",
                vocabIssues.stream().noneMatch(s -> s.contains("找不到这个类")),
                "交叉核对 " + bases.size() + " 个 BE 类");
        report("行为词表没有死条目（有机器标注 behavior 时，词表里的 id 都必须有机器在用）",
                usedBehaviors.isEmpty()
                        || vocabIssues.stream().noneMatch(s -> s.contains("死条目")),
                usedBehaviors.isEmpty() ? "尚未标注 behavior（S2 待做）" : "在用 " + usedBehaviors);
        report("规范是可执行的检查而不是文档（validate 返回具体问题文案，能 FAIL）",
                std != null && !com.mss.polymech.machine.definition.MachineStandard
                        .validate(typo, "t").isEmpty(),
                "可 FAIL");

        // 4) 最强的一条：JSON 里写的 behavior 必须和**真实 BE 继承关系**一致。
        //    做法：由机器 id 推出 BE 类名（steam_hammer → SteamHammerBlockEntity），
        //    在源码里找它 extends 谁，再按 BEHAVIOR_BASE 反推应该是什么行为。
        //    这样"JSON 声明"与"代码实际行为"就不会各说各话（断言由代码推导，不由实现推导）。
        var mismatched = new java.util.ArrayList<String>();
        var noJson = new java.util.ArrayList<String>();
        for (Path p : files) {
            String id = p.getFileName().toString().replace(".json", "");
            JsonObject json;
            try {
                json = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }
            if (!json.has("behavior") || !json.get("behavior").isJsonPrimitive()) {
                noJson.add(id);
                continue;
            }
            String declared = json.get("behavior").getAsString();
            String expected = expectedBehaviorOf(id);
            if (expected == null) {
                mismatched.add(id + ": 找不到对应的 BE 类，无法核对（文件名规则变了？）");
            } else if (!expected.equals(declared)) {
                mismatched.add(id + ": JSON 写 " + declared + "，但 BE 继承关系说明是 " + expected);
            }
        }
        report("每份 JSON 都标了 behavior", noJson.isEmpty(),
                noJson.isEmpty() ? "10/10" : "★ 缺: " + noJson);
        report("JSON 的 behavior 与真实 BE 继承关系一致（声明不会和代码脱节）",
                mismatched.isEmpty(), mismatched.isEmpty() ? "全部一致" : "★ " + mismatched);

        // 5) meta 往返 —— 这是防"编辑器一保存就把作者的 behavior 静默删掉"的那道闸。
        //    编辑器保存走的是 loadFrom → toDefinition → toJson 这条路，所以必须连它一起测。
        JsonObject withMeta = com.google.gson.JsonParser.parseString(
                "{\"schema_version\":3,\"id\":\"t\",\"side_offsets\":[],"
                        + "\"behavior\":\"steam_boiler\",\"tier\":2,\"family\":\"steam\"}")
                .getAsJsonObject();
        MachineDefinition md = MachineDefinition.parse("t", withMeta);
        String direct = md.toJson().toString();
        report("带 behavior/tier/family 的定义经 parse→toJson 原样保留",
                direct.contains("\"behavior\":\"steam_boiler\"")
                        && direct.contains("\"tier\":2")
                        && direct.contains("\"family\":\"steam\""),
                direct);
        var snap = new com.mss.polymech.client.gui.machine.editor.MachineStructureSnapshot("t");
        snap.loadFrom(md);
        String viaSnap = snap.toDefinition().toJson().toString();
        report("快照路径（编辑器保存真正走的那条）也保留 meta —— 保存不会丢 behavior",
                viaSnap.contains("\"behavior\":\"steam_boiler\"")
                        && viaSnap.contains("\"tier\":2")
                        && viaSnap.contains("\"family\":\"steam\""),
                viaSnap);
        report("schema_version 总是写进 JSON（规范要求它在）",
                direct.contains("\"schema_version\":3"), "版本可追溯");
    }

    /** 由机器 id 推出它该有的行为：读 BE 源码看 extends 谁，再按 BEHAVIOR_BASE 反查。 */
    @javax.annotation.Nullable
    private static String expectedBehaviorOf(String id) {
        String pascal = new StringBuilder()
                .append(Character.toUpperCase(id.charAt(0)))
                .append(id.substring(1)).toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pascal.length(); i++) {
            char c = pascal.charAt(i);
            if (c == '_' && i + 1 < pascal.length()) {
                sb.append(Character.toUpperCase(pascal.charAt(++i)));
            } else {
                sb.append(c);
            }
        }
        String beName = sb + "BlockEntity";
        final String[] parent = new String[1];
        try (var walk = Files.walk(MACHINE_PKG)) {
            walk.filter(p -> p.getFileName().toString().equals(beName + ".java")).findFirst()
                    .ifPresent(p -> {
                        try {
                            String src = Files.readString(p);
                            var m = java.util.regex.Pattern
                                    .compile("class\\s+" + beName + "\\s+extends\\s+(\\w+)")
                                    .matcher(src);
                            if (m.find()) parent[0] = m.group(1);
                        } catch (Exception ignored) {
                            // 读不到就返回 null，由调用方报"无法核对"
                        }
                    });
        } catch (java.io.IOException e) {
            return null;
        }
        if (parent[0] == null) return null;
        for (var e : com.mss.polymech.machine.definition.MachineStandard.BEHAVIOR_BASE.entrySet()) {
            if (e.getValue().equals(parent[0])) return e.getKey();
        }
        return null;
    }

    // ==================== M20：机器清单 ↔ JSON 一一对应 ====================

    /**
     * S3 把 10 段手写注册样板收成"一台机器一行 {@code machine("id", ...)}"。
     * 清单化的风险是<strong>清单与 JSON 各说各话</strong>：加了 JSON 忘了加清单（机器不注册），
     * 或加了清单忘了加 JSON（机器注册出来但没结构，侧面方块与舱室全不生成）。
     *
     * <p>这两种错都<strong>不会编译报错、也不会在日志里喊</strong>，所以必须离线钉住：
     * 直接读 {@code ModBlocks} 源码里的 {@code machine("...")} 调用，与
     * {@code machine/*.json} 的文件名逐一比对，双向都要一致。</p>
     */
    private static void verifyManifest() {
        System.out.println();
        System.out.println("==== M20 机器清单 ↔ JSON ====");

        String src;
        try {
            src = Files.readString(MOD_BLOCKS);
        } catch (java.io.IOException e) {
            report("能读到 ModBlocks 源码", false, e.toString());
            return;
        }

        // 清单里的 id（按出现顺序，便于看重复）。锚定行首缩进，别把 javadoc 里举例的算进来。
        var listed = new java.util.ArrayList<String>();
        var m = java.util.regex.Pattern
                .compile("^\\s+machine\\(\"([a-z0-9_]+)\"", java.util.regex.Pattern.MULTILINE)
                .matcher(src);
        while (m.find()) listed.add(m.group(1));

        var listedSet = new java.util.LinkedHashSet<>(listed);
        report("清单里没有重复的机器 id（重复会让后者覆盖前者）",
                listedSet.size() == listed.size(),
                listed.size() + " 行 / " + listedSet.size() + " 个唯一 id");

        var jsonIds = new java.util.LinkedHashSet<String>();
        try (var s = Files.list(JSON_DIR)) {
            s.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> jsonIds.add(p.getFileName().toString().replace(".json", "")));
        } catch (java.io.IOException e) {
            report("能列出机器 JSON", false, e.toString());
            return;
        }

        var onlyListed = new java.util.TreeSet<>(listedSet);
        onlyListed.removeAll(jsonIds);
        var onlyJson = new java.util.TreeSet<>(jsonIds);
        onlyJson.removeAll(listedSet);

        report("清单里的每台机器都有对应的 JSON（加了清单没加 JSON → 机器没结构）",
                onlyListed.isEmpty(),
                onlyListed.isEmpty() ? "全部有 JSON" : "★ 缺 JSON: " + onlyListed);
        report("每份 JSON 都在清单里（加了 JSON 没加清单 → 机器根本不注册）",
                onlyJson.isEmpty(),
                onlyJson.isEmpty() ? "全部已注册" : "★ 没注册: " + onlyJson);
        report("清单与 JSON 完全一一对应（双向一致）",
                listedSet.equals(jsonIds),
                "清单 " + listedSet.size() + " / JSON " + jsonIds.size());

        // 10 个公开字段必须都从清单取（而不是又写回手写注册），否则清单会变成摆设
        var regFields = new java.util.ArrayList<String>();
        var rf = java.util.regex.Pattern
                .compile("public static final MachineRegistrar\\.MachineRegistration (\\w+) = reg\\(\"")
                .matcher(src);
        while (rf.find()) regFields.add(rf.group(1));
        report("10 个公开机器字段都改成从清单取（reg(...)），清单不是摆设",
                regFields.size() == 10,
                "实际 " + regFields.size() + " 个: " + regFields);
        report("已经没有残留的手写 registerBlock 样板（都走清单了）",
                !src.contains("MachineRegistrar.registerBlock(\n                    MachineConfig.builder"),
                "注册只剩 machine(...) 一处");
    }

    // ==================== M21：加载期校验（整合包作者改坏 config 的反馈渠道） ====================

    /**
     * {@code MachineDefinition.parse} <strong>不拒绝未知键</strong>，所以作者手改
     * {@code config/poly_mech/machines/*.json} 时把键名敲错、把 behavior 写成词表外的值，
     * 都会"正常加载、机器照常出现，只是悄悄少了数据"。加载期校验是唯一反馈渠道。
     *
     * <p>这里做两件事：① 确认加载器<strong>两条读入路径都调了校验</strong>（内置 + config，
     * 只补一条就等于作者改的那条没人管）；② 用作者最可能犯的三种错当样本，
     * 确认校验真的会报出来（而不是"调了个永远返回空的方法"）。</p>
     */
    private static void verifyLoadTimeValidation() {
        System.out.println();
        System.out.println("==== M21 加载期校验 ====");

        String src = readSource(Path.of("src/main/java/com/mss/polymech/machine/definition/MachineDefinitionManager.java"));
        if (src == null) {
            report("能读到 MachineDefinitionManager 源码", false, "读不到");
            return;
        }
        report("内置 JSON 读入路径会走规范校验",
                src.contains("validateAgainstStandard(\"内置 \" + path, id, json)"),
                "内置也要校验（离线过了，线上再兜一层）");
        report("config JSON 读入路径也会走规范校验（作者改的就是这条）",
                src.contains("validateAgainstStandard(\"config \" + file.getFileName(), id, json)"),
                "config 校验到位");
        report("校验结果会记进 ERRORS（由 /polymech machinereload 打印）",
                src.contains("ERRORS.add(where + \"：\" + issue)"),
                "有反馈渠道，不是静默忽略");

        // 作者最可能犯的三种错，逐一确认会被报出来
        JsonObject typoKey = com.google.gson.JsonParser.parseString(
                "{\"schema_version\":3,\"side_offset\":[[1,0,0]]}").getAsJsonObject();
        JsonObject badBehavior = com.google.gson.JsonParser.parseString(
                "{\"schema_version\":3,\"side_offsets\":[[1,0,0]],\"behavior\":\"turbine\"}")
                .getAsJsonObject();
        JsonObject badSchema = com.google.gson.JsonParser.parseString(
                "{\"schema_version\":99,\"side_offsets\":[[1,0,0]]}").getAsJsonObject();

        var typoIssues = com.mss.polymech.machine.definition.MachineStandard.validate(typoKey, "t");
        var behIssues = com.mss.polymech.machine.definition.MachineStandard.validate(badBehavior, "t");
        var schIssues = com.mss.polymech.machine.definition.MachineStandard.validate(badSchema, "t");

        report("作者把键名敲错（side_offsets → side_offset）会被报出来",
                issueMentions(typoIssues, "side_offset") && issueMentions(typoIssues, "side_offsets"),
                typoIssues.toString());
        report("作者把 behavior 写成词表外的值会被报出来",
                issueMentions(behIssues, "behavior"),
                behIssues.toString());
        report("作者写了未来版本的 schema_version 会被报出来",
                issueMentions(schIssues, "schema_version"),
                schIssues.toString());

        // 合规样本不能误报（否则作者会被噪声淹没，等于没有校验）
        JsonObject good = com.google.gson.JsonParser.parseString(
                "{\"schema_version\":3,\"side_offsets\":[[1,0,0]],\"behavior\":\"processing\"}")
                .getAsJsonObject();
        report("合规的 config 样本不会误报（有噪声的校验等于没有校验）",
                com.mss.polymech.machine.definition.MachineStandard.validate(good, "t").isEmpty(),
                "空 = 合规");
    }

    /**
     * schema 3：组可嵌套（组里同时有 {@code cubes} 与 {@code groups}，照 Blockbench 的 bone）。
     *
     * <p>钉四件事：递归读进来、递归展平（不丢嵌套 cube）、递归序列化（读写对称）、
     * 组内键的封闭词表真的会报错。前三件是"静默丢数据"的高发区 ——
     * 只读一层的话机器放出来会缺一块，而且不报任何错。</p>
     */
    private static void verifyNestedGroups() {
        System.out.println();
        System.out.println("==== schema 3：嵌套组 ====");

        String json = """
                {"schema_version":3,
                 "groups":[{"name":"主体","cubes":[{"from":[0,0,0],"to":[0,0,0]}],
                            "groups":[{"name":"外壳","cubes":[{"from":[1,0,0],"to":[1,0,0]}]}]}]}
                """;
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();

        report("嵌套组定义通过规范校验", MachineStandard.validate(o, "t").isEmpty(),
                MachineStandard.validate(o, "t").toString());

        MachineDefinition def = MachineDefinition.parse("t", o);
        report("嵌套 cube 被递归展平（不是只读一层）",
                def.cubes().size() == 2 && def.sideOffsets().length == 2,
                "cube=" + def.cubes().size() + " 格=" + def.sideOffsets().length);
        report("组体积递归到子组（父 = 1 + 1 = 2）",
                def.groups().get(0).volume() == 2
                        && def.groups().get(0).groups().get(0).volume() == 1,
                "父=" + def.groups().get(0).volume());
        report("嵌套结构导出再读回一致（读写对称、子组不丢）",
                MachineDefinition.parse("t", def.toJson()).toJson().equals(def.toJson()),
                "round-trip");
        report("导出保留了子组的 name 与 cube",
                def.toJson().getAsJsonArray("groups").get(0).getAsJsonObject()
                        .getAsJsonArray("groups").get(0).getAsJsonObject()
                        .get("name").getAsString().equals("外壳"),
                "外壳还在");

        // 组内键的封闭词表（拼写错误必须报出来）
        JsonObject typo = JsonParser.parseString("""
                {"schema_version":3,"groups":[{"name":"a","cube":[{"from":[0,0,0],"to":[0,0,0]}]}]}
                """).getAsJsonObject();
        report("组里把 cubes 敲成 cube → 报「不认识的键」",
                issueMentions(MachineStandard.validate(typo, "t"), "不认识的键"),
                MachineStandard.validate(typo, "t").toString());

        JsonObject empty = JsonParser.parseString("""
                {"schema_version":3,"groups":[{"name":"a"}]}
                """).getAsJsonObject();
        report("组里既没 cubes 也没 groups → 报错（多半是键写错了）",
                !MachineStandard.validate(empty, "t").isEmpty(),
                MachineStandard.validate(empty, "t").toString());

        JsonObject badSub = JsonParser.parseString("""
                {"schema_version":3,"groups":[{"name":"a","cubes":[],
                   "groups":[{"name":"b","cubes":[{"from":[0,0]}]}]}]}
                """).getAsJsonObject();
        report("子组里的坏 cube 也会被递归校验（from 不是三元组）",
                issueMentions(MachineStandard.validate(badSub, "t"), "from"),
                MachineStandard.validate(badSub, "t").toString());
    }

    /**
     * schema 4：cube 也能有名字（可选键）。
     *
     * <p>起因很具体：cube 以前只有 {@code from}/{@code to}，大纲里所有 cube 只能显示
     * "cube"，作者分不清、也没法给某一格起名。名字是<strong>可选</strong>的，所以必须同时
     * 钉住"无名时导出形状不变"—— 否则老文件一进编辑器就会被改写。</p>
     */
    private static void verifyCubeNames() {
        System.out.println();
        System.out.println("==== schema 4：cube 名字 ====");

        String json = """
                {"schema_version":4,
                 "groups":[{"name":"主体","cubes":[
                    {"name":"齿轮","from":[1,0,0],"to":[1,0,0]},
                    {"from":[2,0,0],"to":[2,0,0]}]}]}
                """;
        JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        report("带 name 的 cube 通过规范校验",
                MachineStandard.validate(o, "t").isEmpty(),
                MachineStandard.validate(o, "t").toString());

        var def = MachineDefinition.parse("t", o);
        var cs = def.groups().get(0).cubes();
        report("有名 cube 读到名字、无名 cube 是 null",
                "齿轮".equals(cs.get(0).name()) && cs.get(1).name() == null,
                "0=" + cs.get(0).name() + " 1=" + cs.get(1).name());

        var back = def.toJson().getAsJsonArray("groups").get(0).getAsJsonObject()
                .getAsJsonArray("cubes");
        report("导出时无名 cube 不写 name（老文件导出形状不变）",
                back.get(0).getAsJsonObject().has("name")
                        && !back.get(1).getAsJsonObject().has("name"),
                "有名写、无名不写");

        report("导出再读回名字还在（读写对称）",
                "齿轮".equals(MachineDefinition.parse("t", def.toJson())
                        .groups().get(0).cubes().get(0).name()),
                "round-trip");

        JsonObject typo = com.google.gson.JsonParser.parseString("""
                {"schema_version":4,"groups":[{"name":"g","cubes":[{"nam":"x","from":[0,0,0]}]}]}
                """).getAsJsonObject();
        report("cube 里把 name 敲成 nam → 报「不认识的键」",
                issueMentions(MachineStandard.validate(typo, "t"), "不认识的键"),
                MachineStandard.validate(typo, "t").toString());

        JsonObject blank = com.google.gson.JsonParser.parseString("""
                {"schema_version":4,"groups":[{"name":"g","cubes":[{"name":"  ","from":[0,0,0]}]}]}
                """).getAsJsonObject();
        report("cube 的 name 是空白串 → 报错（要么别写，要么写有效值）",
                !MachineStandard.validate(blank, "t").isEmpty(),
                MachineStandard.validate(blank, "t").toString());
    }

    private static boolean issueMentions(java.util.List<String> issues, String needle) {
        for (String s : issues) {
            if (s.contains(needle)) return true;
        }
        return false;
    }

    private static void verifyNoJavaShapeLeftovers() {
        System.out.println();
        System.out.println("==== D5 定义只有一处（Java 不得再重写形状/代理）====");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> s = Files.walk(MACHINE_PKG)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = p.getFileName().toString();
                // BaseMachineBlock 是唯一的查表实现，允许出现这些方法签名
                if (name.equals("BaseMachineBlock.java")) continue;
                // verify 子包是校验工具本身，源码里就含这些签名字符串（会被自己误报）
                if (p.toString().replace('\\', '/').contains("/verify/")) continue;
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (src.contains("public Vec3i[] getSideOffsets()")) offenders.add(name + ":getSideOffsets");
                if (src.contains("public Vec3i[][] getFillRegions()")) offenders.add(name + ":getFillRegions");
                if (src.contains("public ItemProxy getItemProxy(")) offenders.add(name + ":getItemProxy");
                if (src.contains("public FluidProxy getFluidProxy(")) offenders.add(name + ":getFluidProxy");
            }
        } catch (Exception e) {
            report("遍历 machine 包源码", false, "★ " + e.getMessage());
            return;
        }
        report("machine 包内无形状/代理重写（只有 BaseMachineBlock 查表）", offenders.isEmpty(),
                offenders.isEmpty() ? "无" : "★ " + offenders);

        String config = readSource(MACHINE_PKG.resolve("common/MachineConfig.java"));
        boolean configClean = config != null && !config.contains("sideOffsets") && !config.contains("fillRegions");
        report("MachineConfig 不再持有形状字段", configClean,
                config == null ? "★ 读不到" : (configClean ? "已清空" : "★ 仍有 sideOffsets/fillRegions"));

        String modBlocks = readSource(MOD_BLOCKS);
        boolean noCall = modBlocks != null && !modBlocks.contains(".sideOffsets(");
        report("ModBlocks 不再调用 .sideOffsets(...)", noCall,
                modBlocks == null ? "★ 读不到" : (noCall ? "已移除" : "★ 仍在调用"));

        String base = readSource(MACHINE_PKG.resolve("BaseMachineBlock.java"));
        boolean wired = base != null && base.contains("MachineDefinitionManager.get(")
                && base.contains("public String getMachineId()");
        report("BaseMachineBlock 经 getMachineId() 查定义", wired,
                wired ? "已接线" : "★ 未接线");

        String large = readSource(MACHINE_PKG.resolve("common/LargeMachineBlock.java"));
        boolean idWired = large != null && large.contains("return config.id();");
        report("LargeMachineBlock.getMachineId() 返回 config.id()", idWired,
                idWired ? "已接线" : "★ 未接线");

        String mgr = readSource(MACHINE_PKG.resolve("definition/MachineDefinitionManager.java"));
        boolean dirs = mgr != null && mgr.contains("\"machines\"") && mgr.contains("BUILTIN_ROOT");
        report("加载器同时支持内置(classpath)与 config 覆盖", dirs,
                dirs ? "两条路径都在" : "★ 缺加载路径");

        String screen = readSource(Path.of(
                "src/main/java/com/mss/polymech/client/gui/machine/editor/MachineTab.java"));
        boolean savesJson = screen != null && screen.contains("MachineDefinitionManager.save(")
                && !screen.contains("exportJavaCode");
        report("编辑器保存 JSON 且不再生成 Java 代码", savesJson,
                screen == null ? "★ 读不到" : (savesJson ? "已改为保存 JSON" : "★ 仍在导出 Java"));
    }

    // ==================== 工具 ====================

    private static String readSource(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<Vec3i> minus(Set<Vec3i> a, Set<Vec3i> b) {
        Set<Vec3i> out = new HashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static String fmt(Vec3i v) {
        return "(" + v.getX() + "," + v.getY() + "," + v.getZ() + ")";
    }

    private static String fmtArr(Vec3i[] arr) {
        List<String> parts = new ArrayList<>();
        for (Vec3i v : arr) parts.add(fmt(v));
        return parts.toString();
    }

    private static void report(String name, boolean ok, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + name + " —— " + detail);
    }

    static {
        // 让 switch/日志里的中文在 Windows 控制台不至于乱码到无法定位（与其它校验器一致的做法）
        Locale.setDefault(Locale.ROOT);
    }
}
