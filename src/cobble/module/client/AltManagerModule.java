package cobble.module.client;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Subcategory;
import cobble.render.Icon;
import cobble.ui.Client;
import java.util.List;

public final class AltManagerModule extends Module {
    public AltManagerModule() {
        super("AltManager", "Log in with another account", Category.CLIENT, Subcategory.BASIC);
        setIcon(Icon.USER_ROUND);
    }

    /* One window at a time, the rule the menu and the radar keep: each has a field you type into. */
    @Override
    public void onEnable(Context context) {
        // Opened first, so the menu closing sees the window holding the screen and leaves it up.
        Client.altManager().open();
        for (Class<? extends Module> other : List.of(ClickGuiModule.class, RadarModule.class)) {
            Module window = ModuleManager.get(other);
            if (window != null && window.enabled()) {
                window.setEnabled(false, context);
            }
        }
    }

    @Override
    public void onDisable(Context context) {
        Client.altManager().close();
    }

    @Override
    public void onRender(Context context) {
        // Escape closes the window directly, so the module follows the window.
        if (enabled() && !Client.altManager().interactive()) {
            setEnabled(false, null);
        }
    }
}
