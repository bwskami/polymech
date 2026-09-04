package com.mss.polymech.client.gui.widget.planet;

import org.joml.Matrix4f;

/**
 * 渲染一颗星球所需的全部外部参数。
 */
public record PlanetRenderParams(
        Matrix4f viewMatrix,
        Matrix4f projectionMatrix,
        double cameraX,
        double cameraY,
        double cameraZ,
        float partialTick,
        double simTime,
        PlanetLighting lighting,
        ShadowModel shadowModel) {
}
