#pragma once

/* Public local-only build. There is no licence wall in this snapshot: no
 * online validation, no HWID binding, no offline grace, no debugger checks,
 * no obfuscated endpoints. Everything is stored locally. */

namespace cold::build {

inline constexpr bool kDev = true;
inline constexpr const char* kFlavour = "public";

}
