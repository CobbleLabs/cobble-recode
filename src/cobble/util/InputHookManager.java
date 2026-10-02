package cobble.util;

import cobble.event.EventBus;
import cobble.event.MouseEvent;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.lwjgl.input.Mouse;

public final class InputHookManager {
    /* LWJGL 2's event record: button, state, x, y, wheel, nanoseconds. */
    private static final int EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8;

    private static final ByteBuffer mouseButtons = mouseField("buttons");
    private static final ByteBuffer mouseEvents = mouseField("readBuffer");

    private InputHookManager() {
    }

    private static ByteBuffer mouseField(String name) {
        try {
            Field var0 = Mouse.class.getDeclaredField(name);
            var0.setAccessible(true);
            return (ByteBuffer)var0.get(null);
        } catch (ReflectiveOperationException var1) {
            throw new IllegalStateException("LWJGL Mouse." + name + " is unavailable", var1);
        }
    }

    public static void simulateMouseButton(int var0, boolean var1) {
        EventBus.post(new MouseEvent(var0, var1, Mouse.getX(), Mouse.getY(), 0, 0, 0));
        setMouseButtonState(var0, var1);
    }

    public static void setMouseButtonState(int var0, boolean var1) {
        mouseButtons.put(var0, (byte)(var1 ? 1 : 0));
    }

    /**
     * Queues a button event as if the window had sent it, which is what a
     * click posted to the game window does: the game's own mouse loop reads it
     * on the next tick, and so does everything hooked into that loop, Lunar's
     * CPS counter included. Mouse.buttons is left alone, so isButtonDown keeps
     * reporting the physical button. Client thread only, where poll and next run.
     */
    public static void queueMouseButton(int button, boolean pressed) {
        ByteBuffer events = mouseEvents;
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
    }
}
