#include "modules/render/CuteVisuals.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <string>

#include "bridge/Minecraft.hpp"
#include "hooks/Input.hpp"
#include "render/Renderer.hpp"
#include "ui/Theme.hpp"

namespace cold::modules {
namespace {

constexpr std::size_t kMaxHearts = 40;
constexpr std::size_t kMaxDots = 80;
constexpr std::size_t kMaxSparks = 160;
constexpr std::size_t kMaxRainbows = 4;

constexpr int kHeartSegments = 20;
constexpr int kRainbowSegments = 28;
constexpr double kHeartRadius = 1.5;
constexpr double kDotSpread = 0.9;
constexpr double kDotDrift = 0.3;
constexpr double kTwoPi = 6.283185307179586;
/* Pixels a single particle may cover. The trail spawns inside arm's reach, so
 * without a cap the one that happens to drift through the camera is projected at
 * a scale of several thousand and fills a quarter of the screen with one flat
 * disc - which reads as a rendering fault rather than as a particle. */
constexpr float kMaxParticlePixels = 90.0f;

/* The parametric heart the original uses, sampled once. Its own units run to
 * about sixteen across, which is why every draw divides by that. */
const std::array<Vec2, kHeartSegments + 1>& heartShape() {
    static const auto shape = [] {
        std::array<Vec2, kHeartSegments + 1> made{};
        for (int index = 0; index <= kHeartSegments; ++index) {
            const double t = static_cast<double>(index) / kHeartSegments * kTwoPi;
            const double sine = std::sin(t);
            made[static_cast<std::size_t>(index)] = Vec2{
                static_cast<float>(16.0 * sine * sine * sine),
                static_cast<float>(-(13.0 * std::cos(t) - 5.0 * std::cos(2.0 * t) -
                                     2.0 * std::cos(3.0 * t) - std::cos(4.0 * t)))};
        }
        return made;
    }();
    return shape;
}

const std::array<Vec2, 9>& starShape() {
    static const auto shape = [] {
        std::array<Vec2, 9> made{};
        for (int index = 0; index <= 8; ++index) {
            const double angle = index * 3.14159265358979323846 / 4.0 - 3.14159265358979323846 / 2.0;
            const double radius = (index % 2 == 0) ? 12.0 : 5.0;
            made[static_cast<std::size_t>(index)] =
                Vec2{static_cast<float>(std::cos(angle) * radius),
                     static_cast<float>(std::sin(angle) * radius)};
        }
        return made;
    }();
    return shape;
}


/* In, hold, out. Every particle in the script fades on this shape; only the
 * hold window differs. */
float envelope(float progress, float fadeIn, float holdUntil) {
    if (progress < fadeIn) {
        return progress / fadeIn;
    }
    if (progress > holdUntil) {
        return (1.0f - progress) / (1.0f - holdUntil);
    }
    return 1.0f;
}

/* The original is four fixed pink ramps and a real rainbow. Everything here
 * is one accent instead, varied along its own axis: the particle types stay
 * distinguishable because they are different *shapes*, which is what the
 * original was actually using them for. */
float heartWeight(int type) {
    static const float kWeights[3] = {1.15f, 0.85f, 1.0f};
    return kWeights[clamp(type, 0, 2)];
}

float dotWeight(int type) {
    static const float kWeights[4] = {1.0f, 1.15f, 0.8f, 1.25f};
    return kWeights[clamp(type, 0, 3)];
}

/* Seven bands from bright to dark across the arc, so it still reads as a
 * banded arch rather than a single fat stroke. */
float bandWeight(int band) {
    return lerp(1.5f, 0.55f, static_cast<float>(clamp(band, 0, 6)) / 6.0f);
}

bool isBed(const std::string& id) { return id.find("_bed") != std::string::npos || id == "bed"; }

/* Draws a closed run of screen points, with the glow the original gets from
 * redrawing the same loop at growing scales. */
void strokeShape(const Vec2* points, int count, float x, float y, float scale, float rotation,
                 const Color& colour, float alpha, float width) {
    render::Renderer& gfx = render::renderer();
    const float cosine = std::cos(rotation);
    const float sine = std::sin(rotation);

    for (int layer = 2; layer >= 0; --layer) {
        const float layerScale = scale * (1.0f + static_cast<float>(layer) * 0.1f);
        const float layerAlpha = layer == 0 ? alpha * 0.9f : alpha * (0.28f / static_cast<float>(layer));
        if (layerAlpha < 0.01f) {
            continue;
        }
        const Color tint = colour.withAlpha(layerAlpha);
        float previousX = 0.0f;
        float previousY = 0.0f;
        for (int index = 0; index < count; ++index) {
            const float sx = points[index].x * layerScale;
            const float sy = points[index].y * layerScale;
            const float px = x + sx * cosine - sy * sine;
            const float py = y + sx * sine + sy * cosine;
            if (index > 0) {
                gfx.line(previousX, previousY, px, py, width + static_cast<float>(layer), tint);
            }
            previousX = px;
            previousY = py;
        }
    }
}

}  // namespace

CuteVisuals::CuteVisuals()
    : Module("CuteVisuals", "Heart and dot trail, and a burst when a bed goes", Category::Render,
             0) {
    setIcon(assets::IconAsset::Flame);

    onlyMoving_ = &addBoolean("Only while moving", "Stop the trail when standing still", true);
    opacity_ = &addNumber("Opacity", "Percent opacity of the trail", 85.0f, 20.0f, 100.0f, 0);
    hearts_ = &addBoolean("Hearts", "Floating hearts behind you", true);
    heartRate_ = &addNumber("Heart rate", "Milliseconds between hearts", 200.0f, 50.0f, 500.0f, 0)
                     .visibleWhen([this] { return hearts_->booleanValue(); });
    heartLife_ = &addNumber("Heart lifetime", "Milliseconds a heart lasts", 1500.0f, 500.0f,
                            4000.0f, 0)
                     .visibleWhen([this] { return hearts_->booleanValue(); });
    dots_ = &addBoolean("Dots", "Drifting dots behind you", true);
    dotRate_ = &addNumber("Dot rate", "Milliseconds between dots", 100.0f, 20.0f, 200.0f, 0)
                   .visibleWhen([this] { return dots_->booleanValue(); });
    dotLife_ = &addNumber("Dot lifetime", "Milliseconds a dot lasts", 1500.0f, 500.0f, 5000.0f, 0)
                   .visibleWhen([this] { return dots_->booleanValue(); });
    pulse_ = &addBoolean("Pulse", "Flicker the dots", false)
                 .visibleWhen([this] { return dots_->booleanValue(); });

    bedBurst_ = &addBoolean("Bed burst", "Shower of shapes when a bed breaks", true);
    burstCount_ = &addNumber("Burst count", "Shapes per burst", 20.0f, 5.0f, 40.0f, 0)
                      .visibleWhen([this] { return bedBurst_->booleanValue(); });
    burstSize_ = &addNumber("Burst size", "Scale of each shape", 0.20f, 0.05f, 0.60f, 2)
                     .visibleWhen([this] { return bedBurst_->booleanValue(); });
    burstSpeed_ = &addNumber("Burst speed", "How hard they are thrown", 2.5f, 0.5f, 7.0f, 1)
                      .visibleWhen([this] { return bedBurst_->booleanValue(); });
    burstLife_ = &addNumber("Burst lifetime", "Milliseconds a shape lasts", 1500.0f, 500.0f,
                            3000.0f, 0)
                     .visibleWhen([this] { return bedBurst_->booleanValue(); });
    rainbow_ = &addBoolean("Arc", "Banded arch over the bed that was broken", true);
    rainbowWidth_ = &addNumber("Arc width", "Band thickness in pixels", 5.0f, 1.0f, 12.0f, 1)
                        .visibleWhen([this] { return rainbow_->booleanValue(); });
    rainbowLife_ = &addNumber("Arc lifetime", "Milliseconds the arc lasts", 3000.0f, 1000.0f,
                              6000.0f, 0)
                       .visibleWhen([this] { return rainbow_->booleanValue(); });
    bedSound_ = &addBoolean("Bed sound", "Play a chime with the burst", true);
}

void CuteVisuals::onEnable(Context&) {
    heartPool_.clear();
    dotPool_.clear();
    sparkPool_.clear();
    rainbowPool_.clear();
    hasLastPosition_ = false;
    watchingBed_ = false;
}

void CuteVisuals::onDisable(Context&) {
    heartPool_.clear();
    dotPool_.clear();
    sparkPool_.clear();
    rainbowPool_.clear();
    watchingBed_ = false;
}

bool CuteVisuals::billboard(Context& context, const Vec3& world, float& x, float& y,
                            float& pixelsPerBlock) const {
    game::ScreenPoint centre;
    if (!context.projection->project(world, centre)) {
        return false;
    }
    /* One block to the camera's right, projected. The distance between the two
     * points is exactly the scale a billboard at that depth needs, and it costs
     * one more matrix multiply rather than a vertex-by-vertex transform. */
    const double yaw = static_cast<double>(context.state->camera.yaw) * kDegToRad;
    const Vec3 right{std::cos(yaw), 0.0, std::sin(yaw)};
    game::ScreenPoint edge;
    if (!context.projection->project(world + right, edge)) {
        return false;
    }
    const float dx = edge.x - centre.x;
    const float dy = edge.y - centre.y;
    pixelsPerBlock = std::sqrt(dx * dx + dy * dy);
    x = centre.x;
    y = centre.y;
    return pixelsPerBlock > 0.05f;
}

void CuteVisuals::watchBed(Context& context, const game::FrameState& state) {
    if (!bedBurst_->booleanValue() && !rainbow_->booleanValue() && !bedSound_->booleanValue()) {
        watchingBed_ = false;
        return;
    }
    game::Game& game = game::Game::instance();
    jobject world = game.world(context.env);
    if (world == nullptr) {
        return;
    }

    /* The bed being mined has stopped being a bed. That is the break, whether
     * it took ten ticks in survival or one click in creative. */
    if (watchingBed_) {
        const std::string current = game.blockId(context.env, world, bedX_, bedY_, bedZ_);
        if (!isBed(current)) {
            watchingBed_ = false;
            context.env->DeleteLocalRef(world);
            burst(context, Vec3{bedX_ + 0.5, bedY_ + 0.5, bedZ_ + 0.5});
            return;
        }
    }

    if (hooks::input().attackHeld() && !state.screenOpen) {
        const game::CrosshairTarget target = game.crosshair(context.env);
        if (target.kind == game::CrosshairTarget::Kind::Block &&
            isBed(game.blockId(context.env, world, target.x, target.y, target.z))) {
            watchingBed_ = true;
            bedX_ = target.x;
            bedY_ = target.y;
            bedZ_ = target.z;
        }
    }
    context.env->DeleteLocalRef(world);
}

void CuteVisuals::burst(Context& context, const Vec3& centre) {
    const game::FrameState& state = *context.state;

    if (rainbow_->booleanValue() && rainbowPool_.size() < kMaxRainbows) {
        Rainbow arc;
        arc.position = centre;
        /* Turned to face where the player was standing, so the arc reads as an
         * arch rather than as a line seen edge on. */
        arc.yaw = static_cast<float>(std::atan2(state.player.pos.x - centre.x,
                                                state.player.pos.z - centre.z) *
                                     static_cast<double>(kRadToDeg));
        rainbowPool_.push_back(arc);
    }

    if (bedBurst_->booleanValue()) {
        const auto count = static_cast<int>(burstCount_->number());
        for (int index = 0; index < count && sparkPool_.size() < kMaxSparks; ++index) {
            Spark spark;
            spark.position = centre;
            const double theta = random_.unit() * kTwoPi;
            const double phi = random_.unit() * 3.14159265358979323846 * 0.67 -
                               3.14159265358979323846 / 6.0;
            const double speed =
                (0.8 + random_.unit() * 1.2) * static_cast<double>(burstSpeed_->number());
            const double cosPhi = std::cos(phi);
            spark.velocity = Vec3{cosPhi * std::cos(theta) * speed,
                                  std::sin(phi) * speed + 1.0,
                                  cosPhi * std::sin(theta) * speed};
            const int roll = random_.range(0, 4);
            spark.type = roll < 2 ? 0 : roll - 1;
            spark.scale = burstSize_->number() * (0.6f + random_.unit() * 0.8f);
            spark.seed = static_cast<int>(sparkPool_.size());
            sparkPool_.push_back(spark);
        }
    }

    if (bedSound_->booleanValue() && state.player.valid) {
        game::Game& game = game::Game::instance();
        game.playSound(context.env, state.player.handle, "entity.experience_orb.pickup", 1.0f,
                       1.5f);
        game.playSound(context.env, state.player.handle, "entity.player.levelup", 0.5f, 2.0f);
    }
}

void CuteVisuals::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid) {
        watchingBed_ = false;
        return;
    }

    watchBed(context, state);

    if (!hasLastPosition_) {
        lastPosition_ = state.player.pos;
        hasLastPosition_ = true;
        return;
    }
    const Vec3 previous = lastPosition_;
    lastPosition_ = state.player.pos;

    if (onlyMoving_->booleanValue() &&
        (state.player.pos - previous).horizontalLength() < 0.01) {
        return;
    }

    if (hearts_->booleanValue() && heartPool_.size() < kMaxHearts &&
        heartTimer_.tryReset(static_cast<std::int64_t>(heartRate_->number()))) {
        Heart heart;
        const double angle = random_.unit() * kTwoPi;
        const double distance = random_.unit() * kHeartRadius;
        heart.position = Vec3{state.player.pos.x + std::cos(angle) * distance,
                              state.player.pos.y + 0.5 + random_.unit() * 0.5,
                              state.player.pos.z + std::sin(angle) * distance};
        heart.spin = random_.range(0.0f, 360.0f);
        heart.tilt = random_.range(-15.0f, 15.0f);
        heart.scale = 0.15f * (0.6f + random_.unit() * 0.8f);
        heart.type = random_.range(0, 2);
        heart.seed = random_.range(0, 255);
        heartPool_.push_back(heart);
    }

    if (dots_->booleanValue() && dotPool_.size() < kMaxDots &&
        dotTimer_.tryReset(static_cast<std::int64_t>(dotRate_->number()))) {
        Dot dot;
        dot.position = Vec3{previous.x + (random_.unit() - 0.5f) * kDotSpread,
                            state.player.pos.y + 0.3 + random_.unit() * 1.2,
                            previous.z + (random_.unit() - 0.5f) * kDotSpread};
        dot.drift = Vec3{(random_.unit() - 0.5f) * kDotDrift,
                         (0.3 + random_.unit() * 0.7) * kDotDrift,
                         (random_.unit() - 0.5f) * kDotDrift};
        dot.scale = 0.04f * (0.5f + random_.unit());
        dot.type = random_.range(0, 3);
        dot.seed = random_.range(0, 255);
        dotPool_.push_back(dot);
    }
}

