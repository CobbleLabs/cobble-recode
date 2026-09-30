#pragma once

#include <algorithm>
#include <vector>

#include "bridge/Minecraft.hpp"
#include "core/Time.hpp"

namespace cold::game {

/* Notices when a player that was there a tick ago is not any more.
 *
 * `captureEntities` drops anything that fails `isAlive`, so a death is an
 * absence rather than a health reading, and there is no client-side signal for
 * *who* landed the blow. Anyone dying next to you is close enough for what this
 * is used for: holding a combat module still for a moment after a kill instead
 * of dumping the next crystal into a corpse.
 *
 * Membership is every player the frame captured, out to the capture distance,
 * rather than everyone inside the radius the caller cares about. Those are very
 * different tests: someone stepping from eleven blocks to thirteen leaves a
 * radius but not the capture set, and treating that as a kill would stall a
 * module every time an opponent repositioned. Leaving the capture set means
 * leaving render distance, logging out, or dying - and of those three only the
 * last happens in a fight. */
class DeathWatch {
public:
    void reset() { seen_.clear(); }

    /* True on the tick a player who was within `radius` disappears. */
    bool update(const FrameState& state, double radius) {
        scratch_.clear();
        for (const EntityView& entity : state.entities) {
            if (!entity.isPlayer || entity.crystal || entity.health <= 0.0f) {
                continue;
            }
            scratch_.push_back(Seen{entity.id, (entity.tickPos - state.player.pos).length()});
        }

        bool died = false;
        for (const Seen& previous : seen_) {
            /* Only somebody who was close enough to have been the one you were
             * fighting. A player vanishing at the far edge of render distance is
             * a chunk unloading, not a kill. */
            if (previous.distance > radius) {
                continue;
            }
            const auto still = std::find_if(scratch_.begin(), scratch_.end(),
                                            [&](const Seen& now) { return now.id == previous.id; });
            if (still == scratch_.end()) {
                died = true;
                break;
            }
        }
        seen_.swap(scratch_);
        return died;
    }

private:
    struct Seen {
        int id = 0;
        double distance = 0.0;
    };

    std::vector<Seen> seen_;
    std::vector<Seen> scratch_;
};

/* A module that stops for a moment after a kill: one timer plus the watch that
 * arms it, so the two modules that want this behave identically. */
class KillPause {
public:
    void reset() {
        watch_.reset();
        pausedUntil_ = 0;
        active_ = false;
    }

    /* Call once per tick. Returns true while the module should hold still. */
    bool update(const FrameState& state, bool enabled, std::int64_t milliseconds,
                double radius = 12.0) {
        if (!enabled) {
            watch_.update(state, radius);
            active_ = false;
            return false;
        }
        if (watch_.update(state, radius)) {
            timer_.reset();
            pausedUntil_ = milliseconds;
            active_ = true;
        }
        if (active_ && timer_.elapsed(pausedUntil_)) {
            active_ = false;
        }
        return active_;
    }

    bool paused() const { return active_; }

private:
    DeathWatch watch_;
    Timer timer_;
    std::int64_t pausedUntil_ = 0;
    bool active_ = false;
};

}  // namespace cold::game
