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
 * 行星渲染 GPU 着色器注册（BASE 层 + CLOUD 层）。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class PlanetShaders {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ShaderInstance planetShader;
    private static ShaderInstance cloudShader;
    private static ShaderInstance atmoShader;

    private PlanetShaders() {}

    @SubscribeEvent
    static void registerShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "planet"),
                            DefaultVertexFormat.POSITION_COLOR_NORMAL),
                    instance -> planetShader = instance);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[poly_mech] 行星 BASE 着色器加载失败，将回退 CPU", e);
        }
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "cloud"),
                            DefaultVertexFormat.POSITION_COLOR_NORMAL),
                    instance -> cloudShader = instance);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[poly_mech] 行星 CLOUD 着色器加载失败，将回退 CPU", e);
        }
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "atmo"),
                            DefaultVertexFormat.POSITION_COLOR_NORMAL),
                    instance -> atmoShader = instance);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[poly_mech] 行星 ATMO 着色器加载失败，将回退 CPU", e);
        }
    }

    public static ShaderInstance planetShader() { return planetShader; }
    public static ShaderInstance cloudShader() { return cloudShader; }
    public static ShaderInstance atmoShader() { return atmoShader; }
    public static boolean isReady() { return planetShader != null; }
    public static boolean isCloudReady() { return cloudShader != null; }
    public static boolean isAtmoReady() { return atmoShader != null; }
}