void CuteVisuals::drawHearts(Context& context, float opacity) {
    const float lifetime = heartLife_->number() / 1000.0f;
    for (Heart& heart : heartPool_) {
        const float progress = heart.age / lifetime;
        const float alpha = envelope(progress, 0.1f, 0.6f) * opacity;
        const float scaleFade = envelope(progress, 0.1f, 0.8f);
        if (alpha < 0.02f || scaleFade <= 0.0f) {
            continue;
        }
        const Vec3 world{
            heart.position.x + std::sin(heart.age * 2.0f + heart.seed * 1.7) * 0.1,
            heart.position.y + 1.5 * progress,
            heart.position.z + std::cos(heart.age * 1.5f + heart.seed * 2.3) * 0.1};

        float x = 0.0f;
        float y = 0.0f;
        float pixels = 0.0f;
        if (!billboard(context, world, x, y, pixels)) {
            continue;
        }
        /* The shape's own units are sixteen across; scale is in blocks. */
        const float scale =
            std::min(heart.scale * scaleFade * pixels, kMaxParticlePixels * 2.0f) / 16.0f;
        const float rotation = (heart.tilt + heart.age * 20.0f) * kDegToRad;
        strokeShape(heartShape().data(), kHeartSegments + 1, x, y, scale, rotation,
                    ui::accentShade(heartWeight(heart.type)), alpha, 1.2f);
    }
}

