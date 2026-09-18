# Porting Raven-b modules onto a native 1.21.11 payload

Raven-b is a 1.8.9 Forge client. Every one of its modules is a `@SubscribeEvent`
over a Forge event plus a mixin accessor onto a 1.8 internal. cold.wtf is a DLL
with a JNI bridge and no bytecode at all. So "port for port" means porting the
*behaviour*, and the interesting part is the handful of places where the 1.8
mechanism has no equivalent and something else has to carry the same meaning.

Written down because the same four questions came up for every module, and the
answers are reusable.

## The four things that never survive the trip

**1. `PrePlayerInputEvent` / `movementInput` writes.** Raven steers the player by
writing `movementInput.moveForward = 1` inside a per-tick event. There is no
such event and `Input` is not something a native payload should be poking. The
replacement is `Game::setBinding` - press the real `KeyBinding`. The difference
that matters: a `movementInput` write lasts exactly one tick and a binding is
sticky, so anything that presses one **must** release it, and must know whether
the player was already holding it. `BridgeAssist`, `Displace` and `JumpReset` all
carry a `wasTheirs_`-shaped flag for exactly this. Taking a key off a player who
is leaning on it is worse than never pressing it.

Reading that state back needed a new mapping: `KeyBinding.pressed` (`Z`).
`Game::bindingPressed` is the counterpart to `setBinding`, and it is the right
question to ask rather than polling GLFW - the player may have rebound the key,
and a module may be holding it for them.

**2. Packet hooks.** Raven listens to `SendPacketEvent` for `C07 PlayerDigging`
and `C08 BlockPlacement`. The payload sends no packets and reads none. Every use
of those hooks reduces to a state edge that can be observed from the frame
instead:

- "the player started digging a bed" -> the crosshair is on a bed and attack is
  held (`CuteVisuals::watchBed`).
- "the bed broke" -> the block that was a bed is not one any more. This is
  strictly better than the original, which had to special-case creative mode
  because creative only sends `START_DESTROY_BLOCK`.

**3. Mixins that change a returned number.** `FastMine` scales what
`getBlockBreakingSpeed` returns. There is no way to change a return value from
here. The way out was noticing what the number is *for*:
`updateBlockBreakingProgress` adds one tick of progress per call and the game
calls it once a tick, so **the multiplier is a call count**. Calling it n-1 extra
times from the tick edge is exactly an n-times multiplier, with the fractional
part carried between ticks so 1.5x is an extra call every other tick rather than
none at all. The delay half is a plain field write once
`ClientPlayerInteractionManager.blockBreakingCooldown` is mapped.

**4. `getStrVsBlock` and the other "ask Minecraft" helpers.** `AutoTool` asks
the game which tool is fastest. Reconstructing that from the registry path is
not as fragile as it sounds, because only two things decide it: which family the
block answers to, and the material's mining speed. Both are in the id. The one
ordering trap: gold is the *fastest* material (12) and netherite is 9, so a
table sorted by "tier" gives the wrong answer to the question the setting
actually asks.

The block table has to be ordered narrow-to-broad. `sandstone` contains `sand`,
a spruce *fence* is wood and an iron *door* is not, and half the registry has
`block` in it somewhere.

## Rendering: the script GL is not portable, and does not need to be

Both ported scripts (`esp`, `trajectories`) and `CuteVisuals` draw with 1.8
immediate-mode GL inside `RenderWorldLastEvent`: `glTranslate` to the particle,
`glRotate` to face the camera, then a `GL_LINE_LOOP`. The renderer here is a
single batched screen-space SDF pass ([[sdf-ui-pipeline]]) with no world-space
mode at all.

Two patterns cover everything:

- **Wireframes and arcs** (3D ESP box, tracers, trajectory line, rainbow):
  project each world point with the frame's own view-projection and draw a
  screen-space line between consecutive results. Drop the whole shape if any
  point fails to project - one corner behind the near plane makes every line
  touching it wrap across the screen.
- **Billboards** (hearts, dots, burst shapes): a camera-facing billboard is by
  definition flat to the screen, so it does not need per-vertex projection at
  all. Project the centre, then project *centre + one unit of camera-right*, and
  the distance between the two results is the pixels-per-block scale at that
  depth. Build the shape in screen space around the centre. Two hundred
  particles cost two hundred projections instead of six thousand.

## The 0.16 that put a hook on every arc

`trajectories` spawns its simulated projectile at
`posX - cos(yaw) * 0.16`, which is where 1.8 spawned throwables - beside the
hand. In 1.21 every `ProjectileEntity` constructor uses `getX(), getEyeY() - 0.1,
getZ()`, with no sideways offset.

