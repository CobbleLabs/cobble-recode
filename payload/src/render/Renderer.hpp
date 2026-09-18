#pragma once

#include <string_view>
#include <vector>

#include "assets/Assets.generated.hpp"
#include "core/Math.hpp"
#include "render/Font.hpp"
#include "render/Gl.hpp"
#include "render/IconAtlas.hpp"
#include "render/Shader.hpp"

namespace cold::render {

enum class Align : std::uint8_t {
    Left,
    Center,
    Right,
};

/* Immediate-mode API over one persistent batch.
 *
 * Every primitive is a quad carrying its own signed-distance parameters, so an
 * entire frame of UI, HUD and ESP collapses into a single draw call unless a
 * clip rectangle or an external texture forces a break.
 *
 * Where the context allows it the quad corners are built on the GPU. The CPU
 * writes one compact record per primitive and a compute shader expands each
 * into the four vertices the draw reads, which halves what crosses the bus and
 * takes the per-corner arithmetic off the render thread entirely - the thread
 * Minecraft is blocked on while the overlay draws. Minecraft creates a 3.3
 * core context, which has no compute stage of its own, so this depends on the
 * driver advertising ARB_compute_shader anyway; when it does not, the same
 * primitives are expanded on the CPU into the same buffer and the draw is
 * identical. See gl::capabilities(). */
class Renderer {
public:
    static Renderer& instance();

    /* Split so the expensive CPU work can run off the render thread: prepare()
     * rasterises atlases, createResources() uploads them plus the programs. */
    bool prepare();
    bool createResources();
    void destroy();
    bool ready() const { return ready_; }
    /* True while the driver still holds every object this client created. The
     * game shares the GL context, so a world switch deletes our atlases and
     * buffers without telling us; polling this at the top of a frame makes the
     * wipe self-healing instead of a slow poisoning of everything drawn. */
    bool resourcesValid() const;

    void beginFrame(int width, int height, float time);
    void endFrame();

    /* Whether the quad expansion is running on the GPU this session. Reported
     * by the control channel, because "is the compute path live" is not
     * something a screenshot can answer. */
    bool gpuExpansion() const { return gpuExpand_; }
    bool gpuExpansionAvailable() const { return expandShader_.valid(); }
    /* Switches back to expanding on the CPU. The two paths fill the same
     * buffer from the same records, so this is an A/B on cost alone - which is
     * the only honest way to say what the kernel is worth on a given machine.
     * Ignored when there is no kernel to switch away from. */
    void setGpuExpansion(bool enabled) { gpuExpand_ = enabled && expandShader_.valid(); }
    /* Primitives the previous frame drew, across every flush it took. Live at
     * any moment the control channel can ask, which the in-flight count is not -
     * endFrame has always just cleared it. */
    std::size_t batchSize() const { return lastFrameQuads_; }

    int width() const { return width_; }
    int height() const { return height_; }

    void rect(const Rect& bounds, const Color& color, float radius = 0.0f);
    void rectVertical(const Rect& bounds, const Color& top, const Color& bottom,
                      float radius = 0.0f);
    void rectHorizontal(const Rect& bounds, const Color& left, const Color& right,
                        float radius = 0.0f);
    void outline(const Rect& bounds, const Color& color, float width, float radius = 0.0f);
    void sweepOutline(const Rect& bounds, const Color& color, float width, float radius);
    void shadow(const Rect& bounds, const Color& color, float spread, float radius);
    void glow(const Rect& bounds, const Color& color, float spread, float radius);
    void ring(float centreX, float centreY, float radius, float thickness, float progress,
              const Color& color);
    void line(float x0, float y0, float x1, float y1, float thickness, const Color& color);
    /* Four screen points in order, filled flat. Unlike rect() this takes no
     * axis-aligned bounds, so a projected cube face can be filled where it
     * actually lands rather than in the box that contains it. */
    void face(const float* xs, const float* ys, const Color& color);
    void image(const Rect& bounds, GLuint texture, const Color& tint, float radius = 0.0f);
    /* A textured quad on four arbitrary screen points, in the same corner order
     * as face(): the uv rectangle is mapped top-left, top-right, bottom-right,
     * bottom-left. This is what draws a projected cube face of a player skin,
     * which is not axis aligned and therefore cannot be a Rect. */
    void imageFace(const float* xs, const float* ys, const Rect& uv, GLuint texture,
                   const Color& tint);
    /* `uv` is in 0..1 texture space. A backdrop has to sample the part of the
     * screen it actually covers, not the whole frame stretched into the quad. */
    void image(const Rect& bounds, const Rect& uv, GLuint texture, const Color& tint,
               float radius);
    /* Same quad, sampled with a nearest sampler instead of the texture's own
     * filtering. Skin portraits are eight texels wide drawn fifty pixels
     * across; through a linear filter every texel edge bleeds into the next
     * and the face turns to mush. */
    void imageCrisp(const Rect& bounds, const Rect& uv, GLuint texture, const Color& tint,
                    float radius = 0.0f);

