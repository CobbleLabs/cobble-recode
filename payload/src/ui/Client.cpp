#include "ui/Client.hpp"

#include <shlobj.h>
#include <shlwapi.h>

#include <cstring>

#include "assets/AssetStore.hpp"
#include "bridge/Jvm.hpp"
#include "bridge/Mappings.hpp"
#include "core/Auth.hpp"
#include "core/Log.hpp"
#include "core/TestHook.hpp"
#include "core/Time.hpp"
#include "game/Raycast.hpp"
#include "game/PlayerRadar.hpp"
#include "game/RotationEngine.hpp"
#include "hooks/Hooks.hpp"
#include "hooks/Input.hpp"
#include "modules/ModuleManager.hpp"
#include "modules/client/ClickGuiModule.hpp"
#include "modules/client/RadarModule.hpp"
#include "render/Gl.hpp"
#include "render/Renderer.hpp"
#include "script/ScriptManager.hpp"
#include "ui/ClickGui.hpp"
#include "ui/Hud.hpp"
#include "ui/Radar.hpp"
#include "ui/Theme.hpp"

namespace cold::ui {
namespace {

constexpr std::string_view kScope = "client";
constexpr unsigned kJvmTimeoutMillis = 60000;
constexpr unsigned kGameWaitMillis = 120000;
/* How far past a tick a frame has to be before an interaction may go out on
 * it. A tick is fifty milliseconds and the risky window is the moment right
 * after the movement packet, so a fifth of a tick is plenty of clearance and
 * costs nothing at any framerate a player would tolerate. */
constexpr double kInteractionGuardSeconds = 0.010;

/* Kept out of the SEH frame: building the message needs objects that would
 * require unwinding, which __try forbids in the same function. */
void reportFrameFault(unsigned long code) {
    /* Where did it happen? The faulting instruction, resolved to a module and
     * offset, is the difference between a mystery and a fix. */
    CONTEXT context = {};
    RtlCaptureContext(&context);
#ifdef _WIN64
    void* faultingIp = reinterpret_cast<void*>(context.Rip);
#else
    void* faultingIp = reinterpret_cast<void*>(context.Eip);
#endif
    HMODULE module = nullptr;
    char moduleNameBuffer[MAX_PATH] = "?";
    const char* moduleName = moduleNameBuffer;
    if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                               GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           reinterpret_cast<LPCWSTR>(faultingIp), &module)) {
        GetModuleFileNameA(module, moduleNameBuffer, MAX_PATH);
        const char* slash = std::strrchr(moduleNameBuffer, '\\');
        if (slash != nullptr) moduleName = slash + 1;
    }
    uintptr_t imageBase = reinterpret_cast<uintptr_t>(module);
    const long long offset =
        module != nullptr
            ? static_cast<long long>(reinterpret_cast<uintptr_t>(faultingIp) - imageBase)
            : 0;

    COLD_ERROR(kScope, "unhandled exception 0x%08lX at %hs+0x%llx (ip %p); overlay disabled", code,
               moduleName, offset, faultingIp);
}

std::wstring moduleDirectory(HMODULE module) {
    wchar_t path[MAX_PATH] = {};
    if (GetModuleFileNameW(module, path, MAX_PATH) == 0) {
        return L".";
    }
    PathRemoveFileSpecW(path);
    return path;
}

bool fileMissing(const std::wstring& path) {
    return GetFileAttributesW(path.c_str()) == INVALID_FILE_ATTRIBUTES;
}

void copyIfMissing(const std::wstring& from, const std::wstring& to) {
    if (!fileMissing(from) && fileMissing(to)) {
        CopyFileW(from.c_str(), to.c_str(), TRUE);
    }
}

/* Everything the client owns lives under %APPDATA%\cobble now: configs, the
 * control channel, logs and scripts. The DLL's own folder is only where new
 * users arrive from. */
std::wstring dataDirectory(const std::wstring& fallback) {
    wchar_t appdata[MAX_PATH] = {};
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, appdata))) {
        return fallback;
    }
    const std::wstring dir = std::wstring(appdata) + L"\\cobble";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir;
}

