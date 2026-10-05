package cobble.module.player;

import cobble.boot.GameVersion;
import cobble.event.PacketSendEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.LegacyModule;
import cobble.module.Subcategory;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.play.client.C03PacketPlayer;

/**
 * Keeps potion effects from running down while standing still. The server only
 * ages an effect on the ticks the player sends a movement packet, so none are
 * sent while you have effects and are not moving.
 */
public final class PotionSaver extends LegacyModule {
    private boolean saving;

    public PotionSaver() {
        super("PotionSaver", "Saves your potion effects while standing still", Category.PLAYER,
                Subcategory.ADVANCED);
    }

    /* Servers from the combat update age effects every tick, packets or not. */
    @Override
    public boolean supported() {
        return !GameVersion.current().combatUpdate();
    }

    @Override
    public String suffix() {
        return saving ? "Saving" : "";
    }

    @Subscribe
    public void onPacketSend(PacketSendEvent event) {
        EntityPlayerSP player = mc.thePlayer;
        if (player == null || player.getActivePotionEffects().isEmpty() || player.motionX != 0.0
                || player.motionZ != 0.0 || !player.onGround && !player.isCollidedVertically) {
            saving = false;
            return;
        }
        if (event.getPacket() instanceof C03PacketPlayer) {
            saving = true;
            event.setCancelled(true);
        }
    }
}
