#include "Inject.hpp"

#include <tlhelp32.h>

#include <vector>

namespace cold::injector {
namespace {

/* Every loader step appends here, and the caller's sink (if any) sees the same
 * line the moment it is produced, so the console shows progress live. */
struct Recorder {
    std::vector<std::wstring>* steps = nullptr;
    const ProgressSink* sink = nullptr;

    void step(std::wstring text) const {
        if (steps != nullptr) {
            steps->push_back(text);
        }
        if (sink != nullptr && *sink) {
            (*sink)(text);
        }
    }

    void stepf(const wchar_t* fmt, ...) {
        wchar_t buffer[512];
        va_list args;
        va_start(args, fmt);
        _vsnwprintf_s(buffer, _TRUNCATE, fmt, args);
        va_end(args);
        step(buffer);
    }
};

std::wstring describeLastError(const wchar_t* operation) {
    const DWORD error = GetLastError();
    wchar_t* text = nullptr;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
                       FORMAT_MESSAGE_IGNORE_INSERTS,
                   nullptr, error, 0, reinterpret_cast<wchar_t*>(&text), 0, nullptr);

    std::wstring message = operation;
    message += L" failed (";
    message += std::to_wstring(error);
    message += L"): ";
    message += text != nullptr ? text : L"unknown error";
    if (text != nullptr) {
        LocalFree(text);
    }
    while (!message.empty() && (message.back() == L'\n' || message.back() == L'\r')) {
        message.pop_back();
    }
    return message;
}

std::wstring toLower(std::wstring value) {
    for (wchar_t& character : value) {
        character = static_cast<wchar_t>(towlower(character));
    }
    return value;
}

}  // namespace

bool isLibraryLoaded(DWORD processId, const std::wstring& libraryPath) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, processId);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return false;
    }
    const std::wstring wanted = toLower(libraryPath);
    MODULEENTRY32W module = {};
    module.dwSize = sizeof(module);
    bool found = false;
    if (Module32FirstW(snapshot, &module)) {
        do {
            if (toLower(module.szExePath) == wanted) {
                found = true;
                break;
            }
        } while (Module32NextW(snapshot, &module));
    }
    CloseHandle(snapshot);
    return found;
}

std::wstring targetImagePath(DWORD processId) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, processId);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return L"";
    }
    MODULEENTRY32W module = {};
    module.dwSize = sizeof(module);
    std::wstring path;
    if (Module32FirstW(snapshot, &module)) {
        path = module.szExePath;
    }
    CloseHandle(snapshot);
    return path;
}

