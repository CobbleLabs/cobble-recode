package cobble.boot;

/**
 * The Minecraft version the client was injected into, told apart by what the
 * game's loader holds rather than by the launcher's command line: Lunar runs
 * 1.7 and 1.8 under MCP names and the modern versions under Fabric's
 * intermediary ones, where the game reports its own version.
 */
public enum GameVersion {
    V1_7_10("1.7.10", "v1_7"),
    V1_8_9("1.8.9", "v1_8"),
    V1_21_11("1.21.11", "v1_21.legacy");

    private static GameVersion current;

    public final String id;
    /** The package under {@code cobble} holding this version's adapter. */
    final String adapterPackage;

    GameVersion(String id, String adapterPackage) {
        this.id = id;
        this.adapterPackage = adapterPackage;
    }

    /** The enum lists the versions oldest first. */
    public boolean atLeast(GameVersion other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * 1.9's combat update and after: an attack cooldown, no blocking with a
     * sword, and servers that tick a player every tick rather than once per
     * movement packet.
     */
    public boolean combatUpdate() {
        return atLeast(V1_21_11);
    }

    public static GameVersion current() {
        return current;
    }

    static void set(GameVersion version) {
        current = version;
    }

    /** The id the game reports, for the log when it is not one the client knows. */
    static String reportedId(ClassLoader game) {
        if (present(game, "net.minecraft.client.Minecraft")) {
            if (present(game, "net.minecraft.util.math.BlockPos")) {
                return "1.9 or later under MCP names";
            }
            return present(game, "net.minecraft.util.BlockPos") ? "1.8.9" : "1.7.10";
        }
        try {
            Class<?> constants = Class.forName("net.minecraft.class_155", true, game);
            Object version = constants.getMethod("method_16673").invoke(null);
            return (String) Class.forName("net.minecraft.class_6489", false, game).getMethod("comp_4025")
                    .invoke(version);
        } catch (ReflectiveOperationException | LinkageError e) {
            return "unknown";
        }
    }

    /** Null when the version is not one the client knows. */
    static GameVersion detect(String reportedId) {
        for (GameVersion version : values()) {
            if (version.id.equals(reportedId)) {
                return version;
            }
        }
        return null;
    }

    private static boolean present(ClassLoader game, String name) {
        try {
            Class.forName(name, false, game);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
