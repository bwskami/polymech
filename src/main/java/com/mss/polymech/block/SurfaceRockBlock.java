package com.mss.polymech.block;

import com.mss.polymech.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 矿物碎块指示方块（对齐TFC GroundcoverBlock looseOre）。
 * <p>
 * 地表/地下放置的小碎矿石块，2像素高无碰撞薄片。
 * 水流可以冲掉它，玩家点击直接拾取对应粗矿。
 * 中键选中获得对应粗矿物品。
 * </p>
 */
public class SurfaceRockBlock extends Block {

    /** TFC looseOre 形状：5,0,5 → 11,2,11，2像素高 */
    public static final VoxelShape SHAPE = box(5.0D, 0.0D, 5.0D, 11.0D, 2.0D, 11.0D);

    private final String mineral;

    public SurfaceRockBlock(Properties properties, String mineral) {
        super(properties);
        this.mineral = mineral;
    }

    public String getMineral() {
        return mineral;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        return level.getBlockState(belowPos).isFaceSturdy(level, belowPos, Direction.UP);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
                                     LevelAccessor level, BlockPos currentPos, BlockPos facingPos) {
        return state.canSurvive(level, currentPos) ? state : Blocks.AIR.defaultBlockState();
    }

    /**
     * 右键拾取：直接给对应粗矿物品。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            ItemStack drop = getDropItem();
            if (!drop.isEmpty()) {
                player.getInventory().placeItemBackInInventory(drop);
            }
            level.removeBlock(pos, false);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 中键选中（Pick Block）：返回对应粗矿物品。
     */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return getDropItem();
    }

    /**
     * 获取掉落物品：矿物碎块 → 对应粗矿。
     */
    private ItemStack getDropItem() {
        if (mineral == null || mineral.isEmpty()) return ItemStack.EMPTY;
        // 优先掉落粗矿（金属矿物）
        var rawItem = ModItems.getRawMineral(mineral);
        if (rawItem != null) return rawItem.get().getDefaultInstance();
        // 其次掉落宝石（宝石矿物如 diamond、emerald、ruby 等）
        var gemItem = ModItems.getMaterialItem(com.mss.polymech.api.item.ModItemTypes.GEM, mineral);
        if (gemItem != null) return gemItem.get().getDefaultInstance();
        // 再尝试粉碎矿
        var crushedItem = ModItems.getMineralItem(com.mss.polymech.api.item.ModItemTypes.CRUSHED, mineral);
        if (crushedItem != null) return crushedItem.get().getDefaultInstance();
        // 最后尝试粉末
        var dustItem = ModItems.getMaterialItem(com.mss.polymech.api.item.ModItemTypes.DUST, mineral);
        if (dustItem != null) return dustItem.get().getDefaultInstance();
        return ItemStack.EMPTY;
    }
}
