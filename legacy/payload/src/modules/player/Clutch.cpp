#include "modules/player/Clutch.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "bridge/Minecraft.hpp"
#include "game/Raycast.hpp"
#include "game/RotationEngine.hpp"
#include "hooks/Input.hpp"

namespace cold::modules {
namespace {

constexpr double kHalfWidth = 0.3;
constexpr double kHeight = 1.8;
constexpr int kClutchPriority = 80;
/* The box the original walks: one to four cells under the feet, five out on
 * each side. Anything outside it is out of reach of a clutch anyway. */
constexpr int kSearchRadius = 5;
constexpr int kSearchDepth = 4;
/* Samples per axis across a face, as fractions of a block. Raven walks 0 to 1
 * in fifths - six values, corners and centre included - and jitters each one by
 * a tenth of the step so two consecutive clutches do not click the same pixel
 * of the same face. */
constexpr double kSampleStep = 0.2;
constexpr double kInset = 0.05;
/* Rays a single tick's search may spend. The cell list is sorted nearest-first
 * and the first face that works ends it, so this is only ever reached by a tick
 * spent inside a wall - where without it the search would cast a hundred rays
 * at each of a hundred cells. */
constexpr int kRayBudget = 2400;

int floorToInt(double value) { return static_cast<int>(std::floor(value)); }

double clamp01(double value) { return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value); }

/* Angles from an eye to a point, in Minecraft's convention. */
void anglesTo(const Vec3& eye, const Vec3& point, float& yaw, float& pitch) {
    const double dx = point.x - eye.x;
    const double dy = point.y - eye.y;
    const double dz = point.z - eye.z;
    const double horizontal = std::sqrt(dx * dx + dz * dz);
    yaw = wrapDegrees(static_cast<float>(std::atan2(dz, dx) * static_cast<double>(kRadToDeg)) -
                      90.0f);
    pitch = static_cast<float>(-std::atan2(dy, horizontal) * static_cast<double>(kRadToDeg));
    pitch = clamp(pitch, -90.0f, 90.0f);
}

/* Squared distance from a point to a cell's box, which is what decides which
 * candidate is worth casting at first and which are out of reach entirely. */
double cellDistance(const Vec3& from, int x, int y, int z) {
    const double dx = from.x - clamp(from.x, static_cast<double>(x), static_cast<double>(x + 1));
    const double dy = from.y - clamp(from.y, static_cast<double>(y), static_cast<double>(y + 1));
    const double dz = from.z - clamp(from.z, static_cast<double>(z), static_cast<double>(z + 1));
    return dx * dx + dy * dy + dz * dz;
}

/* Whether a block filling this cell would land inside the player.
 *
 * Vanilla asks the same question through `World.canPlace`, which runs the
 * candidate shape against every entity in it. Without it the nearest cell to a
 * standing player is always the one under their feet, its top face is always
 * reachable, and the block it would place always goes in the cell the player is
 * standing in - so the search settles on a placement that can never succeed and
 * never looks at the wall beside it. */
bool intersectsPlayer(const game::FrameState& state, int x, int y, int z) {
    const Vec3& feet = state.player.pos;
    return static_cast<double>(x) < feet.x + kHalfWidth &&
           static_cast<double>(x + 1) > feet.x - kHalfWidth &&
           static_cast<double>(y) < feet.y + kHeight && static_cast<double>(y + 1) > feet.y &&
           static_cast<double>(z) < feet.z + kHalfWidth &&
           static_cast<double>(z + 1) > feet.z - kHalfWidth;
}

/* The player's hitbox against the block grid. Every collider the payload can
 * see is a full cube, so a corner test at each of the four footprint columns
 * over every layer the box spans is the whole of it. */
bool blockedAt(JNIEnv* env, jobject world, const Vec3& feet) {
    const int lowY = floorToInt(feet.y);
    const int highY = floorToInt(feet.y + kHeight - 0.01);
    for (int y = lowY; y <= highY; ++y) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                const int cellX = floorToInt(feet.x + kHalfWidth * dx);
                const int cellZ = floorToInt(feet.z + kHalfWidth * dz);
                if (!game::blockPassable(env, world, cellX, y, cellZ)) {
                    return true;
                }
            }
        }
    }
    return false;
}

}  // namespace

