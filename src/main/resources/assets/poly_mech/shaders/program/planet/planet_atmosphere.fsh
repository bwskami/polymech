#version 410

uniform sampler2D DepthSampler;
uniform sampler2D SpaceDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

uniform mat4 iProjMat;
uniform mat4 iModelViewMat;
uniform vec3 CameraPos;

uniform float Exposure;
uniform int StepCount;

uniform int useMinecraftDepth;

const float EPS = 1.0e-6; //容差

struct Star {
    vec3 Pos;   //恒星位置
    vec3 RealPos;   //恒星真实位置
    vec4 Color; //恒星颜色（RGBA）
    float R;    //恒心半径
};
struct Planet {
    vec3 Pos;   //行星位置
    float g; //表面重力加速度
    vec3 RealPos;   //行星真实位置
    float R;    //行星半径
    float AtmosphericHeight; //大气高度
    float RealAtmosphericHeight; //大气真实高度
    float AtmosphericTemperature; //大气温度
    float AtmosphericMolarMass; //气体摩尔质量
    float AtmosphericSeaLevelDensity; //海平面密度
    vec4 AtmosphericColor;   //大气颜色
};
struct BlackHole {
    vec3 Pos;   //黑洞位置
    vec3 RealPos;   //黑洞真实位置
    float R;    //黑洞半径
    float Mass; //黑洞质量
};
layout(std140) uniform CelestialBodyData {
    int StarCount;
    Star starlist[16];

    int PlanetCount;
    Planet planetlist[64];

    int BlackHoleCount;
    BlackHole blackholelist[16];
};

//球射线相交
struct IntersectionData {
    vec3 nearPoint;
    vec3 farPoint;
};
bool intersectSphere(vec3 ro, vec3 rd, vec3 c, float r, out float t0, out float t1) {
    vec3 oc = ro - c;
    float b = dot(rd, oc);
    float cval = dot(oc, oc) - r*r;
    float disc = b*b - cval;
    float s = sqrt(max(disc, 0.0));
    t0 = -b - s;
    t1 = -b + s;
    return (disc > EPS) && (t1 > EPS);
}
IntersectionData getIntersectSphereShellData(vec3 ro, vec3 rd, vec3 c, float innerR, float outerR) {
    IntersectionData res;
    res.nearPoint = vec3(0.0);
    res.farPoint  = vec3(0.0);
    float to0, to1, ti0, ti1;
    bool hitOuter = intersectSphere(ro, rd, c, outerR, to0, to1);
    bool hitInner = intersectSphere(ro, rd, c, innerR, ti0, ti1);
    float valid = float(hitOuter);
    //整个外球段
    float useOuterOnly = float(hitOuter && !hitInner);
    //第一次壳段
    float useOuterMinusInner = float(hitOuter && hitInner);
    //起点是否在内球内
    float insideInner = float(length(ro - c) < innerR);
    float nearA = max(to0, EPS);
    float farA  = to1;
    float nearB1 = max(to0, EPS);
    float farB1  = ti0;
    float nearB2 = ti1;
    float farB2  = to1;
    //选择
    float nearB = mix(nearB1, nearB2, insideInner);
    float farB  = mix(farB1, farB2, insideInner);
    float nearT = nearA * useOuterOnly + nearB * useOuterMinusInner;
    float farT  = farA  * useOuterOnly + farB * useOuterMinusInner;
    res.nearPoint = ro + rd * nearT * valid;
    res.farPoint  = ro + rd * farT  * valid;
    return res;
}

//遮挡检测
bool Occlusion(vec3 Origin, vec3 LightPos, Planet planet) {
    vec3 Dir = normalize(LightPos - Origin);
    float t = dot(planet.Pos - Origin, Dir);
    return (t >= 0 && t <= length(LightPos - Origin)) && (length(planet.Pos - Origin - t * Dir) <= planet.R);
}

//散射
float computeAtmosphericAlpha(Planet planet, float Height) {
    float exponent = (planet.AtmosphericMolarMass * planet.g * Height) / (8.314f * planet.AtmosphericTemperature);
    //瑞利散射
    float RayIntensity = pow(planet.AtmosphericSeaLevelDensity * exp(-exponent), 0.06);
    //米氏散射
    float MieIntensity = Height < 1200 ? 10 * exp(-Height / 12000) : 0;

    return (RayIntensity + MieIntensity) / 943943;
}

//色散
vec3 computeDispersionColor(vec4 AtmosphereColor, vec3 light, vec3 normal) {
    float a = 1 - dot(light, normal);
    a = a * a * a * a * a * a;
    return AtmosphereColor.rgb * (1 - a) + vec3(a * 0.1, a * 0.095, 0);
}

//随机数
float rand(vec2 co) { return fract(sin(dot(co, vec2(127.1, 311.7))) * 43758.5453 + sin(dot(co, vec2(269.5, 183.3))) * 12345.6789); }

//屏幕坐标到世界坐标
vec3 ScreenToWorld(vec2 screenPos) {
    vec4 view = iProjMat * vec4(screenPos * 2.0 - 1.0, max((1 - texture(DepthSampler, screenPos).r) * useMinecraftDepth, texture(SpaceDepthSampler, screenPos).r) * 2.0 - 1.0, 1.0);
    view.w = max(view.w, 1.0e-16f);
    view /= view.w;
    return vec3(vec4(iModelViewMat * view).xyz);
}

