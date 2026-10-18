package com.mss.polymech.client.gui.machine.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 方块某一面上的 <b>3×3 九宫格</b>几何 —— 扳手九宫格与结构编辑器共用同一套坐标。
 *
 * <h3>为什么抽成纯类</h3>
 * 这套"面内 (u,v) → 世界坐标"的换算原来是 {@code WrenchOverlayRenderer} 里的私有方法，
 * 编辑器要用同一套手感（"用类似我们之前扳手的逻辑"）就必须共用，否则两边各写一份、
 * 迟早画出两个不一样位置的九宫格。
 * <p>抽出来之后它是<strong>纯坐标数学</strong>（只依赖 {@link BlockPos}/{@link Direction}），
 * 所以能离线断言"九宫格真的贴在那个面上、格子边长真的是三分之一"——
 * 这类"看起来差不多"的几何错误肉眼极难发现，必须靠断言钉死。</p>
 *
 * <h3>轴向约定（照抄扳手，勿改）</h3>
 * <ul>
 *   <li>N: right=EAST, up=UP；S: right=WEST, up=UP</li>
 *   <li>E: right=NORTH, up=UP；W: right=SOUTH, up=UP</li>
 *   <li>UP: right=EAST, up=SOUTH；DOWN: right=WEST, up=NORTH</li>
 * </ul>
 * 保证从玩家视角看过去九宫格不镜像、不上下颠倒。
 */
public final class FaceGrid {

    private FaceGrid() {
    }

    /** 九宫格的分格数（3×3）。 */
    public static final int DIVISIONS = 3;

    /**
     * 面内两个轴向：{@code [right, up]}（世界方向）。
     * 与 {@code WrenchItem.getFaceAxes} 完全一致 —— 改这里等于改扳手，两边一起变。
     */
    public static Direction[] axes(Direction face) {
        Direction right;
        Direction up;
        switch (face) {
            case NORTH -> { right = Direction.EAST;  up = Direction.UP; }
            case SOUTH -> { right = Direction.WEST;  up = Direction.UP; }
            case EAST  -> { right = Direction.NORTH; up = Direction.UP; }
            case WEST  -> { right = Direction.SOUTH; up = Direction.UP; }
            case UP    -> { right = Direction.EAST;  up = Direction.SOUTH; }
            case DOWN  -> { right = Direction.WEST;  up = Direction.NORTH; }
            default -> throw new IllegalArgumentException("未知方向: " + face);
        }
        return new Direction[]{right, up};
    }

    /**
     * 面内坐标 {@code (u,v) ∈ [0,1]²} → <b>绝对世界坐标</b>（已含 {@code pos}）。
     *
     * <p>{@code offset} 是沿面法线向外的微小偏移，用来避免和方块表面 Z-fighting。</p>
     *
     * <p>面的"原点"取该面所在平面上的角点：法线正方向的面取格子的 +1 侧，
     * 负方向取 0 侧 —— 这样 u/v 增大方向与 {@link #axes} 一致。</p>
     *
     * <p><b>返回绝对坐标而不是方块内局部坐标</b>：编辑器画线用的 PoseStack 是单位矩阵
     * （相机变换已在 modelview 里），方块坐标直接用绝对值；蓝图/管道预览
     * （{@code AnimatedOutline}）也是传绝对 AABB。扳手那份是"先 translate 再画局部"，
     * 属于另一种写法，所以<strong>没有</strong>动它——只把几何约定对齐到这里，
     * 免得改坏一个正在正常工作的功能。</p>
     */
    public static float[] point(BlockPos pos, Direction face, float u, float v, float offset) {
        Direction[] ax = axes(face);
        Direction right = ax[0];
        Direction up = ax[1];
        var normal = face.getNormal();

        // 面所在平面的角点（法线正向取 +1 侧，负向取 0 侧）
        float cx = (normal.getX() != 0) ? (normal.getX() > 0 ? 1 : 0)
                : (right.getStepX() < 0 || up.getStepX() < 0 ? 1 : 0);
        float cy = (normal.getY() != 0) ? (normal.getY() > 0 ? 1 : 0)
                : (right.getStepY() < 0 || up.getStepY() < 0 ? 1 : 0);
        float cz = (normal.getZ() != 0) ? (normal.getZ() > 0 ? 1 : 0)
                : (right.getStepZ() < 0 || up.getStepZ() < 0 ? 1 : 0);

        float x = pos.getX() + cx + normal.getX() * offset + u * right.getStepX() + v * up.getStepX();
        float y = pos.getY() + cy + normal.getY() * offset + u * right.getStepY() + v * up.getStepY();
        float z = pos.getZ() + cz + normal.getZ() * offset + u * right.getStepZ() + v * up.getStepZ();
        return new float[]{x, y, z};
    }

    /**
     * 面内坐标 {@code (u,v)} → 九宫格的第几格（0..8，行优先：v 是行、u 是列）。
     * <p>越界会被夹到边界格内，所以射线打到的点总能归到某一格。</p>
     */
    public static int cellIndex(float u, float v) {
        int col = clampCell(u);
        int row = clampCell(v);
        return row * DIVISIONS + col;
    }

    private static int clampCell(float t) {
        int i = (int) Math.floor(t * DIVISIONS);
        if (i < 0) return 0;
        return Math.min(i, DIVISIONS - 1);
    }

    /**
     * 九宫格的分隔线位置（u 或 v 的取值）：{@code 1/3, 2/3}。
     * 外框另画（{@code 0..1}）。
     */
    public static float[] dividerPositions() {
        float[] out = new float[DIVISIONS - 1];
        for (int i = 1; i < DIVISIONS; i++) out[i - 1] = i / (float) DIVISIONS;
        return out;
    }

    /**
     * 九宫格中某一格的四个角在面内的 (u,v)（用于画格子高亮）。
     * {@code index} 0..8，行优先。
     */
    public static float[][] cellCorners(int index) {
        int row = index / DIVISIONS;
        int col = index % DIVISIONS;
        float u0 = col / (float) DIVISIONS;
        float v0 = row / (float) DIVISIONS;
        float u1 = (col + 1) / (float) DIVISIONS;
        float v1 = (row + 1) / (float) DIVISIONS;
        return new float[][]{{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
    }
}
