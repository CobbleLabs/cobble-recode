#pragma once

#include <jni.h>

#include <cstdint>

#include "bridge/Minecraft.hpp"
#include "core/Math.hpp"

namespace cold::game {

struct RotationTarget {
    float yaw = 0.0f;
    float pitch = 0.0f;

    /* Degrees the aim may travel in one tick before the step is clamped. */
    float speed = 180.0f;
    /* 0 snaps as fast as `speed` allows, 1 barely moves. Shapes the approach
     * curve so the aim decelerates into the target instead of stopping dead. */
    float smoothing = 0.3f;
    /* Extra whole mouse counts of noise per tick. Stays on the sensitivity
     * grid, so it looks like a human hand without breaking the GCD. */
    int jitter = 0;
    /* Send the rotation without moving the camera. See RotationEngine. */
    bool silent = false;
    int priority = 0;
};

/* Turns aim requests into rotations a real mouse could have produced.
 *
 * Minecraft derives yaw from whole mouse counts:
 *
 *     f     = (sensitivity * 0.6 + 0.2)^3 * 8
 *     yaw  += (float)(cursorDeltaX * f) * 0.15
 *
 * so every rotation a legitimate client sends is an integer multiple of
 * `f * 0.15`. Grim's aim processor recovers that step as the GCD of the
 * rotation deltas it observes; a client that writes arbitrary floats produces a
 * GCD near zero and is trivially separable from a mouse. Cobble Recode therefore
 * plans in degrees, then quantises to whole mouse counts before touching the
 * player, and never writes a rotation off that grid.
 *
 * Silent mode adds a second trick. The rotation Minecraft puts in the movement
 * packet is read during the client tick, but the camera is built from the same
 * field slightly later in the frame, inside `gameRenderer.render`. Writing the
 * aim just before the tick and taking it back in between — the frame hook and
 * the `Mouse.tick` probe respectively — sends the rotation the module needs
 * while the player's view never moves. */
class RotationEngine {
public:
    static RotationEngine& instance();

    void configure(double sensitivity);
    float step() const { return step_; }

    /* Modules submit once per tick; the highest priority wins. */
    void submit(const RotationTarget& target);

    /* Plans the next rotation. Called on a tick edge. */
    void tick(JNIEnv* env, const FrameState& state);

    /* Writes an armed silent rotation when the next client tick is close, and
     * cleans up if the post-tick probe never fired. Called every frame. */
    void updateSilent(JNIEnv* env, const FrameState& state, bool tickImminent);

    /* Restores the player's own rotation after the movement packet has gone
     * out. Called from the glfwGetTime probe, so it must stay cheap. */
    void onPostTick(JNIEnv* env);

    void reset();

    /* Silent rotations depend on the glfwGetTime probe being able to take the
     * aim back before the world is drawn. Without that hook every one of them
     * would be visible for a frame, so the engine falls back to moving the
     * camera outright rather than pretending. */
    void setSilentAvailable(bool available) { silentAvailable_ = available; }
    bool silentAvailable() const { return silentAvailable_; }

    bool engaged() const { return engaged_; }
    bool silentActive() const { return silentApplied_; }
    /* How many armed silent rotations actually reached a movement packet. */
    long long silentDelivered() const { return delivered_; }
    long long silentMissed() const { return missed_; }
    /* Diagnostic: how many probe calls elapsed before the client tick ran. */
    int probesBeforeTick() const { return probesBeforeTick_; }
    int probesPerArm() const { return probesPerArm_; }
    float yaw() const { return currentYaw_; }
    float pitch() const { return currentPitch_; }

    /* The rotation the server currently believes the player has. Modules must
     * validate against this, not the camera, before acting on the world. */
    float sentYaw() const { return sentYaw_; }
    float sentPitch() const { return sentPitch_; }

    /* The rotation the *next* movement packet will carry.
     *
     * For a visible rotation this is the same thing as sentYaw: the engine
     * writes it to the player on the tick edge and the tick after that sends
     * it. A silent one is held back until the frame right before the tick, and
     * that write happens after the modules have run - so a module reading
     * sentYaw during its own tick is told about the rotation from the tick
     * before last, and a placement confirmed against it lands on a face the
     * aim has already left. Confirm against this instead; it is the rotation
     * the server is about to hold, which is the one a placement sent from this
     * tick will be reconstructed with. */
    float pendingYaw() const { return silentArmed_ ? silentYaw_ : sentYaw_; }
    float pendingPitch() const { return silentArmed_ ? silentPitch_ : sentPitch_; }

    /* Angles that point the player's eyes at a world position. */
    static RotationTarget aimAt(const Vec3& eye, const Vec3& point, float currentYaw);

private:
    RotationEngine() = default;

    float quantise(float degrees, int jitter);

    double sensitivity_ = -1.0;
    float step_ = 0.15f;
    std::uint32_t noise_ = 0x9E3779B9u;

    RotationTarget request_;
    bool hasRequest_ = false;
    bool engaged_ = false;

    /* The aim the engine is steering, kept across ticks. It is seeded from the
     * camera when nothing was aiming and then composes with the hand: what the
     * player moved the mouse by is added to it rather than replacing it. A
     * silent rotation is never written to the camera, so overwriting this from
     * the camera each tick would make every tick restart from scratch and cap
     * the aim at one tick of travel from wherever the player is looking. */
    float currentYaw_ = 0.0f;
    float currentPitch_ = 0.0f;
    bool active_ = false;
    float lastCameraYaw_ = 0.0f;
    float lastCameraPitch_ = 0.0f;
    float sentYaw_ = 0.0f;
    float sentPitch_ = 0.0f;

    /* Silent state machine. */
    bool silentAvailable_ = true;
    bool silentArmed_ = false;
    bool silentApplied_ = false;
    float silentYaw_ = 0.0f;
    float silentPitch_ = 0.0f;
    float restoreYaw_ = 0.0f;
    float restorePitch_ = 0.0f;
    int ageAtApply_ = -1;
    long long delivered_ = 0;
    long long missed_ = 0;
    int probeIndex_ = 0;
    int probesBeforeTick_ = -1;
    int probesPerArm_ = 0;
};

}  // namespace cold::game
