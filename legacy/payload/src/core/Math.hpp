#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace cold {

inline constexpr float kPi = 3.14159265358979323846f;
inline constexpr float kDegToRad = kPi / 180.0f;
inline constexpr float kRadToDeg = 180.0f / kPi;

template <typename T>
constexpr T clamp(T value, T low, T high) {
    return value < low ? low : (value > high ? high : value);
}

constexpr float lerp(float a, float b, float t) { return a + (b - a) * t; }
constexpr double lerp(double a, double b, double t) { return a + (b - a) * t; }

inline float wrapDegrees(float degrees) {
    degrees = std::fmod(degrees, 360.0f);
    if (degrees >= 180.0f) degrees -= 360.0f;
    if (degrees < -180.0f) degrees += 360.0f;
    return degrees;
}

inline float angleDifference(float from, float to) { return wrapDegrees(to - from); }

struct Vec2 {
    float x = 0.0f;
    float y = 0.0f;

    constexpr Vec2 operator+(const Vec2& o) const { return {x + o.x, y + o.y}; }
    constexpr Vec2 operator-(const Vec2& o) const { return {x - o.x, y - o.y}; }
    constexpr Vec2 operator*(float s) const { return {x * s, y * s}; }
    float length() const { return std::sqrt(x * x + y * y); }
};

struct Vec3 {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;

    constexpr Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    constexpr Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    constexpr Vec3 operator*(double s) const { return {x * s, y * s, z * s}; }
    double lengthSquared() const { return x * x + y * y + z * z; }
    double length() const { return std::sqrt(lengthSquared()); }
    double horizontalLength() const { return std::sqrt(x * x + z * z); }
    constexpr double dot(const Vec3& o) const { return x * o.x + y * o.y + z * o.z; }
};

/* Column-major, matching OpenGL and JOML memory order: m[column * 4 + row]. */
struct Mat4 {
    float m[16] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    static Mat4 identity() { return Mat4{}; }

    float& at(int column, int row) { return m[column * 4 + row]; }
    float at(int column, int row) const { return m[column * 4 + row]; }

    Mat4 operator*(const Mat4& rhs) const {
        Mat4 result;
        for (int column = 0; column < 4; ++column) {
            for (int row = 0; row < 4; ++row) {
                float sum = 0.0f;
                for (int k = 0; k < 4; ++k) {
                    sum += at(k, row) * rhs.at(column, k);
                }
                result.at(column, row) = sum;
            }
        }
        return result;
    }

    /* Transforms a point, returning the full homogeneous result so callers can
     * reject anything behind the near plane by testing w. */
    void transform(double px, double py, double pz, float out[4]) const {
        const float x = static_cast<float>(px);
        const float y = static_cast<float>(py);
        const float z = static_cast<float>(pz);
        for (int row = 0; row < 4; ++row) {
            out[row] = at(0, row) * x + at(1, row) * y + at(2, row) * z + at(3, row);
        }
    }

    static Mat4 orthographic(float left, float right, float bottom, float top) {
        Mat4 result;
        result.at(0, 0) = 2.0f / (right - left);
        result.at(1, 1) = 2.0f / (top - bottom);
        result.at(2, 2) = -1.0f;
        result.at(3, 0) = -(right + left) / (right - left);
        result.at(3, 1) = -(top + bottom) / (top - bottom);
        return result;
    }

    static Mat4 translation(float x, float y, float z) {
        Mat4 result;
        result.at(3, 0) = x;
        result.at(3, 1) = y;
        result.at(3, 2) = z;
        return result;
    }

    /* Rotation from a quaternion, matching JOML's Matrix4f.rotation(Quaternionfc). */
    static Mat4 fromQuaternion(float x, float y, float z, float w) {
        Mat4 result;
        const float w2 = w * w;
        const float x2 = x * x;
        const float y2 = y * y;
        const float z2 = z * z;
        const float zw = z * w;
        const float xy = x * y;
        const float xz = x * z;
        const float yw = y * w;
        const float yz = y * z;
        const float xw = x * w;

        result.at(0, 0) = w2 + x2 - z2 - y2;
        result.at(0, 1) = xy + zw + zw + xy;
        result.at(0, 2) = xz - yw + xz - yw;
        result.at(1, 0) = -zw + xy - zw + xy;
        result.at(1, 1) = y2 - z2 + w2 - x2;
        result.at(1, 2) = yz + yz + xw + xw;
        result.at(2, 0) = yw + xz + xz + yw;
        result.at(2, 1) = yz + yz - xw - xw;
        result.at(2, 2) = z2 - y2 - x2 + w2;
        return result;
    }

    static Mat4 rotationDegrees(float degrees, float ax, float ay, float az) {
        const float half = degrees * kDegToRad * 0.5f;
        const float s = std::sin(half);
        return fromQuaternion(ax * s, ay * s, az * s, std::cos(half));
    }
};

struct Color {
    float r = 1.0f;
    float g = 1.0f;
    float b = 1.0f;
    float a = 1.0f;

    static constexpr Color rgba(std::uint32_t value) {
        return Color{static_cast<float>((value >> 24) & 0xFF) / 255.0f,
                     static_cast<float>((value >> 16) & 0xFF) / 255.0f,
                     static_cast<float>((value >> 8) & 0xFF) / 255.0f,
                     static_cast<float>(value & 0xFF) / 255.0f};
    }