void CuteVisuals::drawDots(Context& context, float opacity) {
    const float lifetime = dotLife_->number() / 1000.0f;
    const bool pulse = pulse_->booleanValue();
    render::Renderer& gfx = render::renderer();

    for (Dot& dot : dotPool_) {
        const float progress = dot.age / lifetime;
        float alpha = envelope(progress, 0.1f, 0.5f) * opacity;
        if (pulse) {
            const float speed = 3.0f + static_cast<float>(dot.type) * 1.5f;
            alpha *= 0.5f + 0.5f * std::sin(dot.age * speed + static_cast<float>(dot.seed) * 2.7f);
        }
        if (alpha < 0.02f) {
            continue;
        }
        const Vec3 world{
            dot.position.x + dot.drift.x * dot.age + std::sin(dot.age * 1.5f + dot.seed * 1.3) * 0.15,
            dot.position.y + dot.drift.y * dot.age,
            dot.position.z + dot.drift.z * dot.age + std::cos(dot.age * 1.2f + dot.seed * 2.1) * 0.15};

        float x = 0.0f;
        float y = 0.0f;
        float pixels = 0.0f;
        if (!billboard(context, world, x, y, pixels)) {
            continue;
        }
        const float radius = std::min(dot.scale * pixels, kMaxParticlePixels);
        if (radius < 0.3f) {
            continue;
        }
        const Color colour = ui::accentShade(dotWeight(dot.type));
        gfx.glow(Rect{x - radius, y - radius, radius * 2.0f, radius * 2.0f},
                 colour.withAlpha(0.5f * alpha), radius * 1.6f, radius);
        gfx.rect(Rect{x - radius, y - radius, radius * 2.0f, radius * 2.0f},
                 colour.withAlpha(alpha), radius);
    }
}

