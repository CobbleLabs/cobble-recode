#pragma once

#include "modules/Module.hpp"

namespace cold::modules {

/* Holds sprint while moving, the way holding the key does. Skidded from
 * Vape's Sprint: no packet is ever shaped by hand, the game's own sprint key
 * state is what the server reads, so there is nothing for Grim to disbelieve.
 * Sprinting still costs hunger and still stops for sneak and screens, exactly
 * as if the key were taped down. */
class Sprint : public Module {
public:
    Sprint();

    void onDisable(Context& context) override;
    void onTick(Context& context) override;
};

}  // namespace cold::modules
