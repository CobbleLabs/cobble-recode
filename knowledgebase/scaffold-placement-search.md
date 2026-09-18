# Scaffold: search behind the player, not under them

A scaffold that only considers the cell directly under the feet does not bridge.
By the time the placement reaches the server the player has already left that
cell, so the block either lands under them and does nothing useful or the cell
is solid already and the module reports "nothing to build against".

The symptom reported from actual play was "it doesn't place blocks behind me".

## The anchor

Bias the search **against the direction of travel**:

```cpp
Vec3 back{0, 0, 0};
const double speed = state.player.velocity.horizontalLength();
if (speed > 0.001) {
    back = {-velocity.x / speed, 0, -velocity.z / speed};
} else {
    const double yaw = state.player.yaw * kDegToRad;
    back = {std::sin(yaw), 0, -std::cos(yaw)};      // behind the facing
}
const Vec3 anchor = feet + back * lookBack;          // lookBack defaults to 0.65
```

The standing-still fallback matters: without it a stationary player has no
direction and the module goes back to building straight down.

## The search

Take a square of cells of radius `Expand` around the anchor at foot level minus
one (or foot level while towering), keep the replaceable ones, sort by squared
horizontal distance from the anchor, and take the first that has a clickable
neighbour. LiquidBounce does the same thing; this is just the small version.

## Choosing the face

Do not take the first solid neighbour. Score them:

```cpp
const Vec3 toEye = eyePos - hit;
const double facing = toEye.dot(normal) / distance;
if (facing <= 0.05) continue;              // turned away, cannot be clicked
const double score = distance - facing * 2.0;
```

A face pointing away from the eye is not reachable by any raycast, and among the
ones that are, the most square-on is the one the server's own raycast is most
likely to land on. See [[block-placement-hit-vector]] for why the hit point sits
exactly on the face and never past it.

## Telly and Watchdog

Both come from Rise 6.9.5 `Scaffold`:

- **Telly** (`getTellyRotation`) is one line: keep `wrapAngleTo180(player.yaw)`,
  pitch `85 + random*0.1`. The yaw is never touched, so it always agrees with
  the direction of travel because it *is* the direction of travel.
- **Watchdog** (`applyNewWatchdogRotations`) aims at the block centre pushed
  half a block along the axis across the player's facing, not at the face
  centre:

  ```cpp
  forward = {-sin(yaw), 0, cos(yaw)};
  side    = {forward.z, 0, -forward.x};
  point   = blockCentre + side * 0.49999999 + jitter(y, +-0.025);
  ```

  then subtracts a movement-dependent yaw offset: -10 while jumping, -30 below
  0.21 speed or airborne, -45 below 0.3, nothing while strafing (a strafe
  already supplies the offset). That lag is what makes the aim read as a hand
  fighting its own momentum rather than a solver sitting on the target.

Verified by the suite: a flat platform with exactly one hole, one cell behind
the facing, nothing moving. Filling it can only mean the search reached behind
the player, because the cell underfoot is already solid.

Related: [[block-placement-hit-vector]],
[[silent-rotations-without-bytecode]], [[grim-rotation-ordering]]
