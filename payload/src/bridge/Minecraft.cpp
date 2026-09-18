#include "bridge/Minecraft.hpp"

#include <cmath>
#include <cstring>

#include <jni.h>

#include "bridge/Jvm.hpp"
#include "bridge/Mappings.hpp"
#include "core/Log.hpp"
#include "render/Gl.hpp"

namespace cold::game {
namespace {

using bridge::checkException;
using bridge::MappedClass;
using bridge::MappedField;
using bridge::MappedMethod;
using bridge::mcClass;
using bridge::mcField;
using bridge::mcMethod;

constexpr std::string_view kScope = "mc";

/* Upper bound on entities captured per frame. Each one costs a JNI local
 * reference held for the whole frame plus a dozen calls, and nothing the client
 * draws is legible past a few hundred anyway. */
constexpr std::size_t kMaxCapturedEntities = 320;
/* Squared distance beyond which an entity is skipped before any of the
 * expensive reads happen. 256 blocks is past any sane render distance. */
constexpr double kMaxCaptureDistanceSquared = 256.0 * 256.0;
/* Vanilla eye height for a standing player; 1.8.9's per-entity accessor is a
 * protected float constant rather than a getter, so this is what the capture
 * assumes unless the bounding box says otherwise. */
constexpr double kDefaultEyeHeight = 1.62;

jobject objectField(JNIEnv* env, jobject owner, MappedField field) {
    jfieldID id = mcField(field);
    return (id == nullptr || owner == nullptr) ? nullptr : env->GetObjectField(owner, id);
}

float floatField(JNIEnv* env, jobject owner, MappedField field, float fallback = 0.0f) {
    jfieldID id = mcField(field);
    return (id == nullptr || owner == nullptr) ? fallback : env->GetFloatField(owner, id);
}

double doubleField(JNIEnv* env, jobject owner, MappedField field, double fallback = 0.0) {
    jfieldID id = mcField(field);
    return (id == nullptr || owner == nullptr) ? fallback : env->GetDoubleField(owner, id);
}

jint intField(JNIEnv* env, jobject owner, MappedField field, jint fallback = 0) {
    jfieldID id = mcField(field);
    return (id == nullptr || owner == nullptr) ? fallback : env->GetIntField(owner, id);
}

/* Static-field reads must go through the static JNI path; passing a static
 * fieldID to GetIntField is undefined and faults - which is exactly what the
 * first-frame crash was. */
jint staticIntField(JNIEnv* env, MappedClass owner, MappedField field, jint fallback = 0) {
    jclass klass = mcClass(owner);
    jfieldID id = mcField(field);
    return (klass == nullptr || id == nullptr) ? fallback
                                               : env->GetStaticIntField(klass, id);
}

bool boolField(JNIEnv* env, jobject owner, MappedField field, bool fallback = false) {
    jfieldID id = mcField(field);
    return (id == nullptr || owner == nullptr) ? fallback
                                               : env->GetBooleanField(owner, id) == JNI_TRUE;
}

jobject staticObjectField(JNIEnv* env, MappedClass owner, MappedField field) {
    jclass klass = mcClass(owner);
    jfieldID id = mcField(field);
    return (klass == nullptr || id == nullptr) ? nullptr : env->GetStaticObjectField(klass, id);
}

float callFloat(JNIEnv* env, jobject owner, MappedMethod method, float fallback = 0.0f) {
    jmethodID id = mcMethod(method);
    if (id == nullptr || owner == nullptr) return fallback;
    const float value = env->CallFloatMethod(owner, id);
    if (checkException(env, "callFloat")) return fallback;
    return value;
}

double callDouble(JNIEnv* env, jobject owner, MappedMethod method, double fallback = 0.0) {
    jmethodID id = mcMethod(method);
    if (id == nullptr || owner == nullptr) return fallback;
    const double value = env->CallDoubleMethod(owner, id);
    if (checkException(env, "callDouble")) return fallback;
    return value;
}

jlong callLong(JNIEnv* env, jobject owner, MappedMethod method, jlong fallback = 0) {
    jmethodID id = mcMethod(method);
    if (owner == nullptr || id == nullptr) return fallback;
    const jlong value = env->CallLongMethod(owner, id);
    if (checkException(env, "callLong")) return fallback;
    return value;
}

jint callInt(JNIEnv* env, jobject owner, MappedMethod method, jint fallback = 0) {
    jmethodID id = mcMethod(method);
    if (id == nullptr || owner == nullptr) return fallback;
    const jint value = env->CallIntMethod(owner, id);
    if (checkException(env, "callInt")) return fallback;
    return value;
}

bool callBool(JNIEnv* env, jobject owner, MappedMethod method, bool fallback = false) {
    jmethodID id = mcMethod(method);
    if (id == nullptr || owner == nullptr) return fallback;
    const bool value = env->CallBooleanMethod(owner, id) == JNI_TRUE;
    if (checkException(env, "callBool")) return fallback;
    return value;
}

jobject callObjectInt(JNIEnv* env, jobject owner, MappedMethod method, jint argument) {
    jmethodID id = mcMethod(method);
    if (id == nullptr || owner == nullptr) return nullptr;
    jobject value = env->CallObjectMethod(owner, id, argument);
    if (checkException(env, "callObjectInt")) return nullptr;
    return value;
}

jobject callObject(JNIEnv* env, jobject owner, MappedMethod method) {
    jmethodID id = mcMethod(method);
    if (id == nullptr || owner == nullptr) return nullptr;
    jobject value = env->CallObjectMethod(owner, id);
    if (checkException(env, "callObject")) return nullptr;
    return value;
}

Vec3 readVec3(JNIEnv* env, jobject vec) {
    if (vec == nullptr) return {};
    return {doubleField(env, vec, MappedField::Vec3d_x), doubleField(env, vec, MappedField::Vec3d_y),
            doubleField(env, vec, MappedField::Vec3d_z)};
}

/* One entity position straight off its fields. */
Vec3 entityPos(JNIEnv* env, jobject entity) {
    return {doubleField(env, entity, MappedField::Entity_posX),
            doubleField(env, entity, MappedField::Entity_posY),
            doubleField(env, entity, MappedField::Entity_posZ)};
}

Vec3 entityPrevPos(JNIEnv* env, jobject entity) {
    return {doubleField(env, entity, MappedField::Entity_prevPosX),
            doubleField(env, entity, MappedField::Entity_prevPosY),
            doubleField(env, entity, MappedField::Entity_prevPosZ)};
}

Vec3 entityVelocity(JNIEnv* env, jobject entity) {
    return {doubleField(env, entity, MappedField::Entity_motionX),
            doubleField(env, entity, MappedField::Entity_motionY),
            doubleField(env, entity, MappedField::Entity_motionZ)};
}

/* Interpolated feet position, the same lerp the renderer does. 1.8.9 has no
 * lerped accessor; the world pass interpolates lastTick -> current by the
 * frame's partial tick, which is exactly what this reproduces. */
Vec3 lerpedPos(JNIEnv* env, jobject entity, float tickProgress) {
    const Vec3 current = entityPos(env, entity);
    const Vec3 previous = entityPrevPos(env, entity);
    return previous + (current - previous) * static_cast<double>(tickProgress);
}

/* Copies a 1.8.9 ActiveRenderInfo matrix into a Mat4. MODELVIEW and
 * PROJECTION are static java.nio.FloatBuffers, not arrays (the live dump
 * confirmed the NIO type), so the copy goes through the direct-buffer
 * address - which is what JNI reads without any per-element round trip. */
bool readMatrix(JNIEnv* env, jobject buffer, Mat4& out) {
    if (buffer == nullptr) {
        return false;
    }
    const auto* data =
        static_cast<const jfloat*>(env->GetDirectBufferAddress(buffer));
    if (checkException(env, "GetDirectBufferAddress") || data == nullptr) {
        return false;
    }
    for (int i = 0; i < 16; ++i) {
        out.m[i] = data[i];
    }
    return true;
}

}  // namespace

Game& Game::instance() {
    static Game game;
    return game;
}

bool Game::initialise(JNIEnv* env) {
    log::Stage stage(kScope, "bind game bridge");
    auto& mappings = bridge::Mappings::instance();

    /* 1.8.9 exposes the client as the static field `theMinecraft`. */
    jclass clientClass = mcClass(MappedClass::MinecraftClient);
    jfieldID theMinecraft = mcField(MappedField::MinecraftClient_theMinecraft);
    if (clientClass == nullptr || theMinecraft == nullptr) {
        stage.fail("Minecraft.theMinecraft is unmapped");
        return false;
    }
    jobject instance = env->GetStaticObjectField(clientClass, theMinecraft);
    if (checkException(env, "Minecraft.theMinecraft") || instance == nullptr) {
        stage.fail("Minecraft.theMinecraft returned null");
        return false;
    }
    /* Shim repair. If the first resolve only ever found a launcher shim, the
     * static still read because the shim mirrors it - but the instance's real
     * class is the game's own Minecraft, and the live GetObjectClass is the
     * definitive answer no loader reasoning can get wrong. */
    const jclass runtimeClass = env->GetObjectClass(instance);
    const jclass tableClass = mcClass(MappedClass::MinecraftClient);
    if (runtimeClass != nullptr && tableClass != nullptr &&
        !env->IsSameObject(runtimeClass, tableClass)) {
        COLD_INFO(kScope, "live instance class differs from the resolved anchor; rebinding the table");
        mappings.rebind(env, runtimeClass);
        clientInstance_ = nullptr;  // rebuilt below, now against the real table
    }
    if (runtimeClass != nullptr) {
        env->DeleteLocalRef(runtimeClass);
    }
    if (clientInstance_ == nullptr) {
        jclass bound = mcClass(MappedClass::MinecraftClient);
        jfieldID field = mcField(MappedField::MinecraftClient_theMinecraft);
        if (bound != nullptr && field != nullptr) {
            jobject fresh = env->GetStaticObjectField(bound, field);
            if (!checkException(env, "Minecraft.theMinecraft after rebind") && fresh != nullptr) {
                clientInstance_ = env->NewGlobalRef(fresh);
                env->DeleteLocalRef(fresh);
            }
        }
        if (clientInstance_ == nullptr) {
            stage.fail("Minecraft.theMinecraft unreachable after rebind");
            return false;
        }
    } else {
        clientInstance_ = env->NewGlobalRef(instance);
        env->DeleteLocalRef(instance);
    }

    /* java.util plumbing the capture shares with every list walk. */
    iterableClass_ = mappings.loadRuntimeClass(env, "java/lang/Iterable");
    iteratorClass_ = mappings.loadRuntimeClass(env, "java/util/Iterator");
    if (iterableClass_ != nullptr) {
        iterableIterator_ = env->GetMethodID(iterableClass_, "iterator", "()Ljava/util/Iterator;");
    }
    if (iteratorClass_ != nullptr) {
        iteratorHasNext_ = env->GetMethodID(iteratorClass_, "hasNext", "()Z");
        iteratorNext_ = env->GetMethodID(iteratorClass_, "next", "()Ljava/lang/Object;");
    }

    numberClass_ = mappings.loadRuntimeClass(env, "java/lang/Number");
    if (numberClass_ != nullptr) {
        numberDoubleValue_ = env->GetMethodID(numberClass_, "doubleValue", "()D");
    }
    booleanClass_ = mappings.loadRuntimeClass(env, "java/lang/Boolean");
    if (booleanClass_ != nullptr) {
        booleanValue_ = env->GetMethodID(booleanClass_, "booleanValue", "()Z");
    }

    if (iteratorNext_ == nullptr) {
        stage.fail("java.util plumbing unresolved");
        return false;
    }

    ready_ = true;
    return true;
}

bool Game::captureWindow(JNIEnv* env, jobject client, FrameState& state) {
    /* The framebuffer is LWJGL Display's; the scaled sizes come from a fresh
     * ScaledResolution, which is exactly what GuiIngame constructs per frame. */
    jclass displayClass = mcClass(MappedClass::Display);
    jmethodID displayWidth = mcMethod(MappedMethod::Display_getWidth);
    jmethodID displayHeight = mcMethod(MappedMethod::Display_getHeight);
    if (displayClass != nullptr && displayWidth != nullptr && displayHeight != nullptr) {
        state.windowWidth = env->CallStaticIntMethod(displayClass, displayWidth);
        state.windowHeight = env->CallStaticIntMethod(displayClass, displayHeight);
        if (checkException(env, "Display size")) {
            return false;
        }
    }

    /* Logical size equals the framebuffer here: LWJGL 2 has no independent
     * content scale, so the space the cursor reports in is the space pixels
     * land in. */
    state.logicalWidth = state.windowWidth;
    state.logicalHeight = state.windowHeight;

    jclass scaledClass = mcClass(MappedClass::Window);
    jmethodID scaledCtor = mcMethod(MappedMethod::Window_ctor);
    if (scaledClass != nullptr && scaledCtor != nullptr) {
        jobject scaled = env->NewObject(scaledClass, scaledCtor, client);
        if (!checkException(env, "ScaledResolution") && scaled != nullptr) {
            state.scaledWidth =
                callInt(env, scaled, MappedMethod::Window_getScaledWidth);
            state.scaledHeight =
                callInt(env, scaled, MappedMethod::Window_getScaledHeight);
            env->DeleteLocalRef(scaled);
        } else if (scaled != nullptr) {
            env->DeleteLocalRef(scaled);
        }
    }
    /* 1.8.9's scale factor is an integer (2 normal, 1 "large"); the overlay's
     * pixel unit follows from the scaled width. */
    state.scaleFactor = state.windowWidth > 0 && state.scaledWidth > 0
                            ? state.windowWidth / state.scaledWidth
                            : 2;

    jobject options = objectField(env, client, MappedField::MinecraftClient_options);
    if (options != nullptr) {
        state.mouseSensitivity =
            static_cast<double>(floatField(env, options, MappedField::GameOptions_mouseSensitivity, 0.5f));
        env->DeleteLocalRef(options);
    }
    return state.windowWidth > 0 && state.windowHeight > 0;
}

bool Game::captureMatrices(JNIEnv* env, jobject client, FrameState& state) {
    /* The world pass has already drawn when the swap hook fires, so
     * ActiveRenderInfo still holds exactly the projection and modelview the
     * frame presented - bob and hurt tilt already folded in, nothing to
     * reproduce. The arrays are statics on the class this id resolves to. */
    jclass infoClass = mcClass(MappedClass::Camera);
    jfieldID projectionField = mcField(MappedField::Camera_PROJECTION);
    jfieldID modelviewField = mcField(MappedField::Camera_MODELVIEW);
    if (infoClass == nullptr || projectionField == nullptr || modelviewField == nullptr) {
        return false;
    }

    jobject projectionBuffer = env->GetStaticObjectField(infoClass, projectionField);
    jobject modelviewBuffer = env->GetStaticObjectField(infoClass, modelviewField);
    const bool projectionOk = readMatrix(env, projectionBuffer, state.projection);
    const bool viewOk = readMatrix(env, modelviewBuffer, state.view);
    if (projectionBuffer != nullptr) env->DeleteLocalRef(projectionBuffer);
    if (modelviewBuffer != nullptr) env->DeleteLocalRef(modelviewBuffer);
    if (!projectionOk || !viewOk) {
        return false;
    }

    /* The camera is derived, not read: the view matrix is orthonormal, so the
     * eye position comes off its last column and the yaw/pitch off its basis.
     * World-space overlays only need the position; the projection carries the
     * rest. */
    state.camera.pos = {state.view.at(3, 0), state.view.at(3, 1), state.view.at(3, 2)};

    /* Tick fraction: Timer.renderPartialTicks is how far through the current
     * tick the presented frame was. */
    jobject timer = objectField(env, client, MappedField::MinecraftClient_timer);
    if (timer != nullptr) {
        state.tickProgress = floatField(env, timer, MappedField::RenderTickCounter_renderPartialTicks);
        env->DeleteLocalRef(timer);
    }

    /* Yaw and pitch straight off the render view entity, which is what the
     * 1.8.9 camera follows. */
    jobject viewEntity = callObject(env, client, MappedMethod::MinecraftClient_getCameraEntity);
    if (viewEntity != nullptr) {
        state.camera.yaw = floatField(env, viewEntity, MappedField::Entity_yaw);
        state.camera.pitch = floatField(env, viewEntity, MappedField::Entity_pitch);
        env->DeleteLocalRef(viewEntity);
    }
    state.camera.thirdPerson = false;

    return true;
}

int Game::capturePing(JNIEnv* env, jobject client, jobject player) {
    jobject handler = callObject(env, client, MappedMethod::MinecraftClient_getNetworkHandler);
    if (handler == nullptr) {
        return -1;
    }
    jobject uuid = objectField(env, player, MappedField::Entity_uuid);
    int latency = -1;
    if (uuid != nullptr) {
        if (jmethodID id = mcMethod(MappedMethod::ClientPlayNetworkHandler_getPlayerListEntry)) {
            jobject entry = env->CallObjectMethod(handler, id, uuid);
            if (!checkException(env, "getPlayerInfo") && entry != nullptr) {
                latency = callInt(env, entry, MappedMethod::PlayerListEntry_getLatency, -1);
                env->DeleteLocalRef(entry);
            }
        }
        env->DeleteLocalRef(uuid);
    }
    env->DeleteLocalRef(handler);
    return latency;
}

void Game::capturePlayer(JNIEnv* env, jobject client, jobject player, FrameState& state) {
    LocalPlayerView& view = state.player;
    view.valid = true;
    view.handle = player;

    view.pos = entityPos(env, player);
    const Vec3 previous = entityPrevPos(env, player);
    const Vec3 interpolated = previous + (view.pos - previous) * static_cast<double>(state.tickProgress);
    view.eyePos = {interpolated.x, interpolated.y + kDefaultEyeHeight, interpolated.z};
    view.eyeHeight = static_cast<float>(kDefaultEyeHeight);

    view.velocity = entityVelocity(env, player);

    view.yaw = floatField(env, player, MappedField::Entity_yaw);
    view.pitch = floatField(env, player, MappedField::Entity_pitch);
    view.lastYaw = floatField(env, player, MappedField::Entity_lastYaw, view.yaw);
    view.lastPitch = floatField(env, player, MappedField::Entity_lastPitch, view.pitch);

    view.health = callFloat(env, player, MappedMethod::LivingEntity_getHealth);
    view.maxHealth = callFloat(env, player, MappedMethod::LivingEntity_getMaxHealth, 20.0f);
    view.armor = callInt(env, player, MappedMethod::LivingEntity_getArmor);
    view.onGround = boolField(env, player, MappedField::Entity_onGround);
    view.sneaking = callBool(env, player, MappedMethod::Entity_isSneaking);
    view.sprinting = callBool(env, player, MappedMethod::Entity_isSprinting);
    /* 1.8.9 keeps the jump flag on the movement input, not on the player. */
    view.jumping = false;
    {
        jobject input = objectField(env, player, MappedField::ClientPlayerEntity_input);
        if (input != nullptr) {
            view.jumping = boolField(env, input, MappedField::LivingEntity_jumping);
            env->DeleteLocalRef(input);
        }
    }
    view.fallDistance = static_cast<double>(floatField(env, player, MappedField::Entity_fallDistance));
    view.usingItem = callBool(env, player, MappedMethod::LivingEntity_isUsingItem);
    view.itemUseTicks =
        view.usingItem ? callInt(env, player, MappedMethod::LivingEntity_getItemUseTime) : 0;
    view.hurtTime = intField(env, player, MappedField::LivingEntity_hurtTime);
    view.maxHurtTime = intField(env, player, MappedField::LivingEntity_maxHurtTime, 10);
    view.age = intField(env, player, MappedField::Entity_age);

    /* 1.8.9 has no attack cooldown; the meter is always full. */
    view.attackCooldown = 1.0f;
    view.limitedAttackSpeed = false;

    state.ping = capturePing(env, client, player);
}

void Game::captureEntities(JNIEnv* env, jobject world, FrameState& state) {
    /* 1.8.9's entity set is the World.loadedEntityList the client itself
     * renders from - there is no separate iterable accessor. */
    jobject list = objectField(env, world, MappedField::World_loadedEntityList);
    if (list == nullptr) {
        return;
    }
    jmethodID listIterator = env->GetMethodID(env->GetObjectClass(list), "iterator",
                                              "()Ljava/util/Iterator;");
    jobject iterator =
        listIterator != nullptr ? env->CallObjectMethod(list, listIterator) : nullptr;
    env->DeleteLocalRef(list);
    if (checkException(env, "loadedEntityList.iterator") || iterator == nullptr) {
        return;
    }

    jclass livingClass = mcClass(MappedClass::LivingEntity);
    jclass playerClass = mcClass(MappedClass::PlayerEntity);
    const jobject self = state.player.handle;

    while (true) {
        /* A throwing hasNext() also returns JNI_FALSE, so the exception has to
         * be cleared before the result is trusted. Leaving one pending would
         * poison every JNI call the rest of the frame makes. */
        const jboolean more = env->CallBooleanMethod(iterator, iteratorHasNext_);
        if (checkException(env, "Iterator.hasNext") || more != JNI_TRUE) {
            break;
        }
        /* Each surviving entity holds a local reference for the whole frame,
         * and the caller's local frame has a finite guaranteed capacity. */
        if (state.entities.size() >= kMaxCapturedEntities) {
            break;
        }
        jobject entity = env->CallObjectMethod(iterator, iteratorNext_);
        if (checkException(env, "Iterator.next") || entity == nullptr) {
            continue;
        }
        const bool living =
            livingClass != nullptr && env->IsInstanceOf(entity, livingClass) == JNI_TRUE;
        if (!living || (self != nullptr && env->IsSameObject(entity, self) == JNI_TRUE)) {
            env->DeleteLocalRef(entity);
            continue;
        }
        if (!callBool(env, entity, MappedMethod::Entity_isAlive)) {
            env->DeleteLocalRef(entity);
            continue;
        }

        /* Cheap distance reject before the dozen reads that follow. */
        const Vec3 rough = entityPos(env, entity);
        if ((rough - state.player.pos).lengthSquared() > kMaxCaptureDistanceSquared) {
            env->DeleteLocalRef(entity);
            continue;
        }

        EntityView view;
        view.handle = entity;
        view.living = living;
        view.id = intField(env, entity, MappedField::Entity_id);
        view.isPlayer = playerClass != nullptr && env->IsInstanceOf(entity, playerClass) == JNI_TRUE;
        view.invisible = callBool(env, entity, MappedMethod::Entity_isInvisible);
        view.sneaking = callBool(env, entity, MappedMethod::Entity_isSneaking);
        view.onGround = boolField(env, entity, MappedField::Entity_onGround);

        view.tickPos = rough;
        view.pos = lerpedPos(env, entity, state.tickProgress);

        view.width = floatField(env, entity, MappedField::Entity_width, 0.6f);
        view.height = floatField(env, entity, MappedField::Entity_height, 1.8f);
        const double eyeHeight =
            view.isPlayer && view.sneaking ? kDefaultEyeHeight - 0.08 : kDefaultEyeHeight;
        view.eyePos = {view.tickPos.x, view.tickPos.y + eyeHeight, view.tickPos.z};

        view.yaw = floatField(env, entity, MappedField::Entity_yaw);
        view.pitch = floatField(env, entity, MappedField::Entity_pitch);
        view.bodyYaw = floatField(env, entity, MappedField::LivingEntity_bodyYaw, view.yaw);
        view.health = callFloat(env, entity, MappedMethod::LivingEntity_getHealth);
        view.maxHealth = callFloat(env, entity, MappedMethod::LivingEntity_getMaxHealth, 20.0f);
        view.absorption =
            callFloat(env, entity, MappedMethod::LivingEntity_getAbsorptionAmount);
        view.armor = callInt(env, entity, MappedMethod::LivingEntity_getArmor);
        view.hurtTime = intField(env, entity, MappedField::LivingEntity_hurtTime);
        /* isBlocking is declared on EntityPlayer; asking a non-player would
         * throw once per entity per frame. */
        view.blocking = view.isPlayer && callBool(env, entity, MappedMethod::LivingEntity_isBlocking);
        view.distance = (view.eyePos - state.player.eyePos).length();

        state.entities.push_back(view);
    }
    env->DeleteLocalRef(iterator);
}

bool Game::capture(JNIEnv* env, FrameState& state) {
    if (!ready_ || clientInstance_ == nullptr) {
        return false;
    }
    state.entities.clear();
    state.player = LocalPlayerView{};
    state.inWorld = false;
    state.matricesValid = false;

    jobject client = clientInstance_;
    if (!captureWindow(env, client, state)) {
        return false;
    }

    /* FPS is the static debugFPS counter the F3 screen reads. */
    state.fps = staticIntField(env, MappedClass::MinecraftClient, MappedField::MinecraftClient_debugFPS, 0);

    jobject screen = objectField(env, client, MappedField::MinecraftClient_currentScreen);
    state.screenOpen = screen != nullptr;
    if (screen != nullptr) env->DeleteLocalRef(screen);

    jobject player = objectField(env, client, MappedField::MinecraftClient_player);
    jobject world = objectField(env, client, MappedField::MinecraftClient_world);
    if (player == nullptr || world == nullptr) {
        if (player != nullptr) env->DeleteLocalRef(player);
        if (world != nullptr) env->DeleteLocalRef(world);
        return true;
    }

    state.inWorld = true;
    state.worldTime = callLong(env, world, MappedMethod::WorldAccess_getTime);
    state.matricesValid = captureMatrices(env, client, state);
    capturePlayer(env, client, player, state);
    captureEntities(env, world, state);

    env->DeleteLocalRef(world);
    return true;
}

void Game::setRotation(JNIEnv* env, jobject player, float yaw, float pitch, bool alsoPrevious) {
    jfieldID yawField = mcField(MappedField::Entity_yaw);
    jfieldID pitchField = mcField(MappedField::Entity_pitch);
    if (player == nullptr || yawField == nullptr || pitchField == nullptr) {
        return;
    }
    const float clamped = clamp(pitch, -90.0f, 90.0f);
    env->SetFloatField(player, yawField, yaw);
    env->SetFloatField(player, pitchField, clamped);

    /* The camera is built from lerp(tickDelta, prevRotation, rotation), and on
     * the frame a tick has just run tickDelta is the fractional remainder.
     * Putting a rotation back without the previous one would still render the
     * rotation being taken back. */
    if (alsoPrevious) {
        const jfieldID lastYaw = mcField(MappedField::Entity_lastYaw);
        const jfieldID lastPitch = mcField(MappedField::Entity_lastPitch);
        if (lastYaw != nullptr) env->SetFloatField(player, lastYaw, yaw);
        if (lastPitch != nullptr) env->SetFloatField(player, lastPitch, clamped);
    }
    checkException(env, "setRotation");
}

void Game::attackEntity(JNIEnv* env, jobject player, jobject target) {
    if (!interactionsAllowed_) {
        return;
    }
    jobject interaction = objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    if (interaction == nullptr) {
        return;
    }
    if (jmethodID id = mcMethod(MappedMethod::ClientPlayerInteractionManager_attackEntity)) {
        env->CallVoidMethod(interaction, id, player, target);
        checkException(env, "attackEntity");
    }
    env->DeleteLocalRef(interaction);
}

void Game::setLocalRotation(JNIEnv* env, float yaw, float pitch) {
    jobject player = objectField(env, clientInstance_, MappedField::MinecraftClient_player);
    if (player == nullptr) {
        return;
    }
    setRotation(env, player, yaw, pitch, true);
    env->DeleteLocalRef(player);
}

int Game::playerAge(JNIEnv* env) {
    if (clientInstance_ == nullptr) {
        return -1;
    }
    jobject player = objectField(env, clientInstance_, MappedField::MinecraftClient_player);
    if (player == nullptr) {
        return -1;
    }
    const jint age = intField(env, player, MappedField::Entity_age, -1);
    env->DeleteLocalRef(player);
    return age;
}

void Game::setAttackCooldown(JNIEnv* env, int ticks) {
    jfieldID id = mcField(MappedField::MinecraftClient_attackCooldown);
    if (id != nullptr && clientInstance_ != nullptr) {
        env->SetIntField(clientInstance_, id, ticks);
    }
}

void Game::setItemUseCooldown(JNIEnv* env, int ticks) {
    jfieldID id = mcField(MappedField::MinecraftClient_itemUseCooldown);
    if (id != nullptr && clientInstance_ != nullptr) {
        env->SetIntField(clientInstance_, id, ticks);
    }
}

void Game::setBlockBreakingCooldown(JNIEnv* env, int ticks) {
    jfieldID id = mcField(MappedField::ClientPlayerInteractionManager_blockBreakingCooldown);
    jobject interaction =
        objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    if (id != nullptr && interaction != nullptr) {
        env->SetIntField(interaction, id, ticks);
    }
    if (interaction != nullptr) env->DeleteLocalRef(interaction);
}

int Game::itemUseCooldown(JNIEnv* env) {
    jfieldID id = mcField(MappedField::MinecraftClient_itemUseCooldown);
    if (id == nullptr || clientInstance_ == nullptr) {
        return 0;
    }
    return env->GetIntField(clientInstance_, id);
}

int Game::blockBreakingCooldown(JNIEnv* env) {
    jobject interaction =
        objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    if (interaction == nullptr) {
        return 0;
    }
    const int ticks = intField(
        env, interaction, MappedField::ClientPlayerInteractionManager_blockBreakingCooldown);
    env->DeleteLocalRef(interaction);
    return ticks;
}

bool Game::breakingBlock(JNIEnv* env) {
    jobject interaction =
        objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    if (interaction == nullptr) {
        return false;
    }
    const bool breaking =
        boolField(env, interaction, MappedField::ClientPlayerInteractionManager_breakingBlock);
    env->DeleteLocalRef(interaction);
    return breaking;
}

void Game::setSprinting(JNIEnv* env, jobject player, bool sprinting) {
    jmethodID id = mcMethod(MappedMethod::Entity_setSprinting);
    if (player == nullptr || id == nullptr) {
        return;
    }
    env->CallVoidMethod(player, id, sprinting ? JNI_TRUE : JNI_FALSE);
    checkException(env, "setSprinting");
}

void Game::swingHand(JNIEnv* env, jobject player, bool offHand) {
    /* 1.8.9 swings the main hand, full stop; there is no offhand to swing. */
    jmethodID swing = mcMethod(MappedMethod::LivingEntity_swingHand);
    if (player == nullptr || swing == nullptr) {
        return;
    }
    env->CallVoidMethod(player, swing);
    checkException(env, "swingItem");
}

void Game::jump(JNIEnv* env, jobject player) {
    jmethodID id = mcMethod(MappedMethod::LivingEntity_jump);
    if (player == nullptr || id == nullptr) {
        return;
    }
    env->CallVoidMethod(player, id);
    checkException(env, "jump");
}

bool Game::placeBlock(JNIEnv* env, jobject player, int blockX, int blockY, int blockZ,
                      BlockFace face, const Vec3& hitVector, bool swing) {
    if (!interactionsAllowed_) {
        return false;
    }
    static constexpr MappedField kFaceFields[] = {
        MappedField::Direction_DOWN,  MappedField::Direction_UP,   MappedField::Direction_NORTH,
        MappedField::Direction_SOUTH, MappedField::Direction_WEST, MappedField::Direction_EAST,
    };

    jclass blockPosClass = mcClass(MappedClass::BlockPos);
    jclass vec3dClass = mcClass(MappedClass::Vec3d);
    jmethodID blockPosCtor = mcMethod(MappedMethod::BlockPos_ctor);
    jmethodID vec3dCtor = mcMethod(MappedMethod::Vec3d_ctor);
    jmethodID interactBlock = mcMethod(MappedMethod::ClientPlayerInteractionManager_interactBlock);
    if (blockPosClass == nullptr || vec3dClass == nullptr || blockPosCtor == nullptr ||
        vec3dCtor == nullptr || interactBlock == nullptr) {
        return false;
    }

    jobject direction = staticObjectField(env, MappedClass::Direction, kFaceFields[static_cast<int>(face)]);
    jobject interaction =
        objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    jobject position = nullptr;
    jobject hit = nullptr;
    bool ok = false;

    if (direction != nullptr && interaction != nullptr) {
        position = env->NewObject(blockPosClass, blockPosCtor, blockX, blockY, blockZ);
        hit = env->NewObject(vec3dClass, vec3dCtor, hitVector.x, hitVector.y, hitVector.z);
        if (!checkException(env, "right-click operands") && position != nullptr && hit != nullptr) {
            /* onPlayerRightClick(player, world, stack, pos, facing, hitVec). */
            jobject rightClick =
                callObject(env, player, MappedMethod::PlayerEntity_getInventory);
            jobject stack = rightClick != nullptr
                                ? callObject(env, rightClick, MappedMethod::PlayerInventory_getSelectedStack)
                                : nullptr;
            jobject worldObject = objectField(env, clientInstance_, MappedField::MinecraftClient_world);
            if (stack != nullptr && worldObject != nullptr) {
                /* 1.8.9 reports acceptance as the boolean return, so the
                 * call goes out as a boolean method, not an object one. */
                const jboolean accepted = env->CallBooleanMethod(
                    interaction, interactBlock, static_cast<jobject>(player), worldObject,
                    stack, position, direction, hit);
                ok = !checkException(env, "onPlayerRightClick") && accepted == JNI_TRUE;
            if (ok && swing) {
                    swingHand(env, player, false);
                }
            }
            if (stack != nullptr) env->DeleteLocalRef(stack);
            if (rightClick != nullptr) env->DeleteLocalRef(rightClick);
            if (worldObject != nullptr) env->DeleteLocalRef(worldObject);
        }
    }

    const auto release = [&](jobject reference) {
        if (reference != nullptr) env->DeleteLocalRef(reference);
    };
    release(hit);
    release(position);
    release(interaction);
    release(direction);
    return ok;
}

bool Game::setCrosshairTarget(JNIEnv* env, int blockX, int blockY, int blockZ, BlockFace face,
                              const Vec3& hitVector) {
    static constexpr MappedField kFaceFields[] = {
        MappedField::Direction_DOWN,  MappedField::Direction_UP,   MappedField::Direction_NORTH,
        MappedField::Direction_SOUTH, MappedField::Direction_WEST, MappedField::Direction_EAST,
    };

    jclass blockPosClass = mcClass(MappedClass::BlockPos);
    jclass vec3dClass = mcClass(MappedClass::Vec3d);
    jclass hitClass = mcClass(MappedClass::BlockHitResult);
    jmethodID blockPosCtor = mcMethod(MappedMethod::BlockPos_ctor);
    jmethodID vec3dCtor = mcMethod(MappedMethod::Vec3d_ctor);
    jmethodID hitCtor = mcMethod(MappedMethod::BlockHitResult_ctor);
    jfieldID target = mcField(MappedField::MinecraftClient_crosshairTarget);
    if (blockPosClass == nullptr || vec3dClass == nullptr || hitClass == nullptr ||
        blockPosCtor == nullptr || vec3dCtor == nullptr || hitCtor == nullptr ||
        target == nullptr || clientInstance_ == nullptr) {
        return false;
    }

    jobject direction =
        staticObjectField(env, MappedClass::Direction, kFaceFields[static_cast<int>(face)]);
    jobject position = nullptr;
    jobject hit = nullptr;
    jobject result = nullptr;
    bool ok = false;

    if (direction != nullptr) {
        position = env->NewObject(blockPosClass, blockPosCtor, blockX, blockY, blockZ);
        hit = env->NewObject(vec3dClass, vec3dCtor, hitVector.x, hitVector.y, hitVector.z);
        if (!checkException(env, "crosshair operands") && position != nullptr && hit != nullptr) {
            /* (hitVec, facing, blockPos); the facing ctor marks the type BLOCK,
             * which is what crosshair() and the game's own doItemUse read. */
            result = env->NewObject(hitClass, hitCtor, hit, direction, position);
            if (!checkException(env, "crosshair MovingObjectPosition") && result != nullptr) {
                env->SetObjectField(clientInstance_, target, result);
                ok = !checkException(env, "objectMouseOver write");
            }
        }
    }

    const auto release = [&](jobject reference) {
        if (reference != nullptr) env->DeleteLocalRef(reference);
    };
    release(result);
    release(hit);
    release(position);
    release(direction);
    return ok;
}

jobject Game::world(JNIEnv* env) {
    return objectField(env, clientInstance_, MappedField::MinecraftClient_world);
}

bool Game::respawn(JNIEnv* env, jobject player) {
    jmethodID id = mcMethod(MappedMethod::ClientPlayerEntity_requestRespawn);
    if (player == nullptr || id == nullptr) {
        return false;
    }
    env->CallVoidMethod(player, id);
    return !checkException(env, "respawnPlayer");
}

void Game::setCursorReleased(JNIEnv* env, bool released) {
    /* On 1.8.9 the cursor lives behind LWJGL Mouse, but the client never
     * touches it directly: showing a dummy GuiScreen makes the game's own
     * input code ungrab and regrab around the menu, and the anchor write keeps
     * the first frame after closing from spinning the camera. */
    jmethodID id = mcMethod(released ? MappedMethod::Mouse_unlockCursor
                                     : MappedMethod::Mouse_lockCursor);
    if (id != nullptr) {
        jclass mouseClass = mcClass(MappedClass::Mouse);
        if (mouseClass != nullptr) {
            env->CallStaticVoidMethod(mouseClass, id);
            checkException(env, released ? "Mouse.ungrab" : "Mouse.grab");
        }
    }
}

void Game::anchorCursor(JNIEnv* env, double x, double y) {
    /* LWJGL 2 Mouse keeps its position in integer window coordinates, updated
     * by poll(); setting them through the raw calls is what the game's own
     * code does when it wants the pointer somewhere specific. */
    /* The integer Mouse.setCursorPosition(int, int) is what carries the
     * anchor; the double Mouse fields of 1.21 do not exist here. */
    jclass mouseClass = mcClass(MappedClass::Mouse);
    jmethodID setCursorPosition =
        mouseClass != nullptr
            ? env->GetStaticMethodID(mouseClass, "setCursorPosition", "(II)V")
            : nullptr;
    if (mouseClass != nullptr && setCursorPosition != nullptr) {
        env->CallStaticVoidMethod(mouseClass, setCursorPosition, static_cast<jint>(x),
                                  static_cast<jint>(y));
        checkException(env, "Mouse.setCursorPosition");
    }
}

void Game::closeScreen(JNIEnv* env) {
    jmethodID id = mcMethod(MappedMethod::MinecraftClient_setScreen);
    if (id == nullptr || clientInstance_ == nullptr) {
        return;
    }
    env->CallVoidMethod(clientInstance_, id, nullptr);
    checkException(env, "displayGuiScreen(null)");
}

bool Game::sendChat(JNIEnv* env, const std::string& text, bool command) {
    /* 1.8.9 sends chat from the player; a slash prefix routes to the command
     * path inside the same call. */
    jobject player = objectField(env, clientInstance_, MappedField::MinecraftClient_player);
    if (player == nullptr) {
        return false;
    }
    jmethodID id = mcMethod(MappedMethod::ClientPlayNetworkHandler_sendChatMessage);
    bool sent = false;
    if (id != nullptr) {
        jstring message = bridge::toJavaString(env, text);
        env->CallVoidMethod(player, id, message);
        sent = !checkException(env, "sendChatMessage");
        env->DeleteLocalRef(message);
    }
    env->DeleteLocalRef(player);
    return sent;
}

namespace {

/* A chat component carrying `text`. 1.8.9 has no Text.literal factory; the
 * concrete ChatComponentText ctor is the literal. Returns a local reference
 * the caller owns, or null. */
jobject buildText(JNIEnv* env, const std::string& text, int colour) {
    jclass textClass = mcClass(MappedClass::Text);
    jmethodID ctor = mcMethod(MappedMethod::Text_ctor);
    if (textClass == nullptr || ctor == nullptr) {
        return nullptr;
    }
    jstring value = bridge::toJavaString(env, text);
    jobject message = env->NewObject(textClass, ctor, value);
    env->DeleteLocalRef(value);
    if (checkException(env, "ChatComponentText") || message == nullptr) {
        return nullptr;
    }
    /* A colour arrives as a chat formatting code prefixed to the text; 1.8.9
     * has no withColor and the codes are the format the client renders. */
    (void)colour;
    return message;
}

}  // namespace

bool Game::printChat(JNIEnv* env, const std::string& text, int colour) {
    jobject hud = objectField(env, clientInstance_, MappedField::MinecraftClient_inGameHud);
    if (hud == nullptr) {
        return false;
    }
    jobject chat = callObject(env, hud, MappedMethod::InGameHud_getChatHud);
    env->DeleteLocalRef(hud);
    if (chat == nullptr) {
        return false;
    }

    bool printed = false;
    jmethodID addMessage = mcMethod(MappedMethod::ChatHud_addMessage);
    jobject message = buildText(env, text, colour);
    if (addMessage != nullptr && message != nullptr) {
        env->CallVoidMethod(chat, addMessage, message);
        printed = !checkException(env, "GuiNewChat.printChatMessage");
    }
    if (message != nullptr) {
        env->DeleteLocalRef(message);
    }
    env->DeleteLocalRef(chat);
    return printed;
}

bool Game::overlayMessage(JNIEnv* env, const std::string& text) {
    /* 1.8.9's action-bar line is a packet-driven field on GuiIngame; without
     * a mapped accessor the overlay message degrades to a chat line. */
    return printChat(env, text, -1);
}

namespace {

std::string identifierPath(JNIEnv* env, jobject identifier) {
    jmethodID getPath = mcMethod(MappedMethod::Identifier_getPath);
    if (identifier == nullptr || getPath == nullptr) {
        return {};
    }
    auto value = static_cast<jstring>(env->CallObjectMethod(identifier, getPath));
    if (checkException(env, "ResourceLocation.getPath") || value == nullptr) {
        return {};
    }
    std::string path = bridge::toStdString(env, value);
    env->DeleteLocalRef(value);
    return path;
}

/* The registry path of a vanilla singleton: "diamond_sword", "obsidian".
 * Identity in Minecraft is the object, but a script needs a name, and the
 * registry is where the two meet. 1.8.9's getNameForObject is declared on
 * RegistrySimple and inherited by RegistryNamespaced; where the name fails,
 * Block has its own getUnlocalizedName as a "stone"-style fallback. */
std::string registryPath(JNIEnv* env, MappedField registryField, jobject value,
                         bool itemRegistry) {
    jmethodID getId = mcMethod(MappedMethod::Registry_getId);
    if (value == nullptr || getId == nullptr) {
        return {};
    }
    jobject registry = staticObjectField(env, itemRegistry ? MappedClass::Registries
                                                           : MappedClass::Block,
                                         registryField);
    if (registry == nullptr) {
        return {};
    }
    jobject identifier = env->CallObjectMethod(registry, getId, value);
    env->DeleteLocalRef(registry);
    if (checkException(env, "RegistryNamespaced.getNameForObject") || identifier == nullptr) {
        /* Lunar's registry proxies do not expose the name lookup; 1.8.9's
         * unlocalized name ("tile.stone", "item.swordDiamond") is close
         * enough for every caller, which only compares or displays it. */
        jmethodID unlocalized = value != nullptr
                                    ? mcMethod(MappedMethod::Block_getUnlocalizedName)
                                    : nullptr;
        if (unlocalized == nullptr && value != nullptr) {
            unlocalized = mcMethod(MappedMethod::Item_getUnlocalizedName);
        }
        if (unlocalized != nullptr) {
            auto text = static_cast<jstring>(env->CallObjectMethod(value, unlocalized));
            if (!checkException(env, "getUnlocalizedName") && text != nullptr) {
                std::string name = bridge::toStdString(env, text);
                env->DeleteLocalRef(text);
                return name;
            }
        }
        return {};
    }
    std::string path = identifierPath(env, identifier);
    env->DeleteLocalRef(identifier);
    return path;
}

}  // namespace

Game::StackView Game::stack(JNIEnv* env, jobject player, int slot) {
    StackView view;
    jobject inventory = callObject(env, player, MappedMethod::PlayerEntity_getInventory);
    if (inventory == nullptr) {
        return view;
    }
    jobject stack = callObjectInt(env, inventory, MappedMethod::Inventory_getStack, slot);
    env->DeleteLocalRef(inventory);
    if (stack == nullptr) {
        return view;
    }
    /* 1.8.9 stacks are null when empty; stackSize is the count. */
    if (!env->IsSameObject(stack, nullptr)) {
        view.present = true;
        view.count = intField(env, stack, MappedField::ItemStack_stackSize, 1);
        view.damage = callInt(env, stack, MappedMethod::ItemStack_getDamage);
        view.maxDamage = callInt(env, stack, MappedMethod::ItemStack_getMaxDamage);
        jobject item = callObject(env, stack, MappedMethod::ItemStack_getItem);
        view.id = registryPath(env, MappedField::Registries_ITEM, item, true);
        if (item != nullptr) {
            jclass blockItemClass = mcClass(MappedClass::BlockItem);
            view.block =
                blockItemClass != nullptr && env->IsInstanceOf(item, blockItemClass) == JNI_TRUE;
            env->DeleteLocalRef(item);
        }
    }
    env->DeleteLocalRef(stack);
    return view;
}

std::string Game::blockId(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ) {
    jclass blockPosClass = mcClass(MappedClass::BlockPos);
    jmethodID blockPosCtor = mcMethod(MappedMethod::BlockPos_ctor);
    jmethodID getBlockState = mcMethod(MappedMethod::BlockView_getBlockState);
    jmethodID getBlock = mcMethod(MappedMethod::AbstractBlockState_getBlock);
    if (world == nullptr || blockPosClass == nullptr || blockPosCtor == nullptr ||
        getBlockState == nullptr || getBlock == nullptr) {
        return {};
    }
    jobject position = env->NewObject(blockPosClass, blockPosCtor, blockX, blockY, blockZ);
    if (position == nullptr) {
        env->ExceptionClear();
        return {};
    }
    jobject state = env->CallObjectMethod(world, getBlockState, position);
    env->DeleteLocalRef(position);
    if (checkException(env, "getBlockState") || state == nullptr) {
        return {};
    }
    jobject block = env->CallObjectMethod(state, getBlock);
    env->DeleteLocalRef(state);
    if (checkException(env, "getBlock") || block == nullptr) {
        return {};
    }
    std::string id = registryPath(env, MappedField::Registries_BLOCK, block, false);
    env->DeleteLocalRef(block);
    return id;
}

std::vector<Game::EffectView> Game::statusEffects(JNIEnv* env, jobject entity) {
    std::vector<EffectView> effects;
    jobject collection = callObject(env, entity, MappedMethod::LivingEntity_getStatusEffects);
    if (collection == nullptr) {
        return effects;
    }
    if (iterableIterator_ == nullptr || iteratorHasNext_ == nullptr || iteratorNext_ == nullptr) {
        env->DeleteLocalRef(collection);
        return effects;
    }
    jobject iterator = env->CallObjectMethod(collection, iterableIterator_);
    env->DeleteLocalRef(collection);
    if (checkException(env, "statusEffects.iterator") || iterator == nullptr) {
        return effects;
    }

    while (env->CallBooleanMethod(iterator, iteratorHasNext_) == JNI_TRUE) {
        if (checkException(env, "statusEffects.hasNext")) {
            break;
        }
        jobject instance = env->CallObjectMethod(iterator, iteratorNext_);
        if (checkException(env, "statusEffects.next") || instance == nullptr) {
            break;
        }
        EffectView view;
        view.amplifier = callInt(env, instance, MappedMethod::StatusEffectInstance_getAmplifier);
        view.duration = callInt(env, instance, MappedMethod::StatusEffectInstance_getDuration);
        /* 1.8.9 identifies a potion by integer id; the bridge maps the handful
         * the client reasons about by id and leaves the rest numeric. */
        const int potionId = callInt(env, instance, MappedMethod::StatusEffectInstance_getPotionID, -1);
        switch (potionId) {
            case 8: view.id = "weakness"; break;
            case 5: view.id = "strength"; break;
            case 1: view.id = "speed"; break;
            case 2: view.id = "slowness"; break;
            case 12: view.id = "fire_resistance"; break;
            case 11: view.id = "resistance"; break;
            default: view.id = "potion_" + std::to_string(potionId); break;
        }
        env->DeleteLocalRef(instance);
        if (!view.id.empty()) {
            effects.push_back(std::move(view));
        }
        if (effects.size() >= 64) {
            break;
        }
    }
    env->DeleteLocalRef(iterator);
    return effects;
}

namespace {

/* The nine vanilla movement bindings, by the names the script API and the
 * modules use for them. */
const MappedField* bindingField(const char* name) {
    struct Binding {
        const char* name;
        MappedField field;
    };
    static const Binding kBindings[] = {
        {"forward", MappedField::GameOptions_forwardKey},
        {"back", MappedField::GameOptions_backKey},
        {"left", MappedField::GameOptions_leftKey},
        {"right", MappedField::GameOptions_rightKey},
        {"jump", MappedField::GameOptions_jumpKey},
        {"sneak", MappedField::GameOptions_sneakKey},
        {"sprint", MappedField::GameOptions_sprintKey},
        {"attack", MappedField::GameOptions_attackKey},
        {"use", MappedField::GameOptions_useKey},
    };
    if (name == nullptr) {
        return nullptr;
    }
    for (const Binding& binding : kBindings) {
        if (std::strcmp(binding.name, name) == 0) {
            return &binding.field;
        }
    }
    return nullptr;
}

}  // namespace

bool Game::setBinding(JNIEnv* env, const char* name, bool pressed) {
    /* setKeyBindState(keyCode, state) is static on 1.8.9 and updates every
     * binding bound to that key, which is exactly what the keyboard does. */
    jmethodID setPressed = mcMethod(MappedMethod::KeyBinding_setPressed);
    const MappedField* wanted = bindingField(name);
    if (wanted == nullptr || setPressed == nullptr) {
        return false;
    }

    jobject options = objectField(env, clientInstance_, MappedField::MinecraftClient_options);
    if (options == nullptr) {
        return false;
    }
    jobject binding = objectField(env, options, *wanted);
    env->DeleteLocalRef(options);
    if (binding == nullptr) {
        return false;
    }
    const int keyCode = callInt(env, binding, MappedMethod::InputUtilKey_getCode, 0);
    env->DeleteLocalRef(binding);
    if (keyCode == 0) {
        return false;
    }
    jclass keyBindingClass = mcClass(MappedClass::KeyBinding);
    if (keyBindingClass == nullptr) {
        return false;
    }
    env->CallStaticVoidMethod(keyBindingClass, setPressed, keyCode, pressed ? JNI_TRUE : JNI_FALSE);
    const bool ok = !checkException(env, "KeyBinding.setKeyBindState");
    return ok;
}

int Game::bindingKey(JNIEnv* env, const char* name) {
    const MappedField* wanted = bindingField(name);
    if (wanted == nullptr) {
        return 0;
    }
    jobject options = objectField(env, clientInstance_, MappedField::MinecraftClient_options);
    if (options == nullptr) {
        return 0;
    }
    jobject binding = objectField(env, options, *wanted);
    env->DeleteLocalRef(options);
    if (binding == nullptr) {
        return 0;
    }
    /* 1.8.9's KeyBinding.keyCode is the raw LWJGL key code, an int field. */
    const int code = intField(env, binding, MappedField::KeyBinding_boundKey, 0);
    env->DeleteLocalRef(binding);
    return code;
}

bool Game::bindingPressed(JNIEnv* env, const char* name) {
    const MappedField* wanted = bindingField(name);
    if (wanted == nullptr) {
        return false;
    }
    jobject options = objectField(env, clientInstance_, MappedField::MinecraftClient_options);
    if (options == nullptr) {
        return false;
    }
    jobject binding = objectField(env, options, *wanted);
    env->DeleteLocalRef(options);
    if (binding == nullptr) {
        return false;
    }
    const bool held = boolField(env, binding, MappedField::KeyBinding_pressed);
    env->DeleteLocalRef(binding);
    return held;
}

void Game::setVelocity(JNIEnv* env, jobject entity, const Vec3& velocity) {
    jmethodID id = mcMethod(MappedMethod::Entity_setVelocity);
    if (entity == nullptr || id == nullptr) {
        return;
    }
    env->CallVoidMethod(entity, id, velocity.x, velocity.y, velocity.z);
    checkException(env, "setVelocity");
}

bool Game::playSound(JNIEnv* env, jobject player, const std::string& id, float volume,
                     float pitch) {
    /* 1.8.9's EntityPlayer.playSound(String, float, float) takes the sound by
     * registry path, so no Identifier or registry hop is needed. */
    jmethodID play = mcMethod(MappedMethod::Entity_playSound);
    if (player == nullptr || play == nullptr) {
        return false;
    }
    const std::string full = id.find(':') == std::string::npos ? "minecraft:" + id : id;
    jstring name = bridge::toJavaString(env, full);
    env->CallVoidMethod(player, play, name, volume, pitch);
    const bool ok = !checkException(env, "playSound");
    env->DeleteLocalRef(name);
    return ok;
}

std::string Game::serverAddress(JNIEnv* env) {
    jobject entry = callObject(env, clientInstance_, MappedMethod::MinecraftClient_getCurrentServerEntry);
    if (entry == nullptr) {
        return {};
    }
    jfieldID address = mcField(MappedField::ServerInfo_address);
    std::string result;
    if (address != nullptr) {
        auto value = static_cast<jstring>(env->GetObjectField(entry, address));
        if (value != nullptr) {
            result = bridge::toStdString(env, value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(entry);
    return result;
}

std::string Game::gameDirectory(JNIEnv* env) {
    /* 1.8.9's mcDataDir is an instance field on the singleton; the static
     * spelling is tried second through its own paired JNI calls. */
    jclass clientClass = mcClass(MappedClass::MinecraftClient);
    jfieldID directoryField = mcField(MappedField::MinecraftClient_runDirectory);
    jobject directory = nullptr;
    if (directoryField != nullptr) {
        directory = env->GetObjectField(clientInstance_, directoryField);
        if (checkException(env, "mcDataDir (instance)")) directory = nullptr;
    }
    if (directory == nullptr && clientClass != nullptr) {
        jfieldID staticField = env->GetStaticFieldID(clientClass, "mcDataDir", "Ljava/io/File;");
        if (!checkException(env, "mcDataDir (static lookup)") && staticField != nullptr) {
            directory = env->GetStaticObjectField(clientClass, staticField);
            if (checkException(env, "mcDataDir (static read)")) directory = nullptr;
        }
    }
    if (directory == nullptr) {
        return {};
    }
    std::string result;
    /* java.io.File is a bootstrap class, so it is asked directly rather than
     * through the mapping tables. */
    jclass fileClass = env->GetObjectClass(directory);
    if (fileClass != nullptr) {
        jmethodID absolutePath = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
        if (absolutePath != nullptr) {
            auto value = static_cast<jstring>(env->CallObjectMethod(directory, absolutePath));
            if (!checkException(env, "File.getAbsolutePath") && value != nullptr) {
                result = bridge::toStdString(env, value);
                env->DeleteLocalRef(value);
            }
        } else {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(fileClass);
    }
    env->DeleteLocalRef(directory);
    return result;
}

unsigned int Game::textureName(JNIEnv* env, jobject abstractTexture) {
    /* 1.8.9 textures are ITextureObject and the GL name is the documented
     * getGlTextureId(); nothing needs reflecting. */
    if (abstractTexture == nullptr) {
        return 0;
    }
    const jint id = callInt(env, abstractTexture, MappedMethod::AbstractTexture_getGlTexture, 0);
    return id > 0 ? static_cast<unsigned int>(id) : 0u;
}

Game::SkinView Game::playerSkin(JNIEnv* env, jobject player) {
    SkinView view;
    if (player == nullptr) {
        return view;
    }
    jobject entry = callObject(env, player, MappedMethod::AbstractClientPlayerEntity_getPlayerListEntry);
    if (entry == nullptr) {
        return view;
    }

    /* Slim is the "slim" skin type string on the tab entry. */
    {
        jmethodID skinType = mcMethod(MappedMethod::PlayerListEntry_getSkinType);
        if (skinType != nullptr) {
            auto type = static_cast<jstring>(env->CallObjectMethod(entry, skinType));
            if (!checkException(env, "getSkinType") && type != nullptr) {
                view.slim = bridge::toStdString(env, type) == "slim";
                env->DeleteLocalRef(type);
            }
        }
    }

    jmethodID locationSkin = mcMethod(MappedMethod::PlayerListEntry_getLocationSkin);
    jobject identifier = locationSkin != nullptr
                             ? env->CallObjectMethod(entry, locationSkin)
                             : nullptr;
    env->DeleteLocalRef(entry);
    if (checkException(env, "getLocationSkin") || identifier == nullptr) {
        return view;
    }

    jobject manager = objectField(env, clientInstance_, MappedField::MinecraftClient_textureManager);
    if (manager != nullptr) {
        jmethodID get = mcMethod(MappedMethod::TextureManager_getTexture);
        jobject texture = get != nullptr ? env->CallObjectMethod(manager, get, identifier) : nullptr;
        if (!checkException(env, "TextureManager.getTexture") && texture != nullptr) {
            view.texture = textureName(env, texture);
            env->DeleteLocalRef(texture);
        }
        env->DeleteLocalRef(manager);
    }
    env->DeleteLocalRef(identifier);
    return view;
}

jobject Game::itemAtlas(JNIEnv* env, bool blocks) {
    /* Both atlases are one stitched TextureMap on 1.8.9; the same GL texture
     * serves items and blocks, and getAtlasSprite keys by icon name. */
    jobject textureMap = objectField(env, clientInstance_, MappedField::MinecraftClient_textureMapBlocks);
    return textureMap;
}

namespace {

/* One sprite out of the stitched atlas, by icon name. */
Game::SpriteView spriteIn(JNIEnv* env, jobject atlas, const std::string& name) {
    Game::SpriteView view;
    jmethodID getSprite = mcMethod(MappedMethod::SpriteAtlasTexture_getSprite);
    if (atlas == nullptr || getSprite == nullptr) {
        return view;
    }
    jstring text = env->NewStringUTF(name.c_str());
    if (text == nullptr) {
        return view;
    }
    jobject sprite = env->CallObjectMethod(atlas, getSprite, text);
    env->DeleteLocalRef(text);
    if (checkException(env, "TextureMap.getAtlasSprite") || sprite == nullptr) {
        return view;
    }
    view.u0 = callFloat(env, sprite, MappedMethod::Sprite_getMinU);
    view.v0 = callFloat(env, sprite, MappedMethod::Sprite_getMinV);
    view.u1 = callFloat(env, sprite, MappedMethod::Sprite_getMaxU);
    view.v1 = callFloat(env, sprite, MappedMethod::Sprite_getMaxV);
    view.valid = view.u1 > view.u0 && view.v1 > view.v0;
    env->DeleteLocalRef(sprite);
    return view;
}

}  // namespace

void Game::resetSpriteCache() {
    spriteCache_.clear();
    missingResolved_ = false;
}

Game::SpriteView Game::itemSprite(JNIEnv* env, const std::string& itemId) {
    if (itemId.empty()) {
        return {};
    }
    const auto cached = spriteCache_.find(itemId);
    if (cached != spriteCache_.end()) {
        return cached->second;
    }

    SpriteView view;
    jobject atlas = itemAtlas(env, false);
    if (atlas == nullptr && spriteCache_.empty()) {
        COLD_WARN(kScope, "block atlas unavailable; radar icons fall back to glyphs");
    }
    if (atlas != nullptr) {
        /* 1.8.9 returns the missing sprite for a name the atlas does not hold,
         * so a known-missing probe calibrates the comparison once. */
        if (!missingResolved_) {
            missingItemSprite_ = spriteIn(env, atlas, "missingno");
        }
        view = spriteIn(env, atlas, itemId);
        if (view.valid) {
            const bool missing = std::fabs(view.u0 - missingItemSprite_.u0) < 1e-6f &&
                                 std::fabs(view.v0 - missingItemSprite_.v0) < 1e-6f &&
                                 std::fabs(view.u1 - missingItemSprite_.u1) < 1e-6f &&
                                 std::fabs(view.v1 - missingItemSprite_.v1) < 1e-6f;
            if (missing) {
                view.valid = false;
            } else {
                view.texture = textureName(env, atlas);
            }
        }
        env->DeleteLocalRef(atlas);
    }

    missingResolved_ = true;
    if (view.texture == 0) {
        view.valid = false;
    }
    spriteCache_.emplace(itemId, view);
    return view;
}

Game::EquipmentView Game::equipment(JNIEnv* env, jobject entity, Slot slot) {
    /* 1.8.9 exposes armour as four indexed getters on the player and the held
     * stacks as ordinary hand reads; the 1.21 equipment-slot enum collapses
     * into an index here. */
    EquipmentView view;
    const auto index = static_cast<int>(slot);
    if (entity == nullptr || index < 0 || index >= static_cast<int>(Slot::Count)) {
        return view;
    }

    jobject stack = nullptr;
    if (slot == Slot::MainHand) {
        stack = callObject(env, entity, MappedMethod::LivingEntity_getMainHandStack);
    } else if (slot == Slot::OffHand) {
        /* No offhand on 1.8.9: the slot is simply empty. */
        return view;
    } else {
        jmethodID armor = mcMethod(MappedMethod::PlayerEntity_getCurrentArmor);
        stack = armor != nullptr ? env->CallObjectMethod(entity, armor, index) : nullptr;
        if (checkException(env, "getCurrentArmor")) {
            stack = nullptr;
        }
    }
    if (stack == nullptr) {
        return view;
    }
    if (env->IsSameObject(stack, nullptr)) {
        env->DeleteLocalRef(stack);
        return view;
    }

    view.present = true;
    view.count = intField(env, stack, MappedField::ItemStack_stackSize, 1);
    view.damage = callInt(env, stack, MappedMethod::ItemStack_getDamage);
    view.maxDamage = callInt(env, stack, MappedMethod::ItemStack_getMaxDamage);

    jobject item = callObject(env, stack, MappedMethod::ItemStack_getItem);
    view.id = registryPath(env, MappedField::Registries_ITEM, item, true);
    if (item != nullptr) env->DeleteLocalRef(item);

    jmethodID displayName = mcMethod(MappedMethod::ItemStack_getName);
    if (displayName != nullptr) {
        auto text = static_cast<jstring>(env->CallObjectMethod(stack, displayName));
        if (!checkException(env, "getDisplayName") && text != nullptr) {
            std::string full = bridge::toStdString(env, text);
            /* §-formatting codes are stripped so the panel shows the name. */
            std::string clean;
            for (std::size_t i = 0; i < full.size(); ++i) {
                if (full[i] == static_cast<char>(0xC2A7 >> 8) && i + 1 < full.size()) {
                    ++i;
                    continue;
                }
                clean.push_back(full[i]);
            }
            view.name = clean.substr(0, 96);
            env->DeleteLocalRef(text);
        }
    }

    /* Enchantments live in the stack's NBT under "ench" as a list of
     * {id: short, lvl: short} compounds. 1.8.9's getEnchantmentTagList()
     * returns the NBTTagList directly; when the accessor is unmapped the
     * stack's own tag compound is fetched and getTagList("ench", 9) walks
     * it instead - the SDK's inventory module does exactly this. */
    jobject enchantments = callObject(env, stack, MappedMethod::ItemStack_getEnchantments);
    jobject tagCompound = nullptr;
    if (enchantments == nullptr) {
        jmethodID getTagCompound = mcMethod(MappedMethod::ItemStack_getTagCompound);
        if (getTagCompound != nullptr) {
            tagCompound = env->CallObjectMethod(stack, getTagCompound);
            if (checkException(env, "getTagCompound") || tagCompound == nullptr) {
                tagCompound = nullptr;
            } else {
                jmethodID getTagList = mcMethod(MappedMethod::ItemEnchantmentsComponent_getEnchantments);
                if (getTagList != nullptr) {
                    /* The key has to be a real jstring: a bare C literal passed
                     * through the vararg is dereferenced by the JVM as a Java
                     * object and takes the process down inside hasKey(). */
                    jstring key = env->NewStringUTF("ench");
                    if (key != nullptr) {
                        enchantments = env->CallObjectMethod(tagCompound, getTagList, key,
                                                             static_cast<jint>(9));
                        env->DeleteLocalRef(key);
                        if (checkException(env, "NBTTagCompound.getTagList(ench)")) {
                            enchantments = nullptr;
                        }
                    }
                }
            }
        }
    }
    if (enchantments != nullptr) {
        jmethodID tagCount = mcMethod(MappedMethod::NBTTagList_tagCount);
        jmethodID compoundAt = mcMethod(MappedMethod::NBTTagList_getCompoundTagAt);
        jmethodID shortOf = mcMethod(MappedMethod::NBTTagCompound_getShort);
        if (tagCount != nullptr && compoundAt != nullptr && shortOf != nullptr) {
            const int count = env->CallIntMethod(enchantments, tagCount);
            if (!checkException(env, "tagCount")) {
                for (int i = 0; i < count && view.enchantments.size() < 12; ++i) {
                    jobject compound = env->CallObjectMethod(enchantments, compoundAt, i);
                    if (checkException(env, "getCompoundTagAt") || compound == nullptr) {
                        break;
                    }
                    EnchantView enchant;
                    /* Same rule as above: "id"/"lvl" must be jstrings, never
                     * raw literals - the JVM treats the pointer as an object. */
                    jstring idKey = env->NewStringUTF("id");
                    jstring lvlKey = idKey != nullptr ? env->NewStringUTF("lvl") : nullptr;
                    const short id =
                        idKey != nullptr ? env->CallShortMethod(compound, shortOf, idKey) : 0;
                    if (!checkException(env, "ench.id")) {
                        const short level = lvlKey != nullptr
                                                ? env->CallShortMethod(compound, shortOf, lvlKey)
                                                : 0;
                        if (!checkException(env, "ench.lvl")) {
                            enchant.id = "enchantment_" + std::to_string(static_cast<int>(id));
                            enchant.level = level;
                            view.enchantments.push_back(std::move(enchant));
                        }
                    }
                    if (idKey != nullptr) env->DeleteLocalRef(idKey);
                    if (lvlKey != nullptr) env->DeleteLocalRef(lvlKey);
                    env->DeleteLocalRef(compound);
                }
            }
        }
        env->DeleteLocalRef(enchantments);
    }
    if (tagCompound != nullptr) env->DeleteLocalRef(tagCompound);

    env->DeleteLocalRef(stack);
    return view;
}

std::string Game::entityUuid(JNIEnv* env, jobject entity) {
    jmethodID id = mcMethod(MappedMethod::Entity_getUuidAsString);
    if (entity == nullptr || id == nullptr) {
        return {};
    }
    jobject uuid = env->CallObjectMethod(entity, id);
    if (checkException(env, "getUniqueID") || uuid == nullptr) {
        return {};
    }
    /* UUID.toString rather than a mapping: java.util is not the game's. */
    jclass uuidClass = env->GetObjectClass(uuid);
    jmethodID toString = env->GetMethodID(uuidClass, "toString", "()Ljava/lang/String;");
    std::string text;
    if (toString != nullptr) {
        auto value = static_cast<jstring>(env->CallObjectMethod(uuid, toString));
        if (!checkException(env, "UUID.toString") && value != nullptr) {
            text = bridge::toStdString(env, value);
            env->DeleteLocalRef(value);
        }
    } else {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(uuidClass);
    env->DeleteLocalRef(uuid);
    return text;
}

int Game::entityLatency(JNIEnv* env, jobject player) {
    jobject entry =
        callObject(env, player, MappedMethod::AbstractClientPlayerEntity_getPlayerListEntry);
    if (entry == nullptr) {
        return -1;
    }
    const int latency = callInt(env, entry, MappedMethod::PlayerListEntry_getLatency, -1);
    env->DeleteLocalRef(entry);
    return latency;
}

std::string Game::entityName(JNIEnv* env, jobject entity) {
    jmethodID id = mcMethod(MappedMethod::Entity_getName);
    if (entity == nullptr || id == nullptr) {
        return {};
    }
    auto value = static_cast<jstring>(env->CallObjectMethod(entity, id));
    if (checkException(env, "getCommandSenderName") || value == nullptr) {
        return {};
    }
    std::string name = bridge::toStdString(env, value);
    env->DeleteLocalRef(value);
    return name;
}

bool Game::stackIs(JNIEnv* env, jobject stack, ItemKind kind) {
    if (stack == nullptr || env->IsSameObject(stack, nullptr)) {
        return false;
    }
    /* Compared by reference against the vanilla singletons. Item identity in
     * Minecraft is the object itself, so this is exact, needs no strings, and
     * survives the obfuscation that a name comparison would not. */
    if (kind == ItemKind::Sword) {
        /* 1.8.9 has no item tags; the sword identity is ItemSword. */
        jclass swordClass = bridge::Mappings::instance().loadRuntimeClass(env, "net/minecraft/item/ItemSword");
        jobject item = callObject(env, stack, MappedMethod::ItemStack_getItem);
        const bool sword =
            swordClass != nullptr && item != nullptr && env->IsInstanceOf(item, swordClass) == JNI_TRUE;
        if (item != nullptr) env->DeleteLocalRef(item);
        return sword;
    }

    if (kind == ItemKind::Axe) {
        jclass axeClass = mcClass(MappedClass::AxeItem);
        jobject item = callObject(env, stack, MappedMethod::ItemStack_getItem);
        const bool axe =
            axeClass != nullptr && item != nullptr && env->IsInstanceOf(item, axeClass) == JNI_TRUE;
        if (item != nullptr) env->DeleteLocalRef(item);
        return axe;
    }

    static const MappedField kFields[] = {
        MappedField::Items_TOTEM_OF_UNDYING, MappedField::Items_END_CRYSTAL,
        MappedField::Items_RESPAWN_ANCHOR,   MappedField::Items_GLOWSTONE,
        MappedField::Items_MACE,             MappedField::Items_OBSIDIAN,
    };
    jobject wanted = staticObjectField(env, MappedClass::Items, kFields[static_cast<int>(kind)]);
    if (wanted == nullptr) {
        return false;
    }
    jobject item = callObject(env, stack, MappedMethod::ItemStack_getItem);
    const bool same = item != nullptr && env->IsSameObject(item, wanted) == JNI_TRUE;
    if (item != nullptr) env->DeleteLocalRef(item);
    env->DeleteLocalRef(wanted);
    return same;
}

bool Game::holdingItem(JNIEnv* env, jobject player, ItemKind kind, bool offHand) {
    /* No offhand on 1.8.9; both hands read the held stack. */
    jobject stack = callObject(env, player, MappedMethod::LivingEntity_getMainHandStack);
    if (stack == nullptr) {
        return false;
    }
    const bool match = stackIs(env, stack, kind);
    env->DeleteLocalRef(stack);
    return match;
}

int Game::findSlot(JNIEnv* env, jobject player, ItemKind kind, int from, int to) {
    jobject inventory = callObject(env, player, MappedMethod::PlayerEntity_getInventory);
    if (inventory == nullptr) {
        return -1;
    }
    int found = -1;
    for (int slot = from; slot <= to && found < 0; ++slot) {
        jobject stack = callObjectInt(env, inventory, MappedMethod::Inventory_getStack, slot);
        if (stack == nullptr) {
            continue;
        }
        if (stackIs(env, stack, kind)) {
            found = slot;
        }
        env->DeleteLocalRef(stack);
    }
    env->DeleteLocalRef(inventory);
    return found;
}

int Game::selectedSlot(JNIEnv* env, jobject player) {
    jobject inventory = callObject(env, player, MappedMethod::PlayerEntity_getInventory);
    if (inventory == nullptr) {
        return 0;
    }
    const int slot = intField(env, inventory, MappedField::PlayerInventory_selectedSlot);
    env->DeleteLocalRef(inventory);
    return slot;
}

bool Game::swapSlot(JNIEnv* env, jobject player, int slot, int hotbar) {
    jmethodID click = mcMethod(MappedMethod::ClientPlayerInteractionManager_clickSlot);
    if (player == nullptr || click == nullptr || slot < 0 || slot > 35) {
        return false;
    }
    /* windowClick mode 2 is the hotbar swap, and its "button" carries the
     * hotbar index - 1.8.9 has no offhand, so only 0..8 is sent. */
    if (hotbar < 0 || hotbar > 8) {
        return false;
    }
    jobject handler = objectField(env, player, MappedField::PlayerEntity_currentScreenHandler);
    jobject interaction =
        objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    bool sent = false;
    if (handler != nullptr && interaction != nullptr) {
        /* The player container numbers the main rows 9..35 exactly as the
         * inventory does and the hotbar 36..44, so only the hotbar needs
         * translating. windowClick returns the stack that moved; the call
         * reads as an object method and the return is dropped. */
        const jint target = slot < 9 ? 36 + slot : slot;
        jobject moved = static_cast<jobject>(env->CallObjectMethod(
            interaction, click, intField(env, handler, MappedField::ScreenHandler_syncId),
            target, static_cast<jint>(hotbar), static_cast<jint>(2), player));
        sent = !checkException(env, "windowClick");
        if (moved != nullptr) env->DeleteLocalRef(moved);
    }
    if (interaction != nullptr) env->DeleteLocalRef(interaction);
    if (handler != nullptr) env->DeleteLocalRef(handler);
    return sent;
}

void Game::setSelectedSlot(JNIEnv* env, jobject player, int slot) {
    if (slot < 0 || slot > 8) {
        return;
    }
    jobject inventory = callObject(env, player, MappedMethod::PlayerEntity_getInventory);
    if (inventory == nullptr) {
        return;
    }
    /* The server only learns of a hotbar change through packet 09; writing the
     * field alone would change the client's hand and nothing else. 1.8.9's
     * InventoryPlayer carries both the field and the sync setter in one, so
     * the field write is paired with the packet by calling through the
     * player's own setCurrentItem path - which is what the keyboard does. */
    jfieldID field = mcField(MappedField::PlayerInventory_selectedSlot);
    if (field != nullptr) {
        env->SetIntField(inventory, field, slot);
    }
    env->DeleteLocalRef(inventory);
    /* Packet 09 goes out from the send queue directly. */
    jobject interaction =
        objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    if (interaction != nullptr) {
        jclass controllerClass = env->GetObjectClass(interaction);
        jmethodID syncCurrentPlayItem =
            controllerClass != nullptr
                ? env->GetMethodID(controllerClass, "syncCurrentPlayItem", "()V")
                : nullptr;
        if (syncCurrentPlayItem != nullptr) {
            env->CallVoidMethod(interaction, syncCurrentPlayItem);
            checkException(env, "syncCurrentPlayItem");
        } else {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(controllerClass);
        env->DeleteLocalRef(interaction);
    }
}

bool Game::blockIs(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ,
                   BlockKind kind) {
    jclass blockPosClass = mcClass(MappedClass::BlockPos);
    jmethodID blockPosCtor = mcMethod(MappedMethod::BlockPos_ctor);
    jmethodID getBlockState = mcMethod(MappedMethod::BlockView_getBlockState);
    jmethodID getBlock = mcMethod(MappedMethod::AbstractBlockState_getBlock);
    if (world == nullptr || blockPosClass == nullptr || blockPosCtor == nullptr ||
        getBlockState == nullptr || getBlock == nullptr) {
        return false;
    }

    static const MappedField kFields[] = {
        MappedField::Blocks_OBSIDIAN,
        MappedField::Blocks_BEDROCK,
        MappedField::Blocks_RESPAWN_ANCHOR,
    };
    jobject wanted = staticObjectField(env, MappedClass::Blocks, kFields[static_cast<int>(kind)]);
    if (wanted == nullptr) {
        return false;
    }

    jobject position = env->NewObject(blockPosClass, blockPosCtor, blockX, blockY, blockZ);
    if (position == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(wanted);
        return false;
    }
    jobject state = env->CallObjectMethod(world, getBlockState, position);
    env->DeleteLocalRef(position);
    if (checkException(env, "getBlockState") || state == nullptr) {
        env->DeleteLocalRef(wanted);
        return false;
    }
    jobject block = env->CallObjectMethod(state, getBlock);
    env->DeleteLocalRef(state);
    bool same = false;
    if (!checkException(env, "getBlock") && block != nullptr) {
        same = env->IsSameObject(block, wanted) == JNI_TRUE;
        env->DeleteLocalRef(block);
    }
    env->DeleteLocalRef(wanted);
    return same;
}

namespace {

/* The crosshair macros work in block positions and faces, so these four
 * conversions carry most of the JNI they need. */
jobject makeBlockPos(JNIEnv* env, int blockX, int blockY, int blockZ) {
    jclass blockPosClass = mcClass(MappedClass::BlockPos);
    jmethodID ctor = mcMethod(MappedMethod::BlockPos_ctor);
    if (blockPosClass == nullptr || ctor == nullptr) {
        return nullptr;
    }
    jobject position = env->NewObject(blockPosClass, ctor, blockX, blockY, blockZ);
    if (checkException(env, "BlockPos")) {
        return nullptr;
    }
    return position;
}

constexpr MappedField kFaceFields[] = {
    MappedField::Direction_DOWN,  MappedField::Direction_UP,   MappedField::Direction_NORTH,
    MappedField::Direction_SOUTH, MappedField::Direction_WEST, MappedField::Direction_EAST,
};

jobject faceObject(JNIEnv* env, BlockFace face) {
    const int index = clamp(static_cast<int>(face), 0, 5);
    return staticObjectField(env, MappedClass::Direction, kFaceFields[index]);
}

BlockFace faceFromObject(JNIEnv* env, jobject direction) {
    if (direction == nullptr) {
        return BlockFace::Up;
    }
    for (int index = 0; index < 6; ++index) {
        jobject candidate = staticObjectField(env, MappedClass::Direction, kFaceFields[index]);
        if (candidate == nullptr) {
            continue;
        }
        const bool same = env->IsSameObject(candidate, direction) == JNI_TRUE;
        env->DeleteLocalRef(candidate);
        if (same) {
            return static_cast<BlockFace>(index);
        }
    }
    return BlockFace::Up;
}

jobject blockStateAt(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ) {
    jmethodID getBlockState = mcMethod(MappedMethod::BlockView_getBlockState);
    if (world == nullptr || getBlockState == nullptr) {
        return nullptr;
    }
    jobject position = makeBlockPos(env, blockX, blockY, blockZ);
    if (position == nullptr) {
        return nullptr;
    }
    jobject state = env->CallObjectMethod(world, getBlockState, position);
    env->DeleteLocalRef(position);
    if (checkException(env, "getBlockState")) {
        return nullptr;
    }
    return state;
}

/* java.lang.Number and java.util.List are not in the mapping table because they
 * are not Minecraft's; each is needed in exactly one place. */
jmethodID numberIntValue(JNIEnv* env) {
    static jmethodID cached = nullptr;
    if (cached == nullptr) {
        jclass klass = env->FindClass("java/lang/Number");
        if (klass == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        cached = env->GetMethodID(klass, "intValue", "()I");
        env->DeleteLocalRef(klass);
        checkException(env, "Number.intValue");
    }
    return cached;
}

jmethodID listIsEmpty(JNIEnv* env) {
    static jmethodID cached = nullptr;
    if (cached == nullptr) {
        jclass klass = env->FindClass("java/util/List");
        if (klass == nullptr) {
            env->ExceptionClear();
            return nullptr;
        }
        cached = env->GetMethodID(klass, "isEmpty", "()Z");
        env->DeleteLocalRef(klass);
        checkException(env, "List.isEmpty");
    }
    return cached;
}

void pressBinding(JNIEnv* env, jobject client, MappedField binding, bool pressed) {
    jmethodID setPressed = mcMethod(MappedMethod::KeyBinding_setPressed);
    jobject options = objectField(env, client, MappedField::MinecraftClient_options);
    if (options == nullptr) {
        return;
    }
    jobject key = objectField(env, options, binding);
    env->DeleteLocalRef(options);
    if (key == nullptr) {
        return;
    }
    if (setPressed != nullptr) {
        const int keyCode = callInt(env, key, MappedMethod::InputUtilKey_getCode, 0);
        if (keyCode != 0) {
            jclass keyBindingClass = mcClass(MappedClass::KeyBinding);
            if (keyBindingClass != nullptr) {
                env->CallStaticVoidMethod(keyBindingClass, setPressed, keyCode,
                                          pressed ? JNI_TRUE : JNI_FALSE);
                checkException(env, "KeyBinding.setKeyBindState");
            }
        }
    }
    env->DeleteLocalRef(key);
}

}  // namespace

CrosshairTarget Game::crosshair(JNIEnv* env) {
    CrosshairTarget target;
    jobject hit = objectField(env, clientInstance_, MappedField::MinecraftClient_crosshairTarget);
    if (hit == nullptr) {
        return target;
    }

    /* 1.8.9's MovingObjectPosition keeps the type as a public field. */
    jobject type = objectField(env, hit, MappedField::HitResult_typeOfHit);
    jobject blockType =
        staticObjectField(env, MappedClass::HitResultType, MappedField::HitResultType_BLOCK);
    jobject entityType =
        staticObjectField(env, MappedClass::HitResultType, MappedField::HitResultType_ENTITY);
    const bool isBlock =
        type != nullptr && blockType != nullptr && env->IsSameObject(type, blockType) == JNI_TRUE;
    const bool isEntity =
        type != nullptr && entityType != nullptr && env->IsSameObject(type, entityType) == JNI_TRUE;
    if (blockType != nullptr) env->DeleteLocalRef(blockType);
    if (entityType != nullptr) env->DeleteLocalRef(entityType);
    if (type != nullptr) env->DeleteLocalRef(type);

    if (isBlock) {
        target.kind = CrosshairTarget::Kind::Block;
        jobject position = objectField(env, hit, MappedField::HitResult_blockPos);
        if (position != nullptr) {
            target.x = callInt(env, position, MappedMethod::Vec3i_getX);
            target.y = callInt(env, position, MappedMethod::Vec3i_getY);
            target.z = callInt(env, position, MappedMethod::Vec3i_getZ);
            env->DeleteLocalRef(position);
        }
        jobject side = objectField(env, hit, MappedField::BlockHitResult_sideHit);
        target.face = faceFromObject(env, side);
        if (side != nullptr) env->DeleteLocalRef(side);
        jobject point = objectField(env, hit, MappedField::HitResult_hitVec);
        if (point != nullptr) {
            target.point = readVec3(env, point);
            env->DeleteLocalRef(point);
        }
    } else if (isEntity) {
        target.kind = CrosshairTarget::Kind::Entity;
        /* The entity reference lives in the caller's frame, like every other
         * handle the modules are given. */
        target.entity = objectField(env, hit, MappedField::EntityHitResult_entityHit);
        target.entityId = intField(env, target.entity, MappedField::Entity_id, -1);
        jclass slimeClass = mcClass(MappedClass::SlimeEntity);
        target.slime = slimeClass != nullptr && target.entity != nullptr &&
                       env->IsInstanceOf(target.entity, slimeClass) == JNI_TRUE;
    }

    env->DeleteLocalRef(hit);
    return target;
}

int Game::anchorCharges(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ) {
    /* Respawn anchors are post-1.8; there is no block to ask. */
    return -1;
}

bool Game::boxEmpty(JNIEnv* env, jobject world, const Vec3& minimum, const Vec3& maximum) {
    jclass boxClass = mcClass(MappedClass::Box);
    jmethodID boxCtor = mcMethod(MappedMethod::Box_ctor);
    jmethodID checkCollision = mcMethod(MappedMethod::EntityView_getOtherEntities);
    if (world == nullptr || boxClass == nullptr || boxCtor == nullptr ||
        checkCollision == nullptr) {
        return true;
    }
    jobject box = env->NewObject(boxClass, boxCtor, minimum.x, minimum.y, minimum.z, maximum.x,
                                 maximum.y, maximum.z);
    if (checkException(env, "AxisAlignedBB") || box == nullptr) {
        return true;
    }
    /* World.checkNoEntityCollision(box, ignore) is the vanilla "is there
     * room" test, entities included. */
    jboolean empty =
        env->CallBooleanMethod(world, checkCollision, box, nullptr) == JNI_TRUE;
    env->DeleteLocalRef(box);
    if (checkException(env, "checkNoEntityCollision")) {
        return true;
    }
    return empty;
}

bool Game::holdingSword(JNIEnv* env, jobject player) {
    jobject stack = callObject(env, player, MappedMethod::LivingEntity_getMainHandStack);
    return stack != nullptr && stackIs(env, stack, ItemKind::Sword);
}

bool Game::holdingWeapon(JNIEnv* env, jobject player) {
    jobject stack = callObject(env, player, MappedMethod::LivingEntity_getMainHandStack);
    if (stack == nullptr) {
        return false;
    }
    const bool weapon = stackIs(env, stack, ItemKind::Sword) || stackIs(env, stack, ItemKind::Axe);
    env->DeleteLocalRef(stack);
    return weapon;
}

bool Game::holdingShield(JNIEnv* env, jobject player, bool offHand) {
    /* Shields are post-1.8; nothing to hold. */
    return false;
}

bool Game::isZombie(JNIEnv* env, jobject entity) {
    jclass zombieClass = mcClass(MappedClass::ZombieEntity);
    return zombieClass != nullptr && entity != nullptr &&
           env->IsInstanceOf(entity, zombieClass) == JNI_TRUE;
}

int Game::attackTargetId(JNIEnv* env, jobject player) {
    /* 1.8.9's recently-hit target lives on the combat tracker; the accessor is
     * a plain field read off EntityLivingBase. */
    jmethodID attacker = mcMethod(MappedMethod::Attackable_getLastAttacker);
    if (attacker == nullptr || player == nullptr) {
        return -1;
    }
    jobject target = env->CallObjectMethod(player, attacker);
    if (checkException(env, "getLastAttacker") || target == nullptr) {
        return -1;
    }
    const int id = intField(env, target, MappedField::Entity_id, -1);
    env->DeleteLocalRef(target);
    return id;
}

int Game::effectAmplifier(JNIEnv* env, jobject player, bool weakness) {
    jmethodID getEffect = mcMethod(MappedMethod::LivingEntity_getStatusEffect);
    jobject entry = staticObjectField(env, MappedClass::StatusEffects,
                                      weakness ? MappedField::StatusEffects_WEAKNESS
                                               : MappedField::StatusEffects_STRENGTH);
    if (getEffect == nullptr || entry == nullptr || player == nullptr) {
        if (entry != nullptr) env->DeleteLocalRef(entry);
        return -1;
    }
    jobject instance = env->CallObjectMethod(player, getEffect, entry);
    env->DeleteLocalRef(entry);
    if (checkException(env, "getActivePotionEffect") || instance == nullptr) {
        return -1;
    }
    const int amplifier = callInt(env, instance, MappedMethod::StatusEffectInstance_getAmplifier);
    env->DeleteLocalRef(instance);
    return amplifier;
}

void Game::attackBlock(JNIEnv* env, int blockX, int blockY, int blockZ, BlockFace face,
                       bool progressOnly) {
    if (!interactionsAllowed_) {
        return;
    }
    /* clickBlock starts a break; onPlayerDamageBlock continues one and reports
     * whether the server accepted the progress. */
    jmethodID method =
        mcMethod(progressOnly
                     ? MappedMethod::ClientPlayerInteractionManager_updateBlockBreakingProgress
                     : MappedMethod::ClientPlayerInteractionManager_attackBlock);
    jobject interaction =
        objectField(env, clientInstance_, MappedField::MinecraftClient_interactionManager);
    jobject position = makeBlockPos(env, blockX, blockY, blockZ);
    jobject direction = faceObject(env, face);
    if (method != nullptr && interaction != nullptr && position != nullptr &&
        direction != nullptr) {
        env->CallBooleanMethod(interaction, method, position, direction);
        checkException(env, "clickBlock");
    }
    if (interaction != nullptr) env->DeleteLocalRef(interaction);
    if (position != nullptr) env->DeleteLocalRef(position);
    if (direction != nullptr) env->DeleteLocalRef(direction);
}

void Game::breakingParticle(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ,
                            BlockFace face) {
    /* The 1.21 particle helper has no 1.8.9 equivalent; the module's swing is
     * the only feedback a break gets, and the game spawns particles itself
     * through onPlayerDamageBlock. */
    (void)env;
    (void)world;
    (void)blockX;
    (void)blockY;
    (void)blockZ;
    (void)face;
}

void Game::queueClick(JNIEnv* env, bool useKey) {
    /* The game counts clicks in KeyBinding.pressTime and drains them in
     * runTick with onTick(). Adding one there is how a click gets into the
     * tick: the interaction then leaves the client from the same place a real
     * click would, in front of that tick's movement packet. */
    jfieldID times = mcField(MappedField::KeyBinding_timesPressed);
    jobject options = objectField(env, clientInstance_, MappedField::MinecraftClient_options);
    if (times == nullptr || options == nullptr) {
        if (options != nullptr) env->DeleteLocalRef(options);
        return;
    }
    jobject key = objectField(env, options, useKey ? MappedField::GameOptions_useKey
                                                   : MappedField::GameOptions_attackKey);
    env->DeleteLocalRef(options);
    if (key == nullptr) {
        return;
    }
    env->SetIntField(key, times, env->GetIntField(key, times) + 1);
    env->DeleteLocalRef(key);
}

void Game::pressKey(JNIEnv* env, bool useKey, bool pressed) {
    pressBinding(env, clientInstance_,
                 useKey ? MappedField::GameOptions_useKey : MappedField::GameOptions_attackKey,
                 pressed);
}

void Game::openInventory(JNIEnv* env, jobject player) {
    jclass screenClass = mcClass(MappedClass::InventoryScreen);
    jmethodID ctor = mcMethod(MappedMethod::InventoryScreen_ctor);
    jmethodID setScreen = mcMethod(MappedMethod::MinecraftClient_setScreen);
    if (screenClass == nullptr || ctor == nullptr || setScreen == nullptr || player == nullptr) {
        return;
    }
    jobject screen = env->NewObject(screenClass, ctor, player);
    if (checkException(env, "GuiInventory") || screen == nullptr) {
        return;
    }
    env->CallVoidMethod(clientInstance_, setScreen, screen);
    checkException(env, "displayGuiScreen");
    env->DeleteLocalRef(screen);
}

bool Game::inventoryOpen(JNIEnv* env) {
    jclass screenClass = mcClass(MappedClass::InventoryScreen);
    jobject screen = objectField(env, clientInstance_, MappedField::MinecraftClient_currentScreen);
    if (screen == nullptr) {
        return false;
    }
    const bool inventory =
        screenClass != nullptr && env->IsInstanceOf(screen, screenClass) == JNI_TRUE;
    env->DeleteLocalRef(screen);
    return inventory;
}

bool Game::hasLastAttacker(JNIEnv* env, jobject entity) {
    jobject attacker = callObject(env, entity, MappedMethod::Attackable_getLastAttacker);
    if (attacker == nullptr) {
        return false;
    }
    env->DeleteLocalRef(attacker);
    return true;
}

bool Game::isAttackingPlayer(JNIEnv* env, jobject player) {
    jclass playerClass = mcClass(MappedClass::PlayerEntity);
    jobject target = callObject(env, player, MappedMethod::LivingEntity_getAttacking);
    if (target == nullptr) {
        return false;
    }
    const bool isPlayer =
        playerClass != nullptr && env->IsInstanceOf(target, playerClass) == JNI_TRUE;
    env->DeleteLocalRef(target);
    return isPlayer;
}

bool Game::handEmpty(JNIEnv* env, jobject player, bool offHand) {
    jobject stack = callObject(env, player, MappedMethod::LivingEntity_getMainHandStack);
    if (stack == nullptr) {
        return true;
    }
    const bool empty = env->IsSameObject(stack, nullptr) == JNI_TRUE;
    env->DeleteLocalRef(stack);
    return empty;
}

bool Game::holdingBlock(JNIEnv* env, jobject player) {
    jclass blockItemClass = mcClass(MappedClass::BlockItem);
    jobject stack = callObject(env, player, MappedMethod::LivingEntity_getMainHandStack);
    if (stack == nullptr) {
        return false;
    }
    bool placeable = false;
    if (!env->IsSameObject(stack, nullptr)) {
        jobject item = callObject(env, stack, MappedMethod::ItemStack_getItem);
        if (item != nullptr) {
            placeable = blockItemClass != nullptr &&
                        env->IsInstanceOf(item, blockItemClass) == JNI_TRUE;
            env->DeleteLocalRef(item);
        }
    }
    env->DeleteLocalRef(stack);
    return placeable;
}

bool Game::isBlockAir(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ) {
    /* "Air" on 1.8.9 is block identity against Blocks.air; there is no
     * isAir() on the state to call. */
    jclass blockPosClass = mcClass(MappedClass::BlockPos);
    jmethodID blockPosCtor = mcMethod(MappedMethod::BlockPos_ctor);
    jmethodID getBlockState = mcMethod(MappedMethod::BlockView_getBlockState);
    jmethodID getBlock = mcMethod(MappedMethod::AbstractBlockState_getBlock);
    if (world == nullptr || blockPosClass == nullptr || blockPosCtor == nullptr ||
        getBlockState == nullptr || getBlock == nullptr) {
        return false;
    }
    jobject wanted = staticObjectField(env, MappedClass::Blocks, MappedField::Blocks_air);
    if (wanted == nullptr) {
        return false;
    }
    jobject position = env->NewObject(blockPosClass, blockPosCtor, blockX, blockY, blockZ);
    if (position == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(wanted);
        return false;
    }
    jobject state = env->CallObjectMethod(world, getBlockState, position);
    env->DeleteLocalRef(position);
    if (checkException(env, "getBlockState") || state == nullptr) {
        env->DeleteLocalRef(wanted);
        return false;
    }
    jobject block = env->CallObjectMethod(state, getBlock);
    env->DeleteLocalRef(state);
    bool same = false;
    if (!checkException(env, "getBlock") && block != nullptr) {
        same = env->IsSameObject(block, wanted) == JNI_TRUE;
        env->DeleteLocalRef(block);
    }
    env->DeleteLocalRef(wanted);
    return same;
}

bool Game::isBlockReplaceable(JNIEnv* env, jobject world, int blockX, int blockY, int blockZ) {
    /* 1.8.9 has no replaceable flag; the material is what the game checks -
     * Material.replaceable() on the state's block material. Walking the
     * material costs three more hops than the plant list is worth, so the
     * bridge answers "air or one of the replaceable plants". */
    if (isBlockAir(env, world, blockX, blockY, blockZ)) {
        return true;
    }
    const std::string id = blockId(env, world, blockX, blockY, blockZ);
    return id == "tallgrass" || id == "grass" || id == "fern" || id == "deadbush" ||
           id == "water" || id == "flowing_water" || id == "lava" || id == "flowing_lava";
}

}  // namespace cold::game
