#include "ui/Hud.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "bridge/Minecraft.hpp"
#include "core/Build.hpp"
#include "core/Time.hpp"
#include "game/TargetFinder.hpp"
#include "modules/ModuleManager.hpp"
#include "modules/render/TargetHud.hpp"
#include "render/Renderer.hpp"
#include "ui/Theme.hpp"

namespace cold::ui {
namespace {

/* GUI-scaled units, measured off the reference. */
constexpr float kMargin = 2.0f;
constexpr float kMarkHeight = 14.0f;
constexpr float kMarkEdge = 2.0f;
constexpr float kLogoWidth = 16.0f;
constexpr float kMarkText = 9.0f;

constexpr float kCardWidth = 135.0f;
constexpr float kLabelHeight = 10.0f;
constexpr float kTargetLabel = 12.0f;
constexpr float kBodyHeight = 31.0f;
constexpr float kSkinSize = 27.0f;
constexpr float kNameSize = 9.0f;
constexpr float kSubSize = 8.0f;
constexpr float kDiffSize = 7.0f;
constexpr float kIconSize = 9.0f;
constexpr float kBarLeft = 32.0f;
constexpr float kBarRight = 132.0f;

constexpr float kRowHeight = 12.0f;
constexpr float kRowText = 9.0f;
constexpr float kRowPad = 3.0f;
constexpr float kListTop = 2.0f;

constexpr float kNoticeHeight = 12.0f;
constexpr float kNoticeTextSize = 8.0f;
constexpr float kNoticePad = 4.0f;
constexpr float kNoticeFade = 0.28f;

constexpr long long kHurtDuration = 400;

/* Editor chrome, in GUI units. The grip and the dot deliberately do not scale with
 * the element they belong to: a element shrunk to half size still needs a handle
 * big enough to hit. */
constexpr float kGripSize = 6.0f;
constexpr float kDotSize = 6.0f;
constexpr float kEditLabel = 7.0f;
constexpr float kMinSize = 0.5f;
constexpr float kMaxSize = 2.5f;

const Color kWatermarkBg = Color::rgb(0x000000, 100.0f / 255.0f);
const Color kLabelBg = Color::rgb(0x000000, 150.0f / 255.0f);
const Color kBodyBg = Color::rgb(0x000000, 120.0f / 255.0f);
const Color kTrackBg = Color::rgb(0x000000, 180.0f / 255.0f);
const Color kListBg = Color::rgb(0x000000, 130.0f / 255.0f);
const Color kHurtRed = Color::rgb(0xFF3C3C);

/* "FastPlace" reads as two words and the list writes it lowercased, one
 * colour per row, so a run of capitals stays whole: ESP does not come out as
 * e s p. */
std::string listName(std::string_view value) {
    std::string spaced;
    spaced.reserve(value.size() + 4);
    for (std::size_t index = 0; index < value.size(); ++index) {
        const char character = value[index];
        const bool upper = character >= 'A' && character <= 'Z';
        if (upper && index > 0) {
            const char previous = value[index - 1];
            const bool previousLower =
                (previous >= 'a' && previous <= 'z') || (previous >= '0' && previous <= '9');
            const bool startsWord = index + 1 < value.size() && value[index + 1] >= 'a' &&
                                    value[index + 1] <= 'z' && previous >= 'A' &&
                                    previous <= 'Z';
            if (previousLower || startsWord) {
                spaced.push_back(' ');
            }
        }
        spaced.push_back(upper ? static_cast<char>(character + 32) : character);
    }
    std::string out;
    out.reserve(spaced.size());
    for (char character : spaced) {
        if (character >= 'A' && character <= 'Z') {
            character = static_cast<char>(character + 32);
        }
        out.push_back(character);
    }
    return out;
}

std::string formatPlayTime(double seconds) {
    const long long total = static_cast<long long>(std::max(0.0, seconds));
    const long long minutes = total / 60;
    const long long hours = minutes / 60;
    char buffer[32];
    if (hours > 0) {
        std::snprintf(buffer, sizeof(buffer), "%lldh %lldm %llds", hours, minutes % 60,
                      total % 60);
    } else {
        std::snprintf(buffer, sizeof(buffer), "%lldm %llds", minutes, total % 60);
    }
    return buffer;
}

float hurtProgress(long long hurtAt) {
    if (hurtAt <= 0) {
        return 1.0f;
    }
    const long long elapsed = nowMillis() - hurtAt;
    if (elapsed >= kHurtDuration) {
        return 1.0f;
    }
    return std::max(0.0f, static_cast<float>(elapsed) / static_cast<float>(kHurtDuration));
}

Color darkerOnce(const Color& colour) {
    return Color{colour.r * 0.7f, colour.g * 0.7f, colour.b * 0.7f, colour.a};
}

}  // namespace

bool Hud::editMode(const game::FrameState& state) const {
    return state.inWorld && state.screenOpen && !menuOpen_;
}

float Hud::shadowed(render::FontRole role, std::string_view value, float x, float capTop,
                    float size, const Color& colour, render::Align align) {
    render::Renderer& gfx = render::renderer();
    gfx.textCap(role, value, x + px(1.0f), capTop + px(1.0f), size,
                Color::rgb(0x000000, 0.55f * colour.a), align);
    return gfx.textCap(role, value, x, capTop, size, colour, align);
}

void Hud::loadLayout(config::Document& config) {
    if (!config.hasSection("hud.layout")) {
        return;
    }
    markX_ = config.getNumber("hud.layout", "markX", markX_);
    markY_ = config.getNumber("hud.layout", "markY", markY_);
    playerX_ = config.getNumber("hud.layout", "playerX", playerX_);
    playerY_ = config.getNumber("hud.layout", "playerY", playerY_);
    playerPlaced_ = config.getBool("hud.layout", "playerPlaced", playerPlaced_);
    targetX_ = config.getNumber("hud.layout", "targetX", targetX_);
    targetY_ = config.getNumber("hud.layout", "targetY", targetY_);
    targetPlaced_ = config.getBool("hud.layout", "targetPlaced", targetPlaced_);
    listR_ = config.getNumber("hud.layout", "listR", listR_);
    listY_ = config.getNumber("hud.layout", "listY", listY_);
    speedX_ = config.getNumber("hud.layout", "speedX", speedX_);
    speedY_ = config.getNumber("hud.layout", "speedY", speedY_);
    speedPlaced_ = config.getBool("hud.layout", "speedPlaced", speedPlaced_);
    static const char* const kSizeKeys[kElements] = {"markSize", "listSize", "playerSize",
                                                     "targetSize", "speedSize"};
    for (int i = 0; i < kElements; ++i) {
        sizes_[i] = clamp(config.getNumber("hud.layout", kSizeKeys[i], sizes_[i]), kMinSize,
                          kMaxSize);
    }
}

void Hud::saveLayout(config::Document& config) const {
    config.setNumber("hud.layout", "markX", markX_, 1);
    config.setNumber("hud.layout", "markY", markY_, 1);
    config.setNumber("hud.layout", "playerX", playerX_, 1);
    config.setNumber("hud.layout", "playerY", playerY_, 1);
    config.setBool("hud.layout", "playerPlaced", playerPlaced_);
    config.setNumber("hud.layout", "targetX", targetX_, 1);
    config.setNumber("hud.layout", "targetY", targetY_, 1);
    config.setBool("hud.layout", "targetPlaced", targetPlaced_);
    config.setNumber("hud.layout", "listR", listR_, 1);
    config.setNumber("hud.layout", "listY", listY_, 1);
    config.setNumber("hud.layout", "speedX", speedX_, 1);
    config.setNumber("hud.layout", "speedY", speedY_, 1);
    config.setBool("hud.layout", "speedPlaced", speedPlaced_);
    static const char* const kSizeKeys[kElements] = {"markSize", "listSize", "playerSize",
                                                     "targetSize", "speedSize"};
    for (int i = 0; i < kElements; ++i) {
        config.setNumber("hud.layout", kSizeKeys[i], sizes_[i], 2);
    }
}

/* ------------------------------------------------------------------------- *
 * Editor
 * ------------------------------------------------------------------------- */

bool Hud::takeToggle(int& element) {
    if (pendingToggle_ < 0) {
        return false;
    }
    element = pendingToggle_;
    pendingToggle_ = -1;
    return true;
}

const char* Hud::labelOf(HudElement id) const {
    switch (id) {
        case HudElement::Watermark: return "Watermark";
        case HudElement::ArrayList: return "Module list";
        case HudElement::PlayerInfo: return "Player info";
        case HudElement::TargetInfo: return "Target info";
        case HudElement::Speed: return "Speed / XYZ";
        default: return "";
    }
}

bool Hud::shownOf(HudElement id) const {
    switch (id) {
        case HudElement::Watermark: return watermark_;
        case HudElement::ArrayList: return arrayList_;
        case HudElement::PlayerInfo: return playerInfo_;
        case HudElement::TargetInfo: return targetInfo_;
        case HudElement::Speed: return speed_;
        default: return false;
    }
}

float& Hud::sizeOf(HudElement id) {
    return sizes_[clamp(index(id), 0, kElements - 1)];
}

float Hud::sizeOf(HudElement id) const {
    return sizes_[clamp(index(id), 0, kElements - 1)];
}

Rect Hud::rectOf(HudElement id) const {
    switch (id) {
        case HudElement::Watermark: return markRect_;
        case HudElement::ArrayList: return listRect_;
        case HudElement::PlayerInfo: return playerRect_;
        case HudElement::TargetInfo: return targetRect_;
        case HudElement::Speed: return speedRect_;
        default: return Rect{};
    }
}

/* Whether the element put a rectangle on screen this frame and can therefore be
 * picked up. The list, target and speed readouts each have their own reason to
 * produce nothing, so they say so explicitly; the other two are known by their
 * rectangle alone. */
bool Hud::liveOf(HudElement id) const {
    switch (id) {
        case HudElement::ArrayList: return listDrawn_ && listRect_.width > 0.0f;
        case HudElement::TargetInfo: return targetDrawn_ && targetRect_.width > 0.0f;
        case HudElement::Speed: return speedDrawn_ && speedRect_.width > 0.0f;
        default: return rectOf(id).width > 0.0f;
    }
}

/* The grip sits on the bottom-right corner, half in and half out, the way a
 * resize handle does everywhere else. Kept a fixed size in GUI units rather than
 * scaling with the element, so a shrunk element does not end up with a grip bigger
 * than itself. */
Rect Hud::gripOf(const Rect& bounds) const {
    const float side = pos(kGripSize);
    return Rect{bounds.right() - side * 0.5f, bounds.bottom() - side * 0.5f, side, side};
}

/* The dot sits just above the top-left corner, outside the body, so it neither
 * covers content nor competes with a drag on the element itself.
 *
 * Unless there is no room above it, which is the common case and not the corner
 * case: the watermark and the module list both default to two units from the top, so
 * placing the dot outside would put it off screen and leave those two impossible to
 * switch off. It then tucks inside the element instead. */
Rect Hud::dotOf(const Rect& bounds) const {
    const float side = pos(kDotSize);
    const float gap = pos(1.5f);
    const float above = bounds.y - side - gap;
    return above >= 0.0f ? Rect{bounds.x, above, side, side}
                         : Rect{bounds.x + gap, bounds.y + gap, side, side};
}

Hud::Grab Hud::pick(HudElement& id) const {
    /* Front to back, and controls before bodies: the grip and the dot are small and
     * overlap their own element, so testing the body first would make them
     * unreachable. */
    for (int i = kElements - 1; i >= 0; --i) {
        const HudElement candidate = static_cast<HudElement>(i);
        if (!liveOf(candidate)) {
            continue;
        }
        const Rect bounds = rectOf(candidate);
        if (dotOf(bounds).contains(mouseX_, mouseY_)) {
            id = candidate;
            return Grab::Toggle;
        }
        if (gripOf(bounds).contains(mouseX_, mouseY_)) {
            id = candidate;
            return Grab::Resize;
        }
    }
    for (int i = kElements - 1; i >= 0; --i) {
        const HudElement candidate = static_cast<HudElement>(i);
        if (liveOf(candidate) && rectOf(candidate).contains(mouseX_, mouseY_)) {
            id = candidate;
            return Grab::Move;
        }
    }
    id = HudElement::Count;
    return Grab::None;
}

void Hud::handleEvent(const hooks::InputEvent& event, const modules::Context& context) {
    const game::FrameState& state = *context.state;
    const float scaleX = state.logicalWidth > 0 ? static_cast<float>(state.windowWidth) /
                                                      static_cast<float>(state.logicalWidth)
                                                : 1.0f;
    const float scaleY = state.logicalHeight > 0 ? static_cast<float>(state.windowHeight) /
                                                       static_cast<float>(state.logicalHeight)
                                                 : 1.0f;
    const float scaledW = state.scaledWidth > 0
                              ? static_cast<float>(state.scaledWidth)
                              : static_cast<float>(state.windowWidth) / unit_;
    const float scaledH = state.scaledHeight > 0
                              ? static_cast<float>(state.scaledHeight)
                              : static_cast<float>(state.windowHeight) / unit_;

    switch (event.kind) {
        case hooks::InputEvent::Kind::MouseMove:
            mouseX_ = static_cast<float>(event.x) * scaleX;
            mouseY_ = static_cast<float>(event.y) * scaleY;
            if (!editMode(state)) {
                break;
            }
            if (grab_ == Grab::Move) {
                const float x = clamp(mouseX_ / unit_ - dragOffX_, 0.0f, scaledW);
                const float y = clamp(mouseY_ / unit_ - dragOffY_, 0.0f, scaledH);
                switch (grabbed_) {
                    case HudElement::Watermark:
                        markX_ = x;
                        markY_ = y;
                        break;
                    case HudElement::PlayerInfo:
                        playerX_ = x;
                        playerY_ = y;
                        playerPlaced_ = true;
                        break;
                    case HudElement::TargetInfo:
                        targetX_ = x;
                        targetY_ = y;
                        targetPlaced_ = true;
                        break;
                    case HudElement::ArrayList:
                        /* Anchored to the right edge, like the list itself. */
                        listR_ = clamp((static_cast<float>(state.windowWidth) - mouseX_) /
                                           unit_ -
                                       dragOffX_,
                                       -20.0f, scaledW);
                        listY_ = y;
                        break;
                    case HudElement::Speed:
                        speedX_ = x;
                        speedY_ = y;
                        speedPlaced_ = true;
                        break;
                    default:
                        break;
                }
            } else if (grab_ == Grab::Resize && grabbed_ != HudElement::Count) {
                /* Project the drag onto the element's own diagonal.
                 *
                 * Taking the width ratio alone would ignore vertical movement, and
                 * taking whichever axis moved more would jump between them mid-drag.
                 * The least-squares projection onto the diagonal is what "drag the
                 * corner, keep the aspect" means, and it behaves the same whether the
                 * element is a wide bar or a squat card. */
                const Rect bounds = rectOf(grabbed_);
                const float wantW = mouseX_ - bounds.x;
                const float wantH = mouseY_ - bounds.y;
                const float denom = grabBaseW_ * grabBaseW_ + grabBaseH_ * grabBaseH_;
                if (denom > 1.0f) {
                    const float factor = (wantW * grabBaseW_ + wantH * grabBaseH_) / denom;
                    sizeOf(grabbed_) = clamp(factor, kMinSize, kMaxSize);
                }
            } else {
                hotKind_ = pick(hot_);
            }
            break;

        case hooks::InputEvent::Kind::MouseButton:
            mouseX_ = static_cast<float>(event.x) * scaleX;
            mouseY_ = static_cast<float>(event.y) * scaleY;
            if (event.action == hooks::kPress) {
                if (event.code != hooks::kMouseLeft || !editMode(state)) {
                    break;
                }
                /* Picked fresh on the press rather than trusting the last move: a click
                 * without a preceding move over the control - the pointer parked where
                 * an element has just appeared - would otherwise do nothing. */
                HudElement id = HudElement::Count;
                const Grab kind = pick(id);
                hot_ = id;
                hotKind_ = kind;
                if (kind == Grab::Toggle) {
                    /* Handed to HudModule, which owns the setting that persists. */
                    pendingToggle_ = index(id);
                    break;
                }
                if (kind == Grab::None) {
                    break;
                }
                grab_ = kind;
                grabbed_ = id;
                const Rect bounds = rectOf(id);
                if (kind == Grab::Resize) {
                    /* Unscaled pixel size, so the projection above is measured against
                     * a fixed reference instead of one that moves as it is edited. */
                    grabScale_ = sizeOf(id);
                    const float safe = grabScale_ > 0.01f ? grabScale_ : 1.0f;
                    grabBaseW_ = bounds.width / safe;
                    grabBaseH_ = bounds.height / safe;
                } else if (id == HudElement::ArrayList) {
                    dragOffX_ =
                        (static_cast<float>(state.windowWidth) - mouseX_) / unit_ - listR_;
                    dragOffY_ = mouseY_ / unit_ - listY_;
                } else {
                    dragOffX_ = (mouseX_ - bounds.x) / unit_;
                    dragOffY_ = (mouseY_ - bounds.y) / unit_;
                }
            } else {
                grab_ = Grab::None;
                grabbed_ = HudElement::Count;
            }
            break;

        case hooks::InputEvent::Kind::Scroll:
        case hooks::InputEvent::Kind::Key:
        case hooks::InputEvent::Kind::Char:
            break;
    }
}

void Hud::drawWatermark(modules::Context& context, bool edit) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const game::FrameState& state = *context.state;

