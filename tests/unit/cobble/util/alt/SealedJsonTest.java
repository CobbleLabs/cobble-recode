package cobble.util.alt;

import cobble.test.Check;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class SealedJsonTest {
    /** Stands in for DPAPI: reversible, and plainly not the input. */
    static final class FakeSealer implements SealedJson.Sealer {
        static final byte[] MAGIC = "SEAL".getBytes(StandardCharsets.US_ASCII);

        @Override
        public byte[] seal(byte[] plain) {
            byte[] out = Arrays.copyOf(MAGIC, MAGIC.length + plain.length);
            for (int i = 0; i < plain.length; ++i) {
                out[MAGIC.length + i] = (byte) (plain[i] ^ 0x5A);
            }
            return out;
        }

        @Override
        public byte[] open(byte[] sealed) {
            if (sealed.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(sealed, MAGIC.length), MAGIC)) {
                throw new IllegalStateException("not ours");
            }
            byte[] out = new byte[sealed.length - MAGIC.length];
            for (int i = 0; i < out.length; ++i) {
                out[i] = (byte) (sealed[MAGIC.length + i] ^ 0x5A);
            }
            return out;
        }
    }

    static final SealedJson.Sealer BROKEN = new SealedJson.Sealer() {
        @Override
        public byte[] seal(byte[] plain) {
            throw new IllegalStateException("CryptProtectData failed: 5");
        }

        @Override
        public byte[] open(byte[] sealed) {
            throw new IllegalStateException("CryptUnprotectData failed: 13");
        }
    };

    private static JsonObject sample() {
        JsonObject root = new JsonObject();
        root.addProperty("active", 0);
        root.addProperty("secret", "M.C123_refresh-token");
        return root;
    }

    static void testRoundTrip() {
        FakeSealer sealer = new FakeSealer();
        String text = SealedJson.write(sample(), sealer);
        SealedJson.Read read = SealedJson.read(text, sealer);
        Check.that(!read.plaintext(), "a sealed file reads as sealed");
        Check.equal(sample(), read.root(), "the document survives sealing");
    }

    static void testWrittenFileHoldsNoSecret() {
        String text = SealedJson.write(sample(), new FakeSealer());
        Check.that(!text.contains("refresh-token"), "the secret is not in the file: " + text);
        JsonObject outer = new JsonParser().parse(text).getAsJsonObject();
        Check.equal(SealedJson.FORMAT, outer.get("format").getAsString(), "format tag");
        Check.equal(2, outer.entrySet().size(), "only format and data are written");
    }

    static void testPlaintextIsReadAsLegacy() {
        String legacy = "{\"active\":-1,\"accounts\":[]}";
        SealedJson.Read read = SealedJson.read(legacy, new FakeSealer());
        Check.that(read.plaintext(), "an unwrapped file is the old plaintext layout");
        Check.equal(-1, read.root().get("active").getAsInt(), "its contents are kept");
    }

    static void testNotJsonIsNull() {
        Check.equal(null, SealedJson.read("not json {", new FakeSealer()), "garbage reads as nothing");
        Check.equal(null, SealedJson.read("[1,2]", new FakeSealer()), "an array is not a document");
    }

    static void testSealFailureNeverFallsBackToPlaintext() {
        Check.throwsA(IllegalStateException.class, () -> SealedJson.write(sample(), BROKEN),
                "a failed seal must throw, not write plaintext");
    }

    static void testForeignBlobThrows() {
        String text = SealedJson.write(sample(), new FakeSealer());
        Check.throwsA(IllegalStateException.class, () -> SealedJson.read(text, BROKEN),
                "another user's file is an error, not an empty list");
    }

    static void testUnknownFormatThrows() {
        Check.throwsA(IllegalStateException.class,
                () -> SealedJson.read("{\"format\":\"dpapi-v9\",\"data\":\"AA==\"}", new FakeSealer()),
                "a format from a newer build is not guessed at");
    }

    static void testBadBase64Throws() {
        Check.throwsA(IllegalStateException.class,
                () -> SealedJson.read("{\"format\":\"dpapi-v1\",\"data\":\"***\"}", new FakeSealer()),
                "corrupt data is reported");
    }
}
