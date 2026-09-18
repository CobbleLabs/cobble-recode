#include "KeyCheck.hpp"

namespace cold::injector::keycheck {

KeyResult ensureKey(const std::wstring& payloadPath) {
    (void)payloadPath;
    KeyResult result;
    result.ok = true;
    result.uid = "local";
    result.message = "local build - no licence checks";
    return result;
}

}
