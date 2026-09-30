// GLSL used by the overlay. Everything the client draws is a signed-distance
// field evaluated per pixel: no meshes, no nine-slice textures, no borrowed
// Minecraft geometry. One batched program covers panels, text, icons, shadows
// and the ESP, so a whole frame of UI is a couple of draw calls.

namespace cold::render::shaders {

/* Quad expansion, run on the GPU.
 *
 * The overlay draws every primitive as one quad carrying its own signed
 * distance parameters, and building those four vertices was the only part of a
 * frame that scaled with how much was on screen: a busy frame is several
 * thousand primitives and sixty-four floats each, written one at a time on the
 * render thread while the game waits inside wglSwapBuffers.
 *
 * So the CPU writes one thirty-two float record per primitive and this kernel
 * writes the vertices, which is half the bandwidth over the bus and none of
 * the per-corner arithmetic on the CPU at all. The vertex and fragment stages
 * are untouched - the buffer this fills is byte for byte the one the CPU path
 * fills, so both paths draw through the same program.
 *
 * Lunar's 1.8.9 context is a legacy compatibility profile, which never
 * exposes ARB_compute_shader regardless of what the card can do, so the GPU
 * path is normally dead here and the CPU expansion draws the frame. The
 * kernel is kept because the source is shared with the modern build. */
inline constexpr const char* kExpandCompute = R"GLSL(#version 430 core
#extension GL_ARB_compute_shader : require
#extension GL_ARB_shader_storage_buffer_object : require
#extension GL_ARB_shading_language_420pack : require

layout(local_size_x = 64) in;

/* Flat float arrays rather than structs on purpose. A std430 struct of vec4s
 * would lay out the same way today, but the C++ side of this is a packed array
 * of sixteen floats per vertex that the vertex format already points at, and
 * matching it by construction is one less thing that can silently drift. */
layout(std430, binding = 0) readonly buffer Primitives { float prims[]; };
layout(std430, binding = 1) writeonly buffer Vertices { float verts[]; };

uniform uint uCount;

const uint PRIM_FLOATS = 36u;
const uint VERT_FLOATS = 16u;

void main() {
    uint index = gl_GlobalInvocationID.x;
    if (index >= uCount) {
        return;
    }

    uint p = index * PRIM_FLOATS;
    vec4 sdf   = vec4(prims[p + 8u], prims[p + 9u], prims[p + 10u], prims[p + 11u]);
    vec4 style = vec4(prims[p + 12u], prims[p + 13u], prims[p + 14u], prims[p + 15u]);
    vec4 uv    = vec4(prims[p + 16u], prims[p + 17u], prims[p + 18u], prims[p + 19u]);

    /* The record carries all four corners, in the order the static index
     * buffer expects: top left, top right, bottom right, bottom left. A line
     * and a projected cube face are quads that are not axis aligned, and they
     * come through here too. */
    vec2 st[4];
    st[0] = vec2(uv.x, uv.y);
    st[1] = vec2(uv.z, uv.y);
    st[2] = vec2(uv.z, uv.w);
    st[3] = vec2(uv.x, uv.w);

    for (uint corner = 0u; corner < 4u; ++corner) {
        uint c = p + 20u + corner * 4u;
        vec4 colour = vec4(prims[c + 0u], prims[c + 1u], prims[c + 2u], prims[c + 3u]);
        uint v = (index * 4u + corner) * VERT_FLOATS;
        verts[v + 0u] = prims[p + corner * 2u];
        verts[v + 1u] = prims[p + corner * 2u + 1u];
        verts[v + 2u] = st[corner].x;
        verts[v + 3u] = st[corner].y;
        verts[v + 4u] = colour.r;
        verts[v + 5u] = colour.g;
        verts[v + 6u] = colour.b;
        verts[v + 7u] = colour.a;
        verts[v + 8u] = sdf.x;
        verts[v + 9u] = sdf.y;
        verts[v + 10u] = sdf.z;
        verts[v + 11u] = sdf.w;
        verts[v + 12u] = style.x;
        verts[v + 13u] = style.y;
        verts[v + 14u] = style.z;
        verts[v + 15u] = style.w;
    }
}
)GLSL";

