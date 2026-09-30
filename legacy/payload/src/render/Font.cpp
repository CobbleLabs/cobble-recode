#include "render/Font.hpp"

#include <algorithm>
#include <cstring>

#include "assets/AssetStore.hpp"
#include "core/Log.hpp"
#include "stb_truetype.h"

namespace cold::render {
namespace {

constexpr std::string_view kScope = "font";

/* Latin-1 plus the few symbols the client prints. The set is fixed so the
 * atlas is built once on the loader thread and never re-uploaded mid-frame. */
constexpr char32_t kFirstCodepoint = 32;
constexpr char32_t kLastCodepoint = 255;
constexpr char32_t kExtra[] = {0x2026, 0x2022, 0x2192, 0x25B8, 0x25BE, 0xFFFD};

constexpr int kPadding = 6;
constexpr unsigned char kOnEdge = 128;

constexpr assets::FontAsset kRoleAssets[static_cast<std::size_t>(FontRole::Count)] = {
    assets::FontAsset::InterRegular, assets::FontAsset::InterMedium,
    assets::FontAsset::InterSemiBold, assets::FontAsset::InterBold,
    assets::FontAsset::MonoMedium,   assets::FontAsset::MonoBold,
};

struct Raster {
    std::size_t role;
    char32_t codepoint;
    unsigned char* bitmap;
    int width;
    int height;
    int offsetX;
    int offsetY;
    float advance;
};

}  // namespace

char32_t decodeUtf8(std::string_view text, std::size_t& cursor) {
    if (cursor >= text.size()) {
        return 0;
    }
    const auto lead = static_cast<unsigned char>(text[cursor]);
    if (lead < 0x80) {
        ++cursor;
        return lead;
    }

    int continuation = 0;
    char32_t codepoint = 0;
    if ((lead & 0xE0) == 0xC0) {
        continuation = 1;
        codepoint = lead & 0x1Fu;
    } else if ((lead & 0xF0) == 0xE0) {
        continuation = 2;
        codepoint = lead & 0x0Fu;
    } else if ((lead & 0xF8) == 0xF0) {
        continuation = 3;
        codepoint = lead & 0x07u;
    } else {
        ++cursor;
        return 0xFFFD;
    }

    if (cursor + static_cast<std::size_t>(continuation) >= text.size()) {
        cursor = text.size();
        return 0xFFFD;
    }

    ++cursor;
    for (int index = 0; index < continuation; ++index, ++cursor) {
        const auto byte = static_cast<unsigned char>(text[cursor]);
        if ((byte & 0xC0) != 0x80) {
            return 0xFFFD;
        }
        codepoint = (codepoint << 6) | (byte & 0x3Fu);
    }
    return codepoint;
}

const Glyph* FontFace::glyph(char32_t codepoint) const {
    auto found = glyphs_.find(codepoint);
    if (found != glyphs_.end()) {
        return &found->second;
    }
    found = glyphs_.find(0xFFFD);
    return found != glyphs_.end() ? &found->second : nullptr;
}

float FontFace::measure(std::string_view text, float pixelSize) const {
    float width = 0.0f;
    std::size_t cursor = 0;
    while (cursor < text.size()) {
        const char32_t codepoint = decodeUtf8(text, cursor);
        if (codepoint == 0) break;
        if (const Glyph* found = glyph(codepoint)) {
            width += found->advance;
        }
    }
    return width * scaleFor(pixelSize);
}

bool FontAtlas::build(float atlasPixelHeight) {
    log::Stage stage(kScope, "build shared SDF font atlas");

    std::vector<char32_t> wanted;
    wanted.reserve(static_cast<std::size_t>(kLastCodepoint - kFirstCodepoint + 1) +
                   std::size(kExtra));
    for (char32_t codepoint = kFirstCodepoint; codepoint <= kLastCodepoint; ++codepoint) {
        wanted.push_back(codepoint);
    }
    for (char32_t codepoint : kExtra) {
        wanted.push_back(codepoint);
    }

    std::vector<Raster> rasters;
    rasters.reserve(wanted.size() * static_cast<std::size_t>(FontRole::Count));

    for (std::size_t role = 0; role < static_cast<std::size_t>(FontRole::Count); ++role) {
        const assets::Blob blob = assets::store().font(kRoleAssets[role]);
        if (blob.data == nullptr) {
            COLD_ERROR(kScope, "font role %zu missing from the resource blob", role);
            continue;
        }

        stbtt_fontinfo info;
        if (stbtt_InitFont(&info, blob.data, stbtt_GetFontOffsetForIndex(blob.data, 0)) == 0) {
            COLD_ERROR(kScope, "stbtt_InitFont rejected font role %zu", role);
            continue;
        }

        const float scale = stbtt_ScaleForPixelHeight(&info, atlasPixelHeight);
        int ascent = 0;
        int descent = 0;
        int lineGap = 0;
        stbtt_GetFontVMetrics(&info, &ascent, &descent, &lineGap);

        FontFace& face = faces_[role];
        /* A size means an em, the way a design tool means it. stbtt normalises
         * its raster to ascent-descent, which is a fifth larger than the em for
         * Inter and different again for the mono face, so sharing one size
         * across a line would come out at two different weights. */
        const float emScale = stbtt_ScaleForMappingEmToPixels(&info, atlasPixelHeight);
        face.atlasPixelHeight_ =
            emScale > 0.0f ? atlasPixelHeight * scale / emScale : atlasPixelHeight;
        face.ascent_ = static_cast<float>(ascent) * scale;
        face.descent_ = static_cast<float>(descent) * scale;
        face.lineGap_ = static_cast<float>(lineGap) * scale;

        const float distanceScale = static_cast<float>(kOnEdge) / static_cast<float>(kPadding);
        for (char32_t codepoint : wanted) {
            int advanceWidth = 0;
            int leftBearing = 0;
            stbtt_GetCodepointHMetrics(&info, static_cast<int>(codepoint), &advanceWidth,
                                       &leftBearing);

            Raster raster{};
            raster.role = role;
            raster.codepoint = codepoint;
            raster.advance = static_cast<float>(advanceWidth) * scale;
            raster.bitmap =
                stbtt_GetCodepointSDF(&info, scale, static_cast<int>(codepoint), kPadding, kOnEdge,
                                      distanceScale, &raster.width, &raster.height, &raster.offsetX,
                                      &raster.offsetY);
            rasters.push_back(raster);
        }
    }

    if (rasters.empty()) {
        stage.fail("no glyphs rasterised");
        return false;
    }

    /* Shelf packing, tallest first. The glyph set is fixed, so this wastes only
     * a few percent and keeps the build well under a millisecond. */
    std::vector<std::size_t> order(rasters.size());
    for (std::size_t index = 0; index < order.size(); ++index) order[index] = index;
    std::sort(order.begin(), order.end(),
              [&](std::size_t a, std::size_t b) { return rasters[a].height > rasters[b].height; });

    int atlasSize = 512;
    while (atlasSize <= 4096) {
        int penX = 1;
        int penY = 1;
        int shelfHeight = 0;
        bool fits = true;
        for (std::size_t index : order) {
            const Raster& raster = rasters[index];
            if (raster.bitmap == nullptr) continue;
            if (penX + raster.width + 1 > atlasSize) {
                penX = 1;
                penY += shelfHeight + 1;
                shelfHeight = 0;
            }
            if (penY + raster.height + 1 > atlasSize) {
                fits = false;
                break;
            }
            penX += raster.width + 1;
            shelfHeight = std::max(shelfHeight, raster.height);
        }
        if (fits) break;
        atlasSize *= 2;
    }
    if (atlasSize > 4096) {
        for (Raster& raster : rasters) {
            if (raster.bitmap != nullptr) stbtt_FreeSDF(raster.bitmap, nullptr);
        }
        stage.fail("glyph set does not fit a 4096 atlas");
        return false;
    }

    atlasWidth_ = atlasSize;
    atlasHeight_ = atlasSize;
    pixels_.assign(static_cast<std::size_t>(atlasSize) * static_cast<std::size_t>(atlasSize), 0);

    const float inverseSize = 1.0f / static_cast<float>(atlasSize);
    int penX = 1;
    int penY = 1;
    int shelfHeight = 0;
    for (std::size_t index : order) {
        Raster& raster = rasters[index];
        Glyph glyph{};
        glyph.advance = raster.advance;

        if (raster.bitmap != nullptr) {
            if (penX + raster.width + 1 > atlasSize) {
                penX = 1;
                penY += shelfHeight + 1;
                shelfHeight = 0;
            }
            for (int row = 0; row < raster.height; ++row) {
                std::memcpy(&pixels_[static_cast<std::size_t>(penY + row) *
                                         static_cast<std::size_t>(atlasSize) +
                                     static_cast<std::size_t>(penX)],
                            &raster.bitmap[static_cast<std::size_t>(row) *
                                           static_cast<std::size_t>(raster.width)],
                            static_cast<std::size_t>(raster.width));
            }
            glyph.u0 = static_cast<float>(penX) * inverseSize;
            glyph.v0 = static_cast<float>(penY) * inverseSize;
            glyph.u1 = static_cast<float>(penX + raster.width) * inverseSize;
            glyph.v1 = static_cast<float>(penY + raster.height) * inverseSize;
            glyph.offsetX = static_cast<float>(raster.offsetX);
            glyph.offsetY = static_cast<float>(raster.offsetY);
            glyph.width = static_cast<float>(raster.width);
            glyph.height = static_cast<float>(raster.height);

            penX += raster.width + 1;
            shelfHeight = std::max(shelfHeight, raster.height);
            stbtt_FreeSDF(raster.bitmap, nullptr);
            raster.bitmap = nullptr;
        }
        if (raster.codepoint == U'H' && glyph.height > 0.0f) {
            /* The SDF bitmap carries kPadding of margin, so the cap line sits that
             * far inside the tile. */
            faces_[raster.role].capHeight_ = -(glyph.offsetY + static_cast<float>(kPadding));
        }
        faces_[raster.role].glyphs_.emplace(raster.codepoint, glyph);
    }

    dirty_ = true;
    COLD_INFO(kScope, "%zu glyphs across %zu faces in a %dx%d atlas", rasters.size(),
              static_cast<std::size_t>(FontRole::Count), atlasWidth_, atlasHeight_);
    return true;
}

void FontAtlas::upload() {
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
    gl::checkError("font atlas upload");
}

void FontAtlas::destroy() {
    if (texture_ != 0) {
        glDeleteTextures(1, &texture_);
        texture_ = 0;
    }
    /* The rasterised pixels are deliberately kept: a world switch deletes only
     * the GL texture out of the shared context, and upload() has to be able to
     * recreate it from the same data instead of a full re-rasterise mid-frame. */
    dirty_ = !pixels_.empty();
}

}  // namespace cold::render
