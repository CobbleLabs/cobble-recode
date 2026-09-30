#pragma once

#include "core/Time.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's Fast Place.
 *
 * Vanilla parks the use key for four ticks after every right click, which is
 * the only thing standing between a held button and a block a tick. The module
 * is one field write: `MinecraftClient.itemUseCooldown` is set to the chosen
 * number of ticks just before the game reads it, so the game itself does the
 * placing, at its own moment, in the vanilla packet order.
 *
 * The conditions are the original's, and they exist because a shortened
 * cooldown applied to the wrong thing is obvious: fired while holding a bow it
 * would spam the draw, and fired while looking at a chest it would reopen the
 * screen every tick. */
class FastPlace : public Module {
public:
    FastPlace();

    void onEnable(Context& context) override;
    void onTick(Context& context) override;

private:
    bool allowed(Context& context) const;

    Setting* delay_;
    Setting* activation_;
    Setting* blocksOnly_;
    Setting* pitchCheck_;

    Timer heldSince_;
    bool wasHeld_ = false;
};

}  // namespace cold::modules
