package cobble.legacy;

import cobble.core.Log;
import cobble.hook.LwjglKeys;
import cobble.hook.MenuScreen;
import cobble.platform.Platform;
import cobble.render.Renderer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

/**
 * 1.8.9's side of the platform: LWJGL 2 input and GL, the
 * compatibility profile, and the MCP-named screen and session classes.
 */
public abstract class LegacyPlatform implements Platform {
    private static final int MOUSE_BUTTONS = 8;

    @Override
    public boolean keyDown(int glfwKey) {
        int key = LwjglKeys.lwjgl(glfwKey);
        return key != 0 && Keyboard.isKeyDown(key);
    }

    @Override
    public boolean buttonDown(int button) {
        return button < Math.min(MOUSE_BUTTONS, Mouse.getButtonCount()) && Mouse.isButtonDown(button);
    }

    @Override
    public double cursorX() {
        return Mouse.getX();
    }

    @Override
    public double cursorY() {
        return flipY(Mouse.getY());
    }

    /** LWJGL measures from the bottom edge; everything above it measures from the top. */
    public static double flipY(int lwjglY) {
        return Minecraft.getMinecraft().displayHeight - lwjglY - 1;
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
        return true;
    }

    @Override
    public boolean gl33() {
        return GLContext.getCapabilities().OpenGL33;
    }

    @Override
    public void glGetIntegers(int name, IntBuffer into) {
        GL11.glGetInteger(name, into);
    }

    @Override
    public void glGetFloats(int name, FloatBuffer into) {
        GL11.glGetFloat(name, into);
    }

    @Override
    public void glGetBooleans(int name, ByteBuffer into) {
        GL11.glGetBoolean(name, into);
    }

    @Override
    public void glLoadMatrix(FloatBuffer matrix) {
        GL11.glLoadMatrix(matrix);
    }

    @Override
    public void glUniformMatrix4(int location, FloatBuffer matrix) {
        GL20.glUniformMatrix4(location, false, matrix);
    }

    @Override
    public String clipboard() {
        return GuiScreen.getClipboardString();
    }

    @Override
    public void setClipboard(String text) {
        GuiScreen.setClipboardString(text);
    }

    @Override
    public Session session() {
        net.minecraft.util.Session session = Minecraft.getMinecraft().getSession();
        return new Session(session.getUsername(), session.getPlayerID(), session.getToken());
    }

    /*
     * The fields are final, so they go through reflection, and by what they
     * hold rather than by name: CheatBreaker runs them under names this
     * source never sees. Null leaves a part as it is.
     */
    @Override
    public void setSession(Session replacement) {
        net.minecraft.util.Session session = Minecraft.getMinecraft().getSession();
        Object username = session.getUsername();
        Object uuid = session.getPlayerID();
        Object token = session.getToken();
        try {
            for (Field field : net.minecraft.util.Session.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(session);
                if (value == username && replacement.username() != null) {
                    field.set(session, replacement.username());
                } else if (value == uuid && replacement.uuid() != null) {
                    field.set(session, replacement.uuid());
                } else if (value == token && replacement.token() != null) {
                    field.set(session, replacement.token());
                }
            }
        } catch (ReflectiveOperationException e) {
            Log.error("alts", "session not changed: %s", e);
        }
    }

    /* The legacy code draws through the game's own GL here, as it is called. */
    @Override
    public void drawDeferred(Renderer gfx) {}
}
