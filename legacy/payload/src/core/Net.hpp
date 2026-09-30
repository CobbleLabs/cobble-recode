#pragma once

#include <string>

namespace cold::net {

struct HttpResult {
    long status = 0;
    std::string body;
};

HttpResult httpsPost(const std::string& host, const std::string& path, const std::string& body,
                     const std::string& contentType);

HttpResult httpsGet(const std::string& host, const std::string& path);

bool jsonBool(const std::string& body, const std::string& key, bool fallback);
std::string jsonString(const std::string& body, const std::string& key);

}
