package cobble.legacy;

import cobble.core.Log;
import cobble.hook.LwjglKeys;
import cobble.hook.MenuScreen;
import cobble.platform.Platform;
import cobble.render.Renderer;
import java.lang.reflect.Field;
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
 * What 1.7.10 and 1.8.9 have in common: LWJGL 2 input and GL, the
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

    /* The fields are final, so they go through reflection. */
    @Override
    public void setSession(Session replacement) {
        net.minecraft.util.Session session = Minecraft.getMinecraft().getSession();
        try {
            set(session, "username", replacement.username());
            set(session, "playerID", replacement.uuid());
            set(session, "token", replacement.token());
        } catch (ReflectiveOperationException e) {
            Log.error("alts", "session not changed: %s", e);
        }
    }

    private static void set(net.minecraft.util.Session session, String name, String value)
            throws ReflectiveOperationException {
        if (value == null) {
            return;
        }
        Field field = net.minecraft.util.Session.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    /* The legacy code draws through the game's own GL here, as it is called. */
    @Override
    public void drawDeferred(Renderer gfx) {}
}
