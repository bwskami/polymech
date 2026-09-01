package com.mss.polymech.client.gui.widget.planet;

/**
 * 相机控制器：管理视角旋转、缩放、焦点过渡、坐标变换。
 * <p>
 * 从 SolarSystemView 中提取，职责单一：
 * <ul>
 *   <li>鼠标拖拽旋转（yaw/pitch）</li>
 *   <li>滚轮缩放（dist）</li>
 *   <li>焦点平滑过渡动画</li>
 *   <li>世界坐标 → 相机空间 → 屏幕坐标变换</li>
 * </ul>
 */
public final class CameraController {

    private static final float MOUSE_SENSITIVITY = 0.01f;
    private static final float ZOOM_FACTOR = 0.85f;
    private static final float ZOOM_INVERSE = 1f / ZOOM_FACTOR;
    private static final float TRANSITION_DURATION = 0.28f;
    private static final float INERTIA_TAU = 0.45f;      // 惯性衰减时间常数（秒）
    private static final float INERTIA_MAX_VEL = 6.0f;    // 最大惯性速度（弧度/秒）
    private static final float INERTIA_MIN_VEL = 0.008f;  // 低于此速度归零

    // ===== 视角状态 =====
    private float yaw = 0.6f;
    private float pitch = 0.35f;
    private float dist = 3.2f;

    // ===== 焦点世界坐标 =====
    private float focalX, focalY, focalZ;

    // ===== 旋转惯性 =====
    private float yawVel, pitchVel;   // 弧度/秒
    private long lastRotateNano = 0;

    // ===== 焦点过渡动画 =====
    private float transT = 1.0f;
    private float fromX, fromY, fromZ;

    // ===== 每帧缓存的投影参数 =====
    private float focalLength, cx, cy;
    private float cosY, sinY, cosX, sinX;

    // ==================== 鼠标交互 ====================

    public void rotate(float dx, float dy) {
        long now = System.nanoTime();
        float edt = lastRotateNano == 0 ? 0.016f : (now - lastRotateNano) / 1e9f;
        lastRotateNano = now;
        edt = Math.max(edt, 0.001f);
        float dyaw = dx * MOUSE_SENSITIVITY;
        float dpitch = dy * MOUSE_SENSITIVITY;
        yaw += dyaw;
        pitch += dpitch;
        pitch = clamp(pitch, -1.4f, 1.4f);
        // 平滑记录瞬时角速度（弧度/秒），松手后作为惯性初速
        yawVel = clamp(yawVel * 0.4f + (dyaw / edt) * 0.6f, -INERTIA_MAX_VEL, INERTIA_MAX_VEL);
        pitchVel = clamp(pitchVel * 0.4f + (dpitch / edt) * 0.6f, -INERTIA_MAX_VEL, INERTIA_MAX_VEL);
    }

    /**
     * 每帧调用：未拖拽时应用旋转惯性（指数衰减）。
     */
    public void updateInertia(float dt, boolean dragging) {
        if (dragging) return;
        if (Math.abs(yawVel) < INERTIA_MIN_VEL && Math.abs(pitchVel) < INERTIA_MIN_VEL) {
            yawVel = 0; pitchVel = 0;
            return;
        }
        yaw += yawVel * dt;
        pitch += pitchVel * dt;
        pitch = clamp(pitch, -1.4f, 1.4f);
        float decay = (float) Math.exp(-dt / INERTIA_TAU);
        yawVel *= decay;
        pitchVel *= decay;
    }

    /** 打断惯性（如点击切换焦点时）。 */
    public void stopInertia() {
        yawVel = 0; pitchVel = 0;
    }

    public void zoom(float wheelDelta, float minDist) {
        if (wheelDelta > 0) dist *= ZOOM_FACTOR;
        else dist *= ZOOM_INVERSE;
        dist = clamp(dist, minDist, 1200f);
    }

    /**
     * 自由视角下的拖拽平移：真正的屏幕空间拖拽。
     * 拖右，场景跟手向右；拖下，场景跟手向下。
     * 焦点沿相机 right/up 向量移动，速度随距离缩放。
     */
    public void pan(float dx, float dy) {
        if (focalLength <= 0) return;
        float scale = dist / focalLength;
        // 相机基向量（世界系）：right = (cosY, 0, sinY)
        // up = (sinY*sinX, cosX, -cosY*sinX)
        float rightX = cosY, rightY = 0, rightZ = sinY;
        float upX = sinY * sinX, upY = cosX, upZ = -cosY * sinX;
        // 场景跟手：鼠标向右 -> 焦点向左；鼠标向下 -> 焦点向上
        focalX += (-rightX * dx + upX * dy) * scale;
        focalY += (-rightY * dx + upY * dy) * scale;
        focalZ += (-rightZ * dx + upZ * dy) * scale;
    }

