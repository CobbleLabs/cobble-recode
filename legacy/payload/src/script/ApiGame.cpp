#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "game/Raycast.hpp"
#include "game/RotationEngine.hpp"
#include "game/TargetFinder.hpp"
#include "hooks/Input.hpp"
#include "script/Api.hpp"
#include "script/ScriptManager.hpp"

namespace cold::script {
namespace {

constexpr const char* kEntityMeta = "cold.entity";

struct EntityHandle {
    int id = -1;
    /* Where it was in the frame's list last time, so repeated field reads on
     * the same handle do not walk the list again. */
    int cached = -1;
};

struct Named {
    const char* name;
    int value;
};

const Named kItemKinds[] = {
    {"totem", static_cast<int>(game::ItemKind::Totem)},
    {"crystal", static_cast<int>(game::ItemKind::EndCrystal)},
    {"anchor", static_cast<int>(game::ItemKind::RespawnAnchor)},
    {"glowstone", static_cast<int>(game::ItemKind::Glowstone)},
    {"mace", static_cast<int>(game::ItemKind::Mace)},
    {"obsidian", static_cast<int>(game::ItemKind::Obsidian)},
    {"axe", static_cast<int>(game::ItemKind::Axe)},
    {"sword", static_cast<int>(game::ItemKind::Sword)},
};

const Named kBlockKinds[] = {
    {"obsidian", static_cast<int>(game::BlockKind::Obsidian)},
    {"bedrock", static_cast<int>(game::BlockKind::Bedrock)},
    {"anchor", static_cast<int>(game::BlockKind::RespawnAnchor)},
};

const Named kFaces[] = {
    {"down", static_cast<int>(game::BlockFace::Down)},
    {"up", static_cast<int>(game::BlockFace::Up)},
    {"north", static_cast<int>(game::BlockFace::North)},
    {"south", static_cast<int>(game::BlockFace::South)},
    {"west", static_cast<int>(game::BlockFace::West)},
    {"east", static_cast<int>(game::BlockFace::East)},
};

int lookup(lua_State* L, int index, const Named* table, std::size_t count, const char* what) {
    const char* name = luaL_checkstring(L, index);
    for (std::size_t entry = 0; entry < count; ++entry) {
        if (std::strcmp(table[entry].name, name) == 0) {
            return table[entry].value;
        }
    }
    luaL_error(L, "unknown %s '%s'", what, name);
    return 0;
}

game::ItemKind checkItemKind(lua_State* L, int index) {
    return static_cast<game::ItemKind>(
        lookup(L, index, kItemKinds, sizeof(kItemKinds) / sizeof(kItemKinds[0]), "item"));
}

game::BlockFace checkFace(lua_State* L, int index) {
    return static_cast<game::BlockFace>(
        lookup(L, index, kFaces, sizeof(kFaces) / sizeof(kFaces[0]), "face"));
}

const char* faceName(game::BlockFace face) {
    const auto value = static_cast<int>(face);
    for (const Named& entry : kFaces) {
        if (entry.value == value) {
            return entry.name;
        }
    }
    return "up";
}

jobject requireWorld(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = game::Game::instance().world(context.env);
    if (world == nullptr) {
        luaL_error(L, "there is no world right now");
    }
    return world;
}

/* ------------------------------------------------------------ entities ---- */

const game::EntityView* resolve(const game::FrameState& state, EntityHandle& handle) {
    if (handle.cached >= 0 && handle.cached < static_cast<int>(state.entities.size()) &&
        state.entities[static_cast<std::size_t>(handle.cached)].id == handle.id) {
        return &state.entities[static_cast<std::size_t>(handle.cached)];
    }
    for (std::size_t index = 0; index < state.entities.size(); ++index) {
        if (state.entities[index].id == handle.id) {
            handle.cached = static_cast<int>(index);
            return &state.entities[index];
        }
    }
    handle.cached = -1;
    return nullptr;
}

EntityHandle& checkHandle(lua_State* L, int index) {
    return *static_cast<EntityHandle*>(luaL_checkudata(L, index, kEntityMeta));
}

const game::EntityView& requireEntity(lua_State* L, int index) {
    EntityHandle& handle = checkHandle(L, index);
    const game::EntityView* entity = resolve(requireState(L), handle);
    if (entity == nullptr) {
        luaL_error(L, "entity %d is no longer there", handle.id);
    }
    return *entity;
}

int entityAttack(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::EntityView& entity = requireEntity(L, 1);
    const game::LocalPlayerView& player = requirePlayer(L);
    game::Game::instance().attackEntity(context.env, player.handle, entity.handle);
    if (optBoolean(L, 2, true)) {
        game::Game::instance().swingHand(context.env, player.handle, false);
    }
    /* Vanilla parks its own left click for the tick after a swing; a script
     * that attacks without this doubles up with a held mouse button. */
    game::Game::instance().setAttackCooldown(context.env, 0);
    return 0;
}

int entityDistanceTo(lua_State* L) {
    const game::EntityView& entity = requireEntity(L, 1);
    Vec3 point;
    readVec3(L, 2, point);
    lua_pushnumber(L, game::hitboxDistance(point, entity.tickBoxMin(), entity.tickBoxMax()));
    return 1;
}

int entityAimPoint(lua_State* L) {
    const game::EntityView& entity = requireEntity(L, 1);
    const game::LocalPlayerView& player = requirePlayer(L);
    pushVec3(L, game::aimPoint(player.eyePos, entity, optFloat(L, 2, 0.1f)));
    return 1;
}

int entityBox(lua_State* L) {
    const game::EntityView& entity = requireEntity(L, 1);
    pushVec3(L, entity.boxMin());
    pushVec3(L, entity.boxMax());
    return 2;
}

int entityName(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::EntityView& entity = requireEntity(L, 1);
    const std::string name = game::Game::instance().entityName(context.env, entity.handle);
    lua_pushlstring(L, name.c_str(), name.size());
    return 1;
}

int entityToString(lua_State* L) {
    EntityHandle& handle = checkHandle(L, 1);
    lua_pushfstring(L, "entity(%d)", handle.id);
    return 1;
}

int entityEquals(lua_State* L) {
    lua_pushboolean(L, checkHandle(L, 1).id == checkHandle(L, 2).id ? 1 : 0);
    return 1;
}

int entityIndex(lua_State* L) {
    EntityHandle& handle = checkHandle(L, 1);
    const char* key = luaL_checkstring(L, 2);

    /* Methods first: pushing a C function with no upvalues costs nothing, so
     * there is no table to keep in step with this list. */
    if (std::strcmp(key, "attack") == 0) {
        lua_pushcfunction(L, entityAttack);
        return 1;
    }
    if (std::strcmp(key, "distanceTo") == 0) {
        lua_pushcfunction(L, entityDistanceTo);
        return 1;
    }
    if (std::strcmp(key, "aimPoint") == 0) {
        lua_pushcfunction(L, entityAimPoint);
        return 1;
    }
    if (std::strcmp(key, "box") == 0) {
        lua_pushcfunction(L, entityBox);
        return 1;
    }
    if (std::strcmp(key, "name") == 0) {
        lua_pushcfunction(L, entityName);
        return 1;
    }
    if (std::strcmp(key, "id") == 0) {
        lua_pushinteger(L, handle.id);
        return 1;
    }

    const game::EntityView* entity = resolve(requireState(L), handle);
    if (std::strcmp(key, "valid") == 0) {
        lua_pushboolean(L, entity != nullptr ? 1 : 0);
        return 1;
    }
    if (entity == nullptr) {
        lua_pushnil(L);
        return 1;
    }

    if (std::strcmp(key, "x") == 0) lua_pushnumber(L, entity->pos.x);
    else if (std::strcmp(key, "y") == 0) lua_pushnumber(L, entity->pos.y);
    else if (std::strcmp(key, "z") == 0) lua_pushnumber(L, entity->pos.z);
    else if (std::strcmp(key, "pos") == 0) pushVec3(L, entity->pos);
    else if (std::strcmp(key, "tickPos") == 0) pushVec3(L, entity->tickPos);
    else if (std::strcmp(key, "eye") == 0) pushVec3(L, entity->eyePos);
    else if (std::strcmp(key, "player") == 0) lua_pushboolean(L, entity->isPlayer ? 1 : 0);
    else if (std::strcmp(key, "living") == 0) lua_pushboolean(L, entity->living ? 1 : 0);
    else if (std::strcmp(key, "crystal") == 0) lua_pushboolean(L, entity->crystal ? 1 : 0);
    else if (std::strcmp(key, "invisible") == 0) lua_pushboolean(L, entity->invisible ? 1 : 0);
    else if (std::strcmp(key, "sneaking") == 0) lua_pushboolean(L, entity->sneaking ? 1 : 0);
    else if (std::strcmp(key, "onGround") == 0) lua_pushboolean(L, entity->onGround ? 1 : 0);
    else if (std::strcmp(key, "blocking") == 0) lua_pushboolean(L, entity->blocking ? 1 : 0);
    else if (std::strcmp(key, "width") == 0) lua_pushnumber(L, entity->width);
    else if (std::strcmp(key, "height") == 0) lua_pushnumber(L, entity->height);
    else if (std::strcmp(key, "yaw") == 0) lua_pushnumber(L, entity->yaw);
    else if (std::strcmp(key, "pitch") == 0) lua_pushnumber(L, entity->pitch);
    else if (std::strcmp(key, "bodyYaw") == 0) lua_pushnumber(L, entity->bodyYaw);
    else if (std::strcmp(key, "health") == 0) lua_pushnumber(L, entity->health);
    else if (std::strcmp(key, "maxHealth") == 0) lua_pushnumber(L, entity->maxHealth);
    else if (std::strcmp(key, "absorption") == 0) lua_pushnumber(L, entity->absorption);
    else if (std::strcmp(key, "armor") == 0) lua_pushinteger(L, entity->armor);
    else if (std::strcmp(key, "hurtTime") == 0) lua_pushinteger(L, entity->hurtTime);
    else if (std::strcmp(key, "distance") == 0) lua_pushnumber(L, entity->distance);
    else lua_pushnil(L);
    return 1;
}

game::TargetFilter readFilter(lua_State* L, int index) {
    game::TargetFilter filter;
    filter.range = 4.0f;
    if (!lua_istable(L, index)) {
        return filter;
    }
    filter.range = fieldFloat(L, index, "range", filter.range);
    filter.players = fieldBoolean(L, index, "players", filter.players);
    filter.mobs = fieldBoolean(L, index, "mobs", filter.mobs);
    filter.invisibles = fieldBoolean(L, index, "invisibles", filter.invisibles);
    filter.dead = fieldBoolean(L, index, "dead", filter.dead);
    return filter;
}

int entitiesAll(lua_State* L) {
    const game::FrameState& state = requireState(L);
    const bool filtered = lua_istable(L, 1);
    const game::TargetFilter filter = readFilter(L, 1);
    const bool livingOnly = filtered && fieldBoolean(L, 1, "living", true);

    lua_newtable(L);
    int count = 0;
    for (const game::EntityView& entity : state.entities) {
        if (filtered) {
            if (livingOnly && !entity.living) continue;
            if (entity.isPlayer ? !filter.players : !filter.mobs) continue;
            if (!filter.invisibles && entity.invisible) continue;
            if (!filter.dead && entity.living && entity.health <= 0.0f) continue;
            if (entity.distance > static_cast<double>(filter.range)) continue;
        }
        pushEntity(L, entity.id);
        lua_rawseti(L, -2, ++count);
    }
    return 1;
}

int entitiesTarget(lua_State* L) {
    const game::FrameState& state = requireState(L);
    const game::TargetFilter filter = readFilter(L, 1);
    game::TargetSort sort = game::TargetSort::Distance;
    if (lua_isstring(L, 2)) {
        const std::string name = lua_tostring(L, 2);
        if (name == "health") sort = game::TargetSort::Health;
        else if (name == "angle") sort = game::TargetSort::Angle;
        else if (name == "hurttime" || name == "hurtTime") sort = game::TargetSort::HurtTime;
    }
    const game::EntityView* target = game::selectTarget(state, filter, sort);
    if (target == nullptr) {
        lua_pushnil(L);
        return 1;
    }
    pushEntity(L, target->id);
    return 1;
}

int entitiesById(lua_State* L) {
    const game::FrameState& state = requireState(L);
    const auto id = static_cast<int>(luaL_checkinteger(L, 1));
    for (const game::EntityView& entity : state.entities) {
        if (entity.id == id) {
            pushEntity(L, id);
            return 1;
        }
    }
    lua_pushnil(L);
    return 1;
}

int entitiesCount(lua_State* L) {
    lua_pushinteger(L, static_cast<lua_Integer>(requireState(L).entities.size()));
    return 1;
}

/* -------------------------------------------------------------- player ---- */

int playerIndex(lua_State* L) {
    const game::FrameState& state = requireState(L);
    const game::LocalPlayerView& player = state.player;
    const char* key = luaL_checkstring(L, 2);

    if (std::strcmp(key, "valid") == 0) {
        lua_pushboolean(L, player.valid ? 1 : 0);
        return 1;
    }
    if (!player.valid) {
        lua_pushnil(L);
        return 1;
    }

    if (std::strcmp(key, "x") == 0) lua_pushnumber(L, player.pos.x);
    else if (std::strcmp(key, "y") == 0) lua_pushnumber(L, player.pos.y);
    else if (std::strcmp(key, "z") == 0) lua_pushnumber(L, player.pos.z);
    else if (std::strcmp(key, "pos") == 0) pushVec3(L, player.pos);
    else if (std::strcmp(key, "eye") == 0) pushVec3(L, player.eyePos);
    else if (std::strcmp(key, "velocity") == 0) pushVec3(L, player.velocity);
    else if (std::strcmp(key, "speed") == 0) lua_pushnumber(L, player.velocity.horizontalLength());
    else if (std::strcmp(key, "yaw") == 0) lua_pushnumber(L, player.yaw);
    else if (std::strcmp(key, "pitch") == 0) lua_pushnumber(L, player.pitch);
    else if (std::strcmp(key, "lastYaw") == 0) lua_pushnumber(L, player.lastYaw);
    else if (std::strcmp(key, "lastPitch") == 0) lua_pushnumber(L, player.lastPitch);
    else if (std::strcmp(key, "eyeHeight") == 0) lua_pushnumber(L, player.eyeHeight);
    else if (std::strcmp(key, "health") == 0) lua_pushnumber(L, player.health);
    else if (std::strcmp(key, "maxHealth") == 0) lua_pushnumber(L, player.maxHealth);
    else if (std::strcmp(key, "armor") == 0) lua_pushinteger(L, player.armor);
    else if (std::strcmp(key, "onGround") == 0) lua_pushboolean(L, player.onGround ? 1 : 0);
    else if (std::strcmp(key, "sneaking") == 0) lua_pushboolean(L, player.sneaking ? 1 : 0);
    else if (std::strcmp(key, "sprinting") == 0) lua_pushboolean(L, player.sprinting ? 1 : 0);
    else if (std::strcmp(key, "jumping") == 0) lua_pushboolean(L, player.jumping ? 1 : 0);
    else if (std::strcmp(key, "usingItem") == 0) lua_pushboolean(L, player.usingItem ? 1 : 0);
    else if (std::strcmp(key, "fallDistance") == 0) lua_pushnumber(L, player.fallDistance);
    else if (std::strcmp(key, "cooldown") == 0) lua_pushnumber(L, player.attackCooldown);
    else if (std::strcmp(key, "age") == 0) lua_pushinteger(L, player.age);
    else lua_pushnil(L);
    return 1;
}

/* ----------------------------------------------------------- inventory ---- */

int inventoryHolding(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    const game::ItemKind kind = checkItemKind(L, 1);
    lua_pushboolean(L, game::Game::instance().holdingItem(context.env, player.handle, kind,
                                                          optBoolean(L, 2, false))
                           ? 1
                           : 0);
    return 1;
}

int inventoryFind(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    const game::ItemKind kind = checkItemKind(L, 1);
    const int from = static_cast<int>(luaL_optinteger(L, 2, 0));
    const int to = static_cast<int>(luaL_optinteger(L, 3, 35));
    const int slot = game::Game::instance().findSlot(context.env, player.handle, kind, from, to);
    if (slot < 0) {
        lua_pushnil(L);
    } else {
        lua_pushinteger(L, slot);
    }
    return 1;
}

int inventorySlot(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    lua_pushinteger(L, game::Game::instance().selectedSlot(context.env, player.handle));
    return 1;
}

int inventorySelect(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    const int slot = static_cast<int>(luaL_checkinteger(L, 1));
    if (slot < 0 || slot > 8) {
        luaL_error(L, "hotbar slots are 0 to 8, not %d", slot);
    }
    game::Game::instance().setSelectedSlot(context.env, player.handle, slot);
    return 0;
}

int inventorySwap(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    const int slot = static_cast<int>(luaL_checkinteger(L, 1));
    const int hotbar = static_cast<int>(luaL_optinteger(L, 2, -1));
    lua_pushboolean(
        L, game::Game::instance().swapSlot(context.env, player.handle, slot, hotbar) ? 1 : 0);
    return 1;
}

int inventoryEmptyHand(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    lua_pushboolean(
        L, game::Game::instance().handEmpty(context.env, player.handle, optBoolean(L, 1, false))
               ? 1
               : 0);
    return 1;
}

int inventoryHoldingBlock(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    lua_pushboolean(L, game::Game::instance().holdingBlock(context.env, player.handle) ? 1 : 0);
    return 1;
}

int inventoryOpen(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    game::Game::instance().openInventory(context.env, player.handle);
    return 0;
}

int inventoryIsOpen(lua_State* L) {
    modules::Context& context = requireContext(L);
    lua_pushboolean(L, game::Game::instance().inventoryOpen(context.env) ? 1 : 0);
    return 1;
}

game::Game::StackView stackAt(lua_State* L, int slot) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    if (slot < 0 || slot > 40) {
        luaL_error(L, "slot %d is outside the inventory", slot);
    }
    return game::Game::instance().stack(context.env, player.handle, slot);
}

void pushStack(lua_State* L, const game::Game::StackView& stack, int slot) {
    lua_newtable(L);
    setInteger(L, "slot", slot);
    setString(L, "id", stack.id);
    setInteger(L, "count", stack.count);
    setInteger(L, "damage", stack.damage);
    setInteger(L, "maxDamage", stack.maxDamage);
    /* 1 is untouched, 0 is about to break. A stack that cannot take damage
     * reports 1 rather than nothing, so a script can compare without a nil
     * check every time. */
    setNumber(L, "durability",
              stack.maxDamage > 0
                  ? clamp(1.0 - static_cast<double>(stack.damage) / stack.maxDamage, 0.0, 1.0)
                  : 1.0);
}

int inventoryStack(lua_State* L) {
    const int slot = static_cast<int>(luaL_checkinteger(L, 1));
    const game::Game::StackView stack = stackAt(L, slot);
    if (!stack.present) {
        lua_pushnil(L);
        return 1;
    }
    pushStack(L, stack, slot);
    return 1;
}

int inventoryId(lua_State* L) {
    const game::Game::StackView stack = stackAt(L, static_cast<int>(luaL_checkinteger(L, 1)));
    if (!stack.present) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushlstring(L, stack.id.c_str(), stack.id.size());
    return 1;
}

int inventoryCount(lua_State* L) {
    const game::Game::StackView stack = stackAt(L, static_cast<int>(luaL_checkinteger(L, 1)));
    lua_pushinteger(L, stack.present ? stack.count : 0);
    return 1;
}

int inventoryDurability(lua_State* L) {
    const game::Game::StackView stack = stackAt(L, static_cast<int>(luaL_checkinteger(L, 1)));
    if (!stack.present || stack.maxDamage <= 0) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushnumber(L, clamp(1.0 - static_cast<double>(stack.damage) / stack.maxDamage, 0.0, 1.0));
    return 1;
}

/* Slot ranges by the names the game uses for them. */
bool slotRange(const std::string& where, int& from, int& to) {
    if (where.empty() || where == "all") {
        from = 0;
        to = 40;
    } else if (where == "hotbar") {
        from = 0;
        to = 8;
    } else if (where == "main") {
        from = 9;
        to = 35;
    } else if (where == "armor" || where == "armour") {
        from = 36;
        to = 39;
    } else if (where == "offhand") {
        from = 40;
        to = 40;
    } else {
        return false;
    }
    return true;
}

int inventoryItems(lua_State* L) {
    int from = 0;
    int to = 40;
    if (lua_isstring(L, 1)) {
        if (!slotRange(lua_tostring(L, 1), from, to)) {
            luaL_error(L, "unknown part of the inventory '%s'", lua_tostring(L, 1));
        }
    } else if (lua_isnumber(L, 1)) {
        from = static_cast<int>(lua_tointeger(L, 1));
        to = static_cast<int>(luaL_optinteger(L, 2, from));
    }

    lua_newtable(L);
    int index = 1;
    for (int slot = from; slot <= to; ++slot) {
        const game::Game::StackView stack = stackAt(L, slot);
        if (!stack.present) {
            continue;
        }
        pushStack(L, stack, slot);
        lua_rawseti(L, -2, index++);
    }
    return 1;
}

int inventoryFindItem(lua_State* L) {
    const std::string wanted = luaL_checkstring(L, 1);
    int from = 0;
    int to = 40;
    if (lua_isstring(L, 2)) {
        slotRange(lua_tostring(L, 2), from, to);
    } else if (lua_isnumber(L, 2)) {
        from = static_cast<int>(lua_tointeger(L, 2));
        to = static_cast<int>(luaL_optinteger(L, 3, 40));
    }
    for (int slot = from; slot <= to; ++slot) {
        const game::Game::StackView stack = stackAt(L, slot);
        if (stack.present && stack.id == wanted) {
            lua_pushinteger(L, slot);
            return 1;
        }
    }
    lua_pushnil(L);
    return 1;
}

int inventoryTotal(lua_State* L) {
    const std::string wanted = luaL_checkstring(L, 1);
    int from = 0;
    int to = 40;
    if (lua_isstring(L, 2)) {
        slotRange(lua_tostring(L, 2), from, to);
    }
    int total = 0;
    for (int slot = from; slot <= to; ++slot) {
        const game::Game::StackView stack = stackAt(L, slot);
        if (stack.present && stack.id == wanted) {
            total += stack.count;
        }
    }
    lua_pushinteger(L, total);
    return 1;
}

int inventoryHold(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    const std::string wanted = luaL_checkstring(L, 1);
    for (int slot = 0; slot <= 8; ++slot) {
        const game::Game::StackView stack =
            game::Game::instance().stack(context.env, player.handle, slot);
        if (stack.present && stack.id == wanted) {
            game::Game::instance().setSelectedSlot(context.env, player.handle, slot);
            lua_pushinteger(L, slot);
            return 1;
        }
    }
    lua_pushnil(L);
    return 1;
}

/* ------------------------------------------------------------- effects ---- */

void pushEffects(lua_State* L, const std::vector<game::Game::EffectView>& effects) {
    lua_newtable(L);
    int index = 1;
    for (const game::Game::EffectView& effect : effects) {
        lua_newtable(L);
        setString(L, "id", effect.id);
        /* Amplifier zero is level one, the way the game writes it on screen. */
        setInteger(L, "level", effect.amplifier + 1);
        setInteger(L, "amplifier", effect.amplifier);
        setInteger(L, "duration", effect.duration);
        setNumber(L, "seconds", effect.duration / 20.0);
        lua_rawseti(L, -2, index++);
    }
}

std::vector<game::Game::EffectView> effectsOf(lua_State* L, int index) {
    modules::Context& context = requireContext(L);
    if (lua_isnoneornil(L, index)) {
        const game::LocalPlayerView& player = requirePlayer(L);
        return game::Game::instance().statusEffects(context.env, player.handle);
    }
    const game::EntityView& entity = requireEntity(L, index);
    return game::Game::instance().statusEffects(context.env, entity.handle);
}

int effectsList(lua_State* L) {
    pushEffects(L, effectsOf(L, 1));
    return 1;
}

int effectsGet(lua_State* L) {
    const std::string wanted = luaL_checkstring(L, 1);
    for (const game::Game::EffectView& effect : effectsOf(L, 2)) {
        if (effect.id != wanted) {
            continue;
        }
        lua_newtable(L);
        setString(L, "id", effect.id);
        setInteger(L, "level", effect.amplifier + 1);
        setInteger(L, "amplifier", effect.amplifier);
        setInteger(L, "duration", effect.duration);
        setNumber(L, "seconds", effect.duration / 20.0);
        return 1;
    }
    lua_pushnil(L);
    return 1;
}

int effectsHas(lua_State* L) {
    const std::string wanted = luaL_checkstring(L, 1);
    bool found = false;
    for (const game::Game::EffectView& effect : effectsOf(L, 2)) {
        if (effect.id == wanted) {
            found = true;
            break;
        }
    }
    lua_pushboolean(L, found ? 1 : 0);
    return 1;
}

int effectsLevel(lua_State* L) {
    const std::string wanted = luaL_checkstring(L, 1);
    for (const game::Game::EffectView& effect : effectsOf(L, 2)) {
        if (effect.id == wanted) {
            lua_pushinteger(L, effect.amplifier + 1);
            return 1;
        }
    }
    lua_pushinteger(L, 0);
    return 1;
}

int effectsDuration(lua_State* L) {
    const std::string wanted = luaL_checkstring(L, 1);
    for (const game::Game::EffectView& effect : effectsOf(L, 2)) {
        if (effect.id == wanted) {
            lua_pushinteger(L, effect.duration);
            return 1;
        }
    }
    lua_pushinteger(L, 0);
    return 1;
}

/* --------------------------------------------------------------- world ---- */

int worldIsAir(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    const int x = static_cast<int>(luaL_checkinteger(L, 1));
    const int y = static_cast<int>(luaL_checkinteger(L, 2));
    const int z = static_cast<int>(luaL_checkinteger(L, 3));
    const bool air = game::Game::instance().isBlockAir(context.env, world, x, y, z);
    context.env->DeleteLocalRef(world);
    lua_pushboolean(L, air ? 1 : 0);
    return 1;
}

int worldIsReplaceable(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    const int x = static_cast<int>(luaL_checkinteger(L, 1));
    const int y = static_cast<int>(luaL_checkinteger(L, 2));
    const int z = static_cast<int>(luaL_checkinteger(L, 3));
    const bool replaceable = game::Game::instance().isBlockReplaceable(context.env, world, x, y, z);
    context.env->DeleteLocalRef(world);
    lua_pushboolean(L, replaceable ? 1 : 0);
    return 1;
}

int worldIsBlock(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    const int x = static_cast<int>(luaL_checkinteger(L, 1));
    const int y = static_cast<int>(luaL_checkinteger(L, 2));
    const int z = static_cast<int>(luaL_checkinteger(L, 3));
    const auto kind = static_cast<game::BlockKind>(
        lookup(L, 4, kBlockKinds, sizeof(kBlockKinds) / sizeof(kBlockKinds[0]), "block"));
    const bool matches = game::Game::instance().blockIs(context.env, world, x, y, z, kind);
    context.env->DeleteLocalRef(world);
    lua_pushboolean(L, matches ? 1 : 0);
    return 1;
}

int worldAnchorCharges(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    const int x = static_cast<int>(luaL_checkinteger(L, 1));
    const int y = static_cast<int>(luaL_checkinteger(L, 2));
    const int z = static_cast<int>(luaL_checkinteger(L, 3));
    const int charges = game::Game::instance().anchorCharges(context.env, world, x, y, z);
    context.env->DeleteLocalRef(world);
    lua_pushinteger(L, charges);
    return 1;
}

int worldBoxEmpty(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    Vec3 minimum;
    Vec3 maximum;
    const int next = readVec3(L, 1, minimum);
    readVec3(L, next, maximum);
    const bool empty = game::Game::instance().boxEmpty(context.env, world, minimum, maximum);
    context.env->DeleteLocalRef(world);
    lua_pushboolean(L, empty ? 1 : 0);
    return 1;
}

int worldRaycast(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    Vec3 origin;
    Vec3 direction;
    int next = readVec3(L, 1, origin);
    next = readVec3(L, next, direction);
    const double reach = luaL_optnumber(L, next, 5.0);
    const game::BlockHit hit =
        game::raycastBlocks(context.env, world, origin, direction, reach);
    context.env->DeleteLocalRef(world);

    if (!hit.hit) {
        lua_pushnil(L);
        return 1;
    }
    lua_newtable(L);
    setInteger(L, "x", hit.x);
    setInteger(L, "y", hit.y);
    setInteger(L, "z", hit.z);
    setString(L, "face", faceName(hit.face));
    setNumber(L, "distance", hit.distance);
    pushVec3(L, hit.point);
    lua_setfield(L, -2, "point");
    return 1;
}

int worldCrosshair(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::CrosshairTarget target = game::Game::instance().crosshair(context.env);
    lua_newtable(L);
    switch (target.kind) {
        case game::CrosshairTarget::Kind::Block: setString(L, "kind", "block"); break;
        case game::CrosshairTarget::Kind::Entity: setString(L, "kind", "entity"); break;
        case game::CrosshairTarget::Kind::Miss: setString(L, "kind", "miss"); break;
    }
    setInteger(L, "x", target.x);
    setInteger(L, "y", target.y);
    setInteger(L, "z", target.z);
    setString(L, "face", faceName(target.face));
    setInteger(L, "entityId", target.entityId);
    setBoolean(L, "crystal", target.crystal);
    pushVec3(L, target.point);
    lua_setfield(L, -2, "point");
    return 1;
}

int worldTime(lua_State* L) {
    lua_pushinteger(L, requireState(L).worldTime);
    return 1;
}

int worldBlock(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    const int x = static_cast<int>(luaL_checkinteger(L, 1));
    const int y = static_cast<int>(luaL_checkinteger(L, 2));
    const int z = static_cast<int>(luaL_checkinteger(L, 3));
    const std::string id = game::Game::instance().blockId(context.env, world, x, y, z);
    context.env->DeleteLocalRef(world);
    lua_pushlstring(L, id.c_str(), id.size());
    return 1;
}

int worldIsSolid(lua_State* L) {
    modules::Context& context = requireContext(L);
    jobject world = requireWorld(L);
    const int x = static_cast<int>(luaL_checkinteger(L, 1));
    const int y = static_cast<int>(luaL_checkinteger(L, 2));
    const int z = static_cast<int>(luaL_checkinteger(L, 3));
    const bool solid = !game::Game::instance().isBlockReplaceable(context.env, world, x, y, z);
    context.env->DeleteLocalRef(world);
    lua_pushboolean(L, solid ? 1 : 0);
    return 1;
}

/* Everything the frame captured inside a box, which is how a script asks about
 * a region rather than about a radius. */
int worldEntitiesIn(lua_State* L) {
    const game::FrameState& state = requireState(L);
    Vec3 minimum;
    Vec3 maximum;
    const int next = readVec3(L, 1, minimum);
    readVec3(L, next, maximum);
    if (minimum.x > maximum.x) std::swap(minimum.x, maximum.x);
    if (minimum.y > maximum.y) std::swap(minimum.y, maximum.y);
    if (minimum.z > maximum.z) std::swap(minimum.z, maximum.z);

    lua_newtable(L);
    int count = 0;
    for (const game::EntityView& entity : state.entities) {
        const Vec3& position = entity.tickPos;
        if (position.x < minimum.x || position.x > maximum.x || position.y < minimum.y ||
            position.y > maximum.y || position.z < minimum.z || position.z > maximum.z) {
            continue;
        }
        pushEntity(L, entity.id);
        lua_rawseti(L, -2, ++count);
    }
    return 1;
}

/* ------------------------------------------------------------ rotation ---- */

int rotationSet(lua_State* L) {
    requireContext(L);
    game::RotationTarget target;
    int options = 3;
    if (lua_istable(L, 1)) {
        target.yaw = fieldFloat(L, 1, "yaw", 0.0f);
        target.pitch = fieldFloat(L, 1, "pitch", 0.0f);
        options = 1;
    } else {
        target.yaw = checkFloat(L, 1);
        target.pitch = checkFloat(L, 2);
    }
    if (lua_istable(L, options)) {
        target.speed = fieldFloat(L, options, "speed", target.speed);
        target.smoothing = fieldFloat(L, options, "smoothing", target.smoothing);
        target.jitter = fieldInt(L, options, "jitter", target.jitter);
        target.silent = fieldBoolean(L, options, "silent", target.silent);
        target.priority = fieldInt(L, options, "priority", target.priority);
    }
    target.pitch = clamp(target.pitch, -90.0f, 90.0f);
    game::RotationEngine::instance().submit(target);
    return 0;
}

int rotationAimAt(lua_State* L) {
    const game::LocalPlayerView& player = requirePlayer(L);
    Vec3 point;
    readVec3(L, 1, point);
    const game::RotationTarget target =
        game::RotationEngine::aimAt(player.eyePos, point, player.yaw);
    lua_pushnumber(L, target.yaw);
    lua_pushnumber(L, target.pitch);
    return 2;
}

int rotationSent(lua_State* L) {
    (void)L;
    lua_pushnumber(L, game::RotationEngine::instance().sentYaw());
    lua_pushnumber(L, game::RotationEngine::instance().sentPitch());
    return 2;
}

int rotationCurrent(lua_State* L) {
    (void)L;
    lua_pushnumber(L, game::RotationEngine::instance().yaw());
    lua_pushnumber(L, game::RotationEngine::instance().pitch());
    return 2;
}

int rotationStep(lua_State* L) {
    lua_pushnumber(L, game::RotationEngine::instance().step());
    return 1;
}

int rotationEngaged(lua_State* L) {
    lua_pushboolean(L, game::RotationEngine::instance().engaged() ? 1 : 0);
    return 1;
}

int rotationLookVector(lua_State* L) {
    pushVec3(L, game::lookVector(checkFloat(L, 1), checkFloat(L, 2)));
    return 1;
}

/* The camera, which is not the same thing as what the engine is steering: with
 * a silent rotation in flight the two are different on purpose. */
int rotationPlayer(lua_State* L) {
    const game::LocalPlayerView& player = requirePlayer(L);
    lua_pushnumber(L, player.yaw);
    lua_pushnumber(L, player.pitch);
    return 2;
}

int rotationIdling(lua_State* L) {
    lua_pushboolean(L, game::RotationEngine::instance().engaged() ? 0 : 1);
    return 1;
}

int rotationReset(lua_State* L) {
    (void)L;
    game::RotationEngine::instance().reset();
    return 0;
}

/* -------------------------------------------------------------- action ---- */

int actionSwing(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    game::Game::instance().swingHand(context.env, player.handle, optBoolean(L, 1, false));
    return 0;
}

int actionPlace(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    const int x = static_cast<int>(luaL_checkinteger(L, 1));
    const int y = static_cast<int>(luaL_checkinteger(L, 2));
    const int z = static_cast<int>(luaL_checkinteger(L, 3));
    const game::BlockFace face = checkFace(L, 4);
    Vec3 hit;
    const int next = readVec3(L, 5, hit);
    const bool swing = optBoolean(L, next, false);
    lua_pushboolean(L, game::Game::instance().placeBlock(context.env, player.handle, x, y, z, face,
                                                         hit, swing)
                           ? 1
                           : 0);
    return 1;
}

int actionBreak(lua_State* L) {
    modules::Context& context = requireContext(L);
    game::Game::instance().attackBlock(
        context.env, static_cast<int>(luaL_checkinteger(L, 1)),
        static_cast<int>(luaL_checkinteger(L, 2)), static_cast<int>(luaL_checkinteger(L, 3)),
        checkFace(L, 4), optBoolean(L, 5, false));
    return 0;
}

int actionJump(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    game::Game::instance().jump(context.env, player.handle);
    return 0;
}

int actionSprint(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    game::Game::instance().setSprinting(context.env, player.handle, optBoolean(L, 1, true));
    return 0;
}

int actionClick(lua_State* L) {
    modules::Context& context = requireContext(L);
    /* Queued on the binding rather than sent straight out: an interaction the
     * payload sends itself would land after the tick's movement packet, which
     * is exactly the ordering an anticheat looks for. */
    game::Game::instance().queueClick(context.env, optBoolean(L, 1, false));
    return 0;
}

int actionHold(lua_State* L) {
    modules::Context& context = requireContext(L);
    game::Game::instance().pressKey(context.env, optBoolean(L, 1, false), optBoolean(L, 2, true));
    return 0;
}

int actionAttackCooldown(lua_State* L) {
    modules::Context& context = requireContext(L);
    game::Game::instance().setAttackCooldown(context.env,
                                             static_cast<int>(luaL_optinteger(L, 1, 0)));
    return 0;
}

int actionUseCooldown(lua_State* L) {
    modules::Context& context = requireContext(L);
    game::Game::instance().setItemUseCooldown(context.env,
                                              static_cast<int>(luaL_optinteger(L, 1, 0)));
    return 0;
}

int actionCloseScreen(lua_State* L) {
    modules::Context& context = requireContext(L);
    game::Game::instance().closeScreen(context.env);
    return 0;
}

int actionKey(lua_State* L) {
    modules::Context& context = requireContext(L);
    const char* name = luaL_checkstring(L, 1);
    const bool pressed = optBoolean(L, 2, true);
    if (!game::Game::instance().setBinding(context.env, name, pressed)) {
        luaL_error(L,
                   "'%s' is not a movement binding: forward, back, left, right, jump, sneak, "
                   "sprint, attack or use",
                   name);
    }
    return 0;
}

int actionVelocity(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    Vec3 velocity;
    readVec3(L, 1, velocity);
    game::Game::instance().setVelocity(context.env, player.handle, velocity);
    return 0;
}

/* Writes the camera outright. Nothing about this is on the mouse grid, so it is
 * for a script that wants to point the view somewhere the player would have -
 * a freelook, a screenshot pose - and never for something the server is going
 * to be asked to believe. cold.rotation.set is the one for that. */
int actionLook(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    game::Game::instance().setRotation(context.env, player.handle, checkFloat(L, 1),
                                       clamp(checkFloat(L, 2), -90.0f, 90.0f),
                                       optBoolean(L, 3, false));
    return 0;
}

/* Whether an interaction sent right now would land where vanilla puts it. The
 * client refuses the ones that would not, so a script that cares can check
 * rather than wonder why a swing did nothing. */
int actionCanInteract(lua_State* L) {
    requireContext(L);
    lua_pushboolean(L, game::Game::instance().interactionsAllowed() ? 1 : 0);
    return 1;
}

int actionSound(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    lua_pushboolean(L, game::Game::instance().playSound(context.env, player.handle,
                                                        luaL_checkstring(L, 1),
                                                        optFloat(L, 2, 1.0f), optFloat(L, 3, 1.0f))
                           ? 1
                           : 0);
    return 1;
}

int actionRespawn(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    lua_pushboolean(L, game::Game::instance().respawn(context.env, player.handle) ? 1 : 0);
    return 1;
}

/* --------------------------------------------------------------- input ---- */

int inputKeyHeld(lua_State* L) {
    lua_pushboolean(L, hooks::input().keyHeld(static_cast<int>(luaL_checkinteger(L, 1))) ? 1 : 0);
    return 1;
}

int inputBindHeld(lua_State* L) {
    lua_pushboolean(L, hooks::input().bindHeld(static_cast<int>(luaL_checkinteger(L, 1))) ? 1 : 0);
    return 1;
}

int inputAttackHeld(lua_State* L) {
    lua_pushboolean(L, hooks::input().attackHeld() ? 1 : 0);
    return 1;
}

int inputUseHeld(lua_State* L) {
    lua_pushboolean(L, hooks::input().useHeld() ? 1 : 0);
    return 1;
}

int inputCursor(lua_State* L) {
    lua_pushnumber(L, hooks::input().cursorX());
    lua_pushnumber(L, hooks::input().cursorY());
    return 2;
}

const luaL_Reg kEntityFunctions[] = {
    {"all", entitiesAll},   {"target", entitiesTarget}, {"byId", entitiesById},
    {"count", entitiesCount}, {nullptr, nullptr},
};

const luaL_Reg kInventoryFunctions[] = {
    {"holding", inventoryHolding},
    {"find", inventoryFind},
    {"slot", inventorySlot},
    {"select", inventorySelect},
    {"swap", inventorySwap},
    {"handEmpty", inventoryEmptyHand},
    {"holdingBlock", inventoryHoldingBlock},
    {"open", inventoryOpen},
    {"isOpen", inventoryIsOpen},
    {"stack", inventoryStack},
    {"id", inventoryId},
    {"count", inventoryCount},
    {"durability", inventoryDurability},
    {"items", inventoryItems},
    {"findItem", inventoryFindItem},
    {"total", inventoryTotal},
    {"hold", inventoryHold},
    {nullptr, nullptr},
};

const luaL_Reg kEffectFunctions[] = {
    {"list", effectsList},         {"get", effectsGet},   {"has", effectsHas},
    {"level", effectsLevel},       {"duration", effectsDuration}, {nullptr, nullptr},
};

const luaL_Reg kWorldFunctions[] = {
    {"isAir", worldIsAir},         {"isReplaceable", worldIsReplaceable},
    {"isSolid", worldIsSolid},     {"is", worldIsBlock},
    {"block", worldBlock},         {"anchorCharges", worldAnchorCharges},
    {"boxEmpty", worldBoxEmpty},   {"raycast", worldRaycast},
    {"crosshair", worldCrosshair}, {"time", worldTime},
    {"entitiesIn", worldEntitiesIn}, {nullptr, nullptr},
};

const luaL_Reg kRotationFunctions[] = {
    {"set", rotationSet},
    {"aimAt", rotationAimAt},
    {"toPoint", rotationAimAt},
    {"sent", rotationSent},
    {"current", rotationCurrent},
    {"player", rotationPlayer},
    {"step", rotationStep},
    {"engaged", rotationEngaged},
    {"idling", rotationIdling},
    {"reset", rotationReset},
    {"lookVector", rotationLookVector},
    {nullptr, nullptr},
};

const luaL_Reg kActionFunctions[] = {
    {"swing", actionSwing},
    {"place", actionPlace},
    {"breakBlock", actionBreak},
    {"jump", actionJump},
    {"sprint", actionSprint},
    {"click", actionClick},
    {"hold", actionHold},
    {"key", actionKey},
    {"setVelocity", actionVelocity},
    {"look", actionLook},
    {"sound", actionSound},
    {"canInteract", actionCanInteract},
    {"attackCooldown", actionAttackCooldown},
    {"useCooldown", actionUseCooldown},
    {"closeScreen", actionCloseScreen},
    {"respawn", actionRespawn},
    {nullptr, nullptr},
};

const luaL_Reg kInputFunctions[] = {
    {"keyHeld", inputKeyHeld},       {"bindHeld", inputBindHeld}, {"attackHeld", inputAttackHeld},
    {"useHeld", inputUseHeld},       {"cursor", inputCursor},     {nullptr, nullptr},
};

void installTable(lua_State* L, const char* name, const luaL_Reg* functions) {
    lua_newtable(L);
    luaL_setfuncs(L, functions, 0);
    lua_setfield(L, -2, name);
}

}  // namespace

