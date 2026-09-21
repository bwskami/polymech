package com.mss.polymech.mps.physical.helper;

/**
 * 整数包围盒（格）—— 本项目自己的极简实现。
 *
 * <p><b>为什么不用 MPS 的 {@code org.joml.primitives.AABBi}</b>：Minecraft 1.21.1 随游戏只带
 * joml 核心包（{@code Vector3d}/{@code Matrix4f}/{@code Quaterniond}…），
 * <b>不含</b> {@code org.joml.primitives}。MPS 那边能编译是因为它自己带了完整 joml。</p>
 *
 * <p>语义与 {@code AABBi} 的用到的部分一致：{@code min > max} 表示空盒；
 * {@link #union} 扩张；{@link #setMin}/{@link #setMax} 可链式调用（返回 this）。</p>
 */
public final class IntAABB {

    public int minX;
    public int minY;
    public int minZ;
    public int maxX;
    public int maxY;
    public int maxZ;

    public IntAABB() {
        setEmpty();
    }

    public IntAABB(IntAABB other) {
        this.minX = other.minX;
        this.minY = other.minY;
        this.minZ = other.minZ;
        this.maxX = other.maxX;
        this.maxY = other.maxY;
        this.maxZ = other.maxZ;
    }

    /** 复位成空盒（min &gt; max）。 */
    public IntAABB setEmpty() {
        this.minX = this.minY = this.minZ = Integer.MAX_VALUE;
        this.maxX = this.maxY = this.maxZ = Integer.MIN_VALUE;
        return this;
    }

    public boolean isEmpty() {
        return minX > maxX || minY > maxY || minZ > maxZ;
    }

    public IntAABB setMin(int x, int y, int z) {
        this.minX = x;
        this.minY = y;
        this.minZ = z;
        return this;
    }

    public IntAABB setMax(int x, int y, int z) {
        this.maxX = x;
        this.maxY = y;
        this.maxZ = z;
        return this;
    }

    /** 把一个格子并进来。 */
    public IntAABB union(int x, int y, int z) {
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
        return "IntAABB[" + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