Clutch::Clutch()
    : Module("Clutch", "Turns to a face within reach and blocks the fall", Category::Player, 0) {
    setIcon(assets::IconAsset::Box);

    reach_ = &addNumber("Reach", "How far a face may be to count", 4.5f, 1.0f, 4.5f, 1);
    speed_ = &addNumber("Speed", "Degrees the aim may travel per tick", 80.0f, 5.0f, 180.0f, 0);
    snapback_ = &addNumber("Snapback speed", "Degrees per tick on the way back", 120.0f, 5.0f,
                           180.0f, 0);
    maxBlocks_ = &addNumber("Max blocks", "Blocks to spend in one fall; zero is unlimited", 10.0f,
                            0.0f, 20.0f, 0);
    tolerance_ = &addNumber("Rotation tolerance", "Degrees the aim may be from the sent rotation "
                                                  "and still place",
                            25.0f, 5.0f, 100.0f, 0);
    predict_ = &addBoolean("Simulate future position", "Weight the search towards where the fall "
                                                       "is taking you",
                           true);
    clickMode_ = &addChoice("Click",
                            "Vanilla queues a press the game acts on itself; Direct sends the "
                            "placement from here and lands behind the movement packet",
                            {"Vanilla", "Direct"}, 0);
    silent_ = &addBoolean("Silent", "Send the aim without moving the camera", true);
    auto_ = &addBoolean("Auto clutch", "Arm on a hit and clutch a long fall by itself", false);
    minimumFall_ = &addNumber("Minimum fall distance", "Blocks of predicted fall before auto arms",
                              10.0f, 3.0f, 20.0f, 0)
                       .visibleWhen([this] { return auto_->booleanValue(); });
    bind_ = &addKey("Bind", "Held to clutch by hand", 0);
}

std::string Clutch::suffix() const {
    if (!holdingAim_) {
        return {};
    }
    char text[16];
    std::snprintf(text, sizeof(text), "%d", placed_);
    return text;
}

void Clutch::onEnable(Context&) {
    holdingAim_ = false;
    placed_ = 0;
    previousSlot_ = -1;
    plannedSlot_ = -1;
    bindingsTaken_ = false;
    hasLastPlaced_ = false;
    autoArmed_ = false;
    autoChecking_ = false;
    autoCheckTick_ = 0;
    autoLandedGuard_ = false;
    autoLandedAge_ = 0;
    lastHurtTime_ = -1;
}

void Clutch::onDisable(Context& context) {
    giveBindings(context);
    restoreSlot(context);
    holdingAim_ = false;
    hasLastPlaced_ = false;
    autoArmed_ = false;
    autoChecking_ = false;
    autoLandedGuard_ = false;
}

void Clutch::restoreSlot(Context& context) {
    if (previousSlot_ >= 0 && context.state->player.valid) {
        game::Game::instance().setSelectedSlot(context.env, context.state->player.handle,
                                               previousSlot_);
    }
    previousSlot_ = -1;
    plannedSlot_ = -1;
}

/* Raven holds attack and use down for the player for as long as an aim is live
 * and hands them back from the real mouse state afterwards. Without it a player
 * leaning on right mouse places at the raw crosshair every tick, which is both
 * the wrong block and a placement the confirmed rotation never covered. */
void Clutch::takeBindings(Context& context) {
    /* Every tick, not once: the player can press the button at any point during
     * the clutch and the game's own callback sets the binding back. */
    bindingsTaken_ = true;
    game::Game& game = game::Game::instance();
    game.setBinding(context.env, "attack", false);
    game.setBinding(context.env, "use", false);
}

void Clutch::giveBindings(Context& context) {
    if (!bindingsTaken_) {
        return;
    }
    bindingsTaken_ = false;
    if (context.state->screenOpen) {
        return;
    }
    game::Game& game = game::Game::instance();
    game.setBinding(context.env, "attack", hooks::input().attackHeld());
    game.setBinding(context.env, "use", hooks::input().useHeld());
}

