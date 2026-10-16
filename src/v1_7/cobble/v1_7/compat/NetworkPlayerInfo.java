package cobble.v1_7.compat;

import com.mojang.authlib.GameProfile;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ResourceLocation;

/** 1.8.9's tab list entry, put together from 1.7.10's name-and-ping entry and the player it names. */
public final class NetworkPlayerInfo {
    private final GameProfile profile;
    private final int responseTime;
    private final ResourceLocation skin;
    private final ScorePlayerTeam team;

    NetworkPlayerInfo(GameProfile profile, int responseTime, ResourceLocation skin, ScorePlayerTeam team) {
        this.profile = profile;
        this.responseTime = responseTime;
        this.skin = skin;
        this.team = team;
    }

    public GameProfile getGameProfile() {
        return profile;
    }

    public int getResponseTime() {
        return responseTime;
    }

    public ResourceLocation getLocationSkin() {
        return skin;
    }

    /* 1.7.10 skins have no slim arms. */
    public String getSkinType() {
        return "default";
    }

    public ScorePlayerTeam getPlayerTeam() {
        return team;
    }
}
