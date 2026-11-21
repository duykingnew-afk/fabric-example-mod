#version 150

// Pocket FSR - plain bilinear stretch of the low resolution picture (the "Bilinear" mode).

uniform sampler2D InSampler;
uniform vec2 OutSize;  // size of the output, in pixels

out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / OutSize;
    fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
}
