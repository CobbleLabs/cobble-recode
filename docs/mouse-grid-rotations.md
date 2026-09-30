# Rotations must land on the sensitivity grid

## Why a float yaw is a giveaway

Minecraft derives rotation from whole mouse counts:

```java
// Mouse.updateMouse
double d = options.getMouseSensitivity().getValue() * 0.6 + 0.2;
double f = d * d * d * 8.0;
player.changeLookDirection(cursorDeltaX * f, cursorDeltaY * f);

// Entity.changeLookDirection
setYaw(getYaw() + (float)cursorDeltaX * 0.15F);
```

`cursorDeltaX` is an integer number of raw mouse counts, so **every** rotation a
legitimate client can produce is an integer multiple of

```
step = (sensitivity * 0.6 + 0.2)^3 * 8 * 0.15
```

Grim's aim processor recovers that step as the greatest common divisor of the
rotation deltas it observes. A client that writes `yaw = atan2(...)` directly
produces deltas with no common divisor, and the GCD collapses toward zero —
which is separable from a mouse with a handful of samples.

## What cold.wtf does

`RotationEngine` plans in degrees and then quantises before touching the player:

```cpp
float counts = degrees / step_;
long rounded = std::lround(counts);
if (rounded == 0 && std::fabs(degrees) > step_ * 0.5f)
    rounded = degrees > 0 ? 1 : -1;      // never stall short of the target
return rounded * step_;
```

The engine re-reads the live sensitivity every tick, because the player can
change it mid-session and the grid moves with it.

Two consequences worth keeping in mind:

- **Jitter must be whole counts.** Adding `random.range(-0.3f, 0.3f)` degrees
  destroys the property the quantisation exists to preserve. The `jitter`
  setting adds ±N counts instead.
- **Never write the aim from two places.** Anything that sets yaw outside the
  engine reintroduces off-grid values. Modules submit a `RotationTarget` with a
  priority; the engine resolves and applies exactly once per tick.

## Also enforced

- Pitch is clamped to [-90, 90] the way `changeLookDirection` does.
- The current rotation is resynchronised from the live player every tick, so
  mouse movement composes with module aim instead of fighting it.
- The engine runs on the **tick edge** (detected from `Entity.age` changing),
  not per frame. Otherwise turn speed would scale with frame rate and a 300 fps
  client would rotate five times faster than a 60 fps one.

Related: [[grim-rotation-ordering]]

Rise 6.9.5 `RotationUtil.applySensitivityPatch` computes exactly this, which is
the independent confirmation that the formula is right. See
[[silent-rotations-without-bytecode]] for keeping the grid while the camera
never moves.
