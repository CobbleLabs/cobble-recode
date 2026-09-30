#include <cstring>
#include <string>

#include "script/Api.hpp"
#include "script/HudElement.hpp"
#include "script/ScriptManager.hpp"

namespace cold::script {
namespace {

constexpr const char* kElementField = "__hud";

HudElement* elementOf(lua_State* L, int index) {
    lua_getfield(L, index, kElementField);
    const char* name = lua_tostring(L, -1);
    HudElement* element = name != nullptr ? manager().findHudElement(name) : nullptr;
    if (element == nullptr) {
        const std::string missing = name != nullptr ? name : "?";
        lua_pop(L, 1);
        luaL_error(L, "the HUD element '%s' is gone", missing.c_str());
    }
    lua_pop(L, 1);
    return element;
}

int elementPosition(lua_State* L) {
    HudElement* element = elementOf(L, 1);
    if (lua_isnumber(L, 2)) {
        element->setPosition(checkFloat(L, 2), checkFloat(L, 3));
        lua_pushvalue(L, 1);
        return 1;
    }
    lua_pushnumber(L, element->x());
    lua_pushnumber(L, element->y());
    return 2;
}

int elementSize(lua_State* L) {
    HudElement* element = elementOf(L, 1);
    if (lua_isnumber(L, 2)) {
        element->setSize(checkFloat(L, 2), checkFloat(L, 3));
        lua_pushvalue(L, 1);
        return 1;
    }
    lua_pushnumber(L, element->width());
    lua_pushnumber(L, element->height());
    return 2;
}

int elementShow(lua_State* L) {
    elementOf(L, 1)->setShowing(true);
    lua_pushvalue(L, 1);
    return 1;
}

int elementHide(lua_State* L) {
    elementOf(L, 1)->setShowing(false);
    lua_pushvalue(L, 1);
    return 1;
}

int elementRemove(lua_State* L) {
    manager().removeHudElement(elementOf(L, 1));
    return 0;
}

int elementIndex(lua_State* L) {
    const char* key = luaL_checkstring(L, 2);
    if (std::strcmp(key, "pos") == 0) {
        lua_pushcfunction(L, elementPosition);
        return 1;
    }
    if (std::strcmp(key, "size") == 0) {
        lua_pushcfunction(L, elementSize);
        return 1;
    }
    if (std::strcmp(key, "show") == 0) {
        lua_pushcfunction(L, elementShow);
        return 1;
    }
    if (std::strcmp(key, "hide") == 0) {
        lua_pushcfunction(L, elementHide);
        return 1;
    }
    if (std::strcmp(key, "remove") == 0) {
        lua_pushcfunction(L, elementRemove);
        return 1;
    }

    HudElement* element = elementOf(L, 1);
    if (std::strcmp(key, "x") == 0) lua_pushnumber(L, element->x());
    else if (std::strcmp(key, "y") == 0) lua_pushnumber(L, element->y());
    else if (std::strcmp(key, "width") == 0) lua_pushnumber(L, element->width());
    else if (std::strcmp(key, "height") == 0) lua_pushnumber(L, element->height());
    else if (std::strcmp(key, "showing") == 0) lua_pushboolean(L, element->showing() ? 1 : 0);
    else if (std::strcmp(key, "name") == 0) {
        lua_pushlstring(L, element->name().c_str(), element->name().size());
    } else {
        lua_pushnil(L);
    }
    return 1;
}

int elementNewIndex(lua_State* L) {
    const char* key = luaL_checkstring(L, 2);
    if (std::strcmp(key, "x") == 0 || std::strcmp(key, "y") == 0) {
        HudElement* element = elementOf(L, 1);
        const float value = checkFloat(L, 3);
        element->setPosition(std::strcmp(key, "x") == 0 ? value : element->x(),
                             std::strcmp(key, "y") == 0 ? value : element->y());
        return 0;
    }
    if (std::strcmp(key, "width") == 0 || std::strcmp(key, "height") == 0) {
        HudElement* element = elementOf(L, 1);
        const float value = checkFloat(L, 3);
        element->setSize(std::strcmp(key, "width") == 0 ? value : element->width(),
                         std::strcmp(key, "height") == 0 ? value : element->height());
        return 0;
    }
    if (std::strcmp(key, "showing") == 0) {
        elementOf(L, 1)->setShowing(lua_toboolean(L, 3) != 0);
        return 0;
    }
    lua_rawset(L, 1);
    return 0;
}

void attachHandleMetatable(lua_State* L) {
    lua_createtable(L, 0, 3);
    lua_pushcfunction(L, elementIndex);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, elementNewIndex);
    lua_setfield(L, -2, "__newindex");
    lua_pushstring(L, "hudElement");
    lua_setfield(L, -2, "__name");
    lua_setmetatable(L, -2);
}

int hudElementNew(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    Script* script = Script::fromState(L);
    if (script == nullptr) {
        luaL_error(L, "this state has no script bound to it");
    }
    const std::string name = fieldString(L, 1, "name", "");
    if (name.empty()) {
        luaL_error(L, "a HUD element needs a name");
    }
    if (manager().findHudElement(name) != nullptr) {
        luaL_error(L, "a HUD element called '%s' already exists", name.c_str());
    }

    auto owned = std::make_unique<HudElement>(*script, name);
    HudElement& element = *owned;
    element.setSize(fieldFloat(L, 1, "width", 70.0f), fieldFloat(L, 1, "height", 16.0f));
    element.setShowing(fieldBoolean(L, 1, "showing", true));

    /* Where the element was left last time wins over where the script asks for
     * it: a position the player dragged it to is theirs, not the script's. */
    const std::string section = script->file();
    const float x = manager().store().getNumber(section, "hud." + name + ".x",
                                                fieldFloat(L, 1, "x", 4.0f));
    const float y = manager().store().getNumber(section, "hud." + name + ".y",
                                                fieldFloat(L, 1, "y", 40.0f));
    element.setPosition(x, y);

    lua_pushvalue(L, 1);
    setString(L, kElementField, name);
    for (const char* stale : {"x", "y", "width", "height", "showing"}) {
        lua_pushnil(L);
        lua_setfield(L, -2, stale);
    }
    attachHandleMetatable(L);

    lua_pushvalue(L, -1);
    element.bind(luaL_ref(L, LUA_REGISTRYINDEX));
    manager().attachHudElement(*script, std::move(owned));
    return 1;
}

int hudFind(lua_State* L) {
    const char* name = luaL_checkstring(L, 1);
    HudElement* element = manager().findHudElement(name);
    if (element == nullptr || element->table() == LUA_NOREF ||
        element->owner().state() != L) {
        lua_pushnil(L);
        return 1;
    }
    lua_rawgeti(L, LUA_REGISTRYINDEX, element->table());
    return 1;
}

int hudAll(lua_State* L) {
    lua_newtable(L);
    int index = 1;
    for (const std::unique_ptr<HudElement>& element : manager().hudElements()) {
        lua_newtable(L);
        setString(L, "name", element->name());
        setString(L, "script", element->owner().file());
        setNumber(L, "x", element->x());
        setNumber(L, "y", element->y());
        setNumber(L, "width", element->width());
        setNumber(L, "height", element->height());
        setBoolean(L, "showing", element->showing());
        lua_rawseti(L, -2, index++);
    }
    return 1;
}

int hudRemove(lua_State* L) {
    HudElement* element = manager().findHudElement(luaL_checkstring(L, 1));
    if (element == nullptr) {
        lua_pushboolean(L, 0);
        return 1;
    }
    manager().removeHudElement(element);
    lua_pushboolean(L, 1);
    return 1;
}

const luaL_Reg kFunctions[] = {
    {"element", hudElementNew}, {"find", hudFind}, {"all", hudAll},
    {"remove", hudRemove},      {nullptr, nullptr},
};

}  // namespace

void installHud(lua_State* L) {
    lua_newtable(L);
    luaL_setfuncs(L, kFunctions, 0);
    lua_setfield(L, -2, "hud");
}

}  // namespace cold::script
