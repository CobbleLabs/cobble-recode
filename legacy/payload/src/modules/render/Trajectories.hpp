#pragma once

#include <vector>

#include "modules/Module.hpp"

namespace cold::modules {

/* The Raven-b `trajectories` script.
 *
 * Simulates the projectile in hand forward tick by tick using vanilla's own
 * numbers - a per-item gravity and drag, a launch speed, and for a bow the
 * charge curve - and draws the path it takes until something stops it. The line
 * is coloured by what that something was, so the readout is not just "it lands
 * there" but "it lands on them", "it hits that wall" or "it drops short".
 *
 * The path is drawn in the world, not on the HUD: every point is projected with
 * the frame's own view-projection matrix, so the arc is welded to the world
 * while the camera moves.
 *
 * The original also trailed thrown projectiles that were already in flight.
 * That is left out here: the frame capture keeps living entities and end
 * crystals only, and widening it to every entity in render distance would cost
 * the whole client a per-frame walk it currently does not do. */
class Trajectories : public Module {
public:
    Trajectories();

    void onEnable(Context& context) override;
    void onRender(Context& context) override;

private:
    enum class Hit : std::uint8_t {
        None,
        Entity,
        Wall,
        Ground,
    };

    struct Projectile {
        bool valid = false;
        double gravity = 0.03;
        double drag = 0.99;
        double velocity = 1.5;
        /* Thrown items take one step of drag and gravity before their first
         * move; an arrow does not. */
        bool tickCorrection = true;
    };

    Projectile properties(Context& context, const game::FrameState& state) const;
    void line3d(Context& context, const Vec3& from, const Vec3& to, float width,
                const Color& colour) const;
    void outlinedBox(Context& context, const Vec3& minimum, const Vec3& maximum, float width,
                     const Color& colour) const;

    Setting* lineWidth_;
    Setting* maxTicks_;
    Setting* landingBox_;
    Setting* landingCross_;
    Setting* landingBlock_;
    Setting* highlight_;
    Setting* playerVelocity_;
    Setting* fadeOut_;

    std::vector<Vec3> path_;
    std::vector<Vec3> lastPath_;
    float lastPathAge_ = 99.0f;
};

}  // namespace cold::modules
