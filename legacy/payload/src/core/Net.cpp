#include "core/Net.hpp"

#include <windows.h>
#include <winhttp.h>

#include <vector>

namespace cold::net {
namespace {

constexpr DWORD kTimeoutMillis = 15000;
constexpr DWORD kMaxBody = 512u * 1024u;

HttpResult request(const std::string& host, const std::string& path, const char* verb,
                   const std::string& body, const std::string& contentType) {
    HttpResult out;
    HINTERNET session =
        WinHttpOpen(L"cold", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, nullptr, nullptr, 0);
    if (session == nullptr) {
        return out;
    }
    WinHttpSetTimeouts(session, kTimeoutMillis, kTimeoutMillis, kTimeoutMillis, kTimeoutMillis);

    const int hostLen = MultiByteToWideChar(CP_UTF8, 0, host.c_str(), -1, nullptr, 0);
    std::wstring wideHost(static_cast<std::size_t>(hostLen > 0 ? hostLen - 1 : 0), L'\0');
    if (hostLen > 1) {
        MultiByteToWideChar(CP_UTF8, 0, host.c_str(), -1, wideHost.data(), hostLen);
    }
    const int pathLen = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
    std::wstring widePath(static_cast<std::size_t>(pathLen > 0 ? pathLen - 1 : 0), L'\0');
    if (pathLen > 1) {
        MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, widePath.data(), pathLen);
    }

    HINTERNET connect = WinHttpConnect(session, wideHost.c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (connect == nullptr) {
        WinHttpCloseHandle(session);
        return out;
    }
    const int verbLen = MultiByteToWideChar(CP_UTF8, 0, verb, -1, nullptr, 0);
    std::wstring wideVerb(static_cast<std::size_t>(verbLen > 0 ? verbLen - 1 : 0), L'\0');
    if (verbLen > 1) {
        MultiByteToWideChar(CP_UTF8, 0, verb, -1, wideVerb.data(), verbLen);
    }
    HINTERNET request = WinHttpOpenRequest(connect, wideVerb.c_str(), widePath.c_str(), nullptr,
                                           nullptr, nullptr, WINHTTP_FLAG_SECURE);
    if (request == nullptr) {
        WinHttpCloseHandle(connect);
        WinHttpCloseHandle(session);
        return out;
    }

    std::wstring headers;
    if (!contentType.empty()) {
        const int headerLen =
            MultiByteToWideChar(CP_UTF8, 0, contentType.c_str(), -1, nullptr, 0);
        std::wstring wideHeader(static_cast<std::size_t>(headerLen > 0 ? headerLen - 1 : 0), L'\0');
        if (headerLen > 1) {
            MultiByteToWideChar(CP_UTF8, 0, contentType.c_str(), -1, wideHeader.data(),
                                headerLen);
        }
        headers = L"Content-Type: " + wideHeader + L"\r\n";
    }

    const void* payload = body.empty() ? WINHTTP_NO_REQUEST_DATA : body.data();
    const DWORD payloadLen = static_cast<DWORD>(body.size());
    BOOL sent = WinHttpSendRequest(request, headers.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : headers.c_str(),
                                   headers.empty() ? 0 : static_cast<DWORD>(-1), 
                                   const_cast<void*>(payload), payloadLen, payloadLen, 0);
    if (sent && WinHttpReceiveResponse(request, nullptr)) {
        DWORD status = 0;
        DWORD statusLen = sizeof(status);
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            nullptr, &status, &statusLen, nullptr);
        out.status = static_cast<long>(status);
        std::string response;
        for (;;) {
            DWORD available = 0;
            if (WinHttpQueryDataAvailable(request, &available) == FALSE || available == 0) {
                break;
            }
            if (response.size() + available > kMaxBody) {
                break;
            }
            const std::size_t base = response.size();
            response.resize(base + available);
            DWORD read = 0;
            if (WinHttpReadData(request, response.data() + base, available, &read) == FALSE) {
                response.resize(base);
                break;
            }
            response.resize(base + read);
            if (read < available) {
                break;
            }
        }
        out.body = std::move(response);
    }

    WinHttpCloseHandle(request);
    WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return out;
}

const char* skipSpace(const char* p, const char* end) {
    while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) ++p;
    return p;
}

}

HttpResult httpsPost(const std::string& host, const std::string& path, const std::string& body,
                     const std::string& contentType) {
    return request(host, path, "POST", body, contentType);
}

HttpResult httpsGet(const std::string& host, const std::string& path) {
    return request(host, path, "GET", {}, {});
}

bool jsonBool(const std::string& body, const std::string& key, bool fallback) {
    const std::string needle = "\"" + key + "\"";
    const std::size_t at = body.find(needle);
    if (at == std::string::npos) {
        return fallback;
    }
    const char* p = skipSpace(body.data() + at + needle.size(),
                              body.data() + body.size());
    if (p < body.data() + body.size() && *p == ':') {
        p = skipSpace(p + 1, body.data() + body.size());
        if (body.compare(p - body.data(), 4, "true") == 0) return true;
        if (body.compare(p - body.data(), 5, "false") == 0) return false;
    }
    return fallback;
}

std::string jsonString(const std::string& body, const std::string& key) {
    const std::string needle = "\"" + key + "\"";
    const std::size_t at = body.find(needle);
    if (at == std::string::npos) {
        return {};
    }
    const char* p = skipSpace(body.data() + at + needle.size(),
                              body.data() + body.size());
    const char* end = body.data() + body.size();
    if (p >= end || *p != ':') {
        return {};
    }
    p = skipSpace(p + 1, end);
    if (p >= end || *p != '"') {
        return {};
    }
    std::string out;
    for (const char* q = p + 1; q < end; ++q) {
        if (*q == '\\' && q + 1 < end) {
            ++q;
            switch (*q) {
                case 'n': out.push_back('\n'); break;
                case 't': out.push_back('\t'); break;
                case 'r': out.push_back('\r'); break;
                default: out.push_back(*q); break;
            }
            continue;
        }
        if (*q == '"') {
            break;
        }
        out.push_back(*q);
    }
    return out;
}

}
