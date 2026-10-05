package cobble.v1_21;

import cobble.boot.Hook;
import cobble.core.Log;
import cobble.game.Game;
import cobble.hook.Input;
import cobble.platform.Platform;
import cobble.render.Renderer;
import cobble.v1_21.compat.RenderPasses;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Lunar's 1.21.11: Fabric under intermediary names, LWJGL 3 with GLFW input,
 * and a core 3.3 context with no fixed-function state at all.
 */
public class PlatformAdapter implements Platform {
    private static final int MOUSE_BUTTONS = 8;
    /* The codes GLFW names; asking it about any other is an error it reports to the game's log. */
    private static final boolean[] KEYS = new boolean[Input.LAST_KEY + 1];

    static {
        int[] ranges = {32, 32, 39, 39, 44, 57, 59, 59, 61, 61, 65, 93, 96, 96, 161, 162, 256, 269, 280, 284,
            290, 314, 320, 336, 340, 348};
        for (int index = 0; index < ranges.length; index += 2) {
            for (int key = ranges[index]; key <= ranges[index + 1]; ++key) {
                KEYS[key] = true;
            }
        }
    }

    private final Game game = new GameAdapter();

    @Override
    public List<Hook> hooks() {
        return HookTable.ALL;
    }

    @Override
    public List<Class<?>> hookHandlers() {
        return List.of(Hooks.class);
    }

    @Override
    public Game game() {
        return game;
    }

    /* The legacy trackers watch 1.8.9's packets; nothing here listens before a module does. */
    @Override
    public void startTrackers() {}

    private static long window() {
        return MinecraftClient.getInstance().getWindow().getHandle();
    }

    @Override
    public boolean keyDown(int glfwKey) {
        return glfwKey >= 0 && glfwKey < KEYS.length && KEYS[glfwKey]
                && GLFW.glfwGetKey(window(), glfwKey) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean buttonDown(int button) {
        return button < MOUSE_BUTTONS && GLFW.glfwGetMouseButton(window(), button) == GLFW.GLFW_PRESS;
    }

    @Override
    public double cursorX() {
        return cursor()[0];
    }

    @Override
    public double cursorY() {
        return cursor()[1];
    }

    /** The cursor in framebuffer pixels: GLFW reports it in window units, which differ on a scaled display. */
    static double[] cursor() {
        Window window = MinecraftClient.getInstance().getWindow();
        double[] x = new double[1];
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(window.getHandle(), x, y);
        double scaleX = window.getWidth() > 0 ? (double) window.getFramebufferWidth() / window.getWidth() : 1.0;
        double scaleY = window.getHeight() > 0 ? (double) window.getFramebufferHeight() / window.getHeight() : 1.0;
        return new double[] {x[0] * scaleX, y[0] * scaleY};
    }

    @Override
    public void showMenuScreen() {
        MenuScreen.show();
    }

    @Override
    public void hideMenuScreen() {
        MenuScreen.hide();
    }

    @Override
    public boolean compatibilityProfile() {
        return false;
    }

    @Override
    public boolean gl33() {
        return GL.getCapabilities().OpenGL33;
    }

    /*
     * The device caches blending, depth, culling, the scissor and the bound
     * textures, all of which the state guard puts back exactly as it found
     * them, so the cache never stops being true.
     */
    @Override
    public void resyncGlState() {}

    @Override
    public void drawDeferred(Renderer gfx) {
        RenderPasses.flush(gfx);
    }

    @Override
    public void glGetIntegers(int name, IntBuffer into) {
        GL11.glGetIntegerv(name, into);
    }

    @Override
    public void glGetFloats(int name, FloatBuffer into) {
        GL11.glGetFloatv(name, into);
    }

    @Override
    public void glGetBooleans(int name, ByteBuffer into) {
        GL11.glGetBooleanv(name, into);
    }

    /* A core context has no matrix stack, and nothing calls this where compatibilityProfile is false. */
    @Override
    public void glLoadMatrix(FloatBuffer matrix) {
        throw new UnsupportedOperationException("no fixed-function matrices on a core context");
    }

    @Override
    public void glUniformMatrix4(int location, FloatBuffer matrix) {
        GL20.glUniformMatrix4fv(location, false, matrix);
    }

    @Override
    public String clipboard() {
        return MinecraftClient.getInstance().keyboard.getClipboard();
    }

    @Override
    public void setClipboard(String text) {
        MinecraftClient.getInstance().keyboard.setClipboard(text);
    }

    @Override
    public Session session() {
        net.minecraft.client.session.Session session = MinecraftClient.getInstance().getSession();
        UUID id = session.getUuidOrNull();
        return new Session(session.getUsername(), id != null ? id.toString() : null, session.getAccessToken());
    }

    /*
     * The fields are final, so they go through reflection, and by what they
     * hold rather than by name: at runtime they carry intermediary names this
     * source never sees. Null leaves a part as it is.
     */
    @Override
    public void setSession(Session replacement) {
        net.minecraft.client.session.Session session = MinecraftClient.getInstance().getSession();
        try {
            Object username = session.getUsername();
            Object token = session.getAccessToken();
            for (Field field : net.minecraft.client.session.Session.class.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(session);
                if (field.getType() == UUID.class && replacement.uuid() != null) {
                    field.set(session, parseUuid(replacement.uuid()));
                } else if (value == username && replacement.username() != null) {
                    field.set(session, replacement.username());
                } else if (value == token && replacement.token() != null) {
                    field.set(session, replacement.token());
                }
            }
        } catch (ReflectiveOperationException e) {
            Log.error("alts", "session not changed: %s", e);
        }
    }

    /* Mojang's services hand ids out without the dashes. */
    private static UUID parseUuid(String text) {
        String dashed = text.indexOf('-') >= 0 ? text : text.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }
}