    if (sessionStart_ < 0.0) {
        sessionStart_ = nowSeconds();
    }

    std::string server = game::Game::instance().serverAddress(context.env);
    if (server.empty()) {
        server = "Singleplayer";
    }
    char numbers[48];
    if (state.ping >= 0) {
        std::snprintf(numbers, sizeof(numbers), "%d FPS %dms", state.fps, state.ping);
    } else {
        std::snprintf(numbers, sizeof(numbers), "%d FPS", state.fps);
    }
    const std::string text =
        std::string(kClientName) + " " + std::string(kClientVersion) + " (" + build::kFlavour +
        ") " + server + " " + numbers;

    const float size = px(kMarkText);
    const float textWidth = gfx.measure(render::FontRole::Medium, text, size);
    const float width = px(kMargin + kLogoWidth + kMargin + 2.0f) + textWidth;
    /* pos(), not px(): the corner it is pinned to must not move when it is resized. */
    const float x = pos(markX_);
    const float y = pos(markY_);
    const float height = px(kMarkHeight);
    markRect_ = Rect{x, y, width, height};

    gfx.rect(Rect{x, y, width, height}, kWatermarkBg, 0.0f);
    gfx.rect(Rect{x, y, width, px(kMarkEdge)}, palette.accent, 0.0f);