void migrateLegacyData(const std::wstring& from, const std::wstring& to) {
    if (from == to) {
        return;
    }
    CreateDirectoryW((to + L"\\scripts").c_str(), nullptr);
    /* Everything a previous build saved carried the coldwtf name; the files
     * arrive under the cobble one, and only when the new home has none yet -
     * a returning user keeps whatever is already there. */
    copyIfMissing(from + L"\\coldwtf.cfg", to + L"\\cobble.cfg");
    copyIfMissing(from + L"\\coldwtf.scripts.cfg", to + L"\\cobble.scripts.cfg");
    WIN32_FIND_DATAW data{};
    HANDLE handle = FindFirstFileW((from + L"\\coldwtf-*.cfg").c_str(), &data);
    if (handle != INVALID_HANDLE_VALUE) {
        do {
            std::wstring name = data.cFileName;
            name.replace(0, 7, L"cobble");  // coldwtf-<name>.cfg -> cobble-<name>.cfg
            copyIfMissing(from + L"\\" + data.cFileName, to + L"\\" + name);
        } while (FindNextFileW(handle, &data) != FALSE);
        FindClose(handle);
    }
    HANDLE scripts = FindFirstFileW((from + L"\\scripts\\*.lua").c_str(), &data);
    if (scripts != INVALID_HANDLE_VALUE) {
        do {
            copyIfMissing(from + L"\\scripts\\" + data.cFileName,
                          to + L"\\scripts\\" + data.cFileName);
        } while (FindNextFileW(scripts, &data) != FALSE);
        FindClose(scripts);
    }
}

/* The client's previous data home was %APPDATA%\coldwtf; pull anything the
 * user built up there into the new one rather than starting them empty. */
void migrateLegacyAppData(const std::wstring& to) {
    wchar_t appdata[MAX_PATH] = {};
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, appdata))) {
        return;
    }
    const std::wstring legacy = std::wstring(appdata) + L"\\coldwtf";
    if (!fileMissing(legacy)) {
        migrateLegacyData(legacy, to);
    }
}

}  // namespace

Client& Client::instance() {
    static Client client;
    return client;
}

bool Client::bootstrap(HMODULE module) {
    if (bootstrapped_) {
        return true;
    }
    module_ = module;
    const std::wstring moduleDir = moduleDirectory(module);
    directory_ = dataDirectory(moduleDir);
    migrateLegacyData(moduleDir, directory_);
    migrateLegacyAppData(directory_);
    configPath_ = directory_ + L"\\cobble.cfg";

    log::open(directory_);
    test::control().configure(directory_);
    COLD_INFO(kScope, "Cobble Recode payload starting in %ls", directory_.c_str());

    if (!auth::authenticate(directory_, session_)) {
        return false;
    }

    if (!assets::store().load(module)) {
        COLD_ERROR(kScope, "embedded assets unavailable, aborting");
        return false;
    }

    bridge::Jvm& jvm = bridge::Jvm::instance();
    if (!jvm.acquire(kJvmTimeoutMillis)) {
        return false;
    }

    /* Nothing Minecraft-facing happens here. Class discovery needs a thread
     * with Java frames on its stack, which this one does not have; the first
     * frame runs it instead. See bridge/Jvm.hpp. */
    if (!render::renderer().prepare()) {
        COLD_ERROR(kScope, "atlas preparation failed");
        return false;
    }

    modules::modules().registerModules();
    modules::modules().loadConfig(configPath_);

    gui_ = std::make_unique<ClickGui>();
    hud_ = std::make_unique<Hud>();
    radar_ = std::make_unique<Radar>();

    /* Layout lands after construction: panels and HUD elements read their
     * stored positions from the same document the modules just read. */
    gui_->loadLayout(modules::modules().config());
    hud_->loadLayout(modules::modules().config());

    /* After the config, because a script module is registered as it loads and
     * is handed whatever the config already held for it. */
    script::manager().initialise(directory_);

    if (!hooks::install()) {
        COLD_ERROR(kScope, "frame hook could not be installed");
        return false;
    }

    bootstrapped_ = true;
    bindDeadline_ = GetTickCount64() + kGameWaitMillis;
    COLD_INFO(kScope, "loader done; waiting for the first frame to bind Minecraft");
    return true;
}

bool Client::bindGame(JNIEnv* env) {
    if (gameReady_) {
        return true;
    }
    if (GetTickCount64() > bindDeadline_) {
        if (!bindGaveUp_) {
            bindGaveUp_ = true;
            COLD_ERROR(kScope, "Minecraft never became reachable; payload is dormant");
        }
        return false;
    }
    if (!bridge::Mappings::instance().resolve(env)) {
        return false;
    }
    if (!game::Game::instance().initialise(env)) {
        return false;
    }
    gameReady_ = true;
    COLD_INFO(kScope, "ready - press the ClickGUI bind to open the menu");
    return true;
}