Clutch::Body Clutch::bodyOf(const game::FrameState& state) const {
    Body body;
    body.pos = state.player.pos;
    body.motion = state.player.velocity;
    body.onGround = state.player.onGround;
    return body;
}

void Clutch::step(JNIEnv* env, jobject world, Body& body, bool stopHorizontal) const {
    if (stopHorizontal) {
        body.motion.x = 0.0;
        body.motion.z = 0.0;
    }
    body.motion.y -= 0.08;

    const double wantX = body.motion.x;
    const double wantY = body.motion.y;
    const double wantZ = body.motion.z;

    /* Vanilla sweeps the hitbox; this walks it in slices no longer than half a
     * block instead, which is the same answer against full cubes and is the
     * difference between noticing the ground and falling straight through it. */
    const double longest =
        std::max(std::fabs(wantX), std::max(std::fabs(wantY), std::fabs(wantZ)));
    const int slices = std::max(1, static_cast<int>(std::ceil(longest / 0.45)));
    const double sliceX = wantX / slices;
    const double sliceY = wantY / slices;
    const double sliceZ = wantZ / slices;

    bool hitX = false;
    bool hitY = false;
    bool hitZ = false;
    body.onGround = false;

    for (int slice = 0; slice < slices && !hitY; ++slice) {
        const Vec3 next{body.pos.x, body.pos.y + sliceY, body.pos.z};
        if (blockedAt(env, world, next)) {
            hitY = true;
            if (sliceY < 0.0) {
                body.pos.y = std::floor(next.y) + 1.0;
                body.onGround = true;
            }
        } else {
            body.pos.y = next.y;
        }

        const Vec3 alongX{body.pos.x + sliceX, body.pos.y, body.pos.z};
        if (blockedAt(env, world, alongX)) {
            hitX = true;
        } else {
            body.pos.x = alongX.x;
        }

        const Vec3 alongZ{body.pos.x, body.pos.y, body.pos.z + sliceZ};
        if (blockedAt(env, world, alongZ)) {
            hitZ = true;
        } else {
            body.pos.z = alongZ.z;
        }
    }

    if (hitX) {
        body.motion.x = 0.0;
    }
    if (hitY) {
        body.motion.y = 0.0;
    }
    if (hitZ) {
        body.motion.z = 0.0;
    }

    body.motion.y *= 0.9800000190734863;
    body.motion.x *= 0.91;
    body.motion.z *= 0.91;
}

bool Clutch::willFallFar(Context& context, const game::FrameState& state, double minimum) const {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return false;
    }
    Body body = bodyOf(state);
    const double startY = state.player.pos.y;
    bool far = false;
    for (int tick = 0; tick < 60; ++tick) {
        step(context.env, world, body, false);
        if (body.onGround) {
            break;
        }
        /* Answered the moment the drop is long enough, not at the end of the
         * simulation: a fall that clears the threshold and lands sixty ticks
         * later is still a fall worth a block. */
        if (startY - body.pos.y > minimum) {
            far = true;
            break;
        }
    }
    context.env->DeleteLocalRef(world);
    return far;
}

bool Clutch::willFallSoon(Context& context, const game::FrameState& state) const {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return false;
    }
    Body body = bodyOf(state);
    bool soon = false;
    for (int tick = 0; tick < 10; ++tick) {
        step(context.env, world, body, true);
        if (!body.onGround && body.motion.y < 0.0) {
            soon = true;
            break;
        }
    }
    context.env->DeleteLocalRef(world);
    return soon;
}

Vec3 Clutch::futurePosition(Context& context, jobject world,
                            const game::FrameState& state) const {
    Body body = bodyOf(state);
    const double startY = state.player.pos.y;
    for (int tick = 0; tick < 20; ++tick) {
        step(context.env, world, body, false);
        /* Two blocks of drop and no further. The point of the future position
         * is to lean the search towards the side of the box the fall is
         * carrying the player onto, and a point a hundred blocks down is not
         * nearer to any of them - it sorts the whole box by depth and buries
         * the wall that is actually within reach. */
        if (body.pos.y < startY - 2.0 || body.onGround) {
            break;
        }
    }
    return body.pos;
}

