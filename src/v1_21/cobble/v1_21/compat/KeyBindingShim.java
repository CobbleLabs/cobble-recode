package cobble.v1_21.compat;

import cobble.hook.LwjglKeys;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * 1.8.9's KeyBinding on 1.21.11's. Key codes are LWJGL 2's, mouse buttons
 * below zero as 1.8 counted them, so they agree with the Keyboard and Mouse
 * shims the same code polls.
 */
public final class KeyBindingShim {
    private static final int MOUSE_OFFSET = 100;

    private KeyBindingShim() {}

    private static InputUtil.Key key(int code) {
        return code < 0 ? InputUtil.Type.MOUSE.createFromCode(code + MOUSE_OFFSET)
                : InputUtil.Type.KEYSYM.createFromCode(LwjglKeys.glfw(code));
    }

    public static int pressTime(KeyBinding binding) {
        return binding.timesPressed;
    }

    public static void pressTime(KeyBinding binding, int times) {
        binding.timesPressed = times;
    }

    public static int getKeyCode(KeyBinding binding) {
        InputUtil.Key key = binding.boundKey;
        return key.getCategory() == InputUtil.Type.MOUSE ? key.getCode() - MOUSE_OFFSET
                : LwjglKeys.lwjgl(key.getCode());
    }

    public static boolean isKeyDown(KeyBinding binding) {
        return binding.isPressed();
    }

    /** 1.8's isPressed takes one queued press, as 1.21's wasPressed does. */
    public static boolean isPressed(KeyBinding binding) {
        return binding.wasPressed();
    }

    public static void onTick(int code) {
        KeyBinding.onKeyPressed(key(code));
    }

    public static void setKeyBindState(int code, boolean pressed) {
        KeyBinding.setKeyPressed(key(code), pressed);
    }
}
