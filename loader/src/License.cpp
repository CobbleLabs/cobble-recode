#include "License.hpp"

#include <windows.h>

#include <bcrypt.h>
#include <intrin.h>
#include <iphlpapi.h>
#include <wincrypt.h>
#include <winhttp.h>

#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <ctime>
#include <iomanip>
#include <map>
#include <memory>
#include <sstream>
#include <vector>

#include "TweetNacl.hpp"

namespace cold::injector::license {
namespace {

constexpr wchar_t kHost[] = L"api.cobble.vip";
constexpr wchar_t kLoginPath[] = L"/api/v2/login";

/* The license server's Ed25519 signing key, the same one the Lite loader pins. */
constexpr uint8_t kServerKey[32] = {
    0x28, 0x41, 0xeb, 0xcd, 0xdb, 0x1d, 0xa0, 0x7a, 0x8c, 0xcd, 0x4b, 0x96, 0x57, 0x64, 0x29, 0xd1,
    0x58, 0xa8, 0xe8, 0xfd, 0x0e, 0x1e, 0x81, 0x43, 0x84, 0x56, 0x25, 0x4d, 0xd9, 0xed, 0x29, 0x7d,
};

using Fields = std::map<std::string, std::string>;
using Handle = std::unique_ptr<void, decltype(&WinHttpCloseHandle)>;

std::string hex(const uint8_t* bytes, std::size_t count) {
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (std::size_t index = 0; index < count; ++index) {
        out << std::setw(2) << static_cast<int>(bytes[index]);
    }
    return out.str();
}

std::wstring widen(const std::string& text) {
    const int chars = MultiByteToWideChar(CP_UTF8, 0, text.c_str(), static_cast<int>(text.size()),
                                          nullptr, 0);
    std::wstring result(static_cast<std::size_t>(chars), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.c_str(), static_cast<int>(text.size()), result.data(),
                        chars);
    return result;
}

/* Same inputs and formatting as the Lite loader's HWID, so one PC shows up
 * with one HWID in the dashboard whichever client it runs. */
std::string cpuId() {
    int info[4] = {0};
    __cpuid(info, 0);
    char vendor[13] = {0};
    memcpy(vendor, &info[1], 4);
    memcpy(vendor + 4, &info[3], 4);
    memcpy(vendor + 8, &info[2], 4);
    __cpuid(info, 1);
    std::stringstream out;
    out << vendor << "-" << std::hex << info[0] << info[3];
    return out.str();
}

std::string diskSerial() {
    DWORD serial = 0;
    if (!GetVolumeInformationW(L"C:\\", nullptr, 0, &serial, nullptr, nullptr, nullptr, 0)) {
        return "UNKNOWN";
    }
    std::stringstream out;
    out << std::hex << std::uppercase << serial;
    return out.str();
}

std::string macAddress() {
    IP_ADAPTER_INFO adapters[16];
    DWORD size = sizeof(adapters);
    if (GetAdaptersInfo(adapters, &size) != ERROR_SUCCESS) {
        return "UNKNOWN";
    }
    for (PIP_ADAPTER_INFO adapter = adapters; adapter != nullptr; adapter = adapter->Next) {
        if (adapter->AddressLength != 6) {
            continue;
        }
        for (int index = 0; index < 6; ++index) {
            if (adapter->Address[index] != 0) {
                return hex(adapter->Address, 6);
            }
        }
    }
    return "UNKNOWN";
}

std::string machineId() {
    const std::string combined = cpuId() + "|" + diskSerial() + "|" + macAddress();
    uint8_t digest[32];
    if (!BCRYPT_SUCCESS(BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0,
                                   reinterpret_cast<PUCHAR>(const_cast<char*>(combined.data())),
                                   static_cast<ULONG>(combined.size()), digest, sizeof(digest)))) {
        return "";
    }
    return hex(digest, sizeof(digest));
}

std::string randomNonce() {
    uint8_t bytes[16];
    if (!BCRYPT_SUCCESS(BCryptGenRandom(nullptr, bytes, sizeof(bytes),
                                        BCRYPT_USE_SYSTEM_PREFERRED_RNG))) {
        return "";
    }
    return hex(bytes, sizeof(bytes));
}

std::string jsonString(const std::string& value) {
    std::string out = "\"";
    for (const char c : value) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += c;
        } else if (static_cast<unsigned char>(c) < 0x20) {
            char escaped[7];
            snprintf(escaped, sizeof(escaped), "\\u%04x", c);
            out += escaped;
        } else {
            out += c;
        }
    }
    return out + "\"";
}

