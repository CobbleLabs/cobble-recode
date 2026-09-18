#include <windows.h>

#include <conio.h>
#include <cstdio>
#include <cwchar>
#include <shlobj.h>
#include <string>
#include <vector>

#include "Inject.hpp"
#include "Embedded.hpp"
#include "KeyCheck.hpp"
#include "ProcessList.hpp"

namespace {

constexpr wchar_t kDefaultPayload[] = L"cobble.dll";
constexpr DWORD kRefreshMillis = 600;

/* ANSI colour, matching the client's palette so the two read as one product. */
constexpr const char* kReset = "\x1b[0m";
constexpr const char* kAccent = "\x1b[38;2;85;214;255m";
constexpr const char* kDim = "\x1b[38;2;110;122;135m";
constexpr const char* kText = "\x1b[38;2;233;238;244m";
constexpr const char* kBad = "\x1b[38;2;255;92;110m";
constexpr const char* kGood = "\x1b[38;2;91;229;154m";
constexpr const char* kWarn = "\x1b[38;2;255;197;92m";

void enableVirtualTerminal() {
    HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
    DWORD mode = 0;
    if (GetConsoleMode(output, &mode)) {
        SetConsoleMode(output, mode | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
    }
    SetConsoleOutputCP(CP_UTF8);
}

void hideCursor(bool hidden) {
    HANDLE output = GetStdHandle(STD_OUTPUT_HANDLE);
    CONSOLE_CURSOR_INFO info = {};
    if (GetConsoleCursorInfo(output, &info)) {
        info.bVisible = hidden ? FALSE : TRUE;
        SetConsoleCursorInfo(output, &info);
    }
}

void clearScreen() {
    std::printf("\x1b[2J\x1b[H");
}

std::wstring sideBySidePayloadPath(const std::wstring& argument) {
    wchar_t resolved[MAX_PATH] = {};
    if (!argument.empty()) {
        if (GetFullPathNameW(argument.c_str(), MAX_PATH, resolved, nullptr) != 0) {
            return resolved;
        }
        return argument;
    }
    wchar_t self[MAX_PATH] = {};
    GetModuleFileNameW(nullptr, self, MAX_PATH);
    wchar_t* fileName = wcsrchr(self, L'\\');
    if (fileName != nullptr) {
        *(fileName + 1) = L'\0';
    }
    std::wstring path = self;
    path += kDefaultPayload;
    return path;
}

std::wstring bundledPayloadPath() {
    if (!cold::injector::embedded::hasEmbeddedPayload()) {
        return L"";
    }
    std::wstring extracted = cold::injector::embedded::ensurePayloadOnDisk();
    if (extracted.empty()) {
        return L"";
    }
    cold::injector::embedded::ensureScriptsOnDisk(extracted);
    return extracted;
}

std::wstring resolvePayloadPath(const std::wstring& argument, bool explicitArgument) {
    if (explicitArgument) {
        return sideBySidePayloadPath(argument);
    }
    if (argument.empty()) {
        std::wstring bundled = bundledPayloadPath();
        if (!bundled.empty()) {
            return bundled;
        }
    }
    return sideBySidePayloadPath(argument);
}

bool fileExists(const std::wstring& path) {
    const DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

std::wstring directoryOf(const std::wstring& path) {
    const std::size_t slash = path.find_last_of(L"\\/");
    return slash == std::wstring::npos ? std::wstring() : path.substr(0, slash);
}

void drawBanner(const std::wstring& payload) {
    const bool bundled = cold::injector::embedded::hasEmbeddedPayload();
    std::printf("%s  cobble%s.vip%s  -  Cobble Recode loader\n", kText, kAccent, kReset);
    std::printf("%s  payload  %ls%s  [public]%s\n\n", kDim, payload.c_str(), bundled ? "  (bundled)" : "",
                kReset);
}

void drawList(const std::vector<cold::injector::ProcessEntry>& entries, std::size_t selected) {
    if (entries.empty()) {
        std::printf("%s  waiting for a java window...%s\n", kDim, kReset);
        return;
    }
    for (std::size_t index = 0; index < entries.size(); ++index) {
        const cold::injector::ProcessEntry& entry = entries[index];
        const bool active = index == selected;
        std::printf("%s%s %s%5lu%s  %-9ls  %s%ls%s%s\n", active ? kAccent : kDim,
                    active ? "▸" : " ", active ? kText : kDim,
                    static_cast<unsigned long>(entry.id), kDim, entry.executable.c_str(),
                    active ? kText : kDim, entry.title.c_str(),
                    entry.sixtyFourBit ? "" : "  (32-bit)", kReset);
    }
}

/* Reads cobble.log beside the payload, freshest lines last. An empty return
 * means the payload never opened its log - itself a diagnosis. */
/* The payload keeps its data in %APPDATA%\cobble and writes its log there;
 * the DLL folder is only the fallback when APPDATA is unavailable. */
std::wstring logPathFor(const std::wstring& payloadPath) {
    wchar_t appdata[MAX_PATH] = {};
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, appdata))) {
        return std::wstring(appdata) + L"\\cobble\\cobble.log";
    }
    std::wstring logPath = directoryOf(payloadPath);
    if (!logPath.empty()) {
        logPath += L"\\cobble.log";
    } else {
        logPath = L"cobble.log";
    }
    return logPath;
}

