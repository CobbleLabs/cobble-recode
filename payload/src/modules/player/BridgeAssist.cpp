#include "modules/player/BridgeAssist.hpp"

#include <cmath>
#include <cstdio>

#include "bridge/Minecraft.hpp"
#include "game/Raycast.hpp"
#include "game/RotationEngine.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {
namespace {

/* Player hitbox half width. Everything about an edge is measured from this. */
constexpr double kHalfWidth = 0.3;
constexpr int kPrePlacePriority = 40;

int floorToInt(double value) { return static_cast<int>(std::floor(value)); }

}  // namespace

BridgeAssist::BridgeAssist()
    : Module("BridgeAssist", "Sneaks at the edge and aims at the face you are bridging off",
             Category::Player, 0) {
    setIcon(assets::IconAsset::Layers);

    mode_ = &addChoice("Mode", "Always assists, or only while you hold sneak yourself",
                       {"Always", "Hold sneak"}, 0);
    edgeOffset_ = &addNumber("Edge offset", "How far past the edge the feet may go before sneaking",
                             0.0f, 0.0f, 0.30f, 2);
    unsneakDelay_ = &addNumber("Unsneak delay", "Milliseconds the sneak is held after it is safe",
                               50.0f, 50.0f, 300.0f, 0);
    sneakOnJump_ = &addNumber("Sneak on jump", "Milliseconds of sneak when a jump leaves the ground",
                              0.0f, 0.0f, 500.0f, 0);
    holdingBlocks_ = &addBoolean("Holding blocks", "Only while a placeable block is in hand", false);
    lookingDown_ = &addBoolean("Looking down", "Only while looking down past seventy degrees",
                               false);
    notMovingForward_ =
        &addBoolean("Not moving forward", "Stop assisting while walking forward", false);
    prePlace_ = &addBoolean("Pre place", "Aim at the side face of the block under you", false);
    prePlaceSpeed_ = &addNumber("Pre place speed", "Degrees the aim may travel per tick", 40.0f,
                                5.0f, 180.0f, 0)
                         .visibleWhen([this] { return prePlace_->booleanValue(); });
}

std::string BridgeAssist::suffix() const {
    char text[16];
    std::snprintf(text, sizeof(text), "%.2f", static_cast<double>(edgeOffset_->number()));
    return text;
}

void BridgeAssist::onEnable(Context&) {
    sneaking_ = false;
    releaseHold_ = 0;
    jumpHold_ = 0;
    wasJumping_ = false;
    overEdge_ = false;
}

void BridgeAssist::onDisable(Context& context) {
    if (sneaking_) {
        game::Game::instance().setBinding(context.env, "sneak", false);
        sneaking_ = false;
    }
    releaseHold_ = 0;
    jumpHold_ = 0;
}

void BridgeAssist::holdSneak(Context& context) {
    if (!sneaking_) {
        game::Game::instance().setBinding(context.env, "sneak", true);
        sneaking_ = true;
    }
}

void BridgeAssist::releaseSneak(Context& context, bool immediately) {
    if (!sneaking_) {
        return;
    }
    if (!immediately) {
        if (jumpHold_ > 0) {
            --jumpHold_;
            return;
        }
        if (releaseHold_ > 0) {
            --releaseHold_;
            return;
        }
    }
    /* In "hold sneak" mode the player is leaning on the key themselves, and
     * taking it off them for the ticks they are safe is the point of the mode -
     * the key event that put it down is not repeated, so the write sticks. */
    game::Game::instance().setBinding(context.env, "sneak", false);
    sneaking_ = false;
    jumpHold_ = 0;
    releaseHold_ = 0;
}

double BridgeAssist::edgeDistance(Context& context, const game::FrameState& state) const {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return 0.0;
    }

    /* Where the feet land next tick. The velocity Minecraft carries is already
     * a per-tick displacement, so this is one step of the same integration the
     * game is about to run, minus the friction it will apply afterwards. */
    const Vec3 feet{state.player.pos.x + state.player.velocity.x, state.player.pos.y,
                    state.player.pos.z + state.player.velocity.z};

    const double minX = feet.x - kHalfWidth;
    const double maxX = feet.x + kHalfWidth;
    const double minZ = feet.z - kHalfWidth;
    const double maxZ = feet.z + kHalfWidth;
    const int groundY = floorToInt(feet.y - 0.01);

    double nearest = -1.0;
    for (int x = floorToInt(minX); x <= floorToInt(maxX); ++x) {
        for (int z = floorToInt(minZ); z <= floorToInt(maxZ); ++z) {
            if (game::blockPassable(context.env, world, x, groundY, z)) {
                continue;
            }
            /* Closest point of that cell to the feet, measured per axis and
             * taken as the larger of the two: a foot hanging over a corner is
             * as unsupported as one hanging over a side. */
            const double closestX = clamp(feet.x, static_cast<double>(x), static_cast<double>(x + 1));
            const double closestZ = clamp(feet.z, static_cast<double>(z), static_cast<double>(z + 1));
            const double distance =
                std::max(std::fabs(feet.x - closestX), std::fabs(feet.z - closestZ));
            if (nearest < 0.0 || distance < nearest) {
                nearest = distance;
            }
        }
    }

    context.env->DeleteLocalRef(world);
    return nearest;
}

bool BridgeAssist::wantsSneak(Context& context) const {
    game::Game& game = game::Game::instance();
    if (game.bindingPressed(context.env, "sneak")) {
        return true;
    }
    /* Ask the keyboard instead. The binding is cleared while the module is
     * running the player unsneaked, and Minecraft only sets it again on a key
     * event - which never comes, because the key never went up. Without this
     * the mode assists exactly once and then switches itself off for as long
     * as the player keeps holding shift, which is the whole time. */
    const int code = game.bindingKey(context.env, "sneak");
    return code != 0 && hooks::input().bindHeld(code);
}

