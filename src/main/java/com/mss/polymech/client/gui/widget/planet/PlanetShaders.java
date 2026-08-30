package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import com.mss.polymech.Polymech;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

import java.io.IOException;

/**
 * 行星 BASE 层的 GPU 光照着色器注册。
 *
 * <p>着色器本体在 {@code assets/poly_mech/shaders/core/planet.{vsh,fsh,json}}，
 * 顶点格式 {@link DefaultVertexFormat#POSITION_COLOR_NORMAL}：局部坐标 + 地块 albedo + 单位径向法线。
 * 光照/阴影全部在顶点着色器中逐顶点计算（见 planet.vsh），与 CPU 版 PlanetLighting 等价。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class PlanetShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ShaderInstance planetShader;

    private PlanetShaders() {}

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) {
        // 着色器加载/编译失败时不让它崩游戏：planetShader 保持 null，渲染自动回退 CPU 路径。
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "planet"),
                            DefaultVertexFormat.POSITION_COLOR_NORMAL),
                    instance -> planetShader = instance);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[poly_mech] 行星 GPU 着色器加载失败，BASE 层将回退 CPU 光照", e);
        }
    }

    /** 着色器实例；资源加载完成前可能为 null。 */
    public static ShaderInstance planetShader() {
        return planetShader;
    }

    /** 着色器是否已加载就绪。 */
    public static boolean isReady() {
        return planetShader != null;
    }
}
