#pragma once

#include "modules/Module.hpp"

namespace cold::modules {

/* The bind that opens the player window, and nothing else.
 *
 * The tracking behind it runs from the client's own tick whether or not this is
 * enabled - a history that only starts when you open the window is a history
 * that is always empty when you want it. What the module owns is the window. */
class RadarModule : public Module {
public:
    RadarModule();

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onRender(Context& context) override;
};

}  // namespace cold::modules
