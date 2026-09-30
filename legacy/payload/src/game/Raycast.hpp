#pragma once

#include <jni.h>

#include "bridge/Minecraft.hpp"
#include "core/Math.hpp"

namespace cold::game {

struct BlockHit {
    bool hit = false;
    int x = 0;
    int y = 0;
    int z = 0;
    BlockFace face = BlockFace::Up;
    Vec3 point;
    double distance = 0.0;
};

/* Marches a ray through the block grid and reports the first block that stops
 * it, along with the face it entered through.
 *
 * This exists because a scaffold has to answer one question honestly: does the
 * rotation about to go out actually put the crosshair on the face being
 * clicked? A server reconstructs the placement from its own raycast, so a
 * rotation that misses is a placement that either does not happen or happens
 * somewhere else. Guessing an angle at the face centre is not the same thing as
 * checking it.
 *
 * Passability is Minecraft's replaceable test, which is true for air and for
 * plants, so grass never counts as a wall. */
BlockHit raycastBlocks(JNIEnv* env, jobject world, const Vec3& origin, const Vec3& direction,
                       double reach);

/* Minecraft's replaceable test, through the same per-tick cache the raycaster
 * uses. Anything walking a grid of cells - a fall prediction, an edge test, a
 * void scan - is asking the same question about the same handful of blocks over
 * and over, and each miss is a JNI round trip. */
bool blockPassable(JNIEnv* env, jobject world, int x, int y, int z);

/* Searching for a rotation means casting a few dozen rays that all start at the
 * same eye and overlap almost completely, so the same handful of cells get
 * looked up over and over. Reset this once per tick and the whole search costs
 * about as many JNI calls as a single ray. */
void resetBlockCache();

/* Distance along the ray to where it enters the box, or a negative number when
 * it misses. An origin inside the box counts as a hit at zero, which is how
 * Minecraft treats an entity you are standing in. */
double rayBox(const Vec3& origin, const Vec3& direction, const Vec3& minimum, const Vec3& maximum,
              double reach);

/* Unit vector for a yaw/pitch pair, in Minecraft's convention. */
Vec3 lookVector(float yaw, float pitch);

}  // namespace cold::game
