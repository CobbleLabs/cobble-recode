package cobble.module.movement;

import cobble.event.MoveEvent;
import cobble.event.Subscribe;
import cobble.event.UpdateWalkingPlayerEvent;
import cobble.module.Category;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Every move goes where the keys point at the chosen speed, jump and sneak
 * rise and sink, and nothing else moves the player. The movement packet never
 * reports the ground.
 */
public final class Fly extends Module {
    private final Setting speed = addNumber("Speed", "How fast you fly", 0.5f, 0.1f, 5.0f, 1);
    private final Setting verticalSpeed = addNumber("Vertical Speed", "How fast jump and sneak move you up and down",
            0.2f, 0.1f, 5.0f, 1);

    public Fly() {
        super("Fly", "Makes you go zoom", Category.MOVEMENT, Subcategory.ADVANCED);
    }

    @Subscribe
    public void onUpdateWalkingPlayer(UpdateWalkingPlayerEvent event) {
        mc.thePlayer.onGround = false;
    }

    @Subscribe
    public void onMove(MoveEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        double vertical = mc.gameSettings.keyBindJump.isKeyDown() ? verticalSpeed.number()
                : mc.gameSettings.keyBindSneak.isKeyDown() ? -verticalSpeed.number() : 0.0;
        event.setY(vertical);
        player.motionY = vertical;
        /* Never slower than walking with whatever Speed effect there is. */
        Strafing.move(event, Math.max(speed.number(), Strafing.baseSpeed(player, 0.2, 0)));
    }
}
