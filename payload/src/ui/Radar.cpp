#include "ui/Radar.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "bridge/Jvm.hpp"
#include "bridge/Minecraft.hpp"
#include "render/Renderer.hpp"
#include "ui/ClickGui.hpp"
#include "ui/Client.hpp"
#include "ui/Theme.hpp"

namespace cold::ui {
namespace {

using render::Align;
using render::FontRole;

/* GUI-scaled units, measured the same way the menu is. The window is wider than
 * the menu because it is three columns of information rather than one list. */
constexpr float kPanelWidth = 460.0f;
constexpr float kPanelHeight = 300.0f;
constexpr float kPanelWidthShare = 0.52f;
constexpr float kPanelHeightShare = 0.62f;
constexpr float kPanelMinimumScale = 1.4f;
constexpr float kEdge = 1.0f;
constexpr float kDragStrip = 15.0f;
constexpr float kCapMiddle = 0.3635f;

constexpr float kMargin = 10.0f;
constexpr float kTitleCapTop = 12.0f;
constexpr float kTitleSize = 14.0f;
constexpr float kSubCapTop = 28.0f;
constexpr float kSubSize = 7.2f;

constexpr float kSearchTop = 38.0f;
constexpr float kSearchHeight = 16.0f;
constexpr float kListLeft = 10.0f;
constexpr float kListWidth = 162.0f;
constexpr float kListTop = 60.0f;
constexpr float kSortHeight = 13.0f;
constexpr float kRowHeight = 32.0f;
constexpr float kRowGap = 4.0f;
constexpr float kHeadSize = 22.0f;

constexpr float kPaneLeft = 182.0f;
constexpr float kPaneWidth = 268.0f;
constexpr float kNameCapTop = 40.0f;
constexpr float kNameSize = 14.0f;

constexpr float kStatsTop = 58.0f;
constexpr float kStatsWidth = 80.0f;
constexpr float kStatHeight = 23.0f;
constexpr float kStatGap = 2.0f;

constexpr float kSkinLeft = 268.0f;
constexpr float kSkinWidth = 72.0f;
constexpr float kSkinHeight = 98.0f;

constexpr float kArmourLeft = 346.0f;
constexpr float kArmourWidth = 104.0f;
constexpr float kArmourRow = 23.0f;

constexpr float kHandsTop = 156.0f;
constexpr float kHandsHeight = 26.0f;

constexpr float kEffectsTop = 188.0f;
constexpr float kEffectsHeight = 16.0f;

constexpr float kHistoryTop = 210.0f;
constexpr float kHistoryRow = 20.0f;

constexpr float kIconChip = 13.0f;

float approach(float current, float target, float rate, float deltaTime) {
    const float step = 1.0f - std::exp(-rate * deltaTime);
    const float next = current + (target - current) * step;
    return std::fabs(target - next) < 0.0015f ? target : next;
}

std::string lowercase(std::string_view value) {
    std::string out;
    out.reserve(value.size());
    for (char character : value) {
        out.push_back(static_cast<char>(character >= 'A' && character <= 'Z' ? character + 32
                                                                            : character));
    }
    return out;
}

/* "fire_aspect" -> "Fire Aspect". The registry path is the only name the
 * enchantment carries without going through the language file, and it turns out
 * to be the name anyway. */
std::string prettify(std::string_view id) {
    std::string out;
    out.reserve(id.size());
    bool boundary = true;
    for (char character : id) {
        if (character == '_') {
            out.push_back(' ');
            boundary = true;
            continue;
        }
        out.push_back(boundary && character >= 'a' && character <= 'z'
                          ? static_cast<char>(character - 32)
                          : character);
        boundary = false;
    }
    return out;
}

std::string roman(int value) {
    static const struct {
        int value;
        const char* text;
    } kNumerals[] = {{10, "X"}, {9, "IX"}, {5, "V"}, {4, "IV"}, {1, "I"}};
    if (value <= 0 || value > 40) {
        char buffer[16];
        std::snprintf(buffer, sizeof(buffer), "%d", value);
        return buffer;
    }
    std::string out;
    for (const auto& numeral : kNumerals) {
        while (value >= numeral.value) {
            out += numeral.text;
            value -= numeral.value;
        }
    }
    return out;
}

std::string enchantLine(const std::vector<game::Game::EnchantView>& enchantments) {
    std::string out;
    for (const game::Game::EnchantView& enchant : enchantments) {
        if (!out.empty()) out += ", ";
        out += prettify(enchant.id);
        out += ' ';
        out += roman(enchant.level);
    }
    return out;
}

std::string format(const char* pattern, double value) {
    char buffer[48];
    std::snprintf(buffer, sizeof(buffer), pattern, value);
    return buffer;
}

/* The one lucide glyph that stands for a whole family of items, for the moment
 * the atlas has nothing flat to show - a block item, or a pack that renamed the
 * texture out from under us. */
assets::IconAsset iconFor(const std::string& id) {
    const auto has = [&](const char* needle) { return id.find(needle) != std::string::npos; };
    if (has("helmet") || has("chestplate") || has("leggings") || has("boots")) {
        return assets::IconAsset::Layers;
    }
    if (has("sword") || has("axe") || has("trident") || has("mace")) return assets::IconAsset::Swords;
    if (has("bow") || has("arrow") || has("crossbow")) return assets::IconAsset::Target;
    if (has("potion") || has("totem")) return assets::IconAsset::Flame;
    if (has("pickaxe") || has("shovel") || has("hoe") || has("shears")) {
        return assets::IconAsset::Hammer;
    }
    if (has("shield")) return assets::IconAsset::Lock;
    return assets::IconAsset::Box;
}

/* The same white the skin texture is sampled with, dimmed for a face turned
 * away from the light. There is no lighting in the batch, so the shade is what
 * separates the six sides of a box from one another. */
Color shaded(float factor, float alpha) {
    return Color{factor, factor, factor, alpha};
}

Color pingColour(int ping) {
    const Palette& palette = theme();
    if (ping < 0) return palette.textFaint;
    if (ping < 80) return palette.positive;
    if (ping < 200) return palette.warning;
    return palette.negative;
}

/* ---- the model ----
 *
 * Six boxes in the units the skin is drawn in, where one unit is one pixel of a
 * 64-wide skin and the whole figure is thirty-two tall. The overlay layer is the
 * same box grown by an eighth, which is what the game does.
 *
 * `uvX`/`uvY` is the top left of the box's cross in the texture. Every box lays
 * its faces out the same way - right, front, left, back along the middle row,
 * top and bottom above them - so one function covers all twelve. */
struct SkinBox {
    float x, y, z;     // centre, in model units, feet at y = 0
    float w, h, d;     // size
    int uvX, uvY;
    bool overlay;
};

constexpr float kSkinTexture = 64.0f;

}  // namespace

void Radar::open() {
    if (open_) {
        return;
    }
    open_ = true;
    appear_ = 0.0f;
    query_.clear();
    hooks::input().setCapturing(true);
}

void Radar::close() {
    if (!open_) {
        return;
    }
    open_ = false;
    dragging_ = false;
    turning_ = false;
    /* The menu may still want the cursor; whichever of the two is still up
     * takes it back on its own next frame. */
    if (!client().gui().interactive()) {
        hooks::input().setCapturing(false);
    }
}

void Radar::toggle() {
    if (open_) {
        close();
    } else {
        open();
    }
}

void Radar::collect() {
    shown_.clear();
    const std::string needle = lowercase(query_);
    for (const game::RadarPlayer& player : game::radar().players()) {
        if (!needle.empty() && lowercase(player.name).find(needle) == std::string::npos) {
            continue;
        }
        shown_.push_back(&player);
    }

    std::sort(shown_.begin(), shown_.end(),
              [this](const game::RadarPlayer* a, const game::RadarPlayer* b) {
                  /* Anybody still loaded outranks anybody who has walked out of
                   * render distance, whatever the sort is - a record you cannot
                   * act on belongs at the bottom. */
                  if (a->self != b->self) return a->self;
                  if (a->loaded != b->loaded) return a->loaded;
                  switch (sort_) {
                      case 1: return a->health < b->health;
                      case 2: return lowercase(a->name) < lowercase(b->name);
                      default: return a->distance < b->distance;
                  }
              });

    if (!selected_.empty() && game::radar().find(selected_) == nullptr) {
        selected_.clear();
    }
    if (selected_.empty() && !shown_.empty()) {
        selected_ = shown_.front()->uuid;
    }
}

void Radar::layout(modules::Context& context) {
    const game::FrameState& state = *context.state;
    const float deltaTime = context.deltaTime;

    const float base =
        clamp(std::min(static_cast<float>(state.windowWidth) * kPanelWidthShare / kPanelWidth,
                       static_cast<float>(state.windowHeight) * kPanelHeightShare / kPanelHeight),
              kPanelMinimumScale, 6.0f);
    const float scaledWidth = static_cast<float>(state.windowWidth) / base;
    const float scaledHeight = static_cast<float>(state.windowHeight) / base;

    alpha_ = approach(alpha_, open_ ? 1.0f : 0.0f, 18.0f, deltaTime);
    appear_ = approach(appear_, open_ ? 1.0f : 0.0f, open_ ? 13.0f : 22.0f, deltaTime);

    if (!placed_ || panelX_ + kPanelWidth > scaledWidth + 1.0f ||
        panelY_ + kPanelHeight > scaledHeight + 1.0f) {
        panelX_ = std::max(0.0f, (scaledWidth - kPanelWidth) * 0.5f);
        panelY_ = std::max(0.0f, (scaledHeight - kPanelHeight) * 0.5f);
        placed_ = true;
    }
    if (dragging_) {
        panelX_ = (mouseX_ - dragOffsetX_) / base;
        panelY_ = (mouseY_ - dragOffsetY_) / base;
    }
    panelX_ = clamp(panelX_, -kPanelWidth + 90.0f, scaledWidth - 90.0f);
    panelY_ = clamp(panelY_, 0.0f, std::max(0.0f, scaledHeight - kDragStrip));

    const float grow = 0.94f + 0.06f * ease::outQuint(clamp(appear_, 0.0f, 1.0f));
    unit_ = base * grow;
    const float centreX = (panelX_ + kPanelWidth * 0.5f) * base;
    const float centreY = (panelY_ + kPanelHeight * 0.5f) * base;
    panel_ = Rect{centreX - px(kPanelWidth) * 0.5f, centreY - px(kPanelHeight) * 0.5f,
                  px(kPanelWidth), px(kPanelHeight)};

    listBounds_ = Rect{panel_.x + px(kListLeft), panel_.y + px(kListTop), px(kListWidth),
                       px(kPanelHeight - kListTop - kMargin - kSortHeight - 4.0f)};
    historyBounds_ = Rect{panel_.x + px(kPaneLeft), panel_.y + px(kHistoryTop + 12.0f),
                          px(kPaneWidth), px(kPanelHeight - kHistoryTop - 12.0f - kMargin)};
    skinBounds_ = Rect{panel_.x + px(kSkinLeft), panel_.y + px(kStatsTop), px(kSkinWidth),
                       px(kSkinHeight)};
    closeBounds_ = Rect{panel_.right() - px(kMargin + 15.0f), panel_.y + px(kMargin), px(15.0f),
                        px(15.0f)};
    sortBounds_ = Rect{panel_.x + px(kListLeft), listBounds_.bottom() + px(4.0f), px(kListWidth),
                       px(kSortHeight)};

    const bool over = open_ && closeBounds_.contains(mouseX_, mouseY_);
    closeHover_ = approach(closeHover_, over ? 1.0f : 0.0f, 18.0f, deltaTime);
    const bool overSort = open_ && sortBounds_.contains(mouseX_, mouseY_);
    sortHover_ = approach(sortHover_, overSort ? 1.0f : 0.0f, 18.0f, deltaTime);

    /* Rows are rebuilt every frame from the filtered list, and their animation
     * state is carried by uuid so a re-sort does not make the list flash. */
    std::vector<Row> next;
    next.reserve(shown_.size());
    float y = 0.0f;
    for (const game::RadarPlayer* player : shown_) {
        Row row;
        row.uuid = player->uuid;
        for (const Row& old : rows_) {
            if (old.uuid == row.uuid) {
                row.hover = old.hover;
                row.active = old.active;
                break;
            }
        }
        row.bounds = Rect{listBounds_.x, listBounds_.y + px(y) + scroll_, listBounds_.width,
                          px(kRowHeight)};
        const bool hovered = open_ && listBounds_.contains(mouseX_, mouseY_) &&
                             row.bounds.contains(mouseX_, mouseY_);
        row.hover = approach(row.hover, hovered ? 1.0f : 0.0f, 18.0f, deltaTime);
        row.active = approach(row.active, row.uuid == selected_ ? 1.0f : 0.0f, 16.0f, deltaTime);
        next.push_back(std::move(row));
        y += kRowHeight + kRowGap;
    }
    rows_ = std::move(next);
    listHeight_ = px(std::max(0.0f, y - kRowGap));

    const float lowest = std::min(0.0f, listBounds_.height - listHeight_);
    scrollTarget_ = clamp(scrollTarget_, lowest, 0.0f);
    scroll_ = approach(scroll_, scrollTarget_, 18.0f, deltaTime);

    const float historyLowest = std::min(0.0f, historyBounds_.height - historyHeight_);
    historyTarget_ = clamp(historyTarget_, historyLowest, 0.0f);
    historyScroll_ = approach(historyScroll_, historyTarget_, 18.0f, deltaTime);

    /* A model nobody has touched turns slowly on its own, which is what makes
     * the panel read as a model rather than a picture. Once it has been
     * dragged it stays where it was put. */
    if (turning_) {
        modelYaw_ = grabYaw_ + (mouseX_ - turnFromX_) / std::max(unit_, 0.1f) * 1.6f;
        modelPitch_ = clamp(grabPitch_ + (mouseY_ - turnFromY_) / std::max(unit_, 0.1f) * 1.0f,
                            -32.0f, 32.0f);
    } else if (!touched_) {
        modelYaw_ += deltaTime * 16.0f;
    }
    modelYaw_ = std::fmod(modelYaw_, 360.0f);
}

void Radar::render(modules::Context& context) {
    collect();
    layout(context);
    if (alpha_ <= 0.004f) {
        rows_.clear();
        return;
    }

    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();

    gfx.shadow(panel_, palette.shadow.withAlpha(0.8f * alpha_), px(18.0f), px(palette.radiusPanel));
    drawPanel(panel_, px(palette.radiusPanel), alpha_, false);

    gfx.pushClip(panel_.inset(px(kEdge)));
    renderHeader(context);
    renderList();
    renderDetail();
    gfx.popClip();
}

void Radar::renderHeader(modules::Context& context) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    gfx.textCap(FontRole::Bold, "Radar", panel_.x + px(kMargin + 4.0f),
                panel_.y + px(kTitleCapTop), px(kTitleSize), palette.textBright.withAlpha(alpha));
    gfx.textCap(FontRole::Regular, "Tracking nearby players", panel_.x + px(kMargin + 4.0f),
                panel_.y + px(kSubCapTop), px(kSubSize), palette.textDim.withAlpha(alpha * 0.9f));

