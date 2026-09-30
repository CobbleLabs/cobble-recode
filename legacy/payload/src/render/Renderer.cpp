#include "render/Renderer.hpp"

#include <algorithm>
#include <cmath>

#include "core/Log.hpp"
#include "render/Shaders.inl"

namespace cold::render {
namespace {

constexpr std::string_view kScope = "render";

constexpr std::size_t kMaxQuads = 24576;
/* Atlas resolution per em. The SDF is rebuilt from the field at draw time, so
 * a higher-resolution atlas is the one lever that makes small text *and* huge
 * menu titles smooth at once: every size the interface draws maps to at most
 * 1:1 against a 96 px field, and the linear filter + analytic edge then
 * average the field instead of magnifying its texels. The build cost is a
 * slightly larger atlas and a few hundred more milliseconds on the loader
 * thread, off the render path. */
constexpr float kAtlasPixelHeight = 96.0f;

constexpr int kModeFill = 0;
constexpr int kModeStroke = 1;
constexpr int kModeText = 2;
constexpr int kModeIcon = 3;
constexpr int kModeImage = 4;
constexpr int kModeShadow = 5;
constexpr int kModeGlow = 6;
constexpr int kModeRing = 7;
constexpr int kModeSweep = 8;
constexpr int kModeLine = 9;
constexpr int kModeTextBloom = 10;
/* How far past the glyph outline the halo carries, in field units. Small is a
 * rim; large is a slab. */
constexpr float kTextBloomSoftness = 0.30f;

}  // namespace

Renderer& Renderer::instance() {
    static Renderer renderer;
    return renderer;
}

bool Renderer::prepare() {
    log::Stage stage(kScope, "prepare atlases");
    const bool fontsReady = fonts_.build(kAtlasPixelHeight);
    const bool iconsReady = icons_.build();
    if (!fontsReady) {
        stage.fail("font atlas could not be built");
        return false;
    }
    if (!iconsReady) {
        COLD_WARN(kScope, "icon atlas empty; icons will not draw");
    }
    return true;
}

bool Renderer::createResources() {
    log::Stage stage(kScope, "create GL resources");
    if (!gl::load()) {
        stage.fail("OpenGL entry points unavailable");
        return false;
    }
    if (!uiShader_.compile("cold.ui", shaders::kUiVertex, shaders::kUiFragment)) {
        stage.fail("ui program failed");
        return false;
    }

    fonts_.upload();
    icons_.upload();

    gl::GenVertexArrays(1, &vertexArray_);
    gl::GenVertexArrays(1, &emptyVertexArray_);
    gl::GenBuffers(1, &vertexBuffer_);
    gl::GenBuffers(1, &indexBuffer_);

    gl::BindVertexArray(vertexArray_);
    gl::BindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
    gl::BufferData(GL_ARRAY_BUFFER,
                   static_cast<GLsizeiptr>(kMaxQuads * 4 * sizeof(Vertex)), nullptr,
                   GL_STREAM_DRAW);

    const auto stride = static_cast<GLsizei>(sizeof(Vertex));
    const auto attribute = [&](GLuint index, GLint components, std::size_t offset) {
        gl::EnableVertexAttribArray(index);
        gl::VertexAttribPointer(index, components, GL_FLOAT, GL_FALSE, stride,
                                reinterpret_cast<const void*>(offset));
    };
    attribute(0, 2, offsetof(Vertex, x));
    attribute(1, 2, offsetof(Vertex, u));
    attribute(2, 4, offsetof(Vertex, r));
    attribute(3, 4, offsetof(Vertex, centreX));
    attribute(4, 4, offsetof(Vertex, radius));

    /* Quad indices never change, so they are uploaded once. */
    std::vector<std::uint32_t> indices(kMaxQuads * 6);
    for (std::size_t quadIndex = 0; quadIndex < kMaxQuads; ++quadIndex) {
        const auto base = static_cast<std::uint32_t>(quadIndex * 4);
        std::uint32_t* target = &indices[quadIndex * 6];
        target[0] = base + 0;
        target[1] = base + 1;
        target[2] = base + 2;
        target[3] = base + 0;
        target[4] = base + 2;
        target[5] = base + 3;
    }
    gl::BindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer_);
    gl::BufferData(GL_ELEMENT_ARRAY_BUFFER,
                   static_cast<GLsizeiptr>(indices.size() * sizeof(std::uint32_t)), indices.data(),
                   GL_STATIC_DRAW);
    indexCapacity_ = indices.size();

