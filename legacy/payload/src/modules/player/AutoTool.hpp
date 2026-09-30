#pragma once

#include <string>

#include "core/Time.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's Auto Tool.
 *
 * Swaps the hotbar to whatever breaks the block under the crosshair fastest,
 * and swaps back when the crosshair leaves it. The original reads Minecraft's
 * own `getStrVsBlock`; there is no such call to make from here, so the choice
 * is reconstructed from the two things that actually decide it - which family
 * of tool the block answers to, and the material's mining speed - which is the
 * same ordering vanilla arrives at for every block that has a right answer.
 *
 * The swap is a real slot change through `PlayerInventory.setSelectedSlot`, so
 * the server is told about it exactly as it would be for a scroll. */
class AutoTool : public Module {
public:
    AutoTool();

    void onEnable(Context& context) override;
    void onDisable(Context& context) override;
    void onTick(Context& context) override;

private:
    /* Hotbar slot that breaks `block` fastest, or -1 when nothing in the hotbar
     * is better than a fist. */
    int bestSlot(Context& context, const std::string& block) const;
    void restore(Context& context);
    void equip(Context& context, int slot);

    Setting* activation_;
    Setting* hoverDelay_;
    Setting* onlyCrouching_;
    Setting* requireAttack_;
    Setting* switchBack_;
    Setting* weapons_;

    Timer attackHeldSince_;
    Timer hoverSince_;
    bool wasAttacking_ = false;
    bool hovering_ = false;
    int previousSlot_ = -1;
};

}  // namespace cold::modules
