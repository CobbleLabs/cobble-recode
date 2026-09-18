#include "modules/movement/Sprint.hpp"

#include "bridge/Minecraft.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {

Sprint::Sprint() : Module("Sprint", "Keeps sprinting while moving", Category::Movement, 0) {}

void Sprint::onDisable(Context& context) {
    if (context.state->player.valid) {
        game::Game::instance().setSprinting(context.env, context.state->player.handle, false);
    }
}

void Sprint::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        return;
    }
    if (state.player.sneaking) {
        return;
    }
    /* The game's view of the bindings, so scripted test input counts exactly
     * like a held key - and so does a real one. */
    game::Game& game = game::Game::instance();
    const bool moving = game.bindingPressed(context.env, "forward") ||
                        game.bindingPressed(context.env, "back") ||
                        game.bindingPressed(context.env, "left") ||
                        game.bindingPressed(context.env, "right");
    if (moving && !state.player.sprinting) {
        game.setSprinting(context.env, state.player.handle, true);
    }
}

}  // namespace cold::modules
