package com.mss.polymech.block.entity;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 传送带方块实体。
 * <p>
 * 职责剥离后仅作为委托层，实际物流逻辑由 {@link ConveyorItemHandler} 处理。
 * 负责：生命周期管理、NBT同步、网络同步、方法委托。
 * </p>
 */
public class ConveyorBlockEntity extends BlockEntity {
    private final ConveyorItemHandler itemHandler;

    public ConveyorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONVEYOR.get(), pos, state);
        this.itemHandler = new ConveyorItemHandler(this);
    }

    // ========== 委托方法 ==========

    /**
     * 添加物品到传送带（起始进度 0.0）。
     */
    public boolean addTransportedItem(ItemStack stack) {
        return itemHandler.addItem(stack);
    }

    /**
     * 添加物品到传送带，指定起始进度。
     */
    public boolean addTransportedItem(ItemStack stack, double startProgress) {
        return itemHandler.addItem(stack, startProgress);
    }

    /**
     * 移除并返回队列末尾的物品。
     */
    public ItemStack removeLastItem() {
        return itemHandler.removeLastItem();
    }

    /**
     * 吸收传送带上的掉落物实体。
     */
    public boolean absorbItemEntity(net.minecraft.world.entity.item.ItemEntity itemEntity) {
        return itemHandler.absorbItemEntity(itemEntity);
    }

    /**
     * @return 当前所有运输物品的不可修改视图
     */
    public List<TransportedItem> getTransportedItems() {
        return itemHandler.getItems();
    }

    // ========== Tick ==========

    /**
     * 传送带 tick 方法，由 {@link ConveyorBlock} 注册。
     */
    public static void tick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity be) {
        be.itemHandler.tick();
    }

    // ========== 工具方法 ==========

    /**
     * 根据进度计算物品在世界中的渲染位置。
     * <p>
     * 供 {@link com.mss.polymech.client.renderer.ConveyorBlockEntityRenderer} 和
     * {@link ConveyorItemHandler} 使用。
     * </p>
     *
     * @param pos      方块位置
     * @param facing   传送带朝向
     * @param type     传送带类型
     * @param progress 进度 (0.0 ~ 1.0)
     * @return 世界坐标
     */
    public static Vec3 getWorldPosition(BlockPos pos, Direction facing, ConveyorType type, double progress) {
        double x = pos.getX() + 0.5 + facing.getStepX() * (progress - 0.5);
        double z = pos.getZ() + 0.5 + facing.getStepZ() * (progress - 0.5);
        double y = pos.getY() + 0.25;
        if (type == ConveyorType.UP) {
            y += progress * 1.0;
        } else if (type == ConveyorType.DOWN) {
            y += (1.0 - progress) * 1.0;
        }
        return new Vec3(x, y, z);
    }

    // ========== 网络同步 ==========

    /**
     * 向客户端发送方块更新数据包。
     */
    public void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // ==================== NBT 同步 ====================

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