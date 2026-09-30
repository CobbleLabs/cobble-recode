#include "core/Log.hpp"

#include <windows.h>

#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <vector>

namespace cold::log {
namespace {

std::mutex g_mutex;
HANDLE g_file = INVALID_HANDLE_VALUE;
Level g_minimum = Level::Debug;
long long g_epoch = 0;

long long nowMicros() {
    LARGE_INTEGER frequency;
    LARGE_INTEGER counter;
    QueryPerformanceFrequency(&frequency);
    QueryPerformanceCounter(&counter);
    return counter.QuadPart * 1000000LL / frequency.QuadPart;
}

const char* levelTag(Level level) {
    switch (level) {
        case Level::Trace: return "TRACE";
        case Level::Debug: return "DEBUG";
        case Level::Info: return "INFO ";
        case Level::Warn: return "WARN ";
        case Level::Error: return "ERROR";
    }
    return "?????";
}

}  // namespace

void open(const std::wstring& directory) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_file != INVALID_HANDLE_VALUE) {
        return;
    }
    g_epoch = nowMicros();

    std::wstring path = directory;
    if (!path.empty() && path.back() != L'\\' && path.back() != L'/') {
        path.push_back(L'\\');
    }
    path += L"cobble.log";

    g_file = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr,
                         CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);

    SYSTEMTIME time;
    GetLocalTime(&time);
    char banner[256];
    const int length = std::snprintf(
        banner, sizeof(banner),
        "Cobble Recode payload log - %04u-%02u-%02u %02u:%02u:%02u\r\n"
        "----------------------------------------------------\r\n",
        time.wYear, time.wMonth, time.wDay, time.wHour, time.wMinute, time.wSecond);
    if (g_file != INVALID_HANDLE_VALUE && length > 0) {
        DWORD written = 0;
        WriteFile(g_file, banner, static_cast<DWORD>(length), &written, nullptr);
    }
}

void close() {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_file != INVALID_HANDLE_VALUE) {
        CloseHandle(g_file);
        g_file = INVALID_HANDLE_VALUE;
    }
}

void setMinimumLevel(Level level) { g_minimum = level; }

Level minimumLevel() { return g_minimum; }

std::string format(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    va_list probe;
    va_copy(probe, args);
    const int needed = std::vsnprintf(nullptr, 0, fmt, probe);
    va_end(probe);

    std::string result;
    if (needed > 0) {
        result.resize(static_cast<std::size_t>(needed));
        std::vsnprintf(result.data(), static_cast<std::size_t>(needed) + 1, fmt, args);
    }
    va_end(args);
    return result;
}

void writeLine(Level level, std::string_view scope, std::string text) {
    const long long elapsed = nowMicros() - g_epoch;
    char prefix[96];
    const int prefixLength = std::snprintf(
        prefix, sizeof(prefix), "[%7lld.%03lld] %s [%.*s] ", elapsed / 1000000,
        (elapsed / 1000) % 1000, levelTag(level), static_cast<int>(scope.size()), scope.data());

    std::string line;
    line.reserve(static_cast<std::size_t>(prefixLength) + text.size() + 2);
    line.append(prefix, static_cast<std::size_t>(prefixLength < 0 ? 0 : prefixLength));
    line.append(text);
    line.append("\r\n");

    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_file != INVALID_HANDLE_VALUE) {
        DWORD written = 0;
        WriteFile(g_file, line.data(), static_cast<DWORD>(line.size()), &written, nullptr);
        FlushFileBuffers(g_file);
    }
    OutputDebugStringA(line.c_str());
}

Stage::Stage(std::string_view scope, std::string_view name)
    : scope_(scope), name_(name), start_(nowMicros()) {
    COLD_DEBUG(scope_, "%.*s ...", static_cast<int>(name_.size()), name_.data());
}

Stage::~Stage() {
    if (failed_) {
        return;
    }
    const double millis = static_cast<double>(nowMicros() - start_) / 1000.0;
    COLD_INFO(scope_, "%.*s ok (%.1f ms)", static_cast<int>(name_.size()), name_.data(), millis);
}

void Stage::fail(std::string reason) {
    failed_ = true;
    const double millis = static_cast<double>(nowMicros() - start_) / 1000.0;
    COLD_ERROR(scope_, "%.*s FAILED after %.1f ms: %s", static_cast<int>(name_.size()),
               name_.data(), millis, reason.c_str());
}

}  // namespace cold::log
