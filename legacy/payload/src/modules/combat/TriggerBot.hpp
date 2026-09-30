#pragma once

#include <cstdint>

#include "core/Time.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* Attacks whatever the vanilla crosshair is already on.
 *
 * Ported from Argon's `TriggerBot`. Nothing here moves the camera, which is
 * the whole point: the rotation is the player's own, so there is no rotation
 * for a server to disbelieve. What is left is deciding *when* a hit is worth
 * sending, and the target is simply `MinecraftClient.crosshairTarget`, exactly
 * as Argon reads it - no private ray, no reach extension.
 *
 * Kept from the previous cold.wtf build instead of Argon's equivalents:
 *  - Mode Smart/Any with the crit timing (`timingAllows` + `aboutToLand`):
 *    Smart only swings a falling crit or a settled on-ground hit with the
 *    cooldown charged; Any swings whenever the rest of the settings are met.
 *    Argon's OnlyCrit Sword/Axe bools are dropped in favour of this.
 *  - Click Vanilla/Direct. Vanilla queues a press the game acts on itself, so
 *    the packet leaves from the place Minecraft leaves from, in front of that
 *    tick's movement packet (see [[grim-rotation-ordering]]). Direct sends the
 *    attack from here instead, exactly as Argon does.
 *  - Sprint reset: drops sprint for a tick before hitting, which is what the
 *    knockback formula actually reads.
 * TPS sync is gone: Smart/Any gate on the live cooldown value directly.
 *
 * Faithful to Argon: sword/axe split with randomised min-max delays, Work In
 * Screen, While Use, On Left Click, Check Shield (with the facing test),
 * While Ascending, Click Simulation, Stray Bypass (zombies), All Entities, Use
 * Shield with Shield Time, Same Player (sticky).
 *
 * Click Simulation is a kept-for-parity no-op, same as AutoCrystal's: every
 * Vanilla hit is already a press on the game's own binding, which is the
 * counter a CPS readout reads, and a synthetic press in Direct mode would be
 * a second real attack the game acts on. There is no Attack event bus to
 * cancel from, so Argon's "cancel attacks not from left click" has no
 * equivalent either - vanilla only attacks from a real or queued press. */
class TriggerBot : public Module {
public:
    TriggerBot();

    std::string suffix() const override;

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;

    /* Exposed so the target HUD can highlight the same entity. */
    int currentTargetId() const { return targetId_; }
    /* Landed strikes, so WTap can tap around them. */
    int hits() const { return hits_; }

private:
    /* Randomised delay in [low, high] ms, Argon's MinMaxSetting semantics. */
    int rollDelay(float low, float high);
    /* Which randomised delay guards this target, or -1 when the held item is
     * not allowed to trigger. All Items mode fires on anything with the sword
     * delay, exactly as Argon does. */
    int delayFor(Context& context, bool& axe);
    /* Argon's target filters against the crosshair entity: players always,
     * zombies with Stray Bypass, anything with All Entities. */
    bool entityAllowed(Context& context, const game::CrosshairTarget& target) const;
    /* Port of Argon's isShieldFacingAway: true when the target looks away, so
     * the shield cannot block the hit. */
    bool shieldFacingAway(const game::FrameState& state,
                          const game::EntityView& target) const;
    /* Kept crit timing: rising never crits, No Sweeps refuses the tick before
     * landing, the charge has to be there, then Smart wants a crit fall or a
     * settled stance while Any takes whatever is aimed at. */
    bool stanceAllows(Context& context, const game::FrameState& state) const;
    /* Would the server count this hit as landing on the ground? Sweeping a
     * sword the tick before touching down turns a crit into a sweep. */
    bool aboutToLand(Context& context, const game::FrameState& state) const;
    /* Puts one hit on the wire, either through the vanilla binding or
     * straight out of here, and re-rolls the delay that guarded it. */
    void strike(Context& context, const game::CrosshairTarget& target, bool axe);
    /* Argon's shield automation: hold the block while a delay is still
     * running, release it for the hit itself. */
    void holdShield(Context& context);
    void releaseShield(Context& context);

    Setting* mode_;
    Setting* clickMode_;
    Setting* allItems_;
    Setting* swordMin_;
    Setting* swordMax_;
    Setting* axeMin_;
    Setting* axeMax_;
    Setting* inScreen_;
    Setting* whileUse_;
    Setting* onLeftClick_;
    Setting* checkShield_;
    Setting* whileAscend_;
    Setting* clickSimulation_;
    Setting* strayBypass_;
    Setting* allEntities_;
    Setting* useShield_;
    Setting* shieldTime_;
    Setting* sticky_;
    Setting* minCharge_;
    Setting* noSweeps_;
    Setting* sprintReset_;
    Setting* swing_;

    Timer delayTimer_;
    Random rng_;
    int currentSwordDelay_ = 0;
    int currentAxeDelay_ = 0;

    /* One tick of held fire: sprint released now, hit sent next tick. */
    int pendingId_ = -1;
    bool pendingSprintReset_ = false;
    int groundTicks_ = 0;
    int targetId_ = -1;
    int hits_ = 0;

    bool shieldHeld_ = false;
    std::int64_t shieldHoldStart_ = 0;
};

}  // namespace cold::modules
