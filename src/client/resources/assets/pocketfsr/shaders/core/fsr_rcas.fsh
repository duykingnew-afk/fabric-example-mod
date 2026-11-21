#version 150

// Pocket FSR - RCAS (Robust Contrast Adaptive Sharpening) pass.
//
// Port of AMD FidelityFX Super Resolution 1.0 (ffx_fsr1.h, FsrRcasF) to GLSL 1.50.
// Divisions are guarded so that pure black / pure white neighbourhoods never produce NaN
// (the reference relies on the GPU's min/max swallowing NaN, which is not guaranteed in GLSL).
//
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// MIT License, see LICENSE-FSR.txt.

uniform sampler2D InSampler;
uniform float Sharpness;  // lobe multiplier: exp2(-stops). 1.0 = sharpest, 0.25 = FSR's default feel.

out vec4 fragColor;

const float RCAS_LIMIT = 0.25 - (1.0 / 16.0);

vec3 fetchTexel(ivec2 p, ivec2 maxP) {
    return texelFetch(InSampler, clamp(p, ivec2(0), maxP), 0).rgb;
}

void main() {
    ivec2 maxP = textureSize(InSampler, 0) - ivec2(1);
    ivec2 sp = ivec2(gl_FragCoord.xy);

    //    b
    //  d e f
    //    h
    vec3 b = fetchTexel(sp + ivec2( 0, -1), maxP);
    vec3 d = fetchTexel(sp + ivec2(-1,  0), maxP);
    vec3 e = fetchTexel(sp, maxP);
    vec3 f = fetchTexel(sp + ivec2( 1,  0), maxP);
    vec3 h = fetchTexel(sp + ivec2( 0,  1), maxP);

    // Min and max of the ring.
    vec3 mn4 = min(min(b, d), min(f, h));
    vec3 mx4 = max(max(b, d), max(f, h));

    // Limiters.
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1.0e-5));
    vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1.0e-5));
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-RCAS_LIMIT, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * Sharpness;

    // Resolve.
    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 pix = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;
    fragColor = vec4(pix, 1.0);
}
