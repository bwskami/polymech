package com.mss.polymech.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class PipePathCalculator {

    public static List<BlockPos> calculatePath(BlockPos start, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();

        int dx = end.getX() - start.getX();
        int dy = end.getY() - start.getY();
        int dz = end.getZ() - start.getZ();

        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);
        int absDz = Math.abs(dz);

        int nonZeroAxes = 0;
        if (absDx > 0) nonZeroAxes++;
        if (absDy > 0) nonZeroAxes++;
        if (absDz > 0) nonZeroAxes++;

        if (nonZeroAxes > 2) {
            return path;
        }

        path.add(start);

        if (nonZeroAxes == 1) {
            Direction dir = getDirection(dx, dy, dz);
            if (dir != null) {
                for (int i = 1; i <= getDistance(absDx, absDy, absDz); i++) {
                    path.add(start.relative(dir, i));
                }
            }
        } else if (nonZeroAxes == 2) {
            Direction firstDir = getPrimaryDirection(absDx, absDy, absDz, dx, dy, dz);
            Direction secondDir = getSecondaryDirection(absDx, absDy, absDz, dx, dy, dz);

            if (firstDir != null && secondDir != null) {
                int firstDist = getFirstDistance(absDx, absDy, absDz);
                int secondDist = getSecondDistance(absDx, absDy, absDz);

                BlockPos current = start;
                for (int i = 1; i <= firstDist; i++) {
                    current = current.relative(firstDir);
                    path.add(current);
                }

                for (int i = 1; i <= secondDist; i++) {
                    current = current.relative(secondDir);
                    path.add(current);
                }
            }
        }

        return path;
    }

    private static Direction getDirection(int dx, int dy, int dz) {
        if (dx != 0) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (dy != 0) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (dz != 0) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        return null;
    }

    private static int getDistance(int absDx, int absDy, int absDz) {
        return Math.max(absDx, Math.max(absDy, absDz));
    }

    private static Direction getPrimaryDirection(int absDx, int absDy, int absDz, int dx, int dy, int dz) {
        if (absDx >= absDy && absDx >= absDz) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (absDy >= absDx && absDy >= absDz) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private static Direction getSecondaryDirection(int absDx, int absDy, int absDz, int dx, int dy, int dz) {
        if (absDx >= absDy && absDx >= absDz) {
            if (absDy >= absDz) {
                return dy > 0 ? Direction.UP : Direction.DOWN;
            } else {
                return dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
        } else if (absDy >= absDx && absDy >= absDz) {
            if (absDx >= absDz) {
                return dx > 0 ? Direction.EAST : Direction.WEST;
            } else {
                return dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
        } else {
            if (absDx >= absDy) {
                return dx > 0 ? Direction.EAST : Direction.WEST;
            } else {
                return dy > 0 ? Direction.UP : Direction.DOWN;
            }
        }
    }

    private static int getFirstDistance(int absDx, int absDy, int absDz) {
        return Math.max(absDx, Math.max(absDy, absDz));
    }

    private static int getSecondDistance(int absDx, int absDy, int absDz) {
        int max = Math.max(absDx, Math.max(absDy, absDz));
        if (max == absDx) return Math.max(absDy, absDz);
        if (max == absDy) return Math.max(absDx, absDz);
        return Math.max(absDx, absDy);
    }
}