    static constexpr Color rgb(std::uint32_t value, float alpha = 1.0f) {
        return Color{static_cast<float>((value >> 16) & 0xFF) / 255.0f,
                     static_cast<float>((value >> 8) & 0xFF) / 255.0f,
                     static_cast<float>(value & 0xFF) / 255.0f, alpha};
    }

    constexpr Color withAlpha(float alpha) const { return Color{r, g, b, a * alpha}; }

    std::uint32_t packed() const {
        const auto toByte = [](float v) {
            return static_cast<std::uint32_t>(clamp(v, 0.0f, 1.0f) * 255.0f + 0.5f);
        };
        return (toByte(r) << 24) | (toByte(g) << 16) | (toByte(b) << 8) | toByte(a);
    }

    static Color mix(const Color& from, const Color& to, float t) {
        return Color{lerp(from.r, to.r, t), lerp(from.g, to.g, t), lerp(from.b, to.b, t),
                     lerp(from.a, to.a, t)};
    }

    static Color hsv(float hue, float saturation, float value, float alpha = 1.0f) {
        hue = hue - std::floor(hue);
        const float h = hue * 6.0f;
        const int sector = static_cast<int>(h);
        const float f = h - static_cast<float>(sector);
        const float p = value * (1.0f - saturation);
        const float q = value * (1.0f - saturation * f);
        const float t = value * (1.0f - saturation * (1.0f - f));
        switch (sector % 6) {
            case 0: return {value, t, p, alpha};
            case 1: return {q, value, p, alpha};
            case 2: return {p, value, t, alpha};
            case 3: return {p, q, value, alpha};
            case 4: return {t, p, value, alpha};
            default: return {value, p, q, alpha};
        }
    }
};

struct Rect {
    float x = 0.0f;
    float y = 0.0f;
    float width = 0.0f;
    float height = 0.0f;

    constexpr float right() const { return x + width; }
    constexpr float bottom() const { return y + height; }
    constexpr float centerX() const { return x + width * 0.5f; }
    constexpr float centerY() const { return y + height * 0.5f; }

    constexpr bool contains(float px, float py) const {
        return px >= x && py >= y && px < x + width && py < y + height;
    }

    constexpr Rect inset(float amount) const {
        return Rect{x + amount, y + amount, width - amount * 2.0f, height - amount * 2.0f};
    }
};

namespace ease {

inline float outCubic(float t) {
    const float inverted = 1.0f - t;
    return 1.0f - inverted * inverted * inverted;
}

inline float outQuint(float t) {
    const float inverted = 1.0f - t;
    return 1.0f - inverted * inverted * inverted * inverted * inverted;
}

inline float inOutQuint(float t) {
    if (t < 0.5f) {
        return 16.0f * t * t * t * t * t;
    }
    const float inverted = -2.0f * t + 2.0f;
    return 1.0f - inverted * inverted * inverted * inverted * inverted / 2.0f;
}

inline float outExpo(float t) {
    return t >= 1.0f ? 1.0f : 1.0f - std::pow(2.0f, -10.0f * t);
}

inline float outBack(float t, float overshoot = 1.70158f) {
    const float c = overshoot + 1.0f;
    const float inverted = t - 1.0f;
    return 1.0f + c * inverted * inverted * inverted + overshoot * inverted * inverted;
}

/* Under-damped spring, normalised to leave 0 and settle at 1. It overshoots once
 * by roughly eight percent and rings down; outBack overshoots too, but as a
 * polynomial fudge rather than as a decaying oscillation, so it reads as a curve
 * with a bump where this reads as something with mass arriving. */
inline float springIn(float t) {
    if (t <= 0.0f) return 0.0f;
    if (t >= 1.0f) return 1.0f;
    const float frequency = 9.4f;
    const float damping = 0.62f;
    const float ringing = frequency * std::sqrt(1.0f - damping * damping);
    const float decay = std::exp(-damping * frequency * t);
    return 1.0f - decay * (std::cos(ringing * t) +
                           (damping * frequency / ringing) * std::sin(ringing * t));
}

/* Smooth at both ends with a long tail, for morphing one shape into another.
 *
 * Built on smootherstep, which is C2-continuous: a shape driven by this leaves
 * rest and arrives at rest with zero velocity AND zero acceleration, so two of
 * these chained back to back have no detectable seam between them. inOutQuint
 * is C1 only, which shows up as a faint tick at the handover. The bias then
 * moves most of the travel early, so the last stretch settles rather than
 * stops. */
inline float morph(float t) {
    t = clamp(t, 0.0f, 1.0f);
    const float smoother = t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
    return 1.0f - std::pow(1.0f - smoother, 1.30f);
}

/* Progress through [from, to] of a shared clock, clamped. Phase windows off one
 * wall clock are what let animations overlap on purpose instead of being chained
 * "start this when that finishes". */
inline float window(float clock, float from, float to) {
    return clamp((clock - from) / std::max(0.0001f, to - from), 0.0f, 1.0f);
}

}  // namespace ease

}  // namespace cold
