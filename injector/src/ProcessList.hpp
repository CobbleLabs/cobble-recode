#pragma once

#include <windows.h>

#include <string>
#include <vector>

namespace cold::injector {

struct ProcessEntry {
    DWORD id = 0;
    std::wstring executable;
    std::wstring title;
    bool sixtyFourBit = true;
};

/* Java processes that own a visible window. A headless JVM has nothing to
 * inject into, and the window title is what tells one instance from another. */
std::vector<ProcessEntry> enumerateJavaProcesses();

}  // namespace cold::injector
