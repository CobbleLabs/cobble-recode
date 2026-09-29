package cobble.ui;

import cobble.core.Color;
import cobble.core.Ease;
import cobble.core.Maths;
import cobble.core.Rect;
import cobble.render.Backdrop;
import cobble.render.Renderer;

/**
 * One dark slab, one lavender accent.
 *
 * The menu carries no borders, no gradients on its controls and no second hue:
 * state is said with opacity and with the accent, so a dense module list stays
 * scannable over a bright Minecraft scene and the whole overlay stays inside a
 * single batched draw call. Layout numbers live in Minecraft's own GUI-scaled
 * units, which is what makes the window the same size on screen as the client
 * it is modelled on.
 */
public final class Theme {
    public static final String CLIENT_NAME = "Cobble Recode";
    /* What the client calls itself when there is no room to say it in full: the
     * reveal capsule, which is sized to its own label. */
    public static final String CLIENT_SHORT_NAME = "Cobble";
    public static final String CLIENT_BRAND = "cobble.vip";
    /* The HUD watermark, the reveal capsule and Lua's cold.version all read
     * this, so a bump lands everywhere at once. */
    public static final String CLIENT_VERSION = "2";

    /* Flat: a gradient across a slab this large reads as a lighting effect
     * rather than as a surface. */
    public static final Color PANEL = Color.rgb(0x171A21);
    public static final Color PANEL_FOOT = Color.rgb(0x171A21);
    /* The rail sits a shade above the body at the top and falls away below it,
     * which is the only thing separating the two without a divider. */
    public static final Color SIDEBAR = Color.rgb(0x1A1C21);
    public static final Color SIDEBAR_FOOT = Color.rgb(0x14161B);
    public static final Color PANEL_SOLID = Color.rgb(0x171A21);
    public static final Color HEADER = Color.rgb(0x121419);

    public static final Color SURFACE = Color.rgb(0x000000, 0.196f);
    public static final Color SURFACE_HOVER = Color.rgb(0x000000, 0.078f);
    public static final Color SURFACE_ACTIVE = Color.rgb(0x000000, 0.137f);
    public static final Color BORDER = Color.rgb(0xFFFFFF, 0.06f);
    public static final Color BORDER_STRONG = Color.rgb(0xFFFFFF, 0.14f);
    public static final Color SCROLLBAR = Color.rgb(0xFFFFFF, 0.20f);

    public static final Color TEXT_BRIGHT = Color.rgb(0xFFFFFF);
    public static final Color TEXT = Color.rgb(0xFFFFFF, 0.86f);
    public static final Color TEXT_DIM = Color.rgb(0xFFFFFF, 0.51f);
    public static final Color TEXT_FAINT = Color.rgb(0xFFFFFF, 0.27f);

    public static final Color POSITIVE = Color.rgb(0x5BE59A);
    public static final Color WARNING = Color.rgb(0xFFC55C);
    public static final Color NEGATIVE = Color.rgb(0xFF5C6E);

    public static final Color SHADOW = Color.rgb(0x000000, 0.55f);
    public static final Color SCRIM = Color.rgb(0x05070A, 0.0f);

    /* GUI-scaled units, not pixels. */
    public static final float RADIUS_PANEL = 12.0f;
    public static final float RADIUS_CARD = 6.0f;
    public static final float RADIUS_PILL = 5.0f;

    /* The accent is a live setting, so the HUD, the ESP and the menu all move
     * together when it changes. Everything else in the palette is fixed. */
    private static Color accent = Color.rgb(0xBDA4BD);
    private static Color accentSoft = Color.rgb(0xBDA4BD, 0.16f);
    private static Color accentDim = Color.rgb(0x877687);

    private Theme() {}

    public static Color accent() {
        return accent;
    }

    public static Color accentSoft() {
        return accentSoft;
    }

    public static Color accentDim() {
        return accentDim;
    }

    public static void setAccent(Color value) {
        accent = value;
        accentSoft = value.withAlpha(0.16f);
        /* The selected sidebar pill is the accent stepped down rather than faded:
         * a translucent pill picks up whatever is behind it and stops matching
         * the accent everywhere else. */
        accentDim = new Color(value.r() * 0.7f, value.g() * 0.7f, value.b() * 0.7f, 1.0f);
    }

    /**
     * The accent, moved along its own axis rather than to a different hue.
     * Everything drawn anywhere is that colour or a step along it, which is why
     * modules carry no colour settings of their own. {@code lightness} below one
     * steps towards black and above one towards white; {@code alpha} scales the
     * result.
     */
    public static Color accentShade(float lightness, float alpha) {
        if (lightness <= 1.0f) {
            float scale = Maths.clamp(lightness, 0.0f, 1.0f);
            return new Color(accent.r() * scale, accent.g() * scale, accent.b() * scale, accent.a() * alpha);
        }
        /* Past one the step is towards white rather than past full brightness,
         * which would only clip a channel and shift the hue while doing it. */
        float towards = Maths.clamp(lightness - 1.0f, 0.0f, 1.0f);
        return new Color(Maths.lerp(accent.r(), 1.0f, towards), Maths.lerp(accent.g(), 1.0f, towards),
                Maths.lerp(accent.b(), 1.0f, towards), accent.a() * alpha);
    }

