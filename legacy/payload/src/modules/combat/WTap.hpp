#pragma once

#include "core/Time.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* W-tap, skidded from Argon's AutoWTap. Every landed hit costs a tap: W goes
 * up for a couple hundred milliseconds and comes back down, so sprint drops
 * and rebuilds around the swing the way a manual w-tap does it. The tap only
 * starts on a strike our own TriggerBot landed, while sprinting on the ground
 * with W physically held - jumping or letting go aborts it and hands W back.
 * Vanishingly Grim-visible: it presses the game's own key, and the sprint
 * packets that follow are the ones vanilla sends for the same input. */
class WTap : public Module {
public:
    WTap();

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;

private:
    Setting* delayMin_;
    Setting* delayMax_;
    Setting* inAir_;

    Random random_{0x7AC401u};
    int lastHits_ = 0;
    bool tapping_ = false;
    bool logged_ = false;
    long long tapEnd_ = 0;
};

}  // namespace cold::modules
