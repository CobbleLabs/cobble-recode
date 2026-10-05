package cobble.v1_21.compat;

import net.minecraft.scoreboard.ScoreboardObjective;

/** 1.8.9's ScoreObjective on 1.21.11's ScoreboardObjective, whose name is text with its colours. */
public final class ScoreObjectiveShim {
    private ScoreObjectiveShim() {}

    public static String getDisplayName(ScoreboardObjective objective) {
        return IChatComponentShim.getFormattedText(objective.getDisplayName());
    }
}
