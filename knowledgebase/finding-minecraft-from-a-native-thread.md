# FindClass does not work from an injected thread

## Symptom

`env->FindClass("net/minecraft/class_310")` returns null with a
`ClassNotFoundException`, even though the game is clearly running.

## Cause

JNI resolves `FindClass` against the class loader of the nearest Java frame on
the calling thread's stack. A thread created by `CreateRemoteThread` and attached
with `AttachCurrentThreadAsDaemon` has **no Java frames**, so JNI falls back to
the *system* class loader. Under Fabric the game classes live in Knot's own
loader, which the system loader cannot see.

## Fix

Bootstrap through JVMTI, which does not care about class loaders:

1. `GetEnv(&jvmti, JVMTI_VERSION_1_2)` — works at live phase, see
   [[jvmti-from-an-injected-dll]].
2. `GetLoadedClasses` and match on the JVM signature to find
   `Lnet/fabricmc/loader/api/FabricLoader;`.
3. Through it, `getMappingResolver()` and translate `net.minecraft.class_310`
   into whatever the runtime calls it ([[fabric-jni-bridge]]).
4. `GetLoadedClasses` again to find that class, and read its loader with
   `Class.getClassLoader()`.
5. Everything after that resolves with
   `Class.forName(name, false, gameClassLoader)`.

Step 4 is the point of the exercise: it hands you the loader that owns the game,
without needing to know anything about Knot.

## Gotchas

- `GetLoadedClasses` returns local references. Delete them, and `Deallocate` the
  array and every signature string, or an injection into a long session leaks.
- The scan is O(classes) and Minecraft loads tens of thousands, so do it exactly
  twice during bootstrap and never per frame.
- On the render thread `GetEnv` succeeds outright — that thread is already a
  Java thread. Only the loader thread needs attaching, and only it should be
  detached.

Related: [[fabric-jni-bridge]]
