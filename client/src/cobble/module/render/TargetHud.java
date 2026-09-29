package cobble.module.render;

import cobble.core.Color;
import cobble.core.Maths;
import cobble.core.Rect;
import cobble.core.Time;
import cobble.game.CombatTarget;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.render.Align;
import cobble.render.FontRole;
import cobble.render.Icon;
import cobble.render.Renderer;
import cobble.ui.Theme;
import java.util.Locale;
import net.minecraft.client.entity.AbstractClientPlayer;

/**
 * Raven-b's TargetHUD, Modern mode.
 *
 * <p>The whole idea of the original is that the bar tells you two things at
 * once: where the target's health is now, and where it was a moment ago. A hit
 * lands as a bright bar snapping back and a dimmer ghost draining after it, so
 * the damage a swing did is legible without reading a number. Healing runs the
 * other way and the ghost leads instead of trailing.
 *
 * <p>It follows whatever combat module currently has a target rather than one
 * in particular, and fades rather than vanishing when that target is dropped -
 * a panel that blinks out between two swings is worse than no panel.
 */
public final class TargetHud extends Module {
    /* GUI-scaled units, the same space the rest of the HUD is measured in. */
    private static final float WIDTH = 116.0f;
    private static final float HEIGHT = 44.0f;
    private static final float PADDING = 7.0f;
    private static final float BAR_HEIGHT = 4.0f;
    private static final float FACE_SIZE = 30.0f;

    private static final float FADE_RATE = 9.0f;
    private static final float BAR_RATE = 22.0f;
    private static final float GHOST_RATE = 4.5f;

    private static final Rect SKIN_FACE_UV = new Rect(8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f);

    private final Setting offsetX = addNumber("Offset X", "Pixels right of the screen centre", 0, -400, 400, 0);
    private final Setting offsetY = addNumber("Offset Y", "Pixels below the screen centre", 34, -300, 300, 0);
    private final Setting scale = addNumber("Scale", "Size of the panel", 1, 0.6f, 2, 2);
    private final Setting backdrop = addNumber("Backdrop", "Opacity of the slab behind the text", 0.55f, 0, 1, 2);
    private final Setting healthColour = addBoolean("Health colour",
            "Colour the bar by health rather than the accent", true);
    private final Setting showDistance = addBoolean("Distance", "Print how far away they are", true);
    private final Setting showOutcome = addBoolean("Win or loss", "Say whether you are ahead on health", true);
    private final Setting marker = addBoolean("Marker", "Bracket the target in the world", true);

    private String name = "";
    private float health;
    private float maxHealth = 20.0f;
    /* 0..1, the lagging copy of the bar. */
    private float ghost;
    /* 0..1, where the bar is now. */
    private float fraction;
    private float distance;
    private float ownFraction = 1.0f;
    private float alpha;
    private int targetId = -1;

    public TargetHud() {
        super("TargetHud", "Panel for whoever the combat modules are hitting", Category.RENDER);
        setIcon(Icon.TARGET);
    }

    @Override
    public void onEnable(Context context) {
        alpha = 0.0f;
        targetId = -1;
        ghost = 0.0f;
        fraction = 0.0f;
    }

    private static EntityView find(FrameState state, int id) {
        for (EntityView entity : state.entities) {
            if (entity.id == id) {
                return entity;
            }
        }
        return null;
    }

    private void drawMarker(Context context, EntityView entity) {
        if (!marker.bool() || entity == null || !context.projection.valid()) {
            return;
        }
        Rect bounds = context.projection.projectBox(entity.boxMin(), entity.boxMax());
        if (bounds != null) {
            Theme.drawTargetMarker(bounds, alpha * animation(), context.time);
        }
    }

