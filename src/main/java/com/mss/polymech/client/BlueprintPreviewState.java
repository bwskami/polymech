package com.mss.polymech.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public class BlueprintPreviewState {

    public enum Mode {
        FACING,
        XYZ,
        CONFIRM
    }

    private static boolean active = false;
    private static BlockPos basePos = null;
    private static Direction facing = Direction.NORTH;
    private static Mode mode = Mode.FACING;
    private static int currentAxis = 0;
    private static int offsetX = 0, offsetY = 0, offsetZ = 0;
    private static String machineId = null;

    public static void enter(BlockPos pos, Direction dir, String machineId) {
        active = true;
        basePos = pos;
        facing = dir;
        mode = Mode.FACING;
        currentAxis = 0;
        offsetX = 0;
        offsetY = 0;
        offsetZ = 0;
        BlueprintPreviewState.machineId = machineId;
    }

    public static void exit() {
        active = false;
        basePos = null;
        machineId = null;
    }

    public static boolean isActive() { return active; }
    public static BlockPos getBasePos() { return basePos; }

    public static BlockPos getTargetPos() {
        return basePos != null ? basePos.offset(offsetX, offsetY, offsetZ) : BlockPos.ZERO;
    }

    public static Direction getFacing() { return facing; }
    public static void setFacing(Direction dir) { facing = dir; }

    public static Mode getMode() { return mode; }
    public static void setMode(Mode m) { mode = m; }

    public static void cycleMode() {
        mode = switch (mode) {
            case FACING -> Mode.XYZ;
            case XYZ -> Mode.CONFIRM;
            case CONFIRM -> Mode.FACING;
        };
    }

    public static int getCurrentAxis() { return currentAxis; }

    public static void cycleAxis() {
        currentAxis = (currentAxis + 1) % 3;
    }

    public static void adjustOffset(int scroll) {
        switch (currentAxis) {
            case 0 -> offsetX += scroll;
            case 1 -> offsetY += scroll;
            case 2 -> offsetZ += scroll;
        }
    }

    public static int getOffsetX() { return offsetX; }
    public static int getOffsetY() { return offsetY; }
    public static int getOffsetZ() { return offsetZ; }
    public static String getMachineId() { return machineId; }
}
