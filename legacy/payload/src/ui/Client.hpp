#pragma once

#include <windows.h>

#include <memory>
#include <string>

#include "bridge/Minecraft.hpp"
#include "core/Auth.hpp"
#include "game/Projection.hpp"
#include "modules/Module.hpp"
#include "render/Backdrop.hpp"

namespace cold::ui {

class ClickGui;
class Hud;
class Radar;

/* Owns the client's lifetime and the per-frame pipeline.
 *
 * Bootstrap runs on the injected loader thread: attach to the JVM, resolve
 * mappings, build the atlases, register modules and read the config. Nothing
 * touching OpenGL happens there, because the context belongs to the render
 * thread; those resources are created on the first frame instead. */
class Client {
public:
    static Client& instance();

    bool bootstrap(HMODULE module);
    void requestShutdown() { shutdownRequested_ = true; }
    bool shutdownRequested() const { return shutdownRequested_; }
    void shutdown();

    /* Entry point from the glfwSwapBuffers detour. Runs the frame under an
     * SEH guard; see guardedFrame. */
    void onFrame(void* window);
    /* The __try frame itself: a function whose only local is the fault code,
     * because SEH cannot coexist with any object that needs unwinding (C2712).
     * Returns 0 when the frame completed. */
    unsigned long guardedFrame(void* window);
    /* Entry point from the glfwGetTime detour, which Minecraft calls between
     * the client tick and the world render. Kept to a boolean test unless a
     * silent rotation is actually in flight. */
    void onNativeTimeQuery();

    ClickGui& gui() { return *gui_; }
    Hud& hud() { return *hud_; }
    Radar& radar() { return *radar_; }
    /* The HUD only exists after bootstrap, and the script manager can have
     * something to say before that. */
    bool hasHud() const { return hud_ != nullptr; }
    render::Backdrop& backdrop() { return backdrop_; }
    const game::FrameState& state() const { return state_; }
    const game::Projection& projection() const { return projection_; }
    const std::wstring& directory() const { return directory_; }
    const auth::Session& session() const { return session_; }
    float time() const { return time_; }

    /* Rolling cost of the whole overlay pass, in microseconds. Reported by the
     * control channel so a run can prove what the client costs the game rather
     * than inferring it from a frame counter the game itself throttles. */
    double overlayMicros() const { return overlayMicros_; }
    /* Client ticks the payload has observed. Used by the control channel to
     * prove the tick edge is firing rather than inferring it from behaviour. */
    long long tickCount() const { return tickCount_; }
    long long frameCount() const { return frameCount_; }

private:
    Client() = default;

    bool bindGame(JNIEnv* env);
    /* Noinline: the SEH __try frame in onFrame must stay free of destructors,
     * and an inlined body would drag every RAII object of the frame into it
     * (C2712). */
    [[msvc::noinline]] void renderFrame(void* window);
    bool createRenderResources();
    /* Recreates anything the game deleted out from under the overlay. A
     * singleplayer world switch or a resource reload wipes every GL object
     * the shared context ever handed out; the overlay's own names are just
     * integers, so "deleted" is invisible until a draw samples texture 0.
     * Cheap to check, and it makes the atlas wipe self-healing. */
    void ensureGpuResources();
    void dispatchInput(modules::Context& context);

    HMODULE module_ = nullptr;
    std::wstring directory_;
    std::wstring configPath_;
    auth::Session session_;

    bool bootstrapped_ = false;
    bool gameReady_ = false;
    bool bindGaveUp_ = false;
    unsigned long long bindDeadline_ = 0;
    bool renderReady_ = false;
    bool renderFailed_ = false;
    bool shutdownRequested_ = false;
    bool loggedFirstFrame_ = false;
    /* True between entering and leaving the frame body, so the fault handler
     * can tell "our draw faulted, recoverable" from "the fault landed inside
     * the game's own swap call, do not touch anything". */
    bool insideGameSwap_ = false;
    bool hadWorld_ = false;
    /* Consecutive between-frame faults before the overlay gives up - two
     * rebuilds in a row means the context itself is unhealthy, not one stale
     * texture. */
    int faultGuard_ = 0;

    game::FrameState state_;
    game::Projection projection_;
    render::Backdrop backdrop_;

    std::unique_ptr<ClickGui> gui_;
    std::unique_ptr<Hud> hud_;
    std::unique_ptr<Radar> radar_;

    int lastPlayerAge_ = -1;
    bool moduleTickPending_ = false;
    int lastObservedAge_ = -1;
    double lastTickSeconds_ = 0.0;
    float smoothedFrameSeconds_ = 1.0f / 60.0f;
    long long tickCount_ = 0;
    long long frameCount_ = 0;
    double overlayMicros_ = 0.0;
    double lastFrameTime_ = 0.0;
    float time_ = 0.0f;
    float deltaTime_ = 0.0f;
};

inline Client& client() { return Client::instance(); }

}  // namespace cold::ui