bool BridgeAssist::conditionsMet(Context& context, const game::FrameState& state) const {
    game::Game& game = game::Game::instance();
    if (notMovingForward_->booleanValue() && game.bindingPressed(context.env, "forward")) {
        return false;
    }
    if (lookingDown_->booleanValue() && state.player.pitch < 70.0f) {
        return false;
    }
    if (holdingBlocks_->booleanValue() && !game.holdingBlock(context.env, state.player.handle)) {
        return false;
    }
    if (mode_->choice() == 1 && !sneaking_ && !wantsSneak(context)) {
        return false;
    }
    return true;
}

void BridgeAssist::prePlace(Context& context, const game::FrameState& state) {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return;
    }

    const float yaw = game::RotationEngine::instance().sentYaw();
    const int feetY = floorToInt(state.player.pos.y) - 1;
    const int minX = floorToInt(state.player.pos.x - kHalfWidth);
    const int maxX = floorToInt(state.player.pos.x + kHalfWidth);
    const int minZ = floorToInt(state.player.pos.z - kHalfWidth);
    const int maxZ = floorToInt(state.player.pos.z + kHalfWidth);

    /* Walking the pitch downwards rather than solving for it: the face the ray
     * lands on is what decides whether a click places anything, and the only
     * honest way to know that is to cast the ray the server will reconstruct.
     * The step is jittered so the search does not always sample the same
     * angles - the winning pitch is otherwise one of thirty fixed values. */
    float bestPitch = 0.0f;
    float bestDelta = 1e9f;
    bool found = false;

    for (float pitch = 60.0f; pitch <= 90.0f;) {
        pitch += random_.range(0.6f, 1.5f);
        const float sample = std::min(pitch, 90.0f);
        const game::BlockHit hit = game::raycastBlocks(
            context.env, world, state.player.eyePos, game::lookVector(yaw, sample), 4.5);
        if (!hit.hit || hit.face == game::BlockFace::Up || hit.face == game::BlockFace::Down) {
            continue;
        }
        /* Only a block the player is standing on, and only where the cell the
         * click would fill is actually empty. */
        if (hit.y != feetY || hit.x < minX || hit.x > maxX || hit.z < minZ || hit.z > maxZ) {
            continue;
        }
        int placedX = hit.x;
        int placedZ = hit.z;
        switch (hit.face) {
            case game::BlockFace::North: --placedZ; break;
            case game::BlockFace::South: ++placedZ; break;
            case game::BlockFace::West: --placedX; break;
            case game::BlockFace::East: ++placedX; break;
            default: break;
        }
        if (!game::blockPassable(context.env, world, placedX, feetY, placedZ)) {
            continue;
        }
        const float delta = std::fabs(sample - state.player.pitch);
        if (delta < bestDelta) {
            bestDelta = delta;
            bestPitch = sample;
            found = true;
        }
    }

    context.env->DeleteLocalRef(world);
    if (!found) {
        return;
    }

    /* Never silent, and not as a default that could be changed.
     *
     * A silent rotation is one the server is told about and the camera is not.
     * That is the right trade for something that acts on the world by itself,
     * because the player is not aiming at anything. Pre-place is the opposite:
     * the module places nothing at all, it only moves the aim so that the
     * click the player is about to make lands on the face they are bridging
     * off. An aim they cannot see is one they cannot correct, and it would put
     * their block somewhere they never pointed. So this moves the camera, and
     * at a speed they can follow. */
    game::RotationTarget target;
    target.yaw = yaw;
    target.pitch = bestPitch;
    target.speed = prePlaceSpeed_->number();
    target.smoothing = 0.35f;
    target.silent = false;
    target.priority = kPrePlacePriority;
    game::RotationEngine::instance().submit(target);
}

void BridgeAssist::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        onDisable(context);
        return;
    }
    if (!conditionsMet(context, state)) {
        releaseSneak(context, true);
        wasJumping_ = state.player.jumping;
        return;
    }

    if (prePlace_->booleanValue()) {
        prePlace(context, state);
    }

    /* A jump off a bridge is the one moment the edge test cannot help: the feet
     * leave the block before the arc is decided. Raven holds the sneak across
     * that window instead, for a rounded number of ticks. */
    const bool jumping = game::Game::instance().bindingPressed(context.env, "jump");
    if (jumping && !wasJumping_ && state.player.onGround && sneakOnJump_->number() > 0.0f) {
        const float ticks = sneakOnJump_->number() / 50.0f;
        jumpHold_ = static_cast<int>(ticks) +
                    (random_.unit() < ticks - std::floor(ticks) ? 1 : 0);
        holdSneak(context);
        wasJumping_ = jumping;
        return;
    }
    wasJumping_ = jumping;

    const double distance = edgeDistance(context, state);
    if (distance < 0.0) {
        /* Nothing under the feet at all next tick. On the ground that is the
         * step off the edge the sneak exists for; in the air it is a fall
         * already in progress and there is nothing left to hold on to. */
        if (state.player.onGround) {
            overEdge_ = true;
            holdSneak(context);
        } else {
            releaseSneak(context, false);
        }
        return;
    }

    if (distance > static_cast<double>(edgeOffset_->number())) {
        overEdge_ = true;
        holdSneak(context);
        return;
    }
    if (overEdge_) {
        overEdge_ = false;
        const float ticks = (unsneakDelay_->number() - 50.0f) / 50.0f;
        releaseHold_ = static_cast<int>(ticks) +
                       (random_.unit() < ticks - std::floor(ticks) ? 1 : 0);
    }
    releaseSneak(context, false);
}

}  // namespace cold::modules
