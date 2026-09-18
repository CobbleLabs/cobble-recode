#pragma once

#include <string>
#include <tuple>
#include <vector>

#include "core/Config.hpp"
#include "core/Math.hpp"
#include "hooks/Input.hpp"
#include "modules/Module.hpp"

namespace cold::render {

class Renderer;

}  // namespace cold::render

namespace cold::ui {

/* Dropdown panels in a centred row, one per category plus configs and client
 * action panels.
 *
 * Everything is laid out in Minecraft's own GUI-scaled units and multiplied up
 * by the scale factor the game is already using, so the menu is the same size
 * on screen as the rest of the interface at any resolution. Sizes are the
 * reference client's own: 140-wide panels on a 16-high header, 12-high module
 * and setting rows, settings folded inline under the module that owns them.
 *
 * Panels drag by their headers and remember their positions in cobble.cfg;
 * a header right-click folds the panel. Layout runs every frame from the same
 * clock as the animations, and the rectangles it produces are kept for the
 * next frame's hit tests - the pointer is one frame behind the pixels, which
 * is invisible and saves laying the whole menu out twice. */
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

    /* Folds a module's settings open without a click, for scripted runs. */
    bool setExpanded(std::string_view moduleName, bool expanded);
    /* Every panel is always on screen, so any known screen name selects
     * nothing and succeeds; the control channel uses this to probe. */
    bool showScreen(std::string_view name);
    bool setPickerOpen(std::string_view moduleName, bool open);

    /* Panel positions and the configs they carry, across restarts. */
    void loadLayout(config::Document& config);
    void saveLayout(config::Document& config) const;

    /* Whether a point is inside any panel, so what is outside it can be
     * offered to whatever else wants the click. */
    bool contains(float x, float y) const;

    void setBlur(bool blur) { blur_ = blur; }
    bool blurEnabled() const { return blur_; }

private:
    struct SettingRow {
        modules::Setting* setting = nullptr;
        Rect bounds;   // whole 12-unit line, the click target
        Rect control;  // slider track, toggle box or swatch
        float hover = 0.0f;
        float knob = 0.0f;  // smoothed slider position
        bool expanded = false;
        float expand = 0.0f;  // choice list open animation
        bool draggingSlider = false;
        bool listeningForKey = false;
        bool pickerOpen = false;
        float picker = 0.0f;
        /* Remembered so a colour dragged down to black keeps the hue it was
         * dragged from; deriving it from the value would snap to red. */
        float hue = 0.0f;
    };

    struct ModuleRow {
        modules::Module* module = nullptr;
        Rect head;  // 12-unit header: the toggle target
        float hover = 0.0f;
        float enabled = 0.0f;
        float expand = 0.0f;
        bool expanded = false;
        bool binding = false;
        std::vector<SettingRow> settings;
    };

    struct Panel {
        modules::Category category = modules::Category::Combat;
        /* Stable identity for positions and config sections. Equals the label
         * for categories; special panels carry their own ("Configs",
         * "ClientActions") so two panels can share a label. */
        std::string key;
        std::string label;
        assets::IconAsset icon = assets::IconAsset::Search;
        bool configs = false;  // the configs panel, not a category
        bool actions = false;  // the client actions panel, not a category
        Rect header;           // 16-unit bar: drag handle, right-click folds
        Rect outer;            // whole panel including the module block
        float x = 0.0f;        // scaled units, top-left of outer
        float y = 12.0f;
        bool placed = false;  // positioned (centred row on first build)
        float hover = 0.0f;
        float expand = 0.0f;
        bool expanded = true;
        std::vector<std::size_t> rows;  // indices into rows_
    };

    /* One saved/ loadable config file beside the payload DLL. */
    struct ConfigFile {
        std::string name;  // without prefix or extension
        Rect bounds;
        float hover = 0.0f;
    };

    struct ActionRow {
        const char* label;
        Rect bounds;
        float hover = 0.0f;
    };

    void layout(modules::Context& context);
    float settingsHeight(ModuleRow& row);
    float panelContentHeight(Panel& panel);
    float configsHeight();
    float actionsHeight();
    void renderPanel(Panel& panel);
    void renderModuleRow(Panel& panel, ModuleRow& row, float top);
    void renderSetting(ModuleRow& row, SettingRow& setting, float top);
    void renderChoiceItems(ModuleRow& row, SettingRow& setting, float top);
    void renderConfigs(Panel& panel, float top);
    void renderActions(Panel& panel, float top);
    void renderPicker(SettingRow& row);
    void renderCursor(render::Renderer& gfx, float alpha);
    ModuleRow* findRow(std::string_view moduleName);

    void refreshConfigs();
    bool loadNamedConfig(const std::string& name);
    bool saveNamedConfig(const std::string& name);
    bool deleteNamedConfig(const std::string& name);

    bool pressAt(float x, float y, int button, modules::Context& context);
    bool pressConfigs(Panel& panel, float x, float y, int button);
    bool pressActions(Panel& panel, float x, float y, int button, modules::Context& context);
    void releaseAll();
    void stopListening();
    bool pickerOpenAnywhere() const;
    void closePickers();

    /* Panel-local units to screen pixels. */
    float px(float units) const { return units * unit_; }

    std::vector<ModuleRow> rows_;
    std::vector<Panel> panels_;
    std::vector<ActionRow> actions_;
    /* Positions surviving a rebuild (script modules arriving late). */
    std::vector<std::tuple<std::string, float, float, bool>> pending_;

    std::vector<ConfigFile> configs_;
    int selectedConfig_ = -1;
    std::string configName_;
    bool editingName_ = false;
    Rect nameField_;
    Rect loadButton_;
    Rect saveButton_;
    Rect deleteButton_;
    float loadHover_ = 0.0f;
    float saveHover_ = 0.0f;
    float deleteHover_ = 0.0f;
    float nameHover_ = 0.0f;

    bool built_ = false;
    bool open_ = false;
    bool closing_ = false;
    float slide_ = 0.0f;  // 0 = above the screen, 1 = in place
    bool blur_ = false;

    float unit_ = 2.0f;
    float panelW_ = 140.0f;
    float fullHeight_ = 0.0f;  // slide distance: the tallest panel
    float scaledW_ = 0.0f;
    float scaledH_ = 0.0f;

    float mouseX_ = 0.0f;
    float mouseY_ = 0.0f;
    bool mouseDown_ = false;
    float time_ = 0.0f;
    Panel* dragPanel_ = nullptr;
    float dragDX_ = 0.0f;
    float dragDY_ = 0.0f;

    SettingRow* activeSlider_ = nullptr;
    SettingRow* keyListener_ = nullptr;
    ModuleRow* bindRow_ = nullptr;
    SettingRow* activePicker_ = nullptr;
    int pickerAxis_ = -1;
};

}  // namespace cold::ui
