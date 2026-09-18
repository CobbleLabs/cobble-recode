# Porting the payload from 1.21.11 Fabric to Lunar Client 1.8.9

The whole client — bridge, hooks, renderer, modules, UI — was moved from a
1.21.11 Fabric runtime to Lunar's 1.8.9 runtime. The architecture survived; five
load-bearing assumptions did not. This is what changed and why, ordered by how
much time each would have cost to discover the hard way.

## Lunar ships MCP names, not intermediary names

The single biggest simplification. The 1.21 build translated Yarn names into
intermediary names at codegen time because a shipped Fabric install resolves
through `MappingResolver`. Lunar's 1.8.9 runtime is **not obfuscated**: classes
are `net.minecraft.client.Minecraft`, fields are `posX`, methods are
`windowClick`. Working injected clients confirm this in practice.

So `tools/mappings.def.mjs` is written directly in runtime names and
`tools/genmappings.mjs` is now a pass-through: lay the tables out in declaration
order, check id uniqueness, emit the headers. No translation step, no namespace
handling, no tiny-mapping format.

What made the rewrite safe was the **enum-id contract**: every JNI site in the
payload references `MappedClass::`, `MappedMethod::`, `MappedField::` constants,
and the generated enum order is what the tables are indexed by. The def file was
rewritten as an id-keyed table in the *old* id order, with a golden list of the
old header's enums diffed mechanically against the new one (89 classes, 165
methods, 119 fields, all in order, new ids appended at the tail). Without that
check, a one-line reorder compiles fine and reads the wrong field.

## The frame hook is wglSwapBuffers, not glfwSwapBuffers

Lunar 1.8.9 presents through LWJGL 2's `Display`, which calls `wglSwapBuffers`
exported by `opengl32.dll`. MinHook detours it — signature `(HDC) -> BOOL` — and
the trampoline is the game's present, so the hook draws *before* the swap like
the GLFW one did.

- The window handle comes from the `HDC` the hook receives:
  `WindowFromDC(hdc)`. Nothing needs to find the window before the first frame,
  which removes a whole class of startup races.
- `wglSwapBuffers` is the process-wide GL present, so the hook survives Lunar
  swapping its internal LWJGL context code paths; there is no per-context
  function pointer to go stale.
- `GetModuleHandleA("opengl32.dll")` resolves before the game has made a
  context — the module is loaded as soon as any GL symbol is touched — so hook
  install no longer waits for `glfwInit`.

Input moved from GLFW callbacks to a `WndProc` subclass chain
(`SetWindowLongPtrW`). The WM_* handlers feed the same event queue the GLFW
callbacks did, so the GUI, HUD and module layers did not change at all.
`WM_KEYDOWN`/`WM_LBUTTONDOWN` and friends are swallowed while the menu owns the
cursor; key translation (virtual keys back to the GLFW-style codes the GUI
binds against) lives in one table. The 1.8.9 camera-grab gotcha: the game
re-centres a grabbed cursor every frame, so `WM_MOUSEMOVE` deltas the game sees
must stop entirely while the menu is open, not just be ignored — the client
drives `Mouse.setGrabbed(false)` from the render thread instead.

## Lunar's context is compatibility profile

Minecraft 1.21 asks GLFW for a 3.3 **core** context; Lunar 1.8.9 runs a legacy
**compatibility** context. Consequences:

- Every shader becomes `#version 330 compatibility` (and anything touching
  `gl_ModelViewProjectionMatrix` needs `compatibility` specifically). The
  compute/SSBO path is probed for and inert — `glDispatchCompute` exists on the
  driver but not in the context, same answer as on 1.21 core, so the renderer's
  CPU fallback simply is the path now.
- The fixed-function matrix stacks are live state the game keeps using after
  the overlay draws. GL 1.1 offers **no query** for the matrices themselves —
  only stack depths — so the state guard cannot snapshot and restore them; it
  must `glPushMatrix`/`glPopMatrix` them and land on the same depth it found,
  with the matrix mode put back too. This is the opposite of the core-profile
  guard, which snapshots everything because nothing has a stack to push.
- Lunar leaves client-state arrays enabled for its own colour/texcoord arrays.
  A stray enabled array before our vertex-attrib fetch reads the wrong pointer,
  so the frame setup disables `GL_VERTEX_ARRAY`/`GL_COLOR_ARRAY`/
  `GL_TEXTURE_COORD_ARRAY` on the first three texture units via
  `glClientActiveTexture` before drawing.

Frame matrices are read from the game rather than composed: projection from
`GameRenderer`-time `ActiveRenderInfo`, view from its modelview, tick fraction
from `Timer.renderPartialTicks`, GUI scale from `ScaledResolution`/`Display`.
The capture runs once per frame on the hook thread that already owns the
context.

## Capture and actions in 1.8.9 terms

- Entities come from `World.loadedEntityList` — there is no typed iterable —
  walked with `List.iterator()` cached once. Interpolation is
  `prevPosX + (posX - prevPosX) * renderPartialTicks`, read per field.
- Inventory moves go through `playerController.windowClick(windowId, slot,
  button, mode, player)` with the **integer** mode (`2` = swap) instead of the
  1.21 `SlotActionType` enum; the inventory-swap trick survives unchanged.
- No offhand, so equipment views collapse to armour plus the held stack, and
  the offhand modules have nothing to say.
- The menu cursor is a matter of `Mouse.setGrabbed` plus the client's own drawn
  cursor; there is no `Mouse.lockCursor` pair to fight.

## What was dropped, and what it cost

The 1.21-only combat modules — AutoCrystal, AutoHitCrystal, AnchorMacro,
AutoMace, AutoInventoryTotem — have no target-version concept (no crystals, no
anchors, no mace, no offhand totems) and are out of the build rather than
half-alive behind optional mappings. Their def-table entries stay as
optional-dead placeholders so the enum contract holds.

`testenv/` (Loom, 1.21.11), the silent-rotation ordering guarantees and the
smoke suite are all built against 1.21 behaviour and are **not** verified on
Lunar. Porting those is its own project, not a side effect.

## Checklist for the next version port

1. Diff the runtime's class/field names against the def table first (a decompile
   of the actual jar beats any wiki).
2. Freeze the enum-id order before touching names; verify mechanically after.
3. Identify how the runtime presents (GLFW vs wgl) and what context profile it
   requests — that decision drives hooks, shaders and the state guard.
4. Assume the state guard needs rework; the state a version's GL path caches is
   version-specific.
5. Grep the module set for version-locked concepts before promising anything.
