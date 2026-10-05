package cobble.v1_21.compat;

import net.minecraft.text.Style;
import net.minecraft.util.Formatting;

/** 1.8.9's ChatStyle on 1.21.11's Style, which is immutable; see ChatComponentStyleShim. */
public final class ChatStyleShim {
    private ChatStyleShim() {}

    public static Style setColor(Style style, Formatting color) {
        Style coloured = style.withColor(color);
        if (style == ChatComponentStyleShim.taken) {
            ChatComponentStyleShim.owner.setStyle(coloured);
            ChatComponentStyleShim.taken = coloured;
        }
        return coloured;
    }
}
