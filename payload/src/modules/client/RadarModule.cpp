#include "modules/client/RadarModule.hpp"

#include "hooks/Input.hpp"
#include "modules/ModuleManager.hpp"
#include "modules/client/ClickGuiModule.hpp"
#include "ui/Client.hpp"
#include "ui/Radar.hpp"

namespace cold::modules {

RadarModule::RadarModule()
    : Module("Radar", "Everybody in a loaded chunk, and what they are carrying", Category::Client,
             hooks::kKeyRightControl) {
    setIcon(assets::IconAsset::Radio);
}

void RadarModule::onEnable(Context& context) {
    /* One window at a time. Both are full panels with a field you type into, and
     * two of those competing for the keyboard is a worse answer than either. */
    if (auto* menu = modules().get<ClickGuiModule>(); menu != nullptr && menu->enabled()) {
        menu->setEnabled(false, &context);
    }
    ui::client().radar().open();
}

void RadarModule::onDisable(Context&) { ui::client().radar().close(); }

void RadarModule::onRender(Context&) {
    /* Escape closes the window directly, so the module follows the window
     * rather than the other way round - the same rule the menu works to. */
    if (enabled() && !ui::client().radar().interactive()) {
        setEnabled(false, nullptr);
    }
}

}  // namespace cold::modules
