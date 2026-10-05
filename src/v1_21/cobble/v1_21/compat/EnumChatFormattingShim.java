package cobble.v1_21.compat;

import net.minecraft.util.Formatting;

/** 1.8.9's EnumChatFormatting on 1.21.11's Formatting, which keeps its names. */
public final class EnumChatFormattingShim {
    private EnumChatFormattingShim() {}

    public static String getTextWithoutFormattingCodes(String text) {
        return Formatting.strip(text);
    }
}
