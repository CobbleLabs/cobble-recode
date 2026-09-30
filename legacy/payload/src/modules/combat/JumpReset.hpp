#pragma once

#include "core/Time.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's Jump Reset, port for port.
 *
 * Knockback is applied to a player on the ground differently from one who is
 * not: leaving the ground on the tick the hit lands trades the ground friction
 * that would otherwise eat the horizontal push for a full arc away from whoever
 * threw it. The whole module is therefore one press of the jump binding on the
 * tick `hurtTime` goes up, and one release the tick after.
 *
 * It presses the game's own key rather than calling `jump()`: a jump the input
 * system knows about is one the movement code, the sprint logic and the
 * server's own reconstruction all agree happened. */
class JumpReset : public Module {
public:
    JumpReset();

    std::string suffix() const override;

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;

private:
    /* The effects that make a jump worse than standing still: jump boost
     * changes the arc the server expects, poison and wither keep ticking
     * damage while you are in the air with no way to reset it. */
    bool badEffect(Context& context) const;
    bool aimingAtPlayer(Context& context) const;
    /* Knockback pushes you away from whoever hit you, so the useful case is the
     * one where you are still broadly facing them. */
    bool pushedBackwards(const game::FrameState& state) const;

    Setting* chance_;
    Setting* requireAttack_;
    Setting* requireForward_;
    Setting* requireAim_;

    Random random_{0x51ED2701u};
    int lastHurtTime_ = 0;
    double lastFallDistance_ = 0.0;
    bool ignoreNext_ = false;
    bool holdingJump_ = false;
    /* Whether the player was already holding jump when the module pressed it.
     * Releasing a key they are still leaning on would be worse than never
     * having pressed it. */
    bool jumpWasTheirs_ = false;
};

}  // namespace cold::modules
