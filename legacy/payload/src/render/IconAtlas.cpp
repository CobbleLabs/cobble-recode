#include "render/IconAtlas.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <string>

#include "assets/AssetStore.hpp"
#include "core/Log.hpp"
#include "core/Math.hpp"
#include "nanosvg.h"
#include "nanosvgrast.h"

namespace cold::render {
namespace {

constexpr std::string_view kScope = "icons";

constexpr int kTile = 64;         // stored SDF resolution per icon
constexpr int kSupersample = 4;   // rasterise at 4x, then measure distance
constexpr int kRaster = kTile * kSupersample;
constexpr float kSpread = 6.0f;   // in tile pixels; matches the font atlas feel
constexpr int kColumns = 8;

constexpr float kInfinity = 1e20f;

/* Felzenszwalb & Huttenlocher's exact distance transform, one dimension at a
 * time. Two passes over a 256x256 buffer per icon, so the whole set converts in
 * a couple of milliseconds on the loader thread. */
void transform1d(const float* source, float* destination, int length, int* hull, float* boundary) {
    int k = 0;
    hull[0] = 0;
    boundary[0] = -kInfinity;
    boundary[1] = kInfinity;

    for (int q = 1; q < length; ++q) {
        float intersection =
            ((source[q] + static_cast<float>(q * q)) -
             (source[hull[k]] + static_cast<float>(hull[k] * hull[k]))) /
            static_cast<float>(2 * q - 2 * hull[k]);
        while (intersection <= boundary[k]) {
            --k;
            intersection = ((source[q] + static_cast<float>(q * q)) -
                            (source[hull[k]] + static_cast<float>(hull[k] * hull[k]))) /
                           static_cast<float>(2 * q - 2 * hull[k]);
        }
        ++k;
        hull[k] = q;
        boundary[k] = intersection;
        boundary[k + 1] = kInfinity;
    }

    k = 0;
    for (int q = 0; q < length; ++q) {
        while (boundary[k + 1] < static_cast<float>(q)) ++k;
        const float delta = static_cast<float>(q - hull[k]);
        destination[q] = delta * delta + source[hull[k]];
    }
}

void transform2d(std::vector<float>& grid, int width, int height) {
    const int longest = std::max(width, height);
    std::vector<float> column(static_cast<std::size_t>(longest));
    std::vector<float> result(static_cast<std::size_t>(longest));
    std::vector<int> hull(static_cast<std::size_t>(longest));
    std::vector<float> boundary(static_cast<std::size_t>(longest) + 1);

    for (int y = 0; y < height; ++y) {
        float* row = &grid[static_cast<std::size_t>(y) * static_cast<std::size_t>(width)];
        std::memcpy(column.data(), row, static_cast<std::size_t>(width) * sizeof(float));
        transform1d(column.data(), result.data(), width, hull.data(), boundary.data());
        std::memcpy(row, result.data(), static_cast<std::size_t>(width) * sizeof(float));
    }

    for (int x = 0; x < width; ++x) {
        for (int y = 0; y < height; ++y) {
            column[static_cast<std::size_t>(y)] =
                grid[static_cast<std::size_t>(y) * static_cast<std::size_t>(width) +
                     static_cast<std::size_t>(x)];
        }
        transform1d(column.data(), result.data(), height, hull.data(), boundary.data());
        for (int y = 0; y < height; ++y) {
            grid[static_cast<std::size_t>(y) * static_cast<std::size_t>(width) +
                 static_cast<std::size_t>(x)] = result[static_cast<std::size_t>(y)];
        }
    }
}

}  // namespace

const IconTile& IconAtlas::tile(assets::IconAsset asset) const {
    static const IconTile kEmpty{};
    const auto index = static_cast<std::size_t>(asset);
    return index < tiles_.size() ? tiles_[index] : kEmpty;
}

bool IconAtlas::build() {
    log::Stage stage(kScope, "rasterise icon SDF atlas");

    const int rows = (static_cast<int>(assets::kIconCount) + kColumns - 1) / kColumns;
    atlasWidth_ = kColumns * kTile;
    atlasHeight_ = rows * kTile;
    pixels_.assign(static_cast<std::size_t>(atlasWidth_) * static_cast<std::size_t>(atlasHeight_),
                   0);

    NSVGrasterizer* rasteriser = nsvgCreateRasterizer();
    if (rasteriser == nullptr) {
        stage.fail("nsvgCreateRasterizer returned null");
        return false;
    }

    std::vector<unsigned char> coverage(static_cast<std::size_t>(kRaster) *
                                        static_cast<std::size_t>(kRaster) * 4);
    std::vector<float> inside(static_cast<std::size_t>(kRaster) * static_cast<std::size_t>(kRaster));
    std::vector<float> outside(inside.size());

    std::size_t built = 0;
    for (std::size_t index = 0; index < assets::kIconCount; ++index) {
        const assets::Blob blob = assets::store().icon(static_cast<assets::IconAsset>(index));
        if (blob.data == nullptr) {
            continue;
        }
        /* nsvgParse mutates its input, so hand it a private copy. */
        std::string source(reinterpret_cast<const char*>(blob.data), blob.size);
        NSVGimage* image = nsvgParse(source.data(), "px", 96.0f);
        if (image == nullptr || image->width <= 0.0f || image->height <= 0.0f) {
            if (image != nullptr) nsvgDelete(image);
            COLD_WARN(kScope, "icon %s failed to parse", assets::Store::iconName(
                                                             static_cast<assets::IconAsset>(index)));
            continue;
        }

        /* Inset by the stroke width so round caps are not clipped at the tile
         * edge, then scale the 24-unit viewBox to fill what is left. */
        const float margin = static_cast<float>(kRaster) * 0.10f;
        const float scale =
            (static_cast<float>(kRaster) - margin * 2.0f) / std::max(image->width, image->height);
        std::fill(coverage.begin(), coverage.end(), static_cast<unsigned char>(0));
        nsvgRasterize(rasteriser, image, margin, margin, scale, coverage.data(), kRaster, kRaster,
                      kRaster * 4);
        nsvgDelete(image);

        for (std::size_t pixel = 0; pixel < inside.size(); ++pixel) {
            const bool solid = coverage[pixel * 4 + 3] >= 128;
            inside[pixel] = solid ? 0.0f : kInfinity;
            outside[pixel] = solid ? kInfinity : 0.0f;
        }
        transform2d(inside, kRaster, kRaster);
        transform2d(outside, kRaster, kRaster);

        const int column = static_cast<int>(index) % kColumns;
        const int row = static_cast<int>(index) / kColumns;
        const int originX = column * kTile;
        const int originY = row * kTile;

        for (int y = 0; y < kTile; ++y) {
            for (int x = 0; x < kTile; ++x) {
                const int sampleX = x * kSupersample + kSupersample / 2;
                const int sampleY = y * kSupersample + kSupersample / 2;
                const std::size_t sample = static_cast<std::size_t>(sampleY) *
                                               static_cast<std::size_t>(kRaster) +
                                           static_cast<std::size_t>(sampleX);
                /* `inside` holds the distance to the nearest solid pixel and
                 * `outside` the distance to the nearest empty one, so the
                 * signed field is outside minus inside: positive within the
                 * shape, which is what the shader's 0.5 threshold expects. */
                const float distance = (std::sqrt(outside[sample]) - std::sqrt(inside[sample])) /
                                       static_cast<float>(kSupersample);
                const float encoded = 128.0f + clamp(distance / kSpread, -1.0f, 1.0f) * 127.0f;
                pixels_[static_cast<std::size_t>(originY + y) *
                            static_cast<std::size_t>(atlasWidth_) +
                        static_cast<std::size_t>(originX + x)] =
                    static_cast<std::uint8_t>(encoded + 0.5f);
            }
        }

        IconTile& tile = tiles_[index];
        tile.u0 = static_cast<float>(originX) / static_cast<float>(atlasWidth_);
        tile.v0 = static_cast<float>(originY) / static_cast<float>(atlasHeight_);
        tile.u1 = static_cast<float>(originX + kTile) / static_cast<float>(atlasWidth_);
        tile.v1 = static_cast<float>(originY + kTile) / static_cast<float>(atlasHeight_);
        tile.valid = true;
        ++built;
    }

    nsvgDeleteRasterizer(rasteriser);
    dirty_ = true;
    COLD_INFO(kScope, "%zu/%zu icons in a %dx%d atlas", built, assets::kIconCount, atlasWidth_,
              atlasHeight_);
    return built > 0;
}

void IconAtlas::upload() {
    if (!dirty_ || pixels_.empty()) {
        return;
    }
    if (texture_ == 0) {
        glGenTextures(1, &texture_);
    }
    glBindTexture(GL_TEXTURE_2D, texture_);
    {
        gl::PixelTransferGuard transfer(true);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, atlasWidth_, atlasHeight_, 0, GL_RED,
                     GL_UNSIGNED_BYTE, pixels_.data());
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    dirty_ = false;
    gl::checkError("icon atlas upload");
}

void IconAtlas::destroy() {
    if (texture_ != 0) {
        glDeleteTextures(1, &texture_);
        texture_ = 0;
    }
    /* Pixels are kept so upload() can rebuild after a context wipe without a
     * re-rasterise; same reasoning as the font atlas. */
    dirty_ = !pixels_.empty();
}

}  // namespace cold::render
