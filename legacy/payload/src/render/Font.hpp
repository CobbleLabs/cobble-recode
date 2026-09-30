#pragma once

#include <array>
#include <cstdint>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "assets/Assets.generated.hpp"
#include "render/Gl.hpp"

namespace cold::render {

enum class FontRole : std::uint8_t {
    Regular,
    Medium,
    SemiBold,
    Bold,
    Mono,
    MonoBold,
    Count,
};

struct Glyph {
    float u0 = 0.0f;
    float v0 = 0.0f;
    float u1 = 0.0f;
    float v1 = 0.0f;
    float offsetX = 0.0f;  // pen-relative, in atlas pixels
    float offsetY = 0.0f;
    float width = 0.0f;
    float height = 0.0f;
    float advance = 0.0f;
};

/* One typeface's glyph table. Metrics are in atlas pixels; multiply by
 * scaleFor(size) to get the on-screen values. */
class FontFace {
public:
    const Glyph* glyph(char32_t codepoint) const;

    float ascent() const { return ascent_; }
    float descent() const { return descent_; }
    /* Distance from the text origin down to the top of a capital, which is what
     * a design measures from. Placing a label by its cap line keeps a column of
     * labels aligned when their sizes differ. */
    float capTop(float pixelSize) const { return (ascent_ - capHeight_) * scaleFor(pixelSize); }
    float scaleFor(float pixelSize) const { return pixelSize / atlasPixelHeight_; }
    float lineHeight(float pixelSize) const {
        return (ascent_ - descent_ + lineGap_) * scaleFor(pixelSize);
    }
    /* Distance from the top of a line box down to the baseline. */
    float baseline(float pixelSize) const { return ascent_ * scaleFor(pixelSize); }
    float measure(std::string_view text, float pixelSize) const;
    bool ready() const { return !glyphs_.empty(); }

private:
    friend class FontAtlas;

    std::unordered_map<char32_t, Glyph> glyphs_;
    float atlasPixelHeight_ = 48.0f;
    float ascent_ = 0.0f;
    float capHeight_ = 0.0f;
    float descent_ = 0.0f;
    float lineGap_ = 0.0f;
};

/* Every typeface shares one signed-distance texture.
 *
 * A single atlas means a HUD line that mixes Inter and JetBrains Mono still
 * batches into one draw call, and the fragment shader reconstructs the edge
 * from a screen-space derivative so 10 px counters and a 22 px watermark are
 * equally crisp without per-size rasterisation. */
class FontAtlas {
public:
    bool build(float atlasPixelHeight);
    void upload();
    void destroy();

    bool ready() const { return texture_ != 0; }
    GLuint texture() const { return texture_; }
    /* 1/atlasWidth, 1/atlasHeight: the size of one field texel in uv units,
     * which the text shader needs to know how far a magnified atlas can be
     * trusted per screen pixel. */
    float texelWidth() const { return atlasWidth_ > 0 ? 1.0f / static_cast<float>(atlasWidth_) : 0.0f; }
    float texelHeight() const { return atlasHeight_ > 0 ? 1.0f / static_cast<float>(atlasHeight_) : 0.0f; }
    const FontFace& face(FontRole role) const {
        return faces_[static_cast<std::size_t>(role)];
    }

    /* The CPU-side atlas is kept so it can be re-uploaded, and so a scripted
     * run can compare it against what the driver actually holds. */
    const std::vector<std::uint8_t>& pixels() const { return pixels_; }
    int atlasWidth() const { return atlasWidth_; }
    int atlasHeight() const { return atlasHeight_; }

private:
    std::array<FontFace, static_cast<std::size_t>(FontRole::Count)> faces_;
    std::vector<std::uint8_t> pixels_;
    int atlasWidth_ = 0;
    int atlasHeight_ = 0;
    GLuint texture_ = 0;
    bool dirty_ = false;
};

/* Decodes one UTF-8 code point, advancing `cursor`. Malformed bytes decode to
 * U+FFFD so a hostile chat message cannot desynchronise text layout. */
char32_t decodeUtf8(std::string_view text, std::size_t& cursor);

}  // namespace cold::render