    char count[48];
    std::snprintf(count, sizeof(count), "%d loaded", game::radar().loadedCount());
    const float pillWidth = gfx.measure(FontRole::Medium, count, px(7.6f)) + px(16.0f);
    const Rect pill{closeBounds_.x - px(6.0f) - pillWidth, panel_.y + px(kMargin), pillWidth,
                    px(15.0f)};
    gfx.rect(pill, palette.surface.withAlpha(alpha), px(palette.radiusPill));
    gfx.textCap(FontRole::Medium, count, pill.centerX(),
                pill.y + px(15.0f) * 0.5f - px(7.6f) * kCapMiddle, px(7.6f),
                palette.text.withAlpha(alpha), Align::Center);

    gfx.rect(closeBounds_,
             palette.surface.withAlpha(alpha * (0.7f + 0.5f * closeHover_)),
             px(palette.radiusPill));
    gfx.icon(assets::IconAsset::X, closeBounds_.inset(px(4.0f)),
             Color::mix(palette.textDim, palette.textBright, closeHover_).withAlpha(alpha));

    /* Search. The field is the whole strip: a box with a glyph in it, typed
     * into whenever the window has the keyboard. */
    const Rect search{panel_.x + px(kListLeft), panel_.y + px(kSearchTop), px(kListWidth),
                      px(kSearchHeight)};
    gfx.rect(search, palette.surface.withAlpha(alpha), px(palette.radiusCard));
    gfx.icon(assets::IconAsset::Search,
             Rect{search.x + px(5.0f), search.centerY() - px(4.5f), px(9.0f), px(9.0f)},
             palette.textFaint.withAlpha(alpha));
    const bool empty = query_.empty();
    gfx.textCap(FontRole::Regular, empty ? "Search player..." : query_,
                search.x + px(17.0f), search.centerY() - px(8.2f) * kCapMiddle, px(8.2f),
                (empty ? palette.textFaint : palette.text).withAlpha(alpha));
    if (!empty && std::fmod(context.time, 1.0f) < 0.5f) {
        const float caret = search.x + px(17.0f) + gfx.measure(FontRole::Regular, query_, px(8.2f));
        gfx.rect(Rect{caret + px(1.0f), search.centerY() - px(4.5f), px(0.9f), px(9.0f)},
                 palette.accent.withAlpha(alpha));
    }
}