inline constexpr const char* kUiVertex = R"GLSL(#version 330 compatibility
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec4 aRect;   // center.xy, half extents.zw, in pixels
layout(location = 4) in vec4 aStyle;  // corner radius, spread, mode, param

uniform mat4 uProjection;

out vec2 vPos;
out vec2 vUv;
out vec4 vColor;
out vec4 vRect;
out vec4 vStyle;

void main() {
    vPos = aPos;
    vUv = aUv;
    vColor = aColor;
    vRect = aRect;
    vStyle = aStyle;
    gl_Position = uProjection * vec4(aPos, 0.0, 1.0);
}
)GLSL";

inline constexpr const char* kUiFragment = R"GLSL(#version 330 compatibility
in vec2 vPos;
in vec2 vUv;
in vec4 vColor;
in vec4 vRect;
in vec4 vStyle;

uniform sampler2D uFontAtlas;
uniform sampler2D uIconAtlas;
uniform sampler2D uImage;
uniform float uTime;
uniform vec2 uFontAtlasTexel;
uniform vec2 uIconAtlasTexel;

out vec4 fragColor;

const int MODE_FILL = 0;
const int MODE_STROKE = 1;
const int MODE_TEXT = 2;
const int MODE_ICON = 3;
const int MODE_IMAGE = 4;
const int MODE_SHADOW = 5;
const int MODE_GLOW = 6;
const int MODE_RING = 7;
const int MODE_SWEEP = 8;
const int MODE_LINE = 9;
const int MODE_TEXT_BLOOM = 10;

float sdRoundBox(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

// Ordered-ish hash dither. Large translucent panels band badly on 8-bit
// backbuffers; a sub-LSB jitter removes it without visible noise.
float dither(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453) - 0.5;
}

// Exactly one pixel of antialiasing.
//
// fwidth(d) is how far the field moves per screen pixel, so d/fwidth(d) is the
// distance measured in screen pixels; offsetting by half a pixel and clamping
// gives the analytic coverage of the edge. smoothstep(-fw, +fw, d) is the
// common shortcut and it is wrong: it spreads the ramp over two pixels, which
// turns 12 px UI text into a smudge.
float coverage(float distance) {
    return clamp(0.5 - distance / max(fwidth(distance), 1e-5), 0.0, 1.0);
}

float sdfCoverage(float value) {
    return clamp((value - 0.5) / max(fwidth(value), 1e-5) + 0.5, 0.0, 1.0);
}

// Text and icons, on the shared SDF atlases.
//
// One fixed-width ramp cannot serve every size: a field sampled near 1:1 is
// already smooth, and widening it then only smudges; a field magnified past
// 1:1 reconstructs its texel grid as hard stair steps, because a single
// fwidth() tap answers "how much did the field move across THIS screen
// pixel" and on a magnified grid that is almost always far less than one
// texel. Measuring the field's change across exactly one atlas texel and
// taking the wider of the two ramps answers the real question - how wide is
// the edge in the field's own units - so small counters stay crisp while a
// stretched watermark lands as a clean smooth ramp instead of visible
// pixels. Taps that cross a tile boundary only land where the glyph's own
// coverage is already zero, so they cannot tint the visible stroke.
float sdfAlpha(sampler2D field, vec2 uv, vec2 texel, float alpha) {
    float centre = texture(field, uv).r;
    float fx = texture(field, uv + vec2(texel.x * 0.5, 0.0)).r -
               texture(field, uv - vec2(texel.x * 0.5, 0.0)).r;
    float fy = texture(field, uv + vec2(0.0, texel.y * 0.5)).r -
               texture(field, uv - vec2(0.0, texel.y * 0.5)).r;
    float width = max(fwidth(centre), max(abs(fx), abs(fy)));
    return alpha * clamp((centre - 0.5) / max(width, 1e-4) + 0.5, 0.0, 1.0);
}