    gl::BindVertexArray(0);
    gl::BindBuffer(GL_ARRAY_BUFFER, 0);
    gl::BindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);

    primitives_.reserve(kMaxQuads * kPrimitiveFloats);
    vertices_.reserve(kMaxQuads * 4);
    gpuExpand_ = createComputeResources();
    COLD_INFO(kScope, "quad expansion runs on the %s", gpuExpand_ ? "GPU" : "CPU");

    gl::GenSamplers(1, &crispSampler_);
    gl::SamplerParameteri(crispSampler_, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    gl::SamplerParameteri(crispSampler_, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

    gl::checkError("renderer setup");
    ready_ = true;
    return true;
}

bool Renderer::createComputeResources() {
    const gl::Capabilities& caps = gl::capabilities();
    if (!caps.compute || !caps.storageBuffers || gl::BindBufferBase == nullptr) {
        COLD_INFO(kScope, "context %d.%d offers no compute path (compute=%d ssbo=%d)",
                  caps.major, caps.minor, caps.compute ? 1 : 0, caps.storageBuffers ? 1 : 0);
        return false;
    }
    if (!expandShader_.compileCompute("cold.expand", shaders::kExpandCompute)) {
        /* A driver that advertises the extension and then refuses the shader is
         * a real case - the block layout qualifier comes from a second
         * extension - and it is not worth failing the whole overlay over. */
        return false;
    }

    gl::GenBuffers(1, &primitiveBuffer_);
    gl::BindBuffer(GL_SHADER_STORAGE_BUFFER, primitiveBuffer_);
    gl::BufferData(GL_SHADER_STORAGE_BUFFER,
                   static_cast<GLsizeiptr>(kMaxQuads * kPrimitiveFloats * sizeof(float)),
                   nullptr, GL_STREAM_DRAW);
    gl::BindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    gl::checkError("compute setup");
    return true;
}

bool Renderer::resourcesValid() const {
    if (!ready_) {
        return false;
    }
    /* Only the long-lived names that beginFrame needs are checked; the atlas
     * textures and the program are the objects a shared-context wipe kills,
     * and glIsTexture is the cheapest way to see it. */
    return glIsTexture(fonts_.texture()) == GL_TRUE &&
           glIsTexture(icons_.texture()) == GL_TRUE && uiShader_.valid();
}

void Renderer::destroy() {
    uiShader_.destroy();
    expandShader_.destroy();
    fonts_.destroy();
    icons_.destroy();
    if (crispSampler_ != 0) gl::DeleteSamplers(1, &crispSampler_);
    crispSampler_ = 0;
    if (primitiveBuffer_ != 0) gl::DeleteBuffers(1, &primitiveBuffer_);
    primitiveBuffer_ = 0;
    gpuExpand_ = false;
    if (vertexBuffer_ != 0) gl::DeleteBuffers(1, &vertexBuffer_);
    if (indexBuffer_ != 0) gl::DeleteBuffers(1, &indexBuffer_);
    if (vertexArray_ != 0) gl::DeleteVertexArrays(1, &vertexArray_);
    if (emptyVertexArray_ != 0) gl::DeleteVertexArrays(1, &emptyVertexArray_);
    vertexBuffer_ = indexBuffer_ = vertexArray_ = emptyVertexArray_ = 0;
    ready_ = false;
}

void Renderer::beginFrame(int width, int height, float time) {
    width_ = width;
    height_ = height;
    time_ = time;
    vertices_.clear();
    primitives_.clear();
    lastFrameQuads_ = frameQuads_;
    frameQuads_ = 0;
    primitiveCount_ = 0;
    clipStack_.clear();
    boundImage_ = 0;
    boundCrisp_ = false;

    gl::BindVertexArray(vertexArray_);
    uiShader_.bind();
    uiShader_.set("uProjection",
                  Mat4::orthographic(0.0f, static_cast<float>(width), static_cast<float>(height),
                                     0.0f));
    uiShader_.set("uTime", time);
    uiShader_.set("uFontAtlas", 0);
    uiShader_.set("uIconAtlas", 1);
    uiShader_.set("uImage", 2);
    /* Texel size of each field, so the text path can widen its antialiasing
     * ramp exactly when the atlas is being magnified. */
    uiShader_.set("uFontAtlasTexel", fonts_.texelWidth(), fonts_.texelHeight());
    uiShader_.set("uIconAtlasTexel", icons_.texelWidth(), icons_.texelHeight());

    gl::ActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, fonts_.texture());
    gl::ActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, icons_.texture());
    gl::ActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, 0);
    if (gl::BindSampler != nullptr) {
        /* Minecraft leaves sampler objects bound; they would override our
         * texture parameters and turn the SDF atlases into nearest-filtered
         * mush. */
        gl::BindSampler(0, 0);
        gl::BindSampler(1, 0);
        gl::BindSampler(2, 0);
    }

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDepthMask(GL_FALSE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glEnable(GL_BLEND);
    gl::BlendEquation(GL_FUNC_ADD);
    gl::BlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    /* Compatibility profile: the fixed-function matrix stack is live state.
     * Minecraft 1.8.9 composes every draw through it, and GL_PROGRAM_MATRIX
     * leakage here would hand the next world fragment a skewed transform. The
     * state guard captures it before we touch anything; the texture matrix too,
     * because Lunar's sprite pass scales coordinates through it. */
    gl::MatrixMode(gl::kModelview);
    gl::LoadIdentity();
    gl::MatrixMode(gl::kProjection);
    gl::LoadIdentity();
    gl::Ortho(0.0, static_cast<double>(width), static_cast<double>(height), 0.0, -1.0, 1.0);
    gl::MatrixMode(gl::kTexture);
    gl::LoadIdentity();
    gl::MatrixMode(gl::kModelview);
    glViewport(0, 0, width, height);
    if (gl::ActiveTexture != nullptr && gl::ClientActiveTexture != nullptr) {
        /* 1.8.9 binds the font/atlas textures as GL_ALPHA through fixed
         * function; the shaders read .r, so a ONE/LACE-up swizzle is not
         * needed - but the legacy client leaves client state enabled for its
         * own colour arrays, and a stray enabled array makes our fetch read
         * from the wrong pointer. */
        for (GLenum unit = 0; unit < 3; ++unit) {
            gl::ClientActiveTexture(GL_TEXTURE0 + unit);
            gl::DisableClientState(gl::kVertexArrayClientState);
            gl::DisableClientState(gl::kColorArrayClientState);
            gl::DisableClientState(gl::kTextureCoordArrayClientState);
        }
        gl::ClientActiveTexture(GL_TEXTURE0);
    }
}

