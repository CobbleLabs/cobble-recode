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

/* ------------------------------------------------------------------------- *
 * Design metrics, in the layout's own pixels at scale 1.0.
 * ------------------------------------------------------------------------- */

constexpr float kMenuWidth = 660.0f;
constexpr float kTopMargin = 18.0f;
constexpr float kTabBarHeight = 38.0f;
constexpr float kTabBarRadius = 12.0f;
constexpr float kPanelGap = 9.0f;
constexpr float kCardRadius = 14.0f;
constexpr float kHeaderHeight = 38.0f;
constexpr float kCardPadX = 12.0f;
constexpr float kRowHeight = 50.0f;
constexpr float kRowGap = 4.0f;
constexpr float kRowRadius = 8.0f;
constexpr float kGearSlotWidth = 52.0f;

constexpr float kRowIconCentre = 27.0f;
constexpr float kRowTextX = 56.0f;
constexpr float kRowNameTop = 9.0f;
constexpr float kRowDescTop = 27.0f;
constexpr float kPillWidth = 38.0f;
constexpr float kPillHeight = 19.0f;
constexpr float kPillMargin = 16.0f;

/* Subpage rows are their own, shorter line: one setting, no description. */
constexpr float kSettingHeight = 40.0f;
constexpr float kSettingPadX = 14.0f;
constexpr float kChoiceItemHeight = 26.0f;
constexpr float kSliderTrack = 4.0f;
constexpr float kEditorHeight = 140.0f;
constexpr float kEditorField = 96.0f;
constexpr float kEditorStrip = 10.0f;
constexpr float kEditorSwatch = 18.0f;
constexpr float kEditorGap = 8.0f;

/* Type sizes as the design measures them: the height of the line box, which is
 * what both ImGui and render::Renderer::text() place from. */
constexpr float kLabelBox = 15.0f;
constexpr float kDescBox = 12.5f;
constexpr float kMicroBox = 9.5f;
constexpr float kHeadingBox = 11.5f;
constexpr float kIconBox = 17.0f;
constexpr float kChevronBox = 13.0f;
constexpr float kTracking = 1.4f;

constexpr auto kLabelFont = render::FontRole::Bold;
constexpr auto kDescFont = render::FontRole::Medium;
constexpr auto kMicroFont = render::FontRole::Bold;
constexpr auto kHeadingFont = render::FontRole::Bold;

constexpr float kSlideSpeed = 16.0f;
constexpr float kHoverSpeed = 16.0f;
constexpr float kOnSpeed = 18.0f;
constexpr float kTabSpeed = 20.0f;
constexpr float kSubpageSpeed = 22.0f;
constexpr float kExpandSpeed = 14.0f;
/* The card resizes when a category with a different number of rows takes over.
 * Slower than the fade on purpose: the fade is a cut, the resize is the frame
 * itself moving, and a frame that snaps to a new size undoes the cut's work. */
constexpr float kCardSpeed = 15.0f;

/* Page handover. The old list leaves quickly and decisively; the new one arrives
 * eased and unhurried, its rows staggered so the list unrolls downward instead of
 * appearing all at once. */
constexpr float kPageOutSeconds = 0.10f;
constexpr float kPageInSeconds = 0.20f;
constexpr float kPageCascadeSeconds = 0.42f;
constexpr float kRowRise = 14.0f;   // design px a row drops through as it arrives
constexpr float kRowStagger = 0.05f;  // cascade clock spent before the next row starts
constexpr float kRowStaggerCap = 0.58f;

/* ------------------------------------------------------------------------- *
 * First-open reveal.
 *
 * The first time the menu opens after injection a small capsule carrying the
 * client's name pops into the spot the tab bar will occupy, holds long enough to
 * be read, stretches sideways until it *is* the tab bar, and then the card
 * unfolds downward out of it. The container arrives first and the contents move
 * in after, which is the whole trick.
 *
 * Phase windows along one shared clock rather than a chain of callbacks. Every
 * value is a pure function of elapsed time, so the widen and the unfold can be
 * given windows that deliberately OVERLAP - the card starts dropping open while
 * the bar is still spreading - and that overlap is what fuses them into one
 * movement instead of two that happen to be adjacent.
 *
 * The bar is already a pill at rest, which is why this shape of animation suits
 * this menu: the capsule is not a foreign element that has to be explained away,
 * it is the tab bar before it has been given its width.
 * ------------------------------------------------------------------------- */
constexpr float kRevealPopEnd = 0.42f;
constexpr float kRevealHoldEnd = 0.72f;
constexpr float kRevealWidenEnd = 1.20f;
constexpr float kRevealUnfoldStart = 1.06f;  // before the widen ends, on purpose
constexpr float kRevealUnfoldEnd = 1.66f;
/* Everything has visibly settled by the unfold's end - the row cascade is released
 * partway through it and finishes first - so this only has to be far enough past
 * that to not cut the last frames off. It is also when the menu starts answering
 * the pointer, so there is no reason to sit on it longer. */
constexpr float kRevealEnd = 1.70f;
constexpr float kRevealPopScale = 0.32f;  // capsule width at the start of the pop

constexpr std::size_t kMaxConfigName = 24;

/* ------------------------------------------------------------------------- *
 * Palette. The accent comes from the shared theme, so the gear tab's colour
 * editor moves the whole menu with it.
 * ------------------------------------------------------------------------- */

const Color kCardBg = Color::rgb(0x111113, 236.0f / 255.0f);
const Color kCardBorder = Color::rgb(0x262629);
const Color kTabBarBg = Color::rgb(0x171719, 236.0f / 255.0f);
const Color kTabChipBg = Color::rgb(0x28282B);
const Color kRowBg = Color::rgb(0x171719, 170.0f / 255.0f);
const Color kRowBgOn = Color::rgb(0x202023, 235.0f / 255.0f);
const Color kSeparator = Color::rgb(0x303034);
const Color kTextPrimary = Color::rgb(0xEDEDEF);
const Color kTextMuted = Color::rgb(0x8A8A90);
const Color kTextFaint = Color::rgb(0x6C6C72);
const Color kChipBg = Color::rgb(0x2C2C2F);
const Color kChipText = Color::rgb(0x9E9EA4);
const Color kToggleOff = Color::rgb(0x3A3A3F);
const Color kKnobOff = Color::rgb(0xA8A8AE);
const Color kKnobOn = Color::rgb(0xFFFFFF);
const Color kIconOn = Color::rgb(0xD6D6DA);
const Color kHoverWash = Color::rgb(0xFFFFFF, 18.0f / 255.0f);
const Color kTrackBg = Color::rgb(0x000000, 0.35f);

float approach(float current, float target, float rate, float deltaTime) {
    const float step = 1.0f - std::exp(-rate * deltaTime);
    const float next = current + (target - current) * step;
    return std::fabs(target - next) < 0.0015f ? target : next;
}

/* Scales a float for the duration of a scope and puts it back.
 *
 * Every colour in this file resolves through fade() or accent(), and both fold in
 * the menu's alpha_, so nudging that one member is how a single row or a single
 * page is faded on its own - the alternative is threading a factor through a few
 * dozen colour arguments and missing some. A guard rather than a pair of
 * assignments because several of these draw functions return early. */
class AlphaScope {
public:
    AlphaScope(float& target, float factor) : target_(target), saved_(target) {
        target_ = saved_ * factor;
    }
    ~AlphaScope() { target_ = saved_; }
    AlphaScope(const AlphaScope&) = delete;
    AlphaScope& operator=(const AlphaScope&) = delete;

private:
    float& target_;
    float saved_;
};

/* A size in this file means the height of the line box, the way the design
 * sheet and ImGui both mean it. The renderer means an em, which for Inter is a
 * fifth smaller, so every call goes through here. */
float fontEm(render::FontRole role, float box) {
    const render::FontFace& face = render::renderer().fonts().face(role);
    const float ratio = (face.ascent() - face.descent()) * face.scaleFor(1.0f);
    return ratio > 0.0001f ? box / ratio : box;
}

float textWidth(render::FontRole role, std::string_view value, float box) {
    return render::renderer().measure(role, value, fontEm(role, box));
}

float drawText(render::FontRole role, std::string_view value, float x, float top, float box,
               const Color& colour, render::Align align = render::Align::Left) {
    return render::renderer().text(role, value, x, top, fontEm(role, box), colour, align);
}

std::string elideText(render::FontRole role, std::string_view value, float box, float maxWidth) {
    return render::renderer().elide(role, value, fontEm(role, box), maxWidth);
}

/* The category title carries tracking, so it is drawn a glyph at a time. */
void drawTracked(render::FontRole role, std::string_view value, float x, float top, float box,
                 const Color& colour, float tracking) {
    const float em = fontEm(role, box);
    render::Renderer& gfx = render::renderer();
    for (char character : value) {
        const std::string_view glyph(&character, 1);
        gfx.text(role, glyph, x, top, em, colour);
        x += gfx.measure(role, glyph, em) + tracking;
    }
}

float trackedWidth(render::FontRole role, std::string_view value, float box, float tracking) {
    const float em = fontEm(role, box);
    render::Renderer& gfx = render::renderer();
    float width = 0.0f;
    for (char character : value) {
        width += gfx.measure(role, std::string_view(&character, 1), em) + tracking;
    }
    return width > 0.0f ? width - tracking : 0.0f;
}

std::string upperCase(std::string_view value) {
    std::string out(value);
    for (char& character : out) {
        if (character >= 'a' && character <= 'z') {
            character = static_cast<char>(character - 32);
        }
    }
    return out;
}

std::string lowerCase(std::string_view value) {
    std::string out;
    out.reserve(value.size());
    for (char character : value) {
        if (character == ' ') continue;
        out.push_back(static_cast<char>(character >= 'A' && character <= 'Z' ? character + 32
                                                                            : character));
    }
    return out;
}

/* The chip beside a module name. Anything at eight and below is a mouse button,
 * which is the rule the binds themselves follow. */
std::string bindLabel(int key, bool listening) {
    if (listening) {
        return "...";
    }
    if (key == 0) {
        return "NONE";
    }
    if (key <= 8) {
        return "MOUSE";
    }
    return upperCase(keyName(key));
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

enum : int {
    kActionReloadScripts = 0,
    kActionReloadConfigs,
    kActionOpenFolder,
    kActionUnload,
};

}  // namespace

