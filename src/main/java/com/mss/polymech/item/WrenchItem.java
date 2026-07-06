package com.mss.polymech.item;

import com.mss.polymech.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;

public class WrenchItem extends Item {
    public WrenchItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof PipeBlock)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        Direction face = context.getClickedFace();
        Vec3 clickLoc = context.getClickLocation();

        float lx = (float) (clickLoc.x - pos.getX());
        float ly = (float) (clickLoc.y - pos.getY());
        float lz = (float) (clickLoc.z - pos.getZ());

        Direction target = determineGridSide(face, lx, ly, lz);
        if (target != null) {
            BooleanProperty prop = getPropertyForDirection(target);
            boolean newValue = !state.getValue(prop);
            level.setBlock(pos, state.setValue(prop, newValue), net.minecraft.world.level.block.Block.UPDATE_NONE);

            BlockPos neighborPos = pos.relative(target);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof PipeBlock) {
                BooleanProperty neighborProp = getPropertyForDirection(target.getOpposite());
                level.setBlock(neighborPos, neighborState.setValue(neighborProp, newValue),
                        net.minecraft.world.level.block.Block.UPDATE_NONE);
            }

            player.swing(context.getHand());
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private static Direction determineGridSide(Direction face, float x, float y, float z) {
        Direction opposite = face.getOpposite();
        switch (face) {
            case DOWN, UP -> {
                if (x < 0.25f) {
                    if (z < 0.25f) return opposite;
                    if (z > 0.75f) return opposite;
                    return Direction.WEST;
                }
                if (x > 0.75f) {
                    if (z < 0.25f) return opposite;
                    if (z > 0.75f) return opposite;
                    return Direction.EAST;
                }
                if (z < 0.25f) return Direction.NORTH;
                if (z > 0.75f) return Direction.SOUTH;
                return face;
            }
            case NORTH, SOUTH -> {
                if (x < 0.25f) {
                    if (y < 0.25f) return opposite;
                    if (y > 0.75f) return opposite;
                    return Direction.WEST;
                }
                if (x > 0.75f) {
                    if (y < 0.25f) return opposite;
                    if (y > 0.75f) return opposite;
                    return Direction.EAST;
                }
                if (y < 0.25f) return Direction.DOWN;
                if (y > 0.75f) return Direction.UP;
                return face;
            }
            case WEST, EAST -> {
                if (z < 0.25f) {
                    if (y < 0.25f) return opposite;
                    if (y > 0.75f) return opposite;
                    return Direction.NORTH;
                }
                if (z > 0.75f) {
                    if (y < 0.25f) return opposite;
                    if (y > 0.75f) return opposite;
                    return Direction.SOUTH;
                }
                if (y < 0.25f) return Direction.DOWN;
                if (y > 0.75f) return Direction.UP;
                return face;
            }
        }
        return null;
    }

    public static BooleanProperty getPropertyForDirection(Direction direction) {
        return switch (direction) {
            case NORTH -> PipeBlock.NORTH;
            case SOUTH -> PipeBlock.SOUTH;
            case EAST  -> PipeBlock.EAST;
            case WEST  -> PipeBlock.WEST;
            case UP    -> PipeBlock.UP;
            case DOWN  -> PipeBlock.DOWN;
        };
    }

    public static Direction[] getFaceAxes(Direction face, Direction playerFacing) {
        Direction right, up;
        switch (face) {
            case NORTH -> { right = Direction.EAST;  up = Direction.UP; }
            case SOUTH -> { right = Direction.WEST;  up = Direction.UP; }
            case EAST  -> { right = Direction.NORTH; up = Direction.UP; }
            case WEST  -> { right = Direction.SOUTH; up = Direction.UP; }
            case UP    -> { right = Direction.EAST;  up = Direction.SOUTH; }
            case DOWN  -> { right = Direction.WEST;  up = Direction.NORTH; }
            default -> throw new IllegalArgumentException();
        }
        return new Direction[]{right, up};
    }
}
