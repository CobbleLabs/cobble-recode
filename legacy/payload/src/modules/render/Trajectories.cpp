#include "modules/render/Trajectories.hpp"

#include <algorithm>
#include <cmath>
#include <string>

#include "bridge/Minecraft.hpp"
#include "game/Raycast.hpp"
#include "render/Renderer.hpp"
#include "ui/Theme.hpp"

namespace cold::modules {
namespace {

/* Four points per tick. Fewer and the arc is visibly a polyline at close
 * range; more is spent on segments a pixel long. */
constexpr int kSubSteps = 4;
constexpr double kHitRadius = 0.25;
constexpr float kFadeSeconds = 2.0f;
/* The first half block of the arc leaves from inside the player's own hand, so
 * it is clipped rather than drawn across the middle of the screen. */
constexpr double kHiddenLength = 0.5;

bool contains(const std::string& value, const char* needle) {
    return value.find(needle) != std::string::npos;
}

}  // namespace

Trajectories::Trajectories()
    : Module("Trajectories", "Draws where the projectile in your hand lands", Category::Render, 0) {
    setIcon(assets::IconAsset::Activity);

    lineWidth_ = &addNumber("Line width", "Arc thickness in pixels", 2.0f, 1.0f, 5.0f, 1);
    maxTicks_ = &addNumber("Max ticks", "How far ahead to simulate", 100.0f, 30.0f, 200.0f, 0);
    landingBox_ = &addBoolean("Landing box", "Cube at the impact point", true);
    landingCross_ = &addBoolean("Landing cross", "Three axes through the impact point", true);
    landingBlock_ = &addBoolean("Landing block", "Outline the block that stops it", true);
    highlight_ = &addBoolean("Highlight entities", "Outline whoever the arc lands on", true);
    playerVelocity_ = &addBoolean("Add player velocity", "Include your own motion in the launch",
                                  false);
    fadeOut_ = &addBoolean("Fade out", "Leave the last arc behind for a moment", true);
}

void Trajectories::onEnable(Context&) {
    path_.clear();
    lastPath_.clear();
    lastPathAge_ = 99.0f;
}

Trajectories::Projectile Trajectories::properties(Context& context,
                                                  const game::FrameState& state) const {
    Projectile projectile;
    game::Game& game = game::Game::instance();
    const int slot = game.selectedSlot(context.env, state.player.handle);
    const game::Game::StackView stack = game.stack(context.env, state.player.handle, slot);
    if (!stack.present) {
        return projectile;
    }
    const std::string& id = stack.id;

    if (contains(id, "bow") && !contains(id, "crossbow")) {
        /* Vanilla's charge curve, and the reason a half-drawn bow is worth so
         * much less than it looks: the speed is quadratic in the draw. */
        float draw = static_cast<float>(state.player.itemUseTicks) + state.tickProgress;
        float charge = draw / 20.0f;
        charge = (charge * charge + charge * 2.0f) / 3.0f;
        charge = std::min(charge, 1.0f);
        if (charge < 0.1f) {
            return projectile;
        }
        projectile.gravity = 0.05;
        projectile.drag = 0.99;
        projectile.velocity = static_cast<double>(charge) * 3.0;
        projectile.tickCorrection = false;
        projectile.valid = true;
        return projectile;
    }
    if (contains(id, "crossbow")) {
        projectile.gravity = 0.05;
        projectile.drag = 0.99;
        projectile.velocity = 3.15;
        projectile.tickCorrection = false;
        projectile.valid = true;
        return projectile;
    }
    if (contains(id, "snowball") || contains(id, "egg") || contains(id, "ender_pearl") ||
        contains(id, "slime_ball")) {
        projectile.gravity = 0.03;
        projectile.velocity = 1.5;
        projectile.valid = true;
        return projectile;
    }
    if (contains(id, "experience_bottle")) {
        projectile.gravity = 0.07;
        projectile.velocity = 0.7;
        projectile.valid = true;
        return projectile;
    }
    if (contains(id, "potion")) {
        projectile.gravity = 0.05;
        projectile.velocity = 0.5;
        projectile.valid = true;
        return projectile;
    }
    return projectile;
}

void Trajectories::line3d(Context& context, const Vec3& from, const Vec3& to, float width,
                          const Color& colour) const {
    game::ScreenPoint a;
    game::ScreenPoint b;
    if (!context.projection->project(from, a) || !context.projection->project(to, b)) {
        return;
    }
    render::renderer().line(a.x, a.y, b.x, b.y, width, colour);
}

void Trajectories::outlinedBox(Context& context, const Vec3& minimum, const Vec3& maximum,
                               float width, const Color& colour) const {
    const Vec3 corner[8] = {
        {minimum.x, minimum.y, minimum.z}, {maximum.x, minimum.y, minimum.z},
        {minimum.x, maximum.y, minimum.z}, {maximum.x, maximum.y, minimum.z},
        {minimum.x, minimum.y, maximum.z}, {maximum.x, minimum.y, maximum.z},
        {minimum.x, maximum.y, maximum.z}, {maximum.x, maximum.y, maximum.z},
    };
    static constexpr int kEdges[12][2] = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3},
                                          {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
    for (const auto& edge : kEdges) {
        line3d(context, corner[edge[0]], corner[edge[1]], width, colour);
    }
}

void Trajectories::onRender(Context& context) {
    lastPathAge_ += context.deltaTime;

    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || !context.projection->valid()) {
        return;
    }

