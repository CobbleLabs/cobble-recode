#include "script/Lua.hpp"

#include <cstdio>

namespace cold::script {
namespace {

constexpr const char* kColourMeta = "cold.colour";
constexpr const char* kVec3Meta = "cold.vec3";

float clamp01(float value) { return clamp(value, 0.0f, 1.0f); }

Color colourFromInteger(lua_Integer value) {
    const auto packed = static_cast<std::uint64_t>(value);
    if (packed > 0xFFFFFFull) {
        return Color::rgba(static_cast<std::uint32_t>(packed & 0xFFFFFFFFull));
    }
    return Color::rgb(static_cast<std::uint32_t>(packed & 0xFFFFFFull));
}

int colourWithAlpha(lua_State* L) {
    Color colour = checkColour(L, 1);
    colour.a = clamp01(static_cast<float>(luaL_checknumber(L, 2)));
    pushColour(L, colour);
    return 1;
}

int colourFade(lua_State* L) {
    Color colour = checkColour(L, 1);
    colour.a = clamp01(colour.a * static_cast<float>(luaL_checknumber(L, 2)));
    pushColour(L, colour);
    return 1;
}

int colourMix(lua_State* L) {
    const Color from = checkColour(L, 1);
    const Color to = checkColour(L, 2);
    pushColour(L, Color::mix(from, to, clamp01(static_cast<float>(luaL_checknumber(L, 3)))));
    return 1;
}

int colourHex(lua_State* L) {
    const Color colour = checkColour(L, 1);
    char buffer[16];
    std::snprintf(buffer, sizeof(buffer), "#%06X", colour.packed() >> 8);
    lua_pushstring(L, buffer);
    return 1;
}

int colourUnpack(lua_State* L) {
    const Color colour = checkColour(L, 1);
    lua_pushnumber(L, colour.r);
    lua_pushnumber(L, colour.g);
    lua_pushnumber(L, colour.b);
    lua_pushnumber(L, colour.a);
    return 4;
}

int colourToString(lua_State* L) {
    const Color colour = checkColour(L, 1);
    char buffer[48];
    std::snprintf(buffer, sizeof(buffer), "colour(#%06X, a=%.2f)", colour.packed() >> 8,
                  static_cast<double>(colour.a));
    lua_pushstring(L, buffer);
    return 1;
}

const luaL_Reg kColourMethods[] = {
    {"alpha", colourWithAlpha}, {"fade", colourFade},     {"mix", colourMix},
    {"hex", colourHex},         {"unpack", colourUnpack}, {nullptr, nullptr},
};

}  // namespace

void registerColourType(lua_State* L) {
    luaL_newmetatable(L, kColourMeta);
    lua_newtable(L);
    luaL_setfuncs(L, kColourMethods, 0);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, colourToString);
    lua_setfield(L, -2, "__tostring");
    lua_pushstring(L, "colour");
    lua_setfield(L, -2, "__name");
    lua_pop(L, 1);
}

void pushColour(lua_State* L, const Color& colour) {
    lua_createtable(L, 0, 4);
    setNumber(L, "r", colour.r);
    setNumber(L, "g", colour.g);
    setNumber(L, "b", colour.b);
    setNumber(L, "a", colour.a);
    luaL_getmetatable(L, kColourMeta);
    lua_setmetatable(L, -2);
}

bool isColour(lua_State* L, int index) {
    return lua_isnumber(L, index) || lua_istable(L, index);
}

Color checkColour(lua_State* L, int index) {
    if (lua_isnumber(L, index)) {
        return colourFromInteger(lua_tointeger(L, index));
    }
    if (!lua_istable(L, index)) {
        luaL_error(L, "expected a colour at argument %d, got %s", index, luaL_typename(L, index));
    }
    Color colour;
    colour.r = clamp01(fieldFloat(L, index, "r", 1.0f));
    colour.g = clamp01(fieldFloat(L, index, "g", 1.0f));
    colour.b = clamp01(fieldFloat(L, index, "b", 1.0f));
    colour.a = clamp01(fieldFloat(L, index, "a", 1.0f));
    return colour;
}

