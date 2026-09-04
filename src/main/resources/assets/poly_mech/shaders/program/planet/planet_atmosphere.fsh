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