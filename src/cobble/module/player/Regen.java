package cobble.module.player;

import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer;

/**
 * Heals faster when low. A server that updates the player once per movement
 * packet also runs natural regeneration once per packet, so a burst of forty
 * while standing still is two seconds of healing at once.
 */
public final class Regen extends Module {
    private static final int BURST = 40;

    private final Setting health = addNumber("Health", "Hearts below which it heals", 8.0f, 0.5f, 10.0f, 1);

    public Regen() {
        super("Regen", "Regenerates health quicker", Category.PLAYER, Subcategory.ADVANCED);
    }

    @Override
    public void onTick(Context context) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || player.isDead || player.getHealth() >= health.number() * 2.0f
                || player.getFoodStats().getFoodLevel() <= 16 || player.isUsingItem() || !player.isCollidedVertically
                || !player.onGround || mc.gameSettings.keyBindJump.isKeyDown() || player.isInWater()
                || player.ticksExisted % 5 != 1) {
            return;
        }
        C03PacketPlayer packet = new C03PacketPlayer(false);
        for (int sent = 0; sent < BURST; ++sent) {
            mc.getNetHandler().addToSendQueue(packet);
        }
    }
}