bool Client::createRenderResources() {
    log::Stage stage(kScope, "create render resources");
    if (!render::renderer().createResources()) {
        stage.fail("renderer setup failed");
        return false;
    }
    if (!backdrop_.createResources()) {
        COLD_WARN(kScope, "backdrop blur unavailable; panels fall back to solid fills");
    }
    return true;
}

void Client::ensureGpuResources() {
    /* A world switch deletes every GL object in the shared context: the font
     * and icon atlases, the programs, the buffers. The overlay's names stay
     * "valid" integers that draw nothing but garbage, which is exactly the
     * broken-font-after-leaving-a-world report. Poll the three long-lived
     * objects a frame needs and rebuild the moment any of them is gone. */
    if (render::renderer().resourcesValid()) {
        return;
    }
    COLD_WARN(kScope, "GL resources vanished (world switch?); rebuilding");
    render::renderer().destroy();
    backdrop_.destroy();
    if (!createRenderResources()) {
        renderFailed_ = true;
    }
}

void Client::dispatchInput(modules::Context& context) {
    hooks::Input& input = hooks::input();
    input.refresh();

    auto* guiModule = modules::modules().get<modules::ClickGuiModule>();
    const int guiKey = guiModule != nullptr ? guiModule->key() : 0;
    auto* radarModule = modules::modules().get<modules::RadarModule>();
    const int radarKey = radarModule != nullptr ? radarModule->key() : 0;

    /* Either window owns the keyboard while it is up - both have a field you
     * type into, and a module bind that fired mid-search would be a surprise.
     * They are never up together: opening one closes the other. */
    const bool overlayOwnsKeys = gui_->interactive() || radar_->interactive();
    hud_->setMenuOpen(gui_->interactive() || radar_->interactive());

    for (const hooks::InputEvent& event : input.drain()) {
        if (event.kind == hooks::InputEvent::Kind::Key && event.action == hooks::kPress) {
            if (!overlayOwnsKeys) {
                if (!context.state->screenOpen) {
                    modules::modules().handleKey(event.code, context);
                }
            } else if (gui_->interactive() && !gui_->capturingKey() && event.code == guiKey &&
                       guiKey != 0 && guiModule != nullptr) {
                guiModule->setEnabled(false, &context);
            } else if (radar_->interactive() && event.code == radarKey && radarKey != 0 &&
                       radarModule != nullptr) {
                radarModule->setEnabled(false, &context);
            } else if (radar_->interactive() && event.code == guiKey && guiKey != 0 &&
                       guiModule != nullptr) {
                /* The other window's bind still works from inside this one, and
                 * opening it closes this one - otherwise the only way across is
                 * two presses with a look at the world in between. */
                guiModule->setEnabled(true, &context);
            } else if (gui_->interactive() && !gui_->capturingKey() && event.code == radarKey &&
                       radarKey != 0 && radarModule != nullptr) {
                radarModule->setEnabled(true, &context);
            }
        }
        gui_->handleEvent(event, context);
        radar_->handleEvent(event, context);
        hud_->handleEvent(event, context);

        /* With the menu open, a click that missed the window is offered to the
         * script HUD elements: that is when they are draggable, and it is the
         * only moment they compete with anything for the pointer. */
        if (gui_->interactive()) {
            const float scaleX = state_.logicalWidth > 0
                                     ? static_cast<float>(state_.windowWidth) /
                                           static_cast<float>(state_.logicalWidth)
                                     : 1.0f;
            const float scaleY = state_.logicalHeight > 0
                                     ? static_cast<float>(state_.windowHeight) /
                                           static_cast<float>(state_.logicalHeight)
                                     : 1.0f;
            const float pointerX = static_cast<float>(event.x) * scaleX;
            const float pointerY = static_cast<float>(event.y) * scaleY;
            const float unit = state_.scaledWidth > 0
                                   ? static_cast<float>(state_.windowWidth) /
                                         static_cast<float>(state_.scaledWidth)
                                   : 2.0f;
            if (event.kind == hooks::InputEvent::Kind::MouseButton &&
                event.code == hooks::kMouseLeft) {
                if (event.action == hooks::kPress) {
                    if (!gui_->contains(pointerX, pointerY)) {
                        script::manager().hudPress(pointerX, pointerY, unit);
                    }
                } else {
                    script::manager().hudRelease();
                }
            } else if (event.kind == hooks::InputEvent::Kind::MouseMove) {
                script::manager().hudDrag(pointerX, pointerY, unit);
            }
        }

        /* Scripts see the input the game would have seen. While the menu owns
         * the cursor they see none of it, which is the same rule the module
         * binds follow. */
        if (gui_->interactive()) {
            continue;
        }
        switch (event.kind) {
            case hooks::InputEvent::Kind::Key:
                if (event.action != hooks::kRepeat) {
                    script::manager().key(event.code, event.action == hooks::kPress,
                                          event.modifiers);
                }
                break;
            case hooks::InputEvent::Kind::Char:
                script::manager().charTyped(static_cast<unsigned int>(event.code));
                break;
            case hooks::InputEvent::Kind::MouseButton:
                script::manager().mouse(event.code, event.action == hooks::kPress, event.x,
                                        event.y);
                break;
            case hooks::InputEvent::Kind::MouseMove:
                script::manager().mouseMove(event.x, event.y);
                break;
            case hooks::InputEvent::Kind::Scroll:
                script::manager().scroll(event.x, event.y);
                break;
            default:
                break;
        }
    }

    /* Hand the cursor over through Minecraft's own Mouse. It re-centres the
     * pointer and resets the anchor the camera delta is measured against, so
     * closing the menu cannot fling the player round to wherever the pointer
     * had wandered. */
    if (input.consumeCursorChange()) {
        game::Game::instance().setCursorReleased(context.env, input.capturing());
        const double centreX = context.state->logicalWidth * 0.5;
        const double centreY = context.state->logicalHeight * 0.5;
        game::Game::instance().anchorCursor(context.env, centreX, centreY);
    }
}

