#pragma once

#include <string>

namespace cold::modules {
struct Context;
}

namespace cold::test {

/* A file-driven control channel for scripted runs.
 *
 * Minecraft owns the keyboard and its window is usually not in the foreground
 * on a build machine, so there is no reliable way to drive the client or grab a
 * frame from outside the process. Dropping a `cobble.control` file beside the
 * payload does both: the client executes the commands on its next frame,
 * appends what happened to `cobble.control.out`, and deletes the request.
 *
 *   gui on|off
 *   module <name> on|off|toggle
 *   set <module> <setting> <value>
 *   shot <file.bmp>
 *   state
 *
 * When no request file exists this costs one GetFileAttributes every 20 frames.
 */
class Control {
public:
    void configure(const std::wstring& directory);
    void poll(modules::Context& context, int framebufferWidth, int framebufferHeight);

private:
    bool execute(const std::string& line, modules::Context& context, std::string& reply);
    bool capture(const std::string& fileName, int width, int height, std::string& reply);

    std::wstring requestPath_;
    std::wstring replyPath_;
    std::wstring directory_;
    std::string pendingShotName_;
    /* Shots wait a few frames so animations started by the same batch have
     * settled before the frame is grabbed. */
    int pendingShotFrames_ = 0;
    int countdown_ = 0;
    bool enabled_ = false;
};

Control& control();

}  // namespace cold::test
