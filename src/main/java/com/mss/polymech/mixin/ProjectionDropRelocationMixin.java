package com.mss.polymech.mixin;

import com.mss.polymech.physics.ProjectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把投影维度里产生的掉落物搬到物理体所在的世界。
 *
 * <p><b>问题</b>：红石导线脱落、活塞砸掉火把、机器吐物品 —— 这些逻辑都在投影维度里跑，
 * 原版把掉落物生成在<b>那一格</b>（隐藏维度）。玩家在太空维度既看不见也捡不到，
 * 那些东西就永远烂在投影里。</p>
 *
 * <p><b>钩子选型</b>：{@code Block#popResource(Level, BlockPos, ItemStack)} 是 vanilla 掉落的主干道
 * （{@code Block#dropResources}、{@code Containers#dropContents} 都汇到它），
 * 而且签名被我们自己的代码验证过（{@code PhysicsBodyInteraction} 正在用）。
 * 比打在 {@code Level#addFreshEntity} 上稳 —— 后者在 {@code ServerLevel} 里有重写，
 * 注入到父类未必能被调用到。</p>
 *
 * <p>非投影维度直接放行（{@code relocateDrop} 内部先判 level），普通存档零开销。</p>
 */
@Mixin(Block.class)
public abstract class ProjectionDropRelocationMixin {

    @Inject(
            method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void polymech$relocateProjectionDrop(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        if (ProjectionManager.relocateDrop(level, pos, stack)) {
            ci.cancel(); // 投影里不再生成，已经在世界侧喷出来了
        }
    }
}
