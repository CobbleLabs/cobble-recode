#include "ui/Theme.hpp"

#include <algorithm>
#include <cmath>

#include "render/Backdrop.hpp"
#include "render/Renderer.hpp"
#include "ui/Client.hpp"

namespace cold::ui {
namespace {

Palette kPalette;

}  // namespace

const Palette& theme() { return kPalette; }

void setAccent(const Color& accent) {
    kPalette.accent = accent;
    kPalette.accentSoft = accent.withAlpha(0.16f);
    /* The selected sidebar pill is the accent stepped down rather than faded:
     * a translucent pill picks up whatever is behind it and stops matching the
     * accent everywhere else. */
    kPalette.accentDim = Color{accent.r * 0.7f, accent.g * 0.7f, accent.b * 0.7f, 1.0f};
}

Color accentShade(float lightness, float alpha) {
    const Color& accent = kPalette.accent;
    if (lightness <= 1.0f) {
        const float scale = clamp(lightness, 0.0f, 1.0f);
        return Color{accent.r * scale, accent.g * scale, accent.b * scale, accent.a * alpha};
    }
    /* Past one the step is towards white rather than past full brightness,
     * which would only clip a channel and shift the hue while doing it. */
    const float towards = clamp(lightness - 1.0f, 0.0f, 1.0f);
    return Color{lerp(accent.r, 1.0f, towards), lerp(accent.g, 1.0f, towards),
                 lerp(accent.b, 1.0f, towards), accent.a * alpha};
}

Color healthColour(float health, float maxHealth) {
    const float fraction = maxHealth <= 0.0f ? 0.0f : clamp(health / maxHealth, 0.0f, 1.0f);
    if (fraction > 0.5f) {
        return Color::mix(kPalette.warning, kPalette.positive, (fraction - 0.5f) * 2.0f);
    }
    return Color::mix(kPalette.negative, kPalette.warning, fraction * 2.0f);
}

void drawPanel(const Rect& bounds, float radius, float alpha, bool useBackdrop) {
    render::Renderer& gfx = render::renderer();
    if (alpha <= 0.005f) {
        return;
    }

    const render::Backdrop& backdrop = client().backdrop();
    if (useBackdrop && backdrop.valid()) {
        /* Sample the slice of the blurred frame that sits behind this panel.
         * Using 0..1 here would stretch the whole scene into every panel, which
         * reads as a warped mirror of the world rather than frosted glass.
         *
         * The v span runs backwards: the pyramid is seeded by blitting out of
         * the default framebuffer, whose row 0 is the bottom of the window,
         * while the UI works top-down. Sampling it the same way round puts the
         * sky behind a panel at the bottom of the screen and the ground behind
         * one at the top. */
        const float screenWidth = static_cast<float>(gfx.width());
        const float screenHeight = static_cast<float>(gfx.height());
        const Rect uv{bounds.x / screenWidth, 1.0f - bounds.y / screenHeight,
                      bounds.width / screenWidth, -bounds.height / screenHeight};
        gfx.image(bounds, uv, backdrop.texture(), Color{1.0f, 1.0f, 1.0f, alpha}, radius);
        gfx.rectVertical(bounds, kPalette.panel.withAlpha(alpha * 0.82f),
                         kPalette.panelFoot.withAlpha(alpha * 0.82f), radius);
    } else {
        /* No border. A hairline round a slab this large only draws the eye to
         * the edge of the window instead of to what is written inside it. */
        gfx.rectVertical(bounds, kPalette.panel.withAlpha(alpha),
                         kPalette.panelFoot.withAlpha(alpha), radius);
    }
}

void drawTargetMarker(const Rect& bounds, float fade, float time) {
    render::Renderer& gfx = render::renderer();
    const float eased = ease::outBack(clamp(fade, 0.0f, 1.0f), 1.2f);
    const float inset = (1.0f - eased) * 10.0f;
    const Rect box = bounds.inset(-4.0f + inset);
    const float radius = std::min(6.0f, std::min(box.width, box.height) * 0.25f);

    gfx.glow(box, kPalette.accent.withAlpha(0.18f * fade), 20.0f, radius);

    /* Corner brackets rather than a full rectangle: the target stays legible
     * without a border that competes with the ESP outline underneath. */
    const float armX = std::min(box.width * 0.28f, 16.0f);
    const float armY = std::min(box.height * 0.28f, 16.0f);
    const float thickness = 1.6f;
    const Color colour = kPalette.accent.withAlpha(fade);

    const float left = box.x;
    const float right = box.right();
    const float top = box.y;
    const float bottom = box.bottom();

    gfx.line(left, top, left + armX, top, thickness, colour);
    gfx.line(left, top, left, top + armY, thickness, colour);
    gfx.line(right - armX, top, right, top, thickness, colour);
    gfx.line(right, top, right, top + armY, thickness, colour);
    gfx.line(left, bottom - armY, left, bottom, thickness, colour);
    gfx.line(left, bottom, left + armX, bottom, thickness, colour);
    gfx.line(right - armX, bottom, right, bottom, thickness, colour);
    gfx.line(right, bottom - armY, right, bottom, thickness, colour);

    /* A slow orbit around the centre reads as an active lock without pulling
     * attention off the fight. */
    const float orbitRadius = std::min(box.width, box.height) * 0.5f + 5.0f;
    gfx.ring(box.centerX(), box.centerY(), orbitRadius, 1.4f,
             0.18f + 0.06f * std::sin(time * 2.1f), kPalette.accent.withAlpha(0.5f * fade));
}

void drawMeter(const Rect& bounds, float fraction, const Color& track, const Color& fill,
               float radius) {
    render::Renderer& gfx = render::renderer();
    gfx.rect(bounds, track, radius);
    const float width = bounds.width * clamp(fraction, 0.0f, 1.0f);
    if (width > 0.5f) {
        gfx.rect(Rect{bounds.x, bounds.y, width, bounds.height}, fill, radius);
    }
}

}  // namespace cold::ui
