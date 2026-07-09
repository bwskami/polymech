package com.mss.polymech.client.model.pipe;

import com.mss.polymech.block.PipeBlock;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BakedPipeModel extends BakedModelWrapper<BakedModel> {
    private final BakedModel centerModel;
    private final Map<Direction, BakedModel> armModels;

    public BakedPipeModel(BakedModel centerModel, Map<Direction, BakedModel> armModels) {
        super(centerModel);
        this.centerModel = centerModel;
        this.armModels = armModels;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> quads = new ArrayList<>();

        // 始终渲染中心
        quads.addAll(centerModel.getQuads(state, side, rand, data, renderType));

        // 根据 blockstate 渲染连接臂
        if (state != null && state.getBlock() instanceof PipeBlock pipeBlock) {
            if (state.getValue(PipeBlock.NORTH)) {
                BakedModel arm = armModels.get(Direction.NORTH);
                if (arm != null) quads.addAll(arm.getQuads(state, side, rand, data, renderType));
            }
            if (state.getValue(PipeBlock.SOUTH)) {
                BakedModel arm = armModels.get(Direction.SOUTH);
                if (arm != null) quads.addAll(arm.getQuads(state, side, rand, data, renderType));
            }
            if (state.getValue(PipeBlock.EAST)) {
                BakedModel arm = armModels.get(Direction.EAST);
                if (arm != null) quads.addAll(arm.getQuads(state, side, rand, data, renderType));
            }
            if (state.getValue(PipeBlock.WEST)) {
                BakedModel arm = armModels.get(Direction.WEST);
                if (arm != null) quads.addAll(arm.getQuads(state, side, rand, data, renderType));
            }
            if (state.getValue(PipeBlock.UP)) {
                BakedModel arm = armModels.get(Direction.UP);
                if (arm != null) quads.addAll(arm.getQuads(state, side, rand, data, renderType));
            }
            if (state.getValue(PipeBlock.DOWN)) {
                BakedModel arm = armModels.get(Direction.DOWN);
                if (arm != null) quads.addAll(arm.getQuads(state, side, rand, data, renderType));
            }
        }

        return quads;
    }
}
