#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

// ── Textures ────────────────────────────────────────────────────────────────
uniform sampler2D      uFrame;      // live RGBA frame from UVC dongle
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

// 3×3 Sobel gradient magnitude on the luma channel
float sobelMag(vec2 uv) {
    vec2 t = uTexelSize;
    float tl = luma(texture(uFrame, uv + vec2(-t.x,  t.y)).rgb);
    float tm = luma(texture(uFrame, uv + vec2( 0.0,  t.y)).rgb);
    float tr = luma(texture(uFrame, uv + vec2( t.x,  t.y)).rgb);
    float ml = luma(texture(uFrame, uv + vec2(-t.x,  0.0)).rgb);
    float mr = luma(texture(uFrame, uv + vec2( t.x,  0.0)).rgb);
    float bl = luma(texture(uFrame, uv + vec2(-t.x, -t.y)).rgb);
    float bm = luma(texture(uFrame, uv + vec2( 0.0, -t.y)).rgb);
    float br = luma(texture(uFrame, uv + vec2( t.x, -t.y)).rgb);
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
    vec3 color = texture(uFrame, vTexCoord).rgb;

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