Kept as-is, the symptom is not subtle and is easy to misread as a projection
bug: the far end of the arc is correct and the near end swings out to one side.
The reason is that the script clips the first half block of the path, and at half
a block out a 0.16 sideways offset is `atan(0.16 / 0.5)` - nearly eighteen
degrees, which at 1280 pixels across a 70 degree field is a third of the screen.
The offset's angular weight shrinks with distance, so the arc converges back onto
the true path and only the near end looks wrong.

## The block cache was never being reset

Not a porting problem, but the thing that would have broken `Clutch` and
`BridgeAssist` first. `game::resetBlockCache()` existed, was declared, was
defined, and was called from nowhere - so `blockPassable` answered from whenever
that cell was first looked at, for the life of the session. Fine for a single
raycast search, wrong the moment anything places or breaks a block: the cell just
filled still reads as air. It is now reset on the tick edge in `Client`, and
`blockPassable` is exposed so the fall predictions, edge tests and void scans all
share the same per-tick cache instead of each paying its own JNI round trip.

## The two lines of a fall prediction that decide whether Clutch works

`Clutch` was ported with its fall simulation intact and two details of it
dropped, and either one on its own is enough to stop it placing a single block.

**The future position is capped at two blocks of drop.** Raven runs the
prediction twenty ticks but breaks the moment `posY < startY - 2`. That number
is not a performance guard - the future position exists only to lean the cell
search towards the side of the box the fall is carrying the player onto. Run the
full twenty ticks and the point ends up a hundred blocks down, at which range
every cell in the search box is the same distance from it *except* in depth, so
the whole box sorts by y and the wall actually within arm's reach sorts last.
Combined with any cap on how many cells get a rotation search, the module spends
its entire budget on the four cells directly below and finds nothing.

**The step has to be sub-stepped.** Integrating a tick of free fall as one
`pos += velocity` tunnels straight through the floor once the fall passes about
a block a tick, which is four ticks in. `onGround` then never becomes true and
the drop grows forever, so `willFallFar` says yes to everything and
`willFallSoon` says no to everything. Slicing the move into steps of at most
0.45 blocks and resolving Y, then X, then Z per slice is the same answer as
vanilla's swept AABB against full cubes, which is all the payload can see
anyway.

Four smaller ones from the same module, all worth copying rather than
rediscovering:

- The rotation already going out belongs in the candidate list at zero cost.
  Looking at a face that works means clicking it this tick without moving the
  mouse at all, and every "why did it turn away from a perfectly good block" is
  this line missing.
- The confirm raycast must place at whatever the *sent* rotation hits, not
  demand that it hit the cell the search picked. Raven can demand that because
  it smooths and confirms in the same event; here the engine steps the aim on
  the tick edge before modules run, so the sent rotation is always one step
  behind an aim that is re-chosen every tick. Requiring equality means the two
  never agree and nothing is ever placed. Validating the hit on its own terms -
  face is not the underside, the cell it would fill is empty, it is below the
  feet - is the same guarantee without the deadlock.
- The cell a block would fill has to be checked against the **player's own
  hitbox**, not only for air. Vanilla asks this through `World.canPlace`, which
  runs the candidate shape against every entity in it. Skip it and the nearest
  cell to a standing player is always the one under their feet; its top face is
  always reachable; and the block it would place always goes in the cell the
  player is standing in. The search settles on that placement, it fails every
  tick, and the wall two blocks away never gets a single ray. Symptom is
  "it works once and then does nothing" - the one time it works is the tick the
  geometry happened to pick a side face instead.
- Raven's first gate is `canPlaceThrough(below)`: **there has to be nothing
  under the feet.** That is the line between a clutch and a scaffold. Without it
  the module finds the side of the block the player is standing on and bridges
  outwards, placing every tick from a stationary player.

Raven also holds `attack` and `use` down for the player for as long as an aim is
live, every tick rather than once, and hands them back from the real mouse state
afterwards. Without it a player leaning on right mouse places at the raw
crosshair a frame before the confirmed rotation lands.

## Related

- [[grim-rotation-ordering]] - why every placement here waits for the rotation
  the server already holds.
- [[block-placement-hit-vector]] - what `Clutch` confirms before it clicks.
- [[sdf-ui-pipeline]] - the renderer these modules draw into.
- [[lua-scripting]] - the other way a Raven script could have been carried over,
  and why these were not.
