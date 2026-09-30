#include "hooks/Hooks.hpp"

#include <windows.h>

#include "MinHook.h"
#include "core/Log.hpp"
#include "game/RotationEngine.hpp"
#include "hooks/Window.hpp"
#include "ui/Client.hpp"

namespace cold::hooks {
namespace {

constexpr std::string_view kScope = "hooks";

using WglSwapBuffers = BOOL(WINAPI*)(HDC);

WglSwapBuffers g_originalSwapBuffers = nullptr;
bool g_installed = false;
bool g_minHookReady = false;

/* Lunar 1.8.9 presents through LWJGL 2's Display, which ends at opengl32's
 * wglSwapBuffers with the device context as its only argument. The game has
 * already drawn world and HUD when it lands here, the legacy compatibility
 * context is current on this thread, and the next client tick is moments
 * away - the same frame boundary glfwSwapBuffers gave on 1.21. */
BOOL WINAPI hookedSwapBuffers(HDC deviceContext) {
    void* window = window::fromDeviceContext(deviceContext);
    ui::client().onFrame(window);
    if (g_originalSwapBuffers != nullptr) {
        return g_originalSwapBuffers(deviceContext);
    }
    return FALSE;
}

}  // namespace

bool installed() { return g_installed; }

bool ensureMinHook() {
    if (g_minHookReady) {
        return true;
    }
    const MH_STATUS status = MH_Initialize();
    if (status != MH_OK && status != MH_ERROR_ALREADY_INITIALIZED) {
        COLD_ERROR(kScope, "MH_Initialize returned %d", status);
        return false;
    }
    g_minHookReady = true;
    return true;
}

bool install() {
    if (g_installed) {
        return true;
    }
    log::Stage stage(kScope, "install frame hook");

    const HMODULE opengl32 = GetModuleHandleW(L"opengl32.dll");
    if (opengl32 == nullptr) {
        stage.fail("opengl32.dll is not loaded; no GL context exists yet");
        return false;
    }
    const WglSwapBuffers target =
        reinterpret_cast<WglSwapBuffers>(GetProcAddress(opengl32, "wglSwapBuffers"));
    if (target == nullptr) {
        stage.fail("opengl32.dll does not export wglSwapBuffers");
        return false;
    }

    if (!ensureMinHook()) {
        stage.fail("MinHook would not initialise");
        return false;
    }

    const MH_STATUS status =
        MH_CreateHook(reinterpret_cast<void*>(target),
                      reinterpret_cast<void*>(&hookedSwapBuffers),
                      reinterpret_cast<void**>(&g_originalSwapBuffers));
    if (status != MH_OK) {
        stage.fail(log::format("MH_CreateHook returned %d", status));
        return false;
    }
    if (MH_EnableHook(reinterpret_cast<void*>(target)) != MH_OK) {
        stage.fail(log::format("MH_EnableHook returned %d", status));
        return false;
    }

    /* 1.8.9 has no GLFW and no cheap tick-boundary probe to detour: silent
     * rotations run in camera mode, where the engine aims the real view and
     * lets the packet carry it. The setter below is what RotationEngine reads
     * to know that. */
    game::RotationEngine::instance().setSilentAvailable(false);
    COLD_INFO(kScope, "wglSwapBuffers hooked at %p", reinterpret_cast<void*>(target));

    g_installed = true;
    return true;
}

void uninstall() {
    if (!g_installed) {
        return;
    }
    const HMODULE opengl32 = GetModuleHandleW(L"opengl32.dll");
    if (opengl32 != nullptr) {
        const void* target = reinterpret_cast<const void*>(
            GetProcAddress(opengl32, "wglSwapBuffers"));
        if (target != nullptr) {
            MH_DisableHook(const_cast<void*>(target));
            MH_RemoveHook(const_cast<void*>(target));
        }
    }
    g_installed = false;

    /* Give any thread already inside the trampoline time to leave before the
     * DLL unmaps; MinHook suspends threads but the callee is ours. */
    Sleep(120);
    if (g_minHookReady) {
        MH_Uninitialize();
        g_minHookReady = false;
    }
    COLD_INFO(kScope, "frame hook removed");
}

}  // namespace cold::hooks
