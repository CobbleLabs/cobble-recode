package cobble.v1_21.compat;

import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;

/** 1.8.9's S02PacketChat on 1.21.11's GameMessageS2CPacket, which only carries system lines and the action bar. */
public final class S02PacketChatShim {
    private S02PacketChatShim() {}

    public static Text getChatComponent(GameMessageS2CPacket packet) {
        return packet.content();
    }

    /* 1.8's types: 0 chat, 1 system, 2 above the hotbar. */
    public static byte getType(GameMessageS2CPacket packet) {
        return (byte) (packet.overlay() ? 2 : 1);
    }
}
