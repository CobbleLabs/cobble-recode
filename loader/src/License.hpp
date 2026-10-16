#pragma once

#include <string>

namespace cold::injector::license {

struct LoginResult {
    bool ok = false;
    std::wstring message;
};

/* Signs in to the Cobble license server. ok only when the server's signed
 * reply grants v2 to this machine for this request's nonce. */
LoginResult login(const std::string& username, const std::string& password);

}  // namespace cold::injector::license
