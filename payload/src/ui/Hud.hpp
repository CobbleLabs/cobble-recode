#pragma once

#include <string>
#include <unordered_map>
#include <vector>

#include "core/Config.hpp"
#include "hooks/Input.hpp"
#include "modules/Module.hpp"
#include "render/Renderer.hpp"

namespace cold::ui {

/* Watermark bar, player/target info cards and the enabled-module list.
 *
 * Sizes are in Minecraft's GUI-scaled units, so the HUD matches the rest of
 * the interface at any resolution. Panels are flat dark slabs with a two-pixel
 * accent edge on the watermark and a stepped accent outline down the module
 * list; text is drawn once with a shadow.
 *
 * Every element drags by the mouse and remembers its position in cobble.cfg.
 * While a screen is open in-world (chat included) the HUD enters edit mode:
 * all elements draw even when they have nothing to say - the target card
 * shows your own stats as a stand-in - so the layout can be arranged live. */
class Hud {
public:
    void render(modules::Context& context);
    void handleEvent(const hooks::InputEvent& event, const modules::Context& context);

    /* While a menu owns the mouse the HUD ignores it. */
    void setMenuOpen(bool value) { menuOpen_ = value; }

    void setScale(float scale) { scale_ = clamp(scale, 0.6f, 2.5f); }
    void setShowWatermark(bool value) { watermark_ = value; }
    void setShowArrayList(bool value) { arrayList_ = value; }
    void setShowPlayerInfo(bool value) { playerInfo_ = value; }
    void setShowTargetInfo(bool value) { targetInfo_ = value; }
    void setShowTarget(bool value) { targetInfo_ = value; }
    void setShowNotifications(bool value) { notifications_ = value; }
    void setShowSpeed(bool value) { speed_ = value; }

    void loadLayout(config::Document& config);
    void saveLayout(config::Document& config) const;

    /* One-line live state for the control channel: toggles, positions,
     * element rectangles and whether the player view is valid. */
    std::string debugLine(const game::FrameState& state) const;

    /* One line on the stack in the bottom right. The client uses it to say what
     * happened to a script; cold.notify is the same call from Lua. */
    void notify(std::string text, float seconds, const Color& accent);
    /* Drops a module's array-list state, so a script module that is unloaded
     * does not leave an entry behind keyed on a pointer that is gone. */
    void forget(const modules::Module* module) { entries_.erase(module); }

private:
    struct ListEntry {
        float offset = 1.0f;  // 1 = fully off screen to the right
        float alpha = 0.0f;
        float width = 0.0f;
        float y = 0.0f;
        bool seen = false;
    };

    struct Notification {
        std::string text;
        Color accent;
        float life = 0.0f;   // seconds left
        float total = 1.0f;
        float offset = 1.0f;  // 1 = fully off screen to the right
        float y = 0.0f;
        bool placed = false;
    };

    enum class Drag {
        None,
        Watermark,
        Player,
        Target,
        List,
        Speed,
    };

    float px(float units) const { return units * unit_; }
    bool editMode(const game::FrameState& state) const;

    /* Draws the run with a shadow, returning the advance. */
    float shadowed(render::FontRole role, std::string_view value, float x, float capTop,
                    float size, const Color& colour, render::Align align = render::Align::Left);

    void drawWatermark(modules::Context& context, bool edit);
    void drawArrayList(modules::Context& context, bool edit);
    void drawPlayerInfo(modules::Context& context, bool edit, float& below);
    void drawTargetInfo(modules::Context& context, bool edit, float top);
    void drawFace(modules::Context& context, jobject handle, const Rect& bounds, float alpha,
                  float push, float tint);
    void drawEquipment(modules::Context& context, jobject handle, float x, float y, float alpha);
    void drawSpeedReadout(modules::Context& context, bool edit);
    void drawEditOutline(const Rect& bounds, bool hot);
    void drawNotifications(modules::Context& context);

    std::unordered_map<const modules::Module*, ListEntry> entries_;
    std::vector<Notification> notices_;
    float scale_ = 1.0f;
    float unit_ = 2.0f;
    bool watermark_ = true;
    bool arrayList_ = true;
    bool playerInfo_ = true;
    bool targetInfo_ = true;
    bool notifications_ = true;
    bool speed_ = true;
    bool menuOpen_ = false;

    /* Layout in GUI units, written to cobble.cfg. */
    float markX_ = 2.0f;
    float markY_ = 2.0f;
    float playerX_ = 0.0f;
    float playerY_ = 0.0f;
    bool playerPlaced_ = false;
    float targetX_ = 0.0f;
    float targetY_ = 0.0f;
    bool targetPlaced_ = false;
    float listR_ = 0.0f;  // margin from the right edge
    float listY_ = 2.0f;
    float speedX_ = 6.0f;  // bottom-left corner by default
    float speedY_ = 0.0f;  // 0 = auto, anchored to the bottom margin
    bool speedPlaced_ = false;

    /* Frame rectangles in pixels, for the next frame's hit tests. */
    Rect markRect_;
    Rect playerRect_;
    Rect targetRect_;
    bool targetDrawn_ = false;
    Rect listRect_;
    bool listDrawn_ = false;
    Rect speedRect_;
    bool speedDrawn_ = false;

    float mouseX_ = 0.0f;
    float mouseY_ = 0.0f;
    Drag drag_ = Drag::None;
    Drag hot_ = Drag::None;
    float dragOffX_ = 0.0f;
    float dragOffY_ = 0.0f;

    double sessionStart_ = -1.0;
    /* Blocks-per-second readout: distance moved horizontally since the last
     * frame over the frame time, smoothed so it does not flicker per frame. */
    double lastPosX_ = 0.0;
    double lastPosY_ = 0.0;
    double lastPosZ_ = 0.0;
    bool hasLastPos_ = false;
    float speedBps_ = 0.0f;
    float playerHealth_ = -1.0f;  // smoothed bar, < 0 until first read
    float playerActual_ = -1.0f;
    long long playerHurtAt_ = 0;
    float targetHealth_ = -1.0f;
    float targetActual_ = -1.0f;
    long long targetHurtAt_ = 0;
    float targetAlpha_ = 0.0f;
    int targetId_ = -1;
    std::string targetName_;
};

}  // namespace cold::ui
