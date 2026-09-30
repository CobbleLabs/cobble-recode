#include "script/ScriptManager.hpp"

#include <windows.h>

#include <algorithm>

#include "core/Log.hpp"
#include "modules/ModuleManager.hpp"
#include "render/Renderer.hpp"
#include "script/Api.hpp"
#include "script/HudElement.hpp"
#include "script/ScriptModule.hpp"
#include "ui/Client.hpp"
#include "ui/ClickGui.hpp"
#include "ui/Hud.hpp"
#include "ui/Theme.hpp"

namespace cold::script {
namespace {

constexpr std::string_view kScope = "scripts";
constexpr const char* kEnabledKey = "enabled";

std::string narrow(const std::wstring& value) {
    if (value.empty()) {
        return {};
    }
    const int size = WideCharToMultiByte(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()),
                                         nullptr, 0, nullptr, nullptr);
    std::string out(static_cast<std::size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()), out.data(), size,
                        nullptr, nullptr);
    return out;
}

std::wstring widen(const std::string& value) {
    if (value.empty()) {
        return {};
    }
    const int size = MultiByteToWideChar(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()),
                                         nullptr, 0);
    std::wstring out(static_cast<std::size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()), out.data(),
                        size);
    return out;
}

Color noticeColour(Notice kind) {
    const ui::Palette& palette = ui::theme();
    switch (kind) {
        case Notice::Good: return palette.positive;
        case Notice::Warn: return palette.warning;
        case Notice::Bad: return palette.negative;
        case Notice::Info: break;
    }
    return palette.accent;
}

}  // namespace

bool readTextFile(const std::wstring& path, std::string& out) {
    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                              nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        return false;
    }
    LARGE_INTEGER size;
    if (!GetFileSizeEx(file, &size) || size.QuadPart < 0 || size.QuadPart > (8 << 20)) {
        CloseHandle(file);
        return false;
    }
    out.assign(static_cast<std::size_t>(size.QuadPart), '\0');
    DWORD read = 0;
    const bool ok =
        out.empty() ||
        ReadFile(file, out.data(), static_cast<DWORD>(out.size()), &read, nullptr) != FALSE;
    CloseHandle(file);
    if (!ok) {
        return false;
    }
    out.resize(read);
    /* A file saved from a Windows editor often starts with a byte order mark,
     * which Lua reads as an unexpected symbol on line one. */
    if (out.size() >= 3 && static_cast<unsigned char>(out[0]) == 0xEF &&
        static_cast<unsigned char>(out[1]) == 0xBB && static_cast<unsigned char>(out[2]) == 0xBF) {
        out.erase(0, 3);
    }
    return true;
}

std::wstring scriptPath(const std::string& file) {
    return manager().directory() + L"\\" + widen(file);
}

void notify(const std::string& text, Notice kind, float seconds) {
    if (!ui::client().hasHud()) {
        return;
    }
    ui::client().hud().notify(text, seconds, noticeColour(kind));
}

ScriptManager& ScriptManager::instance() {
    static ScriptManager manager;
    return manager;
}

void ScriptManager::initialise(const std::wstring& payloadDirectory) {
    if (initialised_) {
        return;
    }
    directory_ = payloadDirectory + L"\\scripts";
    storePath_ = payloadDirectory + L"\\cobble.scripts.cfg";
    CreateDirectoryW(directory_.c_str(), nullptr);
    store_.load(storePath_);
    initialised_ = true;

    scan();
    for (const std::unique_ptr<Script>& script : scripts_) {
        if (enabled(script->file())) {
            startScript(*script);
        }
    }
    COLD_INFO(kScope, "%zu script%s found, %d loaded", scripts_.size(),
              scripts_.size() == 1 ? "" : "s", loadedCount());
}

void ScriptManager::shutdown() {
    if (!initialised_) {
        return;
    }
    for (const std::unique_ptr<Script>& script : scripts_) {
        if (script->state() != nullptr) {
            stopScript(*script);
        }
    }
    saveStore();
    scripts_.clear();
    initialised_ = false;
}

