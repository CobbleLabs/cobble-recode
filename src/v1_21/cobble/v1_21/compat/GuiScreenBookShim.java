package cobble.v1_21.compat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.text.Text;

/** 1.8.9's GuiScreenBook on 1.21.11's BookScreen. */
public final class GuiScreenBookShim {
    /* Private; they run under their intermediary names, which the build leaves in strings. */
    private static final Field CONTENTS = field("field_17418");
    private static final Field PAGE = field("field_17119");

    private GuiScreenBookShim() {}

    private static Field field(String name) {
        try {
            Field field = BookScreen.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("BookScreen." + name + " not found", e);
        }
    }

    /** The lines of the open page, which 1.8 kept for its click events. */
    public static List<Text> field_175386_A(BookScreen screen) {
        List<Text> lines = new ArrayList<>();
        try {
            BookScreen.Contents contents = (BookScreen.Contents) CONTENTS.get(screen);
            int page = PAGE.getInt(screen);
            if (page >= 0 && page < contents.getPageCount()) {
                for (String line : contents.getPage(page).getString().split("\n")) {
                    lines.add(Text.literal(line));
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return lines;
    }
}
