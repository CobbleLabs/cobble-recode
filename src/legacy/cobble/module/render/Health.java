package cobble.module.render;

import cobble.core.Color;
import cobble.core.Rect;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Subcategory;
import cobble.render.Align;
import cobble.render.FontRole;
import cobble.render.Renderer;
import java.util.Locale;
import net.minecraft.client.entity.EntityPlayerSP;

/** Your health under the crosshair, in hearts to the half, green to yellow to red as it drops. */
public final class Health extends LegacyModule {
    private static final float TEXT_SIZE = 9.0f;
    private static final float OFFSET_Y = 14.0f;
    private static final Color SHADOW = Color.rgb(0x05070A, 0.75f);

    public Health() {
        super("Health", "Displays your health in the center of your screen", Category.HUD, Subcategory.BASIC);
    }

    @Override
    public void onRender(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.currentScreen != null) {
            return;
        }
        double hearts = player.getHealth() / 2.0;
        boolean absorbing = player.getAbsorptionAmount() > 0.0f;
        hearts += player.getAbsorptionAmount() / 2.0;
        String text = String.format(Locale.ROOT, "%.1f", Math.floor((hearts + 0.25) / 0.5) * 0.5).replace(".0", "");
        Color tint = hearts >= 7.0 ? Color.rgb(0x02BE3A) : hearts > 4.0 ? Color.rgb(0xFFF912) : Color.rgb(0xFF1414);

        Renderer gfx = Renderer.instance();
        float unit = context.state.scaleFactor;
        float size = TEXT_SIZE * unit;
        float heart = size * 0.8f;
        float gap = size * 0.3f;
        float width = gfx.measure(FontRole.SEMI_BOLD, text, size) + gap + heart;
        float x = context.state.windowWidth * 0.5f - width * 0.5f;
        float y = context.state.windowHeight * 0.5f + OFFSET_Y * unit;
        gfx.text(FontRole.SEMI_BOLD, text, x + 0.7f * unit, y + 0.7f * unit, size, SHADOW, Align.LEFT);
        float end = gfx.text(FontRole.SEMI_BOLD, text, x, y, size, tint, Align.LEFT);
        drawHeart(gfx, end + gap, y + (gfx.lineHeight(FontRole.SEMI_BOLD, size) - heart) * 0.5f, heart,
                absorbing ? Color.rgb(0xFFAA00) : Color.rgb(0xFF5555));
    }

    /* Two round lobes over a diamond whose lower point is the heart's tip. */
    private static void drawHeart(Renderer gfx, float x, float y, float size, Color colour) {
        float lobe = size * 0.55f;
        gfx.rect(new Rect(x, y, lobe, lobe), colour, lobe * 0.5f);
        gfx.rect(new Rect(x + size - lobe, y, lobe, lobe), colour, lobe * 0.5f);
        float middle = x + size * 0.5f;
        float waist = y + lobe * 0.5f;
        gfx.face(new float[] {x + size * 0.02f, middle, x + size * 0.98f, middle},
                new float[] {waist, y + lobe * 0.35f, waist, y + size}, colour);
    }
}
