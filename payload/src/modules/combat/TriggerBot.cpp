#include "modules/combat/TriggerBot.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>

#include "bridge/Minecraft.hpp"
#include "core/Log.hpp"
#include "core/Time.hpp"
#include "game/TargetFinder.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {
namespace {

constexpr std::string_view kScope = "trigger";

int floorToInt(double value) { return static_cast<int>(std::floor(value)); }

}  // namespace

TriggerBot::TriggerBot()
    : Module("TriggerBot", "Attacks whatever the crosshair is already on", Category::Combat, 0) {
    setIcon(assets::IconAsset::Crosshair);

    mode_ = &addChoice("Mode",
                       "Smart only swings falling crits and settled hits, Any swings at "
                       "whatever is aimed at once the rest is met",
                       {"Smart", "Any"}, 0);
    clickMode_ = &addChoice("Click",
                            "Vanilla queues a press the game acts on itself; Direct sends the "
                            "attack from here like Argon does",
                            {"Vanilla", "Direct"}, 0);
    allItems_ = &addBoolean("All Items", "Fire with anything held, on the sword delay", false);
    swordMin_ =
        &addNumber("Sword Delay Min", "Fastest swing with a sword, in ms", 540.0f, 0.0f, 1000.0f, 0);
    swordMax_ =
        &addNumber("Sword Delay Max", "Slowest swing with a sword, in ms", 550.0f, 0.0f, 1000.0f, 0);
    axeMin_ =
        &addNumber("Axe Delay Min", "Fastest swing with an axe, in ms", 780.0f, 0.0f, 1000.0f, 0);
    axeMax_ =
        &addNumber("Axe Delay Max", "Slowest swing with an axe, in ms", 800.0f, 0.0f, 1000.0f, 0);
    inScreen_ = &addBoolean("Work In Screen", "Trigger even while a screen is open", false);
    whileUse_ = &addBoolean("While Use", "Hit even while eating or blocking", false);
    onLeftClick_ =
        &addBoolean("On Left Click", "Only trigger while holding left click", false);
    checkShield_ = &addBoolean("Check Shield", "Skip targets whose shield faces you", false);
    whileAscend_ = &addBoolean("While Ascending", "Allow hits while rising from a jump", false);
    clickSimulation_ =
        &addBoolean("Click Simulation", "Kept for parity, hits already move CPS", false);
    strayBypass_ =
        &addBoolean("Stray Bypass", "Also hit zombies, bypassing Stray's anti-triggerbot", false);
    allEntities_ = &addBoolean("All Entities", "Hit whatever the crosshair lands on", false);
    useShield_ = &addBoolean("Use Shield", "Hold a shield in the offhand between hits", false);
    shieldTime_ =
        &addNumber("Shield Time", "How long to hold the block, in ms", 350.0f, 100.0f, 1000.0f, 0);
    sticky_ = &addBoolean("Same Player", "Only hit the current attack target, good for FFA", false);
    minCharge_ = &addNumber("Min charge", "Attack charge required before a hit goes out", 85.0f,
                            0.0f, 100.0f, 0);
    noSweeps_ = &addBoolean("No sweeps", "Skip the last ticks of a fall, where a hit lands grounded",
                            true);
    sprintReset_ = &addBoolean("Sprint reset", "Drop sprint for a tick before hitting", true);
    swing_ = &addBoolean("Swing", "Play the hand animation on Direct hits", true);
}

std::string TriggerBot::suffix() const {
    if (targetId_ < 0) {
        return {};
    }
    return mode_->choiceName();
}

void TriggerBot::onEnable(Context&) {
    currentSwordDelay_ = rollDelay(swordMin_->number(), swordMax_->number());
    currentAxeDelay_ = rollDelay(axeMin_->number(), axeMax_->number());
    /* First engagement is instant: the enable-time roll must not be waited out
     * while already staring at someone. Mid-fight cadence is untouched, every
     * strike still re-arms the full delay. */
    delayTimer_.reset();
    delayTimer_.elapse(std::max(currentSwordDelay_, currentAxeDelay_));
    pendingId_ = -1;
    pendingSprintReset_ = false;
    groundTicks_ = 0;
    targetId_ = -1;
    hits_ = 0;
    shieldHeld_ = false;
}

