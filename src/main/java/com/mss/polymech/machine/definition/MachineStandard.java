package com.mss.polymech.machine.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 机器编写的<b>规范</b>：键的封闭词表 + 取值约束 + 行为 id 的封闭词表。
 *
 * <h3>为什么要这个东西</h3>
 * 结构/IO 做成 JSON 之后，"加一台机器"变成"加一份 JSON"，整合包作者也能改——这是好事。
 * 但 JSON 没有编译期检查：把 {@code side_offsets} 敲成 {@code side_offset}、
 * 把 {@code behavior} 写成 {@code steam_boiler2}，机器会<strong>静默地什么都不做</strong>，
 * 而不是报错。GT 那种 Java builder 链天然有编译器兜底，我们走 JSON 就必须自己把
 * 这层兜底补上——这就是本类存在的理由（{@link #validate} 由离线校验任务对所有
 * 内置 JSON 跑一遍，改错了直接 FAIL）。
 *
 * <h3>行为词表是封闭的</h3>
 * {@link #BEHAVIORS} 里的 id 与 {@code com.mss.polymech.machine} 下的
 * {@code Abstract*BlockEntity} 基类一一对应（见 {@link #BEHAVIOR_BASE}）。
 * 词表封闭 + 与代码交叉核对，保证"JSON 里写了 behavior"这件事不会和实际行为脱节。
 */
public final class MachineStandard {

    private MachineStandard() {
    }

    /** 当前规范版本。以后改 schema 就 +1，并在这里做迁移。 */
    public static final int CURRENT_SCHEMA = 4;

    /**
     * 本解析器<strong>还能读</strong>的最旧 schema 版本。
     *
     * <p>schema 是<strong>向后兼容</strong>演进的：schema 2 引入 {@code groups}（结构树），
     * schema 3 让组能嵌套，schema 4 给 cube 加了可选的 name。所以 1/2/3 的文件
     * 用现在的解析器读出来<strong>完全等价</strong>，不该因为版本号旧就报"需要迁移"——
     * 那是纯噪声，而"有噪声的校验等于没有校验"（作者会开始无视所有报错）。
     * 比当前版本更新（未来版本）才是真的读不了，必须报出来。</p>
     */
    public static final int MIN_READABLE_SCHEMA = 1;

    /** 允许出现的顶层键。多一个键就报错 —— 这是抓拼写错误最有效的一招。 */
    public static final Set<String> ALLOWED_KEYS = Set.of(
            "schema_version",
            "id",
            // schema 2：结构 = 结构树（分组 + cube），取代 side_offsets / fill_regions
            // schema 3：组可嵌套（组里同时有 cubes 与 groups，照 Blockbench 的 bone）
            "groups",
            // 旧格式仍允许（解析器会把它们合成一个组），但新文件请用 groups
            "side_offsets",
            "fill_regions",
            "item_proxies",
            "fluid_proxies",
            "notes",
            // —— S2 预留：声明性事实（行为/等级/族/配方/界面/模型/显示名）——
            "behavior",
            "tier",
            "family",
            "recipe_type",
            "ui",
            "model",
            "lang");

    /**
     * 必须出现的顶层键。
     *
     * <p>结构本身不在这里要求：{@code groups}（schema 2）与 {@code side_offsets}
     * （旧格式）二者其一即可，由 {@link #validate} 单独判断，
     * 这样作者手上的旧 config 不会因为升级而突然报错。</p>
     */
    public static final Set<String> REQUIRED_KEYS = Set.of("schema_version");

    /**
     * 行为 id 的封闭词表。
     *
     * <p>{@code processing} = 通用加工机（{@code AbstractProcessingBlockEntity}）；
     * {@code turbine_generator} = 涡轮发电机（{@code AbstractTurbineGeneratorBlockEntity}，
     * 它是 processing 的子类，单独列出来是因为发电机的运行语义不同）；
     * {@code steam_boiler} = 蒸汽锅炉（{@code AbstractSteamBoilerBlockEntity}）。</p>
     */
    public static final Set<String> BEHAVIORS = new LinkedHashSet<>(List.of(
            "processing", "turbine_generator", "steam_boiler"));

    /** 行为 id → 它对应的 BE 基类名（离线校验拿它跟真实源码交叉核对，防止词表与代码脱节）。 */
    public static final java.util.Map<String, String> BEHAVIOR_BASE = java.util.Map.of(
            "processing", "AbstractProcessingBlockEntity",
            "turbine_generator", "AbstractTurbineGeneratorBlockEntity",
            "steam_boiler", "AbstractSteamBoilerBlockEntity");

    /** 必须是"非空字符串"的可选键。 */
    private static final Set<String> STRING_KEYS = Set.of(
            "id", "family", "recipe_type", "ui", "model", "lang", "behavior");

    /**
     * 组对象里允许出现的键（schema 3）。
     *
     * <p>{@code cubes} 与 {@code groups} 可以同时有：一个组既直接挂 cube，也挂子组
     * —— 与 Blockbench 的 bone 一致。多出别的键就是拼写错误，直接报出来。</p>
     */
    public static final Set<String> GROUP_KEYS = Set.of("name", "cubes", "groups");

    /**
     * cube 对象里允许出现的键（schema 4 起多了个可选的 {@code name}）。
     *
     * <p>cube 以前无名（只有 from/to），所以大纲里所有 cube 都只能显示 "cube"，
     * 也没办法给某一格起名 —— 这就是"cube 改不了名"的根因。</p>
     */
    public static final Set<String> CUBE_KEYS = Set.of("name", "from", "to");

    /**
     * 校验一份机器 JSON。
     *
     * @param json 机器定义 JSON
     * @param id   机器 id（报错信息用）
     * @return 问题列表；空 = 合规
     */
    public static List<String> validate(JsonObject json, String id) {
        List<String> issues = new ArrayList<>();
        String where = "machine/" + id;

        // 1) 未知键（拼写错误的主要来源）
        for (String key : json.keySet()) {
            if (!ALLOWED_KEYS.contains(key)) {
                issues.add(where + ": 不认识的键 \"" + key + "\"（是不是拼错了？允许的键见 MachineStandard.ALLOWED_KEYS）");
            }
        }

        // 2) 必填键
        for (String key : REQUIRED_KEYS) {
            if (!json.has(key)) issues.add(where + ": 缺少必填键 \"" + key + "\"");
        }

        // 2b) 结构必须由 groups（schema 2/3）或 side_offsets（旧格式）之一给出
        if (!json.has("groups") && !json.has("side_offsets")) {
            issues.add(where + ": 结构缺失 —— 需要 \"groups\"（结构树）或旧格式 \"side_offsets\"");
        }

        // 2c) 结构树形状
        issues.addAll(validateGroups(json, where));

        // 3) schema 版本
        if (json.has("schema_version")) {
            JsonElement v = json.get("schema_version");
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                issues.add(where + ".schema_version 必须是数字");
            } else if (v.getAsInt() < MIN_READABLE_SCHEMA || v.getAsInt() > CURRENT_SCHEMA) {
                issues.add(where + ".schema_version = " + v.getAsInt()
                        + "，本版本只能读 " + MIN_READABLE_SCHEMA + ".." + CURRENT_SCHEMA
                        + "（更旧/更新的版本需要迁移）");
            }
        }

        // 4) side_offsets 必须是数组
        if (json.has("side_offsets") && !json.get("side_offsets").isJsonArray()) {
            issues.add(where + ".side_offsets 必须是数组，如 [[1,0,0],[-1,0,0]]");
        }

        // 5) 行为 id 必须落在封闭词表里
        if (json.has("behavior")) {
            JsonElement v = json.get("behavior");
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                issues.add(where + ".behavior 必须是字符串（行为 id）");
            } else {
                String b = v.getAsString().trim().toLowerCase(Locale.ROOT);
                if (!BEHAVIORS.contains(b)) {
                    issues.add(where + ".behavior = \"" + v.getAsString()
                            + "\" 不在词表里，可用：" + BEHAVIORS);
                }
            }
        }

        // 6) tier 必须是非负整数
        if (json.has("tier")) {
            JsonElement v = json.get("tier");
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                issues.add(where + ".tier 必须是数字");
            } else if (v.getAsInt() < 0) {
                issues.add(where + ".tier 不能为负: " + v.getAsInt());
            }
        }

        // 7) 字符串键不能是空串（空串等于"声明了但没写"，属于写错）
        for (String key : STRING_KEYS) {
            if (!json.has(key)) continue;
            JsonElement v = json.get(key);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                issues.add(where + "." + key + " 必须是字符串");
            } else if (v.getAsString().isBlank()) {
                issues.add(where + "." + key + " 不能是空串（要么别写这个键，要么写有效值）");
            }
        }

        // 8) notes 必须是对象
        if (json.has("notes") && !json.get("notes").isJsonObject()) {
            issues.add(where + ".notes 必须是对象，如 {\"0,3,0\":\"蒸汽输出口\"}");
        }

        return issues;
    }

    /**
     * 词表级的检查：每个行为 id 都必须真的有机器在用，且每个 id 都能在源码里找到对应的 BE 基类。
     *
     * @param usedBehaviors 所有机器 JSON 里出现过的 behavior 值
     * @param baseClasses   源码里真实存在的 {@code Abstract*BlockEntity} 基类名集合
     */
    /**
     * 校验结构树 {@code groups:[{name, cubes:[{from,to}], groups:[...]}]}（schema 3，可嵌套）。
     *
     * <p>这是几何声明，形状错了就会让机器"放下去但结构是空的"，所以逐层检查到每个 cube 的
     * from/to 必须是 3 个整数，且递归到子组。组对象的键也在封闭词表内
     * （{@link #GROUP_KEYS}）—— 把 {@code cubes} 敲成 {@code cube} 会被抓出来。</p>
     */
    private static List<String> validateGroups(JsonObject json, String where) {
        List<String> issues = new ArrayList<>();
        if (!json.has("groups")) return issues;
        JsonElement g = json.get("groups");
        if (!g.isJsonArray()) {
            issues.add(where + ".groups 必须是数组");
            return issues;
        }
        validateGroupArray(g.getAsJsonArray(), where + ".groups", issues);
        return issues;
    }

    /** 递归校验一组同级组。{@code base} 是报错位置前缀，如 {@code groups[0].groups}。 */
    private static void validateGroupArray(JsonArray groups, String base, List<String> issues) {
        for (int i = 0; i < groups.size(); i++) {
            String gw = base + "[" + i + "]";
            JsonElement ge = groups.get(i);
            if (!ge.isJsonObject()) {
                issues.add(gw + " 必须是对象 {name, cubes}（可带子组 groups）");
                continue;
            }
            JsonObject go = ge.getAsJsonObject();

            // 组内键的封闭词表（抓 cube→cubes、subgroups→groups 这类拼写错误）
            for (String key : go.keySet()) {
                if (!GROUP_KEYS.contains(key)) {
                    issues.add(gw + ": 不认识的键 \"" + key + "\"（组里只允许 name / cubes / groups）");
                }
            }
            if (!go.has("name")) {
                issues.add(gw + " 缺少 \"name\"（文件夹名，给人看的）");
            }

            boolean hasCubes = go.has("cubes");
            boolean hasGroups = go.has("groups");
            // 一个组可以只有子组（纯文件夹），但不能什么都没有 —— 那多半是键写错了
            if (!hasCubes && !hasGroups) {
                issues.add(gw + " 既没有 \"cubes\" 也没有 \"groups\"（空组请写 \"cubes\": []）");
            }

            if (hasCubes) {
                JsonElement cs = go.get("cubes");
                if (!cs.isJsonArray()) {
                    issues.add(gw + ".cubes 必须是数组");
                } else {
                    validateCubes(cs.getAsJsonArray(), gw + ".cubes", issues);
                }
            }
            if (hasGroups) {
                JsonElement gs = go.get("groups");
                if (!gs.isJsonArray()) {
                    issues.add(gw + ".groups 必须是数组");
                } else {
                    validateGroupArray(gs.getAsJsonArray(), gw + ".groups", issues);
                }
            }
        }
    }

    private static void validateCubes(JsonArray cubes, String base, List<String> issues) {
        for (int j = 0; j < cubes.size(); j++) {
            String cw = base + "[" + j + "]";
            JsonElement ce = cubes.get(j);
            if (!ce.isJsonObject()) {
                issues.add(cw + " 必须是对象 {from, to}");
                continue;
            }
            JsonObject co = ce.getAsJsonObject();

            // 键名封闭：拼写错误必须报出来，不能静默忽略
            for (String key : co.keySet()) {
                if (!CUBE_KEYS.contains(key)) {
                    issues.add(cw + ": 不认识的键 \"" + key + "\"（可用：" + CUBE_KEYS + "）");
                }
            }

            if (co.has("name")) {
                JsonElement nm = co.get("name");
                if (!nm.isJsonPrimitive() || !nm.getAsJsonPrimitive().isString()) {
                    issues.add(cw + ".name 必须是字符串");
                } else if (nm.getAsString().isBlank()) {
                    issues.add(cw + ".name 不能是空串（要么别写这个键，要么写有效值）");
                }
            }

            if (!co.has("from")) {
                issues.add(cw + " 缺少 \"from\"");
            } else if (!isIntTriple(co.get("from"))) {
                issues.add(cw + ".from 必须是 3 个整数 [x,y,z]");
            }
            // to 可省略（单格 cube），写错就要报
            if (co.has("to") && !isIntTriple(co.get("to"))) {
                issues.add(cw + ".to 必须是 3 个整数 [x,y,z]");
            }
        }
    }

    private static boolean isIntTriple(JsonElement el) {
        if (el == null || !el.isJsonArray()) return false;
        var a = el.getAsJsonArray();
        if (a.size() != 3) return false;
        for (JsonElement e : a) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        }
        return true;
    }

    public static List<String> validateVocabulary(Set<String> usedBehaviors, Set<String> baseClasses) {
        List<String> issues = new ArrayList<>();
        for (String b : BEHAVIORS) {
            String base = BEHAVIOR_BASE.get(b);
            if (base == null) {
                issues.add("行为 \"" + b + "\" 没在 BEHAVIOR_BASE 里登记基类");
            } else if (!baseClasses.contains(base)) {
                issues.add("行为 \"" + b + "\" 声称对应基类 " + base + "，但源码里找不到这个类（词表与代码脱节）");
            }
            if (usedBehaviors != null && !usedBehaviors.isEmpty() && !usedBehaviors.contains(b)) {
                issues.add("行为 \"" + b + "\" 在词表里但没有任何机器在用（死条目）");
            }
        }
        for (String used : usedBehaviors == null ? Set.<String>of() : usedBehaviors) {
            if (!BEHAVIORS.contains(used)) {
                issues.add("有机器声明了词表外的行为 \"" + used + "\"");
            }
        }
        return issues;
    }

    /** 方便测试/调试：把一个 JsonArray 的字符串元素收集成集合。 */
    public static Set<String> strings(JsonArray a) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement e : a) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) out.add(e.getAsString());
        }
        return out;
    }
}
