# Build, run, inject, look at it

## The loop

```bash
legacy/tools/dev.sh build                       # cmake --build, errors only
legacy/tools/dev.sh run                         # runClient with the payload autoloaded
legacy/tools/dev.sh wait                        # blocks until the payload draws a frame
legacy/tools/dev.sh stop                        # unload cleanly, then kill the client
```

`stop` before `build`: the DLL is mapped into the running game and the linker
cannot overwrite it (`LNK1104: cannot open file ... coldwtf.dll`).

## Driving a running client without a keyboard

Minecraft's window is rarely in the foreground on a build machine, and
`SetForegroundWindow` is refused by Windows most of the time, so a screen-scrape
grabs whatever is on top instead. Both problems go away with the payload's file
control channel: drop `coldwtf.control` in `%APPDATA%\coldwtf` (where the DLL
keeps its configs, logs and scripts since the move off the DLL folder), and it
executes the commands on its next frame. First boot migrates the old files over.

```powershell
powershell -File legacy/tools/shoot.ps1 -Script "gui on;expand KillAura on;shot a.bmp" `
    -Png build/shots/a.png
```

`shot` is queued for ~45 frames after the rest of the batch so animations
started by the same commands have settled. Frames, not milliseconds: uncapped on
the title screen the client runs at four figures, and a menu told to close in
the same batch is still visibly fading in the frame that comes back. Send the
state change and the `shot` as two batches when the animation matters.

The frame comes from `glReadPixels` on the default framebuffer, which is exactly
what was presented — no compositor, no occlusion, no focus required.

Commands: `gui`, `screen`, `module`, `set`, `script`, `expand`, `pick`, `entities`,
`radar`, `state`, `cmd`, `say`, `hold`, `respawn`, `closescreen`, `atlas`, `gpu`,
`shot`, `unload`.

`radar` dumps what the player radar has on file - every tracked player with
their six equipment slots and enchantments. A screenshot cannot say whether a
slot came back empty because the player has nothing or because the read failed;
this can.

`gpu on|off` switches the quad expansion between the compute kernel and the CPU
at runtime. Both fill the same buffer from the same records, so it is an A/B on
cost alone - sample `overlay=` from `state` a few times either side of the
switch, and give the rolling average a moment to settle before believing it.
`state` reports `gpu=` and `batch=` (primitives the previous frame drew) so a
measurement can say what load it was taken under.

`screen <name>` switches the menu to a sidebar screen and `pick <module> on`
opens that module's colour popup, because the channel cannot move a pointer and
both are only checkable in a screenshot.

`hold use on` and `hold attack on` press a mouse button that nobody is pressing.
GLFW is re-polled every frame, so the flag lives beside the real button state
rather than in it; without this nothing that triggers on a held button - the
anchor macro, the crystal hit - can be reached from a script at all.

`script list`, `script reload [file]` and `script on|off <file>` drive the Lua
system. Everything except `list` is queued and lands at the top of the next
frame, so assert on the `state` line that follows rather than on the reply.

`state` is the assertion surface — world, screen, fps, ping, framebuffer, entity
count, overlay microseconds, frame and tick counters, player age, aim state,
sensitivity step, loaded scripts against scripts found, registered modules,
position and rotation.

## A world to test in

`gradlew runServer` (runDir `run-server`) brings up a superflat, offline,
creative server; `runClient -Pserver=localhost:25565` joins it straight from the
launch with `--quickPlayMultiplayer`. Real latency, real chunk loading, and
`cmd <slash command>` over the control channel sets scenarios up, because the
dev player is opped in `run-server/ops.json` by its offline-mode UUID:

```js
md5("OfflinePlayer:" + name) with version 3 and the RFC 4122 variant bits
```

Two traps that cost time:

- **`/fill` silently refuses unloaded chunks** and the failure is not
  broadcast, so it never reaches the server log. Teleport first, wait for
  chunks, then build.
- Command feedback goes to chat, not the server log. To assert on world state,
  use `execute ... run say MARKER` and grep `run-server/logs/latest.log`.

## Testing against GrimAC

Bring your own GrimAC server (not shipped in this snapshot): any standalone
Fabric server with GrimAC works, started directly with its launch jar, then
`runClient -Pserver=localhost:25565` as usual. Grim's alerts go to the
console with `print-to-console: true`, so `grep "failed" logs/latest.log` is the
whole assertion surface, and the count before and after a run is the measurement.

Do **not** drop `grimac-fabric.jar` into `legacy/testenv/run-server/mods/` and use
Loom's `runServer` instead. That workspace runs in the *named* mapping
namespace, Fabric Loader remaps the production jar on the way in, and some of
GrimAC's mixin targets do not survive the trip — the loader logs
`Cannot remap field_... because it does not exist in any of the targets` and the
server then dies on the first player tick with a `StackOverflowError` recursing
through `ServerPlayerEntity.isDisconnected`. It looks like a Grim bug and is
not one.

Two things to set up before measuring anything: `difficulty peaceful` and
armour stands as targets. Zombies despawn on peaceful and move; armour stands
are `LivingEntity`, hold still, take damage and survive the difficulty, which
makes them the only repeatable combat target.

The framerate is part of the experiment, not a detail. `maxFps` in
`legacy/testenv/run/options.txt` reproduces the low-framerate case that the ordering
work in [[grim-rotation-ordering]] is about; at 20 the flags appear reliably and
at 1000 they never do.

## Looking at pixels

A 1280x720 screenshot shown at a glance hides everything. `tools/crop.ps1`
scales a region up with nearest-neighbour:

```powershell
powershell -File tools/crop.ps1 -In build/shots/gui.png -Out build/shots/zoom.png `
    -X 8 -Y 8 -W 320 -H 170 -Zoom 4
```

