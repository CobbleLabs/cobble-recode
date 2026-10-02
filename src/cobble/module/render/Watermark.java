package cobble.module.render;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import cobble.ui.Client;
import cobble.ui.Hud;
import cobble.ui.Theme;
import cobble.util.font.FontManager;
import cobble.util.font.IFont;
import java.awt.Color;
import net.minecraft.client.renderer.GlStateManager;

/**
 * The client's name, each letter a step along the Interface theme's gradient,
 * the gradient drifting over time. Top left unless dragged elsewhere, standing
 * against whichever edge it is nearest.
 */
public final class Watermark extends Module {
    private final Setting scale = addNumber("Scale", "How large the watermark is drawn", 1.0f, 0.5f, 2.5f, 2);
    private final Setting shadow = addBoolean("Drop Shadow", "Render the name with a shadow", true);

    public Watermark() {
        super("Watermark", "The client's name in the theme's gradient", Category.HUD, Subcategory.BASIC);
        setIcon(Icon.MONITOR);
        setEnabled(true, null);
    }

    @Override
    public void onRender(Context context) {
        Hud hud = Client.hud();
        hud.bindScale(Hud.Element.WATERMARK, scale);
        Interface.inGuiSpace((width, height) -> draw(hud, width, height));
    }

    private void draw(Hud hud, float screenWidth, float screenHeight) {
        IFont font = FontManager.getLineSeedBold(18.0f);
        String text = Theme.CLIENT_SHORT_NAME;
        float width = font.getStringWidth(text);
        float height = font.getFontHeight();
        Hud.Anchor anchor = hud.anchor(Hud.Element.WATERMARK, 5.0f, 5.0f, screenWidth, screenHeight);
        float size = anchor.scale();
        float x = Hud.keepOnScreen(anchor.x(), width * size, anchor.left(), screenWidth);
        float y = Hud.keepOnScreen(anchor.y(), height * size, anchor.top(), screenHeight);
        Interface theme = ModuleManager.get(Interface.class);
        Color from = theme.getThemeColor1();
        Color to = theme.getThemeColor2();
        long now = System.currentTimeMillis();

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(size, size, 1.0f);
        float cursor = anchor.left() ? 0.0f : -width;
        float top = anchor.top() ? 0.0f : -height;
        for (int index = 0; index < text.length(); ++index) {
            String letter = String.valueOf(text.charAt(index));
            double phase = Math.sin(now / 600.0 + index * 0.45) * 0.5 + 0.5;
            font.drawString(letter, cursor, top, Interface.interpolate(from, to, phase).getRGB(), shadow.bool());
            cursor += font.getStringWidth(letter);
        }
        GlStateManager.popMatrix();
        hud.placeGui(Hud.Element.WATERMARK, anchor, x, y, width * size, height * size);
    }
}
