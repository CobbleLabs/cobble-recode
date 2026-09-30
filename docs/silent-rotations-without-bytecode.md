# Silent rotations without bytecode injection

A native client cannot mixin `ClientPlayerEntity.sendMovementPackets`, so the
usual trick of writing a separate server rotation into the packet is not
available. What is available is the ordering of Minecraft's game loop.

## The window

`MinecraftClient.render` runs, per frame:

1. `runTick()` -> the client tick, which sends the movement packet carrying
   `player.getYaw()/getPitch()`.
2. `Mouse.tick()` -> reads the cursor, and calls `glfwGetTime` for its own
   smoothing timer.
3. `GameRenderer.render` -> the world is drawn from `player.getYaw()/getPitch()`.

So there is a window between the packet going out and anything being drawn. Put
the aim in at (0), take it back at (2), and the server sees the rotation while
the camera never does.

## Hooking it

- **Write** from the `glfwSwapBuffers` detour, on the last frame before the
  next tick. Any earlier and a frame gets drawn with the borrowed camera; any
  later and the packet has already gone without it.
- **Take it back** from a `glfwGetTime` detour.

### Predicting the tick: ask the game, do not guess

The first version estimated the tick from the wall clock:

```cpp
now - lastTickSeconds >= 0.05 - smoothedFrameSeconds * 2.5   // WRONG above ~500 fps
```

`lastTickSeconds` is stamped on the frame where the age change was *observed*,
which is already after the tick, so the estimate of "when does the next one
land" carries a whole frame of error. At 60 fps that is lost in the margin. At
2000 fps in a focused dev client it is the entire window, and delivery sat at
**exactly 140 of 280** - every other rotation armed a hair too late.

`Camera.getLastTickProgress()` is how far through the current tick the frame
that just rendered was, so the answer is exact:

```cpp
const float frameFraction = smoothedFrameSeconds / 0.05f;
const bool tickImminent = state.tickProgress + frameFraction >= 1.0f;
```

Less than one frame of the tick left means the tick lands during the next frame.
That took delivery to **179 of 179** at the same framerate, and it is
framerate-independent by construction.

Do **not** "fix" a miss by re-arming on the miss path. The re-arm fires in the
same `updateSilent` call that just restored the camera, so it applies again
immediately and the camera ends up holding the aim on almost every frame - which
is the one thing the whole mechanism exists to avoid. A miss means the rotation
was not delivered; drop it and let the next tick ask again.

## The trap: do not count probe calls

The first attempt assumed a fixed number of `glfwGetTime` calls between arming
and `Mouse.tick`. It delivered **0 of 234**. Minecraft's frame limiter
busy-waits on `glfwGetTime`, so 26-164 calls can land in between, and the count
varies every frame.

The reliable marker is `player.age`. It advances exactly when the client tick
ran, which is exactly when the packet went out. So:

```cpp
const int age = Game::instance().playerAge(env);
if (age < 0 || age == ageAtApply_) return;   // tick has not happened yet
Game::instance().setLocalRotation(env, restoreYaw_, restorePitch_);
```

That took delivery to **381 of 381**. The probe must stay cheap - it runs
hundreds of times a frame - so gate it on a plain bool before touching JNI.

## The trap: the aim must live outside the camera

The engine originally resynced its integrator from the live player every tick:

```cpp
currentYaw_ = state.player.yaw;   // WRONG for silent
```

With a visible rotation that is right, because the camera *is* the aim. With a
silent one the camera has already been restored by the probe, so every tick
restarted from the player's own heading and the aim could never travel further
than one tick's allowance from it. Symptom: Scaffold logged
`waiting: aim 90.0/80.9, server holds 12.0/80.9` forever, with the yaw pinned at
exactly `camera + turnSpeed` - it converged in pitch (small error) and never in
yaw (180 degrees away).

The fix is to keep `currentYaw_` across ticks and **compose** the player's mouse
movement into it instead of overwriting:

```cpp
if (!active_) {
    currentYaw_ = cameraYaw;                              // seed
} else {
    currentYaw_ += wrapDegrees(cameraYaw - lastCameraYaw_);  // compose
}
```

This is what Rise's `RotationComponent` does with its `fk` server rotation and
what `MovementFix` compensates for. See [[grim-rotation-ordering]].

## What modules must check

`sentYaw()/sentPitch()` - what the server holds - never the camera. And
`sentYaw_` is only written when `updateSilent` actually applies the rotation,
never optimistically when the aim is computed: a module asking whether the
server can see the block face must not be told about a rotation still in flight.
Grim validates an action against the *previous* tick's rotation anyway, so
placing one tick after the aim lands is not a delay, it is the correct order.

## Verifying it

`legacy/tools/smoke.ps1` parks the camera at a fixed yaw inside the aura's FOV cone,
enables KillAura with Silent set, and asserts the camera has not moved by even
0.001 degrees while `sentyaw` swung onto the target, that the swing is a whole
number of mouse counts, and that every armed rotation reached a tick
(`delivered == armed`).

Related: [[grim-rotation-ordering]], [[mouse-grid-rotations]],
[[frame-hook-choice]], [[block-placement-hit-vector]]
