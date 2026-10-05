package cobble.v1_21.compat;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/** 1.8.9's ChatComponentText as 1.21.11's literal text. */
public final class ChatComponentTextShim {
    private ChatComponentTextShim() {}

    public static MutableText create(String text) {
        return Text.literal(text);
    }
}
