#pragma once

#include <string>
#include <vector>

#include "core/Math.hpp"
#include "game/PlayerRadar.hpp"
#include "hooks/Input.hpp"
#include "modules/Module.hpp"

namespace cold::ui {

/* The player window: a searchable list of everybody in a loaded chunk down the
 * left, and everything the client knows about one of them down the right.
 *
 * It is laid out in the same GUI-scaled units as the menu and drawn out of the
 * same batch, so it is the same size on screen at any resolution and costs the
 * overlay one more pass over a few hundred quads.
 *
 * The skin is a real model rather than a portrait: six boxes and their overlay
 * layers, projected by hand and drawn back to front as textured quads out of
 * the skin the game has already uploaded. Dragging inside its panel turns it.
 * See [[player-radar]]. */
class Radar {
public:
    void open();
    void close();
    void toggle();
    bool visible() const { return open_ || alpha_ > 0.002f; }
    bool interactive() const { return open_; }
    bool contains(float x, float y) const { return panel_.contains(x, y); }

    void handleEvent(const hooks::InputEvent& event, modules::Context& context);
    void render(modules::Context& context);

    /* Whoever the detail pane is showing, so the tracker can spend its whole
     * refresh budget on them. Empty when nothing is selected. */
    const std::string& focus() const { return selected_; }
    /* How many players may have their equipment re-read per tick right now.
     * Wide open while the window is up, a trickle while it is closed. */
    int trackBudget() const { return open_ ? 6 : 2; }

private:
    struct Row {
        std::string uuid;
        Rect bounds;
        float hover = 0.0f;
        float active = 0.0f;
    };

    /* Screen-space corners of one quad of the model, with the depth its sort
     * position is taken from. */
    struct SkinQuad {
        float xs[4];
        float ys[4];
        Rect uv;
        float depth;
        float shade;
    };

    void layout(modules::Context& context);
    void renderHeader(modules::Context& context);
    void renderList();
    void renderDetail();
    void renderStats(const game::RadarPlayer& player, const Rect& bounds);
    void renderSkin(const game::RadarPlayer& player, const Rect& bounds);
    void renderArmour(const game::RadarPlayer& player, const Rect& bounds);
    void renderHands(const game::RadarPlayer& player, const Rect& bounds);
    void renderEffects(const game::RadarPlayer& player, const Rect& bounds);
    void renderHistory(const game::RadarPlayer& player, const Rect& bounds);
    void renderEmpty(const Rect& bounds);
    /* One item chip: the sprite out of the game's own atlas, or a lucide stand
     * in for anything the atlas has no flat icon for. */
    void renderItemIcon(const std::string& id, const Rect& bounds);
    void renderStatCard(const Rect& bounds, assets::IconAsset icon, std::string_view label,
                        std::string_view value, const Color& tint);

    bool pressAt(float x, float y, int button, modules::Context& context);
    void typeCharacter(char32_t codepoint);
    void collect();

    float px(float units) const { return units * unit_; }

    std::vector<Row> rows_;
    /* Snapshot of the tracker, filtered and sorted for this frame. Pointers
     * into the tracker's own storage, which is stable for the frame. */
    std::vector<const game::RadarPlayer*> shown_;
    std::string selected_;
    std::string query_;

    bool open_ = false;
    float alpha_ = 0.0f;
    float appear_ = 0.0f;

    float panelX_ = 0.0f;
    float panelY_ = 0.0f;
    bool placed_ = false;
    Rect panel_;
    float unit_ = 2.0f;

    float scroll_ = 0.0f;
    float scrollTarget_ = 0.0f;
    float listHeight_ = 0.0f;
    float historyScroll_ = 0.0f;
    float historyTarget_ = 0.0f;
    float historyHeight_ = 0.0f;
    Rect listBounds_;
    Rect historyBounds_;
    Rect skinBounds_;
    Rect closeBounds_;
    Rect sortBounds_;
    float closeHover_ = 0.0f;
    float sortHover_ = 0.0f;

    /* 0 distance, 1 health, 2 name. */
    int sort_ = 0;

    float mouseX_ = 0.0f;
    float mouseY_ = 0.0f;
    bool dragging_ = false;
    bool turning_ = false;
    float dragOffsetX_ = 0.0f;
    float dragOffsetY_ = 0.0f;
    float turnFromX_ = 0.0f;
    float turnFromY_ = 0.0f;

    /* Where the model is facing. Yaw runs on its own until somebody grabs it. */
    float modelYaw_ = 24.0f;
    float modelPitch_ = 0.0f;
    float grabYaw_ = 0.0f;
    float grabPitch_ = 0.0f;
    bool touched_ = false;

    std::vector<SkinQuad> quads_;
};

}  // namespace cold::ui
