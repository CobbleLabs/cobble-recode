package cobble.module.other;

import cobble.event.LoadWorldEvent;
import cobble.event.PacketReceiveEvent;
import cobble.event.Subscribe;
import cobble.module.Category;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.Setting;
import cobble.module.Subcategory;
import cobble.util.team.ApolloMessages;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S3FPacketCustomPayload;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;

/**
 * Cobble's Teams: decides who counts as a teammate, and from which evidence,
 * for every module that asks TeamUtils. Off means no team awareness at all.
 *
 * <p>Strongest first: the roster a server states through Lunar's Apollo API,
 * an Apollo nametag turned green (confirmed over several signals, because
 * cosmetic colour cycling can flash one), shared scoreboard team, your own
 * nametag colour or one you pick, and the game's own same-team flag.
 */
public final class Teams extends Module {
    private static final String APOLLO_CHANNEL = "lunar:apollo";
    private static final List<String> COLOURS = List.of("Black", "Dark Blue", "Dark Green", "Dark Aqua", "Dark Red",
            "Dark Purple", "Gold", "Gray", "Dark Gray", "Blue", "Green", "Aqua", "Red", "Light Purple", "Yellow",
            "White");
    /* Adventure hex colours to their chat colour, with the near-vanilla shades Apollo nametags use. */
    private static final Map<String, EnumChatFormatting> HEX = Map.ofEntries(
            Map.entry("000000", EnumChatFormatting.BLACK), Map.entry("0000AA", EnumChatFormatting.DARK_BLUE),
            Map.entry("00AA00", EnumChatFormatting.DARK_GREEN), Map.entry("00AAAA", EnumChatFormatting.DARK_AQUA),
            Map.entry("AA0000", EnumChatFormatting.DARK_RED), Map.entry("AA00AA", EnumChatFormatting.DARK_PURPLE),
            Map.entry("FFAA00", EnumChatFormatting.GOLD), Map.entry("AAAAAA", EnumChatFormatting.GRAY),
            Map.entry("555555", EnumChatFormatting.DARK_GRAY), Map.entry("5555FF", EnumChatFormatting.BLUE),
            Map.entry("55FF55", EnumChatFormatting.GREEN), Map.entry("55FFFF", EnumChatFormatting.AQUA),
            Map.entry("FF5555", EnumChatFormatting.RED), Map.entry("FF55FF", EnumChatFormatting.LIGHT_PURPLE),
            Map.entry("FFFF55", EnumChatFormatting.YELLOW), Map.entry("FFFFFF", EnumChatFormatting.WHITE),
            Map.entry("55FF5F", EnumChatFormatting.GREEN), Map.entry("FF5551", EnumChatFormatting.RED),
            Map.entry("AA0003", EnumChatFormatting.DARK_RED), Map.entry("FF55FA", EnumChatFormatting.LIGHT_PURPLE),
            Map.entry("AA00AC", EnumChatFormatting.DARK_PURPLE), Map.entry("00AAAC", EnumChatFormatting.DARK_AQUA),
            Map.entry("5555FE", EnumChatFormatting.BLUE), Map.entry("FFFFFA", EnumChatFormatting.WHITE),
            Map.entry("AAAAAC", EnumChatFormatting.GRAY), Map.entry("55FFFA", EnumChatFormatting.AQUA),
            Map.entry("FFAA02", EnumChatFormatting.GOLD), Map.entry("FFFF5D", EnumChatFormatting.YELLOW));
    private static final String COLOUR_KEY = "\"color\":\"#";

