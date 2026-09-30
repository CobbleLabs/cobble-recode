#include "modules/combat/JumpReset.hpp"

#include <cmath>
#include <cstdio>

#include "bridge/Minecraft.hpp"
#include "game/RotationEngine.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {
namespace {

constexpr float kFovCone = 165.0f;

}  // namespace

JumpReset::JumpReset()
    : Module("JumpReset", "Jumps on the tick a hit lands so the knockback carries",
             Category::Combat, 0) {
    setIcon(assets::IconAsset::Activity);

    chance_ = &addNumber("Chance", "Percent of hits to reset on", 80.0f, 0.0f, 100.0f, 0);
    requireAttack_ = &addBoolean("Require mouse down", "Only while attacking", false);
    requireForward_ = &addBoolean("Require moving forward", "Only while walking forward", true);
    requireAim_ = &addBoolean("Require aim", "Only while the crosshair is on a player", true);
}

std::string JumpReset::suffix() const {
    const int percent = static_cast<int>(chance_->number());
    if (percent >= 100) {
        return {};
    }
    char text[8];
    std::snprintf(text, sizeof(text), "%d%%", percent);
    return text;
}

void JumpReset::onEnable(Context&) {
    lastHurtTime_ = 0;
    lastFallDistance_ = 0.0;
    ignoreNext_ = false;
    holdingJump_ = false;
    jumpWasTheirs_ = false;
}

void JumpReset::onDisable(Context& context) {
    if (holdingJump_ && !jumpWasTheirs_) {
        game::Game::instance().setBinding(context.env, "jump", false);
    }
    holdingJump_ = false;
    jumpWasTheirs_ = false;
}

bool JumpReset::badEffect(Context& context) const {
    for (const game::Game::EffectView& effect :
         game::Game::instance().statusEffects(context.env, context.state->player.handle)) {
        if (effect.id == "jump_boost" || effect.id == "poison" || effect.id == "wither") {
            return true;
        }
    }
    return false;
}

bool JumpReset::aimingAtPlayer(Context& context) const {
    const game::CrosshairTarget target = game::Game::instance().crosshair(context.env);
    if (target.kind != game::CrosshairTarget::Kind::Entity || target.crystal) {
        return false;
    }
    for (const game::EntityView& entity : context.state->entities) {
        if (entity.id == target.entityId) {
            return entity.isPlayer;
        }
    }
    return false;
}

bool JumpReset::pushedBackwards(const game::FrameState& state) const {
    const double speed = state.player.velocity.horizontalLength();
    if (speed < 0.02) {
        return true;
    }
    /* Minecraft's yaw convention: -z is zero and yaw grows clockwise, which is
     * what atan2(-x, z) gives directly. */
    const auto motionYaw = static_cast<float>(std::atan2(-state.player.velocity.x,
                                                         state.player.velocity.z) *
                                              static_cast<double>(kRadToDeg));
    const float look = game::RotationEngine::instance().sentYaw();
    return std::fabs(angleDifference(look, motionYaw)) <= kFovCone;
}

void JumpReset::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        onDisable(context);
        return;
    }

    game::Game& game = game::Game::instance();

    /* One tick of jump is the whole press. Anything longer and the player is
     * committed to an arc they did not ask for. */
    if (holdingJump_) {
        holdingJump_ = false;
        if (!jumpWasTheirs_) {
            game.setBinding(context.env, "jump", false);
        }
    }

    const int hurtTime = state.player.hurtTime;
    /* Landing a long fall sets hurtTime the same way a hit does. Jumping out of
     * that is a second fall, not a reset. */
    if (state.player.onGround && lastFallDistance_ > 3.0) {
        ignoreNext_ = true;
    }

    if (hurtTime > lastHurtTime_) {
        const bool attacking = !requireAttack_->booleanValue() || hooks::input().attackHeld();
        const bool forward =
            !requireForward_->booleanValue() || game.bindingPressed(context.env, "forward");
        const bool aimed = !requireAim_->booleanValue() || aimingAtPlayer(context);
        const bool rolled =
            chance_->number() >= 100.0f || random_.range(0.0f, 100.0f) < chance_->number();

        if (!ignoreNext_ && state.player.onGround && attacking && forward && aimed && rolled &&
            pushedBackwards(state) && !badEffect(context)) {
            jumpWasTheirs_ = game.bindingPressed(context.env, "jump");
            game.setBinding(context.env, "jump", true);
            holdingJump_ = true;
        }
        ignoreNext_ = false;
    }

    lastHurtTime_ = hurtTime;
    lastFallDistance_ = state.player.fallDistance;
}

}  // namespace cold::modules
