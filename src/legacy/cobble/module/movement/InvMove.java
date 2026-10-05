package cobble.module.movement;

import cobble.core.Maths;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.game.RotationEngine;
import cobble.hook.MenuScreen;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.LegacyModule;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.ui.Client;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * A screen swallows key presses, so the physical state of the movement keys is
 * forwarded to their bindings every tick. Typing must never walk the player:
 * chat and the client's own text fields are always left alone.
 */
public final class InvMove extends LegacyModule {
    private static final String[] BINDINGS = {"forward", "back", "left", "right", "jump", "sneak"};
    private static final float PITCH_STEP = 3.0f;
    private static final float YAW_STEP = 5.0f;

    private final Setting inventoryOnly = addBoolean("Inventory Only", "Only walk in your own inventory, not in chests",
            true);
    private final Setting sneak = addBoolean("Sneak", "Take the sneak key too", true);
    private final Setting rotate = addBoolean("Rotate", "Turn with the arrow keys", false);

    private boolean active;

    public InvMove() {
        super("InvMove", "Walks while a screen is open", Category.MOVEMENT, Subcategory.BASIC);
    }

    private void releaseAll() {
        for (String binding : BINDINGS) {
            Game.get().setBinding(binding, false);
        }
        active = false;
    }

    @Override
    public void onDisable(Context context) {
        if (active) {
            releaseAll();
        }
    }

    @Override
    public void onTick(Context context) {
        FrameState state = context.state;
        if (!state.inWorld || !state.player.valid || !state.screenOpen || !walkable(mc.currentScreen)) {
            if (active) {
                releaseAll();
            }
            return;
        }
        active = true;
        for (String binding : BINDINGS) {
            boolean held = Game.get().bindingPhysicallyHeld(binding) && (sneak.bool() || !binding.equals("sneak"));
            Game.get().setBinding(binding, held);
        }
        if (rotate.bool()) {
            turn(mc.thePlayer);
        }
    }

    private boolean walkable(GuiScreen screen) {
        if (inventoryOnly.bool()) {
            return Game.get().inventoryOpen();
        }
        if (screen instanceof GuiChat) {
            return false;
        }
        return !(screen instanceof MenuScreen) || !Client.gui().capturingKey() && !Client.radar().interactive()
                && !Client.altManager().interactive();
    }

    /* Whole mouse counts, as everything else that turns the player. */
    private static void turn(EntityPlayerSP player) {
        float pitch = player.rotationPitch;
        float yaw = player.rotationYaw;
        if (Keyboard.isKeyDown(Keyboard.KEY_UP) && pitch - PITCH_STEP > -90.0f) {
            pitch = Maths.clamp(pitch - grid(PITCH_STEP), -89.5f, 89.5f);
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_DOWN) && pitch + PITCH_STEP < 90.0f) {
            pitch = Maths.clamp(pitch + grid(PITCH_STEP), -89.5f, 89.5f);
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_LEFT)) {
            yaw -= grid(YAW_STEP);
        }
        if (Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) {
            yaw += grid(YAW_STEP);
        }
        player.rotationPitch = pitch;
        player.rotationYaw = yaw;
    }

    private static float grid(float degrees) {
        float step = RotationEngine.step();
        return Math.round(degrees / step) * step;
    }
}
