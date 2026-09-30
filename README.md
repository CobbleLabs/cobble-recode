# Cobble Recode

[cobble.vip](https://cobble.vip)

An injectable Minecraft utility for **Lunar Client 1.8.9**, written in C++ with a
JNI bridge into the running game. Two artefacts: a payload DLL that does
everything, and an injector that maps it into a live `javaw.exe`.

Nothing is added to Minecraft's world rendering. Every pixel the client draws —
panels, text, icons, shadows, the ESP — is a signed-distance field evaluated in
one batched shader pass over the finished frame.

The client was originally built for 1.21.11 Fabric and was ported to Lunar's
1.8.9 runtime: Lunar ships deobfuscated **MCP names**, renders through LWJGL 2
on a legacy compatibility GL context, and presents with `wglSwapBuffers` rather
than GLFW. See the *Lunar 1.8.9 port* section below and
`docs/lunar-1-8-9-port.md` for what that changed.

```
dist/
  cobble-injector.exe    the loader: process picker + LoadLibrary injection,
                         with the payload DLL and the example scripts baked in.
                         Send only this file - it extracts the rest beside
                         itself on first run (temp dir fallback)
  cobble.dll             extracted payload: modules, UI, JNI bridge, assets
  cobble.cfg             written on unload, read on the next injection
  cobble.scripts.cfg     which scripts are on, and what they stored
  cobble.log             per-session diagnostic log
  scripts/               Lua scripts, read at startup and reloadable in place
```

Everything the client owns lives in `%APPDATA%\cobble`; a previous cold.wtf
install's configs and scripts are picked up from `%APPDATA%\coldwtf` on the
first run of the new build.

## Build

Needs Windows x64, the MSVC C++ toolchain, CMake 3.21+ and a JDK 17+ with
`jni.h`. Configuring fetches the public jars into `infra/lib`; the MCP-named
`minecraft-1.8.9-mcp.jar` has to be copied there by hand.

```bash
cmake -S . -B build/native -A x64 -DCOLD_JAVA_HOME="<path to JDK>"
cmake --build build/native --config Release
```

`build.ps1` wraps both steps and finds or installs CMake.

```
src/cobble/      the Java client
  boot/            entry points the DLL calls by name, class patcher
  core/ event/     config, maths, logging; the event bus
  game/ hook/      views over Minecraft state; the bytecode hook handlers
  module/          modules, one package per menu category
  render/ ui/      SDF renderer; menu, HUD, radar
  script/          the Lua API
  util/            block, font, item, packet, raytrace, render, rotation, team
bootstrap/       cobble.dll: finds the JVM, defines MemoryLoader, starts Boot
loader/          cobble-injector.exe
infra/ext/       vendored C++ (Dear ImGui)
infra/lib/       jars the client compiles against (not in git)
assets/          fonts, icons, shaders, the Lua prelude
scripts/         example Lua scripts, copied to dist/scripts
docs/            notes, most of them from the C++ payload era
tools/           Widen.java (used by CMake), crop.ps1
legacy/          the old C++ payload, the 1.21 test env and their tools
dist/            build output
```

## Use

Start Lunar Client on the 1.8.9 version, get into a world, then:

```
dist\cobble-injector.exe            # pick a window with the arrow keys
dist\cobble-injector.exe <pid>      # or name the process directly
```

The payload finds the JVM itself, waits for the game if it is still starting,
and installs its frame hook. Watch `dist/cobble.log` if anything looks wrong —
every startup stage reports success, timing, and the reason on failure.

- **Right Shift** opens the menu. Left click toggles a module, right click folds
  its settings open, and dragging a header moves the panel. The **Scripts**
  screen lists what is in `dist/scripts`: the dot switches one on, the arrow
  reloads it.
- **End**, held for half a second, unloads the client cleanly: hooks removed,
  input handed back, config saved, DLL freed. Minecraft keeps running, and the
  same process can be injected again — the config is read back on the way in.

The overlay draws through a `#version 330 compatibility` shader over Lunar's
legacy context; on 1.21 the geometry could be expanded by a compute shader, but
that path is inert here and the CPU expansion is what runs.

One behaviour worth knowing: the overlay lives in the frame hook, so while the
game window is **minimised** Minecraft stops rendering and the client stops with
it. It resumes the moment the window is restored.

## Lunar 1.8.9 port notes

- **Names.** Lunar's 1.8.9 runtime is not obfuscated: `legacy/tools/mappings.def.mjs`
  is written directly in MCP names (`net/minecraft/client/Minecraft`,
  `theMinecraft`, `posX`, `windowClick`), and `legacy/tools/genmappings.mjs` is a
  pass-through that lays the tables out and checks id uniqueness.
- **Frame hook.** `hooks/Hooks.cpp` detours `wglSwapBuffers` in `opengl32.dll`,
  which is what LWJGL 2's `Display` calls to present. The window handle comes
  from the `HDC` the hook receives; input is a `WndProc` subclass chain, not
  GLFW callbacks.
- **Rendering.** Lunar runs a legacy compatibility context, so the shaders are
  `#version 330 compatibility` and the state guard pushes/pops the fixed-function
  matrix stacks (which cannot be read back — only saved on the driver's own
  stack). Frame matrices come from `ScaledResolution`, `ActiveRenderInfo` and
  `Timer.renderPartialTicks`.
- **Modules.** The 1.21-only combat modules (crystals, anchors, mace, inventory
  totems) are out of the build; everything else runs on 1.8.9 semantics —
  `windowClick` with an integer mode covers the inventory-swap trick.
- **Not ported (yet).** The GPU compute path, the silent-rotation ordering
  guarantees and the scripted test environment were built against 1.21 and are
  not verified here.

## Modules

| Module | Category | What it does |
| --- | --- | --- |
| TriggerBot | Combat | Attacks whatever the crosshair is already on |
| Displace | Combat | Turns on the hit so the knockback throws them sideways, into the void when there is one |
| JumpReset | Combat | Jumps on the tick a hit lands so the knockback carries |
| AutoTool | Player | Swaps to the fastest tool for whatever is under the crosshair |
| BridgeAssist | Player | Sneaks at the edge and aims at the face you are bridging off |
| Clutch | Player | Turns to a face within reach and blocks the fall, by hand or on its own |
| FastMine | Player | Shortens the wait between blocks and speeds up the break |
| FastPlace | Player | Removes the four tick wait between right clicks |
| ESP | Render | Boxes flat or in 3D, tracers, off-screen arrows, health, name and distance |
| TargetHud | Render | Panel for whoever the combat modules are hitting, with a lagging damage bar |
| Trajectories | Render | Draws where the projectile in your hand lands, and what stops it |
| CuteVisuals | Render | Heart and dot trail, and a burst when a bed goes |
| ClickGUI | Client | The menu, so its bind lives in the same list as everything else |
| HUD | Client | Watermark, frame rate, latency, module list, target panel, speed/XYZ, notifications |
| Radar | Client | Everybody in a loaded chunk: gear, enchantments, effects, a running item history and a skin you can turn |

The Player category and the four Render modules below ESP are ports of
[Raven-b](https://codeberg.org/strangerrrrs/raven-bS) - a 1.8.9 Forge client -
and of two of its scripts. What was ported is the behaviour, not the code: the
originals are mixins over 1.8 internals and this is a native payload on 1.8.9,
so every one of them was rebuilt on what the JNI bridge can actually reach.
Where that changed something, the module says so in its own header comment.
Licensing of anything derived from that source is on you.

**There is one colour.** Every module draws in the ClickGUI accent or a step
along it, and no module carries a colour setting of its own - a per-module
picker is how a client ends up with six hues that were each reasonable alone
and a mess together, and it makes the accent setting a lie. Where a module has
something to say that colour is the right way to say it - how much health a
target has left, whether you are ahead in a fight - it uses the theme's own
semantic colours, which move with the theme and are still not pickers. See
`ui::accentShade`.

Everything that touches rotation goes through one engine that quantises to whole
mouse counts and applies once per client tick. Everything that acts on the world
does so only after the rotation the server *already holds* covers the target.
Both properties are what a modern anticheat actually measures; see
`docs/grim-rotation-ordering.md` and `docs/mouse-grid-rotations.md`.

**Silent rotations** are the default for Scaffold and available on KillAura. The
aim goes into the player only on the frame before the client tick, so the
movement packet carries it, and a second hook takes it back before the world is
drawn — the server sees the rotation, the camera never does. No mixins and no
bytecode injection are involved; it falls out of the order Minecraft does things
in. `docs/silent-rotations-without-bytecode.md` has the whole thing,
including the two ways of getting it wrong that cost the most time.

Scaffold searches **behind** the direction of travel, not straight down: by the
time a placement reaches the server the player has already left the cell they
were standing in. Telly does not even choose a cell first, it casts from yaws on
the 45 degree grid and takes the block the ray landed on, so the rotation and
the placement agree by construction. It has three aim styles — Normal points at the hit vector,
**Telly** is a three-phase cycle, winding up on the ground with the yaw
untouched, snapping the yaw onto the direction of travel for the tick the jump
goes out, then aiming for real while airborne. **Watchdog** is
Rise's `applyNewWatchdogRotations`, aiming half a block off the block centre
along the player's side axis and subtracting a movement-dependent yaw offset.
The block about to be placed is outlined as an actual projected cube, faces and
all, rather than the screen rectangle that contains it.
`docs/scaffold-placement-search.md` has the details.

## Scripting

Everything in the table above can also be written in Lua. `dist/scripts/*.lua`
is read at startup and reloadable from the menu; a script declares modules that
the rest of the client cannot tell from the C++ ones — same categories, same
menu cards, same `cobble.cfg`, same array list.

```lua
cold.script { name = "Aura", version = "1.0", author = "you" }

local aura = cold.module {
    name = "ScriptAura", subcategory = "extreme", icon = "swords",
    settings = { { name = "Range", type = "number", value = 3.0, min = 1, max = 6, decimals = 2 } },
}

function aura.onTick()
    local target = cold.entities.target { range = aura.settings.Range }
    if not target then return end
    local yaw, pitch = cold.rotation.aimAt(target:aimPoint(0.12))
    cold.rotation.set(yaw, pitch, { silent = true, priority = 5 })

    -- Only swing once the rotation the server holds actually covers the target.
    local sentYaw = cold.rotation.sent()
    if math.abs(cold.util.angleDiff(sentYaw, yaw)) < 4 then target:attack() end
end
```

The Lua surface keeps the `cold.*` namespace from the previous build, so scripts
written for it run unchanged. It covers the player, the entities the frame
captured and their status effects, the world and its blocks by name, the
inventory by registry id, the rotation engine (quantised to whole mouse counts
for a script exactly as for a module), the interaction queue, the movement
bindings, the input state, and the same signed-distance renderer the HUD and the
menu draw with — in two dimensions and in three, projected with the frame's own
matrices.

On top of that: HUD elements of your own that the player drags where they like
and that stay there, client-side chat and the actionbar, on-screen
notifications, the client's theme and friends list, JSON storage, and
twenty-four events covering the tick, the frame, every kind of input, world and
screen changes, entity spawns and deaths, health, held slot, module toggles and
status effects. Event names are matched loosely enough that a script written for
another client mostly reads the same.

Each script is its own interpreter, so one erroring cannot reach another;
callbacks are stopped after 250 ms and states are capped at 64 MB, because all
of it runs on the render thread. **[`scripts/README.md`](scripts/README.md)** is
the full reference, `scripts/selftest.lua` exercises every part of it and says
what failed, and the files beside them are worked examples;
`docs/lua-scripting.md` is how it is put together.

## Test environment

`legacy/testenv/` is a Loom workspace that was built against 1.21.11 Fabric. It is not
part of the 1.8.9 port — the control-channel smoke suite below has not been
rewired to Lunar. It is kept for reference and for a future 1.21 build.

The payload exposes a file-driven control channel next to the DLL — drop
commands in `cobble.control`, read `cobble.control.out` — but the smoke suite
that drives it asserts 1.21 behaviours and has not been rewired to Lunar.

Other tools:

| Command | Purpose |
| --- | --- |
| `legacy/tools/shoot.ps1 -Script "..."` | Send control commands, capture a frame |

## Measured cost

On a 1280x720 dev client with the menu closed, the ESP running and the HUD
drawing, the whole overlay pass is **0.1–0.5 ms per frame** (`state` reports
`overlay=<n>us`). Startup from injection to first drawn frame is ~1.0 s, of
which most is rasterising the SDF font atlas on the loader thread, off the
render path.

## The Knowledge

`docs/` is an Obsidian-style vault of everything in this project that
cost more than one attempt. Start at [`docs/MAP.md`](docs/MAP.md).

## Provenance

The JNI bridge design, the injector shape and the rotation-controller model were
studied from the [Vape 4.21 recovery project](https://github.com/OpenVapeCN/VapeV4.21).
The dropdown menu follows the layout [LiquidBounce](https://github.com/CCBlueX/LiquidBounce)
popularised. The silent-rotation model — a server rotation kept separate from the
camera, composed with the player's own mouse movement rather than overwritten by
it — and the telly bridge come from Rise 6.9.5 (`RotationComponent`,
`RotationUtil.applySensitivityPatch`, `Scaffold.getTellyRotation`); Rise's
sensitivity quantisation is the same formula this engine had arrived at
independently, which is how it was confirmed correct.

Those are Java clients for 1.8.9 and this is C++, so nothing was
transliterated — but the designs are theirs, and all four are licensed
differently from this repository. **Check the licences before redistributing.**

Fonts are [Inter](https://rsms.me/inter/) and
[JetBrains Mono](https://www.jetbrains.com/lp/mono/) (OFL), icons are
[Lucide](https://lucide.dev/) (ISC), and the bundled third-party sources are
MinHook (BSD-2), Lua 5.4 (MIT), stb_truetype (public domain) and nanosvg
(zlib).
