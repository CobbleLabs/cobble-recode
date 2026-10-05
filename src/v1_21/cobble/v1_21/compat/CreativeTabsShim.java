package cobble.v1_21.compat;

/** 1.8.9's CreativeTabs, which only listed item subtypes; 1.21.11 items have none, so there is no tab to name. */
public final class CreativeTabsShim {
    private CreativeTabsShim() {}

    public static Absent tabAllSearch() {
        return null;
    }
}
