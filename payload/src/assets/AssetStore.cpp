#include "assets/AssetStore.hpp"

#include <cstring>

#include "core/Log.hpp"

namespace cold::assets {
namespace {

constexpr std::string_view kScope = "assets";
constexpr int kBlobResourceId = 4001;

}  // namespace

Store& store() {
    static Store instance;
    return instance;
}

bool Store::load(HMODULE module) {
    log::Stage stage(kScope, "map embedded asset blob");

    HRSRC resource = FindResourceW(module, MAKEINTRESOURCEW(kBlobResourceId), RT_RCDATA);
    if (resource == nullptr) {
        stage.fail("RCDATA resource not found");
        return false;
    }
    HGLOBAL handle = LoadResource(module, resource);
    if (handle == nullptr) {
        stage.fail("LoadResource failed");
        return false;
    }
    base_ = static_cast<const std::uint8_t*>(LockResource(handle));
    size_ = SizeofResource(module, resource);

    if (base_ == nullptr || size_ < kBlobSize) {
        stage.fail(log::format("blob truncated: %u of %u bytes", size_, kBlobSize));
        base_ = nullptr;
        return false;
    }
    COLD_INFO(kScope, "%u bytes, %zu fonts, %zu icons", size_, kFontCount, kIconCount);
    return true;
}

Blob Store::slice(const Span& span) const {
    if (base_ == nullptr || span.offset + span.size > size_) {
        return {};
    }
    return Blob{base_ + span.offset, span.size};
}

Blob Store::font(FontAsset asset) const {
    const auto index = static_cast<std::size_t>(asset);
    return index < kFontCount ? slice(kFontSpans[index]) : Blob{};
}

Blob Store::icon(IconAsset asset) const {
    const auto index = static_cast<std::size_t>(asset);
    return index < kIconCount ? slice(kIconSpans[index]) : Blob{};
}

const char* Store::iconName(IconAsset asset) {
    const auto index = static_cast<std::size_t>(asset);
    return index < kIconCount ? kIconNames[index] : "";
}

IconAsset Store::iconByName(std::string_view name, IconAsset fallback) {
    for (std::size_t index = 0; index < kIconCount; ++index) {
        if (name == kIconNames[index]) {
            return static_cast<IconAsset>(index);
        }
    }
    return fallback;
}

}  // namespace cold::assets
