#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "assets/Assets.generated.hpp"
#include "core/Config.hpp"
#include "core/Math.hpp"
#include "hooks/Input.hpp"
#include "modules/Module.hpp"

namespace cold::render {

class Renderer;

}  // namespace cold::render

namespace cold::ui {

/* Tab menu: one pill-shaped tab bar and one card, both centred at the top of
 * the screen.
 *
 * The bar carries a chip that slides to the selected slot with an accent
 * underline, plus a gear at its right end for the client's own settings. The
 * card below it shows the tab's rows - icon, name, keybind chip, description
 * and a pill toggle - and a row with settings opens its own subpage in place,
 * with a back chevron where the category title was.
 *
 * Geometry is the design's own pixels multiplied by one scale factor, which is
 * derived from Minecraft's GUI scale so the menu is the same size on screen as
 * the rest of the interface. Nothing drags: the menu has one position and the
 * layout runs every frame from the same clock as the animations. The rectangles
 * it produces are kept for the next frame's hit tests, which puts the pointer
 * one frame behind the pixels and saves laying the whole menu out twice. */
class ClickGui {
public:
    void build();
    void reset();

    void open();
    void close();
    void toggle();
    bool visible() const { return open_ || slide_ > 0.002f; }
    bool interactive() const { return open_ && !closing_; }
    /* True while a bind row or the config name field is waiting for input, so
     * the client must not route keys anywhere else. */
    bool capturingKey() const {
        return keyListener_ != nullptr || bindRow_ != nullptr || editingName_;
    }

    void handleEvent(const hooks::InputEvent& event, modules::Context& context);
    void render(modules::Context& context);

    /* Opens a module's subpage without a click, for scripted runs. */
    bool setExpanded(std::string_view moduleName, bool expanded);
    /* Selects the tab with that name; the control channel uses it to probe. */
    bool showScreen(std::string_view name);
    bool setPickerOpen(std::string_view moduleName, bool open);

    /* The selected tab, across restarts. */
    void loadLayout(config::Document& config);
    void saveLayout(config::Document& config) const;

    /* Whether a point is inside the menu, so what is outside it can be offered
     * to whatever else wants the click. */
    bool contains(float x, float y) const;

    void setBlur(bool blur) { blur_ = blur; }
    bool blurEnabled() const { return blur_; }

private:
    static constexpr std::size_t kNoRow = static_cast<std::size_t>(-1);

    struct SettingRow {
        /* nullptr is the module's own keybind, which is a row in the subpage
         * as well as a chip in the list. */
        modules::Setting* setting = nullptr;
        Rect bounds;   // whole line, the click target
        Rect control;  // slider track, pill, chip or swatch
        float hover = 0.0f;
        float knob = 0.0f;   // smoothed slider position
        float enter = 1.0f;  // 0..1 arrival, staggered by position in the page
        bool expanded = false;
        float expand = 0.0f;  // choice list open animation
        bool draggingSlider = false;
        bool listeningForKey = false;
        bool pickerOpen = false;
        float picker = 0.0f;  // colour editor open animation
        /* Remembered so a colour dragged down to black keeps the hue it was
         * dragged from; deriving it from the value would snap to red. */
        float hue = 0.0f;
    };

    enum class RowKind : std::uint8_t {
        Module,   // toggle pill plus a settings subpage
        Flag,     // one boolean setting, toggle pill only
        Colour,   // one colour setting, subpage with the editor
        Configs,  // subpage with the config manager
        Action,   // runs something when clicked
    };

    struct Row {
        RowKind kind = RowKind::Module;
        modules::Module* module = nullptr;
        modules::Setting* setting = nullptr;
        int action = -1;
        std::string name;
        std::string description;
        assets::IconAsset icon = assets::IconAsset::Box;
        std::vector<SettingRow> settings;

        Rect bounds;
        Rect chevron;  // the strip that opens the subpage instead of toggling
        float hover = 0.0f;
        float on = 0.0f;
        float enter = 1.0f;  // 0..1 arrival, staggered by position in the list
        bool binding = false;

