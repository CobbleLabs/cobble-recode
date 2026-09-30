package cobble.module;

/** The tier a module is filed under inside its tab. GENERAL is also the view that lists every tier. */
public enum Subcategory {
    GENERAL("General"),
    BASIC("Basic"),
    ADVANCED("Advanced"),
    EXTREME("Extreme");

    public final String displayName;

    Subcategory(String displayName) {
        this.displayName = displayName;
    }

    public boolean shows(Module module) {
        return this == GENERAL || module.subcategory == this;
    }
}
