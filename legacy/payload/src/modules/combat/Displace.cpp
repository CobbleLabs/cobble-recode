#include "modules/combat/Displace.hpp"

#include <array>
#include <cmath>
#include <cstdio>

#include "bridge/Minecraft.hpp"
#include "core/Time.hpp"
#include "game/Raycast.hpp"
#include "game/RotationEngine.hpp"
#include "game/TargetFinder.hpp"
#include "hooks/Input.hpp"
#include "render/Renderer.hpp"
#include "ui/Theme.hpp"

namespace cold::modules {
namespace {

constexpr int kDirections = 16;
constexpr int kStaticRings = 10;
constexpr double kRingStep = 0.5;
constexpr int kVoidDepth = 10;
constexpr double kLaneStep = 0.5;
constexpr double kLaneDistance = 6.0;
constexpr double kLaneSide = 0.45;
/* One displace lasts a window of ten ticks before the delay is measured from
 * scratch, which is what keeps a held delay from simply switching the module
 * off against a target that never leaves. */
constexpr int kWindowTicks = 10;
constexpr int kDisplacePriority = 60;

struct Direction {
    double x;
    double z;
};

const Direction& compass(int index) {
    static const auto table = [] {
        std::array<Direction, kDirections> made{};
        for (int i = 0; i < kDirections; ++i) {
            const double angle = 6.283185307179586 * static_cast<double>(i) / kDirections;
            made[static_cast<std::size_t>(i)] = Direction{std::cos(angle), std::sin(angle)};
        }
        return made;
    }();
    return table[static_cast<std::size_t>(index)];
}

float yawFromForward(double x, double z) {
    return static_cast<float>(std::atan2(z, x) * static_cast<double>(kRadToDeg)) - 90.0f;
}

int floorToInt(double value) { return static_cast<int>(std::floor(value)); }

}  // namespace

Displace::Displace()
    : Module("Displace", "Turns on the hit so the knockback throws them sideways",
             Category::Combat, 0) {
    setIcon(assets::IconAsset::Move3d);

    mode_ = &addChoice("Mode", "Static turns by a fixed offset; Dynamic aims down the emptiest lane",
                       {"Static", "Dynamic"}, 0);
    yawOffset_ = &addNumber("Yaw offset", "Degrees to turn on the displace tick", 90.0f, 0.0f,
                            180.0f, 0)
                     .visibleWhen([this] { return mode_->choice() == 0; });
    direction_ = &addChoice("Direction", "Which way to turn when there is no void to aim at",
                            {"Left", "Right"}, 0)
                     .visibleWhen([this] { return mode_->choice() == 0; });
    findVoid_ = &addBoolean("Find void", "Aim at the nearest hole rather than the fixed offset",
                            true)
                    .visibleWhen([this] { return mode_->choice() == 0; });
    delay_ = &addNumber("Delay", "Milliseconds into the window before displacing", 0.0f, 0.0f,
                        500.0f, 0);
    range_ = &addNumber("Range", "How far a target may be", 4.5f, 2.0f, 9.0f, 1);
    showDirection_ = &addBoolean("Show direction", "Draw the arrow the knockback follows", true);
}

std::string Displace::suffix() const {
    char text[16];
    std::snprintf(text, sizeof(text), "%dms", static_cast<int>(delay_->number()));
    return text;
}

void Displace::onEnable(Context&) {
    displaceTick_ = false;
    compensate_ = false;
    forwardHeld_ = false;
    strafeHeld_ = false;
    forwardWasTheirs_ = false;
    strafeWasTheirs_ = false;
    tick_ = 0;
    windowStart_ = -1;
    windowTargetId_ = -1;
    arrowValid_ = false;
    arrowFade_ = 0.0f;
}

void Displace::onDisable(Context& context) {
    releaseMovement(context);
    displaceTick_ = false;
    compensate_ = false;
    arrowValid_ = false;
}

void Displace::releaseMovement(Context& context) {
    game::Game& game = game::Game::instance();
    if (forwardHeld_) {
        if (!forwardWasTheirs_) {
            game.setBinding(context.env, "forward", false);
        }
        forwardHeld_ = false;
    }
    if (strafeHeld_) {
        if (!strafeWasTheirs_) {
            game.setBinding(context.env, strafeLeft_ ? "left" : "right", false);
        }
        strafeHeld_ = false;
    }
    holdTicks_ = 0;
}

bool Displace::voidColumn(Context& context, jobject world, double x, double y, double z) const {
    const int cellX = floorToInt(x);
    const int cellZ = floorToInt(z);
    const int top = floorToInt(y) - 1;
    for (int cellY = top; cellY > top - kVoidDepth; --cellY) {
        if (!game::blockPassable(context.env, world, cellX, cellY, cellZ)) {
            return false;
        }
    }
    return true;
}

bool Displace::staticVoidYaw(Context& context, const game::FrameState& state,
                             const game::EntityView& target, float& yaw) {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return false;
    }

