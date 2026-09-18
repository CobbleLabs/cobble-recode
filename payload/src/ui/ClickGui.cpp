#include "ui/ClickGui.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include <windows.h>

#include <shellapi.h>

#include "core/Log.hpp"
#include "modules/ModuleManager.hpp"
#include "modules/client/ClickGuiModule.hpp"
#include "render/Renderer.hpp"
#include "script/ScriptManager.hpp"
#include "ui/Client.hpp"
#include "ui/Hud.hpp"
#include "ui/Keys.hpp"
#include "ui/Theme.hpp"

namespace cold::ui {
namespace {

constexpr std::string_view kScope = "gui";

/* Minecraft GUI-scaled units throughout; the reference works in scaled pixels
 * and px() multiplies by the game's own scale factor. */
constexpr float kBarY = 12.0f;
constexpr float kPanelWidth = 140.0f;
constexpr float kHeaderHeight = 16.0f;
constexpr float kRowHeight = 12.0f;
constexpr float kPadding = 4.0f;
constexpr float kCatGap = 12.0f;
constexpr float kHoverShift = 3.0f;
constexpr float kToggleSize = 6.0f;
constexpr float kSliderHeight = 1.5f;

constexpr float kTextSize = 9.0f;
constexpr float kArrowSize = 6.0f;

constexpr float kSlideSpeed = 14.0f;
constexpr float kStagger = 0.12f;
constexpr float kHoverSpeed = 12.0f;
constexpr float kEnableSpeed = 10.0f;
constexpr float kExpandSpeed = 14.0f;
constexpr float kChoiceExpandSpeed = 12.0f;

constexpr float kPickerWidth = 105.0f;
constexpr float kPickerHeight = 100.0f;
constexpr float kPickerPad = 5.0f;
constexpr float kPickerField = 62.0f;
constexpr float kPickerStrip = 6.0f;
/* Gap between a panel and its floating popup (colour picker). The old 8
 * read as two disconnected widgets; the popup is part of the panel's
 * setting, so it hugs the edge it opens from. */
constexpr float kPickerGap = 2.0f;

constexpr std::size_t kMaxConfigName = 24;
constexpr int kKeyEnter = 257;

const Color kBase = Color::rgb(0x121212);
const Color kHoverBg = Color::rgb(0x1C1C1C);
const Color kActiveBg = Color::rgb(0x1D1D1D);
const Color kModuleBg = Color::rgb(0x282828);
const Color kModuleHover = Color::rgb(0x323232);
const Color kSettingBg = Color::rgb(0x1D1D1D);
const Color kSettingDeep = Color::rgb(0x141414);
const Color kTrackBg = Color::rgb(0x141414);
const Color kOutlineDark = Color::rgb(0x363636);
const Color kTextDim = Color::rgb(0xAAAAAA);
const Color kTextMid = Color::rgb(0xDDDDDD);
const Color kTextOn = Color::rgb(0xE8E8E8);
const Color kItemIdle = Color::rgb(0x666666);
const Color kBindIdle = Color::rgb(0x949494);

float approach(float current, float target, float rate, float deltaTime) {
    const float step = 1.0f - std::exp(-rate * deltaTime);
    const float next = current + (target - current) * step;
    return std::fabs(target - next) < 0.0015f ? target : next;
}

Color darker(const Color& colour, int times) {
    Color out = colour;
    for (int i = 0; i < times; ++i) {
        out = Color{out.r * 0.7f, out.g * 0.7f, out.b * 0.7f, out.a};
    }
    return out;
}

std::string formatNumber(const modules::Setting& setting) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%.*f", setting.decimals(),
                  static_cast<double>(setting.number()));
    std::string text = buffer;
    if (text.find('.') != std::string::npos) {
        while (!text.empty() && text.back() == '0') text.pop_back();
        if (!text.empty() && text.back() == '.') text.pop_back();
    }
    return text;
}

std::string lowercase(std::string_view value) {
    std::string out;
    out.reserve(value.size());
    for (char character : value) {
        if (character == ' ') continue;
        out.push_back(static_cast<char>(character >= 'A' && character <= 'Z' ? character + 32
                                                                             : character));
    }
    return out;
}

float hueOf(const Color& colour) {
    const float maximum = std::max({colour.r, colour.g, colour.b});
    const float minimum = std::min({colour.r, colour.g, colour.b});
    const float delta = maximum - minimum;
    if (delta <= 0.0001f) {
        return 0.0f;
    }
    float hue;
    if (maximum == colour.r) {
        hue = std::fmod((colour.g - colour.b) / delta, 6.0f);
    } else if (maximum == colour.g) {
        hue = (colour.b - colour.r) / delta + 2.0f;
    } else {
        hue = (colour.r - colour.g) / delta + 4.0f;
    }
    hue /= 6.0f;
    return hue < 0.0f ? hue + 1.0f : hue;
}

bool validConfigChar(char character) {
    return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z') ||
           (character >= '0' && character <= '9') || character == '_' || character == '-';
}

std::wstring widenAscii(const std::string& text) {
    return std::wstring(text.begin(), text.end());
}

}  // namespace

void ClickGui::build() {
    if (built_) {
        return;
    }
    rows_.clear();
    panels_.clear();

    for (int index = 0; index < static_cast<int>(modules::Category::Count); ++index) {
        const auto category = static_cast<modules::Category>(index);
        std::vector<modules::Module*> inCategory = modules::modules().byCategory(category);
        if (inCategory.empty()) {
            continue;
        }
        Panel panel;
        panel.category = category;
        panel.key = modules::categoryName(category);
        panel.label = modules::categoryName(category);
        panel.icon = modules::categoryIcon(category);
        for (modules::Module* module : inCategory) {
            ModuleRow row;
            row.module = module;
            for (const std::unique_ptr<modules::Setting>& setting : module->settings()) {
                SettingRow settingRow;
                settingRow.setting = setting.get();
                settingRow.knob = setting->normalised();
                settingRow.hue = hueOf(setting->colour());
                row.settings.push_back(settingRow);
            }
            panel.rows.push_back(rows_.size());
            rows_.push_back(std::move(row));
        }
        panels_.push_back(std::move(panel));
    }

    Panel configs;
    configs.configs = true;
    configs.key = "Configs";
    configs.label = "Configs";
    configs.icon = assets::IconAsset::Settings2;
    panels_.push_back(std::move(configs));

    Panel actions;
    actions.actions = true;
    actions.key = "ClientActions";
    actions.label = "Client";
    actions.icon = assets::IconAsset::Wrench;
    panels_.push_back(std::move(actions));

    actions_.clear();
    actions_.push_back(ActionRow{"Reload Scripts"});
    actions_.push_back(ActionRow{"Reload Configs"});
    actions_.push_back(ActionRow{"Open Folder"});
    actions_.push_back(ActionRow{"Unload"});

    /* Centred row on first sight; afterwards panels stay where they were put,
     * across rebuilds via pending_ and across restarts via loadLayout. */
    float cursorX = 0.0f;
    {
        float totalWidth = 0.0f;
        for (std::size_t i = 0; i < panels_.size(); ++i) {
            totalWidth += kPanelWidth;
            if (i + 1 < panels_.size()) totalWidth += kCatGap;
        }
        const float scaledWidth = scaledW_ > 0.0f ? scaledW_ : 640.0f;
        cursorX = (scaledWidth - std::min(totalWidth, scaledWidth - 16.0f)) * 0.5f;
    }
    for (Panel& panel : panels_) {
        bool restored = false;
        for (const auto& [key, x, y, expanded] : pending_) {
            if (key == panel.key) {
                panel.x = x;
                panel.y = y;
                panel.expanded = expanded;
                panel.placed = true;
                restored = true;
                break;
            }
        }
        if (!restored) {
            panel.x = cursorX;
            panel.y = kBarY;
            panel.placed = true;
            cursorX += kPanelWidth + kCatGap;
        }
    }
    pending_.clear();

    refreshConfigs();
    built_ = true;
    COLD_INFO(kScope, "%zu modules across %zu panels", rows_.size(), panels_.size());
}

