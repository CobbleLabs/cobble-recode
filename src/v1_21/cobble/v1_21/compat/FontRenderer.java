package cobble.v1_21.compat;

/**
 * 1.8.9's FontRenderer over {@link GlEmu}: text goes out in the overlay's
 * font at the size 1.8's would have had, and widths are measured the same
 * way, so layouts built on them still line up.
 */
public final class FontRenderer {
    static final FontRenderer INSTANCE = new FontRenderer();

    public final int FONT_HEIGHT = (int) GlEmu.FONT_HEIGHT;

    private FontRenderer() {}

    public int drawString(String text, float x, float y, int color, boolean shadow) {
        return (int) GlEmu.text(text, x, y, color, shadow);
    }

    public int drawStringWithShadow(String text, float x, float y, int color) {
        return drawString(text, x, y, color, true);
    }

    public int getStringWidth(String text) {
        return text == null ? 0 : Math.round(GlEmu.textWidth(stripCodes(text)));
    }

    public int getCharWidth(char c) {
        return Math.round(GlEmu.textWidth(String.valueOf(c)));
    }

    /** The RGB a section-sign colour code stands for, or -1. */
    public int getColorCode(char code) {
        return GlEmu.codeColor(code);
    }

    /** The codes in force at the end of the text, as 1.8 worked them out: a colour clears the styles before it. */
    public static String getFormatFromString(String text) {
        StringBuilder format = new StringBuilder();
        for (int index = text.indexOf('\u00a7'); index >= 0 && index + 1 < text.length();
                index = text.indexOf('\u00a7', index + 1)) {
            char code = Character.toLowerCase(text.charAt(index + 1));
            if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                format.setLength(0);
                format.append('\u00a7').append(code);
            } else if (code >= 'k' && code <= 'o' || code == 'r') {
                format.append('\u00a7').append(code);
            }
        }
        return format.toString();
    }

    private static String stripCodes(String text) {
        return text.replaceAll("\u00a7.", "");
    }
}