void TriggerBot::onDisable(Context& context) {
    game::Game& game = game::Game::instance();
    /* Sprint was borrowed, not taken. */
    if (pendingSprintReset_ && context.state->player.valid) {
        game.setSprinting(context.env, context.state->player.handle, true);
    }
    releaseShield(context);
    pendingId_ = -1;
    pendingSprintReset_ = false;
    targetId_ = -1;
}

int TriggerBot::rollDelay(float low, float high) {
    const int lo = static_cast<int>(low);
    const int hi = static_cast<int>(high);
    if (hi <= lo) {
        return lo;
    }
    return rng_.range(lo, hi);
}

int TriggerBot::delayFor(Context& context, bool& axe) {
    game::Game& game = game::Game::instance();
    const bool sword = game.holdingSword(context.env, context.state->player.handle);
    axe = !sword && game.holdingItem(context.env, context.state->player.handle,
                                     game::ItemKind::Axe);
    if (allItems_->booleanValue()) {
        axe = false;
        return currentSwordDelay_;
    }
    if (sword) {
        return currentSwordDelay_;
    }
    if (axe) {
        return currentAxeDelay_;
    }
    return -1;
}

bool TriggerBot::entityAllowed(Context& context, const game::CrosshairTarget& target) const {
    const game::FrameState& state = *context.state;
    game::Game& game = game::Game::instance();

    const game::EntityView* view = nullptr;
    for (const game::EntityView& entity : state.entities) {
        if (entity.id == target.entityId) {
            view = &entity;
            break;
        }
    }

    /* Players always count. Stray's anti-triggerbot is dodged through its
     * zombie stand-ins. All Entities takes whatever the crosshair found,
     * including anything too far sideways to be captured. */
    const bool zombie =
        target.entity != nullptr && game.isZombie(context.env, target.entity);
    bool ok = false;
    if (view != nullptr && view->isPlayer) {
        ok = true;
    } else if (strayBypass_->booleanValue() && zombie) {
        ok = true;
    } else if (allEntities_->booleanValue()) {
        ok = true;
    }
    if (!ok) {
        return false;
    }

    /* A raised shield facing the hit eats it, so the swing is spent nowhere.
     * Facing away it cannot block, and the hit goes through. */
    if (checkShield_->booleanValue() && view != nullptr && view->blocking &&
        !shieldFacingAway(state, *view)) {
        return false;
    }
    return true;
}

bool TriggerBot::shieldFacingAway(const game::FrameState& state,
                                  const game::EntityView& target) const {
    Vec3 toPlayer{state.player.pos.x - target.tickPos.x, state.player.pos.y - target.tickPos.y,
                  state.player.pos.z - target.tickPos.z};
    if (toPlayer.lengthSquared() <= 0.0) {
        return false;
    }
    const double length = toPlayer.length();
    toPlayer = toPlayer * (1.0 / length);

    const double yaw = static_cast<double>(target.yaw) * kDegToRad;
    const double pitch = static_cast<double>(target.pitch) * kDegToRad;
    Vec3 facing{-std::sin(yaw) * std::cos(pitch), -std::sin(pitch),
                std::cos(yaw) * std::cos(pitch)};
    if (facing.lengthSquared() <= 0.0) {
        return false;
    }
    facing = facing * (1.0 / facing.length());
    return facing.dot(toPlayer) < 0.0;
}

bool TriggerBot::aboutToLand(Context& context, const game::FrameState& state) const {
    const double fall = -state.player.velocity.y;
    if (state.player.onGround || fall <= 0.0) {
        return false;
    }
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return false;
    }
    /* The four corners of the hitbox, from the feet down by as far as this tick
     * will carry them. Anything solid in there means the landing resolves
     * before the hit does, and the server scores a sweep instead of a crit. */
    const int lowest = floorToInt(state.player.pos.y - fall);
    const int highest = floorToInt(state.player.pos.y - 0.001);
    bool blocked = false;
    for (int y = highest; y >= lowest && !blocked; --y) {
        for (int dx = -1; dx <= 1 && !blocked; dx += 2) {
            for (int dz = -1; dz <= 1 && !blocked; dz += 2) {
                const int cellX = floorToInt(state.player.pos.x + 0.3 * dx);
                const int cellZ = floorToInt(state.player.pos.z + 0.3 * dz);
                if (!game::Game::instance().isBlockReplaceable(context.env, world, cellX, y,
                                                               cellZ)) {
                    blocked = true;
                }
            }
        }
    }
    context.env->DeleteLocalRef(world);
    return blocked;
}