void ClickGui::reset() {
    /* A rebuild must not lose where the panels were: script modules arriving
     * late would otherwise teleport the whole menu back to the centre. */
    pending_.clear();
    for (const Panel& panel : panels_) {
        pending_.emplace_back(panel.key, panel.x, panel.y, panel.expanded);
    }
    releaseAll();
    stopListening();
    if (bindRow_ != nullptr) {
        bindRow_->binding = false;
        bindRow_ = nullptr;
    }
    dragPanel_ = nullptr;
    editingName_ = false;
    built_ = false;
    rows_.clear();
    panels_.clear();
}

void ClickGui::open() {
    if (open_ && !closing_) {
        return;
    }
    build();
    open_ = true;
    closing_ = false;
    slide_ = 0.0f;
    refreshConfigs();
    hooks::input().setCapturing(true);
}

void ClickGui::close() {
    if (!open_ || closing_) {
        return;
    }
    closing_ = true;
    releaseAll();
    stopListening();
    if (bindRow_ != nullptr) {
        bindRow_->binding = false;
        bindRow_ = nullptr;
    }
    dragPanel_ = nullptr;
    editingName_ = false;
    closePickers();
}

void ClickGui::toggle() {
    if (open_ && !closing_) {
        close();
    } else {
        open();
    }
}

void ClickGui::releaseAll() {
    mouseDown_ = false;
    dragPanel_ = nullptr;
    activeSlider_ = nullptr;
    activePicker_ = nullptr;
    pickerAxis_ = -1;
    for (ModuleRow& row : rows_) {
        for (SettingRow& setting : row.settings) {
            setting.draggingSlider = false;
        }
    }
}

ClickGui::ModuleRow* ClickGui::findRow(std::string_view moduleName) {
    for (ModuleRow& row : rows_) {
        if (row.module->name() == moduleName) {
            return &row;
        }
    }
    return nullptr;
}

bool ClickGui::setExpanded(std::string_view moduleName, bool expanded) {
    build();
    ModuleRow* row = findRow(moduleName);
    if (row == nullptr) {
        return false;
    }
    row->expanded = expanded;
    return true;
}

bool ClickGui::showScreen(std::string_view name) {
    build();
    const std::string needle = lowercase(name);
    if (needle == "search" || needle == "scripts") {
        return true;
    }
    for (const Panel& panel : panels_) {
        if (lowercase(panel.label) == needle) {
            return true;
        }
    }
    return false;
}

bool ClickGui::setPickerOpen(std::string_view moduleName, bool open) {
    build();
    ModuleRow* row = findRow(moduleName);
    if (row == nullptr) {
        return false;
    }
    closePickers();
    for (SettingRow& setting : row->settings) {
        if (setting.setting->type() != modules::Setting::Type::Colour) {
            continue;
        }
        if (open) {
            row->expanded = true;
        }
        setting.pickerOpen = open;
        setting.hue = hueOf(setting.setting->colour());
        return true;
    }
    return false;
}

void ClickGui::loadLayout(config::Document& config) {
    pending_.clear();
    for (int index = 0; index < static_cast<int>(modules::Category::Count); ++index) {
        const std::string key =
            modules::categoryName(static_cast<modules::Category>(index));
        const std::string section = "gui.panel." + key;
        if (!config.hasSection(section)) {
            continue;
        }
        pending_.emplace_back(key, config.getNumber(section, "x", 0.0f),
                              config.getNumber(section, "y", kBarY),
                              config.getBool(section, "expanded", true));
    }
    for (const char* key : {"Configs", "ClientActions"}) {
        const std::string section = std::string("gui.panel.") + key;
        if (!config.hasSection(section)) {
            continue;
        }
        pending_.emplace_back(key, config.getNumber(section, "x", 0.0f),
                              config.getNumber(section, "y", kBarY),
                              config.getBool(section, "expanded", true));
    }
    /* Already open (a config loaded mid-session): apply straight away. */
    for (Panel& panel : panels_) {
        for (const auto& [key, x, y, expanded] : pending_) {
            if (key == panel.key) {
                panel.x = x;
                panel.y = y;
                panel.expanded = expanded;
                break;
            }
        }
    }
    if (!panels_.empty()) {
        pending_.clear();
    }
}

void ClickGui::saveLayout(config::Document& config) const {
    for (const Panel& panel : panels_) {
        const std::string section = "gui.panel." + panel.key;
        config.setNumber(section, "x", panel.x, 1);
        config.setNumber(section, "y", panel.y, 1);
        config.setBool(section, "expanded", panel.expanded);
    }
}

bool ClickGui::contains(float x, float y) const {
    for (const Panel& panel : panels_) {
        if (panel.outer.contains(x, y)) {
            return true;
        }
    }
    return false;
}

void ClickGui::refreshConfigs() {
    configs_.clear();
    selectedConfig_ = -1;
    const std::wstring pattern = client().directory() + L"\\cobble-*.cfg";
    WIN32_FIND_DATAW data{};
    HANDLE handle = FindFirstFileW(pattern.c_str(), &data);
    if (handle != INVALID_HANDLE_VALUE) {
        do {
            std::wstring file = data.cFileName;
            /* cobble-<name>.cfg: strip the prefix and the extension. */
            if (file.size() > 11 && file.compare(file.size() - 4, 4, L".cfg") == 0) {
                std::wstring name = file.substr(7, file.size() - 11);
                std::string narrow;
                narrow.reserve(name.size());
                bool clean = !name.empty();
                for (wchar_t c : name) {
                    clean = clean && c < 128 && validConfigChar(static_cast<char>(c));
                    narrow.push_back(static_cast<char>(c));
                }
                if (clean) {
                    ConfigFile entry;
                    entry.name = narrow;
                    configs_.push_back(std::move(entry));
                }
            }
        } while (FindNextFileW(handle, &data) != FALSE);
        FindClose(handle);
    }
    std::sort(configs_.begin(), configs_.end(),
              [](const ConfigFile& a, const ConfigFile& b) { return a.name < b.name; });
}

bool ClickGui::loadNamedConfig(const std::string& name) {
    if (name.empty()) {
        return false;
    }
    const std::wstring path =
        client().directory() + L"\\cobble-" + widenAscii(name) + L".cfg";
    if (GetFileAttributesW(path.c_str()) == INVALID_FILE_ATTRIBUTES) {
        return false;
    }
    modules::modules().loadConfig(path);
    loadLayout(modules::modules().config());
    client().hud().loadLayout(modules::modules().config());
    COLD_INFO(kScope, "config %s loaded", name.c_str());
    return true;
}

bool ClickGui::saveNamedConfig(const std::string& name) {
    if (name.empty()) {
        return false;
    }
    const std::wstring path =
        client().directory() + L"\\cobble-" + widenAscii(name) + L".cfg";
    modules::modules().saveConfig(path);
    /* saveConfig only carries module sections, so the layout goes in after. */
    config::Document document;
    document.load(path);
    saveLayout(document);
    client().hud().saveLayout(document);
    const bool ok = document.save(path);
    if (ok) {
        COLD_INFO(kScope, "config %s saved", name.c_str());
        refreshConfigs();
        for (std::size_t i = 0; i < configs_.size(); ++i) {
            if (configs_[i].name == name) {
                selectedConfig_ = static_cast<int>(i);
                break;
            }
        }
    }
    return ok;
}

