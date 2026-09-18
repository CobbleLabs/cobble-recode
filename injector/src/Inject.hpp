#pragma once

#include <windows.h>

#include <functional>
#include <string>
#include <vector>

namespace cold::injector {

struct InjectResult {
    bool ok = false;
    std::wstring message;
    /* One line per loader step, in order. The verbose view prints these live
     * and again in the diagnostics block, so a failure shows exactly how far
     * the chain got and with which error. */
    std::vector<std::wstring> steps;
};

/* Receives each loader step as it happens; may be empty. */
using ProgressSink = std::function<void(const std::wstring&)>;

/* Classic LoadLibraryW-in-a-remote-thread. The payload does the rest itself:
 * it finds the JVM, waits for Minecraft and installs its own hook, so the
 * injector never has to reason about the game's state. */
InjectResult injectLibrary(DWORD processId, const std::wstring& libraryPath,
                           const ProgressSink& progress = {});

/* True when the module is already mapped into the target, which would make a
 * second LoadLibrary a no-op that silently looks like success. */
bool isLibraryLoaded(DWORD processId, const std::wstring& libraryPath);

/* Path of the target's main image (the exe), for the diagnostics view - or an
 * empty string when the module snapshot cannot be taken. */
std::wstring targetImagePath(DWORD processId);

}  // namespace cold::injector