InjectResult injectLibrary(DWORD processId, const std::wstring& libraryPath,
                           const ProgressSink& progress) {
    InjectResult result;
    Recorder recorder{&result.steps, &progress};

    recorder.stepf(L"target pid %lu, payload %s", static_cast<unsigned long>(processId),
                   libraryPath.c_str());

    HANDLE process = OpenProcess(PROCESS_CREATE_THREAD | PROCESS_QUERY_INFORMATION |
                                     PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
                                 FALSE, processId);
    if (process == nullptr) {
        result.message = describeLastError(L"OpenProcess");
        recorder.step(L"STEP OpenProcess: " + result.message);
        recorder.step(L"hint: run the injector as the same (or an elevated) user as the game");
        return result;
    }
    recorder.step(L"STEP OpenProcess ok (handle acquired)");

    BOOL wow64 = FALSE;
    if (IsWow64Process(process, &wow64) && wow64) {
        CloseHandle(process);
        result.message = L"target is a 32-bit JVM; Cobble Recode is x64 only";
        recorder.step(L"STEP IsWow64Process: " + result.message);
        return result;
    }
    recorder.step(L"STEP IsWow64Process ok (64-bit target)");

    /* Remote module list, for the diagnostics view: proves the toolhelp snapshot
     * can see into the target and names the JVM that is there. */
    {
        HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, processId);
        if (snapshot != INVALID_HANDLE_VALUE) {
            MODULEENTRY32W module = {};
            module.dwSize = sizeof(module);
            int count = 0;
            std::wstring jvm;
            if (Module32FirstW(snapshot, &module)) {
                do {
                    ++count;
                    const std::wstring lower = toLower(module.szModule);
                    if (jvm.empty() &&
                        (lower.find(L"jvm.dll") != std::wstring::npos ||
                         lower.find(L"java.dll") != std::wstring::npos ||
                         lower.find(L"server\\jvm.dll") != std::wstring::npos)) {
                        jvm = module.szExePath;
                    }
                } while (Module32NextW(snapshot, &module));
            }
            CloseHandle(snapshot);
            if (jvm.empty()) {
                recorder.stepf(L"INFO %d modules mapped, JVM not loaded yet (game may still be starting)", count);
            } else {
                recorder.stepf(L"INFO %d modules mapped, JVM found: %s", count, jvm.c_str());
            }
        } else {
            recorder.step(L"INFO module snapshot unavailable: " + describeLastError(L"CreateToolhelp32Snapshot"));
        }
    }

    const SIZE_T bytes = (libraryPath.size() + 1) * sizeof(wchar_t);
    void* remote = VirtualAllocEx(process, nullptr, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (remote == nullptr) {
        result.message = describeLastError(L"VirtualAllocEx");
        recorder.step(L"STEP VirtualAllocEx: " + result.message);
        CloseHandle(process);
        return result;
    }
    recorder.stepf(L"STEP VirtualAllocEx ok (%zu bytes at %p)", bytes, remote);

    if (!WriteProcessMemory(process, remote, libraryPath.c_str(), bytes, nullptr)) {
        result.message = describeLastError(L"WriteProcessMemory");
        recorder.step(L"STEP WriteProcessMemory: " + result.message);
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return result;
    }
    recorder.step(L"STEP WriteProcessMemory ok (path written)");

    {
        /* Read-back: the write is verified rather than assumed, because a
         * truncated path string is exactly the kind of failure that turns into
         * "LoadLibraryW returned NULL" with no further explanation. */
        std::vector<wchar_t> verify(libraryPath.size() + 1, L'\0');
        SIZE_T read = 0;
        if (ReadProcessMemory(process, remote, verify.data(), bytes, &read) && read == bytes &&
            verify.data() == libraryPath) {
            recorder.step(L"STEP ReadProcessMemory ok (path verified in target)");
        } else {
            recorder.step(L"INFO path read-back mismatch (write may not have landed)");
        }
    }

    /* kernel32 is mapped at the same base in every process on a given boot, so
     * the local address of LoadLibraryW is valid in the target. */
    HMODULE kernel32 = GetModuleHandleW(L"kernel32.dll");
    auto loadLibrary =
        reinterpret_cast<LPTHREAD_START_ROUTINE>(GetProcAddress(kernel32, "LoadLibraryW"));
    if (loadLibrary == nullptr) {
        result.message = L"LoadLibraryW could not be resolved";
        recorder.step(L"STEP GetProcAddress(LoadLibraryW): " + result.message);
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return result;
    }
    recorder.stepf(L"STEP LoadLibraryW resolved at %p", reinterpret_cast<void*>(loadLibrary));

    HANDLE thread = CreateRemoteThread(process, nullptr, 0, loadLibrary, remote, 0, nullptr);
    if (thread == nullptr) {
        result.message = describeLastError(L"CreateRemoteThread");
        recorder.step(L"STEP CreateRemoteThread: " + result.message);
        recorder.step(L"hint: some launchers inject their own DLLs first; try injecting again once the world has loaded");
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        CloseHandle(process);
        return result;
    }
    recorder.step(L"STEP CreateRemoteThread ok (remote thread running LoadLibraryW)");

    /* A load that crashes the target dies here before the wait does, so poll
     * the process while waiting and say so instead of reporting a timeout. */
    const DWORD waitBudget = 20000;
    const DWORD started = GetTickCount();
    DWORD wait = WAIT_TIMEOUT;
    DWORD targetExit = 0;
    while (true) {
        wait = WaitForSingleObject(thread, 250);
        if (wait == WAIT_OBJECT_0) {
            break;
        }
        if (GetExitCodeProcess(process, &targetExit) && targetExit != STILL_ACTIVE) {
            recorder.stepf(L"FATAL target process exited (code %lu) while the payload was loading", targetExit);
            result.message = L"the game exited while the payload was loading";
            CloseHandle(thread);
            VirtualFreeEx(process, remote, 0, MEM_RELEASE);
            CloseHandle(process);
            return result;
        }
        if (GetTickCount() - started > waitBudget) {
            wait = WAIT_TIMEOUT;
            break;
        }
    }
    DWORD exitCode = 0;
    GetExitCodeThread(thread, &exitCode);
    CloseHandle(thread);

    const bool mapped = isLibraryLoaded(processId, libraryPath);
    VirtualFreeEx(process, remote, 0, MEM_RELEASE);
    CloseHandle(process);

    if (wait == WAIT_TIMEOUT) {
        result.message = L"LoadLibraryW did not return within 20 seconds";
        recorder.step(L"STEP wait: " + result.message);
        recorder.step(mapped ? L"INFO the module is mapped despite the slow load; it may still be bootstrapping"
                             : L"INFO module not mapped; the remote thread is stuck - check the game's own log");
        return result;
    }
    if (exitCode == 0) {
        result.message = L"LoadLibraryW returned NULL";
        recorder.stepf(L"STEP LoadLibraryW returned NULL (thread exit code 0)%s", mapped ? L" but the module is mapped (re-load no-op)" : L"");
        if (!mapped) {
            recorder.step(L"hint: usual causes are a blocked DLL (antivirus), a missing runtime, or a crash inside DllMain - see the diagnostics below");
        }
        return result;
    }

    recorder.stepf(L"STEP LoadLibraryW returned 0x%08lX; module mapped = %s", exitCode, mapped ? L"yes" : L"no");
    if (!mapped) {
        recorder.step(L"INFO module not visible in the remote module list (snapshot may lag); continuing");
    }

    result.ok = true;
    result.message = L"payload mapped; see cobble.log beside the DLL";
    return result;
}

}  // namespace cold::injector
