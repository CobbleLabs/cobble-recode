package cobble.module;

import cobble.core.Color;
import cobble.core.Config;
import cobble.core.Log;
import cobble.core.Time;
import cobble.event.EventBus;
import cobble.hook.Input;
import cobble.ui.Client;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ModuleManager {
    private static final List<Module> modules = new ArrayList<>();
    private static final Config config = new Config();

    private ModuleManager() {}

    public static void registerModules() {
        if (!modules.isEmpty()) {
            return;
        }
        for (Module module : ModuleList.create()) {
            add(module);
        }
        sort();
        Log.info("modules", "%d modules registered", modules.size());
    }

    /**
     * Modules that arrive after startup, which is every module a Lua script
     * declares. The config already read is applied as they land, so a script
     * module keeps its settings across a reload exactly as a built-in keeps
     * them across a session. Both calls are only safe outside a tick or a
     * render pass, which is why the script manager services its queue before
     * either runs.
     */
    public static void registerModule(Module module) {
        if (find(module.name) != null) {
            return;
        }
        applyConfig(module);
        add(module);
        sort();
    }

    public static void unregisterModule(Module module, Context context) {
        if (!modules.contains(module)) {
            return;
        }
        /* Remembered before it goes: a script reload tears its modules down and
         * builds them again, and would otherwise hand back defaults. */
        captureConfig(config, module);
        module.setEnabled(false, context);
        modules.remove(module);
        EventBus.unregister(module);
        Log.debug("modules", "unregistered %s", module.name);
    }

    private static void add(Module module) {
        Log.debug("modules", "registered %s (%s)", module.name, module.category.displayName);
        modules.add(module);
        EventBus.register(module);
    }

    private static void sort() {
        modules.sort(Comparator.<Module, Category>comparing(module -> module.category)
                .thenComparing(module -> module.name));
    }

    /** The live document behind load/save, for UI layout sections that are not module settings. */
    public static Config config() {
        return config;
    }

    public static List<Module> all() {
        return Collections.unmodifiableList(modules);
    }

    public static List<Module> byCategory(Category category) {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.category == category) {
                result.add(module);
            }
        }
        return result;
    }

    public static Module find(String name) {
        for (Module module : modules) {
            if (module.name.equals(name)) {
                return module;
            }
        }
        return null;
    }

    public static <T extends Module> T get(Class<T> type) {
        for (Module module : modules) {
            if (type.isInstance(module)) {
                return type.cast(module);
            }
        }
        return null;
    }

    private static String colourToHex(Color colour) {
        return String.format("#%08X", colour.packed());
    }

    private static Color colourFromHex(String text, Color fallback) {
        if (text.length() != 9 || text.charAt(0) != '#') {
            return fallback;
        }
        try {
            return Color.rgba(Integer.parseUnsignedInt(text.substring(1), 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void applyConfig(Module module) {
        String section = module.name;
        if (!config.hasSection(section)) {
            return;
        }
        module.setKey(config.getInt(section, "key", module.key()));
        /* Assigned, not just enabled: a module whose constructor turns itself
         * on could otherwise never be switched off persistently. */
        module.setEnabled(config.getBool(section, "enabled", module.enabled()), null);
        for (Setting setting : module.settings()) {
            String key = setting.name;
            switch (setting.type) {
                case BOOLEAN -> setting.setBool(config.getBool(section, key, setting.bool()));
                case NUMBER -> setting.setNumber(config.getNumber(section, key, setting.number()));
                case CHOICE -> {
                    int index = setting.choices().indexOf(config.get(section, key, setting.choiceName()));
                    if (index >= 0) {
                        setting.setChoice(index);
                    }
                }
                case KEY -> setting.setKey(config.getInt(section, key, setting.key()));
                case COLOUR -> setting.setColour(colourFromHex(
                        config.get(section, key, colourToHex(setting.colour())), setting.colour()));
            }
        }
    }

    private static void captureConfig(Config document, Module module) {
        String section = module.name;
        /* Rebuilt, not merged: a module that has lost a setting must lose the line as well. */
        document.clearSection(section);
        document.setBool(section, "enabled", module.enabled());
        document.setInt(section, "key", module.key());
        for (Setting setting : module.settings()) {
            String key = setting.name;
            switch (setting.type) {
                case BOOLEAN -> document.setBool(section, key, setting.bool());
                case NUMBER -> document.setNumber(section, key, setting.number(), setting.decimals());
                case CHOICE -> document.set(section, key, setting.choiceName());
                case KEY -> document.setInt(section, key, setting.key());
                case COLOUR -> document.set(section, key, colourToHex(setting.colour()));
            }
        }
    }

    /* Kept as Cobble keeps it: written shortly after each change rather than
     * only on unload, so a crash or a game closed without unloading keeps what
     * was set, and the next injection starts from exactly that. */
    private static final double SAVE_DELAY_SECONDS = 1.0;
    private static double changedAt = -1.0;

    public static void markChanged() {
        changedAt = Time.nowSeconds();
    }

    /** True once, a moment after the last change, when it is time to write the config. */
    public static boolean changesSettled() {
        if (changedAt < 0.0 || Time.nowSeconds() - changedAt < SAVE_DELAY_SECONDS) {
            return false;
        }
        changedAt = -1.0;
        return true;
    }

    public static void loadConfig(Path path) {
        if (!config.load(path)) {
            return;
        }
        for (Module module : modules) {
            applyConfig(module);
        }
        Log.info("modules", "configuration applied");
    }

    /**
     * Captures into the loaded document rather than a fresh one: a script that
     * is switched off right now still has a section in there, and rebuilding
     * from the live list alone would throw its settings away.
     */
    public static void saveConfig(Path path) {
        for (Module module : modules) {
            captureConfig(config, module);
        }
        config.save(path);
    }

    public static void tick(Context context) {
        for (Module module : modules) {
            if (module.holdsKey() && module.key() != 0) {
                module.keyHeld(!context.state.screenOpen && Input.bindHeld(module.key()), context);
            }
            module.sync(context);
            if (module.enabled()) {
                module.onTick(context);
            }
        }
    }

    public static void update() {
        boolean menuOpen = Client.gui().visible();
        for (Module module : modules) {
            if (module.enabled()) {
                module.onUpdate();
            }
            if (menuOpen) {
                module.guiUpdate();
            }
        }
    }

    public static void render(Context context) {
        for (Module module : modules) {
            if (module.enabled() || module.animation() > 0.001f) {
                module.onRender(context);
            }
        }
    }

    public static void advanceAnimations(float deltaTime) {
        for (Module module : modules) {
            module.advanceAnimation(deltaTime);
        }
    }

    /** True when the key was bound to something, so the caller can swallow it. */
    public static boolean handleKey(int key, Context context) {
        if (key == 0) {
            return false;
        }
        boolean consumed = false;
        for (Module module : modules) {
            if (module.key() == key) {
                if (!module.holdsKey()) {
                    module.toggle(context);
                }
                consumed = true;
            }
        }
        return consumed;
    }
}
