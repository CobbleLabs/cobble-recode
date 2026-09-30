#pragma once

#include <string>
#include <windows.h>

#include "core/Auth.hpp"

namespace cold::cloud {

/* Public build: cloud sync is compiled out. Signatures stay so callers do
 * not need ifdefs; both always report "nothing to do". */
bool pull(const std::wstring& directory, const auth::Session& session);
bool push(const std::wstring& directory, const auth::Session& session);

}
