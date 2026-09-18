#include <cstring>
#include <string>
#include <vector>

#include "modules/ModuleManager.hpp"
#include "script/Api.hpp"
#include "script/ScriptManager.hpp"
#include "script/ScriptModule.hpp"

namespace cold::script {
namespace {

constexpr const char* kModuleField = "__module";

/* Module callbacks by the name a script uses for them. */
const char* callbackField(const std::string& event) {
    if (event == "enable") return "onEnable";
    if (event == "disable") return "onDisable";
    if (event == "tick" || event == "update") return "onTick";
    if (event == "render" || event == "draw") return "onRender";
    return nullptr;
}

std::string lowercase(std::string value) {
    for (char& character : value) {
        if (character >= 'A' && character <= 'Z') {
            character = static_cast<char>(character + 32);
        }
    }
    return value;
}

assets::IconAsset iconFrom(const std::string& name, assets::IconAsset fallback) {
    for (std::size_t index = 0; index < assets::kIconCount; ++index) {
        if (name == assets::kIconNames[index]) {
            return static_cast<assets::IconAsset>(index);
        }
    }
    return fallback;
}

modules::Module* moduleOf(lua_State* L, int index) {
    lua_getfield(L, index, kModuleField);
    const char* name = lua_tostring(L, -1);
    modules::Module* module = name != nullptr ? modules::modules().find(name) : nullptr;
    if (module == nullptr) {
        const std::string missing = name != nullptr ? name : "?";
        lua_pop(L, 1);
        luaL_error(L, "module '%s' is not registered any more", missing.c_str());
    }
    lua_pop(L, 1);
    return module;
}

modules::Setting* settingOf(modules::Module& module, const char* name) {
    return module.setting(name);
}

void pushSettingValue(lua_State* L, modules::Setting& setting) {
    switch (setting.type()) {
        case modules::Setting::Type::Boolean: lua_pushboolean(L, setting.booleanValue() ? 1 : 0); break;
        case modules::Setting::Type::Number: lua_pushnumber(L, setting.number()); break;
        case modules::Setting::Type::Choice:
            lua_pushlstring(L, setting.choiceName().c_str(), setting.choiceName().size());
            break;
        case modules::Setting::Type::Key: lua_pushinteger(L, setting.key()); break;
        case modules::Setting::Type::Colour: pushColour(L, setting.colour()); break;
    }
}

void applySettingValue(lua_State* L, modules::Setting& setting, int index) {
    switch (setting.type()) {
        case modules::Setting::Type::Boolean:
            setting.setBoolean(lua_toboolean(L, index) != 0);
            break;
        case modules::Setting::Type::Number:
            setting.setNumber(static_cast<float>(luaL_checknumber(L, index)));
            break;
        case modules::Setting::Type::Choice: {
            if (lua_isnumber(L, index)) {
                setting.setChoice(static_cast<int>(lua_tointeger(L, index)));
                break;
            }
            const std::string wanted = lowercase(luaL_checkstring(L, index));
            const std::vector<std::string>& choices = setting.choices();
            for (std::size_t entry = 0; entry < choices.size(); ++entry) {
                if (lowercase(choices[entry]) == wanted) {
                    setting.setChoice(static_cast<int>(entry));
                    return;
                }
            }
            luaL_error(L, "'%s' is not one of the choices for %s", wanted.c_str(),
                       setting.name().c_str());
            break;
        }
        case modules::Setting::Type::Key:
            setting.setKey(static_cast<int>(luaL_checkinteger(L, index)));
            break;
        case modules::Setting::Type::Colour:
            setting.setColour(checkColour(L, index));
            break;
    }
}

/* ------------------------------------------------------- setting proxy ---- */

int settingsIndex(lua_State* L) {
    lua_getfield(L, 1, kModuleField);
    const char* owner = lua_tostring(L, -1);
    modules::Module* module = owner != nullptr ? modules::modules().find(owner) : nullptr;
    lua_pop(L, 1);
    if (module == nullptr) {
        lua_pushnil(L);
        return 1;
    }
    modules::Setting* setting = settingOf(*module, luaL_checkstring(L, 2));
    if (setting == nullptr) {
        lua_pushnil(L);
        return 1;
    }
    pushSettingValue(L, *setting);
    return 1;
}

int settingsNewIndex(lua_State* L) {
    lua_getfield(L, 1, kModuleField);
    const char* owner = lua_tostring(L, -1);
    modules::Module* module = owner != nullptr ? modules::modules().find(owner) : nullptr;
    lua_pop(L, 1);
    const char* key = luaL_checkstring(L, 2);
    modules::Setting* setting = module != nullptr ? settingOf(*module, key) : nullptr;
    if (setting == nullptr) {
        luaL_error(L, "no setting called '%s'", key);
    }
    applySettingValue(L, *setting, 3);
    return 0;
}

void pushSettingsProxy(lua_State* L, const std::string& moduleName) {
    lua_createtable(L, 0, 1);
    setString(L, kModuleField, moduleName);
    lua_createtable(L, 0, 3);
    lua_pushcfunction(L, settingsIndex);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, settingsNewIndex);
    lua_setfield(L, -2, "__newindex");
    lua_pushstring(L, "settings");
    lua_setfield(L, -2, "__name");
    lua_setmetatable(L, -2);
}

/* -------------------------------------------------------- module handle ---- */

int moduleToggle(lua_State* L) {
    modules::Module* module = moduleOf(L, 1);
    module->setEnabled(!module->enabled(), manager().context());
    lua_pushboolean(L, module->enabled() ? 1 : 0);
    return 1;
}

int moduleSettingInfo(lua_State* L) {
    modules::Module* module = moduleOf(L, 1);
    modules::Setting* setting = settingOf(*module, luaL_checkstring(L, 2));
    if (setting == nullptr) {
        lua_pushnil(L);
        return 1;
    }
    lua_newtable(L);
    setString(L, "name", setting->name());
    setString(L, "description", setting->description());
    switch (setting->type()) {
        case modules::Setting::Type::Boolean: setString(L, "type", "boolean"); break;
        case modules::Setting::Type::Number:
            setString(L, "type", "number");
            setNumber(L, "min", setting->minimum());
            setNumber(L, "max", setting->maximum());
            setInteger(L, "decimals", setting->decimals());
            break;
        case modules::Setting::Type::Choice: {
            setString(L, "type", "choice");
            lua_newtable(L);
            int index = 1;
            for (const std::string& choice : setting->choices()) {
                lua_pushlstring(L, choice.c_str(), choice.size());
                lua_rawseti(L, -2, index++);
            }
            lua_setfield(L, -2, "choices");
            break;
        }
        case modules::Setting::Type::Key: setString(L, "type", "key"); break;
        case modules::Setting::Type::Colour: setString(L, "type", "color"); break;
    }
    pushSettingValue(L, *setting);
    lua_setfield(L, -2, "value");
    return 1;
}

int moduleSettingNames(lua_State* L) {
    modules::Module* module = moduleOf(L, 1);
    lua_newtable(L);
    int index = 1;
    for (const std::unique_ptr<modules::Setting>& setting : module->settings()) {
        lua_pushlstring(L, setting->name().c_str(), setting->name().size());
        lua_rawseti(L, -2, index++);
    }
    return 1;
}

/* Two handlers under one name, called in the order they were added. Upvalues
 * rather than a list, so `on` composes as many as a script cares to add. */
int chainedCallback(lua_State* L) {
    const int arguments = lua_gettop(L);
    for (int upvalue = 1; upvalue <= 2; ++upvalue) {
        lua_pushvalue(L, lua_upvalueindex(upvalue));
        for (int argument = 1; argument <= arguments; ++argument) {
            lua_pushvalue(L, argument);
        }
        lua_call(L, arguments, 0);
    }
    return 0;
}

int moduleOn(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    const std::string event = lowercase(luaL_checkstring(L, 2));
    luaL_checktype(L, 3, LUA_TFUNCTION);
    const char* field = callbackField(event);
    if (field == nullptr) {
        luaL_error(L, "a module has no '%s' event", event.c_str());
    }

    lua_getfield(L, 1, field);
    if (lua_isfunction(L, -1)) {
        lua_pushvalue(L, 3);
        lua_pushcclosure(L, chainedCallback, 2);
    } else {
        lua_pop(L, 1);
        lua_pushvalue(L, 3);
    }
    lua_setfield(L, 1, field);
    lua_pushvalue(L, 1);
    return 1;
}

int moduleIndex(lua_State* L) {
    const char* key = luaL_checkstring(L, 2);

    if (std::strcmp(key, "toggle") == 0) {
        lua_pushcfunction(L, moduleToggle);
        return 1;
    }
    if (std::strcmp(key, "setting") == 0) {
        lua_pushcfunction(L, moduleSettingInfo);
        return 1;
    }
    if (std::strcmp(key, "settingNames") == 0) {
        lua_pushcfunction(L, moduleSettingNames);
        return 1;
    }
    if (std::strcmp(key, "on") == 0) {
        lua_pushcfunction(L, moduleOn);
        return 1;
    }

    modules::Module* module = moduleOf(L, 1);
    if (std::strcmp(key, "enabled") == 0) {
        lua_pushboolean(L, module->enabled() ? 1 : 0);
    } else if (std::strcmp(key, "bind") == 0 || std::strcmp(key, "key") == 0) {
        lua_pushinteger(L, module->key());
    } else if (std::strcmp(key, "name") == 0) {
        lua_pushlstring(L, module->name().c_str(), module->name().size());
    } else if (std::strcmp(key, "description") == 0) {
        lua_pushlstring(L, module->description().c_str(), module->description().size());
    } else if (std::strcmp(key, "category") == 0) {
        lua_pushstring(L, modules::categoryName(module->category()));
    } else if (std::strcmp(key, "animation") == 0) {
        lua_pushnumber(L, module->animation());
    } else {
        lua_pushnil(L);
    }
    return 1;
}

int moduleNewIndex(lua_State* L) {
    const char* key = luaL_checkstring(L, 2);
    if (std::strcmp(key, "enabled") == 0) {
        modules::Module* module = moduleOf(L, 1);
        module->setEnabled(lua_toboolean(L, 3) != 0, manager().context());
        return 0;
    }
    if (std::strcmp(key, "bind") == 0 || std::strcmp(key, "key") == 0) {
        modules::Module* module = moduleOf(L, 1);
        module->setKey(static_cast<int>(luaL_checkinteger(L, 3)));
        return 0;
    }
    /* Anything else - the callbacks, and whatever state a script wants to keep
     * beside them - lives in the handle table itself. */
    lua_rawset(L, 1);
    return 0;
}

void attachHandleMetatable(lua_State* L) {
    lua_createtable(L, 0, 3);
    lua_pushcfunction(L, moduleIndex);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, moduleNewIndex);
    lua_setfield(L, -2, "__newindex");
    lua_pushstring(L, "module");
    lua_setfield(L, -2, "__name");
    lua_setmetatable(L, -2);
}