    const float logoSize = px(10.0f);
    gfx.icon(assets::IconAsset::Zap,
             Rect{x + px(kMargin + 3.0f), y + (height - logoSize) * 0.5f, logoSize,
                  logoSize},
             palette.accent);
    const float capTop = y + (height - size * 0.727f) * 0.5f - px(1.0f);
    shadowed(render::FontRole::Medium, text, x + px(kMargin + kLogoWidth + kMargin + 2.0f),
             capTop, size, palette.textBright);
    (void)edit;  // the editor's chrome is drawn from render(), on top of everything
}

void Hud::drawFace(modules::Context& context, jobject handle, const Rect& bounds, float alpha,
                   float push, float tint) {
    (void)context;
    render::Renderer& gfx = render::renderer();
    if (handle == nullptr) {
        gfx.rect(bounds, Color::rgb(0x141414, alpha), 0.0f);
        gfx.icon(assets::IconAsset::UserRound, bounds.inset(bounds.width * 0.18f),
                 Color::rgb(0xAAAAAA, alpha));
        return;
    }
    game::Game::SkinView skin = game::Game::instance().playerSkin(context.env, handle);
    const float scale = 1.0f - push;
    const Rect face{bounds.centerX() - bounds.width * 0.5f * scale,
                    bounds.centerY() - bounds.height * 0.5f * scale,
                    bounds.width * scale, bounds.height * scale};
    if (skin.texture != 0) {
        const Color shade{1.0f, 1.0f - tint, 1.0f - tint, alpha};
        gfx.imageCrisp(face, Rect{8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f},
                       skin.texture, shade);
        gfx.imageCrisp(face, Rect{40.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f},
                       skin.texture, shade);
    } else {
        gfx.rect(face, Color::rgb(0x141414, alpha), 0.0f);
        gfx.icon(assets::IconAsset::UserRound, face.inset(face.width * 0.18f),
                 Color::rgb(0xAAAAAA, alpha));
    }
}

