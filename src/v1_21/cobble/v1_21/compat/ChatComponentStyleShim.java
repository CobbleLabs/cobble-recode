package cobble.v1_21.compat;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;

/**
 * 1.8.9's ChatComponentStyle on 1.21.11's MutableText. 1.8's style was the
 * text's own to change; 1.21's is a value, so the text a style was last
 * taken from gets a change made to it back; see ChatStyleShim.
 */
public final class ChatComponentStyleShim {
    static MutableText owner;
    static Style taken;

    private ChatComponentStyleShim() {}

    public static Style getChatStyle(MutableText text) {
        owner = text;
        taken = text.getStyle();
        return taken;
    }
}