void pushEntity(lua_State* L, int entityId) {
    auto* handle = static_cast<EntityHandle*>(lua_newuserdatauv(L, sizeof(EntityHandle), 0));
    handle->id = entityId;
    handle->cached = -1;
    luaL_getmetatable(L, kEntityMeta);
    lua_setmetatable(L, -2);
}

void installGame(lua_State* L) {
    luaL_newmetatable(L, kEntityMeta);
    lua_pushcfunction(L, entityIndex);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, entityToString);
    lua_setfield(L, -2, "__tostring");
    lua_pushcfunction(L, entityEquals);
    lua_setfield(L, -2, "__eq");
    lua_pushstring(L, "entity");
    lua_setfield(L, -2, "__name");
    lua_pop(L, 1);

    installTable(L, "entities", kEntityFunctions);
    installTable(L, "inventory", kInventoryFunctions);
    installTable(L, "effects", kEffectFunctions);
    installTable(L, "world", kWorldFunctions);
    installTable(L, "rotation", kRotationFunctions);
    installTable(L, "action", kActionFunctions);
    installTable(L, "input", kInputFunctions);

    /* cold.player reads the frame in flight on every access, so a script that
     * keeps a reference to it keeps a live view rather than a stale copy. */
    lua_newtable(L);
    lua_newtable(L);
    lua_pushcfunction(L, playerIndex);
    lua_setfield(L, -2, "__index");
    lua_pushstring(L, "player");
    lua_setfield(L, -2, "__name");
    lua_setmetatable(L, -2);
    lua_setfield(L, -2, "player");
}

}  // namespace cold::script
