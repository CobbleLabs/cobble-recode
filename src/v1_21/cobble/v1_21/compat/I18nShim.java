package cobble.v1_21.compat;

import net.minecraft.client.resource.language.I18n;

/** 1.8.9's I18n on 1.21.11's. */
public final class I18nShim {
    private I18nShim() {}

    public static String format(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }
}
