# Attacks are validated against last tick's rotation

This is the single most important fact for making combat modules survive Grim,
and it is easy to get backwards.

## The vanilla packet order

Inside one client tick, `MinecraftClient.tick()` runs:

1. `handleInputEvents()` → `doAttack()` → swing packet, then
   `interactionManager.attackEntity(...)` → `PlayerInteractEntityC2SPacket`
2. `player.tick()` → `sendMovementPackets()` → the position/rotation packet

So the attack for tick *N* leaves **before** the rotation for tick *N*. The
server therefore validates that attack against the rotation it received during
tick *N-1*.

Verified from `MinecraftClient.doAttack` bytecode — note the order, swing first:

```
140: invokevirtual ClientPlayerEntity.swingHand:(Hand)V
...  switch on crosshairTarget type
...  invokevirtual ClientPlayerInteractionManager.attackEntity:(PlayerEntity;Entity)V
```

`attackEntity` itself does **not** swing, so a module that only calls
`attackEntity` sends an interact with no preceding swing — a shape no vanilla
client ever produces.

## What that means for a module

The naive loop is wrong:

```
rotate towards target      // sets yaw for the NEXT movement packet
attack                     // validated against the PREVIOUS rotation
```

The correct order is the opposite:

```
if (current rotation already covers the hitbox) attack     // uses the rotation
rotate towards target                                      // the server holds
```

cold.wtf runs its module tick inside the `glfwSwapBuffers` hook, which is after
the client tick for that frame. At that moment `player.getYaw()` is exactly the
rotation the server has already received, so measuring the aim error there and
attacking only when it is inside tolerance makes aim and hit agree by
construction. The new rotation is written afterwards and rides the next tick's
movement packet.

`KillAura::onTick` and `HitFlick::onTick` are both written in that order, and
`Scaffold` uses the same rule for block placement — Grim rejects a placement
whose rotation does not intersect the clicked face.

## The one thing that still flagged, and why

Everything above is about *which* rotation an attack is validated against. There
is a second, separate ordering question — *where in the tick the interaction
packet lands* — and it is the only thing that ever flagged in a full run against
a real GrimAC server (2.3.74, `Post (interact entity)`).

Vanilla sends its interactions from `handleInputEvents` and the movement packet
from the player tick immediately after, so on the wire an interaction always
sits just **in front of** a movement packet and never just behind one. The
client acts from the frame hook instead, aiming for the frame the next tick is
about to land on. At any playable framerate that is a good approximation: at
three thousand frames a second, two hundred attacks produced no flags at all.

It falls apart at low framerates. The estimate is
`tickProgress + frameTime/50ms >= 1`, and once a frame is long enough that test
is true on the frame the tick has only just happened in — so the interaction
goes out a millisecond *after* the movement packet rather than milliseconds
before the next one. Measured at thirty frames a second: two or three `Post`
flags per thirty attacks.

The fix is **not** to skip the module tick. The rotation engine wants a request
on every tick — one missed tick and `active_` resets, the aim restarts from the
camera and never converges — so gating the tick turned a flagging aura into one
that could not hit at all. Gate the *packet* instead:
`Game::setInteractionsAllowed`, set once a frame by the client, and checked by
`attackEntity`, `placeBlock` and `attackBlock`. Modules still tick, still aim,
and simply do not get to send an interaction on a frame that is too close
behind a movement packet.

"Too close" is measured from the camera's own tick progress, not from the wall
clock: `tickProgress * 50 ms` is how far into the tick window the frame that
just rendered was, and it is right at any framerate. The wall clock is only the
fallback for a frame with no camera to ask, and it is the weaker of the two —
it measures from the frame that *observed* the tick, which at a low framerate is
already a frame late. The threshold is ten milliseconds, a fifth of a tick.

At sixty frames a second and above the guard never refuses anything, because the
frame the modules act on is by then always late in the tick window. Measured
against GrimAC 2.3.74 at thirty frames a second, the framerate that was failing
before: **twelve armour stands killed, zero flags**, against two or three flags
per run before. An earlier version of the guard tested the clock and the tick
progress with an OR and still let one flag through in two hundred hits; the
single well-founded measure is what closed it.

Anything routed through the game's own binding — `Game::queueClick`, which the
crystal macros use — is exempt and always will be. The game sends those itself,
at its own moment, in the vanilla order.

## The crosshair is the other half of the binding rule