void Radar::renderList() {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    gfx.pushClip(listBounds_);
    for (std::size_t index = 0; index < rows_.size() && index < shown_.size(); ++index) {
        const Row& row = rows_[index];
        const game::RadarPlayer& player = *shown_[index];
        if (row.bounds.bottom() < listBounds_.y || row.bounds.y > listBounds_.bottom()) {
            continue;
        }

        const float lift = 0.35f * row.hover + row.active;
        gfx.rect(row.bounds, palette.surface.withAlpha(alpha * (0.75f + 0.35f * lift)),
                 px(palette.radiusCard));
        if (row.active > 0.01f) {
            gfx.outline(row.bounds, palette.accent.withAlpha(alpha * row.active * 0.7f), px(0.9f),
                        px(palette.radiusCard));
        }

        /* The face out of the skin, plus the hat layer over it - the same two
         * rectangles the game draws in its own tab list. */
        const Rect head{row.bounds.x + px(5.0f), row.bounds.centerY() - px(kHeadSize) * 0.5f,
                        px(kHeadSize), px(kHeadSize)};
        gfx.rect(head, palette.surfaceActive.withAlpha(alpha), px(2.0f));
        if (player.skin.texture != 0) {
            const Color tint = Color::rgb(0xFFFFFF, alpha * (player.loaded ? 1.0f : 0.45f));
            gfx.imageCrisp(head, Rect{8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f},
                           player.skin.texture, tint, px(2.0f));
            gfx.imageCrisp(head, Rect{40.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f},
                           player.skin.texture, tint, px(2.0f));
        } else {
            gfx.icon(assets::IconAsset::UserRound, head.inset(px(5.0f)),
                     palette.textFaint.withAlpha(alpha));
        }

        const float textX = head.right() + px(6.0f);
        const Color nameColour =
            (player.loaded ? palette.textBright : palette.textDim).withAlpha(alpha);
        const float nameWidth = row.bounds.right() - px(6.0f) - textX;
        gfx.textCap(FontRole::SemiBold, gfx.elide(FontRole::SemiBold, player.name, px(9.4f),
                                                  nameWidth),
                    textX, row.bounds.y + px(7.0f), px(9.4f), nameColour);

        char detail[64];
        if (player.self) {
            std::snprintf(detail, sizeof(detail), "you  %dms", player.ping < 0 ? 0 : player.ping);
        } else if (player.loaded) {
            std::snprintf(detail, sizeof(detail), "%.1fm  %dms", player.distance,
                          player.ping < 0 ? 0 : player.ping);
        } else {
            std::snprintf(detail, sizeof(detail), "out of range");
        }
        gfx.textCap(FontRole::Regular, detail, textX, row.bounds.y + px(17.5f), px(7.2f),
                    palette.textDim.withAlpha(alpha * 0.85f));

        /* Health as a bar rather than a number: the row is a glance, and the
         * pane behind it carries the figures. */
        const Rect meter{textX, row.bounds.bottom() - px(7.0f), nameWidth * 0.62f, px(2.0f)};
        const float fraction =
            player.maxHealth > 0.0f ? clamp(player.health / player.maxHealth, 0.0f, 1.0f) : 0.0f;
        drawMeter(meter, fraction, palette.surfaceActive.withAlpha(alpha),
                  healthColour(player.health, player.maxHealth).withAlpha(alpha), px(1.0f));
        gfx.textCap(FontRole::Mono, format("%.1f", player.health), row.bounds.right() - px(6.0f),
                    row.bounds.bottom() - px(11.0f), px(7.0f),
                    healthColour(player.health, player.maxHealth).withAlpha(alpha), Align::Right);
    }
    gfx.popClip();

    if (rows_.empty()) {
        gfx.textCap(FontRole::Regular, query_.empty() ? "No players in range" : "No match",
                    listBounds_.centerX(), listBounds_.y + px(20.0f), px(8.0f),
                    palette.textFaint.withAlpha(alpha), Align::Center);
    }

    if (listHeight_ > listBounds_.height + 1.0f) {
        const float ratio = listBounds_.height / listHeight_;
        const float travel = listBounds_.height * (1.0f - ratio);
        const float offset = listHeight_ > listBounds_.height
                                 ? -scroll_ / (listHeight_ - listBounds_.height)
                                 : 0.0f;
        gfx.rect(Rect{listBounds_.right() + px(2.0f), listBounds_.y + travel * offset, px(2.0f),
                      listBounds_.height * ratio},
                 palette.scrollbar.withAlpha(alpha * 0.7f), px(1.0f));
    }

    static const char* const kSorts[] = {"Distance", "Health", "Name"};
    gfx.rect(sortBounds_, palette.surface.withAlpha(alpha * (0.7f + 0.4f * sortHover_)),
             px(palette.radiusPill));
    gfx.textCap(FontRole::Regular, "Sort by", sortBounds_.x + px(7.0f),
                sortBounds_.centerY() - px(7.0f) * kCapMiddle, px(7.0f),
                palette.textFaint.withAlpha(alpha));
    gfx.textCap(FontRole::Medium, kSorts[sort_], sortBounds_.right() - px(7.0f),
                sortBounds_.centerY() - px(7.4f) * kCapMiddle, px(7.4f),
                palette.accent.withAlpha(alpha), Align::Right);
}

