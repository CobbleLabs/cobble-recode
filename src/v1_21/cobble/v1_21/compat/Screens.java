package cobble.v1_21.compat;

import cobble.v1_21.MenuScreen;

/** Screen tests the legacy code makes against its own 1.8.9 screens. */
public final class Screens {
    private Screens() {}

    public static boolean isMenu(Object screen) {
        return screen instanceof MenuScreen;
    }
}
