#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "core/Log.hpp"
#include "modules/ModuleManager.hpp"
#include "render/Renderer.hpp"
#include "core/Time.hpp"
#include "script/Api.hpp"
#include "script/Prelude.inl"
#include "script/Script.hpp"
#include "script/ScriptManager.hpp"
#include "ui/Client.hpp"
#include "ui/ClickGui.hpp"
#include "ui/Hud.hpp"
#include "ui/Keys.hpp"
#include "ui/Theme.hpp"

namespace cold::script {
namespace {

constexpr std::string_view kScope = "script";
constexpr const char* kIncludedTable = "cold.included";
constexpr const char* kConfigPrefix = "var.";

struct KeyName {
    const char* name;
    int code;
};

/* GLFW codes. Only the ones someone would bind or test for; a script that
 * wants an exotic key can pass the number. */
const KeyName kKeys[] = {
    {"SPACE", 32},      {"APOSTROPHE", 39}, {"COMMA", 44},     {"MINUS", 45},
    {"PERIOD", 46},     {"SLASH", 47},      {"SEMICOLON", 59}, {"EQUAL", 61},
    {"LBRACKET", 91},   {"BACKSLASH", 92},  {"RBRACKET", 93},  {"GRAVE", 96},
    {"ESCAPE", 256},    {"ENTER", 257},     {"TAB", 258},      {"BACKSPACE", 259},
    {"INSERT", 260},    {"DELETE", 261},    {"RIGHT", 262},    {"LEFT", 263},
    {"DOWN", 264},      {"UP", 265},        {"PAGEUP", 266},   {"PAGEDOWN", 267},
    {"HOME", 268},      {"END", 269},       {"CAPSLOCK", 280}, {"NUMLOCK", 282},
    {"PRINTSCREEN", 283}, {"PAUSE", 284},   {"LSHIFT", 340},   {"LCONTROL", 341},
    {"LALT", 342},      {"LSUPER", 343},    {"RSHIFT", 344},   {"RCONTROL", 345},
    {"RALT", 346},      {"RSUPER", 347},    {"MENU", 348},
};

int preludeTraceback(lua_State* L) {
    const char* message = lua_tostring(L, 1);
    luaL_traceback(L, L, message != nullptr ? message : "prelude failed", 1);
    return 1;
}

Script& owner(lua_State* L) {
    Script* script = Script::fromState(L);
    if (script == nullptr) {
        luaL_error(L, "this state has no script bound to it");
    }
    return *script;
}

/* Everything a script prints goes through one place: several values, spaces
 * between them, tostring for anything that is not already text. */
std::string joinArguments(lua_State* L, int first) {
    std::string text;
    const int top = lua_gettop(L);
    for (int index = first; index <= top; ++index) {
        if (index > first) {
            text.push_back(' ');
        }
        if (luaL_callmeta(L, index, "__tostring") != 0) {
            text += lua_tostring(L, -1) != nullptr ? lua_tostring(L, -1) : "?";
            lua_pop(L, 1);
            continue;
        }
        switch (lua_type(L, index)) {
            case LUA_TNIL: text += "nil"; break;
            case LUA_TBOOLEAN: text += lua_toboolean(L, index) != 0 ? "true" : "false"; break;
            case LUA_TNUMBER:
            case LUA_TSTRING: text += lua_tostring(L, index); break;
            default:
                text += luaL_typename(L, index);
                break;
        }
    }
    return text;
}

int coldScriptInfo(lua_State* L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    std::string author = fieldString(L, 1, "author", "");
    if (author.empty()) {
        author = fieldString(L, 1, "authors", "");
    }
    owner(L).setMetadata(fieldString(L, 1, "name", ""), fieldString(L, 1, "version", ""),
                         std::move(author), fieldString(L, 1, "description", ""));
    return 0;
}

int coldOn(lua_State* L) {
    const char* name = luaL_checkstring(L, 1);
    luaL_checktype(L, 2, LUA_TFUNCTION);
    Event event = Event::Tick;
    if (!eventFromName(name, event)) {
        luaL_error(L, "unknown event '%s'", name);
    }
    lua_pushvalue(L, 2);
    const int reference = luaL_ref(L, LUA_REGISTRYINDEX);
    owner(L).subscribe(event, reference);
    lua_pushinteger(L, reference);
    return 1;
}

int coldOff(lua_State* L) {
    const char* name = luaL_checkstring(L, 1);
    const int reference = static_cast<int>(luaL_checkinteger(L, 2));
    Event event = Event::Tick;
    if (!eventFromName(name, event)) {
        luaL_error(L, "unknown event '%s'", name);
    }
    Script& script = owner(L);
    const bool removed = script.unsubscribe(event, reference);
    if (removed) {
        luaL_unref(L, LUA_REGISTRYINDEX, reference);
    }
    lua_pushboolean(L, removed ? 1 : 0);
    return 1;
}

int coldNotify(lua_State* L) {
    const std::string text = luaL_checkstring(L, 1);
    const float seconds = optFloat(L, 2, 4.0f);
    Notice kind = Notice::Info;
    if (lua_isstring(L, 3)) {
        const std::string name = lua_tostring(L, 3);
        if (name == "good" || name == "success") kind = Notice::Good;
        else if (name == "warn" || name == "warning") kind = Notice::Warn;
        else if (name == "bad" || name == "error") kind = Notice::Bad;
    } else if (lua_istable(L, 3)) {
        if (ui::client().hasHud()) {
            ui::client().hud().notify(text, seconds, checkColour(L, 3));
        }
        return 0;
    }
    notify(text, kind, seconds);
    return 0;
}

int coldChat(lua_State* L) {
    modules::Context& context = requireContext(L);
    const std::string text = joinArguments(L, 1);
    lua_pushboolean(L, game::Game::instance().printChat(context.env, text) ? 1 : 0);
    return 1;
}

int coldSay(lua_State* L) {
    modules::Context& context = requireContext(L);
    const std::string text = luaL_checkstring(L, 1);
    lua_pushboolean(L, game::Game::instance().sendChat(context.env, text, false) ? 1 : 0);
    return 1;
}

int coldCommand(lua_State* L) {
    modules::Context& context = requireContext(L);
    std::string text = luaL_checkstring(L, 1);
    if (!text.empty() && text.front() == '/') {
        text.erase(0, 1);
    }
    lua_pushboolean(L, game::Game::instance().sendChat(context.env, text, true) ? 1 : 0);
    return 1;
}

int coldLog(lua_State* L) {
    const std::string text = joinArguments(L, 1);
    COLD_INFO(kScope, "%s: %s", owner(L).file().c_str(), text.c_str());
    return 0;
}

int coldInclude(lua_State* L) {
    std::string name = luaL_checkstring(L, 1);
    if (name.find("..") != std::string::npos || name.find(':') != std::string::npos ||
        name.find('\\') != std::string::npos || (!name.empty() && name.front() == '/')) {
        luaL_error(L, "include stays inside the scripts folder: '%s'", name.c_str());
    }
    if (name.size() < 4 || name.compare(name.size() - 4, 4, ".lua") != 0) {
        name += ".lua";
    }

    luaL_getsubtable(L, LUA_REGISTRYINDEX, kIncludedTable);
    lua_getfield(L, -1, name.c_str());
    if (!lua_isnil(L, -1)) {
        return 1;
    }
    lua_pop(L, 1);

    std::string source;
    if (!readTextFile(scriptPath(name), source)) {
        luaL_error(L, "include could not read '%s'", name.c_str());
    }
    const std::string chunk = "@" + name;
    if (luaL_loadbuffer(L, source.data(), source.size(), chunk.c_str()) != LUA_OK) {
        lua_error(L);
    }
    lua_call(L, 0, 1);
    if (lua_isnil(L, -1)) {
        lua_pop(L, 1);
        lua_pushboolean(L, 1);
    }
    lua_pushvalue(L, -1);
    lua_setfield(L, -3, name.c_str());
    return 1;
}

int coldColour(lua_State* L) {
    const auto channel = [L](int index) {
        const double value = luaL_checknumber(L, index);
        /* Written either way: 0..1 the way the renderer holds a colour, or
         * 0..255 the way a script copied off a palette will have it. */
        return static_cast<float>(value > 1.0 ? value / 255.0 : value);
    };
    Color colour;
    colour.r = channel(1);
    colour.g = channel(2);
    colour.b = channel(3);
    const double alpha = luaL_optnumber(L, 4, 1.0);
    colour.a = static_cast<float>(alpha > 1.0 ? alpha / 255.0 : alpha);
    pushColour(L, colour);
    return 1;
}

int coldRgb(lua_State* L) {
    const auto packed = static_cast<std::uint32_t>(luaL_checkinteger(L, 1) & 0xFFFFFF);
    pushColour(L, Color::rgb(packed, optFloat(L, 2, 1.0f)));
    return 1;
}

int coldRgba(lua_State* L) {
    const auto packed = static_cast<std::uint32_t>(luaL_checkinteger(L, 1) & 0xFFFFFFFF);
    pushColour(L, Color::rgba(packed));
    return 1;
}

int coldHsv(lua_State* L) {
    pushColour(L, Color::hsv(checkFloat(L, 1), optFloat(L, 2, 1.0f), optFloat(L, 3, 1.0f),
                             optFloat(L, 4, 1.0f)));
    return 1;
}

int coldAccent(lua_State* L) {
    pushColour(L, ui::theme().accent);
    return 1;
}

int coldKeyName(lua_State* L) {
    lua_pushstring(L, ui::keyName(static_cast<int>(luaL_checkinteger(L, 1))).c_str());
    return 1;
}

/* ------------------------------------------------------------- notices ---- */

int notifyCall(lua_State* L) {
    /* Reached as cold.notify("..."), so the table itself is argument one. */
    lua_remove(L, 1);
    return coldNotify(L);
}

int notifyKind(lua_State* L, Notice kind) {
    notify(luaL_checkstring(L, 1), kind, optFloat(L, 2, 4.0f));
    return 0;
}

int notifyInfo(lua_State* L) { return notifyKind(L, Notice::Info); }
int notifySuccess(lua_State* L) { return notifyKind(L, Notice::Good); }
int notifyWarn(lua_State* L) { return notifyKind(L, Notice::Warn); }
int notifyError(lua_State* L) { return notifyKind(L, Notice::Bad); }

/* The line above the hotbar, where the game puts its own one-shot messages. */
int notifyOverlay(lua_State* L) {
    modules::Context& context = requireContext(L);
    lua_pushboolean(
        L, game::Game::instance().overlayMessage(context.env, luaL_checkstring(L, 1)) ? 1 : 0);
    return 1;
}

/* --------------------------------------------------------------- theme ---- */

int themeColour(lua_State* L) {
    const std::string name = lua_isstring(L, 1) ? lua_tostring(L, 1) : "accent";
    const ui::Palette& palette = ui::theme();
    if (name == "text") pushColour(L, palette.text);
    else if (name == "textDim" || name == "second") pushColour(L, palette.textDim);
    else if (name == "textBright") pushColour(L, palette.textBright);
    else if (name == "background" || name == "panel") pushColour(L, palette.panel);
    else if (name == "surface") pushColour(L, palette.surface);
    else if (name == "outline" || name == "border") pushColour(L, palette.border);
    else if (name == "positive" || name == "good") pushColour(L, palette.positive);
    else if (name == "warning" || name == "warn") pushColour(L, palette.warning);
    else if (name == "negative" || name == "bad") pushColour(L, palette.negative);
    else if (name == "shadow") pushColour(L, palette.shadow);
    else pushColour(L, palette.accent);
    return 1;
}

int themeSetAccent(lua_State* L) {
    ui::setAccent(checkColour(L, 1));
    return 0;
}

/* Black or white, whichever can actually be read on the colour given. The
 * weights are the usual perceptual ones. */
int themeReadable(lua_State* L) {
    const Color background = checkColour(L, 1);
    const float luminance =
        0.2126f * background.r + 0.7152f * background.g + 0.0722f * background.b;
    pushColour(L, luminance > 0.55f ? Color::rgb(0x101317) : Color::rgb(0xFFFFFF));
    return 1;
}

int themeHealth(lua_State* L) {
    pushColour(L, ui::healthColour(checkFloat(L, 1), optFloat(L, 2, 20.0f)));
    return 1;
}

/* ------------------------------------------------------------- friends ---- */

constexpr const char* kFriendSection = "friends";

std::string friendKey(const char* name) {
    std::string key = name != nullptr ? name : "";
    for (char& character : key) {
        if (character >= 'A' && character <= 'Z') {
            character = static_cast<char>(character + 32);
        }
    }
    return key;
}

int friendsAdd(lua_State* L) {
    const std::string key = friendKey(luaL_checkstring(L, 1));
    if (key.empty()) {
        lua_pushboolean(L, 0);
        return 1;
    }
    manager().store().set(kFriendSection, key, luaL_checkstring(L, 1));
    manager().saveStore();
    lua_pushboolean(L, 1);
    return 1;
}

int friendsRemove(lua_State* L) {
    const std::string key = friendKey(luaL_checkstring(L, 1));
    const bool had = !manager().store().get(kFriendSection, key, "").empty();
    manager().store().set(kFriendSection, key, "");
    manager().saveStore();
    lua_pushboolean(L, had ? 1 : 0);
    return 1;
}

int friendsIs(lua_State* L) {
    const std::string key = friendKey(luaL_checkstring(L, 1));
    lua_pushboolean(L, manager().store().get(kFriendSection, key, "").empty() ? 0 : 1);
    return 1;
}

int friendsList(lua_State* L) {
    lua_newtable(L);
    int index = 1;
    for (const std::string& name : manager().store().keys(kFriendSection)) {
        const std::string display = manager().store().get(kFriendSection, name, "");
        if (display.empty()) {
            continue;
        }
        lua_pushlstring(L, display.c_str(), display.size());
        lua_rawseti(L, -2, index++);
    }
    return 1;
}

int friendsClear(lua_State* L) {
    for (const std::string& name : manager().store().keys(kFriendSection)) {
        manager().store().set(kFriendSection, name, "");
    }
    manager().saveStore();
    (void)L;
    return 0;
}

/* --------------------------------------------------------------- sound ---- */

int soundPlay(lua_State* L) {
    modules::Context& context = requireContext(L);
    const game::LocalPlayerView& player = requirePlayer(L);
    lua_pushboolean(L, game::Game::instance().playSound(context.env, player.handle,
                                                        luaL_checkstring(L, 1),
                                                        optFloat(L, 2, 1.0f), optFloat(L, 3, 1.0f))
                           ? 1
                           : 0);
    return 1;
}

/* -------------------------------------------------------------- client ---- */

int clientMessage(lua_State* L, int colour) {
    modules::Context& context = requireContext(L);
    lua_pushboolean(
        L, game::Game::instance().printChat(context.env, joinArguments(L, 1), colour) ? 1 : 0);
    return 1;
}

int clientMsg(lua_State* L) { return clientMessage(L, -1); }
int clientWarn(lua_State* L) { return clientMessage(L, 0xFFC55C); }
int clientError(lua_State* L) { return clientMessage(L, 0xFF5C6E); }

int clientServer(lua_State* L) {
    modules::Context& context = requireContext(L);
    const std::string address = game::Game::instance().serverAddress(context.env);
    if (address.empty()) {
        lua_pushnil(L);
    } else {
        lua_pushlstring(L, address.c_str(), address.size());
    }
    return 1;
}

int clientGameDirectory(lua_State* L) {
    modules::Context& context = requireContext(L);
    const std::string path = game::Game::instance().gameDirectory(context.env);
    lua_pushlstring(L, path.c_str(), path.size());
    return 1;
}

int clientFind(lua_State* L) {
    const char* moduleName = luaL_checkstring(L, 1);
    modules::Module* module = modules::modules().find(moduleName);
    if (module == nullptr) {
        lua_pushnil(L);
        return 1;
    }
    if (!lua_isstring(L, 2)) {
        pushModuleHandle(L, module->name());
        return 1;
    }
    modules::Setting* setting = module->setting(lua_tostring(L, 2));
    if (setting == nullptr) {
        lua_pushnil(L);
        return 1;
    }
    pushModuleHandle(L, module->name());
    lua_getfield(L, -1, "settings");
    lua_pushvalue(L, 2);
    lua_gettable(L, -2);
    return 1;
}

int clientFontWidth(lua_State* L) {
    std::size_t length = 0;
    const char* text = luaL_checklstring(L, 1, &length);
    lua_pushnumber(L, render::renderer().measure(render::FontRole::Medium,
                                                 std::string_view(text, length),
                                                 optFloat(L, 2, 10.0f)));
    return 1;
}

int clientFontHeight(lua_State* L) {
    lua_pushnumber(L, render::renderer().lineHeight(render::FontRole::Medium,
                                                    optFloat(L, 1, 10.0f)));
    return 1;
}

int clientIndex(lua_State* L) {
    const std::string key = luaL_checkstring(L, 2);
    ui::Client& client = ui::client();
    const game::FrameState& state = client.state();

    /* The methods live on the same table as the live properties, which is the
     * shape a script expects: client.fps beside client.msg("..."). */
    struct Method {
        const char* name;
        lua_CFunction function;
    };
    static const Method kMethods[] = {
        {"msg", clientMsg},         {"warn", clientWarn},
        {"error", clientError},     {"overlay", notifyOverlay},
        {"server", clientServer},   {"gameDir", clientGameDirectory},
        {"find", clientFind},       {"fontWidth", clientFontWidth},
        {"fontHeight", clientFontHeight},
    };
    for (const Method& method : kMethods) {
        if (key == method.name) {
            lua_pushcfunction(L, method.function);
            return 1;
        }
    }

    if (key == "name") {
        lua_pushlstring(L, ui::kClientName.data(), ui::kClientName.size());
    } else if (key == "version") {
        lua_pushlstring(L, ui::kClientVersion.data(), ui::kClientVersion.size());
    } else if (key == "fps") {
        lua_pushinteger(L, state.fps);
    } else if (key == "ping") {
        lua_pushinteger(L, state.ping);
    } else if (key == "inWorld") {
        lua_pushboolean(L, state.inWorld ? 1 : 0);
    } else if (key == "screenOpen") {
        lua_pushboolean(L, state.screenOpen ? 1 : 0);
    } else if (key == "menuOpen") {
        lua_pushboolean(L, client.gui().interactive() ? 1 : 0);
    } else if (key == "width") {
        lua_pushinteger(L, state.windowWidth);
    } else if (key == "height") {
        lua_pushinteger(L, state.windowHeight);
    } else if (key == "scaledWidth") {
        lua_pushinteger(L, state.scaledWidth);
    } else if (key == "scaledHeight") {
        lua_pushinteger(L, state.scaledHeight);
    } else if (key == "scale") {
        /* Framebuffer pixels per GUI unit, which is what a HUD element has to
         * multiply by to be the same size as the rest of the interface. */
        lua_pushnumber(L, state.scaledWidth > 0 ? static_cast<double>(state.windowWidth) /
                                                      static_cast<double>(state.scaledWidth)
                                                : 2.0);
    } else if (key == "guiScale") {
        lua_pushinteger(L, state.scaleFactor);
    } else if (key == "sensitivity") {
        lua_pushnumber(L, state.mouseSensitivity);
    } else if (key == "time") {
        lua_pushnumber(L, client.time());
    } else if (key == "delta") {
        modules::Context* context = manager().context();
        lua_pushnumber(L, context != nullptr ? context->deltaTime : 0.0f);
    } else if (key == "tick") {
        lua_pushinteger(L, client.tickCount());
    } else if (key == "frame") {
        lua_pushinteger(L, client.frameCount());
    } else if (key == "worldTime") {
        lua_pushinteger(L, state.worldTime);
    } else if (key == "overlay") {
        lua_pushnumber(L, client.overlayMicros());
    } else if (key == "tickProgress") {
        lua_pushnumber(L, state.tickProgress);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

/* ------------------------------------------------------------- config ---- */

int configGet(lua_State* L) {
    const std::string key = kConfigPrefix + std::string(luaL_checkstring(L, 1));
    const std::string section = owner(L).file();
    config::Document& store = manager().store();

    switch (lua_type(L, 2)) {
        case LUA_TNUMBER:
            lua_pushnumber(L, store.getNumber(section, key, static_cast<float>(lua_tonumber(L, 2))));
            break;
        case LUA_TBOOLEAN:
            lua_pushboolean(L, store.getBool(section, key, lua_toboolean(L, 2) != 0) ? 1 : 0);
            break;
        default: {
            const std::string fallback = lua_isstring(L, 2) ? lua_tostring(L, 2) : "";
            const std::string value = store.get(section, key, fallback);
            if (value.empty() && lua_isnoneornil(L, 2)) {
                lua_pushnil(L);
            } else {
                lua_pushlstring(L, value.c_str(), value.size());
            }
            break;
        }
    }
    return 1;
}

int configSet(lua_State* L) {
    const std::string key = kConfigPrefix + std::string(luaL_checkstring(L, 1));
    const std::string section = owner(L).file();
    config::Document& store = manager().store();

    switch (lua_type(L, 2)) {
        case LUA_TNUMBER: store.setNumber(section, key, static_cast<float>(lua_tonumber(L, 2)), 4); break;
        case LUA_TBOOLEAN: store.setBool(section, key, lua_toboolean(L, 2) != 0); break;
        case LUA_TNIL: store.set(section, key, ""); break;
        default: store.set(section, key, luaL_checkstring(L, 2)); break;
    }
    return 0;
}

int configSave(lua_State* L) {
    (void)L;
    manager().saveStore();
    return 0;
}

/* ------------------------------------------------------------ scripts ---- */

int scriptsList(lua_State* L) {
    lua_newtable(L);
    int index = 1;
    for (const std::unique_ptr<Script>& script : manager().scripts()) {
        lua_newtable(L);
        setString(L, "file", script->file());
        setString(L, "name", script->name());
        setString(L, "version", script->version());
        setString(L, "author", script->author());
        setString(L, "description", script->description());
        setString(L, "error", script->error());
        setBoolean(L, "enabled", manager().enabled(script->file()));
        setBoolean(L, "loaded", script->running());
        lua_rawseti(L, -2, index++);
    }
    return 1;
}

int scriptsReload(lua_State* L) {
    manager().requestReload(lua_isstring(L, 1) ? lua_tostring(L, 1) : "");
    return 0;
}

int scriptsEnable(lua_State* L) {
    const char* file = luaL_checkstring(L, 1);
    manager().requestEnable(file, optBoolean(L, 2, true));
    return 0;
}

const luaL_Reg kColdFunctions[] = {
    {"script", coldScriptInfo}, {"on", coldOn},         {"off", coldOff},
    {"chat", coldChat},         {"say", coldSay},
    {"command", coldCommand},   {"log", coldLog},       {"include", coldInclude},
    {"color", coldColour},      {"colour", coldColour}, {"rgb", coldRgb},
    {"rgba", coldRgba},         {"hsv", coldHsv},       {"accent", coldAccent},
    {"keyName", coldKeyName},   {nullptr, nullptr},
};

const luaL_Reg kConfigFunctions[] = {
    {"get", configGet}, {"set", configSet}, {"save", configSave}, {nullptr, nullptr},
};

const luaL_Reg kScriptsFunctions[] = {
    {"list", scriptsList}, {"reload", scriptsReload}, {"enable", scriptsEnable},
    {nullptr, nullptr},
};

const luaL_Reg kNotifyFunctions[] = {
    {"info", notifyInfo},     {"success", notifySuccess}, {"warn", notifyWarn},
    {"error", notifyError},   {"overlay", notifyOverlay}, {nullptr, nullptr},
};

const luaL_Reg kThemeFunctions[] = {
    {"color", themeColour},     {"colour", themeColour},   {"setAccent", themeSetAccent},
    {"readable", themeReadable}, {"health", themeHealth},  {nullptr, nullptr},
};

const luaL_Reg kFriendFunctions[] = {
    {"add", friendsAdd},   {"remove", friendsRemove}, {"is", friendsIs},
    {"list", friendsList}, {"clear", friendsClear},   {nullptr, nullptr},
};

const luaL_Reg kSoundFunctions[] = {
    {"play", soundPlay}, {nullptr, nullptr},
};

void installKeys(lua_State* L) {
    lua_newtable(L);
    for (const KeyName& key : kKeys) {
        setInteger(L, key.name, key.code);
    }
    for (char letter = 'A'; letter <= 'Z'; ++letter) {
        const char name[2] = {letter, '\0'};
        setInteger(L, name, letter);
    }
    for (int digit = 0; digit <= 9; ++digit) {
        char name[8];
        std::snprintf(name, sizeof(name), "NUM%d", digit);
        setInteger(L, name, '0' + digit);
        std::snprintf(name, sizeof(name), "KP%d", digit);
        setInteger(L, name, 320 + digit);
    }
    for (int index = 1; index <= 25; ++index) {
        char name[8];
        std::snprintf(name, sizeof(name), "F%d", index);
        setInteger(L, name, 289 + index);
    }
    lua_setfield(L, -2, "keys");

    lua_newtable(L);
    setInteger(L, "LEFT", 0);
    setInteger(L, "RIGHT", 1);
    setInteger(L, "MIDDLE", 2);
    setInteger(L, "BUTTON4", 3);
    setInteger(L, "BUTTON5", 4);
    lua_setfield(L, -2, "mouseButtons");
}

}  // namespace

modules::Context& requireContext(lua_State* L) {
    modules::Context* context = manager().context();
    if (context == nullptr || context->state == nullptr || context->env == nullptr) {
        luaL_error(L, "the game is only reachable from inside a client callback");
    }
    return *context;
}

const game::FrameState& requireState(lua_State* L) {
    return *requireContext(L).state;
}

const game::LocalPlayerView& requirePlayer(lua_State* L) {
    const game::FrameState& state = requireState(L);
    if (!state.player.valid) {
        luaL_error(L, "there is no player right now");
    }
    return state.player;
}

void installCore(lua_State* L) {
    luaL_setfuncs(L, kColdFunctions, 0);
    setString(L, "name", std::string(ui::kClientName));
    setString(L, "version", std::string(ui::kClientVersion));

    installKeys(L);

    lua_newtable(L);
    luaL_setfuncs(L, kConfigFunctions, 0);
    lua_setfield(L, -2, "config");

    lua_newtable(L);
    luaL_setfuncs(L, kScriptsFunctions, 0);
    lua_setfield(L, -2, "scripts");

    lua_newtable(L);
    luaL_setfuncs(L, kThemeFunctions, 0);
    lua_setfield(L, -2, "theme");

    lua_newtable(L);
    luaL_setfuncs(L, kFriendFunctions, 0);
    lua_setfield(L, -2, "friends");

    lua_newtable(L);
    luaL_setfuncs(L, kSoundFunctions, 0);
    lua_setfield(L, -2, "sound");

    /* cold.notify is both a function and a namespace: notify("text") is the
     * common case and notify.error("text") is the one that says how. */
    lua_newtable(L);
    luaL_setfuncs(L, kNotifyFunctions, 0);
    lua_newtable(L);
    lua_pushcfunction(L, notifyCall);
    lua_setfield(L, -2, "__call");
    lua_setmetatable(L, -2);
    lua_setfield(L, -2, "notify");

    /* cold.client is a live view rather than a snapshot: reading a field asks
     * the frame that is being drawn, so a script never holds a stale number. */
    lua_newtable(L);
    lua_newtable(L);
    lua_pushcfunction(L, clientIndex);
    lua_setfield(L, -2, "__index");
    lua_pushstring(L, "client");
    lua_setfield(L, -2, "__name");
    lua_setmetatable(L, -2);
    lua_setfield(L, -2, "client");
}

bool installApi(lua_State* L, Script& script) {
    (void)script;
    const int base = lua_gettop(L);
    registerColourType(L);
    registerVec3Type(L);

    lua_newtable(L);
    installCore(L);
    installGame(L);
    installRender(L);
    installRender3d(L);
    installHud(L);
    installModules(L);
    lua_pushvalue(L, -1);
    lua_setglobal(L, "cold");

    /* print goes to the log, not to a console nobody is watching. */
    lua_getfield(L, -1, "log");
    lua_setglobal(L, "print");
    lua_pop(L, 1);

    lua_pushcfunction(L, &preludeTraceback);
    const int handler = lua_gettop(L);
    if (luaL_loadbuffer(L, kPrelude, std::strlen(kPrelude), "@cold.prelude") != LUA_OK ||
        lua_pcall(L, 0, 0, handler) != LUA_OK) {
        COLD_ERROR(kScope, "prelude: %s",
                   lua_tostring(L, -1) != nullptr ? lua_tostring(L, -1) : "?");
        lua_settop(L, base);
        return false;
    }
    lua_settop(L, base);
    return true;
}

}  // namespace cold::script
