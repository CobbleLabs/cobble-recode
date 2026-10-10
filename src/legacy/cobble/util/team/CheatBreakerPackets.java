package cobble.util.team;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The CheatBreaker API's teammates packet, read the way
 * CheatBreakerAPINetHandler writes it: a varint packet id, 7 for this one,
 * then an optional leader, the time it was sent, and every teammate with the
 * named coordinates CheatBreaker draws them at. CheatBreaker 1.8.9 declares
 * both channels.
 */
public final class CheatBreakerPackets {
    public static final Set<String> CHANNELS = Set.of("CB-Binary", "cheatbreaker:pm");
    private static final int TEAMMATES = 7;
    private static final int MAX_VARINT_BYTES = 5;

    private CheatBreakerPackets() {}

    /** The players the packet names, its leader among them, or null for any other packet or a malformed one. */
    public static Set<UUID> teammates(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data);
        try {
            if (varint(in) != TEAMMATES) {
                return null;
            }
            Set<UUID> team = new HashSet<>();
            if (in.get() != 0) {
                team.add(new UUID(in.getLong(), in.getLong()));
            }
            in.getLong();
            int players = varint(in);
            for (int player = 0; player < players; ++player) {
                team.add(new UUID(in.getLong(), in.getLong()));
                int coordinates = varint(in);
                for (int coordinate = 0; coordinate < coordinates; ++coordinate) {
                    int name = varint(in);
                    in.position(in.position() + name + Double.BYTES);
                }
            }
            return team;
        } catch (BufferUnderflowException | IllegalArgumentException malformed) {
            return null;
        }
    }

    private static int varint(ByteBuffer in) {
        int value = 0;
        for (int index = 0; index < MAX_VARINT_BYTES; ++index) {
            byte part = in.get();
            value |= (part & 0x7F) << index * 7;
            if ((part & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("varint too long");
    }
}
