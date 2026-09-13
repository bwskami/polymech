package com.mss.polymech.mixin.space;

import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 超人姿态的<b>碰撞箱尺寸</b>：太空维度 + 疾跑冲刺中，把 {@code getDimensions} 收成 0.6³。
 *
 * <p>为什么不改 {@code Pose}：原版 {@code Pose.FALL_FLYING} 会牵连模型手臂、披风、
 * 视角摇晃等一整套"鞘翅滑翔"表现，而我们只要它的<b>尺寸</b>（0.6³ 正方体）。
 * 直接覆盖 {@code getDimensions(Pose)} 的返回值，{@code getBbWidth()}/{@code getBbHeight()}
 * 就随之变成 0.6 —— AABB（{@code MixinEntity#polymech$spaceBoundingBox}）、
 * 物理碰撞体（{@code ClientPhysics#ensurePlayerBody}）、渲染支点
 * （{@code SpaceBodyTiltMixin}）三处用同一个尺寸来源，自动统一。</p>
 *
 * <p><b>缓存刷新</b>：{@code getBbWidth/Height} 读的是缓存字段 {@code dimensions}，
 * 它只在 {@code refreshDimensions()} 时经 {@code getDimensions(getPose())} 重建。
 * 而疾跑开始/结束<b>不改变 Pose</b>，原版不会触发刷新 —— 所以在 {@code tick} 尾部
 * 检测超人态翻转，翻转时主动 {@code refreshDimensions()}（它内部会重建缓存尺寸
 * 并 {@code reapplyPosition()} 重建 AABB）。两端各自检测、各自刷新，零网络同步。</p>
 *
 * <p>眼睛高度取 <b>1.6</b>（= {@link SpacePlayerData#HEAD_BOX_CENTER}，取 1.6 而非 1.62
 * 是为了和物理头盒的 0.1 体素格子对齐），不取原版滑翔位姿的 0.4 ——
 * 0.4 相对 1.8 的玩家模型只到小腿，盒子看着就"还在脚上"。
 * 配合 {@code MixinEntity} 的头盒 AABB（中心 = 实体位置 + 1.6）与物理建体的
 * {@code bodyCenterOffset}（同样 1.6），<b>眼睛正好落在头盒正中心</b>：
 * 第一人称相机离盒壁四面各 0.3 格，撞墙时是"脸撞盒壁"被挡，不会把头伸进方块看内部；
 * 且普通/超人眼高相同，切换姿态时视角不跳。</p>
 */
@Mixin(Entity.class)
public abstract class SupermanDimensionsMixin {

    /**
     * 超人姿态的正方体尺寸：0.6 × 0.6，眼高 = {@link SpacePlayerData#SUPERMAN_CENTER_HEIGHT}（= 1.6）。
     *
     * <p>盒子高 0.6 但眼高 1.62 —— "眼高大于身高"在原版是合法且已有的写法
     * （见 {@code Pose.DYING}：{@code fixed(0.2, 0.2).withEyeHeight(1.62)}）。
     * 这样 {@code getEyeHeight()} 仍是站立眼高，第一人称相机仍在头部高度，
     * 切换姿态时视角不跳；头盒 AABB 则由 {@code MixinEntity} 按同一偏移另行给出。</p>
     */
    private static final EntityDimensions POLYMECH$SUPERMAN =
            EntityDimensions.scalable(0.6F, 0.6F)
                    .withEyeHeight((float) SpacePlayerData.SUPERMAN_CENTER_HEIGHT);

    /** 上一 tick 的超人态（用于检测翻转）。仅玩家使用，非玩家实体始终为 false。 */
    @Unique
    private boolean polymech$wasSuperman = false;

    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void polymech$supermanDimensions(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player)) {
            return;
        }
        if (SpacePlayerData.isSuperman(self)) {
            cir.setReturnValue(POLYMECH$SUPERMAN);
        }
    }

    /**
     * tick 尾部：超人态翻转时刷新缓存尺寸 + AABB。
     *
     * <p>只盯玩家；疾跑状态两端各自同步（命令包），两端独立得出同一翻转结论，
     * 各自调用 {@code refreshDimensions()}，无需额外网络包。</p>
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void polymech$refreshOnSupermanChange(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player)) {
            return;
        }
        boolean now = SpacePlayerData.isSuperman(self);
        if (now != this.polymech$wasSuperman) {
            this.polymech$wasSuperman = now;
            self.refreshDimensions();
        }
    }
}
