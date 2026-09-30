#pragma once

#include <jni.h>
#include <jvmti.h>

#include <string>

namespace cold::bridge {

/* Owns the process-wide JVM handles. The payload attaches to a JVM that is
 * already running, so everything here is discovery rather than creation. */
class Jvm {
public:
    static Jvm& instance();

    /* Waits until jvm.dll has published a created VM. */
    bool acquire(unsigned timeoutMillis);

    JavaVM* vm() const { return vm_; }
    jvmtiEnv* jvmti() const { return jvmti_; }
    bool ready() const { return vm_ != nullptr; }

    /* Returns the env for the calling thread, attaching it as a daemon if the
     * thread is not yet known to the VM. Threads attached this way are detached
     * by releaseThread(). */
    JNIEnv* env();
    void releaseThread();

    /* Bootstrap class lookup, by slash-separated binary name.
     *
     * This only works from a thread that has Java frames on its stack, because
     * FindClass resolves against the class loader of the nearest one. The
     * render thread qualifies: Cobble Recode's frame hook is entered from LWJGL's
     * native method, whose declaring class Fabric loaded through Knot, so the
     * game and loader classes are both visible. A worker thread created by the
     * injector has no frames and would fall back to the system loader, which
     * cannot see them. */
    jclass findClass(const char* binaryName);

    /* Resolves a class through a specific loader, which is how everything after
     * bootstrap is looked up. Returns a global reference or nullptr. */
    jclass loadClass(const std::string& binaryName, jobject classLoader);

private:
    Jvm() = default;

    JavaVM* vm_ = nullptr;
    jvmtiEnv* jvmti_ = nullptr;
};

/* RAII wrapper around PushLocalFrame/PopLocalFrame. Every per-frame or
 * per-tick JNI batch runs inside one so local references cannot pile up. */
class LocalFrame {
public:
    LocalFrame(JNIEnv* env, jint capacity);
    ~LocalFrame();

    LocalFrame(const LocalFrame&) = delete;
    LocalFrame& operator=(const LocalFrame&) = delete;

    bool ok() const { return pushed_; }

private:
    JNIEnv* env_;
    bool pushed_;
};

/* Logs and clears a pending exception. Returns true when one was present. */
bool checkException(JNIEnv* env, const char* context);

std::string toStdString(JNIEnv* env, jstring value);
jstring toJavaString(JNIEnv* env, const std::string& value);

}  // namespace cold::bridge
