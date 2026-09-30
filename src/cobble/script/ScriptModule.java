package cobble.script;

import cobble.core.Color;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.render.Icon;
import java.util.List;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * A module whose behaviour lives in Lua.
 *
 * <p>It is a Module like any other, which is the point: it sits in the same manager, the same
 * config file, the same menu category and the same array list as the ones written in Java, and
 * everything that walks the module list gets it for free. The only difference is where onTick goes.
 */
final class ScriptModule extends Module {
    final Script owner;
    private LuaTable table;
    private boolean started;

    ScriptModule(Script owner, String name, String description, Category category, Subcategory subcategory,
            int key) {
        super(name, description, category, subcategory, key);
        this.owner = owner;
    }

    void bind(LuaTable value) {
        table = value;
    }

    LuaTable table() {
        return table;
    }

    @Override
    public String suffix() {
        if (!owner.running() || table == null) {
            return "";
        }
        Varargs result = owner.protect(() -> {
            LuaValue value = table.get("suffix");
            return value.isfunction() ? value.call(table) : value;
        });
        return result != null && result.arg1().type() == LuaValue.TSTRING ? result.arg1().tojstring() : "";
    }

    /**
     * Calls the named field of the module table with the table as its only argument, so both
     * {@code function m.onTick()} and {@code function m:onTick()} work. The context is bound for the
     * call because modules tick from the tick hook, outside any frame.
     */
    private void invoke(String key, Context context) {
        if (!owner.running() || table == null) {
            return;
        }
        ScriptManager.within(context, () -> owner.protect(() -> {
            LuaValue callback = table.get(key);
            return callback.isfunction() ? callback.call(table) : LuaValue.NONE;
        }));
    }

    /*
     * A module the config turned on is enabled before there is any context to call onEnable with,
     * exactly as the built-in ones are. The difference is that a script keeps its state in that
     * callback, so the first tick or frame that finds the module already on runs it.
     */
    private void ensureStarted(Context context) {
        if (started || !enabled()) {
            return;
        }
        started = true;
        invoke("onEnable", context);
    }

    @Override
    public void onEnable(Context context) {
        if (started) {
            return;
        }
        started = true;
        invoke("onEnable", context);
    }

    @Override
    public void onDisable(Context context) {
        if (!started) {
            return;
        }
        started = false;
        invoke("onDisable", context);
    }

    @Override
    public void onTick(Context context) {
        ensureStarted(context);
        invoke("onTick", context);
    }

    @Override
    public void onRender(Context context) {
        ensureStarted(context);
        ScriptManager.draw(() -> invoke("onRender", context));
    }

    /* The settings a definition table asks for. Module keeps its builders protected, so the script
     * side goes through these. */

    Setting defineBoolean(String settingName, String settingDescription, boolean value) {
        return addBoolean(settingName, settingDescription, value);
    }

    Setting defineNumber(String settingName, String settingDescription, float value, float minimum, float maximum,
            int decimals) {
        return addNumber(settingName, settingDescription, value, minimum, maximum, decimals);
    }

    Setting defineChoice(String settingName, String settingDescription, List<String> choices, int index) {
        return addChoice(settingName, settingDescription, choices, index);
    }

    Setting defineKey(String settingName, String settingDescription, int value) {
        return addKey(settingName, settingDescription, value);
    }

    Setting defineColour(String settingName, String settingDescription, Color colour) {
        return addColour(settingName, settingDescription, colour);
    }

    void defineIcon(Icon icon) {
        setIcon(icon);
    }

    /** Hides a setting behind a Lua predicate, the same way the Java modules hide theirs behind a lambda. */
    void defineVisibility(Setting setting, LuaValue predicate) {
        setting.visibleWhen(() -> {
            if (!owner.running()) {
                return true;
            }
            Varargs result = owner.call(predicate, LuaValue.NONE);
            return result == null || result.arg1().toboolean();
        });
    }
}
