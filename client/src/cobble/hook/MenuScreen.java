package cobble.hook;

import cobble.core.Log;
import java.nio.IntBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * An empty screen that hands the pointer and the keyboard to the menu and the
 * radar. Opening a screen is what makes the game ungrab the mouse, release
 * every held key binding and stop reading input for itself, so the client
 * borrows that instead of taking the window over. It draws nothing: the menu
 * renders from the frame hook, cursor included, and the system cursor is
 * swapped for a transparent one.
 */
public final class MenuScreen extends GuiScreen {
    private static final String SCOPE = "input";

    private static Cursor blank;

    public static void show() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof MenuScreen)) {
            mc.displayGuiScreen(new MenuScreen());
        }
    }

    public static void hide() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof MenuScreen) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void initGui() {
        Input.setCapturing(true);
        Keyboard.enableRepeatEvents(true);
        try {
            if (blank == null) {
                IntBuffer pixel = BufferUtils.createIntBuffer(1);
                blank = new Cursor(1, 1, 0, 0, 1, pixel, null);
            }
            Mouse.setNativeCursor(blank);
        } catch (LWJGLException e) {
            Log.warn(SCOPE, "system cursor stays visible: %s", e.getMessage());
        }
    }

    @Override
    public void onGuiClosed() {
        Input.setCapturing(false);
        Keyboard.enableRepeatEvents(false);
        try {
            Mouse.setNativeCursor(null);
        } catch (LWJGLException e) {
            Log.warn(SCOPE, "could not restore the system cursor: %s", e.getMessage());
        }
    }

    /*
     * The game only asks a screen for input from the tick, twenty times a
     * second, which would leave the drawn cursor stepping. Draining here runs
     * it every frame; the tick then finds the queues empty.
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        while (Mouse.next()) {
            handleMouseInput();
        }
        while (Keyboard.next()) {
            handleKeyboardInput();
        }
    }

    @Override
    public void handleKeyboardInput() {
        int key = Input.glfwKey(Keyboard.getEventKey());
        boolean down = Keyboard.getEventKeyState();
        int modifiers = Input.modifiers();
        if (key != 0) {
            int action = !down ? Input.RELEASE : Keyboard.isRepeatEvent() ? Input.REPEAT : Input.PRESS;
            Input.push(new Input.Event(Input.Event.Kind.KEY, key, action, modifiers, 0, 0));
        }
        char character = Keyboard.getEventCharacter();
        if (down && character >= 32) {
            Input.push(new Input.Event(Input.Event.Kind.CHAR, character, Input.PRESS, modifiers, 0, 0));
        }
        mc.dispatchKeypresses();
    }

    @Override
    public void handleMouseInput() {
        double x = Mouse.getEventX();
        double y = Input.flipY(Mouse.getEventY());
        int modifiers = Input.modifiers();
        Input.push(new Input.Event(Input.Event.Kind.MOUSE_MOVE, 0, 0, modifiers, x, y));
        int button = Mouse.getEventButton();
        if (button >= 0) {
            int action = Mouse.getEventButtonState() ? Input.PRESS : Input.RELEASE;
            Input.push(new Input.Event(Input.Event.Kind.MOUSE_BUTTON, button, action, modifiers, x, y));
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            Input.push(new Input.Event(Input.Event.Kind.SCROLL, 0, 0, modifiers, 0, wheel / 120.0));
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
