#pragma once

#include <memory>
#include <string>
#include <vector>

#include "core/Config.hpp"
#include "modules/Module.hpp"

namespace cold::modules {

class ModuleManager {
public:
    static ModuleManager& instance();

    void registerModules();
    void loadConfig(const std::wstring& path);
    void saveConfig(const std::wstring& path) const;

    /* The live document behind load/save, for UI layout sections that are not
     * module settings. Reads land after loadConfig, writes before saveConfig. */
    config::Document& config() { return config_; }
    const config::Document& config() const { return config_; }

    /* Modules that arrive after startup, which is every module a Lua script
     * declares. The config the client already read is applied as they land, so
     * a script module keeps its settings across a reload exactly as a built-in
     * keeps them across a session. Both calls are only safe outside a tick or a
     * render pass - the list is walked by both - which is why the script
     * manager services its queue before either runs. */
    void registerModule(std::unique_ptr<Module> module);
    void unregisterModule(Module* module, Context* context);

    void tick(Context& context);
    void render(Context& context);
    void advanceAnimations(float deltaTime);

    /* Returns true when the key was bound to something, so the hook can decide
     * whether to swallow it. */
    bool handleKey(int key, Context& context);

    const std::vector<std::unique_ptr<Module>>& all() const { return modules_; }
    std::vector<Module*> byCategory(Category category) const;
    Module* find(std::string_view name) const;

    template <typename T>
    T* get() const {
        for (const std::unique_ptr<Module>& module : modules_) {
            if (auto* typed = dynamic_cast<T*>(module.get())) {
                return typed;
            }
        }
        return nullptr;
    }

private:
    ModuleManager() = default;

    void add(std::unique_ptr<Module> module);
    void applyConfig(Module& module);
    static void captureConfig(config::Document& document, const Module& module);
    void sortModules();

    std::vector<std::unique_ptr<Module>> modules_;
    config::Document config_;
};

inline ModuleManager& modules() { return ModuleManager::instance(); }

}  // namespace cold::modules
