#include "modules/player/FastPlace.hpp"

#include <cstdio>

#include "bridge/Minecraft.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {

FastPlace::FastPlace()
    : Module("FastPlace", "Removes the four tick wait between right clicks", Category::Player, 0) {
    setIcon(assets::IconAsset::Hammer);

    delay_ = &addNumber("Tick delay", "Ticks between uses; vanilla is four", 1.0f, 0.0f, 3.0f, 0);
    activation_ = &addNumber("Activation time", "Milliseconds the button must be held first", 0.0f,
                             0.0f, 100.0f, 0);
    blocksOnly_ = &addBoolean("Blocks only", "Only while a placeable block is in hand", true);
    pitchCheck_ = &addBoolean("Pitch check", "Only while looking down past seventy degrees", false);
}


void FastPlace::onEnable(Context&) { wasHeld_ = false; }

bool FastPlace::allowed(Context& context) const {
    const game::FrameState& state = *context.state;
    if (blocksOnly_->booleanValue() &&
        !game::Game::instance().holdingBlock(context.env, state.player.handle)) {
        return false;
    }
    if (pitchCheck_->booleanValue() && state.player.pitch < 70.0f) {
        return false;
    }
    return true;
}

void FastPlace::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        wasHeld_ = false;
        return;
    }

    const bool held = hooks::input().useHeld() || state.player.usingItem;
    if (!held) {
        wasHeld_ = false;
        return;
    }
    if (!wasHeld_) {
        wasHeld_ = true;
        heldSince_.reset();
    }
    if (!heldSince_.elapsed(static_cast<std::int64_t>(activation_->number()))) {
        return;
    }
    if (!allowed(context)) {
        return;
    }

    /* Lowered, never written.
     *
     * The game decrements this at the top of every tick and only uses the item
     * on the tick it finds it at zero. Writing the delay unconditionally means
     * the value is put back before it can ever be found at zero, and the
     * module stops the placing it was meant to speed up - which is the
     * opposite of the setting and looks exactly like the module doing nothing. */
    game::Game& game = game::Game::instance();
    const int delay = static_cast<int>(delay_->number());
    if (game.itemUseCooldown(context.env) > delay) {
        game.setItemUseCooldown(context.env, delay);
    }
}

}  // namespace cold::modules