bool ClickGui::deleteNamedConfig(const std::string& name) {
    if (name.empty()) {
        return false;
    }
    const std::wstring path =
        client().directory() + L"\\cobble-" + widenAscii(name) + L".cfg";
    const bool ok = DeleteFileW(path.c_str()) != FALSE;
    if (ok) {
        COLD_INFO(kScope, "config %s deleted", name.c_str());
        refreshConfigs();
    }
    return ok;
}

void ClickGui::handleEvent(const hooks::InputEvent& event, modules::Context& context) {
    if (!open_) {
        return;
    }
    const game::FrameState& state = *context.state;
    const float scaleX = state.logicalWidth > 0 ? static_cast<float>(state.windowWidth) /
                                                      static_cast<float>(state.logicalWidth)
                                                : 1.0f;
    const float scaleY = state.logicalHeight > 0 ? static_cast<float>(state.windowHeight) /
                                                       static_cast<float>(state.logicalHeight)
                                                 : 1.0f;

    switch (event.kind) {
        case hooks::InputEvent::Kind::MouseMove:
            mouseX_ = static_cast<float>(event.x) * scaleX;
            mouseY_ = static_cast<float>(event.y) * scaleY;
            if (dragPanel_ != nullptr) {
                dragPanel_->x = clamp(mouseX_ / unit_ - dragDX_, -panelW_ + 40.0f,
                                      std::max(scaledW_ - 40.0f, -panelW_ + 40.0f));
                dragPanel_->y = clamp(mouseY_ / unit_ - dragDY_, 0.0f,
                                      std::max(scaledH_ - 20.0f, 0.0f));
            }
            break;

        case hooks::InputEvent::Kind::MouseButton:
            mouseX_ = static_cast<float>(event.x) * scaleX;
            mouseY_ = static_cast<float>(event.y) * scaleY;
            if (event.action == hooks::kPress) {
                if (closing_) {
                    break;
                }
                mouseDown_ = event.code == hooks::kMouseLeft;
                pressAt(mouseX_, mouseY_, event.code, context);
            } else {
                mouseDown_ = false;
                dragPanel_ = nullptr;
                if (activeSlider_ != nullptr) {
                    activeSlider_->draggingSlider = false;
                    activeSlider_ = nullptr;
                }
                activePicker_ = nullptr;
                pickerAxis_ = -1;
            }
            break;

        case hooks::InputEvent::Kind::Scroll:
            break;

        case hooks::InputEvent::Kind::Key: {
            if (event.action == hooks::kRelease) {
                break;
            }
            if (editingName_) {
                if (event.code == hooks::kKeyEscape) {
                    editingName_ = false;
                } else if (event.code == hooks::kKeyBackspace) {
                    if (!configName_.empty()) configName_.pop_back();
                } else if (event.code == kKeyEnter && !configName_.empty()) {
                    saveNamedConfig(configName_);
                    editingName_ = false;
                }
                break;
            }
            if (keyListener_ != nullptr) {
                keyListener_->setting->setKey(event.code == hooks::kKeyEscape ? 0
                                                                              : event.code);
                keyListener_->listeningForKey = false;
                keyListener_ = nullptr;
                break;
            }
            if (bindRow_ != nullptr) {
                if (event.code == hooks::kKeyEscape || event.code == hooks::kKeyBackspace) {
                    bindRow_->module->setKey(0);
                } else {
                    bindRow_->module->setKey(event.code);
                }
                bindRow_->binding = false;
                bindRow_ = nullptr;
                break;
            }
            if (event.code == hooks::kKeyEscape) {
                if (pickerOpenAnywhere()) {
                    closePickers();
                } else {
                    close();
                }
            }
            break;
        }

        case hooks::InputEvent::Kind::Char:
            if (editingName_) {
                const char c = static_cast<char>(event.code);
                if (validConfigChar(c) && configName_.size() < kMaxConfigName) {
                    configName_.push_back(c);
                }
            }
            break;
    }
}

void ClickGui::stopListening() {
    if (keyListener_ != nullptr) {
        keyListener_->listeningForKey = false;
        keyListener_ = nullptr;
    }
}

bool ClickGui::pickerOpenAnywhere() const {
    for (const ModuleRow& row : rows_) {
        for (const SettingRow& setting : row.settings) {
            if (setting.pickerOpen) return true;
        }
    }
    return false;
}

void ClickGui::closePickers() {
    for (ModuleRow& row : rows_) {
        for (SettingRow& setting : row.settings) {
            setting.pickerOpen = false;
        }
    }
    activePicker_ = nullptr;
    pickerAxis_ = -1;
}

float ClickGui::settingsHeight(ModuleRow& row) {
    float height = 0.0f;
    for (const SettingRow& setting : row.settings) {
        if (!setting.setting->visible()) {
            continue;
        }
        height += kRowHeight;
        if (setting.setting->type() == modules::Setting::Type::Choice) {
            height += static_cast<float>(setting.setting->choices().size()) * kRowHeight *
                      setting.expand;
        }
    }
    return height * row.expand;
}

float ClickGui::panelContentHeight(Panel& panel) {
    if (panel.configs) {
        return configsHeight() * panel.expand;
    }
    if (panel.actions) {
        return actionsHeight() * panel.expand;
    }
    float height = 0.0f;
    for (std::size_t index : panel.rows) {
        ModuleRow& row = rows_[index];
        height += kRowHeight + settingsHeight(row);
    }
    return height * panel.expand;
}

float ClickGui::configsHeight() {
    return static_cast<float>(configs_.size() + 4) * kRowHeight;
}

float ClickGui::actionsHeight() {
    return static_cast<float>(actions_.size()) * kRowHeight;
}

