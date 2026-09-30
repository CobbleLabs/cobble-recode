#pragma once

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

#include <string>

#include "core/Math.hpp"

namespace cold::script {

/* Value conversions shared by every binding.
 *
 * The rule everywhere: a script may hand over the convenient form or the exact
 * one and mean the same thing. A colour is a table of r/g/b/a in 0..1 - what
 * cold.color and friends return - or a packed integer, 0xRRGGBB when it fits in
 * twenty-four bits and 0xRRGGBBAA when it does not. A position is a table with
 * x/y/z or three loose numbers. */

void registerColourType(lua_State* L);
/* Positions come back with a metatable of their own so the prelude can hang
 * arithmetic on them; the C side only has to create it. */
void registerVec3Type(lua_State* L);
void pushColour(lua_State* L, const Color& colour);
bool isColour(lua_State* L, int index);
Color checkColour(lua_State* L, int index);
Color optColour(lua_State* L, int index, const Color& fallback);

void pushVec3(lua_State* L, const Vec3& value);
void pushRect(lua_State* L, const Rect& bounds);
/* One {x,y,z} table or three loose numbers starting at `index`. Returns the
 * stack index just past what it consumed, so a caller keeps reading its own
 * arguments from there. */
int readVec3(lua_State* L, int index, Vec3& out);

float checkFloat(lua_State* L, int index);
float optFloat(lua_State* L, int index, float fallback);
bool optBoolean(lua_State* L, int index, bool fallback);

bool fieldBoolean(lua_State* L, int index, const char* key, bool fallback);
float fieldFloat(lua_State* L, int index, const char* key, float fallback);
int fieldInt(lua_State* L, int index, const char* key, int fallback);
std::string fieldString(lua_State* L, int index, const char* key, const char* fallback);
/* True when the field holds a function, which it leaves on the stack for the
 * caller to reference. */
bool fieldFunction(lua_State* L, int index, const char* key);

void setInteger(lua_State* L, const char* key, lua_Integer value);
void setNumber(lua_State* L, const char* key, double value);
void setString(lua_State* L, const char* key, const std::string& value);
void setBoolean(lua_State* L, const char* key, bool value);

}  // namespace cold::script
