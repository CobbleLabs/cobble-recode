#pragma once

#include <vector>

#include "bridge/Minecraft.hpp"
#include "core/Math.hpp"

namespace cold::game {

enum class TargetSort : int {
    Distance = 0,
    Health = 1,
    Angle = 2,
    HurtTime = 3,
};

struct TargetFilter {
    float range = 3.0f;
    bool players = true;
    bool mobs = true;
    bool invisibles = false;
    bool dead = false;
};

/* Closest point on an axis-aligned hitbox to the eye. This is the distance the
 * server measures for an attack, and the one Grim's reach check reconstructs,
 * so range gating uses it rather than centre-to-centre. */
double hitboxDistance(const Vec3& eye, const Vec3& minimum, const Vec3& maximum);

/* Best point on the hitbox to aim at: the eye-height slab is preferred because
 * it is what a human aims at, and it keeps the ray inside the box after the
 * target moves a tick. */
Vec3 aimPoint(const Vec3& eye, const EntityView& entity, float shrink);

const EntityView* selectTarget(const FrameState& state, const TargetFilter& filter,
                               TargetSort sort);

/* Whoever a combat module is currently swinging at.
 *
 * Several modules pick a target and none of them owns the answer, so the one
 * surface that wants it - the target HUD - would otherwise have to know about
 * every one of them. They publish an entity id instead and the last publish
 * wins for as long as it stays fresh. Nothing here holds a reference: an id is
 * resolved against the frame being drawn, so a target that despawned simply
 * stops resolving. */
class CombatTarget {
public:
    static CombatTarget& instance();

    void publish(int entityId, double atSeconds);
    void clear() { id_ = -1; }

    /* The published id while it is younger than 'maxAge' seconds, or -1. */
    int current(double nowSeconds, double maxAge = 0.35) const;

private:
    CombatTarget() = default;

    int id_ = -1;
    double at_ = 0.0;
};

}  // namespace cold::game
