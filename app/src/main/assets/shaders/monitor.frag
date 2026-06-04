#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

// ── Textures ────────────────────────────────────────────────────────────────
uniform sampler2D      uFrameY;     // live NV21 luma plane
uniform sampler2D      uFrameVU;    // live NV21 interleaved chroma plane
uniform sampler2D      uOnionTex;   // reference frame for onion skin
uniform sampler2D      uFalseLUT;   // 256×1 luma→colour false-colour ramp
uniform lowp sampler3D uLut3d;      // 33×33×33 colour-grading LUT (.cube)

// ── Feature flags ────────────────────────────────────────────────────────────
uniform bool uFalseColorEnabled;
uniform bool uPeakingEnabled;
uniform bool uLutEnabled;
uniform bool uOnionEnabled;

// ── Parameters ───────────────────────────────────────────────────────────────
uniform float uPeakThreshold;   // Sobel magnitude cutoff   (default 0.08)
uniform vec3  uPeakColor;       // highlight tint            (default green)
uniform float uLutStrength;     // LUT mix weight            (0 – 1)
uniform float uOnionOpacity;    // ghost frame opacity       (0 – 1)
uniform vec2  uTexelSize;       // 1/frameWidth, 1/frameHeight

// ── Helpers ──────────────────────────────────────────────────────────────────
float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 sampleFrame(vec2 uv) {
    float y = 1.1643 * (texture(uFrameY, uv).r - 0.0625);
    vec2 vu = texture(uFrameVU, uv).rg - vec2(0.5);
    return clamp(vec3(
        y + 1.5958 * vu.x,
        y - 0.8129 * vu.x - 0.3917 * vu.y,
        y + 2.0170 * vu.y
    ), 0.0, 1.0);
}

// 3×3 Sobel gradient magnitude on the luma channel
float sobelMag(vec2 uv) {
    vec2 t = uTexelSize;
    float tl = texture(uFrameY, uv + vec2(-t.x,  t.y)).r;
    float tm = texture(uFrameY, uv + vec2( 0.0,  t.y)).r;
    float tr = texture(uFrameY, uv + vec2( t.x,  t.y)).r;
    float ml = texture(uFrameY, uv + vec2(-t.x,  0.0)).r;
    float mr = texture(uFrameY, uv + vec2( t.x,  0.0)).r;
    float bl = texture(uFrameY, uv + vec2(-t.x, -t.y)).r;
    float bm = texture(uFrameY, uv + vec2( 0.0, -t.y)).r;
    float br = texture(uFrameY, uv + vec2( t.x, -t.y)).r;
    float gx = -tl - 2.0*ml - bl + tr + 2.0*mr + br;
    float gy = -tl - 2.0*tm - tr + bl + 2.0*bm + br;
    return sqrt(gx*gx + gy*gy);
}

// Trilinear sample of a 33-point 3-D LUT
vec3 applyLut3d(vec3 c) {
    const float N = 33.0;
    vec3 uvw = c * ((N - 1.0) / N) + 0.5 / N;
    return texture(uLut3d, uvw).rgb;
}

// False-colour ramp: luma → broadcast exposure colour
vec3 falseColor(float y) {
    return texture(uFalseLUT, vec2(y, 0.5)).rgb;
}

// ── Main ─────────────────────────────────────────────────────────────────────
void main() {
    // 1. Live frame
    vec3 color = sampleFrame(vTexCoord);

    // 2. Optional 3-D LUT colour preview (e.g. Canon Log → Rec.709 look)
    if (uLutEnabled) {
        vec3 graded = applyLut3d(color);
        color = mix(color, graded, uLutStrength);
    }

    // 3. Focus peaking — Sobel edges overlaid in chosen colour
    if (uPeakingEnabled) {
        float mag = sobelMag(vTexCoord);
        if (mag > uPeakThreshold) {
            float strength = smoothstep(uPeakThreshold, uPeakThreshold * 2.5, mag);
            color = mix(color, uPeakColor, strength * 0.9);
        }
    }

    // 4. False colour — replaces RGB with exposure-zone palette
    if (uFalseColorEnabled) {
        color = falseColor(luma(color));
    }

    // 5. Onion skin — desaturated ghost of reference frame
    //    Use this to match a living-room shot to a warehouse recreation.
    if (uOnionEnabled) {
        vec4 ghost = texture(uOnionTex, vTexCoord);
        float ghostY = luma(ghost.rgb);
        // Partially desaturate so ghost reads as a guide, not a colour overlay
        vec3 ghostDisplay = mix(ghost.rgb, vec3(ghostY), 0.55);
        color = mix(color, ghostDisplay, uOnionOpacity * ghost.a);
    }

    fragColor = vec4(color, 1.0);
}
