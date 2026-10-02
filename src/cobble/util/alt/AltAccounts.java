package cobble.util.alt;

import cobble.core.Log;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

/**
 * The saved accounts and the one in use. Using an account rewrites the game's
 * live Session in place, so everything holding a reference to it - the
 * server join, the login packet, Lunar - sees the new account. A premium
 * account swaps the name, uuid and token, which is what online-mode servers
 * check; an offline one swaps the name alone.
 *
 * <p>The list lives in its own file: the tokens and cookie jars are far too
 * long for the config's lines.
 */
public final class AltAccounts {
    private static final String SCOPE = "altmanager";
    private static final int MAX_ACCOUNTS = 64;

    /* Stored by ordinal, so never reorder. */
    public enum Type { CRACKED, MICROSOFT, TOKEN, REFRESH, COOKIE }

    /** secret is the Microsoft refresh token, or the cookie jar of a cookie login. */
    public record Account(String username, String uuid, String accessToken, String secret, Type type) {
        public boolean premium() {
            return type != Type.CRACKED;
        }
    }

    private record Original(String username, String uuid, String token) {}

    private static final List<Account> accounts = new ArrayList<>();
    private static int active = -1;
    private static Original original;
    private static Path file;

    private AltAccounts() {}

    public static List<Account> accounts() {
        return Collections.unmodifiableList(accounts);
    }

    public static int active() {
        return active;
    }

    /** The account the game was launched with, or null before anything was swapped. */
    public static String originalName() {
        return original == null ? null : original.username();
    }

    public static boolean validName(String name) {
        return name.matches("[A-Za-z0-9_]{3,16}");
    }

    /** Loads the list and puts the account that was in use back into the session. */
    public static void load(Path path) {
        file = path;
        accounts.clear();
        active = -1;
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            Log.warn(SCOPE, "accounts not loaded: %s", e.getMessage());
            return;
        }
        JsonObject root;
        try {
            JsonElement parsed = new JsonParser().parse(text);
            if (!parsed.isJsonObject()) {
                return;
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            Log.warn(SCOPE, "accounts file unreadable: %s", e.getMessage());
            return;
        }
        if (root.has("accounts") && root.get("accounts").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("accounts")) {
                if (!element.isJsonObject() || accounts.size() >= MAX_ACCOUNTS) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                int ordinal = entry.has("type") ? entry.get("type").getAsInt() : 0;
                Type type = ordinal >= 0 && ordinal < Type.values().length ? Type.values()[ordinal] : Type.CRACKED;
                String username = string(entry, "username");
                /* A premium name is whatever Mojang says it is; only offline ones must look like names. */
                if (username.isEmpty() || type == Type.CRACKED && !validName(username)) {
                    continue;
                }
                accounts.add(new Account(username, string(entry, "uuid"), string(entry, "token"),
                        string(entry, "secret"), type));
            }
        }
        int stored = root.has("active") ? root.get("active").getAsInt() : -1;
        if (stored >= 0 && stored < accounts.size()) {
            use(stored);
        }
    }

    private static void save() {
        JsonArray list = new JsonArray();
        for (Account account : accounts) {
            JsonObject entry = new JsonObject();
            entry.addProperty("username", account.username());
            entry.addProperty("uuid", account.uuid());
            entry.addProperty("token", account.accessToken());
            entry.addProperty("secret", account.secret());
            entry.addProperty("type", account.type().ordinal());
            list.add(entry);
        }
        JsonObject root = new JsonObject();
        root.addProperty("active", active);
        root.add("accounts", list);
        try {
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.error(SCOPE, "accounts not saved: %s", e.getMessage());
        }
    }

    /** Adds an offline name and logs in with it; false when the list is full. */
    public static boolean addCracked(String username) {
        /* Only collapses onto another offline entry: a premium account may carry
         * the same name, and reusing it would log in with its session. */
        for (int index = 0; index < accounts.size(); ++index) {
            Account account = accounts.get(index);
            if (!account.premium() && account.username().equals(username)) {
                use(index);
                return true;
            }
        }
        if (accounts.size() >= MAX_ACCOUNTS) {
            return false;
        }
        accounts.add(new Account(username, "", "", "", Type.CRACKED));
        use(accounts.size() - 1);
        return true;
    }

    /** Adds or refreshes in place an account out of a real login; its index, or -1 when the list is full. */
    public static int addPremium(Account account) {
        for (int index = 0; index < accounts.size(); ++index) {
            Account existing = accounts.get(index);
            boolean sameUuid = !account.uuid().isEmpty() && existing.uuid().equals(account.uuid());
            boolean sameName = existing.premium() && existing.username().equals(account.username());
            if (sameUuid || sameName) {
                String secret = account.secret().isEmpty() ? existing.secret() : account.secret();
                String uuid = account.uuid().isEmpty() ? existing.uuid() : account.uuid();
                accounts.set(index, new Account(account.username(), uuid, account.accessToken(), secret,
                        account.type()));
                save();
                return index;
            }
        }
        if (accounts.size() >= MAX_ACCOUNTS) {
            return -1;
        }
        accounts.add(account);
        save();
        return accounts.size() - 1;
    }

    public static void use(int index) {
        Account account = accounts.get(index);
        captureOriginal();
        if (account.premium()) {
            /* Missing pieces keep whatever the real session had rather than going blank. */
            writeSession(account.username(), account.uuid().isEmpty() ? null : account.uuid(),
                    account.accessToken().isEmpty() ? null : account.accessToken());
        } else {
            writeSession(account.username(), null, null);
        }
        active = index;
        save();
    }

    /** Deleting the account in use also hands the real session back. */
    public static void remove(int index) {
        accounts.remove(index);
        if (active == index) {
            resetToOriginal();
        } else if (active > index) {
            --active;
        }
        save();
    }

    public static void resetToOriginal() {
        active = -1;
        if (original != null) {
            writeSession(original.username(), original.uuid(), original.token());
        }
        save();
    }

    /* Snapshotted before the first swap, so there is always a real account to go back to. */
    private static void captureOriginal() {
        if (original == null) {
            Session session = Minecraft.getMinecraft().getSession();
            original = new Original(session.getUsername(), session.getPlayerID(), session.getToken());
        }
    }

    /* The fields are final, so they go through reflection; null leaves one as it is. */
    private static void writeSession(String username, String uuid, String token) {
        Session session = Minecraft.getMinecraft().getSession();
        try {
            set(session, "username", username);
            set(session, "playerID", uuid);
            set(session, "token", token);
            Log.info(SCOPE, "session is now '%s'", username);
        } catch (ReflectiveOperationException e) {
            Log.error(SCOPE, "session not changed: %s", e);
        }
    }

    private static void set(Session session, String name, String value) throws ReflectiveOperationException {
        if (value == null) {
            return;
        }
        Field field = Session.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(session, value);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
