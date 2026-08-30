#version 150
// 大气层片元着色器：逐像素 rim + 日照 + 阴影。

in vec3 vPos;
in vec3 vNrm;

uniform vec3 SunDir;
uniform vec3 ViewDir;
uniform float Intensity;
uniform float IsSun;
uniform float AtmoInner;
uniform float CasterCount;
uniform vec3 CasterRel0, CasterRel1, CasterRel2, CasterRel3;
uniform float CasterRad0, CasterRad1, CasterRad2, CasterRad3;
uniform vec3 AtmoColor;

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

    // Rim：按视线与大气层的几何关系——行星边缘最亮，向外渐隐到 0
    float NdotV = max(0.0, dot(nrm, ViewDir));
    float d = sqrt(max(0.0, 1.0 - NdotV * NdotV)); // 视线到球心距离 / R
    float inner = max(AtmoInner, 0.001); // 行星半径 / 大气半径
    float rim;
    if (d > inner) {
        float t = (d - inner) / (1.0 - inner); // 0=行星边缘, 1=大气外缘
        rim = pow(1.0 - t, 1.5); // 平缓衰减：边缘逐渐变透明，过渡可见
    } else {
        float t = d / inner; // 0=中心, 1=行星边缘
        rim = t * t;
    }

    // 日照：朝阳面亮
    float sunDot = max(0.0, dot(nrm, SunDir));
    float shadow = computeShadow(vPos);
    float sunLift = pow(sunDot, 0.75);

    float alpha;
    if (IsSun > 0.5) {
        alpha = rim * 0.45;
    } else {
        float sunF = Intensity * (1.0 - shadow * sunDot);
        alpha = rim * sunF * (0.14 + 0.44 * sunLift);
    }
    if (alpha < 0.003) discard;

    fragColor = vec4(AtmoColor, alpha);
}
