package com.mss.polymech.client.gui.machine.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.common.MachineGeometry;
import com.mss.polymech.machine.definition.MachineDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机器结构快照 —— 3D 编辑器的<strong>可编辑数据模型</strong>，与
 * {@link MachineDefinition}（JSON）<strong>一一对应</strong>。
 *
 * <h3>为什么保留 side_offsets / fill_regions 两分法</h3>
 * 编辑器当然可以把所有格子摊平成"逐格列表"，但那样导出就把大机器的区间退化成上千个离散格——
 * 正是之前被否掉的"逐格画图案"。所以快照保留原始两分法：
 * <ul>
 *   <li>{@code sideOffsets}：离散格（手动加的格子也进这里）；</li>
 *   <li>{@code fillRegions}：区间（超大机器的主体，导出仍是区间）。</li>
 * </ul>
 * 删除一个落在区间里的格子时，把区间<strong>切成最多 6 块</strong>
 * （{@link #subtractCell}），而不是摊平——所以"抠洞"之后导出还是区间。
 *
 * <h3>坐标</h3>
 * 全是 {@code facing=NORTH} 的本地坐标，与 JSON 声明一致。渲染时整体平移
 * {@code originShift} 变成非负坐标喂给 {@code Scene} 的 DummyWorld，查询时减回去。
 */
public final class MachineStructureSnapshot {

    /** 一格：本地坐标 + 角色（控制器 / 外壳 / 物品口 / 流体口）。 */
    public record Cell(Vec3i local,
                       boolean controller,
                       @Nullable BaseMachineBlock.ItemProxy itemProxy,
                       @Nullable BaseMachineBlock.FluidProxy fluidProxy) {

        public String describe() {
            if (controller) return "控制器（主方块，原点）";
            StringBuilder sb = new StringBuilder();
            if (itemProxy != null) {
                sb.append("物品 ").append(ioName(itemProxy.io()))
                        .append(" 槽位").append(arr(itemProxy.slots()))
                        .append(facesText(itemProxy.faces()));
            }
            if (fluidProxy != null) {
                if (!sb.isEmpty()) sb.append(" / ");
                sb.append("流体 ").append(ioName(fluidProxy.io()))
                        .append(" 储罐").append(arr(fluidProxy.tanks()))
                        .append(facesText(fluidProxy.faces()));
            }
            return sb.isEmpty() ? "外壳（不代理）" : sb.toString();
        }

        private static String ioName(BaseMachineBlock.ProxyIO io) {
            return io == BaseMachineBlock.ProxyIO.INPUT ? "输入" : "输出";
        }

        private static String arr(int[] a) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(a[i]);
            }
            return sb.append("]").toString();
        }

        private static String facesText(@Nullable Direction[] faces) {
            if (faces == null || faces.length == 0) return "";
            StringBuilder sb = new StringBuilder(" 开口面[");
            for (int i = 0; i < faces.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(faces[i].name());
            }
            return sb.append("]").toString();
        }
    }

    /** 填充区间（含边界，与 JSON 的 from/to 一致）。 */
    public record Region(Vec3i from, Vec3i to) {
    }

    /**
     * 结构树里的<strong>路径寻址</strong>：从根开始一路往下的组下标。
     *
     * <p>schema 3 的组可以嵌套，光用一个 {@code int} 已经指不到唯一的组
     * （顶层的第 1 组 与 顶层第 0 组的第 0 个子组 是两回事）。空路径 = 根
     * （顶层组的容器，不是任何一个真实的组），{@code [0]} = 顶层第 0 组，
     * {@code [0,1]} = 它的第 1 个子组。</p>
     */
    public record GroupPath(List<Integer> indices) {
        /** 根：顶层组的容器（本身不是一个组）。 */
        public static final GroupPath ROOT = new GroupPath(List.of());

        public GroupPath {
            indices = indices == null ? List.of() : List.copyOf(indices);
        }

        public static GroupPath of(int... idx) {
            List<Integer> l = new ArrayList<>(idx.length);
            for (int i : idx) l.add(i);
            return new GroupPath(l);
        }

        public boolean isRoot() {
            return indices.isEmpty();
        }

        /** 层级：顶层组 = 1，根 = 0。 */
        public int depth() {
            return indices.size();
        }

        public int last() {
            return indices.get(indices.size() - 1);
        }

        /** 父路径（根的父还是根）。 */
        public GroupPath parent() {
            return isRoot() ? this : new GroupPath(indices.subList(0, indices.size() - 1));
        }

        /** 在本路径下再往下一层。 */
        public GroupPath child(int index) {
            List<Integer> l = new ArrayList<>(indices);
            l.add(index);
            return new GroupPath(l);
        }

        @Override
        public String toString() {
            return indices.toString();
        }
    }

    /** 结构树里的一个组 + 它的路径与层级（大纲按深度优先顺序用它）。 */
    public record GroupEntry(GroupPath path, MachineDefinition.Group group, int depth) {
    }

    /** 结构树里的一个 cube + 它所在组的路径（多选与移交用它定位）。 */
    public record CubeEntry(GroupPath path, MachineDefinition.Cube cube, int index) {
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    private final String machineId;
    /** 规范元数据（schema 版本 / 行为 id / 等级 / 族），随 loadFrom 带入、随 toDefinition 带出。 */
    private MachineDefinition.Meta meta = MachineDefinition.Meta.DEFAULT;
    /** 结构树：文件夹 + cube —— 结构的<strong>权威存储</strong>，格子由它展平。 */
    private final List<MachineDefinition.Group> groups = new ArrayList<>();
    private final Map<Vec3i, BaseMachineBlock.ItemProxy> itemProxies = new LinkedHashMap<>();
    private final Map<Vec3i, BaseMachineBlock.FluidProxy> fluidProxies = new LinkedHashMap<>();
    private final Map<Vec3i, String> notes = new LinkedHashMap<>();

    /** 本地坐标 → 格子（由上面三者派生，改动后重建）。 */
    private final Map<Vec3i, Cell> cellsByLocal = new LinkedHashMap<>();
    private Vec3i originShift = Vec3i.ZERO;

    public MachineStructureSnapshot(String machineId) {
        this.machineId = machineId;
        rebuild();
    }

    /**
     * 从机器现有定义读取快照。
     * <p>形状与语义都来自 JSON（经 {@code BaseMachineBlock} 查表），
     * 枚举用 {@link MachineGeometry#enumerateLocalOffsets} —— 与放置逻辑同一份实现，
     * 所以编辑器里画的和游戏中放出来的必然一致。</p>
     */
    public static MachineStructureSnapshot of(String machineId, BaseMachineBlock block) {
        var snap = new MachineStructureSnapshot(machineId);

        MachineDefinition def = block.definition();
        if (def != null) snap.groups.addAll(def.groups());

        // 逐格问语义（形状未覆盖到的格不会有代理）
        for (Vec3i local : MachineGeometry.enumerateLocalOffsets(
                block.getSideOffsets(), block.getFillRegions())) {
            var item = block.getItemProxy(local);
            var fluid = block.getFluidProxy(local);
            if (item != null) snap.itemProxies.put(local, item);
            if (fluid != null) snap.fluidProxies.put(local, fluid);
        }

        // 说明文字直接从 JSON 定义带过来（编辑过的格会清掉，见 setRole）。
        // 注意要取"有物品代理或有流体代理"的并集：只取物品代理会丢掉流体代理的注释。
        java.util.Set<Vec3i> withProxy = new LinkedHashSet<>(snap.itemProxies.keySet());
        withProxy.addAll(snap.fluidProxies.keySet());
        for (Vec3i local : withProxy) {
            String note = noteOf(block, local);
            if (note != null) snap.notes.put(local, note);
        }

        snap.rebuild();
        return snap;
    }

    @Nullable
    private static String noteOf(BaseMachineBlock block, Vec3i local) {
        var def = block.definition();
        return def == null ? null : def.note(local);
    }

    // ==================== 查询 ====================

    public String machineId() {
        return machineId;
    }

    /** 顶层组（结构树的根一层）。这是编辑器里结构的唯一真相。 */
    public List<MachineDefinition.Group> groups() {
        return List.copyOf(groups);
    }

    /**
     * 递归展平<strong>全部</strong>组（深度优先，父在前），带路径与层级。
     *
     * <p>大纲树、组计数、缩进冲突判定都走它 —— 只看 {@link #groups()} 会漏掉子组
     * （schema 3 的嵌套 cube 就会在界面上消失）。</p>
     */
    public List<GroupEntry> allGroups() {
        List<GroupEntry> out = new ArrayList<>();
        collectGroups(groups, new ArrayList<>(), out);
        return out;
    }

    private static void collectGroups(List<MachineDefinition.Group> list,
                                      List<Integer> prefix, List<GroupEntry> out) {
        for (int i = 0; i < list.size(); i++) {
            MachineDefinition.Group g = list.get(i);
            prefix.add(i);
            out.add(new GroupEntry(new GroupPath(prefix), g, prefix.size()));
            collectGroups(g.groups(), prefix, out);
            prefix.remove(prefix.size() - 1);
        }
    }

    /** 组总数（含嵌套）。 */
    public int countGroups() {
        return allGroups().size();
    }

    /** 按路径取组；路径无效返回 null。 */
    @Nullable
    public MachineDefinition.Group groupAt(@Nullable GroupPath path) {
        if (path == null || path.isRoot()) return null;
        List<MachineDefinition.Group> level = groups;
        MachineDefinition.Group cur = null;
        for (int idx : path.indices()) {
            if (idx < 0 || idx >= level.size()) return null;
            cur = level.get(idx);
            level = cur.groups();
        }
        return cur;
    }

    /** 全部 cube + 它所在组的路径（大纲与多选定位用）。 */
    public List<CubeEntry> cubesWithPath() {
        List<CubeEntry> out = new ArrayList<>();
        for (GroupEntry e : allGroups()) {
            List<MachineDefinition.Cube> cs = e.group().cubes();
            for (int i = 0; i < cs.size(); i++) {
                out.add(new CubeEntry(e.path(), cs.get(i), i));
            }
        }
        return out;
    }

    /** 某个 cube 所在组的路径（找不到返回 null）。 */
    @Nullable
    public GroupPath cubePath(@Nullable MachineDefinition.Cube cube) {
        if (cube == null) return null;
        return cubePathIn(groups, cube, new ArrayList<>());
    }

    @Nullable
    private static GroupPath cubePathIn(List<MachineDefinition.Group> list,
                                         MachineDefinition.Cube cube, List<Integer> prefix) {
        for (int i = 0; i < list.size(); i++) {
            MachineDefinition.Group g = list.get(i);
            if (g.cubes().contains(cube)) {
                prefix.add(i);
                GroupPath p = new GroupPath(prefix);
                prefix.remove(prefix.size() - 1);
                return p;
            }
            prefix.add(i);
            GroupPath r = cubePathIn(g.groups(), cube, prefix);
            prefix.remove(prefix.size() - 1);
            if (r != null) return r;
        }
        return null;
    }

    /** 全部结构格子（展平 groups）。保留"离散偏移"的读法，供校验器等旧调用方使用。 */
    public List<Vec3i> sideOffsets() {
        List<Vec3i> out = new ArrayList<>();
        for (var g : groups) {
            for (var c : g.allCubes()) {
                for (int x = c.minX(); x <= c.maxX(); x++) {
                    for (int y = c.minY(); y <= c.maxY(); y++) {
                        for (int z = c.minZ(); z <= c.maxZ(); z++) {
                            out.add(new Vec3i(x, y, z));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 全部格子（含控制器），保序、去重。 */
    public List<Cell> cells() {
        return new ArrayList<>(cellsByLocal.values());
    }

    public int cellCount() {
        return cellsByLocal.size();
    }

    public boolean hasCell(Vec3i local) {
        return cellsByLocal.containsKey(local);
    }

    @Nullable
    public Cell cellAt(Vec3i local) {
        return cellsByLocal.get(local);
    }

    /** 渲染坐标（非负）→ 本地坐标。 */
    @Nullable
    public Cell cellAtRenderPos(BlockPos renderPos) {
        return cellAt(new Vec3i(renderPos.getX() - originShift.getX(),
                renderPos.getY() - originShift.getY(),
                renderPos.getZ() - originShift.getZ()));
    }

    public Vec3i originShift() {
        return originShift;
    }

    /** 本地坐标 → 非负渲染坐标。 */
    public BlockPos toRenderPos(Vec3i local) {
        return new BlockPos(local.getX() + originShift.getX(),
                local.getY() + originShift.getY(),
                local.getZ() + originShift.getZ());
    }

    /** 全部格子的渲染坐标（喂给 {@code Scene.setRenderedCore}）。 */
    public List<BlockPos> renderPositions() {
        List<BlockPos> out = new ArrayList<>(cellsByLocal.size());
        for (Vec3i local : cellsByLocal.keySet()) {
            out.add(toRenderPos(local));
        }
        return out;
    }

    /** 该格是否来自填充区间（决定删除时的提示语）。递归到子组。 */
    public boolean isInFillRegion(Vec3i local) {
        return isInFillRegion(groups, local);
    }

    private static boolean isInFillRegion(List<MachineDefinition.Group> list, Vec3i local) {
        for (var g : list) {
            if (g.cubes().size() > 1) {   // 单 cube 组 = 手动加的，不算"批量区间"
                for (var c : g.cubes()) {
                    if (c.contains(local)) return true;
                }
            }
            if (isInFillRegion(g.groups(), local)) return true;
        }
        return false;
    }

    // ==================== 可视化查询（3D 视口用） ====================

    /** 该格的视觉分类（控制器 / 外壳 / 物品舱室 / 流体舱室 / 两者）。 */
    public EditorCellVisuals.Kind kindOf(Vec3i local) {
        Cell c = cellsByLocal.get(local);
        return c == null ? EditorCellVisuals.Kind.STRUCTURE : EditorCellVisuals.kindOf(c);
    }

    /**
     * 该格实际生效的开口面集合（物品与流体代理取并集）。
     * <p>没声明面 = 六面全通，与能力层同一判据（{@link ProxyFaces#open}）。</p>
     */
    public Set<Direction> openFaces(Vec3i local) {
        Cell c = cellsByLocal.get(local);
        if (c == null) return Set.of();
        Set<Direction> out = EnumSet.noneOf(Direction.class);
        if (c.itemProxy() != null) out.addAll(ProxyFaces.open(c.itemProxy().faces()));
        if (c.fluidProxy() != null) out.addAll(ProxyFaces.open(c.fluidProxy().faces()));
        return out;
    }

    /** 全部舱室格（物品或流体），保持格子表的顺序，界面列表用。 */
    public List<Cell> proxyCells() {
        List<Cell> out = new ArrayList<>();
        for (Cell c : cellsByLocal.values()) {
            if (c.itemProxy() != null || c.fluidProxy() != null) out.add(c);
        }
        return out;
    }

    public int proxyCount() {
        return itemProxies.size() + fluidProxies.size();
    }

    /** 该格的说明文字（没有则 null）。 */
    @Nullable
    public String noteAt(Vec3i local) {
        return notes.get(local);
    }

    // ==================== 编辑 ====================

    /** 加一格：往最后一个文件夹塞一个单格 cube（没有文件夹就建一个"结构"）。 */
    public boolean addCell(Vec3i local) {
        if (hasCell(local)) return false;
        var cube = new MachineDefinition.Cube(local, local);
        if (groups.isEmpty()) {
            groups.add(new MachineDefinition.Group("结构", List.of(cube)));
        } else {
            int last = groups.size() - 1;
            var g = groups.get(last);
            List<MachineDefinition.Cube> cubes = new ArrayList<>(g.cubes());
            cubes.add(cube);
            groups.set(last, withCubes(g, cubes));
        }
        rebuild();
        return true;
    }

    /**
     * 角点区间填充 —— <b>不是新图元</b>，而是「新建一个文件夹 + 生成体积那么多个单格 cube」
     * （2×2×2 → 8 个 cube）。这是用户定的模型：一个方块 = 一个 cube。
     */
    public void addFillRegion(Vec3i from, Vec3i to) {
        var box = new MachineDefinition.Cube(from, to);
        List<MachineDefinition.Cube> cubes = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    Vec3i p = new Vec3i(x, y, z);
                    cubes.add(new MachineDefinition.Cube(p, p));
                }
            }
        }
        groups.add(new MachineDefinition.Group("区间 " + (groups.size() + 1), cubes));
        rebuild();
    }

    /** 删掉第 index 个文件夹（连同它里面的 cube）。 */
    public boolean removeFillRegion(int index) {
        if (index < 0 || index >= groups.size()) return false;
        groups.remove(index);
        rebuild();
        return true;
    }

    /** 清空全部文件夹（结构只剩控制器）。 */
    public void clearFillRegions() {
        groups.clear();
        rebuild();
    }

    /** 设置某格的说明文字（空串 = 删掉）。 */
    public void setNote(Vec3i local, @Nullable String note) {
        if (note == null || note.isBlank()) notes.remove(local);
        else notes.put(local, note.trim());
    }

    /**
     * 用一份定义<strong>整体替换</strong>内容（撤销/重做、从盘上重载）。
     *
     * <p>撤销栈存的就是 {@link MachineDefinition} 快照，换回来即可 ——
     * 不需要为每种编辑写逆运算（区间切割"一拆六"的逆并不唯一，写逆运算必错）。</p>
     */
    public void loadFrom(MachineDefinition def) {
        // 结构以 groups 为准 —— 分组名与每个 cube 都原样带过来，
        // 这样"编辑器打开 → 保存"不会把作者的分组结构压成一个组。
        groups.clear();
        groups.addAll(def.groups());
        itemProxies.clear();
        itemProxies.putAll(def.itemProxies());
        fluidProxies.clear();
        fluidProxies.putAll(def.fluidProxies());
        notes.clear();
        notes.putAll(def.notes());
        // 规范元数据也要带过来，否则编辑器保存时会把 behavior/tier/family 静默丢掉
        this.meta = def.meta();
        rebuild();
    }

    /**
     * 删一格。
     * <p>落在填充区间里时把区间切成最多 6 块（不摊平），所以大机器抠洞后导出仍是区间。</p>
     *
     * @return 是否删掉了（控制器、不存在的格返回 false）
     */
    public boolean removeCell(Vec3i local) {
        if (local.equals(Vec3i.ZERO)) return false;   // 控制器不可删
        boolean changed = carveCell(groups, local);
        if (changed) {
            itemProxies.remove(local);
            fluidProxies.remove(local);
            notes.remove(local);
            rebuild();
        }
        return changed;
    }

    /** 递归找包含该格的 cube，抠掉那一格（大盒子拆成周围若干块，不摊平）。 */
    private static boolean carveCell(List<MachineDefinition.Group> list, Vec3i local) {
        for (int gi = 0; gi < list.size(); gi++) {
            MachineDefinition.Group g = list.get(gi);
            List<MachineDefinition.Cube> cubes = new ArrayList<>(g.cubes());
            for (int ci = 0; ci < cubes.size(); ci++) {
                MachineDefinition.Cube c = cubes.get(ci);
                if (!c.contains(local)) continue;
                cubes.remove(ci);
                if (!c.isUnit()) {
                    // 从大盒子里抠掉一格：拆成周围若干盒子（不摊平成上千个单格）
                    for (Region part : subtractCell(c, local)) {
                        cubes.add(ci++, new MachineDefinition.Cube(part.from(), part.to()));
                    }
                }
                list.set(gi, withCubes(g, cubes));
                return true;
            }
            List<MachineDefinition.Group> subs = new ArrayList<>(g.groups());
            if (carveCell(subs, local)) {
                list.set(gi, withSubGroups(g, subs));
                return true;
            }
        }
        return false;
    }

    /** 设置某格的代理语义（两者都可为 null = 纯外壳）。该格必须已在结构中。 */
    /**
     * 写某一面的物品槽位（{@code face_slots}）。
     *
     * @param face  本地方向（与 faces 同坐标）
     * @param slots null = <b>删掉这一面的配置</b>（该面回退到方块级 {@code slots}）；
     *              空数组 = 这一面<strong>什么都不暴露</strong>；非空 = 这一面只暴露这些槽位
     */
    public void setItemFaceSlots(Vec3i local, Direction face, @Nullable int[] slots) {
        BaseMachineBlock.ItemProxy it = itemProxies.get(local);
        if (it == null) return;
        Map<Direction, int[]> m = new java.util.LinkedHashMap<>();
        if (it.faceSlots() != null) m.putAll(it.faceSlots());
        if (slots == null) m.remove(face);
        else m.put(face, slots.clone());
        itemProxies.put(local, new BaseMachineBlock.ItemProxy(
                it.slots(), it.io(), it.faces(), m.isEmpty() ? null : m));
        rebuild();
    }

    /** 写某一面的流体储罐（{@code face_tanks}）。语义同 {@link #setItemFaceSlots}。 */
    public void setFluidFaceTanks(Vec3i local, Direction face, @Nullable int[] tanks) {
        BaseMachineBlock.FluidProxy fl = fluidProxies.get(local);
        if (fl == null) return;
        Map<Direction, int[]> m = new java.util.LinkedHashMap<>();
        if (fl.faceTanks() != null) m.putAll(fl.faceTanks());
        if (tanks == null) m.remove(face);
        else m.put(face, tanks.clone());
        fluidProxies.put(local, new BaseMachineBlock.FluidProxy(
                fl.tanks(), fl.io(), fl.faces(), m.isEmpty() ? null : m));
        rebuild();
    }

    /** 渲染坐标 → 本地坐标（{@link #toRenderPos} 的逆运算）。 */
    public Vec3i toLocal(BlockPos renderPos) {
        return new Vec3i(renderPos.getX() - originShift.getX(),
                renderPos.getY() - originShift.getY(),
                renderPos.getZ() - originShift.getZ());
    }

    /**
     * 改这一格舱室的<b>开口面</b>（物品与流体代理一起改，各自保留槽位/储罐、IO 与按面配置）。
     *
     * @param faces null 或空数组 = 六面全通（导出时省略 faces 字段）
     */
    public void setFaces(Vec3i local, @Nullable Direction[] faces) {
        BaseMachineBlock.ItemProxy it = itemProxies.get(local);
        if (it != null) {
            itemProxies.put(local, new BaseMachineBlock.ItemProxy(
                    it.slots(), it.io(), faces, it.faceSlots()));
        }
        BaseMachineBlock.FluidProxy fl = fluidProxies.get(local);
        if (fl != null) {
            fluidProxies.put(local, new BaseMachineBlock.FluidProxy(
                    fl.tanks(), fl.io(), faces, fl.faceTanks()));
        }
        rebuild();
    }

    /**
     * 按"物品/流体"开关重建这一格的舱室角色（对应"舱室角色"工具循环切换）。
     *
     * <p>新造的代理<strong>槽位留空</strong>，由作者在界面上填——故意不猜默认槽位：
     * 猜错了是静默错误，留空会被 {@code EditorValidator} 直接标成 ERROR 提示去填。</p>
     */
    public void setRoleKind(Vec3i local, boolean item, boolean fluid) {
        if (!item) {
            itemProxies.remove(local);
        } else if (!itemProxies.containsKey(local)) {
            itemProxies.put(local, new BaseMachineBlock.ItemProxy(
                    new int[0], BaseMachineBlock.ProxyIO.INPUT, null));
        }
        if (!fluid) {
            fluidProxies.remove(local);
        } else if (!fluidProxies.containsKey(local)) {
            fluidProxies.put(local, new BaseMachineBlock.FluidProxy(
                    new int[0], BaseMachineBlock.ProxyIO.INPUT, null));
        }
        rebuild();
    }

    public boolean setRole(Vec3i local, @Nullable BaseMachineBlock.ItemProxy item,
                           @Nullable BaseMachineBlock.FluidProxy fluid) {
        if (!hasCell(local)) return false;
        if (item == null) itemProxies.remove(local);
        else itemProxies.put(local, item);
        if (fluid == null) fluidProxies.remove(local);
        else fluidProxies.put(local, fluid);
        notes.remove(local);   // 语义改了，旧说明不再适用
        rebuild();
        return true;
    }

    // ==================== 导出 ====================

    /** 转成定义对象（JSON 的唯一序列化入口在 {@link MachineDefinition#toJson()}）。 */
    public MachineDefinition toDefinition() {
        return new MachineDefinition(machineId,
                new ArrayList<>(groups),
                new LinkedHashMap<>(itemProxies),
                new LinkedHashMap<>(fluidProxies),
                new LinkedHashMap<>(notes),
                meta);
    }

    /** 规范元数据（编辑器保存时随定义一起写回 JSON）。 */
    public MachineDefinition.Meta meta() {
        return meta;
    }

    public JsonObject toJson() {
        return toDefinition().toJson();
    }

    /** 导出格式化 JSON 文本（编辑器保存/复制用）。 */
    public String exportJson() {
        return PRETTY.toJson(toJson());
    }

    /**
     * 一句话概括，显示在界面顶部。
     * <p>刻意做短：窄栏里长文案必然出框，而且"格"和"cube"本来就是同一个东西
     * （一个方块 = 一个 cube），写两遍是废话。</p>
     */
    public String summary() {
        return cellsByLocal.size() + " 格 · " + countGroups() + " 组"
                + " · 物品 " + itemProxies.size() + " · 流体 " + fluidProxies.size();
    }

    // ==================== 内部 ====================

    /** 由 groups 展平重建格子表与渲染平移量。 */
    private void rebuild() {
        cellsByLocal.clear();
        LinkedHashSet<Vec3i> all = new LinkedHashSet<>();
        for (var g : groups) {
            for (var c : g.allCubes()) {
                for (int x = c.minX(); x <= c.maxX(); x++) {
                    for (int y = c.minY(); y <= c.maxY(); y++) {
                        for (int z = c.minZ(); z <= c.maxZ(); z++) {
                            all.add(new Vec3i(x, y, z));
                        }
                    }
                }
            }
        }
        all.add(Vec3i.ZERO);   // 控制器始终在

        for (Vec3i local : all) {
            boolean controller = local.equals(Vec3i.ZERO);
            cellsByLocal.put(local, new Cell(local, controller,
                    controller ? null : itemProxies.get(local),
                    controller ? null : fluidProxies.get(local)));
        }
        recomputeShift();
    }

    /** 抠一格：把盒子拆成周围至多 6 块（复用盒子版实现）。 */
    private static List<Region> subtractCell(MachineDefinition.Cube c, Vec3i cell) {
        return subtractCell(new Region(c.from(), c.to()), cell);
    }

    /** 全部 cube（跨组递归展平，含嵌套子组）。 */
    public List<MachineDefinition.Cube> cubes() {
        List<MachineDefinition.Cube> out = new ArrayList<>();
        for (var g : groups) out.addAll(g.allCubes());
        return out;
    }

    /** 包含某格的那个 cube（没有返回 null）—— 右侧「变换」面板改的就是它。递归到子组。 */
    @Nullable
    public MachineDefinition.Cube cubeAt(Vec3i local) {
        if (local == null) return null;
        for (MachineDefinition.Group g : groups) {
            var c = cubeAtIn(g, local);
            if (c != null) return c;
        }
        return null;
    }

    @Nullable
    private static MachineDefinition.Cube cubeAtIn(MachineDefinition.Group g, Vec3i local) {
        for (var c : g.cubes()) {
            if (c.contains(local)) return c;
        }
        for (var sub : g.groups()) {
            var c = cubeAtIn(sub, local);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * 把某个 cube 换成 {@code newFrom..newTo}（位置与尺寸都走这里）。
     *
     * <p>用「值相等」找回原 cube（{@code Cube} 是 record），所以调用方只要把
     * <b>改之前那一个</b> 传进来 —— 不用索引，索引在插入/删除后会失效。</p>
     */
    public boolean updateCube(MachineDefinition.Cube oldCube, Vec3i newFrom, Vec3i newTo) {
        if (oldCube == null) return false;
        // 名字不参与几何，但换 cube 实例时必须带上 —— 否则改个尺寸名字就没了
        if (!replaceCubeIn(groups, oldCube,
                new MachineDefinition.Cube(newFrom, newTo, oldCube.name()))) return false;
        rebuild();
        return true;
    }

    private static boolean replaceCubeIn(List<MachineDefinition.Group> list,
                                         MachineDefinition.Cube oldCube,
                                         MachineDefinition.Cube newCube) {
        for (int gi = 0; gi < list.size(); gi++) {
            MachineDefinition.Group g = list.get(gi);
            List<MachineDefinition.Cube> cubes = new ArrayList<>(g.cubes());
            int idx = cubes.indexOf(oldCube);
            if (idx >= 0) {
                cubes.set(idx, newCube);
                list.set(gi, withCubes(g, cubes));
                return true;
            }
            List<MachineDefinition.Group> subs = new ArrayList<>(g.groups());
            if (replaceCubeIn(subs, oldCube, newCube)) {
                list.set(gi, withSubGroups(g, subs));
                return true;
            }
        }
        return false;
    }

    /**
     * 按路径给组改名（含嵌套；名字不参与几何，所以不用 rebuild）。
     *
     * <p>重建 {@code Group} 时必须把 {@code g.groups()} 一起带上 —— 只写
     * {@code new Group(n, g.cubes())} 会把子组连同里面的 cube 一起丢掉。</p>
     */
    public boolean renameGroup(@Nullable GroupPath path, String name) {
        if (path == null || path.isRoot()) return false;
        var g = groupAt(path);
        if (g == null) return false;
        String n = (name == null || name.isBlank()) ? "未命名" : name.trim();
        return replaceGroupIn(groups, path.indices(), 0,
                new MachineDefinition.Group(n, g.cubes(), g.groups()));
    }

    /** 顶层组改名（旧的一参 API，等价于 {@code renameGroup(GroupPath.of(index), name)}）。 */
    public boolean renameGroup(int index, String name) {
        return renameGroup(GroupPath.of(index), name);
    }

    /**
     * 给 cube 改名字（{@code null}/空白 = 去掉名字，退回显示 "cube"）。递归到子组。
     *
     * <p>名字不参与几何，所以不用 {@code rebuild()}。但 {@link MachineDefinition.Cube}
     * 是 record，{@code name} 参与 equals —— 改名等于换了一个实例，调用方手里的旧引用
     * 会失效，必须用返回值替换（比如大纲的选中集合）。</p>
     *
     * @return 改名后的 cube；{@code null} = 结构里找不到这个 cube
     */
    @Nullable
    public MachineDefinition.Cube renameCube(@Nullable MachineDefinition.Cube cube, @Nullable String name) {
        if (cube == null) return null;
        var renamed = new MachineDefinition.Cube(cube.from(), cube.to(), name);
        if (renamed.equals(cube)) return cube;                    // 名字没变
        if (!replaceCubeIn(groups, cube, renamed)) return null;
        return renamed;
    }

    /** 递归删掉一个组（连同它的子组与里面的 cube）。 */
    public boolean removeGroup(@Nullable GroupPath path) {
        if (path == null || path.isRoot()) return false;
        if (!removeGroupIn(groups, path.indices(), 0)) return false;
        rebuild();
        return true;
    }

    /**
     * 在 {@code parent} 下新建一个组（{@code parent} 为 null 或 {@link GroupPath#ROOT} 就是顶层），
     * 返回新组的路径；父路径无效返回 null。
     */
    @Nullable
    public GroupPath addGroup(@Nullable GroupPath parent, String name) {
        String n = (name == null || name.isBlank()) ? "组 " + (countGroups() + 1) : name.trim();
        var created = new MachineDefinition.Group(n, List.of());
        if (parent == null || parent.isRoot()) {
            groups.add(created);
            rebuild();
            return GroupPath.of(groups.size() - 1);
        }
        var p = groupAt(parent);
        if (p == null) return null;
        List<MachineDefinition.Group> subs = new ArrayList<>(p.groups());
        subs.add(created);
        if (!replaceGroupIn(groups, parent.indices(), 0, withSubGroups(p, subs))) return null;
        rebuild();
        return parent.child(subs.size() - 1);
    }

    /**
     * 把一个 cube 从它所在的组<b>移交</b>到目标组（不是复制）。
     *
     * <p>这是"用选中新建组"与"缩进冲突确认"共用的那一步：一个 cube 只属于一个组，
     * 所以移交 = 从原处摘掉 + 追加到目标组末尾。cube 已经在目标组里时返回 false（没变化）。</p>
     */
    public boolean moveCubeToGroup(@Nullable MachineDefinition.Cube cube, @Nullable GroupPath target) {
        if (cube == null || target == null || target.isRoot()) return false;
        var from = cubePath(cube);
        if (from == null || from.equals(target)) return false;
        if (!detachCube(cube)) return false;
        var t = groupAt(target);
        if (t == null) return false;
        List<MachineDefinition.Cube> cs = new ArrayList<>(t.cubes());
        cs.add(cube);
        if (!replaceGroupIn(groups, target.indices(), 0, withCubes(t, cs))) return false;
        rebuild();
        return true;
    }

    /** 只把 cube 从树里摘下来（<b>不动</b>代理/说明，也不 rebuild）—— 移交的中间步骤。 */
    private boolean detachCube(MachineDefinition.Cube cube) {
        return detachCubeIn(groups, cube);
    }

    private static boolean detachCubeIn(List<MachineDefinition.Group> list, MachineDefinition.Cube cube) {
        for (int gi = 0; gi < list.size(); gi++) {
            MachineDefinition.Group g = list.get(gi);
            List<MachineDefinition.Cube> cubes = new ArrayList<>(g.cubes());
            if (cubes.remove(cube)) {
                list.set(gi, withCubes(g, cubes));
                return true;
            }
            List<MachineDefinition.Group> subs = new ArrayList<>(g.groups());
            if (detachCubeIn(subs, cube)) {
                list.set(gi, withSubGroups(g, subs));
                return true;
            }
        }
        return false;
    }

    /**
     * 删掉整个 cube（大纲树上的「×」）。
     *
     * <p>与 {@link #removeCell} 的区别：那个是"从盒子里抠一格"（盒子会拆成周围几块），
     * 这个是"整个 cube 不要了"——单格 cube 两者等价，盒子上差别很大。</p>
     */
    public boolean removeCube(MachineDefinition.Cube cube) {
        if (cube == null) return false;
        if (!detachCube(cube)) return false;
        clearCubeSemantics(cube);
        rebuild();
        return true;
    }

    /** 清掉一个 cube 覆盖的所有格子上的代理/说明（防止留下指向不存在格子的悬空数据）。 */
    private void clearCubeSemantics(MachineDefinition.Cube cube) {
        for (int x = cube.minX(); x <= cube.maxX(); x++) {
            for (int y = cube.minY(); y <= cube.maxY(); y++) {
                for (int z = cube.minZ(); z <= cube.maxZ(); z++) {
                    Vec3i p = new Vec3i(x, y, z);
                    itemProxies.remove(p);
                    fluidProxies.remove(p);
                    notes.remove(p);
                }
            }
        }
    }

    // ---- 递归改树的小工具（不可变的 Group 只能靠"换一个新的上去"来改） ----

    /** 递归替换某条路径上的组；{@code path} 从 {@code list} 这一层算起。 */
    private static boolean replaceGroupIn(List<MachineDefinition.Group> list, List<Integer> path,
                                          int depth, MachineDefinition.Group replacement) {
        int idx = path.get(depth);
        if (idx < 0 || idx >= list.size()) return false;
        if (depth == path.size() - 1) {
            list.set(idx, replacement);
            return true;
        }
        MachineDefinition.Group g = list.get(idx);
        List<MachineDefinition.Group> subs = new ArrayList<>(g.groups());
        if (!replaceGroupIn(subs, path, depth + 1, replacement)) return false;
        list.set(idx, withSubGroups(g, subs));
        return true;
    }

    /** 递归删除某条路径上的组。 */
    private static boolean removeGroupIn(List<MachineDefinition.Group> list, List<Integer> path, int depth) {
        int idx = path.get(depth);
        if (idx < 0 || idx >= list.size()) return false;
        if (depth == path.size() - 1) {
            list.remove(idx);
            return true;
        }
        MachineDefinition.Group g = list.get(idx);
        List<MachineDefinition.Group> subs = new ArrayList<>(g.groups());
        if (!removeGroupIn(subs, path, depth + 1)) return false;
        list.set(idx, withSubGroups(g, subs));
        return true;
    }

    /** 换掉这一组的直接 cube（子组原样保留）。 */
    private static MachineDefinition.Group withCubes(MachineDefinition.Group g,
                                                     List<MachineDefinition.Cube> cubes) {
        return new MachineDefinition.Group(g.name(), cubes, g.groups());
    }

    /** 换掉这一组的直接子组（cube 原样保留）。 */
    private static MachineDefinition.Group withSubGroups(MachineDefinition.Group g,
                                                         List<MachineDefinition.Group> subs) {
        return new MachineDefinition.Group(g.name(), g.cubes(), subs);
    }

    /** 平移量：让所有格子落到非负象限（Scene 的 DummyWorld 摆放前提）。 */
    private void recomputeShift() {
        int minX = 0, minY = 0, minZ = 0;
        for (Vec3i v : cellsByLocal.keySet()) {
            minX = Math.min(minX, v.getX());
            minY = Math.min(minY, v.getY());
            minZ = Math.min(minZ, v.getZ());
        }
        originShift = new Vec3i(-minX, -minY, -minZ);
    }

    private static boolean contains(Region r, Vec3i v) {
        int x0 = Math.min(r.from().getX(), r.to().getX()), x1 = Math.max(r.from().getX(), r.to().getX());
        int y0 = Math.min(r.from().getY(), r.to().getY()), y1 = Math.max(r.from().getY(), r.to().getY());
        int z0 = Math.min(r.from().getZ(), r.to().getZ()), z1 = Math.max(r.from().getZ(), r.to().getZ());
        return v.getX() >= x0 && v.getX() <= x1
                && v.getY() >= y0 && v.getY() <= y1
                && v.getZ() >= z0 && v.getZ() <= z1;
    }

    /**
     * 从区间里抠掉一格，返回残块（最多 6 块，互不重叠且并集 = 原区间 − 该格）。
     * <p>这是"删格子但不摊平区间"的关键：大机器抠一个洞，导出还是几个区间。</p>
     */
    static List<Region> subtractCell(Region r, Vec3i c) {
        int x0 = Math.min(r.from().getX(), r.to().getX()), x1 = Math.max(r.from().getX(), r.to().getX());
        int y0 = Math.min(r.from().getY(), r.to().getY()), y1 = Math.max(r.from().getY(), r.to().getY());
        int z0 = Math.min(r.from().getZ(), r.to().getZ()), z1 = Math.max(r.from().getZ(), r.to().getZ());
        int x = c.getX(), y = c.getY(), z = c.getZ();

        List<Region> out = new ArrayList<>();
        if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) {
            out.add(r);   // 不在里面，原样返回
            return out;
        }
        // 六个方向的残块恰好拼成"原区间去掉该格"
        if (x > x0) out.add(new Region(new Vec3i(x0, y0, z0), new Vec3i(x - 1, y1, z1)));
        if (x < x1) out.add(new Region(new Vec3i(x + 1, y0, z0), new Vec3i(x1, y1, z1)));
        if (y > y0) out.add(new Region(new Vec3i(x, y0, z0), new Vec3i(x, y - 1, z1)));
        if (y < y1) out.add(new Region(new Vec3i(x, y + 1, z0), new Vec3i(x, y1, z1)));
        if (z > z0) out.add(new Region(new Vec3i(x, y, z0), new Vec3i(x, y, z - 1)));
        if (z < z1) out.add(new Region(new Vec3i(x, y, z + 1), new Vec3i(x, y, z1)));
        return out;
    }
}
