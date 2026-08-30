#version 150
// 大气层顶点着色器：只变换和传递，逐像素 rim/阴影在片元着色器算。

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vPos;
out vec3 vNrm;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vPos = Position;
    vNrm = normalize(Normal);
}
