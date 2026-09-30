#pragma once

namespace cold::hooks {

/* Installs the single detour Cobble Recode needs.
 *
 * `wglSwapBuffers` is the last thing to touch the frame: the world, the
 * vanilla HUD and any post processing are done, the default framebuffer holds
 * the finished image and the legacy compatibility context is current on this
 * thread. Lunar's 1.8.9 runs LWJGL 2, which presents through the GDI
 * swap chain rather than GLFW, so this is the frame boundary the client
 * draws on. It is also the point immediately before the next client tick,
 * so a rotation written here is carried by the very next movement packet. */
bool install();
void uninstall();
bool installed();

/* MinHook is shared: the input layer subclasses the game's window procedure
 * and releases it again on shutdown, and it has to be able to do that whether
 * or not the frame hook went in first. */
bool ensureMinHook();

}  // namespace cold::hooks
