package cobble.module.client;

import cobble.core.Color;
import cobble.hook.Input;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import cobble.ui.ClickGui;
import cobble.ui.Client;
import cobble.ui.Theme;

public final class ClickGuiModule extends Module {
    private final Setting blur;
    private final Setting accent;

    public ClickGuiModule() {
        super("ClickGUI", "Opens the Cobble Recode menu", Category.CLIENT, Subcategory.BASIC, Input.KEY_INSERT);
        setIcon(Icon.SLIDERS_HORIZONTAL);
        blur = addBoolean("Backdrop blur", "Frost the scene behind the panels", false);
        accent = addColour("Accent", "Drives the menu, the HUD and the ESP", Color.rgb(0xBDA4BD));
    }

    @Override
    public void onEnable(Context context) {
        ClickGui gui = Client.gui();
        gui.setBlur(blur.bool());
        Theme.setAccent(accent.colour());
        gui.open();
        /* The radar is the other full window; only one of them holds the
         * keyboard. Closed after the menu opens, so it leaves the screen up. */
        RadarModule radar = ModuleManager.get(RadarModule.class);
        if (radar != null && radar.enabled()) {
            radar.setEnabled(false, context);
        }
    }

    @Override
    public void onDisable(Context context) {
        Client.gui().close();
    }

    @Override
    public void onRender(Context context) {
        ClickGui gui = Client.gui();
        gui.setBlur(blur.bool());
        Theme.setAccent(accent.colour());
        // Escape closes the menu directly, so the module state follows the menu rather than the other way round.
        if (enabled() && !gui.interactive()) {
            setEnabled(false, null);
        }
    }
}
