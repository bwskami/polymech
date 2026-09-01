#version 150

in vec2 vUV;
in vec4 vColor;

out vec4 fragColor;

void main() {
    vec2 p = (vUV - 0.5) * 2.0;
    float d = length(p);

    // 中心光晕
    float glow = exp(-d * 2.2);
    // 六条衍射星芒
    float spikes = 0.0;
    for (int i = 0; i < 6; i++) {
        float ang = 3.14159265 * float(i) / 6.0;
        vec2 dir = vec2(cos(ang), sin(ang));
        float along = dot(p, dir);
        float perp = abs(dot(p, vec2(-dir.y, dir.x)));
        float ray = exp(-perp * 18.0) * exp(-along * along * 0.22);
        spikes += ray;
    }
    float core = exp(-d * 10.0);

    float a = clamp(glow * 0.55 + spikes * 1.15 + core, 0.0, 1.0);
    fragColor = vec4(vColor.rgb, a);
}
