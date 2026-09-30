#pragma once

#include "bridge/Minecraft.hpp"
#include "core/Math.hpp"

namespace cold::game {

struct ScreenPoint {
    float x = 0.0f;
    float y = 0.0f;
    float depth = 0.0f;
    bool visible = false;
};

/* World space to framebuffer pixels, using the exact matrices Minecraft used
 * for the frame being presented.
 *
 * The projection comes straight from GameRenderer (including the view-bob
 * matrix that renderWorld folds into it) and the view matrix from the camera's
 * conjugated rotation quaternion, so overlays stay welded to entities while the
 * player walks instead of swimming a pixel behind them. */
class Projection {
public:
    void update(const FrameState& state, int targetWidth, int targetHeight);

    bool valid() const { return valid_; }
    bool project(const Vec3& world, ScreenPoint& out) const;

    /* Screen-space bounds of a world axis-aligned box. Returns false when the
     * box is behind the camera or entirely off screen. */
    bool projectBox(const Vec3& minimum, const Vec3& maximum, Rect& out) const;

private:
    Mat4 viewProjection_;
    Vec3 cameraPosition_;
    float width_ = 0.0f;
    float height_ = 0.0f;
    bool valid_ = false;
};

}  // namespace cold::game
