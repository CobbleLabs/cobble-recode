package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

/**
 * LWJGL 2's Mouse on GLFW; only the polling half. LWJGL 2 measured the
 * cursor in framebuffer pixels from the bottom left, GLFW in window units
 * from the top left.
 */
public final class Mouse {
    private static final int BUTTONS = 8;

    private Mouse() {}

    private static Window window() {
        return MinecraftClient.getInstance().getWindow();
    }

    public static boolean isButtonDown(int button) {
        return button >= 0 && button < BUTTONS
                && GLFW.glfwGetMouseButton(window().getHandle(), button) == GLFW.GLFW_PRESS;
    }

    public static int getButtonCount() {
        return BUTTONS;
    }

    public static int getX() {
        Window window = window();
        double[] x = new double[1];
        GLFW.glfwGetCursorPos(window.getHandle(), x, new double[1]);
        return (int) (x[0] * window.getFramebufferWidth() / Math.max(1, window.getWidth()));
    }

    public static int getY() {
        Window window = window();
        double[] y = new double[1];
        GLFW.glfwGetCursorPos(window.getHandle(), new double[1], y);
        return window.getFramebufferHeight() - 1
                - (int) (y[0] * window.getFramebufferHeight() / Math.max(1, window.getHeight()));
    }

    public static boolean isGrabbed() {
        return MinecraftClient.getInstance().mouse.isCursorLocked();
    }

    public static void setCursorPosition(int x, int y) {
        Window window = window();
        double windowX = x * (double) window.getWidth() / Math.max(1, window.getFramebufferWidth());
        double windowY = (window.getFramebufferHeight() - 1 - y) * (double) window.getHeight()
                / Math.max(1, window.getFramebufferHeight());
        GLFW.glfwSetCursorPos(window.getHandle(), windowX, windowY);
    }
}
