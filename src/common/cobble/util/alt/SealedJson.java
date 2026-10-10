package cobble.util.alt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The on-disk shape of a JSON document that must not be readable off the disk:
 * {@code {"format":"dpapi-v1","data":"<base64 of the sealed JSON>"}}. Sealing is
 * the bootstrap's DPAPI, tied to the Windows user, so a copied file is useless
 * anywhere else. A file without the wrapper is the plaintext layout every
 * version before this one wrote, and is read as it is so it can be resealed.
 */
final class SealedJson {
    static final String FORMAT = "dpapi-v1";

    /** What seals and opens the bytes; {@link #DPAPI} in the game, a stand-in in tests. */
    interface Sealer {
        byte[] seal(byte[] plain);

        byte[] open(byte[] sealed);
    }

    static final Sealer DPAPI = new Sealer() {
        @Override
        public byte[] seal(byte[] plain) {
            return cobble.boot.Native.protect(plain);
        }

        @Override
        public byte[] open(byte[] sealed) {
            return cobble.boot.Native.unprotect(sealed);
        }
    };

    /** A read file: the document, and whether it was still plaintext on disk. */
    record Read(JsonObject root, boolean plaintext) {}

    private SealedJson() {}

    /**
     * Null for text that is not a JSON object. Throws IllegalStateException
     * when the file is sealed but cannot be opened, which is a different
     * Windows user or machine rather than an empty list.
     */
    static Read read(String text, Sealer sealer) {
        JsonObject outer = object(text);
        if (outer == null) {
            return null;
        }
        if (!outer.has("data") || !outer.has("format")) {
            return new Read(outer, true);
        }
        String format = outer.get("format").getAsString();
        if (!FORMAT.equals(format)) {
            throw new IllegalStateException("unknown format '" + format + "'");
        }
        byte[] sealed;
        try {
            sealed = Base64.getDecoder().decode(outer.get("data").getAsString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("data is not base64", e);
        }
        JsonObject inner = object(new String(sealer.open(sealed), StandardCharsets.UTF_8));
        if (inner == null) {
            throw new IllegalStateException("opened to something that is not a JSON object");
        }
        return new Read(inner, false);
    }

    /** Throws when the sealer cannot seal: callers must never fall back to plaintext. */
    static String write(JsonObject root, Sealer sealer) {
        byte[] sealed = sealer.seal(root.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject outer = new JsonObject();
        outer.addProperty("format", FORMAT);
        outer.addProperty("data", Base64.getEncoder().encodeToString(sealed));
        return outer.toString();
    }

    private static JsonObject object(String text) {
        try {
            JsonElement parsed = new JsonParser().parse(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }
}
