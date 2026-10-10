package cobble.hook;

import cobble.legacy.LegacyPlatform;
import cobble.hook.LwjglKeys;
import cobble.ui.Client;
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
 *
 * <p>Opened over another screen, such as the multiplayer list the Alt Manager
 * opens from, it keeps drawing that screen underneath and hands it back on
 * close.
 *
 * <p>The game closes it on its own too: a respawn or a world change shuts
 * whatever screen is up once the terrain arrives, and a server can close it
 * like any window. The menus then close with it, or they would stay drawn
 * while the game grabs the mouse back.
 */
public final class MenuScreen extends GuiScreen {
    private final GuiScreen parent;
    private boolean released;

    private MenuScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /*
     * The press that opened the menu was read from live key state and may still
     * be queued, since the game only drains the queues from the tick; replayed
     * into the menu, the menu key would close it again. Lunar drains them on
     * every screen open, CheatBreaker and vanilla do not.
     */
    public static void show() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof MenuScreen)) {
            while (Mouse.next()) {}
            while (Keyboard.next()) {}
            mc.displayGuiScreen(new MenuScreen(mc.currentScreen));
        }
    }

    /* Also called by the menus closing from onGuiClosed, while the game is already replacing this screen. */
    public static void hide() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof MenuScreen screen && !screen.released) {
            screen.released = true;
            mc.displayGuiScreen(screen.parent);
        }
    }

    @Override
    public void initGui() {
        if (parent != null) {
            parent.setWorldAndResolution(mc, width, height);
        }
        Input.setCapturing(true);
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Input.setCapturing(false);
        Keyboard.enableRepeatEvents(false);
        if (!released) {
            released = true;
            Client.gui().close();
            Client.radar().close();
            Client.altManager().close();
        }
    }

    /*
     * The game only asks a screen for input from the tick, twenty times a
     * second, which would leave hovers and drags stepping. Draining here runs
     * it every frame; the tick then finds the queues empty.
     */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        /* Off-screen pointer: the screen underneath must not light up hovers through the window. */
        if (parent != null) {
            parent.drawScreen(-1, -1, partialTicks);
        }
        while (Mouse.next()) {
            handleMouseInput();
        }
        while (Keyboard.next()) {
            handleKeyboardInput();
        }
    }

    @Override
    public void handleKeyboardInput() {
        int key = LwjglKeys.glfw(Keyboard.getEventKey());
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
        double y = LegacyPlatform.flipY(Mouse.getEventY());
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
