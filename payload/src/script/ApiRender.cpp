#include <cstring>
#include <string>

#include "assets/Assets.generated.hpp"
#include "render/Renderer.hpp"
#include "script/Api.hpp"
#include "script/ScriptManager.hpp"

namespace cold::script {
namespace {

render::Renderer& requireDrawing(lua_State* L) {
    if (!manager().drawing()) {
        luaL_error(L, "drawing is only allowed from a render callback");
    }
    return render::renderer();
}

render::FontRole fontRole(const std::string& name) {
    if (name == "medium") return render::FontRole::Medium;
    if (name == "semibold") return render::FontRole::SemiBold;
    if (name == "bold") return render::FontRole::Bold;
    if (name == "mono") return render::FontRole::Mono;
    if (name == "monobold") return render::FontRole::MonoBold;
    return render::FontRole::Regular;
}

render::Align alignment(const std::string& name) {
    if (name == "center" || name == "centre") return render::Align::Center;
    if (name == "right") return render::Align::Right;
    return render::Align::Left;
}

Rect checkRect(lua_State* L, int index) {
    return Rect{checkFloat(L, index), checkFloat(L, index + 1), checkFloat(L, index + 2),
                checkFloat(L, index + 3)};
}

int renderRect(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const Rect bounds = checkRect(L, 1);
    gfx.rect(bounds, checkColour(L, 5), optFloat(L, 6, 0.0f));
    return 0;
}

int renderGradient(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const Rect bounds = checkRect(L, 1);
    const Color from = checkColour(L, 5);
    const Color to = checkColour(L, 6);
    const bool horizontal = optBoolean(L, 7, false);
    const float radius = optFloat(L, 8, 0.0f);
    if (horizontal) {
        gfx.rectHorizontal(bounds, from, to, radius);
    } else {
        gfx.rectVertical(bounds, from, to, radius);
    }
    return 0;
}

int renderOutline(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const Rect bounds = checkRect(L, 1);
    gfx.outline(bounds, checkColour(L, 5), optFloat(L, 6, 1.0f), optFloat(L, 7, 0.0f));
    return 0;
}

int renderShadow(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const Rect bounds = checkRect(L, 1);
    gfx.shadow(bounds, checkColour(L, 5), optFloat(L, 6, 8.0f), optFloat(L, 7, 0.0f));
    return 0;
}

int renderGlow(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const Rect bounds = checkRect(L, 1);
    gfx.glow(bounds, checkColour(L, 5), optFloat(L, 6, 8.0f), optFloat(L, 7, 0.0f));
    return 0;
}

int renderLine(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    gfx.line(checkFloat(L, 1), checkFloat(L, 2), checkFloat(L, 3), checkFloat(L, 4),
             optFloat(L, 5, 1.0f), checkColour(L, 6));
    return 0;
}

int renderRing(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    gfx.ring(checkFloat(L, 1), checkFloat(L, 2), checkFloat(L, 3), optFloat(L, 4, 1.0f),
             optFloat(L, 5, 1.0f), checkColour(L, 6));
    return 0;
}

int renderCircle(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const float x = checkFloat(L, 1);
    const float y = checkFloat(L, 2);
    const float radius = checkFloat(L, 3);
    gfx.rect(Rect{x - radius, y - radius, radius * 2.0f, radius * 2.0f}, checkColour(L, 4), radius);
    return 0;
}

/* Four screen points filled flat, in order. Unlike a rectangle this is not
 * axis-aligned, so it is what a projected face or a slanted shape is drawn
 * with. */
int renderQuad(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    float xs[4];
    float ys[4];
    for (int corner = 0; corner < 4; ++corner) {
        xs[corner] = checkFloat(L, corner * 2 + 1);
        ys[corner] = checkFloat(L, corner * 2 + 2);
    }
    gfx.face(xs, ys, checkColour(L, 9));
    return 0;
}

/* An outline whose brightness travels round it, which is how the client marks
 * something as live rather than merely present. */
int renderSweep(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const Rect bounds = checkRect(L, 1);
    gfx.sweepOutline(bounds, checkColour(L, 5), optFloat(L, 6, 1.0f), optFloat(L, 7, 0.0f));
    return 0;
}

int renderText(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    std::size_t length = 0;
    const char* text = luaL_checklstring(L, 1, &length);
    const float x = checkFloat(L, 2);
    const float y = checkFloat(L, 3);
    const float size = optFloat(L, 4, 12.0f);
    const Color colour = optColour(L, 5, Color::rgb(0xFFFFFF));

    render::FontRole role = render::FontRole::Medium;
    render::Align align = render::Align::Left;
    bool shadow = false;
    bool byCapHeight = false;
    float bloom = 0.0f;
    bool outlined = false;
    Color outline = Color::rgb(0x000000);
    if (lua_istable(L, 6)) {
        role = fontRole(fieldString(L, 6, "font", "medium"));
        align = alignment(fieldString(L, 6, "align", "left"));
        shadow = fieldBoolean(L, 6, "shadow", false);
        byCapHeight = fieldBoolean(L, 6, "cap", false);
        bloom = fieldFloat(L, 6, "bloom", 0.0f);
        lua_getfield(L, 6, "outline");
        outlined = !lua_isnil(L, -1);
        if (outlined) {
            outline = checkColour(L, lua_gettop(L));
        }
        lua_pop(L, 1);
    }

    const std::string_view value(text, length);
    if (bloom > 0.0f) {
        gfx.textBloom(role, value, x, byCapHeight ? y : y + gfx.fonts().face(role).capTop(size),
                      size, colour.withAlpha(0.5f), bloom, align);
    }
    if (outlined) {
        /* Four offset copies underneath. Enough to read over anything, and
         * cheap: they batch with the run itself. */
        const float offset = std::max(1.0f, size * 0.075f);
        const float steps[4][2] = {{-offset, 0.0f}, {offset, 0.0f}, {0.0f, -offset},
                                   {0.0f, offset}};
        for (const auto& step : steps) {
            if (byCapHeight) {
                gfx.textCap(role, value, x + step[0], y + step[1], size, outline, align);
            } else {
                gfx.text(role, value, x + step[0], y + step[1], size, outline, align);
            }
        }
    }
    if (shadow) {
        const float offset = std::max(1.0f, size * 0.09f);
        const Color dark = Color::rgb(0x000000, 0.55f * colour.a);
        if (byCapHeight) {
            gfx.textCap(role, value, x + offset, y + offset, size, dark, align);
        } else {
            gfx.text(role, value, x + offset, y + offset, size, dark, align);
        }
    }
    const float end = byCapHeight ? gfx.textCap(role, value, x, y, size, colour, align)
                                  : gfx.text(role, value, x, y, size, colour, align);
    lua_pushnumber(L, end);
    return 1;
}

int renderMeasure(lua_State* L) {
    std::size_t length = 0;
    const char* text = luaL_checklstring(L, 1, &length);
    const float size = optFloat(L, 2, 12.0f);
    const render::FontRole role = fontRole(lua_isstring(L, 3) ? lua_tostring(L, 3) : "medium");
    lua_pushnumber(L, render::renderer().measure(role, std::string_view(text, length), size));
    return 1;
}

int renderLineHeight(lua_State* L) {
    const float size = optFloat(L, 1, 12.0f);
    const render::FontRole role = fontRole(lua_isstring(L, 2) ? lua_tostring(L, 2) : "medium");
    lua_pushnumber(L, render::renderer().lineHeight(role, size));
    return 1;
}

int renderIcon(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    const char* name = luaL_checkstring(L, 1);
    int found = -1;
    for (std::size_t index = 0; index < assets::kIconCount; ++index) {
        if (std::strcmp(assets::kIconNames[index], name) == 0) {
            found = static_cast<int>(index);
            break;
        }
    }
    if (found < 0) {
        luaL_error(L, "no icon called '%s'", name);
    }
    const Rect bounds{checkFloat(L, 2), checkFloat(L, 3), checkFloat(L, 4), checkFloat(L, 5)};
    gfx.icon(static_cast<assets::IconAsset>(found), bounds, optColour(L, 6, Color::rgb(0xFFFFFF)));
    return 0;
}

int renderIcons(lua_State* L) {
    lua_newtable(L);
    for (std::size_t index = 0; index < assets::kIconCount; ++index) {
        lua_pushstring(L, assets::kIconNames[index]);
        lua_rawseti(L, -2, static_cast<lua_Integer>(index + 1));
    }
    return 1;
}

int renderClip(lua_State* L) {
    render::Renderer& gfx = requireDrawing(L);
    gfx.pushClip(checkRect(L, 1));
    manager().noteClipPush();
    return 0;
}

int renderUnclip(lua_State* L) {
    requireDrawing(L);
    manager().noteClipPop();
    return 0;
}

int renderSize(lua_State* L) {
    const game::FrameState& state = requireState(L);
    lua_pushinteger(L, state.windowWidth);
    lua_pushinteger(L, state.windowHeight);
    return 2;
}

int renderScale(lua_State* L) {
    const game::FrameState& state = requireState(L);
    lua_pushnumber(L, state.scaledWidth > 0 ? static_cast<double>(state.windowWidth) /
                                                  static_cast<double>(state.scaledWidth)
                                            : 2.0);
    return 1;
}

int renderProject(lua_State* L) {
    modules::Context& context = requireContext(L);
    Vec3 point;
    readVec3(L, 1, point);
    game::ScreenPoint screen;
    if (context.projection == nullptr || !context.projection->project(point, screen)) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushnumber(L, screen.x);
    lua_pushnumber(L, screen.y);
    lua_pushboolean(L, screen.visible ? 1 : 0);
    return 3;
}

int renderProjectBox(lua_State* L) {
    modules::Context& context = requireContext(L);
    Vec3 minimum;
    Vec3 maximum;
    const int next = readVec3(L, 1, minimum);
    readVec3(L, next, maximum);
    Rect bounds;
    if (context.projection == nullptr || !context.projection->projectBox(minimum, maximum, bounds)) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushnumber(L, bounds.x);
    lua_pushnumber(L, bounds.y);
    lua_pushnumber(L, bounds.width);
    lua_pushnumber(L, bounds.height);
    return 4;
}

const luaL_Reg kRenderFunctions[] = {
    {"rect", renderRect},         {"gradient", renderGradient},
    {"outline", renderOutline},   {"shadow", renderShadow},
    {"glow", renderGlow},         {"line", renderLine},
    {"ring", renderRing},         {"circle", renderCircle},
    {"quad", renderQuad},         {"sweep", renderSweep},
    {"text", renderText},
    {"measure", renderMeasure},   {"lineHeight", renderLineHeight},
    {"icon", renderIcon},         {"icons", renderIcons},
    {"clip", renderClip},         {"unclip", renderUnclip},
    {"size", renderSize},         {"scale", renderScale},
    {"project", renderProject},   {"projectBox", renderProjectBox},
    {nullptr, nullptr},
};

}  // namespace

void installRender(lua_State* L) {
    lua_newtable(L);
    luaL_setfuncs(L, kRenderFunctions, 0);
    lua_setfield(L, -2, "render");
}

}  // namespace cold::script