/* --------------------------------------------------------- declaration ---- */

void defineSetting(lua_State* L, int index, ScriptModule& module) {
    const std::string name = fieldString(L, index, "name", "");
    if (name.empty()) {
        luaL_error(L, "every setting needs a name");
    }
    const std::string description = fieldString(L, index, "description", "");

    std::string type = lowercase(fieldString(L, index, "type", ""));
    lua_getfield(L, index, "value");
    const int valueType = lua_type(L, -1);
    const bool hasChoices = [&] {
        lua_getfield(L, index, "choices");
        const bool table = lua_istable(L, -1);
        lua_pop(L, 1);
        return table;
    }();
    if (type.empty()) {
        /* Inferred from what the value looks like, which covers every settings
         * table anybody actually writes. */
        if (hasChoices) type = "choice";
        else if (valueType == LUA_TBOOLEAN) type = "boolean";
        else if (valueType == LUA_TTABLE) type = "color";
        else if (valueType == LUA_TNUMBER) type = "number";
        else type = "boolean";
    }
    lua_pop(L, 1);

    modules::Setting* setting = nullptr;
    if (type == "boolean" || type == "bool" || type == "check") {
        setting = &module.defineBoolean(name, description, fieldBoolean(L, index, "value", false));
    } else if (type == "number" || type == "float" || type == "int" || type == "slider") {
        const float minimum = fieldFloat(L, index, "min", 0.0f);
        const float maximum = fieldFloat(L, index, "max", 100.0f);
        const int decimals =
            fieldInt(L, index, "decimals", (type == "int" || maximum - minimum > 40.0f) ? 0 : 2);
        setting = &module.defineNumber(name, description, fieldFloat(L, index, "value", minimum),
                                       minimum, maximum, decimals);
    } else if (type == "choice" || type == "mode" || type == "list" || type == "enum") {
        std::vector<std::string> choices;
        lua_getfield(L, index, "choices");
        if (lua_istable(L, -1)) {
            const lua_Integer count = luaL_len(L, -1);
            for (lua_Integer entry = 1; entry <= count; ++entry) {
                lua_rawgeti(L, -1, entry);
                if (lua_isstring(L, -1)) {
                    choices.emplace_back(lua_tostring(L, -1));
                }
                lua_pop(L, 1);
            }
        }
        lua_pop(L, 1);
        if (choices.empty()) {
            luaL_error(L, "setting '%s' is a choice with nothing to choose from", name.c_str());
        }
        const std::string initial = lowercase(fieldString(L, index, "value", choices[0].c_str()));
        int selected = 0;
        for (std::size_t entry = 0; entry < choices.size(); ++entry) {
            if (lowercase(choices[entry]) == initial) {
                selected = static_cast<int>(entry);
                break;
            }
        }
        setting = &module.defineChoice(name, description, std::move(choices), selected);
    } else if (type == "key" || type == "bind") {
        setting = &module.defineKey(name, description, fieldInt(L, index, "value", 0));
    } else if (type == "color" || type == "colour") {
        lua_getfield(L, index, "value");
        const Color colour = optColour(L, lua_gettop(L), Color::rgb(0x58C6FF));
        lua_pop(L, 1);
        setting = &module.defineColour(name, description, colour);
    } else {
        luaL_error(L, "setting '%s' has an unknown type '%s'", name.c_str(), type.c_str());
    }

    if (setting != nullptr && fieldFunction(L, index, "visible")) {
        module.defineVisibility(*setting, luaL_ref(L, LUA_REGISTRYINDEX));
    }
}

