package cobble.script;

import cobble.core.Color;
import cobble.core.Config;
import cobble.core.Log;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.render.Renderer;
import cobble.ui.Client;
import cobble.ui.Theme;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * Owns every loaded script and the points the client dispatches into them.
 *
 * <p>Two rules hold the whole thing together. Everything runs on the client thread, so a binding
 * may touch the game exactly as a Java module does and nothing needs a lock. And nothing that
 * changes the script or module lists happens while either is being walked: loading, unloading and
 * reloading are requests, serviced at the top of the next frame, which is what lets a script ask
 * for its own reload from inside one of its own callbacks.
 */
public final class ScriptManager {
    private static final String SCOPE = "scripts";
    private static final String ENABLED_KEY = "enabled";
    private static final long MAX_SOURCE_BYTES = 8L << 20;

    enum Notice {
        INFO, GOOD, WARN, BAD;

        Color colour() {
            return switch (this) {
                case GOOD -> Theme.POSITIVE;
                case WARN -> Theme.WARNING;
                case BAD -> Theme.NEGATIVE;
                case INFO -> Theme.accent();
            };
        }

        String title() {
            return switch (this) {
                case GOOD -> "Success";
                case BAD -> "Action failed";
                case INFO, WARN -> "Cobble";
            };
        }
    }

    private enum RequestKind { ENABLE, DISABLE, RELOAD, RELOAD_ALL }

    private record Request(RequestKind kind, String file) {}

    private static final List<Script> scripts = new ArrayList<>();
    private static final List<HudElement> hudElements = new ArrayList<>();
    private static final List<Request> pending = new ArrayList<>();
    private static final Config store = new Config();
    private static Path directory;
    private static Path storePath;

    private static HudElement dragged;
    private static float dragOffsetX;
    private static float dragOffsetY;
    private static float hudUnit = 2.0f;

    private static Context context;
    private static int drawing;
    private static int clips;
    private static boolean initialised;
    private static boolean loading;
    private static boolean menuDirty;
    private static long ticks;
    private static long frames;
    private static long frameStarted;
    private static long overlayMicros;

    /*
     * What the last frame looked like, so this one can say what changed. Each of these is only kept
     * up to date while something is actually listening for the event it feeds.
     */
    private static boolean wasInWorld;
    private static boolean wasScreenOpen;
    private static float lastHealth = -1.0f;
    private static int lastSlot = -1;
    private static Map<Integer, Float> seenEntities = new HashMap<>();
    private static Map<String, Integer> seenEffects = new HashMap<>();
    private static List<Module> toggledOn = new ArrayList<>();

    private ScriptManager() {}

    /** Reads a whole file as bytes. Scripts are UTF-8 text and Lua does not care beyond that, so nothing is transcoded. */
    static byte[] readSource(Path path) {
        try {
            if (Files.size(path) > MAX_SOURCE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            // A file saved from a Windows editor often starts with a byte order mark, which Lua reads
            // as an unexpected symbol on line one.
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                    && (bytes[2] & 0xFF) == 0xBF) {
                return Arrays.copyOfRange(bytes, 3, bytes.length);
            }
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    static Path scriptPath(String file) {
        return directory.resolve(file);
    }

    /** One line on the HUD's notification stack. Used by the manager itself and by cold.notify. */
    static void notify(String text, Notice kind) {
        notify(text, kind, 4.0f);
    }

    static void notify(String text, Notice kind, float seconds) {
        Client.hud().notify(kind.title(), text, kind.colour(), seconds);
    }

    public static void initialise(Path home) {
        if (initialised) {
            return;
        }
        directory = home.resolve("scripts");
        storePath = home.resolve("cobble.scripts.cfg");
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            Log.warn(SCOPE, "scripts folder not created: %s", e.getMessage());
        }
        store.load(storePath);
        initialised = true;

        scan();
        for (Script script : scripts) {
            if (enabled(script.file)) {
                startScript(script);
            }
        }
        Log.info(SCOPE, "%d script%s found, %d loaded", scripts.size(), scripts.size() == 1 ? "" : "s",
                loadedCount());
    }

    public static void shutdown() {
        if (!initialised) {
            return;
        }
        for (Script script : scripts) {
            stopScript(script);
        }
        saveStore();
        scripts.clear();
        initialised = false;
    }

    private static void scan() {
        List<String> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.lua")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                // A leading underscore means a file that exists to be included by another one.
                // Loading it as a script of its own would put a library in the list and run it twice.
                if (!Files.isDirectory(file) && !name.startsWith("_")) {
                    found.add(name);
                }
            }
        } catch (IOException e) {
            Log.warn(SCOPE, "scripts folder not readable: %s", e.getMessage());
            return;
        }

