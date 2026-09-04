#version 410

// 恒星泛光 + 镜头星芒（屏幕空间后处理）。
//
// 与 planet_atmosphere.fsh 共用 CelestialBodyData UBO 与同一套坐标约定：
// 天体位置是「相机相对的真实坐标（米）」，相机位于原点。
//
// 每颗恒星叠加两项：
//   glow   —— 像素视线到恒星表面的垂直距离衰减，形成柔和的球状光晕；
//   streak —— ScatterCount 条方向线各带一个伪随机长度系数，形成镜头星芒。
//
// ============================================================================
// 深度只用来判定「这个像素是不是 MC 几何体」，不用来反解距离。
//
// space mod 能拿深度直接算世界距离，靠的是让两套投影共用同一对 near/far：
// mixin 把 getDepthFar 改成 FarCompress * 4 = 4194304，MC 主投影是
// setPerspective(near=0.05, far=4194304)，它自己的太空投影是
// setPerspective(near=4194304, far=0.05) —— 数值相同、Z 方向翻转，
// 于是 1 - mainDepth 和 spaceDepth 都等于 0.05/d，才能写成 max() 统一取值
// （见 space 的 star_bloom.fsh:63 与 buffer_switch.fsh:28 的 gl_FragDepth）。
// 再加 PositionCompression 把 [16384,∞) 压进 [16384,1048576)、远平面取 4 倍压缩上限，
// 保证天空一定反解到比任何天体都远，距离阈值才有意义。
//
// 本项目一样都没有：主深度是混合投影的 —— 星球在 AFTER_SKY 用 spaceProj
// (near=1000m / far=1e13m) 写入，地形/实体/粒子随后用 MC 自己的投影写入，
// 两者区间互相重叠（地球 8.88e6m → 0.9998874，443m 处的方块 → 0.9998871），
// 单看一个深度值分不出来源，反解出来的距离是假的。拿它做距离阈值的结果就是
// 整颗恒星的泛光被随机剔掉 —— 之前实机「一闪一闪」的真凶之一。
//
// 所以 SpaceRenderer 在星球层画完、MC 地形还没开始画之前额外留了一份深度底
// （SkyDepthSampler）。两份一比就是无歧义的 MC 几何体掩码：
// depthNow < skyDepth 当且仅当该像素有 MC 几何体通过深度测试、真正画了上去
// （画在星球后面的方块会被深度测试挡掉、两份相等，正确地不算遮挡）。
//
// 遮挡只做逐像素一级：本像素是 MC 几何体就不叠泛光，没被挡住的像素照常叠加。
// 于是方块和玩家会在光晕/星芒上剪出自己的轮廓 —— 挡住的部分消失，没挡住的部分露出来。
//
// 这里刻意不做 space mod star_bloom.fsh:95 那种「采样恒星中心那一点、被挡就整颗 continue」
// 的逐恒星判定：它把遮挡变成了全有全无 —— 玩家只盖住太阳一小块，整道星芒就全灭了，
// 而剩下本该露出来的部分也一起没了。space mod 那么写是因为它那一条距离阈值
// 同时承担了行星遮挡与 MC 几何遮挡两件事，没有别的机制；
// 本项目的行星遮挡已经由 StarVisibility 用 smoothstep 平滑处理，不需要再叠一层硬开关。
//
// 行星遮挡不走深度，仍然是 StarVisibility 的角空间解析判定：行星同时存在于两份
// 深度里且完全相等，掩码天然为 0；而解析判定在轮廓处是 smoothstep 过渡，
// 不会像深度那样在边缘硬 pop。
// ============================================================================

uniform sampler2D DiffuseSampler;
//AFTER_PARTICLES 时的主深度：天空/星球/地形/实体/粒子全在里面，投影不统一。
uniform sampler2D DepthSampler;
//AFTER_SKY 星球层画完时的主深度底，只含天空（1.0）与星球。
uniform sampler2D SkyDepthSampler;

in vec2 texCoord;

out vec4 fragColor;

uniform mat4 tProjMat;
uniform mat4 tModelViewMat;
uniform mat4 iProjMat;
uniform mat4 iModelViewMat;
uniform vec2 OutSize;
uniform int ScatterCount;
uniform float Exposure;
uniform float GlowStrength;
uniform float StreakStrength;
uniform float StreakWidth;
uniform float StreakFalloff;
uniform float StreakJitter;