/* The server only answers with flat objects of strings, numbers and booleans.
 * Strings come back unescaped; other values keep their literal text. */
bool parseObject(const std::string& text, Fields& out) {
    std::size_t at = 0;
    const auto skipSpace = [&] {
        while (at < text.size() && std::isspace(static_cast<unsigned char>(text[at]))) {
            ++at;
        }
    };
    const auto readString = [&](std::string& value) {
        if (at >= text.size() || text[at] != '"') {
            return false;
        }
        ++at;
        value.clear();
        while (at < text.size()) {
            const char c = text[at++];
            if (c == '"') {
                return true;
            }
            if (c != '\\') {
                value += c;
                continue;
            }
            if (at >= text.size()) {
                return false;
            }
            const char escape = text[at++];
            switch (escape) {
                case 'b': value += '\b'; break;
                case 'f': value += '\f'; break;
                case 'n': value += '\n'; break;
                case 'r': value += '\r'; break;
                case 't': value += '\t'; break;
                case 'u': {
                    if (at + 4 > text.size()) {
                        return false;
                    }
                    const std::string digits = text.substr(at, 4);
                    char* end = nullptr;
                    const unsigned long code = strtoul(digits.c_str(), &end, 16);
                    if (end != digits.c_str() + 4) {
                        return false;
                    }
                    at += 4;
                    if (code < 0x80) {
                        value += static_cast<char>(code);
                    } else if (code < 0x800) {
                        value += static_cast<char>(0xC0 | (code >> 6));
                        value += static_cast<char>(0x80 | (code & 0x3F));
                    } else {
                        value += static_cast<char>(0xE0 | (code >> 12));
                        value += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
                        value += static_cast<char>(0x80 | (code & 0x3F));
                    }
                    break;
                }
                default: value += escape; break;
            }
        }
        return false;
    };

    skipSpace();
    if (at >= text.size() || text[at++] != '{') {
        return false;
    }
    skipSpace();
    if (at < text.size() && text[at] == '}') {
        return true;
    }
    while (true) {
        std::string key;
        std::string value;
        skipSpace();
        if (!readString(key)) {
            return false;
        }
        skipSpace();
        if (at >= text.size() || text[at++] != ':') {
            return false;
        }
        skipSpace();
        if (at < text.size() && text[at] == '"') {
            if (!readString(value)) {
                return false;
            }
        } else {
            const std::size_t start = at;
            while (at < text.size() && text[at] != ',' && text[at] != '}') {
                ++at;
            }
            value = text.substr(start, at - start);
            while (!value.empty() && std::isspace(static_cast<unsigned char>(value.back()))) {
                value.pop_back();
            }
            if (value.empty() || value.front() == '{' || value.front() == '[') {
                return false;
            }
        }
        out[key] = value;
        skipSpace();
        if (at >= text.size()) {
            return false;
        }
        const char next = text[at++];
        if (next == '}') {
            return true;
        }
        if (next != ',') {
            return false;
        }
    }
}

std::vector<uint8_t> base64(const std::string& text) {
    DWORD size = 0;
    if (!CryptStringToBinaryA(text.c_str(), static_cast<DWORD>(text.size()), CRYPT_STRING_BASE64,
                              nullptr, &size, nullptr, nullptr)) {
        return {};
    }
    std::vector<uint8_t> bytes(size);
    if (!CryptStringToBinaryA(text.c_str(), static_cast<DWORD>(text.size()), CRYPT_STRING_BASE64,
                              bytes.data(), &size, nullptr, nullptr)) {
        return {};
    }
    bytes.resize(size);
    return bytes;
}

