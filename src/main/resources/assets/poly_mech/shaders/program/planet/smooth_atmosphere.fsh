#version 150

uniform sampler2D DiffuseSampler;
uniform sampler2D MainScreenSampler;
uniform vec2 ScreenSize;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texelSize = 1.0 / ScreenSize;

    vec4 result = vec4(0.0);

    for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) result += texture(DiffuseSampler, (texCoord  + vec2(x, y) * texelSize));

    fragColor = result / 25.0 * (texture(DiffuseSampler, texCoord).a == 0 ? 0 : 1) + texture(MainScreenSampler, texCoord);
    fragColor.a = 1;
}