Color optColour(lua_State* L, int index, const Color& fallback) {
    if (lua_isnoneornil(L, index)) {
        return fallback;
    }
    return checkColour(L, index);
}

void registerVec3Type(lua_State* L) {
    luaL_newmetatable(L, kVec3Meta);
    lua_pushstring(L, "vec3");
    lua_setfield(L, -2, "__name");
    lua_pop(L, 1);
}

void pushVec3(lua_State* L, const Vec3& value) {
    lua_createtable(L, 0, 3);
    setNumber(L, "x", value.x);
    setNumber(L, "y", value.y);
    setNumber(L, "z", value.z);
    luaL_getmetatable(L, kVec3Meta);
    lua_setmetatable(L, -2);
}

void pushRect(lua_State* L, const Rect& bounds) {
    lua_createtable(L, 0, 4);
    setNumber(L, "x", bounds.x);
    setNumber(L, "y", bounds.y);
    setNumber(L, "width", bounds.width);
    setNumber(L, "height", bounds.height);
}

int readVec3(lua_State* L, int index, Vec3& out) {
    if (lua_istable(L, index)) {
        out.x = fieldFloat(L, index, "x", 0.0f);
        out.y = fieldFloat(L, index, "y", 0.0f);
        out.z = fieldFloat(L, index, "z", 0.0f);
        return index + 1;
    }
    out.x = luaL_checknumber(L, index);
    out.y = luaL_checknumber(L, index + 1);
    out.z = luaL_checknumber(L, index + 2);
    return index + 3;
}

float checkFloat(lua_State* L, int index) {
    return static_cast<float>(luaL_checknumber(L, index));
}

float optFloat(lua_State* L, int index, float fallback) {
    return static_cast<float>(luaL_optnumber(L, index, static_cast<lua_Number>(fallback)));
}

bool optBoolean(lua_State* L, int index, bool fallback) {
    if (lua_isnoneornil(L, index)) {
        return fallback;
    }
    return lua_toboolean(L, index) != 0;
}

bool fieldBoolean(lua_State* L, int index, const char* key, bool fallback) {
    lua_getfield(L, index, key);
    const bool value = lua_isnil(L, -1) ? fallback : lua_toboolean(L, -1) != 0;
    lua_pop(L, 1);
    return value;
}

float fieldFloat(lua_State* L, int index, const char* key, float fallback) {
    lua_getfield(L, index, key);
    const float value =
        lua_isnumber(L, -1) ? static_cast<float>(lua_tonumber(L, -1)) : fallback;
    lua_pop(L, 1);
    return value;
}

int fieldInt(lua_State* L, int index, const char* key, int fallback) {
    lua_getfield(L, index, key);
    const int value = lua_isnumber(L, -1) ? static_cast<int>(lua_tointeger(L, -1)) : fallback;
    lua_pop(L, 1);
    return value;
}

std::string fieldString(lua_State* L, int index, const char* key, const char* fallback) {
    lua_getfield(L, index, key);
    std::string value = lua_isstring(L, -1) ? lua_tostring(L, -1) : fallback;
    lua_pop(L, 1);
    return value;
}

bool fieldFunction(lua_State* L, int index, const char* key) {
    lua_getfield(L, index, key);
    if (lua_isfunction(L, -1)) {
        return true;
    }
    lua_pop(L, 1);
    return false;
}

void setInteger(lua_State* L, const char* key, lua_Integer value) {
    lua_pushinteger(L, value);
    lua_setfield(L, -2, key);
}

void setNumber(lua_State* L, const char* key, double value) {
    lua_pushnumber(L, value);
    lua_setfield(L, -2, key);
}

void setString(lua_State* L, const char* key, const std::string& value) {
    lua_pushlstring(L, value.c_str(), value.size());
    lua_setfield(L, -2, key);
}

void setBoolean(lua_State* L, const char* key, bool value) {
    lua_pushboolean(L, value ? 1 : 0);
    lua_setfield(L, -2, key);
}

}  // namespace cold::script
