#version 150
// 云层片元着色器：逐像素光照/阴影。
// 云形状（噪声阈值）由 CPU 用 Noise3 预计算并跳过空面，保证和原版形状完全一致。

in vec3 vPos;
in vec3 vNrm;
in float vDensity;

uniform vec3 SunDir;
uniform float Intensity;
uniform float CasterCount;
uniform vec3 CasterRel0, CasterRel1, CasterRel2, CasterRel3;
uniform float CasterRad0, CasterRad1, CasterRad2, CasterRad3;

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

    // 光照（与行星同一套方程）
    float ndotl = dot(nrm, SunDir);
    float shadow = computeShadow(vPos);
    float direct = max(0.0, ndotl) * Intensity * (1.0 - shadow);
    // 夜面云也要可见：ambient 抬高，并给一个冷灰蓝的夜色云色
    float ambient = 0.40;
    float shade = ambient + (1.0 - ambient) * direct;

    // 云色：受光偏暖金，背光偏冷蓝灰（可见，而不是死黑）
    vec3 lightC = mix(vec3(0.35, 0.40, 0.55), vec3(1.00, 0.52, 0.20), clamp(direct, 0.0, 1.0));
    vec3 cloudColor = vec3(0.96, 0.97, 1.0) * lightC;
    float alpha = 0.55 * shade * vDensity;

    fragColor = vec4(cloudColor, alpha);
}
