# Where to hook the frame

## The choice

`glfwSwapBuffers`, detoured with MinHook, resolved out of whichever module in
the process exports it.

## Why not the alternatives

| Option | Why not |
| --- | --- |
| Bytecode injection via JVMTI `RetransformClasses` | Needs a class-file editor in C++, and every inserted call shifts StackMapTable offsets, exception ranges and line tables. High risk of a verifier error for a callback you can get for free. |
| Mixin / a Java-side mod | Turns the payload into a mod install. The brief is an injectable client. |
| `wglSwapBuffers` in opengl32 | Works, but GLFW may not route through it on every driver path, and you lose the window handle that the input chain needs. |
| A polling thread | Wrong thread. OpenGL calls must run where the context is current, and JNI calls off the render thread race the game's own state. |

## What the position gives you

At `glfwSwapBuffers` the frame is finished: world, entities, vanilla HUD and any
post-processing are all in the default framebuffer, the GL context is current on
this thread, and the client tick for the frame has already run.

That last point matters more than it sounds. `RenderSystem.flipFrame` is:

```java
GLFW.glfwPollEvents();
replayQueue();
GLFW.glfwSwapBuffers(window);
GLFW.glfwPollEvents();
```

so the hook fires immediately before the next tick's input handling and movement
packet. A rotation written here rides the very next packet — see
[[grim-rotation-ordering]].

It is also the reason [[finding-minecraft-from-a-native-thread]] became a
non-issue: the hook is entered from LWJGL's native method, so the render thread
has Java frames and plain `FindClass` resolves against the game's class loader.

## Input, without a second detour

GLFW's `glfwSet*Callback` functions **return the previously registered
callback**. Installing ours hands back Minecraft's, which we then chain to — or
do not, when the menu owns the cursor. No trampoline, and LWJGL's own callback
bookkeeping stays consistent.

Re-installing every frame is idempotent and self-healing: compare the returned
pointer against our own function, and only update the chain target when it
differs.

## Crash containment

The hook runs on Minecraft's render thread, so a fault in it takes the game
down. `Client::onFrame` wraps the real body in SEH and disables the overlay on a
fault instead of propagating. Note MSVC's C2712: a function containing `__try`
cannot hold objects that require unwinding, so the body and the error reporting
both live in separate functions.
