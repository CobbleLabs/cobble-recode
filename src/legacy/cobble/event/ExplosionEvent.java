package cobble.event;

import net.minecraft.network.play.server.S27PacketExplosion;

/** An explosion about to be applied on the game thread; its push on the player can still be changed. */
public final class ExplosionEvent extends Event {
    public final S27PacketExplosion packet;

    public ExplosionEvent(S27PacketExplosion packet) {
        this.packet = packet;
    }
}
