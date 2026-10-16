/* Native half of the Java client.
 *
 * The DLL carries two resources: MemoryLoader.class and the client image (a zip
 * of every class and asset). It attaches to the running JVM, defines
 * MemoryLoader into the system loader, instantiates it over the image with
 * Lunar's Minecraft loader as parent, and hands control to cobble.boot.Boot.
 * From then on it only serves the natives the Java side cannot do itself
 * (DefineClass into a foreign loader, JVMTI retransform, DPAPI, the stream
 * proof window) and watches the unload key. Nothing is ever written to disk. */

#include <windows.h>
#include <wincrypt.h>

#include <jni.h>
#include <jvmti.h>

#include "StreamProof.hpp"

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_set>
#include <vector>

namespace {

constexpr int kMemoryLoaderResource = 101;
constexpr int kImageResource = 102;

/* Held rather than tapped, so it cannot fire from a stray keypress mid-fight. */
constexpr int kUnloadKey = VK_END;
constexpr ULONGLONG kUnloadHoldMillis = 600;

constexpr DWORD kVmTimeoutMillis = 30000;
constexpr DWORD kMinecraftTimeoutMillis = 120000;

HMODULE g_module = nullptr;
JavaVM* g_vm = nullptr;
jvmtiEnv* g_jvmti = nullptr;
jclass g_bootClass = nullptr;
jclass g_nativeClass = nullptr;
jmethodID g_transform = nullptr;

SRWLOCK g_targetsLock = SRWLOCK_INIT;
std::unordered_set<std::string> g_targets;

std::atomic<int> g_callbacksInFlight{0};
std::atomic<bool> g_unloadRequested{false};
thread_local bool t_insideTransform = false;

std::wstring g_directory;
std::wstring g_logPath;
long long g_epochNanos = 0;

long long nanosNow() {
    LARGE_INTEGER frequency;
    LARGE_INTEGER counter;
    QueryPerformanceFrequency(&frequency);
    QueryPerformanceCounter(&counter);
    return static_cast<long long>(static_cast<double>(counter.QuadPart) * 1e9 /
                                  static_cast<double>(frequency.QuadPart));
}

/* Same file and line format as the Java Log, which appends to it once the
 * client is up. Opened per line: the native side logs a handful of lines per
 * session, and a held handle would have to be shared with the JVM. */
void logLine(const char* level, const char* fmt, ...) {
    char text[1024];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(text, sizeof(text), fmt, args);
    va_end(args);

    const long long elapsed = (nanosNow() - g_epochNanos) / 1000;
    char line[1200];
    const int length = std::snprintf(line, sizeof(line), "[%7lld.%03lld] %s [native] %s\r\n",
                                     elapsed / 1000000, (elapsed / 1000) % 1000, level, text);
    OutputDebugStringA(line);
    if (g_logPath.empty() || length <= 0) {
        return;
    }
    HANDLE file = CreateFileW(g_logPath.c_str(), FILE_APPEND_DATA,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return;
    }
    DWORD written = 0;
    WriteFile(file, line, static_cast<DWORD>(length), &written, nullptr);
    CloseHandle(file);
}

#define LOG_INFO(...) logLine("INFO ", __VA_ARGS__)
#define LOG_ERROR(...) logLine("ERROR", __VA_ARGS__)

/* %APPDATA%\cobble, same place the injector watches the log. The banner
 * truncates, so each injection starts a fresh log. */
void openLog() {
    g_epochNanos = nanosNow();
    wchar_t appdata[MAX_PATH] = {};
    if (GetEnvironmentVariableW(L"APPDATA", appdata, MAX_PATH) == 0) {
        return;
    }
    g_directory = std::wstring(appdata) + L"\\cobble";
    CreateDirectoryW(g_directory.c_str(), nullptr);
    g_logPath = g_directory + L"\\cobble.log";

    HANDLE file = CreateFileW(g_logPath.c_str(), GENERIC_WRITE,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return;
    }
    SYSTEMTIME time;
    GetLocalTime(&time);
    char banner[160];
    const int length = std::snprintf(
        banner, sizeof(banner),
        "Cobble Recode log - %04u-%02u-%02u %02u:%02u:%02u\r\n"
        "----------------------------------------------------\r\n",
        time.wYear, time.wMonth, time.wDay, time.wHour, time.wMinute, time.wSecond);
    DWORD written = 0;
    WriteFile(file, banner, static_cast<DWORD>(length), &written, nullptr);
    CloseHandle(file);
}

bool resourceBytes(int id, const jbyte*& data, jsize& size) {
    HRSRC found = FindResourceW(g_module, MAKEINTRESOURCEW(id), RT_RCDATA);
    if (found == nullptr) {
        return false;
    }
    HGLOBAL handle = LoadResource(g_module, found);
    const void* locked = handle != nullptr ? LockResource(handle) : nullptr;
    if (locked == nullptr) {
        return false;
    }
    data = static_cast<const jbyte*>(locked);
    size = static_cast<jsize>(SizeofResource(g_module, found));
    return true;
}

jstring javaString(JNIEnv* env, const std::wstring& value) {
    return env->NewString(reinterpret_cast<const jchar*>(value.c_str()),
                          static_cast<jsize>(value.size()));
}

/* Where the injector put the DLL, and the example scripts beside it. */
std::wstring moduleDirectory() {
    wchar_t path[MAX_PATH] = {};
    const DWORD length = GetModuleFileNameW(g_module, path, MAX_PATH);
    std::wstring directory(path, length);
    return directory.substr(0, directory.find_last_of(L'\\'));
}

std::string javaString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

/* Logs and clears a pending exception. The Java side logs its own failures
 * with stack traces; this only covers what escapes it. */
bool logException(JNIEnv* env, const char* context) {
    if (!env->ExceptionCheck()) {
        return false;
    }
    jthrowable thrown = env->ExceptionOccurred();
    env->ExceptionClear();
    jclass objectClass = env->FindClass("java/lang/Object");
    jmethodID toString = env->GetMethodID(objectClass, "toString", "()Ljava/lang/String;");
    auto text = static_cast<jstring>(env->CallObjectMethod(thrown, toString));
    if (env->ExceptionCheck() || text == nullptr) {
        env->ExceptionClear();
        LOG_ERROR("%s: java exception (unprintable)", context);
    } else {
        LOG_ERROR("%s: %s", context, javaString(env, text).c_str());
    }
    return true;
}

bool acquireVm() {
    const ULONGLONG deadline = GetTickCount64() + kVmTimeoutMillis;
    HMODULE jvm = nullptr;
    while ((jvm = GetModuleHandleW(L"jvm.dll")) == nullptr) {
        if (GetTickCount64() >= deadline) {
            LOG_ERROR("jvm.dll never appeared in the process");
            return false;
        }
        Sleep(50);
    }
    using GetCreatedJavaVMs = jint(JNICALL*)(JavaVM**, jsize, jsize*);
    auto getCreated =
        reinterpret_cast<GetCreatedJavaVMs>(GetProcAddress(jvm, "JNI_GetCreatedJavaVMs"));
    if (getCreated == nullptr) {
        LOG_ERROR("jvm.dll does not export JNI_GetCreatedJavaVMs");
        return false;
    }
    while (true) {
        jsize count = 0;
        if (getCreated(&g_vm, 1, &count) == JNI_OK && count > 0 && g_vm != nullptr) {
            return true;
        }
        if (GetTickCount64() >= deadline) {
            LOG_ERROR("no JavaVM was created before the timeout");
            return false;
        }
        Sleep(50);
    }
}

/* Kept free of C++ objects so SEH can guard it (C2712). A JVMTI acquisition
 * that faults leaves the client dormant instead of taking the game down. */
jint guardedJvmtiEnv(jvmtiEnv** out) {
    __try {
        return g_vm->GetEnv(reinterpret_cast<void**>(out), JVMTI_VERSION_1_2);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        return JNI_ERR;
    }
}

void JNICALL onClassFileLoad(jvmtiEnv* jvmti, JNIEnv* env, jclass, jobject loader,
                             const char* name, jobject, jint length, const unsigned char* data,
                             jint* newLength, unsigned char** newData) {
    if (name == nullptr || t_insideTransform) {
        return;
    }
    AcquireSRWLockShared(&g_targetsLock);
    const bool wanted = g_targets.count(name) != 0;
    ReleaseSRWLockShared(&g_targetsLock);
    if (!wanted) {
        return;
    }

    g_callbacksInFlight.fetch_add(1);
    t_insideTransform = true;
    if (env->PushLocalFrame(8) == JNI_OK) {
        jbyteArray input = env->NewByteArray(length);
        env->SetByteArrayRegion(input, 0, length, reinterpret_cast<const jbyte*>(data));
        auto output = static_cast<jbyteArray>(env->CallStaticObjectMethod(
            g_bootClass, g_transform, loader, env->NewStringUTF(name), input));
        if (!logException(env, name) && output != nullptr) {
            const jsize size = env->GetArrayLength(output);
            unsigned char* buffer = nullptr;
            if (jvmti->Allocate(size, &buffer) == JVMTI_ERROR_NONE) {
                env->GetByteArrayRegion(output, 0, size, reinterpret_cast<jbyte*>(buffer));
                *newLength = size;
                *newData = buffer;
            }
        }
        env->PopLocalFrame(nullptr);
    }
    t_insideTransform = false;
    g_callbacksInFlight.fetch_sub(1);
}

bool acquireJvmti() {
    if (guardedJvmtiEnv(&g_jvmti) != JNI_OK || g_jvmti == nullptr) {
        LOG_ERROR("GetEnv(JVMTI_VERSION_1_2) failed");
        return false;
    }
    jvmtiCapabilities capabilities = {};
    capabilities.can_retransform_classes = 1;
    if (const jvmtiError error = g_jvmti->AddCapabilities(&capabilities);
        error != JVMTI_ERROR_NONE) {
        LOG_ERROR("AddCapabilities(can_retransform_classes) failed: %d", error);
        return false;
    }
    jvmtiEventCallbacks callbacks = {};
    callbacks.ClassFileLoadHook = &onClassFileLoad;
    if (const jvmtiError error = g_jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
        error != JVMTI_ERROR_NONE) {
        LOG_ERROR("SetEventCallbacks failed: %d", error);
        return false;
    }
    return true;
}

/* The game's main class under the names Lunar runs it with: MCP on 1.7 and
 * 1.8, Fabric intermediary (MinecraftClient) from 1.20 on. */
constexpr const char* kMinecraftSignatures[] = {
    "Lnet/minecraft/client/Minecraft;",
    "Lnet/minecraft/class_310;",
};

/* CheatBreaker renames the Minecraft class in every build but keeps vanilla's
 * launch class, and runs the game from the plain class path, which Lunar never
 * does. */
constexpr const char kLaunchSignature[] = "Lnet/minecraft/client/main/Main;";

bool isMinecraftClass(const char* signature) {
    for (const char* candidate : kMinecraftSignatures) {
        if (std::strcmp(signature, candidate) == 0) {
            return true;
        }
    }
    return false;
}

/* A worker thread with no Java frames resolves FindClass against the system
 * loader, which cannot see Lunar's game, so the game's loader is found by
 * signature among everything the VM has loaded. Polls, because the injector
 * may run while the launcher is still starting the game. */
jobject findMinecraftLoader(JNIEnv* env) {
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystem =
        env->GetStaticMethodID(loaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject system = env->CallStaticObjectMethod(loaderClass, getSystem);
    const ULONGLONG deadline = GetTickCount64() + kMinecraftTimeoutMillis;
    while (GetTickCount64() < deadline) {
        jint count = 0;
        jclass* classes = nullptr;
        if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE) {
            return nullptr;
        }
        jobject loader = nullptr;
        for (jint i = 0; i < count; ++i) {
            if (loader == nullptr) {
                char* signature = nullptr;
                if (g_jvmti->GetClassSignature(classes[i], &signature, nullptr) ==
                    JVMTI_ERROR_NONE) {
                    if (isMinecraftClass(signature)) {
                        g_jvmti->GetClassLoader(classes[i], &loader);
                    } else if (std::strcmp(signature, kLaunchSignature) == 0) {
                        jobject launcher = nullptr;
                        g_jvmti->GetClassLoader(classes[i], &launcher);
                        if (env->IsSameObject(launcher, system)) {
                            loader = launcher;
                        } else {
                            env->DeleteLocalRef(launcher);
                        }
                    }
                    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(signature));
                }
            }
            env->DeleteLocalRef(classes[i]);
        }
        g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
        if (loader != nullptr) {
            return loader;
        }
        Sleep(500);
    }
    LOG_ERROR("the Minecraft class was never loaded");
    return nullptr;
}

