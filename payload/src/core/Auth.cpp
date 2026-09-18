#include "core/Auth.hpp"

#include "core/Log.hpp"

namespace cold::auth {
namespace {
constexpr std::string_view kScope = "auth";
}

bool authenticate(const std::wstring& directory, Session& session) {
    (void)directory;
    session.key = "local";
    session.hwid = "local";
    session.uid = "local";
    session.username = "local";
    session.offline = true;
    COLD_INFO(kScope, "local session, no licence checks in this build");
    return true;
}

}