bool Clutch::underPlayer(const game::FrameState& state, int cellX, int cellY, int cellZ) const {
    if (cellY >= floorToInt(state.player.pos.y)) {
        return false;
    }
    for (int dx = -1; dx <= 1; dx += 2) {
        for (int dz = -1; dz <= 1; dz += 2) {
            if (cellX == floorToInt(state.player.pos.x + kHalfWidth * dx) &&
                cellZ == floorToInt(state.player.pos.z + kHalfWidth * dz)) {
                return true;
            }
        }
    }
    return false;
}

/* Raven's first gate, and the line between a clutch and a scaffold: there has to
 * be nothing under the feet. Standing on something, the module would find the
 * side of the block it is standing on and start bridging outwards - placing on
 * every tick from a stationary player, which is neither what the bind is for nor
 * a shape worth sending. */
bool Clutch::overAir(Context& context, const game::FrameState& state) const {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return false;
    }
    const bool air = game::blockPassable(context.env, world, floorToInt(state.player.pos.x),
                                         floorToInt(state.player.pos.y) - 1,
                                         floorToInt(state.player.pos.z));
    context.env->DeleteLocalRef(world);
    return air;
}

bool Clutch::placeable(Context& context, jobject world, const game::FrameState& state,
                       const game::BlockHit& hit) const {
    int x = hit.x;
    int y = hit.y;
    int z = hit.z;
    switch (hit.face) {
        case game::BlockFace::Up: ++y; break;
        case game::BlockFace::Down: --y; break;
        case game::BlockFace::North: --z; break;
        case game::BlockFace::South: ++z; break;
        case game::BlockFace::West: --x; break;
        case game::BlockFace::East: ++x; break;
    }
    return game::blockPassable(context.env, world, x, y, z) && !intersectsPlayer(state, x, y, z);
}

bool Clutch::faceAim(Context& context, jobject world, const game::FrameState& state, int cellX,
                     int cellY, int cellZ, bool under, int& budget, Aim& out) const {
    struct Candidate {
        double cost;
        float yaw;
        float pitch;
    };

    const Vec3 eye = state.player.eyePos;
    const float baseYaw = game::RotationEngine::instance().pendingYaw();
    const float basePitch = game::RotationEngine::instance().pendingPitch();
    /* The faces worth clicking are the two the eye is on the near side of, plus
     * the top when the cell sits under the player - a bridge block goes on top
     * of the one below and against the side of everything else. */
    const bool nearSouth = eye.z > static_cast<double>(cellZ) + 0.5;
    const bool nearEast = eye.x > static_cast<double>(cellX) + 0.5;
    const auto samples = static_cast<int>(std::lround(1.0 / kSampleStep));
    const auto jitter = static_cast<float>(kSampleStep * 0.1);

    std::vector<Candidate> candidates;
    candidates.reserve(static_cast<std::size_t>(samples + 1) * (samples + 1) * 3 + 1);
    /* The rotation already going out, at no cost at all. Looking at a face that
     * works means clicking it this tick without moving the mouse. */
    candidates.push_back(Candidate{0.0, baseYaw, basePitch});

    for (int row = 0; row <= samples; ++row) {
        const double v = clamp01(row * kSampleStep +
                                 static_cast<double>(random_.range(-jitter, jitter)));
        for (int column = 0; column <= samples; ++column) {
            const double u = clamp01(column * kSampleStep +
                                     static_cast<double>(random_.range(-jitter, jitter)));
            const auto push = [&](const Vec3& point) {
                float yaw = 0.0f;
                float pitch = 0.0f;
                anglesTo(eye, point, yaw, pitch);
                const double cost = std::fabs(static_cast<double>(angleDifference(baseYaw, yaw))) +
                                    std::fabs(static_cast<double>(pitch - basePitch));
                candidates.push_back(Candidate{cost, yaw, pitch});
            };

            if (under) {
                push(Vec3{cellX + u, cellY + 1.0 - kInset, cellZ + v});
            }
            push(Vec3{cellX + u, cellY + v, nearSouth ? cellZ + 1.0 - kInset : cellZ + kInset});
            push(Vec3{nearEast ? cellX + 1.0 - kInset : cellX + kInset, cellY + v, cellZ + u});
        }
    }

    std::sort(candidates.begin(), candidates.end(),
              [](const Candidate& a, const Candidate& b) { return a.cost < b.cost; });

    const auto reach = static_cast<double>(reach_->number());
    for (const Candidate& candidate : candidates) {
        if (budget <= 0) {
            return false;
        }
        --budget;
        const game::BlockHit hit = game::raycastBlocks(
            context.env, world, eye, game::lookVector(candidate.yaw, candidate.pitch), reach);
        if (!hit.hit || hit.x != cellX || hit.y != cellY || hit.z != cellZ) {
            continue;
        }
        if (hit.face == game::BlockFace::Down) {
            continue;
        }
        if (hit.face == game::BlockFace::Up && !under) {
            continue;
        }
        /* The cell the block would land in has to be empty, or the click is
         * spent on nothing. */
        if (!placeable(context, world, state, hit)) {
            continue;
        }

        out.yaw = candidate.yaw;
        out.pitch = candidate.pitch;
        out.x = cellX;
        out.y = cellY;
        out.z = cellZ;
        out.face = hit.face;
        out.point = hit.point;
        return true;
    }
    return false;
}

