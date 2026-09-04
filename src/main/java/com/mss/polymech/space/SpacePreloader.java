package com.mss.polymech.space;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 目标维度区块预加载。
 */
public final class SpacePreloader {

    private static final int RADIUS_CHUNKS = 3;

    private SpacePreloader() {
    }

    public static void preload(ServerLevel level, BlockPos center) {
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
            for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
                level.getChunk(cx + dx, cz + dz);
            }
        }
    }
}
