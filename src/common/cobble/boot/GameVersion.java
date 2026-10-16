package cobble.boot;

import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * The Minecraft version the client was injected into, told apart by what the
 * game's loader holds rather than by the launcher's command line: Lunar runs
 * 1.7 and 1.8 under MCP names and the modern versions under Fabric's
 * intermediary ones, where the game reports its own version; CheatBreaker
 * keeps its own classes' names, and runs each version from a jar named after
 * it. A version's {@code id} also names its overlay in the image and its
 * module list.
 */
public enum GameVersion {
    V1_7_10("1.7.10", "v1_7"),
    /** CheatBreaker's 1.7.10, which renames every Minecraft class and member. */
    CB_1_7_10("cb-1.7.10", "cb_1_7"),
    V1_8_9("1.8.9", "v1_8"),
    /** CheatBreaker's 1.8.9, renamed the same way. */
    CB_1_8_9("cb-1.8.9", "cb"),
    V1_21_11("1.21.11", "v1_21.legacy");

    private static final String CHEATBREAKER_CLASS = "com.cheatbreaker.client.network.messages.Message";
    private static final String CHEATBREAKER_PREFIX = "cb-";
    private static final String CHEATBREAKER_JAR = ".patch";

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

    boolean cheatBreaker() {
        return id.startsWith(CHEATBREAKER_PREFIX);
    }

    /** Minecraft's version without the launcher: 1.8.9 for CheatBreaker's 1.8.9. */
    String minecraft() {
        return cheatBreaker() ? id.substring(CHEATBREAKER_PREFIX.length()) : id;
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
        if (present(game, CHEATBREAKER_CLASS)) {
            String jar = cheatBreakerJar(game).getFileName().toString();
            return CHEATBREAKER_PREFIX + (jar.endsWith(CHEATBREAKER_JAR)
                    ? jar.substring(0, jar.length() - CHEATBREAKER_JAR.length()) : jar);
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

    /** The jar CheatBreaker runs the game from, versions/1.8.9/1.8.9.patch for its 1.8.9. */
    static Path cheatBreakerJar(ClassLoader game) {
        try {
            return Path.of(Class.forName(CHEATBREAKER_CLASS, false, game).getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (ClassNotFoundException | URISyntaxException e) {
            throw new IllegalStateException(e);
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
