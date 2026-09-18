#include "modules/combat/WTap.hpp"

#include "bridge/Minecraft.hpp"
#include "core/Log.hpp"
#include "hooks/Input.hpp"
#include "modules/ModuleManager.hpp"
#include "modules/combat/TriggerBot.hpp"

namespace cold::modules {
namespace {

constexpr std::string_view kScope = "wtap";

}  // namespace

WTap::WTap() : Module("WTap", "W-taps every landed hit", Category::Combat, 0) {
    delayMin_ = &addNumber("Delay min", "Shortest W release in milliseconds", 230.0f, 0.0f,
                           1000.0f, 0);
    delayMax_ = &addNumber("Delay max", "Longest W release in milliseconds", 270.0f, 0.0f,
                           1000.0f, 0);
    inAir_ = &addBoolean("In air", "Also tap while airborne", false);
}

void WTap::onEnable(Context&) {
    tapping_ = false;
    logged_ = false;
    lastHits_ = 0;
}

void WTap::onDisable(Context& context) {
    if (tapping_) {
        game::Game& game = game::Game::instance();
        if (game.bindingPressed(context.env, "forward")) {
            game.setBinding(context.env, "forward", true);
        }
        tapping_ = false;
    }
    if (auto* trigger = modules().get<TriggerBot>(); trigger != nullptr) {
        lastHits_ = trigger->hits();
    }
}

void WTap::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    game::Game& game = game::Game::instance();
    auto* trigger = modules().get<TriggerBot>();
    if (!state.inWorld || !state.player.valid || state.screenOpen || trigger == nullptr) {
        if (tapping_) {
            game.setBinding(context.env, "forward", true);
            tapping_ = false;
        }
        return;
    }

    const bool holdingW = game.bindingPressed(context.env, "forward");
    const bool onGround = state.player.onGround;

    if (tapping_) {
        const bool jumped = game.bindingPressed(context.env, "jump");
        /* Letting go or jumping hands W straight back, like Argon. */
        if (!holdingW || (jumped && !inAir_->booleanValue()) || nowMillis() >= tapEnd_) {
            if (holdingW) {
                game.setBinding(context.env, "forward", true);
            }
            tapping_ = false;
        }
        lastHits_ = trigger->hits();
        return;
    }

    const bool struck = trigger->hits() != lastHits_;
    lastHits_ = trigger->hits();
    if (!struck || !holdingW || !state.player.sprinting) {
        return;
    }
    if (!inAir_->booleanValue() && !onGround) {
        return;
    }
    const float span =
        std::max(delayMax_->number() - delayMin_->number(), 0.0f);
    tapEnd_ = nowMillis() + static_cast<long long>(delayMin_->number() +
                                                   random_.unit() * span);
    game.setBinding(context.env, "forward", false);
    tapping_ = true;
    if (!logged_) {
        logged_ = true;
        COLD_DEBUG(kScope, "first tap %lldms after strike", tapEnd_ - nowMillis());
    }
}

}  // namespace cold::modules