void Renderer::endFrame() {
    flush();
    glDisable(GL_SCISSOR_TEST);
}

void Renderer::flush() {
    const std::size_t quadCount = primitiveCount_;
    if (quadCount == 0) {
        return;
    }

    if (gpuExpand_) {
        /* Orphan then refill, both buffers: the driver hands back fresh storage
         * instead of stalling on the previous frame's draw. */
        gl::BindBuffer(GL_SHADER_STORAGE_BUFFER, primitiveBuffer_);
        gl::BufferData(GL_SHADER_STORAGE_BUFFER,
                       static_cast<GLsizeiptr>(kMaxQuads * kPrimitiveFloats * sizeof(float)),
                       nullptr, GL_STREAM_DRAW);
        gl::BufferSubData(GL_SHADER_STORAGE_BUFFER, 0,
                          static_cast<GLsizeiptr>(primitives_.size() * sizeof(float)),
                          primitives_.data());

        expandShader_.bind();
        expandShader_.setUint("uCount", static_cast<unsigned>(quadCount));
        gl::BindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, primitiveBuffer_);
        gl::BindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, vertexBuffer_);
        /* One invocation per primitive, in groups of sixty-four. */
        gl::DispatchCompute(static_cast<GLuint>((quadCount + 63) / 64), 1, 1);
        /* The draw reads this buffer through the vertex fetch, so that is the
         * barrier bit that matters. Without it the fetch is allowed to see the
         * previous frame. */
        gl::MemoryBarrier_(GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);

        gl::BindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, 0);
        gl::BindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, 0);
        gl::BindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        uiShader_.bind();
    } else {
        vertices_.clear();
        for (std::size_t index = 0; index < quadCount; ++index) {
            expandOnCpu(index);
        }
        gl::BindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
        gl::BufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(kMaxQuads * 4 * sizeof(Vertex)),
                       nullptr, GL_STREAM_DRAW);
        gl::BufferSubData(GL_ARRAY_BUFFER, 0,
                          static_cast<GLsizeiptr>(vertices_.size() * sizeof(Vertex)),
                          vertices_.data());
    }

    glDrawElements(GL_TRIANGLES, static_cast<GLsizei>(quadCount * 6), GL_UNSIGNED_INT, nullptr);
    frameQuads_ += quadCount;
    vertices_.clear();
    primitives_.clear();
    primitiveCount_ = 0;
}

