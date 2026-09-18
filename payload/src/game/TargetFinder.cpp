#include "game/TargetFinder.hpp"

#include <algorithm>
#include <cmath>

namespace cold::game {
namespace {

double clampAxis(double value, double low, double high) {
    return value < low ? low : (value > high ? high : value);
}

}  // namespace

double hitboxDistance(const Vec3& eye, const Vec3& minimum, const Vec3& maximum) {
    const double x = clampAxis(eye.x, minimum.x, maximum.x) - eye.x;
    const double y = clampAxis(eye.y, minimum.y, maximum.y) - eye.y;
    const double z = clampAxis(eye.z, minimum.z, maximum.z) - eye.z;
    return std::sqrt(x * x + y * y + z * z);
}

Vec3 aimPoint(const Vec3& eye, const EntityView& entity, float shrink) {
    Vec3 minimum = entity.tickBoxMin();
    Vec3 maximum = entity.tickBoxMax();

    /* Pull the aim point inside the hitbox so a tick of target movement cannot
     * leave the ray outside it, which is what produces "hit but no damage". */
    const double insetX = std::min(shrink, entity.width * 0.35f);
    const double insetZ = insetX;
    const double insetY = std::min(shrink, entity.height * 0.2f);
    minimum = {minimum.x + insetX, minimum.y + insetY, minimum.z + insetZ};
    maximum = {maximum.x - insetX, maximum.y - insetY, maximum.z - insetZ};

    return {clampAxis(eye.x, minimum.x, maximum.x), clampAxis(eye.y, minimum.y, maximum.y),
            clampAxis(eye.z, minimum.z, maximum.z)};
}

const EntityView* selectTarget(const FrameState& state, const TargetFilter& filter,
                               TargetSort sort) {
    if (!state.player.valid) {
        return nullptr;
    }
    const Vec3 eye = state.player.eyePos;
    const float playerYaw = state.player.yaw;

    const EntityView* best = nullptr;
    double bestScore = 0.0;

    for (const EntityView& entity : state.entities) {
        if (!entity.living) continue;
        if (entity.isPlayer ? !filter.players : !filter.mobs) continue;
        if (!filter.invisibles && entity.invisible) continue;
        if (!filter.dead && entity.health <= 0.0f) continue;

        const double distance = hitboxDistance(eye, entity.tickBoxMin(), entity.tickBoxMax());
        if (distance > filter.range) continue;

        double score = 0.0;
        switch (sort) {
            case TargetSort::Distance:
                score = -distance;
                break;
            case TargetSort::Health:
                score = -static_cast<double>(entity.health + entity.absorption);
                break;
            case TargetSort::Angle: {
                const double dx = entity.tickPos.x - eye.x;
                const double dz = entity.tickPos.z - eye.z;
                const auto yaw =
                    static_cast<float>(std::atan2(dz, dx) * 180.0 / 3.14159265358979323846) - 90.0f;
                score = -std::fabs(static_cast<double>(wrapDegrees(yaw - playerYaw)));
                break;
            }
            case TargetSort::HurtTime:
                score = -static_cast<double>(entity.hurtTime) * 100.0 - distance;
                break;
        }

        if (best == nullptr || score > bestScore) {
            best = &entity;
            bestScore = score;
        }
    }
    return best;
}


CombatTarget& CombatTarget::instance() {
    static CombatTarget target;
    return target;
}

void CombatTarget::publish(int entityId, double atSeconds) {
    if (entityId < 0) {
        return;
    }
    id_ = entityId;
    at_ = atSeconds;
}

int CombatTarget::current(double nowSeconds, double maxAge) const {
    if (id_ < 0 || nowSeconds - at_ > maxAge) {
        return -1;
    }
    return id_;
}

}  // namespace cold::game
