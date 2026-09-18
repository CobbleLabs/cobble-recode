#include "game/Raycast.hpp"

#include <algorithm>
#include <cmath>

namespace cold::game {
namespace {

int floorToInt(double value) { return static_cast<int>(std::floor(value)); }

struct CacheEntry {
    int x = 0;
    int y = 0;
    int z = 0;
    bool valid = false;
    bool passable = false;
};

constexpr int kCacheSize = 1024;
CacheEntry g_cache[kCacheSize];

bool passable(JNIEnv* env, jobject world, int x, int y, int z) {
    const auto mix = static_cast<unsigned>(x * 73856093 ^ y * 19349663 ^ z * 83492791);
    const int slot = static_cast<int>(mix % kCacheSize);
    CacheEntry& entry = g_cache[slot];
    if (entry.valid && entry.x == x && entry.y == y && entry.z == z) {
        return entry.passable;
    }
    entry.x = x;
    entry.y = y;
    entry.z = z;
    entry.valid = true;
    entry.passable = Game::instance().isBlockReplaceable(env, world, x, y, z);
    return entry.passable;
}

}  // namespace

bool blockPassable(JNIEnv* env, jobject world, int x, int y, int z) {
    return world != nullptr && passable(env, world, x, y, z);
}

void resetBlockCache() {
    for (CacheEntry& entry : g_cache) {
        entry.valid = false;
    }
}

double rayBox(const Vec3& origin, const Vec3& direction, const Vec3& minimum, const Vec3& maximum,
              double reach) {
    if (origin.x >= minimum.x && origin.x <= maximum.x && origin.y >= minimum.y &&
        origin.y <= maximum.y && origin.z >= minimum.z && origin.z <= maximum.z) {
        return 0.0;
    }

    double near = 0.0;
    double far = reach;
    const double from[3] = {origin.x, origin.y, origin.z};
    const double along[3] = {direction.x, direction.y, direction.z};
    const double low[3] = {minimum.x, minimum.y, minimum.z};
    const double high[3] = {maximum.x, maximum.y, maximum.z};

    for (int axis = 0; axis < 3; ++axis) {
        if (std::fabs(along[axis]) < 1e-9) {
            if (from[axis] < low[axis] || from[axis] > high[axis]) {
                return -1.0;
            }
            continue;
        }
        const double inverse = 1.0 / along[axis];
        double entry = (low[axis] - from[axis]) * inverse;
        double exit = (high[axis] - from[axis]) * inverse;
        if (entry > exit) {
            std::swap(entry, exit);
        }
        near = std::max(near, entry);
        far = std::min(far, exit);
        if (near > far) {
            return -1.0;
        }
    }
    return near;
}

Vec3 lookVector(float yaw, float pitch) {
    const double yawRadians = static_cast<double>(yaw) * kDegToRad;
    const double pitchRadians = static_cast<double>(pitch) * kDegToRad;
    const double horizontal = std::cos(pitchRadians);
    return Vec3{-std::sin(yawRadians) * horizontal, -std::sin(pitchRadians),
                std::cos(yawRadians) * horizontal};
}

BlockHit raycastBlocks(JNIEnv* env, jobject world, const Vec3& origin, const Vec3& direction,
                       double reach) {
    BlockHit result;
    if (world == nullptr || reach <= 0.0) {
        return result;
    }

    /* Amanatides and Woo: step whole cells and keep, per axis, the distance at
     * which the ray next crosses a boundary. The axis that crosses first is the
     * axis stepped, and the face entered is that axis pointing back the way the
     * ray came. Stepping in fixed increments instead would either miss thin
     * grazing hits or cost several times the lookups. */
    int cellX = floorToInt(origin.x);
    int cellY = floorToInt(origin.y);
    int cellZ = floorToInt(origin.z);

    const int stepX = direction.x > 0.0 ? 1 : (direction.x < 0.0 ? -1 : 0);
    const int stepY = direction.y > 0.0 ? 1 : (direction.y < 0.0 ? -1 : 0);
    const int stepZ = direction.z > 0.0 ? 1 : (direction.z < 0.0 ? -1 : 0);

    constexpr double kFar = 1e30;
    const double deltaX = stepX != 0 ? std::fabs(1.0 / direction.x) : kFar;
    const double deltaY = stepY != 0 ? std::fabs(1.0 / direction.y) : kFar;
    const double deltaZ = stepZ != 0 ? std::fabs(1.0 / direction.z) : kFar;

    const auto boundary = [](double position, int cell, int step) {
        return step > 0 ? static_cast<double>(cell + 1) - position
                        : position - static_cast<double>(cell);
    };
    double nextX = stepX != 0 ? boundary(origin.x, cellX, stepX) * deltaX : kFar;
    double nextY = stepY != 0 ? boundary(origin.y, cellY, stepY) * deltaY : kFar;
    double nextZ = stepZ != 0 ? boundary(origin.z, cellZ, stepZ) * deltaZ : kFar;

    if (!passable(env, world, cellX, cellY, cellZ)) {
        /* Standing inside a block: nothing sensible to click, and Minecraft's
         * own raycast starts outside it too. */
        return result;
    }

    double travelled = 0.0;
    BlockFace entered = BlockFace::Up;
    for (int guard = 0; guard < 96; ++guard) {
        if (nextX <= nextY && nextX <= nextZ) {
            travelled = nextX;
            nextX += deltaX;
            cellX += stepX;
            entered = stepX > 0 ? BlockFace::West : BlockFace::East;
        } else if (nextY <= nextZ) {
            travelled = nextY;
            nextY += deltaY;
            cellY += stepY;
            entered = stepY > 0 ? BlockFace::Down : BlockFace::Up;
        } else {
            travelled = nextZ;
            nextZ += deltaZ;
            cellZ += stepZ;
            entered = stepZ > 0 ? BlockFace::North : BlockFace::South;
        }
        if (travelled > reach) {
            return result;
        }
        if (passable(env, world, cellX, cellY, cellZ)) {
            continue;
        }

        result.hit = true;
        result.x = cellX;
        result.y = cellY;
        result.z = cellZ;
        result.face = entered;
        result.distance = travelled;
        result.point = Vec3{origin.x + direction.x * travelled, origin.y + direction.y * travelled,
                            origin.z + direction.z * travelled};
        return result;
    }
    return result;
}

}  // namespace cold::game
