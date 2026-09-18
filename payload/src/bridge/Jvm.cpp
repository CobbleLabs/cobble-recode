#include "bridge/Jvm.hpp"

#include <windows.h>

#include <vector>

#include "core/Log.hpp"

namespace cold::bridge {
namespace {

constexpr std::string_view kScope = "jvm";

using GetCreatedJavaVMs = jint(JNICALL*)(JavaVM**, jsize, jsize*);

HMODULE findJvmModule() {
    if (HMODULE module = GetModuleHandleW(L"jvm.dll")) {
        return module;
    }
    return nullptr;
}

}  // namespace

Jvm& Jvm::instance() {
    static Jvm jvm;
    return jvm;
}

bool Jvm::acquire(unsigned timeoutMillis) {
    const ULONGLONG deadline = GetTickCount64() + timeoutMillis;

    HMODULE module = nullptr;
    while ((module = findJvmModule()) == nullptr) {
        if (GetTickCount64() >= deadline) {
            COLD_ERROR(kScope, "jvm.dll never appeared in the process");
            return false;
        }
        Sleep(25);
    }

    auto getCreatedVms =
        reinterpret_cast<GetCreatedJavaVMs>(GetProcAddress(module, "JNI_GetCreatedJavaVMs"));
    if (getCreatedVms == nullptr) {
        COLD_ERROR(kScope, "jvm.dll does not export JNI_GetCreatedJavaVMs");
        return false;
    }

    while (true) {
        JavaVM* vms[4] = {};
        jsize count = 0;
        if (getCreatedVms(vms, 4, &count) == JNI_OK && count > 0 && vms[0] != nullptr) {
            vm_ = vms[0];
            break;
        }
        if (GetTickCount64() >= deadline) {
            COLD_ERROR(kScope, "JNI_GetCreatedJavaVMs reported no VM before timeout");
            return false;
        }
        Sleep(25);
    }

    COLD_INFO(kScope, "found JavaVM %p", static_cast<void*>(vm_));
    return true;
}

JNIEnv* Jvm::env() {
    if (vm_ == nullptr) {
        return nullptr;
    }
    JNIEnv* env = nullptr;
    const jint status = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (status == JNI_OK && env != nullptr) {
        return env;
    }
    if (vm_->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
        COLD_ERROR(kScope, "AttachCurrentThreadAsDaemon failed on thread %lu",
                   GetCurrentThreadId());
        return nullptr;
    }
    return env;
}

void Jvm::releaseThread() {
    if (vm_ != nullptr) {
        vm_->DetachCurrentThread();
    }
}

jclass Jvm::findClass(const char* binaryName) {
    JNIEnv* jni = env();
    if (jni == nullptr) {
        return nullptr;
    }
    jclass local = jni->FindClass(binaryName);
    if (local == nullptr) {
        jni->ExceptionClear();
        COLD_DEBUG(kScope, "FindClass could not resolve %s", binaryName);
        return nullptr;
    }
    auto global = static_cast<jclass>(jni->NewGlobalRef(local));
    jni->DeleteLocalRef(local);
    return global;
}

jclass Jvm::loadClass(const std::string& binaryName, jobject classLoader) {
    JNIEnv* jni = env();
    if (jni == nullptr) {
        return nullptr;
    }
    LocalFrame frame(jni, 8);

    jclass classClass = jni->FindClass("java/lang/Class");
    if (classClass == nullptr) {
        checkException(jni, "FindClass(java/lang/Class)");
        return nullptr;
    }
    jmethodID forName = jni->GetStaticMethodID(
        classClass, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    if (forName == nullptr) {
        checkException(jni, "Class.forName lookup");
        return nullptr;
    }

    jstring name = toJavaString(jni, binaryName);
    auto resolved = static_cast<jclass>(
        jni->CallStaticObjectMethod(classClass, forName, name, JNI_FALSE, classLoader));
    if (checkException(jni, binaryName.c_str()) || resolved == nullptr) {
        return nullptr;
    }
    return static_cast<jclass>(jni->NewGlobalRef(resolved));
}

LocalFrame::LocalFrame(JNIEnv* env, jint capacity) : env_(env) {
    pushed_ = env_ != nullptr && env_->PushLocalFrame(capacity) == 0;
}

LocalFrame::~LocalFrame() {
    if (pushed_) {
        env_->PopLocalFrame(nullptr);
    }
}

bool checkException(JNIEnv* env, const char* context) {
    if (env == nullptr || env->ExceptionCheck() == JNI_FALSE) {
        return false;
    }
    jthrowable throwable = env->ExceptionOccurred();
    env->ExceptionClear();

    std::string message = "<unavailable>";
    if (throwable != nullptr) {
        jclass throwableClass = env->GetObjectClass(throwable);
        jmethodID toString =
            env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
        if (toString != nullptr) {
            auto text = static_cast<jstring>(env->CallObjectMethod(throwable, toString));
            if (env->ExceptionCheck() == JNI_FALSE && text != nullptr) {
                message = toStdString(env, text);
                env->DeleteLocalRef(text);
            } else {
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(throwableClass);
        env->DeleteLocalRef(throwable);
    }
    COLD_ERROR(kScope, "java exception during %s: %s", context, message.c_str());
    return true;
}

std::string toStdString(JNIEnv* env, jstring value) {
    if (env == nullptr || value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJavaString(JNIEnv* env, const std::string& value) {
    return env == nullptr ? nullptr : env->NewStringUTF(value.c_str());
}

}  // namespace cold::bridge
