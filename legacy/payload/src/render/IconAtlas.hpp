#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "assets/Assets.generated.hpp"
#include "render/Gl.hpp"

namespace cold::render {

struct IconTile {
    float u0 = 0.0f;
    float v0 = 0.0f;
    float u1 = 0.0f;
    float v1 = 0.0f;
    bool valid = false;
};

/* Lucide's icons ship as stroked SVG paths. Cobble Recode rasterises them once at
 * 4x, converts coverage into an exact euclidean distance field, and stores one
 * tile per icon. The result scales to any HUD or menu size from a single
 * texture, and shares the text shader path because both use the same
 * 128-is-the-edge encoding. */
class IconAtlas {
public:
    bool build();
    void upload();
    void destroy();

    bool ready() const { return texture_ != 0; }
    GLuint texture() const { return texture_; }
    /* One atlas texel in uv units; see FontAtlas. */
    float texelWidth() const { return atlasWidth_ > 0 ? 1.0f / static_cast<float>(atlasWidth_) : 0.0f; }
    float texelHeight() const { return atlasHeight_ > 0 ? 1.0f / static_cast<float>(atlasHeight_) : 0.0f; }
    const IconTile& tile(assets::IconAsset asset) const;

private:
    std::array<IconTile, assets::kIconCount> tiles_ = {};
    std::vector<std::uint8_t> pixels_;
    int atlasWidth_ = 0;
    int atlasHeight_ = 0;
    GLuint texture_ = 0;
    bool dirty_ = false;
};

}  // namespace cold::render