void Renderer::expandOnCpu(std::size_t index) {
    const float* record = &primitives_[index * kPrimitiveFloats];
    const float u0 = record[16];
    const float v0 = record[17];
    const float u1 = record[18];
    const float v1 = record[19];
    /* Same corner order as the kernel and as the static index buffer: top
     * left, top right, bottom right, bottom left. */
    const float us[4] = {u0, u1, u1, u0};
    const float vs[4] = {v0, v0, v1, v1};

    for (std::size_t corner = 0; corner < 4; ++corner) {
        const float* c = record + 20 + corner * 4;
        vertices_.push_back(Vertex{record[corner * 2], record[corner * 2 + 1], us[corner],
                                   vs[corner], c[0], c[1], c[2], c[3], record[8], record[9],
                                   record[10], record[11], record[12], record[13], record[14],
                                   record[15]});
    }
}

void Renderer::applyClip() {
    if (clipStack_.empty()) {
        glDisable(GL_SCISSOR_TEST);
        return;
    }
    const Rect& bounds = clipStack_.back();
    const int x = static_cast<int>(std::floor(bounds.x));
    const int y = static_cast<int>(std::floor(bounds.y));
    const int w = static_cast<int>(std::ceil(bounds.width));
    const int h = static_cast<int>(std::ceil(bounds.height));
    glEnable(GL_SCISSOR_TEST);
    glScissor(x, height_ - (y + h), w < 0 ? 0 : w, h < 0 ? 0 : h);
}

void Renderer::pushClip(const Rect& bounds) {
    flush();
    Rect clipped = bounds;
    if (!clipStack_.empty()) {
        const Rect& parent = clipStack_.back();
        const float left = std::max(clipped.x, parent.x);
        const float top = std::max(clipped.y, parent.y);
        const float right = std::min(clipped.right(), parent.right());
        const float bottom = std::min(clipped.bottom(), parent.bottom());
        clipped = Rect{left, top, std::max(0.0f, right - left), std::max(0.0f, bottom - top)};
    }
    clipStack_.push_back(clipped);
    applyClip();
}

void Renderer::popClip() {
    flush();
    if (!clipStack_.empty()) {
        clipStack_.pop_back();
    }
    applyClip();
}

void Renderer::quad(const Rect& bounds, const Rect& sdfBounds, float radius, float spread, int mode,
                    float param, const Color& c0, const Color& c1, const Color& c2, const Color& c3,
                    float u0, float v0, float u1, float v1) {
    const float corners[8] = {bounds.x,       bounds.y,      bounds.right(), bounds.y,
                              bounds.right(), bounds.bottom(), bounds.x,     bounds.bottom()};
    submit(corners, sdfBounds, radius, spread, mode, param, c0, c1, c2, c3, u0, v0, u1, v1);
}

