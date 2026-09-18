#include "hooks/Input.hpp"

#include <windows.h>
#include <windowsx.h>

#include "core/Log.hpp"
#include "hooks/Hooks.hpp"
#include "hooks/Window.hpp"

namespace cold::hooks {
namespace {

constexpr std::string_view kScope = "input";

/* The game's window procedure, chained. Our dispatcher looks at everything
 * first and decides per-event whether the game sees it. */
WNDPROC g_chainWndProc = nullptr;
HWND g_window = nullptr;
bool g_attached = false;

/* VK codes the client binds against directly; GLFW codes above space come in
 * mapped to VK through the translate helper below. */
constexpr int kVkLeft = VK_LBUTTON;
constexpr int kVkRight = VK_RBUTTON;
constexpr int kVkMiddle = VK_MBUTTON;

int toGlfwKey(WPARAM wParam) {
    /* Only keys Minecraft's own key binding table cares about are forwarded
     * as key events; the rest arrive as characters. The mapping mirrors the
     * GLFW to LWJGL2 Keyboard translation the game performs itself. */
    switch (wParam) {
        case VK_SPACE: return 32;  // GLFW_KEY_SPACE, the bottom of the tracked range
        case VK_BACK: return kKeyBackspace;
        case VK_RETURN: return kKeyEnter;
        case VK_ESCAPE: return kKeyEscape;
        case VK_INSERT: return kKeyInsert;
        case VK_DELETE: return kKeyDelete;
        case VK_RIGHT: return 262;  // GLFW_KEY_RIGHT
        case VK_LEFT: return 263;  // GLFW_KEY_LEFT
        case VK_DOWN: return 264;  // GLFW_KEY_DOWN
        case VK_UP: return 265;  // GLFW_KEY_UP
        case VK_RSHIFT: return kKeyRightShift;
        case VK_RCONTROL: return kKeyRightControl;
        default: break;
    }
    if (wParam >= 'A' && wParam <= 'Z') {
        return 65 + static_cast<int>(wParam - 'A');  // GLFW sits on ASCII for letters
    }
    if (wParam >= '0' && wParam <= '9') {
        return '0' + static_cast<int>(wParam - '0');
    }
    if (wParam >= VK_NUMPAD0 && wParam <= VK_NUMPAD9) {
        return 320 + static_cast<int>(wParam - VK_NUMPAD0);  // GLFW_KEY_KP_0
    }
    if (wParam >= VK_F1 && wParam <= VK_F12) {
        return 290 + static_cast<int>(wParam - VK_F1);  // GLFW_KEY_F1..F12
    }
    return -1;
}

LRESULT CALLBACK hookedWndProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    Input& self = Input::instance();

    switch (message) {
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN: {
            const int key = toGlfwKey(wParam);
            if (key >= 0 && (lParam & 0x40000000) == 0) {  // no autorepeat
                self.push(InputEvent{InputEvent::Kind::Key, key, kPress, 0, self.cursorX(),
                                     self.cursorY()});
            } else if (key >= 0) {
                self.push(InputEvent{InputEvent::Kind::Key, key, kRepeat, 0, self.cursorX(),
                                     self.cursorY()});
            }
            if (self.capturing()) {
                return 0;
            }
            break;
        }
        case WM_KEYUP:
        case WM_SYSKEYUP: {
            const int key = toGlfwKey(wParam);
            if (key >= 0) {
                self.push(InputEvent{InputEvent::Kind::Key, key, kRelease, 0, self.cursorX(),
                                     self.cursorY()});
            }
            if (self.capturing()) {
                return 0;
            }
            break;
        }
        case WM_CHAR: {
            self.push(InputEvent{InputEvent::Kind::Char, static_cast<int>(wParam), 0, 0,
                                 self.cursorX(), self.cursorY()});
            if (self.capturing()) {
                return 0;
            }
            break;
        }
        case WM_LBUTTONDOWN:
        case WM_RBUTTONDOWN:
        case WM_MBUTTONDOWN: {
            const int button = message == WM_LBUTTONDOWN ? kMouseLeft
                               : message == WM_RBUTTONDOWN ? kMouseRight
                                                           : kMouseMiddle;
            if (button == kMouseLeft) self.trackButton(button, true);
            if (button == kMouseRight) self.trackButton(button, true);
            self.push(InputEvent{InputEvent::Kind::MouseButton, button, kPress, 0,
                                 self.cursorX(), self.cursorY()});
            if (self.capturing()) {
                return 0;
            }
            break;
        }
        case WM_LBUTTONUP:
        case WM_RBUTTONUP:
        case WM_MBUTTONUP: {
            const int button = message == WM_LBUTTONUP ? kMouseLeft
                               : message == WM_RBUTTONUP ? kMouseRight
                                                         : kMouseMiddle;
            if (button == kMouseLeft) self.trackButton(button, false);
            if (button == kMouseRight) self.trackButton(button, false);
            self.push(InputEvent{InputEvent::Kind::MouseButton, button, kRelease, 0,
                                 self.cursorX(), self.cursorY()});
            if (self.capturing()) {
                return 0;
            }
            break;
        }
        case WM_MOUSEMOVE: {
            /* 1.8.9 measures the camera delta from a grab point it re-centres
             * every frame under a released cursor; while the menu owns the
             * pointer the game must not see movement at all, or the camera
             * would spin. The queued event still carries the position so the
             * GUI can draw its own cursor. */
            POINT point = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            self.trackCursor(static_cast<double>(point.x), static_cast<double>(point.y));
            self.push(InputEvent{InputEvent::Kind::MouseMove, 0, 0, 0, self.cursorX(),
                                 self.cursorY()});
            if (self.capturing()) {
                return 0;
            }
            break;
        }
        case WM_MOUSEWHEEL: {
            const double delta = static_cast<short>(HIWORD(wParam)) / static_cast<double>(WHEEL_DELTA);
            self.push(InputEvent{InputEvent::Kind::Scroll, 0, 0, 0, 0.0, delta});
            if (self.capturing()) {
                return 0;
            }
            break;
        }
        default:
            break;
    }

