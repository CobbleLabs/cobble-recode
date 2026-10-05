package cobble.v1_21.compat;

import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

/** 1.8.9's ScorePlayerTeam on 1.21.11's Team; 1.8 spelt prefixes and names with section-sign codes. */
public final class ScorePlayerTeamShim {
    private ScorePlayerTeamShim() {}

    public static String formatPlayerName(Team team, String name) {
        return IChatComponentShim.getFormattedText(Team.decorateName(team, Text.literal(name)));
    }

    public static String getColorPrefix(Team team) {
        return IChatComponentShim.getFormattedText(team.getPrefix());
    }
}