    float text(FontRole role, std::string_view value, float x, float y, float size,
               const Color& color, Align align = Align::Left);
    /* Same, placed by the top of the capitals instead of by the line box, so a
     * label lands where a design measures it from. */
    float textCap(FontRole role, std::string_view value, float x, float capTop, float size,
                  const Color& color, Align align = Align::Left);
    /* A halo around the same run, `spread` pixels wide, for drawing underneath
     * it. The glyph tile is inflated and its distance field softened, so this
     * is a bloom rather than a bigger copy of the text. */
    float textBloom(FontRole role, std::string_view value, float x, float capTop, float size,
                    const Color& color, float spread, Align align = Align::Left);
    float measure(FontRole role, std::string_view value, float size) const;
    /* Trims to fit and marks the cut, so a long enum value shortens instead of
     * running out of its row. */
    std::string elide(FontRole role, std::string_view value, float size, float maxWidth) const;
    float lineHeight(FontRole role, float size) const;
    void icon(assets::IconAsset asset, const Rect& bounds, const Color& color);

    void pushClip(const Rect& bounds);
    void popClip();

    const FontAtlas& fonts() const { return fonts_; }
    IconAtlas& icons() { return icons_; }

private:
    Renderer() = default;

    struct Vertex {
        float x, y;
        float u, v;
        float r, g, b, a;
        float centreX, centreY, halfWidth, halfHeight;
        float radius, spread, mode, param;
    };

    void quad(const Rect& bounds, const Rect& sdfBounds, float radius, float spread, int mode,
              float param, const Color& c0, const Color& c1, const Color& c2, const Color& c3,
              float u0 = 0.0f, float v0 = 0.0f, float u1 = 0.0f, float v1 = 0.0f);
    /* Turns the record at `index` into four vertices. The CPU fallback for the
     * compute kernel, and the definition both paths are written against. */
    void expandOnCpu(std::size_t index);
    bool createComputeResources();
    void flush();
    void applyClip();

    bool ready_ = false;
    Shader uiShader_;
    Shader expandShader_;
    GLuint vertexArray_ = 0;
    GLuint vertexBuffer_ = 0;
    GLuint indexBuffer_ = 0;
    GLuint emptyVertexArray_ = 0;
    std::size_t indexCapacity_ = 0;

    /* One record per primitive: four corner positions, the sdf box, the style
     * block, a uv rectangle and four corner colours. Thirty-six floats against
     * the sixty-four the same primitive costs written out as vertices.
     *
     * The corners are explicit rather than an axis-aligned rectangle because a
     * line and a projected cube face are quads that are not axis aligned, and
     * they have to travel the same path as everything else - two batches with
     * two expansions would cost more than the eight floats. */
    static constexpr std::size_t kPrimitiveFloats = 36;
    /* Writes one record. The only place a primitive is created. */
    void submit(const float* corners, const Rect& sdfBounds, float radius, float spread,
                int mode, float param, const Color& c0, const Color& c1, const Color& c2,
                const Color& c3, float u0, float v0, float u1, float v1);
    std::vector<float> primitives_;
    std::size_t primitiveCount_ = 0;
    std::size_t frameQuads_ = 0;
    std::size_t lastFrameQuads_ = 0;
    GLuint primitiveBuffer_ = 0;
    bool gpuExpand_ = false;

    std::vector<Vertex> vertices_;
    std::vector<Rect> clipStack_;
    GLuint boundImage_ = 0;

    FontAtlas fonts_;
    IconAtlas icons_;
    GLuint crispSampler_ = 0;
    bool boundCrisp_ = false;

    int width_ = 0;
    int height_ = 0;
    float time_ = 0.0f;
};

inline Renderer& renderer() { return Renderer::instance(); }

}  // namespace cold::render