bool Clutch::search(Context& context, const game::FrameState& state, Aim& out) const {
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        return false;
    }

    const bool weighted = predict_->booleanValue();
    const Vec3 future = weighted ? futurePosition(context, world, state) : state.player.pos;

    struct Cell {
        double score;
        int x;
        int y;
        int z;
    };
    std::vector<Cell> cells;

    const int feetX = floorToInt(state.player.pos.x);
    const int feetY = floorToInt(state.player.pos.y);
    const int feetZ = floorToInt(state.player.pos.z);
    const auto reach = static_cast<double>(reach_->number());
    const double reachSquared = reach * reach;

    for (int y = feetY - 1; y >= feetY - kSearchDepth; --y) {
        for (int x = feetX - kSearchRadius; x <= feetX + kSearchRadius; ++x) {
            for (int z = feetZ - kSearchRadius; z <= feetZ + kSearchRadius; ++z) {
                /* Nothing out of reach of the eye can be hit by any rotation,
                 * so it is not worth a single ray. */
                if (cellDistance(state.player.eyePos, x, y, z) > reachSquared) {
                    continue;
                }
                if (game::blockPassable(context.env, world, x, y, z)) {
                    continue;
                }
                const double now = cellDistance(state.player.pos, x, y, z);
                const double soon = cellDistance(future, x, y, z);
                double score = weighted ? now * 0.3 + soon * 0.7 : now;
                if (hasLastPlaced_ && x == lastPlacedX_ && y == lastPlacedY_ && z == lastPlacedZ_) {
                    score *= 0.95;
                }
                cells.push_back(Cell{score, x, y, z});
            }
        }
    }
    std::sort(cells.begin(), cells.end(),
              [](const Cell& a, const Cell& b) { return a.score < b.score; });

    bool found = false;
    int budget = kRayBudget;
    for (const Cell& cell : cells) {
        if (budget <= 0) {
            break;
        }
        if (faceAim(context, world, state, cell.x, cell.y, cell.z,
                    underPlayer(state, cell.x, cell.y, cell.z), budget, out)) {
            found = true;
            break;
        }
    }

    context.env->DeleteLocalRef(world);
    return found;
}

int Clutch::blockSlot(Context& context, const game::FrameState& state) const {
    game::Game& game = game::Game::instance();
    const int current = game.selectedSlot(context.env, state.player.handle);
    if (game.stack(context.env, state.player.handle, current).block) {
        return current;
    }
    for (int slot = 8; slot >= 0; --slot) {
        if (game.stack(context.env, state.player.handle, slot).block) {
            return slot;
        }
    }
    return -1;
}

