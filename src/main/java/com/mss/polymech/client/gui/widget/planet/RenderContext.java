package com.mss.polymech.client.gui.widget.planet;

import org.joml.Matrix4f;

/**
 * 渲染上下文：每帧传递给所有渲染方法的共享参数。
 * <p>
 * 替代原先 {@code drawXxx(mat, t, cosY, sinY, cosX, sinX, focal, cx, cy)} 的长参数列表。
 */
public final class RenderContext {

    public final Matrix4f matrix;
    public final float cosY, sinY, cosX, sinX;
    public final float focal, cx, cy;
    public final float simTime;
    public final float overlayFade;

    public RenderContext(Matrix4f matrix,
                         float cosY, float sinY, float cosX, float sinX,
                         float focal, float cx, float cy,
                         float simTime, float overlayFade) {
        this.matrix = matrix;
        this.cosY = cosY;
        this.sinY = sinY;
        this.cosX = cosX;
        this.sinX = sinX;
        this.focal = focal;
        this.cx = cx;
        this.cy = cy;
        this.simTime = simTime;
        this.overlayFade = overlayFade;
    }
}