    if (g_chainWndProc != nullptr) {
        return CallWindowProcW(g_chainWndProc, window, message, wParam, lParam);
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

}  // namespace

Input& Input::instance() {
    static Input input;
    return input;
}

bool Input::attach(void* window) {
    const HWND hwnd = static_cast<HWND>(window);
    if (hwnd == nullptr) {
        COLD_ERROR(kScope, "no window handle from the frame hook");
        return false;
    }
    if (g_attached && g_window == hwnd) {
        return true;
    }
    if (g_attached) {
        detach();
    }
    window_ = hwnd;
    g_window = hwnd;
    g_chainWndProc = window::attach(hwnd, &hookedWndProc);
    g_attached = g_chainWndProc != nullptr;
    if (!g_attached) {
        COLD_ERROR(kScope, "window subclass failed");
        return false;
    }
    attached_ = true;
    COLD_INFO(kScope, "window procedure chained on hwnd %p", static_cast<void*>(hwnd));
    return true;
}

void Input::detach() {
    if (!g_attached || g_window == nullptr) {
        return;
    }
    setCapturing(false);
    window::detach(g_window, g_chainWndProc);
    g_chainWndProc = nullptr;
    g_window = nullptr;
    g_attached = false;
    attached_ = false;
    COLD_INFO(kScope, "window procedure released");
}

void Input::refresh() {
    if (!g_attached) {
        return;
    }
    /* Live button state comes from the OS while the game owns the cursor, so
     * a bind works whether or not the client saw the press that started it.
     * While the menu owns the cursor the tracked pair is the only state, the
     * OS never delivered an up we swallowed. */
    if (!capturing_) {
        attackHeld_ = (GetAsyncKeyState(kVkLeft) & 0x8000) != 0;
        useHeld_ = (GetAsyncKeyState(kVkRight) & 0x8000) != 0;
    }
}

void Input::setSyntheticButton(int button, bool down) {
    if (button == kMouseLeft) {
        syntheticAttack_ = down;
    } else if (button == kMouseRight) {
        syntheticUse_ = down;
    }
}

bool Input::consumeCursorChange() {
    const bool pending = cursorDirty_;
    cursorDirty_ = false;
    return pending;
}

std::vector<InputEvent> Input::drain() {
    std::lock_guard<std::mutex> guard(mutex_);
    std::vector<InputEvent> events;
    events.swap(queue_);
    return events;
}

void Input::setCapturing(bool capturing) {
    if (capturing_ == capturing) {
        return;
    }
    if (capturing) {
        /* Everything held right now is about to stop being forwarded, so the
         * release that ends it would never arrive and the KeyBinding would
         * stay pressed. Minecraft normally gets out of this by opening a
         * Screen, which calls unpressAll; the client does not open one, so it
         * has to hand the releases over itself. */
        releaseHeldInputs();
    }
    capturing_ = capturing;
    cursorDirty_ = true;
    if (capturing_) {
        attackHeld_ = false;
        useHeld_ = false;
        syntheticAttack_ = false;
        syntheticUse_ = false;
    }
    COLD_DEBUG(kScope, "cursor %s", capturing_ ? "released to the menu" : "returned to the game");
}

bool Input::keyHeld(int key) const {
    if (g_window == nullptr || key < kFirstTrackedKey || key > kLastTrackedKey) {
        return false;
    }
    /* GLFW's tracked range starts at space; below it the map is not defined.
     * The reverse translation covers exactly the keys toGlfwKey produces. */
    int vk = 0;
    if (key >= 290 && key <= 301) {
        vk = VK_F1 + (key - 290);
    } else if (key >= 320 && key <= 329) {
        vk = VK_NUMPAD0 + (key - 320);
    } else if (key >= '0' && key <= '9') {
        vk = key;
    } else if (key >= 65 && key <= 90) {
        vk = key;  // VK_A..VK_Z coincide with 'A'..'Z'
    } else {
        switch (key) {
            case 32: vk = VK_SPACE; break;
            case kKeyBackspace: vk = VK_BACK; break;
            case kKeyEnter: vk = VK_RETURN; break;
            case kKeyEscape: vk = VK_ESCAPE; break;
            case kKeyInsert: vk = VK_INSERT; break;
            case kKeyDelete: vk = VK_DELETE; break;
            case 262: vk = VK_RIGHT; break;
            case 263: vk = VK_LEFT; break;
            case 264: vk = VK_DOWN; break;
            case 265: vk = VK_UP; break;
            case kKeyRightShift: vk = VK_RSHIFT; break;
            case kKeyRightControl: vk = VK_RCONTROL; break;
            default: return false;
        }
    }
    return (GetAsyncKeyState(vk) & 0x8000) != 0;
}

bool Input::bindHeld(int code) const {
    if (code > 8) {
        return keyHeld(code);
    }
    if (code == kMouseLeft) {
        return attackHeld();
    }
    if (code == kMouseRight) {
        return useHeld();
    }
    return (GetAsyncKeyState(VK_MBUTTON) & 0x8000) != 0;
}

void Input::sendKey(int key, bool down) {
    /* Drives the game through the same chain a physical press arrives on, so
     * the verification suite can walk the player without a human. */
    if (g_window == nullptr || g_chainWndProc == nullptr) {
        return;
    }
    int vk = 0;
    if (key >= 'A' && key <= 'Z') {
        vk = key;
    } else if (key >= '0' && key <= '9') {
        vk = key;
    } else {
        switch (key) {
            case kKeyBackspace: vk = VK_BACK; break;
            case kKeyEnter: vk = VK_RETURN; break;
            case kKeyEscape: vk = VK_ESCAPE; break;
            case kKeyInsert: vk = VK_INSERT; break;
            case kKeyDelete: vk = VK_DELETE; break;
            case kKeyRightShift: vk = VK_RSHIFT; break;
            case kKeyRightControl: vk = VK_RCONTROL; break;
            default: return;
        }
    }
    const UINT downMessage = down ? WM_KEYDOWN : WM_KEYUP;
    const LPARAM extra = down ? 0 : 0xC0000000;
    SendMessageW(g_window, downMessage, vk, extra);
}

void Input::releaseHeldInputs() {
    if (g_window == nullptr || g_chainWndProc == nullptr) {
        return;
    }
    for (int vk = 0x08; vk <= 0xFE; ++vk) {
        if ((GetAsyncKeyState(vk) & 0x8000) != 0) {
            SendMessageW(g_window, WM_KEYUP, vk, 0xC0000000);
        }
    }
    if ((GetAsyncKeyState(kVkLeft) & 0x8000) != 0) {
        SendMessageW(g_window, WM_LBUTTONUP, 0, 0);
    }
    if ((GetAsyncKeyState(kVkRight) & 0x8000) != 0) {
        SendMessageW(g_window, WM_RBUTTONUP, 0, 0);
    }
    if ((GetAsyncKeyState(kVkMiddle) & 0x8000) != 0) {
        SendMessageW(g_window, WM_MBUTTONUP, 0, 0);
    }
}

}  // namespace cold::hooks