void CuteVisuals::drawSparks(Context& context) {
    const float lifetime = burstLife_->number() / 1000.0f;
    render::Renderer& gfx = render::renderer();

    for (Spark& spark : sparkPool_) {
        const float progress = spark.age / lifetime;
        const float alpha = envelope(progress, 0.1f, 0.7f);
        const float scaleFade = envelope(progress, 0.1f, 0.7f);
        if (alpha < 0.02f || scaleFade <= 0.0f) {
            continue;
        }
        /* Thrown, then pulled down. The 1.5 is the script's own gravity, in
         * blocks per second squared rather than per tick. */
        const double seconds = spark.age;
        const Vec3 world{
            spark.position.x + spark.velocity.x * seconds +
                std::sin(spark.age * 2.0f + spark.seed * 1.7) * 0.05,
            spark.position.y + spark.velocity.y * seconds - 1.5 * seconds * seconds,
            spark.position.z + spark.velocity.z * seconds +
                std::cos(spark.age * 1.5f + spark.seed * 2.3) * 0.05};

        float x = 0.0f;
        float y = 0.0f;
        float pixels = 0.0f;
        if (!billboard(context, world, x, y, pixels)) {
            continue;
        }
        /* Alternating weights across the burst, so a shower of twenty shapes
         * has depth in it without a second hue. */
        const Color colour = ui::accentShade(bandWeight(spark.seed % 7));
        const float rotation = (spark.age * 40.0f + static_cast<float>(spark.seed) * 60.0f) * kDegToRad;
        const float size = std::min(spark.scale * scaleFade * pixels, kMaxParticlePixels * 2.0f);

        switch (spark.type) {
            case 0:
                strokeShape(heartShape().data(), kHeartSegments + 1, x, y, size / 16.0f, rotation,
                            colour, alpha, 1.2f);
                break;
            case 1:
                strokeShape(starShape().data(), 9, x, y, size / 16.0f, rotation, colour, alpha,
                            1.2f);
                break;
            case 2: {
                const float radius = std::max(size * 0.5f, 0.4f);
                gfx.glow(Rect{x - radius, y - radius, radius * 2.0f, radius * 2.0f},
                         colour.withAlpha(0.45f * alpha), radius * 1.8f, radius);
                gfx.rect(Rect{x - radius, y - radius, radius * 2.0f, radius * 2.0f},
                         colour.withAlpha(alpha), radius);
                break;
            }
            default: {
                static const Vec2 kDiamond[5] = {{0.0f, -14.0f}, {8.0f, 0.0f}, {0.0f, 14.0f},
                                                 {-8.0f, 0.0f},  {0.0f, -14.0f}};
                strokeShape(kDiamond, 5, x, y, size / 16.0f, rotation, colour, alpha, 1.2f);
                break;
            }
        }
    }
}

