package cobble.module.client;

import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.render.Icon;
import cobble.ui.Client;

public final class RadarModule extends Module {
    public RadarModule() {
        super("Radar", "Everybody in a loaded chunk, and what they are carrying", Category.CLIENT,
                Input.KEY_RIGHT_CONTROL);
        setIcon(Icon.RADIO);
    }

    /*
     * One window at a time. Both are full panels with a field you type into, and
     * two of those competing for the keyboard is a worse answer than either.
     */
    @Override
    public void onEnable(Context context) {
        ClickGuiModule menu = ModuleManager.get(ClickGuiModule.class);
        if (menu != null && menu.enabled()) {
            menu.setEnabled(false, context);
        }
        Client.radar().open();
    }

    @Override
    public void onDisable(Context context) {
        Client.radar().close();
    }

    @Override
    public void onRender(Context context) {
        // Escape closes the window directly, so the module follows the window, the same rule the menu works to.
        if (enabled() && !Client.radar().interactive()) {
            setEnabled(false, null);
        }
    }
}
