package cobble.util.team;

import cobble.module.ModuleManager;
import cobble.module.other.Teams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.scoreboard.ScorePlayerTeam;

public final class TeamUtils {
    private TeamUtils() {
    }

    /** Decided by the Teams module, which off means no teammates at all. */
    public static boolean isTeammate(Minecraft var0, Entity var1) {
        return ModuleManager.get(Teams.class).isTeammate(var1);
    }

    public static String getOwnFormattedName(Minecraft var0) {
        try {
            NetworkPlayerInfo var1 = var0.getNetHandler().getPlayerInfo(var0.thePlayer.getUniqueID());
            return ScorePlayerTeam.formatPlayerName(var1.getPlayerTeam(), var1.getGameProfile().getName());
        } catch (Exception var2) {
            return "";
        }
    }
}
