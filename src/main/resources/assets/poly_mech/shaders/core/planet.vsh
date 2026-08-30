#version 150

// 行星 BASE 层：顶点着色器只做变换和变元传递。
// 光照/阴影在 planet.fsh 逐像素计算 —— 阴影边缘不受底图三角形大小限制。

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vPos;
out vec3 vNrm;
out vec3 vAlb;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vPos = Position;
    vNrm = normalize(Normal);
    vAlb = Color.rgb;
}
