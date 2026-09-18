#include "bridge/Mappings.hpp"

#include <cstring>

#include "bridge/Jvm.hpp"
#include "core/Log.hpp"

namespace cold::bridge {
namespace {

constexpr std::string_view kScope = "map";

std::string dotted(const char* slashed) {
    std::string result(slashed);
    for (char& character : result) {
        if (character == '/') character = '.';
    }
    return result;
}

std::string slashed(const std::string& dottedName) {
    std::string result(dottedName);
    for (char& character : result) {
        if (character == '.') character = '/';
    }
    return result;
}

/* ClassLoader.getSystemClassLoader(). Always reachable - java.lang.ClassLoader
 * is a bootstrap class - and the fallback of last resort. */
jobject systemLoader(JNIEnv* env) {
    jclass loaderClass = env->FindClass("java/lang/ClassLoader");
    if (loaderClass == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID getter = env->GetStaticMethodID(loaderClass, "getSystemClassLoader",
                                              "()Ljava/lang/ClassLoader;");
    jobject loader =
        getter != nullptr ? env->CallStaticObjectMethod(loaderClass, getter) : nullptr;
    env->DeleteLocalRef(loaderClass);
    if (checkException(env, "ClassLoader.getSystemClassLoader")) {
        return nullptr;
    }
    return loader;
}

/* Thread.currentThread().getContextClassLoader(). Native threads attached with
 * AttachCurrentThread have none, but the frame thread is a Java thread and the
 * game sets its context loader - which on Lunar is the one that owns the game. */
jobject contextLoader(JNIEnv* env) {
    jclass threadClass = env->FindClass("java/lang/Thread");
    if (threadClass == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    jobject thread = current != nullptr ? env->CallStaticObjectMethod(threadClass, current) : nullptr;
    jmethodID getter = thread != nullptr
                           ? env->GetMethodID(threadClass, "getContextClassLoader",
                                              "()Ljava/lang/ClassLoader;")
                           : nullptr;
    jobject loader = thread != nullptr && getter != nullptr
                         ? env->CallObjectMethod(thread, getter)
                         : nullptr;
    env->DeleteLocalRef(threadClass);
    env->DeleteLocalRef(thread);
    if (checkException(env, "Thread.getContextClassLoader")) {
        return nullptr;
    }
    return loader;
}

}  // namespace

Mappings& Mappings::instance() {
    static Mappings mappings;
    return mappings;
}

jclass Mappings::loadRuntimeClass(JNIEnv* env, const char* binaryName) {
    /* FindClass first: from the frame hook the calling thread runs underneath
     * Java frames, so JNI resolves against the loader that owns the game. */
    jclass direct = env->FindClass(binaryName);
    if (direct != nullptr) {
        jclass global = static_cast<jclass>(env->NewGlobalRef(direct));
        env->DeleteLocalRef(direct);
        return global;
    }
    env->ExceptionClear();
    /* Then the captured game loader, then the thread's context loader, then
     * the system one. The order matters: a launcher shim class can resolve
     * through FindClass while the real game lives elsewhere. */
    const jobject candidates[] = {gameClassLoader_, contextLoader(env), systemLoader(env)};
    for (const jobject loader : candidates) {
        if (loader == nullptr) {
            continue;
        }
        jclass loaded = Jvm::instance().loadClass(dotted(binaryName), loader);
        if (loaded != nullptr) {
            return loaded;
        }
    }
    return nullptr;
}

bool Mappings::resolve(JNIEnv* env) {
    log::Stage stage(kScope, "resolve 1.8.9 runtime names");

    /* The capture order: FindClass (the frame thread's own loader), then the
     * thread context loader, then the system loader. The first loader that
     * actually holds net.minecraft.client.Minecraft is the game loader, and
     * everything else in the table loads through it. */
    const auto& anchor = kClasses[static_cast<std::size_t>(MappedClass::MinecraftClient)];
    const jobject loaderCandidates[] = {nullptr, contextLoader(env), systemLoader(env)};
    jclass anchorClass = nullptr;
    for (const jobject loader : loaderCandidates) {
        if (loader == nullptr) {
            /* FindClass first - the frame thread runs under Java frames, so
             * this resolves against whichever loader is closest. */
            anchorClass = Jvm::instance().findClass(slashed(anchor.runtime).c_str());
            if (anchorClass != nullptr) {
                COLD_INFO(kScope, "anchor %s resolved via FindClass", anchor.runtime);
                break;
            }
            continue;
        }
        anchorClass = Jvm::instance().loadClass(dotted(anchor.runtime), loader);
        if (anchorClass != nullptr) {
            gameClassLoader_ = env->NewGlobalRef(loader);
            COLD_INFO(kScope, "anchor %s resolved via %s loader", anchor.runtime,
                      loader == loaderCandidates[2] ? "system" : "context");
            break;
        }
    }
    if (anchorClass == nullptr) {
        stage.fail("Minecraft (net.minecraft.client.Minecraft) is not visible yet");
        return false;
    }

    /* When FindClass won, capture the anchor's own loader explicitly. */
    if (gameClassLoader_ == nullptr) {
        jclass classClass = env->GetObjectClass(anchorClass);
        jmethodID getClassLoader =
            env->GetMethodID(classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject loader = env->CallObjectMethod(anchorClass, getClassLoader);
        if (checkException(env, "Class.getClassLoader") || loader == nullptr) {
            stage.fail("Minecraft has no class loader");
            return false;
        }
        gameClassLoader_ = env->NewGlobalRef(loader);
        env->DeleteLocalRef(classClass);
        env->DeleteLocalRef(loader);
    }

    /* Shim check. Launchers that re-implement entry points keep a real
     * Minecraft class somewhere else; the tell is a missing theWorld field,
     * which every 1.8.x runtime carries on the instance (1.8.9 names its
     * texture manager renderEngine, so that field must not be the probe). */
    {
        jfieldID theWorld = env->GetFieldID(anchorClass, "theWorld", "Lnet/minecraft/client/multiplayer/WorldClient;");
        if (theWorld == nullptr) {
            env->ExceptionClear();
            COLD_WARN(kScope,
                      "anchor %s has no theWorld field - it is a launcher shim; "
                      "the real class will come from the live instance",
                      anchor.runtime);
        }
    }

    std::size_t resolvedClasses = resolveClasses(env);
    resolveMembers(env);

    COLD_INFO(kScope, "resolved %zu/%zu classes, %zu/%zu methods, %zu/%zu fields", resolvedClasses,
              kClassCount, resolvedMethods_, requiredMethods_, resolvedFields_, requiredFields_);

    const bool complete = resolvedClasses == kClassCount && resolvedMethods_ == requiredMethods_ &&
                          resolvedFields_ == requiredFields_;
    if (!complete) {
        COLD_WARN(kScope, "running with an incomplete mapping table; affected features degrade");
    }
    return true;
}

std::size_t Mappings::resolveClasses(JNIEnv* env) {
    std::size_t resolvedClasses = 0;
    for (std::size_t index = 0; index < kClassCount; ++index) {
        if (classes_[index] != nullptr) {
            ++resolvedClasses;
            continue;
        }
        const ClassEntry& entry = kClasses[index];
        jclass resolved = loadRuntimeClass(env, entry.runtime);
        if (resolved == nullptr) {
            /* 1.8.9 has no classes for post-1.8 concepts; those ids stay null
             * and every user null-checks. An unexpected miss is still an error
             * so a real mapping break is not lost in the noise. */
            COLD_ERROR(kScope, "class %s (%s) unresolved", entry.readable, entry.runtime);
            continue;
        }
        classes_[index] = resolved;
        ++resolvedClasses;
    }
    return resolvedClasses;
}

/* Ground truth for a failed member: ask the live class what it declares with
 * the same fragment. The generated list is walked once per failure, only at
 * resolve time, so a name mismatch announces its own correction. */
void dumpDeclared(JNIEnv* env, jclass owner, const MemberEntry& failed) {
    LocalFrame frame(env, 64);
    jclass classClass = env->FindClass("java/lang/Class");
    if (classClass == nullptr) {
        env->ExceptionClear();
        return;
    }
    const bool isMethod = failed.descriptor[0] == '(';
    jmethodID declared = nullptr;
    if (isMethod) {
        declared = env->GetMethodID(classClass, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    } else {
        declared = env->GetMethodID(classClass, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
    }
    if (declared == nullptr) {
        env->ExceptionClear();
        return;
    }

    auto methodClass = static_cast<jclass>(env->FindClass("java/lang/reflect/Method"));
    auto fieldClass = static_cast<jclass>(env->FindClass("java/lang/reflect/Field"));
    if (methodClass == nullptr || fieldClass == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID getName = isMethod ? env->GetMethodID(methodClass, "getName", "()Ljava/lang/String;")
                                 : env->GetMethodID(fieldClass, "getName", "()Ljava/lang/String;");
    jmethodID getDescriptor =
        isMethod ? env->GetMethodID(methodClass, "toGenericString", "()Ljava/lang/String;")
                 : env->GetMethodID(fieldClass, "toGenericString", "()Ljava/lang/String;");
    if (getName == nullptr || getDescriptor == nullptr) {
        env->ExceptionClear();
        return;
    }

    auto array = static_cast<jobjectArray>(env->CallObjectMethod(owner, declared));
    if (checkException(env, "getDeclared members") || array == nullptr) {
        return;
    }
    const jsize count = env->GetArrayLength(array);
    std::string wanted = failed.readable;
    /* Lowercase the wanted name so the match ignores the Yarn/MCP casing. */
    for (char& c : wanted) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
    std::size_t shown = 0;
    for (jsize index = 0; index < count && shown < 6; ++index) {
        jobject member = env->GetObjectArrayElement(array, index);
        auto name = static_cast<jstring>(env->CallObjectMethod(member, getName));
        if (checkException(env, "member name") || name == nullptr) {
            env->DeleteLocalRef(member);
            continue;
        }
        const std::string actual = toStdString(env, name);
        env->DeleteLocalRef(name);
        std::string lowered = actual;
        for (char& c : lowered) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
        /* Case-insensitive match, or every member when the name is not even
         * close - the dump then shows the shape of the class. */
        const bool fuzzy = lowered.find(wanted) != std::string::npos ||
                           wanted.find(lowered) != std::string::npos;
        if (!fuzzy && count <= 24) {
            /* Small classes: dump everything, the name is likely wholly wrong. */
        } else if (!fuzzy) {
            env->DeleteLocalRef(member);
            continue;
        }
        auto generic = static_cast<jstring>(env->CallObjectMethod(member, getDescriptor));
        const std::string text = generic != nullptr ? toStdString(env, generic) : actual;
        env->DeleteLocalRef(generic);
        COLD_INFO(kScope, "  %s declares: %s", failed.readable, text.c_str());
        ++shown;
        env->DeleteLocalRef(member);
    }
    env->DeleteLocalRef(array);
    env->DeleteLocalRef(methodClass);
    env->DeleteLocalRef(fieldClass);
}

void Mappings::resolveMembers(JNIEnv* env) {
    resolvedMethods_ = 0;
    resolvedFields_ = 0;
    requiredMethods_ = 0;
    requiredFields_ = 0;
    for (std::size_t index = 0; index < kMethodCount; ++index) {
        const MemberEntry& entry = kMethods[index];
        if (!entry.optional) ++requiredMethods_;
        jclass owner = classes_[static_cast<std::size_t>(entry.owner)];
        if (owner == nullptr) {
            continue;
        }
        jmethodID id =
            entry.isStatic
                ? env->GetStaticMethodID(owner, entry.runtime, entry.descriptor)
                : env->GetMethodID(owner, entry.runtime, entry.descriptor);
        if (id == nullptr) {
            env->ExceptionClear();
            if (entry.optional) {
                COLD_DEBUG(kScope, "optional method %s.%s absent on this version",
                           kClasses[static_cast<std::size_t>(entry.owner)].readable,
                           entry.readable);
            } else {
                COLD_ERROR(kScope, "method %s.%s%s unresolved",
                           kClasses[static_cast<std::size_t>(entry.owner)].readable,
                           entry.readable, entry.descriptor);
                dumpDeclared(env, owner, entry);
            }
            continue;
        }
        methods_[index] = id;
        if (!entry.optional) ++resolvedMethods_;
    }

    for (std::size_t index = 0; index < kFieldCount; ++index) {
        const MemberEntry& entry = kFields[index];
        if (!entry.optional) ++requiredFields_;
        jclass owner = classes_[static_cast<std::size_t>(entry.owner)];
        if (owner == nullptr) {
            continue;
        }
        jfieldID id =
            entry.isStatic
                ? env->GetStaticFieldID(owner, entry.runtime, entry.descriptor)
                : env->GetFieldID(owner, entry.runtime, entry.descriptor);
        if (id == nullptr) {
            env->ExceptionClear();
            if (entry.optional) {
                COLD_DEBUG(kScope, "optional field %s.%s absent on this version",
                           kClasses[static_cast<std::size_t>(entry.owner)].readable,
                           entry.readable);
            } else {
                COLD_ERROR(kScope, "field %s.%s %s unresolved",
                           kClasses[static_cast<std::size_t>(entry.owner)].readable,
                           entry.readable, entry.descriptor);
                dumpDeclared(env, owner, entry);
            }
            continue;
        }
        fields_[index] = id;
        if (!entry.optional) ++resolvedFields_;
    }
}

void Mappings::rebind(JNIEnv* env, jclass runtime) {
    if (runtime == nullptr || rebound_) {
        return;
    }
    rebound_ = true;

    /* The live instance's class is the truth. Put it in place, take its
     * loader as the game loader, and run the whole table again. */
    if (classes_[static_cast<std::size_t>(MappedClass::MinecraftClient)] != nullptr) {
        env->DeleteGlobalRef(classes_[static_cast<std::size_t>(MappedClass::MinecraftClient)]);
    }
    classes_[static_cast<std::size_t>(MappedClass::MinecraftClient)] =
        static_cast<jclass>(env->NewGlobalRef(runtime));

    {
        jclass classClass = env->GetObjectClass(runtime);
        jmethodID getClassLoader =
            env->GetMethodID(classClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject loader = env->CallObjectMethod(runtime, getClassLoader);
        if (!checkException(env, "rebind Class.getClassLoader") && loader != nullptr) {
            if (gameClassLoader_ != nullptr) {
                env->DeleteGlobalRef(gameClassLoader_);
            }
            gameClassLoader_ = env->NewGlobalRef(loader);
        }
        env->DeleteLocalRef(classClass);
        env->DeleteLocalRef(loader);
    }

    /* Members were bound against the shim; drop them all before re-resolving,
     * a stale jmethodID against the wrong class is worse than a null one. */
    methods_.fill(nullptr);
    fields_.fill(nullptr);

    log::Stage stage(kScope, "rebind against the live Minecraft class");
    const std::size_t resolvedClasses = resolveClasses(env);
    resolveMembers(env);

    COLD_INFO(kScope, "rebind resolved %zu/%zu classes, %zu/%zu methods, %zu/%zu fields",
              resolvedClasses, kClassCount, resolvedMethods_, requiredMethods_, resolvedFields_,
              requiredFields_);
    if (resolvedClasses < kClassCount || resolvedMethods_ != requiredMethods_ ||
        resolvedFields_ != requiredFields_) {
        COLD_WARN(kScope, "rebind still incomplete; affected features degrade");
        stage.fail("incomplete after rebind");
    }
}

}  // namespace cold::bridge
