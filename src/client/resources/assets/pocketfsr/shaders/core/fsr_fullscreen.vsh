#version 150

// Full-screen quad. The quad is submitted directly in clip space, so no matrices are needed.
in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
