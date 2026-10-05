package cobble.v1_21.compat;

import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreboardEntry;

/**
 * 1.8.9's Score as 1.21.11's ScoreboardEntry. 1.21's entries are values, so
 * the one Scoreboard.getValueFromObjective last handed out remembers where a
 * setScorePoints on it goes, the only way the legacy code writes one.
 */
public final class ScoreShim {
    private static ScoreboardEntry writable;
    private static ScoreAccess access;

    private ScoreShim() {}

    static ScoreboardEntry writable(ScoreboardEntry entry, ScoreAccess score) {
        writable = entry;
        access = score;
        return entry;
    }

    public static String getPlayerName(ScoreboardEntry entry) {
        return entry.owner();
    }

    public static int getScorePoints(ScoreboardEntry entry) {
        return entry.value();
    }

    public static void setScorePoints(ScoreboardEntry entry, int points) {
        if (entry == writable) {
            access.setScore(points);
        }
    }
}
