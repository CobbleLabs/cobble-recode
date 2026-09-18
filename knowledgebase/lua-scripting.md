# Putting Lua inside the payload

The script system is Lua 5.4 vendored into `payload/thirdparty/lua`, one
`lua_State` per script, driven from the same frame the client already draws in.
`scripts/README.md` is the API reference; this is the list of things that were
not obvious.

## Compile Lua as C++, not as C

Lua's error path is `longjmp`. Every binding in `payload/src/script` holds
`std::string`s and calls `luaL_error` on bad input, and a `longjmp` out of that
skips every destructor between the raise and `lua_pcall` — a leak per bad
argument, and undefined behaviour the moment anything non-trivial is on the
stack.

`ldo.c` already has the answer:

```c
#if defined(__cplusplus) && !defined(LUA_USE_LONGJMP)
#define LUAI_THROW(L,c)  throw(c)
```

So the sources are listed in CMake with `set_source_files_properties(... LANGUAGE
CXX)`. Lua compiles clean as C++ (it is a supported configuration upstream), the
errors become real C++ exceptions that unwind properly, and `lua_pcall` catches
them exactly as before. The only thing to remember is that `lua.h` must then be
included **directly**, never through `lua.hpp` — the `extern "C"` in that header
would give the declarations a linkage the definitions no longer have.

## Never mutate the module list from inside a walk

`ModuleManager::modules_` is a `vector<unique_ptr<Module>>` walked every tick and
every frame. A script that declares a module appends to it; a script being
unloaded erases from it. Doing either from inside `tick()` or `render()` — which
is where a Lua callback runs — invalidates the loop that is calling you.

Two rules keep it safe, and both are cheap:

- Loading, unloading, reloading and enabling are **requests**. `ScriptManager`
  queues them and services the queue in `beginFrame`, before anything walks the
  list. That is also what lets a script ask for its own reload from inside one of
  its own callbacks.
- `cold.module` refuses outside the loading window. `ScriptManager::loading_` is
  true only while a script's chunk and its `load` handlers run, both of which
  happen from that same safe point.

## Unload in the right order

A `ScriptModule` holds a registry reference into its script's state, so the
state has to outlive its modules. `stopScript` therefore goes: fire `unload`,
unregister every module the script owns (which destroys them, and they give
their references back while the state is still open), then `lua_close`.

Getting this backwards is not a crash you will see straight away — the reference
is only touched in the destructor.

## A reload must not forget what the module was

Reloading a script tears its modules down and builds them again from scratch,
which means the new one starts from its declared defaults. Without help, every
reload would silently switch the module off and reset every slider the user had
touched since the client started.

`ModuleManager::unregisterModule` writes the module's live state into the config
document it already holds before erasing it, and `registerModule` applies that
document on the way in. The same document is the base for `saveConfig`, so a
script that is switched off right now still keeps its settings in the file.

## The menu caches pointers into the module list

`ClickGui` builds one card per module and keeps `Module*` in it, plus interaction
pointers into those cards' rows. Any change to the module list therefore has to
be followed by `gui().reset()`, which the script manager does once per frame when
anything actually changed.

Two smaller traps came with the Scripts screen:

- A category tab exists only while something is in that category, so unloading a
  script can shorten the tab list. `build()` clamps the selected tab, otherwise
  the next frame indexes past the end.
- The screen draws no module cards, so their rectangles have to be cleared as
  the category screens do. A stale rectangle keeps taking clicks on a screen the
  card is not on.

## The renderer's clip stack is shared

`cold.render.clip` pushes onto the same stack the HUD and the menu use, and they
are drawn after the scripts are. A script that forgets to unclip would clip
them too. `DrawingScope` records the depth on the way in and unwinds whatever is
left on the way out, so the worst a script can do is clip itself.

## Handles resolve, they do not hold

Every `jobject` in a `FrameState` is a JNI local reference that dies with the
frame's `LocalFrame`. A Lua value can outlive the frame it was made in — a
script can perfectly well keep an entity in an upvalue — so entity handles store
an **id** and resolve against the frame in flight on every access, caching the
last index they found it at. Once the entity is gone the handle answers
`valid == false` instead of dereferencing something that was freed.

The same reasoning applies to module handles, which store a name and look it up.

## A runaway script is a frozen game

Everything runs on the render thread, so `while true do end` in a script is not a
slow script, it is a hung Minecraft. Each state gets a count hook every 200k VM
instructions that compares against a deadline set when the outermost call
started; overrunning 250 ms raises an ordinary Lua error, which the script's
error counter then handles like any other. The allocator is wrapped as well, so
a leak hits a 64 MB ceiling and takes the script down rather than the JVM.

## Two mappings worth having

`cold.chat` and `entity:name()` needed four new entries in
`tools/mappings.def.mjs`:

- `MinecraftClient.inGameHud` → `InGameHud.getChatHud()` → `ChatHud.addMessage(Text)`,
  with `Text.literal(String)` to build the argument. That is a client-side chat
  line: nothing is sent to the server.
- `Nameable.getStringifiedName()` returns a plain `String`. `Entity` implements
  `Nameable`, and JNI resolves an interface method on the instance, so this is
  one call with no `Text` handling at all — `Text.getString` is not in the
  mappings for 1.21.11 and reaching a name through `Text` would have been three
  calls and a translation lookup.

## Events are only computed while something is listening

Most of the event list is a diff of something the frame already has: entities
that appeared or went, the player's health, the selected slot, which modules are
on, which status effects are up. Producing those every frame for nobody would be
a waste, and two of them (the slot and the effects) cost JNI calls.

So every tracker in `ScriptManager` starts with `anyHandler(Event::X)` and
clears its own state when the answer is no. The cost of the whole event system
with nothing subscribed is one comparison per event per frame.

The one that is not a diff is `entitydeath`. `captureEntities` drops anything
that fails `isAlive`, so a death normally looks like an absence rather than a
health reading — the same thing `game/DeathWatch.hpp` is built around. The
tracker therefore fires on both: health crossing zero while the entity is still
captured, and a living entity leaving the capture set.

## HUD elements are units, not pixels

`HudElement` stores its position and size in Minecraft's GUI-scaled units, like
the menu and the HUD, and multiplies up by the scale factor the game is already
using. A saved position then survives a resolution change, a GUI-scale change
and a monitor swap, which a pixel position does not.

The drag is offered the click only when it missed the menu window
(`ClickGui::contains`), and only while the menu is open — that is the one moment
the elements compete with anything for the pointer.

## Related

- [[grim-rotation-ordering]] — why `cold.rotation.sent()` is what a script must
  check before acting, not the camera.
- [[mouse-grid-rotations]] — what `cold.rotation.set` is doing for a script that
  it must not try to do itself.
- [[matching-a-reference-ui]] — the GUI units the Scripts screen is laid out in.