/* Byte offset where the live log watch starts, so only new lines print. */
ULARGE_INTEGER logEndOffset(const std::wstring& payloadPath) {
    ULARGE_INTEGER size{};
    HANDLE file = CreateFileW(logPathFor(payloadPath).c_str(), GENERIC_READ,
                              FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return size;
    }
    LARGE_INTEGER zero{};
    LARGE_INTEGER end{};
    if (SetFilePointerEx(file, zero, &end, FILE_END)) {
        size.QuadPart = end.QuadPart;
    }
    CloseHandle(file);
    return size;
}

/* Streams every new log line the payload writes, for up to `seconds`, so the
 * bootstrap's stages appear in the console as they happen. A key press ends it
 * early. Returns what it printed, for the diagnostics block to summarise. */
std::vector<std::wstring> watchLog(const std::wstring& payloadPath, ULARGE_INTEGER from,
                                   int seconds) {
    std::vector<std::wstring> lines;
    const std::wstring path = logPathFor(payloadPath);
    const ULONGLONG deadline = GetTickCount64() + static_cast<ULONGLONG>(seconds) * 1000ULL;
    std::string pending;
    while (GetTickCount64() < deadline) {
        while (_kbhit()) {
            (void)_getwch();
            return lines;
        }
        HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                  nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file != INVALID_HANDLE_VALUE) {
            /* The payload opens its log with CREATE_ALWAYS, which truncates it -
             * a pre-inject offset can then sit past EOF and silently read
             * nothing. Clamp back to the start when the file shrank. */
            LARGE_INTEGER current{};
            LARGE_INTEGER zeroForSize{};
            if (SetFilePointerEx(file, zeroForSize, &current, FILE_END) &&
                current.QuadPart < static_cast<LONGLONG>(from.QuadPart)) {
                from.QuadPart = 0ULL;
            }
            LARGE_INTEGER move{};
            move.QuadPart = static_cast<LONGLONG>(from.QuadPart);
            if (SetFilePointerEx(file, move, nullptr, FILE_BEGIN)) {
                char buffer[4096];
                DWORD read = 0;
                while (ReadFile(file, buffer, sizeof(buffer), &read, nullptr) && read > 0) {
                    pending.append(buffer, read);
                }
            }
            LARGE_INTEGER end{};
            LARGE_INTEGER zero{};
            if (SetFilePointerEx(file, zero, &end, FILE_END)) {
                from.QuadPart = end.QuadPart;
            }
            CloseHandle(file);
        }
        std::size_t newline = std::wstring::npos;
        while ((newline = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, newline);
            pending.erase(0, newline + 1);
            if (!line.empty() && line.back() == '\r') {
                line.pop_back();
            }
            if (line.empty()) {
                continue;
            }
            std::wstring wide(line.begin(), line.end());
            lines.push_back(wide);
            std::printf("  %ls\n", wide.c_str());
            std::fflush(stdout);
        }
        Sleep(120);
    }
    return lines;
}

