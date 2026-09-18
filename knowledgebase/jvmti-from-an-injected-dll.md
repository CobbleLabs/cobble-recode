# JVMTI from an injected DLL: don't

## What was tried

The standard injected-client bootstrap is to ask the live VM for a JVMTI
environment and use `GetLoadedClasses` to find game classes without a class
loader:

```c
vm->GetEnv(&jvmti, JVMTI_VERSION_1_2);
jvmti->GetPotentialCapabilities(&potential);
jvmti->AddCapabilities(&requested);
```

Against Minecraft 1.21.11 on JDK 21, loaded through `System.load` from a Fabric
mod initialiser, this **crashed the JVM** with an access violation
(`0xC0000005`) immediately after `JNI_GetCreatedJavaVMs` returned. No `hs_err`
file was produced. The last log line written was the VM pointer, which places
the fault inside the `GetEnv`/capabilities sequence.

Worth knowing before spending an afternoon on it: this is the documented-ish
path that other clients use, and it does work in other JVM configurations, so
the failure is specific rather than universal. It was not worth chasing, because
the whole reason for reaching for JVMTI turned out not to apply.

## What replaced it

The bootstrap only needed JVMTI to work around `FindClass` resolving against the
system class loader from a thread with no Java frames. But cold.wtf's frame hook
runs on the **render thread**, entered from LWJGL's `glfwSwapBuffers` native
method — so there *are* Java frames on the stack, and `FindClass` resolves
against the class loader that Fabric used for LWJGL, which sees the whole game.

So the loader thread now does only the work that needs no JNI (logging, mapping
the asset blob, finding the JavaVM, rasterising atlases, registering modules,
installing the hook) and everything Minecraft-facing happens on the first frame.
See [[finding-minecraft-from-a-native-thread]] and [[frame-hook-choice]].

Net effect beyond avoiding the crash:

- No `GetLoadedClasses` scan over tens of thousands of classes.
- No JVMTI capabilities requested, so the VM keeps its fast paths — a JVMTI env
  with redefinition capabilities disables some JIT optimisations.
- The payload survives being injected before the game window exists: the first
  frame simply retries until Minecraft is reachable, with a deadline.

## If you do need it later

Bytecode-level hooks would need it back. Wrap the acquisition in SEH and treat
failure as non-fatal, the way `Client::onFrame` wraps the frame body — a client
that cannot start is much better than one that takes the game down with it.
