package cobble.v1_21.compat;

import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.Hand;

/** 1.8.9's C0APacketAnimation on 1.21.11's HandSwingC2SPacket. */
public final class C0APacketAnimationShim {
    private C0APacketAnimationShim() {}

    public static HandSwingC2SPacket create() {
        return new HandSwingC2SPacket(Hand.MAIN_HAND);
    }
}
