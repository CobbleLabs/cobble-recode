#pragma once

#include "core/Math.hpp"
#include "render/Gl.hpp"
#include "render/Shader.hpp"

namespace cold::render {

/* Frosted-glass backdrop for the click GUI.
 *
 * The scene behind the menu is blitted out of the default framebuffer, run
 * through a dual-Kawase pyramid and tinted. Blurring a quarter-resolution copy
 * with four bilinear taps per pass costs a fraction of a separable gaussian, so
 * the menu stays cheap enough to leave open during a fight. */
class Backdrop {
public:
    bool createResources();
    void destroy();

    /* Captures and blurs the current default framebuffer. `strength` in 0..1
     * scales the sample offset, letting the panel fade its blur in with the
     * open animation. */
    bool capture(int width, int height, float strength, const Color& tint);

    GLuint texture() const { return levels_[0].texture; }
    bool valid() const { return valid_; }

private:
    struct Level {
        GLuint texture = 0;
        GLuint framebuffer = 0;
        int width = 0;
        int height = 0;
    };

    static constexpr int kLevels = 4;

    bool resize(int width, int height);
    void drawFullscreen();

    Level levels_[kLevels];
    GLuint emptyVertexArray_ = 0;
    Shader downShader_;
    Shader upShader_;
    Shader tintShader_;
    int sourceWidth_ = 0;
    int sourceHeight_ = 0;
    /* Consecutive failed captures. A context that will not run the pyramid at
     * all fails every frame: one log line per frame buries everything else in
     * the file, and nine wasted render passes per frame is a framerate. */
    static constexpr int kFailureLimit = 8;
    int failures_ = 0;
    bool valid_ = false;
    bool ready_ = false;
};

}  // namespace cold::render
