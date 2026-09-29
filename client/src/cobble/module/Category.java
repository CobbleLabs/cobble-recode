package cobble.module;

import cobble.render.Icon;

public enum Category {
    COMBAT("Combat", Icon.SWORDS),
    MOVEMENT("Movement", Icon.FOOTPRINTS),
    PLAYER("Player", Icon.USER_ROUND),
    RENDER("Render", Icon.EYE),
    WORLD("World", Icon.BOX),
    MINIGAMES("Minigames", Icon.FLAME),
    OTHER("Other", Icon.WRENCH),
    CLIENT("Client", Icon.SNOWFLAKE),
    SCRIPTS("Scripts", Icon.BRACES);

    public final String displayName;
    public final Icon icon;

    Category(String displayName, Icon icon) {
        this.displayName = displayName;
        this.icon = icon;
    }
}
