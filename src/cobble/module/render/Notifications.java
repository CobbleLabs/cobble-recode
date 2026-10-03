package cobble.module.render;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import cobble.ui.Client;
import cobble.ui.Hud;

/** The cards the client and the scripts post; the HUD draws them, this switches and sizes them. */
public final class Notifications extends Module {
    private final Setting scale = addNumber("Scale", "How large the cards are drawn", 1.0f, 0.5f, 2.5f, 2);

    public Notifications() {
        super("Notifications", "Cards for what the client and the scripts have to say", Category.HUD,
                Subcategory.BASIC);
        setIcon(Icon.MONITOR);
        setEnabled(true, null);
    }

    @Override
    public void onEnable(Context context) {
        Client.hud().setShowNotifications(true);
    }

    @Override
    public void onDisable(Context context) {
        Client.hud().setShowNotifications(false);
    }

    @Override
    public void onRender(Context context) {
        Client.hud().bindScale(Hud.Element.NOTIFICATIONS, scale);
    }
}
