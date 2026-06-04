#version 300 es

in vec2 aPosition;
in vec2 aTexCoord;

out vec2 vTexCoord;

void main() {
    // Flip Y: AUSBC delivers rows top-down; GL origin is bottom-left
    vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
