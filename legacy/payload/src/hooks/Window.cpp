#include "hooks/Window.hpp"

#include <string_view>

#include "core/Log.hpp"

namespace cold::hooks::window {
namespace {

constexpr std::string_view kScope = "window";

}  // namespace

HWND fromDeviceContext(HDC deviceContext) {
    if (deviceContext == nullptr) {
        return nullptr;
    }
    /* WindowFromDC is the documented reverse of GetDC and costs nothing; the
     * alternative, walking GWL_HWNDPARENT guesses, is wrong for any child
     * window and LWJGL 2 creates a real top-level window anyway. */
    return WindowFromDC(deviceContext);
}

HWND active() { return GetActiveWindow(); }

WNDPROC attach(HWND window, WNDPROC replacement) {
    if (window == nullptr || replacement == nullptr) {
        return nullptr;
    }
    const WNDPROC previous =
        reinterpret_cast<WNDPROC>(SetWindowLongPtrW(window, GWLP_WNDPROC,
                                                    reinterpret_cast<LONG_PTR>(replacement)));
    if (previous == nullptr) {
        COLD_ERROR(kScope, "SetWindowLongPtrW failed with %lu", GetLastError());
        return nullptr;
    }
    return previous;
}

void detach(HWND window, WNDPROC previous) {
    if (window == nullptr || previous == nullptr) {
        return;
    }
    SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(previous));
}

}  // namespace cold::hooks::window