        // Anything that left the folder goes with it, callbacks and modules included: a script file
        // that is not there cannot be reloaded, and leaving its modules in the menu would be a list of
        // things that answer to nobody.
        for (int index = scripts.size() - 1; index >= 0; index--) {
            Script script = scripts.get(index);
            if (found.contains(script.file)) {
                continue;
            }
            Log.info(SCOPE, "%s went away", script.file);
            stopScript(script);
            scripts.remove(index);
        }

        for (String file : found) {
            if (find(file) == null) {
                scripts.add(new Script(file, directory.resolve(file)));
            }
        }
        scripts.sort(Comparator.comparing(script -> script.file));
    }

    static List<Script> scripts() {
        return scripts;
    }

    static Script find(String file) {
        for (Script script : scripts) {
            if (script.file.equals(file) || script.name().equals(file)) {
                return script;
            }
        }
        return null;
    }

    static boolean enabled(String file) {
        // A script that appears in the folder runs: dropping one in is the whole install step, and a
        // list of scripts that all need switching on first is an extra ceremony for nothing.
        return store.getBool(file, ENABLED_KEY, true);
    }

    private static int loadedCount() {
        int count = 0;
        for (Script script : scripts) {
            if (script.running()) {
                count++;
            }
        }
        return count;
    }

    private static boolean startScript(Script script) {
        if (script.globals() != null) {
            return !script.stopped();
        }
        loading = true;
        boolean ok = script.load();
        menuDirty = true;
        if (!ok) {
            loading = false;
            // A chunk that failed halfway may already have registered modules, and those have to go
            // back: they would answer to globals nobody is dispatching into.
            stopScript(script);
            return false;
        }
        Log.info(SCOPE, "loaded %s (%s %s)", script.file, script.name(), script.version());
        for (LuaValue handler : List.copyOf(script.handlers(Script.Event.LOAD))) {
            script.call(handler, LuaValue.NONE);
        }
        loading = false;
        return true;
    }

    private static void stopScript(Script script) {
        if (script.globals() == null) {
            return;
        }
        script.fireUnload();
        for (int index = hudElements.size() - 1; index >= 0; index--) {
            HudElement element = hudElements.get(index);
            if (element.owner == script) {
                removeHudElement(element);
            }
        }
        for (ScriptModule module : List.copyOf(script.modules())) {
            Client.hud().forget(module);
            ModuleManager.unregisterModule(module, context);
        }
        script.close();
        menuDirty = true;
    }

    private static void reloadScript(Script script) {
        stopScript(script);
        if (enabled(script.file)) {
            startScript(script);
        }
    }

    /** Hands a module built by a loading script to the module manager, which applies whatever the config already held for it. */
    static void attachModule(Script script, ScriptModule module) {
        script.addModule(module);
        ModuleManager.registerModule(module);
        menuDirty = true;
    }

    /*
     * HUD elements a script owns. They are drawn after the client's own HUD and dragged with the
     * menu open, so the drag has to be offered to them before anything else outside the menu window
     * gets the click.
     */

    static void attachHudElement(HudElement element) {
        hudElements.add(element);
    }

    static HudElement findHudElement(String name) {
        for (HudElement element : hudElements) {
            if (element.name.equals(name)) {
                return element;
            }
        }
        return null;
    }

    static void removeHudElement(HudElement element) {
        if (dragged == element) {
            dragged = null;
        }
        hudElements.remove(element);
    }

    static List<HudElement> hudElements() {
        return hudElements;
    }

    public static void renderHud(Context frame, boolean menuOpen) {
        FrameState state = frame.state;
        // Same rule as the client's own HUD: nothing on the title screen. A script element draws game
        // state, and there is none to draw.
        if (hudElements.isEmpty() || !state.inWorld) {
            return;
        }
        hudUnit = state.scaledWidth > 0 ? (float) state.windowWidth / state.scaledWidth : 2.0f;
        draw(() -> {
            // Copied: a render callback may remove an element, its own included.
            for (HudElement element : List.copyOf(hudElements)) {
                if (element.visible()) {
                    element.render(hudUnit, menuOpen, dragged == element);
                }
            }
        });
    }

    /** Positions are in the unit of the last frame drawn, which is the one the pointer is over. */
    public static void hudPress(float x, float y) {
        // Topmost first: the last one declared draws over the others, so it is the one the pointer is on.
        for (int index = hudElements.size() - 1; index >= 0; index--) {
            HudElement element = hudElements.get(index);
            if (!element.showing() || !element.bounds(hudUnit).contains(x, y)) {
                continue;
            }
            dragged = element;
            dragOffsetX = x - element.x() * hudUnit;
            dragOffsetY = y - element.y() * hudUnit;
            return;
        }
    }

    public static void hudDrag(float x, float y) {
        if (dragged != null) {
            dragged.setPosition((x - dragOffsetX) / hudUnit, (y - dragOffsetY) / hudUnit);
        }
    }

    public static void hudRelease() {
        if (dragged == null) {
            return;
        }
        // Written where it was let go, so the next session finds it there.
        String section = dragged.owner.file;
        store.setNumber(section, "hud." + dragged.name + ".x", dragged.x(), 2);
        store.setNumber(section, "hud." + dragged.name + ".y", dragged.y(), 2);
        saveStore();
        dragged = null;
    }

    static void requestEnable(String file, boolean value) {
        pending.add(new Request(value ? RequestKind.ENABLE : RequestKind.DISABLE, file));
    }

    public static void requestReload() {
        pending.add(new Request(RequestKind.RELOAD_ALL, ""));
    }

    static void requestReload(String file) {
        pending.add(new Request(RequestKind.RELOAD, file));
    }

    private static void applyPending() {
        if (pending.isEmpty()) {
            return;
        }
        // Serviced from a copy: loading a script runs its chunk, and a chunk is allowed to ask for
        // another reload. Those land on the next frame rather than extending the loop that is running.
        List<Request> requests = List.copyOf(pending);
        pending.clear();

        for (Request request : requests) {
            switch (request.kind()) {
                case RELOAD_ALL -> {
                    scan();
                    for (Script script : scripts) {
                        reloadScript(script);
                    }
                    notify("scripts reloaded", Notice.GOOD);
                }
                case RELOAD -> {
                    Script script = find(request.file());
                    if (script != null) {
                        reloadScript(script);
                        notify(script.file + " reloaded", Notice.GOOD);
                    }
                }
                case ENABLE, DISABLE -> {
                    Script script = find(request.file());
                    if (script != null) {
                        boolean value = request.kind() == RequestKind.ENABLE;
                        store.setBool(script.file, ENABLED_KEY, value);
                        saveStore();
                        if (value) {
                            startScript(script);
                        } else {
                            stopScript(script);
                        }
                    }
                }
            }
        }
    }

    private static void rebuildMenu() {
        if (!menuDirty) {
            return;
        }
        menuDirty = false;
        // The menu caches one card per module and the cards hold references into the module list, so a
        // list that changed is a menu that has to be built again.
        Client.gui().reset();
    }

    private static boolean anyHandler(Script.Event event) {
        for (Script script : scripts) {
            if (script.running() && !script.handlers(event).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void dispatch(Script.Event event) {
        dispatch(event, script -> LuaValue.NONE);
    }

    /** The arguments are built per script: a table carries the metatables of the globals it goes into. */
    private static void dispatch(Script.Event event, Function<Script, Varargs> arguments) {
        for (Script script : scripts) {
            if (!script.running()) {
                continue;
            }
            // Copied: a handler may subscribe or unsubscribe while it runs.
            for (LuaValue handler : List.copyOf(script.handlers(event))) {
                if (!script.running()) {
                    break;
                }
                script.call(handler, arguments.apply(script));
            }
        }
    }

    /** Services pending requests and raises the world and entity events, so a script sees them before anything else this frame. */
    public static void beginFrame(Context frame) {
        context = frame;
        frames++;
        frameStarted = System.nanoTime();
        applyPending();
        rebuildMenu();
        trackWorld();
        dispatch(Script.Event.FRAME, script -> LuaValue.valueOf(frame.deltaTime));
    }

    public static void endFrame() {
        overlayMicros = (System.nanoTime() - frameStarted) / 1000;
        context = null;
    }

    private static void trackWorld() {
        FrameState state = context.state;
        if (state.inWorld != wasInWorld) {
            wasInWorld = state.inWorld;
            dispatch(state.inWorld ? Script.Event.WORLD_LOAD : Script.Event.WORLD_LEAVE);
            if (!state.inWorld) {
                seenEntities.clear();
                seenEffects.clear();
                lastHealth = -1.0f;
                lastSlot = -1;
            }
        }
        if (state.screenOpen != wasScreenOpen) {
            wasScreenOpen = state.screenOpen;
            dispatch(state.screenOpen ? Script.Event.SCREEN_OPEN : Script.Event.SCREEN_CLOSE);
        }
        trackEntities(state);
        trackPlayer(state);
        trackModules();
    }

    private static void trackEntities(FrameState state) {
        boolean wantSpawn = anyHandler(Script.Event.ENTITY_SPAWN);
        boolean wantRemove = anyHandler(Script.Event.ENTITY_REMOVE);
        boolean wantDeath = anyHandler(Script.Event.ENTITY_DEATH);
        if (!state.inWorld || (!wantSpawn && !wantRemove && !wantDeath)) {
            seenEntities.clear();
            return;
        }

        Map<Integer, Float> current = new HashMap<>();
        for (EntityView entity : state.entities) {
            current.put(entity.id, entity.health);
        }
        for (Map.Entry<Integer, Float> entry : current.entrySet()) {
            int id = entry.getKey();
            Float previous = seenEntities.get(id);
            if (previous == null) {
                if (wantSpawn) {
                    dispatch(Script.Event.ENTITY_SPAWN, script -> ApiGame.entity(script, id));
                }
            } else if (wantDeath && previous > 0.0f && entry.getValue() <= 0.0f) {
                dispatch(Script.Event.ENTITY_DEATH, script -> ApiGame.entity(script, id));
            }
        }
        for (Map.Entry<Integer, Float> entry : seenEntities.entrySet()) {
            int id = entry.getKey();
            if (current.containsKey(id)) {
                continue;
            }
            if (wantRemove) {
                dispatch(Script.Event.ENTITY_REMOVE, script -> LuaValue.valueOf(id));
            }
            // Game.capture drops anything that fails isEntityAlive, so for a living entity leaving the
            // capture set is usually how a death looks from here.
            if (wantDeath && entry.getValue() > 0.0f) {
                dispatch(Script.Event.ENTITY_DEATH, script -> LuaValue.valueOf(id));
            }
        }
        seenEntities = current;
    }

    private static void trackPlayer(FrameState state) {
        if (!state.player.valid) {
            lastHealth = -1.0f;
            lastSlot = -1;
            return;
        }

        float health = state.player.health;
        if (lastHealth >= 0.0f && health != lastHealth) {
            float previous = lastHealth;
            dispatch(Script.Event.HEALTH_CHANGE,
                    script -> LuaValue.varargsOf(LuaValue.valueOf(health), LuaValue.valueOf(previous)));
            if (health <= 0.0f && previous > 0.0f) {
                dispatch(Script.Event.DEATH);
            } else if (previous <= 0.0f && health > 0.0f) {
                dispatch(Script.Event.RESPAWN);
            }
        }
        lastHealth = health;

        if (!anyHandler(Script.Event.SLOT_CHANGE)) {
            lastSlot = -1;
            return;
        }
        int slot = Game.selectedSlot();
        if (lastSlot >= 0 && slot != lastSlot) {
            int previous = lastSlot;
            dispatch(Script.Event.SLOT_CHANGE,
                    script -> LuaValue.varargsOf(LuaValue.valueOf(slot), LuaValue.valueOf(previous)));
        }
        lastSlot = slot;
    }

    private static void trackModules() {
        if (!anyHandler(Script.Event.MODULE_TOGGLED)) {
            toggledOn.clear();
            return;
        }
        List<Module> enabled = new ArrayList<>();
        for (Module module : ModuleManager.all()) {
            if (!module.enabled()) {
                continue;
            }
            enabled.add(module);
            if (!toggledOn.contains(module)) {
                String name = module.name;
                dispatch(Script.Event.MODULE_TOGGLED,
                        script -> LuaValue.varargsOf(LuaValue.valueOf(name), LuaValue.TRUE));
            }
        }
        for (Module module : toggledOn) {
            if (!enabled.contains(module)) {
                String name = module.name;
                dispatch(Script.Event.MODULE_TOGGLED,
                        script -> LuaValue.varargsOf(LuaValue.valueOf(name), LuaValue.FALSE));
            }
        }
        toggledOn = enabled;
    }

    private static void trackEffects() {
        boolean wantAdded = anyHandler(Script.Event.EFFECT_ADDED);
        boolean wantRemoved = anyHandler(Script.Event.EFFECT_REMOVED);
        if ((!wantAdded && !wantRemoved) || !context.state.player.valid) {
            seenEffects.clear();
            return;
        }

        Map<String, Integer> current = new HashMap<>();
        for (Game.EffectView effect : Game.statusEffects(context.state.player.handle)) {
            current.put(effect.id(), effect.amplifier());
            Integer previous = seenEffects.get(effect.id());
            if (wantAdded && (previous == null || previous != effect.amplifier())) {
                dispatch(Script.Event.EFFECT_ADDED, script -> LuaValue.varargsOf(LuaValue.valueOf(effect.id()),
                        LuaValue.valueOf(effect.amplifier() + 1), LuaValue.valueOf(effect.duration())));
            }
        }
        if (wantRemoved) {
            for (String id : seenEffects.keySet()) {
                if (!current.containsKey(id)) {
                    dispatch(Script.Event.EFFECT_REMOVED, script -> LuaValue.valueOf(id));
                }
            }
        }
        seenEffects = current;
    }

    /** Effects change on the tick, so they are watched from here rather than every frame. */
    public static void tick(Context frame) {
        ticks++;
        within(frame, () -> {
            trackEffects();
            dispatch(Script.Event.TICK);
        });
    }

    public static void render() {
        draw(() -> {
            dispatch(Script.Event.RENDER3D);
            dispatch(Script.Event.RENDER);
        });
    }

    public static void key(int code, boolean down, int modifiers) {
        if (anyHandler(Script.Event.KEY)) {
            dispatch(Script.Event.KEY, script -> LuaValue.varargsOf(LuaValue.valueOf(code), LuaValue.valueOf(down),
                    LuaValue.valueOf(modifiers)));
        }
    }

    public static void charTyped(int codepoint) {
        if (anyHandler(Script.Event.CHAR_TYPED)) {
            // The character itself as well as its code point: a script watching what was typed wants the
            // letter, not the number.
            String character = new String(Character.toChars(codepoint));
            dispatch(Script.Event.CHAR_TYPED,
                    script -> LuaValue.varargsOf(LuaValue.valueOf(codepoint), LuaValue.valueOf(character)));
        }
    }

    public static void mouse(int button, boolean down, double x, double y) {
        if (anyHandler(Script.Event.MOUSE)) {
            dispatch(Script.Event.MOUSE, script -> LuaValue.varargsOf(new LuaValue[] {LuaValue.valueOf(button),
                    LuaValue.valueOf(down), LuaValue.valueOf(x), LuaValue.valueOf(y)}));
        }
    }

    public static void mouseMove(double x, double y) {
        if (anyHandler(Script.Event.MOUSE_MOVE)) {
            dispatch(Script.Event.MOUSE_MOVE, script -> LuaValue.varargsOf(LuaValue.valueOf(x), LuaValue.valueOf(y)));
        }
    }

    public static void scroll(double x, double y) {
        if (anyHandler(Script.Event.SCROLL)) {
            dispatch(Script.Event.SCROLL, script -> LuaValue.varargsOf(LuaValue.valueOf(x), LuaValue.valueOf(y)));
        }
    }

    /** The frame in flight, or null outside one. Every binding that touches the game asks for this and refuses politely when it is not there. */
    static Context context() {
        return context;
    }

    /** Runs script code against a context from outside the frame: the tick hook, where modules act. */
    static void within(Context frame, Runnable body) {
        Context outer = context;
        context = frame;
        try {
            body.run();
        } finally {
            context = outer;
        }
    }

    /*
     * The renderer's clip stack is shared with the HUD and the menu, which are drawn after the
     * scripts are: one left open by a script would clip them too, so the depth is tracked and
     * unwound at the end of every callback.
     */

    /** Marks the span in which the renderer's batch is open, which is the only time a script may draw. */
    static void draw(Runnable body) {
        int depth = clips;
        drawing++;
        try {
            body.run();
        } finally {
            while (clips > depth) {
                noteClipPop();
            }
            drawing--;
        }
    }

    static boolean drawing() {
        return drawing > 0;
    }

    static void noteClipPush() {
        clips++;
    }

    static void noteClipPop() {
        if (clips > 0) {
            clips--;
            Renderer.instance().popClip();
        }
    }

    /**
     * True only while a script's chunk and its load handlers are running. Modules may be declared then
     * and at no other time: the module list is walked every tick and every frame, and appending to it
     * from inside one of those walks would pull the ground out from under the loop.
     */
    static boolean loading() {
        return loading;
    }

    /** Per-script persistent storage, saved beside the client. */
    public static Config store() {
        return store;
    }

    public static void saveStore() {
        if (initialised) {
            store.save(storePath);
        }
    }

    static long ticks() {
        return ticks;
    }

    static long frames() {
        return frames;
    }

    /** How long the last frame's overlay took, from the scripts' first event to the end of the draw. */
    static long overlayMicros() {
        return overlayMicros;
    }
}
