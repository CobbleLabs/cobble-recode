#pragma once

#include "bridge/Minecraft.hpp"
#include "modules/Module.hpp"
#include "script/Lua.hpp"

namespace cold::script {

class Script;

/* Builds the whole `cold` table in a fresh state, then runs the prelude - the
 * part of the API that is easier to say in Lua than in C. */
bool installApi(lua_State* L, Script& script);

/* One slice of the surface each. Every one of them expects the `cold` table on
 * top of the stack and leaves it there. */
void installCore(lua_State* L);
void installGame(lua_State* L);
void installRender(lua_State* L);
void installRender3d(lua_State* L);
void installHud(lua_State* L);
void installModules(lua_State* L);

/* Entity handles resolve by id against the frame in flight rather than holding
 * anything of it, so one can be kept in a script variable and simply reports
 * itself invalid once the entity is gone. */
void pushEntity(lua_State* L, int entityId);
/* Handle for any module, script-owned or not, by name. */
void pushModuleHandle(lua_State* L, const std::string& name);

/* Guards shared by the bindings. Each raises a Lua error rather than returning
 * one, so a script that reaches for the game outside a frame gets a message
 * with a line number on it instead of a silent no-op. */
modules::Context& requireContext(lua_State* L);
const game::FrameState& requireState(lua_State* L);
const game::LocalPlayerView& requirePlayer(lua_State* L);

}  // namespace cold::script
