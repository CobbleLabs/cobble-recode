package cobble.v1_21.compat;

import cobble.hook.LwjglKeys;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/** LWJGL 2's Keyboard on GLFW, in LWJGL 2's key codes; only the polling half, which is what modules use. */
public final class Keyboard {
    private Keyboard() {}

    public static boolean isKeyDown(int key) {
        int glfw = LwjglKeys.glfw(key);
        return glfw > 0 && GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), glfw)
                == GLFW.GLFW_PRESS;
    }

    /* GLFW always reports repeats; there is nothing to switch. */
    public static void enableRepeatEvents(boolean enable) {}
}
