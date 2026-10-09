package com.mss.polymech.mixin;

import com.mss.polymech.physics.ProjectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把<b>投影维度</b>里发出的 {@code levelEvent} 搬到物理体所在的世界。
 *
 * <p><b>问题</b>：{@code ServerLevel.levelEvent} 按 {@code this.dimension()} 广播，
 * 而投影维度里一个玩家都没有 ⇒ 破坏碎屑与音效（2001）、活塞/发射器/音符盒那一类事件
 * 全部发给了空气 —— 与掉落物是同一个病（见 {@code ProjectionDropRelocationMixin}）。</p>
 *
 * <p><b>钩子选型</b>：{@code ServerLevel#levelEvent(Player, int, BlockPos, int)} 是所有
 * "世界事件"的主干道（{@code Block#spawnDestroyParticles}、{@code Level#destroyBlock}、
 * 各方块的 {@code level.levelEvent(...)} 都汇到它），而且签名里带的是<b>整型格坐标</b>，
 * 拦下来正好能做坐标换算。打在 {@code HEAD} + cancellable：搬成功就取消投影里的那一次。</p>
 *
 * <p>非投影维度直接放行（{@link ProjectionManager#relocateLevelEvent} 先判 level），
 * 普通存档零开销。</p>
 *
 * <p>注册位置见 {@code poly_mech.mixins.json} 的 {@code mixins}（服务端与客户端都要，
 * 单人档的集成服务器也走它）。</p>
 */
@Mixin(ServerLevel.class)
public abstract class ProjectionLevelEventMixin {

    @Inject(
            method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void polymech$relocateProjectionLevelEvent(Player player, int type, BlockPos pos, int data,
                                                       CallbackInfo ci) {
        if (ProjectionManager.relocateLevelEvent((ServerLevel) (Object) this, type, pos, data)) {
            ci.cancel(); // 投影里不再广播，已经在世界侧放过了
        }
    }
}