Color ClickGui::accent(float alpha) const { return theme().accent.withAlpha(alpha_ * alpha); }

/* ------------------------------------------------------------------------- *
 * Reveal
 * ------------------------------------------------------------------------- */

float ClickGui::entranceAt(std::size_t ordinal) const {
    if (pageEnter_ >= 1.0f) {
        return 1.0f;
    }
    const float delay =
        std::min(kRowStaggerCap, static_cast<float>(ordinal) * kRowStagger);
    const float span = std::max(0.12f, 1.0f - delay);
    return ease::outCubic(clamp((pageEnter_ - delay) / span, 0.0f, 1.0f));
}

/* Resolve the reveal for this frame against the settled geometry the layout has
 * just produced. `bar_` and `card_` are the finished rectangles throughout; what
 * this computes is the smaller shapes the paint is currently allowed to fill. */
void ClickGui::advanceReveal(float deltaTime) {
    const auto settled = [this] {
        Reveal done;
        done.bar = bar_;
        done.card = card_;
        done.barRadius = s(kTabBarRadius);
        return done;  // glow 0, label 0, the two content alphas 1, card visible
    };

    if (!revealing_) {
        reveal_ = settled();
        return;
    }

    revealClock_ += deltaTime;
    if (revealClock_ >= kRevealEnd) {
        revealing_ = false;
        reveal_ = settled();
        return;
    }

    const float clock = revealClock_;
    const float popT = ease::springIn(ease::window(clock, 0.0f, kRevealPopEnd));
    const float widenT = ease::morph(ease::window(clock, kRevealHoldEnd, kRevealWidenEnd));
    const float unfoldT =
        ease::morph(ease::window(clock, kRevealUnfoldStart, kRevealUnfoldEnd));

    /* The capsule is born at the tab bar's own height and the pop is over long
     * before the widen begins, so width is the only axis still travelling by then
     * and the bar cannot drift off the row it belongs on. */
    const float pop = lerp(kRevealPopScale, 1.0f, popT);
    const std::string brand = upperCase(kClientName);
    const float labelWidth = trackedWidth(kHeadingFont, brand, s(kHeadingBox), s(kTracking));
    const float capsule = std::min(bar_.width, labelWidth + s(56.0f));

    /* The spring overshoots, which is the point, but on a window narrow enough that
     * the capsule is already the full bar width that would briefly make it wider
     * than the bar it is turning into. The height is left free to overshoot: there
     * it reads as the arrival having weight. */
    const float width = std::min(bar_.width, lerp(capsule * pop, bar_.width, widenT));
    const float height = bar_.height * pop;

    /* Converge on the bar exactly rather than on a rounded-off centre, so the
     * frame the capsule becomes the tab bar is not a fraction of a pixel out from
     * the card that hangs off it. */
    reveal_.bar = widenT >= 1.0f ? bar_
                                 : Rect{bar_.centerX() - width * 0.5f,
                                        bar_.centerY() - height * 0.5f, width, height};
    reveal_.barRadius = lerp(reveal_.bar.height * 0.5f, s(kTabBarRadius), widenT);

    /* Halo on arrival only: it expands out of the capsule and dissolves, so the
     * pop looks like it displaced something instead of merely becoming visible. */
    const float ripple = ease::window(clock, 0.0f, kRevealPopEnd * 0.9f);
    reveal_.glow = (1.0f - ripple) * (1.0f - ripple);

    /* The label arrives after the shape has landed and leaves the instant it
     * starts to stretch. Riding the widen would smear a centred string across a
     * growing box, which is the one thing that would give away that this is a
     * rectangle being resized rather than an object changing form. */
    reveal_.label = ease::outExpo(ease::window(clock, 0.12f, kRevealPopEnd + 0.06f)) *
                    (1.0f - ease::morph(ease::window(clock, kRevealHoldEnd,
                                                     kRevealHoldEnd + 0.16f)));
    /* Tabs fade in once the bar has its full width, so none of them is ever seen
     * half-clipped by an edge still on its way out. */
    reveal_.barContent = ease::outCubic(ease::window(clock, kRevealWidenEnd - 0.04f,
                                                     kRevealWidenEnd + 0.20f));

    /* Not drawn at all until there is more than a hairline of it: a rectangle half a
     * pixel tall carrying a fourteen-pixel corner radius is degenerate geometry, and
     * what an SDF makes of it is not worth finding out one driver at a time. */
    const float cardHeight = card_.height * unfoldT;
    reveal_.cardVisible = cardHeight > 1.0f;
    reveal_.card = Rect{card_.x, card_.y, card_.width, cardHeight};
    reveal_.cardContent = ease::outCubic(ease::window(clock, kRevealUnfoldStart + 0.16f,
                                                      kRevealUnfoldEnd));
}

/* ------------------------------------------------------------------------- *
 * Build
 * ------------------------------------------------------------------------- */

void ClickGui::build() {
    if (built_) {
        return;
    }
    rows_.clear();
    tabs_.clear();

    for (int index = 0; index < static_cast<int>(modules::Category::Count); ++index) {
        const auto category = static_cast<modules::Category>(index);
        std::vector<modules::Module*> inCategory = modules::modules().byCategory(category);
        if (inCategory.empty()) {
            continue;
        }
        Tab tab;
        tab.key = modules::categoryName(category);
        tab.label = modules::categoryName(category);
        tab.icon = modules::categoryIcon(category);
        tab.countNoun = "MODULES";
        for (modules::Module* module : inCategory) {
            Row row;
            row.kind = RowKind::Module;
            row.module = module;
            row.name = module->name();
            row.description = module->description();
            row.icon = module->icon();
            /* The module's own bind leads its subpage: the list only shows it,
             * and a middle click is not something anyone discovers. */
            row.settings.push_back(SettingRow{});
            for (const std::unique_ptr<modules::Setting>& setting : module->settings()) {
                SettingRow settingRow;
                settingRow.setting = setting.get();
                settingRow.knob = setting->normalised();
                settingRow.hue = hueOf(setting->colour());
                row.settings.push_back(settingRow);
            }
            tab.rows.push_back(rows_.size());
            rows_.push_back(std::move(row));
        }
        tabs_.push_back(std::move(tab));
    }

    /* The gear: the client's own settings, the config manager and the four
     * things the menu can do to the client itself. */
    Tab settings;
    settings.key = "Settings";
    settings.label = "Settings";
    settings.icon = assets::IconAsset::Settings2;
    settings.countNoun = "OPTIONS";
    settings.iconOnly = true;

    const auto addRow = [&](Row row) {
        settings.rows.push_back(rows_.size());
        rows_.push_back(std::move(row));
    };

    {
        Row row;
        row.kind = RowKind::Configs;
        row.name = "Configs";
        row.description = "Create, load and delete your configs.";
        row.icon = assets::IconAsset::Box;
        addRow(std::move(row));
    }

    if (modules::Module* self = modules::modules().find("ClickGUI"); self != nullptr) {
        if (modules::Setting* accentSetting = self->setting("Accent"); accentSetting != nullptr) {
            Row row;
            row.kind = RowKind::Colour;
            row.setting = accentSetting;
            row.name = "Accent";
            row.description = "Pick the colour the whole client follows.";
            row.icon = assets::IconAsset::Palette;
            SettingRow editor;
            editor.setting = accentSetting;
            editor.hue = hueOf(accentSetting->colour());
            editor.pickerOpen = true;
            editor.picker = 1.0f;
            row.settings.push_back(editor);
            addRow(std::move(row));
        }
        if (modules::Setting* blurSetting = self->setting("Backdrop blur"); blurSetting != nullptr) {
            Row row;
            row.kind = RowKind::Flag;
            row.setting = blurSetting;
            row.name = "Backdrop blur";
            row.description = "Frost the scene behind the menu.";
            row.icon = assets::IconAsset::Layers;
            addRow(std::move(row));
        }
    }

    struct ActionSpec {
        int action;
        const char* name;
        const char* description;
        assets::IconAsset icon;
    };
    const ActionSpec specs[] = {
        {kActionReloadScripts, "Reload scripts", "Re-read every Lua script from disk.",
         assets::IconAsset::Braces},
        {kActionReloadConfigs, "Reload configs", "Rescan the config folder.",
         assets::IconAsset::RotateCcw},
        {kActionOpenFolder, "Open folder", "Show the client's data folder.",
         assets::IconAsset::Globe},
        {kActionUnload, "Unload", "Detach the client from the game.", assets::IconAsset::X},
    };
    for (const ActionSpec& spec : specs) {
        Row row;
        row.kind = RowKind::Action;
        row.action = spec.action;
        row.name = spec.name;
        row.description = spec.description;
        row.icon = spec.icon;
        addRow(std::move(row));
    }
    tabs_.push_back(std::move(settings));

    tab_ = 0;
    if (!pendingTab_.empty()) {
        for (std::size_t index = 0; index < tabs_.size(); ++index) {
            if (tabs_[index].key == pendingTab_) {
                tab_ = index;
                break;
            }
        }
        pendingTab_.clear();
    }
    subpageRow_ = kNoRow;
    subpageAnim_ = 0.0f;
    /* A rebuild is not a navigation: the displayed tab catches up at once so the
     * list does not fade itself out because the row vector was replaced. */
    shownTab_ = tab_;
    pageLeaving_ = false;
    pageFade_ = 1.0f;
    indicator_ = static_cast<float>(tab_);
    indicatorAlpha_ = tabs_[tab_].iconOnly ? 0.0f : 1.0f;

    refreshConfigs();
    built_ = true;
    COLD_INFO(kScope, "%zu rows across %zu tabs", rows_.size(), tabs_.size());
}

void ClickGui::reset() {
    /* A rebuild must not lose which tab was open: script modules arriving late
     * would otherwise throw the menu back to the first category. */
    if (!tabs_.empty() && tab_ < tabs_.size()) {
        pendingTab_ = tabs_[tab_].key;
    }
    releaseAll();
    stopListening();
    if (bindRow_ != nullptr) {
        bindRow_->binding = false;
        bindRow_ = nullptr;
    }
    editingName_ = false;
    subpageRow_ = kNoRow;
    built_ = false;
    rows_.clear();
    tabs_.clear();
}

