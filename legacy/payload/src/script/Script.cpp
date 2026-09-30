#include "script/Script.hpp"

#include <cstdlib>
#include <cstring>

#include "core/Log.hpp"
#include "core/Time.hpp"
#include "script/Api.hpp"
#include "script/ScriptManager.hpp"
#include "script/ScriptModule.hpp"

namespace cold::script {
namespace {

constexpr std::string_view kScope = "script";

/* A callback that overruns this long is a runaway loop, not slow work:
 * everything here runs on the render thread, so the alternative to breaking out
 * of it is a frozen game. */
constexpr double kCallBudgetSeconds = 0.25;
constexpr int kHookInstructions = 200000;
/* Per-state ceiling. Generous for anything a script legitimately holds, small
 * enough that a leak cannot starve the JVM it is living inside. */
constexpr std::size_t kMemoryCeiling = 64u << 20;
/* Errors are logged in full until this many and only counted after: a callback
 * that fails every frame would otherwise write a log the size of the world. */
constexpr long long kLoggedErrors = 5;
constexpr long long kErrorLimit = 40;

const char kScriptKey = 0;

const char* const kEventNames[kEventCount] = {
    "load",         "unload",     "frame",       "tick",        "render",
    "render3d",     "key",        "chartyped",   "mouse",       "mousemove",
    "scroll",       "worldload",  "worldleave",  "screenopen",  "screenclose",
    "entityspawn",  "entityremove", "entitydeath", "healthchange", "death",
    "respawn",      "slotchange", "moduletoggled", "effectadded", "effectremoved",
};

/* Underscores are dropped before a name is matched, so `entity_death` and
 * `entitydeath` are the same event and a script ported from a client that
 * spells them the other way needs no rewriting. */
std::string normalise(std::string_view name) {
    std::string out;
    out.reserve(name.size());
    for (char character : name) {
        if (character == '_' || character == '-' || character == ' ') {
            continue;
        }
        out.push_back(static_cast<char>(character >= 'A' && character <= 'Z' ? character + 32
                                                                            : character));
    }
    return out;
}

std::string stem(const std::string& file) {
    const std::size_t dot = file.find_last_of('.');
    return dot == std::string::npos ? file : file.substr(0, dot);
}

int traceback(lua_State* L) {
    const char* message = lua_tostring(L, 1);
    if (message == nullptr) {
        if (luaL_callmeta(L, 1, "__tostring") != 0 && lua_type(L, -1) == LUA_TSTRING) {
            return 1;
        }
        message = lua_pushfstring(L, "(error object is a %s value)", luaL_typename(L, 1));
    }
    luaL_traceback(L, L, message, 1);
    return 1;
}

int onPanic(lua_State* L) {
    const char* message = lua_tostring(L, -1);
    COLD_ERROR(kScope, "unprotected error: %s", message != nullptr ? message : "?");
    return 0;
}

void onInstructionHook(lua_State* L, lua_Debug*) {
    Script* script = Script::fromState(L);
    if (script == nullptr || script->deadline() <= 0.0 || nowSeconds() <= script->deadline()) {
        return;
    }
    luaL_error(L, "callback ran for longer than %d ms and was stopped",
               static_cast<int>(kCallBudgetSeconds * 1000.0));
}

void* allocate(void* userData, void* pointer, std::size_t oldSize, std::size_t newSize) {
    auto* used = static_cast<std::size_t*>(userData);
    const std::size_t previous = pointer != nullptr ? oldSize : 0;
    if (newSize == 0) {
        std::free(pointer);
        *used -= previous;
        return nullptr;
    }
    if (newSize > previous && *used + (newSize - previous) > kMemoryCeiling) {
        return nullptr;
    }
    void* block = std::realloc(pointer, newSize);
    if (block == nullptr) {
        return nullptr;
    }
    *used = *used - previous + newSize;
    return block;
}

}  // namespace

const char* eventName(Event event) {
    const auto index = static_cast<std::size_t>(event);
    return index < kEventCount ? kEventNames[index] : "?";
}

bool eventFromName(std::string_view name, Event& out) {
    const std::string wanted = normalise(name);
    for (std::size_t index = 0; index < kEventCount; ++index) {
        if (wanted == kEventNames[index]) {
            out = static_cast<Event>(index);
            return true;
        }
    }
    /* The names the other clients use for the same moments, so a ported script
     * does not have to be rewritten to say the same thing. */
    struct Alias {
        const char* name;
        Event event;
    };
    static const Alias kAliases[] = {
        {"update", Event::Tick},
        {"gametick", Event::Tick},
        {"draw", Event::Render},
        {"render2d", Event::Render},
        {"hudrender", Event::Render},
        {"keypress", Event::Key},
        {"keydown", Event::Key},
        {"join", Event::WorldLoad},
        {"connect", Event::WorldLoad},
        {"worldchange", Event::WorldLoad},
        {"leave", Event::WorldLeave},
        {"disconnect", Event::WorldLeave},
        {"closescreen", Event::ScreenClose},
        {"openscreen", Event::ScreenOpen},
        {"moduletoggle", Event::ModuleToggled},
        {"hurt", Event::HealthChange},
        {"heldslot", Event::SlotChange},
    };
    for (const Alias& alias : kAliases) {
        if (wanted == alias.name) {
            out = alias.event;
            return true;
        }
    }
    return false;
}

Script::Script(std::string file, std::wstring path)
    : file_(std::move(file)), path_(std::move(path)) {
    name_ = stem(file_);
}

Script::~Script() {
    close();
}

Script* Script::fromState(lua_State* L) {
    if (lua_rawgetp(L, LUA_REGISTRYINDEX, &kScriptKey) != LUA_TLIGHTUSERDATA) {
        lua_pop(L, 1);
        return nullptr;
    }
    auto* script = static_cast<Script*>(lua_touserdata(L, -1));
    lua_pop(L, 1);
    return script;
}

void Script::openLibraries() {
    static const luaL_Reg kLibraries[] = {
        {LUA_GNAME, luaopen_base},          {LUA_TABLIBNAME, luaopen_table},
        {LUA_STRLIBNAME, luaopen_string},   {LUA_MATHLIBNAME, luaopen_math},
        {LUA_COLIBNAME, luaopen_coroutine}, {LUA_UTF8LIBNAME, luaopen_utf8},
        {LUA_OSLIBNAME, luaopen_os},        {LUA_DBLIBNAME, luaopen_debug},
        {nullptr, nullptr},
    };
    for (const luaL_Reg* library = kLibraries; library->func != nullptr; ++library) {
        luaL_requiref(state_, library->name, library->func, 1);
        lua_pop(state_, 1);
    }

    /* What is left out is not a sandbox - a script has the whole client API and
     * needs no filesystem to do damage with it - but shelling out and deleting
     * files are foot-guns rather than features, and io and package are simply
     * never opened. */
    static const char* const kRemovedFromOs[] = {"execute", "exit",    "remove",
                                                 "rename",  "tmpname", "setlocale"};
    lua_getglobal(state_, LUA_OSLIBNAME);
    for (const char* name : kRemovedFromOs) {
        lua_pushnil(state_);
        lua_setfield(state_, -2, name);
    }
    lua_pop(state_, 1);

    for (const char* name : {"dofile", "loadfile"}) {
        lua_pushnil(state_);
        lua_setglobal(state_, name);
    }
}

bool Script::load() {
    close();
    stopped_ = false;
    errors_ = 0;
    error_.clear();
    name_ = stem(file_);
    version_.clear();
    author_.clear();
    description_.clear();

    std::string source;
    if (!readTextFile(path_, source)) {
        fail("file could not be read");
        return false;
    }

    allocated_ = 0;
    state_ = lua_newstate(&allocate, &allocated_);
    if (state_ == nullptr) {
        fail("interpreter could not be created");
        return false;
    }
    lua_atpanic(state_, &onPanic);
    lua_pushlightuserdata(state_, this);
    lua_rawsetp(state_, LUA_REGISTRYINDEX, &kScriptKey);
    openLibraries();
    lua_sethook(state_, &onInstructionHook, LUA_MASKCOUNT, kHookInstructions);

    if (!installApi(state_, *this)) {
        fail("the client API could not be installed");
        return false;
    }

    /* The handler goes on first: lua_pcall wants it below the function it is
     * protecting, and the chunk is that function. */
    const std::string chunkName = "@" + file_;
    const int handler = pushHandler();
    if (luaL_loadbuffer(state_, source.data(), source.size(), chunkName.c_str()) != LUA_OK) {
        std::string message =
            lua_tostring(state_, -1) != nullptr ? lua_tostring(state_, -1) : "syntax error";
        lua_pop(state_, 2);
        fail(std::move(message));
        return false;
    }
    if (!call(0, 0, handler)) {
        stopped_ = true;
        return false;
    }
    return true;
}

void Script::fireUnload() {
    if (state_ == nullptr) {
        return;
    }
    /* A script that stopped on an error still gets its unload handler: it is
     * where a script gives back whatever it borrowed from the game. */
    const bool wasStopped = stopped_;
    stopped_ = false;
    const std::vector<int> handlers = handlers_[static_cast<std::size_t>(Event::Unload)];
    for (int reference : handlers) {
        const int handler = pushHandler();
        lua_rawgeti(state_, LUA_REGISTRYINDEX, reference);
        call(0, 0, handler);
    }
    stopped_ = wasStopped;
}

void Script::close() {
    if (state_ == nullptr) {
        return;
    }
    lua_close(state_);
    state_ = nullptr;
    for (std::vector<int>& list : handlers_) {
        list.clear();
    }
    modules_.clear();
}

void Script::setMetadata(std::string name, std::string version, std::string author,
                         std::string description) {
    if (!name.empty()) name_ = std::move(name);
    if (!version.empty()) version_ = std::move(version);
    if (!author.empty()) author_ = std::move(author);
    if (!description.empty()) description_ = std::move(description);
}

void Script::subscribe(Event event, int reference) {
    handlers_[static_cast<std::size_t>(event)].push_back(reference);
}

bool Script::unsubscribe(Event event, int reference) {
    std::vector<int>& list = handlers_[static_cast<std::size_t>(event)];
    for (std::size_t index = 0; index < list.size(); ++index) {
        if (list[index] == reference) {
            list.erase(list.begin() + static_cast<std::ptrdiff_t>(index));
            return true;
        }
    }
    return false;
}

void Script::removeModule(ScriptModule* module) {
    for (std::size_t index = 0; index < modules_.size(); ++index) {
        if (modules_[index] == module) {
            modules_.erase(modules_.begin() + static_cast<std::ptrdiff_t>(index));
            return;
        }
    }
}

int Script::pushHandler() {
    lua_pushcfunction(state_, &traceback);
    return lua_gettop(state_);
}

bool Script::call(int arguments, int results, int handlerIndex) {
    if (depth_ == 0) {
        deadline_ = nowSeconds() + kCallBudgetSeconds;
    }
    ++depth_;
    const int status = lua_pcall(state_, arguments, results, handlerIndex);
    --depth_;
    if (depth_ == 0) {
        deadline_ = 0.0;
    }
    if (status != LUA_OK) {
        std::string message =
            lua_tostring(state_, -1) != nullptr ? lua_tostring(state_, -1) : "unknown error";
        lua_pop(state_, 1);
        lua_remove(state_, handlerIndex);
        reportError(std::move(message));
        return false;
    }
    lua_remove(state_, handlerIndex);
    return true;
}

void Script::reportError(std::string message) {
    ++errors_;
    error_ = std::move(message);
    if (errors_ <= kLoggedErrors) {
        COLD_ERROR(kScope, "%s: %s", file_.c_str(), error_.c_str());
    }
    if (errors_ == 1) {
        notify(file_ + " errored, see the log", Notice::Bad);
    }
    if (errors_ >= kErrorLimit && !stopped_) {
        stopped_ = true;
        COLD_ERROR(kScope, "%s stopped after %lld errors", file_.c_str(), errors_);
        notify(file_ + " stopped after too many errors", Notice::Bad);
    }
}

void Script::fail(std::string message) {
    stopped_ = true;
    error_ = std::move(message);
    COLD_ERROR(kScope, "%s: %s", file_.c_str(), error_.c_str());
    notify(file_ + ": " + error_, Notice::Bad);
}

}  // namespace cold::script