void Clutch::updateAuto(Context& context, const game::FrameState& state) {
    if (!auto_->booleanValue()) {
        autoArmed_ = false;
        autoChecking_ = false;
        autoLandedGuard_ = false;
        lastHurtTime_ = state.player.hurtTime;
        return;
    }

    if (state.player.hurtTime > lastHurtTime_) {
        autoChecking_ = true;
        autoCheckTick_ = 0;
        autoLandedGuard_ = false;
    }
    lastHurtTime_ = state.player.hurtTime;

    /* The prediction is the expensive half - a few dozen block lookups - so it
     * runs every third tick while the check is live rather than every tick. */
    if (autoChecking_ && !autoArmed_ && !autoLandedGuard_) {
        if (autoCheckTick_ % 3 == 0 &&
            willFallFar(context, state, static_cast<double>(minimumFall_->number()))) {
            autoArmed_ = true;
        }
        ++autoCheckTick_;
    }

    /* Touching down is not the same as having landed - a ledge on the way past
     * is still a fall. The guard keeps the aim alive for ten ticks and drops it
     * early only on something that says the fall is genuinely over: a jump, or
     * upward motion. */
    if (autoLandedGuard_) {
        const bool expired = state.player.age - autoLandedAge_ >= 10;
        const bool jumped = game::Game::instance().bindingPressed(context.env, "jump");
        const bool rising = !state.player.onGround && state.player.velocity.y > 0.0;
        if (expired || jumped || rising) {
            autoArmed_ = false;
            autoChecking_ = false;
            autoLandedGuard_ = false;
        }
    }

    if (autoArmed_ && !autoLandedGuard_ && state.player.onGround &&
        state.player.hurtTime < state.player.maxHurtTime - 2) {
        autoLandedGuard_ = true;
        autoLandedAge_ = state.player.age;
        /* Standing on something that is not about to give way ends it outright. */
        if (!willFallSoon(context, state)) {
            autoArmed_ = false;
            autoChecking_ = false;
            autoLandedGuard_ = false;
        }
    }

    if (!autoArmed_ && !autoLandedGuard_ && state.player.onGround && state.player.hurtTime == 0) {
        autoChecking_ = false;
        autoCheckTick_ = 0;
    }
}

