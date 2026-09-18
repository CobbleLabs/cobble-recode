#pragma once

#include <string>
#include <windows.h>

namespace cold::injector::keycheck {

/* Public build: no licence key, no auth server. */
struct KeyResult {
    bool ok = false;
    std::string message;
    std::string uid;
};

KeyResult ensureKey(const std::wstring& payloadPath);

}
