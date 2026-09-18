#pragma once

#include <jni.h>

#include <array>
#include <string>

#include "bridge/Mappings.generated.hpp"

namespace cold::bridge {

/* Resolves the generated table into live JNI handles against Lunar 1.8.9.
 *
 * Lunar ships the 1.8.9 jar deobfuscated to MCP field and method names, so
 * every entry in the generated table is spelled exactly as the runtime
 * expects it. No resolver, no namespace translation: resolution is FindClass
 * plus GetMethodID/GetFieldID, and anything marked optional is allowed to
 * come back null and degrade the feature that wanted it. */
class Mappings {
public:
    static Mappings& instance();

    bool resolve(JNIEnv* env);

    jclass classOf(MappedClass id) const { return classes_[static_cast<std::size_t>(id)]; }
    jmethodID method(MappedMethod id) const { return methods_[static_cast<std::size_t>(id)]; }
    jfieldID field(MappedField id) const { return fields_[static_cast<std::size_t>(id)]; }

    jobject gameClassLoader() const { return gameClassLoader_; }

    /* Exposed for the few runtime lookups that are not worth a table entry. */
    jclass loadRuntimeClass(JNIEnv* env, const char* binaryName);

    /* Re-resolves every class, method and field against `runtime` — the class
     * of a live Minecraft instance. Called from the frame hook once the first
     * instance is in hand, for launchers where the anchor resolves to a shim
     * and only a live instance proves where the real game lives. */
    void rebind(JNIEnv* env, jclass runtime);

private:
    Mappings() = default;

    std::size_t resolveClasses(JNIEnv* env);
    void resolveMembers(JNIEnv* env);

    jobject gameClassLoader_ = nullptr;
    bool rebound_ = false;
    std::size_t resolvedMethods_ = 0;
    std::size_t requiredMethods_ = 0;
    std::size_t resolvedFields_ = 0;
    std::size_t requiredFields_ = 0;

    std::array<jclass, kClassCount> classes_ = {};
    std::array<jmethodID, kMethodCount> methods_ = {};
    std::array<jfieldID, kFieldCount> fields_ = {};
};

inline jclass mcClass(MappedClass id) { return Mappings::instance().classOf(id); }
inline jmethodID mcMethod(MappedMethod id) { return Mappings::instance().method(id); }
inline jfieldID mcField(MappedField id) { return Mappings::instance().field(id); }

}  // namespace cold::bridge
