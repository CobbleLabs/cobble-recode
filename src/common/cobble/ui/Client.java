package cobble.ui;

import cobble.boot.Native;
import cobble.core.Color;
import cobble.core.Log;
import cobble.core.Maths;
import cobble.core.Time;
import cobble.event.EventBus;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.PlayerRadar;
import cobble.game.Projection;
import cobble.game.Raycast;
import cobble.game.RotationEngine;
import cobble.hook.Input;
import cobble.module.Context;
import cobble.module.ModuleManager;
import cobble.module.client.AltManagerModule;
import cobble.module.client.ClickGuiModule;
import cobble.module.client.RadarModule;
import cobble.render.Backdrop;
import cobble.render.Renderer;
import cobble.platform.Platform;
import cobble.render.StateGuard;
import cobble.script.ScriptManager;
import cobble.util.alt.AltAccounts;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The client's lifetime and its two heartbeats. {@link #tick} runs inside the
 * game tick, where modules act; {@link #frame} runs once a frame right before
 * the buffer swap, where input is dispatched and everything is drawn. Both run
 * on the client thread; start and stop arrive on the bootstrap's.
 */
public final class Client {
    private static final String SCOPE = "client";
    private static final float TICK_SECONDS = 0.05f;

    private static final Backdrop backdrop = new Backdrop();
    private static final Projection projection = new Projection();
    private static final StateGuard guard = new StateGuard();

    private static Path directory;
    private static Path configPath;
    private static ClickGui gui;
    private static Context lastContext;
    private static Hud hud;
    private static Radar radar;
    private static AltManager altManager;
    private static volatile boolean running;
    private static boolean disabled;
    private static boolean renderReady;
    private static boolean loggedFirstFrame;
    private static int faultGuard;
    private static double lastFrameTime;

    private Client() {}

    public static ClickGui gui() {
        return gui;
    }

    public static Hud hud() {
        return hud;
    }

    public static Radar radar() {
        return radar;
    }

    public static AltManager altManager() {
        return altManager;
    }

    public static Backdrop backdrop() {
        return backdrop;
    }

    /** %APPDATA%\cobble: configs, logs and scripts. */
    public static Path directory() {
        return directory;
    }

    /** {@code arrivals} is the DLL's folder: new users arrive from there, with the example scripts. */
    public static void start(Path home, Path arrivals) throws ReflectiveOperationException {
        directory = home;
        configPath = home.resolve("cobble.cfg");
        try {
            migrateLegacyData(arrivals, home);
            migrateLegacyData(home.resolveSibling("coldwtf"), home);
        } catch (IOException e) {
            Log.warn(SCOPE, "legacy data not migrated: %s", e.getMessage());
        }
        Log.info(SCOPE, "Cobble Recode starting in %s", home);

        Renderer.instance().prepare();
        ModuleManager.registerModules();
        /* Trackers modules read from whether or not any module is on; the event
         * bus skips only disabled module owners, so these always listen. */
        Platform.get().startTrackers();
        ModuleManager.loadConfig(configPath);

        gui = new ClickGui();
        hud = new Hud();
        radar = new Radar();
        altManager = new AltManager();
        AltAccounts.load(home.resolve("alts.json"));

        /* Layout lands after construction: HUD elements read their stored
         * positions from the same document the modules just read. The menu
         * always opens on its first tab, Combat. */
        hud.loadLayout(ModuleManager.config());

        /* After the config, because a script module is registered as it loads
         * and is handed whatever the config already held for it. */
        ScriptManager.initialise(home);
        running = true;
    }

    /**
     * Arrives on the bootstrap thread, so the teardown is handed to the client
     * thread: GL objects can only be deleted where the context is current, and
     * everything else the client owns is only ever touched there.
     */
    public static void stop() throws Exception {
        Game.get().runOnClientThread(Client::teardown);
    }

    /** The menu's unload button: the bootstrap then runs {@link #stop}, exactly as for the unload key. */
    public static void requestShutdown() {
        Native.requestUnload();
    }

    private static void teardown() {
        running = false;
        /* The pointer and the keyboard go back first; the frame that would
         * have closed the menu never runs again. */
        Platform.get().hideMenuScreen();
        save();
        /* After the config is written: the settings a script module owns are
         * saved with everything else, and unloading takes those modules away. */
        ScriptManager.shutdown();
        if (renderReady) {
            Renderer.instance().destroy();
            backdrop.destroy();
            renderReady = false;
        }
        Log.info(SCOPE, "Cobble Recode unloaded");
    }

    /** After the crosshair ray and before input handling, so clicks go out ahead of the movement packet. */
    public static void tick() {
        // A paused singleplayer game ticks the client but not the player; nothing a module did would land.
        if (!running || disabled || Game.get().paused()) {
            return;
        }
        FrameState state = Game.get().capture(1.0f);
        RotationEngine.tick(state);
        if (!state.player.valid) {
            PlayerRadar.clear();
            return;
        }
        float time = (float) Time.nowSeconds();
        Context context = context(state, TICK_SECONDS, time);
        /* The block cache answers from the last tick otherwise, which is fine
         * for a raycast inside one search and wrong the moment anything places
         * or breaks a block - the cell it just changed would read as it was. */
        Raycast.resetBlockCache();
        ModuleManager.tick(context);
        /* The radar tracks whether or not its window is open: a history that
         * starts when you open it is empty exactly when you want it. */
        PlayerRadar.update(state, time);
        ScriptManager.tick(context);
    }

    public static void frame() {
        if (!running || disabled) {
            return;
        }
        try {
            renderFrame();
            faultGuard = 0;
        } catch (RuntimeException thrown) {
            recover(thrown);
        }
        if (ModuleManager.changesSettled()) {
            save();
        }
    }

    /* Layout first: the HUD elements write their sections into the live
     * document, which saveConfig then carries to disk. */
    private static void save() {
        hud.saveLayout(ModuleManager.config());
        ModuleManager.saveConfig(configPath);
    }

    /*
     * Two consecutive faults mean the context itself is unhealthy rather than
     * one stale texture: dead overlay, live game. A single one usually came
     * from GL objects the game just deleted under a world switch, so the
     * overlay's own resources are rebuilt from scratch on the next frame.
     */
    private static void recover(RuntimeException thrown) {
        Log.error(SCOPE, thrown, "frame failed");
        if (faultGuard >= 2) {
            disable("repeated frame faults");
            return;
        }
        ++faultGuard;
        PlayerRadar.clear();
        Renderer.instance().destroy();
        backdrop.destroy();
        renderReady = false;
        Log.warn(SCOPE, "recovered from a frame fault; rebuilding resources");
    }

    /** The menu screen is still swallowing every key if it was up; the game gets its input back. */
    private static void disable(String reason) {
        disabled = true;
        Platform.get().hideMenuScreen();
        Log.error(SCOPE, "%s; overlay disabled", reason);
    }

    private static boolean createRenderResources() {
        if (!Renderer.instance().createResources()) {
            return false;
        }
        if (!backdrop.createResources()) {
            Log.warn(SCOPE, "backdrop blur unavailable; panels fall back to solid fills");
        }
        return true;
    }

    /*
     * The guard spans the whole frame, resource setup included: an upload binds
     * its texture, and a binding left behind is one GlStateManager knows nothing
     * about. It would skip the game's next bind of its own font texture as
     * already done, and every vanilla string would sample our atlas instead.
     */
    private static void renderFrame() {
        guard.capture();
        try {
            drawFrame();
        } finally {
            guard.restore();
        }
    }

    private static void drawFrame() {
        Renderer gfx = Renderer.instance();
        if (!renderReady) {
            if (!createRenderResources()) {
                disable("renderer setup failed");
                return;
            }
            gui.build();
            renderReady = true;
            lastFrameTime = Time.nowSeconds();
        } else if (!gfx.resourcesValid()) {
            /* A world switch deletes every GL object in the shared context: the
             * font and icon atlases, the program, the buffers. The names stay
             * "valid" integers that draw nothing but garbage, which is exactly
             * the broken-font-after-leaving-a-world report, so the long-lived
             * objects are polled and rebuilt the moment any of them is gone. */
            Log.warn(SCOPE, "GL resources vanished (world switch?); rebuilding");
            gfx.destroy();
            backdrop.destroy();
            if (!createRenderResources()) {
                disable("renderer rebuild failed");
                return;
            }
        }

        double now = Time.nowSeconds();
        float deltaTime = Maths.clamp((float) (now - lastFrameTime), 0.0001f, 0.25f);
        lastFrameTime = now;
        float time = (float) now;

        FrameState state = Game.get().capture(Game.get().partialTicks());
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            Log.info(SCOPE, "first frame: %dx%d framebuffer, %d fps", state.windowWidth, state.windowHeight,
                    state.fps);
        }
        projection.update(state, state.windowWidth, state.windowHeight);
        Context context = context(state, deltaTime, time);

        /* Services whatever the scripts asked for last frame - a reload, a
         * script switched on - before anything walks the module list. */
        ScriptManager.beginFrame(context);
        Input.poll();
        dispatchInput(context);
        ModuleManager.advanceAnimations(deltaTime);

        if (gui.visible() && gui.blurEnabled()) {
            backdrop.capture(state.windowWidth, state.windowHeight, gui.blurStrength(), Color.rgb(0x0A0D12, 0.35f));
        }
        gfx.beginFrame(state.windowWidth, state.windowHeight, time);
        Platform.get().drawDeferred(gfx);
        ModuleManager.render(context);
        ScriptManager.render();
        hud.render(context);
        ScriptManager.renderHud(context, gui.interactive());
        gui.render(context);
        radar.render(context);
        altManager.render(context);
        gfx.endFrame();
        ScriptManager.endFrame();
    }

    /** The context of the latest tick or frame, for code that is not handed one, such as event handlers. */
    public static Context context() {
        return lastContext;
    }

    private static Context context(FrameState state, float deltaTime, float time) {
        Context context = new Context();
        context.state = state;
        context.projection = projection;
        context.deltaTime = deltaTime;
        context.time = time;
        lastContext = context;
        return context;
    }

    private static void dispatchInput(Context context) {
        ClickGuiModule guiModule = ModuleManager.get(ClickGuiModule.class);
        RadarModule radarModule = ModuleManager.get(RadarModule.class);
        AltManagerModule altModule = ModuleManager.get(AltManagerModule.class);
        int guiKey = guiModule.key();
        int radarKey = radarModule.key();

        /* Either window owns the keyboard while it is up - both have a field
         * you type into, and a module bind that fired mid-search would be a
         * surprise. They are never up together: opening one closes the other. */
        boolean overlayOwnsKeys = gui.interactive() || radar.interactive() || altManager.interactive();
        hud.setMenuOpen(gui.interactive());

        for (Input.Event event : Input.drain()) {
            int code = event.code();
            /* A bind at eight and below is a mouse button, so a button press toggles like a key. */
            if (event.kind() == Input.Event.Kind.MOUSE_BUTTON && event.action() == Input.PRESS
                    && code != Input.MOUSE_LEFT && code != Input.MOUSE_RIGHT && !overlayOwnsKeys
                    && !context.state.screenOpen) {
                ModuleManager.handleKey(code, context);
            }
            if (event.kind() == Input.Event.Kind.KEY && event.action() == Input.PRESS && code != 0) {
                if (!overlayOwnsKeys) {
                    if (!context.state.screenOpen) {
                        ModuleManager.handleKey(code, context);
                    } else if (code == guiKey && Game.get().multiplayerScreenOpen()) {
                        /* Where accounts get switched, the menu key opens the Alt Manager instead of the menu. */
                        altModule.setEnabled(true, context);
                    }
                } else if (altManager.interactive() && code == guiKey) {
                    altModule.setEnabled(false, context);
                } else if (gui.interactive() && !gui.capturingKey() && code == guiKey) {
                    guiModule.setEnabled(false, context);
                } else if (radar.interactive() && code == radarKey) {
                    radarModule.setEnabled(false, context);
                } else if (radar.interactive() && code == guiKey) {
                    /* The other window's bind still works from inside this one,
                     * and opening it closes this one - otherwise the only way
                     * across is two presses with a look at the world between. */
                    guiModule.setEnabled(true, context);
                } else if (gui.interactive() && !gui.capturingKey() && code == radarKey) {
                    radarModule.setEnabled(true, context);
                }
            }
            gui.handleEvent(event, context);
            radar.handleEvent(event);
            altManager.handleEvent(event);
            hud.handleEvent(event, context);

            /* Scripts see the input the game would have seen. While the menu
             * owns the cursor they see none of it, which is the same rule the
             * module binds follow - except that a click which missed the window
             * is offered to the script HUD elements: that is when they are
             * draggable, and the only moment they compete for the pointer. */
            if (gui.interactive()) {
                float x = (float) event.x();
                float y = (float) event.y();
                if (event.kind() == Input.Event.Kind.MOUSE_BUTTON && code == Input.MOUSE_LEFT) {
                    if (event.action() == Input.PRESS) {
                        if (!gui.contains(x, y)) {
                            ScriptManager.hudPress(x, y);
                        }
                    } else {
                        ScriptManager.hudRelease();
                    }
                } else if (event.kind() == Input.Event.Kind.MOUSE_MOVE) {
                    ScriptManager.hudDrag(x, y);
                }
                continue;
            }
            switch (event.kind()) {
                case KEY -> {
                    if (event.action() != Input.REPEAT) {
                        ScriptManager.key(code, event.action() == Input.PRESS, event.modifiers());
                    }
                }
                case CHAR -> ScriptManager.charTyped(code);
                case MOUSE_BUTTON -> ScriptManager.mouse(code, event.action() == Input.PRESS, event.x(), event.y());
                case MOUSE_MOVE -> ScriptManager.mouseMove(event.x(), event.y());
                case SCROLL -> ScriptManager.scroll(event.x(), event.y());
            }
        }
    }

    /*
     * Everything a previous build saved carried the coldwtf name, in the DLL's
     * folder or in %APPDATA%\coldwtf. The files arrive under the cobble one, and
     * only when the new home has none yet - a returning user keeps whatever is
     * already there.
     */
    private static void migrateLegacyData(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        Path scripts = to.resolve("scripts");
        Files.createDirectories(scripts);
        copyIfMissing(from.resolve("coldwtf.cfg"), to.resolve("cobble.cfg"));
        copyIfMissing(from.resolve("coldwtf.scripts.cfg"), to.resolve("cobble.scripts.cfg"));
        try (DirectoryStream<Path> configs = Files.newDirectoryStream(from, "coldwtf-*.cfg")) {
            for (Path config : configs) {
                String name = config.getFileName().toString();
                copyIfMissing(config, to.resolve("cobble" + name.substring("coldwtf".length())));
            }
        }
        Path legacyScripts = from.resolve("scripts");
        if (!Files.isDirectory(legacyScripts)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(legacyScripts, "*.lua")) {
            for (Path file : files) {
                copyIfMissing(file, scripts.resolve(file.getFileName()));
            }
        }
    }

    private static void copyIfMissing(Path from, Path to) throws IOException {
        if (Files.exists(from) && !Files.exists(to)) {
            Files.copy(from, to);
        }
    }
}
