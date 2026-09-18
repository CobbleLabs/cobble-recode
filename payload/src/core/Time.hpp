#pragma once

#include <chrono>
#include <cstdint>

namespace cold {

inline double nowSeconds() {
    using clock = std::chrono::steady_clock;
    static const clock::time_point epoch = clock::now();
    return std::chrono::duration<double>(clock::now() - epoch).count();
}

inline std::int64_t nowMillis() {
    return static_cast<std::int64_t>(nowSeconds() * 1000.0);
}

/* Millisecond stopwatch used by everything that fires on a delay: click
 * timers, place cooldowns, HUD fades. */
class Timer {
public:
    void reset() { mark_ = nowMillis(); }
    /* Banks part of the wait up front, so the mark reads as already old.
     * Used for first-engagement reaction: a freshly enabled module must not
     * wait out a full delay while already staring at someone. */
    void elapse(std::int64_t millis) { mark_ -= millis; }
    std::int64_t elapsed() const { return nowMillis() - mark_; }
    bool elapsed(std::int64_t millis) const { return elapsed() >= millis; }

    bool tryReset(std::int64_t millis) {
        if (!elapsed(millis)) {
            return false;
        }
        reset();
        return true;
    }

private:
    std::int64_t mark_ = 0;
};

/* Deterministic 32-bit xorshift. Module timing jitter wants a cheap, private
 * generator rather than perturbing the global rand() state of the host JVM
 * process. */
class Random {
public:
    explicit Random(std::uint32_t seed = 0x1F123BB5u) : state_(seed == 0 ? 1u : seed) {}

    std::uint32_t next() {
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return state_;
    }

    float unit() { return static_cast<float>(next() >> 8) / 16777216.0f; }
    float range(float low, float high) { return low + unit() * (high - low); }
    int range(int low, int high) {
        if (high <= low) return low;
        return low + static_cast<int>(next() % static_cast<std::uint32_t>(high - low + 1));
    }

private:
    std::uint32_t state_;
};

}  // namespace cold