void Renderer::submit(const float* corners, const Rect& sdfBounds, float radius, float spread,
                      int mode, float param, const Color& c0, const Color& c1, const Color& c2,
                      const Color& c3, float u0, float v0, float u1, float v1) {
    if (primitiveCount_ >= kMaxQuads) {
        flush();
    }

    /* One append. The per-corner texture coordinates and the four copies of the
     * style block that every vertex needs are built downstream - by the kernel,
     * or by expandOnCpu when there is no kernel. */
    const float record[kPrimitiveFloats] = {
        corners[0], corners[1], corners[2], corners[3],
        corners[4], corners[5], corners[6], corners[7],
        sdfBounds.centerX(), sdfBounds.centerY(), sdfBounds.width * 0.5f,
        sdfBounds.height * 0.5f,
        radius, spread, static_cast<float>(mode), param,
        u0, v0, u1, v1,
        c0.r, c0.g, c0.b, c0.a,
        c1.r, c1.g, c1.b, c1.a,
        c2.r, c2.g, c2.b, c2.a,
        c3.r, c3.g, c3.b, c3.a,
    };
    primitives_.insert(primitives_.end(), std::begin(record), std::end(record));
    ++primitiveCount_;
}

void Renderer::rect(const Rect& bounds, const Color& color, float radius) {
    quad(bounds, bounds, radius, 0.0f, kModeFill, 0.0f, color, color, color, color);
}

void Renderer::rectVertical(const Rect& bounds, const Color& top, const Color& bottom,
                            float radius) {
    quad(bounds, bounds, radius, 0.0f, kModeFill, 0.0f, top, top, bottom, bottom);
}

void Renderer::rectHorizontal(const Rect& bounds, const Color& left, const Color& right,
                              float radius) {
    quad(bounds, bounds, radius, 0.0f, kModeFill, 0.0f, left, right, right, left);
}

void Renderer::outline(const Rect& bounds, const Color& color, float width, float radius) {
    const Rect expanded = bounds.inset(-width);
    quad(expanded, bounds, radius, 0.0f, kModeStroke, width, color, color, color, color);
}

void Renderer::sweepOutline(const Rect& bounds, const Color& color, float width, float radius) {
    const Rect expanded = bounds.inset(-width);
    quad(expanded, bounds, radius, 0.0f, kModeSweep, width, color, color, color, color);
}

void Renderer::shadow(const Rect& bounds, const Color& color, float spread, float radius) {
    const Rect expanded = bounds.inset(-spread * 1.6f);
    quad(expanded, bounds, radius, spread, kModeShadow, 0.0f, color, color, color, color);
}

void Renderer::glow(const Rect& bounds, const Color& color, float spread, float radius) {
    const Rect expanded = bounds.inset(-spread * 1.6f);
    quad(expanded, bounds, radius, spread, kModeGlow, 0.0f, color, color, color, color);
}

void Renderer::ring(float centreX, float centreY, float radius, float thickness, float progress,
                    const Color& color) {
    const Rect bounds{centreX - radius, centreY - radius, radius * 2.0f, radius * 2.0f};
    quad(bounds, bounds, radius, progress, kModeRing, thickness, color, color, color, color);
}

void Renderer::line(float x0, float y0, float x1, float y1, float thickness, const Color& color) {
    const float dx = x1 - x0;
    const float dy = y1 - y0;
    const float length = std::sqrt(dx * dx + dy * dy);
    if (length < 0.0001f) {
        return;
    }
    const float half = thickness * 0.5f;
    const float normalX = -dy / length * half;
    const float normalY = dx / length * half;

    /* The uv rectangle carries the across-the-line coordinate the fragment
     * shader antialiases against, which runs -1 on one side to 1 on the other
     * - the same u0,v0,u1,v1 slots, used for something that is not a texture. */
    const float corners[8] = {x0 + normalX, y0 + normalY, x1 + normalX, y1 + normalY,
                              x1 - normalX, y1 - normalY, x0 - normalX, y0 - normalY};
    submit(corners, Rect{-half, -half, half * 2.0f, half * 2.0f}, 0.0f, 0.0f, kModeLine,
           thickness, color, color, color, color, 0.0f, -1.0f, 1.0f, 1.0f);
}

std::string Renderer::elide(FontRole role, std::string_view value, float size,
                            float maxWidth) const {
    if (maxWidth <= 0.0f) {
        return {};
    }
    if (measure(role, value, size) <= maxWidth) {
        return std::string(value);
    }
    const float ellipsis = measure(role, "...", size);
    std::string trimmed(value);
    while (!trimmed.empty() && measure(role, trimmed, size) + ellipsis > maxWidth) {
        /* Drop whole code points, not bytes, or a multi-byte character can be
         * cut in half and render as replacement glyphs. */
        do {
            trimmed.pop_back();
        } while (!trimmed.empty() && (static_cast<unsigned char>(trimmed.back()) & 0xC0) == 0x80);
    }
    return trimmed + "...";
}

