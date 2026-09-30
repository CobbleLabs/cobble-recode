package cobble.module.client;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import cobble.ui.Client;
import cobble.ui.Hud;

public final class HudModule extends Module {
    private final Setting scale;
    private final Setting watermark;
    private final Setting arrayList;
    private final Setting playerInfo;
    private final Setting targetInfo;
    private final Setting notifications;
    private final Setting speed;

    public HudModule() {
        super("HUD", "Watermark and module list", Category.HUD, Subcategory.BASIC);
        setIcon(Icon.MONITOR);
        scale = addNumber("Scale", "Size of the HUD elements", 1.0f, 0.6f, 2.5f, 2);
        watermark = addBoolean("Watermark", "cobble.vip bar in the corner", true);
        arrayList = addBoolean("Module list", "Enabled modules down the right edge", true);
        playerInfo = addBoolean("Player info", "Your stats card at the top", true);
        targetInfo = addBoolean("Target info", "Whoever the combat modules are hitting", true);
        notifications = addBoolean("Notifications", "What the client and the scripts have to say", true);
        speed = addBoolean("Speed / XYZ", "Movement speed and position readout", true);
        setEnabled(true, null);
    }

    @Override
    public void onRender(Context context) {
        Hud hud = Client.hud();
        /*
         * Drain the editor's requests before pushing state down, so a dot clicked in
         * the pause screen lands on the setting that is persisted and shown in the
         * menu. The HUD cannot flip its own copy: the push below would overwrite it
         * on the very next frame. Ordered by Hud.Element.
         */
        Setting[] visibility = {watermark, arrayList, playerInfo, targetInfo, speed};
        for (int element = hud.takeToggle(); element >= 0; element = hud.takeToggle()) {
            if (element < visibility.length) {
                visibility[element].setBool(!visibility[element].bool());
            }
        }
        hud.setScale(scale.number());
        hud.setShowWatermark(watermark.bool());
        hud.setShowArrayList(arrayList.bool());
        hud.setShowPlayerInfo(playerInfo.bool());
        hud.setShowTargetInfo(targetInfo.bool());
        hud.setShowNotifications(notifications.bool());
        hud.setShowSpeed(speed.bool());
    }
}
