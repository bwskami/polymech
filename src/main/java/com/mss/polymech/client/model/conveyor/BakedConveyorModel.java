package com.mss.polymech.client.model.conveyor;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class BakedConveyorModel extends BakedModelWrapper<BakedModel> {

    public static final ModelProperty<Boolean> LEFT_INPUT = new ModelProperty<>();
    public static final ModelProperty<Boolean> RIGHT_INPUT = new ModelProperty<>();
    public static final ModelProperty<ConveyorType> CONVEYOR_TYPE = new ModelProperty<>();

    private final BakedModel centerModel;
    private final BakedModel leftRailModel;
    private final BakedModel rightRailModel;
    private final BakedModel upModel;
    private final BakedModel downModel;

    public BakedConveyorModel(BakedModel centerModel, BakedModel leftRailModel, BakedModel rightRailModel) {
        this(centerModel, leftRailModel, rightRailModel, null, null);
    }

    public BakedConveyorModel(BakedModel centerModel, BakedModel leftRailModel, BakedModel rightRailModel,
                               BakedModel upModel, BakedModel downModel) {
        super(centerModel);
        this.centerModel = centerModel;
        this.leftRailModel = leftRailModel;
        this.rightRailModel = rightRailModel;
        this.upModel = upModel;
        this.downModel = downModel;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                              @NotNull RandomSource rand, @NotNull ModelData extraData,
                                              @Nullable RenderType renderType) {
        List<BakedQuad> quads = new ArrayList<>();

        ConveyorType type = extraData.has(CONVEYOR_TYPE) ? extraData.get(CONVEYOR_TYPE) : ConveyorType.HORIZONTAL;

        switch (type) {
            case UP -> {
                if (upModel != null) {
                    quads.addAll(upModel.getQuads(state, side, rand, extraData, renderType));
                }
            }
            case DOWN -> {
                if (downModel != null) {
                    quads.addAll(downModel.getQuads(state, side, rand, extraData, renderType));
                }
            }
            default -> {
                quads.addAll(centerModel.getQuads(state, side, rand, extraData, renderType));

                boolean leftFed = extraData.has(LEFT_INPUT) && Boolean.TRUE.equals(extraData.get(LEFT_INPUT));
                boolean rightFed = extraData.has(RIGHT_INPUT) && Boolean.TRUE.equals(extraData.get(RIGHT_INPUT));

                if (!leftFed) {
                    quads.addAll(leftRailModel.getQuads(state, side, rand, extraData, renderType));
                }
                if (!rightFed) {
                    quads.addAll(rightRailModel.getQuads(state, side, rand, extraData, renderType));
                }
            }
        }

        return quads;
    }

    @Override
    public @NotNull ModelData getModelData(@NotNull BlockAndTintGetter world, @NotNull BlockPos pos,
                                            @NotNull BlockState state, @NotNull ModelData tileData) {
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        if (type != ConveyorType.HORIZONTAL) {
            return tileData.derive()
                    .with(CONVEYOR_TYPE, type)
                    .build();
        }

        Direction facing = state.getValue(ConveyorBlock.FACING);

        Direction leftDir = facing.getCounterClockWise();
        Direction rightDir = facing.getClockWise();

        boolean leftInput = isConveyorFeedingFrom(world, pos, leftDir, facing);
        boolean rightInput = isConveyorFeedingFrom(world, pos, rightDir, facing);

        return tileData.derive()
                .with(LEFT_INPUT, leftInput)
                .with(RIGHT_INPUT, rightInput)
                .with(CONVEYOR_TYPE, type)
                .build();
    }

    private boolean isConveyorFeedingFrom(BlockAndTintGetter world, BlockPos pos,
                                          Direction side, Direction myFacing) {
        BlockPos neighborPos = pos.relative(side);
        BlockState neighborState = world.getBlockState(neighborPos);
        if (!(neighborState.getBlock() instanceof ConveyorBlock)) {
            return false;
        }
        Direction neighborFacing = neighborState.getValue(ConveyorBlock.FACING);
        return neighborFacing == side.getOpposite();
    }
}