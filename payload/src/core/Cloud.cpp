#include "core/Cloud.hpp"

namespace cold::cloud {

bool pull(const std::wstring& directory, const auth::Session& session) {
    (void)directory;
    (void)session;
    return false;
}

bool push(const std::wstring& directory, const auth::Session& session) {
    (void)directory;
    (void)session;
    return false;
}

}
