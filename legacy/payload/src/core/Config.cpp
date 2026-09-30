#include "core/Config.hpp"

#include <windows.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "core/Log.hpp"

namespace cold::config {
namespace {

constexpr std::string_view kScope = "config";

std::string trim(std::string_view text) {
    std::size_t begin = 0;
    std::size_t end = text.size();
    while (begin < end && (text[begin] == ' ' || text[begin] == '\t' || text[begin] == '\r')) {
        ++begin;
    }
    while (end > begin &&
           (text[end - 1] == ' ' || text[end - 1] == '\t' || text[end - 1] == '\r')) {
        --end;
    }
    return std::string(text.substr(begin, end - begin));
}

}  // namespace

bool Document::load(const std::wstring& path) {
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        COLD_DEBUG(kScope, "no config on disk yet, starting from defaults");
        return false;
    }

    LARGE_INTEGER size;
    if (!GetFileSizeEx(file, &size) || size.QuadPart <= 0 || size.QuadPart > (4 << 20)) {
        CloseHandle(file);
        return false;
    }
    std::string content(static_cast<std::size_t>(size.QuadPart), '\0');
    DWORD read = 0;
    const bool ok = ReadFile(file, content.data(), static_cast<DWORD>(content.size()), &read,
                             nullptr) != FALSE;
    CloseHandle(file);
    if (!ok) {
        COLD_ERROR(kScope, "failed to read config");
        return false;
    }
    content.resize(read);

    sections_.clear();
    std::string section;
    std::size_t start = 0;
    while (start <= content.size()) {
        std::size_t end = content.find('\n', start);
        if (end == std::string::npos) end = content.size();
        const std::string line = trim(std::string_view(content).substr(start, end - start));
        start = end + 1;

        if (line.empty() || line[0] == '#' || line[0] == ';') {
            continue;
        }
        if (line.front() == '[' && line.back() == ']') {
            section = line.substr(1, line.size() - 2);
            sections_[section];
            continue;
        }
        const std::size_t separator = line.find('=');
        if (separator == std::string::npos || section.empty()) {
            continue;
        }
        sections_[section][trim(std::string_view(line).substr(0, separator))] =
            trim(std::string_view(line).substr(separator + 1));
    }

    COLD_INFO(kScope, "loaded %zu sections", sections_.size());
    return true;
}

bool Document::save(const std::wstring& path) const {
    std::string content =
        "# Cobble Recode configuration\n"
        "# Edited by the client on exit; hand edits are picked up on the next injection.\n";
    for (const auto& [section, entries] : sections_) {
        content += "\n[";
        content += section;
        content += "]\n";
        for (const auto& [key, value] : entries) {
            content += key;
            content += " = ";
            content += value;
            content += '\n';
        }
    }

    HANDLE file = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        COLD_ERROR(kScope, "cannot open config for writing (error %lu)", GetLastError());
        return false;
    }
    DWORD written = 0;
    const bool ok =
        WriteFile(file, content.data(), static_cast<DWORD>(content.size()), &written, nullptr) !=
        FALSE;
    CloseHandle(file);
    if (!ok) {
        COLD_ERROR(kScope, "config write failed");
    }
    return ok;
}

void Document::set(const std::string& section, const std::string& key, const std::string& value) {
    sections_[section][key] = value;
}

void Document::setBool(const std::string& section, const std::string& key, bool value) {
    set(section, key, value ? "true" : "false");
}

void Document::setNumber(const std::string& section, const std::string& key, float value,
                         int decimals) {
    char buffer[48];
    std::snprintf(buffer, sizeof(buffer), "%.*f", decimals < 0 ? 0 : decimals,
                  static_cast<double>(value));
    set(section, key, buffer);
}

void Document::setInt(const std::string& section, const std::string& key, int value) {
    set(section, key, std::to_string(value));
}

std::string Document::get(const std::string& section, const std::string& key,
                          const std::string& fallback) const {
    auto sectionEntry = sections_.find(section);
    if (sectionEntry == sections_.end()) {
        return fallback;
    }
    auto keyEntry = sectionEntry->second.find(key);
    return keyEntry == sectionEntry->second.end() ? fallback : keyEntry->second;
}

bool Document::getBool(const std::string& section, const std::string& key, bool fallback) const {
    const std::string value = get(section, key, fallback ? "true" : "false");
    return value == "true" || value == "1" || value == "yes";
}

float Document::getNumber(const std::string& section, const std::string& key,
                          float fallback) const {
    const std::string value = get(section, key, {});
    if (value.empty()) {
        return fallback;
    }
    return std::strtof(value.c_str(), nullptr);
}

int Document::getInt(const std::string& section, const std::string& key, int fallback) const {
    const std::string value = get(section, key, {});
    if (value.empty()) {
        return fallback;
    }
    return static_cast<int>(std::strtol(value.c_str(), nullptr, 10));
}

void Document::clearSection(const std::string& section) { sections_.erase(section); }

bool Document::hasSection(const std::string& section) const {
    return sections_.find(section) != sections_.end();
}

std::vector<std::string> Document::keys(const std::string& section) const {
    std::vector<std::string> names;
    const auto found = sections_.find(section);
    if (found == sections_.end()) {
        return names;
    }
    names.reserve(found->second.size());
    for (const auto& [key, value] : found->second) {
        names.push_back(key);
    }
    return names;
}

}  // namespace cold::config