//精度补偿函数
float fittedY(float x) {
    return pow(max(x - 512, 0), 0.95);
}

void main() {
    // ★★★ 世界几何遮挡（2026-09-30 第十二轮：改用 space 的结构，判据本身保持"投影无关"）
    //
    //   判据只问一件事：**这一像素上 MC 世界画了几何体吗？**
    //   画了 ⇒ 它一定比天体近，大气绝对不许再往这一像素上画。
    //   依据：太空维度里世界几何体最远也就到渲染距离（≤512 格），而任何天体压缩后都
    //   ≥ NEAR = 16384 米（更别说未压缩时的真实米数）⇒ 世界永远更近。
    //
    //   ⚠ 这里曾经是 `DepthSampler < SpaceDepthSampler`（拿主深度与"太空底"比大小）。
    //   那条判据是**假的**，因为两张深度来自**两套投影**：星球走 spaceProj
    //   （near=1000m / far=524288m），世界走 MC 主投影（near=0.05 / far=768），
    //   同一个距离在两边差着数量级。实测（离线 DepthOcclusionProbe，地球 50853 压缩米）：
    //       星球深度 0.98221，而 5 格处的方块是 0.99006 —— 方块"看起来更远"
    //       ⇒ 判据不触发 ⇒ 大气整块盖到物理体上。临界距离只有 2.80 格。
    //   （换回压缩启用前的 far=1e13 时临界距离是 2.53 格 ⇒ **不是**距离压缩引入的回归，
    //     旧判据从一开始就只对贴脸的东西成立。）
    //
    //   ★ 第十二轮起 `mainDepth < 1.0` 之所以成立，不再靠"画完把主深度清一遍"那种补丁，
    //   而是**结构上**成立：天体改画进独立的 spaceRenderTarget（space 的做法），
    //   主深度从头到尾只属于 MC 世界。同时两套投影已对表（SpaceDepthFarMixin 把
    //   getDepthFar() 抬到 FAR×2，天体投影取 (FAR×2, 0.05) ⇒ 反向 Z），
    //   于是 `1 - mainDepth` 与 `spaceDepth` 是**精确镜像**（都等于 0.05/z）。
    //   本判据因此可以等价地写成 space 的 `1 - mainDepth > spaceDepth`；
    //   这里保留更简单的形式（少绑一张纹理、少一次比较，两者在本项目几何下等价）。
    //   SpaceDepthSampler 仍只用于下面 ScreenToWorld 重建星球表面位置
    //   （useMinecraftDepth=1，取"世界与天体里更近的那个表面"）。
    if (texture(DepthSampler, texCoord).r < 1.0 - 1.0e-7) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 Ray = ScreenToWorld(texCoord);
    float RayLength = length(Ray);

    vec4 brightness = vec4(0, 0, 0, 0);

    for (int i = 0; i < PlanetCount; i++) {
        Planet planet = planetlist[i];
        vec4 light = vec4(0, 0, 0, 0);

        IntersectionData AtmosphereIntersection = getIntersectSphereShellData(vec3(0), normalize(Ray), planet.Pos, planet.R, planet.R + planet.AtmosphericHeight);

        if (planet.AtmosphericHeight <= 0 || dot(AtmosphereIntersection.farPoint - AtmosphereIntersection.nearPoint, AtmosphereIntersection.farPoint - AtmosphereIntersection.nearPoint) <= EPS) continue;

        float LastLightLength = 0;
        vec3 LastSamplePos = vec3(0);
        for (int j = 0; j < StepCount; j++) {
            vec3 SamplePos = mix(AtmosphereIntersection.nearPoint, AtmosphereIntersection.farPoint, pow(float(j) / StepCount, 0.5));
            float LightLength = length(SamplePos);
            vec3 normal = normalize(SamplePos - planet.Pos);
            float h = (distance((SamplePos + LastSamplePos) / 2, planet.Pos) - planet.R) / (planet.AtmosphericHeight / planet.RealAtmosphericHeight);
            LastSamplePos = SamplePos;
            float RealSetpLightLenght = (LightLength - LastLightLength) / (planet.AtmosphericHeight / planet.RealAtmosphericHeight);
            LastLightLength = LightLength;

            float data_save = RealSetpLightLenght * computeAtmosphericAlpha(planet, h);
            for (int x = 0; x < StarCount; x++) if (!Occlusion(SamplePos, starlist[x].RealPos, planet) && LightLength < RayLength + fittedY(LightLength)) light += vec4(computeDispersionColor(planet.AtmosphericColor, normalize(starlist[x].RealPos - SamplePos), normal), 1) * data_save;
        }

        light.r = min(planet.AtmosphericColor.r * 1.25, light.r);
        light.g = min(planet.AtmosphericColor.g * 1.25, light.g);
        light.b = min(planet.AtmosphericColor.b * 1.25, light.b);

        brightness += light;
    }

    fragColor = vec4(brightness) * 2 / pow(StepCount, 0.125) * Exposure;
}