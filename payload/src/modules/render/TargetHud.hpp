#pragma once

#include <string>

#include "modules/Module.hpp"

namespace cold::modules {

/* Raven-b's TargetHUD, Modern mode.
 *
 * The whole idea of the original is that the bar tells you two things at once:
 * where the target's health is now, and where it was a moment ago. A hit lands
 * as a bright bar snapping back and a dimmer ghost draining after it, so the
 * damage a swing did is legible without reading a number. Healing runs the
 * other way and the ghost leads instead of trailing.
 *
 * It follows whatever combat module currently has a target rather than one in
 * particular, and fades rather than vanishing when that target is dropped -
 * a panel that blinks out between two swings is worse than no panel. */
class TargetHud : public Module {
public:
    TargetHud();

    void onEnable(Context& context) override;
    void onRender(Context& context) override;

private:
    void drawPanel(modules::Context& context, const Rect& bounds, float alpha);
    void drawMarker(Context& context);

    Setting* offsetX_;
    Setting* offsetY_;
    Setting* scale_;
    Setting* backdrop_;
    Setting* healthColour_;
    Setting* showDistance_;
    Setting* showOutcome_;
    Setting* marker_;

    std::string name_;
    float health_ = 0.0f;
    float maxHealth_ = 20.0f;
    float ghost_ = 0.0f;      // 0..1, the lagging copy of the bar
    float fraction_ = 0.0f;   // 0..1, where the bar is now
    float distance_ = 0.0f;
    float ownFraction_ = 1.0f;
    float alpha_ = 0.0f;
    int targetId_ = -1;
    bool targetIsPlayer_ = false;
};

}  // namespace cold::modules
