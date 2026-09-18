#pragma once

#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's Fast Mine, rebuilt on what a native payload can actually reach.
 *
 * The original is two mixins: one shortens `blockBreakingCooldown`, the other
 * scales the value `getBlockBreakingSpeed` returns. There is no bytecode here,
 * so only the first is a field write - and the second turns out not to need
 * one. `updateBlockBreakingProgress` adds one tick of progress to the block
 * under the crosshair every time it is called, and the game calls it once a
 * tick; calling it again from the tick edge is worth exactly one more tick of
 * progress, so the multiplier is a call count.
 *
 * Both halves are visible to an anticheat by construction - breaking a block in
 * a third of the time it takes is the definition of the check - so the speed
 * slider starts at one and the delay is the half worth having. */
class FastMine : public Module {
public:
    FastMine();

    std::string suffix() const override;
    void onTick(Context& context) override;

private:
    Setting* delay_;
    Setting* speed_;
    Setting* requireAttack_;

    /* Carries the fractional part of the multiplier between ticks, so 1.5x is
     * an extra call on every other tick rather than none at all. */
    float accumulator_ = 0.0f;
};

}  // namespace cold::modules