bool ClickGui::pressAt(float x, float y, int button, modules::Context& context) {
    stopListening();

    for (ModuleRow& row : rows_) {
        for (SettingRow& setting : row.settings) {
            if (!setting.pickerOpen || setting.picker < 0.5f || setting.control.width <= 0.0f) {
                continue;
            }
            const Rect popup{setting.control.right() + px(8.0f), setting.control.y - px(3.0f),
                             px(kPickerWidth), px(kPickerHeight)};
            if (!popup.contains(x, y)) {
                continue;
            }
            const Rect field{popup.x + px(kPickerPad), popup.y + px(kPickerPad),
                             popup.width - px(kPickerPad * 2.0f), px(kPickerField)};
            activePicker_ = &setting;
            pickerAxis_ = y < field.bottom() + px(kPickerPad * 0.5f) ? 0 : 1;
            return true;
        }
    }

    if (!contains(x, y)) {
        editingName_ = false;
        return false;
    }

    for (Panel& panel : panels_) {
        if (panel.header.contains(x, y)) {
            if (button == hooks::kMouseRight) {
                panel.expanded = !panel.expanded;
            } else if (button == hooks::kMouseLeft) {
                /* A header press drags; expanding is the right button's job. */
                dragPanel_ = &panel;
                dragDX_ = x / unit_ - panel.x;
                dragDY_ = y / unit_ - panel.y;
            }
            return true;
        }
    }

    for (Panel& panel : panels_) {
        if (panel.configs) {
            if (panel.expand > 0.05f && pressConfigs(panel, x, y, button)) {
                return true;
            }
            continue;
        }
        if (panel.actions) {
            if (panel.expand > 0.05f && pressActions(panel, x, y, button, context)) {
                return true;
            }
            continue;
        }
        if (!panel.expanded || panel.expand < 0.05f) {
            continue;
        }
        for (std::size_t index : panel.rows) {
            ModuleRow& row = rows_[index];
            if (row.head.width > 0.0f && row.head.contains(x, y)) {
                if (button == hooks::kMouseLeft) {
                    row.module->toggle(&context);
                } else if (button == hooks::kMouseRight) {
                    row.expanded = !row.expanded;
                } else if (button == hooks::kMouseMiddle) {
                    if (bindRow_ != nullptr) {
                        bindRow_->binding = false;
                    }
                    bindRow_ = &row;
                    row.binding = true;
                }
                return true;
            }
            if (!row.expanded || row.expand < 0.05f) {
                continue;
            }
            for (SettingRow& setting : row.settings) {
                if (!setting.setting->visible() || setting.bounds.width <= 0.0f ||
                    !setting.bounds.contains(x, y)) {
                    continue;
                }
                switch (setting.setting->type()) {
                    case modules::Setting::Type::Boolean:
                        if (button == hooks::kMouseLeft) {
                            setting.setting->setBoolean(!setting.setting->booleanValue());
                        }
                        break;
                    case modules::Setting::Type::Number:
                        if (button == hooks::kMouseLeft) {
                            setting.draggingSlider = true;
                            activeSlider_ = &setting;
                            if (setting.control.width > 0.0f) {
                                setting.setting->setNormalised(
                                    (x - setting.control.x) / setting.control.width);
                            }
                        }
                        break;
                    case modules::Setting::Type::Choice: {
                        const float localY = y - setting.bounds.y;
                        if (localY < px(kRowHeight)) {
                            if (button == hooks::kMouseLeft ||
                                button == hooks::kMouseRight) {
                                setting.expanded = !setting.expanded;
                            }
                        } else if (setting.expand > 0.3f) {
                            const int item = static_cast<int>((localY - px(kRowHeight)) /
                                                              px(kRowHeight));
                            const auto& choices = setting.setting->choices();
                            if (item >= 0 &&
                                item < static_cast<int>(choices.size())) {
                                setting.setting->setChoice(item);
                            }
                        }
                        break;
                    }
                    case modules::Setting::Type::Key:
                        if (button == hooks::kMouseLeft) {
                            setting.listeningForKey = true;
                            keyListener_ = &setting;
                        }
                        break;
                    case modules::Setting::Type::Colour: {
                        if (button == hooks::kMouseLeft || button == hooks::kMouseRight) {
                            const bool wasOpen = setting.pickerOpen;
                            closePickers();
                            setting.pickerOpen = !wasOpen;
                            if (setting.pickerOpen) {
                                setting.hue = hueOf(setting.setting->colour());
                            }
                        }
                        break;
                    }
                }
                return true;
            }
        }
    }

    return true;
}

bool ClickGui::pressConfigs(Panel& panel, float x, float y, int button) {
    if (button != hooks::kMouseLeft) {
        return false;
    }
    for (std::size_t i = 0; i < configs_.size(); ++i) {
        if (configs_[i].bounds.width > 0.0f && configs_[i].bounds.contains(x, y)) {
            selectedConfig_ = static_cast<int>(i);
            configName_ = configs_[i].name;
            editingName_ = false;
            return true;
        }
    }
    if (nameField_.width > 0.0f && nameField_.contains(x, y)) {
        editingName_ = true;
        return true;
    }
    if (loadButton_.width > 0.0f && loadButton_.contains(x, y)) {
        editingName_ = false;
        if (selectedConfig_ >= 0 &&
            selectedConfig_ < static_cast<int>(configs_.size())) {
            loadNamedConfig(configs_[static_cast<std::size_t>(selectedConfig_)].name);
        }
        return true;
    }
    if (saveButton_.width > 0.0f && saveButton_.contains(x, y)) {
        editingName_ = false;
        if (!configName_.empty()) {
            saveNamedConfig(configName_);
        }
        return true;
    }
    if (deleteButton_.width > 0.0f && deleteButton_.contains(x, y)) {
        editingName_ = false;
        if (selectedConfig_ >= 0 &&
            selectedConfig_ < static_cast<int>(configs_.size())) {
            deleteNamedConfig(configs_[static_cast<std::size_t>(selectedConfig_)].name);
        }
        return true;
    }
    (void)panel;
    return false;
}

bool ClickGui::pressActions(Panel& panel, float x, float y, int button,
                             modules::Context& context) {
    if (button != hooks::kMouseLeft) {
        return false;
    }
    for (std::size_t i = 0; i < actions_.size(); ++i) {
        if (actions_[i].bounds.width <= 0.0f || !actions_[i].bounds.contains(x, y)) {
            continue;
        }
        switch (i) {
            case 0:
                script::manager().requestReload({});
                COLD_INFO(kScope, "scripts reloading");
                break;
            case 1:
                refreshConfigs();
                break;
            case 2:
                ShellExecuteW(nullptr, L"open", client().directory().c_str(), nullptr,
                              nullptr, SW_SHOWNORMAL);
                break;
            case 3:
                if (auto* self = modules::modules().get<modules::ClickGuiModule>();
                    self != nullptr) {
                    self->setEnabled(false, &context);
                }
                client().requestShutdown();
                break;
            default:
                break;
        }
        (void)panel;
        return true;
    }
    return false;
}