void Radar::renderEmpty(const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    gfx.textCap(FontRole::Regular, "Select a player", bounds.centerX(),
                bounds.y + bounds.height * 0.45f, px(9.0f),
                palette.textFaint.withAlpha(alpha_), Align::Center);
}

void Radar::renderStatCard(const Rect& bounds, assets::IconAsset icon, std::string_view label,
                           std::string_view value, const Color& tint) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    /* Label above value rather than beside it. A coordinate triple is wide
     * enough to run into a label sharing its line, and a card that only reads
     * at short values is not a card. */
    gfx.rect(bounds, palette.surface.withAlpha(alpha * 0.8f), px(palette.radiusCard));
    gfx.icon(icon, Rect{bounds.x + px(5.0f), bounds.centerY() - px(4.0f), px(8.0f), px(8.0f)},
             palette.textFaint.withAlpha(alpha));
    gfx.textCap(FontRole::Regular, label, bounds.x + px(16.0f), bounds.y + px(5.0f), px(6.2f),
                palette.textFaint.withAlpha(alpha));
    gfx.textCap(FontRole::Medium,
                gfx.elide(FontRole::Medium, value, px(8.4f), bounds.width - px(20.0f)),
                bounds.x + px(16.0f), bounds.y + px(13.0f), px(8.4f), tint.withAlpha(alpha));
}

