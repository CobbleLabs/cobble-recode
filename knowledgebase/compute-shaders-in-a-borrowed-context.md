# Compute shaders in a context you did not create

The overlay does not create an OpenGL context. It borrows the one Minecraft
made, and Minecraft asks GLFW for **3.3 core**. Compute shaders arrived in GL
4.3. So the first answer looks like "no compute here", and that answer is
wrong — but only on some machines, which is the part worth writing down.

## What is actually true

A driver honours a version request literally: a 3.3 core context has no 4.x
core features. But `ARB_compute_shader` is an *extension*, and an ICD may
advertise an extension on a lower context if it wants to. Both AMD and Nvidia
do. On the dev machine:

```
[gl] context 3.3.0 Core Profile Context 26.8.1.260810 on AMD Radeon RX 7700 XT
[gl] caps 3.3  compute=1 ssbo=1 instancing=1  extensions=296  workgroup=1024
```

296 extensions on a 3.3 context, including `GL_ARB_compute_shader` and
`GL_ARB_shader_storage_buffer_object`. `glDispatchCompute` works. The kernel
compiles and links.

So the capability is a property of **the driver and the host process**, not of
the card, and it has to be asked at runtime. `gl::capabilities()` asks; the
renderer keeps a CPU path for the answer being no, and says which one it took:

```
[render] quad expansion runs on the GPU
```

## Three things that will waste an hour each

**1. Resolving the entry point proves nothing.** On Windows `opengl32.dll` and
the ICD export the whole of GL regardless of the current context, so
`wglGetProcAddress("glDispatchCompute")` returns a real pointer on a 1.1
context. It will crash or no-op. The extension string is the only honest test,
which is why `Capabilities` requires **both** the pointer and either version
4.3 or the extension by name.

**2. The GLSL version is still 330.** The shader is not `#version 430`. It is:

```glsl
#version 330 core
#extension GL_ARB_compute_shader : require
#extension GL_ARB_shader_storage_buffer_object : require
#extension GL_ARB_shading_language_420pack : require
```

That third one is easy to miss and the error message does not point at it.
`layout(std430, binding = 0) buffer` — the *binding* qualifier on an interface
block — is not part of either of the first two extensions; it comes from
`ARB_shading_language_420pack`. Without it the shader fails to compile on a
line that looks like it should be covered by the SSBO extension. The alternative
is `glShaderStorageBlockBinding`, which needs `ARB_program_interface_query`, so
asking for 420pack is the smaller dependency.

**3. A driver can advertise and still refuse.** Which is why a failed compile of
the kernel is not a fatal error here — `createComputeResources` returns false
and the client runs the CPU path. Never let an optional fast path be able to
take the overlay down with it.

## What was moved, and what it was worth

The overlay draws every primitive as one quad carrying its own signed-distance
parameters. Building those four vertices was the only part of a frame that
scaled with what was on screen: sixty-four floats per primitive, written one at
a time, on the render thread, inside `glfwSwapBuffers` — with Minecraft blocked
behind it.

Now the CPU writes **one thirty-six float record** per primitive and a kernel
writes the vertices. The vertex and fragment stages are untouched: the buffer
the kernel fills is byte for byte the one the CPU path fills, so both draw
through the same program, and `gpu off` on the control channel switches between
them at runtime — which is the only honest way to measure what the kernel is
worth on a given machine.

At ~1250 primitives a frame (ESP with 3D boxes, tracers, arrows and labels on
five entities, plus HUD and menu):

| expansion | overlay | fps   |
| --------- | ------- | ----- |
| GPU       | 132 µs  | 2 558 |
| CPU       | 173 µs  | 2 364 |

About a quarter off the overlay's CPU cost, reproducible across A/B cycles. The
gap widens with primitive count, because that is the only thing it scales with.

## The record has explicit corners, not a rectangle

The obvious record is `bounds` as a rect — four floats instead of eight. It is
wrong. `line()` and `face()` submit quads that are **not axis aligned**: a line
is a rectangle rotated to its own direction, and a projected cube face is an
arbitrary quadrilateral. Both have to travel the same batch as everything else,
because a second batch means a second draw and a second expansion, which costs
far more than the four floats saved.

The first version of this kept `line()` and `face()` pushing vertices directly
and only routed `quad()` through the kernel. Every line in the client vanished:
`flush()` rebuilds `vertices_` from the records, so anything that wrote vertices
behind the records' back was cleared before the draw.

## Related

- [[sdf-ui-pipeline]] — what the vertices being built here feed.
- [[gl-state-restore]] — the state Minecraft caches that the overlay has to put
  back, which the compute path adds nothing to: a dispatch touches no fixed
  function state.
- [[frame-hook-choice]] — why this all runs inside `glfwSwapBuffers`, and hence
  why the render thread's time is Minecraft's time.
