#pragma once

#include <windows.h>

#include <string>

namespace cold::injector {

struct InjectResult {
    bool ok = false;
    std::wstring message;
};

/* Classic LoadLibraryW-in-a-remote-thread. The payload does the rest itself:
 * it finds the JVM, waits for Minecraft and installs its own hook, so the
 * injector never has to reason about the game's state. */
InjectResult injectLibrary(DWORD processId, const std::wstring& libraryPath);

/* True when a module with the library's file name is already mapped into the
 * target. Matching the name rather than the path also catches a copy loaded
 * from the temp fallback, which a second LoadLibrary would load again. */
bool isLibraryLoaded(DWORD processId, const std::wstring& libraryPath);

}  // namespace cold::injector
