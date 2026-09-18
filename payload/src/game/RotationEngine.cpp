#include "game/RotationEngine.hpp"

#include <cmath>

#include "core/Log.hpp"

namespace cold::game {
namespace {

constexpr std::string_view kScope = "aim";

}  // namespace

RotationEngine& RotationEngine::instance() {
    static RotationEngine engine;
    return engine;
}

void RotationEngine::configure(double sensitivity) {
    if (std::fabs(sensitivity - sensitivity_) < 1e-9) {
        return;
    }
    sensitivity_ = sensitivity;
    const double scaled = sensitivity * 0.6 + 0.2;
    const double factor = scaled * scaled * scaled * 8.0;
    step_ = static_cast<float>(factor * 0.15);
    if (!(step_ > 0.0f)) {
        step_ = 0.15f;
    }
    COLD_INFO(kScope, "sensitivity %.4f -> %.6f deg per mouse count", sensitivity, step_);
}

void RotationEngine::submit(const RotationTarget& target) {
    if (!hasRequest_ || target.priority >= request_.priority) {
        request_ = target;
        hasRequest_ = true;
    }
}

void RotationEngine::reset() {
    hasRequest_ = false;
    engaged_ = false;
    silentArmed_ = false;
    silentApplied_ = false;
    active_ = false;
    ageAtApply_ = -1;
}

RotationTarget RotationEngine::aimAt(const Vec3& eye, const Vec3& point, float currentYaw) {
    const double dx = point.x - eye.x;
    const double dy = point.y - eye.y;
    const double dz = point.z - eye.z;
    const double horizontal = std::sqrt(dx * dx + dz * dz);

    RotationTarget target;
    const auto rawYaw = static_cast<float>(std::atan2(dz, dx) * kRadToDeg) - 90.0f;
    target.yaw = currentYaw + wrapDegrees(rawYaw - currentYaw);
    target.pitch = clamp(static_cast<float>(-(std::atan2(dy, horizontal) * kRadToDeg)), -90.0f,
                         90.0f);
    return target;
}

float RotationEngine::quantise(float degrees, int jitter) {
    if (step_ <= 0.0f) {
        return 0.0f;
    }
    long rounded = std::lround(degrees / step_);

    if (jitter > 0) {
        /* xorshift keeps this deterministic per session and free of a PRNG
         * dependency; the offset is whole counts so the grid survives. */
        noise_ ^= noise_ << 13;
        noise_ ^= noise_ >> 17;
        noise_ ^= noise_ << 5;
        const int span = jitter * 2 + 1;
        rounded += static_cast<long>(noise_ % static_cast<std::uint32_t>(span)) - jitter;
    }

    /* Never stall short of the target: a sub-count remainder still gets one
     * count of travel, which is exactly what a slow hand produces. */
    if (rounded == 0 && std::fabs(degrees) > step_ * 0.5f) {
        rounded = degrees > 0.0f ? 1 : -1;
    }
    return static_cast<float>(rounded) * step_;
}

void RotationEngine::tick(JNIEnv* env, const FrameState& state) {
    if (!state.player.valid) {
        reset();
        return;
    }
    configure(state.mouseSensitivity);

    const float cameraYaw = state.player.yaw;
    const float cameraPitch = state.player.pitch;

    if (!active_) {
        currentYaw_ = cameraYaw;
        currentPitch_ = cameraPitch;
    } else {
        currentYaw_ += wrapDegrees(cameraYaw - lastCameraYaw_);
        currentPitch_ = clamp(currentPitch_ + (cameraPitch - lastCameraPitch_), -90.0f, 90.0f);
    }
    lastCameraYaw_ = cameraYaw;
    lastCameraPitch_ = cameraPitch;

    if (!hasRequest_) {
        /* Nothing is aiming, so the next movement packet carries the camera. */
        engaged_ = false;
        active_ = false;
        silentArmed_ = false;
        sentYaw_ = cameraYaw;
        sentPitch_ = cameraPitch;
        return;
    }

    const float yawError = wrapDegrees(request_.yaw - currentYaw_);
    const float pitchError = clamp(request_.pitch, -90.0f, 90.0f) - currentPitch_;

    /* Approach curve: a large error moves at full speed, a small one eases in. */
    const float magnitude = std::sqrt(yawError * yawError + pitchError * pitchError);
    const float approach = clamp(magnitude / 45.0f, 0.12f, 1.0f);
    const float allowance =
        request_.speed * approach * (1.0f - clamp(request_.smoothing, 0.0f, 0.95f));

    const float appliedYaw = quantise(clamp(yawError, -allowance, allowance), request_.jitter);
    const float appliedPitch = quantise(clamp(pitchError, -allowance, allowance), request_.jitter);

    currentYaw_ += appliedYaw;
    currentPitch_ = clamp(currentPitch_ + appliedPitch, -90.0f, 90.0f);

    if (request_.silent && silentAvailable_) {
        /* Hold it back until the frame right before the next tick, so the
         * camera is only borrowed for the packet. sentYaw_ stays where it is
         * until updateSilent actually writes it: a module asking what the
         * server holds must never be told about a rotation still in flight. */
        silentYaw_ = currentYaw_;
        silentPitch_ = currentPitch_;
        silentArmed_ = true;
    } else {
        sentYaw_ = currentYaw_;
        sentPitch_ = currentPitch_;
        silentArmed_ = false;
        lastCameraYaw_ = currentYaw_;
        lastCameraPitch_ = currentPitch_;
        Game::instance().setRotation(env, state.player.handle, currentYaw_, currentPitch_);
    }

    active_ = true;
    engaged_ = true;
    hasRequest_ = false;
}

void RotationEngine::updateSilent(JNIEnv* env, const FrameState& state, bool tickImminent) {
    if (!state.player.valid) {
        silentApplied_ = false;
        silentArmed_ = false;
        return;
    }

    if (silentApplied_) {
        /* No tick arrived before this frame came round again, so the aim never
         * left the client and the server still holds the camera. Undo it here:
         * one frame of a borrowed camera is the worst case, and it stays off
         * screen because the render already happened.
         *
         * The player may have moved the mouse in between, and Mouse.updateMouse
         * has already folded that into the camera. Restoring the captured value
         * outright would throw their input away, so take back exactly what was
         * put in and leave whatever they added on top. */
        const float drift = wrapDegrees(state.player.yaw - silentYaw_);
        const float driftPitch = state.player.pitch - silentPitch_;
        Game::instance().setRotation(env, state.player.handle, restoreYaw_ + drift,
                                     clamp(restorePitch_ + driftPitch, -90.0f, 90.0f), true);
        sentYaw_ = restoreYaw_ + drift;
        sentPitch_ = clamp(restorePitch_ + driftPitch, -90.0f, 90.0f);
        silentApplied_ = false;
        ++missed_;
    }

    if (!silentArmed_ || !tickImminent) {
        return;
    }

    restoreYaw_ = state.player.yaw;
    restorePitch_ = state.player.pitch;
    sentYaw_ = silentYaw_;
    sentPitch_ = silentPitch_;
    ageAtApply_ = state.player.age;
    probeIndex_ = 0;
    silentApplied_ = true;
    silentArmed_ = false;
    Game::instance().setRotation(env, state.player.handle, silentYaw_, silentPitch_);
}

void RotationEngine::onPostTick(JNIEnv* env) {
    if (!silentApplied_ || env == nullptr) {
        return;
    }
    /* Minecraft's frame limiter busy-waits on glfwGetTime, so a hundred or more
     * calls can land between arming and the tick. Counting them is useless; the
     * player's age is the only reliable marker. It moves on exactly when the
     * client tick ran, which is exactly when the movement packet carrying the
     * rotation went out — and the very next call is Mouse.tick's, still ahead
     * of the world render. */
    ++probeIndex_;
    const int age = Game::instance().playerAge(env);
    if (age < 0 || age == ageAtApply_) {
        return;
    }
    ++delivered_;
    probesBeforeTick_ = probeIndex_ - 1;
    probesPerArm_ = probeIndex_;
    Game::instance().setLocalRotation(env, restoreYaw_, restorePitch_);
    silentApplied_ = false;
}

}  // namespace cold::game
