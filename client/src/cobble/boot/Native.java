package cobble.boot;

/** Registered by the bootstrap DLL; see bootstrap/Main.cpp. */
public final class Native {
    private Native() {}

    /** JNI DefineClass, which can target a loader this code has no handle into. */
    public static native Class<?> defineClass(ClassLoader loader, String binaryName, byte[] bytes);

    public static native Class<?>[] loadedClasses();

    /** Throws IllegalStateException when JVMTI refuses. */
    public static native void retransform(Class<?>[] classes);

    /** Internal names the ClassFileLoadHook forwards to {@link Boot#transform}. */
    public static native void setTargets(String[] internalNames);

    /** Asks the bootstrap to run {@link Boot#stop} and unload, as the unload key does. */
    public static native void requestUnload();
}