bool post(const std::string& body, DWORD& status, std::string& response) {
    Handle session(WinHttpOpen(L"Cobble v2 Injector", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                               WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0),
                   WinHttpCloseHandle);
    if (!session) {
        return false;
    }
    WinHttpSetTimeouts(session.get(), 10000, 10000, 15000, 15000);
    Handle connection(WinHttpConnect(session.get(), kHost, INTERNET_DEFAULT_HTTPS_PORT, 0),
                      WinHttpCloseHandle);
    if (!connection) {
        return false;
    }
    Handle request(WinHttpOpenRequest(connection.get(), L"POST", kLoginPath, nullptr,
                                      WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                      WINHTTP_FLAG_SECURE),
                   WinHttpCloseHandle);
    if (!request) {
        return false;
    }
    const DWORD length = static_cast<DWORD>(body.size());
    if (!WinHttpSendRequest(request.get(), L"Content-Type: application/json\r\n", static_cast<DWORD>(-1),
                            const_cast<char*>(body.data()), length, length, 0) ||
        !WinHttpReceiveResponse(request.get(), nullptr)) {
        return false;
    }
    DWORD statusSize = sizeof(status);
    WinHttpQueryHeaders(request.get(), WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize,
                        WINHTTP_NO_HEADER_INDEX);
    char buffer[4096];
    DWORD read = 0;
    while (WinHttpReadData(request.get(), buffer, sizeof(buffer), &read) && read > 0) {
        response.append(buffer, read);
    }
    return true;
}

LoginResult fail(std::wstring message) {
    return {false, std::move(message)};
}

}  // namespace

LoginResult login(const std::string& username, const std::string& password) {
    const std::string hwid = machineId();
    const std::string nonce = randomNonce();
    if (hwid.empty() || nonce.empty()) {
        return fail(L"Could not identify this PC.");
    }

    const std::string body = "{\"username\":" + jsonString(username) +
                             ",\"password\":" + jsonString(password) +
                             ",\"hwid\":" + jsonString(hwid) + ",\"nonce\":" + jsonString(nonce) + "}";
    DWORD status = 0;
    std::string response;
    if (!post(body, status, response)) {
        return fail(L"Could not reach the Cobble server. Check your connection.");
    }

    Fields envelope;
    if (!parseObject(response, envelope)) {
        return fail(L"The Cobble server sent an unreadable reply (HTTP " + std::to_wstring(status) + L").");
    }
    if (envelope["payload_b64"].empty()) {
        const std::string error = envelope["error"];
        return fail(error.empty() ? L"Sign-in failed (HTTP " + std::to_wstring(status) + L")."
                                  : widen(error));
    }

    const std::vector<uint8_t> payloadBytes = base64(envelope["payload_b64"]);
    const std::vector<uint8_t> signature = base64(envelope["sig_b64"]);
    Fields payload;
    if (payloadBytes.empty() || signature.size() != 64 ||
        !tweetnacl::verify(signature.data(), payloadBytes.data(), payloadBytes.size(), kServerKey) ||
        !parseObject(std::string(payloadBytes.begin(), payloadBytes.end()), payload) ||
        payload["product"] != "v2") {
        return fail(L"The server reply failed verification.");
    }

    if (payload["valid"] != "true") {
        const std::string message = payload["message"];
        return fail(message.empty() ? L"Sign-in failed." : widen(message));
    }
    /* A signed grant only counts for the request it answers, on this PC. */
    if (payload["nonce"] != nonce || payload["hwid"] != hwid) {
        return fail(L"The server reply failed verification.");
    }
    if (static_cast<long long>(time(nullptr)) > std::strtoll(payload["expires_at"].c_str(), nullptr, 10)) {
        return fail(L"The server reply has expired. Check that your PC's clock is correct.");
    }
    return {true, {}};
}

}  // namespace cold::injector::license
