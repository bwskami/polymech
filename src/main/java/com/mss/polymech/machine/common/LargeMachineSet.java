package com.mss.polymech.machine.common;

import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.SideBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.function.Supplier;

/**
 * 大型机器注册集合，封装同一大型机器的主方块、侧面方块、物品及方块实体引用。
 * <p>
 * 方块实体类型在 {@link com.mss.polymech.block.entity.ModBlockEntities} 中注册后
 * 通过 {@link #setBlockEntities(Supplier, Supplier)} 回填，以解决方块与方块实体
 * 注册阶段不同步的问题。
 * </p>
 *
 * @param <M> 主方块类型
 * @param <S> 侧面方块类型
 */
public final class LargeMachineSet<M extends BaseMachineBlock, S extends SideBlock> {

    private final DeferredBlock<M> mainBlock;
    private final DeferredBlock<S> sideBlock;
    private final DeferredItem<? extends BlockItem> item;
    private Supplier<? extends BlockEntityType<?>> mainBlockEntity;
    private Supplier<? extends BlockEntityType<?>> sideBlockEntity;

    public LargeMachineSet(DeferredBlock<M> mainBlock,
                           DeferredBlock<S> sideBlock,
                           DeferredItem<? extends BlockItem> item) {
        this.mainBlock = mainBlock;
        this.sideBlock = sideBlock;
        this.item = item;
    }

    public DeferredBlock<M> mainBlock() {
        return mainBlock;
    }

    public DeferredBlock<S> sideBlock() {
        return sideBlock;
    }

    public DeferredItem<? extends BlockItem> item() {
        return item;
    }

    public Supplier<? extends BlockEntityType<?>> mainBlockEntity() {
        return mainBlockEntity;
    }

    public Supplier<? extends BlockEntityType<?>> sideBlockEntity() {
        return sideBlockEntity;
    }

    /**
     * 回填方块实体类型引用。
     *
     * @param mainBlockEntity 主方块实体类型
     * @param sideBlockEntity 侧面方块实体类型
     */
    public void setBlockEntities(Supplier<? extends BlockEntityType<?>> mainBlockEntity,
                                 Supplier<? extends BlockEntityType<?>> sideBlockEntity) {
        this.mainBlockEntity = mainBlockEntity;
        this.sideBlockEntity = sideBlockEntity;
    }
}