int coldModule(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    Script* script = Script::fromState(L);
    if (script == nullptr) {
        luaL_error(L, "this state has no script bound to it");
    }
    if (!manager().loading()) {
        luaL_error(L, "modules can only be declared while the script is loading");
    }

    const std::string name = fieldString(L, 1, "name", "");
    if (name.empty()) {
        luaL_error(L, "a module needs a name");
    }
    if (modules::modules().find(name) != nullptr) {
        luaL_error(L, "a module called '%s' already exists", name.c_str());
    }

    const std::string description = fieldString(L, 1, "description", "");
    /* Script modules live in the Scripts tab, always. There is no category
     * field any more; a stale one in the definition table is ignored. */
    constexpr modules::Category category = modules::Category::Scripts;
    int bind = fieldInt(L, 1, "bind", 0);
    if (bind == 0) {
        bind = fieldInt(L, 1, "key", 0);
    }

    auto owned = std::make_unique<ScriptModule>(*script, name, description, category, bind);
    ScriptModule& module = *owned;
    module.defineIcon(iconFrom(fieldString(L, 1, "icon", ""), modules::categoryIcon(category)));

    lua_getfield(L, 1, "settings");
    if (lua_istable(L, -1)) {
        const int settings = lua_gettop(L);
        const lua_Integer count = luaL_len(L, settings);
        for (lua_Integer entry = 1; entry <= count; ++entry) {
            lua_rawgeti(L, settings, entry);
            if (lua_istable(L, -1)) {
                defineSetting(L, lua_gettop(L), module);
            }
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 1);

    /* The definition table becomes the handle: the callbacks written inside it
     * are already where they need to be, and everything else is reachable
     * through the metatable. */
    lua_pushvalue(L, 1);
    setString(L, kModuleField, name);
    pushSettingsProxy(L, name);
    lua_setfield(L, -2, "settings");
    for (const char* stale : {"bind", "key", "enabled", "category"}) {
        lua_pushnil(L);
        lua_setfield(L, -2, stale);
    }
    attachHandleMetatable(L);

    lua_pushvalue(L, -1);
    module.bind(luaL_ref(L, LUA_REGISTRYINDEX));
    manager().attachModule(*script, std::move(owned));
    return 1;
}

/* ---------------------------------------------------------- cold.modules -- */

int modulesGet(lua_State* L) {
    const char* name = luaL_checkstring(L, 1);
    modules::Module* module = modules::modules().find(name);
    if (module == nullptr) {
        lua_pushnil(L);
        return 1;
    }
    pushModuleHandle(L, module->name());
    return 1;
}

int modulesList(lua_State* L) {
    lua_newtable(L);
    int index = 1;
    for (const std::unique_ptr<modules::Module>& module : modules::modules().all()) {
        lua_pushlstring(L, module->name().c_str(), module->name().size());
        lua_rawseti(L, -2, index++);
    }
    return 1;
}

int modulesEnabled(lua_State* L) {
    modules::Module* module = modules::modules().find(luaL_checkstring(L, 1));
    lua_pushboolean(L, module != nullptr && module->enabled() ? 1 : 0);
    return 1;
}

int modulesToggle(lua_State* L) {
    modules::Module* module = modules::modules().find(luaL_checkstring(L, 1));
    if (module == nullptr) {
        lua_pushboolean(L, 0);
        return 1;
    }
    module->setEnabled(lua_isnoneornil(L, 2) ? !module->enabled() : lua_toboolean(L, 2) != 0,
                       manager().context());
    lua_pushboolean(L, module->enabled() ? 1 : 0);
    return 1;
}

const luaL_Reg kModulesFunctions[] = {
    {"get", modulesGet},         {"list", modulesList}, {"enabled", modulesEnabled},
    {"toggle", modulesToggle},   {nullptr, nullptr},
};

}  // namespace

void pushModuleHandle(lua_State* L, const std::string& name) {
    lua_createtable(L, 0, 2);
    setString(L, kModuleField, name);
    pushSettingsProxy(L, name);
    lua_setfield(L, -2, "settings");
    attachHandleMetatable(L);
}

void installModules(lua_State* L) {
    lua_pushcfunction(L, coldModule);
    lua_setfield(L, -2, "module");

    lua_newtable(L);
    luaL_setfuncs(L, kModulesFunctions, 0);
    lua_setfield(L, -2, "modules");
}

}  // namespace cold::script
