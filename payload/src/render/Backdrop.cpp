#include "render/Backdrop.hpp"

#include <algorithm>

#include "core/Log.hpp"
#include "render/Shaders.inl"

namespace cold::render {
namespace {

constexpr std::string_view kScope = "backdrop";

}  // namespace

bool Backdrop::createResources() {
    log::Stage stage(kScope, "create blur pyramid programs");
    if (!downShader_.compile("cold.kawase.down", shaders::kFullscreenVertex,
                             shaders::kKawaseDownFragment) ||
        !upShader_.compile("cold.kawase.up", shaders::kFullscreenVertex,
                           shaders::kKawaseUpFragment) ||
        !tintShader_.compile("cold.backdrop.tint", shaders::kFullscreenVertex,
                             shaders::kBackdropFragment)) {
        stage.fail("blur programs failed to compile");
        return false;
    }
    gl::GenVertexArrays(1, &emptyVertexArray_);
    ready_ = true;
    return true;
}

void Backdrop::destroy() {
    for (Level& level : levels_) {
        if (level.framebuffer != 0) gl::DeleteFramebuffers(1, &level.framebuffer);
        if (level.texture != 0) glDeleteTextures(1, &level.texture);
        level = Level{};
    }
    if (emptyVertexArray_ != 0) {
        gl::DeleteVertexArrays(1, &emptyVertexArray_);
        emptyVertexArray_ = 0;
    }
    downShader_.destroy();
    upShader_.destroy();
    tintShader_.destroy();
    valid_ = false;
    ready_ = false;
    sourceWidth_ = 0;
    sourceHeight_ = 0;
}

bool Backdrop::resize(int width, int height) {
    if (width == sourceWidth_ && height == sourceHeight_ && levels_[0].texture != 0) {
        return true;
    }
    for (Level& level : levels_) {
        if (level.framebuffer != 0) gl::DeleteFramebuffers(1, &level.framebuffer);
        if (level.texture != 0) glDeleteTextures(1, &level.texture);
        level = Level{};
    }

    sourceWidth_ = width;
    sourceHeight_ = height;
    for (int index = 0; index < kLevels; ++index) {
        Level& level = levels_[index];
        level.width = std::max(1, width >> (index + 1));
        level.height = std::max(1, height >> (index + 1));

        glGenTextures(1, &level.texture);
        glBindTexture(GL_TEXTURE_2D, level.texture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, level.width, level.height, 0, GL_RGBA,
                     GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        gl::GenFramebuffers(1, &level.framebuffer);
        gl::BindFramebuffer(GL_FRAMEBUFFER, level.framebuffer);
        gl::FramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, level.texture,
                                 0);
        if (gl::CheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            COLD_ERROR(kScope, "blur level %d framebuffer incomplete", index);
            gl::BindFramebuffer(GL_FRAMEBUFFER, 0);
            return false;
        }
    }
    gl::BindFramebuffer(GL_FRAMEBUFFER, 0);
    COLD_DEBUG(kScope, "blur pyramid rebuilt for %dx%d", width, height);
    return true;
}

void Backdrop::drawFullscreen() { glDrawArrays(GL_TRIANGLES, 0, 3); }

bool Backdrop::capture(int width, int height, float strength, const Color& tint) {
    valid_ = false;
    /* ready_ rather than one shader: createResources short-circuits, so a
     * failure in the up or tint program leaves the down program valid and the
     * vertex array at zero. Drawing then binds VAO 0 and program 0, which a
     * core context rejects, and the panels would blend the raw half-res blit
     * instead of the solid fallback they were promised. */
    if (width <= 0 || height <= 0 || !ready_) {
        return false;
    }
    if (!resize(width, height)) {
        return false;
    }

    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    gl::BindVertexArray(emptyVertexArray_);
    gl::ActiveTexture(GL_TEXTURE0);
    if (gl::BindSampler != nullptr) gl::BindSampler(0, 0);

    /* Half-resolution copy of what the game just presented. */
    gl::BindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    gl::BindFramebuffer(GL_DRAW_FRAMEBUFFER, levels_[0].framebuffer);
    gl::BlitFramebuffer(0, 0, width, height, 0, 0, levels_[0].width, levels_[0].height,
                        GL_COLOR_BUFFER_BIT, GL_LINEAR);

    const float offset = clamp(strength, 0.0f, 1.0f) * 2.6f + 0.4f;

    downShader_.bind();
    downShader_.set("uSource", 0);
    downShader_.set("uOffset", offset);
    for (int index = 1; index < kLevels; ++index) {
        const Level& source = levels_[index - 1];
        const Level& target = levels_[index];
        gl::BindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, target.width, target.height);
        glBindTexture(GL_TEXTURE_2D, source.texture);
        downShader_.set("uHalfPixel", 0.5f / static_cast<float>(source.width),
                        0.5f / static_cast<float>(source.height));
        drawFullscreen();
    }

    upShader_.bind();
    upShader_.set("uSource", 0);
    upShader_.set("uOffset", offset);
    for (int index = kLevels - 1; index > 0; --index) {
        const Level& source = levels_[index];
        const Level& target = levels_[index - 1];
        gl::BindFramebuffer(GL_FRAMEBUFFER, target.framebuffer);
        glViewport(0, 0, target.width, target.height);
        glBindTexture(GL_TEXTURE_2D, source.texture);
        upShader_.set("uHalfPixel", 0.5f / static_cast<float>(source.width),
                      0.5f / static_cast<float>(source.height));
        drawFullscreen();
    }

    /* Tint in place through level 1, then back to level 0, so the panel shader
     * only ever samples one texture. */
    tintShader_.bind();
    tintShader_.set("uSource", 0);
    tintShader_.set("uTint", tint);
    tintShader_.set("uSaturation", 0.72f);
    gl::BindFramebuffer(GL_FRAMEBUFFER, levels_[1].framebuffer);
    glViewport(0, 0, levels_[1].width, levels_[1].height);
    glBindTexture(GL_TEXTURE_2D, levels_[0].texture);
    drawFullscreen();

    gl::BindFramebuffer(GL_READ_FRAMEBUFFER, levels_[1].framebuffer);
    gl::BindFramebuffer(GL_DRAW_FRAMEBUFFER, levels_[0].framebuffer);
    gl::BlitFramebuffer(0, 0, levels_[1].width, levels_[1].height, 0, 0, levels_[0].width,
                        levels_[0].height, GL_COLOR_BUFFER_BIT, GL_LINEAR);

    gl::BindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width, height);
    glEnable(GL_BLEND);
    gl::checkError("backdrop capture");
    valid_ = true;
    return true;
}

}  // namespace cold::render
