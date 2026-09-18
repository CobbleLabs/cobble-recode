# The Knowledge — cold.wtf

Index of everything that cost more than one attempt to get right. Start here.

## Porting the payload itself
- [[lunar-1-8-9-port]] — moving the whole client from 1.21.11 Fabric to Lunar's
  1.8.9 runtime: MCP names, `wglSwapBuffers`, the compatibility context, and the
  enum-id contract that made the mapping rewrite safe.

## Bridging into a running Fabric client
- [[fabric-jni-bridge]] — one mapping table that works in `runClient` **and** in a
  shipped Fabric install, via Fabric's own `MappingResolver`.
- [[finding-minecraft-from-a-native-thread]] — why `FindClass` fails from an
  injected thread and what to use instead.
- [[jvmti-from-an-injected-dll]] — why the usual JVMTI bootstrap crashed the VM
  here, and why nothing needed it in the end.

## Not getting flagged
- [[grim-rotation-ordering]] — the packet order that decides whether an attack
  is validated against the rotation you intended.
- [[mouse-grid-rotations]] — why arbitrary float yaws are trivially detectable
  and how to stay on the sensitivity grid.
- [[block-placement-hit-vector]] — a placement that worked fine and was still
  flaggable on every click.
- [[silent-rotations-without-bytecode]] — putting an aim in the movement packet
  without it ever reaching the camera, from a native DLL with no mixins.
- [[scaffold-placement-search]] — why building straight down does not bridge,
  and the Rise telly and Watchdog rotations.
- [[telly-scaffold]] — the three-phase telly cycle, and why the rotation has to
  choose the block rather than the other way round.
- [[inventory-swap-without-a-screen]] — the one call that moves a stack to the
  offhand, and why the version that walked the real cursor is gone.

## Getting the damage right
- [[explosion-damage-model]] — the vanilla falloff, the two errors that made
  every AutoCrystal candidate fail its own self-damage limit, and the three
  placement rules a crystal has to satisfy before the server will spawn one.
- [[shields-and-the-mace]] — why a mace smash into a raised shield is worth
  literally nothing, and the shape of the axe-first fix.

## Rendering over Minecraft
- [[frame-hook-choice]] — where to hook, and why `glfwSwapBuffers` beats the
  alternatives on 1.21.9+.
- [[gl-state-restore]] — the state Minecraft caches that you must put back,
  including the sampler objects that silently ruin texture filtering, and why a
  blit out of the default framebuffer comes back upside down.
- [[mc-1-21-11-projection]] — the exact view/projection composition, verified
  against `GameRenderer` bytecode.
- [[sdf-ui-pipeline]] — one shader, one draw call, crisp at every size.
- [[compute-shaders-in-a-borrowed-context]] — why a 3.3 core context can still
  dispatch compute, the three things that waste an hour proving it, and what
  moving the quad expansion onto the GPU was actually worth.
- [[matching-a-reference-ui]] — porting a menu 1:1 from a screenshot: measuring
  it, laying it out in Minecraft's own GUI units, and what a font size means.
- [[player-radar]] — reading another player's equipment, skin and item history
  from a native payload, and drawing the skin back as a model you can turn.
- [[lwjgl-owns-the-glfw-callbacks]] — why chaining onto a GLFW callback the
  obvious way kills the JVM when the game is closed.

## Porting from other clients
- [[porting-raven-b-modules]] — the four Raven-b mechanisms that have no
  equivalent in a native payload and what carries the same meaning instead,
  plus the billboard trick that makes a particle trail cost one projection.

## Scripting
- [[lua-scripting]] — Lua inside the payload: why it is compiled as C++, how a
  script's modules join and leave the module list without breaking the loops
  that walk it, and what a handle is allowed to hold.

## Working on this repo
- [[build-and-test]] — build, run, inject, screenshot.
- [[reading-minecraft-ground-truth]] — using `javap` on the remapped jar instead
  of guessing at behaviour.
- [[triggerbot-reach]] — the two causes of the intermittent reach flags and
  the fixes, verified against a local GrimAC test server.
