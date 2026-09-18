#pragma once

#include <windows.h>

namespace cold::hooks::window {

/* The Win32 window behind LWJGL 2's Display.
 *
 * LWJGL 2 never hands out the HWND through a stable export the way GLFW
 * hands out glfwGetCurrentWindow, and Display.hasWindow is a Java field, not
 * native state. The handle is recovered the way the driver sees it: a device
 * context is owned by its window, so the frame hook can recover the window
 * from the HDC it is handed, and the message queue's foreground window is the
 * same one the game pumps from the input side. */
HWND fromDeviceContext(HDC deviceContext);
HWND active();

/* Subclass bookkeeping for the input layer. Returns the previous window
 * procedure so detach can restore it exactly. */
WNDPROC attach(HWND window, WNDPROC replacement);
void detach(HWND window, WNDPROC previous);

}  // namespace cold::hooks::window