bool TriggerBot::stanceAllows(Context& context, const game::FrameState& state) const {
    /* A rising player cannot crit, so Smart refuses outright rather than
     * trading the crit away for tempo. */
    const bool falling = state.player.velocity.y < 0.0 && !state.player.onGround;
    if (!falling && state.player.velocity.y > 0.0) {
        return false;
    }
    if (noSweeps_->booleanValue() && falling && aboutToLand(context, state)) {
        return false;
    }
    if (state.player.attackCooldown < minCharge_->number() / 100.0f) {
        return false;
    }
    if (mode_->choice() == 1) {
        /* Any: whatever is aimed at, once the charge and the delays are met. */
        return true;
    }
    /* Smart: take the crit when it is there, and otherwise only hit from a
     * settled stance, so a swing is never spent mid-jump. */
    const bool critFall =
        falling && state.player.fallDistance > 0.05 && !aboutToLand(context, state);
    return critFall || (state.player.onGround && groundTicks_ >= 2);
}

void TriggerBot::holdShield(Context& context) {
    if (!useShield_->booleanValue()) {
        return;
    }
    game::Game& game = game::Game::instance();
    const game::FrameState& state = *context.state;
    if (!game.holdingShield(context.env, state.player.handle, true) || state.player.usingItem) {
        return;
    }
    const std::int64_t now = nowMillis();
    if (!shieldHeld_) {
        game.pressKey(context.env, true, true);
        shieldHeld_ = true;
        shieldHoldStart_ = now;
    } else if (now - shieldHoldStart_ >= static_cast<std::int64_t>(shieldTime_->number())) {
        releaseShield(context);
    }
}

void TriggerBot::releaseShield(Context& context) {
    if (!shieldHeld_) {
        return;
    }
    game::Game::instance().pressKey(context.env, true, false);
    shieldHeld_ = false;
}

void TriggerBot::strike(Context& context, const game::CrosshairTarget& target, bool axe) {
    game::Game& game = game::Game::instance();
    const game::FrameState& state = *context.state;

    /* Argon drops a raised shield for the hit itself. */
    if (useShield_->booleanValue() && state.player.usingItem &&
        game.holdingShield(context.env, state.player.handle, true)) {
        game.pressKey(context.env, true, false);
    }
    shieldHeld_ = false;

    if (clickMode_->choice() == 0) {
        /* One press added to the binding. The game drains it in
         * handleInputEvents on the next tick and does the whole of vanilla
         * doAttack from there - the pick, the packet, the swing and the
         * cooldown - so the packet leaves from the place Minecraft leaves
         * from, in front of that tick's movement packet. */
        game.queueClick(context.env, false);
    } else {
        /* Argon's own path: straight through the interaction manager. */
        game.attackEntity(context.env, state.player.handle, target.entity);
        if (swing_->booleanValue()) {
            game.swingHand(context.env, state.player.handle, false);
        }
        game.setAttackCooldown(context.env, 0);
    }

    /* Click Simulation stays a parity no-op, same as AutoCrystal's: a Vanilla
     * hit already is a press on the game's binding, which is what a CPS
     * readout counts, and a synthetic press here would be a second real
     * attack. The setting is still read by the menu, so it stays. */

    if (axe && !allItems_->booleanValue()) {
        currentAxeDelay_ = rollDelay(axeMin_->number(), axeMax_->number());
    } else {
        currentSwordDelay_ = rollDelay(swordMin_->number(), swordMax_->number());
    }
    delayTimer_.reset();
    ++hits_;
}

