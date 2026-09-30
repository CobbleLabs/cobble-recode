# Putting Minecraft's GL state back

Minecraft caches GL state in `GlStateManager` and skips redundant driver calls.
If the overlay changes something and does not restore it, the game keeps
believing the old value and never sets it again — the symptom is not a crash but
a subtly wrong frame minutes later, which is miserable to track down.

`gl::StateGuard` snapshots and restores, in the destructor:

- current program, VAO, array buffer, element array buffer
- framebuffer binding, viewport, scissor box
- blend enable, `BlendFuncSeparate` factors, blend equations
- depth test, depth write mask, colour write mask
- cull face, stencil test, scissor test
- texture bindings **and sampler bindings** for units 0-3, plus the active unit

## The one that bites

**Sampler objects.** Minecraft binds sampler objects on texture units, and a
bound sampler *overrides* the texture's own `TEXTURE_MIN_FILTER` /
`TEXTURE_MAG_FILTER`. An SDF atlas uploaded with `GL_LINEAR` renders through
whatever filter the leftover sampler specifies — usually nearest — and the text
turns into blocky mush for no visible reason.

The fix is one line per unit at the start of the frame:

```cpp
gl::BindSampler(unit, 0);
```

and restoring the previous binding on the way out.

## Other notes

- Core profile: VAO 0 is not a valid binding. Always bind our own, and restore
  whatever was bound rather than binding 0.
- Fullscreen passes use `gl_VertexID` to synthesise a triangle, so they need a
  bound-but-empty VAO and no vertex buffer at all.
- The streaming vertex buffer is orphaned (`BufferData(..., nullptr, ...)`)
  before each `BufferSubData`, so the driver hands back fresh storage instead of
  stalling on the previous frame's draw.
- `glGetIntegerv` calls in the constructor are a full pipeline sync in the worst
  case; twenty of them per frame is still nothing next to a Minecraft frame, but
  do not put them in an inner loop.

## The frosted-glass backdrop reads the frame upside down

The blur pyramid is seeded with `glBlitFramebuffer` out of the default
framebuffer, whose **row 0 is the bottom of the window**. The UI works top-down,
so sampling the panel's slice with `v = bounds.y / height` mirrors it: a panel at
the top of the screen shows the ground behind it and one at the bottom shows the
sky. It looks plausible in a screenshot and wrong the moment a panel is dragged.

The v span has to run backwards:

```cpp
const Rect uv{bounds.x / width, 1.0f - bounds.y / height,
              bounds.width / width, -bounds.height / height};
```

Related: [[sdf-ui-pipeline]]