void Renderer::face(const float* xs, const float* ys, const Color& color) {
    /* The SDF box is grown well past the polygon so every fragment inside it
     * reads as fully covered; the edges are drawn separately and carry the
     * antialiasing. */
    float minX = xs[0];
    float minY = ys[0];
    float maxX = xs[0];
    float maxY = ys[0];
    for (int index = 1; index < 4; ++index) {
        minX = std::min(minX, xs[index]);
        minY = std::min(minY, ys[index]);
        maxX = std::max(maxX, xs[index]);
        maxY = std::max(maxY, ys[index]);
    }
    const float centreX = (minX + maxX) * 0.5f;
    const float centreY = (minY + maxY) * 0.5f;
    const float halfWidth = (maxX - minX) * 0.5f + 2.0f;
    const float halfHeight = (maxY - minY) * 0.5f + 2.0f;

    const float corners[8] = {xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], xs[3], ys[3]};
    submit(corners, Rect{centreX - halfWidth, centreY - halfHeight, halfWidth * 2.0f,
                         halfHeight * 2.0f},
           0.0f, 0.0f, kModeFill, 0.0f, color, color, color, color, 0.0f, 0.0f, 0.0f, 0.0f);
}

void Renderer::image(const Rect& bounds, GLuint texture, const Color& tint, float radius) {
    image(bounds, Rect{0.0f, 0.0f, 1.0f, 1.0f}, texture, tint, radius);
}

void Renderer::image(const Rect& bounds, const Rect& uv, GLuint texture, const Color& tint,
                     float radius) {
    if (texture != boundImage_ || boundCrisp_) {
        flush();
        gl::ActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, texture);
        if (gl::BindSampler != nullptr) gl::BindSampler(2, 0);
        boundImage_ = texture;
        boundCrisp_ = false;
    }
    quad(bounds, bounds, radius, 0.0f, kModeImage, 0.0f, tint, tint, tint, tint, uv.x, uv.y,
         uv.right(), uv.bottom());
}

void Renderer::imageCrisp(const Rect& bounds, const Rect& uv, GLuint texture,
                          const Color& tint, float radius) {
    if (texture != boundImage_ || !boundCrisp_) {
        flush();
        gl::ActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, texture);
        if (gl::BindSampler != nullptr) gl::BindSampler(2, crispSampler_);
        boundImage_ = texture;
        boundCrisp_ = true;
    }
    quad(bounds, bounds, radius, 0.0f, kModeImage, 0.0f, tint, tint, tint, tint, uv.x, uv.y,
         uv.right(), uv.bottom());
}

void Renderer::imageFace(const float* xs, const float* ys, const Rect& uv, GLuint texture,
                         const Color& tint) {
    if (texture != boundImage_ || boundCrisp_) {
        flush();
        gl::ActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, texture);
        if (gl::BindSampler != nullptr) gl::BindSampler(2, 0);
        boundImage_ = texture;
        boundCrisp_ = false;
    }

    /* Grown past the polygon so the rounded-box coverage the image mode applies
     * reads as fully covered everywhere inside it; the quad's own edges are
     * what clips the sample, exactly as in face(). */
    float minX = xs[0];
    float minY = ys[0];
    float maxX = xs[0];
    float maxY = ys[0];
    for (int index = 1; index < 4; ++index) {
        minX = std::min(minX, xs[index]);
        minY = std::min(minY, ys[index]);
        maxX = std::max(maxX, xs[index]);
        maxY = std::max(maxY, ys[index]);
    }
    const float centreX = (minX + maxX) * 0.5f;
    const float centreY = (minY + maxY) * 0.5f;
    const float halfWidth = (maxX - minX) * 0.5f + 2.0f;
    const float halfHeight = (maxY - minY) * 0.5f + 2.0f;

    const float corners[8] = {xs[0], ys[0], xs[1], ys[1], xs[2], ys[2], xs[3], ys[3]};
    submit(corners,
           Rect{centreX - halfWidth, centreY - halfHeight, halfWidth * 2.0f, halfHeight * 2.0f},
           0.0f, 0.0f, kModeImage, 0.0f, tint, tint, tint, tint, uv.x, uv.y, uv.right(),
           uv.bottom());
}

