package cobble.v1_21.compat;

import net.minecraft.client.MinecraftClient;

/** 1.8.9's GuiScreen statics on 1.21.11, which keeps the clipboard on the keyboard handler. */
public final class GuiScreenShim {
    private GuiScreenShim() {}

    public static String getClipboardString() {
        return MinecraftClient.getInstance().keyboard.getClipboard();
    }

    public static void setClipboardString(String text) {
        MinecraftClient.getInstance().keyboard.setClipboard(text);
    }
}