jclass JNICALL nativeDefineClass(JNIEnv* env, jclass, jobject loader, jstring name,
                                 jbyteArray bytes) {
    const std::string binaryName = javaString(env, name);
    const jsize length = env->GetArrayLength(bytes);
    jbyte* data = env->GetByteArrayElements(bytes, nullptr);
    jclass defined = env->DefineClass(binaryName.c_str(), loader, data, length);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
    return defined;
}

jobjectArray JNICALL nativeLoadedClasses(JNIEnv* env, jclass) {
    jint count = 0;
    jclass* classes = nullptr;
    if (const jvmtiError error = g_jvmti->GetLoadedClasses(&count, &classes);
        error != JVMTI_ERROR_NONE) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      ("GetLoadedClasses failed: " + std::to_string(error)).c_str());
        return nullptr;
    }
    jobjectArray result = env->NewObjectArray(count, env->FindClass("java/lang/Class"), nullptr);
    for (jint i = 0; i < count; ++i) {
        env->SetObjectArrayElement(result, i, classes[i]);
        env->DeleteLocalRef(classes[i]);
    }
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return result;
}

void JNICALL nativeRetransform(JNIEnv* env, jclass, jobjectArray targets) {
    const jsize count = env->GetArrayLength(targets);
    if (count == 0) {
        return;
    }
    std::vector<jclass> classes(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        classes[static_cast<std::size_t>(i)] =
            static_cast<jclass>(env->GetObjectArrayElement(targets, i));
    }
    const jvmtiError error = g_jvmti->RetransformClasses(count, classes.data());
    for (jclass value : classes) {
        env->DeleteLocalRef(value);
    }
    if (error != JVMTI_ERROR_NONE) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      ("RetransformClasses failed: " + std::to_string(error)).c_str());
    }
}

