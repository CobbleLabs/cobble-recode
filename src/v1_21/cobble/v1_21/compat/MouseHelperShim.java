package cobble.v1_21.compat;

import net.minecraft.client.Mouse;

/** 1.8.9's MouseHelper on 1.21.11's Mouse. */
public final class MouseHelperShim {
    private MouseHelperShim() {}

    public static void grabMouseCursor(Mouse mouse) {
        mouse.lockCursor();
    }
}
