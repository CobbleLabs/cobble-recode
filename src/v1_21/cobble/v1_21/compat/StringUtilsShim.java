package cobble.v1_21.compat;

import net.minecraft.util.StringHelper;

/** 1.8.9's StringUtils on 1.21.11's StringHelper. */
public final class StringUtilsShim {
    private StringUtilsShim() {}

    public static String stripControlCodes(String text) {
        return StringHelper.stripTextFormat(text);
    }
}