float Renderer::measure(FontRole role, std::string_view value, float size) const {
    return fonts_.face(role).measure(value, size);
}

float Renderer::lineHeight(FontRole role, float size) const {
    return fonts_.face(role).lineHeight(size);
}

float Renderer::text(FontRole role, std::string_view value, float x, float y, float size,
                     const Color& color, Align align) {
    const FontFace& face = fonts_.face(role);
    if (!face.ready()) {
        return x;
    }
    const float scale = face.scaleFor(size);

    float penX = x;
    if (align != Align::Left) {
        const float width = face.measure(value, size);
        penX -= align == Align::Center ? width * 0.5f : width;
    }
    /* Snap the run's origin to whole pixels but let the glyphs inside it sit
     * on the subpixel grid: the SDF evaluates the edge analytically, so a
     * glyph rounding down to the previous pixel loses more in shifted stems
     * than the fractional placement ever costs in shimmer. Advances stay
     * fractional so spacing does not drift. */
    penX = std::round(penX);
    const float baseline = std::round(y + face.baseline(size));

    std::size_t cursor = 0;
    while (cursor < value.size()) {
        const char32_t codepoint = decodeUtf8(value, cursor);
        if (codepoint == 0) break;
        const Glyph* glyph = face.glyph(codepoint);
        if (glyph == nullptr) continue;
        if (glyph->width > 0.0f && glyph->height > 0.0f) {
            const Rect bounds{penX + glyph->offsetX * scale,
                              baseline + glyph->offsetY * scale,
                              glyph->width * scale, glyph->height * scale};
            quad(bounds, bounds, 0.0f, 0.0f, kModeText, 0.0f, color, color, color, color, glyph->u0,
                 glyph->v0, glyph->u1, glyph->v1);
        }
        penX += glyph->advance * scale;
    }
    return penX;
}

float Renderer::textBloom(FontRole role, std::string_view value, float x, float capTop, float size,
                          const Color& color, float spread, Align align) {
    const FontFace& face = fonts_.face(role);
    if (!face.ready() || spread <= 0.0f) {
        return x;
    }
    const float scale = face.scaleFor(size);

    float penX = x;
    if (align != Align::Left) {
        const float width = face.measure(value, size);
        penX -= align == Align::Center ? width * 0.5f : width;
    }
    penX = std::round(penX);
    const float baseline = std::round(capTop - face.capTop(size) + face.baseline(size));

    std::size_t cursor = 0;
    while (cursor < value.size()) {
        const char32_t codepoint = decodeUtf8(value, cursor);
        if (codepoint == 0) break;
        const Glyph* glyph = face.glyph(codepoint);
        if (glyph == nullptr) continue;
        if (glyph->width > 0.0f && glyph->height > 0.0f) {
            const Rect tile{penX + glyph->offsetX * scale,
                            baseline + glyph->offsetY * scale, glyph->width * scale,
                            glyph->height * scale};
            const Rect halo = tile.inset(-spread);
            /* The falloff is steep on purpose. A wide, gentle halo per glyph
             * overlaps its neighbours - the tiles of a word are inflated into
             * each other - and eight of them summed read as a lit rectangle
             * behind the run rather than as a glow on the letters. Steep keeps
             * each one inside its own outline. */
            quad(halo, halo, 0.0f, 0.0f, kModeTextBloom, kTextBloomSoftness, color, color, color,
                 color, glyph->u0, glyph->v0, glyph->u1, glyph->v1);
        }
        penX += glyph->advance * scale;
    }
    return penX;
}

float Renderer::textCap(FontRole role, std::string_view value, float x, float capTop, float size,

                        const Color& color, Align align) {
    return text(role, value, x, capTop - fonts_.face(role).capTop(size), size, color, align);
}

void Renderer::icon(assets::IconAsset asset, const Rect& bounds, const Color& color) {
    const IconTile& tile = icons_.tile(asset);
    if (!tile.valid) {
        return;
    }
    quad(bounds, bounds, 0.0f, 0.0f, kModeIcon, 0.0f, color, color, color, color, tile.u0, tile.v0,
         tile.u1, tile.v1);
}

}  // namespace cold::render
