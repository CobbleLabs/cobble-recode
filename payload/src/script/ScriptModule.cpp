#include "script/ScriptModule.hpp"

#include "script/ScriptManager.hpp"

namespace cold::script {

ScriptModule::ScriptModule(Script& owner, std::string name, std::string description,
                           modules::Category category, int key)
    : modules::Module(std::move(name), std::move(description), category, key), owner_(owner) {}

ScriptModule::~ScriptModule() {
    /* The state outlives its modules by construction - the manager removes
     * them before it closes it - so the reference is worth giving back. */
    if (tableRef_ != LUA_NOREF && owner_.state() != nullptr) {
        luaL_unref(owner_.state(), LUA_REGISTRYINDEX, tableRef_);
    }
    tableRef_ = LUA_NOREF;
}

bool ScriptModule::pushCallback(const char* key) const {
    if (!owner_.running() || tableRef_ == LUA_NOREF) {
        return false;
    }
    lua_State* L = owner_.state();
    /* The handler goes on first and the call is arranged above it: lua_pcall
     * wants it underneath the function it protects. */
    const int handler = owner_.pushHandler();
    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef_);
    lua_getfield(L, -1, key);
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 2);
        lua_remove(L, handler);
        return false;
    }
    /* handler, function, table: the table is the argument, so `function m.onTick(self)`
     * and `function m:onTick()` are the same thing. */
    lua_insert(L, -2);
    return true;
}

void ScriptModule::invoke(const char* key) const {
    if (!pushCallback(key)) {
        return;
    }
    lua_State* L = owner_.state();
    owner_.call(1, 0, lua_gettop(L) - 2);
}

std::string ScriptModule::suffix() const {
    if (!owner_.running() || tableRef_ == LUA_NOREF) {
        return {};
    }
    lua_State* L = owner_.state();
    const int handler = owner_.pushHandler();
    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef_);
    lua_getfield(L, -1, "suffix");
    if (lua_type(L, -1) == LUA_TSTRING) {
        std::string value = lua_tostring(L, -1);
        lua_pop(L, 2);
        lua_remove(L, handler);
        return value;
    }
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 2);
        lua_remove(L, handler);
        return {};
    }
    lua_insert(L, -2);
    if (!owner_.call(1, 1, handler)) {
        return {};
    }
    std::string value = lua_type(L, -1) == LUA_TSTRING ? lua_tostring(L, -1) : std::string();
    lua_pop(L, 1);
    return value;
}

void ScriptModule::ensureStarted() {
    if (started_ || !enabled()) {
        return;
    }
    started_ = true;
    invoke("onEnable");
}

void ScriptModule::onEnable(modules::Context&) {
    if (started_) {
        return;
    }
    started_ = true;
    invoke("onEnable");
}

void ScriptModule::onDisable(modules::Context&) {
    if (!started_) {
        return;
    }
    started_ = false;
    invoke("onDisable");
}

void ScriptModule::onTick(modules::Context&) {
    ensureStarted();
    invoke("onTick");
}

void ScriptModule::onRender(modules::Context&) {
    ensureStarted();
    DrawingScope drawing;
    invoke("onRender");
}

modules::Setting& ScriptModule::defineBoolean(std::string name, std::string description,
                                              bool value) {
    return addBoolean(std::move(name), std::move(description), value);
}

modules::Setting& ScriptModule::defineNumber(std::string name, std::string description, float value,
                                             float minimum, float maximum, int decimals) {
    return addNumber(std::move(name), std::move(description), value, minimum, maximum, decimals);
}

modules::Setting& ScriptModule::defineChoice(std::string name, std::string description,
                                             std::vector<std::string> choices, int index) {
    return addChoice(std::move(name), std::move(description), std::move(choices), index);
}

modules::Setting& ScriptModule::defineKey(std::string name, std::string description, int key) {
    return addKey(std::move(name), std::move(description), key);
}

modules::Setting& ScriptModule::defineColour(std::string name, std::string description,
                                             const Color& colour) {
    return addColour(std::move(name), std::move(description), colour);
}

void ScriptModule::defineVisibility(modules::Setting& setting, int functionRef) {
    Script* script = &owner_;
    setting.visibleWhen([script, functionRef] {
        if (!script->running()) {
            return true;
        }
        lua_State* L = script->state();
        const int handler = script->pushHandler();
        lua_rawgeti(L, LUA_REGISTRYINDEX, functionRef);
        if (!script->call(0, 1, handler)) {
            return true;
        }
        const bool visible = lua_toboolean(L, -1) != 0;
        lua_pop(L, 1);
        return visible;
    });
}

}  // namespace cold::script
