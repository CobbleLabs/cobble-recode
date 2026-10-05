package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;

/** LWJGL 2's Display size: the game window's framebuffer. */
public final class Display {
    private Display() {}

    public static int getWidth() {
        return MinecraftClient.getInstance().getWindow().getFramebufferWidth();
    }

    public static int getHeight() {
        return MinecraftClient.getInstance().getWindow().getFramebufferHeight();
    }
}