void Radar::renderStats(const game::RadarPlayer& player, const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();

    const float step = px(kStatHeight + kStatGap);
    Rect card{bounds.x, bounds.y, bounds.width, px(kStatHeight)};

    char health[48];
    std::snprintf(health, sizeof(health), "%.1f / %.0f", player.health + player.absorption,
                  player.maxHealth);
    renderStatCard(card, assets::IconAsset::Activity, "Health", health,
                   healthColour(player.health, player.maxHealth));

    card.y += step;
    renderStatCard(card, assets::IconAsset::Wifi, "Ping",
                   player.ping < 0 ? "-" : format("%.0f ms", static_cast<double>(player.ping)),
                   pingColour(player.ping));

    card.y += step;
    renderStatCard(card, assets::IconAsset::Move3d, "Distance",
                   player.loaded ? format("%.1f m", player.distance) : "-", palette.accent);

    card.y += step;
    char coords[64];
    std::snprintf(coords, sizeof(coords), "%d, %d, %d", static_cast<int>(std::floor(player.pos.x)),
                  static_cast<int>(std::floor(player.pos.y)),
                  static_cast<int>(std::floor(player.pos.z)));
    renderStatCard(card, assets::IconAsset::Pin, "Coordinates", coords, palette.text);
    (void)gfx;
}

void Radar::renderSkin(const game::RadarPlayer& player, const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    gfx.rect(bounds, palette.surface.withAlpha(alpha * 0.8f), px(theme().radiusCard));
    if (player.skin.texture == 0) {
        gfx.icon(assets::IconAsset::UserRound, bounds.inset(bounds.width * 0.32f),
                 palette.textFaint.withAlpha(alpha));
        return;
    }

    const float armWidth = player.skin.slim ? 3.0f : 4.0f;
    const float armOffset = 4.0f + armWidth * 0.5f;
    const SkinBox kBoxes[] = {
        /*        x            y     z    w          h     d   uvX uvY  overlay */
        {0.0f, 28.0f, 0.0f, 8.0f, 8.0f, 8.0f, 0, 0, false},
        {0.0f, 18.0f, 0.0f, 8.0f, 12.0f, 4.0f, 16, 16, false},
        {-armOffset, 18.0f, 0.0f, armWidth, 12.0f, 4.0f, 40, 16, false},
        {armOffset, 18.0f, 0.0f, armWidth, 12.0f, 4.0f, 32, 48, false},
        {-2.0f, 6.0f, 0.0f, 4.0f, 12.0f, 4.0f, 0, 16, false},
        {2.0f, 6.0f, 0.0f, 4.0f, 12.0f, 4.0f, 16, 48, false},
        {0.0f, 28.0f, 0.0f, 8.0f, 8.0f, 8.0f, 32, 0, true},
        {0.0f, 18.0f, 0.0f, 8.0f, 12.0f, 4.0f, 16, 32, true},
        {-armOffset, 18.0f, 0.0f, armWidth, 12.0f, 4.0f, 40, 32, true},
        {armOffset, 18.0f, 0.0f, armWidth, 12.0f, 4.0f, 48, 48, true},
        {-2.0f, 6.0f, 0.0f, 4.0f, 12.0f, 4.0f, 0, 32, true},
        {2.0f, 6.0f, 0.0f, 4.0f, 12.0f, 4.0f, 0, 48, true},
    };

    /* Orthographic, because a figure this size gains nothing from perspective
     * and loses the straight edges that make it read as pixel art. The scale is
     * whatever fits thirty-six units of model into the panel. */
    const float scale = std::min(bounds.width / 22.0f, bounds.height / 38.0f);
    const float originX = bounds.centerX();
    const float originY = bounds.centerY() + 17.0f * scale;
    const float yaw = modelYaw_ * kPi / 180.0f;
    const float pitch = modelPitch_ * kPi / 180.0f;
    const float cosYaw = std::cos(yaw);
    const float sinYaw = std::sin(yaw);
    const float cosPitch = std::cos(pitch);
    const float sinPitch = std::sin(pitch);

    quads_.clear();
    for (const SkinBox& box : kBoxes) {
        const float grow = box.overlay ? 0.5f : 0.0f;
        const float hw = box.w * 0.5f + grow;
        const float hh = box.h * 0.5f + grow;
        const float hd = box.d * 0.5f + grow;

        /* Eight corners, then the six faces as indices into them. */
        float cx[8];
        float cy[8];
        float cz[8];
        for (int corner = 0; corner < 8; ++corner) {
            const float lx = ((corner & 1) ? hw : -hw) + box.x;
            const float ly = ((corner & 2) ? hh : -hh) + box.y;
            const float lz = ((corner & 4) ? hd : -hd) + box.z;
            const float rx = lx * cosYaw + lz * sinYaw;
            const float rz = -lx * sinYaw + lz * cosYaw;
            const float ry = ly * cosPitch - rz * sinPitch;
            cz[corner] = ly * sinPitch + rz * cosPitch;
            cx[corner] = originX + rx * scale;
            cy[corner] = originY - ry * scale;
        }

        struct Face {
            int a, b, c, d;
            int uvX, uvY, uvW, uvH;
            float shade;
        };
        const int w = static_cast<int>(box.w);
        const int h = static_cast<int>(box.h);
        const int dd = static_cast<int>(box.d);
        /* Corner order is top left, top right, bottom right, bottom left as the
         * face is seen from outside the box, which is both what orients the
         * texture and what makes the winding test below a back-face test.
         *
         * The model faces -z, so the viewer's left is the entity's right - and
         * the entity's right is the face the skin lays out first, at uvX. */
        const Face faces[6] = {
            {2, 3, 1, 0, box.uvX + dd, box.uvY + dd, w, h, 1.0f},            // front, -z
            {7, 6, 4, 5, box.uvX + dd + w + dd, box.uvY + dd, w, h, 0.74f},  // back, +z
            {6, 2, 0, 4, box.uvX, box.uvY + dd, dd, h, 0.86f},               // entity right, -x
            {3, 7, 5, 1, box.uvX + dd + w, box.uvY + dd, dd, h, 0.86f},      // entity left, +x
            {6, 7, 3, 2, box.uvX + dd, box.uvY, w, dd, 1.0f},                // top, +y
            {0, 1, 5, 4, box.uvX + dd + w, box.uvY, w, dd, 0.62f},           // bottom, -y
        };

        for (const Face& face : faces) {
            const int order[4] = {face.a, face.b, face.c, face.d};
            SkinQuad quad;
            float depth = 0.0f;
            for (int index = 0; index < 4; ++index) {
                quad.xs[index] = cx[order[index]];
                quad.ys[index] = cy[order[index]];
                depth += cz[order[index]];
            }
            /* Signed area in screen space. A face turned away from the camera
             * winds the other way and is dropped, which is the whole of the
             * hidden surface removal a convex box needs. */
            float area = 0.0f;
            for (int index = 0; index < 4; ++index) {
                const int next = (index + 1) & 3;
                area += quad.xs[index] * quad.ys[next] - quad.xs[next] * quad.ys[index];
            }
            if (area <= 0.0f) {
                continue;
            }
            quad.depth = depth * 0.25f - (box.overlay ? 0.01f : 0.0f);
            quad.shade = face.shade;
            quad.uv = Rect{static_cast<float>(face.uvX) / kSkinTexture,
                           static_cast<float>(face.uvY) / kSkinTexture,
                           static_cast<float>(face.uvW) / kSkinTexture,
                           static_cast<float>(face.uvH) / kSkinTexture};
            quads_.push_back(quad);
        }
    }

    /* Painter's algorithm. Boxes overlap each other - an arm crosses the body -
     * and the batch has no depth buffer of its own, so the faces are drawn from
     * the back of the model forwards. */
    std::sort(quads_.begin(), quads_.end(),
              [](const SkinQuad& a, const SkinQuad& b) { return a.depth > b.depth; });

    gfx.pushClip(bounds);
    for (const SkinQuad& quad : quads_) {
        gfx.imageFace(quad.xs, quad.ys, quad.uv, player.skin.texture,
                      shaded(quad.shade, alpha));
    }
    gfx.popClip();
}