void Clutch::onTick(Context& context) {
    const game::FrameState& state = *context.state;
    if (!state.inWorld || !state.player.valid || state.screenOpen) {
        onDisable(context);
        return;
    }

    game::Game& game = game::Game::instance();
    game::RotationEngine& engine = game::RotationEngine::instance();

    if (state.player.onGround) {
        placed_ = 0;
    }
    updateAuto(context, state);

    const bool held = bind_->key() != 0 && hooks::input().bindHeld(bind_->key());
    if ((!held && !autoArmed_) || !overAir(context, state)) {
        if (holdingAim_) {
            /* Back to where the player was looking, at the snapback speed, so
             * the camera does not simply teleport. */
            game::RotationTarget back;
            back.yaw = state.player.yaw;
            back.pitch = state.player.pitch;
            back.speed = snapback_->number();
            back.smoothing = 0.2f;
            back.silent = silent_->booleanValue();
            back.priority = kClutchPriority;
            engine.submit(back);
            holdingAim_ = false;
        }
        hasLastPlaced_ = false;
        giveBindings(context);
        restoreSlot(context);
        return;
    }

    const int slot = blockSlot(context, state);
    if (slot < 0) {
        holdingAim_ = false;
        giveBindings(context);
        restoreSlot(context);
        return;
    }

    /* The aim is re-chosen every tick. A fall moves the eye half a block or more
     * between them, so a face that was the nearest one a tick ago rarely still
     * is, and holding onto the old one is how the aim ends up chasing a block it
     * has already fallen past. */
    Aim found;
    if (search(context, state, found)) {
        aim_ = found;
        holdingAim_ = true;
    }
    if (!holdingAim_) {
        giveBindings(context);
        restoreSlot(context);
        return;
    }

    /* An aim is live, so the hand comes off the mouse and the block comes into
     * it - for as long as the aim lasts, not just for the tick a placement
     * happens on. */
    takeBindings(context);
    if (previousSlot_ < 0) {
        previousSlot_ = game.selectedSlot(context.env, state.player.handle);
    }
    if (plannedSlot_ != slot) {
        plannedSlot_ = slot;
        game.setSelectedSlot(context.env, state.player.handle, slot);
    }

    game::RotationTarget target;
    target.yaw = aim_.yaw;
    target.pitch = aim_.pitch;
    target.speed = speed_->number();
    target.smoothing = 0.25f;
    target.jitter = random_.range(0, 1);
    target.silent = silent_->booleanValue();
    target.priority = kClutchPriority;
    engine.submit(target);

    const int limit = static_cast<int>(maxBlocks_->number());
    if (limit > 0 && placed_ >= limit) {
        return;
    }

    /* The engine steps the aim on the tick edge, so the rotation in this tick's
     * movement packet is one step behind the aim just chosen. That rotation, and
     * nothing else, decides the placement: cast it, take the face it lands on,
     * and click there. Demanding it land on the exact cell the search picked
     * would mean never clicking at all - the search moves cell every tick while
     * the aim is still travelling towards the last one. */
    const float sentYaw = engine.pendingYaw();
    const float sentPitch = engine.pendingPitch();
    if (std::fabs(angleDifference(sentYaw, aim_.yaw)) > tolerance_->number() ||
        std::fabs(sentPitch - aim_.pitch) > tolerance_->number()) {
        return;
    }

    jobject world = game.world(context.env);
    if (world == nullptr) {
        return;
    }
    const game::BlockHit hit = game::raycastBlocks(
        context.env, world, state.player.eyePos, game::lookVector(sentYaw, sentPitch),
        static_cast<double>(reach_->number()));
    const bool usable = hit.hit && hit.y < floorToInt(state.player.pos.y) &&
                        hit.face != game::BlockFace::Down &&
                        (hit.face != game::BlockFace::Up ||
                         underPlayer(state, hit.x, hit.y, hit.z)) &&
                        placeable(context, world, state, hit);
    context.env->DeleteLocalRef(world);
    if (!usable) {
        return;
    }

    hasLastPlaced_ = true;
    lastPlacedX_ = hit.x;
    lastPlacedY_ = hit.y;
    lastPlacedZ_ = hit.z;

    /* A block set on top of the one under the feet is the first tick of every
     * clutch and costs nothing, so it does not come out of the budget - only the
     * ones built out to the side do. */
    const bool counts = hit.face != game::BlockFace::Up;

    if (clickMode_->choice() == 0) {
        /* One press onto the use binding. The game drains it in
         * handleInputEvents on the next tick and runs the whole of vanilla
         * doItemUse from there - the packet, the swing, the cooldown - so the
         * placement leaves from where Minecraft leaves from.
         *
         * doItemUse works off the cached crosshair, which the game last built
         * on the previous rendered frame from the camera. That is the wrong
         * face whenever the aim has moved since, and it is *always* the wrong
         * face with a silent rotation, which never touches the camera at all.
         * So the confirmed hit is written into the crosshair first: same face,
         * same hit vector, same ray the rotation about to go out produces. */
        game.setCrosshairTarget(context.env, hit.x, hit.y, hit.z, hit.face, hit.point);
        /* The vanilla use path parks a four tick cooldown of its own. It only
         * gates the held button, which is already down for the player here, so
         * clearing it costs nothing and is the difference between a block a
         * tick and a block every fifth one. */
        game.setItemUseCooldown(context.env, 0);
        game.queueClick(context.env, true);
        if (counts) {
            ++placed_;
        }
        return;
    }
    if (game.placeBlock(context.env, state.player.handle, hit.x, hit.y, hit.z, hit.face, hit.point,
                        true) &&
        counts) {
        ++placed_;
    }
}

}  // namespace cold::modules
