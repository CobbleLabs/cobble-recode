#include "modules/ModuleManager.hpp"

#include <algorithm>

#include "core/Log.hpp"
#include "modules/ModuleList.hpp"

namespace cold::modules {
namespace {

constexpr std::string_view kScope = "modules";

std::string colourToHex(const Color& colour) {
    char buffer[16];
    std::snprintf(buffer, sizeof(buffer), "#%08X", colour.packed());
    return buffer;
}

Color colourFromHex(const std::string& text, const Color& fallback) {
    if (text.size() != 9 || text[0] != '#') {
        return fallback;
    }
    const auto value = static_cast<std::uint32_t>(std::strtoul(text.c_str() + 1, nullptr, 16));
    return Color::rgba(value);
}

}  // namespace

ModuleManager& ModuleManager::instance() {
    static ModuleManager manager;
    return manager;
}

void ModuleManager::add(std::unique_ptr<Module> module) {
    COLD_DEBUG(kScope, "registered %s (%s)", module->name().c_str(),
               categoryName(module->category()));
    modules_.push_back(std::move(module));
}

void ModuleManager::sortModules() {
    std::stable_sort(modules_.begin(), modules_.end(),
                     [](const std::unique_ptr<Module>& a, const std::unique_ptr<Module>& b) {
                         if (a->category() != b->category()) {
                             return static_cast<int>(a->category()) < static_cast<int>(b->category());
                         }
                         return a->name() < b->name();
                     });
}

void ModuleManager::registerModules() {
    if (!modules_.empty()) {
        return;
    }
    for (std::unique_ptr<Module>& module : createModules()) {
        add(std::move(module));
    }
    sortModules();
    COLD_INFO(kScope, "%zu modules registered", modules_.size());
}

void ModuleManager::registerModule(std::unique_ptr<Module> module) {
    if (module == nullptr || find(module->name()) != nullptr) {
        return;
    }
    applyConfig(*module);
    add(std::move(module));
    sortModules();
}

void ModuleManager::unregisterModule(Module* module, Context* context) {
    for (std::size_t index = 0; index < modules_.size(); ++index) {
        if (modules_[index].get() != module) {
            continue;
        }
        /* Remembered before it goes: reloading a script tears its modules down
         * and builds them again, and without this the reload would hand back a
         * module switched off with default settings - losing whatever was
         * changed in the menu since the client started. */
        captureConfig(config_, *module);
        module->setEnabled(false, context);
        COLD_DEBUG(kScope, "unregistered %s", module->name().c_str());
        modules_.erase(modules_.begin() + static_cast<std::ptrdiff_t>(index));
        return;
    }
}

std::vector<Module*> ModuleManager::byCategory(Category category) const {
    std::vector<Module*> result;
    for (const std::unique_ptr<Module>& module : modules_) {
        if (module->category() == category) {
            result.push_back(module.get());
        }
    }
    return result;
}

Module* ModuleManager::find(std::string_view name) const {
    for (const std::unique_ptr<Module>& module : modules_) {
        if (module->name() == name) {
            return module.get();
        }
    }
    return nullptr;
}

void ModuleManager::applyConfig(Module& module) {
    const std::string& section = module.name();
    if (!config_.hasSection(section)) {
        return;
    }
    module.setKey(config_.getInt(section, "key", module.key()));
    /* Assign, do not just enable: a module whose constructor turns itself on
     * could otherwise never be switched off persistently. The null context
     * is deliberate — the render thread is not up yet and onEnable may need
     * JNI, so the first tick is what actually starts the module. */
    module.setEnabled(config_.getBool(section, "enabled", module.enabled()), nullptr);
    for (const std::unique_ptr<Setting>& setting : module.settings()) {
        const std::string& key = setting->name();
        switch (setting->type()) {
            case Setting::Type::Boolean:
                setting->setBoolean(config_.getBool(section, key, setting->booleanValue()));
                break;
            case Setting::Type::Number:
                setting->setNumber(config_.getNumber(section, key, setting->number()));
                break;
            case Setting::Type::Choice: {
                const std::string value = config_.get(section, key, setting->choiceName());
                const auto& choices = setting->choices();
                for (std::size_t index = 0; index < choices.size(); ++index) {
                    if (choices[index] == value) {
                        setting->setChoice(static_cast<int>(index));
                        break;
                    }
                }
                break;
            }
            case Setting::Type::Key:
                setting->setKey(config_.getInt(section, key, setting->key()));
                break;
            case Setting::Type::Colour:
                setting->setColour(
                    colourFromHex(config_.get(section, key, colourToHex(setting->colour())),
                                  setting->colour()));
                break;
        }
    }
}

void ModuleManager::loadConfig(const std::wstring& path) {
    if (!config_.load(path)) {
        return;
    }
    for (const std::unique_ptr<Module>& module : modules_) {
        applyConfig(*module);
    }
    COLD_INFO(kScope, "configuration applied");
}

void ModuleManager::captureConfig(config::Document& document, const Module& module) {
    const std::string& section = module.name();
    /* Rebuilt, not merged. A module that has lost a setting since the file was
     * written must lose the line as well. */
    document.clearSection(section);
    document.setBool(section, "enabled", module.enabled());
    document.setInt(section, "key", module.key());
    for (const std::unique_ptr<Setting>& setting : module.settings()) {
        const std::string& key = setting->name();
        switch (setting->type()) {
            case Setting::Type::Boolean:
                document.setBool(section, key, setting->booleanValue());
                break;
            case Setting::Type::Number:
                document.setNumber(section, key, setting->number(), setting->decimals());
                break;
            case Setting::Type::Choice:
                document.set(section, key, setting->choiceName());
                break;
            case Setting::Type::Key:
                document.setInt(section, key, setting->key());
                break;
            case Setting::Type::Colour:
                document.set(section, key, colourToHex(setting->colour()));
                break;
        }
    }
}

void ModuleManager::saveConfig(const std::wstring& path) const {
    /* Starts from what was read rather than from nothing: a script that is
     * switched off right now still has a section in there, and rebuilding the
     * file from the live list alone would throw its settings away. */
    config::Document document = config_;
    for (const std::unique_ptr<Module>& module : modules_) {
        captureConfig(document, *module);
    }
    document.save(path);
}

void ModuleManager::tick(Context& context) {
    for (const std::unique_ptr<Module>& module : modules_) {
        if (module->enabled()) {
            module->onTick(context);
        }
    }
}

void ModuleManager::render(Context& context) {
    for (const std::unique_ptr<Module>& module : modules_) {
        if (module->enabled() || module->animation() > 0.001f) {
            module->onRender(context);
        }
    }
}

void ModuleManager::advanceAnimations(float deltaTime) {
    for (const std::unique_ptr<Module>& module : modules_) {
        module->advanceAnimation(deltaTime);
    }
}

bool ModuleManager::handleKey(int key, Context& context) {
    if (key == 0) {
        return false;
    }
    bool consumed = false;
    for (const std::unique_ptr<Module>& module : modules_) {
        if (module->key() == key) {
            module->toggle(&context);
            consumed = true;
        }
    }
    return consumed;
}

}  // namespace cold::modules
