package com.mss.polymech.machine.common;

import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

/**
 * 大型机器的全部配置，一处定义、处处自动注册。
 * <p>
 * 新建机器只需：1 个 BlockEntity 类 + 1 条 MachineConfig 定义，
 * 主方块、侧面方块、侧面方块实体均由通用类自动生成。
 * </p>
 */
public class MachineConfig {

    private final String id;
    private final Vec3i[] sideOffsets;
    @Nullable
    private final Vec3i[][] fillRegions;
    private final BlockBehaviour.Properties blockProperties;
    private final BlockEntityType.BlockEntitySupplier<? extends BlockEntity> blockEntityFactory;
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

    public DeferredBlock<?> sideBlock() { return sideBlock; }
    void setSideBlock(DeferredBlock<?> sideBlock) { this.sideBlock = sideBlock; }

    public DeferredBlock<? extends BaseMachineBlock> mainBlock() { return mainBlock; }
    void setMainBlock(DeferredBlock<? extends BaseMachineBlock> mainBlock) { this.mainBlock = mainBlock; }

    // -- Builder --

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final String id;
        private Vec3i[] sideOffsets;
        @Nullable private Vec3i[][] fillRegions;
        private BlockBehaviour.Properties blockProperties;
        private BlockEntityType.BlockEntitySupplier<? extends BlockEntity> blockEntityFactory;
        private int slotCount = 3;
        private int defaultMaxProgress = 200;
        private int powerPerTick = 10;

        private Builder(String id) {
            this.id = id;
        }

        public Builder sideOffsets(Vec3i[] offsets) { this.sideOffsets = offsets; return this; }
        public Builder fillRegions(@Nullable Vec3i[][] regions) { this.fillRegions = regions; return this; }
        public Builder blockProperties(BlockBehaviour.Properties props) { this.blockProperties = props; return this; }
        public Builder blockEntityFactory(BlockEntityType.BlockEntitySupplier<? extends BlockEntity> factory) {
            this.blockEntityFactory = factory; return this;
        }
        public Builder slotCount(int count) { this.slotCount = count; return this; }
        public Builder defaultMaxProgress(int maxProgress) { this.defaultMaxProgress = maxProgress; return this; }
        public Builder powerPerTick(int power) { this.powerPerTick = power; return this; }

        public MachineConfig build() {
            if (sideOffsets == null || blockProperties == null || blockEntityFactory == null) {
                throw new IllegalStateException("sideOffsets, blockProperties, blockEntityFactory are required for machine: " + id);
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