    const float width = lineWidth_->number();
    const float alpha = animation();

    const Projectile projectile = properties(context, state);
    if (!projectile.valid) {
        /* The arc the last throw would have taken, dimming out. Without it the
         * line vanishes the instant the item leaves the hand, which is the one
         * moment somebody is looking at it. */
        if (fadeOut_->booleanValue() && lastPath_.size() >= 2 && lastPathAge_ < kFadeSeconds) {
            const float fade = (1.0f - lastPathAge_ / kFadeSeconds) * alpha;
            const Color colour = ui::accentShade(0.7f, fade);
            for (std::size_t index = 1; index < lastPath_.size(); ++index) {
                line3d(context, lastPath_[index - 1], lastPath_[index], width, colour);
            }
        }
        return;
    }

    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return;
    }

    /* Where the projectile is actually created: straight below the eye by a
     * tenth of a block, which is what every ProjectileEntity constructor in
     * 1.21 does. The sixteen-hundredths sideways offset the 1.8 script carries
     * is from a version that spawned throwables beside the hand, and at half a
     * block out that offset is nearly twenty degrees - it put a visible hook on
     * the near end of every arc. */
    const double yaw = static_cast<double>(state.player.yaw) * kDegToRad;
    const double pitch = static_cast<double>(state.player.pitch) * kDegToRad;
    Vec3 position{state.player.pos.x, state.player.eyePos.y - 0.1, state.player.pos.z};

    Vec3 motion{-std::sin(yaw) * std::cos(pitch), -std::sin(pitch),
                std::cos(yaw) * std::cos(pitch)};
    const double length = motion.length();
    if (length < 1e-6) {
        context.env->DeleteLocalRef(world);
        return;
    }
    motion = motion * (projectile.velocity / length);
    if (playerVelocity_->booleanValue()) {
        motion = motion + state.player.velocity;
    }
    if (projectile.tickCorrection) {
        motion = motion * projectile.drag;
        motion.y -= projectile.gravity;
    }

    path_.clear();
    Hit hit = Hit::None;
    Vec3 hitPoint;
    int hitEntity = -1;

    const int ticks = static_cast<int>(maxTicks_->number());
    for (int tick = 0; tick < ticks; ++tick) {
        const Vec3 next = position + motion;
        const double step = motion.length();

        /* Whichever stops it first. Both tests are run against the same
         * segment, so "an arrow that clips the wall behind them" is the wall
         * and not the kill it looks like. */
        double entityAlong = step + 1.0;
        const game::EntityView* struck = nullptr;
        if (step > 1e-9) {
            const Vec3 direction = motion * (1.0 / step);
            for (const game::EntityView& entity : state.entities) {
                if (!entity.living || entity.crystal || entity.health <= 0.0f) {
                    continue;
                }
                const Vec3 low = entity.boxMin() -
                                 Vec3{kHitRadius, kHitRadius, kHitRadius};
                const Vec3 high = entity.boxMax() +
                                  Vec3{kHitRadius, kHitRadius, kHitRadius};
                const double along = game::rayBox(position, direction, low, high, step);
                if (along >= 0.0 && along <= step && along < entityAlong) {
                    entityAlong = along;
                    struck = &entity;
                }
            }
        }

        const game::BlockHit block =
            step > 1e-9 ? game::raycastBlocks(context.env, world, position,
                                              motion * (1.0 / step), step)
                        : game::BlockHit{};

        const bool entityFirst = struck != nullptr && (!block.hit || entityAlong < block.distance);
        if (entityFirst || block.hit) {
            const double along = entityFirst ? entityAlong : block.distance;
            const double fraction = step > 1e-9 ? along / step : 0.0;
            const auto substeps = static_cast<int>(std::ceil(fraction * kSubSteps));
            for (int sub = 0; sub < substeps; ++sub) {
                const double t = static_cast<double>(sub) / kSubSteps;
                path_.push_back(position + motion * t);
            }
            hitPoint = entityFirst ? position + motion * (along / std::max(step, 1e-9))
                                   : block.point;
            path_.push_back(hitPoint);
            if (entityFirst) {
                hit = Hit::Entity;
                hitEntity = struck->id;
            } else {
                hit = block.face == game::BlockFace::Up || block.face == game::BlockFace::Down
                          ? Hit::Ground
                          : Hit::Wall;
            }
            break;
        }

        for (int sub = 0; sub < kSubSteps; ++sub) {
            const double t = static_cast<double>(sub) / kSubSteps;
            path_.push_back(position + motion * t);
        }
        position = next;
        motion = motion * projectile.drag;
        motion.y -= projectile.gravity;
        if (position.y < -256.0) {
            break;
        }
    }
    context.env->DeleteLocalRef(world);

    /* One colour, three weights. An arc that lands on somebody is the accent
     * lifted towards white, one that stops on a block is the accent as it is,
     * and one that simply runs out of ticks is stepped down - so the throw
     * worth taking is the brightest thing on screen without introducing a
     * second hue to read. The landing marker says the rest: it is twice the
     * size for an entity hit. */
    const float weight = hit == Hit::Entity ? 1.4f : (hit == Hit::None ? 0.7f : 1.0f);
    const Color colour = ui::accentShade(weight, alpha);

    /* Skip the opening half block so the arc does not start inside the hand. */
    double skipped = 0.0;
    std::size_t first = 1;
    while (first < path_.size() && skipped < kHiddenLength) {
        skipped += (path_[first] - path_[first - 1]).length();
        ++first;
    }
    for (std::size_t index = first; index < path_.size(); ++index) {
        line3d(context, path_[index - 1], path_[index], width, colour);
    }

    if (hit != Hit::None) {
        const double boxSize = hit == Hit::Entity ? 0.4 : 0.2;
        const double crossSize = hit == Hit::Entity ? 0.5 : 0.3;
        if (landingBox_->booleanValue()) {
            outlinedBox(context, hitPoint - Vec3{boxSize, boxSize, boxSize},
                        hitPoint + Vec3{boxSize, boxSize, boxSize}, 2.0f, colour);
        }
        if (landingCross_->booleanValue()) {
            line3d(context, hitPoint - Vec3{crossSize, 0, 0}, hitPoint + Vec3{crossSize, 0, 0},
                   1.5f, colour);
            line3d(context, hitPoint - Vec3{0, crossSize, 0}, hitPoint + Vec3{0, crossSize, 0},
                   1.5f, colour);
            line3d(context, hitPoint - Vec3{0, 0, crossSize}, hitPoint + Vec3{0, 0, crossSize},
                   1.5f, colour);
        }
        if (landingBlock_->booleanValue() && hit != Hit::Entity) {
            const Vec3 cell{std::floor(hitPoint.x), std::floor(hitPoint.y), std::floor(hitPoint.z)};
            outlinedBox(context, cell + Vec3{0.005, 0.005, 0.005},
                        cell + Vec3{0.995, 0.995, 0.995}, 1.5f, colour);
        }
    }

    if (highlight_->booleanValue() && hitEntity >= 0) {
        for (const game::EntityView& entity : state.entities) {
            if (entity.id != hitEntity) {
                continue;
            }
            outlinedBox(context, entity.boxMin() - Vec3{0.3, 0.3, 0.3},
                        entity.boxMax() + Vec3{0.3, 0.3, 0.3}, 2.0f, colour);
            break;
        }
    }

    if (fadeOut_->booleanValue()) {
        lastPath_ = path_;
        lastPathAge_ = 0.0f;
    }
}

}  // namespace cold::modules
