#include "script/HudElement.hpp"

#include "render/Renderer.hpp"
#include "script/ScriptManager.hpp"
#include "ui/Theme.hpp"

namespace cold::script {

HudElement::HudElement(Script& owner, std::string name)
    : owner_(owner), name_(std::move(name)) {}

HudElement::~HudElement() {
    if (tableRef_ != LUA_NOREF && owner_.state() != nullptr) {
        luaL_unref(owner_.state(), LUA_REGISTRYINDEX, tableRef_);
    }
    tableRef_ = LUA_NOREF;
}

void HudElement::setPosition(float x, float y) {
    x_ = x;
    y_ = y;
}

void HudElement::setSize(float width, float height) {
    width_ = std::max(4.0f, width);
    height_ = std::max(4.0f, height);
}

Rect HudElement::bounds(float unit) const {
    return Rect{x_ * unit, y_ * unit, width_ * unit, height_ * unit};
}

bool HudElement::visible() const {
    if (!showing_ || !owner_.running() || tableRef_ == LUA_NOREF) {
        return false;
    }
    lua_State* L = owner_.state();
    const int handler = owner_.pushHandler();
    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef_);
    lua_getfield(L, -1, "visible");
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 2);
        lua_remove(L, handler);
        return true;
    }
    lua_insert(L, -2);
    if (!owner_.call(1, 1, handler)) {
        return false;
    }
    const bool shown = lua_toboolean(L, -1) != 0;
    lua_pop(L, 1);
    return shown;
}

void HudElement::render(float unit, bool menuOpen, bool dragging) {
    if (!owner_.running() || tableRef_ == LUA_NOREF) {
        return;
    }
    const Rect rect = bounds(unit);

    /* With the menu open every element shows its own outline, because an
     * element that draws nothing right now still has to be findable to be
     * dragged. */
    if (menuOpen) {
        const ui::Palette& palette = ui::theme();
        render::renderer().rect(rect, palette.surface.withAlpha(dragging ? 0.9f : 0.55f),
                                unit * 2.0f);
        render::renderer().outline(rect,
                                   (dragging ? palette.accent : palette.border).withAlpha(0.9f),
                                   std::max(1.0f, unit * 0.5f), unit * 2.0f);
    }

    lua_State* L = owner_.state();
    const int handler = owner_.pushHandler();
    lua_rawgeti(L, LUA_REGISTRYINDEX, tableRef_);
    lua_getfield(L, -1, "render");
    if (!lua_isfunction(L, -1)) {
        lua_pop(L, 2);
        lua_remove(L, handler);
        return;
    }
    lua_insert(L, -2);

    /* The element table first, so `function el.render(self, d)` reads the way
     * every other callback does, then the frame's own numbers. */
    lua_createtable(L, 0, 7);
    setNumber(L, "x", rect.x);
    setNumber(L, "y", rect.y);
    setNumber(L, "width", rect.width);
    setNumber(L, "height", rect.height);
    setNumber(L, "unit", unit);
    setBoolean(L, "dragging", dragging);
    setBoolean(L, "menu", menuOpen);
    owner_.call(2, 0, handler);
}

}  // namespace cold::script
