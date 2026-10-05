package cobble.script;

import cobble.core.Rect;
import cobble.render.Renderer;
import cobble.ui.Theme;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * A HUD element a script owns.
 *
 * <p>Position and size are in Minecraft's GUI-scaled units, the same ones the client's own HUD is
 * laid out in, so an element is the same size on screen at any resolution and its saved position
 * survives a resolution change. It is dragged with the menu open, exactly as the panels are, and
 * what it draws is whatever its render callback draws with cold.render.
 */
final class HudElement {
    final Script owner;
    final String name;
    private LuaTable table;
    private float x = 4.0f;
    private float y = 4.0f;
    private float width = 70.0f;
    private float height = 16.0f;
    private boolean showing = true;

    HudElement(Script owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    void bind(LuaTable value) {
        table = value;
    }

    LuaTable table() {
        return table;
    }

    float x() {
        return x;
    }

    float y() {
        return y;
    }

    float width() {
        return width;
    }

    float height() {
        return height;
    }

    boolean showing() {
        return showing;
    }

    void setShowing(boolean value) {
        showing = value;
    }

    void setPosition(float newX, float newY) {
        x = newX;
        y = newY;
    }

    void setSize(float newWidth, float newHeight) {
        width = Math.max(4.0f, newWidth);
        height = Math.max(4.0f, newHeight);
    }

    /** Screen rectangle for the frame being drawn; {@code unit} is framebuffer pixels per GUI unit. */
    Rect bounds(float unit) {
        return new Rect(x * unit, y * unit, width * unit, height * unit);
    }

    /** Runs the visibility predicate, if the script gave one. */
    boolean visible() {
        if (!showing || !owner.running() || table == null) {
            return false;
        }
        Varargs result = owner.protect(() -> {
            LuaValue predicate = table.get("visible");
            return predicate.isfunction() ? predicate.call(table) : LuaValue.TRUE;
        });
        return result != null && result.arg1().toboolean();
    }

    void render(float unit, boolean menuOpen, boolean dragging) {
        if (!owner.running() || table == null) {
            return;
        }
        Rect rect = bounds(unit);
        // With the menu open every element shows its own outline, because an element that draws
        // nothing right now still has to be findable to be dragged.
        if (menuOpen) {
            Renderer renderer = Renderer.instance();
            renderer.rect(rect, Theme.SURFACE.withAlpha(dragging ? 0.9f : 0.55f), unit * 2.0f);
            renderer.outline(rect, (dragging ? Theme.accent() : Theme.BORDER).withAlpha(0.9f),
                    Math.max(1.0f, unit * 0.5f), unit * 2.0f);
        }
        owner.protect(() -> {
            LuaValue callback = table.get("render");
            if (!callback.isfunction()) {
                return LuaValue.NONE;
            }
            // The element table first, so function el.render(self, d) reads the way every other
            // callback does, then the frame's own numbers.
            LuaTable frame = new LuaTable();
            frame.set("x", rect.x());
            frame.set("y", rect.y());
            frame.set("width", rect.width());
            frame.set("height", rect.height());
            frame.set("unit", unit);
            frame.set("dragging", LuaValue.valueOf(dragging));
            frame.set("menu", LuaValue.valueOf(menuOpen));
            return callback.call(table, frame);
        });
    }
}
