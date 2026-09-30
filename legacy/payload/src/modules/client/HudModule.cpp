#include "modules/client/HudModule.hpp"

#include <iterator>

#include "ui/Client.hpp"
#include "ui/Hud.hpp"

namespace cold::modules {

HudModule::HudModule() : Module("HUD", "Watermark and module list", Category::Client, 0) {
    setIcon(assets::IconAsset::Monitor);

    scale_ = &addNumber("Scale", "Size of the HUD elements", 1.0f, 0.6f, 2.5f, 2);
    watermark_ = &addBoolean("Watermark", "cobble.vip bar in the corner", true);
    arrayList_ = &addBoolean("Module list", "Enabled modules down the right edge", true);
    playerInfo_ = &addBoolean("Player info", "Your stats card at the top", true);
    targetInfo_ = &addBoolean("Target info", "Whoever the combat modules are hitting", true);
    notifications_ =
        &addBoolean("Notifications", "What the client and the scripts have to say", true);
    speed_ = &addBoolean("Speed / XYZ", "Movement speed and position readout", true);

    setEnabled(true, nullptr);
}

void HudModule::onRender(Context&) {
    ui::Hud& hud = ui::client().hud();

    /* Drain the editor's requests before pushing state down, so a dot clicked in the
     * pause screen lands on the setting that is persisted and shown in the menu. The
     * HUD cannot flip its own copy: the push below would overwrite it on the very
     * next frame. Ordered by ui::HudElement. */
    Setting* const visibility[] = {watermark_, arrayList_, playerInfo_, targetInfo_, speed_};
    int element = 0;
    while (hud.takeToggle(element)) {
        if (element >= 0 && element < static_cast<int>(std::size(visibility))) {
            Setting* setting = visibility[element];
            if (setting != nullptr) {
                setting->setBoolean(!setting->booleanValue());
            }
        }
    }

    hud.setScale(scale_->number());
    hud.setShowWatermark(watermark_->booleanValue());
    hud.setShowArrayList(arrayList_->booleanValue());
    hud.setShowPlayerInfo(playerInfo_->booleanValue());
    hud.setShowTargetInfo(targetInfo_->booleanValue());
    hud.setShowNotifications(notifications_->booleanValue());
    hud.setShowSpeed(speed_->booleanValue());
}

}  // namespace cold::modules
