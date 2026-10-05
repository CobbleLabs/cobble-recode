package cobble.v1_21.compat;

import java.util.Optional;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

/** 1.8.9's IChatComponent on 1.21.11's Text. */
public final class IChatComponentShim {
    private IChatComponentShim() {}

    public static String getUnformattedText(Text text) {
        return text.getString();
    }

    /** The text with 1.8's section-sign codes in front of every styled run, as 1.8 printed it. */
    public static String getFormattedText(Text text) {
        StringBuilder out = new StringBuilder();
        text.visit((style, part) -> {
            out.append(codes(style)).append(part);
            return Optional.empty();
        }, Style.EMPTY);
        return out.toString();
    }

    private static String codes(Style style) {
        StringBuilder codes = new StringBuilder();
        TextColor color = style.getColor();
        if (color != null) {
            Formatting formatting = Formatting.byName(color.getName());
            if (formatting != null) {
                codes.append(formatting);
            }
        }
        if (style.isBold()) {
            codes.append(Formatting.BOLD);
        }
        if (style.isItalic()) {
            codes.append(Formatting.ITALIC);
        }
        if (style.isUnderlined()) {
            codes.append(Formatting.UNDERLINE);
        }
        if (style.isStrikethrough()) {
            codes.append(Formatting.STRIKETHROUGH);
        }
        if (style.isObfuscated()) {
            codes.append(Formatting.OBFUSCATED);
        }
        return codes.toString();
    }

    /* 1.8 appended in place; only a mutable text can be changed in place here. */
    public static Text appendSibling(Text text, Text sibling) {
        return text instanceof MutableText mutable ? mutable.append(sibling) : text.copy().append(sibling);
    }
}
