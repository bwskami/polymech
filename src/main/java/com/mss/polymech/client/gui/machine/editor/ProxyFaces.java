package com.mss.polymech.client.gui.machine.editor;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 舱室"开口面"（JSON 里的 {@code faces}）的<strong>纯逻辑</strong>：语义、中文名、与界面/JSON 的互转。
 *
 * <h3>语义必须与能力层一致</h3>
 * {@code null} 或空数组 = <strong>六面全通</strong>，否则只有声明的面才暴露能力、才参与主动推送
 * （见 {@code BaseMachineBlock.ItemProxy#allowsWorldFace}）。编辑器只是把这条规则<strong>画出来</strong>，
 * 不另立一套；所以这里直接复用同一条判据。
 *
 * <h3>为什么单独成一个类</h3>
 * 它是纯函数（只碰 {@link Direction} 枚举），所以"全开时导出要不要写 faces 字段"
 * "中文名对不对"这些都能离线用断言钉死，不需要进游戏看。
 */
public final class ProxyFaces {

    /**
     * 六个面的固定顺序 = 界面上朝向面勾选框的顺序：上 / 下 / 北 / 南 / 西 / 东。
     * <p>导出时也按这个顺序写 {@code faces}，保证同一份结构每次导出字节一致（便于 diff）。</p>
     */
    public static final List<Direction> ORDER = List.of(
            Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST);

    private ProxyFaces() {
    }

    /**
     * 实际生效的开口面集合。
     * <p>{@code null} / 空数组 → 六面全通（与 {@code allowsWorldFace} 同一判据）。</p>
     */
    public static Set<Direction> open(@Nullable Direction[] faces) {
        if (faces == null || faces.length == 0) return EnumSet.allOf(Direction.class);
        Set<Direction> out = EnumSet.noneOf(Direction.class);
        for (Direction d : faces) {
            if (d != null) out.add(d);
        }
        return out.isEmpty() ? EnumSet.allOf(Direction.class) : out;
    }

    /** 是否六面全通（= 没声明面）。 */
    public static boolean isAllOpen(@Nullable Direction[] faces) {
        return open(faces).size() >= ORDER.size();
    }

    /**
     * 集合 → JSON 字段值。
     * <p>六面全通返回 {@code null}：导出时<strong>省略</strong> {@code faces} 字段，
     * 与手写 JSON 的"留空=全通"写法一致，不会写出多余的六项数组。</p>
     */
    @Nullable
    public static Direction[] toArray(@Nullable Set<Direction> faces) {
        if (faces == null || faces.isEmpty() || faces.size() >= ORDER.size()) return null;
        List<Direction> out = new ArrayList<>(faces.size());
        for (Direction d : ORDER) {
            if (faces.contains(d)) out.add(d);
        }
        return out.toArray(new Direction[0]);
    }

    /** 中文名（界面上显示）：上/下/北/南/西/东。 */
    /**
     * 世界方向 → <b>本地方向</b>的反查（{@code faces} 存的是本地方向，与 facing 同坐标）。
     *
     * <p>编辑器里主方块是默认朝向（{@code facing=NORTH}），两者相同；仍走一次反查，
     * 免得以后编辑器支持旋转时这里悄悄错位。查不到时原样返回。</p>
     *
     * <p>用 {@link com.mss.polymech.machine.common.MachineGeometry#rotateDirection} 而不是
     * {@code BaseMachineBlock} 的同名静态方法：后者会触发外层 {@code <clinit>}（注册表依赖），
     * 离线校验会炸。</p>
     */
    public static Direction localFace(Direction worldFace, Direction facing) {
        for (Direction local : ORDER) {
            if (com.mss.polymech.machine.common.MachineGeometry
                    .rotateDirection(local, facing) == worldFace) {
                return local;
            }
        }
        return worldFace;
    }

    public static String cn(Direction d) {
        return switch (d) {
            case UP -> "上";
            case DOWN -> "下";
            case NORTH -> "北";
            case SOUTH -> "南";
            case WEST -> "西";
            case EAST -> "东";
        };
    }

    /** 一句话描述：{@code 六面全通} 或 {@code 北+上}。 */
    public static String label(@Nullable Direction[] faces) {
        Set<Direction> s = open(faces);
        if (s.size() >= ORDER.size()) return "六面全通";
        StringBuilder sb = new StringBuilder();
        for (Direction d : ORDER) {
            if (s.contains(d)) {
                if (!sb.isEmpty()) sb.append("+");
                sb.append(cn(d));
            }
        }
        return sb.toString();
    }

    /**
     * 解析用户输入：接受 {@code UP} / {@code up} / {@code 上}，分隔符 {@code , ， + 空格}。
     * <p>空输入 = 六面全通（返回 null）。</p>
     */
    @Nullable
    public static Direction[] parse(String text) {
        if (text == null || text.isBlank()) return null;
        Set<Direction> out = EnumSet.noneOf(Direction.class);
        for (String part : text.split("[,，+\\s]+")) {
            Direction d = byName(part.trim());
            if (d != null) out.add(d);
        }
        return toArray(out);
    }

    @Nullable
    private static Direction byName(String p) {
        if (p.isEmpty()) return null;
        for (Direction d : ORDER) {
            if (d.getSerializedName().equalsIgnoreCase(p)
                    || d.name().equalsIgnoreCase(p)
                    || cn(d).equals(p)) {
                return d;
            }
        }
        return null;
    }
}