    public static Color accentShade(float lightness) {
        return accentShade(lightness, 1.0f);
    }

    /** Health drives hue from the positive to the negative end of the palette. */
    public static Color healthColour(float health, float maxHealth) {
        float fraction = maxHealth <= 0.0f ? 0.0f : Maths.clamp(health / maxHealth, 0.0f, 1.0f);
        if (fraction > 0.5f) {
            return Color.mix(WARNING, POSITIVE, (fraction - 0.5f) * 2.0f);
        }
        return Color.mix(NEGATIVE, WARNING, fraction * 2.0f);
    }

    /**
     * {@code alpha} scales the whole panel for open and close animations.
     * {@code useBackdrop} frosts the scene behind it, which the menu leaves off
     * by default: a blurred world behind a list of words is one more thing
     * competing with the words.
     */
    public static void drawPanel(Rect bounds, float radius, float alpha, boolean useBackdrop) {
        if (alpha <= 0.005f) {
            return;
        }
        Renderer gfx = Renderer.instance();
        Backdrop backdrop = Client.backdrop();
        if (useBackdrop && backdrop.valid()) {
            /* Sample the slice of the blurred frame behind this panel; 0..1 would
             * stretch the whole scene into every panel. The v span runs
             * backwards because the pyramid is seeded from the default
             * framebuffer, whose row 0 is the bottom of the window. */
            float screenWidth = gfx.width();
            float screenHeight = gfx.height();
            Rect uv = new Rect(bounds.x() / screenWidth, 1.0f - bounds.y() / screenHeight,
                    bounds.width() / screenWidth, -bounds.height() / screenHeight);
            gfx.image(bounds, uv, backdrop.texture(), new Color(1.0f, 1.0f, 1.0f, alpha), radius);
            gfx.rectVertical(bounds, PANEL.withAlpha(alpha * 0.82f), PANEL_FOOT.withAlpha(alpha * 0.82f), radius);
        } else {
            /* No border. A hairline round a slab this large only draws the eye to
             * the edge of the window instead of to what is written inside it. */
            gfx.rectVertical(bounds, PANEL.withAlpha(alpha), PANEL_FOOT.withAlpha(alpha), radius);
        }
    }

    /** Corner brackets plus a soft halo around a projected hitbox. */
    public static void drawTargetMarker(Rect bounds, float fade, float time) {
        Renderer gfx = Renderer.instance();
        float eased = Ease.outBack(Maths.clamp(fade, 0.0f, 1.0f), 1.2f);
        float inset = (1.0f - eased) * 10.0f;
        Rect box = bounds.inset(-4.0f + inset);
        float radius = Math.min(6.0f, Math.min(box.width(), box.height()) * 0.25f);

        gfx.glow(box, accent.withAlpha(0.18f * fade), 20.0f, radius);

        /* Brackets rather than a full rectangle: the target stays legible
         * without a border that competes with the ESP outline underneath. */
        float armX = Math.min(box.width() * 0.28f, 16.0f);
        float armY = Math.min(box.height() * 0.28f, 16.0f);
        float thickness = 1.6f;
        Color colour = accent.withAlpha(fade);
        float left = box.x();
        float right = box.right();
        float top = box.y();
        float bottom = box.bottom();

        gfx.line(left, top, left + armX, top, thickness, colour);
        gfx.line(left, top, left, top + armY, thickness, colour);
        gfx.line(right - armX, top, right, top, thickness, colour);
        gfx.line(right, top, right, top + armY, thickness, colour);
        gfx.line(left, bottom - armY, left, bottom, thickness, colour);
        gfx.line(left, bottom, left + armX, bottom, thickness, colour);
        gfx.line(right - armX, bottom, right, bottom, thickness, colour);
        gfx.line(right, bottom - armY, right, bottom, thickness, colour);

        float orbitRadius = Math.min(box.width(), box.height()) * 0.5f + 5.0f;
        gfx.ring(box.centerX(), box.centerY(), orbitRadius, 1.4f, 0.18f + 0.06f * (float) Math.sin(time * 2.1f),
                accent.withAlpha(0.5f * fade));
    }

    /** Bar with a filled portion; used for health, progress and sliders. */
    public static void drawMeter(Rect bounds, float fraction, Color track, Color fill, float radius) {
        Renderer gfx = Renderer.instance();
        gfx.rect(bounds, track, radius);
        float width = bounds.width() * Maths.clamp(fraction, 0.0f, 1.0f);
        if (width > 0.5f) {
            gfx.rect(new Rect(bounds.x(), bounds.y(), width, bounds.height()), fill, radius);
        }
    }
}