    double bestX = 0.0;
    double bestZ = 0.0;
    double bestScore = 0.0;
    bool found = false;

    /* Nearest ring first, and the whole search stops at the first ring that has
     * anything in it: a hole two blocks away is worth more than a wider one
     * four blocks away, whatever the tie-break says. */
    for (int ring = 1; ring <= kStaticRings && !found; ++ring) {
        const double radius = static_cast<double>(ring) * kRingStep;
        for (int index = 0; index < kDirections; ++index) {
            const Direction& step = compass(index);
            const double x = target.tickPos.x + step.x * radius;
            const double z = target.tickPos.z + step.z * radius;
            if (!voidColumn(context, world, x, target.tickPos.y, z)) {
                continue;
            }
            const double dx = x - state.player.pos.x;
            const double dz = z - state.player.pos.z;
            const double score = dx * dx + dz * dz;
            if (!found || score < bestScore) {
                bestScore = score;
                bestX = x;
                bestZ = z;
                found = true;
            }
        }
    }
    context.env->DeleteLocalRef(world);
    if (!found) {
        return false;
    }

    /* Which side of the target the hole is on decides the compensating strafe;
     * the sign of the cross product between "towards them" and "towards it" is
     * exactly that question. */
    const double targetDx = target.tickPos.x - state.player.pos.x;
    const double targetDz = target.tickPos.z - state.player.pos.z;
    left_ = targetDx * (bestZ - state.player.pos.z) - targetDz * (bestX - state.player.pos.x) < 0.0;

    /* Aim just past the near edge of their hitbox towards the hole rather than
     * at the hole itself: the knockback is dealt along the yaw, and a yaw that
     * misses them entirely deals none. */
    const double dx = bestX - target.tickPos.x;
    const double dz = bestZ - target.tickPos.z;
    const double length = std::sqrt(dx * dx + dz * dz);
    if (length < 0.001) {
        return false;
    }
    const double radius = std::min(length, std::max(0.35, target.width * 0.5 + 0.15));
    const double aimX = target.tickPos.x + dx / length * radius;
    const double aimZ = target.tickPos.z + dz / length * radius;
    yaw = yawFromForward(aimX - state.player.eyePos.x, aimZ - state.player.eyePos.z);
    return true;
}

