#include "modules/player/FastMine.hpp"

#include <cmath>
#include <cstdio>

#include "bridge/Minecraft.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {

FastMine::FastMine()
    : Module("FastMine", "Shortens the wait between blocks and speeds up the break",
             Category::Player, 0) {
    setIcon(assets::IconAsset::Zap);

    delay_ = &addNumber("Break delay", "Milliseconds between finishing one block and starting the "
                                       "next; vanilla is 250",
                        250.0f, 0.0f, 250.0f, 0);
    speed_ = &addNumber("Break speed", "Multiplies how fast a block breaks", 1.0f, 1.0f, 4.0f, 2);
    requireAttack_ = &addBoolean("Require mouse down", "Only while the attack button is held", true);
}

std::string FastMine::suffix() const {
    /* Only when it is doing something the name does not already say. At one
     * times the module is a shorter delay and nothing else, and "1x" beside it
     * is a word in the array list spent on saying "no". */
    if (speed_->number() <= 1.0f) {
        return {};
    }
    char text[16];
    std::snprintf(text, sizeof(text), "%.2gx", static_cast<double>(speed_->number()));
    return text;
}

void FastMine::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        return;
    }
    if (requireAttack_->booleanValue() && !hooks::input().attackHeld()) {
        return;
    }

    game::Game& game = game::Game::instance();

    /* Vanilla parks this at five ticks the instant a block gives way, and
     * spends one tick of every break decrementing it. Lowering it is the whole
     * of the delay setting - but it has to be a lowering and not a write, or
     * the value never reaches zero and the module blocks the mining it was
     * meant to speed up. */
    const int ticks = static_cast<int>(std::floor(delay_->number() / 50.0f));
    if (ticks < 5 && game.blockBreakingCooldown(context.env) > ticks) {
        game.setBlockBreakingCooldown(context.env, ticks);
    }

    const float multiplier = speed_->number();
    if (multiplier <= 1.0f || !game.breakingBlock(context.env)) {
        return;
    }
    const game::CrosshairTarget target = game.crosshair(context.env);
    if (target.kind != game::CrosshairTarget::Kind::Block) {
        return;
    }
    /* The game's own call is the first tick of progress, so only the extra
     * ones are ours. A fractional multiplier lands as an extra call on the
     * fraction of ticks it names, which averages out to the number asked for
     * rather than rounding it away. */
    const float extra = multiplier - 1.0f;
    int calls = static_cast<int>(extra);
    accumulator_ += extra - static_cast<float>(calls);
    if (accumulator_ >= 1.0f) {
        accumulator_ -= 1.0f;
        ++calls;
    }
    for (int index = 0; index < calls; ++index) {
        game.attackBlock(context.env, target.x, target.y, target.z, target.face, true);
    }
}

}  // namespace cold::modules
