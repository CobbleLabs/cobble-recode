package cobble.module;

/** The tier a module is filed under inside its tab. ALL is the view that lists every tier. */
public enum Subcategory {
    ALL("All"),
    BASIC("Basic"),
    MEDIUM("Medium"),
    ADVANCED("Advanced");

    public final String displayName;

    Subcategory(String displayName) {
        this.displayName = displayName;
    }

    public boolean shows(Module module) {
        return this == ALL || module.subcategory == this;
    }
}
