package com.mss.polymech.client.space;

/**
 * "太空世界不画实体阴影"锚点（{@code com.mss.polymech.mixin.space.MixinEntityRenderDispatcher}）
 * 的<b>注入自检</b>。
 *
 * <p><b>职责</b>：回答一个问题 —— 那个 mixin 到底有没有被注入进
 * {@code net.minecraft.client.renderer.entity.EntityRenderDispatcher}。</p>
 *
 * <p><b>为什么需要它</b>：Mixin 注入<i>成功</i>时不打任何日志，失败才报错。于是"太空里没卡死"
 * 到底是"守卫生效"还是"这次压根没走到阴影路径"完全分不清 —— 本会话就撞过一次：进太空 35 秒
 * 没卡、守卫日志却 0 命中；真正原因是第一人称下本地玩家不进渲染列表
 * （{@code LevelRenderer:1014}），{@code renderShadow} 一次都没被调用。
 * 用"注入痕迹"把<b>成功</b>也变成可观测的，才能堵住这类误判。</p>
 *
 * <p><b>⚠️ 为什么这个类必须放在 mixin 包之外</b>（本轮因为放错位置崩过一次）：
 * {@code poly_mech.mixins.json} 里声明了 {@code "package": "com.mss.polymech.mixin"}，
 * 而<b>该包（含子包）内的类被 Mixin 视为 mixin 类、不允许被直接引用</b>，否则启动抛
 * {@code IllegalClassLoadError: … is in a defined mixin package … and cannot be referenced directly}。
 * 同理，mixin 类里也不允许非 private 的 static 方法
 * （{@code contains non-private static method …}，也会打崩启动）。
 * 这两条规则本轮都踩了，别再把它搬回 mixin 包或写回 mixin 里。</p>
 *
 * <p><b>判据</b>：本模组注入的成员名都带 {@code polymech$} 前缀（Mixin 对含 {@code $} 的名字不改名），
 * 因此目标类上只要出现任何 {@code polymech$} 成员，就是注入成功的证据。</p>
 */
public final class SpaceShadowAnchor {

    private SpaceShadowAnchor() {
    }

    /** 目标类上是否出现了本模组的注入痕迹。任何异常都吞掉：自检本身不该成为故障源。 */
    public static boolean applied() {
        try {
            Class<?> target = net.minecraft.client.renderer.entity.EntityRenderDispatcher.class;

            for (java.lang.reflect.Field field : target.getDeclaredFields()) {
                if (field.getName().contains("polymech$")) {
                    return true;
                }
            }

            for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
                if (method.getName().contains("polymech$")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 目标类尚未加载 / 不在客户端环境：返回 false，由调用方决定怎么报。
        }

        return false;
    }
}