void Hud::drawEquipment(modules::Context& context, jobject handle, float x, float y,
                        float alpha) {
    if (handle == nullptr) {
        return;
    }
    render::Renderer& gfx = render::renderer();
    static constexpr game::Game::Slot kSlots[] = {
        game::Game::Slot::MainHand, game::Game::Slot::OffHand, game::Game::Slot::Head,
        game::Game::Slot::Chest, game::Game::Slot::Legs, game::Game::Slot::Feet,
    };
    const float size = px(kIconSize);
    float cursor = x;
    for (game::Game::Slot slot : kSlots) {
        game::Game::EquipmentView view =
            game::Game::instance().equipment(context.env, handle, slot);
        if (!view.present) {
            continue;
        }
        game::Game::SpriteView sprite =
            game::Game::instance().itemSprite(context.env, view.id);
        const Rect bounds{cursor, y, size, size};
        if (sprite.valid) {
            gfx.image(bounds,
                      Rect{sprite.u0, sprite.v0, sprite.u1 - sprite.u0, sprite.v1 - sprite.v0},
                      sprite.texture, Color::rgb(0xFFFFFF, alpha), 0.0f);
        } else {
            gfx.rect(bounds, Color::rgb(0x141414, alpha), 0.0f);
        }
        cursor += size + px(1.0f);
        if (cursor - x > px(70.0f)) {
            break;
        }
    }
}

void Hud::drawSpeedReadout(modules::Context& context, bool edit) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const game::FrameState& state = *context.state;
    if (!state.player.valid) {
        speedDrawn_ = false;
        hasLastPos_ = false;
        speedBps_ = 0.0f;
        return;
    }

    /* Horizontal speed from consecutive positions. A frame is not a tick, so
     * the raw quotient jitters with frame pacing; a short exponential smooth
     * keeps the number readable without lagging a stop. */
    if (hasLastPos_) {
        const double dx = state.player.pos.x - lastPosX_;
        const double dz = state.player.pos.z - lastPosZ_;
        const float dt = context.deltaTime;
        if (dt > 0.0005f) {
            const float raw = static_cast<float>(std::sqrt(dx * dx + dz * dz) / dt);
            speedBps_ += (raw - speedBps_) * std::min(1.0f, dt * 12.0f);
        }
    } else {
        speedBps_ = 0.0f;
    }
    lastPosX_ = state.player.pos.x;
    lastPosY_ = state.player.pos.y;
    lastPosZ_ = state.player.pos.z;
    hasLastPos_ = true;

    char line[96];
    std::snprintf(line, sizeof(line), "%.1f bps  xyz %.1f / %.1f / %.1f", speedBps_,
                  state.player.pos.x, state.player.pos.y, state.player.pos.z);

    const float size = px(kSubSize);
    const float rowHeight = px(kRowHeight);
    const float width =
        gfx.measure(render::FontRole::Medium, line, size) + px(kRowPad * 2.0f);
    const float x = speedPlaced_ ? pos(speedX_) : pos(6.0f);
    const float y = speedPlaced_
                        ? pos(speedY_)
                        : static_cast<float>(state.windowHeight) - pos(6.0f) - rowHeight;
    speedRect_ = Rect{x, y, width, rowHeight};
    speedDrawn_ = true;

    gfx.rect(speedRect_, kListBg, 0.0f);
    gfx.rect(Rect{x, y + rowHeight - px(1.0f), width, px(1.0f)}, palette.accent, 0.0f);
    const float capTop = y + (rowHeight - size * 0.727f) * 0.5f;
    shadowed(render::FontRole::Medium, line, x + px(kRowPad), capTop, size,
             palette.textBright);
    (void)edit;
}

/* Frame, name, state dot and corner grip for one element.
 *
 * Drawn from render() once every element has laid itself out, rather than by each
 * element at the end of its own draw, for two reasons: the chrome then sits on top
 * of everything instead of under whatever draws next, and it is drawn with the
 * global unit while drawScale_ is reset, so a grip on an element shrunk to half
 * size is still a grip you can hit. */
void Hud::drawEditChrome(HudElement id, const Rect& bounds, bool shown) {
    if (bounds.width <= 0.0f || bounds.height <= 0.0f) {
        return;
    }
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const bool held = grabbed_ == id && grab_ != Grab::None;
    const bool hot = hot_ == id || held;

    /* Switched off: the body still draws, because an invisible element cannot be
     * switched back on, but it is veiled so there is no reading the screen as though
     * this were live. */
    if (!shown) {
        gfx.rect(bounds, Color::rgb(0x05070A, 0.62f), 0.0f);
    }

    gfx.outline(bounds, palette.accent.withAlpha(hot ? 1.0f : 0.32f), 1.0f, 0.0f);

    const Rect dot = dotOf(bounds);
    const float labelSize = pos(kEditLabel);
    /* On a backing plate, because when the dot has had to tuck inside the element the
     * name lands on top of live content. */
    const char* label = labelOf(id);
    const float labelX = dot.right() + pos(2.0f);
    const float labelW = gfx.measure(render::FontRole::Medium, label, labelSize);
    gfx.rect(Rect{labelX - pos(1.0f), dot.y, labelW + pos(2.0f), dot.height},
             Color::rgb(0x05070A, 0.66f), pos(1.0f));
    shadowed(render::FontRole::Medium, label, labelX,
             dot.y + (dot.height - labelSize * 0.727f) * 0.5f, labelSize,
             Color::rgb(0xFFFFFF, shown ? (hot ? 0.95f : 0.72f) : 0.42f));

    /* Filled while the element is on, a ring while it is off. */
    const bool dotHot = hot_ == id && hotKind_ == Grab::Toggle;
    gfx.rect(dot, Color::rgb(0x05070A, 0.80f), pos(1.0f));
    if (shown) {
        gfx.rect(dot.inset(pos(1.3f)), palette.accent.withAlpha(dotHot ? 1.0f : 0.85f),
                 pos(1.0f));
    } else {
        gfx.outline(dot.inset(pos(1.3f)), Color::rgb(0xFFFFFF, dotHot ? 0.95f : 0.45f), 1.0f,
                    pos(1.0f));
    }

    const Rect grip = gripOf(bounds);
    const bool gripHot = (hot_ == id && hotKind_ == Grab::Resize) ||
                         (held && grab_ == Grab::Resize);
    gfx.rect(grip, Color::rgb(0x05070A, 0.80f), pos(1.0f));
    gfx.rect(grip.inset(pos(1.1f)), palette.accent.withAlpha(gripHot ? 1.0f : 0.70f),
             pos(0.8f));

    /* The one number in the editor, and only while the gesture is in progress. It is
     * feedback on a drag, not a way to set the value - which is the difference
     * between this and the X/Y fields it replaces. */
    if (held && grab_ == Grab::Resize) {
        char text[16];
        std::snprintf(text, sizeof(text), "%.0f%%",
                      static_cast<double>(sizeOf(id) * 100.0f));
        shadowed(render::FontRole::Bold, text, grip.x - pos(2.5f),
                 grip.y - pos(kEditLabel + 1.5f), labelSize, Color::rgb(0xFFFFFF),
                 render::Align::Right);
    }
}