bool Displace::dynamicVoidYaw(Context& context, const game::FrameState& state,
                              const game::EntityView& target, float& yaw) {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return false;
    }

    double bestScore = 0.0;
    double bestX = 0.0;
    double bestZ = 0.0;

    const auto steps = static_cast<int>(kLaneDistance / kLaneStep);
    for (int index = 0; index < kDirections; ++index) {
        const Direction& forward = compass(index);
        const double sideX = -forward.z;
        const double sideZ = forward.x;
        double score = 0.0;
        int run = 0;

        for (int step = 1; step <= steps; ++step) {
            const double along = static_cast<double>(step) * kLaneStep;
            const double centreX = target.tickPos.x + forward.x * along;
            const double centreZ = target.tickPos.z + forward.z * along;
            /* A lane with a wall across it is not a lane. Once the path is
             * blocked, nothing further along it counts. */
            if (!game::blockPassable(context.env, world, floorToInt(centreX),
                                     floorToInt(target.tickPos.y), floorToInt(centreZ))) {
                break;
            }

            bool centreVoid = false;
            for (int lane = -1; lane <= 1; ++lane) {
                const double offset = static_cast<double>(lane) * kLaneSide;
                const double x = centreX + sideX * offset;
                const double z = centreZ + sideZ * offset;
                if (!voidColumn(context, world, x, target.tickPos.y, z)) {
                    continue;
                }
                /* Nearer void is worth more, and the middle of the lane is
                 * worth more than its edges - a hole they would be knocked past
                 * is not a hole they fall into. */
                score += (lane == 0 ? 1.4 : 1.0) * (kLaneDistance + kLaneStep - along);
                centreVoid |= lane == 0;
            }
            run = centreVoid ? run + 1 : 0;
            score += static_cast<double>(run) * 2.0;
        }

        if (score > bestScore) {
            bestScore = score;
            bestX = forward.x;
            bestZ = forward.z;
        }
    }

    context.env->DeleteLocalRef(world);
    if (bestScore <= 0.0) {
        return false;
    }

    const double targetDx = target.tickPos.x - state.player.pos.x;
    const double targetDz = target.tickPos.z - state.player.pos.z;
    left_ = targetDx * (target.tickPos.z + bestZ - state.player.pos.z) -
                targetDz * (target.tickPos.x + bestX - state.player.pos.x) <
            0.0;
    yaw = yawFromForward(bestX, bestZ);
    return true;
}

const game::EntityView* Displace::pickTarget(const game::FrameState& state) const {
    const int published = game::CombatTarget::instance().current(nowSeconds());
    const bool attacking = hooks::input().attackHeld() || published >= 0;
    if (!attacking) {
        return nullptr;
    }

    const game::EntityView* best = nullptr;
    double bestDistance = static_cast<double>(range_->number());
    for (const game::EntityView& entity : state.entities) {
        if (!entity.isPlayer || entity.crystal || entity.health <= 0.0f) {
            continue;
        }
        /* Range still applies to the published target. Another module may be
         * swinging at somebody well outside the distance a displace is worth,
         * and turning ninety degrees away from a fight for them is worse than
         * not displacing at all. */
        if (entity.id == published && entity.distance <= static_cast<double>(range_->number())) {
            return &entity;
        }
        if (entity.distance <= bestDistance) {
            bestDistance = entity.distance;
            best = &entity;
        }
    }
    return best;
}

void Displace::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        onDisable(context);
        return;
    }

    ++tick_;
    game::Game& game = game::Game::instance();

    /* The forward press and the strafe that answers it each last exactly one
     * tick, so the player's own keys come straight back. */
    if (holdTicks_ > 0 && --holdTicks_ == 0) {
        releaseMovement(context);
    }

    const game::EntityView* target = pickTarget(state);
    if (target == nullptr) {
        releaseMovement(context);
        displaceTick_ = false;
        compensate_ = false;
        arrowValid_ = false;
        windowTargetId_ = -1;
        return;
    }

    /* The tick after a displace is spent strafing back the way the turn threw
     * you, which is what keeps the net movement a sidestep rather than a
     * wander into the opponent. */
    if (compensate_) {
        compensate_ = false;
        strafeLeft_ = left_;
        strafeWasTheirs_ = game.bindingPressed(context.env, strafeLeft_ ? "left" : "right");
        game.setBinding(context.env, strafeLeft_ ? "left" : "right", true);
        strafeHeld_ = true;
        holdTicks_ = 1;
        return;
    }

    float yaw = 0.0f;
    bool aimed = false;
    if (mode_->choice() == 1) {
        aimed = dynamicVoidYaw(context, state, *target, yaw);
    } else {
        if (findVoid_->booleanValue()) {
            aimed = staticVoidYaw(context, state, *target, yaw);
        }
        if (!aimed) {
            left_ = direction_->choice() == 0;
            const float base = game::RotationEngine::instance().sentYaw();
            yaw = left_ ? base - yawOffset_->number() : base + yawOffset_->number();
            aimed = true;
        }
    }
    if (!aimed) {
        arrowValid_ = false;
        return;
    }

    arrowValid_ = true;
    arrowYaw_ = yaw;
    arrowTargetId_ = target->id;

    /* Every other tick. A rotation held across two ticks is a player facing the
     * wrong way; held across one it is a flick, which is what a hit needs. */
    displaceTick_ = !displaceTick_;
    if (!displaceTick_) {
        return;
    }

    if (windowTargetId_ != target->id || windowStart_ < 0 || tick_ - windowStart_ >= kWindowTicks) {
        windowTargetId_ = target->id;
        windowStart_ = tick_;
    } else {
        const int delayTicks = static_cast<int>(std::ceil(delay_->number() / 50.0f));
        if (tick_ - windowStart_ < delayTicks) {
            displaceTick_ = false;
            return;
        }
    }

    game::RotationTarget rotation;
    rotation.yaw = yaw;
    rotation.pitch = game::RotationEngine::instance().sentPitch();
    rotation.speed = 180.0f;
    rotation.smoothing = 0.0f;
    rotation.silent = true;
    rotation.priority = kDisplacePriority;
    game::RotationEngine::instance().submit(rotation);

    forwardWasTheirs_ = game.bindingPressed(context.env, "forward");
    game.setBinding(context.env, "forward", true);
    forwardHeld_ = true;
    holdTicks_ = 1;
    compensate_ = true;
}

