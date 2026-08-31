#version 150

// 岩石实例化：静态网格 + per-instance 矩阵/颜色，光照在顶点着色器里算（太阳在原点）。
in vec3 Position;
in vec4 Color;
in vec3 Normal;
in mat4 InstanceMat;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vColor;

void main() {
    mat4 inst = InstanceMat;
    vec4 worldPos = inst * vec4(Position, 1.0);
    gl_Position = ProjMat * ModelViewMat * worldPos;

    vec3 nrm = normalize(mat3(inst) * Normal);
    vec3 sunDir = normalize(-worldPos.xyz);
    float len = length(worldPos.xz);
    float intensity = clamp(2.0 / (1.0 + len * 0.012), 0.35, 1.1);
    float ndotl = max(dot(nrm, sunDir), 0.0);
    float lit = 0.14 + 0.86 * ndotl * intensity;
    lit = max(0.25, lit);
    vColor = vec4(Color.rgb * lit, 1.0);
}