void Hud::drawEditHint(const game::FrameState& state) {
    render::Renderer& gfx = render::renderer();
    const char* line = "HUD editor    drag to move    corner to resize    dot to toggle";
    const float size = pos(kEditLabel + 1.0f);
    const float height = pos(12.0f);
    const float width = gfx.measure(render::FontRole::Medium, line, size) + pos(10.0f);
    const float x = (static_cast<float>(state.windowWidth) - width) * 0.5f;
    const float y = static_cast<float>(state.windowHeight) - height - pos(5.0f);
    gfx.rect(Rect{x, y, width, height}, Color::rgb(0x05070A, 0.74f), pos(2.0f));
    gfx.rect(Rect{x, y, pos(1.5f), height}, theme().accent, 0.0f);
    shadowed(render::FontRole::Medium, line, x + pos(6.0f),
             y + (height - size * 0.727f) * 0.5f, size, Color::rgb(0xFFFFFF, 0.92f));
}

void Hud::drawPlayerInfo(modules::Context& context, bool edit, float& below) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const game::FrameState& state = *context.state;
    if (!state.player.valid) {
        return;
    }

    const float health = state.player.health;
    const float maxHealth = state.player.maxHealth > 0.0f ? state.player.maxHealth : 20.0f;
    if (playerHealth_ < 0.0f) {
        playerHealth_ = health;
        playerActual_ = health;
    }
    if (health < playerActual_ - 0.01f) {
        playerHurtAt_ = nowMillis();
        playerActual_ = health;
    } else if (health > playerActual_ + 0.01f) {
        playerActual_ = health;
    }
    playerHealth_ += (health - playerHealth_) *
                     std::min(1.0f, context.deltaTime * 10.0f);

    const std::string playTime = formatPlayTime(nowSeconds() - sessionStart_);
    const float nameSize = px(kNameSize);
    const float playWidth = gfx.measure(render::FontRole::Medium, playTime, px(kSubSize));
    /* playWidth is already in pixels at this element's scale, so dividing it back out
     * has to use the same scale px() multiplied it by, not the bare unit. */
    const float drawUnit = unit_ * drawScale_;
    const float hudUnits = std::max(kCardWidth, kBarLeft + playWidth / drawUnit + 5.0f);
    const float width = px(hudUnits);
    const float scaledH = static_cast<float>(state.windowHeight) / unit_;
    const float x = playerPlaced_ ? pos(playerX_)
                                  : (static_cast<float>(state.windowWidth) - width) * 0.5f;
    const float y = playerPlaced_ ? pos(playerY_) : scaledH / 10.0f * unit_;
    playerRect_ = Rect{x, y, width, px(kLabelHeight + kBodyHeight)};

    gfx.rect(Rect{x, y, width, px(kLabelHeight)}, kLabelBg, 0.0f);
    const std::string label = "Player Info";
    const float labelWidth = gfx.measure(render::FontRole::Medium, label, nameSize);
    shadowed(render::FontRole::Medium, label, x + (width - labelWidth) * 0.5f,
             y + px(1.0f), nameSize, Color::rgb(0xFFFFFF));

    const float bodyY = y + px(kLabelHeight);
    gfx.rect(Rect{x, bodyY, width, px(kBodyHeight)}, kBodyBg, 0.0f);

    const float progress = hurtProgress(playerHurtAt_);
    const float push = progress < 1.0f ? std::sin(progress * 3.14159f) * 0.18f : 0.0f;
    const float tint = progress < 1.0f ? (1.0f - progress) * 0.7f : 0.0f;
    drawFace(context, state.player.handle,
             Rect{x + px(2.0f), bodyY + px(2.0f), px(kSkinSize), px(kSkinSize)}, 1.0f, push,
             tint);

    shadowed(render::FontRole::Medium,
             game::Game::instance().entityName(context.env, state.player.handle),
             x + px(kBarLeft), bodyY + px(2.0f), nameSize, Color::rgb(0xFFFFFF));
    shadowed(render::FontRole::Medium, playTime, x + px(kBarLeft), bodyY + px(13.5f),
             px(kSubSize), Color::rgb(0xFFFFFF));

    const float fraction = clamp(playerHealth_ / maxHealth, 0.0f, 1.0f);
    const Rect bar{x + px(kBarLeft), bodyY + px(kBodyHeight) - px(8.0f),
                   px(kBarRight - kBarLeft), px(6.0f)};
    gfx.rect(bar, kTrackBg, 0.0f);
    if (fraction > 0.001f) {
        gfx.rectHorizontal(Rect{bar.x, bar.y, bar.width * fraction, bar.height},
                           palette.accent, darkerOnce(palette.accent), 0.0f);
    }
    if (tint > 0.0f && fraction > 0.001f) {
        gfx.rect(Rect{bar.x, bar.y, bar.width * fraction, bar.height},
                 kHurtRed.withAlpha(tint * 0.35f), 0.0f);
    }

    below = bodyY + px(kBodyHeight);
    (void)edit;
}

