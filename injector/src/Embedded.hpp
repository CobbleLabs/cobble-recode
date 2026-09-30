#pragma once

#include <windows.h>

#include <cstddef>
#include <cstdint>
#include <string>

namespace cold::injector::embedded {

bool payloadBytes(const std::uint8_t*& data, std::size_t& size);
bool scriptBytes(int resourceId, const std::uint8_t*& data, std::size_t& size);
std::wstring loaderDirectory();
std::wstring ensurePayloadOnDisk();
void ensureScriptsOnDisk(const std::wstring& dllPath);

}
