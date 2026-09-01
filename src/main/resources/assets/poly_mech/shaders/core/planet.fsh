#version 150

// 行星 BASE 层：逐像素光照/阴影 + 环影 + 云影 + 镜面高光 + ACES 色调映射。

in vec3 vPos;
in vec3 vNrm;
in vec3 vAlb;
in float vSpec;

uniform vec3 ViewDir;
uniform vec3 SunDir;
uniform float Intensity;
uniform float IsSun;
uniform float SunVisibility;
uniform float CasterCount;
uniform vec3 CasterRel0;
uniform vec3 CasterRel1;
uniform vec3 CasterRel2;
uniform vec3 CasterRel3;
uniform float CasterRad0;
uniform float CasterRad1;
uniform float CasterRad2;
uniform float CasterRad3;
uniform vec3 ParentRel;
uniform float ReflStrength;

uniform float SpecularStrength;
uniform float SpecularPower;
uniform float RingInner;
uniform float RingOuter;
uniform float RingShadowStrength;

out vec4 fragColor;

vec3 casterRel(int i) {
    if (i == 0) return CasterRel0;
    if (i == 1) return CasterRel1;
    if (i == 2) return CasterRel2;
    return CasterRel3;
}
float casterRad(int i) {
    if (i == 0) return CasterRad0;
    if (i == 1) return CasterRad1;
    if (i == 2) return CasterRad2;
    return CasterRad3;
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

// 阴影锥：纯几何遮挡 —— "这个像素能否看到恒星"。
float computeShadow(vec3 rel) {
    float maxShadow = 0.0;
    int n = int(CasterCount + 0.5);
    for (int i = 0; i < n; i++) {
        vec3 dx = rel - casterRel(i);
        float dotSun = dot(dx, SunDir);
        if (dotSun > 0.0) continue;
        vec3 perp = dx - dotSun * SunDir;
        float perpDist = length(perp);
        float effR = casterRad(i) * (1.0 + abs(dotSun) * 0.025);
        if (perpDist < effR) {
            maxShadow = max(maxShadow, 1.0);
        } else if (perpDist < effR * 1.6) {
            maxShadow = max(maxShadow, 1.0 - (perpDist - effR) / (effR * 0.6));
        }
    }
    return maxShadow;
}

void main() {
    vec3 nrm = normalize(vNrm);
    vec3 alb = vAlb;

    // 恒星：自身就是光源，HDR 亮度经 ACES 映射，中心灼白、边缘暖橙
    if (IsSun > 0.5) {
        // 恒星 = unlit 自发光表面：什么颜色就发射什么光，不施加任何外部光照。
        // 辉光由大气层 rim 和外圈 glow billboard 负责（类似 Mindustry 的 bloom 层）。
        // 直接输出底图颜色：ACES/乘法会把橙金色推向白色（纯度下降）。
        // 恒星表面是 unlit 自发光，辉光交给大气层 rim 和外圈 glow billboard。
        fragColor = vec4(alb, 1.0);
        return;
    }

    /************ 标准光照 ************/
    float ndotl = dot(nrm, SunDir);
    float shadow = computeShadow(vPos);

    // 环影：行星环在表面投下的阴影（环平面 y=0，沿太阳方向射线求交）
    if (RingShadowStrength > 0.0 && abs(SunDir.y) > 1e-4) {
        float t = -vPos.y / SunDir.y;
        if (t > 0.0) {
            vec3 q = vPos + SunDir * t;
            float r = length(q.xz);
            if (r > RingInner && r < RingOuter) {
                shadow = max(shadow, RingShadowStrength);
            }
        }
    }

    float direct = max(0.0, ndotl) * Intensity * (1.0 - shadow);
    float bounce = direct * 0.16;
    float ambient = 0.06 * Intensity * SunVisibility;

    float refl = 0.0;
    if (ReflStrength > 0.0 && length(ParentRel) > 1e-5) {
        vec3 rdir = normalize(ParentRel - vPos);
        refl = max(0.0, dot(nrm, rdir)) * ReflStrength;
    }

    // 镜面高光（海洋/冰面）
    float spec = 0.0;
    if (SpecularStrength > 0.0) {
        vec3 V = normalize(ViewDir);
        vec3 H = normalize(SunDir + V);
        spec = pow(max(dot(nrm, H), 0.0), SpecularPower) * SpecularStrength * vSpec * Intensity * (1.0 - shadow);
    }

    float lit = clamp(direct + bounce + ambient + refl * 0.6, 0.0, 1.4);
    float t = clamp(lit, 0.0, 1.0);
    vec3 lightC = mix(vec3(0.12, 0.14, 0.18), vec3(1.00, 0.52, 0.20), t);

    float maxC = max(alb.r, max(alb.g, alb.b));
    float minC = min(alb.r, min(alb.g, alb.b));
    float sat = maxC > 1e-5 ? (maxC - minC) / maxC : 0.0;
    float tint = 0.25 + (0.85 - 0.25) * sat;

    vec3 tinted = alb * lightC;
    float ll = (lightC.r + lightC.g + lightC.b) / 3.0;
    vec3 neutral = alb * ll;
    vec3 col = neutral + (tinted - neutral) * tint;
    col += spec * lightC;

    fragColor = vec4(aces(col * 1.25), 1.0);
}