That is what turned "the text looks a bit soft" into "the antialiasing ramp is
two pixels wide" ([[sdf-ui-pipeline]]).

For texture problems, dump the atlas from both sides and compare:

```
atlas cpufont cpu.bmp      # what the builder produced
atlas font    gpu.bmp      # what the driver actually holds
atlas icon    icons.bmp
```

A mismatch between those two is a pixel-transfer state problem, not a builder
bug ([[gl-state-restore]]).

## The suite

```powershell
powershell -ExecutionPolicy Bypass -File legacy/tools/smoke.ps1
```

20 checks over startup, rendering, tick alignment, ESP, the GPU expansion path
and its fallback, TriggerBot and Clutch (acquisition, the hit or the placement
landing, and GrimAC staying quiet for both) and the two cooldown modules. Write assertions against `state` and the
payload log rather than against a single sampled frame: module state is
transient, and sampling once catches a module between targets.

Two things the suite has to do to itself and did not, both of which made runs
fail for reasons that were not the client: **respawn on the way in** (a client
left on a death screen reports `world=1` and then never ticks again, so every
timing and combat check below it fails), and **stay on peaceful** (an earlier
version raised the difficulty and summoned zombies beside a client nobody was
sitting at, which came back to a corpse).

## Ground truth

When Minecraft's behaviour is in question, read the jar rather than remembering
it: [[reading-minecraft-ground-truth]].

## Driving the control channel from a script

Write the request beside the file and **rename over it**. `WriteAllText` (and a
plain shell redirect) truncate first, so the payload — which polls the file
every frame — can read it while it is still empty, delete it, and answer
nothing. The symptom is a command that silently never gets a reply, roughly one
run in three, always on a different command. The payload also skips an empty
request rather than deleting it, which covers writers that do not rename.

```powershell
[IO.File]::WriteAllText("$request.tmp", ($Lines -join "`n") + "`n")
Move-Item -LiteralPath "$request.tmp" -Destination $request -Force
```

The other thing that stalls the channel: the payload only services it from the
frame hook, so a **minimised** window stops it dead, and an unfocused one
throttles it to the inactivity frame limiter. Do not work around that by forcing
the window to the foreground — Minecraft then takes real mouse input, and any
assertion about where the camera is pointing becomes a race with whoever is
sitting at the machine.

## A second payload on the same channel

The control files live in `%APPDATA%\coldwtf`, so any other game with our DLL injected
answers the same channel: check `state` for absurd `frames=`/`ticks=` counts
before trusting a reply, and never send actuating commands (`look`, `cmd`,
`hold`, module toggles) until the channel is yours alone. A `hud` command
reports the HUD's live toggles, positions and element rectangles the same way
`state` reports the world.
