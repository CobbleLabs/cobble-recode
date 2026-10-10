package cobble.platform;

import cobble.boot.Hook;
import cobble.game.Game;
import cobble.render.Renderer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * What one Minecraft version supplies to the version-neutral client: where
 * its hooks go, how its window reports input, and the parts of its renderer
 * the overlay has to cooperate with. Boot loads the adapter for the version
 * it detected and installs it before anything else of the client runs.
 */
public interface Platform {
    static Platform get() {
        return Holder.platform;
    }

    static void install(Platform platform) {
        Holder.platform = platform;
        Game.install(platform.game());
    }

    List<Hook> hooks();

    /**
     * The classes holding a static handler for every hook, by the hook's
     * handler name; the first that has one wins. A handler may take wider
     * types than the hook passes, so one written against a superclass serves
     * a version that patches the subclass, and may leave off trailing
     * arguments it has no use for.
     */
    List<Class<?>> hookHandlers();

    Game game();

    /** Registers what the version listens to whether or not any module is on. */
    void startTrackers();

    /** Live keyboard and mouse state, in GLFW key codes and framebuffer pixels from the top left. */
    boolean keyDown(int glfwKey);

    boolean buttonDown(int button);

    double cursorX();

    double cursorY();

    /**
     * Opens the empty screen that hands the pointer and keyboard to the
     * overlay; while it is up its input reaches {@link cobble.hook.Input}.
     */
    void showMenuScreen();

    void hideMenuScreen();

    /** Whether the fixed-function pipeline exists: the matrix stacks, alpha test and client arrays. */
    boolean compatibilityProfile();

    boolean gl33();

    /** Brings whatever state cache the game's renderer keeps back in line with the driver. */
    void resyncGlState();

    /** Draws, inside the overlay's frame, what the legacy code drew where the platform could not draw it then. */
    void drawDeferred(Renderer gfx);

    /** Whether DrawEvent handlers draw as they are called, rather than being replayed by {@link #drawDeferred}. */
    boolean drawsImmediately();

    /* The GL entry points LWJGL 2 and 3 spell differently; everything else the overlay calls is the same in both. */
    void glGetIntegers(int name, IntBuffer into);

    void glGetFloats(int name, FloatBuffer into);

    void glGetBooleans(int name, ByteBuffer into);

    void glLoadMatrix(FloatBuffer matrix);

    void glUniformMatrix4(int location, FloatBuffer matrix);

    String clipboard();

    void setClipboard(String text);

    record Session(String username, String uuid, String token) {}

    Session session();

    /** Swaps the account the game joins servers as; a null part keeps the current one. */
    void setSession(Session session);

    final class Holder {
        private static Platform platform;

        private Holder() {}
    }
}
