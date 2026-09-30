#pragma once

#include <array>
#include <cstddef>
#include <string>

namespace cold::obf {

template <std::size_t N, unsigned Key>
struct XorStr {
    std::array<char, N> data{};

    constexpr XorStr(const char (&s)[N]) {
        for (std::size_t i = 0; i < N; ++i) {
            data[i] = static_cast<char>(s[i] ^ static_cast<char>((Key + i * 31u) & 0xFFu));
        }
    }

    std::string decrypt() const {
        std::string out;
        out.resize(N - 1);
        for (std::size_t i = 0; i < N - 1; ++i) {
            out[i] = static_cast<char>(data[i] ^ static_cast<char>((Key + i * 31u) & 0xFFu));
        }
        return out;
    }
};

}

#define COLD_OBF(s) \
    (cold::obf::XorStr<sizeof(s), (__COUNTER__ * 2654435761u + 0x9E3779B9u)>(s).decrypt())
