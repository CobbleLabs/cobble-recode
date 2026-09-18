#include "game/Projection.hpp"

#include <algorithm>

namespace cold::game {

void Projection::update(const FrameState& state, int targetWidth, int targetHeight) {
    width_ = static_cast<float>(targetWidth);
    height_ = static_cast<float>(targetHeight);
    cameraPosition_ = state.camera.pos;
    viewProjection_ = state.projection * state.view;
    valid_ = state.inWorld && state.matricesValid && width_ > 0.0f && height_ > 0.0f;
}

bool Projection::project(const Vec3& world, ScreenPoint& out) const {
    if (!valid_) {
        return false;
    }
    float clip[4];
    viewProjection_.transform(world.x - cameraPosition_.x, world.y - cameraPosition_.y,
                             world.z - cameraPosition_.z, clip);
    if (clip[3] <= 0.0001f) {
        out.visible = false;
        return false;
    }
    const float inverseW = 1.0f / clip[3];
    const float ndcX = clip[0] * inverseW;
    const float ndcY = clip[1] * inverseW;

    out.x = (ndcX * 0.5f + 0.5f) * width_;
    out.y = (1.0f - (ndcY * 0.5f + 0.5f)) * height_;
    out.depth = clip[3];
    out.visible = true;
    return true;
}

bool Projection::projectBox(const Vec3& minimum, const Vec3& maximum, Rect& out) const {
    if (!valid_) {
        return false;
    }
    const Vec3 corners[8] = {
        {minimum.x, minimum.y, minimum.z}, {maximum.x, minimum.y, minimum.z},
        {minimum.x, maximum.y, minimum.z}, {maximum.x, maximum.y, minimum.z},
        {minimum.x, minimum.y, maximum.z}, {maximum.x, minimum.y, maximum.z},
        {minimum.x, maximum.y, maximum.z}, {maximum.x, maximum.y, maximum.z},
    };

    float left = width_;
    float top = height_;
    float right = 0.0f;
    float bottom = 0.0f;

    for (const Vec3& corner : corners) {
        ScreenPoint point;
        /* One corner behind the near plane makes the whole screen-space box
         * meaningless, so the caller falls back to a marker rather than drawing
         * a rectangle that wraps around the viewport. */
        if (!project(corner, point)) {
            return false;
        }
        left = std::min(left, point.x);
        right = std::max(right, point.x);
        top = std::min(top, point.y);
        bottom = std::max(bottom, point.y);
    }

    if (right <= 0.0f || bottom <= 0.0f || left >= width_ || top >= height_) {
        return false;
    }
    out = Rect{left, top, right - left, bottom - top};
    return out.width > 0.5f && out.height > 0.5f;
}

}  // namespace cold::game
