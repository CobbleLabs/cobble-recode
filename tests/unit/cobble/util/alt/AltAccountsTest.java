package cobble.util.alt;

import cobble.test.Check;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The accounts file on disk. Every case keeps "active" at -1: using an account
 * swaps the game's session, and there is no game here.
 */
final class AltAccountsTest {
    private static final String LEGACY = """
            {
              "active": -1,
              "accounts": [
                {"username": "Notch", "uuid": "069a79f4", "token": "eyJaccess", "secret": "M.C5_refresh", "type": 3},
                {"username": "offline_guy", "uuid": "", "token": "", "secret": "", "type": 0}
              ]
            }
            """;

    private static Path fresh() throws Exception {
        Path dir = Files.createTempDirectory("cobble-alts");
        dir.toFile().deleteOnExit();
        return dir.resolve("alts.json");
    }

    static void testLegacyPlaintextIsResealedOnLoad() throws Exception {
        Path file = fresh();
        Files.writeString(file, LEGACY, StandardCharsets.UTF_8);
        AltAccounts.useSealer(new SealedJsonTest.FakeSealer());
        try {
            AltAccounts.load(file);
            Check.equal(2, AltAccounts.accounts().size(), "both accounts loaded");
            Check.equal("M.C5_refresh", AltAccounts.accounts().get(0).secret(), "secret kept in memory");
            String onDisk = Files.readString(file, StandardCharsets.UTF_8);
            Check.that(!onDisk.contains("M.C5_refresh") && !onDisk.contains("eyJaccess"),
                    "no token left in plaintext on disk: " + onDisk);
            Check.that(onDisk.contains(SealedJson.FORMAT), "file rewritten in the sealed format");

            AltAccounts.load(file);
            Check.equal(2, AltAccounts.accounts().size(), "sealed file loads back");
            Check.equal(AltAccounts.Type.REFRESH, AltAccounts.accounts().get(0).type(), "type survives");
            Check.equal("eyJaccess", AltAccounts.accounts().get(0).accessToken(), "token survives");
        } finally {
            AltAccounts.useSealer(SealedJson.DPAPI);
        }
    }

    static void testSealFailureLeavesFileUntouched() throws Exception {
        Path file = fresh();
        Files.writeString(file, LEGACY, StandardCharsets.UTF_8);
        AltAccounts.useSealer(SealedJsonTest.BROKEN);
        try {
            AltAccounts.load(file);
            Check.equal(LEGACY, Files.readString(file, StandardCharsets.UTF_8),
                    "a failed seal never rewrites the file, and never in plaintext");
            Check.that(!Files.exists(file.resolveSibling("alts.json.tmp")), "no temp file left behind");
        } finally {
            AltAccounts.useSealer(SealedJson.DPAPI);
        }
    }

    static void testForeignFileIsSetAsideNotOverwritten() throws Exception {
        Path file = fresh();
        String sealed = SealedJson.write(SealedJson.read(LEGACY, new SealedJsonTest.FakeSealer()).root(),
                new SealedJsonTest.FakeSealer());
        Files.writeString(file, sealed, StandardCharsets.UTF_8);
        AltAccounts.useSealer(SealedJsonTest.BROKEN);
        try {
            AltAccounts.load(file);
            Check.equal(0, AltAccounts.accounts().size(), "nothing loaded from a file that cannot be opened");
            Check.that(!Files.exists(file), "the unreadable file is moved out of the way");
            Check.equal(sealed, Files.readString(file.resolveSibling("alts.json.unreadable"), StandardCharsets.UTF_8),
                    "and kept intact beside it");
        } finally {
            AltAccounts.useSealer(SealedJson.DPAPI);
        }
    }

    static void testMissingFileIsAnEmptyList() throws Exception {
        Path file = fresh();
        AltAccounts.useSealer(new SealedJsonTest.FakeSealer());
        try {
            AltAccounts.load(file);
            Check.equal(0, AltAccounts.accounts().size(), "no file, no accounts");
            Check.that(!Files.exists(file), "loading alone creates nothing");
        } finally {
            AltAccounts.useSealer(SealedJson.DPAPI);
        }
    }
}
