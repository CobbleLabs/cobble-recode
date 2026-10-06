package cobble.v1_21;

import cobble.hook.Input;
import cobble.ui.Client;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/**
 * The empty screen that hands the pointer and keyboard to the overlay, as on
 * the legacy versions: opening a screen is what makes the game release the
 * mouse and stop reading input for itself. It draws nothing of its own and
 * hands every event to {@link Input} in framebuffer pixels, which is what
 * the overlay lays itself out in, rather than the screen's scaled units.
 */
public final class MenuScreen extends Screen {
    private final Screen parent;
    private final Set<Integer> keysDown = new HashSet<>();
    private boolean released;

    private MenuScreen(Screen parent) {
        super(Text.empty());
        this.parent = parent;
    }

    static void show() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.currentScreen instanceof MenuScreen)) {
            mc.setScreen(new MenuScreen(mc.currentScreen));
        }
    }

    /* Also called by the menus closing from removed, while the game is already replacing this screen. */
    static void hide() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof MenuScreen screen && !screen.released) {
            screen.released = true;
            mc.setScreen(screen.parent);
        }
    }

    @Override
    public void init() {
        if (parent != null) {
            parent.init(width, height);
        }
        Input.setCapturing(true);
    }

    @Override
    public void removed() {
        Input.setCapturing(false);
        if (!released) {
            released = true;
            Client.gui().close();
            Client.radar().close();
            Client.altManager().close();
        }
    }

    /* No background: the menus draw from the frame hook over whatever is underneath. */
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (parent != null) {
            parent.render(context, -1, -1, delta);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int action = keysDown.add(input.key()) ? Input.PRESS : Input.REPEAT;
        Input.push(new Input.Event(Input.Event.Kind.KEY, input.key(), action, input.modifiers(), 0, 0));
        return true;
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        keysDown.remove(input.key());
        Input.push(new Input.Event(Input.Event.Kind.KEY, input.key(), Input.RELEASE, input.modifiers(), 0, 0));
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        Input.push(new Input.Event(Input.Event.Kind.CHAR, input.codepoint(), Input.PRESS, input.modifiers(), 0, 0));
        return true;
    }

    @Override
    public void mouseMoved(double x, double y) {
        Input.push(new Input.Event(Input.Event.Kind.MOUSE_MOVE, 0, 0, Input.modifiers(), cursorX(), cursorY()));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        Input.push(new Input.Event(Input.Event.Kind.MOUSE_BUTTON, click.button(), Input.PRESS, click.modifiers(),
                cursorX(), cursorY()));
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        Input.push(new Input.Event(Input.Event.Kind.MOUSE_BUTTON, click.button(), Input.RELEASE, click.modifiers(),
                cursorX(), cursorY()));
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        Input.push(new Input.Event(Input.Event.Kind.SCROLL, 0, 0, Input.modifiers(), horizontal, vertical));
        return true;
    }

    private static double cursorX() {
        return PlatformAdapter.cursor()[0];
    }

    private static double cursorY() {
        return PlatformAdapter.cursor()[1];
    }
}