        /* Text the row draws every frame but only changes when something it
         * depends on does.
         *
         * Eliding walks the string and re-measures what is left of it once per
         * dropped code point, and every measure is a hash lookup per glyph - a
         * few thousand of them per frame across a list, to arrive at the same
         * answer as the frame before. `title` is the uppercased name the subpage
         * header wants, and the bind chip is a formatted key name. None of it
         * depends on the frame, so none of it is recomputed on one. */
        std::string title;         // upperCase(name), for the subpage header
        std::string descShown;     // description, elided to descLimit
        float descLimit = -1.0f;   // width descShown was elided to
        float descScale = 0.0f;    // scale_ descShown was measured at
        std::string bindShown;     // formatted keybind chip
        int bindKey = -2;          // key bindShown was built from (-2 = unset)
        bool bindListening = false;
    };

    struct Tab {
        std::string key;
        std::string label;
        assets::IconAsset icon = assets::IconAsset::Box;
        const char* countNoun = "MODULES";
        /* The settings tab is only the gear at the end of the bar. */
        bool iconOnly = false;
        std::vector<std::size_t> rows;  // indices into rows_
        Rect bounds;
        float hover = 0.0f;

        /* Uppercased label and its tracked width, which the header needs every
         * frame and which only move when the scale does. The tracked width is a
         * measure per glyph, so it is not free. */
        std::string title;
        float titleWidth = 0.0f;
        float titleScale = 0.0f;
        /* "8 MODULES", rebuilt only when the row count changes. */
        std::string count;
        std::size_t countFor = static_cast<std::size_t>(-1);
    };

    /* One saved/loadable config file beside the payload DLL. */
    struct ConfigFile {
        std::string name;
        Rect bounds;
        Rect remove;
        float hover = 0.0f;
        float removeHover = 0.0f;
    };

    /* Where the reveal has got to on the frame being drawn.
     *
     * The layout always runs at the menu's final geometry, so rows sit exactly
     * where they belong from the first frame; these are the rectangles the
     * *paint* is confined to while the menu is still assembling itself. Growing
     * the real card instead would reflow the list inside a box a few pixels
     * tall, and the rows would visibly settle afterwards. */
    struct Reveal {
        Rect bar;                 // capsule stretching into the tab bar
        Rect card;                // card unfolding downward
        float barRadius = 0.0f;   // capsule radius easing to the bar's own
        float glow = 0.0f;        // accent halo, on arrival only
        float label = 0.0f;       // brand label opacity
        float barContent = 1.0f;  // tab chip, labels and gear opacity
        float cardContent = 1.0f; // header and rows opacity
        bool cardVisible = true;  // false until the card starts unfolding
    };

    void advanceReveal(float deltaTime);
    void drawRevealLabel();
    /* 0..1 arrival for the row at `ordinal` of a page that has just changed. */
    float entranceAt(std::size_t ordinal) const;

    /* Whether the menu will answer the pointer. Narrower than interactive(): while
     * the menu is still assembling itself there is nothing under the cursor yet, and
     * a row that has not finished arriving must not light up or take a click.
     *
     * Deliberately not folded into interactive(), which the client also uses to
     * decide who owns the keyboard and whether the HUD should step aside. Excluding
     * the reveal there would hand module binds back to the game for the couple of
     * seconds the menu is opening. */
    bool pointable() const { return open_ && !closing_ && !revealing_; }

    void layout(modules::Context& context);
    float listHeight(const Tab& tab) const;
    float subpageHeight(Row& row);
    float settingHeight(const SettingRow& row) const;
    float configsBodyHeight() const;
    void layoutList(Tab& tab, float deltaTime);
    void layoutSubpage(Row& row, float deltaTime);
    void layoutConfigs(Row& row, float top, float deltaTime);

    void drawTabBar();
    void drawCard();
    void drawCardHeader(Tab& tab);
    void drawSubpageHeader(const Row& row);
    void drawRow(Row& row);
    void drawSetting(Row& owner, SettingRow& setting);
    void drawChoiceItems(SettingRow& setting);
    void drawColourEditor(SettingRow& setting, const Rect& area);
    void drawConfigs(Row& row);
    void drawPill(const Rect& bounds, float on);
    void drawChip(float x, float centreY, std::string_view text, const Color& background,
                  const Color& textColour);
    void renderCursor(render::Renderer& gfx);

    /* Refresh the per-frame text caches only when their inputs moved. */
    const std::string& rowBind(Row& row);
    const std::string& rowDescription(Row& row, float limit);
    /* Whether a rectangle can contribute a visible pixel to the card body, so a
     * row scrolled out of sight costs nothing but this test. */
    bool visibleInBody(const Rect& bounds) const;
    /* Whether the body clip rectangle can actually cut anything this frame. The
     * scissor is free but the flush that installing it forces is not. */
    bool bodyNeedsClip() const;