void JNICALL nativeSetTargets(JNIEnv* env, jclass, jobjectArray names) {
    std::unordered_set<std::string> next;
    const jsize count = env->GetArrayLength(names);
    for (jsize i = 0; i < count; ++i) {
        auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        next.insert(javaString(env, name));
        env->DeleteLocalRef(name);
    }
    AcquireSRWLockExclusive(&g_targetsLock);
    g_targets.swap(next);
    ReleaseSRWLockExclusive(&g_targetsLock);
}

void JNICALL nativeRequestUnload(JNIEnv*, jclass) { g_unloadRequested = true; }

jboolean JNICALL nativeStreamProofBegin(JNIEnv*, jclass) {
    return streamproof::begin() ? JNI_TRUE : JNI_FALSE;
}

void JNICALL nativeStreamProofEnd(JNIEnv*, jclass) { streamproof::end(); }

void JNICALL nativeStreamProofPresent(JNIEnv*, jclass) { streamproof::present(); }

void JNICALL nativeStreamProofShutdown(JNIEnv*, jclass) { streamproof::shutdown(); }

/* DPAPI, scoped to the Windows user: what the client keeps on disk that must
 * not be readable by copying the file elsewhere (the Alt Manager's tokens).
 * The entropy only keeps other DPAPI users' blobs and ours apart. */
