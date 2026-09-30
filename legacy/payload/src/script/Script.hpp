#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "script/Lua.hpp"

namespace cold::script {

class ScriptModule;

/* Everything a script can be told about. `Load` and `Unload` fire once each and
 * bracket the script's life; the rest are the client's own frame, tick and
 * input path. */
enum class Event : std::uint8_t {
    Load,
    Unload,
    Frame,
    Tick,
    Render,
    Render3d,
    Key,
    CharTyped,
    Mouse,
    MouseMove,
    Scroll,
    WorldLoad,
    WorldLeave,
    ScreenOpen,
    ScreenClose,
    EntitySpawn,
    EntityRemove,
    EntityDeath,
    HealthChange,
    Death,
    Respawn,
    SlotChange,
    ModuleToggled,
    EffectAdded,
    EffectRemoved,
    Count,
};

inline constexpr auto kEventCount = static_cast<std::size_t>(Event::Count);

const char* eventName(Event event);
bool eventFromName(std::string_view name, Event& out);

/* One .lua file, one lua_State.
 *
 * A state per script rather than one shared state is the whole isolation
 * model: a script that errors, leaks a global or loops a table cannot reach
 * another one, and unloading is a single lua_close rather than an unpicking of
 * whatever it left behind. The cost is one interpreter per script, which is
 * tens of kilobytes. */
class Script {
public:
    Script(std::string file, std::wstring path);
    ~Script();

    Script(const Script&) = delete;
    Script& operator=(const Script&) = delete;

    /* Opens a state, installs the API and runs the chunk. False leaves the
     * script stopped with error() explaining why. */
    bool load();
    /* Fires the unload event; the manager removes the modules and closes the
     * state around this. */
    void fireUnload();
    void close();

    lua_State* state() const { return state_; }
    /* The script that owns a state, for the bindings a callback lands in. */
    static Script* fromState(lua_State* L);
    /* Running means loaded, not stopped by an error, and safe to dispatch to. */
    bool running() const { return state_ != nullptr && !stopped_; }
    bool stopped() const { return stopped_; }

    const std::string& file() const { return file_; }
    const std::wstring& path() const { return path_; }
    const std::string& name() const { return name_; }
    const std::string& version() const { return version_; }
    const std::string& author() const { return author_; }
    const std::string& description() const { return description_; }
    const std::string& error() const { return error_; }
    long long errorCount() const { return errors_; }

    void setMetadata(std::string name, std::string version, std::string author,
                     std::string description);

    const std::vector<int>& handlers(Event event) const {
        return handlers_[static_cast<std::size_t>(event)];
    }
    void subscribe(Event event, int reference);
    bool unsubscribe(Event event, int reference);

    void addModule(ScriptModule* module) { modules_.push_back(module); }
    void removeModule(ScriptModule* module);
    const std::vector<ScriptModule*>& modules() const { return modules_; }

    /* Pushes the traceback handler and returns its absolute stack index. Every
     * call into a script goes through one: without it a runtime error reports
     * the line the error was raised on and nothing about how it got there,
     * which for a callback fired from C is no information at all. */
    int pushHandler();
    /* Runs a call whose function and arguments are already on the stack, with
     * the handler at `handlerIndex`. Reports and counts failures, and pops the
     * handler either way. */
    bool call(int arguments, int results, int handlerIndex);

    /* Stops the script and records why. Used for a load failure and for a
     * script that keeps erroring. */
    void fail(std::string message);
    /* Deadline for the call in flight, in seconds on the client clock. The
     * instruction hook compares against it: a script with a runaway loop is a
     * frozen game otherwise, because everything here runs on the render
     * thread. */
    double deadline() const { return deadline_; }

private:
    std::string file_;
    std::wstring path_;
    std::string name_;
    std::string version_;
    std::string author_;
    std::string description_;
    std::string error_;

    void openLibraries();
    void reportError(std::string message);

    lua_State* state_ = nullptr;
    bool stopped_ = false;
    long long errors_ = 0;
    int depth_ = 0;
    double deadline_ = 0.0;
    /* Bytes the state has allocated, against a per-script ceiling. A script
     * that leaks a table every frame takes itself down rather than the game. */
    std::size_t allocated_ = 0;

    std::array<std::vector<int>, kEventCount> handlers_;
    std::vector<ScriptModule*> modules_;
};

}  // namespace cold::script