`queueClick` hands the placement to the game, and the game acts on
`MinecraftClient.crosshairTarget` - a field it rebuilds **once per rendered
frame, from the camera**. The note above says that is fine for a module that
does not move the aim. It is not fine for one that does, and it is never fine
with a silent rotation:

| | what the crosshair holds at `handleInputEvents` |
| --- | --- |
| visible aim | the rotation of the last rendered frame - one frame stale |
| silent aim | the player's own rotation, because the camera never moved |

The frame order is the reason. A silent rotation is written in the
`glfwSwapBuffers` detour at the end of frame *F-1*, the tick runs at the top of
frame *F*, and the camera is given back in the `glfwGetTime` probe before frame
*F* renders. `updateTargetedEntity` only ever runs in that render, so it never
once sees the borrowed camera. `Clutch` with Silent on aimed perfectly, sent the
rotation, confirmed the face - and placed wherever the player was looking.

The fix is one field write: `Game::setCrosshairTarget` builds the same
`BlockHitResult` the module already confirmed and stores it, on the same frame
as the `queueClick`. There is no render between that frame and
`handleInputEvents`, so the value survives, `doItemUse` runs the whole vanilla
path against it, and the game replaces it with its own on the next render.

So the rule has a second clause:

> A module must not send an interaction. It queues a press, **and it writes the
> crosshair the press is meant to act on.**

Also worth knowing: vanilla `doItemUse` sets `itemUseCooldown = 4` itself. That
only gates the *held* button branch, and a module that queues presses has
usually already cleared the binding's pressed state for the player - so
`setItemUseCooldown(0)` beside the queue is safe, and is the difference between
a block a tick and a block every fifth tick.

## The guard is a mitigation. The binding is the fix.

The frame-timing guard above reduces Post flags. It does not remove them, and
the difference only shows up under load — which is how three separate modules
shipped with the same defect and only one of them was caught.

Measured against GrimAC 2.3.74, each module holding its own button:

| module | what it sent, and from where | Post alerts |
| --- | --- | --- |
| TriggerBot | `attackEntity` from the frame hook | 43 in ~90 s |
| Clutch | `placeBlock` from the frame hook | ~770 in ~2 min |
| TriggerBot | one press queued onto the attack binding | **0** |
| Clutch | one press queued onto the use binding | **0** |

Clutch is the one that makes the point. It aims correctly, it confirms the
rotation the server already holds lands on the face before it clicks, and it
obeys the interaction guard — every rule in this note — and it still flagged on
essentially every placement, because none of that changes **where in the tick**
the packet leaves. Held down beside a wall it placed continuously, and Grim
counted every one.

So the rule for this codebase is not "be careful about timing". It is:

> **A module must not send an interaction. It queues a press and lets the game
> send it.**

`Game::queueClick` adds one to `KeyBinding.timesPressed`; the game drains it in
`handleInputEvents` at the top of the next client tick and runs the whole of
vanilla `doAttack` or `doItemUse` from there — the pick, the packet, the swing,
the cooldown. Nothing has to imitate any of it, and the packet leaves from the
place Minecraft leaves from, in front of that tick's movement packet.

The one thing to check before converting a module: **the game acts on its own
crosshair, not on the module's target.** That is fine for anything that picks
along the rotation the server already holds without moving the aim (TriggerBot,
AutoMace) and fine for anything that has already confirmed its aim lands on the
face it wants (Clutch). It would be wrong for a module that wants to hit
something the player is not looking at, which is what the direct call is still
there for - every one of these keeps a `Click` setting with a `Direct` mode, and
the setting says what it costs.

Park the cooldown when converting a module whose bind is also the vanilla
binding, or the queued press and the held button both go out: `setItemUseCooldown(2)`
before `queueClick(use)`, which is what AutoCrystal already did and what Clutch
now does.

## Also worth matching

- Set `MinecraftClient.attackCooldown = 10` after a module swing when
  `hasLimitedAttackSpeed()` is true. Vanilla does this; skipping it lets a held
  mouse button double up on the module's clicks.
- Respect `getAttackCooldownProgress(0)` before swinging, or every hit lands at
  reduced damage and the click pattern stops looking like a person.
- Reach is measured to the **hitbox**, not the entity centre. `hitboxDistance`
  clamps the eye position into the box, which is the same quantity the server
  computes. 3.0 blocks is the survival attack limit.

Related: [[mouse-grid-rotations]], [[reading-minecraft-ground-truth]]

Doing this while the camera stays put is
[[silent-rotations-without-bytecode]].