void Radar::renderArmour(const game::RadarPlayer& player, const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    static const char* const kSlots[] = {"Helmet", "Chestplate", "Leggings", "Boots"};
    for (int slot = 0; slot < 4; ++slot) {
        const Rect row{bounds.x, bounds.y + px(kArmourRow + 1.5f) * static_cast<float>(slot),
                       bounds.width, px(kArmourRow)};
        const game::Game::EquipmentView& item = player.slots[slot];
        gfx.rect(row, palette.surface.withAlpha(alpha * 0.8f), px(palette.radiusCard));

        const Rect chip{row.x + px(3.5f), row.centerY() - px(kIconChip) * 0.5f, px(kIconChip),
                        px(kIconChip)};
        if (!item.present) {
            gfx.icon(assets::IconAsset::Minus, chip.inset(px(3.5f)),
                     palette.textFaint.withAlpha(alpha * 0.6f));
            gfx.textCap(FontRole::Regular, kSlots[slot], chip.right() + px(4.0f),
                        row.centerY() - px(7.4f) * kCapMiddle, px(7.4f),
                        palette.textFaint.withAlpha(alpha * 0.7f));
            continue;
        }

        renderItemIcon(item.id, chip);

        /* The name gets the full width of the row and the wear sits at the end
         * of the enchantment line under it. A percentage beside the name costs
         * the name four characters, and the name is the thing being read. */
        const float textX = chip.right() + px(4.0f);
        gfx.textCap(FontRole::Medium,
                    gfx.elide(FontRole::Medium, item.name, px(7.8f), row.right() - px(4.0f) - textX),
                    textX, row.y + px(4.5f), px(7.8f), palette.textBright.withAlpha(alpha));

        const float wear =
            item.maxDamage > 0
                ? 1.0f - static_cast<float>(item.damage) / static_cast<float>(item.maxDamage)
                : 1.0f;
        char percent[16];
        std::snprintf(percent, sizeof(percent), "%.0f%%", wear * 100.0f);
        const float percentWidth = gfx.measure(FontRole::Mono, percent, px(6.6f));
        gfx.textCap(FontRole::Mono, percent, row.right() - px(4.0f), row.y + px(13.0f), px(6.6f),
                    (wear > 0.35f ? palette.positive : palette.warning).withAlpha(alpha),
                    Align::Right);

        const std::string line = enchantLine(item.enchantments);
        gfx.textCap(FontRole::Regular,
                    gfx.elide(FontRole::Regular, line.empty() ? "No enchantments" : line, px(6.6f),
                              row.right() - px(8.0f) - percentWidth - textX),
                    textX, row.y + px(13.0f), px(6.6f),
                    (line.empty() ? palette.textFaint : palette.accentDim).withAlpha(alpha));
    }
}