    private final Setting lunarApi = addBoolean("Lunar API", "The roster the server sends through Apollo", true);
    private final Setting apolloNametags = addBoolean("Apollo Nametags", "A green Apollo nametag marks a teammate",
            true);
    private final Setting scoreboard = addBoolean("Scoreboard", "Players on your scoreboard team", true);
    private final Setting ownColour = addBoolean("Own Nametag Color", "Players whose name is your colour", true);
    private final Setting manualColour = addBoolean("Manual Color", "Players whose name is a colour you pick", false);
    private final Setting teamColour = addChoice("Team Color", "The colour Manual Color looks for", COLOURS, 10)
            .visibleWhen(manualColour::bool);
    private final Setting confirmHits = addNumber("Confirm Hits", "Green nametag signals needed to trust one",
            3, 1, 8, 0);
    private final Setting confirmWindow = addNumber("Confirm Window (ms)", "Signals further apart start over",
            1500, 250, 5000, 0);
    private final Setting graceTime = addNumber("Grace Time (ms)", "How long a teammate survives losing the colour",
            10000, 0, 20000, 0);
    private final Setting requireTeamTag = addBoolean("Require Team Tag", "Only greens from the nested team tag",
            false);
    private final Setting lunarExclusive = addBoolean("Lunar Overrides Tags", "A live roster ignores nametag colours",
            true);
    private final Setting vanillaTeams = addBoolean("Vanilla Teams", "The game's own same-team flag", true);

    private record Signal(int greenHits, long lastGreen, boolean confirmed) {}

    /* Written on the network thread, read by every module on the game thread. */
    private final Set<UUID> lunarTeammates = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Signal> nametags = new ConcurrentHashMap<>();

    public Teams() {
        super("Teams", "Who counts as a teammate", Category.OTHER, Subcategory.BASIC);
        /* On by default, as Cobble's was, so nothing starts hitting teammates after an upgrade. */
        setEnabled(true, null);
    }

    @Override
    public String suffix() {
        if (!lunarTeammates.isEmpty()) {
            return "Lunar " + lunarTeammates.size();
        }
        long tags = nametags.values().stream().filter(Signal::confirmed).count();
        return tags > 0 ? "Tags " + tags : "";
    }

    /* Turning it back on starts from whatever the server says now. */
    @Override
    public void onDisable(Context context) {
        clearDetected();
    }

    @Subscribe
    public void onLoadWorld(LoadWorldEvent event) {
        clearDetected();
    }

    private void clearDetected() {
        lunarTeammates.clear();
        nametags.clear();
    }

