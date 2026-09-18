#include "modules/client/ClickGuiModule.hpp"

#include "hooks/Input.hpp"
#include "modules/ModuleManager.hpp"
#include "modules/client/RadarModule.hpp"
#include "ui/ClickGui.hpp"
#include "ui/Client.hpp"
#include "ui/Theme.hpp"

namespace cold::modules {

ClickGuiModule::ClickGuiModule()
    : Module("ClickGUI", "Opens the Cobble Recode menu", Category::Client, hooks::kKeyInsert) {
    setIcon(assets::IconAsset::SlidersHorizontal);

    blur_ = &addBoolean("Backdrop blur", "Frost the scene behind the panels", false);
    accent_ = &addColour("Accent", "Drives the menu, the HUD and the ESP", Color::rgb(0xBDA4BD));
}

void ClickGuiModule::onEnable(Context& context) {
    /* The radar is the other full window; only one of them holds the keyboard. */
    if (auto* radar = modules().get<RadarModule>(); radar != nullptr && radar->enabled()) {
        radar->setEnabled(false, &context);
    }
    ui::ClickGui& gui = ui::client().gui();
    gui.setBlur(blur_->booleanValue());
    ui::setAccent(accent_->colour());
    gui.open();
}

void ClickGuiModule::onDisable(Context&) { ui::client().gui().close(); }

void ClickGuiModule::onRender(Context&) {
    ui::ClickGui& gui = ui::client().gui();
    gui.setBlur(blur_->booleanValue());
    ui::setAccent(accent_->colour());

    /* Escape closes the menu directly, so the module state follows the menu
     * rather than the other way round. */
    if (enabled() && !gui.interactive()) {
        setEnabled(false, nullptr);
    }
}

}  // namespace cold::modules
