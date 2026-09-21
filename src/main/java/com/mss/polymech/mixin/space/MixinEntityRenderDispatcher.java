package com.mss.polymech.mixin.space;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 对应 space 0.1.0 的
 * {@code org.deep_space_studio.space.mixin.client.render.MixinEntityRenderDispatche}：
 * <b>太空世界里一律不画实体阴影</b>。
 *
 * <p><b>职责</b>：太空世界的实体阴影（{@code renderShadow}）直接跳过；其它维度照常。</p>
 *
 * <p><b>为什么必须掐掉 —— 这不是美术取舍，是防死循环</b>。
 * 原版 {@code EntityRenderDispatcher.renderShadow} 的方块循环边界是：
 * <pre>
 *   int i  = Mth.floor(d0 - size);   int j  = Mth.floor(d0 + size);   // d0 = 实体世界 X
 *   int i1 = Mth.floor(d2 - size);   int j1 = Mth.floor(d2 + size);   // d2 = 实体世界 Z
 *   for (int k1 = i1; k1 &lt;= j1; k1++)
 *       for (int l1 = i; l1 &lt;= j; l1++) { ... }
 * </pre>
 * {@code Mth.floor} 是 {@code (int)value} 的<b>饱和</b>转换（{@code return value < (double)i ? i - 1 : i;}）：
 * 坐标一旦越过 ±2^31，上下界双双饱和到 {@code Integer.MAX_VALUE}（负向越界时 {@code MIN_VALUE - 1}
 * 还会回绕成 {@code MAX_VALUE}）。于是 {@code i == j == Integer.MAX_VALUE}，而 {@code l1++} 到 MAX 之后
 * 回绕成 {@code MIN_VALUE}，{@code MIN_VALUE <= MAX_VALUE} 依然成立 —— <b>循环永不退出</b>。
 *
 * <p>太空维度按恒等约定（1 格 = 1 米）玩家坐标就是 1e10~1e16 量级，且玩家自己的阴影
 * （{@code distanceToSqr == 0}）每帧必画，于是客户端 tick（与渲染同线程）整帧卡死，
 * 现象就是「一进太空就未响应」，日志里客户端行全部停住、只有其它线程还在打印。
 *
 * <p><b>为什么判据是"是否在太空世界"而不是"坐标阈值"</b>：这是原版用 int 算方块坐标的固有限制，
 * 不是我们的坐标算错；太空里本来也没有真正的投影面，space 的选择就是不做这件事。
 * 地表（行星维度）坐标仍是小数值，阴影照常，所以按维度判定，与 space 完全一致。
 *
 * <p><b>验证依据</b>（离线复现，不靠推理）：{@code build/pm-diag/ShadowLoopProbe.java} 按 1.21.1
 * 源码逐字复刻该循环 —— 太空真实坐标 X=+1.675e10 / Z=-1.468e11 跑满 43 亿次（int 一整圈）
 * 仍未退出；旧 ZOOM=10000 约定下的同一位置 2 次即退出；临界点 2^31-1 也已死循环。
 *
 * <p><b>锚点为什么用 {@code @WrapOperation} 而不是 space 的 {@code @Redirect + @Shadow}</b>：
 * 锚点本身与 space 相同（{@code render} 里那次 {@code renderShadow} 调用）。
 * 但 {@code @Redirect} 要配合 {@code @Shadow} 声明目标方法，而 {@code @Shadow} 只认<i>目标类自身声明</i>
 * 的方法（见 {@code MixinItemEntity} 的教训），对一个 private static 目标不必要地多一层约束；
 * {@code @WrapOperation} 直接给到原调用，是本项目已标准化的写法。
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinEntityRenderDispatcher {

    // 刻意<b>不</b>声明 static final Logger 或任何带初始化器的静态字段：那要求 Mixin 把本类的
    // <clinit> 合并进目标类，而"是否真的合并了"离线无法验证；万一没合并，字段为 null，
    // 第一次拦截就会在 render 的 try 里 NPE（比卡死更难查）。所以静态字段只有下面两个
    // "无初始化器"的布尔（默认 false，不产生 <clinit>），日志器用到时再取（每次会话至多两次）。

    /** 只报一次：首次拦到 renderShadow 就说明锚点在运行时确实生效（Mixin 成功是静默的）。 */
    private static boolean polymech$reportedFirstIntercept;

    /** 只报一次：首次在太空世界拦下阴影，说明"跳过"这条路径确实被走到。 */
    private static boolean polymech$reportedSpaceSkip;

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V")
    )
    private void polymech$skipShadowInSpaceWorld(PoseStack poseStack, MultiBufferSource buffer, Entity entity,
                                                 float weight, float partialTicks, LevelReader level, float size,
                                                 Operation<Void> original) {
        boolean inSpace = ClientSpaceWorld.isSpaceWorld();

        if (!polymech$reportedFirstIntercept) {
            polymech$reportedFirstIntercept = true;
            LoggerFactory.getLogger("PolyMech/Space/Shadow")
                    .info("[太空阴影] 锚点已生效：首次拦到 renderShadow（在太空世界={}，实体={}，世界坐标=({}, {}, {})）",
                            inSpace, entity.getClass().getSimpleName(), entity.getX(), entity.getY(), entity.getZ());
        }

        if (inSpace) {
            if (!polymech$reportedSpaceSkip) {
                polymech$reportedSpaceSkip = true;
                LoggerFactory.getLogger("PolyMech/Space/Shadow")
                        .info("[太空阴影] 已跳过太空世界的实体阴影（实体={}，世界坐标=({}, {}, {})）"
                                        + " —— 原版 int 方块循环在这里永不退出",
                                entity.getClass().getSimpleName(), entity.getX(), entity.getY(), entity.getZ());
            }
            return;
        }

        original.call(poseStack, buffer, entity, weight, partialTicks, level, size);
    }

    // ⚠️ 这里不能再放 public / 非 private 的 static 辅助方法：Mixin 会以
    //    "contains non-private static method ..." 直接把启动打崩（本轮踩过，见 §31.8）。
    //    注入痕迹的自检放在普通类 SpaceShadowAnchor 里做（按 polymech$ 前缀识别）。
}
