package com.mss.polymech.client.gui.machine.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 一个方块某个"面"的几何 —— 朝向面片画在哪、箭头从哪指向哪。
 *
 * <h3>为什么单独拆出来</h3>
 * 这是纯数学（只碰 {@link BlockPos} 与 {@link Direction}），拆出来后
 * "面片是不是真的贴在该贴的那个面上""四个角是不是正好构成一个正方形"
 * 都能<strong>离线断言</strong>，不用进游戏靠肉眼判断。
 *
 * <p>坐标是 {@code Scene} 的渲染坐标（已加 {@code originShift}），单位 = 格。</p>
 */
public final class FaceQuad {

    private FaceQuad() {
    }

    /**
     * 面片的 4 个角（渲染坐标）。
     *
     * @param inset 从方块边缘向内缩进的距离（0=正好贴满整个面，0.15=留出一圈边）
     */
    public static float[][] corners(BlockPos p, Direction d, float inset) {
        float x0 = p.getX() + inset, x1 = p.getX() + 1f - inset;
        float y0 = p.getY() + inset, y1 = p.getY() + 1f - inset;
        float z0 = p.getZ() + inset, z1 = p.getZ() + 1f - inset;
        return switch (d) {
            case UP -> new float[][]{
                    {x0, p.getY() + 1f - inset, z0}, {x1, p.getY() + 1f - inset, z0},
                    {x1, p.getY() + 1f - inset, z1}, {x0, p.getY() + 1f - inset, z1}};
            case DOWN -> new float[][]{
                    {x0, p.getY() + inset, z0}, {x1, p.getY() + inset, z0},
                    {x1, p.getY() + inset, z1}, {x0, p.getY() + inset, z1}};
            case NORTH -> new float[][]{
                    {x0, y0, p.getZ() + inset}, {x1, y0, p.getZ() + inset},
                    {x1, y1, p.getZ() + inset}, {x0, y1, p.getZ() + inset}};
            case SOUTH -> new float[][]{
                    {x0, y0, p.getZ() + 1f - inset}, {x1, y0, p.getZ() + 1f - inset},
                    {x1, y1, p.getZ() + 1f - inset}, {x0, y1, p.getZ() + 1f - inset}};
            case WEST -> new float[][]{
                    {p.getX() + inset, y0, z0}, {p.getX() + inset, y0, z1},
                    {p.getX() + inset, y1, z1}, {p.getX() + inset, y1, z0}};
            case EAST -> new float[][]{
                    {p.getX() + 1f - inset, y0, z0}, {p.getX() + 1f - inset, y0, z1},
                    {p.getX() + 1f - inset, y1, z1}, {p.getX() + 1f - inset, y1, z0}};
        };
    }

    /** 面的中心（正好在方块表面上，不缩进）。 */
    public static float[] center(BlockPos p, Direction d) {
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

    /** 从面中心沿该面法线往外 {@code len} 的点（画"朝向"箭头用）。 */
    public static float[] normalTip(BlockPos p, Direction d, float len) {
        float[] c = center(p, d);
        return new float[]{
                c[0] + d.getStepX() * len,
                c[1] + d.getStepY() * len,
                c[2] + d.getStepZ() * len};
    }
}
