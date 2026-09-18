#pragma once

#include <vector>

#include "core/Time.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* The Raven-b `CuteVisuals` script - hearts and dots trailing the player, and a
 * burst plus a rainbow when a bed goes.
 *
 * The original draws every shape as a billboarded line loop in world space,
 * which costs a matrix push and thirty vertices per particle. Here each shape
 * is projected once - its centre, plus one unit vector to recover how many
 * pixels a block is worth at that distance - and then drawn in screen space.
 * A camera-facing billboard is by definition flat to the screen, so the result
 * is identical and two hundred particles cost two hundred projections instead
 * of six thousand.
 *
 * Bed breaks are noticed by watching the block under the crosshair rather than
 * by reading the digging packet the original hooks: the payload sends no
 * packets of its own and reads none, so the honest signal is that the bed being
 * mined is not a bed any more. */
class CuteVisuals : public Module {
public:
    CuteVisuals();

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;
    void onRender(Context& context) override;

private:
    struct Heart {
        Vec3 position;
        float age = 0.0f;
        float spin = 0.0f;
        float tilt = 0.0f;
        float scale = 0.15f;
        int type = 0;
        int seed = 0;
    };

    struct Dot {
        Vec3 position;
        Vec3 drift;
        float age = 0.0f;
        float scale = 0.04f;
        int type = 0;
        int seed = 0;
    };

    struct Spark {
        Vec3 position;
        Vec3 velocity;
        float age = 0.0f;
        float scale = 0.2f;
        int type = 0;
        int seed = 0;
    };

    struct Rainbow {
        Vec3 position;
        float yaw = 0.0f;
        float age = 0.0f;
    };

    /* Screen-space frame for one billboard: where it is and how many pixels a
     * block is worth there. Returns false when the point is behind the camera. */
    bool billboard(Context& context, const Vec3& world, float& x, float& y,
                   float& pixelsPerBlock) const;
    void watchBed(Context& context, const game::FrameState& state);
    void burst(Context& context, const Vec3& centre);

    void drawHearts(Context& context, float opacity);
    void drawDots(Context& context, float opacity);
    void drawSparks(Context& context);
    void drawRainbows(Context& context);

    Setting* hearts_;
    Setting* heartRate_;
    Setting* heartLife_;
    Setting* dots_;
    Setting* dotRate_;
    Setting* dotLife_;
    Setting* pulse_;
    Setting* onlyMoving_;
    Setting* opacity_;

    Setting* bedBurst_;
    Setting* burstCount_;
    Setting* burstSize_;
    Setting* burstSpeed_;
    Setting* burstLife_;
    Setting* rainbow_;
    Setting* rainbowWidth_;
    Setting* rainbowLife_;
    Setting* bedSound_;

    Random random_{0xC0FFEE11u};
    std::vector<Heart> heartPool_;
    std::vector<Dot> dotPool_;
    std::vector<Spark> sparkPool_;
    std::vector<Rainbow> rainbowPool_;

    Timer heartTimer_;
    Timer dotTimer_;
    Vec3 lastPosition_;
    bool hasLastPosition_ = false;

    bool watchingBed_ = false;
    int bedX_ = 0;
    int bedY_ = 0;
    int bedZ_ = 0;
};

}  // namespace cold::modules