void TriggerBot::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    game::Game& game = game::Game::instance();
    hooks::Input& input = hooks::input();

    if (!state.inWorld || !state.player.valid ||
        (!inScreen_->booleanValue() && state.screenOpen)) {
        if (pendingSprintReset_) {
            game.setSprinting(context.env, state.player.handle, true);
            pendingSprintReset_ = false;
        }
        pendingId_ = -1;
        targetId_ = -1;
        releaseShield(context);
        return;
    }

    groundTicks_ = state.player.onGround ? groundTicks_ + 1 : 0;

    /* Argon only fires while holding left click when asked to. */
    if (onLeftClick_->booleanValue() && !input.attackHeld()) {
        targetId_ = -1;
        releaseShield(context);
        return;
    }

    /* Eaten food and a raised shield both read as using the item while the
     * use key is down, which covers Argon's offhand food-or-shield check and
     * the main-hand meal it misses. */
    if (state.player.usingItem && input.useHeld() && !whileUse_->booleanValue()) {
        targetId_ = -1;
        return;
    }

    /* Argon refuses the rising part of a jump outright. Smart refuses it too
     * inside the stance check; this gate is what gives Any the same manners. */
    if (!whileAscend_->booleanValue() && !state.player.onGround &&
        (state.player.velocity.y > 0.0 || state.player.fallDistance <= 0.0)) {
        targetId_ = -1;
        return;
    }

    /* A hit held back for a sprint reset lands this tick. Last tick's sprint
     * packet has already gone out, which is the whole point of the wait.
     * Vanilla is re-picked by the game itself at act time, so any valid
     * target under the crosshair may take the held hit - demanding the same
     * id drops the strike whenever fast footwork moves it off for a tick,
     * which reads as slow reactions in a sprinting fight. Direct sends from
     * here, so it keeps the same-id check: a tick of target motion is the
     * gap between a legal hit and a reach flag (see [[triggerbot-reach]]). */
    if (pendingId_ >= 0) {
        const int wanted = pendingId_;
        pendingId_ = -1;
        const game::CrosshairTarget repicked = game.crosshair(context.env);
        const bool aimed = repicked.kind == game::CrosshairTarget::Kind::Entity &&
                           repicked.entity != nullptr;
        targetId_ = aimed ? repicked.entityId : -1;
        if (aimed) {
            game::CombatTarget::instance().publish(targetId_, nowSeconds());
        }
        const bool vanilla = clickMode_->choice() == 0;
        if (aimed && (vanilla || repicked.entityId == wanted) &&
            entityAllowed(context, repicked) && stanceAllows(context, state)) {
            bool axe = false;
            const int delay = delayFor(context, axe);
            if (delay >= 0 && delayTimer_.elapsed(delay)) {
                strike(context, repicked, axe);
            }
        }
        if (pendingSprintReset_) {
            game.setSprinting(context.env, state.player.handle, true);
            pendingSprintReset_ = false;
        }
        return;
    }

    const game::CrosshairTarget target = game.crosshair(context.env);
    if (target.kind != game::CrosshairTarget::Kind::Entity || target.entity == nullptr) {
        targetId_ = -1;
        releaseShield(context);
        return;
    }
    targetId_ = target.entityId;
    game::CombatTarget::instance().publish(targetId_, nowSeconds());

    if (!entityAllowed(context, target)) {
        return;
    }

    /* Same Player: only the entity the player is already fighting. */
    if (sticky_->booleanValue()) {
        const int current = game.attackTargetId(context.env, state.player.handle);
        if (current >= 0 && current != target.entityId) {
            return;
        }
    }

    bool axe = false;
    const int delay = delayFor(context, axe);
    if (delay < 0) {
        releaseShield(context);
        return;
    }

    if (!stanceAllows(context, state)) {
        return;
    }

    if (!delayTimer_.elapsed(delay)) {
        holdShield(context);
        return;
    }

    /* Sprinting on the ground caps knockback. Dropping sprint for one tick is
     * what a w-tap does, so hold the hit back a tick and take the better one. */
    if (sprintReset_->booleanValue() && state.player.sprinting && state.player.onGround) {
        game.setSprinting(context.env, state.player.handle, false);
        pendingSprintReset_ = true;
        pendingId_ = target.entityId;
        return;
    }

    strike(context, target, axe);
    if (hits_ == 1) {
        COLD_DEBUG(kScope, "first hit on entity %d", target.entityId);
    }
}

}  // namespace cold::modules
