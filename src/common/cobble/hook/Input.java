package cobble.hook;

import cobble.platform.Platform;
import java.util.ArrayList;
import java.util.List;

/**
 * Keyboard and mouse in GLFW codes, which is what binds in the config and the
 * Lua API have always been written in. A version on LWJGL 2 translates its
 * codes in its adapter and nowhere else.
 *
 * <p>Events come from two places. While the menu screen is up it forwards
 * every event the game hands it; the rest of the time the game consumes its
 * input itself, so key and button edges are recovered by polling live state
 * once a frame instead.
 */
public final class Input {
    public static final int RELEASE = 0;
    public static final int PRESS = 1;
    public static final int REPEAT = 2;

    public static final int KEY_ESCAPE = 256;
    public static final int KEY_ENTER = 257;
    public static final int KEY_TAB = 258;
    public static final int KEY_BACKSPACE = 259;
    public static final int KEY_INSERT = 260;
    public static final int KEY_DELETE = 261;
    public static final int KEY_RIGHT = 262;
    public static final int KEY_LEFT = 263;
    public static final int KEY_DOWN = 264;
    public static final int KEY_UP = 265;
    public static final int KEY_HOME = 268;
    public static final int KEY_END = 269;
    public static final int KEY_LEFT_SHIFT = 340;
    public static final int KEY_LEFT_CONTROL = 341;
    public static final int KEY_LEFT_ALT = 342;
    public static final int KEY_RIGHT_SHIFT = 344;
    public static final int KEY_RIGHT_CONTROL = 345;
    public static final int KEY_RIGHT_ALT = 346;

    public static final int MOD_SHIFT = 1;
    public static final int MOD_CONTROL = 2;
    public static final int MOD_ALT = 4;

    public static final int MOUSE_LEFT = 0;
    public static final int MOUSE_RIGHT = 1;
    public static final int MOUSE_MIDDLE = 2;

    public static final int LAST_KEY = 348;
    private static final int MOUSE_BUTTONS = 8;

    public record Event(Kind kind, int code, int action, int modifiers, double x, double y) {
        public enum Kind { KEY, CHAR, MOUSE_BUTTON, MOUSE_MOVE, SCROLL }
    }

    private static final List<Event> queue = new ArrayList<>();
    private static final boolean[] keysDown = new boolean[LAST_KEY + 1];
    private static final boolean[] buttonsDown = new boolean[MOUSE_BUTTONS];
    private static boolean capturing;
    private static double cursorX;
    private static double cursorY;

    private Input() {}

    public static boolean keyHeld(int key) {
        return key > 0 && key <= LAST_KEY && Platform.get().keyDown(key);
    }

    /** A bind is a mouse button at eight and below and a key above it. */
    public static boolean bindHeld(int code) {
        if (code > MOUSE_BUTTONS) {
            return keyHeld(code);
        }
        return buttonHeld(code);
    }

    /**
     * A click on a menu toggle is not the player attacking: while the menu owns
     * the pointer, the buttons read as up for everything that acts on them.
     */
    public static boolean attackHeld() {
        return buttonHeld(MOUSE_LEFT);
    }

    public static boolean useHeld() {
        return buttonHeld(MOUSE_RIGHT);
    }

    private static boolean buttonHeld(int button) {
        return !capturing && Platform.get().buttonDown(button);
    }

    public static double cursorX() {
        return cursorX;
    }

    public static double cursorY() {
        return cursorY;
    }

    /**
     * Set by the menu screen as it opens and closes. The edge state is cleared
     * both ways so nothing held across the switch reads as a fresh press.
     */
    public static void setCapturing(boolean value) {
        capturing = value;
        Platform platform = Platform.get();
        for (int key = 0; key <= LAST_KEY; ++key) {
            keysDown[key] = platform.keyDown(key);
        }
        for (int button = 0; button < MOUSE_BUTTONS; ++button) {
            buttonsDown[button] = platform.buttonDown(button);
        }
    }

    public static int modifiers() {
        int modifiers = 0;
        if (keyHeld(KEY_LEFT_SHIFT) || keyHeld(KEY_RIGHT_SHIFT)) {
            modifiers |= MOD_SHIFT;
        }
        if (keyHeld(KEY_LEFT_CONTROL) || keyHeld(KEY_RIGHT_CONTROL)) {
            modifiers |= MOD_CONTROL;
        }
        if (keyHeld(KEY_LEFT_ALT) || keyHeld(KEY_RIGHT_ALT)) {
            modifiers |= MOD_ALT;
        }
        return modifiers;
    }

    /** For the menu screen of each version, which forwards what the game hands it. */
    public static void push(Event event) {
        if (event.kind() == Event.Kind.MOUSE_MOVE || event.kind() == Event.Kind.MOUSE_BUTTON) {
            cursorX = event.x();
            cursorY = event.y();
        }
        if (queue.size() < 256) {
            queue.add(event);
        }
    }

    /**
     * Recovers edges from live state while the game owns the event queue.
     * Runs once a frame; the menu screen feeds the queue directly instead.
     */
    public static void poll() {
        if (capturing) {
            return;
        }
        Platform platform = Platform.get();
        double x = platform.cursorX();
        double y = platform.cursorY();
        if (x != cursorX || y != cursorY) {
            push(new Event(Event.Kind.MOUSE_MOVE, 0, 0, 0, x, y));
        }
        int modifiers = modifiers();
        for (int key = 0; key <= LAST_KEY; ++key) {
            boolean down = platform.keyDown(key);
            if (down != keysDown[key]) {
                keysDown[key] = down;
                push(new Event(Event.Kind.KEY, key, down ? PRESS : RELEASE, modifiers, 0, 0));
            }
        }
        for (int button = 0; button < MOUSE_BUTTONS; ++button) {
            boolean down = platform.buttonDown(button);
            if (down != buttonsDown[button]) {
                buttonsDown[button] = down;
                push(new Event(Event.Kind.MOUSE_BUTTON, button, down ? PRESS : RELEASE, modifiers, x, y));
            }
        }
    }

    public static List<Event> drain() {
        List<Event> events = new ArrayList<>(queue);
        queue.clear();
        return events;
    }
}
