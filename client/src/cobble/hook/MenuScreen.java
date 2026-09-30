package cobble.hook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * An empty screen that hands the pointer and the keyboard to the menu and the
 * radar. Opening a screen is what makes the game ungrab the mouse, release
 * every held key binding and stop reading input for itself, so the client
 * borrows that instead of taking the window over. It draws nothing: the menu
 * renders from the frame hook, over the system cursor.
 */
public final class MenuScreen extends GuiScreen {
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
    }

    @Override
    public void onGuiClosed() {
        Input.setCapturing(false);
        Keyboard.enableRepeatEvents(false);
    }

    /*
     * The game only asks a screen for input from the tick, twenty times a
     * second, which would leave hovers and drags stepping. Draining here runs
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