constexpr char kDpapiEntropy[] = "cobble.recode.dpapi.v1";

jbyteArray dpapi(JNIEnv* env, jbyteArray input, bool protect) {
    const jsize length = env->GetArrayLength(input);
    std::vector<BYTE> plain(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(plain.data()));
    DATA_BLOB in{static_cast<DWORD>(length), plain.data()};
    DATA_BLOB entropy{static_cast<DWORD>(sizeof(kDpapiEntropy) - 1),
                      reinterpret_cast<BYTE*>(const_cast<char*>(kDpapiEntropy))};
    DATA_BLOB out{};
    const BOOL ok = protect
        ? CryptProtectData(&in, L"cobble", &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)
        : CryptUnprotectData(&in, nullptr, &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out);
    SecureZeroMemory(plain.data(), plain.size());
    if (!ok) {
        const DWORD error = GetLastError();
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      (std::string(protect ? "CryptProtectData" : "CryptUnprotectData") +
                       " failed: " + std::to_string(error)).c_str());
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.cbData));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.cbData),
                            reinterpret_cast<const jbyte*>(out.pbData));
    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
    return result;
}

jbyteArray JNICALL nativeProtect(JNIEnv* env, jclass, jbyteArray plain) {
    return dpapi(env, plain, true);
}

jbyteArray JNICALL nativeUnprotect(JNIEnv* env, jclass, jbyteArray sealed) {
    return dpapi(env, sealed, false);
}