void ClickGui::layout(modules::Context& context) {
    const game::FrameState& state = *context.state;
    const float deltaTime = context.deltaTime;
    const float factor = state.scaledWidth > 0 ? static_cast<float>(state.windowWidth) /
                                                     static_cast<float>(state.scaledWidth)
                                               : 2.0f;
    unit_ = std::max(factor, 0.5f);

    slide_ = approach(slide_, open_ && !closing_ ? 1.0f : 0.0f, kSlideSpeed, deltaTime);
    if (closing_ && slide_ < 0.02f) {
        open_ = false;
        closing_ = false;
        slide_ = 0.0f;
        hooks::input().setCapturing(false);
    }

    scaledW_ = state.scaledWidth > 0 ? static_cast<float>(state.scaledWidth)
                                     : static_cast<float>(state.windowWidth) / unit_;
    scaledH_ = state.scaledHeight > 0 ? static_cast<float>(state.scaledHeight)
                                      : static_cast<float>(state.windowHeight) / unit_;

    /* The reference is 140-wide panels; on a narrow screen they would hang
     * off both edges, so they shrink just enough to fit instead. */
    panelW_ = kPanelWidth;
    if (!panels_.empty()) {
        const float fit = (scaledW_ - 16.0f -
                           kCatGap * static_cast<float>(panels_.size() - 1)) /
                          static_cast<float>(panels_.size());
        panelW_ = clamp(std::min(kPanelWidth, fit), 70.0f, kPanelWidth);
    }

    fullHeight_ = 0.0f;
    for (Panel& panel : panels_) {
        panel.expand = approach(panel.expand, panel.expanded ? 1.0f : 0.0f, kExpandSpeed,
                                deltaTime);
        fullHeight_ = std::max(fullHeight_, kHeaderHeight + panelContentHeight(panel) + 1.0f);
    }

    const float maxX = std::max(scaledW_ - 40.0f, -panelW_ + 40.0f);
    const float maxY = std::max(scaledH_ - 20.0f, 0.0f);
    time_ = context.time;
    for (std::size_t i = 0; i < panels_.size(); ++i) {
        Panel& panel = panels_[i];
        panel.x = clamp(panel.x, -panelW_ + 40.0f, maxX);
        panel.y = clamp(panel.y, 0.0f, maxY);

        const float start = static_cast<float>(i) * kStagger;
        const float local =
            clamp((slide_ - start) / std::max(1.0f - start, 0.001f), 0.0f, 1.0f);
        const float yOffset = (local - 1.0f) * fullHeight_;

        const float contentH = panelContentHeight(panel);
        const float totalH = kHeaderHeight + contentH + 1.0f;
        panel.header = Rect{px(panel.x), px(panel.y + yOffset), px(panelW_),
                            px(kHeaderHeight)};
        panel.outer = Rect{px(panel.x), px(panel.y + yOffset), px(panelW_), px(totalH)};

        const bool headerHovered =
            open_ && !closing_ && panel.header.contains(mouseX_, mouseY_);
        panel.hover = approach(panel.hover, headerHovered ? 1.0f : 0.0f, kHoverSpeed,
                               deltaTime);

        if (panel.configs) {
            float rowTop = panel.y + yOffset + kHeaderHeight;
            for (ConfigFile& entry : configs_) {
                entry.bounds =
                    panel.expand > 0.02f
                        ? Rect{panel.outer.x, px(rowTop), panel.outer.width, px(kRowHeight)}
                        : Rect{};
                const bool hovered =
                    open_ && !closing_ && entry.bounds.width > 0.0f &&
                    entry.bounds.contains(mouseX_, mouseY_);
                entry.hover = approach(entry.hover, hovered ? 1.0f : 0.0f, kHoverSpeed,
                                       deltaTime);
                rowTop += kRowHeight * panel.expand;
            }
            nameField_ =
                panel.expand > 0.02f
                    ? Rect{panel.outer.x, px(rowTop), panel.outer.width, px(kRowHeight)}
                    : Rect{};
            rowTop += kRowHeight * panel.expand;
            loadButton_ =
                panel.expand > 0.02f
                    ? Rect{panel.outer.x, px(rowTop), panel.outer.width, px(kRowHeight)}
                    : Rect{};
            rowTop += kRowHeight * panel.expand;
            saveButton_ =
                panel.expand > 0.02f
                    ? Rect{panel.outer.x, px(rowTop), panel.outer.width, px(kRowHeight)}
                    : Rect{};
            rowTop += kRowHeight * panel.expand;
            deleteButton_ =
                panel.expand > 0.02f
                    ? Rect{panel.outer.x, px(rowTop), panel.outer.width, px(kRowHeight)}
                    : Rect{};
            const bool nameHovered = open_ && !closing_ && nameField_.width > 0.0f &&
                                     nameField_.contains(mouseX_, mouseY_);
            const bool loadHovered = open_ && !closing_ && loadButton_.width > 0.0f &&
                                     loadButton_.contains(mouseX_, mouseY_);
            const bool saveHovered = open_ && !closing_ && saveButton_.width > 0.0f &&
                                     saveButton_.contains(mouseX_, mouseY_);
            const bool deleteHovered = open_ && !closing_ && deleteButton_.width > 0.0f &&
                                       deleteButton_.contains(mouseX_, mouseY_);
            nameHover_ = approach(nameHover_, nameHovered ? 1.0f : 0.0f, kHoverSpeed,
                                  deltaTime);
            loadHover_ = approach(loadHover_, loadHovered ? 1.0f : 0.0f, kHoverSpeed,
                                  deltaTime);
            saveHover_ = approach(saveHover_, saveHovered ? 1.0f : 0.0f, kHoverSpeed,
                                  deltaTime);
            deleteHover_ = approach(deleteHover_, deleteHovered ? 1.0f : 0.0f, kHoverSpeed,
                                    deltaTime);
            continue;
        }

        if (panel.actions) {
            float rowTop = panel.y + yOffset + kHeaderHeight;
            for (ActionRow& action : actions_) {
                action.bounds =
                    panel.expand > 0.02f
                        ? Rect{panel.outer.x, px(rowTop), panel.outer.width, px(kRowHeight)}
                        : Rect{};
                const bool hovered =
                    open_ && !closing_ && action.bounds.width > 0.0f &&
                    action.bounds.contains(mouseX_, mouseY_);
                action.hover = approach(action.hover, hovered ? 1.0f : 0.0f, kHoverSpeed,
                                        deltaTime);
                rowTop += kRowHeight * panel.expand;
            }
            continue;
        }

        float rowTop = panel.y + yOffset + kHeaderHeight;
        for (std::size_t index : panel.rows) {
            ModuleRow& row = rows_[index];
            const bool rowHovered =
                open_ && !closing_ && panel.expand > 0.3f &&
                Rect{panel.outer.x, px(rowTop), panel.outer.width, px(kRowHeight)}.contains(
                    mouseX_, mouseY_);
            row.hover = approach(row.hover, rowHovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
            row.enabled = approach(row.enabled, row.module->enabled() ? 1.0f : 0.0f,
                                   kEnableSpeed, deltaTime);
            row.expand = approach(row.expand, row.expanded ? 1.0f : 0.0f, kExpandSpeed,
                                  deltaTime);

            row.head = panel.expand > 0.02f
                           ? Rect{panel.outer.x, px(rowTop), panel.outer.width,
                                  px(kRowHeight)}
                           : Rect{};

            float settingTop = rowTop + kRowHeight;
            for (SettingRow& setting : row.settings) {
                if (!setting.setting->visible()) {
                    setting.bounds = Rect{};
                    setting.control = Rect{};
                    continue;
                }
                const float h = kRowHeight +
                                (setting.setting->type() == modules::Setting::Type::Choice
                                     ? static_cast<float>(setting.setting->choices().size()) *
                                           kRowHeight * setting.expand
                                     : 0.0f);
                const bool settingHovered =
                    open_ && !closing_ && row.expand > 0.3f && panel.expand > 0.3f &&
                    Rect{panel.outer.x, px(settingTop), panel.outer.width, px(h)}.contains(
                        mouseX_, mouseY_);
                setting.hover =
                    approach(setting.hover, settingHovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
                setting.expand = approach(setting.expand, setting.expanded ? 1.0f : 0.0f,
                                          kChoiceExpandSpeed, deltaTime);
                setting.picker = approach(setting.picker, setting.pickerOpen ? 1.0f : 0.0f,
                                          16.0f, deltaTime);
                if (setting.setting->type() == modules::Setting::Type::Number) {
                    setting.knob = approach(setting.knob, setting.setting->normalised(),
                                            20.0f, deltaTime);
                }
                setting.bounds =
                    (row.expand > 0.02f && panel.expand > 0.02f)
                        ? Rect{panel.outer.x, px(settingTop), panel.outer.width, px(h)}
                        : Rect{};
                settingTop += (kRowHeight +
                               (setting.setting->type() == modules::Setting::Type::Choice
                                    ? static_cast<float>(setting.setting->choices().size()) *
                                          kRowHeight * setting.expand
                                    : 0.0f)) *
                              row.expand;
            }
            rowTop += (kRowHeight + settingsHeight(row)) * panel.expand;
        }
    }

    if (activeSlider_ != nullptr && mouseDown_ && activeSlider_->control.width > 0.0f) {
        const Rect& track = activeSlider_->control;
        activeSlider_->setting->setNormalised((mouseX_ - track.x) / track.width);
    }

    if (activePicker_ != nullptr && mouseDown_) {
        SettingRow& settingRow = *activePicker_;
        const Rect popup{settingRow.control.right() + px(kPickerGap),
                         settingRow.control.y - px(1.5f), px(kPickerWidth),
                         px(kPickerHeight)};
        const Rect field{popup.x + px(kPickerPad), popup.y + px(kPickerPad),
                         popup.width - px(kPickerPad * 2.0f), px(kPickerField)};
        const Color current = settingRow.setting->colour();
        if (pickerAxis_ == 0) {
            const float saturation = clamp((mouseX_ - field.x) / std::max(field.width, 1.0f),
                                           0.0f, 1.0f);
            const float value =
                1.0f - clamp((mouseY_ - field.y) / std::max(field.height, 1.0f), 0.0f, 1.0f);
            settingRow.setting->setColour(Color::hsv(settingRow.hue, saturation, value,
                                                     current.a));
        } else {
            const Rect strip{field.x, field.bottom() + px(kPickerPad), field.width,
                             px(kPickerStrip)};
            settingRow.hue =
                clamp((mouseX_ - strip.x) / std::max(strip.width, 1.0f), 0.0f, 1.0f);
            const float maximum = std::max({current.r, current.g, current.b});
            const float minimum = std::min({current.r, current.g, current.b});
            const float saturation = maximum <= 0.0f ? 1.0f : (maximum - minimum) / maximum;
            settingRow.setting->setColour(Color::hsv(
                settingRow.hue, saturation, maximum <= 0.0f ? 1.0f : maximum, current.a));
        }
    }
}

void ClickGui::render(modules::Context& context) {
    build();
    layout(context);
    if (slide_ <= 0.004f || panels_.empty()) {
        return;
    }

    render::Renderer& gfx = render::renderer();
    const float alpha = clamp(slide_, 0.0f, 1.0f);

    /* Dimmed scene behind the panels, fading with the slide. */
    const float screenWidth = static_cast<float>(context.state->windowWidth);
    const float screenHeight = static_cast<float>(context.state->windowHeight);
    const render::Backdrop& backdrop = client().backdrop();
    if (blur_ && backdrop.valid()) {
        const Rect full{0.0f, 0.0f, screenWidth, screenHeight};
        const Rect uv{0.0f, 1.0f, 1.0f, -1.0f};
        gfx.image(full, uv, backdrop.texture(), Color{1.0f, 1.0f, 1.0f, alpha}, 0.0f);
        gfx.rect(full, Color::rgb(0x000000, 0.47f * alpha), 0.0f);
    } else {
        gfx.rect(Rect{0.0f, 0.0f, screenWidth, screenHeight},
                 Color::rgb(0x000000, 0.47f * alpha), 0.0f);
    }

    for (Panel& panel : panels_) {
        renderPanel(panel);
    }

    for (ModuleRow& row : rows_) {
        for (SettingRow& setting : row.settings) {
            if (setting.picker > 0.004f) {
                renderPicker(setting);
            }
        }
    }

    renderCursor(gfx, alpha);
}

/* The game's cursor is grabbed (and therefore invisible) while the menu owns
 * it, so the menu draws its own. The GWEN-style triangle with a hairline edge
 * reads against both the dim and the blurred backdrops. */
void ClickGui::renderCursor(render::Renderer& gfx, float alpha) {
    const float x = mouseX_;
    const float y = mouseY_;
    float w = px(6.0f);
    float h = px(9.5f);
    const float xs[4] = {x, x, x + w, x + w * 0.62f};
    const float ys[4] = {y, y + h, y + h * 0.68f, y + h * 0.68f};
    /* One-pixel outline first, then the fill inset toward the centroid, so the
     * edge reads on any background. */
    gfx.face(xs, ys, Color::rgb(0xE8ECF2, 0.9f * alpha));
    float cx = 0.0f;
    float cy = 0.0f;
    for (int i = 0; i < 4; ++i) {
        cx += xs[i] * 0.25f;
        cy += ys[i] * 0.25f;
    }
    const float inset = 0.85f;
    float xs2[4];
    float ys2[4];
    for (int i = 0; i < 4; ++i) {
        xs2[i] = cx + (xs[i] - cx) * inset;
        ys2[i] = cy + (ys[i] - cy) * inset;
    }
    gfx.face(xs2, ys2, Color::rgb(0x0B0E14, 0.9f * alpha));
}

void ClickGui::renderPanel(Panel& panel) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = clamp(slide_, 0.0f, 1.0f);
    const Color& accent = palette.accent;

    /* Accent hairline around the whole panel, then the dark body inset by one
     * device pixel. */
    gfx.rect(panel.outer, accent.withAlpha(alpha), 0.0f);
    gfx.rect(panel.outer.inset(1.0f), darker(kModuleBg, 2).withAlpha(alpha), 0.0f);

    if (panel.expand > 0.005f) {
        gfx.pushClip(Rect{panel.outer.x + 1.0f, panel.header.bottom() - 1.0f,
                          panel.outer.width - 2.0f,
                          std::max(0.0f, panel.outer.bottom() - panel.header.bottom())});
        float rowTop = panel.header.y / unit_ + kHeaderHeight;
        if (panel.configs) {
            renderConfigs(panel, rowTop);
        } else if (panel.actions) {
            renderActions(panel, rowTop);
        } else {
            for (std::size_t index : panel.rows) {
                ModuleRow& row = rows_[index];
                renderModuleRow(panel, row, rowTop);
                rowTop += (kRowHeight + settingsHeight(row)) * panel.expand;
            }
        }
        gfx.popClip();
    }

    /* Header over the module block: base, hover, then the active step. */
    const Color header =
        Color::mix(Color::mix(kBase, kHoverBg, panel.hover), kActiveBg, panel.expand);
    gfx.rect(Rect{panel.outer.x + 1.5f, panel.outer.y + 1.5f, panel.outer.width - 3.0f,
                  px(kHeaderHeight) - 2.5f},
             header.withAlpha(alpha), 0.0f);

    /* Indicator line growing from the centre, quadratic ease-out. */
    if (panel.expand > 0.005f) {
        const float ease = 1.0f - (1.0f - panel.expand) * (1.0f - panel.expand);
        const float half = (panel.outer.width * 0.5f - 1.5f) * ease;
        const float mid = panel.outer.x + panel.outer.width * 0.5f;
        gfx.rect(Rect{mid - half, panel.outer.y + 1.5f, half * 2.0f, px(0.5f)},
                 accent.withAlpha(alpha), 0.0f);
    }

    const Color text =
        Color::mix(Color::mix(kTextDim, kTextMid, panel.hover), Color::rgb(0xFFFFFF),
                   panel.expand);
    gfx.textCap(render::FontRole::Medium, panel.label, panel.outer.x + px(kPadding),
                panel.outer.y + px((kHeaderHeight - kTextSize * 0.727f) * 0.5f), px(kTextSize),
                text.withAlpha(alpha));
    const float iconSize = px(9.0f);
    gfx.icon(panel.icon,
             Rect{panel.outer.right() - px(kPadding) - iconSize - 1.0f,
                  panel.outer.y + (px(kHeaderHeight) - iconSize) * 0.5f, iconSize, iconSize},
             accent.withAlpha(alpha));
}

void ClickGui::renderModuleRow(Panel& panel, ModuleRow& row, float top) {
    (void)panel;
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = clamp(slide_, 0.0f, 1.0f);
    const Color& accent = palette.accent;

    if (row.head.width <= 0.0f) {
        return;
    }
    const Rect head{row.head.x + 1.5f, row.head.y, row.head.width - 3.0f,
                    px(kRowHeight)};

    const Color disabled = Color::mix(kModuleBg, kModuleHover, row.hover);
    gfx.rect(head, Color::mix(disabled, darker(accent, 2), row.enabled).withAlpha(alpha),
             0.0f);

    const Color text = Color::mix(Color::mix(kTextDim, kTextMid, row.hover), kTextOn,
                                  row.enabled);
    const float drawX = head.x + px(kPadding) + px(kHoverShift) * row.hover;
    const float textY = head.y + px(2.0f);
    const float size = px(kTextSize);
    const float nameEnd =
        gfx.textCap(render::FontRole::Medium, row.module->name(), drawX, textY, size,
                    text.withAlpha(alpha));

    if (row.binding) {
        gfx.textCap(render::FontRole::Medium, "[...]", nameEnd + px(kTextSize * 0.5f), textY,
                    size, accent.withAlpha(alpha));
    } else if (row.module->key() != 0) {
        char bind[32];
        std::snprintf(bind, sizeof(bind), "[%s]", keyName(row.module->key()).c_str());
        gfx.textCap(render::FontRole::Medium, bind, nameEnd + px(kTextSize * 0.5f), textY,
                    size, kBindIdle.withAlpha(alpha));
    }

    bool hasSettings = false;
    for (const SettingRow& setting : row.settings) {
        if (setting.setting->visible()) {
            hasSettings = true;
            break;
        }
    }
    if (hasSettings) {
        const Color idle = Color::mix(kItemIdle, kTextMid, row.hover);
        const Color arrow = Color::mix(Color::mix(idle, accent, row.expand),
                                       kTextOn, row.enabled);
        const float arrowX = head.right() - px(kPadding) - px(5.0f);
        const float arrowY = head.y + px(3.0f);
        /* No rotated text in the batcher, so the chevron crossfades from left
         * to down across the expand animation instead of rotating. */
        gfx.textCap(render::FontRole::Medium, "<", arrowX, arrowY, px(kArrowSize),
                    arrow.withAlpha(alpha * (1.0f - row.expand)));
        gfx.textCap(render::FontRole::Medium, "v", arrowX, arrowY, px(kArrowSize),
                    arrow.withAlpha(alpha * row.expand));
    }

    if (row.expand <= 0.005f) {
        return;
    }
    float settingTop = top + kRowHeight;
    for (SettingRow& setting : row.settings) {
        if (!setting.setting->visible()) {
            continue;
        }
        renderSetting(row, setting, settingTop);
        settingTop += kRowHeight +
                      (setting.setting->type() == modules::Setting::Type::Choice
                           ? static_cast<float>(setting.setting->choices().size()) *
                                 kRowHeight * setting.expand
                           : 0.0f);
    }
}

void ClickGui::renderSetting(ModuleRow& row, SettingRow& setting, float top) {
    (void)row;
    (void)top;
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = clamp(slide_, 0.0f, 1.0f);
    const Color& accent = palette.accent;
    modules::Setting& value = *setting.setting;

    if (setting.bounds.height <= 0.5f) {
        return;
    }

    const float x = setting.bounds.x + 1.5f;
    const float width = setting.bounds.width - 3.0f;
    gfx.rect(Rect{x, setting.bounds.y, width, px(kRowHeight)}, kSettingBg.withAlpha(alpha),
             0.0f);

    const float size = px(kTextSize);
    const float drawX = x + px(kPadding) + px(kHoverShift) * setting.hover;
    const float textY = setting.bounds.y + px(2.0f);
    const Color text = Color::mix(kTextDim, kTextMid, setting.hover);

    switch (value.type()) {
        case modules::Setting::Type::Boolean: {
            gfx.textCap(render::FontRole::Medium, value.name(), drawX, textY, size,
                        text.withAlpha(alpha));
            const float box = px(kToggleSize);
            const Rect frame{setting.bounds.right() - 1.5f - px(kPadding) - box - 1.0f,
                             setting.bounds.y + (px(kRowHeight) - box) * 0.5f - 1.0f,
                             box + 2.0f, box + 2.0f};
            setting.control = Rect{frame.x + 1.0f, frame.y + 1.0f, box, box};
            gfx.rect(frame, kOutlineDark.withAlpha(alpha), 0.0f);
            if (value.booleanValue()) {
                gfx.rect(setting.control, accent.withAlpha(alpha), 0.0f);
            }
            break;
        }

        case modules::Setting::Type::Number: {
            const std::string label = value.name() + ": " + formatNumber(value);
            gfx.textCap(render::FontRole::Medium, label, drawX, textY, size,
                        text.withAlpha(alpha));
            const Rect track{x, setting.bounds.y + px(kRowHeight - kSliderHeight),
                             width, px(kSliderHeight)};
            setting.control = track;
            gfx.rect(track, kTrackBg.withAlpha(alpha), 0.0f);
            const float fill = track.width * clamp(value.normalised(), 0.0f, 1.0f);
            if (fill > 0.5f) {
                gfx.rectHorizontal(Rect{track.x, track.y, fill, track.height},
                                   accent.withAlpha(alpha),
                                   darker(accent, 1).withAlpha(alpha), 0.0f);
            }
            break;
        }

        case modules::Setting::Type::Choice: {
            gfx.textCap(render::FontRole::Medium, value.name(), drawX, textY, size,
                        text.withAlpha(alpha));
            const std::string& current = value.choiceName();
            gfx.textCap(render::FontRole::Medium, current,
                        setting.bounds.right() - 1.5f - px(kPadding), textY, size,
                        text.withAlpha(alpha), render::Align::Right);
            setting.control = setting.bounds;
            if (setting.expand > 0.01f) {
                renderChoiceItems(row, setting, top);
            }
            break;
        }

        case modules::Setting::Type::Key: {
            gfx.textCap(render::FontRole::Medium, value.name(), drawX, textY, size,
                        text.withAlpha(alpha));
            const std::string label =
                setting.listeningForKey ? "[...]" : "[" + keyName(value.key()) + "]";
            gfx.textCap(render::FontRole::Medium, label,
                        setting.bounds.right() - 1.5f - px(kPadding), textY, size,
                        (setting.listeningForKey ? accent : text).withAlpha(alpha),
                        render::Align::Right);
            setting.control = setting.bounds;
            break;
        }

        case modules::Setting::Type::Colour: {
            gfx.textCap(render::FontRole::Medium, value.name(), drawX, textY, size,
                        text.withAlpha(alpha));
            const float box = px(kToggleSize);
            setting.control = Rect{setting.bounds.right() - 1.5f - px(kPadding) - box,
                                   setting.bounds.y + (px(kRowHeight) - box) * 0.5f, box,
                                   box};
            gfx.rect(Rect{setting.control.x - 1.0f, setting.control.y - 1.0f, box + 2.0f,
                          box + 2.0f},
                     kOutlineDark.withAlpha(alpha), 0.0f);
            gfx.rect(setting.control, value.colour().withAlpha(alpha), 0.0f);
            break;
        }
    }
}

void ClickGui::renderChoiceItems(ModuleRow& row, SettingRow& setting, float top) {
    (void)row;
    (void)top;
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = clamp(slide_, 0.0f, 1.0f) * clamp(setting.expand, 0.0f, 1.0f);
    if (alpha <= 0.01f) {
        return;
    }
    modules::Setting& value = *setting.setting;
    const float x = setting.bounds.x + 1.5f;
    const float width = setting.bounds.width - 3.0f;
    const float size = px(kTextSize);

    gfx.rect(Rect{x, setting.bounds.y + px(kRowHeight), width,
                  static_cast<float>(value.choices().size()) * px(kRowHeight) *
                      setting.expand},
             kSettingDeep.withAlpha(alpha), 0.0f);

    const float revealed =
        static_cast<float>(value.choices().size()) * px(kRowHeight) * setting.expand;
    for (std::size_t i = 0; i < value.choices().size(); ++i) {
        const float itemY =
            setting.bounds.y + px(kRowHeight + static_cast<float>(i) * kRowHeight);
        if (itemY + px(kRowHeight) > setting.bounds.y + px(kRowHeight) + revealed + 0.5f) {
            break;
        }
        const bool selected = static_cast<int>(i) == value.choice();
        const bool hovered =
            mouseX_ >= setting.bounds.x && mouseX_ <= setting.bounds.right() &&
            mouseY_ >= itemY && mouseY_ <= itemY + px(kRowHeight);
        Color colour = Color::mix(kItemIdle, kTextMid, hovered ? 1.0f : 0.0f);
        if (selected) {
            colour = palette.accent;
        }
        gfx.textCap(render::FontRole::Medium, value.choices()[i],
                    x + px(kPadding) + (hovered ? px(kHoverShift) : 0.0f),
                    itemY + px(2.0f), size, colour.withAlpha(alpha));
    }
}

void ClickGui::renderConfigs(Panel& panel, float top) {
    (void)top;
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = clamp(slide_, 0.0f, 1.0f);
    const Color& accent = palette.accent;
    const float size = px(kTextSize);

    for (std::size_t i = 0; i < configs_.size(); ++i) {
        const ConfigFile& entry = configs_[i];
        if (entry.bounds.width <= 0.0f) {
            continue;
        }
        const Rect row{entry.bounds.x + 1.5f, entry.bounds.y, entry.bounds.width - 3.0f,
                       px(kRowHeight)};
        gfx.rect(row, kSettingBg.withAlpha(alpha), 0.0f);
        const bool selected = static_cast<int>(i) == selectedConfig_;
        const Color text =
            selected ? accent : Color::mix(kTextDim, kTextMid, entry.hover);
        gfx.textCap(render::FontRole::Medium, entry.name,
                    row.x + px(kPadding) + px(kHoverShift) * entry.hover,
                    row.y + px(2.0f), size, text.withAlpha(alpha));
    }

    if (nameField_.width > 0.0f) {
        const Rect row{nameField_.x + 1.5f, nameField_.y, nameField_.width - 3.0f,
                       px(kRowHeight)};
        gfx.rect(row, kSettingDeep.withAlpha(alpha), 0.0f);
        const std::string shown =
            configName_.empty() ? "Config name..." : configName_;
        const Color text = configName_.empty()
                               ? kItemIdle.withAlpha(alpha)
                               : Color::mix(kTextDim, kTextMid, nameHover_).withAlpha(alpha);
        const float end = gfx.textCap(render::FontRole::Medium, shown,
                                      row.x + px(kPadding), row.y + px(2.0f), size, text);
        if (editingName_) {
            const float blink = 0.5f + 0.5f * std::sin(time_ * 6.0f);
            gfx.rect(Rect{end + px(1.5f), row.y + px(2.0f), px(1.0f), size},
                     accent.withAlpha(alpha * blink), 0.0f);
        }
    }

    struct Button {
        const Rect* bounds;
        const char* label;
        float hover;
    };
    const Button buttons[] = {
        {&loadButton_, "Load", loadHover_},
        {&saveButton_, "Save", saveHover_},
        {&deleteButton_, "Delete", deleteHover_},
    };
    for (const Button& button : buttons) {
        if (button.bounds->width <= 0.0f) {
            continue;
        }
        const Rect row{button.bounds->x + 1.5f, button.bounds->y,
                       button.bounds->width - 3.0f, px(kRowHeight)};
        gfx.rect(row, Color::mix(kSettingBg, kModuleHover, button.hover).withAlpha(alpha),
                 0.0f);
        gfx.textCap(render::FontRole::Medium, button.label, row.x + px(kPadding),
                    row.y + px(2.0f), size,
                    Color::mix(kTextDim, kTextOn, button.hover).withAlpha(alpha));
    }
    (void)panel;
}

void ClickGui::renderActions(Panel& panel, float top) {
    (void)panel;
    (void)top;
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = clamp(slide_, 0.0f, 1.0f);
    const float size = px(kTextSize);

    for (std::size_t i = 0; i < actions_.size(); ++i) {
        const ActionRow& action = actions_[i];
        if (action.bounds.width <= 0.0f) {
            continue;
        }
        const Rect row{action.bounds.x + 1.5f, action.bounds.y,
                       action.bounds.width - 3.0f, px(kRowHeight)};
        gfx.rect(row, Color::mix(kSettingBg, kModuleHover, action.hover).withAlpha(alpha),
                 0.0f);
        /* The way out is the one row that is not like the others. */
        const Color text = i + 1 == actions_.size()
                               ? Color::mix(palette.textDim, palette.negative, action.hover)
                               : Color::mix(kTextDim, kTextOn, action.hover);
        gfx.textCap(render::FontRole::Medium, action.label, row.x + px(kPadding),
                    row.y + px(2.0f), size, text.withAlpha(alpha));
    }
}

void ClickGui::renderPicker(SettingRow& setting) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = clamp(slide_, 0.0f, 1.0f) *
                        ease::outQuint(clamp(setting.picker, 0.0f, 1.0f));
    if (alpha <= 0.004f || setting.control.width <= 0.0f) {
        return;
    }

    const Rect popup{setting.control.right() + px(kPickerGap), setting.control.y - px(1.5f),
                     px(kPickerWidth), px(kPickerHeight)};
    gfx.shadow(popup, palette.shadow.withAlpha(alpha), px(14.0f), px(9.0f));
    gfx.rect(popup, palette.header.withAlpha(alpha), px(9.0f));

    const Color current = setting.setting->colour();
    const float saturation = [&] {
        const float maximum = std::max({current.r, current.g, current.b});
        const float minimum = std::min({current.r, current.g, current.b});
        return maximum <= 0.0f ? 0.0f : (maximum - minimum) / maximum;
    }();
    const float value = std::max({current.r, current.g, current.b});

    const Rect field{popup.x + px(kPickerPad), popup.y + px(kPickerPad),
                     popup.width - px(kPickerPad * 2.0f), px(kPickerField)};
    gfx.rectHorizontal(field, Color{1.0f, 1.0f, 1.0f, alpha},
                       Color::hsv(setting.hue, 1.0f, 1.0f, alpha), px(4.0f));
    gfx.rectVertical(field, Color{0.0f, 0.0f, 0.0f, 0.0f}, Color{0.0f, 0.0f, 0.0f, alpha},
                     px(4.0f));
    gfx.ring(field.x + field.width * saturation, field.y + field.height * (1.0f - value),
             px(3.2f), px(1.3f), 1.0f, Color{1.0f, 1.0f, 1.0f, alpha});

    const Rect strip{field.x, field.bottom() + px(kPickerPad), field.width,
                     px(kPickerStrip)};
    const int segments = 6;
    const float segmentWidth = strip.width / static_cast<float>(segments);
    for (int index = 0; index < segments; ++index) {
        const float from = static_cast<float>(index) / static_cast<float>(segments);
        const float to = static_cast<float>(index + 1) / static_cast<float>(segments);
        gfx.rectHorizontal(
            Rect{strip.x + segmentWidth * static_cast<float>(index), strip.y, segmentWidth,
                 strip.height},
            Color::hsv(from, 1.0f, 1.0f, alpha), Color::hsv(to - 0.0001f, 1.0f, 1.0f, alpha),
            0.0f);
    }
    gfx.rect(Rect{strip.x + strip.width * setting.hue - px(1.0f), strip.y - px(1.5f),
                  px(2.0f), strip.height + px(3.0f)},
             Color{1.0f, 1.0f, 1.0f, alpha}, px(1.0f));

    const Rect preview{field.x, strip.bottom() + px(kPickerPad), field.width, px(12.0f)};
    gfx.rect(preview, current.withAlpha(alpha), px(3.0f));
    char hex[16];
    std::snprintf(hex, sizeof(hex), "#%06X", current.packed() >> 8);
    gfx.textCap(render::FontRole::Mono, hex, preview.centerX(),
                preview.centerY() - px(3.0f), px(6.6f),
                Color{0.0f, 0.0f, 0.0f, alpha * 0.65f}, render::Align::Center);
}

}  // namespace cold::ui