void Hud::drawTargetInfo(modules::Context& context, bool edit, float top) {
    auto* targetHud = modules::modules().get<modules::TargetHud>();
    if (targetHud != nullptr && targetHud->enabled()) {
        targetDrawn_ = false;
        return;
    }
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();
    const game::FrameState& state = *context.state;

    const game::EntityView* target = nullptr;
    const int id = game::CombatTarget::instance().current(nowSeconds());
    if (id >= 0) {
        for (const game::EntityView& entity : state.entities) {
            if (entity.id == id && entity.health > 0.0f) {
                target = &entity;
                break;
            }
        }
    }

    const float step = 1.0f - std::exp(-9.0f * context.deltaTime);
    if (target != nullptr) {
        if (target->id != targetId_) {
            targetId_ = target->id;
            targetHealth_ = -1.0f;
            targetHurtAt_ = 0;
        }
        targetAlpha_ += (1.0f - targetAlpha_) * step;
    } else {
        targetAlpha_ -= targetAlpha_ * step;
        if (targetAlpha_ < 0.01f) {
            targetId_ = -1;
        }
    }

    /* Edit mode with nothing targeted draws an empty frame, not a copy of your own
     * stats. Standing in with the player's data put two cards on screen carrying the
     * same name and a "+0.0" health difference against yourself, which read as a
     * duplicate player card rather than as a target card waiting for a target. The
     * frame alone is still something to move and resize, which was the only reason
     * the stand-in existed. */
    const bool ghost = target == nullptr;
    const float alpha = target != nullptr ? targetAlpha_ : 0.0f;
    if (ghost && !edit) {
        targetDrawn_ = false;
        return;
    }
    if (!ghost && alpha < 0.01f) {
        targetDrawn_ = false;
        return;
    }
    if (ghost) {
        /* Nominal size, so the frame is where the card will be. */
        const float ghostW = px(kCardWidth);
        const float ghostH = px(kBodyHeight);
        const float ghostX = targetPlaced_
                                 ? pos(targetX_)
                                 : (static_cast<float>(state.windowWidth) - ghostW) * 0.5f;
        const float ghostY = targetPlaced_ ? pos(targetY_) : top + px(4.0f);
        targetRect_ = Rect{ghostX, ghostY, ghostW, ghostH};
        targetDrawn_ = true;
        gfx.rect(targetRect_, kBodyBg.withAlpha(0.45f), 0.0f);
        const float hint = px(kSubSize);
        const std::string waiting = "No target";
        const float hintWidth = gfx.measure(render::FontRole::Medium, waiting, hint);
        shadowed(render::FontRole::Medium, waiting,
                 ghostX + (ghostW - hintWidth) * 0.5f,
                 ghostY + (ghostH - hint * 0.727f) * 0.5f, hint,
                 Color::rgb(0xFFFFFF, 0.45f));
        return;
    }

    /* Past the ghost branch there is always a target. */
    const float health = target->health + target->absorption;
    const float maxHealth = target->maxHealth > 0.0f ? target->maxHealth : 20.0f;
    jobject handle = target->handle;
    const bool isPlayer = target->isPlayer;
    std::string name = game::Game::instance().entityName(context.env, handle);
    if (name.empty()) {
        name = isPlayer ? "player" : "entity";
    }
    if (targetHealth_ < 0.0f) {
        targetHealth_ = health;
        targetActual_ = health;
    }
    if (health < targetActual_ - 0.01f) {
        targetHurtAt_ = nowMillis();
        targetActual_ = health;
    } else if (health > targetActual_ + 0.01f) {
        targetActual_ = health;
    }
    targetHealth_ += (health - targetHealth_) * std::min(1.0f, context.deltaTime * 10.0f);
    const float display = targetHealth_ >= 0.0f ? targetHealth_ : health;

    const float width = px(kCardWidth);
    const float x = targetPlaced_ ? pos(targetX_)
                                  : (static_cast<float>(state.windowWidth) - width) * 0.5f;
    const float y = targetPlaced_ ? pos(targetY_) : top + px(4.0f);
    targetRect_ = Rect{x, y, width, px(kBodyHeight)};
    targetDrawn_ = true;

    gfx.rect(Rect{x, y, width, px(kBodyHeight)}, kBodyBg.withAlpha(alpha), 0.0f);

    const float progress = hurtProgress(targetHurtAt_);
    const float push = progress < 1.0f ? std::sin(progress * 3.14159f) * 0.18f : 0.0f;
    const float tint = progress < 1.0f ? (1.0f - progress) * 0.7f : 0.0f;
    if (isPlayer || handle == nullptr) {
        drawFace(context, handle,
                 Rect{x + px(2.0f), y + px(2.0f), px(kSkinSize), px(kSkinSize)}, alpha,
                 push, tint);
    } else {
        const Rect face{x + px(2.0f), y + px(2.0f), px(kSkinSize), px(kSkinSize)};
        gfx.rect(face, Color::rgb(0x141414, alpha), 0.0f);
        gfx.icon(assets::IconAsset::UserRound, face.inset(face.width * 0.18f),
                 Color::rgb(0xAAAAAA, alpha));
    }

    const float nameSize = px(kNameSize);
    shadowed(render::FontRole::Medium, name, x + px(kBarLeft), y + px(2.0f), nameSize,
             Color::rgb(0xFFFFFF, alpha));

    char diffText[24];
    {
        const float ownHealth = state.player.health;
        std::snprintf(diffText, sizeof(diffText), "%s%.1f", display > ownHealth ? "-" : "+",
                      static_cast<double>(std::fabs(display - ownHealth)));
    }
    shadowed(render::FontRole::Medium, diffText, x + px(kBarRight), y + px(15.0f),
             px(kDiffSize), Color::rgb(0xFFFFFF, alpha), render::Align::Right);

    if (handle != nullptr) {
        drawEquipment(context, handle, x + px(kBarLeft), y + px(kTargetLabel - 2.0f),
                      alpha);
    }

    const float fraction = clamp(display / maxHealth, 0.0f, 1.0f);
    const Rect bar{x + px(kBarLeft), y + px(23.0f), px(kBarRight - kBarLeft), px(6.0f)};
    gfx.rect(bar, kTrackBg.withAlpha(alpha), 0.0f);
    if (fraction > 0.001f) {
        gfx.rectHorizontal(Rect{bar.x, bar.y, bar.width * fraction, bar.height},
                           palette.accent.withAlpha(alpha),
                           darkerOnce(palette.accent).withAlpha(alpha), 0.0f);
    }
    if (tint > 0.0f && fraction > 0.001f) {
        gfx.rect(Rect{bar.x, bar.y, bar.width * fraction, bar.height},
                 kHurtRed.withAlpha(tint * 0.35f * alpha), 0.0f);
    }
}

