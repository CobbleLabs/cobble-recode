package cobble.util;

import cobble.script.ScriptManager;
import java.util.Locale;

/**
 * Friends and enemies, kept in the script store so scripts and modules share
 * one list. Entries sit under the lower-cased name and hold the name as
 * written; an empty value is an entry removed.
 */
public final class RelationManager {
    public static final RelationManager INSTANCE = new RelationManager();

    private static final String FRIENDS = "friends";
    private static final String ENEMIES = "enemies";

    private RelationManager() {}

    public boolean addFriend(String name) {
        return add(FRIENDS, name);
    }

    public boolean addEnemy(String name) {
        return add(ENEMIES, name);
    }

    public boolean removeFriend(String name) {
        return remove(FRIENDS, name);
    }

    public boolean removeEnemy(String name) {
        return remove(ENEMIES, name);
    }

    public boolean isFriend(String name) {
        return has(FRIENDS, name);
    }

    public boolean isEnemy(String name) {
        return has(ENEMIES, name);
    }

    private static boolean add(String section, String name) {
        if (name.isEmpty() || has(section, name)) {
            return false;
        }
        ScriptManager.store().set(section, name.toLowerCase(Locale.ROOT), name);
        ScriptManager.saveStore();
        return true;
    }

    private static boolean remove(String section, String name) {
        if (!has(section, name)) {
            return false;
        }
        ScriptManager.store().set(section, name.toLowerCase(Locale.ROOT), "");
        ScriptManager.saveStore();
        return true;
    }

    private static boolean has(String section, String name) {
        return !ScriptManager.store().get(section, name.toLowerCase(Locale.ROOT), "").isEmpty();
    }
}