    public void ensureMinDist(float minDist) {
        dist = Math.max(dist, minDist);
    }

    /**
     * 把焦点限制在恒星系边界内（XZ 圆盘半径 + Y 高度），避免自由视角漂太远回不来。
     */
    public void clampFocal(float maxRadius) {
        if (maxRadius <= 0) return;
        float h2 = focalX * focalX + focalZ * focalZ;
        if (h2 > maxRadius * maxRadius) {
            float h = (float) Math.sqrt(h2);
            float s = maxRadius / h;
            focalX *= s;
            focalZ *= s;
        }
        if (focalY > maxRadius) focalY = maxRadius;
        else if (focalY < -maxRadius) focalY = -maxRadius;
    }

    /** 直接设置相机距离（切换星系时用于重新取景）。 */
    public void setDist(float d) {
        dist = clamp(d, 1.5f, 1200f);
    }

    // ==================== 焦点过渡 ====================

    /**
     * 开始从当前位置过渡到新的焦点星球。
     */
    public void beginTransition(float targetX, float targetZ) {
        beginTransition(targetX, 0, targetZ);
    }

    public void beginTransition(float targetX, float targetY, float targetZ) {
        fromX = focalX;
        fromY = focalY;
        fromZ = focalZ;
        transT = 0;
        focalX = targetX;
        focalY = targetY;
        focalZ = targetZ;
    }

    /**
     * 每帧更新过渡动画。返回 true 表示仍在过渡中。
     */
    public boolean updateTransition(float dt, float targetX, float targetZ) {
        return updateTransition(dt, targetX, 0, targetZ);
    }

    public boolean updateTransition(float dt, float targetX, float targetY, float targetZ) {
        if (transT < 1.0f) {
            transT = Math.min(1.0f, transT + dt / TRANSITION_DURATION);
            float t = easeInCubic(transT);
            focalX = fromX + (targetX - fromX) * t;
            focalY = fromY + (targetY - fromY) * t;
            focalZ = fromZ + (targetZ - fromZ) * t;
            return true;
        } else {
            focalX = targetX;
            focalY = targetY;
            focalZ = targetZ;
            return false;
        }
    }

    // ==================== 直接设置 ====================

    /**
     * 直接设置焦点坐标（不经过过渡动画）。
     */
    public void setFocal(float x, float z) {
        setFocal(x, 0, z);
    }

    public void setFocal(float x, float y, float z) {
        focalX = x;
        focalY = y;
        focalZ = z;
        fromX = x;
        fromY = y;
        fromZ = z;
        transT = 1.0f;
    }

    // ==================== 投影参数 ====================

    /**
     * 每帧开始时计算投影参数（FOV、焦点、三角函数）。
     */
    public void updateProjection(float viewWidth, float viewHeight) {
        float focalDesired = Math.min(viewWidth, viewHeight) * 0.9f;
        float fov = 2f * (float) Math.atan((viewHeight / 2f) / focalDesired);
        focalLength = (viewHeight / 2f) / (float) Math.tan(fov / 2f);
        cx = viewWidth / 2f;
        cy = viewHeight / 2f;
        cosY = (float) Math.cos(yaw);
        sinY = (float) Math.sin(yaw);
        cosX = (float) Math.cos(pitch);
        sinX = (float) Math.sin(pitch);
    }

    // ==================== 坐标变换 ====================

    /**
     * 将星球局部坐标变换到相机空间。
     * <p>
     * 流程：local → 自转缩放 → 轴倾斜 → 世界偏移 → 相机旋转 → 相机空间
     *
     * @param out       输出相机空间坐标 [x, y, z]
     * @param v         局部顶点坐标
     * @param layerR    层半径
     * @param dwx       世界X偏移（星球世界位置 - 焦点世界位置）
     * @param dwz       世界Z偏移
     * @param sc        自转角 cos
     * @param ss        自转角 sin
     * @param tilt      轴倾斜角（弧度）
     */
    public void cameraTo(float[] out, float[] v, float layerR,
                         float dwx, float dwz, float sc, float ss, float tilt) {
        cameraTo(out, v, layerR, dwx, 0f, dwz, sc, ss, tilt);
    }

    public void cameraTo(float[] out, float[] v, float layerR,
                         float dwx, float dwy, float dwz, float sc, float ss, float tilt) {
        float lx = (v[0] * sc - v[2] * ss) * layerR;
        float lz = (v[0] * ss + v[2] * sc) * layerR;
        float ly = v[1] * layerR;
        float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        float wx = lx * ct - ly * st + dwx;
        float wz = lz + dwz;
        float wy = lx * st + ly * ct + dwy - focalY;
        float rx = wx * cosY + wz * sinY;
        float rz1 = -wx * sinY + wz * cosY;
        float ry2 = wy * cosX - rz1 * sinX;
        float rz = wy * sinX + rz1 * cosX;
        out[0] = rx;
        out[1] = ry2;
        out[2] = rz - dist;
    }

