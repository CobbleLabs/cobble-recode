#include "modules/movement/InvMove.hpp"

#include "bridge/Minecraft.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {
namespace {

constexpr const char* kBindings[] = {"forward", "back", "left",
                                     "right",   "jump", "sneak"};

}  // namespace

InvMove::InvMove()
    : Module("InvMove", "Walks while the inventory is open", Category::Movement, 0) {}

void InvMove::releaseAll(Context& context) const {
    game::Game& game = game::Game::instance();
    for (const char* binding : kBindings) {
        game.setBinding(context.env, binding, false);
    }
}

void InvMove::onDisable(Context& context) {
    if (active_) {
        releaseAll(context);
        active_ = false;
    }
}

void InvMove::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    game::Game& game = game::Game::instance();
    if (!state.inWorld || !state.player.valid || !state.screenOpen ||
        !game.inventoryOpen(context.env)) {
        if (active_) {
            releaseAll(context);
            active_ = false;
        }
        return;
    }
    active_ = true;
    for (const char* binding : kBindings) {
        game.setBinding(context.env, binding,
                        hooks::input().keyHeld(game.bindingKey(context.env, binding)));
    }
}

}  // namespace cold::modules
