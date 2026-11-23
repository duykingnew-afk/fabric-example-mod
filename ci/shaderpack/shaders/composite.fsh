#version 120

// A tiny post-processing pass for the CI test (not a real shader pack): it reads the scene colour and
// the depth buffer, so it shows whether both have the size of the pass it draws into.

varying vec2 texcoord;

uniform sampler2D colortex0;
uniform sampler2D depthtex0;

/* DRAWBUFFERS:0 */
void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	float depth = texture2D(depthtex0, texcoord).r;

	// warm tint, so a working pass is obvious
	color *= vec3(1.10, 0.92, 0.72);

	// depth fog on the sky and the far distance (the sky has depth 1.0)
	float fog = smoothstep(0.9990, 1.0, depth);
	color = mix(color, vec3(0.78, 0.62, 0.45), fog * 0.6);

	// vignette in normalised coordinates: it is off-centre if the textures and the pass disagree on the size
	vec2 uv = texcoord - 0.5;
	color *= 1.0 - dot(uv, uv) * 0.9;

	gl_FragData[0] = vec4(color, 1.0);
}
