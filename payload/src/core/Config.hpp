#pragma once

#include <map>
#include <string>
#include <vector>

namespace cold::config {

/* Flat sectioned key/value store, written next to the payload as
 * `cobble.cfg`. The schema is one section per module and one line per
 * setting, which stays hand-editable and needs no parser beyond a line split.
 *
 *   [KillAura]
 *   enabled = true
 *   Range = 3.00
 */
class Document {
public:
    bool load(const std::wstring& path);
    bool save(const std::wstring& path) const;

    void set(const std::string& section, const std::string& key, const std::string& value);
    void setBool(const std::string& section, const std::string& key, bool value);
    void setNumber(const std::string& section, const std::string& key, float value, int decimals);
    void setInt(const std::string& section, const std::string& key, int value);

    std::string get(const std::string& section, const std::string& key,
                    const std::string& fallback) const;
    bool getBool(const std::string& section, const std::string& key, bool fallback) const;
    float getNumber(const std::string& section, const std::string& key, float fallback) const;
    int getInt(const std::string& section, const std::string& key, int fallback) const;

    bool hasSection(const std::string& section) const;
    /* Drops a section outright. Writing a module back over the top of what
     * was loaded leaves any key it no longer has behind forever, so the file
     * grows a settings graveyard - and a setting later re-added under an old
     * name would silently inherit a value nobody set. */
    void clearSection(const std::string& section);
    /* Every key in a section, in the order the file holds them. Used where a
     * section is a set rather than a fixed schema - the friends list. */
    std::vector<std::string> keys(const std::string& section) const;
    void clear() { sections_.clear(); }

private:
    /* std::map keeps sections and keys ordered, so a saved file has a stable
     * diff instead of shuffling on every write. */
    std::map<std::string, std::map<std::string, std::string>> sections_;
};

}  // namespace cold::config
