package com.mss.polymech.machine.common;

import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * 大型机器的全部配置，一处定义、处处自动注册。
 * <p>
 * 新建机器只需：1 个 BlockEntity 类 + 1 条 MachineConfig 定义，
 * 主方块、侧面方块、侧面方块实体均由通用类自动生成。
 * </p>
 * <p>
 * 侧面方块全部是通用代理，不分类。
 * 哪个位置对应什么功能（流体输入/输出、物品输入/输出等），
 * 由 BlockEntity 根据位置映射到内部处理器。
 * </p>
 * <p>
 * 如需自定义侧面方块布局，创建 LargeMachineBlock 子类并重写
 * {@code getSideOffsets()} / {@code getFillRegions()}，
 * 然后通过 {@code .blockFactory(MyBlock::new)} 注入。
 * </p>
 */
public class MachineConfig {

    private final String id;
    private final Vec3i[] sideOffsets;
    @Nullable
    private final Vec3i[][] fillRegions;
    private final BlockBehaviour.Properties blockProperties;
    private final BlockEntityType.BlockEntitySupplier<? extends BlockEntity> blockEntityFactory;
    private final Function<MachineConfig, ? extends LargeMachineBlock> blockFactory;
    private final int slotCount;
    private final int defaultMaxProgress;
    private final int powerPerTick;
    @Nullable
    private DeferredBlock<?> sideBlock;
    @Nullable
    private DeferredBlock<? extends BaseMachineBlock> mainBlock;

    private MachineConfig(Builder builder) {
        this.id = builder.id;
        this.sideOffsets = builder.sideOffsets;
        this.fillRegions = builder.fillRegions;
        this.blockProperties = builder.blockProperties;
        this.blockEntityFactory = builder.blockEntityFactory;
        this.blockFactory = builder.blockFactory;
        this.slotCount = builder.slotCount;
        this.defaultMaxProgress = builder.defaultMaxProgress;
        this.powerPerTick = builder.powerPerTick;
    }

    // -- getters --

    public String id() { return id; }
    public Vec3i[] sideOffsets() { return sideOffsets; }
    @Nullable public Vec3i[][] fillRegions() { return fillRegions; }
    public BlockBehaviour.Properties blockProperties() { return blockProperties; }
    public BlockEntityType.BlockEntitySupplier<? extends BlockEntity> blockEntityFactory() { return blockEntityFactory; }
    public int slotCount() { return slotCount; }
    public int defaultMaxProgress() { return defaultMaxProgress; }
    public int powerPerTick() { return powerPerTick; }

    /** 方块工厂，用于创建 Block 实例（默认 {@code LargeMachineBlock::new}），子类重写偏移时可注入自定义构造函数。 */
    public Function<MachineConfig, ? extends LargeMachineBlock> blockFactory() { return blockFactory; }

    public DeferredBlock<?> sideBlock() { return sideBlock; }
    void setSideBlock(DeferredBlock<?> sideBlock) { this.sideBlock = sideBlock; }

    public DeferredBlock<? extends BaseMachineBlock> mainBlock() { return mainBlock; }
    void setMainBlock(DeferredBlock<? extends BaseMachineBlock> mainBlock) { this.mainBlock = mainBlock; }

    /** 根据主方块位置和朝向，计算所有侧面方块的世界坐标 */
    public BlockPos[] getSidePositions(BlockPos center, net.minecraft.core.Direction facing) {
        BlockPos[] positions = new BlockPos[sideOffsets.length];
        for (int i = 0; i < sideOffsets.length; i++) {
            Vec3i rotated = BaseMachineBlock.rotateVec3i(sideOffsets[i], facing);
            positions[i] = center.offset(rotated);
        }
        return positions;
    }

    // -- Builder --

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final String id;
        private Vec3i[] sideOffsets = new Vec3i[0];
        @Nullable private Vec3i[][] fillRegions;
        private BlockBehaviour.Properties blockProperties;
        private BlockEntityType.BlockEntitySupplier<? extends BlockEntity> blockEntityFactory;
        private Function<MachineConfig, ? extends LargeMachineBlock> blockFactory = LargeMachineBlock::new;
        private int slotCount = 3;
        private int defaultMaxProgress = 200;
        private int powerPerTick = 10;

        private Builder(String id) {
            this.id = id;
        }

        /** 设置侧面方块偏移（通用，不分类）。 */
        public Builder sideOffsets(Vec3i[] offsets) {
            this.sideOffsets = offsets;
            return this;
        }

        public Builder fillRegions(@Nullable Vec3i[][] regions) { this.fillRegions = regions; return this; }
        public Builder blockProperties(BlockBehaviour.Properties props) { this.blockProperties = props; return this; }
        public Builder blockEntityFactory(BlockEntityType.BlockEntitySupplier<? extends BlockEntity> factory) {
            this.blockEntityFactory = factory; return this;
        }
        /**
         * 自定义 Block 子类构造函数，用于重写 {@code getSideOffsets()} / {@code getFillRegions()}。
         * 默认为 {@code LargeMachineBlock::new}，不调用则使用通用方块类。
         */
        public Builder blockFactory(Function<MachineConfig, ? extends LargeMachineBlock> factory) {
            this.blockFactory = factory; return this;
        }
        public Builder slotCount(int count) { this.slotCount = count; return this; }
        public Builder defaultMaxProgress(int maxProgress) { this.defaultMaxProgress = maxProgress; return this; }
        public Builder powerPerTick(int power) { this.powerPerTick = power; return this; }

        public MachineConfig build() {
            if (blockProperties == null || blockEntityFactory == null) {
                throw new IllegalStateException("blockProperties, blockEntityFactory are required for machine: " + id);
            }
            return new MachineConfig(this);
        }
    }

    // -- 通用侧面偏移预设 --

    /** 十字形偏移（前后左右各一格） */
    public static Vec3i[] crossOffsets() {
        return new Vec3i[]{
                new Vec3i(1, 0, 0), new Vec3i(-1, 0, 0),
                new Vec3i(0, 0, 1), new Vec3i(0, 0, -1),
        };
    }
}
