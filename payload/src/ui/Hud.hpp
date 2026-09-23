#pragma once

#include <cstdint>
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
 * While a screen is open in-world - the pause menu, the inventory, chat - the HUD
 * becomes an editor. Every element then carries a frame with its name, a dot that
 * switches it off and a corner grip, and can be dragged to move, dragged by the
 * grip to resize, or clicked on the dot to toggle. Positions and per-element sizes
 * are remembered in cobble.cfg; nothing here is typed as a number, because the
 * point of editing in place is seeing the result while you do it.
 *
 * Elements with nothing to say draw a named ghost frame rather than inventing
 * content. The target card used to stand in with a copy of your own stats, which
 * put two cards on screen both showing your name. */
/* The elements the in-world editor can move, resize and switch off. The order is
 * the contract HudModule maps its visibility settings onto; see takeToggle(). */
enum class HudElement : std::uint8_t {
    Watermark,
    ArrayList,
    PlayerInfo,
    TargetInfo,
    Speed,
    Count,
};

class Hud {
public:
    void render(modules::Context& context);
    void handleEvent(const hooks::InputEvent& event, const modules::Context& context);

    /* Edit mode toggles an element by asking, not by writing.
     *
     * The booleans below are a copy: HudModule pushes them down from its settings
     * every frame, because those settings are what the config persists and what the
     * menu checkbox reads. Flipping the copy would be undone before the next frame
     * drew. So the editor raises a request here and HudModule flips the setting,
     * which comes straight back down the existing path.
     *
     * Returns false when nothing is pending. `element` is a HudElement ordinal. */
    bool takeToggle(int& element);

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

    /* What the pointer is doing to an element, kept apart from *which* element it is
     * doing it to. The two used to be one enum, which had no room for a second verb. */
    enum class Grab : std::uint8_t { None, Move, Resize, Toggle };

    static constexpr int kElements = static_cast<int>(HudElement::Count);
    static constexpr int index(HudElement id) { return static_cast<int>(id); }

    /* Sizes and offsets inside an element, so they follow its own resize handle. */
    float px(float units) const { return units * unit_ * drawScale_; }
    /* Stored positions, which must NOT follow it: an element that moved as you
     * resized it would crawl across the screen every time you touched the corner. */
    float pos(float units) const { return units * unit_; }

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
    void drawNotifications(modules::Context& context);

    /* ---- Editor ---- */
    /* Frame, name, state dot and corner grip for one element. Also dims the body
     * when the element is switched off, which is what tells you at a glance that the
     * thing you are looking at will not be there once you close the screen. */
    void drawEditChrome(HudElement id, const Rect& bounds, bool shown);
    void drawEditHint(const game::FrameState& state);
    /* The two controls, derived from the element's own rectangle so they stay put as
     * it is moved and resized. */
    Rect gripOf(const Rect& bounds) const;
    Rect dotOf(const Rect& bounds) const;
    /* What the pointer is over, grip and dot taking priority over the body. */
    Grab pick(HudElement& id) const;

    const char* labelOf(HudElement id) const;
    bool shownOf(HudElement id) const;
    float& sizeOf(HudElement id);
    float sizeOf(HudElement id) const;
    Rect rectOf(HudElement id) const;
    bool liveOf(HudElement id) const;

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

    /* Per-element size, multiplied on top of the master Scale setting. Dragged, never
     * typed - which is the whole point - so it lives in the layout section of the
     * config next to the positions rather than as five more sliders in the menu. */
    float sizes_[kElements] = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    /* The factor px() is currently applying. Set around each element as it draws. */
    float drawScale_ = 1.0f;

    int pendingToggle_ = -1;  // HudElement ordinal awaiting HudModule, or -1

    float mouseX_ = 0.0f;
    float mouseY_ = 0.0f;
    Grab grab_ = Grab::None;
    HudElement grabbed_ = HudElement::Count;
    Grab hotKind_ = Grab::None;
    HudElement hot_ = HudElement::Count;
    float dragOffX_ = 0.0f;
    float dragOffY_ = 0.0f;
    /* Captured when a resize starts: the element's unscaled pixel size and the factor
     * it was at, so the drag is measured against where it began rather than
     * accumulating rounding every frame. */
    float grabBaseW_ = 0.0f;
    float grabBaseH_ = 0.0f;
    float grabScale_ = 1.0f;

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