    /** Whether the combat and visual modules should leave this entity alone as a teammate. */
    public boolean isTeammate(Entity entity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!enabled() || !(entity instanceof EntityPlayer player) || mc.thePlayer == null || entity == mc.thePlayer) {
            return false;
        }
        UUID id = player.getUniqueID();
        if (lunarApi.bool() && lunarTeammates.contains(id)) {
            return true;
        }
        /* A server that states its roster is trusted over a colour, unless told otherwise. */
        boolean rosterLive = lunarApi.bool() && lunarExclusive.bool() && !lunarTeammates.isEmpty();
        if (apolloNametags.bool() && !rosterLive) {
            Signal signal = nametags.get(id);
            if (signal != null && signal.confirmed()) {
                return true;
            }
        }
        if (vanillaTeams.bool() && mc.thePlayer.isOnSameTeam(player)) {
            return true;
        }
        if (scoreboard.bool()) {
            ScorePlayerTeam own = mc.theWorld.getScoreboard().getPlayersTeam(mc.thePlayer.getName());
            if (own != null && own == mc.theWorld.getScoreboard().getPlayersTeam(player.getName())) {
                return true;
            }
        }
        EnumChatFormatting colour = nameColour(player);
        if (ownColour.bool() && colour != null && colour == nameColour(mc.thePlayer)) {
            return true;
        }
        return manualColour.bool() && colour != null && colour.ordinal() == teamColour.choice();
    }

    /* The first colour code of the name the game draws over the player. */
    private static EnumChatFormatting nameColour(EntityPlayer player) {
        String name = player.getDisplayName().getFormattedText();
        for (int i = 0; i + 1 < name.length(); ++i) {
            if (name.charAt(i) == '§') {
                EnumChatFormatting code = byCode(name.charAt(i + 1));
                if (code != null && code.isColor()) {
                    return code;
                }
            }
        }
        return null;
    }

    private static EnumChatFormatting byCode(char code) {
        int index = "0123456789abcdef".indexOf(Character.toLowerCase(code));
        return index < 0 ? null : EnumChatFormatting.values()[index];
    }

    @Subscribe
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof S3FPacketCustomPayload payload)
                || !APOLLO_CHANNEL.equals(payload.getChannelName())) {
            return;
        }
        /* Copied out without moving the reader: Lunar reads this same buffer after us. */
        ByteBuf buffer = payload.getBufferData();
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), data);
        Object message = ApolloMessages.decode(data);
        if (message instanceof ApolloMessages.TeamMembers members) {
            lunarTeammates.retainAll(members.members());
            lunarTeammates.addAll(members.members());
        } else if (message instanceof ApolloMessages.Nametag nametag) {
            nametagSignal(nametag);
        }
    }

    /*
     * A green signal only counts toward trust; a run of them inside the window
     * confirms the player. A confirmed one keeps the status through the grace
     * time after losing the colour, which covers temporary recolours such as an
     * archer tag.
     */
    private void nametagSignal(ApolloMessages.Nametag nametag) {
        long now = System.currentTimeMillis();
        NametagColour colour = colourOf(nametag.lines());
        boolean signal = colour.green() && (!requireTeamTag.bool() || colour.fromTeamTag());
        Signal previous = nametags.getOrDefault(nametag.player(), new Signal(0, 0, false));
        Signal next;
        if (signal) {
            int hits = previous.lastGreen() > 0 && now - previous.lastGreen() > confirmWindow.intValue()
                    ? 1 : previous.greenHits() + 1;
            next = new Signal(hits, now, previous.confirmed() || hits >= confirmHits.intValue());
        } else if (!previous.confirmed()) {
            next = new Signal(0, previous.lastGreen(), false);
        } else if (previous.lastGreen() == 0 || now - previous.lastGreen() > graceTime.intValue()) {
            next = new Signal(0, previous.lastGreen(), false);
        } else {
            next = previous;
        }
        nametags.put(nametag.player(), next);
    }

    private record NametagColour(boolean green, boolean fromTeamTag) {}

    /*
     * Only the outer colour and the first nested one are read: deeper colours
     * are cosmetic effects. A server that hides the team colour puts it in the
     * nested tag, so a green there wins.
     */
    private static NametagColour colourOf(List<String> lines) {
        EnumChatFormatting outer = null;
        EnumChatFormatting nested = null;
        for (String line : lines) {
            int depth = 0;
            boolean inString = false;
            boolean escape = false;
            for (int i = 0; i < line.length(); ++i) {
                if (line.startsWith(COLOUR_KEY, i) && i + COLOUR_KEY.length() + 6 <= line.length()) {
                    EnumChatFormatting found = HEX.get(
                            line.substring(i + COLOUR_KEY.length(), i + COLOUR_KEY.length() + 6).toUpperCase());
                    if (depth == 1 && outer == null) {
                        outer = found;
                    } else if (depth > 1 && depth <= 3 && nested == null) {
                        nested = found;
                    }
                }
                char c = line.charAt(i);
                if (inString) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    ++depth;
                } else if ((c == '}' || c == ']') && depth > 0) {
                    --depth;
                }
            }
            if (outer != null && nested != null) {
                break;
            }
        }
        if (isGreen(nested)) {
            return new NametagColour(true, true);
        }
        if (isGreen(outer)) {
            return new NametagColour(true, false);
        }
        if (outer == null && nested == null) {
            /* No Adventure colour: fall back to the first legacy colour code. */
            for (String line : lines) {
                int at = line.indexOf('§');
                if (at >= 0 && at + 1 < line.length()) {
                    return new NametagColour(isGreen(byCode(line.charAt(at + 1))), false);
                }
            }
        }
        return new NametagColour(false, false);
    }

    private static boolean isGreen(EnumChatFormatting colour) {
        return colour == EnumChatFormatting.GREEN || colour == EnumChatFormatting.DARK_GREEN;
    }
}