void ClickGui::open() {
    if (open_ && !closing_) {
        return;
    }
    build();
    open_ = true;
    closing_ = false;
    slide_ = 0.0f;
    scroll_ = 0.0f;
    /* Every open unrolls its list; the first one of the session also gets the
     * capsule. Claimed here rather than when it finishes, so a menu opened and
     * shut inside a second does not queue the reveal up for next time. */
    pageEnter_ = 0.0f;
    if (!revealSpent_) {
        revealSpent_ = true;
        revealing_ = true;
        revealClock_ = 0.0f;
    }
    refreshConfigs();
    hooks::input().setCapturing(true);
}

void ClickGui::close() {
    if (!open_ || closing_) {
        return;
    }
    closing_ = true;
    revealing_ = false;  // shutting the menu mid-reveal abandons it; it stays spent
    releaseAll();
    stopListening();
    if (bindRow_ != nullptr) {
        bindRow_->binding = false;
        bindRow_ = nullptr;
    }
    editingName_ = false;
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
    activeSlider_ = nullptr;
    activePicker_ = nullptr;
    pickerAxis_ = -1;
    for (Row& row : rows_) {
        for (SettingRow& setting : row.settings) {
            setting.draggingSlider = false;
        }
    }
}

void ClickGui::stopListening() {
    if (keyListener_ != nullptr) {
        keyListener_->listeningForKey = false;
        keyListener_ = nullptr;
    }
}

void ClickGui::closePickers() {
    for (Row& row : rows_) {
        if (row.kind == RowKind::Colour) {
            continue;  // the accent editor is the whole subpage; it stays open
        }
        for (SettingRow& setting : row.settings) {
            setting.pickerOpen = false;
        }
    }
    activePicker_ = nullptr;
    pickerAxis_ = -1;
}

bool ClickGui::pickerOpenAnywhere() const {
    for (const Row& row : rows_) {
        if (row.kind == RowKind::Colour) {
            continue;
        }
        for (const SettingRow& setting : row.settings) {
            if (setting.pickerOpen) return true;
        }
    }
    return false;
}

ClickGui::Row* ClickGui::findRow(std::string_view moduleName) {
    for (Row& row : rows_) {
        if (row.module != nullptr && row.module->name() == moduleName) {
            return &row;
        }
    }
    return nullptr;
}

ClickGui::Row* ClickGui::subpage() {
    return subpageRow_ < rows_.size() ? &rows_[subpageRow_] : nullptr;
}

void ClickGui::selectTab(std::size_t index) {
    if (index >= tabs_.size()) {
        return;
    }
    if (index == tab_) {
        /* Re-picking the active category is a second way out of a subpage. Worth
         * keeping: it is the same gesture as the back chevron and people find it by
         * accident. There is no page to hand over to, so nothing animates. */
        if (subpageOpen()) {
            closeSubpage();
        }
        return;
    }
    /* The selection moves now - the chip has to leave under the click or the menu
     * feels unresponsive - while the list below is only told to start leaving. The
     * swap itself happens in layout() once the old page has faded out. */
    tab_ = index;
    closeSubpage();
    pageLeaving_ = true;
}

void ClickGui::openSubpage(std::size_t rowIndex) {
    if (rowIndex >= rows_.size()) {
        return;
    }
    subpageRow_ = rowIndex;
    subpageAnim_ = 0.0f;
    scroll_ = 0.0f;
    /* subpageAnim_ already carries the sideways slide, so the swap stays immediate
     * here; restarting the cascade is what makes the settings unroll downward into
     * it rather than arriving as one block. */
    pageEnter_ = 0.0f;
}

void ClickGui::closeSubpage() {
    const bool wasOpen = subpageRow_ != kNoRow;
    subpageRow_ = kNoRow;
    stopListening();
    closePickers();
    editingName_ = false;
    scroll_ = 0.0f;
    if (wasOpen) {
        pageEnter_ = 0.0f;  // the list is coming back; unroll it again
    }
}

/* ------------------------------------------------------------------------- *
 * Scripted access
 * ------------------------------------------------------------------------- */

bool ClickGui::setExpanded(std::string_view moduleName, bool expanded) {
    build();
    Row* row = findRow(moduleName);
    if (row == nullptr) {
        return false;
    }
    const std::size_t index = static_cast<std::size_t>(row - rows_.data());
    if (!expanded) {
        if (subpageRow_ == index) {
            closeSubpage();
        }
        return true;
    }
    for (std::size_t tab = 0; tab < tabs_.size(); ++tab) {
        const std::vector<std::size_t>& list = tabs_[tab].rows;
        if (std::find(list.begin(), list.end(), index) != list.end()) {
            /* Scripted, so it jumps rather than animating a handover nobody asked
             * for; the subpage it is about to open draws from the row, not the tab. */
            tab_ = tab;
            shownTab_ = tab;
            pageLeaving_ = false;
            pageFade_ = 1.0f;
            break;
        }
    }
    openSubpage(index);
    return true;
}

bool ClickGui::showScreen(std::string_view name) {
    build();
    const std::string needle = lowerCase(name);
    for (std::size_t index = 0; index < tabs_.size(); ++index) {
        if (lowerCase(tabs_[index].label) == needle) {
            selectTab(index);
            return true;
        }
    }
    /* Names the old sidebar carried and the suite still probes. */
    if (needle == "search" || needle == "configs" || needle == "clientactions") {
        return true;
    }
    return false;
}

bool ClickGui::setPickerOpen(std::string_view moduleName, bool open) {
    build();
    Row* row = findRow(moduleName);
    if (row == nullptr) {
        return false;
    }
    for (SettingRow& setting : row->settings) {
        if (setting.setting == nullptr ||
            setting.setting->type() != modules::Setting::Type::Colour) {
            continue;
        }
        if (open) {
            setExpanded(moduleName, true);
        }
        setting.pickerOpen = open;
        setting.hue = hueOf(setting.setting->colour());
        return true;
    }
    return false;
}

/* ------------------------------------------------------------------------- *
 * Persisted state
 * ------------------------------------------------------------------------- */

void ClickGui::loadLayout(config::Document& config) {
    if (!config.hasSection("gui.menu")) {
        return;
    }
    const std::string wanted = config.get("gui.menu", "tab", "");
    if (wanted.empty()) {
        return;
    }
    if (tabs_.empty()) {
        pendingTab_ = wanted;
        return;
    }
    for (std::size_t index = 0; index < tabs_.size(); ++index) {
        if (tabs_[index].key == wanted) {
            /* Restoring a saved selection, not navigating to it: no handover. */
            tab_ = index;
            shownTab_ = index;
            pageLeaving_ = false;
            pageFade_ = 1.0f;
            indicator_ = static_cast<float>(index);
            closeSubpage();
            break;
        }
    }
}

void ClickGui::saveLayout(config::Document& config) const {
    if (tabs_.empty() || tab_ >= tabs_.size()) {
        return;
    }
    config.set("gui.menu", "tab", tabs_[tab_].key);
}

bool ClickGui::contains(float x, float y) const {
    return bar_.contains(x, y) || card_.contains(x, y);
}

/* ------------------------------------------------------------------------- *
 * Configs
 * ------------------------------------------------------------------------- */

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
    const std::wstring path = client().directory() + L"\\cobble-" + widenAscii(name) + L".cfg";
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
    const std::wstring path = client().directory() + L"\\cobble-" + widenAscii(name) + L".cfg";
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
    const std::wstring path = client().directory() + L"\\cobble-" + widenAscii(name) + L".cfg";
    const bool ok = DeleteFileW(path.c_str()) != FALSE;
    if (ok) {
        COLD_INFO(kScope, "config %s deleted", name.c_str());
        refreshConfigs();
    }
    return ok;
}

void ClickGui::runAction(int action, modules::Context& context) {
    switch (action) {
        case kActionReloadScripts:
            script::manager().requestReload({});
            COLD_INFO(kScope, "scripts reloading");
            break;
        case kActionReloadConfigs:
            refreshConfigs();
            break;
        case kActionOpenFolder:
            ShellExecuteW(nullptr, L"open", client().directory().c_str(), nullptr, nullptr,
                          SW_SHOWNORMAL);
            break;
        case kActionUnload:
            if (auto* self = modules::modules().get<modules::ClickGuiModule>(); self != nullptr) {
                self->setEnabled(false, &context);
            }
            client().requestShutdown();
            break;
        default:
            break;
    }
}