void Radar::renderHands(const game::RadarPlayer& player, const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    static const char* const kLabels[] = {"Main hand", "Off hand"};
    const float half = (bounds.width - px(6.0f)) * 0.5f;
    for (int hand = 0; hand < 2; ++hand) {
        const Rect card{bounds.x + (half + px(6.0f)) * static_cast<float>(hand), bounds.y, half,
                        bounds.height};
        const game::Game::EquipmentView& item = player.slots[4 + hand];
        gfx.rect(card, palette.surface.withAlpha(alpha * 0.8f), px(palette.radiusCard));
        gfx.textCap(FontRole::Regular, kLabels[hand], card.x + px(6.0f), card.y + px(4.5f),
                    px(6.4f), palette.textFaint.withAlpha(alpha));

        const Rect chip{card.x + px(6.0f), card.bottom() - px(4.0f) - px(kIconChip), px(kIconChip),
                        px(kIconChip)};
        if (!item.present) {
            gfx.textCap(FontRole::Regular, "Empty", card.x + px(6.0f), card.y + px(14.5f),
                        px(8.0f), palette.textFaint.withAlpha(alpha));
            continue;
        }
        renderItemIcon(item.id, chip);
        const std::string line = enchantLine(item.enchantments);
        const float textX = chip.right() + px(4.0f);
        gfx.textCap(FontRole::Medium,
                    gfx.elide(FontRole::Medium, item.name, px(8.0f), card.right() - px(4.0f) - textX),
                    textX, card.y + px(11.0f), px(8.0f), palette.textBright.withAlpha(alpha));
        if (!line.empty()) {
            gfx.textCap(FontRole::Regular,
                        gfx.elide(FontRole::Regular, line, px(6.4f),
                                  card.right() - px(4.0f) - textX),
                        textX, card.y + px(19.0f), px(6.4f), palette.accentDim.withAlpha(alpha));
        }
    }
}

void Radar::renderEffects(const game::RadarPlayer& player, const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    if (player.effects.empty()) {
        gfx.textCap(FontRole::Regular, "No active effects", bounds.x, bounds.centerY() - px(7.0f) * kCapMiddle,
                    px(7.0f), palette.textFaint.withAlpha(alpha));
        return;
    }
    float x = bounds.x;
    for (const game::Game::EffectView& effect : player.effects) {
        std::string label = prettify(effect.id);
        if (effect.amplifier > 0) {
            label += ' ';
            label += roman(effect.amplifier + 1);
        }
        const float width = gfx.measure(FontRole::Medium, label, px(6.8f)) + px(11.0f);
        if (x + width > bounds.right()) {
            break;
        }
        const Rect chip{x, bounds.y, width, bounds.height};
        gfx.rect(chip, palette.accentSoft.withAlpha(alpha * 0.9f), px(palette.radiusPill));
        gfx.textCap(FontRole::Medium, label, chip.centerX(),
                    chip.centerY() - px(6.8f) * kCapMiddle, px(6.8f),
                    palette.accent.withAlpha(alpha), Align::Center);
        x += width + px(4.0f);
    }
}

void Radar::renderHistory(const game::RadarPlayer& player, const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    char header[64];
    std::snprintf(header, sizeof(header), "Seen items  %d", static_cast<int>(player.history.size()));
    gfx.textCap(FontRole::SemiBold, header, bounds.x, bounds.y - px(9.0f), px(8.0f),
                palette.text.withAlpha(alpha));

    historyHeight_ = px(static_cast<float>(player.history.size()) * (kHistoryRow + 2.0f));

    gfx.pushClip(bounds);
    /* Armour first, then hands, then everything else, newest sighting first
     * inside each group - which is the order somebody scanning this actually
     * wants: the kit, then what is in the hand, then the rest of the hotbar as
     * it leaks out over time. */
    std::vector<const game::RadarItem*> ordered;
    ordered.reserve(player.history.size());
    for (const game::RadarItem& item : player.history) {
        ordered.push_back(&item);
    }
    std::sort(ordered.begin(), ordered.end(),
              [](const game::RadarItem* a, const game::RadarItem* b) {
                  if (a->slot != b->slot) return a->slot < b->slot;
                  return a->lastSeen > b->lastSeen;
              });

    float y = bounds.y + historyScroll_;
    for (const game::RadarItem* item : ordered) {
        const Rect row{bounds.x, y, bounds.width, px(kHistoryRow)};
        y += px(kHistoryRow + 2.0f);
        if (row.bottom() < bounds.y || row.y > bounds.bottom()) {
            continue;
        }
        gfx.rect(row, palette.surface.withAlpha(alpha * 0.55f), px(palette.radiusCard));

        const Rect chip{row.x + px(3.5f), row.centerY() - px(kIconChip) * 0.5f, px(kIconChip),
                        px(kIconChip)};
        renderItemIcon(item->id, chip);

        char tally[24];
        std::snprintf(tally, sizeof(tally), "x%d", item->count);
        const float tallyWidth = gfx.measure(FontRole::Mono, tally, px(7.0f));
        gfx.textCap(FontRole::Mono, tally, row.right() - px(5.0f), row.centerY() - px(7.0f) * kCapMiddle,
                    px(7.0f), palette.textDim.withAlpha(alpha), Align::Right);

        const float textX = chip.right() + px(4.0f);
        const float width = row.right() - px(9.0f) - tallyWidth - textX;
        const std::string line = enchantLine(item->enchantments);
        if (line.empty()) {
            gfx.textCap(FontRole::Medium, gfx.elide(FontRole::Medium, item->name, px(7.8f), width),
                        textX, row.centerY() - px(7.8f) * kCapMiddle, px(7.8f),
                        palette.textBright.withAlpha(alpha));
        } else {
            gfx.textCap(FontRole::Medium, gfx.elide(FontRole::Medium, item->name, px(7.6f), width),
                        textX, row.y + px(4.0f), px(7.6f), palette.textBright.withAlpha(alpha));
            gfx.textCap(FontRole::Regular, gfx.elide(FontRole::Regular, line, px(6.4f), width),
                        textX, row.y + px(12.0f), px(6.4f), palette.accentDim.withAlpha(alpha));
        }
    }
    gfx.popClip();

    if (player.history.empty()) {
        gfx.textCap(FontRole::Regular, "Nothing seen yet", bounds.x, bounds.y + px(10.0f), px(7.4f),
                    palette.textFaint.withAlpha(alpha));
    }
    if (historyHeight_ > bounds.height + 1.0f) {
        const float ratio = bounds.height / historyHeight_;
        const float travel = bounds.height * (1.0f - ratio);
        const float offset = -historyScroll_ / (historyHeight_ - bounds.height);
        gfx.rect(Rect{bounds.right() + px(2.0f), bounds.y + travel * offset, px(2.0f),
                      bounds.height * ratio},
                 palette.scrollbar.withAlpha(alpha * 0.7f), px(1.0f));
    }
}