void ScriptManager::scan() {
    WIN32_FIND_DATAW entry;
    const std::wstring pattern = directory_ + L"\\*.lua";
    std::vector<std::string> found;

    HANDLE search = FindFirstFileW(pattern.c_str(), &entry);
    if (search != INVALID_HANDLE_VALUE) {
        do {
            if ((entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                continue;
            }
            /* A leading underscore means a file that exists to be included by
             * another one. Loading it as a script of its own would put a
             * library in the list and run it twice. */
            if (entry.cFileName[0] == L'_') {
                continue;
            }
            found.push_back(narrow(entry.cFileName));
        } while (FindNextFileW(search, &entry) != 0);
        FindClose(search);
    }
    std::sort(found.begin(), found.end());

    /* Anything that left the folder goes with it, callbacks and modules
     * included: a script file that is not there cannot be reloaded, and leaving
     * its modules in the menu would be a list of things that answer to nobody. */
    for (std::size_t index = scripts_.size(); index > 0; --index) {
        Script& script = *scripts_[index - 1];
        if (std::find(found.begin(), found.end(), script.file()) != found.end()) {
            continue;
        }
        COLD_INFO(kScope, "%s went away", script.file().c_str());
        stopScript(script);
        scripts_.erase(scripts_.begin() + static_cast<std::ptrdiff_t>(index - 1));
    }

    for (const std::string& file : found) {
        if (find(file) != nullptr) {
            continue;
        }
        scripts_.push_back(std::make_unique<Script>(file, directory_ + L"\\" + widen(file)));
    }
    std::sort(scripts_.begin(), scripts_.end(),
              [](const std::unique_ptr<Script>& a, const std::unique_ptr<Script>& b) {
                  return a->file() < b->file();
              });
}

Script* ScriptManager::find(std::string_view file) const {
    for (const std::unique_ptr<Script>& script : scripts_) {
        if (script->file() == file || script->name() == file) {
            return script.get();
        }
    }
    return nullptr;
}

bool ScriptManager::enabled(const std::string& file) const {
    /* A script that appears in the folder runs: dropping one in is the whole
     * install step, and a list of scripts that all need switching on first is
     * an extra ceremony for nothing. */
    return store_.getBool(file, kEnabledKey, true);
}

int ScriptManager::loadedCount() const {
    int count = 0;
    for (const std::unique_ptr<Script>& script : scripts_) {
        if (script->state() != nullptr && !script->stopped()) {
            ++count;
        }
    }
    return count;
}

bool ScriptManager::startScript(Script& script) {
    if (script.state() != nullptr) {
        return !script.stopped();
    }
    loading_ = true;
    const bool ok = script.load();
    menuDirty_ = true;
    if (!ok) {
        loading_ = false;
        /* A chunk that failed halfway may already have registered modules, and
         * those have to go back: they would answer to a state nobody is
         * dispatching into. */
        stopScript(script);
        return false;
    }
    COLD_INFO(kScope, "loaded %s (%s %s)", script.file().c_str(), script.name().c_str(),
              script.version().empty() ? "" : script.version().c_str());
    const std::vector<int> handlers = script.handlers(Event::Load);
    for (int reference : handlers) {
        const int handler = script.pushHandler();
        lua_rawgeti(script.state(), LUA_REGISTRYINDEX, reference);
        script.call(0, 0, handler);
    }
    loading_ = false;
    return true;
}

void ScriptManager::stopScript(Script& script) {
    if (script.state() == nullptr) {
        return;
    }
    script.fireUnload();

    for (std::size_t index = hudElements_.size(); index > 0; --index) {
        if (&hudElements_[index - 1]->owner() == &script) {
            removeHudElement(hudElements_[index - 1].get());
        }
    }

    const std::vector<ScriptModule*> owned = script.modules();
    for (ScriptModule* module : owned) {
        if (ui::client().hasHud()) {
            ui::client().hud().forget(module);
        }
        modules::modules().unregisterModule(module, context_);
    }
    script.close();
    menuDirty_ = true;
}

void ScriptManager::reloadScript(Script& script) {
    stopScript(script);
    if (enabled(script.file())) {
        startScript(script);
    }
}

void ScriptManager::attachModule(Script& script, std::unique_ptr<ScriptModule> module) {
    ScriptModule* raw = module.get();
    script.addModule(raw);
    modules::modules().registerModule(std::move(module));
    menuDirty_ = true;
}

void ScriptManager::attachHudElement(Script& script, std::unique_ptr<HudElement> element) {
    (void)script;
    hudElements_.push_back(std::move(element));
}

HudElement* ScriptManager::findHudElement(std::string_view name) const {
    for (const std::unique_ptr<HudElement>& element : hudElements_) {
        if (element->name() == name) {
            return element.get();
        }
    }
    return nullptr;
}

void ScriptManager::removeHudElement(HudElement* element) {
    if (draggedElement_ == element) {
        draggedElement_ = nullptr;
    }
    for (std::size_t index = 0; index < hudElements_.size(); ++index) {
        if (hudElements_[index].get() == element) {
            hudElements_.erase(hudElements_.begin() + static_cast<std::ptrdiff_t>(index));
            return;
        }
    }
}

void ScriptManager::renderHud(modules::Context& context, bool menuOpen) {
    const game::FrameState& state = *context.state;
    /* Same rule as the client's own HUD: nothing on the title screen. A script
     * element draws game state, and there is none to draw. */
    if (hudElements_.empty() || !state.inWorld) {
        return;
    }
    const float unit = state.scaledWidth > 0 ? static_cast<float>(state.windowWidth) /
                                                   static_cast<float>(state.scaledWidth)
                                             : 2.0f;
    DrawingScope drawing;
    for (const std::unique_ptr<HudElement>& element : hudElements_) {
        if (!element->visible()) {
            continue;
        }
        element->render(unit, menuOpen, draggedElement_ == element.get());
    }
}

bool ScriptManager::hudPress(float x, float y, float unit) {
    /* Topmost first: the last one declared draws over the others, so it is the
     * one the pointer is on. */
    for (std::size_t index = hudElements_.size(); index > 0; --index) {
        HudElement& element = *hudElements_[index - 1];
        if (!element.showing() || !element.bounds(unit).contains(x, y)) {
            continue;
        }
        draggedElement_ = &element;
        dragOffsetX_ = x - element.x() * unit;
        dragOffsetY_ = y - element.y() * unit;
        return true;
    }
    return false;
}

void ScriptManager::hudDrag(float x, float y, float unit) {
    if (draggedElement_ == nullptr || unit <= 0.0f) {
        return;
    }
    draggedElement_->setPosition((x - dragOffsetX_) / unit, (y - dragOffsetY_) / unit);
}

void ScriptManager::hudRelease() {
    if (draggedElement_ == nullptr) {
        return;
    }
    /* Written where it was let go, so the next session finds it there. */
    const std::string section = draggedElement_->owner().file();
    store_.setNumber(section, "hud." + draggedElement_->name() + ".x", draggedElement_->x(), 2);
    store_.setNumber(section, "hud." + draggedElement_->name() + ".y", draggedElement_->y(), 2);
    saveStore();
    draggedElement_ = nullptr;
}

void ScriptManager::requestEnable(std::string file, bool value) {
    pending_.push_back({value ? Request::Kind::Enable : Request::Kind::Disable, std::move(file)});
}

void ScriptManager::requestReload(std::string file) {
    pending_.push_back({file.empty() ? Request::Kind::ReloadAll : Request::Kind::Reload,
                        std::move(file)});
}

void ScriptManager::requestRescan() {
    pending_.push_back({Request::Kind::Rescan, {}});
}

void ScriptManager::applyPending() {
    if (pending_.empty()) {
        return;
    }
    /* Serviced from a copy: loading a script runs its chunk, and a chunk is
     * allowed to ask for another reload. Those land on the next frame rather
     * than extending the loop that is running. */
    const std::vector<Request> requests = std::move(pending_);
    pending_.clear();

    for (const Request& request : requests) {
        switch (request.kind) {
            case Request::Kind::Rescan:
                scan();
                for (const std::unique_ptr<Script>& script : scripts_) {
                    if (script->state() == nullptr && enabled(script->file())) {
                        startScript(*script);
                    }
                }
                break;

            case Request::Kind::ReloadAll:
                scan();
                for (const std::unique_ptr<Script>& script : scripts_) {
                    reloadScript(*script);
                }
                notify("scripts reloaded", Notice::Good);
                break;

            case Request::Kind::Reload:
                if (Script* script = find(request.file)) {
                    reloadScript(*script);
                    notify(script->file() + " reloaded", Notice::Good);
                }
                break;

            case Request::Kind::Enable:
            case Request::Kind::Disable: {
                Script* script = find(request.file);
                if (script == nullptr) {
                    break;
                }
                const bool value = request.kind == Request::Kind::Enable;
                store_.setBool(script->file(), kEnabledKey, value);
                saveStore();
                if (value) {
                    startScript(*script);
                } else {
                    stopScript(*script);
                }
                break;
            }
        }
    }
}

void ScriptManager::rebuildMenu() {
    if (!menuDirty_) {
        return;
    }
    menuDirty_ = false;
    /* The menu caches one card per module and the cards hold pointers into the
     * module list, so a list that changed is a menu that has to be built
     * again. */
    ui::client().gui().reset();
}

bool ScriptManager::anyHandler(Event event) const {
    for (const std::unique_ptr<Script>& script : scripts_) {
        if (script->running() && !script->handlers(event).empty()) {
            return true;
        }
    }
    return false;
}

void ScriptManager::dispatch(Event event, const std::function<int(lua_State*)>& push) {
    for (const std::unique_ptr<Script>& script : scripts_) {
        if (!script->running()) {
            continue;
        }
        /* Copied: a handler may subscribe or unsubscribe while it runs. */
        const std::vector<int> handlers = script->handlers(event);
        if (handlers.empty()) {
            continue;
        }
        lua_State* L = script->state();
        for (int reference : handlers) {
            if (!script->running()) {
                break;
            }
            const int handler = script->pushHandler();
            lua_rawgeti(L, LUA_REGISTRYINDEX, reference);
            const int arguments = push ? push(L) : 0;
            script->call(arguments, 0, handler);
        }
    }
}

void ScriptManager::beginFrame(modules::Context& context) {
    context_ = &context;
    applyPending();
    rebuildMenu();
    trackWorld();
    dispatch(Event::Frame, [&context](lua_State* L) {
        lua_pushnumber(L, static_cast<lua_Number>(context.deltaTime));
        return 1;
    });
}

void ScriptManager::endFrame() {
    context_ = nullptr;
    drawing_ = 0;
}

void ScriptManager::trackWorld() {
    if (context_ == nullptr || context_->state == nullptr) {
        return;
    }
    const game::FrameState& state = *context_->state;
    if (state.inWorld != wasInWorld_) {
        wasInWorld_ = state.inWorld;
        dispatch(state.inWorld ? Event::WorldLoad : Event::WorldLeave);
        if (!state.inWorld) {
            seenEntities_.clear();
            seenEffects_.clear();
            lastHealth_ = -1.0f;
            lastSlot_ = -1;
        }
    }
    if (state.screenOpen != wasScreenOpen_) {
        wasScreenOpen_ = state.screenOpen;
        dispatch(state.screenOpen ? Event::ScreenOpen : Event::ScreenClose);
    }

    trackEntities(state);
    trackPlayer(state);
    trackModules();
    if (context_->tickEdge) {
        trackEffects();
    }
}

void ScriptManager::trackEntities(const game::FrameState& state) {
    const bool wantSpawn = anyHandler(Event::EntitySpawn);
    const bool wantRemove = anyHandler(Event::EntityRemove);
    const bool wantDeath = anyHandler(Event::EntityDeath);
    if (!state.inWorld || (!wantSpawn && !wantRemove && !wantDeath)) {
        seenEntities_.clear();
        return;
    }

    entityScratch_.clear();
    entityScratch_.reserve(state.entities.size());
    for (const game::EntityView& entity : state.entities) {
        entityScratch_.push_back(SeenEntity{entity.id, entity.health, entity.living});
    }
    std::sort(entityScratch_.begin(), entityScratch_.end(),
              [](const SeenEntity& a, const SeenEntity& b) { return a.id < b.id; });

    const auto lookup = [](const std::vector<SeenEntity>& list, int id) -> const SeenEntity* {
        const auto found = std::lower_bound(
            list.begin(), list.end(), id,
            [](const SeenEntity& entry, int wanted) { return entry.id < wanted; });
        return found != list.end() && found->id == id ? &*found : nullptr;
    };

    for (const SeenEntity& entry : entityScratch_) {
        const SeenEntity* previous = lookup(seenEntities_, entry.id);
        if (previous == nullptr) {
            if (wantSpawn) {
                const int id = entry.id;
                dispatch(Event::EntitySpawn, [id](lua_State* L) {
                    pushEntity(L, id);
                    return 1;
                });
            }
        } else if (wantDeath && entry.living && previous->health > 0.0f && entry.health <= 0.0f) {
            const int id = entry.id;
            dispatch(Event::EntityDeath, [id](lua_State* L) {
                pushEntity(L, id);
                return 1;
            });
        }
    }
    for (const SeenEntity& entry : seenEntities_) {
        if (lookup(entityScratch_, entry.id) != nullptr) {
            continue;
        }
        const int id = entry.id;
        if (wantRemove) {
            dispatch(Event::EntityRemove, [id](lua_State* L) {
                lua_pushinteger(L, id);
                return 1;
            });
        }
        /* `captureEntities` drops anything that fails isAlive, so for a living
         * entity leaving the capture set is usually how a death looks from
         * here. See game/DeathWatch.hpp for the same reasoning. */
        if (wantDeath && entry.living && entry.health > 0.0f) {
            dispatch(Event::EntityDeath, [id](lua_State* L) {
                lua_pushinteger(L, id);
                return 1;
            });
        }
    }
    seenEntities_.swap(entityScratch_);
}

void ScriptManager::trackPlayer(const game::FrameState& state) {
    if (!state.player.valid) {
        lastHealth_ = -1.0f;
        lastSlot_ = -1;
        return;
    }

    const float health = state.player.health;
    if (lastHealth_ >= 0.0f && health != lastHealth_) {
        const float previous = lastHealth_;
        if (anyHandler(Event::HealthChange)) {
            dispatch(Event::HealthChange, [health, previous](lua_State* L) {
                lua_pushnumber(L, health);
                lua_pushnumber(L, previous);
                return 2;
            });
        }
        if (health <= 0.0f && previous > 0.0f) {
            dispatch(Event::Death);
        } else if (previous <= 0.0f && health > 0.0f) {
            dispatch(Event::Respawn);
        }
    }
    lastHealth_ = health;

    if (anyHandler(Event::SlotChange)) {
        const int slot = game::Game::instance().selectedSlot(context_->env, state.player.handle);
        if (lastSlot_ >= 0 && slot != lastSlot_) {
            const int previous = lastSlot_;
            dispatch(Event::SlotChange, [slot, previous](lua_State* L) {
                lua_pushinteger(L, slot);
                lua_pushinteger(L, previous);
                return 2;
            });
        }
        lastSlot_ = slot;
    } else {
        lastSlot_ = -1;
    }
}

void ScriptManager::trackModules() {
    if (!anyHandler(Event::ModuleToggled)) {
        toggledOn_.clear();
        return;
    }
    std::vector<const modules::Module*> enabled;
    for (const std::unique_ptr<modules::Module>& module : modules::modules().all()) {
        if (!module->enabled()) {
            continue;
        }
        enabled.push_back(module.get());
        if (std::find(toggledOn_.begin(), toggledOn_.end(), module.get()) == toggledOn_.end()) {
            const std::string name = module->name();
            dispatch(Event::ModuleToggled, [name](lua_State* L) {
                lua_pushlstring(L, name.c_str(), name.size());
                lua_pushboolean(L, 1);
                return 2;
            });
        }
    }
    for (const modules::Module* module : toggledOn_) {
        if (std::find(enabled.begin(), enabled.end(), module) != enabled.end()) {
            continue;
        }
        /* The module may be gone rather than merely off, so the name is taken
         * from the live list and never from the pointer. */
        const modules::Module* live = nullptr;
        for (const std::unique_ptr<modules::Module>& entry : modules::modules().all()) {
            if (entry.get() == module) {
                live = entry.get();
                break;
            }
        }
        if (live == nullptr) {
            continue;
        }
        const std::string name = live->name();
        dispatch(Event::ModuleToggled, [name](lua_State* L) {
            lua_pushlstring(L, name.c_str(), name.size());
            lua_pushboolean(L, 0);
            return 2;
        });
    }
    toggledOn_.swap(enabled);
}

void ScriptManager::trackEffects() {
    const bool wantAdded = anyHandler(Event::EffectAdded);
    const bool wantRemoved = anyHandler(Event::EffectRemoved);
    if (!wantAdded && !wantRemoved || context_ == nullptr || context_->state == nullptr ||
        !context_->state->player.valid) {
        seenEffects_.clear();
        return;
    }

    const std::vector<game::Game::EffectView> effects =
        game::Game::instance().statusEffects(context_->env, context_->state->player.handle);

    for (const game::Game::EffectView& effect : effects) {
        const auto found = std::find_if(
            seenEffects_.begin(), seenEffects_.end(),
            [&](const SeenEffect& entry) { return entry.id == effect.id; });
        if (found != seenEffects_.end() && found->amplifier == effect.amplifier) {
            continue;
        }
        if (wantAdded) {
            const std::string id = effect.id;
            const int amplifier = effect.amplifier;
            const int duration = effect.duration;
            dispatch(Event::EffectAdded, [id, amplifier, duration](lua_State* L) {
                lua_pushlstring(L, id.c_str(), id.size());
                lua_pushinteger(L, amplifier + 1);
                lua_pushinteger(L, duration);
                return 3;
            });
        }
    }
    for (const SeenEffect& entry : seenEffects_) {
        const auto found = std::find_if(
            effects.begin(), effects.end(),
            [&](const game::Game::EffectView& effect) { return effect.id == entry.id; });
        if (found != effects.end()) {
            continue;
        }
        if (wantRemoved) {
            const std::string id = entry.id;
            dispatch(Event::EffectRemoved, [id](lua_State* L) {
                lua_pushlstring(L, id.c_str(), id.size());
                return 1;
            });
        }
    }

    seenEffects_.clear();
    seenEffects_.reserve(effects.size());
    for (const game::Game::EffectView& effect : effects) {
        seenEffects_.push_back(SeenEffect{effect.id, effect.amplifier});
    }
}

void ScriptManager::tick() {
    dispatch(Event::Tick);
}

void ScriptManager::render() {
    DrawingScope drawing;
    dispatch(Event::Render3d);
    dispatch(Event::Render);
}

void ScriptManager::key(int code, bool down, int modifiers) {
    if (!anyHandler(Event::Key)) {
        return;
    }
    dispatch(Event::Key, [code, down, modifiers](lua_State* L) {
        lua_pushinteger(L, code);
        lua_pushboolean(L, down ? 1 : 0);
        lua_pushinteger(L, modifiers);
        return 3;
    });
}

void ScriptManager::charTyped(unsigned int codepoint) {
    if (!anyHandler(Event::CharTyped)) {
        return;
    }
    dispatch(Event::CharTyped, [codepoint](lua_State* L) {
        lua_pushinteger(L, static_cast<lua_Integer>(codepoint));
        /* The character itself as well as its code point: a script watching
         * what was typed wants the letter, not the number. */
        char utf8[5] = {};
        int length = 0;
        if (codepoint < 0x80) {
            utf8[length++] = static_cast<char>(codepoint);
        } else if (codepoint < 0x800) {
            utf8[length++] = static_cast<char>(0xC0 | (codepoint >> 6));
            utf8[length++] = static_cast<char>(0x80 | (codepoint & 0x3F));
        } else if (codepoint < 0x10000) {
            utf8[length++] = static_cast<char>(0xE0 | (codepoint >> 12));
            utf8[length++] = static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F));
            utf8[length++] = static_cast<char>(0x80 | (codepoint & 0x3F));
        }
        lua_pushlstring(L, utf8, static_cast<std::size_t>(length));
        return 2;
    });
}

