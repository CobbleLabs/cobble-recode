#pragma once

#include "modules/Module.hpp"

namespace cold::modules {

/* Walks while the inventory is open. A screen swallows new key presses, so
 * the physical state of WASD, space and shift is forwarded to the movement
 * bindings every tick instead. Only the inventory screen counts: typing in
 * chat must never walk the player. Everything released the moment the screen
 * closes or the module goes off, so no binding is left stuck down. The
 * packets this produces are shaped exactly like walking without a screen. */
class InvMove : public Module {
public:
    InvMove();

    void onDisable(Context& context) override;
    void onTick(Context& context) override;

private:
    void releaseAll(Context& context) const;

    bool active_ = false;
};

}  // namespace cold::modules
