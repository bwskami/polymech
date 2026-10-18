package com.mss.polymech.machine.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一台机器的结构定义 —— <strong>形状 + 代理语义的唯一来源</strong>。
 *
 * <h3>为什么是 JSON 而不是 Java</h3>
 * 以前形状写在 {@code getSideOffsets()/getFillRegions()}、语义写在
 * {@code getItemProxy/getFluidProxy} 的 if 链里。问题有两个：
 * <ol>
 *   <li>同一份形状散在两处（{@code MachineConfig} 的字段 与 Block 子类的重写），容易对不上；</li>
 *   <li>在游戏里改机器只能生成 Java 源码再手工贴回去，风险高、也无法即时生效。</li>
 * </ol>
 * 搬进 JSON 后：游戏内编辑器直接改文件、{@code /polymech machinereload} 即时生效，
 * 新增机器也只是多一个 JSON。
 *
 * <h3>写法沿用原来的声明风格，不引入图案</h3>
 * <pre>
 * {
 *   "side_offsets": [[0,3,0],[0,3,2],[0,4,2]],            // 离散格
 *   "fill_regions": [{"from":[-1,0,-2],"to":[1,2,2]}],    // 区间（超大机器靠它，不逐格画）
 *   "item_proxies":  [{"at":[0,1,-2],"slots":[2],"io":"input","note":"燃料输入"}],
 *   "fluid_proxies": [{"at":[0,0,1],"tanks":[0],"io":"input"},
 *                     {"at":[0,3,0],"tanks":[1],"io":"output","faces":["up"],"note":"蒸汽输出（仅顶面）"}]
 * }
 * </pre>
 * 坐标一律是 {@code facing=NORTH} 的<strong>本地坐标</strong>（与旧 if 链完全一致），
 * 运行时按朝向旋转由 {@code MachineGeometry} 负责。
 *
 * <h3>校验</h3>
 * {@link #parse} 对格式错误<strong>抛异常并指明位置</strong>（哪个键、哪一项），
 * 不静默吞掉——离线校验 {@code verifyMachineDefinitions} 靠它把坏定义判死。
 */
public final class MachineDefinition {

    /** 空定义：无侧面方块、无代理（单方块机器 / 未找到 JSON 时的兜底）。 */
    public static final MachineDefinition EMPTY =
            new MachineDefinition("", new Vec3i[0], null, Map.of(), Map.of(), Map.of());

    private final String id;
    private final Vec3i[] sideOffsets;
    @Nullable
    private final Vec3i[][] fillRegions;
    private final Map<Vec3i, BaseMachineBlock.ItemProxy> itemProxies;
    private final Map<Vec3i, BaseMachineBlock.FluidProxy> fluidProxies;
    /** 每格的说明文字（只为人读，参与 JSON 往返，不影响运行）。 */
    private final Map<Vec3i, String> notes;
    /** 规范元数据（schema 版本 / 行为 id / 等级 / 族）。见 {@link Meta}。 */
    private final Meta meta;
    /** 结构树：分组 + 每组的 cube。这是结构的<strong>真实来源</strong>，格子由它展平。 */
    private final List<Group> groups;

    /**
     * 规范元数据 —— 机器"是什么"的声明性事实，与结构/IO 分开放在一起。
     *
     * <h3>为什么必须是字段而不是"解析时读一眼就扔"</h3>
     * 编辑器保存走的是 {@code snapshot.exportJson() → toDefinition() → toJson()}。
     * 如果这些键只在 {@link #parse} 里被读、{@link #toJson} 不写回去，
     * 那么"打开编辑器 → 保存"就会<strong>静默删掉</strong>作者写的
     * {@code behavior}/{@code tier} —— 这正是本项目反复踩过的数据丢失类 bug。
     * 所以它们必须真的存在于定义对象里，参与往返。
     */
    public record Meta(int schemaVersion, int tier,
                       @Nullable String family, @Nullable String behavior) {

        /** 缺省元数据（通用机器：无行为、0 级、无族）。 */
        public static final Meta DEFAULT =
                new Meta(MachineStandard.CURRENT_SCHEMA, 0, null, null);

        /** 是否等价于缺省（用于决定要不要往 JSON 里写这些键）。 */
        public boolean isDefault() {
            return schemaVersion == MachineStandard.CURRENT_SCHEMA
                    && tier == 0 && family == null && behavior == null;
        }
    }

    /**
     * 一组 cube / 子组 —— 对应 Blockbench 的 bone / 文件夹。
     *
     * <h3>为什么一组里同时有 cubes 和 groups（schema 3）</h3>
     * Blockbench 的 bone 既能直接挂 cube，也能挂子 bone，两者并存而不互斥。
     * 结构树照抄这个模型：{@code {"name":"主体","cubes":[...],"groups":[{"name":"外壳",...}]}}。
     * 这样 schema 2 的平铺 JSON <strong>不用改结构</strong>就能读（它们只有 cubes、没有 groups），
     * 只是 {@code schema_version} 要升到 3。
     *
     * <p>名字只为人读、只用于结构树分组，<strong>不参与几何</strong>；
     * {@link #volume()} 与 {@link #allCubes()} 都会递归到子组。</p>
     */
    public record Group(String name, List<Cube> cubes, List<Group> groups) {
        public Group {
            cubes = cubes == null ? List.of() : List.copyOf(cubes);
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        /** 没有子组的两参写法（schema 2 的 JSON 与调用点都走这条）。 */
        public Group(String name, List<Cube> cubes) {
            this(name, cubes, List.of());
        }

        /** 这一组（含全部子组）占据的格子数。 */
        public long volume() {
            long v = 0;
            for (Cube c : cubes) v += c.volume();
            for (Group g : groups) v += g.volume();
            return v;
        }

        /**
         * 这一组（含子组递归）的全部 cube，保持声明顺序：本组 cube 在前、子组按顺序在后。
         *
         * <p>展平格子、统计、导出校验都必须走它 —— 只遍历 {@link #cubes()} 会
         * <strong>静默漏掉嵌套的 cube</strong>（机器放出来会缺一块）。</p>
         */
        public List<Cube> allCubes() {
            List<Cube> out = new ArrayList<>(cubes);
            for (Group g : groups) out.addAll(g.allCubes());
            return out;
        }
    }

    /**
     * 一个 cube：格子范围 {@code [from, to]}（含两端）。
     *
     * <p><b>一个方块 = 一个 cube。</b>「角点区间填充」不是新图元，而是
     * 「新建一个文件夹 + 生成体积那么多个单格 cube」（2×2×2 → 8 个 cube），
     * 所以工具产出的 cube 都是 {@code from == to} 的单格。</p>
     *
     * <p>schema 仍允许 {@code from != to} 的盒子：手写超大机器时可以写 1 个盒子
     * 代替上千行字面量。两者展平出的格子集合完全等价，几何层不区分。</p>
     */
    public record Cube(Vec3i from, Vec3i to, @Nullable String name) {
        public Cube {
            if (from == null || to == null) {
                throw new IllegalArgumentException("cube 的 from/to 不能为空");
            }
            // 归一化：空白名等于没名字，省得 JSON 里出现 "name": ""
            name = (name == null || name.isBlank()) ? null : name.trim();
        }

        /** 无名 cube —— schema 4 之前的所有文件都是这个形态，渲染时退回 "cube"。 */
        public Cube(Vec3i from, Vec3i to) {
            this(from, to, null);
        }

        public boolean hasName() {
            return name != null;
        }

        public int minX() { return Math.min(from.getX(), to.getX()); }
        public int minY() { return Math.min(from.getY(), to.getY()); }
        public int minZ() { return Math.min(from.getZ(), to.getZ()); }
        public int maxX() { return Math.max(from.getX(), to.getX()); }
        public int maxY() { return Math.max(from.getY(), to.getY()); }
        public int maxZ() { return Math.max(from.getZ(), to.getZ()); }

        /** 体积（格子数）。 */
        public long volume() {
            return (long) (maxX() - minX() + 1) * (maxY() - minY() + 1) * (maxZ() - minZ() + 1);
        }

        /** 是不是单格 cube（"一个方块 = 一个 cube"的常态）。 */
        public boolean isUnit() {
            return volume() == 1L;
        }

        public boolean contains(Vec3i p) {
            return p != null
                    && p.getX() >= minX() && p.getX() <= maxX()
                    && p.getY() >= minY() && p.getY() <= maxY()
                    && p.getZ() >= minZ() && p.getZ() <= maxZ();
        }

        /**
         * 由「位置 + 尺寸」造 cube —— 变换面板的换算就是这一条。
         *
         * <p>尺寸是<b>格子数</b>，含两端，所以 {@code to = from + 尺寸 - 1}
         * （2×2×2 → from 到 from+(2,2,2)-1，共 8 格）。
         * 单独抽出来是为了能离线断言：如果把这个 {@code -1} 写错，
         * 2×2×2 会变成 3×3×3=27 格，而界面上不容易一眼看出来。</p>
         */
        public static Cube ofSize(Vec3i from, int sizeX, int sizeY, int sizeZ) {
            int sx = Math.max(1, sizeX);
            int sy = Math.max(1, sizeY);
            int sz = Math.max(1, sizeZ);
            return new Cube(from, new Vec3i(from.getX() + sx - 1,
                    from.getY() + sy - 1, from.getZ() + sz - 1));
        }
    }

    public MachineDefinition(String id,
                             Vec3i[] sideOffsets,
                             @Nullable Vec3i[][] fillRegions,
                             Map<Vec3i, BaseMachineBlock.ItemProxy> itemProxies,
                             Map<Vec3i, BaseMachineBlock.FluidProxy> fluidProxies,
                             Map<Vec3i, String> notes) {
        this(id, sideOffsets, fillRegions, itemProxies, fluidProxies, notes, Meta.DEFAULT);
    }

    public MachineDefinition(String id,
                             Vec3i[] sideOffsets,
                             @Nullable Vec3i[][] fillRegions,
                             Map<Vec3i, BaseMachineBlock.ItemProxy> itemProxies,
                             Map<Vec3i, BaseMachineBlock.FluidProxy> fluidProxies,
                             Map<Vec3i, String> notes,
                             Meta meta) {
        this(id, sideOffsets, fillRegions, itemProxies, fluidProxies, notes, meta,
                synthesizeGroups(sideOffsets, fillRegions));
    }

    /**
     * 以 <strong>groups 为准</strong>构造：格子集合由 groups 展平得来，
     * {@link #sideOffsets()}／{@link #fillRegions()} 作为派生访问器继续可用，
     * 所以下游（几何、渲染、能力注册）一行都不用改。
     */
    public MachineDefinition(String id,
                             List<Group> groups,
                             Map<Vec3i, BaseMachineBlock.ItemProxy> itemProxies,
                             Map<Vec3i, BaseMachineBlock.FluidProxy> fluidProxies,
                             Map<Vec3i, String> notes,
                             Meta meta) {
        this(id, flatten(groups), null, itemProxies, fluidProxies, notes, meta, groups);
    }

    private MachineDefinition(String id,
                              Vec3i[] sideOffsets,
                              @Nullable Vec3i[][] fillRegions,
                              Map<Vec3i, BaseMachineBlock.ItemProxy> itemProxies,
                              Map<Vec3i, BaseMachineBlock.FluidProxy> fluidProxies,
                              Map<Vec3i, String> notes,
                              Meta meta,
                              List<Group> groups) {
        this.id = id;
        this.sideOffsets = sideOffsets;
        this.fillRegions = fillRegions;
        this.itemProxies = itemProxies;
        this.fluidProxies = fluidProxies;
        this.notes = notes;
        this.meta = meta == null ? Meta.DEFAULT : meta;
        this.groups = groups == null ? List.of() : List.copyOf(groups);
    }

    // ---- groups ↔ 格子 互转 ----

    /** 把盒子展开成单格 cube（2×2×2 的角点 → 8 个 cube）。 */
    private static void addUnitCubes(Cube box, List<Cube> out) {
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    Vec3i p = new Vec3i(x, y, z);
                    out.add(new Cube(p, p));
                }
            }
        }
    }

    /** 旧数据（side_offsets + fill_regions）→ 统一合成一个组。 */
    private static List<Group> synthesizeGroups(@Nullable Vec3i[] sideOffsets,
                                                @Nullable Vec3i[][] fillRegions) {
        List<Cube> cubes = new ArrayList<>();
        if (fillRegions != null) {
            for (Vec3i[] r : fillRegions) {
                if (r == null || r.length < 2 || r[0] == null || r[1] == null) continue;
                addUnitCubes(new Cube(r[0], r[1]), cubes);
            }
        }
        if (sideOffsets != null) {
            for (Vec3i v : sideOffsets) {
                if (v != null) cubes.add(new Cube(v, v));
            }
        }
        return cubes.isEmpty() ? List.of() : List.of(new Group("结构", cubes));
    }

    /** 展平所有 cube 为格子（保持声明顺序，供几何层使用）。 */
    private static Vec3i[] flatten(@Nullable List<Group> groups) {
        List<Vec3i> out = new ArrayList<>();
        if (groups != null) {
            for (Group g : groups) {
                if (g == null) continue;
                for (Cube c : g.allCubes()) {
                    for (int x = c.minX(); x <= c.maxX(); x++) {
                        for (int y = c.minY(); y <= c.maxY(); y++) {
                            for (int z = c.minZ(); z <= c.maxZ(); z++) {
                                out.add(new Vec3i(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        return out.toArray(new Vec3i[0]);
    }

    /** 结构树用：全部组（含每组的 cube）。 */
    public List<Group> groups() {
        return groups;
    }

    /** 全部 cube 的扁平列表（含嵌套子组，结构树统计/遍历用）。 */
    public List<Cube> cubes() {
        List<Cube> out = new ArrayList<>();
        for (Group g : groups) out.addAll(g.allCubes());
        return out;
    }

    /** 规范元数据。 */
    public Meta meta() {
        return meta;
    }

    /** 换一份元数据（其它字段照抄，groups 一并带上）。 */
    public MachineDefinition withMeta(Meta m) {
        return new MachineDefinition(id, groups, itemProxies, fluidProxies, notes, m);
    }

    // ==================== 读取 ====================

    public String id() {
        return id;
    }

    /** 离散侧面格。返回内部数组，<strong>只读</strong>，调用方不要修改。 */
    public Vec3i[] sideOffsets() {
        return sideOffsets;
    }

    /** 填充区间（每项 {@code {min,max}}）。返回内部数组，只读。 */
    @Nullable
    public Vec3i[][] fillRegions() {
        return fillRegions;
    }

    public Map<Vec3i, BaseMachineBlock.ItemProxy> itemProxies() {
        return itemProxies;
    }

    public Map<Vec3i, BaseMachineBlock.FluidProxy> fluidProxies() {
        return fluidProxies;
    }

    public Map<Vec3i, String> notes() {
        return notes;
    }

    @Nullable
    public BaseMachineBlock.ItemProxy itemProxy(Vec3i local) {
        return itemProxies.get(local);
    }

    @Nullable
    public BaseMachineBlock.FluidProxy fluidProxy(Vec3i local) {
        return fluidProxies.get(local);
    }

    @Nullable
    public String note(Vec3i local) {
        return notes.get(local);
    }

    /** 是否真的声明了结构（空定义 = 单方块机器）。 */
    public boolean isEmpty() {
        return sideOffsets.length == 0 && (fillRegions == null || fillRegions.length == 0);
    }

    // ==================== JSON 解析 ====================

    /**
     * 解析一份机器定义。
     *
     * @throws IllegalArgumentException 格式错误（消息里带具体键名/下标，便于定位）
     */
    public static MachineDefinition parse(String id, JsonObject json) {
        // ---- side_offsets ----
        List<Vec3i> offsets = new ArrayList<>();
        JsonArray so = optArray(json, "side_offsets");
        if (so != null) {
            for (int i = 0; i < so.size(); i++) {
                offsets.add(vec3(so.get(i), "side_offsets[" + i + "]"));
            }
        }

        // ---- fill_regions ----
        List<Vec3i[]> regions = new ArrayList<>();
        JsonArray fr = optArray(json, "fill_regions");
        if (fr != null) {
            for (int i = 0; i < fr.size(); i++) {
                JsonElement el = fr.get(i);
                if (!el.isJsonObject()) {
                    throw new IllegalArgumentException("fill_regions[" + i + "] 必须是对象 {from,to}");
                }
                JsonObject o = el.getAsJsonObject();
                Vec3i from = vec3(req(o, "from", "fill_regions[" + i + "]"), "fill_regions[" + i + "].from");
                Vec3i to = vec3(req(o, "to", "fill_regions[" + i + "]"), "fill_regions[" + i + "].to");
                regions.add(new Vec3i[]{from, to});
            }
        }

        // ---- item_proxies / fluid_proxies ----
        Map<Vec3i, BaseMachineBlock.ItemProxy> items = new LinkedHashMap<>();
        Map<Vec3i, BaseMachineBlock.FluidProxy> fluids = new LinkedHashMap<>();
        Map<Vec3i, String> notes = new LinkedHashMap<>();

        JsonArray ip = optArray(json, "item_proxies");
        if (ip != null) {
            for (int i = 0; i < ip.size(); i++) {
                String where = "item_proxies[" + i + "]";
                JsonObject o = asObject(ip.get(i), where);
                Vec3i at = vec3(req(o, "at", where), where + ".at");
                int[] slots = intArray(req(o, "slots", where), where + ".slots");
                BaseMachineBlock.ProxyIO io = io(req(o, "io", where), where + ".io");
                Direction[] faces = faces(o, where);
                Map<Direction, int[]> faceSlots = faceIntMap(o, "face_slots", where);
                if (items.put(at, new BaseMachineBlock.ItemProxy(slots, io, faces, faceSlots)) != null) {
                    throw new IllegalArgumentException(where + " 与前面的物品代理位置重复: " + fmt(at));
                }
                noteOf(o, at, notes, where);
            }
        }

        JsonArray fp = optArray(json, "fluid_proxies");
        if (fp != null) {
            for (int i = 0; i < fp.size(); i++) {
                String where = "fluid_proxies[" + i + "]";
                JsonObject o = asObject(fp.get(i), where);
                Vec3i at = vec3(req(o, "at", where), where + ".at");
                int[] tanks = intArray(req(o, "tanks", where), where + ".tanks");
                BaseMachineBlock.ProxyIO io = io(req(o, "io", where), where + ".io");
                Direction[] faces = faces(o, where);
                Map<Direction, int[]> faceTanks = faceIntMap(o, "face_tanks", where);
                if (fluids.put(at, new BaseMachineBlock.FluidProxy(tanks, io, faces, faceTanks)) != null) {
                    throw new IllegalArgumentException(where + " 与前面的流体代理位置重复: " + fmt(at));
                }
                noteOf(o, at, notes, where);
            }
        }

        Meta meta = parseMeta(id, json);
        // schema 2 起结构由 groups 声明；没有 groups 说明是旧格式，
        // 交给旧构造器把 side_offsets/fill_regions 合成一个组（老 config 不会因此报废）。
        List<Group> groups = parseGroups(json);
        if (groups != null) {
            return new MachineDefinition(id, groups, items, fluids, notes, meta);
        }
        return new MachineDefinition(id,
                offsets.toArray(new Vec3i[0]),
                regions.isEmpty() ? null : regions.toArray(new Vec3i[0][]),
                items, fluids, notes, meta);
    }

    /**
     * 读结构树 {@code groups: [{name, cubes: [...], groups: [...]}]}（schema 3，可嵌套）。
     *
     * <p>组对象里 {@code cubes} 与 {@code groups} <strong>可以同时有</strong>（照 Blockbench 的
     * bone：既挂 cube 也挂子 bone）。schema 2 的平铺 JSON 只有 {@code cubes}，
     * 走同一条递归路径读进来，所以老文件不用改。</p>
     *
     * <p>没有 {@code groups} 键就返回 null，由调用方回退到旧的
     * {@code side_offsets}/{@code fill_regions}（这样作者手上的旧 config 仍然能加载）。</p>
     */
    @Nullable
    private static List<Group> parseGroups(JsonObject json) {
        JsonArray arr = optArray(json, "groups");
        if (arr == null) return null;
        return parseGroupArray(arr, "groups");
    }

    /** 递归读一组同级组。{@code base} 是报错用的位置前缀，如 {@code groups[0].groups}。 */
    private static List<Group> parseGroupArray(JsonArray arr, String base) {
        List<Group> groups = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            groups.add(parseGroup(arr.get(i), base + "[" + i + "]", "组" + (i + 1)));
        }
        return groups;
    }

    /** 递归读一个组：{@code name} + 直接 cube + 子组。 */
    private static Group parseGroup(JsonElement el, String where, String fallbackName) {
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException(where + " 必须是对象 {name, cubes}（可带子组 groups）");
        }
        JsonObject o = el.getAsJsonObject();
        String name = o.has("name") && o.get("name").isJsonPrimitive()
                ? o.get("name").getAsString()
                : fallbackName;

        List<Cube> cubes = new ArrayList<>();
        JsonArray cs = optArray(o, "cubes");
        if (cs != null) {
            for (int j = 0; j < cs.size(); j++) {
                String cw = where + ".cubes[" + j + "]";
                JsonElement ce = cs.get(j);
                if (!ce.isJsonObject()) {
                    throw new IllegalArgumentException(cw + " 必须是对象 {from, to}");
                }
                JsonObject co = ce.getAsJsonObject();
                Vec3i from = vec3(req(co, "from", cw), cw + ".from");
                JsonElement toEl = co.get("to");
                // to 省略 = 单格 cube（"一个方块 = 一个 cube"的常态）
                Vec3i to = (toEl == null || toEl.isJsonNull()) ? from : vec3(toEl, cw + ".to");
                // name 可省略（schema 4 之前没有这个键）
                String cubeName = co.has("name") && co.get("name").isJsonPrimitive()
                        && co.getAsJsonPrimitive("name").isString()
                        ? co.get("name").getAsString()
                        : null;
                cubes.add(new Cube(from, to, cubeName));
            }
        }

        List<Group> subs = new ArrayList<>();
        JsonArray gs = optArray(o, "groups");
        if (gs != null) subs.addAll(parseGroupArray(gs, where + ".groups"));

        return new Group(name, cubes, subs);
    }

    /** 读规范元数据（schema 版本 / 等级 / 族 / 行为 id）。缺省即 {@link Meta#DEFAULT}。 */
    private static Meta parseMeta(String id, JsonObject json) {
        int schema = MachineStandard.CURRENT_SCHEMA;
        if (json.has("schema_version") && json.get("schema_version").isJsonPrimitive()
                && json.getAsJsonPrimitive("schema_version").isNumber()) {
            schema = json.get("schema_version").getAsInt();
        }
        int tier = 0;
        if (json.has("tier") && json.get("tier").isJsonPrimitive()
                && json.getAsJsonPrimitive("tier").isNumber()) {
            tier = json.get("tier").getAsInt();
        }
        String family = optString(json, "family");
        String behavior = optString(json, "behavior");
        return new Meta(schema, tier, family, behavior);
    }

    @Nullable
    private static String optString(JsonObject o, String key) {
        if (!o.has(key)) return null;
        var v = o.get(key);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) return null;
        String s = v.getAsString().trim();
        return s.isEmpty() ? null : s;
    }

    // ==================== JSON 序列化 ====================

    /** 写回与 {@link #parse} 完全对称的 JSON（编辑器保存、离线往返校验都用它）。 */
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        // schema 版本总是写（规范要求它在），其余元数据只在非缺省时写，避免噪声
        root.addProperty("schema_version", meta.schemaVersion());
        root.addProperty("id", id);
        if (meta.tier() != 0) root.addProperty("tier", meta.tier());
        if (meta.family() != null) root.addProperty("family", meta.family());
        if (meta.behavior() != null) root.addProperty("behavior", meta.behavior());

        // 结构树：groups → cubes / 子组（递归）。取代旧的 side_offsets / fill_regions。
        JsonArray gs = new JsonArray();
        for (Group g : groups) gs.add(groupJson(g));
        root.add("groups", gs);

        JsonArray ip = new JsonArray();
        for (var e : itemProxies.entrySet()) {
            JsonObject o = new JsonObject();
            o.add("at", vec3Json(e.getKey()));
            o.add("slots", intArrayJson(e.getValue().slots()));
            o.addProperty("io", ioName(e.getValue().io()));
            addFaces(o, e.getValue().faces());
            addFaceIntMap(o, "face_slots", e.getValue().faceSlots());
            addNote(o, notes.get(e.getKey()));
            ip.add(o);
        }
        root.add("item_proxies", ip);

        JsonArray fp = new JsonArray();
        for (var e : fluidProxies.entrySet()) {
            JsonObject o = new JsonObject();
            o.add("at", vec3Json(e.getKey()));
            o.add("tanks", intArrayJson(e.getValue().tanks()));
            o.addProperty("io", ioName(e.getValue().io()));
            addFaces(o, e.getValue().faces());
            addFaceIntMap(o, "face_tanks", e.getValue().faceTanks());
            addNote(o, notes.get(e.getKey()));
            fp.add(o);
        }
        root.add("fluid_proxies", fp);

        return root;
    }

    // ==================== 解析工具 ====================

    private static JsonArray optArray(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (!el.isJsonArray()) throw new IllegalArgumentException(key + " 必须是数组");
        return el.getAsJsonArray();
    }

    private static JsonObject asObject(JsonElement el, String where) {
        if (!el.isJsonObject()) throw new IllegalArgumentException(where + " 必须是对象");
        return el.getAsJsonObject();
    }

    private static JsonElement req(JsonObject o, String key, String where) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) throw new IllegalArgumentException(where + " 缺少必填键 " + key);
        return el;
    }

    /** 解析 {@code [x,y,z]}。 */
    private static Vec3i vec3(JsonElement el, String where) {
        if (!el.isJsonArray()) throw new IllegalArgumentException(where + " 必须是 [x,y,z] 数组");
        JsonArray a = el.getAsJsonArray();
        if (a.size() != 3) throw new IllegalArgumentException(where + " 必须恰好 3 个数字，实际 " + a.size());
        return new Vec3i(asInt(a.get(0), where + "[0]"), asInt(a.get(1), where + "[1]"), asInt(a.get(2), where + "[2]"));
    }

    private static int asInt(JsonElement el, String where) {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(where + " 必须是数字");
        }
        return el.getAsInt();
    }

    /** 解析下标数组：必须非空、非负、不重复。 */
    private static int[] intArray(JsonElement el, String where) {
        return intArray(el, where, false);
    }

    /**
     * 解析下标数组。
     *
     * @param allowEmpty 是否允许空数组。方块级 {@code slots}/{@code tanks} 不允许为空
     *                   （一个舱室不暴露任何槽位是没有意义的，属于写错）；
     *                   但<b>按面</b>的 {@code face_slots}/{@code face_tanks} 里，
     *                   空数组是有意义的状态 —— 「这一面什么都不暴露」，
     *                   与「这一面没配过（回退方块级默认）」是两回事。
     *                   序列化端本来就会写出 {@code []}，所以解析端必须接受它，
     *                   否则按面配置一存一读就炸（曾经就是这样）。
     */
    private static int[] intArray(JsonElement el, String where, boolean allowEmpty) {
        if (!el.isJsonArray()) throw new IllegalArgumentException(where + " 必须是数组");
        JsonArray a = el.getAsJsonArray();
        if (a.isEmpty()) {
            if (!allowEmpty) throw new IllegalArgumentException(where + " 不能为空");
            return new int[0];
        }
        int[] out = new int[a.size()];
        for (int i = 0; i < a.size(); i++) {
            int v = asInt(a.get(i), where + "[" + i + "]");
            if (v < 0) throw new IllegalArgumentException(where + "[" + i + "] 不能为负: " + v);
            for (int j = 0; j < i; j++) {
                if (out[j] == v) throw new IllegalArgumentException(where + " 有重复下标: " + v);
            }
            out[i] = v;
        }
        return out;
    }

    private static BaseMachineBlock.ProxyIO io(JsonElement el, String where) {
        if (!el.isJsonPrimitive()) throw new IllegalArgumentException(where + " 必须是字符串 input/output");
        String s = el.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "input", "in" -> BaseMachineBlock.ProxyIO.INPUT;
            case "output", "out" -> BaseMachineBlock.ProxyIO.OUTPUT;
            default -> throw new IllegalArgumentException(where + " 只能是 input 或 output，实际: " + el.getAsString());
        };
    }

    @Nullable
    private static Direction[] faces(JsonObject o, String where) {
        JsonElement el = o.get("faces");
        if (el == null || el.isJsonNull()) return null;
        if (!el.isJsonArray()) throw new IllegalArgumentException(where + ".faces 必须是数组");
        JsonArray a = el.getAsJsonArray();
        if (a.isEmpty()) throw new IllegalArgumentException(where + ".faces 不能为空数组（要六面全通就整个省略）");
        Direction[] out = new Direction[a.size()];
        for (int i = 0; i < a.size(); i++) {
            String s = a.get(i).getAsString().trim().toUpperCase(java.util.Locale.ROOT);
            try {
                out[i] = Direction.valueOf(s);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(where + ".faces[" + i + "] 不是合法方向: " + a.get(i).getAsString());
            }
        }
        return out;
    }

    private static void noteOf(JsonObject o, Vec3i at, Map<Vec3i, String> notes, String where) {
        JsonElement el = o.get("note");
        if (el == null || el.isJsonNull()) return;
        if (!el.isJsonPrimitive()) throw new IllegalArgumentException(where + ".note 必须是字符串");
        notes.put(at, el.getAsString());
    }

    // ==================== 序列化工具 ====================

    /**
     * 一个组 → JSON（递归）。
     *
     * <p>{@code cubes} 总是写（空组写 {@code []}，读者一眼看得出"这是空组"而不是"键忘了写"）；
     * {@code groups} 只在真的有子组时才写，这样 schema 2 的平铺文件导出后形状不变。</p>
     */
    private static JsonObject groupJson(Group g) {
        JsonObject go = new JsonObject();
        go.addProperty("name", g.name());
        JsonArray cs = new JsonArray();
        for (Cube c : g.cubes()) {
            JsonObject co = new JsonObject();
            // 只在真有名字时才写：没有名字的 cube 导出后与 schema 3 的形状完全一致
            if (c.hasName()) co.addProperty("name", c.name());
            co.add("from", vec3Json(c.from()));
            co.add("to", vec3Json(c.to()));
            cs.add(co);
        }
        go.add("cubes", cs);
        if (!g.groups().isEmpty()) {
            JsonArray subs = new JsonArray();
            for (Group sub : g.groups()) subs.add(groupJson(sub));
            go.add("groups", subs);
        }
        return go;
    }

    private static JsonArray vec3Json(Vec3i v) {
        JsonArray a = new JsonArray();
        a.add(v.getX());
        a.add(v.getY());
        a.add(v.getZ());
        return a;
    }

    private static JsonArray intArrayJson(int[] arr) {
        JsonArray a = new JsonArray();
        for (int v : arr) a.add(v);
        return a;
    }

    /**
     * 解析「按面的槽位/储罐」表：{@code {"north":[2], "up":[5]}}。
     *
     * <p>键是<strong>本地方向</strong>（与 {@code faces} 同一套坐标，facing=NORTH）。
     * 值为空数组是合法的（表示"这一面什么都不暴露"），所以不做非空校验。</p>
     */
    @Nullable
    private static Map<Direction, int[]> faceIntMap(JsonObject o, String key, String where) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException(where + "." + key + " 必须是对象，如 {\"north\":[2]}");
        }
        JsonObject obj = el.getAsJsonObject();
        if (obj.isEmpty()) return null;
        Map<Direction, int[]> out = new LinkedHashMap<>();
        for (var entry : obj.entrySet()) {
            String ks = entry.getKey().trim().toUpperCase(java.util.Locale.ROOT);
            Direction d;
            try {
                d = Direction.valueOf(ks);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        where + "." + key + " 的方向键不合法: " + entry.getKey());
            }
            out.put(d, intArray(entry.getValue(), where + "." + key + "." + entry.getKey(), true));
        }
        return out;
    }

    private static void addFaceIntMap(JsonObject o, String key, @Nullable Map<Direction, int[]> map) {
        if (map == null || map.isEmpty()) return;
        JsonObject obj = new JsonObject();
        // 固定顺序输出，保证 JSON 往返逐字节稳定（离线往返校验依赖这一点）
        for (Direction d : Direction.values()) {
            int[] v = map.get(d);
            if (v != null) obj.add(d.name().toLowerCase(java.util.Locale.ROOT), intArrayJson(v));
        }
        // 兜底：万一有 Direction.values() 之外的键（不可能，但别静默丢数据）
        for (var e : map.entrySet()) {
            if (obj.has(e.getKey().name().toLowerCase(java.util.Locale.ROOT))) continue;
            obj.add(e.getKey().name().toLowerCase(java.util.Locale.ROOT), intArrayJson(e.getValue()));
        }
        if (!obj.isEmpty()) o.add(key, obj);
    }

    private static void addFaces(JsonObject o, @Nullable Direction[] faces) {
        if (faces == null || faces.length == 0) return;
        JsonArray a = new JsonArray();
        for (Direction d : faces) a.add(d.name().toLowerCase(java.util.Locale.ROOT));
        o.add("faces", a);
    }

    private static void addNote(JsonObject o, @Nullable String note) {
        if (note != null && !note.isBlank()) o.addProperty("note", note);
    }

    private static String ioName(BaseMachineBlock.ProxyIO io) {
        return io == BaseMachineBlock.ProxyIO.INPUT ? "input" : "output";
    }

    public static String fmt(Vec3i v) {
        return "(" + v.getX() + "," + v.getY() + "," + v.getZ() + ")";
    }

    @Override
    public String toString() {
        return "MachineDefinition[" + id + " side=" + sideOffsets.length
                + " regions=" + (fillRegions == null ? 0 : fillRegions.length)
                + " item=" + itemProxies.size() + " fluid=" + fluidProxies.size() + "]";
    }
}
