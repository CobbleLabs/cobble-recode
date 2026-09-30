# Chaining a GLFW callback kills the JVM on quit

## The crash

Close the game with the payload attached and the JVM dies during shutdown:

```
EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=..., V [jvm.dll+0x1dba45]
Current thread: JavaThread "Render thread" [_thread_in_vm]
j org.lwjgl.system.jni.JNINativeInterface.nDeleteGlobalRef(J)V
j org.lwjgl.system.Callback.free(J)V
j org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(J)V
j net.minecraft.client.util.Window.close()V
j net.minecraft.client.MinecraftClient.stop()V
```

`siginfo` reads address `0xffffffffffffffff`, and the run before it looks
perfect - the client draws, input works, nothing is logged.

## Why

`Callbacks.glfwFreeCallbacks` walks every window callback, sets it to `NULL`,
and calls `Callback.free` on the pointer GLFW hands back. `Callback.free`
assumes that pointer is one of LWJGL's own closures and deletes the JNI global
ref stored beside it.

Installing a handler the obvious way - `glfwSetKeyCallback(window, &ours)`,
keep the returned pointer, forward to it - means the pointer GLFW hands back at
shutdown is **ours**, a function inside the payload. LWJGL reads a global ref
out of the middle of the DLL's code section and deletes it. The window is
already being torn down, so nothing before this point looks wrong.

Unloading first hides it: `Input::detach` puts the game's callbacks back, so
the whole failure only shows up when the game is closed with the client still
attached, which is exactly how anyone actually plays.

## The fix

Detour the five setters as well (`glfwSetKeyCallback`, `SetChar`,
`SetMouseButton`, `SetCursorPos`, `SetScroll`), in `hooks/Input.cpp`:

- the game asks to set a callback: store it as the chain, register **our**
  dispatcher with the real GLFW, and return the previous chain pointer;
- the game asks for `NULL` at shutdown: the chain goes to null and it gets its
  own pointer back to free, which is what it expected all along.

Two ordering traps:

- The GLFW entry points are resolved when the frame hook goes in, long after
  static initialisation, so the detour table has to be filled at install time,
  not in an initialiser.
- `Input::detach` must remove the detours **before** handing the callbacks
  back. Restoring through a live detour just reinstalls our dispatcher and the
  payload unloads with GLFW still pointing into it.

Guard each detour against being handed our own dispatcher (`installCallbacks`
goes through the same patched entry point) or it recurses into itself.

The same reasoning applies to anything else LWJGL frees by asking the library
what is registered. Do not hand a Java-side owner a pointer it will try to
free.