void CuteVisuals::drawRainbows(Context& context) {
    const float lifetime = rainbowLife_->number() / 1000.0f;
    const float width = rainbowWidth_->number();
    render::Renderer& gfx = render::renderer();

    for (Rainbow& arc : rainbowPool_) {
        const float progress = arc.age / lifetime;
        const float alpha = envelope(progress, 0.15f, 0.6f);
        if (alpha < 0.02f) {
            continue;
        }
        /* The arc draws itself in over the first fifth of its life, which is
         * the whole reason it reads as an arch going up rather than one that
         * was simply switched on. */
        const float sweep = progress < 0.2f ? (progress / 0.2f) * (progress / 0.2f) : 1.0f;

        const double yaw = static_cast<double>(arc.yaw) * kDegToRad;
        const Vec3 right{std::cos(yaw), 0.0, -std::sin(yaw)};

        for (int band = 0; band < 7; ++band) {
            const double radius = 3.0 + (band - 3) * 0.15;
            if (radius < 0.1) {
                continue;
            }
            const Color colour = ui::accentShade(bandWeight(band));
            float previousX = 0.0f;
            float previousY = 0.0f;
            bool have = false;
            for (int segment = 0; segment <= kRainbowSegments; ++segment) {
                const double angle = static_cast<double>(segment) / kRainbowSegments *
                                     3.14159265358979323846 * sweep;
                const Vec3 point{arc.position.x + right.x * std::cos(angle) * radius,
                                 arc.position.y + std::sin(angle) * radius,
                                 arc.position.z + right.z * std::cos(angle) * radius};
                game::ScreenPoint screen;
                if (!context.projection->project(point, screen)) {
                    have = false;
                    continue;
                }
                if (have) {
                    gfx.line(previousX, previousY, screen.x, screen.y, width + 2.0f,
                             colour.withAlpha(0.2f * alpha));
                    gfx.line(previousX, previousY, screen.x, screen.y, width,
                             colour.withAlpha(0.85f * alpha));
                }
                previousX = screen.x;
                previousY = screen.y;
                have = true;
            }
        }
    }
}

void CuteVisuals::onRender(Context& context) {
    const float delta = context.deltaTime;
    for (Heart& heart : heartPool_) heart.age += delta;
    for (Dot& dot : dotPool_) dot.age += delta;
    for (Spark& spark : sparkPool_) spark.age += delta;
    for (Rainbow& arc : rainbowPool_) arc.age += delta;

    const auto expire = [](auto& pool, float lifetime) {
        pool.erase(std::remove_if(pool.begin(), pool.end(),
                                  [&](const auto& item) { return item.age >= lifetime; }),
                   pool.end());
    };
    expire(heartPool_, heartLife_->number() / 1000.0f);
    expire(dotPool_, dotLife_->number() / 1000.0f);
    expire(sparkPool_, burstLife_->number() / 1000.0f);
    expire(rainbowPool_, rainbowLife_->number() / 1000.0f);

    if (!context.projection->valid()) {
        return;
    }
    const float opacity = opacity_->number() / 100.0f * animation();
    if (hearts_->booleanValue()) {
        drawHearts(context, opacity);
    }
    if (dots_->booleanValue()) {
        drawDots(context, opacity);
    }
    drawSparks(context);
    drawRainbows(context);
}

}  // namespace cold::modules
