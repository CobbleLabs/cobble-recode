#pragma once

#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's Displace, cut down to the half that matters.
 *
 * Knockback is dealt along the attacker's yaw, not along the line between the
 * two players. Turning ninety degrees on the tick a hit lands therefore throws
 * the target sideways instead of backwards, and walking forward on that same
 * rotation carries you around them rather than into them. Every other tick is
 * spent turned back, so the movement the server sees is a strafe rather than a
 * player facing a wall.
 *
 * The void finder is what turns that from a nuisance into a kill: rather than a
 * fixed offset, the yaw is chosen to point at the nearest column of nothing, so
 * the knockback goes off the edge. Static scans rings around the target for the
 * closest hole; Dynamic scores whole lanes leading away from them and picks the
 * one with the most air along it and no wall in the way.
 *
 * The original's blink, teammate list and item conditions are gone - the ask
 * was the void finder and simpler settings. */
class Displace : public Module {
public:
    Displace();

    std::string suffix() const override;

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;
    void onRender(Context& context) override;

private:
    /* Yaw pointing past the target at the nearest air column, or nothing. */
    bool staticVoidYaw(Context& context, const game::FrameState& state,
                       const game::EntityView& target, float& yaw);
    /* Yaw along the lane with the most void in it, or nothing. */
    bool dynamicVoidYaw(Context& context, const game::FrameState& state,
                        const game::EntityView& target, float& yaw);
    bool voidColumn(Context& context, jobject world, double x, double y, double z) const;
    const game::EntityView* pickTarget(const game::FrameState& state) const;
    void releaseMovement(Context& context);

    Setting* mode_;
    Setting* yawOffset_;
    Setting* direction_;
    Setting* delay_;
    Setting* findVoid_;
    Setting* range_;
    Setting* showDirection_;

    bool displaceTick_ = false;
    bool compensate_ = false;
    bool left_ = false;
    bool forwardHeld_ = false;
    bool strafeHeld_ = false;
    /* What the player was holding when the module borrowed the key. A binding
     * is sticky - releasing one they are leaning on would take their movement
     * away until they let go and pressed it again. */
    bool forwardWasTheirs_ = false;
    bool strafeWasTheirs_ = false;
    /* Which side was actually pressed. The void scan can pick the other side
     * between the press and the release, and letting go of the key that was
     * never pressed would leave the real one held down. */
    bool strafeLeft_ = false;
    int holdTicks_ = 0;
    int tick_ = 0;
    int windowStart_ = -1;
    int windowTargetId_ = -1;

    /* What the arrow draws, kept between the tick that decides it and the frame
     * that draws it. */
    bool arrowValid_ = false;
    float arrowYaw_ = 0.0f;
    int arrowTargetId_ = -1;
    float arrowFade_ = 0.0f;
};

}  // namespace cold::modules
