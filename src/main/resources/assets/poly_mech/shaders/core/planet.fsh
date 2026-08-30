#version 150

// 行星 BASE 层：逐像素光照/阴影。
// 所有光源独立求和：
//   direct   = max(0, ndotl) * I * (1 - shadow)
//   bounce   = direct * 0.16
//   ambient  = 0.06 * I * SunVisibility
//   refl     = max(0, dot(nrm, parentDir)) * ReflStrength
//   lit      = direct + bounce + ambient + refl * 0.6
// 昼夜面是 max(0, ndotl) 的自然结果。

in vec3 vPos;
in vec3 vNrm;
in vec3 vAlb;

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

    // 恒星：自发光 + 边缘昏暗
    if (IsSun > 0.5) {
        float limbDot = max(0.0, dot(nrm, ViewDir));
        float limb = 0.5 + 0.5 * pow(limbDot, 1.4);
        fragColor = vec4(alb * limb, 1.0);
        return;
    }

    /************ 标准光照 ************/
    float ndotl = dot(nrm, SunDir);
    float shadow = computeShadow(vPos);

    float direct = max(0.0, ndotl) * Intensity * (1.0 - shadow);
    float bounce = direct * 0.16;
    float ambient = 0.06 * Intensity * SunVisibility;

    float refl = 0.0;
    if (ReflStrength > 0.0 && length(ParentRel) > 1e-5) {
        vec3 rdir = normalize(ParentRel - vPos);
        refl = max(0.0, dot(nrm, rdir)) * ReflStrength;
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

    fragColor = vec4(min(col, vec3(1.0)), 1.0);
}
