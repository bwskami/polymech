package com.mss.polymech.client.gui.machine.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 半透明填充方块的<b>面剔除</b>与<b>绕序</b> —— 让一堆相邻格子看起来像一整块实心体，
 * 而不是一堆各自发光的玻璃。
 *
 * <h3>要解决的两个问题（都是"越叠越白"）</h3>
 * <ol>
 *   <li><b>相邻格之间的内面</b>：两个格子贴在一起时，接触面在视觉上完全被遮住，
 *       但半透明渲染里它<strong>照样参与混合</strong>，于是 alpha 叠成
 *       {@code 1-(1-a)²}，比单层明显更白。</li>
 *   <li><b>背面</b>：一个格子如果 6 个面全提交（双面），近处看是"正面 + 背面"两层，
 *       同样叠 alpha。</li>
 * </ol>
 *
 * <p>MC 玻璃就是靠 {@code Block.shouldRenderFace}/{@code skipRendering} 去掉相邻面
 * ＋ 背面剔除不画背面来解决的，所以<strong>玻璃叠放不会越叠越白</strong>。
 * 这里照同一套做法：只提交"外露面"（{@link #outerFaces}）＋ 只按正面绕序提交
 * （{@link #outwardCorners}），再配合 {@code RenderSystem.enableCull()}。
 * 结果：一整块实心区域在任何视线方向上<strong>只有一层 alpha</strong>。</p>
 *
 * <h3>绕序为什么能离线断言</h3>
 * 纯坐标数学。{@link #outwardCorners} 按「{@code t × u = 法线}」的右手系构造，
 * 所以 {@code cross(c1-c0, c2-c0)} 必然平行于该面的<strong>外法线</strong>——
 * 离线断言直接算叉乘即可判死，不用进游戏看"哪一面没画出来"。
 */
public final class CellFillFaces {

    private CellFillFaces() {
    }

    /**
     * 该格朝向 {@code d} 的面是不是"外露面"（邻居不是填充格才要画）。
     * <p>邻居是填充格 → 这个面在内部，视觉上永远看不到，提交它只会叠 alpha。</p>
     */
    public static boolean isOuterFace(Vec3i local, Direction d, Set<Vec3i> filled) {
        Vec3i neighbor = local.offset(d.getStepX(), d.getStepY(), d.getStepZ());
        return !filled.contains(neighbor);
    }

    /** 该格所有需要提交的面（相邻填充格之间的面已剔除）。 */
    public static List<Direction> outerFaces(Vec3i local, Set<Vec3i> filled) {
        List<Direction> out = new ArrayList<>(6);
        for (Direction d : Direction.values()) {
            if (isOuterFace(local, d, filled)) out.add(d);
        }
        return out;
    }

    /**
     * 一条棱（绝对坐标的两个端点）。
     */
    public record Edge(float x1, float y1, float z1, float x2, float y2, float z2) {
    }

    /**
     * 该格<b>需要画的棱</b> —— 相邻同种方块之间的棱不画，效果就是<strong>无缝玻璃</strong>：
     * 一整片结构只剩外轮廓和折痕，不会每一格都描一遍内部缝。
     *
     * <h3>判据（体素轮廓提取的标准做法）</h3>
     * 一条棱周围在垂直平面内有 <b>4 个象限</b>（2×2）。数填充了几个：
     * <ul>
     *   <li><b>1 个</b> → 凸角，画；</li>
     *   <li><b>2 个相邻</b>（共边）→ 表面是平的，这条棱只是两块之间的接缝 →
     *       <b>不画</b>（无缝玻璃要的正是这条）；</li>
     *   <li><b>2 个对角</b>（只共这条棱）→ 相触，画；</li>
     *   <li><b>3 个</b> → 凹角（内折痕），画；</li>
     *   <li><b>4 个</b> → 完全在内部，<b>不画</b>。</li>
     * </ul>
     * 注意"2 个相邻"和"2 个对角"必须区分：前者是平接缝（藏），后者是两块只靠这条棱
     * 接触（露）。只看"填充数量"会把这两种混为一谈，那是错的。
     *
     * @param p     该格的<b>渲染坐标</b>（输出的棱端点用它）
     * @param local 该格的<b>本地坐标</b>（邻居判定用它，因为填充集合是本地坐标）
     */
    public static List<Edge> outerEdges(BlockPos p, Vec3i local, Set<Vec3i> filled) {
        List<Edge> out = new ArrayList<>(12);
        // 沿 X 的 4 条棱：角点由 (y+dy, z+dz) 决定
        for (int dy = 0; dy <= 1; dy++) {
            for (int dz = 0; dz <= 1; dz++) {
                if (edgeVisible(local, filled, 0, dy, dz)) {
                    out.add(new Edge(p.getX(), p.getY() + dy, p.getZ() + dz,
                            p.getX() + 1f, p.getY() + dy, p.getZ() + dz));
                }
            }
        }
        // 沿 Y 的 4 条棱：角点由 (x+dx, z+dz) 决定
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                if (edgeVisible(local, filled, 1, dx, dz)) {
                    out.add(new Edge(p.getX() + dx, p.getY(), p.getZ() + dz,
                            p.getX() + dx, p.getY() + 1f, p.getZ() + dz));
                }
            }
        }
        // 沿 Z 的 4 条棱：角点由 (x+dx, y+dy) 决定
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                if (edgeVisible(local, filled, 2, dx, dy)) {
                    out.add(new Edge(p.getX() + dx, p.getY() + dy, p.getZ(),
                            p.getX() + dx, p.getY() + dy, p.getZ() + 1f));
                }
            }
        }
        return out;
    }

    /**
     * 某条棱是否<b>由本格提交</b>（可见 + 归属本格）。
     *
     * @param axis   0=沿 X、1=沿 Y、2=沿 Z
     * @param ca     沿"第一个垂直轴"的角点（0 或 1）
     * @param cb     沿"第二个垂直轴"的角点（0 或 1）
     */
    private static boolean edgeVisible(Vec3i local, Set<Vec3i> filled, int axis, int ca, int cb) {
        // 4 个象限候选格（绝对坐标）：A-1/A 与 B-1/B 的 2×2 组合
        Vec3i[] cand = new Vec3i[4];
        int i = 0;
        for (int da = -1; da <= 0; da++) {
            for (int db = -1; db <= 0; db++) {
                int a = ca + da;   // 取值 ca-1 或 ca
                int b = cb + db;   // 取值 cb-1 或 cb
                cand[i++] = switch (axis) {
                    case 0 -> local.offset(0, a, b);   // 沿 X：垂直轴是 Y、Z
                    case 1 -> local.offset(a, 0, b);   // 沿 Y：垂直轴是 X、Z
                    default -> local.offset(a, b, 0);  // 沿 Z：垂直轴是 X、Y
                };
            }
        }
        boolean[] f = new boolean[4];
        int n = 0;
        for (int k = 0; k < 4; k++) {
            f[k] = filled.contains(cand[k]);
            if (f[k]) n++;
        }
        if (n == 4) return false;                       // 完全在内部
        if (n == 2) {
            // 索引布局：0=(A-1,B-1) 1=(A-1,B) 2=(A,B-1) 3=(A,B)
            // 相邻（共边）= (0,1) (2,3) (0,2) (1,3)；对角 = (0,3) (1,2)
            boolean adjacent = (f[0] && f[1]) || (f[2] && f[3]) || (f[0] && f[2]) || (f[1] && f[3]);
            if (adjacent) return false;                 // 平接缝 → 无缝玻璃，藏掉
        }
        // 归属：一段棱最多被 4 个填充格同时拥有（各自都会提交同一段几何），
        // 只让坐标最小的那个提交 —— 否则同一段会被画 2~4 遍，
        // 半透明线框叠起来会比别处更亮（和"内面叠 alpha"是同一类问题）。
        Vec3i owner = null;
        for (int k = 0; k < 4; k++) {
            if (!f[k]) continue;
            if (owner == null || less(cand[k], owner)) owner = cand[k];
        }
        return owner != null && owner.equals(local);
    }

    /** 字典序比较（用于选出唯一归属格）。 */
    private static boolean less(Vec3i a, Vec3i b) {
        if (a.getX() != b.getX()) return a.getX() < b.getX();
        if (a.getY() != b.getY()) return a.getY() < b.getY();
        return a.getZ() < b.getZ();
    }

    /**
     * 某个面按<b>朝外正面</b>绕序排列的 4 个角（绝对坐标）。
     *
     * <p>构造方式：取一对切向轴 {@code (t,u)} 满足 {@code t × u = 法线}（右手系），
     * 再按 {@code O → O+t → O+t+u → O+u} 排列。这样从面外侧看是逆时针（正面），
     * 且 {@code cross(c1-c0, c2-c0) == 法线}——配合背面剔除只画外表面。</p>
     */
    public static float[][] outwardCorners(BlockPos p, Direction d) {
        float x0 = p.getX(), y0 = p.getY(), z0 = p.getZ();
        float x1 = x0 + 1f, y1 = y0 + 1f, z1 = z0 + 1f;
        return switch (d) {
            // t=+Z, u=+X → (0,0,1)×(1,0,0)=(0,1,0)=+Y
            case UP -> new float[][]{
                    {x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            // t=+X, u=+Z → (1,0,0)×(0,0,1)=(0,-1,0)=-Y
            case DOWN -> new float[][]{
                    {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
            // t=+Y, u=+X → (0,1,0)×(1,0,0)=(0,0,-1)=-Z
            case NORTH -> new float[][]{
                    {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
            // t=+X, u=+Y → (1,0,0)×(0,1,0)=(0,0,1)=+Z
            case SOUTH -> new float[][]{
                    {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
            // t=+Z, u=+Y → (0,0,1)×(0,1,0)=(-1,0,0)=-X
            case WEST -> new float[][]{
                    {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
            // t=+Y, u=+Z → (0,1,0)×(0,0,1)=(1,0,0)=+X
            case EAST -> new float[][]{
                    {x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
        };
    }
}