    /**
     * 将星球局部坐标变换到相机空间（返回新数组）。
     */
    public float[] camera(float[] v, float layerR, float dwx, float dwz, float sc, float ss, float tilt) {
        return camera(v, layerR, dwx, 0f, dwz, sc, ss, tilt);
    }

    public float[] camera(float[] v, float layerR, float dwx, float dwy, float dwz, float sc, float ss, float tilt) {
        float[] out = new float[3];
        cameraTo(out, v, layerR, dwx, dwy, dwz, sc, ss, tilt);
        return out;
    }

    /**
     * 将相机空间坐标变换到屏幕坐标。
     */
    public float[] toScreen(float[] cam) {
        float d = Math.max(-cam[2], 0.15f);
        return new float[]{cx + cam[0] * focalLength / d, cy - cam[1] * focalLength / d, cam[2]};
    }

    /**
     * 将屏幕坐标反投影到相机空间。
     */
    public float[] toCamera(float sx, float sy, float z) {
        float camX = (sx - cx) * (-z) / focalLength;
        float camY = -(sy - cy) * (-z) / focalLength;
        return new float[]{camX, camY, z};
    }

    /**
     * 将世界坐标变换到相机空间（不经过星球局部坐标）。
     * 用于太阳光晕、轨道环等全局元素。
     */
    public void worldToCamera(float[] out, float worldX, float worldY, float worldZ,
                              float dwx, float dwz) {
        float wx = worldX + dwx;
        float wz = worldZ + dwz;
        float wy = worldY - focalY;
        float rx = wx * cosY + wz * sinY;
        float rz1 = -wx * sinY + wz * cosY;
        float ry2 = wy * cosX - rz1 * sinX;
        float rz = wy * sinX + rz1 * cosX;
        out[0] = rx;
        out[1] = ry2;
        out[2] = rz - dist;
    }

    /**
     * 计算星球世界坐标在相机空间的 Z 深度（用于排序）。
     */
    public float camDepth(float dwx, float dwz) {
        float rz1 = -dwx * sinY + dwz * cosY;
        return dist - rz1 * cosX;
    }


    // ==================== 无分配坐标变换（热路径优化）====================

    /**
     * 与 {@link #cameraTo} 相同的变换，但写入已有数组，不分配新对象。
     */
    public void cameraToNoAlloc(float[] out, float[] v, float layerR,
                                float dwx, float dwz, float sc, float ss, float tilt) {
        cameraToNoAlloc(out, v, layerR, dwx, 0f, dwz, sc, ss, tilt);
    }

    public void cameraToNoAlloc(float[] out, float[] v, float layerR,
                                float dwx, float dwy, float dwz, float sc, float ss, float tilt) {
        float lx = (v[0] * sc - v[2] * ss) * layerR;
        float lz = (v[0] * ss + v[2] * sc) * layerR;
        float ly = v[1] * layerR;
        float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        float wx = lx * ct - ly * st + dwx;
        float wz = lz + dwz;
        float wy = lx * st + ly * ct + dwy - focalY;
        float rx = wx * cosY + wz * sinY;
        float rz1 = -wx * sinY + wz * cosY;
        float ry2 = wy * cosX - rz1 * sinX;
        float rz = wy * sinX + rz1 * cosX;
        out[0] = rx;
        out[1] = ry2;
        out[2] = rz - dist;
    }

    /**
     * 与 {@link #toScreen} 相同，但结果写入已有数组。
     */
    public void toScreenNoAlloc(float[] out, float[] cam) {
        float d = Math.max(-cam[2], 0.15f);
        out[0] = cx + cam[0] * focalLength / d;
        out[1] = cy - cam[1] * focalLength / d;
        out[2] = cam[2];
    }



    // ==================== Getters ====================

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public float dist() { return dist; }
    public float focalX() { return focalX; }
    public float focalY() { return focalY; }
    public float focalZ() { return focalZ; }
    public float focalLength() { return focalLength; }
    public float cx() { return cx; }
    public float cy() { return cy; }
    public float cosY() { return cosY; }
    public float sinY() { return sinY; }
    public float cosX() { return cosX; }
    public float sinX() { return sinX; }
    public boolean isTransitioning() { return transT < 1.0f; }

    // ==================== 内部工具 ====================

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float smoothStep(float t) {
        return t * t * (3 - 2 * t);
    }

    /**
     * 缓入急速出：前段缓慢启动，后段急速冲到目标。
     */
    private static float easeInCubic(float t) {
        return t * t * t;
    }
}