std::vector<std::wstring> readLogTail(const std::wstring& payloadPath, std::size_t maxLines) {
    std::vector<std::wstring> lines;
    const std::wstring logPath = logPathFor(payloadPath);

    HANDLE file = CreateFileW(logPath.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                              nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return lines;
    }

    std::string contents;
    char buffer[4096];
    DWORD read = 0;
    while (ReadFile(file, buffer, sizeof(buffer), &read, nullptr) && read > 0) {
        contents.append(buffer, read);
        if (contents.size() > 1 << 20) {
            /* Keep at most the last megabyte; the interesting part is the end. */
            contents.erase(0, contents.size() - (1 << 20));
        }
    }
    CloseHandle(file);

    /* Normalise line endings, keep the tail. */
    if (!contents.empty() && contents.back() != '\n') {
        contents.push_back('\n');
    }
    std::size_t start = 0;
    while (start < contents.size()) {
        std::size_t end = contents.find('\n', start);
        if (end == std::wstring::npos) {
            end = contents.size();
        }
        std::size_t stop = end;
        if (stop > start && contents[stop - 1] == '\r') {
            --stop;
        }
        std::wstring line(contents.begin() + static_cast<std::ptrdiff_t>(start),
                          contents.begin() + static_cast<std::ptrdiff_t>(stop));
        lines.push_back(line);
        start = end + 1;
    }
    if (lines.size() > maxLines) {
        lines.erase(lines.begin(), lines.end() - static_cast<std::ptrdiff_t>(maxLines));
    }
    return lines;
}

bool payloadLoadedIn(DWORD processId, const std::wstring& payload) {
    return cold::injector::isLibraryLoaded(processId, payload);
}

/* The whole post-mortem: target state, payload state, every loader step, the
 * payload's own log tail, and what to try next. Shown after every inject, not
 * only the failing ones - a success with nothing in the log is also a clue. */
void printDiagnostics(const cold::injector::InjectResult& result, DWORD processId,
                      const std::wstring& payload) {
    std::printf("\n%s  ---------------- diagnostics ----------------%s\n", kDim, kReset);
    std::printf("%s  result     %s%ls%s\n", kDim, result.ok ? kGood : kBad,
                result.message.c_str(), kReset);
    std::printf("%s  payload    %s%ls%s\n", kDim, kText, payload.c_str(), kReset);
    std::printf("%s  exists     %s%s%s\n", kDim, fileExists(payload) ? kGood : kBad,
                fileExists(payload) ? "yes" : "no", kReset);

    const bool mappedNow = payloadLoadedIn(processId, payload);
    std::printf("%s  in target  %s%s%s%s\n", kDim, mappedNow ? kGood : kBad,
                mappedNow ? "module mapped" : "module NOT mapped",
                result.ok && !mappedNow ? kWarn : kReset, kReset);

    const std::wstring image = cold::injector::targetImagePath(processId);
    if (!image.empty()) {
        std::printf("%s  target     %ls%s\n", kDim, image.c_str(), kReset);
    } else {
        std::printf("%s  target     pid %lu (module snapshot unavailable - process may have exited)%s\n",
                    kDim, static_cast<unsigned long>(processId), kReset);
    }

    std::printf("\n%s  loader steps:%s\n", kDim, kReset);
    for (const std::wstring& step : result.steps) {
        std::printf("  %ls\n", step.c_str());
    }

    const std::vector<std::wstring> log = readLogTail(payload, 40);
    std::printf("\n%s  payload log (cobble.log, last %zu lines):%s\n", kDim, log.size(), kReset);
    if (log.empty()) {
        std::printf("  %s(no log found%s - if the module is mapped, DllMain ran but the\n"
                    "  payload never reached its first log line; if it is not mapped,\n"
                    "  LoadLibrary never really loaded the file)%s\n",
                    kWarn, kReset, kReset);
    } else {
        for (const std::wstring& line : log) {
            std::printf("  %ls\n", line.c_str());
        }
    }

    std::printf("%s  ----------------------------------------------%s\n", kDim, kReset);
}

/* The console must not close on the result: everything above is worthless if
 * the window vanishes with it. Waits for a key, and does not time out. */
void pauseForUser() {
    std::printf("\n%s  press any key to continue...%s\n", kAccent, kReset);
    std::fflush(stdout);
    (void)_getwch();
}