void Displace::onRender(Context& context) {
    const float step = 1.0f - std::exp(-9.0f * context.deltaTime);
    arrowFade_ += ((arrowValid_ ? 1.0f : 0.0f) - arrowFade_) * step;
    if (!showDirection_->booleanValue() || arrowFade_ < 0.02f || !context.projection->valid()) {
        return;
    }

    const game::EntityView* target = nullptr;
    for (const game::EntityView& entity : context.state->entities) {
        if (entity.id == arrowTargetId_) {
            target = &entity;
            break;
        }
    }
    if (target == nullptr) {
        return;
    }

    /* A flat arrow lying in the horizontal plane at chest height, pointing the
     * way the knockback will go. Drawn from projected world points rather than
     * as a screen sprite, so it stays welded to the target and shrinks with
     * distance the way anything in the world does. */
    const double yawRadians = static_cast<double>(arrowYaw_) * kDegToRad;
    const double forwardX = -std::sin(yawRadians);
    const double forwardZ = std::cos(yawRadians);
    const double sideX = -forwardZ;
    const double sideZ = forwardX;

    const Vec3 centre{target->pos.x, target->pos.y + target->height * 0.5, target->pos.z};
    const double base = target->width * 0.5 + 0.24;
    const auto at = [&](double along, double across) {
        return Vec3{centre.x + forwardX * (base + along) + sideX * across, centre.y,
                    centre.z + forwardZ * (base + along) + sideZ * across};
    };

    const Vec3 shape[7] = {
        at(0.0, -0.08), at(0.74, -0.08), at(0.56, -0.30),
        at(1.26, 0.0),  at(0.56, 0.30),  at(0.74, 0.08),
        at(0.0, 0.08),
    };

    float xs[7];
    float ys[7];
    for (int index = 0; index < 7; ++index) {
        game::ScreenPoint point;
        if (!context.projection->project(shape[index], point)) {
            return;
        }
        xs[index] = point.x;
        ys[index] = point.y;
    }

    render::Renderer& gfx = render::renderer();
    const float alpha = arrowFade_ * animation();
    const Color fill = ui::theme().accent.withAlpha(0.82f * alpha);
    const Color edge = Color::rgb(0x05070A, 0.9f * alpha);

    /* Two triangle fans: the shaft, then the head. face() takes four points, so
     * the shaft is one quad and the head is a quad with a doubled tip. */
    const float shaftX[4] = {xs[0], xs[1], xs[5], xs[6]};
    const float shaftY[4] = {ys[0], ys[1], ys[5], ys[6]};
    gfx.face(shaftX, shaftY, fill);
    const float headX[4] = {xs[2], xs[3], xs[3], xs[4]};
    const float headY[4] = {ys[2], ys[3], ys[3], ys[4]};
    gfx.face(headX, headY, fill);

    for (int index = 0; index < 7; ++index) {
        const int next = (index + 1) % 7;
        gfx.line(xs[index], ys[index], xs[next], ys[next], 1.4f, edge);
    }
}

}  // namespace cold::modules