void Hud::drawArrayList(modules::Context& context, bool edit) {
    render::Renderer& gfx = render::renderer();
    const Palette& palette = theme();

    const float size = px(kRowText);
    const float rowHeight = px(kRowHeight);
    const float screenW = static_cast<float>(context.state->windowWidth);
    /* pos(): the margin from the right edge is a position, so resizing the list must
     * not walk it across the screen. */
    const float rightEdge = screenW - pos(listR_);
    const float blend = 1.0f - std::exp(-14.0f * context.deltaTime);

    struct Visible {
        modules::Module* module;
        std::string text;
        std::string suffix;
        float width;
    };
    std::vector<Visible> visible;

    for (auto& [module, entry] : entries_) {
        entry.seen = false;
    }

    for (const std::unique_ptr<modules::Module>& module : modules::modules().all()) {
        ListEntry& entry = entries_[module.get()];
        if (!module->enabled() && entry.alpha < 0.004f) {
            continue;
        }
        entry.seen = true;

        /* The client's own chrome has nothing to say in its own list. */
        const std::string& name = module->name();
        if (name == "ClickGUI" || name == "HUD") {
            continue;
        }

        Visible item;
        item.module = module.get();
        item.text = listName(name);
        const std::string suffix = module->suffix();
        if (!suffix.empty()) {
            item.suffix = " " + listName(suffix);
        }
        item.width = gfx.measure(render::FontRole::Medium, item.text, size) +
                     gfx.measure(render::FontRole::Medium, item.suffix, size);
        entry.width = item.width;
        visible.push_back(std::move(item));
    }

    /* Widest first: the block gets a clean diagonal edge instead of a ragged
     * one, which is the whole point of an array list. */
    std::sort(visible.begin(), visible.end(),
              [](const Visible& a, const Visible& b) { return a.width > b.width; });

    struct Placed {
        Visible item;
        float x;
        float y;
        float alpha;
    };
    std::vector<Placed> placed;
    float y = pos(listY_);
    for (const Visible& item : visible) {
        ListEntry& entry = entries_[item.module];
        const float targetAlpha = item.module->enabled() ? 1.0f : 0.0f;
        const float targetOffset = item.module->enabled() ? 0.0f : 1.0f;
        entry.alpha += (targetAlpha - entry.alpha) * blend;
        entry.offset += (targetOffset - entry.offset) * blend;
        entry.y = entry.y <= 0.0f ? y : entry.y + (y - entry.y) * blend;
        if (std::fabs(y - entry.y) < 0.5f) {
            entry.y = y;
        }
        if (entry.alpha > 0.004f) {
            const float slide =
                ease::outQuint(clamp(entry.offset, 0.0f, 1.0f)) * (item.width + px(8.0f));
            /* Settled, the text ends one pad short of the edge and the
             * background runs to it, exactly like the reference. */
            placed.push_back(Placed{item,
                                    std::round(rightEdge + slide - item.width - px(kRowPad)),
                                    std::round(entry.y), entry.alpha});
        }
        y += rowHeight;
    }

    listDrawn_ = false;
    if (!placed.empty()) {
        float minX = rightEdge;
        for (const Placed& row : placed) {
            minX = std::min(minX, row.x - px(kRowPad) - 1.0f);
        }
        const float lastBottom = placed.back().y + rowHeight + 1.0f;
        listRect_ = Rect{minX, placed.front().y, rightEdge + px(kRowPad) - minX,
                         lastBottom - placed.front().y};
        listDrawn_ = true;
    } else if (edit) {
        /* Something to grab when every module is off. */
        const float boxW = px(90.0f);
        const float boxH = px(kRowHeight);
        listRect_ = Rect{rightEdge - boxW, y, boxW, boxH};
        gfx.rect(listRect_, Color::rgb(0x000000, 0.25f), 0.0f);
        shadowed(render::FontRole::Medium, "module list", listRect_.x + px(kRowPad),
                 listRect_.y + (boxH - size * 0.727f) * 0.5f, size,
                 Color::rgb(0xAAAAAA, 0.8f));
        listDrawn_ = true;
    }

    const Color light = Color::mix(palette.accent, Color::rgb(0xFFFFFF), 0.35f);
    for (std::size_t i = 0; i < placed.size(); ++i) {
        const Placed& row = placed[i];

        const float pad = px(kRowPad);
        const float fade =
            0.5f + 0.5f * std::sin(context.time * 2.2f - static_cast<float>(i) * 0.55f);
        const Color colour = Color::mix(palette.accent, light, fade);

        gfx.rect(Rect{row.x - pad, row.y, (rightEdge - row.x) + pad, rowHeight},
                 kListBg.withAlpha(row.alpha), 0.0f);

        const float capTop = row.y + (rowHeight - size * 0.727f) * 0.5f;
        const float nameWidth = gfx.measure(render::FontRole::Medium, row.item.text, size);
        /* row.x is the left edge of the text; the background was placed
         * around the same origin, so both move as one. */
        shadowed(render::FontRole::Medium, row.item.text, row.x, capTop, size,
                 colour.withAlpha(row.alpha));
        if (!row.item.suffix.empty()) {
            shadowed(render::FontRole::Medium, row.item.suffix, row.x + nameWidth, capTop,
                     size, Color::rgb(0xAAAAAA, row.alpha));
        }

        /* Stepped outline: a left bar per row, bridged to the neighbour
         * wherever the widths differ, and a floor under the last row. */
        gfx.rect(Rect{row.x - pad - 1.0f, row.y, 1.0f, rowHeight},
                 colour.withAlpha(row.alpha), 0.0f);
        if (i > 0) {
            const float prevRight = placed[i - 1].x - pad;
            const float thisRight = row.x - pad;
            if (thisRight > prevRight + 0.5f) {
                gfx.rect(Rect{prevRight - 1.0f, row.y - 1.0f, thisRight - prevRight + 1.0f,
                              1.0f},
                         colour.withAlpha(row.alpha), 0.0f);
            }
        }
        if (i + 1 < placed.size()) {
            const float nextRight = placed[i + 1].x - pad;
            const float thisRight = row.x - pad;
            if (thisRight > nextRight + 0.5f) {
                gfx.rect(Rect{nextRight - 1.0f, row.y + rowHeight, thisRight - nextRight + 1.0f,
                              1.0f},
                         colour.withAlpha(row.alpha), 0.0f);
            }
        } else {
            gfx.rect(Rect{row.x - pad - 1.0f, row.y + rowHeight,
                          (rightEdge - row.x) + pad + 1.0f, 1.0f},
                     colour.withAlpha(row.alpha), 0.0f);
        }
    }

    for (auto iterator = entries_.begin(); iterator != entries_.end();) {
        if (!iterator->second.seen && iterator->second.alpha < 0.004f) {
            iterator = entries_.erase(iterator);
        } else {
            ++iterator;
        }
    }
}

void Hud::notify(std::string text, float seconds, const Color& accent) {
    if (text.empty()) {
        return;
    }
    constexpr std::size_t kMaxNotices = 5;
    if (notices_.size() >= kMaxNotices) {
        notices_.erase(notices_.begin());
    }
    Notification notice;
    notice.text = std::move(text);
    notice.accent = accent;
    notice.total = clamp(seconds, 0.5f, 30.0f);
    notice.life = notice.total;
    notices_.push_back(std::move(notice));
}