void main() {
    int mode = int(vStyle.z + 0.5);
    vec2 local = vPos - vRect.xy;
    vec2 halfSize = vRect.zw;
    float radius = min(vStyle.x, min(halfSize.x, halfSize.y));
    vec4 color = vColor;

    if (mode == MODE_TEXT) {
        fragColor = vec4(color.rgb, sdfAlpha(uFontAtlas, vUv, uFontAtlasTexel, color.a));
        return;
    }

    if (mode == MODE_TEXT_BLOOM) {
        // The same glyph field read over a quad inflated past its tile: the
        // padding the rasteriser left around the outline is stretched over more
        // screen pixels, so a soft ramp across it is a real halo rather than a
        // second, blurrier copy of the letter.
        float field = texture(uFontAtlas, vUv).r;
        float soft = max(vStyle.w, 0.01);
        // Exponential in field distance: the halo is down to a fiftieth by the
        // edge of the tile, so nothing squares off where the field runs out.
        float halo = exp(-max((0.5 - field) / soft, 0.0) * 4.0);
        fragColor = vec4(color.rgb, color.a * halo);
        return;
    }

    if (mode == MODE_ICON) {
        fragColor = vec4(color.rgb, sdfAlpha(uIconAtlas, vUv, uIconAtlasTexel, color.a));
        return;
    }

    if (mode == MODE_IMAGE) {
        vec4 sampled = texture(uImage, vUv);
        float alpha = coverage(sdRoundBox(local, halfSize, radius));
        fragColor = vec4(sampled.rgb * color.rgb, sampled.a * color.a * alpha);
        fragColor.rgb += dither(vPos) * (1.0 / 255.0);
        return;
    }

    if (mode == MODE_SHADOW) {
        float distance = sdRoundBox(local, halfSize, radius);
        float spread = max(vStyle.y, 0.001);
        // Exponential falloff reads much closer to a real gaussian shadow than
        // smoothstep, and costs one exp.
        float alpha = exp(-max(distance, 0.0) * (2.2 / spread));
        alpha *= step(-0.5, distance) * 0.85 + 0.15;
        fragColor = vec4(color.rgb, color.a * alpha);
        return;
    }

    if (mode == MODE_GLOW) {
        float distance = sdRoundBox(local, halfSize, radius);
        float spread = max(vStyle.y, 0.001);
        float halo = exp(-max(distance, 0.0) * (3.0 / spread));
        float core = 1.0 - smoothstep(-1.5, 1.5, distance);
        float alpha = max(halo * 0.85, core);
        fragColor = vec4(color.rgb, color.a * alpha);
        return;
    }

    if (mode == MODE_RING) {
        float distance = abs(length(local) - (min(halfSize.x, halfSize.y) - vStyle.w * 0.5)) -
                         vStyle.w * 0.5;
        float angle = atan(local.x, -local.y) / 6.2831853 + 0.5;
        float progress = clamp(vStyle.y, 0.0, 1.0);
        float within = step(angle, progress);
        fragColor = vec4(color.rgb, color.a * coverage(distance) * within);
        return;
    }

    if (mode == MODE_LINE) {
        // vUv.y runs -1..1 across the strip, so an arbitrarily rotated line
        // still antialiases without leaving the axis-aligned SDF path.
        float across = abs(vUv.y);
        fragColor = vec4(color.rgb, color.a * coverage(across - 1.0));
        return;
    }

    if (mode == MODE_SWEEP) {
        float distance = abs(sdRoundBox(local, halfSize, radius)) - vStyle.w * 0.5;
        float angle = atan(local.y, local.x) / 6.2831853 + 0.5;
        float wave = 0.55 + 0.45 * sin((angle * 2.0 - uTime * 0.35) * 6.2831853);
        fragColor = vec4(color.rgb, color.a * coverage(distance) * wave);
        return;
    }

    float distance = sdRoundBox(local, halfSize, radius);
    if (mode == MODE_STROKE) {
        distance = abs(distance) - vStyle.w * 0.5;
    }
    float alpha = coverage(distance);
    fragColor = vec4(color.rgb + dither(vPos) * (1.0 / 255.0), color.a * alpha);
}
)GLSL";

