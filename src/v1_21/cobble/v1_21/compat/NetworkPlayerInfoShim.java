package cobble.v1_21.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.scoreboard.Team;

/** 1.8.9's NetworkPlayerInfo on 1.21.11's PlayerListEntry. */
public final class NetworkPlayerInfoShim {
    private NetworkPlayerInfoShim() {}

    public static GameProfile getGameProfile(PlayerListEntry entry) {
        return entry.getProfile();
    }

    public static int getResponseTime(PlayerListEntry entry) {
        return entry.getLatency();
    }

    public static Team getPlayerTeam(PlayerListEntry entry) {
        return entry.getScoreboardTeam();
    }

    /* 1.8 names the arm models "default" and "slim". */
    public static String getSkinType(PlayerListEntry entry) {
        return entry.getSkinTextures().model() == PlayerSkinType.SLIM ? "slim" : "default";
    }
}