const float PI = 3.14159265358979323846;

struct Star {
    vec3 Pos;   //恒星位置
    vec3 RealPos;   //恒星真实位置
    vec4 Color; //恒星颜色（RGBA）
    float R;    //恒星半径
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

//屏幕 UV → 视线方向。
//透视投影下方向只由 UV 决定、与深度无关，所以固定取 z_ndc = 0 反解。
vec3 ScreenToDir(vec2 screenPos) {
    vec4 view = iProjMat * vec4(screenPos * 2.0 - 1.0, 0.0, 1.0);
    float w = view.w;
    view /= (w < 0.0 ? -1.0 : 1.0) * max(abs(w), 1.0e-16f);
    return normalize(vec3(iModelViewMat * view));
}

//天体坐标 → 屏幕 UV；在相机后方时返回 false。
//只用来做「移出画面时渐隐」，不参与任何遮挡判定。
bool WorldToScreen(vec3 worldPos, out vec2 screenUv) {
    vec4 clip = tProjMat * tModelViewMat * vec4(worldPos, 1.0);
    screenUv = vec2(0.0);
    if (clip.w <= 1.0e-4f) return false;
    screenUv = (clip.xy / clip.w) * 0.5 + 0.5;
    return true;
}

//天体的角半径（弧度）。相机在天体内部时返回 PI，等价于「整个天空都是它」。
float AngularRadius(vec3 pos, float radius) {
    float dist = length(pos);
    if (dist <= radius) return PI;
    return asin(clamp(radius / dist, 0.0, 1.0));
}

// 恒星被行星挡住的解析判定，返回 [0,1] 的可见度。
//
// 判定放在角空间（比方向夹角与两者角半径之和），不在屏幕空间比圆盘：
// 屏幕空间的做法在行星中心接近视锥边缘时会彻底失真 —— clipW→0 让行星的 UV
// 和屏幕半径同时爆炸，而两者爆炸的比例并不一致，于是「行星圆盘是否盖住恒星」
// 这个布尔结论会随视角微小转动在 0/1 之间来回跳，整颗恒星的泛光跟着 pop。
// 角空间没有投影、没有除法奇点，输入全是 UBO 里的确定值，同一帧全屏一致。
// 循环对全屏幕所有像素走同样的分支，没有 warp divergence。
float StarVisibility(vec3 starDir, float starAngularRadius, float starDist) {
    float visible = 1.0;
    // 过渡带至少给到恒星的角半径，避免轮廓处出现硬边。
    float soft = max(starAngularRadius, 1.0e-5f);
    for (int k = 0; k < PlanetCount; k++) {
        Planet planet = planetlist[k];
        if (planet.R <= 0.0) continue;
        float planetDist = length(planet.Pos);
        // 行星必须比恒星近才挡得住
        if (planetDist >= starDist) continue;
        vec3 planetDir = planet.Pos / max(planetDist, 1.0e-6f);
        float angle = acos(clamp(dot(starDir, planetDir), -1.0, 1.0));
        float planetAngularRadius = AngularRadius(planet.Pos, planet.R);
        visible *= smoothstep(planetAngularRadius - soft, planetAngularRadius + soft, angle);
        if (visible <= 0.0) break;
    }
    return visible;
}

//space mod 原版的伪随机：输入是方向线的角度，给每条线一个稳定的随机长度系数。
//不用常见的 hash11：实算下来 space 这个式子在 16 条线上的 falloff 极差是 1.80 倍，
//hash11(float(j)+0.3183) 只有 1.62 倍，射线长短差异明显更弱。
float rand(float angle) {
    return (cos((sin(angle * 1234.5678) + cos(angle / 114.514)) * 13.78) + 1.0) / 2.0;
}

//两份深度快照的比对容差。glBlitFramebuffer 对同格式深度是位精确拷贝，
//没有 MC 几何体写入的像素两份完全相等；1e-6 远小于任何真实几何体造成的深度差
//（500m 处的方块与天空之间就差了约 1e-4），只是用来挡驱动层的舍入噪声。
const float DEPTH_EPS = 1.0e-6;

//该像素是否有 MC 几何体（方块 / 实体 / 粒子）真正画了上去。
//对星球恒为 false —— 星球在两份快照里深度完全相同。
bool MinecraftOccluder(vec2 screenPos) {
    return texture(DepthSampler, screenPos).r < texture(SkyDepthSampler, screenPos).r - DEPTH_EPS;
}

void main() {
    vec4 base = texture(DiffuseSampler, texCoord);

    // 逐像素遮挡：本像素被 MC 几何体占着就不叠泛光，没被占着的像素照常叠加。
    // 遮挡因此是部分的：方块和玩家只在光晕/星芒上剪出自己的轮廓。
    // 这也和 3D 日冕的表现一致 —— drawSunGlows 深度测试开启，随后地形画上去就把它盖住了。
    if (MinecraftOccluder(texCoord)) {
        fragColor = vec4(base.rgb, 1.0);
        return;
    }

    vec3 rayDir = ScreenToDir(texCoord);

    vec3 bloom = vec3(0.0);

    for (int i = 0; i < StarCount; i++) {
        Star star = starlist[i];
        if (star.R <= 0.0) continue;

        // 恒星必须在相机前方
        vec2 starScreen;
        if (!WorldToScreen(star.Pos, starScreen)) continue;

        // 移出画面时渐隐。space mod 原版是 star_pos 一出 [0,1] 就整颗 continue，
        // 转视角时能看到光晕和星芒突然全部消失；这里在 [-0.25,0] / [1,1.25] 平滑淡出。
        vec2 fadeIn = smoothstep(vec2(-0.25), vec2(0.0), starScreen);
        vec2 fadeOut = 1.0 - smoothstep(vec2(1.0), vec2(1.25), starScreen);
        float edgeFade = fadeIn.x * fadeIn.y * fadeOut.x * fadeOut.y;
        if (edgeFade <= 0.0) continue;

        float starDist = length(star.Pos);
        vec3 starDir = star.Pos / max(starDist, 1.0e-6f);

        // 行星遮挡：角空间解析判定，不读深度、不比屏幕圆盘
        float visibility = StarVisibility(starDir, AngularRadius(star.Pos, star.R), starDist);
        if (visibility <= 0.0) continue;

        // 光晕：视线到恒星表面的垂直距离，按恒星半径归一化后反比衰减
        float glowPerp = length(rayDir * max(dot(star.Pos, rayDir), 0.0) - star.Pos);
        float glow = 1.0 / (1.0 + pow(glowPerp / star.R, 1.35));

        // 星芒：ScatterCount 条过恒星中心的方向线（覆盖半圆，双向延长共 2N 道射线），
        // 每条线一个随机长度系数，像素归属于离它最近的那一条。
        //
        // 这里刻意保留 space mod 原版的 argmin（离散归属），不做相邻线之间的插值：
        // 插值会把每条线的随机长度在角平分线附近抹平成两者的均值，长短射线的硬边界跟着消失，
        // 整体退化成一圈平滑的径向模糊 —— 看上去就是「每条射线都一样长、分布特别平均」。
        // 离散归属的代价只是在角平分线上、且恒星屏幕位置亚像素移动时会有一道细线闪一下，
        // 远轻于整颗恒星 pop 掉，而且 space mod 本身就是这个观感。
        //
        // minLength 初值取 1.0（不是无穷大）也是照抄原版：它同时充当距离截断，
        // 离恒星超过 1.0 UV 的像素 exp(-1.0 * StreakWidth) 已经是 0，不会贡献任何亮度。
        vec2 relative = (starScreen - texCoord) * vec2(OutSize.x / OutSize.y, 1.0);
        float relativeLength = length(relative);
        float angleStep = PI / float(max(ScatterCount, 1));
        float minLength = 1.0;
        float minAngle = 0.0;
        for (int j = 0; j < ScatterCount; j++) {
            float angle = angleStep * float(j);
            vec2 direction = vec2(sin(angle), cos(angle));
            float perp = abs(relative.x * direction.y - relative.y * direction.x);
            // 用 <= 而不是 <，与原版一致：并列时归给索引更大的那条线
            if (perp <= minLength) {
                minLength = perp;
                minAngle = angle;
            }
        }
        float falloff = StreakFalloff + StreakJitter * (rand(minAngle) * 2.0 - 1.0);
        float streak = exp(-minLength * StreakWidth) * exp(-relativeLength * falloff);

        bloom += star.Color.rgb * (glow * GlowStrength + streak * StreakStrength) * edgeFade * visibility;
    }

    fragColor = vec4(base.rgb + bloom * Exposure, 1.0);
}
