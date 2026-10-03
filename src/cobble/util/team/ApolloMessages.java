package cobble.util.team;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The two Apollo messages team detection reads off the lunar:apollo channel,
 * decoded straight from the protobuf wire format. Each payload is a
 * google.protobuf.Any: field 1 the type URL, field 2 the message.
 */
public final class ApolloMessages {
    private static final String TEAM_MEMBERS = "lunarclient.apollo.team.v1.UpdateTeamMembersMessage";
    private static final String OVERRIDE_NAMETAG = "lunarclient.apollo.nametag.v1.OverrideNametagMessage";

    /** UpdateTeamMembersMessage: the server's own roster. */
    public record TeamMembers(List<UUID> members) {}

    /** OverrideNametagMessage: one player's nametag, as Adventure JSON lines. */
    public record Nametag(UUID player, List<String> lines) {}

    private ApolloMessages() {}

    /** The decoded message, or null for anything else or anything malformed. */
    public static Object decode(byte[] data) {
        Reader any = new Reader(data, 0, data.length);
        String type = null;
        Reader value = null;
        while (any.more()) {
            int tag = any.varint();
            if (tag == (1 << 3 | 2)) {
                type = any.string();
            } else if (tag == (2 << 3 | 2)) {
                value = any.message();
            } else if (!any.skip(tag)) {
                return null;
            }
        }
        if (type == null || value == null || any.broken) {
            return null;
        }
        if (type.endsWith(TEAM_MEMBERS)) {
            return teamMembers(value);
        }
        if (type.endsWith(OVERRIDE_NAMETAG)) {
            return nametag(value);
        }
        return null;
    }

    /* Field 1 repeats a TeamMember, whose field 1 is the player's Uuid. */
    private static TeamMembers teamMembers(Reader message) {
        List<UUID> members = new ArrayList<>();
        while (message.more()) {
            int tag = message.varint();
            if (tag != (1 << 3 | 2)) {
                if (!message.skip(tag)) {
                    return null;
                }
                continue;
            }
            Reader member = message.message();
            while (member.more()) {
                int memberTag = member.varint();
                if (memberTag == (1 << 3 | 2)) {
                    UUID id = uuid(member.message());
                    if (id != null) {
                        members.add(id);
                    }
                } else if (!member.skip(memberTag)) {
                    break;
                }
            }
        }
        return message.broken ? null : new TeamMembers(members);
    }

    /* Field 1 the player's Uuid, field 3 the repeated JSON lines. */
    private static Nametag nametag(Reader message) {
        UUID player = null;
        List<String> lines = new ArrayList<>();
        while (message.more()) {
            int tag = message.varint();
            if (tag == (1 << 3 | 2)) {
                player = uuid(message.message());
            } else if (tag == (3 << 3 | 2)) {
                lines.add(message.string());
            } else if (!message.skip(tag)) {
                return null;
            }
        }
        return message.broken || player == null ? null : new Nametag(player, lines);
    }

    /* Apollo's Uuid: high and low halves as fixed64 fields 1 and 2. */
    private static UUID uuid(Reader message) {
        long high = 0;
        long low = 0;
        while (message.more()) {
            int tag = message.varint();
            if (tag == (1 << 3 | 1)) {
                high = message.fixed64();
            } else if (tag == (2 << 3 | 1)) {
                low = message.fixed64();
            } else if (!message.skip(tag)) {
                return null;
            }
        }
        return message.broken || high == 0 && low == 0 ? null : new UUID(high, low);
    }

    /* Reads one message's bytes. A read past the end marks it broken rather than throwing. */
    private static final class Reader {
        private final byte[] data;
        private final int end;
        private int position;
        boolean broken;

        Reader(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        boolean more() {
            return !broken && position < end;
        }

        int varint() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                if (position >= end) {
                    broken = true;
                    return 0;
                }
                byte next = data[position++];
                value |= (next & 0x7F) << shift;
                if ((next & 0x80) == 0) {
                    return value;
                }
            }
            broken = true;
            return 0;
        }

        long fixed64() {
            if (end - position < 8) {
                broken = true;
                return 0;
            }
            long value = 0;
            for (int i = 0; i < 8; ++i) {
                value |= (data[position++] & 0xFFL) << (8 * i);
            }
            return value;
        }

        Reader message() {
            int length = varint();
            if (broken || length < 0 || length > end - position) {
                broken = true;
                return new Reader(data, end, end);
            }
            Reader inner = new Reader(data, position, position + length);
            position += length;
            return inner;
        }

        String string() {
            Reader bytes = message();
            return new String(data, bytes.position, bytes.end - bytes.position, StandardCharsets.UTF_8);
        }

        boolean skip(int tag) {
            switch (tag & 7) {
                case 0 -> varint();
                case 1 -> position += 8;
                case 2 -> message();
                case 5 -> position += 4;
                default -> broken = true;
            }
            if (position > end) {
                broken = true;
            }
            return !broken;
        }
    }
}
