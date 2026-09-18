#include "modules/render/Esp.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "bridge/Minecraft.hpp"
#include "render/Renderer.hpp"
#include "ui/Theme.hpp"

namespace cold::modules {
namespace {

constexpr float kFadeRate = 12.0f;
/* Corner ordering the twelve edges and the six faces are both indexed against:
 * bit 0 is x, bit 1 is y, bit 2 is z. */
constexpr int kEdges[12][2] = {
    {0, 1}, {2, 3}, {4, 5}, {6, 7},  // along x
    {0, 2}, {1, 3}, {4, 6}, {5, 7},  // along y
    {0, 4}, {1, 5}, {2, 6}, {3, 7},  // along z
};
constexpr int kFaces[6][4] = {
    {0, 1, 3, 2}, {4, 5, 7, 6},  // near and far z
    {0, 1, 5, 4}, {2, 3, 7, 6},  // bottom and top
    {0, 2, 6, 4}, {1, 3, 7, 5},  // left and right x
};

}  // namespace

Esp::Esp() : Module("ESP", "Outlines, tracers and off-screen arrows for entities",
                    Category::Render, 0) {
    setIcon(assets::IconAsset::Eye);

    mode_ = &addChoice("Mode", "Outline style", {"Box", "Corners", "Glow", "None"}, 0);
    players_ = &addBoolean("Players", "Highlight players", true);
    mobs_ = &addBoolean("Mobs", "Highlight mobs", false);
    invisibles_ = &addBoolean("Invisibles", "Also highlight invisible entities", false);
    range_ = &addNumber("Range", "Maximum distance to highlight", 64.0f, 8.0f, 256.0f, 0);
    colourMode_ = &addChoice("Colour", "The client accent, or how much health is left",
                             {"Accent", "Health"}, 0);
    thickness_ = &addNumber("Thickness", "Outline width in pixels", 1.2f, 0.5f, 5.0f, 1);
    glowStrength_ = &addNumber("Glow", "Halo intensity around the outline", 0.45f, 0.0f, 1.0f, 2);
    fill_ = &addNumber("Fill", "Opacity of the box interior", 0.10f, 0.0f, 0.5f, 2);
    healthBar_ = &addBoolean("Health bar", "Vertical health meter beside the box", true);
    nameLabel_ = &addBoolean("Name", "Print the name above the box", false);
    distanceLabel_ = &addBoolean("Distance", "Print the distance under the box", false);
    smoothing_ = &addNumber("Smoothing", "Softens box movement; 0 tracks the entity exactly", 0.0f,
                            0.0f, 1.0f, 2);

    box3d_ = &addBoolean("3D box", "Wireframe hitbox drawn in the world", false);
    thickness3d_ = &addNumber("3D thickness", "Wireframe width in pixels", 1.0f, 0.5f, 5.0f, 1)
                       .visibleWhen([this] { return box3d_->booleanValue(); });
    fill3d_ = &addBoolean("3D fill", "Fill the six faces of the wireframe", false)
                  .visibleWhen([this] { return box3d_->booleanValue(); });

    tracers_ = &addBoolean("Tracers", "Line from you to every target", false);
    tracerFrom_ = &addChoice("Tracer origin", "Where the line starts on screen",
                             {"Crosshair", "Bottom"}, 0)
                      .visibleWhen([this] { return tracers_->booleanValue(); });
    tracerThickness_ = &addNumber("Tracer thickness", "Line width in pixels", 1.0f, 0.5f, 5.0f, 1)
                           .visibleWhen([this] { return tracers_->booleanValue(); });

    arrows_ = &addBoolean("Arrows", "Ring of pointers to targets that are off screen", false);
    arrowStyle_ = &addChoice("Arrow style", "Shape of the pointer", {"Classic", "Chevron"}, 0)
                      .visibleWhen([this] { return arrows_->booleanValue(); });
    arrowRadius_ = &addNumber("Arrow radius", "Pixels from the crosshair", 100.0f, 20.0f, 300.0f, 0)
                       .visibleWhen([this] { return arrows_->booleanValue(); });
    arrowSize_ = &addNumber("Arrow size", "Scale of the pointer", 1.0f, 0.5f, 2.0f, 1)
                     .visibleWhen([this] { return arrows_->booleanValue(); });
    arrowOutline_ = &addNumber("Arrow outline", "Width of the dark edge", 1.0f, 0.0f, 5.0f, 1)
                        .visibleWhen([this] { return arrows_->booleanValue(); });
    /* Distance is said with brightness rather than with a second hue: the
     * pointer for somebody on top of you is the accent lifted towards white,
     * and one at the far edge of the fade is the same colour stepped down
     * towards the panel. */
    arrowFade_ = &addNumber("Arrow fade", "Blocks over which the pointer dims", 30.0f, 5.0f,
                            120.0f, 0)
                     .visibleWhen([this] { return arrows_->booleanValue(); });
}

std::string Esp::suffix() const {
    if (mode_->choice() == 3) {
        return box3d_->booleanValue() ? "3D" : std::string{};
    }
    return mode_->choiceName();
}

Color Esp::colourFor(const Entry& entry) const {
    /* Health is the one thing worth spending colour on, because it is the
     * one thing about a target that changes while you are looking at it. */
    return colourMode_->choice() == 1 ? ui::healthColour(entry.health, entry.maxHealth)
                                      : ui::theme().accent;
}

void Esp::drawBox(const Rect& bounds, const Color& colour, float fade) {
    render::Renderer& gfx = render::renderer();
    const float radius = std::min(3.0f, std::min(bounds.width, bounds.height) * 0.2f);
    const float glow = glowStrength_->number();
    const float width = thickness_->number();

    if (glow > 0.0f) {
        gfx.glow(bounds, colour.withAlpha(0.34f * glow * fade), 16.0f * glow + 4.0f, radius);
    }
    if (fill_->number() > 0.0f) {
        gfx.rect(bounds, colour.withAlpha(fill_->number() * fade), radius);
    }
    /* A dark hairline under the bright one keeps the outline readable against
     * snow, sand and every other pale block. */
    gfx.outline(bounds.inset(-0.6f), Color::rgb(0x05070A, 0.55f * fade), width, radius);
    if (mode_->choice() == 2) {
        gfx.sweepOutline(bounds, colour.withAlpha(fade), width + 0.2f, radius);
    } else {
        gfx.outline(bounds, colour.withAlpha(fade), width, radius);
    }
}

void Esp::drawCorners(const Rect& bounds, const Color& colour, float fade) {
    render::Renderer& gfx = render::renderer();
    const float armX = std::min(bounds.width * 0.3f, 14.0f);
    const float armY = std::min(bounds.height * 0.3f, 14.0f);
    const float thickness = thickness_->number() + 0.2f;
    const Color line = colour.withAlpha(fade);
    const Color backing = Color::rgb(0x05070A, 0.5f * fade);

    const float left = bounds.x;
    const float right = bounds.right();
    const float top = bounds.y;
    const float bottom = bounds.bottom();

    const auto bracket = [&](float x0, float y0, float x1, float y1) {
        gfx.line(x0, y0 + 0.8f, x1, y1 + 0.8f, thickness + 1.2f, backing);
        gfx.line(x0, y0, x1, y1, thickness, line);
    };
    bracket(left, top, left + armX, top);
    bracket(left, top, left, top + armY);
    bracket(right - armX, top, right, top);
    bracket(right, top, right, top + armY);
    bracket(left, bottom - armY, left, bottom);
    bracket(left, bottom, left + armX, bottom);
    bracket(right - armX, bottom, right, bottom);
    bracket(right, bottom - armY, right, bottom);

    if (glowStrength_->number() > 0.0f) {
        gfx.glow(bounds, colour.withAlpha(0.22f * glowStrength_->number() * fade), 14.0f, 4.0f);
    }
}

void Esp::drawBox3d(Context& context, const Entry& entry, const Color& colour, float fade) {
    /* One corner behind the near plane makes every line touching it wrap across
     * the screen, so the whole box is dropped rather than clipped: at that
     * distance the flat box is already covering the same entity. */
    float xs[8];
    float ys[8];
    for (int index = 0; index < 8; ++index) {
        const Vec3 corner{(index & 1) != 0 ? entry.boxMax.x : entry.boxMin.x,
                          (index & 2) != 0 ? entry.boxMax.y : entry.boxMin.y,
                          (index & 4) != 0 ? entry.boxMax.z : entry.boxMin.z};
        game::ScreenPoint point;
        if (!context.projection->project(corner, point)) {
            return;
        }
        xs[index] = point.x;
        ys[index] = point.y;
    }

    render::Renderer& gfx = render::renderer();
    if (fill3d_->booleanValue()) {
        const Color face = colour.withAlpha(0.18f * fade);
        for (const auto& quad : kFaces) {
            const float px[4] = {xs[quad[0]], xs[quad[1]], xs[quad[2]], xs[quad[3]]};
            const float py[4] = {ys[quad[0]], ys[quad[1]], ys[quad[2]], ys[quad[3]]};
            gfx.face(px, py, face);
        }
    }

    const float width = thickness3d_->number();
    const Color backing = Color::rgb(0x05070A, 0.55f * fade);
    const Color line = colour.withAlpha(fade);
    for (const auto& edge : kEdges) {
        gfx.line(xs[edge[0]], ys[edge[0]] + 0.7f, xs[edge[1]], ys[edge[1]] + 0.7f, width + 1.0f,
                 backing);
    }
    for (const auto& edge : kEdges) {
        gfx.line(xs[edge[0]], ys[edge[0]], xs[edge[1]], ys[edge[1]], width, line);
    }
}

void Esp::drawTracer(Context& context, const Entry& entry, float fade) {
    game::ScreenPoint point;
    const Vec3 centre{(entry.boxMin.x + entry.boxMax.x) * 0.5,
                      (entry.boxMin.y + entry.boxMax.y) * 0.5,
                      (entry.boxMin.z + entry.boxMax.z) * 0.5};
    if (!context.projection->project(centre, point)) {
        return;
    }
    const auto width = static_cast<float>(context.state->windowWidth);
    const auto height = static_cast<float>(context.state->windowHeight);
    const float originX = width * 0.5f;
    const float originY = tracerFrom_->choice() == 0 ? height * 0.5f : height;

    render::Renderer& gfx = render::renderer();
    const float thickness = tracerThickness_->number();
    gfx.line(originX, originY, point.x, point.y + 0.7f, thickness + 1.0f,
             Color::rgb(0x05070A, 0.5f * fade));
    gfx.line(originX, originY, point.x, point.y, thickness, ui::theme().accent.withAlpha(fade));
}

void Esp::drawArrow(Context& context, const Entry& entry, float fade) {
    const auto width = static_cast<float>(context.state->windowWidth);
    const auto height = static_cast<float>(context.state->windowHeight);
    const float centreX = width * 0.5f;
    const float centreY = height * 0.5f;
    const float radius = arrowRadius_->number();

    const Vec3 centre{(entry.boxMin.x + entry.boxMax.x) * 0.5,
                      (entry.boxMin.y + entry.boxMax.y) * 0.5,
                      (entry.boxMin.z + entry.boxMax.z) * 0.5};

    float dirX = 0.0f;
    float dirY = 1.0f;
    game::ScreenPoint point;
    if (context.projection->project(centre, point)) {
        dirX = point.x - centreX;
        dirY = point.y - centreY;
        /* Already inside the ring: the box is doing the job and a pointer on
         * top of it is noise. */
        if (dirX * dirX + dirY * dirY < radius * radius) {
            return;
        }
    } else {
        /* Behind the camera. The screen has nothing to say about where it is,
         * so the bearing comes from the yaw instead and the pointer swings the
         * long way round, which is where the entity actually is. */
        const double dx = centre.x - context.state->camera.pos.x;
        const double dz = centre.z - context.state->camera.pos.z;
        const auto bearing =
            static_cast<float>(std::atan2(dz, dx) * static_cast<double>(kRadToDeg)) - 90.0f;
        const float relative = angleDifference(context.state->camera.yaw, bearing);
        const double radians = static_cast<double>(relative) * kDegToRad;
        dirX = static_cast<float>(std::sin(radians));
        dirY = -static_cast<float>(std::cos(radians));
    }

    const float length = std::sqrt(dirX * dirX + dirY * dirY);
    if (length < 0.001f) {
        return;
    }
    dirX /= length;
    dirY /= length;

    const float away =
        clamp(static_cast<float>(entry.distance) / arrowFade_->number(), 0.0f, 1.0f);
    const Color colour = ui::accentShade(lerp(1.35f, 0.6f, away));

    /* The pointer is drawn in its own frame - x along the bearing, y across it -
     * so the shape below reads the same way it does in the original script. */
    const float scale = arrowSize_->number();
    const float baseX = centreX + dirX * radius;
    const float baseY = centreY + dirY * radius;
    const auto place = [&](float along, float across, float& outX, float& outY) {
        outX = baseX + (dirX * along - dirY * across) * scale;
        outY = baseY + (dirY * along + dirX * across) * scale;
    };

    render::Renderer& gfx = render::renderer();
    const Color fill = colour.withAlpha(fade);
    const Color edge = Color::rgb(0x05070A, 0.95f * fade);
    const float outline = arrowOutline_->number();

    if (arrowStyle_->choice() == 1) {
        float px[4];
        float py[4];
        place(10.0f, 0.0f, px[0], py[0]);
        place(-4.0f, 7.0f, px[1], py[1]);
        place(-1.0f, 0.0f, px[2], py[2]);
        place(-4.0f, -7.0f, px[3], py[3]);
        gfx.face(px, py, fill);
        if (outline > 0.0f) {
            for (int index = 0; index < 4; ++index) {
                const int next = (index + 1) % 4;
                gfx.line(px[index], py[index], px[next], py[next], outline, edge);
            }
        }
        return;
    }

    float px[3];
    float py[3];
    place(10.0f, 0.0f, px[0], py[0]);
    place(-6.0f, 6.0f, px[1], py[1]);
    place(-6.0f, -6.0f, px[2], py[2]);
    /* face() takes a quad; a triangle is the same call with the tip doubled. */
    const float quadX[4] = {px[0], px[1], px[2], px[0]};
    const float quadY[4] = {py[0], py[1], py[2], py[0]};
    gfx.face(quadX, quadY, fill);
    if (outline > 0.0f) {
        for (int index = 0; index < 3; ++index) {
            const int next = (index + 1) % 3;
            gfx.line(px[index], py[index], px[next], py[next], outline, edge);
        }
    }
}

void Esp::onRender(Context& context) {
    if (!context.projection->valid()) {
        /* No projection means no world. Entity ids restart per world, so
         * keeping the cache would draw the last server's boxes over the next
         * one, and any id that happens to match would smooth in from a
         * coordinate in a different dimension. */
        entries_.clear();
        return;
    }
    render::Renderer& gfx = render::renderer();
    const ui::Palette& palette = ui::theme();
    const float step = 1.0f - std::exp(-kFadeRate * context.deltaTime);

    for (auto& [id, entry] : entries_) {
        entry.seen = false;
    }

    for (const game::EntityView& entity : context.state->entities) {
        if (!entity.living) continue;
        if (entity.isPlayer ? !players_->booleanValue() : !mobs_->booleanValue()) continue;
        if (entity.invisible && !invisibles_->booleanValue()) continue;
        if (entity.distance > range_->number()) continue;

        Rect bounds;
        const bool projected =
            context.projection->projectBox(entity.boxMin(), entity.boxMax(), bounds);

        Entry& entry = entries_[entity.id];
        entry.seen = true;
        entry.health = entity.health + entity.absorption;
        entry.maxHealth = entity.maxHealth;
        entry.distance = entity.distance;
        entry.isPlayer = entity.isPlayer;
        /* Padded the way the original script pads it, so the wireframe sits
         * just outside the model rather than clipping through it. */
        entry.boxMin = entity.boxMin() - Vec3{0.1, 0.1, 0.1};
        entry.boxMax = entity.boxMax() + Vec3{0.1, 0.1, 0.1};
        if (nameLabel_->booleanValue() && entry.name.empty()) {
            entry.name = game::Game::instance().entityName(context.env, entity.handle);
        }

        /* The projection already uses the frame's interpolated entity position
         * and the exact camera matrices, so the raw rectangle is on the entity
         * every frame. Smoothing it can only add lag, which is why it defaults
         * to off; the setting exists for anyone who prefers a softer box. */
        if (projected) {
            if (entry.fade <= 0.001f || smoothing_->number() <= 0.0f) {
                entry.bounds = bounds;
            } else {
                const float rate = lerp(45.0f, 9.0f, smoothing_->number());
                const float follow = 1.0f - std::exp(-rate * context.deltaTime);
                entry.bounds.x += (bounds.x - entry.bounds.x) * follow;
                entry.bounds.y += (bounds.y - entry.bounds.y) * follow;
                entry.bounds.width += (bounds.width - entry.bounds.width) * follow;
                entry.bounds.height += (bounds.height - entry.bounds.height) * follow;
            }
        } else {
            /* Off screen or behind the camera. The arrow still wants the entry,
             * so the box is simply left where it was and drawn at zero width. */
            entry.bounds.width = 0.0f;
        }
        entry.fade += (1.0f - entry.fade) * step;
    }

    /* Draw from the cache rather than the entity list, so an entry that is
     * fading out still renders for the frames it takes to disappear. */
    for (auto iterator = entries_.begin(); iterator != entries_.end();) {
        Entry& entry = iterator->second;
        if (!entry.seen) {
            entry.fade -= entry.fade * step;
            if (entry.fade < 0.01f) {
                iterator = entries_.erase(iterator);
                continue;
            }
        }
        ++iterator;

        const Color colour = colourFor(entry);
        const float fade = entry.fade * animation();

        if (box3d_->booleanValue()) {
            drawBox3d(context, entry, colour, fade);
        }
        if (tracers_->booleanValue()) {
            drawTracer(context, entry, fade);
        }
        if (arrows_->booleanValue()) {
            drawArrow(context, entry, fade);
        }

        const Rect box = entry.bounds;
        if (box.width <= 0.5f || box.height <= 0.5f) {
            continue;
        }

        switch (mode_->choice()) {
            case 1: drawCorners(box, colour, fade); break;
            case 3: break;
            default: drawBox(box, colour, fade); break;
        }

        if (healthBar_->booleanValue() && entry.maxHealth > 0.0f) {
            const float fraction = clamp(entry.health / entry.maxHealth, 0.0f, 1.0f);
            const Rect track{box.x - 4.5f, box.y, 2.0f, box.height};
            gfx.rect(track.inset(-0.6f), Color::rgb(0x05070A, 0.6f * fade), 1.4f);
            gfx.rect(track, Color::rgb(0x1A1F27, 0.85f * fade), 1.0f);
            const float filled = track.height * fraction;
            gfx.rect(Rect{track.x, track.bottom() - filled, track.width, filled},
                     ui::healthColour(entry.health, entry.maxHealth).withAlpha(fade), 1.0f);
        }

        /* A one-pixel dark pass behind every label: over sand or snow a
         * single-colour run disappears entirely. */
        const auto label = [&](std::string_view text, float y, float size, const Color& tint) {
            gfx.text(render::FontRole::Medium, text, box.centerX() + 0.7f, y + 0.7f, size,
                     Color::rgb(0x05070A, 0.75f * fade), render::Align::Center);
            gfx.text(render::FontRole::Medium, text, box.centerX(), y, size, tint,
                     render::Align::Center);
        };

        if (nameLabel_->booleanValue() && !entry.name.empty()) {
            constexpr float kNameSize = 8.5f;
            label(entry.name, box.y - kNameSize - 2.5f, kNameSize,
                  palette.textBright.withAlpha(fade));
        }
        if (distanceLabel_->booleanValue()) {
            char text[24];
            std::snprintf(text, sizeof(text), "%.1fm", entry.distance);
            label(text, box.bottom() + 2.0f, 7.5f, palette.text.withAlpha(fade));
        }
    }
}

}  // namespace cold::modules
