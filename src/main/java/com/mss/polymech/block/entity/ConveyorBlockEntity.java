package com.mss.polymech.block.entity;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorSpec;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 传送带方块实体。
 * <p>
 * 桥梁角色：服务端执行运输逻辑，客户端向渲染器提供视觉进度数据。
 * 自身不做复杂运算。
 * </p>
 */
public class ConveyorBlockEntity extends BlockEntity {
    private final ConveyorItemHandler itemHandler;

    public ConveyorBlockEntity(BlockPos pos, BlockState state) {
        this(pos, state, ConveyorSpec.DEFAULT);
    }

    public ConveyorBlockEntity(BlockPos pos, BlockState state, ConveyorSpec spec) {
        super(ModBlockEntities.CONVEYOR.get(), pos, state);
        this.itemHandler = new ConveyorItemHandler(this, spec);
    }

    /** 添加物品到传送带（默认从入口开始）。 */
    public boolean addTransportedItem(ItemStack stack) {
        return itemHandler.addItem(stack);
    }

    /** 添加物品到传送带，指定起始进度。 */
    public boolean addTransportedItem(ItemStack stack, double startProgress) {
        return itemHandler.addItem(stack, startProgress);
    }

    /** 移除入口物品（最新放入）。 */
    public ItemStack removeLastItem() {
        return itemHandler.removeLastItem();
    }

    /** 吸收掉落物。 */
    public boolean absorbItemEntity(net.minecraft.world.entity.item.ItemEntity entity) {
        return itemHandler.absorbItemEntity(entity);
    }

    /** @return 当前缓冲队列（索引 0 = 入口，末尾 = 出口） */
    public List<ItemStack> getBufferContents() {
        return itemHandler.getContents();
    }

    /** @return 当前传送带规格 */
    public ConveyorSpec getSpec() {
        return itemHandler.getSpec();
    }

    /** @return 指定索引物品的计时器值（渲染器用） */
    public int getTimer(int index) {
        return itemHandler.getTimer(index);
    }

    // ========== Tick ==========

    public static void tick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity be) {
        if (level.isClientSide()) {
            be.clientTick();
        } else {
            be.itemHandler.tick();
        }
    }

    /**
     * 客户端 tick：独立推进 timer，实现平滑视觉动画。
     * 服务端同步到来时会通过 load() 覆盖 timers，消除偏差。
     */
    private void clientTick() {
        if (level == null) return;
        // 不修改 itemHandler 的 timers（由服务端管理），而是在本地额外维护一个克隆
        // 但为了简洁，我们直接让 timer 在客户端也自增，服务端同步时覆盖纠正
        // 只要 block update 频率高于肉眼可察觉的偏差，效果就没问题
        itemHandler.tickClient();
    }

    // ========== 视觉进度计算 ===========

    /**
     * 计算指定索引物品的视觉进度 (0.0 ~ 1.0)。
     * <p>
     * 进度直接基于该物品的计时器，与在队列中的索引无关。
     * 物品放入时 timer=0 → progress=0（入口），
     * timer = slotCount × ticksPerSlot → progress=1.0（出口，正好被输出）。
     * </p>
     */
    public double getVisualProgress(int index) {
        int totalTicks = itemHandler.getSpec().slotCount() * itemHandler.getSpec().ticksPerSlot();
        return Math.min((double) itemHandler.getTimer(index) / totalTicks, 1.0);
    }

    // ========== 网络同步 ==========

    public void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // ========== NBT ==========

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        itemHandler.save(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        itemHandler.load(tag, registries);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}