void Client::onFrame(void* window) {
    if (!bootstrapped_ || renderFailed_) {
        return;
    }
    const unsigned long faultCode = guardedFrame(window);
    if (faultCode == 0) {
        faultGuard_ = 0;
        return;
    }
    reportFrameFault(faultCode);
    /* A fault that landed inside the game's own swap call leaves its state
     * undefined; recovering from that would be gambling with the game. Two
     * consecutive faults mean the context itself is unhealthy rather than one
     * stale texture. Either way: dead overlay, live game. */
    if (faultGuard_ >= 2 || insideGameSwap_) {
        renderFailed_ = true;
        /* The input hooks are still installed and still swallowing everything
         * if the menu happened to be open. Give the game its input back rather
         * than leaving it deaf. */
        hooks::input().setCapturing(false);
        hooks::input().detach();
        COLD_ERROR(kScope, "overlay disabled");
        return;
    }
    ++faultGuard_;
    /* A fault *between* swaps is different: the world is intact and the fault
     * usually came from GL objects the game just deleted under a world switch.
     * Reset the overlay's own state - GL included, because a fault mid-draw
     * leaves our program, matrices and atlas bindings live and every later
     * Minecraft font glyph would sample our texture - then rebuild from
     * scratch on the next frame. */
    hooks::input().setCapturing(false);
    game::radar().clear();
    gl::resetFixedFunctionState();
    render::renderer().destroy();
    backdrop_.destroy();
    renderReady_ = false;
    lastFrameTime_ = nowSeconds();
    COLD_WARN(kScope, "recovered from a frame fault; rebuilding resources");
}

/* The SEH frame: deliberately free of locals that need unwinding, so MSVC
 * accepts __try here. Everything with a destructor lives in renderFrame. */
unsigned long Client::guardedFrame(void* window) {
    unsigned long faultCode = 0;
    __try {
        insideGameSwap_ = true;
        renderFrame(window);
        insideGameSwap_ = false;
    } __except (insideGameSwap_ = false,
                faultCode = GetExceptionCode(), EXCEPTION_EXECUTE_HANDLER) {
        faultCode = faultCode == 0 ? 1u : faultCode;
    }
    return faultCode;
}

void Client::onNativeTimeQuery() {
    /* Called several times a frame, so the common case must not touch JNI. */
    if (!gameReady_ || renderFailed_ || !game::RotationEngine::instance().silentActive()) {
        return;
    }
    game::RotationEngine::instance().onPostTick(bridge::Jvm::instance().env());
}