int runInteractive(const std::wstring& payload) {
    std::vector<cold::injector::ProcessEntry> entries;
    std::size_t selected = 0;
    ULONGLONG nextRefresh = 0;
    bool dirty = true;

    hideCursor(true);
    while (true) {
        const ULONGLONG now = GetTickCount64();
        if (now >= nextRefresh) {
            const DWORD previous = entries.empty() ? 0 : entries[selected].id;
            entries = cold::injector::enumerateJavaProcesses();
            selected = 0;
            for (std::size_t index = 0; index < entries.size(); ++index) {
                if (entries[index].id == previous) {
                    selected = index;
                    break;
                }
            }
            nextRefresh = now + kRefreshMillis;
            dirty = true;
        }

        if (dirty) {
            clearScreen();
            drawBanner(payload);
            drawList(entries, selected);
            std::printf("\n%s  up/down select   enter inject   esc quit%s\n", kDim, kReset);
            dirty = false;
        }

        if (_kbhit()) {
            int key = _getwch();
            if (key == 0 || key == 0xE0) {
                key = _getwch();
                if (key == 72 && !entries.empty()) {
                    selected = selected == 0 ? entries.size() - 1 : selected - 1;
                    dirty = true;
                } else if (key == 80 && !entries.empty()) {
                    selected = (selected + 1) % entries.size();
                    dirty = true;
                }
            } else if (key == 13 && !entries.empty()) {
                hideCursor(false);
                const cold::injector::ProcessEntry entry = entries[selected];

                if (cold::injector::isLibraryLoaded(entry.id, payload)) {
                    std::printf("\n%s  already loaded in that process%s\n", kBad, kReset);
                    cold::injector::InjectResult already;
                    already.message = L"no action taken: the payload module is already mapped";
                    printDiagnostics(already, entry.id, payload);
                    pauseForUser();
                    return 2;
                }

                clearScreen();
                drawBanner(payload);
                std::printf("%s  injecting into pid %lu - %ls%s\n\n", kDim,
                            static_cast<unsigned long>(entry.id), entry.title.c_str(), kReset);

                cold::injector::InjectResult result;
                const cold::injector::ProgressSink sink = [](const std::wstring& line) {
                    std::printf("  %ls\n", line.c_str());
                    std::fflush(stdout);
                };
                result = cold::injector::injectLibrary(entry.id, payload, sink);

                std::printf("\n%s  %ls%s\n", result.ok ? kGood : kBad, result.message.c_str(),
                            kReset);
                if (result.ok) {
                    /* The payload bootstraps on its own thread after LoadLibrary
                     * returns, so the interesting log lines land in the next
                     * few seconds. Stream them live; a key skips ahead. */
                    std::printf("\n%s  payload bootstrap (live, any key to skip):%s\n", kDim,
                                kReset);
                    watchLog(payload, logEndOffset(payload), 8);
                }
                printDiagnostics(result, entry.id, payload);
                pauseForUser();
                return result.ok ? 0 : 1;
            } else if (key == 27) {
                hideCursor(false);
                clearScreen();
                return 0;
            }
        }
        Sleep(16);
    }
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    enableVirtualTerminal();

    std::wstring payload;
    if (argc >= 2 && iswdigit(argv[1][0]) == 0 && argc == 2) {
        /* A single non-numeric argument is a payload path override for dev. */
        payload = resolvePayloadPath(argv[1], true);
    } else {
        payload = resolvePayloadPath(argc >= 3 ? argv[2] : L"", argc >= 3);
    }

    if (!fileExists(payload)) {
        std::printf("%s  payload not found: %ls%s\n", kBad, payload.c_str(), kReset);
        pauseForUser();
        return 1;
    }

    const cold::injector::keycheck::KeyResult key =
        cold::injector::keycheck::ensureKey(payload);
    if (!key.ok) {
        std::printf("%s  %s%s\n", kBad, key.message.c_str(), kReset);
        pauseForUser();
        return 1;
    }
    std::printf("%s  %s (%s)%s\n", kGood, key.message.c_str(), key.uid.c_str(), kReset);

    /* Command-line pid: same verbose treatment as the interactive path. */
    if (argc >= 2 && iswdigit(argv[1][0]) != 0) {
        const auto processId = static_cast<DWORD>(wcstoul(argv[1], nullptr, 10));
        std::printf("%s  injecting into pid %lu%s\n\n", kDim, static_cast<unsigned long>(processId),
                    kReset);

        cold::injector::InjectResult result;
        const cold::injector::ProgressSink sink = [](const std::wstring& line) {
            std::printf("  %ls\n", line.c_str());
            std::fflush(stdout);
        };
        result = cold::injector::injectLibrary(processId, payload, sink);

        std::printf("\n%s  %ls%s\n", result.ok ? kGood : kBad, result.message.c_str(), kReset);
        if (result.ok) {
            std::printf("\n%s  payload bootstrap (live, any key to skip):%s\n", kDim, kReset);
            watchLog(payload, logEndOffset(payload), 8);
        }
        printDiagnostics(result, processId, payload);
        pauseForUser();
        return result.ok ? 0 : 1;
    }

    return runInteractive(payload);
}
