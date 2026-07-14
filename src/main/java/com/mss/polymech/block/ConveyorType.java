package com.mss.polymech.block;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum ConveyorType implements StringRepresentable {
    HORIZONTAL("horizontal"),
    UP("up"),
    DOWN("down");

    private final String name;

    ConveyorType(String name) {
        this.name = name;
    }

    @Override
    public @NotNull String getSerializedName() {
        return name;
    }

    public ConveyorType next() {
        return switch (this) {
            case HORIZONTAL -> UP;
            case UP -> DOWN;
            case DOWN -> HORIZONTAL;
        };
    }
}