void Client::renderFrame(void* window) {
    JNIEnv* env = bridge::Jvm::instance().env();
    if (env == nullptr) {
        return;
    }

    if (!gameReady_ && !bindGame(env)) {
        return;
    }

    if (!renderReady_) {
        if (!createRenderResources()) {
            renderFailed_ = true;
            return;
        }
        /* The window handle arrives with the frame: the device context the
         * swap hook is handed belongs to exactly one top-level window, which
         * is the one LWJGL created. No input hook can go in earlier. */
        hooks::input().attach(window);
        gui_->build();
        renderReady_ = true;
        lastFrameTime_ = nowSeconds();
    } else {
        ensureGpuResources();
    }

    if (shutdownRequested_) {
        /* Hand the cursor back before anything is torn down. detach() only
         * marks it dirty, and the frame that would act on that never runs
         * because this branch stops the overlay for good - so the player would
         * be left in-world with the cursor unlocked and the camera dead. */
        if (hooks::input().capturing()) {
            game::Game::instance().setCursorReleased(env, false);
            game::Game::instance().anchorCursor(env, state_.logicalWidth * 0.5,
                                                state_.logicalHeight * 0.5);
        }
        hooks::input().detach();
        render::renderer().destroy();
        backdrop_.destroy();
        renderReady_ = false;
        renderFailed_ = true;
        COLD_INFO(kScope, "render resources released");
        return;
    }

    const double now = nowSeconds();
    deltaTime_ = clamp(static_cast<float>(now - lastFrameTime_), 0.0001f, 0.25f);
    lastFrameTime_ = now;
    time_ = static_cast<float>(now);
    const double overlayStart = now;

    /* Everything below allocates JNI locals from one frame-sized pool, so a
     * session cannot leak JNI references. The capacity has to cover the worst
     * multiplayer tick, not a quiet singleplayer one: up to 320 captured
     * entities at roughly a dozen reads each plus the radar walking every
     * player on the server, which is well past 4000 references. A frame sized
     * for the living room overflows exactly when a server fills up - the
     * pending LocalRefOverflow then poisons every later call and takes the
     * game down with it. */
    bridge::LocalFrame frame(env, 8192);
    if (!frame.ok() || !game::Game::instance().capture(env, state_)) {
        return;
    }

    if (!loggedFirstFrame_) {
        loggedFirstFrame_ = true;
        COLD_INFO(kScope, "first frame: %dx%d framebuffer, %d fps", state_.windowWidth,
                  state_.windowHeight, state_.fps);
    }

    /* Leaving a world must reset what the capture itself cannot: the tick
     * bookkeeping is keyed on the player age, and the next world starts that
     * counter from zero - without this the first tick in a fresh world looks
     * like thousands of elapsed ticks. */
    if (hadWorld_ && !state_.inWorld) {
        lastPlayerAge_ = -1;
        lastObservedAge_ = -1;
        moduleTickPending_ = false;
        game::radar().clear();
        COLD_DEBUG(kScope, "left the world; per-world state reset");
    }
    hadWorld_ = state_.inWorld;

    ++frameCount_;
    const bool tickEdge = state_.player.valid && state_.player.age != lastPlayerAge_;
    if (state_.player.valid) {
        lastPlayerAge_ = state_.player.age;
    }
    if (tickEdge) {
        /* Below about 20 fps Minecraft catches up by running several ticks in
         * one frame, so the age can jump by more than one. Count what actually
         * happened; the client still acts once per frame, because that is the
         * only point at which it can see the world. */
        const int elapsedTicks = state_.player.age - lastObservedAge_;
        tickCount_ += (lastObservedAge_ < 0 || elapsedTicks <= 0) ? 1 : elapsedTicks;
        lastObservedAge_ = state_.player.age;
        lastTickSeconds_ = now;
    }
    smoothedFrameSeconds_ += (deltaTime_ - smoothedFrameSeconds_) * 0.1f;
    /* A silent rotation may only be written on the last frame before the next
     * tick: any earlier and a frame gets drawn with the borrowed camera, any
     * later and the movement packet has already gone without it.
     *
     * Camera.getLastTickProgress is how far through the current tick the frame
     * that just rendered was, so the tick lands during the next frame exactly
     * when less than one frame of it is left. Estimating the same thing from
     * the wall clock works at ordinary framerates and falls apart above a
     * thousand, where the error in "when did the tick happen" is a whole frame
     * and half the rotations miss. The clock is only the fallback for a frame
     * with no camera to ask. */
    const float frameFraction = static_cast<float>(smoothedFrameSeconds_) / 0.05f;
    const bool tickImminent =
        state_.tickProgress > 0.0f
            ? state_.tickProgress + frameFraction >= 1.0f
            : (now - lastTickSeconds_) >= (0.05 - static_cast<double>(smoothedFrameSeconds_) * 2.5);

    /* How far into the tick window the frame that just rendered was, which is
     * what decides whether an interaction sent now lands safely in front of the
     * next movement packet or suspiciously behind the last one. The camera's
     * tick progress says it directly and is right at any framerate; the wall
     * clock is only the fallback for a frame with no camera to ask, and it
     * measures from the frame that *observed* the tick, which is already a
     * frame late. See Game::setInteractionsAllowed. */
    const double intoTick = state_.tickProgress > 0.0f
                                ? static_cast<double>(state_.tickProgress) * 0.05
                                : (now - lastTickSeconds_);
    game::Game::instance().setInteractionsAllowed(intoTick >= kInteractionGuardSeconds);

    projection_.update(state_, state_.windowWidth, state_.windowHeight);

    modules::Context context;
    context.env = env;
    context.state = &state_;
    context.projection = &projection_;
    context.deltaTime = deltaTime_;
    context.time = time_;
    context.tickEdge = tickEdge;

    /* Services whatever the scripts asked for last frame - a reload, a script
     * switched on - before anything walks the module list, then raises the
     * world and entity events. */
    script::manager().beginFrame(context);

    dispatchInput(context);

    if (tickEdge) {
        game::RotationEngine::instance().tick(env, state_);
        moduleTickPending_ = true;
    }
    /* Modules act on the last frame before the next tick, not on the first
     * frame after the last one. Vanilla sends an interaction from
     * handleInputEvents and the movement packet from the player tick that
     * follows it, so a click that goes out just after a tick arrives after that
     * tick's movement packet - which is exactly what Grim's Post check is
     * looking for. Waiting for the frame the tick is about to land on puts the
     * click back in front of the movement packet where the game puts it.
     *
     * This has to happen on every tick, not on most of them: the rotation
     * engine drops its aim the moment a tick goes by without a request, so a
     * module that skips one starts again from the camera and never converges.
     * Anything that wants a stricter guarantee about where its packet lands in
     * the tick sends it through the game's own binding instead - see
     * Game::queueClick and knowledge base note grim-rotation-ordering. */
    if (moduleTickPending_ && tickImminent) {
        moduleTickPending_ = false;
        /* The block cache answers from the last tick otherwise, which is fine
         * for a raycast inside one search and wrong the moment anything places
         * or breaks a block - the cell it just changed would read as it was. */
        game::resetBlockCache();
        modules::modules().tick(context);
        /* The radar tracks whether or not its window is open: a history that
         * starts when you open it is empty exactly when you want it. What the
         * window changes is how much of the refresh budget it gets. */
        if (state_.inWorld && state_.player.valid) {
            game::radar().update(env, state_, time_, radar_->trackBudget(), radar_->focus());
        } else {
            game::radar().clear();
        }
        script::manager().tick();
    }
    game::RotationEngine::instance().updateSilent(env, state_, tickImminent);
    modules::modules().advanceAnimations(deltaTime_);

    gl::StateGuard guard;

    if (gui_->visible() && gui_->blurEnabled()) {
        backdrop_.capture(state_.windowWidth, state_.windowHeight, 1.0f,
                          Color::rgb(0x0A0D12, 0.35f));
    }

    render::Renderer& gfx = render::renderer();
    gfx.beginFrame(state_.windowWidth, state_.windowHeight, time_);
    modules::modules().render(context);
    script::manager().render();
    hud_->render(context);
    script::manager().renderHud(context, gui_->interactive());
    gui_->render(context);
    radar_->render(context);
    gfx.endFrame();

    /* Measured before the control channel runs, so a screenshot's glReadPixels
     * stall never lands in the number. */
    const double elapsed = (nowSeconds() - overlayStart) * 1000000.0;
    overlayMicros_ += (elapsed - overlayMicros_) * 0.05;

    test::control().poll(context, state_.windowWidth, state_.windowHeight);
    script::manager().endFrame();
}

void Client::shutdown() {
    if (!bootstrapped_) {
        return;
    }
    /* Layout first: the panels and HUD elements write their sections into
     * the live document, which saveConfig then carries to disk. */
    gui_->saveLayout(modules::modules().config());
    hud_->saveLayout(modules::modules().config());
    modules::modules().saveConfig(configPath_);
    /* After the config is written: the settings a script module owns are saved
     * with everything else, and unloading takes those modules away. */
    script::manager().shutdown();
    hooks::uninstall();
    COLD_INFO(kScope, "Cobble Recode unloaded");
    log::close();
    bootstrapped_ = false;
}

}  // namespace cold::ui