void Hud::drawNotifications(modules::Context& context) {
    if (notices_.empty()) {
        return;
    }
    render::Renderer& gfx = render::renderer();
    const float size = px(kNoticeTextSize);
    const float rowHeight = std::round(px(kNoticeHeight));
    const float right = static_cast<float>(context.state->windowWidth) - px(kMargin);
    const float blend = 1.0f - std::exp(-14.0f * context.deltaTime);

    float y = std::round(static_cast<float>(context.state->windowHeight) - px(kMargin) -
                         rowHeight);
    for (std::size_t index = notices_.size(); index > 0; --index) {
        Notification& notice = notices_[index - 1];
        notice.life -= context.deltaTime;

        const float target = notice.life <= kNoticeFade ? 1.0f : 0.0f;
        notice.offset += (target - notice.offset) * blend;
        notice.y = notice.placed ? notice.y + (y - notice.y) * blend : y;
        notice.placed = true;
        if (std::fabs(y - notice.y) < 0.5f) {
            notice.y = y;
        }

        const float width =
            gfx.measure(render::FontRole::Medium, notice.text, size) + px(kNoticePad * 2.0f);
        const float slide =
            ease::outQuint(clamp(notice.offset, 0.0f, 1.0f)) * (width + px(6.0f));
        const float alpha = clamp(1.0f - notice.offset * 0.6f, 0.0f, 1.0f);
        const Rect panel{std::round(right - width + slide), std::round(notice.y), width,
                         rowHeight};

        gfx.rect(panel, Color::rgb(0x000000, 0.47f * alpha), px(2.0f));
        shadowed(render::FontRole::Medium, notice.text, panel.x + px(kNoticePad),
                 std::round(panel.y + (rowHeight - size * 0.727f) * 0.5f - px(1.0f)), size,
                 notice.accent.withAlpha(alpha));
        const float remaining = clamp(notice.life / notice.total, 0.0f, 1.0f);
        gfx.rect(Rect{panel.x, panel.bottom() - px(1.0f), panel.width * remaining, px(1.0f)},
                 notice.accent.withAlpha(0.55f * alpha), 0.0f);

        y -= rowHeight + std::round(px(1.5f));
    }

    for (std::size_t index = notices_.size(); index > 0; --index) {
        if (notices_[index - 1].life <= 0.0f && notices_[index - 1].offset > 0.9f) {
            notices_.erase(notices_.begin() + static_cast<std::ptrdiff_t>(index - 1));
        }
    }
}

std::string Hud::debugLine(const game::FrameState& state) const {
    char buffer[512];
    std::snprintf(
        buffer, sizeof(buffer),
        "show=%d%d%d%d edit=%d valid=%d unit=%.2f mark=(%.1f,%.1f,%.0fx%.0f) "
        "player=(%.1f,%.1f,placed=%d,%.0fx%.0f) target=(%.1f,%.1f,placed=%d,drawn=%d) "
        "list=(r=%.1f,y=%.1f,drawn=%d) sizes=%.2f/%.2f/%.2f/%.2f/%.2f grab=%d,%d hot=%d,%d",
        watermark_ ? 1 : 0, arrayList_ ? 1 : 0, playerInfo_ ? 1 : 0,
        targetInfo_ ? 1 : 0, editMode(state) ? 1 : 0, state.player.valid ? 1 : 0, unit_,
        markX_, markY_, markRect_.width, markRect_.height, playerX_, playerY_,
        playerPlaced_ ? 1 : 0, playerRect_.width, playerRect_.height, targetX_, targetY_,
        targetPlaced_ ? 1 : 0, targetDrawn_ ? 1 : 0, listR_, listY_,
        listDrawn_ ? 1 : 0, sizes_[0], sizes_[1], sizes_[2], sizes_[3], sizes_[4],
        static_cast<int>(grab_), static_cast<int>(grabbed_), static_cast<int>(hotKind_),
        static_cast<int>(hot_));
    return buffer;
}

void Hud::render(modules::Context& context) {
    const game::FrameState& state = *context.state;
    const float factor = state.scaledWidth > 0 ? static_cast<float>(state.windowWidth) /
                                                     static_cast<float>(state.scaledWidth)
                                               : 2.0f;
    unit_ = std::max(factor * scale_, 0.5f);

    if (state.inWorld) {
        if (sessionStart_ < 0.0) {
            sessionStart_ = nowSeconds();
        }
        const bool edit = editMode(state);

        /* Each element draws under its own size factor. drawScale_ is what px()
         * multiplies by, so setting it here is all it takes for every offset and
         * dimension inside the element to follow its resize handle. */
        auto scoped = [this](HudElement id) { drawScale_ = sizeOf(id); };

        if (watermark_ || edit) {
            scoped(HudElement::Watermark);
            drawWatermark(context, edit);
        } else {
            markRect_ = Rect{};
        }
        if (arrayList_ || edit) {
            scoped(HudElement::ArrayList);
            drawArrayList(context, edit);
        } else {
            listDrawn_ = false;
        }
        float below = 0.0f;
        if (playerInfo_ || edit) {
            scoped(HudElement::PlayerInfo);
            drawPlayerInfo(context, edit, below);
        } else {
            playerRect_ = Rect{};
        }
        if (targetInfo_ || edit) {
            scoped(HudElement::TargetInfo);
            drawTargetInfo(context, edit, below);
        } else {
            targetDrawn_ = false;
        }
        if (speed_ || edit) {
            scoped(HudElement::Speed);
            drawSpeedReadout(context, edit);
        } else {
            speedDrawn_ = false;
        }
        drawScale_ = 1.0f;

        /* Chrome last and unscaled: on top of every element, and with handles sized
         * in screen units so an element shrunk to half size still has a grip you can
         * hit. */
        if (edit) {
            for (int i = 0; i < kElements; ++i) {
                const HudElement id = static_cast<HudElement>(i);
                if (liveOf(id)) {
                    drawEditChrome(id, rectOf(id), shownOf(id));
                }
            }
            drawEditHint(state);
        } else {
            hot_ = HudElement::Count;
            hotKind_ = Grab::None;
        }
    } else {
        entries_.clear();
        targetAlpha_ = 0.0f;
        targetId_ = -1;
        drawScale_ = 1.0f;
        grab_ = Grab::None;
        grabbed_ = HudElement::Count;
        hot_ = HudElement::Count;
        hotKind_ = Grab::None;
    }
    if (notifications_) {
        drawNotifications(context);
    }
}

}  // namespace cold::ui
