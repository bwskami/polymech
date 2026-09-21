package com.mss.polymech.mps.physical.helper;

/**
 * 浮点包围盒（世界坐标，格）—— 本项目自己的极简实现。
 *
 * <p><b>为什么不用 MPS 的 {@code org.joml.primitives.AABBd}</b>：与 {@link IntAABB} 同因 ——
 * MC 1.21.1 只随游戏带 joml 核心，不含 {@code primitives} 包。</p>
 *
 * <p>语义对齐用到的部分：{@code min > max} 表示空盒；{@link #union} 扩张；
 * {@link #setMin}/{@link #setMax} 链式返回 this。</p>
 */
public final class DoubleAABB {

    public double minX;
    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;

    public DoubleAABB() {
        setEmpty();
    }

    public DoubleAABB(DoubleAABB other) {
        this.minX = other.minX;
        this.minY = other.minY;
        this.minZ = other.minZ;
        this.maxX = other.maxX;
        this.maxY = other.maxY;
        this.maxZ = other.maxZ;
    }

    /** 复位成空盒（min = +inf, max = -inf）。 */
    public DoubleAABB setEmpty() {
        this.minX = this.minY = this.minZ = Double.POSITIVE_INFINITY;
        this.maxX = this.maxY = this.maxZ = Double.NEGATIVE_INFINITY;
        return this;
    }

    public boolean isEmpty() {
        return minX > maxX || minY > maxY || minZ > maxZ;
    }

    public DoubleAABB setMin(double x, double y, double z) {
        this.minX = x;
        this.minY = y;
        this.minZ = z;
        return this;
    }

    public DoubleAABB setMax(double x, double y, double z) {
        this.maxX = x;
        this.maxY = y;
        this.maxZ = z;
        return this;
    }

    public DoubleAABB union(double x, double y, double z) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
        return this;
    }

    @Override
    public String toString() {
        return "DoubleAABB[" + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
