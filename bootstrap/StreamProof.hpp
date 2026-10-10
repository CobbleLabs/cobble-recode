#pragma once

/* The stream proof layer: an offscreen target the client draws the modules it
 * hides into, shown on a click-through window over the game that Windows
 * leaves out of screen capture. All but stopWindow run on the render thread
 * with the game's GL context current. */
namespace streamproof {

using Logger = void (*)(const char* message);

void setLogger(Logger logger);

/* False when the system cannot keep a window off captures, or the layer
 * cannot be built; the client then draws those modules in the game's frame. */
bool begin();
void end();
void present();
void shutdown();

/* What shutdown does without the GL half, for an unload that never got to it:
 * the window thread runs this DLL's code and must be gone before it unmaps. */
void stopWindow();

}  // namespace streamproof