// Fullscreen passes use gl_VertexID so they need no vertex buffer at all.
inline constexpr const char* kFullscreenVertex = R"GLSL(#version 330 compatibility
out vec2 vUv;
void main() {
    vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = corner;
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
)GLSL";

// Dual Kawase: four bilinear taps down, eight up. Six passes over a quarter-res
// target give a 40 px-equivalent blur for a fraction of a separable gaussian.
//
// On Lunar the panel backdrop samples the default framebuffer directly, so the
// pyramid normally sits unused here; the programs are the same as the modern
// build's and the capture path is kept for completeness.
inline constexpr const char* kKawaseDownFragment = R"GLSL(#version 330 compatibility
in vec2 vUv;
uniform sampler2D uSource;
uniform vec2 uHalfPixel;
uniform float uOffset;
out vec4 fragColor;

void main() {
    vec4 sum = texture(uSource, vUv) * 4.0;
    sum += texture(uSource, vUv - uHalfPixel * uOffset);
    sum += texture(uSource, vUv + uHalfPixel * uOffset);
    sum += texture(uSource, vUv + vec2(uHalfPixel.x, -uHalfPixel.y) * uOffset);
    sum += texture(uSource, vUv - vec2(uHalfPixel.x, -uHalfPixel.y) * uOffset);
    fragColor = sum / 8.0;
}
)GLSL";

inline constexpr const char* kKawaseUpFragment = R"GLSL(#version 330 compatibility
in vec2 vUv;
uniform sampler2D uSource;
uniform vec2 uHalfPixel;
uniform float uOffset;
out vec4 fragColor;

void main() {
    vec4 sum = texture(uSource, vUv + vec2(-uHalfPixel.x * 2.0, 0.0) * uOffset);
    sum += texture(uSource, vUv + vec2(-uHalfPixel.x, uHalfPixel.y) * uOffset) * 2.0;
    sum += texture(uSource, vUv + vec2(0.0, uHalfPixel.y * 2.0) * uOffset);
    sum += texture(uSource, vUv + vec2(uHalfPixel.x, uHalfPixel.y) * uOffset) * 2.0;
    sum += texture(uSource, vUv + vec2(uHalfPixel.x * 2.0, 0.0) * uOffset);
    sum += texture(uSource, vUv + vec2(uHalfPixel.x, -uHalfPixel.y) * uOffset) * 2.0;
    sum += texture(uSource, vUv + vec2(0.0, -uHalfPixel.y * 2.0) * uOffset);
    sum += texture(uSource, vUv + vec2(-uHalfPixel.x, -uHalfPixel.y) * uOffset) * 2.0;
    fragColor = sum / 12.0;
}
)GLSL";

// Tints and slightly desaturates the blurred backdrop so panel text keeps its
// contrast over a bright scene without the panel itself going opaque.
inline constexpr const char* kBackdropFragment = R"GLSL(#version 330 compatibility
in vec2 vUv;
uniform sampler2D uSource;
uniform vec4 uTint;
uniform float uSaturation;
out vec4 fragColor;

void main() {
    vec3 color = texture(uSource, vUv).rgb;
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, uSaturation);
    color = mix(color, uTint.rgb, uTint.a);
    fragColor = vec4(color, 1.0);
}
)GLSL";

}  // namespace cold::render::shaders
