#pragma once

#include <string>
#include <windows.h>

namespace cold::auth {

/* Public build: local session only. No key file, no server, no HWID. */
struct Session {
    std::string key = "local";
    std::string uid = "local";
    std::string username = "local";
    std::string hwid = "local";
    bool offline = true;
};

inline std::string hardwareId() { return "local"; }
bool authenticate(const std::wstring& directory, Session& session);

}
