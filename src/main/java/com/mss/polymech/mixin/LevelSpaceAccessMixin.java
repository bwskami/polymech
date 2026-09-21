package com.mss.polymech.mixin;

import com.mss.polymech.space.SpaceWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>大坐标系地基（参考 space mod 新版的 MixinLevel/MixinBlockGetter/MixinWorldBorder）：
 * 只对太空维度生效，主世界与行星影子维度行为完全不变。</p>
 * <ol>
 *   <li>{@link Level#getBlockState} / {@link Level#getFluidState}：深空位置
 *       （超过 {@link SpaceWorld#DEEP_SPACE_LIMIT}，即 BlockPos 26 位极限）直接返回
 *       真空，不进入区块系统 —— 避免在无区块坐标上触发区块加载与 26 位长整型溢出。</li>
 * </ol>
 *
 * <p>边界那条（{@code getWorldBorder} → 太空专用大边界）已删除：
 * {@code space.MixinWorldBorder} 把边界整套放开后它成了冗余。</p>
 */
@Mixin(Level.class)
public abstract class LevelSpaceAccessMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void polymech$deepSpaceBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (SpaceWorld.isSpace((Level) (Object) this) && SpaceWorld.isDeepSpace(pos.getX(), pos.getZ())) {
            cir.setReturnValue(Blocks.VOID_AIR.defaultBlockState());
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void polymech$deepSpaceFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (SpaceWorld.isSpace((Level) (Object) this) && SpaceWorld.isDeepSpace(pos.getX(), pos.getZ())) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }

    /**
     * 深空<b>写入</b>也必须拦 —— 这是 {@code docs/mps-clone-plan.md} §30.10 查出来的缺口。
     *
     * <p>原因：守卫原来只拦读取。而 {@code BlockPos.asLong} 的 X/Z 只有 26 位
     * （{@code i |= ((long)x & PACKED_X_MASK)} …），**1e11 格会别名到原点附近** ⇒
     * 一次"把方块放在深空"实际会<b>在正常世界里凭空放/拆方块</b>，
     * 比"读到错方块"危险得多（`getBlockState` 别名最多读到空气，`setBlock` 会改世界）。</p>
     *
     * <p><b>为什么用完整描述符而不是方法名</b>：{@code Level} 有两个 {@code setBlock} 重载
     * （{@code (BlockPos,BlockState,int)} 与 {@code (BlockPos,BlockState,int,int)}），
     * 只写方法名 Mixin 无法确定目标。两个都拦，是因为内部路径直接走 4 参版，
     * 3 参版又被外部调用 —— 只拦一个会漏。</p>
     *
     * <p>返回 false（"没放成"）是正确语义：深空没有方块空间，放不进去。</p>
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"), cancellable = true)
    private void polymech$deepSpaceSetBlock(BlockPos pos, BlockState state, int flags,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (SpaceWorld.isSpace((Level) (Object) this) && SpaceWorld.isDeepSpace(pos.getX(), pos.getZ())) {
            cir.setReturnValue(false);
        }
    }

    /** 4 参重载：内部写入路径（`setBlockAndUpdate`、邻居更新等）走这条。 */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void polymech$deepSpaceSetBlockRecursive(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (SpaceWorld.isSpace((Level) (Object) this) && SpaceWorld.isDeepSpace(pos.getX(), pos.getZ())) {
            cir.setReturnValue(false);
        }
    }
}
