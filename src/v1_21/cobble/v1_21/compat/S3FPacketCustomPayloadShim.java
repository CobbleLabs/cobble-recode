package cobble.v1_21.compat;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;

/**
 * 1.8.9's S3FPacketCustomPayload on 1.21.11's CustomPayloadS2CPacket. 1.21
 * decodes a payload into its channel's own type before anything sees it, so
 * the raw bytes 1.8 handed out are gone: the buffer reads as empty.
 */
public final class S3FPacketCustomPayloadShim {
    private S3FPacketCustomPayloadShim() {}

    public static String getChannelName(CustomPayloadS2CPacket packet) {
        return packet.payload().getId().id().toString();
    }

    public static PacketByteBuf getBufferData(CustomPayloadS2CPacket packet) {
        return new PacketByteBuf(Unpooled.EMPTY_BUFFER);
    }
}
