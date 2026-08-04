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
    /** 抽取口模型（EXTRACT 状态叠加在管臂外侧），可能为空 */
    private final Map<Direction, BakedModel> inputModels;

    public BakedPipeModel(BakedModel centerModel, Map<Direction, BakedModel> armModels,
                          Map<Direction, BakedModel> inputModels) {
        super(centerModel);
        this.centerModel = centerModel;
        this.armModels = armModels;
        this.inputModels = inputModels;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                    RandomSource rand, ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> quads = new ArrayList<>();

        // 始终渲染中心
        quads.addAll(centerModel.getQuads(state, side, rand, data, renderType));

        // 根据 blockstate 连接状态渲染：非 NONE 渲染管臂，EXTRACT 额外叠加抽取口
        if (state != null && state.getBlock() instanceof PipeBlock) {
            for (Direction dir : Direction.values()) {
                PipeBlock.PipeConnection conn = state.getValue(PipeBlock.getProperty(dir));
                if (conn == PipeBlock.PipeConnection.NONE) continue;
                BakedModel arm = armModels.get(dir);
                if (arm != null) quads.addAll(arm.getQuads(state, side, rand, data, renderType));
                if (conn == PipeBlock.PipeConnection.EXTRACT) {
                    BakedModel input = inputModels.get(dir);
                    if (input != null) quads.addAll(input.getQuads(state, side, rand, data, renderType));
                }
            }
        }

        return quads;
    }
}
