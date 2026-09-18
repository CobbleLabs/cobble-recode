#include "modules/render/TargetHud.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "bridge/Minecraft.hpp"
#include "core/Time.hpp"
#include "game/TargetFinder.hpp"
#include "render/Renderer.hpp"
#include "ui/Theme.hpp"

namespace cold::modules {
namespace {

/* GUI-scaled units, the same space the rest of the HUD is measured in. */
constexpr float kWidth = 116.0f;
constexpr float kHeight = 44.0f;
constexpr float kPadding = 7.0f;
constexpr float kBarHeight = 4.0f;
constexpr float kFaceSize = 30.0f;

constexpr float kFadeRate = 9.0f;
constexpr float kBarRate = 22.0f;
constexpr float kGhostRate = 4.5f;

}  // namespace

TargetHud::TargetHud()
    : Module("TargetHud", "Panel for whoever the combat modules are hitting", Category::Render, 0) {
    setIcon(assets::IconAsset::Target);

    offsetX_ = &addNumber("Offset X", "Pixels right of the screen centre", 0.0f, -400.0f, 400.0f, 0);
    offsetY_ = &addNumber("Offset Y", "Pixels below the screen centre", 34.0f, -300.0f, 300.0f, 0);
    scale_ = &addNumber("Scale", "Size of the panel", 1.0f, 0.6f, 2.0f, 2);
    backdrop_ = &addNumber("Backdrop", "Opacity of the slab behind the text", 0.55f, 0.0f, 1.0f, 2);
    healthColour_ = &addBoolean("Health colour", "Colour the bar by health rather than the accent",
                                true);
    showDistance_ = &addBoolean("Distance", "Print how far away they are", true);
    showOutcome_ = &addBoolean("Win or loss", "Say whether you are ahead on health", true);
    marker_ = &addBoolean("Marker", "Bracket the target in the world", true);
}

void TargetHud::onEnable(Context&) {
    alpha_ = 0.0f;
    targetId_ = -1;
    ghost_ = 0.0f;
    fraction_ = 0.0f;
}

void TargetHud::drawMarker(Context& context) {
    if (!marker_->booleanValue() || targetId_ < 0 || !context.projection->valid()) {
        return;
    }
    for (const game::EntityView& entity : context.state->entities) {
        if (entity.id != targetId_) {
            continue;
        }
        Rect bounds;
        if (context.projection->projectBox(entity.boxMin(), entity.boxMax(), bounds)) {
            ui::drawTargetMarker(bounds, alpha_ * animation(), context.time);
        }
        return;
    }
}

void TargetHud::drawPanel(modules::Context& context, const Rect& bounds, float alpha) {
    render::Renderer& gfx = render::renderer();
    const ui::Palette& palette = ui::theme();
    const float unit = bounds.height / kHeight;
    const float radius = 5.0f * unit;

    const float backdrop = backdrop_->number();
    if (backdrop > 0.0f) {
        gfx.shadow(bounds, Color::rgb(0x000000, 0.45f * alpha), 10.0f * unit, radius);
        gfx.rect(bounds, Color::rgb(0x0B0D12, backdrop * alpha), radius);
        gfx.outline(bounds, palette.border.withAlpha(alpha), 1.0f, radius);
    }

    const Color bar = healthColour_->booleanValue()
                          ? ui::healthColour(health_, maxHealth_)
                          : palette.accent;

    const float pad = kPadding * unit;
    const float nameSize = 10.0f * unit;
    const float smallSize = 8.0f * unit;

    /* Avatar on the left, like the reference card: the target's own skin face
     * when there is one, a dim placeholder when there is not. */
    jobject handle = nullptr;
    if (targetId_ >= 0) {
        for (const game::EntityView& entity : context.state->entities) {
            if (entity.id == targetId_) {
                handle = entity.handle;
                break;
            }
        }
    }
    const Rect face{bounds.x + pad, bounds.centerY() - kFaceSize * unit * 0.5f,
                    kFaceSize * unit, kFaceSize * unit};
    bool faceDrawn = false;
    if (handle != nullptr && targetIsPlayer_) {
        const game::Game::SkinView skin =
            game::Game::instance().playerSkin(context.env, handle);
        if (skin.texture != 0) {
            gfx.imageCrisp(face, Rect{8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f},
                           skin.texture, Color{1.0f, 1.0f, 1.0f, alpha});
            faceDrawn = true;
        }
    }
    if (!faceDrawn) {
        gfx.rect(face, Color::rgb(0x05070A, 0.55f * alpha), 3.0f * unit);
        gfx.icon(assets::IconAsset::UserRound, face.inset(face.width * 0.18f),
                 Color::rgb(0xAAAAAA, 0.7f * alpha));
    }

    /* The text column starts clear of the avatar. */
    const float textLeft = face.right() + 6.0f * unit;
    const float textWidth = bounds.right() - pad - textLeft;
    /* Three rows, measured from the cap line down: who it is, how much of them
     * is left, and the bar. Spaced from the panel rather than from each other
     * so no row can drift into the one below it. */
    const float nameRow = bounds.y + pad;
    const float statRow = bounds.y + 21.0f * unit;

    /* Name on the left, distance on the right, both on the same cap line so the
     * two runs sit on one baseline whatever the glyphs are. */
    const float nameWidth =
        textWidth - (showDistance_->booleanValue() ? 34.0f * unit : 0.0f);
    const std::string name = gfx.elide(render::FontRole::SemiBold, name_, nameSize, nameWidth);
    gfx.textCap(render::FontRole::SemiBold, name, textLeft + 0.8f * unit,
                nameRow + 0.8f * unit, nameSize, Color::rgb(0x05070A, 0.7f * alpha));
    gfx.textCap(render::FontRole::SemiBold, name, textLeft, nameRow, nameSize,
                palette.textBright.withAlpha(alpha));

    if (showDistance_->booleanValue()) {
        char text[16];
        std::snprintf(text, sizeof(text), "%.1fm", static_cast<double>(distance_));
        gfx.textCap(render::FontRole::Medium, text, bounds.right() - pad, nameRow + 1.5f * unit,
                    smallSize, palette.textDim.withAlpha(alpha), render::Align::Right);
    }

    const Rect track{textLeft, bounds.bottom() - pad - kBarHeight * unit,
                     textWidth, kBarHeight * unit};
    const float barRadius = track.height * 0.5f;
    gfx.rect(track, Color::rgb(0x05070A, 0.62f * alpha), barRadius);

    /* The ghost is the same bar a beat behind. Drawn first and dimmer, so a hit
     * reads as the bright bar snapping in and the dim one draining after it. */
    if (ghost_ > fraction_ + 0.001f) {
        gfx.rect(Rect{track.x, track.y, track.width * ghost_, track.height},
                 bar.withAlpha(0.32f * alpha), barRadius);
    }
    if (fraction_ > 0.001f) {
        gfx.rect(Rect{track.x, track.y, track.width * fraction_, track.height},
                 bar.withAlpha(alpha), barRadius);
    }
    /* Healing runs the other way: the ghost leads and the solid bar catches up,
     * so the gap is the health that just came back. */
    if (ghost_ < fraction_ - 0.001f) {
        gfx.rect(Rect{track.x + track.width * ghost_, track.y,
                      track.width * (fraction_ - ghost_), track.height},
                 palette.textBright.withAlpha(0.28f * alpha), barRadius);
    }

    char health[24];
    std::snprintf(health, sizeof(health), "%.1f", static_cast<double>(health_));
    gfx.textCap(render::FontRole::Medium, health, textLeft + 0.7f * unit,
                statRow + 0.7f * unit, smallSize, Color::rgb(0x05070A, 0.7f * alpha));
    gfx.textCap(render::FontRole::Medium, health, textLeft, statRow, smallSize,
                bar.withAlpha(alpha));

    if (showOutcome_->booleanValue()) {
        const bool winning = fraction_ <= ownFraction_;
        gfx.textCap(render::FontRole::Bold, winning ? "W" : "L", bounds.right() - pad, statRow,
                    smallSize, (winning ? palette.positive : palette.negative).withAlpha(alpha),
                    render::Align::Right);
    }
}

void TargetHud::onRender(Context& context) {
    const game::FrameState& state = *context.state;
    const float step = 1.0f - std::exp(-kFadeRate * context.deltaTime);

    const game::EntityView* target = nullptr;
    if (state.inWorld && !state.screenOpen) {
        const int id = game::CombatTarget::instance().current(nowSeconds());
        for (const game::EntityView& entity : state.entities) {
            if (entity.id == id && entity.health > 0.0f) {
                target = &entity;
                break;
            }
        }
    }

    if (target != nullptr) {
        if (target->id != targetId_) {
            /* A new target starts with the bar already where it belongs; easing
             * it up from zero would read as the last one healing. */
            targetId_ = target->id;
            ghost_ = 1.0f;
            fraction_ = 1.0f;
        }
        name_ = game::Game::instance().entityName(context.env, target->handle);
        if (name_.empty()) {
            name_ = target->isPlayer ? "player" : "entity";
        }
        targetIsPlayer_ = target->isPlayer;
        health_ = target->health + target->absorption;
        maxHealth_ = target->maxHealth > 0.0f ? target->maxHealth : 20.0f;
        distance_ = static_cast<float>(target->distance);
        ownFraction_ = state.player.maxHealth > 0.0f
                           ? clamp(state.player.health / state.player.maxHealth, 0.0f, 1.0f)
                           : 1.0f;
        alpha_ += (1.0f - alpha_) * step;
    } else {
        alpha_ -= alpha_ * step;
        if (alpha_ < 0.01f) {
            targetId_ = -1;
            return;
        }
    }

    const float wanted = maxHealth_ > 0.0f ? clamp(health_ / maxHealth_, 0.0f, 1.0f) : 0.0f;
    fraction_ += (wanted - fraction_) * (1.0f - std::exp(-kBarRate * context.deltaTime));
    ghost_ += (fraction_ - ghost_) * (1.0f - std::exp(-kGhostRate * context.deltaTime));

    const float alpha = alpha_ * animation();
    if (alpha < 0.01f) {
        return;
    }

    /* Sized in GUI units so the panel is the same size on screen at any
     * resolution, exactly as the rest of the HUD is. */
    const float unit = static_cast<float>(std::max(1, state.scaleFactor)) * scale_->number();
    const float width = kWidth * unit;
    const float height = kHeight * unit;
    const Rect bounds{static_cast<float>(state.windowWidth) * 0.5f - width * 0.5f +
                          offsetX_->number() * unit,
                      static_cast<float>(state.windowHeight) * 0.5f + offsetY_->number() * unit,
                      width, height};

    drawMarker(context);
    drawPanel(context, bounds, alpha);
}

}  // namespace cold::modules
