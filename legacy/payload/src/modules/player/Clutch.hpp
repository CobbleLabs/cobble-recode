#pragma once

#include "core/Time.hpp"
#include "game/Raycast.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's Clutch.
 *
 * Held down over a drop, it finds a block face within reach, turns to it and
 * clicks - the thing a player does at the bottom of a fall and almost never
 * does in time. Auto Clutch does the same thing unprompted, armed by taking a
 * hit and by a fall prediction that says the ground is far enough away to be
 * worth the block.
 *
 * The aim is not guessed at. Every candidate rotation is cast against the block
 * grid before it is chosen, and the placement only goes out on a tick where the
 * rotation the server already holds lands on a face that can be built off - the
 * same rule the scaffold works to, and the reason the click survives a
 * reconstruction on the far end. See [[block-placement-hit-vector]].
 *
 * The click itself is queued onto the vanilla binding rather than sent from
 * here. Minecraft places from `handleInputEvents` at the top of the client tick
 * and sends the movement packet from the player tick straight after, so a
 * vanilla placement always sits just in front of a movement packet; one sent
 * from the frame hook sits just behind one, which is what Grim's Post check
 * counts. Held down beside a wall, the direct version raised several hundred
 * Post alerts in a couple of minutes against a real GrimAC server. Queuing a
 * press instead makes the game place, at its own moment, at the face the aim is
 * already confirmed to be on.
 *
 * While an aim is live the attack and use bindings are held down for the player
 * and given back on release, which is what stops a held right mouse from
 * placing at the raw crosshair a frame before the confirmed rotation lands. */
class Clutch : public Module {
public:
    Clutch();

    std::string suffix() const override;

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;

private:
    struct Aim {
        float yaw = 0.0f;
        float pitch = 0.0f;
        int x = 0;
        int y = 0;
        int z = 0;
        game::BlockFace face = game::BlockFace::Up;
        Vec3 point;
    };

    /* Vanilla free fall, stepped the way `Entity.move` steps it: gravity, a
     * sub-stepped per-axis move against the block grid, then drag. Sub-stepping
     * is the whole point - a fall integrated in one jump per tick tunnels
     * straight through the floor at three blocks a tick and reports a drop that
     * never ends. */
    struct Body {
        Vec3 pos;
        Vec3 motion;
        bool onGround = false;
    };

    Body bodyOf(const game::FrameState& state) const;
    void step(JNIEnv* env, jobject world, Body& body, bool stopHorizontal) const;

    bool willFallFar(Context& context, const game::FrameState& state, double minimum) const;
    bool willFallSoon(Context& context, const game::FrameState& state) const;
    Vec3 futurePosition(Context& context, jobject world, const game::FrameState& state) const;

    /* Best face within reach to build off, or nothing. */
    bool search(Context& context, const game::FrameState& state, Aim& out) const;
    bool faceAim(Context& context, jobject world, const game::FrameState& state, int cellX,
                 int cellY, int cellZ, bool underPlayer, int& budget, Aim& out) const;
    bool underPlayer(const game::FrameState& state, int cellX, int cellY, int cellZ) const;
    /* Nothing under the feet, which is the whole precondition for a clutch. */
    bool overAir(Context& context, const game::FrameState& state) const;
    /* Whether a block placed against this face has somewhere to go. */
    bool placeable(Context& context, jobject world, const game::FrameState& state,
                   const game::BlockHit& hit) const;

    int blockSlot(Context& context, const game::FrameState& state) const;
    void updateAuto(Context& context, const game::FrameState& state);
    void restoreSlot(Context& context);
    void takeBindings(Context& context);
    void giveBindings(Context& context);

    Setting* reach_;
    Setting* speed_;
    Setting* snapback_;
    Setting* maxBlocks_;
    Setting* tolerance_;
    Setting* predict_;
    Setting* clickMode_;
    Setting* silent_;
    Setting* auto_;
    Setting* minimumFall_;
    Setting* bind_;

    mutable Random random_{0xA5C3D19Fu};

    bool holdingAim_ = false;
    Aim aim_;
    int placed_ = 0;
    int previousSlot_ = -1;
    int plannedSlot_ = -1;
    bool bindingsTaken_ = false;

    bool hasLastPlaced_ = false;
    int lastPlacedX_ = 0;
    int lastPlacedY_ = 0;
    int lastPlacedZ_ = 0;

    /* Auto clutch arms on a hit and disarms once the player is standing on
     * something that is not about to disappear from under them. The landed
     * guard is what keeps it armed across the bounce: hitting a ledge on the
     * way down is not the same as having landed. */
    bool autoArmed_ = false;
    bool autoChecking_ = false;
    int autoCheckTick_ = 0;
    bool autoLandedGuard_ = false;
    int autoLandedAge_ = 0;
    int lastHurtTime_ = -1;
};

}  // namespace cold::modules
