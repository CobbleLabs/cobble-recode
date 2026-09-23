#pragma once

#include <string_view>

#include "core/Math.hpp"
#include "render/Font.hpp"

namespace cold::ui {

inline constexpr std::string_view kClientName = "Cobble Recode";
/* What the client calls itself when there is no room to say it in full: the
 * reveal capsule, which is sized to its own label. */
inline constexpr std::string_view kClientShortName = "Cobble";
inline constexpr std::string_view kClientBrand = "cobble.vip";
/* One source of truth for the build's version. The HUD watermark, the reveal
 * capsule and Lua's cold.version all read this, so a bump lands everywhere at
 * once rather than in whichever of the three someone remembered. */
inline constexpr std::string_view kClientVersion = "2";

/* One dark slab, one lavender accent.
 *
 * The menu carries no borders, no gradients on its controls and no second hue:
 * state is said with opacity and with the accent, so a dense module list stays
 * scannable over a bright Minecraft scene and the whole overlay stays inside a
 * single batched draw call. Layout numbers live in Minecraft's own GUI-scaled
 * units, which is what makes the window the same size on screen as the client
 * it is modelled on. */
struct Palette {
    /* The window body. Flat: a gradient across a slab this large reads as a
     * lighting effect rather than as a surface. */
    Color panel = Color::rgb(0x171A21);
    Color panelFoot = Color::rgb(0x171A21);
    /* The rail sits a shade above the body at the top and falls away below it,
     * which is the only thing separating the two without a divider. */
    Color sidebar = Color::rgb(0x1A1C21);
    Color sidebarFoot = Color::rgb(0x14161B);
    Color panelSolid = Color::rgb(0x171A21);
    Color header = Color::rgb(0x121419);

    Color surface = Color::rgb(0x000000, 0.196f);
    Color surfaceHover = Color::rgb(0x000000, 0.078f);
    Color surfaceActive = Color::rgb(0x000000, 0.137f);
    Color border = Color::rgb(0xFFFFFF, 0.06f);
    Color borderStrong = Color::rgb(0xFFFFFF, 0.14f);
    Color scrollbar = Color::rgb(0xFFFFFF, 0.20f);

    Color accent = Color::rgb(0xBDA4BD);
    Color accentSoft = Color::rgb(0xBDA4BD, 0.16f);
    Color accentDim = Color::rgb(0x877687);

    Color textBright = Color::rgb(0xFFFFFF);
    Color text = Color::rgb(0xFFFFFF, 0.86f);
    Color textDim = Color::rgb(0xFFFFFF, 0.51f);
    Color textFaint = Color::rgb(0xFFFFFF, 0.27f);

    Color positive = Color::rgb(0x5BE59A);
    Color warning = Color::rgb(0xFFC55C);
    Color negative = Color::rgb(0xFF5C6E);

    Color shadow = Color::rgb(0x000000, 0.55f);
    Color scrim = Color::rgb(0x05070A, 0.0f);

    /* GUI-scaled units, not pixels. */
    float radiusPanel = 12.0f;
    float radiusCard = 6.0f;
    float radiusPill = 5.0f;
};

const Palette& theme();

/* The accent is a live setting, so the HUD, the ESP and the menu all move
 * together when it changes. Everything else in the palette is fixed. */
void setAccent(const Color& accent);

/* Health drives hue from the positive to the negative end of the palette, so a
 * dying target reads at a glance without adding a fourth colour to the set. */
Color healthColour(float health, float maxHealth);

/* The accent, moved along its own axis rather than to a different hue.
 *
 * There is exactly one colour in this client and everything drawn anywhere -
 * the menu, the HUD, the ESP boxes, the trajectory arc, the particles - is that
 * colour or a step along it. Modules therefore carry no colour settings at all:
 * a per-module picker is how a client ends up with six hues that were each
 * reasonable alone and a mess together, and it makes the accent setting a lie.
 *
 * `lightness` below one steps towards black and above one towards white;
 * `alpha` scales the result. */
Color accentShade(float lightness, float alpha = 1.0f);

/* Flat panel: fill, hairline border. `alpha` scales the whole thing for
 * open/close animations. `useBackdrop` frosts the scene behind it, which the
 * menu leaves off by default - a blurred world behind a list of words is one
 * more thing competing with the words. */
void drawPanel(const Rect& bounds, float radius, float alpha, bool useBackdrop);

/* Corner brackets plus a soft halo around a projected hitbox. */
void drawTargetMarker(const Rect& bounds, float fade, float time);

/* Bar with a filled portion; used for health, progress and sliders. */
void drawMeter(const Rect& bounds, float fraction, const Color& track, const Color& fill,
               float radius);

}  // namespace cold::ui
