#version 150
// 云层顶点着色器：只做变换和传递。云形状由 CPU Noise3 预判，光照/阴影在片元着色器算。

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vPos;
out vec3 vNrm;
out float vDensity;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vPos = Position;
    vNrm = normalize(Normal);
    vDensity = Color.r; // 云密度阈值（通过顶点色传递）
}