void ScriptManager::mouseMove(double x, double y) {
    if (!anyHandler(Event::MouseMove)) {
        return;
    }
    dispatch(Event::MouseMove, [x, y](lua_State* L) {
        lua_pushnumber(L, x);
        lua_pushnumber(L, y);
        return 2;
    });
}

void ScriptManager::mouse(int button, bool down, double x, double y) {
    if (!anyHandler(Event::Mouse)) {
        return;
    }
    dispatch(Event::Mouse, [button, down, x, y](lua_State* L) {
        lua_pushinteger(L, button);
        lua_pushboolean(L, down ? 1 : 0);
        lua_pushnumber(L, x);
        lua_pushnumber(L, y);
        return 4;
    });
}

void ScriptManager::scroll(double x, double y) {
    if (!anyHandler(Event::Scroll)) {
        return;
    }
    dispatch(Event::Scroll, [x, y](lua_State* L) {
        lua_pushnumber(L, x);
        lua_pushnumber(L, y);
        return 2;
    });
}

void ScriptManager::noteClipPop() {
    if (clips_ <= 0) {
        return;
    }
    --clips_;
    render::renderer().popClip();
}

void ScriptManager::unwindClips(int target) {
    while (clips_ > target) {
        noteClipPop();
    }
}

void ScriptManager::saveStore() {
    if (!initialised_) {
        return;
    }
    store_.save(storePath_);
}

}  // namespace cold::script
