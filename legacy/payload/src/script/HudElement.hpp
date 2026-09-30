#pragma once

#include <string>

#include "core/Math.hpp"
#include "modules/Module.hpp"
#include "script/Script.hpp"

namespace cold::script {

/* A HUD element a script owns.
 *
 * Position and size are in Minecraft's GUI-scaled units, the same ones the
 * client's own HUD is laid out in, so an element is the same size on screen at
 * any resolution and its saved position survives a resolution change. It is
 * dragged with the menu open, exactly as the panels are, and what it draws is
 * whatever its render callback draws with cold.render. */
class HudElement {
public:
    HudElement(Script& owner, std::string name);
    ~HudElement();

    HudElement(const HudElement&) = delete;
    HudElement& operator=(const HudElement&) = delete;

    Script& owner() const { return owner_; }
    const std::string& name() const { return name_; }

    void bind(int reference) { tableRef_ = reference; }
    int table() const { return tableRef_; }

    float x() const { return x_; }
    float y() const { return y_; }
    float width() const { return width_; }
    float height() const { return height_; }
    bool showing() const { return showing_; }

    void setPosition(float x, float y);
    void setSize(float width, float height);
    void setShowing(bool showing) { showing_ = showing; }

    /* Screen rectangle for the frame being drawn. `unit` is framebuffer pixels
     * per GUI unit. */
    Rect bounds(float unit) const;
    /* Runs the visibility predicate, if the script gave one. */
    bool visible() const;
    void render(float unit, bool menuOpen, bool dragging);

private:
    Script& owner_;
    std::string name_;
    int tableRef_ = LUA_NOREF;

    float x_ = 4.0f;
    float y_ = 4.0f;
    float width_ = 70.0f;
    float height_ = 16.0f;
    bool showing_ = true;
};

}  // namespace cold::script