const JNINativeMethod kNatives[] = {
    {const_cast<char*>("defineClass"),
     const_cast<char*>("(Ljava/lang/ClassLoader;Ljava/lang/String;[B)Ljava/lang/Class;"),
     reinterpret_cast<void*>(&nativeDefineClass)},
    {const_cast<char*>("loadedClasses"), const_cast<char*>("()[Ljava/lang/Class;"),
     reinterpret_cast<void*>(&nativeLoadedClasses)},
    {const_cast<char*>("retransform"), const_cast<char*>("([Ljava/lang/Class;)V"),
     reinterpret_cast<void*>(&nativeRetransform)},
    {const_cast<char*>("setTargets"), const_cast<char*>("([Ljava/lang/String;)V"),
     reinterpret_cast<void*>(&nativeSetTargets)},
    {const_cast<char*>("requestUnload"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(&nativeRequestUnload)},
    {const_cast<char*>("streamProofBegin"), const_cast<char*>("()Z"),
     reinterpret_cast<void*>(&nativeStreamProofBegin)},
    {const_cast<char*>("streamProofEnd"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(&nativeStreamProofEnd)},
    {const_cast<char*>("streamProofPresent"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(&nativeStreamProofPresent)},
    {const_cast<char*>("streamProofShutdown"), const_cast<char*>("()V"),
     reinterpret_cast<void*>(&nativeStreamProofShutdown)},
    {const_cast<char*>("protect"), const_cast<char*>("([B)[B"),
     reinterpret_cast<void*>(&nativeProtect)},
    {const_cast<char*>("unprotect"), const_cast<char*>("([B)[B"),
     reinterpret_cast<void*>(&nativeUnprotect)},
};

/* MemoryLoader outlives an injection: a class defined into the system loader
 * cannot be removed, so a re-injection finds the previous definition and
 * reuses it. It holds no state of its own beyond each instance. */
jclass memoryLoaderClass(JNIEnv* env) {
    jclass existing = env->FindClass("cobble/boot/MemoryLoader");
    if (existing != nullptr) {
        return existing;
    }
    env->ExceptionClear();

    const jbyte* bytes = nullptr;
    jsize size = 0;
    if (!resourceBytes(kMemoryLoaderResource, bytes, size)) {
        LOG_ERROR("MemoryLoader resource is missing");
        return nullptr;
    }
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID getSystem =
        env->GetStaticMethodID(loaderClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject system = env->CallStaticObjectMethod(loaderClass, getSystem);
    jclass defined = env->DefineClass("cobble/boot/MemoryLoader", system, bytes, size);
    logException(env, "define MemoryLoader");
    return defined;
}

jclass loadThrough(JNIEnv* env, jobject loader, const char* binaryName) {
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClass =
        env->GetMethodID(loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    auto loaded = static_cast<jclass>(
        env->CallObjectMethod(loader, loadClass, env->NewStringUTF(binaryName)));
    if (logException(env, binaryName)) {
        return nullptr;
    }
    return static_cast<jclass>(env->NewGlobalRef(loaded));
}

/* Everything up to Boot.start. Returns false with the reason logged. */
bool start(JNIEnv* env) {
    if (!acquireJvmti()) {
        return false;
    }
    jobject minecraftLoader = findMinecraftLoader(env);
    if (minecraftLoader == nullptr) {
        return false;
    }

    jclass memoryLoader = memoryLoaderClass(env);
    const jbyte* image = nullptr;
    jsize imageSize = 0;
    if (memoryLoader == nullptr || !resourceBytes(kImageResource, image, imageSize)) {
        LOG_ERROR("client image unavailable");
        return false;
    }
    jbyteArray imageArray = env->NewByteArray(imageSize);
    env->SetByteArrayRegion(imageArray, 0, imageSize, image);
    jmethodID construct =
        env->GetMethodID(memoryLoader, "<init>", "(Ljava/lang/ClassLoader;[B)V");
    jobject loader = env->NewObject(memoryLoader, construct, minecraftLoader, imageArray);
    if (logException(env, "MemoryLoader(image)")) {
        return false;
    }

    g_nativeClass = loadThrough(env, loader, "cobble.boot.Native");
    g_bootClass = loadThrough(env, loader, "cobble.boot.Boot");
    if (g_nativeClass == nullptr || g_bootClass == nullptr) {
        return false;
    }
    if (env->RegisterNatives(g_nativeClass, kNatives,
                             static_cast<jint>(sizeof(kNatives) / sizeof(kNatives[0]))) != 0) {
        logException(env, "RegisterNatives");
        return false;
    }
    g_transform = env->GetStaticMethodID(g_bootClass, "transform",
                                         "(Ljava/lang/ClassLoader;Ljava/lang/String;[B)[B");
    jmethodID bootStart =
        env->GetStaticMethodID(g_bootClass, "start", "(Ljava/lang/String;Ljava/lang/String;J)Z");
    if (logException(env, "Boot methods")) {
        return false;
    }
    if (g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,
                                          nullptr) != JVMTI_ERROR_NONE) {
        LOG_ERROR("could not enable ClassFileLoadHook");
        return false;
    }

    const jboolean started = env->CallStaticBooleanMethod(
        g_bootClass, bootStart, javaString(env, g_directory), javaString(env, moduleDirectory()),
        g_epochNanos);
    return !logException(env, "Boot.start") && started == JNI_TRUE;
}

void waitForUnload() {
    ULONGLONG holdSince = 0;
    while (!g_unloadRequested) {
        if ((GetAsyncKeyState(kUnloadKey) & 0x8000) != 0) {
            const ULONGLONG now = GetTickCount64();
            if (holdSince == 0) {
                holdSince = now;
            } else if (now - holdSince >= kUnloadHoldMillis) {
                return;
            }
        } else {
            holdSince = 0;
        }
        Sleep(40);
    }
}

/* Boot.stop has already restored every patched class. What is left is making
 * sure no JVM thread can reach this DLL's code once it unmaps. */
void teardown(JNIEnv* env) {
    streamproof::stopWindow();
    if (g_jvmti != nullptr) {
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,
                                          nullptr);
        /* A thread can be between the target check and the counter; the sleep
         * covers that window, the loop covers slow transforms. */
        Sleep(50);
        while (g_callbacksInFlight.load() != 0) {
            Sleep(10);
        }
    }
    if (g_nativeClass != nullptr) {
        env->UnregisterNatives(g_nativeClass);
        env->DeleteGlobalRef(g_nativeClass);
    }
    if (g_bootClass != nullptr) {
        env->DeleteGlobalRef(g_bootClass);
    }
    if (g_jvmti != nullptr) {
        g_jvmti->DisposeEnvironment();
    }
}

DWORD WINAPI loaderThread(LPVOID) {
    openLog();
    LOG_INFO("bootstrap loaded");
    streamproof::setLogger([](const char* message) { LOG_INFO("stream proof: %s", message); });
    if (!acquireVm()) {
        FreeLibraryAndExitThread(g_module, 1);
    }
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
        LOG_ERROR("AttachCurrentThreadAsDaemon failed");
        FreeLibraryAndExitThread(g_module, 1);
    }

    env->PushLocalFrame(64);
    const bool started = start(env);
    env->PopLocalFrame(nullptr);

    if (started) {
        waitForUnload();
        LOG_INFO("unload requested");
        jmethodID stop = env->GetStaticMethodID(g_bootClass, "stop", "()V");
        env->CallStaticVoidMethod(g_bootClass, stop);
        logException(env, "Boot.stop");
    } else {
        LOG_ERROR("start failed; unloading");
    }

    teardown(env);
    LOG_INFO("unloaded");
    g_vm->DetachCurrentThread();
    FreeLibraryAndExitThread(g_module, 0);
}

}  // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = module;
        DisableThreadLibraryCalls(module);
        /* DllMain runs under the loader lock; everything real happens on a
         * worker thread. */
        if (HANDLE thread = CreateThread(nullptr, 0, &loaderThread, nullptr, 0, nullptr)) {
            CloseHandle(thread);
        }
    }
    return TRUE;
}
