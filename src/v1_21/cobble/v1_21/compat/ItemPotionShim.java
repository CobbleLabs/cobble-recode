package cobble.v1_21.compat;

/** 1.8.9's ItemPotion statics, against the metadata {@link ItemStackShim} hands out. */
public final class ItemPotionShim {
    static final int SPLASH = 16384;

    private ItemPotionShim() {}

    public static boolean isSplash(int metadata) {
        return (metadata & SPLASH) != 0;
    }
}
