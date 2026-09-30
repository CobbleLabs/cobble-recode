#include <windows.h>

#include "bridge/Jvm.hpp"
#include "core/Log.hpp"
#include "ui/Client.hpp"

namespace {

constexpr std::string_view kScope = "main";

/* Unload key. Held for a moment rather than tapped, so it cannot fire from a
 * stray keypress during a fight. */
constexpr int kUnloadKey = VK_END;
constexpr ULONGLONG kUnloadHoldMillis = 600;

HMODULE g_module = nullptr;

DWORD WINAPI loaderThread(LPVOID parameter) {
    auto module = static_cast<HMODULE>(parameter);

    if (!cold::ui::client().bootstrap(module)) {
        COLD_ERROR(kScope, "bootstrap failed; the payload will stay dormant");
        cold::log::close();
        FreeLibraryAndExitThread(module, 1);
        return 1;
    }

    ULONGLONG holdSince = 0;
    while (!cold::ui::client().shutdownRequested()) {
        if ((GetAsyncKeyState(kUnloadKey) & 0x8000) != 0) {
            const ULONGLONG now = GetTickCount64();
            if (holdSince == 0) {
                holdSince = now;
            } else if (now - holdSince >= kUnloadHoldMillis) {
                cold::ui::client().requestShutdown();
                break;
            }
        } else {
            holdSince = 0;
        }
        Sleep(40);
    }

    COLD_INFO(kScope, "unload requested");

    /* Give the render thread a few frames to drop its GL objects and the input
     * chain before the detour is removed and the image unmaps. */
    Sleep(400);
    cold::ui::client().shutdown();
    cold::bridge::Jvm::instance().releaseThread();

    FreeLibraryAndExitThread(module, 0);
    return 0;
}

}  // namespace

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID reserved) {
    (void)reserved;
    switch (reason) {
        case DLL_PROCESS_ATTACH: {
            g_module = module;
            DisableThreadLibraryCalls(module);
            /* DllMain runs under the loader lock, so every real step happens on
             * a worker thread. */
            HANDLE thread = CreateThread(nullptr, 0, &loaderThread, module, 0, nullptr);
            if (thread != nullptr) {
                CloseHandle(thread);
            }
            break;
        }
        case DLL_PROCESS_DETACH:
            break;
        default:
            break;
    }
    return TRUE;
}