/* ------------------------------------------------------------------------- *
 * Input
 * ------------------------------------------------------------------------- */

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
            break;

        case hooks::InputEvent::Kind::MouseButton:
            mouseX_ = static_cast<float>(event.x) * scaleX;
            mouseY_ = static_cast<float>(event.y) * scaleY;
            if (event.action == hooks::kPress) {
                /* Nothing is laid out under the pointer while the menu is still
                 * assembling itself, and a row that has not arrived must not be
                 * clickable. Swallowed rather than passed through: the cursor is up
                 * and the mouse ungrabbed, so the game is the wrong place for it. */
                if (closing_ || revealing_) {
                    break;
                }
                mouseDown_ = event.code == hooks::kMouseLeft;
                pressAt(mouseX_, mouseY_, event.code, context);
            } else {
                mouseDown_ = false;
                if (activeSlider_ != nullptr) {
                    activeSlider_->draggingSlider = false;
                    activeSlider_ = nullptr;
                }
                activePicker_ = nullptr;
                pickerAxis_ = -1;
            }
            break;

        case hooks::InputEvent::Kind::Scroll:
            /* The card clips rather than scrolls in the design, which only
             * works while everything fits. A long settings page on a short
             * screen has to be reachable. */
            if (content_ > body_.height) {
                scroll_ = clamp(scroll_ - static_cast<float>(event.y) * s(48.0f), 0.0f,
                                content_ - body_.height);
            }
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
                } else if (event.code == hooks::kKeyEnter && !configName_.empty()) {
                    configStatus_ = saveNamedConfig(configName_) ? "Saved" : "Could not save";
                    editingName_ = false;
                }
                break;
            }
            if (keyListener_ != nullptr) {
                keyListener_->setting->setKey(event.code == hooks::kKeyEscape ? 0 : event.code);
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
                if (subpageOpen()) {
                    closeSubpage();
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

bool ClickGui::pressAt(float x, float y, int button, modules::Context& context) {
    stopListening();

    if (!contains(x, y)) {
        editingName_ = false;
        return false;
    }

    if (bar_.contains(x, y)) {
        if (button != hooks::kMouseLeft) {
            return true;
        }
        for (std::size_t index = 0; index < tabs_.size(); ++index) {
            const Tab& tab = tabs_[index];
            if (tab.bounds.width > 0.0f && tab.bounds.contains(x, y)) {
                selectTab(index);
                return true;
            }
        }
        return true;
    }

    /* The back chevron sits in the card header, above the clipped body. */
    if (subpageOpen()) {
        const Rect back{card_.x + s(8.0f), card_.y + s(4.0f), s(34.0f),
                        s(kHeaderHeight) - s(8.0f)};
        if (back.contains(x, y)) {
            if (button == hooks::kMouseLeft || button == hooks::kMouseRight) {
                closeSubpage();
            }
            return true;
        }
    }

    if (!body_.contains(x, y)) {
        editingName_ = false;
        return true;
    }

    if (Row* row = subpage(); row != nullptr) {
        if (row->kind == RowKind::Configs) {
            pressConfigs(*row, x, y, button);
            return true;
        }
        for (SettingRow& setting : row->settings) {
            if (setting.bounds.width <= 0.0f || !setting.bounds.contains(x, y)) {
                continue;
            }
            pressSetting(setting, x, y, button);
            return true;
        }
        return true;
    }

    /* The displayed tab, not the selected one: these are the rows the last layout
     * actually placed, and the rectangles being tested belong to them. */
    if (shownTab_ >= tabs_.size()) {
        return true;
    }
    for (std::size_t index : tabs_[shownTab_].rows) {
        Row& row = rows_[index];
        if (row.bounds.width > 0.0f && row.bounds.contains(x, y)) {
            return pressRow(index, x, y, button, context);
        }
    }
    return true;
}

bool ClickGui::pressRow(std::size_t index, float x, float y, int button,
                        modules::Context& context) {
    Row& row = rows_[index];
    const bool onChevron = row.chevron.width > 0.0f && row.chevron.contains(x, y);

    if (row.kind == RowKind::Action) {
        if (button == hooks::kMouseLeft) {
            runAction(row.action, context);
        }
        return true;
    }

    if (row.kind == RowKind::Module && button == hooks::kMouseMiddle) {
        if (bindRow_ != nullptr) {
            bindRow_->binding = false;
        }
        bindRow_ = &row;
        row.binding = true;
        return true;
    }

    const bool wantsSubpage =
        row.kind == RowKind::Colour || row.kind == RowKind::Configs ||
        ((onChevron || button == hooks::kMouseRight) && row.kind == RowKind::Module);
    if (wantsSubpage) {
        if (button == hooks::kMouseLeft || button == hooks::kMouseRight) {
            openSubpage(index);
        }
        return true;
    }

    if (button != hooks::kMouseLeft) {
        return true;
    }
    if (row.kind == RowKind::Module) {
        row.module->toggle(&context);
    } else if (row.kind == RowKind::Flag && row.setting != nullptr) {
        row.setting->setBoolean(!row.setting->booleanValue());
    }
    return true;
}

bool ClickGui::pressSetting(SettingRow& setting, float x, float y, int button) {
    if (setting.setting == nullptr) {
        /* The module's own bind. */
        if (button == hooks::kMouseLeft) {
            Row* row = subpage();
            if (row != nullptr && row->module != nullptr) {
                if (bindRow_ != nullptr) {
                    bindRow_->binding = false;
                }
                bindRow_ = row;
                row->binding = true;
            }
        } else if (button == hooks::kMouseRight) {
            Row* row = subpage();
            if (row != nullptr && row->module != nullptr) {
                row->module->setKey(0);
            }
        }
        return true;
    }

    modules::Setting& value = *setting.setting;
    switch (value.type()) {
        case modules::Setting::Type::Boolean:
            if (button == hooks::kMouseLeft) {
                value.setBoolean(!value.booleanValue());
            }
            break;

        case modules::Setting::Type::Number:
            if (button == hooks::kMouseLeft && setting.control.width > 0.0f) {
                setting.draggingSlider = true;
                activeSlider_ = &setting;
                value.setNormalised((x - setting.control.x) / setting.control.width);
            }
            break;

        case modules::Setting::Type::Choice: {
            const float localY = y - setting.bounds.y;
            if (localY < s(kSettingHeight)) {
                if (button == hooks::kMouseLeft || button == hooks::kMouseRight) {
                    setting.expanded = !setting.expanded;
                }
            } else if (setting.expand > 0.3f) {
                const int item =
                    static_cast<int>((localY - s(kSettingHeight)) / s(kChoiceItemHeight));
                if (item >= 0 && item < static_cast<int>(value.choices().size())) {
                    value.setChoice(item);
                }
            }
            break;
        }

        case modules::Setting::Type::Key:
            if (button == hooks::kMouseLeft) {
                setting.listeningForKey = true;
                keyListener_ = &setting;
            } else if (button == hooks::kMouseRight) {
                value.setKey(0);
            }
            break;

        case modules::Setting::Type::Colour: {
            const Rect editor{setting.bounds.x, setting.bounds.y + s(kSettingHeight),
                              setting.bounds.width, s(kEditorHeight)};
            if (setting.pickerOpen && setting.picker > 0.5f && editor.contains(x, y)) {
                const Rect field{editor.x + s(kSettingPadX), editor.y + s(kEditorGap),
                                 editor.width - s(kSettingPadX) * 2.0f, s(kEditorField)};
                activePicker_ = &setting;
                pickerAxis_ = y < field.bottom() + s(kEditorGap) * 0.5f ? 0 : 1;
                break;
            }
            if (button == hooks::kMouseLeft || button == hooks::kMouseRight) {
                setting.pickerOpen = !setting.pickerOpen;
                if (setting.pickerOpen) {
                    setting.hue = hueOf(value.colour());
                }
            }
            break;
        }
    }
    return true;
}

bool ClickGui::pressConfigs(Row& row, float x, float y, int button) {
    (void)row;
    if (button != hooks::kMouseLeft) {
        return false;
    }
    if (nameField_.width > 0.0f && nameField_.contains(x, y)) {
        editingName_ = true;
        return true;
    }
    if (saveButton_.width > 0.0f && saveButton_.contains(x, y)) {
        editingName_ = false;
        configStatus_ = configName_.empty()
                            ? "Name it first"
                            : (saveNamedConfig(configName_) ? "Saved" : "Could not save");
        return true;
    }
    for (std::size_t i = 0; i < configs_.size(); ++i) {
        ConfigFile& entry = configs_[i];
        if (entry.remove.width > 0.0f && entry.remove.contains(x, y)) {
            editingName_ = false;
            configStatus_ = deleteNamedConfig(entry.name) ? "Deleted" : "Could not delete";
            return true;
        }
        if (entry.bounds.width > 0.0f && entry.bounds.contains(x, y)) {
            editingName_ = false;
            selectedConfig_ = static_cast<int>(i);
            configName_ = entry.name;
            configStatus_ = loadNamedConfig(entry.name) ? "Loaded" : "Could not load";
            return true;
        }
    }
    editingName_ = false;
    return true;
}

/* ------------------------------------------------------------------------- *
 * Layout
 * ------------------------------------------------------------------------- */

float ClickGui::settingHeight(const SettingRow& row) const {
    float height = s(kSettingHeight);
    if (row.setting == nullptr) {
        return height;
    }
    if (row.setting->type() == modules::Setting::Type::Choice) {
        height += static_cast<float>(row.setting->choices().size()) * s(kChoiceItemHeight) *
                  row.expand;
    } else if (row.setting->type() == modules::Setting::Type::Colour) {
        height += s(kEditorHeight) * row.picker;
    }
    return height;
}

float ClickGui::listHeight(const Tab& tab) const {
    return static_cast<float>(tab.rows.size()) * (s(kRowHeight) + s(kRowGap)) + s(kRowGap);
}

float ClickGui::subpageHeight(Row& row) {
    if (row.kind == RowKind::Configs) {
        return configsBodyHeight();
    }
    float height = s(kRowGap);
    for (const SettingRow& setting : row.settings) {
        if (setting.setting != nullptr && !setting.setting->visible()) {
            continue;
        }
        height += settingHeight(setting) + s(kRowGap);
    }
    return height;
}

float ClickGui::configsBodyHeight() const {
    return s(kRowGap) + s(kSettingHeight) + s(kRowGap) +
           static_cast<float>(configs_.size()) * (s(kSettingHeight) + s(kRowGap)) +
           s(kSettingHeight);
}

void ClickGui::layout(modules::Context& context) {
    const game::FrameState& state = *context.state;
    const float deltaTime = context.deltaTime;
    time_ = context.time;

    const float factor = state.scaledWidth > 0 ? static_cast<float>(state.windowWidth) /
                                                     static_cast<float>(state.scaledWidth)
                                               : 2.0f;
    unit_ = std::max(factor, 0.5f);
    /* The design is drawn at one device pixel per design pixel when Minecraft
     * is at GUI scale 2, which is where its own interface is 1:1 with it. */
    scale_ = clamp(unit_ * 0.5f, 0.7f, 2.2f);

    screenW_ = static_cast<float>(state.windowWidth);
    screenH_ = static_cast<float>(state.windowHeight);

    slide_ = approach(slide_, open_ && !closing_ ? 1.0f : 0.0f, kSlideSpeed, deltaTime);
    if (closing_ && slide_ < 0.02f) {
        open_ = false;
        closing_ = false;
        slide_ = 0.0f;
        hooks::input().setCapturing(false);
    }
    alpha_ = clamp(ease::outCubic(clamp(slide_, 0.0f, 1.0f)), 0.0f, 1.0f);
    if (revealing_) {
        /* The reveal owns every fade and every movement while it runs, so the
         * ordinary open is held at "fully arrived" underneath it. Two openings
         * animating at once would fight over the same pixels. */
        slide_ = 1.0f;
        alpha_ = 1.0f;
    }

    /* Page handover. The old list leaves, the swap happens at zero opacity, then
     * the new one arrives with its rows staggered. Deferring the swap is the whole
     * point: committing on the click, as this did, means there is no moment at
     * which anything can be cross-faded and the change can only ever be a cut. */
    if (pageLeaving_) {
        pageFade_ = std::max(0.0f, pageFade_ - deltaTime / kPageOutSeconds);
        if (pageFade_ <= 0.0f) {
            shownTab_ = tab_;
            pageLeaving_ = false;
            pageEnter_ = 0.0f;
            scroll_ = 0.0f;
        }
    } else {
        pageFade_ = std::min(1.0f, pageFade_ + deltaTime / kPageInSeconds);
    }
    /* Held at the start of its cascade until the card has begun to open, then
     * released so the rows are arriving *while* it unfolds rather than after it.
     * Waiting for the reveal to finish leaves the card hanging open and empty for a
     * beat, and the card clips to its own growing edge anyway, so a row that lands
     * below that edge is hidden until the card reaches it - the two movements cover
     * for each other instead of queueing. */
    if (!revealing_ || revealClock_ >= kRevealUnfoldStart + 0.10f) {
        pageEnter_ = std::min(1.0f, pageEnter_ + deltaTime / kPageCascadeSeconds);
    }

    if (tabs_.empty()) {
        bar_ = Rect{};
        card_ = Rect{};
        body_ = Rect{};
        return;
    }
    if (tab_ >= tabs_.size()) {
        tab_ = 0;
    }
    if (shownTab_ >= tabs_.size()) {
        shownTab_ = tab_;
    }

    const float width = std::min(s(kMenuWidth), screenW_ - s(32.0f));
    /* A window too narrow to hold the card at all, or a frame captured mid
     * resize with no size yet. Laying out to a negative width would hand the
     * batcher inverted quads. */
    if (width <= s(120.0f) || screenH_ <= s(120.0f)) {
        bar_ = Rect{};
        card_ = Rect{};
        body_ = Rect{};
        return;
    }
    const float x = std::floor((screenW_ - width) * 0.5f);
    /* No rise during the reveal: the capsule's spring is the arrival, and sliding
     * the whole menu down underneath it would read as two arrivals. */
    const float rise = revealing_ ? 0.0f : (1.0f - alpha_) * s(10.0f);
    const float y = std::floor(s(kTopMargin) - rise);

    bar_ = Rect{x, y, width, s(kTabBarHeight)};

    /* Tab slots: the named ones split what the gear leaves. */
    std::size_t named = 0;
    for (const Tab& tab : tabs_) {
        if (!tab.iconOnly) ++named;
    }
    const float inset = s(4.0f);
    const float gearWidth = s(kGearSlotWidth);
    const float slotWidth =
        named > 0 ? (width - inset * 2.0f - gearWidth) / static_cast<float>(named) : 0.0f;

    std::size_t slot = 0;
    for (std::size_t index = 0; index < tabs_.size(); ++index) {
        Tab& tab = tabs_[index];
        if (tab.iconOnly) {
            tab.bounds = Rect{bar_.right() - inset - gearWidth, bar_.y + s(3.0f), gearWidth,
                              bar_.height - s(6.0f)};
            gearSlot_ = tab.bounds;
        } else {
            tab.bounds = Rect{bar_.x + inset + static_cast<float>(slot) * slotWidth,
                              bar_.y + s(3.0f), slotWidth, bar_.height - s(6.0f)};
            if (index == tab_) {
                indicator_ = approach(indicator_, static_cast<float>(slot), kTabSpeed, deltaTime);
            }
            ++slot;
        }
        const bool hovered = pointable() && tab.bounds.contains(mouseX_, mouseY_);
        if (tab.iconOnly) {
            gearHover_ = approach(gearHover_, hovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
        }
        tab.hover = approach(tab.hover, hovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
    }
    indicatorAlpha_ =
        approach(indicatorAlpha_, tabs_[tab_].iconOnly ? 0.0f : 1.0f, 18.0f, deltaTime);

    Row* page = subpage();
    subpageAnim_ = approach(subpageAnim_, page != nullptr ? 1.0f : 0.0f, kSubpageSpeed, deltaTime);
    backHover_ = approach(backHover_,
                          pointable() && page != nullptr &&
                                  Rect{card_.x + s(8.0f), card_.y + s(4.0f), s(34.0f),
                                       s(kHeaderHeight) - s(8.0f)}
                                      .contains(mouseX_, mouseY_)
                              ? 1.0f
                              : 0.0f,
                          kHoverSpeed, deltaTime);

    /* Animations run for every row, not only the visible ones: a toggle driven
     * from a script or a bind must not snap when its tab comes back. */
    for (Row& row : rows_) {
        float target = 0.0f;
        if (row.kind == RowKind::Module && row.module != nullptr) {
            target = row.module->enabled() ? 1.0f : 0.0f;
        } else if (row.kind == RowKind::Flag && row.setting != nullptr) {
            target = row.setting->booleanValue() ? 1.0f : 0.0f;
        }
        row.on = approach(row.on, target, kOnSpeed, deltaTime);
    }

    content_ = page != nullptr ? subpageHeight(*page) : listHeight(tabs_[shownTab_]);

    const float cardY = bar_.bottom() + s(kPanelGap);
    const float header = s(kHeaderHeight);
    const float maxHeight = std::max(header + s(40.0f), screenH_ - cardY - s(24.0f));
    const float wanted = header + content_ + s(8.0f);
    const float height = std::min(maxHeight, std::max(header + s(40.0f), wanted));

    /* Smoothed, so handing over from a three-row category to an eleven-row one
     * grows the card into its new size instead of snapping it. Snapped instead on
     * the first laid-out frame and whenever the menu is not up, so opening never
     * starts by animating the card out of whatever the last session left. */
    if (cardHeight_ <= 0.0f || !visible()) {
        cardHeight_ = height;
    } else {
        cardHeight_ = approach(cardHeight_, height, kCardSpeed, deltaTime);
    }

    card_ = Rect{x, cardY, width, cardHeight_};
    body_ = Rect{card_.x + s(kCardPadX), card_.y + header + s(4.0f),
                 card_.width - s(kCardPadX) * 2.0f,
                 std::max(s(20.0f), card_.height - header - s(6.0f))};

    scroll_ = clamp(scroll_, 0.0f, std::max(0.0f, content_ - body_.height));

    if (page != nullptr) {
        layoutSubpage(*page, deltaTime);
    } else {
        layoutList(tabs_[shownTab_], deltaTime);
    }

    /* Last, because the reveal confines the paint to a fraction of the geometry
     * the rest of this function has just finished producing. */
    advanceReveal(deltaTime);

    if (activeSlider_ != nullptr && mouseDown_ && activeSlider_->control.width > 0.0f) {
        const Rect& track = activeSlider_->control;
        activeSlider_->setting->setNormalised((mouseX_ - track.x) / track.width);
    }

    if (activePicker_ != nullptr && mouseDown_ && activePicker_->setting != nullptr) {
        SettingRow& settingRow = *activePicker_;
        const Rect editor{settingRow.bounds.x, settingRow.bounds.y + s(kSettingHeight),
                          settingRow.bounds.width, s(kEditorHeight)};
        const Rect field{editor.x + s(kSettingPadX), editor.y + s(kEditorGap),
                         editor.width - s(kSettingPadX) * 2.0f, s(kEditorField)};
        const Color current = settingRow.setting->colour();
        if (pickerAxis_ == 0) {
            const float saturation =
                clamp((mouseX_ - field.x) / std::max(field.width, 1.0f), 0.0f, 1.0f);
            const float value =
                1.0f - clamp((mouseY_ - field.y) / std::max(field.height, 1.0f), 0.0f, 1.0f);
            settingRow.setting->setColour(
                Color::hsv(settingRow.hue, saturation, value, current.a));
        } else {
            const Rect strip{field.x, field.bottom() + s(kEditorGap), field.width,
                             s(kEditorStrip)};
            settingRow.hue = clamp((mouseX_ - strip.x) / std::max(strip.width, 1.0f), 0.0f, 1.0f);
            const float maximum = std::max({current.r, current.g, current.b});
            const float minimum = std::min({current.r, current.g, current.b});
            const float saturation = maximum <= 0.0f ? 1.0f : (maximum - minimum) / maximum;
            settingRow.setting->setColour(Color::hsv(settingRow.hue, saturation,
                                                     maximum <= 0.0f ? 1.0f : maximum, current.a));
        }
    }
}

void ClickGui::layoutList(Tab& tab, float deltaTime) {
    float top = body_.y + s(kRowGap) - scroll_;
    std::size_t ordinal = 0;
    for (std::size_t index : tab.rows) {
        Row& row = rows_[index];

        /* Each row drops the last few pixels into its slot, on a delay that grows
         * down the list, so picking a category unrolls it downward. The offset goes
         * on the row's own rectangle - which is the hit target too, so the pointer
         * never lands somewhere the row is not - but never on `top`, because layout
         * has to stay put or the whole list would visibly resettle as the cascade
         * finishes. */
        row.enter = entranceAt(ordinal++);
        const float drop = (1.0f - row.enter) * s(kRowRise);
        row.bounds = Rect{body_.x, top - drop, body_.width, s(kRowHeight)};

        const bool hasPill = row.kind == RowKind::Module || row.kind == RowKind::Flag;
        const float chevronCentre =
            row.bounds.right() -
            (hasPill ? s(kPillMargin + kPillWidth + kChevronBox) : s(22.0f));
        row.chevron = row.kind == RowKind::Flag
                          ? Rect{}
                          : Rect{chevronCentre - s(14.0f), row.bounds.y, s(28.0f),
                                 row.bounds.height};

        const bool hovered = pointable() && body_.contains(mouseX_, mouseY_) &&
                             row.bounds.contains(mouseX_, mouseY_);
        row.hover = approach(row.hover, hovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
        top += s(kRowHeight) + s(kRowGap);
    }
}

void ClickGui::layoutSubpage(Row& row, float deltaTime) {
    const float slide = s(10.0f) * (1.0f - subpageAnim_);
    float top = body_.y + s(kRowGap) - scroll_;

    if (row.kind == RowKind::Configs) {
        layoutConfigs(row, top, deltaTime);
        return;
    }

    std::size_t ordinal = 0;
    for (SettingRow& setting : row.settings) {
        if (setting.setting != nullptr && !setting.setting->visible()) {
            setting.bounds = Rect{};
            setting.control = Rect{};
            continue;
        }
        setting.enter = entranceAt(ordinal++);
        setting.expand = approach(setting.expand, setting.expanded ? 1.0f : 0.0f, kExpandSpeed,
                                  deltaTime);
        setting.picker =
            approach(setting.picker, setting.pickerOpen ? 1.0f : 0.0f, 16.0f, deltaTime);
        if (setting.setting != nullptr &&
            setting.setting->type() == modules::Setting::Type::Number) {
            setting.knob = approach(setting.knob, setting.setting->normalised(), 20.0f, deltaTime);
        }

        const float height = settingHeight(setting);
        const float drop = (1.0f - setting.enter) * s(kRowRise);
        setting.bounds = Rect{body_.x + slide, top - drop, body_.width - slide, height};

        const bool hovered = pointable() && body_.contains(mouseX_, mouseY_) &&
                             setting.bounds.contains(mouseX_, mouseY_);
        setting.hover = approach(setting.hover, hovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);

        if (setting.setting != nullptr &&
            setting.setting->type() == modules::Setting::Type::Number) {
            setting.control = Rect{setting.bounds.x + s(kSettingPadX),
                                   setting.bounds.y + s(kSettingHeight) - s(11.0f),
                                   setting.bounds.width - s(kSettingPadX) * 2.0f, s(kSliderTrack)};
        } else {
            setting.control = setting.bounds;
        }
        top += height + s(kRowGap);
    }
}

void ClickGui::layoutConfigs(Row& row, float top, float deltaTime) {
    (void)row;
    const float slide = s(10.0f) * (1.0f - subpageAnim_);
    const float left = body_.x + slide;
    const float width = body_.width - slide;
    const float buttonWidth = s(84.0f);

    nameField_ = Rect{left, top, width - buttonWidth - s(kRowGap), s(kSettingHeight)};
    saveButton_ = Rect{nameField_.right() + s(kRowGap), top, buttonWidth, s(kSettingHeight)};
    const bool nameHovered = pointable() && nameField_.contains(mouseX_, mouseY_);
    const bool saveHovered = pointable() && saveButton_.contains(mouseX_, mouseY_);
    nameHover_ = approach(nameHover_, nameHovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
    saveHover_ = approach(saveHover_, saveHovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
    top += s(kSettingHeight) + s(kRowGap);

    for (ConfigFile& entry : configs_) {
        entry.bounds = Rect{left, top, width, s(kSettingHeight)};
        entry.remove = Rect{entry.bounds.right() - s(kSettingPadX) - s(62.0f),
                            entry.bounds.y + s(7.0f), s(62.0f), s(kSettingHeight) - s(14.0f)};
        const bool hovered = pointable() && body_.contains(mouseX_, mouseY_) &&
                             entry.bounds.contains(mouseX_, mouseY_);
        const bool removeHovered = hovered && entry.remove.contains(mouseX_, mouseY_);
        entry.hover = approach(entry.hover, hovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
        entry.removeHover =
            approach(entry.removeHover, removeHovered ? 1.0f : 0.0f, kHoverSpeed, deltaTime);
        top += s(kSettingHeight) + s(kRowGap);
    }
}

/* ------------------------------------------------------------------------- *
 * Render
 * ------------------------------------------------------------------------- */

void ClickGui::render(modules::Context& context) {
    build();
    layout(context);
    /* bar_ is zero-width when layout bailed out - no tabs yet, or a window too
     * narrow to hold the card. The reveal rectangles are only refreshed on the
     * paths that produce real geometry, so drawing past this point would paint last
     * frame's shapes at a size that no longer exists. */
    if (slide_ <= 0.004f || tabs_.empty() || bar_.width <= 0.0f) {
        return;
    }

    render::Renderer& gfx = render::renderer();

    /* The design puts nothing behind the menu; the card carries its own body.
     * The blur is opt-in for the same reason it always was. */
    const render::Backdrop& backdrop = client().backdrop();
    if (blur_ && backdrop.valid()) {
        const Rect full{0.0f, 0.0f, screenW_, screenH_};
        const Rect uv{0.0f, 1.0f, 1.0f, -1.0f};
        gfx.image(full, uv, backdrop.texture(), Color{1.0f, 1.0f, 1.0f, alpha_}, 0.0f);
        gfx.rect(full, Color::rgb(0x000000, 0.28f * alpha_), 0.0f);
    }

    drawTabBar();
    drawCard();
    renderCursor(gfx);
}

void ClickGui::drawTabBar() {
    render::Renderer& gfx = render::renderer();

    /* The bar is drawn at the reveal's rectangle and radius, which are its own
     * once nothing is being revealed. During the reveal this is the capsule: same
     * fill, same hairline, just narrower and rounder. */
    const Rect frame = reveal_.bar;
    const float radius = reveal_.barRadius;

    if (reveal_.glow > 0.002f) {
        /* Accent halo blooming out of the capsule as it lands, so the pop displaces
         * something instead of merely becoming visible. */
        gfx.glow(frame, theme().accent.withAlpha(0.30f * reveal_.glow), s(22.0f) * reveal_.glow,
                 radius);
    }

    gfx.rect(frame, fade(kTabBarBg), radius);
    gfx.outline(frame, fade(kCardBorder), std::max(1.0f, s(1.0f)), radius);

    if (reveal_.label > 0.002f) {
        drawRevealLabel();
    }

    if (reveal_.barContent <= 0.002f) {
        return;  // still a capsule; it has no tabs yet
    }

    std::size_t named = 0;
    for (const Tab& tab : tabs_) {
        if (!tab.iconOnly) ++named;
    }
    if (named == 0) {
        return;
    }

    /* Everything below is bar content, so it arrives together once the bar has
     * reached its width. */
    const AlphaScope barAlpha{alpha_, reveal_.barContent};
    const float inset = s(4.0f);
    const float gearWidth = s(kGearSlotWidth);
    const float slotWidth = (bar_.width - inset * 2.0f - gearWidth) / static_cast<float>(named);

    /* Chip and underline of the active slot, interpolated so they slide. */
    if (indicatorAlpha_ > 0.001f) {
        const float chipX = bar_.x + inset + indicator_ * slotWidth;
        const Rect chip{chipX, bar_.y + s(3.0f), slotWidth, bar_.height - s(6.0f)};
        gfx.rect(chip, fade(kTabChipBg, indicatorAlpha_), s(9.0f));

        const float underlineWidth = slotWidth * 0.78f;
        const float underlineHeight = s(2.5f);
        gfx.rect(Rect{chip.x + (slotWidth - underlineWidth) * 0.5f,
                      chip.bottom() - underlineHeight, underlineWidth, underlineHeight},
                 accent(indicatorAlpha_), underlineHeight * 0.5f);
    }

    for (std::size_t index = 0; index < tabs_.size(); ++index) {
        const Tab& tab = tabs_[index];
        if (tab.iconOnly) {
            /* The gear stays in the accent, as the design has it. */
            const float box = s(kIconBox);
            const Color colour = Color::mix(theme().accent, Color::rgb(0xFFFFFF),
                                            gearHover_ * 0.35f);
            gfx.icon(tab.icon,
                     Rect{tab.bounds.centerX() - box * 0.5f, tab.bounds.centerY() - box * 0.5f,
                          box, box},
                     fade(colour));
            continue;
        }
        const bool selected = index == tab_;
        const Color colour = selected ? kTextPrimary
                                      : Color::mix(kTextMuted, kTextPrimary, tab.hover * 0.7f);
        drawText(kLabelFont, tab.label, tab.bounds.centerX(),
                 tab.bounds.centerY() - s(kLabelBox) * 0.5f, s(kLabelBox), fade(colour),
                 render::Align::Center);
    }
}

/* The client's name, in the same voice the card header uses for a category:
 * uppercase, tracked, accent. Reusing that treatment rather than inventing a
 * splash style is what keeps the reveal looking like this menu introducing
 * itself instead of a loading screen bolted on in front of it. */
void ClickGui::drawRevealLabel() {
    const std::string brand = upperCase(kClientName);
    const float box = s(kHeadingBox);
    const float tracking = s(kTracking);
    const float width = trackedWidth(kHeadingFont, brand, box, tracking);
    const Rect frame = reveal_.bar;
    drawTracked(kHeadingFont, brand, frame.centerX() - width * 0.5f, frame.centerY() - box * 0.5f,
                box, theme().accent.withAlpha(alpha_ * reveal_.label), tracking);
}

void ClickGui::drawCard() {
    if (!reveal_.cardVisible) {
        return;  // the capsule has not finished becoming the bar yet
    }
    render::Renderer& gfx = render::renderer();

    /* Body drawn at the reveal's rectangle, so while it unfolds the card is a
     * genuinely shorter card - rounded bottom corners and all - rather than a
     * full-height one with its lower half clipped off square. */
    const Rect frame = reveal_.card;
    /* Capped against the frame's own height so the corners cannot exceed the shape
     * they are rounding while it is still short. */
    const float cardRadius = std::min(s(kCardRadius), frame.height * 0.5f);
    gfx.rect(frame, fade(kCardBg), cardRadius);
    gfx.outline(frame, fade(kCardBorder), std::max(1.0f, s(1.0f)), cardRadius);

    /* Contents stay at the settled geometry and are confined to the frame, which
     * is what turns the unfold into a reveal: the header and the rows are already
     * exactly where they belong and the growing card uncovers them top to bottom. */
    const bool unfolding = frame.height < card_.height - 0.5f;
    if (unfolding) {
        gfx.pushClip(frame);
    }

    {
        const AlphaScope cardAlpha{alpha_, reveal_.cardContent};

        Row* page = subpage();
        if (page != nullptr) {
            drawSubpageHeader(*page);
        } else {
            drawCardHeader(tabs_[shownTab_]);
        }

        /* The page fade is the handover between two categories, so it applies to the
         * list and to the header's own naming of it - not to the separator, which is
         * the card's structure and is the same line whichever page is showing. */
        const AlphaScope listAlpha{alpha_, pageFade_};

        gfx.pushClip(body_);
        if (page != nullptr) {
            if (page->kind == RowKind::Configs) {
                drawConfigs(*page);
            } else {
                for (SettingRow& setting : page->settings) {
                    if (setting.bounds.width <= 0.0f) {
                        continue;
                    }
                    drawSetting(*page, setting);
                }
            }
        } else {
            for (std::size_t index : tabs_[shownTab_].rows) {
                drawRow(rows_[index]);
            }
        }
        gfx.popClip();
    }

    if (unfolding) {
        gfx.popClip();
    }
}

void ClickGui::drawCardHeader(const Tab& tab) {
    render::Renderer& gfx = render::renderer();
    const float height = s(kHeaderHeight);
    const float centreY = card_.y + height * 0.5f;
    float cursorX = card_.x + s(16.0f);

    /* Icon, title and count name the category, so they cross-fade with the list
     * they belong to. The separator below them does not. */
    {
        const AlphaScope titleAlpha{alpha_, pageFade_};

        const float iconBox = s(16.0f);
        gfx.icon(tab.icon, Rect{cursorX, centreY - iconBox * 0.5f, iconBox, iconBox}, accent());
        cursorX += iconBox + s(10.0f);

        const std::string title = upperCase(tab.label);
        const float tracking = s(kTracking);
        drawTracked(kHeadingFont, title, cursorX, centreY - s(kHeadingBox) * 0.5f, s(kHeadingBox),
                    accent(), tracking);
        cursorX += trackedWidth(kHeadingFont, title, s(kHeadingBox), tracking) + s(14.0f);

        gfx.line(cursorX, centreY - s(7.0f), cursorX, centreY + s(7.0f), std::max(1.0f, s(1.0f)),
                 fade(kSeparator));
        cursorX += s(14.0f);

        char count[64];
        std::snprintf(count, sizeof(count), "%d %s", static_cast<int>(tab.rows.size()),
                      tab.countNoun != nullptr ? tab.countNoun : "MODULES");
        drawText(kMicroFont, count, cursorX, centreY - s(kMicroBox) * 0.5f, s(kMicroBox),
                 fade(kTextFaint));
    }

    gfx.line(card_.x, card_.y + height, card_.right(), card_.y + height, std::max(1.0f, s(1.0f)),
             fade(kSeparator));
}

void ClickGui::drawSubpageHeader(const Row& row) {
    render::Renderer& gfx = render::renderer();
    const float height = s(kHeaderHeight);
    const float centreY = card_.y + height * 0.5f;

    const Rect back{card_.x + s(8.0f), card_.y + s(4.0f), s(34.0f), height - s(8.0f)};
    if (backHover_ > 0.001f) {
        gfx.rect(back, fade(Color::rgb(0xFFFFFF, 20.0f / 255.0f), backHover_), s(7.0f));
    }
    const float box = s(kChevronBox);
    gfx.icon(assets::IconAsset::ChevronLeft,
             Rect{back.centerX() - box * 0.5f, back.centerY() - box * 0.5f, box, box},
             fade(Color::mix(kTextMuted, kTextPrimary, backHover_)));

    const std::string title = upperCase(row.name);
    drawTracked(kHeadingFont, title, back.right() + s(8.0f), centreY - s(kHeadingBox) * 0.5f,
                s(kHeadingBox), accent(), s(kTracking));

    gfx.line(card_.x, card_.y + height, card_.right(), card_.y + height, std::max(1.0f, s(1.0f)),
             fade(kSeparator));
}

void ClickGui::drawPill(const Rect& bounds, float on) {
    render::Renderer& gfx = render::renderer();
    const float radius = bounds.height * 0.5f;
    if (on > 0.001f) {
        gfx.rect(bounds.inset(-s(2.0f)), accent(0.16f * on), radius + s(2.0f));
    }
    gfx.rect(bounds, fade(Color::mix(kToggleOff, theme().accent, on)), radius);

    const float knobRadius = radius - s(2.5f);
    const float left = bounds.x + s(2.5f) + knobRadius;
    const float right = bounds.right() - s(2.5f) - knobRadius;
    const float centreX = left + (right - left) * on;
    gfx.rect(Rect{centreX - knobRadius, bounds.centerY() - knobRadius, knobRadius * 2.0f,
                  knobRadius * 2.0f},
             fade(Color::mix(kKnobOff, kKnobOn, on)), knobRadius);
}

void ClickGui::drawChip(float x, float centreY, std::string_view text, const Color& background,
                        const Color& textColour) {
    render::Renderer& gfx = render::renderer();
    const float padX = s(6.0f);
    const float box = s(kMicroBox);
    const float chipHeight = box + s(5.0f);
    const float width = textWidth(kMicroFont, text, box) + padX * 2.0f;
    const Rect chip{x, centreY - chipHeight * 0.5f, width, chipHeight};
    gfx.rect(chip, fade(background), s(4.0f));
    drawText(kMicroFont, text, chip.x + padX, chip.centerY() - box * 0.5f, box, fade(textColour));
}

void ClickGui::drawRow(Row& row) {
    render::Renderer& gfx = render::renderer();
    if (row.bounds.width <= 0.0f || row.enter <= 0.002f) {
        return;
    }
    /* Fades with its own arrival, on top of whatever the page is doing. */
    const AlphaScope rowAlpha{alpha_, row.enter};

    const Rect bounds = row.bounds;
    const float height = bounds.height;
    const bool hasPill = row.kind == RowKind::Module || row.kind == RowKind::Flag;

    Color background = Color::mix(kRowBg, kRowBgOn, row.on);
    background = Color::mix(background, kHoverWash, row.hover * 0.6f);
    gfx.rect(bounds, fade(background), s(kRowRadius));

    if (row.on > 0.001f) {
        const float barInset = s(7.0f);
        gfx.rect(Rect{bounds.x, bounds.y + barInset, s(3.0f), height - barInset * 2.0f},
                 accent(row.on), s(1.5f));
    }

    const float iconBox = s(kIconBox);
    gfx.icon(row.icon,
             Rect{bounds.x + s(kRowIconCentre) - iconBox * 0.5f, bounds.centerY() - iconBox * 0.5f,
                  iconBox, iconBox},
             fade(Color::mix(kTextMuted, kIconOn, row.on)));

    const float textX = bounds.x + s(kRowTextX);
    const float nameTop = bounds.y + s(kRowNameTop);
    const float nameEnd =
        drawText(kLabelFont, row.name, textX, nameTop, s(kLabelBox), fade(kTextPrimary));

    if (row.kind == RowKind::Module && row.module != nullptr) {
        drawChip(nameEnd + s(9.0f), nameTop + s(kLabelBox) * 0.5f,
                 bindLabel(row.module->key(), row.binding), kChipBg,
                 row.binding ? theme().accent : kChipText);
    }

    if (!row.description.empty()) {
        float limit = bounds.right() - s(kPillMargin) - textX;
        if (hasPill) limit -= s(kPillWidth);
        if (row.chevron.width > 0.0f) limit -= s(28.0f);
        const std::string shown =
            elideText(kDescFont, row.description, s(kDescBox), std::max(limit, s(30.0f)));
        drawText(kDescFont, shown, textX, bounds.y + s(kRowDescTop), s(kDescBox),
                 fade(kTextMuted));
    }

    if (hasPill) {
        const float pillWidth = s(kPillWidth);
        const float pillHeight = s(kPillHeight);
        drawPill(Rect{bounds.right() - s(kPillMargin) - pillWidth,
                      bounds.centerY() - pillHeight * 0.5f, pillWidth, pillHeight},
                 row.on);
    }

    if (row.chevron.width > 0.0f) {
        const float box = s(kChevronBox);
        gfx.icon(assets::IconAsset::ChevronRight,
                 Rect{row.chevron.centerX() - box * 0.5f, row.chevron.centerY() - box * 0.5f, box,
                      box},
                 fade(Color::mix(kTextFaint, kTextPrimary, row.hover)));
    }
}

void ClickGui::drawSetting(Row& owner, SettingRow& setting) {
    render::Renderer& gfx = render::renderer();
    if (setting.enter <= 0.002f) {
        return;
    }
    const AlphaScope settingAlpha{alpha_, setting.enter};

    const Rect bounds = setting.bounds;
    const Rect line{bounds.x, bounds.y, bounds.width, s(kSettingHeight)};

    Color background = Color::mix(kRowBg, kHoverWash, setting.hover * 0.6f);
    gfx.rect(line, fade(background), s(kRowRadius));

    const float labelBox = s(13.0f);
    const float padX = s(kSettingPadX);
    const float rightEdge = line.right() - padX;

    if (setting.setting == nullptr) {
        /* The module's own bind. */
        drawText(kLabelFont, "Keybind", line.x + padX, line.centerY() - labelBox * 0.5f, labelBox,
                 fade(kTextPrimary));
        const std::string label =
            bindLabel(owner.module != nullptr ? owner.module->key() : 0, owner.binding);
        const float chipWidth = textWidth(kMicroFont, label, s(kMicroBox)) + s(12.0f);
        drawChip(rightEdge - chipWidth, line.centerY(), label, kChipBg,
                 owner.binding ? theme().accent : kChipText);
        return;
    }

    modules::Setting& value = *setting.setting;
    switch (value.type()) {
        case modules::Setting::Type::Boolean: {
            drawText(kLabelFont, value.name(), line.x + padX, line.centerY() - labelBox * 0.5f,
                     labelBox, fade(kTextPrimary));
            const float pillWidth = s(32.0f);
            const float pillHeight = s(17.0f);
            drawPill(Rect{rightEdge - pillWidth, line.centerY() - pillHeight * 0.5f, pillWidth,
                          pillHeight},
                     value.booleanValue() ? 1.0f : 0.0f);
            break;
        }

        case modules::Setting::Type::Number: {
            drawText(kLabelFont, value.name(), line.x + padX, line.y + s(8.0f), labelBox,
                     fade(kTextPrimary));
            drawText(kMicroFont, formatNumber(value), rightEdge, line.y + s(9.5f), s(kMicroBox),
                     fade(kChipText), render::Align::Right);
            const Rect track = setting.control;
            if (track.width > 0.0f) {
                gfx.rect(track, fade(kTrackBg), track.height * 0.5f);
                const float fill = track.width * clamp(setting.knob, 0.0f, 1.0f);
                if (fill > 0.5f) {
                    gfx.rect(Rect{track.x, track.y, fill, track.height}, accent(),
                             track.height * 0.5f);
                }
                const float knobRadius = s(5.0f);
                gfx.rect(Rect{track.x + fill - knobRadius, track.centerY() - knobRadius,
                              knobRadius * 2.0f, knobRadius * 2.0f},
                         fade(kKnobOn), knobRadius);
            }
            break;
        }

        case modules::Setting::Type::Choice: {
            drawText(kLabelFont, value.name(), line.x + padX, line.centerY() - labelBox * 0.5f,
                     labelBox, fade(kTextPrimary));
            const std::string current = value.choiceName();
            const float chipWidth = textWidth(kMicroFont, current, s(kMicroBox)) + s(12.0f);
            drawChip(rightEdge - chipWidth, line.centerY(), current, kChipBg, kChipText);
            if (setting.expand > 0.01f) {
                drawChoiceItems(setting);
            }
            break;
        }

        case modules::Setting::Type::Key: {
            drawText(kLabelFont, value.name(), line.x + padX, line.centerY() - labelBox * 0.5f,
                     labelBox, fade(kTextPrimary));
            const std::string label = bindLabel(value.key(), setting.listeningForKey);
            const float chipWidth = textWidth(kMicroFont, label, s(kMicroBox)) + s(12.0f);
            drawChip(rightEdge - chipWidth, line.centerY(), label, kChipBg,
                     setting.listeningForKey ? theme().accent : kChipText);
            break;
        }

        case modules::Setting::Type::Colour: {
            drawText(kLabelFont, value.name(), line.x + padX, line.centerY() - labelBox * 0.5f,
                     labelBox, fade(kTextPrimary));
            const float swatchWidth = s(30.0f);
            const float swatchHeight = s(16.0f);
            const Rect swatch{rightEdge - swatchWidth, line.centerY() - swatchHeight * 0.5f,
                              swatchWidth, swatchHeight};
            gfx.rect(swatch, fade(value.colour()), s(4.0f));
            gfx.outline(swatch, fade(kCardBorder), std::max(1.0f, s(1.0f)), s(4.0f));
            if (setting.picker > 0.01f) {
                drawColourEditor(setting,
                                 Rect{bounds.x, bounds.y + s(kSettingHeight), bounds.width,
                                      s(kEditorHeight)});
            }
            break;
        }
    }
}

void ClickGui::drawChoiceItems(SettingRow& setting) {
    render::Renderer& gfx = render::renderer();
    modules::Setting& value = *setting.setting;
    const float reveal = clamp(setting.expand, 0.0f, 1.0f);
    const float itemHeight = s(kChoiceItemHeight);
    const float full = static_cast<float>(value.choices().size()) * itemHeight;
    const Rect area{setting.bounds.x, setting.bounds.y + s(kSettingHeight), setting.bounds.width,
                    full * reveal};
    if (area.height <= 0.5f) {
        return;
    }
    gfx.pushClip(area);
    for (std::size_t i = 0; i < value.choices().size(); ++i) {
        const float itemY = area.y + static_cast<float>(i) * itemHeight;
        const bool hovered = pointable() && mouseX_ >= area.x && mouseX_ <= area.right() &&
                             mouseY_ >= itemY && mouseY_ <= itemY + itemHeight;
        const bool selected = static_cast<int>(i) == value.choice();
        if (hovered) {
            gfx.rect(Rect{area.x + s(6.0f), itemY + s(1.0f), area.width - s(12.0f),
                          itemHeight - s(2.0f)},
                     fade(kHoverWash, 0.7f), s(5.0f));
        }
        const Color colour = selected ? theme().accent : Color::mix(kTextMuted, kTextPrimary,
                                                                    hovered ? 1.0f : 0.0f);
        drawText(kDescFont, value.choices()[i], area.x + s(kSettingPadX + 10.0f),
                 itemY + (itemHeight - s(kDescBox)) * 0.5f, s(kDescBox), fade(colour));
    }
    gfx.popClip();
}

void ClickGui::drawColourEditor(SettingRow& setting, const Rect& area) {
    render::Renderer& gfx = render::renderer();
    const float reveal = clamp(setting.picker, 0.0f, 1.0f);
    if (reveal <= 0.01f) {
        return;
    }
    gfx.pushClip(Rect{area.x, area.y, area.width, area.height * reveal});

    const Color current = setting.setting->colour();
    const float maximum = std::max({current.r, current.g, current.b});
    const float minimum = std::min({current.r, current.g, current.b});
    const float saturation = maximum <= 0.0f ? 0.0f : (maximum - minimum) / maximum;

    const Rect field{area.x + s(kSettingPadX), area.y + s(kEditorGap),
                     area.width - s(kSettingPadX) * 2.0f, s(kEditorField)};
    gfx.rectHorizontal(field, fade(Color{1.0f, 1.0f, 1.0f, 1.0f}),
                       fade(Color::hsv(setting.hue, 1.0f, 1.0f)), s(6.0f));
    gfx.rectVertical(field, Color{0.0f, 0.0f, 0.0f, 0.0f}, fade(Color{0.0f, 0.0f, 0.0f, 1.0f}),
                     s(6.0f));
    gfx.ring(field.x + field.width * saturation, field.y + field.height * (1.0f - maximum),
             s(5.0f), s(2.0f), 1.0f, fade(Color{1.0f, 1.0f, 1.0f, 1.0f}));

    const Rect strip{field.x, field.bottom() + s(kEditorGap), field.width, s(kEditorStrip)};
    const int segments = 6;
    const float segmentWidth = strip.width / static_cast<float>(segments);
    for (int index = 0; index < segments; ++index) {
        const float from = static_cast<float>(index) / static_cast<float>(segments);
        const float to = static_cast<float>(index + 1) / static_cast<float>(segments);
        gfx.rectHorizontal(Rect{strip.x + segmentWidth * static_cast<float>(index), strip.y,
                                segmentWidth, strip.height},
                           fade(Color::hsv(from, 1.0f, 1.0f)),
                           fade(Color::hsv(to - 0.0001f, 1.0f, 1.0f)), 0.0f);
    }
    gfx.rect(Rect{strip.x + strip.width * setting.hue - s(1.5f), strip.y - s(2.0f), s(3.0f),
                  strip.height + s(4.0f)},
             fade(Color{1.0f, 1.0f, 1.0f, 1.0f}), s(1.5f));

    const Rect preview{field.x, strip.bottom() + s(kEditorGap), field.width, s(kEditorSwatch)};
    gfx.rect(preview, fade(current), s(5.0f));
    char hex[16];
    std::snprintf(hex, sizeof(hex), "#%06X", current.packed() >> 8);
    drawText(kMicroFont, hex, preview.centerX(), preview.centerY() - s(kMicroBox) * 0.5f,
             s(kMicroBox), fade(Color{0.0f, 0.0f, 0.0f, 0.7f}), render::Align::Center);

    gfx.popClip();
}

void ClickGui::drawConfigs(Row& row) {
    (void)row;
    render::Renderer& gfx = render::renderer();
    const float labelBox = s(13.0f);
    const float padX = s(kSettingPadX);

    gfx.rect(nameField_, fade(Color::mix(kRowBg, kHoverWash, nameHover_ * 0.6f)), s(kRowRadius));
    const bool empty = configName_.empty();
    const std::string shown = empty ? "Config name" : configName_;
    const float end = drawText(kLabelFont, shown, nameField_.x + padX,
                               nameField_.centerY() - labelBox * 0.5f, labelBox,
                               fade(empty ? kTextFaint : kTextPrimary));
    if (editingName_) {
        const float blink = 0.5f + 0.5f * std::sin(time_ * 6.0f);
        gfx.rect(Rect{end + s(2.0f), nameField_.centerY() - labelBox * 0.5f, s(1.5f), labelBox},
                 accent(blink), s(0.75f));
    }

    gfx.rect(saveButton_, fade(Color::mix(kRowBg, kHoverWash, 0.4f + saveHover_ * 0.6f)),
             s(kRowRadius));
    drawText(kLabelFont, "Save", saveButton_.centerX(), saveButton_.centerY() - labelBox * 0.5f,
             labelBox, fade(Color::mix(kTextMuted, kTextPrimary, saveHover_)),
             render::Align::Center);

    for (std::size_t i = 0; i < configs_.size(); ++i) {
        const ConfigFile& entry = configs_[i];
        if (entry.bounds.width <= 0.0f) {
            continue;
        }
        const bool selected = static_cast<int>(i) == selectedConfig_;
        gfx.rect(entry.bounds, fade(Color::mix(kRowBg, kHoverWash, entry.hover * 0.6f)),
                 s(kRowRadius));
        if (selected) {
            gfx.rect(Rect{entry.bounds.x, entry.bounds.y + s(7.0f), s(3.0f),
                          entry.bounds.height - s(14.0f)},
                     accent(), s(1.5f));
        }
        drawText(kLabelFont, entry.name, entry.bounds.x + padX,
                 entry.bounds.centerY() - labelBox * 0.5f, labelBox,
                 fade(selected ? kTextPrimary : Color::mix(kTextMuted, kTextPrimary, entry.hover)));

        gfx.rect(entry.remove,
                 fade(Color::mix(kChipBg, theme().negative, entry.removeHover * 0.75f)), s(5.0f));
        drawText(kMicroFont, "DELETE", entry.remove.centerX(),
                 entry.remove.centerY() - s(kMicroBox) * 0.5f, s(kMicroBox),
                 fade(Color::mix(kChipText, kTextPrimary, entry.removeHover)),
                 render::Align::Center);
    }

    if (!configStatus_.empty() && !configs_.empty()) {
        const float top = configs_.back().bounds.bottom() + s(kRowGap) + s(10.0f);
        drawText(kDescFont, configStatus_, body_.x + padX, top, s(kDescBox), fade(kTextFaint));
    } else if (!configStatus_.empty()) {
        drawText(kDescFont, configStatus_, body_.x + padX,
                 nameField_.bottom() + s(kRowGap) + s(10.0f), s(kDescBox), fade(kTextFaint));
    }
}

/* The game's cursor is grabbed (and therefore invisible) while the menu owns
 * it, so the menu draws its own. The GWEN-style triangle with a hairline edge
 * reads against both a bright scene and the card. */
void ClickGui::renderCursor(render::Renderer& gfx) {
    const float x = mouseX_;
    const float y = mouseY_;
    const float w = s(6.0f);
    const float h = s(9.5f);
    const float xs[4] = {x, x, x + w, x + w * 0.62f};
    const float ys[4] = {y, y + h, y + h * 0.68f, y + h * 0.68f};
    gfx.face(xs, ys, Color::rgb(0xE8ECF2, 0.9f * alpha_));
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
    gfx.face(xs2, ys2, Color::rgb(0x0B0E14, 0.9f * alpha_));
}

}  // namespace cold::ui