    Row* findRow(std::string_view moduleName);
    void selectTab(std::size_t index);
    void openSubpage(std::size_t rowIndex);
    void closeSubpage();
    bool subpageOpen() const { return subpageRow_ != kNoRow; }
    Row* subpage();

    void refreshConfigs();
    bool loadNamedConfig(const std::string& name);
    bool saveNamedConfig(const std::string& name);
    bool deleteNamedConfig(const std::string& name);
    void runAction(int action, modules::Context& context);

    bool pressAt(float x, float y, int button, modules::Context& context);
    bool pressRow(std::size_t index, float x, float y, int button, modules::Context& context);
    bool pressSetting(SettingRow& setting, float x, float y, int button);
    bool pressConfigs(Row& row, float x, float y, int button);
    void releaseAll();
    void stopListening();
    void closePickers();
    bool pickerOpenAnywhere() const;

    /* Design pixels to screen pixels. */
    float s(float value) const { return value * scale_; }
    Color fade(const Color& colour) const { return colour.withAlpha(alpha_); }
    Color fade(const Color& colour, float extra) const {
        return colour.withAlpha(alpha_ * extra);
    }
    Color accent(float alpha = 1.0f) const;

    std::vector<Row> rows_;
    std::vector<Tab> tabs_;
    std::vector<ConfigFile> configs_;

    /* tab_ is what the user picked; shownTab_ is what the card is currently
     * displaying. They differ only for the length of a handover: the chip and the
     * bar follow the click immediately (anything else feels unresponsive) while
     * the list below finishes fading the old category out. */
    std::size_t tab_ = 0;
    std::size_t shownTab_ = 0;
    float pageFade_ = 1.0f;      // 1 settled, 0 the instant the swap happens
    float pageEnter_ = 1.0f;     // 0..1 cascade clock for the page that arrived
    bool pageLeaving_ = false;   // the outgoing page is on its way out
    float cardHeight_ = 0.0f;    // smoothed, so a taller category grows into place

    /* The brand label, uppercased and measured with its tracking, plus the capsule
     * width that follows from it. Both animations need all three every frame they
     * run, and the measure is a hash lookup per glyph, so they are resolved once
     * per scale instead. */
    void refreshBrand();
    float capsuleWidth(float barWidth);
    std::string brandText_;
    float brandWidth_ = 0.0f;
    float brandScale_ = 0.0f;

    /* Once per injection, not once per open. */
    bool revealSpent_ = false;
    bool revealing_ = false;
    float revealClock_ = 0.0f;
    /* Every close, on its own clock: the reveal in reverse and tightened up. Runs
     * instead of the plain slide_ fade, which is left for the one case the exit
     * cannot start from - a close that lands mid-reveal. */
    bool exiting_ = false;
    float exitClock_ = 0.0f;
    Reveal reveal_;

    std::size_t subpageRow_ = kNoRow;
    float subpageAnim_ = 0.0f;
    float indicator_ = 0.0f;       // interpolated slot of the tab chip
    float indicatorAlpha_ = 1.0f;  // fades out when the gear is selected
    float gearHover_ = 0.0f;
    float backHover_ = 0.0f;
    Rect gearSlot_;
    std::string pendingTab_;  // selection restored before the tabs exist

    int selectedConfig_ = -1;
    std::string configName_;
    std::string configStatus_;
    bool editingName_ = false;
    Rect nameField_;
    Rect saveButton_;
    float nameHover_ = 0.0f;
    float saveHover_ = 0.0f;

    bool built_ = false;
    bool open_ = false;
    bool closing_ = false;
    float slide_ = 0.0f;  // 0 = gone, 1 = in place
    float alpha_ = 0.0f;
    bool blur_ = false;

    float scale_ = 1.0f;
    float unit_ = 2.0f;
    float screenW_ = 0.0f;
    float screenH_ = 0.0f;

    Rect bar_;
    Rect card_;
    Rect body_;
    float scroll_ = 0.0f;
    float content_ = 0.0f;

    float mouseX_ = 0.0f;
    float mouseY_ = 0.0f;
    bool mouseDown_ = false;
    float time_ = 0.0f;

    SettingRow* activeSlider_ = nullptr;
    SettingRow* keyListener_ = nullptr;
    Row* bindRow_ = nullptr;
    SettingRow* activePicker_ = nullptr;
    int pickerAxis_ = -1;
};

}  // namespace cold::ui
