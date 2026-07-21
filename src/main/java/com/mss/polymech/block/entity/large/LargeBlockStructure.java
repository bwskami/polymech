package com.mss.polymech.block.entity.large;

import net.minecraft.core.BlockPos;
import java.util.*;

public class LargeBlockStructure {

    public enum BlockType {
        EMPTY,      // 空格
        PLACEHOLDER, // 普通占位 (A)
        CORE,       // 核心方块 (B)
        OUTPUT,     // 输出仓 (C)
        INPUT,      // 输入仓 (D)
        ITEM_BUS,   // 物品总线 (E)
        FLUID_BUS,  // 流体总线 (F)
        ENERGY;     // 能量接口 (G)
    }

    private final char[][][] layout;
    private final char coreMarker;
    private final int width;
    private final int height;
    private final int depth;

    public LargeBlockStructure(char[][][] layout, char coreMarker) {
        this.layout = layout;
        this.coreMarker = coreMarker;
        this.height = layout.length;
        this.depth = layout[0].length;
        this.width = layout[0][0].length;
    }

    public BlockType getBlockType(int x, int y, int z) {
        if (y < 0 || y >= height) return BlockType.EMPTY;
        if (z < 0 || z >= depth) return BlockType.EMPTY;
        if (x < 0 || x >= width) return BlockType.EMPTY;
        
        char c = layout[y][z][x];
        if (c == coreMarker) return BlockType.CORE;
        
        switch (c) {
            case ' ': return BlockType.EMPTY;
            case 'A': return BlockType.PLACEHOLDER;
            case 'C': return BlockType.OUTPUT;
            case 'D': return BlockType.INPUT;
            case 'E': return BlockType.ITEM_BUS;
            case 'F': return BlockType.FLUID_BUS;
            case 'G': return BlockType.ENERGY;
            default: return BlockType.PLACEHOLDER;
        }
    }

    public BlockPos getCoreOffset() {
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    if (layout[y][z][x] == coreMarker) {
                        return new BlockPos(x, y, z);
                    }
                }
            }
        }
        return BlockPos.ZERO;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }

    public Collection<BlockPos> getPositions(BlockType type) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    if (getBlockType(x, y, z) == type) {
                        positions.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return positions;
    }
}