    private void drawPanel(EntityView entity, Rect bounds, float alpha) {
        Renderer gfx = Renderer.instance();
        float unit = bounds.height() / HEIGHT;
        float radius = 5.0f * unit;

        float slab = backdrop.number();
        if (slab > 0.0f) {
            gfx.shadow(bounds, Color.rgb(0x000000, 0.45f * alpha), 10.0f * unit, radius);
            gfx.rect(bounds, Color.rgb(0x0B0D12, slab * alpha), radius);
            gfx.outline(bounds, Theme.BORDER.withAlpha(alpha), 1.0f, radius);
        }

        Color bar = healthColour.bool() ? Theme.healthColour(health, maxHealth) : Theme.accent();

        float pad = PADDING * unit;
        float nameSize = 10.0f * unit;
        float smallSize = 8.0f * unit;

        /* Avatar on the left, like the reference card: the target's own skin face
         * when there is one, a dim placeholder when there is not. */
        Rect face = new Rect(bounds.x() + pad, bounds.centerY() - FACE_SIZE * unit * 0.5f, FACE_SIZE * unit,
                FACE_SIZE * unit);
        int texture = entity != null && entity.handle instanceof AbstractClientPlayer player
                ? Game.playerSkin(player).texture() : 0;
        if (texture != 0) {
            gfx.imageCrisp(face, SKIN_FACE_UV, texture, new Color(1.0f, 1.0f, 1.0f, alpha), 0.0f);
        } else {
            gfx.rect(face, Color.rgb(0x05070A, 0.55f * alpha), 3.0f * unit);
            gfx.icon(Icon.USER_ROUND, face.inset(face.width() * 0.18f), Color.rgb(0xAAAAAA, 0.7f * alpha));
        }

        /* The text column starts clear of the avatar. */
        float textLeft = face.right() + 6.0f * unit;
        float textWidth = bounds.right() - pad - textLeft;
        /* Three rows, measured from the cap line down: who it is, how much of
         * them is left, and the bar. Spaced from the panel rather than from each
         * other so no row can drift into the one below it. */
        float nameRow = bounds.y() + pad;
        float statRow = bounds.y() + 21.0f * unit;

        /* Name on the left, distance on the right, both on the same cap line so
         * the two runs sit on one baseline whatever the glyphs are. */
        float nameWidth = textWidth - (showDistance.bool() ? 34.0f * unit : 0.0f);
        String shown = gfx.elide(FontRole.SEMI_BOLD, name, nameSize, nameWidth);
        gfx.textCap(FontRole.SEMI_BOLD, shown, textLeft + 0.8f * unit, nameRow + 0.8f * unit, nameSize,
                Color.rgb(0x05070A, 0.7f * alpha));
        gfx.textCap(FontRole.SEMI_BOLD, shown, textLeft, nameRow, nameSize, Theme.TEXT_BRIGHT.withAlpha(alpha));

        if (showDistance.bool()) {
            gfx.textCap(FontRole.MEDIUM, String.format(Locale.ROOT, "%.1fm", distance), bounds.right() - pad,
                    nameRow + 1.5f * unit, smallSize, Theme.TEXT_DIM.withAlpha(alpha), Align.RIGHT);
        }

        Rect track = new Rect(textLeft, bounds.bottom() - pad - BAR_HEIGHT * unit, textWidth, BAR_HEIGHT * unit);
        float barRadius = track.height() * 0.5f;
        gfx.rect(track, Color.rgb(0x05070A, 0.62f * alpha), barRadius);

        /* The ghost is the same bar a beat behind. Drawn first and dimmer, so a
         * hit reads as the bright bar snapping in and the dim one draining after
         * it. */
        if (ghost > fraction + 0.001f) {
            gfx.rect(new Rect(track.x(), track.y(), track.width() * ghost, track.height()),
                    bar.withAlpha(0.32f * alpha), barRadius);
        }
        if (fraction > 0.001f) {
            gfx.rect(new Rect(track.x(), track.y(), track.width() * fraction, track.height()), bar.withAlpha(alpha),
                    barRadius);
        }
        /* Healing runs the other way: the ghost leads and the solid bar catches
         * up, so the gap is the health that just came back. */
        if (ghost < fraction - 0.001f) {
            gfx.rect(new Rect(track.x() + track.width() * ghost, track.y(), track.width() * (fraction - ghost),
                    track.height()), Theme.TEXT_BRIGHT.withAlpha(0.28f * alpha), barRadius);
        }

        String healthText = String.format(Locale.ROOT, "%.1f", health);
        gfx.textCap(FontRole.MEDIUM, healthText, textLeft + 0.7f * unit, statRow + 0.7f * unit, smallSize,
                Color.rgb(0x05070A, 0.7f * alpha));
        gfx.textCap(FontRole.MEDIUM, healthText, textLeft, statRow, smallSize, bar.withAlpha(alpha));

        if (showOutcome.bool()) {
            boolean winning = fraction <= ownFraction;
            gfx.textCap(FontRole.BOLD, winning ? "W" : "L", bounds.right() - pad, statRow, smallSize,
                    (winning ? Theme.POSITIVE : Theme.NEGATIVE).withAlpha(alpha), Align.RIGHT);
        }
    }

    @Override
    public void onRender(Context context) {
        FrameState state = context.state;
        float step = 1.0f - (float) Math.exp(-FADE_RATE * context.deltaTime);

        EntityView target = null;
        if (state.inWorld && !state.screenOpen) {
            EntityView current = find(state, CombatTarget.current(Time.nowSeconds()));
            if (current != null && current.health > 0.0f) {
                target = current;
            }
        }

        if (target != null) {
            if (target.id != targetId) {
                /* A new target starts with the bar already where it belongs;
                 * easing it up from zero would read as the last one healing. */
                targetId = target.id;
                ghost = 1.0f;
                fraction = 1.0f;
            }
            name = Game.entityName(target.handle);
            if (name.isEmpty()) {
                name = target.isPlayer ? "player" : "entity";
            }
            health = target.health + target.absorption;
            maxHealth = target.maxHealth > 0.0f ? target.maxHealth : 20.0f;
            distance = (float) target.distance;
            ownFraction = state.player.maxHealth > 0.0f
                    ? Maths.clamp(state.player.health / state.player.maxHealth, 0.0f, 1.0f) : 1.0f;
            alpha += (1.0f - alpha) * step;
        } else {
            alpha -= alpha * step;
            if (alpha < 0.01f) {
                targetId = -1;
                return;
            }
        }

        float wanted = maxHealth > 0.0f ? Maths.clamp(health / maxHealth, 0.0f, 1.0f) : 0.0f;
        fraction += (wanted - fraction) * (1.0f - (float) Math.exp(-BAR_RATE * context.deltaTime));
        ghost += (fraction - ghost) * (1.0f - (float) Math.exp(-GHOST_RATE * context.deltaTime));

        float shown = alpha * animation();
        if (shown < 0.01f) {
            return;
        }

        /* Sized in GUI units so the panel is the same size on screen at any
         * resolution, exactly as the rest of the HUD is. */
        float unit = Math.max(1, state.scaleFactor) * scale.number();
        float width = WIDTH * unit;
        float height = HEIGHT * unit;
        Rect bounds = new Rect(state.windowWidth * 0.5f - width * 0.5f + offsetX.number() * unit,
                state.windowHeight * 0.5f + offsetY.number() * unit, width, height);

        EntityView entity = find(state, targetId);
        drawMarker(context, entity);
        drawPanel(entity, bounds, shown);
    }
}
