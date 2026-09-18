#pragma once

#include "core/Time.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's Bridge Assist.
 *
 * Two independent halves, both of which are things a player does with their own
 * hands and neither of which sends a packet of its own:
 *
 *  - The sneak. Minecraft stops a sneaking player at the edge of a block, so
 *    bridging safely means shift on while the feet are over nothing and off
 *    again the moment they are not. The test is run against where the player
 *    will be next tick rather than where they are, because a decision made on
 *    the current position arrives a tick after the step off the edge.
 *  - The pre-place. A rotation is chosen that puts the crosshair on the side
 *    face of the block being stood on, which is the face a bridge block goes
 *    against. The click stays the player's own; only the aim is helped.
 *
 * The delays exist because a sneak that snaps on and off on exact tick
 * boundaries is a square wave nobody's hand produces. */
class BridgeAssist : public Module {
public:
    BridgeAssist();

    std::string suffix() const override;

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;

private:
    /* Chebyshev distance from the predicted feet centre to the nearest ground
     * the hitbox still overlaps, or a negative number when there is no ground
     * under it at all - which is the case the sneak exists for. */
    double edgeDistance(Context& context, const game::FrameState& state) const;
    bool conditionsMet(Context& context, const game::FrameState& state) const;
    /* Whether the player is asking to sneak, physically. In "hold sneak" mode
     * the module clears the binding for the ticks it is safe to run, so the
     * binding cannot answer this - it was the module that cleared it. */
    bool wantsSneak(Context& context) const;
    void holdSneak(Context& context);
    void releaseSneak(Context& context, bool immediately);
    /* Aims at the side face of the support block, the way a bridge is built. */
    void prePlace(Context& context, const game::FrameState& state);

    Setting* mode_;
    Setting* edgeOffset_;
    Setting* unsneakDelay_;
    Setting* sneakOnJump_;
    Setting* holdingBlocks_;
    Setting* lookingDown_;
    Setting* notMovingForward_;
    Setting* prePlace_;
    Setting* prePlaceSpeed_;

    Random random_{0x2B7E1516u};
    bool sneaking_ = false;
    /* Ticks left before the sneak may be dropped. Seeded from the delay
     * sliders with a coin flip on the fractional tick, so the same setting does
     * not produce the same integer every time. */
    int releaseHold_ = 0;
    int jumpHold_ = 0;
    bool wasJumping_ = false;
    /* The release countdown is seeded once, on the tick the feet come back
     * over solid ground. Re-seeding it every tick that followed would hold the
     * sneak down forever. */
    bool overEdge_ = false;
};

}  // namespace cold::modules
