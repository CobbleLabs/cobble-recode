package cobble.util;

import cobble.event.EventBus;
import cobble.event.MouseEvent;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Mouse;

public final class InputHookManager {
    /* LWJGL 2's event record: button, state, x, y, wheel, nanoseconds. */
    private static final int EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8;

    /* Mouse.create allocates both buffers again, and the game recreates the
     * mouse with its window on every fullscreen toggle, so they are read on
     * each use: a kept one would take events the game never looks at again. */
    private static final Field mouseButtons = mouseField("buttons");
    private static final Field mouseEvents = mouseField("readBuffer");

    private static final int MOUSE_BUTTONS = 8;
    /* Per button: whether a clicker has it shown as its queued events, what
     * they last said, and what the HUD's turn displaced, to be put back. */
    private static final boolean[] shownQueued = new boolean[MOUSE_BUTTONS];
    private static final boolean[] queuedDown = new boolean[MOUSE_BUTTONS];
    private static final byte[] physicalDown = new byte[MOUSE_BUTTONS];
    private static final KeyBinding[] displacedBinding = new KeyBinding[MOUSE_BUTTONS];
    private static final boolean[] bindingDown = new boolean[MOUSE_BUTTONS];
    private static boolean showingQueued;

    private InputHookManager() {
    }

    private static Field mouseField(String name) {
        try {
            Field var0 = Mouse.class.getDeclaredField(name);
            var0.setAccessible(true);
            return var0;
        } catch (ReflectiveOperationException var1) {
            throw new IllegalStateException("LWJGL Mouse." + name + " is unavailable", var1);
        }
    }

    private static ByteBuffer current(Field field) {
        try {
            return (ByteBuffer) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("LWJGL Mouse." + field.getName() + " is unavailable", e);
        }
    }

    public static void simulateMouseButton(int var0, boolean var1) {
        EventBus.post(new MouseEvent(var0, var1, Mouse.getX(), Mouse.getY(), 0, 0, 0));
        setMouseButtonState(var0, var1);
    }

    public static void setMouseButtonState(int var0, boolean var1) {
        current(mouseButtons).put(var0, (byte)(var1 ? 1 : 0));
    }

    /**
     * Queues a button event as if the window had sent it, which is what a
     * click posted to the game window does: the game's own mouse loop reads it
     * on the next tick, and so does everything hooked into that loop, Lunar's
     * CPS counter included. Mouse.buttons is left alone, so isButtonDown keeps
     * reporting the physical button. Client thread only, where poll and next run.
     */
    public static void queueMouseButton(int button, boolean pressed) {
        ByteBuffer events = current(mouseEvents);
        /* The same compact, append, flip that Mouse.poll does with what the window reported. */
        events.compact();
        if (events.remaining() >= EVENT_SIZE) {
            events.put((byte) button);
            events.put((byte) (pressed ? 1 : 0));
            if (Mouse.isGrabbed()) {
                events.putInt(0);
                events.putInt(0);
            } else {
                events.putInt(Mouse.getX());
                events.putInt(Mouse.getY());
            }
            events.putInt(0);
            events.putLong(System.nanoTime());
        }
        events.flip();
        queuedDown[button] = pressed;
    }

    /* Starts from the physical button, which is what the game last saw of it. */
    public static void showQueued(int button, boolean shown) {
        if (shown && !shownQueued[button]) {
            queuedDown[button] = Mouse.isButtonDown(button);
        }
        shownQueued[button] = shown;
    }

    /**
     * Right before the HUD is drawn, a button a clicker drives reads as its own
     * presses and releases, both to Mouse.isButtonDown and to the key bound to
     * it, which is what keystroke displays draw from. Only for the HUD's turn:
     * everything else goes on reading the button the hand is holding.
     */
    public static void showQueuedButtons() {
        ByteBuffer buttons = current(mouseButtons);
        for (int button = 0; button < MOUSE_BUTTONS; button++) {
            if (!shownQueued[button]) {
                continue;
            }
            physicalDown[button] = buttons.get(button);
            buttons.put(button, (byte) (queuedDown[button] ? 1 : 0));
            displacedBinding[button] = boundTo(button);
            if (displacedBinding[button] != null) {
                bindingDown[button] = displacedBinding[button].isKeyDown();
                KeyBinding.setKeyBindState(button - 100, queuedDown[button]);
            }
        }
        showingQueued = true;
    }

    public static void showPhysicalButtons() {
        if (!showingQueued) {
            return;
        }
        ByteBuffer buttons = current(mouseButtons);
        for (int button = 0; button < MOUSE_BUTTONS; button++) {
            if (!shownQueued[button]) {
                continue;
            }
            buttons.put(button, physicalDown[button]);
            if (displacedBinding[button] != null) {
                KeyBinding.setKeyBindState(button - 100, bindingDown[button]);
                displacedBinding[button] = null;
            }
        }
        showingQueued = false;
    }

    /* Attack or use, whichever is on the button; a mouse button is bound as its number less a hundred. */
    private static KeyBinding boundTo(int button) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        if (settings.keyBindAttack.getKeyCode() == button - 100) {
            return settings.keyBindAttack;
        }
        return settings.keyBindUseItem.getKeyCode() == button - 100 ? settings.keyBindUseItem : null;
    }
}