void Radar::renderItemIcon(const std::string& id, const Rect& bounds) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const float alpha = alpha_;

    JNIEnv* env = client().state().player.valid ? bridge::Jvm::instance().env() : nullptr;
    game::Game::SpriteView sprite;
    if (env != nullptr) {
        sprite = game::Game::instance().itemSprite(env, id);
    }
    if (sprite.valid) {
        gfx.image(bounds, Rect{sprite.u0, sprite.v0, sprite.u1 - sprite.u0, sprite.v1 - sprite.v0},
                  sprite.texture, Color::rgb(0xFFFFFF, alpha), 0.0f);
        return;
    }
    gfx.rect(bounds, palette.surfaceActive.withAlpha(alpha * 0.8f), px(2.0f));
    gfx.icon(iconFor(id), bounds.inset(px(2.6f)), palette.accentDim.withAlpha(alpha));
}

void Radar::renderDetail() {
    const Rect pane{panel_.x + px(kPaneLeft), panel_.y + px(kMargin), px(kPaneWidth),
                    px(kPanelHeight - kMargin * 2.0f)};
    const game::RadarPlayer* player =
        selected_.empty() ? nullptr : game::radar().find(selected_);
    if (player == nullptr) {
        renderEmpty(pane);
        return;
    }

    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();

    gfx.textCap(FontRole::Bold,
                gfx.elide(FontRole::Bold, player->name, px(kNameSize), pane.width - px(60.0f)),
                pane.x, panel_.y + px(kNameCapTop), px(kNameSize),
                palette.textBright.withAlpha(alpha_));
    if (!player->loaded) {
        gfx.textCap(FontRole::Regular, "last seen", pane.right(), panel_.y + px(kNameCapTop + 3.0f),
                    px(7.0f), palette.textFaint.withAlpha(alpha_), Align::Right);
    }

    renderStats(*player, Rect{pane.x, panel_.y + px(kStatsTop), px(kStatsWidth),
                              px(kStatHeight * 4.0f + kStatGap * 3.0f)});
    renderSkin(*player, skinBounds_);
    renderArmour(*player, Rect{panel_.x + px(kArmourLeft), panel_.y + px(kStatsTop),
                               px(kArmourWidth), px(kArmourRow * 4.0f + 4.5f)});
    renderHands(*player,
                Rect{pane.x, panel_.y + px(kHandsTop), pane.width, px(kHandsHeight)});
    renderEffects(*player,
                  Rect{pane.x, panel_.y + px(kEffectsTop), pane.width, px(kEffectsHeight)});
    renderHistory(*player, historyBounds_);
}

void Radar::typeCharacter(char32_t codepoint) {
    if (codepoint < 32 || codepoint > 126 || query_.size() >= 24) {
        return;
    }
    query_.push_back(static_cast<char>(codepoint));
    scrollTarget_ = 0.0f;
}

bool Radar::pressAt(float x, float y, int button, modules::Context& context) {
    (void)context;
    if (!panel_.contains(x, y)) {
        return false;
    }
    if (button != hooks::kMouseLeft) {
        return true;
    }

    if (closeBounds_.contains(x, y)) {
        close();
        return true;
    }
    if (sortBounds_.contains(x, y)) {
        sort_ = (sort_ + 1) % 3;
        return true;
    }
    if (skinBounds_.contains(x, y)) {
        turning_ = true;
        touched_ = true;
        grabYaw_ = modelYaw_;
        grabPitch_ = modelPitch_;
        turnFromX_ = x;
        turnFromY_ = y;
        return true;
    }
    if (listBounds_.contains(x, y)) {
        for (const Row& row : rows_) {
            if (row.bounds.contains(x, y)) {
                if (selected_ != row.uuid) {
                    selected_ = row.uuid;
                    historyTarget_ = 0.0f;
                    historyScroll_ = 0.0f;
                }
                return true;
            }
        }
        return true;
    }

    /* The title strip drags the window and nothing else does. Letting the panes
     * drag it means every mis-aimed click on a card slides the whole thing out
     * from under the pointer. */
    if (y < panel_.y + px(kSearchTop)) {
        dragging_ = true;
        dragOffsetX_ = x - panel_.x;
        dragOffsetY_ = y - panel_.y;
    }
    return true;
}

void Radar::handleEvent(const hooks::InputEvent& event, modules::Context& context) {
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
                pressAt(mouseX_, mouseY_, event.code, context);
            } else {
                dragging_ = false;
                turning_ = false;
            }
            break;

        case hooks::InputEvent::Kind::Scroll:
            if (historyBounds_.contains(mouseX_, mouseY_)) {
                historyTarget_ += static_cast<float>(event.y) * px(28.0f);
            } else if (listBounds_.contains(mouseX_, mouseY_)) {
                scrollTarget_ += static_cast<float>(event.y) * px(36.0f);
            }
            break;

        case hooks::InputEvent::Kind::Char:
            typeCharacter(static_cast<char32_t>(event.code));
            break;

        case hooks::InputEvent::Kind::Key:
            if (event.action == hooks::kRelease) {
                break;
            }
            if (event.code == hooks::kKeyBackspace && !query_.empty()) {
                query_.pop_back();
            } else if (event.code == hooks::kKeyEscape) {
                close();
            }
            break;

        default:
            break;
    }
}

}  // namespace cold::ui
