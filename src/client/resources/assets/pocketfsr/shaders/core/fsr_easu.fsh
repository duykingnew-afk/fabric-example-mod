#version 150

// Pocket FSR - EASU (Edge Adaptive Spatial Upsampling) pass.
//
// This is a port of AMD FidelityFX Super Resolution 1.0 (ffx_fsr1.h, FsrEasuF) to GLSL 1.50.
// textureGather is not used (it needs GLSL 4.00, which phone GL translation layers often lack);
// the same 12 taps are fetched one by one with texelFetch instead. The approximate rcp/rsqrt of
// the reference are replaced by exact ones.
//
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software
// and associated documentation files (the "Software"), to deal in the Software without restriction,
// including without limitation the rights to use, copy, modify, merge, publish, distribute,
// sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions: The above copyright notice and this
// permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND. (MIT License, see LICENSE-FSR.txt)

uniform sampler2D InSampler;
uniform vec2 InSize;   // size of the low resolution input, in pixels
uniform vec2 OutSize;  // size of the output, in pixels

out vec4 fragColor;

vec3 fetchTexel(ivec2 p, ivec2 maxP) {
    return texelFetch(InSampler, clamp(p, ivec2(0), maxP), 0).rgb;
}

// Simplest multi-channel approximate luma (luma times 2).
float lumaTimes2(vec3 c) {
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

// Accumulate direction and length for one of the 4 bilinear taps.
//    a
//  b c d
//    e
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    float lenX = max(abs(lD - lC), abs(lC - lB));
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) / max(lenX, 1.0e-8), 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;

    float lenY = max(abs(lE - lC), abs(lC - lA));
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) / max(lenY, 1.0e-8), 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

// Filtering for a given tap.
void easuTap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    // Rotate offset by direction.
    vec2 v;
    v.x = (off.x * dir.x) + (off.y * dir.y);
    v.y = (off.x * -dir.y) + (off.y * dir.x);
    // Anisotropy.
    v *= len;
    // Distance^2, limited to the window.
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    // Approximation of lanczos2 without sin() or rcp() or sqrt().
    float wB = 2.0 / 5.0 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

void main() {
    ivec2 maxP = ivec2(InSize) - ivec2(1);

    // Position of 'f' in the input.
    vec2 ratio = InSize / OutSize;
    vec2 ip = floor(gl_FragCoord.xy);
    vec2 pp = ip * ratio + (0.5 * ratio - 0.5);
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 f0 = ivec2(fp);

    // 12-tap kernel.
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 tb = fetchTexel(f0 + ivec2( 0, -1), maxP);
    vec3 tc = fetchTexel(f0 + ivec2( 1, -1), maxP);
    vec3 te = fetchTexel(f0 + ivec2(-1,  0), maxP);
    vec3 tf = fetchTexel(f0 + ivec2( 0,  0), maxP);
    vec3 tg = fetchTexel(f0 + ivec2( 1,  0), maxP);
    vec3 th = fetchTexel(f0 + ivec2( 2,  0), maxP);
    vec3 ti = fetchTexel(f0 + ivec2(-1,  1), maxP);
    vec3 tj = fetchTexel(f0 + ivec2( 0,  1), maxP);
    vec3 tk = fetchTexel(f0 + ivec2( 1,  1), maxP);
    vec3 tl = fetchTexel(f0 + ivec2( 2,  1), maxP);
    vec3 tn = fetchTexel(f0 + ivec2( 0,  2), maxP);
    vec3 to = fetchTexel(f0 + ivec2( 1,  2), maxP);

    float lb = lumaTimes2(tb);
    float lc = lumaTimes2(tc);
    float le = lumaTimes2(te);
    float lf = lumaTimes2(tf);
    float lg = lumaTimes2(tg);
    float lh = lumaTimes2(th);
    float li = lumaTimes2(ti);
    float lj = lumaTimes2(tj);
    float lk = lumaTimes2(tk);
    float ll = lumaTimes2(tl);
    float ln = lumaTimes2(tn);
    float lo = lumaTimes2(to);

    // Accumulate for bilinear interpolation.
    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - pp.x) * (1.0 - pp.y), lb, le, lf, lg, lj);
    easuSet(dir, len,        pp.x  * (1.0 - pp.y), lc, lf, lg, lh, lk);
    easuSet(dir, len, (1.0 - pp.x) *        pp.y , lf, li, lj, lk, ln);
    easuSet(dir, len,        pp.x  *        pp.y , lg, lj, lk, ll, lo);

    // Normalize, and clean up close to zero.
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = inversesqrt(max(dirR, 1.0e-12));
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);

    // Transform from {0 to 2} to {0 to 1} range, and shape with square.
    len = len * 0.5;
    len *= len;

    // Stretch kernel {1.0 vert|horz, to sqrt(2.0) on diagonal}.
    float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));

    // Anisotropic length after rotation.
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);

    // Based on the amount of 'edge', the window shifts.
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;

    // Distance^2 clipping point at the end of the adjustable window.
    float clp = 1.0 / lob;

    // Min and max of the 4 nearest, used to avoid ringing.
    vec3 min4 = min(min(tf, tg), min(tj, tk));
    vec3 max4 = max(max(tf, tg), max(tj, tk));

    // Accumulation.
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    easuTap(aC, aW, vec2( 0.0, -1.0) - pp, dir, len2, lob, clp, tb);
    easuTap(aC, aW, vec2( 1.0, -1.0) - pp, dir, len2, lob, clp, tc);
    easuTap(aC, aW, vec2(-1.0,  1.0) - pp, dir, len2, lob, clp, ti);
    easuTap(aC, aW, vec2( 0.0,  1.0) - pp, dir, len2, lob, clp, tj);
    easuTap(aC, aW, vec2( 0.0,  0.0) - pp, dir, len2, lob, clp, tf);
    easuTap(aC, aW, vec2(-1.0,  0.0) - pp, dir, len2, lob, clp, te);
    easuTap(aC, aW, vec2( 1.0,  1.0) - pp, dir, len2, lob, clp, tk);
    easuTap(aC, aW, vec2( 2.0,  1.0) - pp, dir, len2, lob, clp, tl);
    easuTap(aC, aW, vec2( 2.0,  0.0) - pp, dir, len2, lob, clp, th);
    easuTap(aC, aW, vec2( 1.0,  0.0) - pp, dir, len2, lob, clp, tg);
    easuTap(aC, aW, vec2( 1.0,  2.0) - pp, dir, len2, lob, clp, to);
    easuTap(aC, aW, vec2( 0.0,  2.0) - pp, dir, len2, lob, clp, tn);

    // Normalize and dering.
    vec3 pix = (aW > 1.0e-5) ? aC / aW : tf;
    pix = min(max4, max(min4, pix));
    fragColor = vec4(pix, 1.0);
}
