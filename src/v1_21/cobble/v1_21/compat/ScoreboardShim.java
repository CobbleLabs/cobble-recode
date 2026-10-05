package cobble.v1_21.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

/** 1.8.9's Scoreboard on 1.21.11's; a 1.8 score is a 1.21 scoreboard entry. */
public final class ScoreboardShim {
    private ScoreboardShim() {}

    public static ScoreboardObjective getObjectiveInDisplaySlot(Scoreboard scoreboard, int slot) {
        return scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.FROM_ID.apply(slot));
    }

    public static Team getPlayersTeam(Scoreboard scoreboard, String name) {
        return scoreboard.getScoreHolderTeam(name);
    }

    /** Lowest first, as 1.8 sorted them. */
    public static Collection<ScoreboardEntry> getSortedScores(Scoreboard scoreboard, ScoreboardObjective objective) {
        List<ScoreboardEntry> entries = new ArrayList<>(scoreboard.getScoreboardEntries(objective));
        entries.sort(Comparator.comparingInt(ScoreboardEntry::value));
        return entries;
    }

    public static void removeObjectiveFromEntity(Scoreboard scoreboard, String name, ScoreboardObjective objective) {
        scoreboard.removeScore(ScoreHolder.fromName(name), objective);
    }

    public static ScoreboardEntry getValueFromObjective(Scoreboard scoreboard, String name,
                                                        ScoreboardObjective objective) {
        ScoreAccess access = scoreboard.getOrCreateScore(ScoreHolder.fromName(name), objective);
        return ScoreShim.writable(new ScoreboardEntry(name, access.getScore(), null, null), access);
    }
}
