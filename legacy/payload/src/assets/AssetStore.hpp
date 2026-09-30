#pragma once

#include <windows.h>

#include <cstdint>
#include <string_view>

#include "assets/Assets.generated.hpp"

namespace cold::assets {

struct Blob {
    const std::uint8_t* data = nullptr;
    std::uint32_t size = 0;
};

/* Fonts and icons live in one RCDATA resource inside the payload, so the DLL
 * stays self-contained: nothing to unpack next to the game, nothing to go
 * missing between the injector and the client. */
class Store {
public:
    bool load(HMODULE module);

    Blob font(FontAsset asset) const;
    Blob icon(IconAsset asset) const;
    bool ready() const { return base_ != nullptr; }

    static const char* iconName(IconAsset asset);
    static IconAsset iconByName(std::string_view name, IconAsset fallback);

private:
    Blob slice(const Span& span) const;

    const std::uint8_t* base_ = nullptr;
    std::uint32_t size_ = 0;
};

Store& store();

}  // namespace cold::assets
