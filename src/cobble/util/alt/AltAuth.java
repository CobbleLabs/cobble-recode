package cobble.util.alt;

import cobble.core.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Alt Manager's logins, ported from Cobble's, which ports the four of the
 * accountmanager Forge mod:
 *
 * <ul>
 *   <li>Microsoft: the OAuth code flow, through the system browser and a
 *       one-shot listener on 127.0.0.1:25575.
 *   <li>Refresh: a Microsoft OAuth refresh token, usually an "M.C..." one.
 *   <li>Token: Minecraft access tokens pasted directly, one or many.
 *   <li>Cookie: browser cookies exported as Netscape .txt or JSON, replayed
 *       against the silent (prompt=none) OAuth endpoints.
 * </ul>
 *
 * <p>Every login runs on one worker thread. The window polls the status each
 * frame and drains finished logins with {@link #takeResults}.
 */
public final class AltAuth {
    private static final String SCOPE = "altauth";

    private static final String MS_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf";
    private static final String MS_TOKEN_URL = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    /* The mod's own registered app, redirecting to our listener. */
    private static final String OAUTH_CLIENT_ID = "42a60a84-599d-44b2-a7c6-b00cdef1d6a2";
    private static final String OAUTH_REDIRECT = "http://localhost:25575/callback";
    private static final int CALLBACK_PORT = 25575;
    private static final long CALLBACK_TIMEOUT_MS = 180_000;

    /* "M.C..." refresh tokens belong to the legacy desktop client and its MBI_SSL scope. */
    private static final String DESKTOP_CLIENT_ID = "00000000402b5328";
    private static final String DESKTOP_REDIRECT = "https://login.live.com/oauth20_desktop.srf";
    private static final String DESKTOP_SCOPE = "service::user.auth.xboxlive.com::MBI_SSL";

    private static final String COOKIE_OAUTH_SISU = "https://login.live.com/oauth20_authorize.srf"
            + "?redirect_uri=https://sisu.xboxlive.com/connect/oauth/XboxLive&response_type=token"
            + "&client_id=000000004420578E&scope=XboxLive.Signin%20XboxLive.offline_access&prompt=none";
    private static final String COOKIE_OAUTH_DESKTOP = "https://login.live.com/oauth20_authorize.srf"
            + "?client_id=00000000402b5328&redirect_uri=https%3A%2F%2Flogin.live.com%2Foauth20_desktop.srf"
            + "&response_type=token&scope=service%3A%3Auser.auth.xboxlive.com%3A%3AMBI_SSL&prompt=none";
    private static final int COOKIE_MAX_HOPS = 12;

    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36";

    /* The MSA endpoints care about cookie order. The mod ships two orders that
     * differ only in whether JSHP or JSH comes first, and tries both. */
    private static final List<String> COOKIE_ORDER_JSHP = List.of("__Host-MSAAUTH", "__Host-MSAAUTHP", "JSHP",
            "JSH", "MSPAuth", "MSPBack", "MSPProf", "MSPRequ", "MSPSoftVis", "MSPOK", "MSPShared", "MSPPre",
            "MSPCID", "MSPOAuthVis", "AMCSecAuth", "NAP", "ANON", "OParams", "PPLState", "WLSSC", "uaid", "pres",
            "LOpt");
    private static final List<String> COOKIE_ORDER_JSH = List.of("__Host-MSAAUTH", "__Host-MSAAUTHP", "JSH",
            "JSHP", "MSPAuth", "MSPBack", "MSPProf", "MSPRequ", "MSPSoftVis", "MSPOK", "MSPShared", "MSPPre",
            "MSPCID", "MSPOAuthVis", "AMCSecAuth", "NAP", "ANON", "OParams", "PPLState", "WLSSC", "uaid", "pres",
            "LOpt");
    private static final List<String> RELEVANT_DOMAINS = List.of("live.com", "microsoftonline.com",
            "microsoft.com", "xboxlive.com", "minecraft.net", "mojang.com");

    private static final String CALLBACK_HTML = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<title>Cobble</title></head><body style=\"background:#111;color:#eee;font-family:Segoe UI,"
            + "sans-serif;text-align:center;padding-top:18vh\"><h2>Signed in</h2><p style=\"color:#888\">"
            + "You can close this tab and go back to Minecraft.</p></body></html>";

    private static final AtomicBoolean busy = new AtomicBoolean();
    private static final AtomicBoolean browsing = new AtomicBoolean();
    private static volatile boolean cancelled;
    private static volatile String status = "";
    private static volatile String error = "";
    private static volatile String authUrl = "";
    private static volatile String browsedFile;
    private static final List<AltAccounts.Account> results = new ArrayList<>();

    private AltAuth() {}

    /** A login step that failed, with the message the window shows for it. */
    private static final class AuthException extends Exception {
        AuthException(String message) {
            super(message);
        }
    }

    public static boolean busy() {
        return busy.get();
    }

    /** One line of progress, such as "Xbox Live...". */
    public static String status() {
        return status;
    }

    /** The last failure, or "" when there is none. */
    public static String error() {
        return error;
    }

    public static void clearError() {
        error = "";
    }

    public static void cancel() {
        cancelled = true;
    }

    /** The URL the browser was sent to, so it can be copied when the browser did not open. */
    public static String authUrl() {
        return authUrl;
    }

    /** Logins finished since the last call, handed over together so a bulk import reads as one. */
    public static List<AltAccounts.Account> takeResults() {
        synchronized (results) {
            List<AltAccounts.Account> out = new ArrayList<>(results);
            results.clear();
            return out;
        }
    }

    public static boolean beginMicrosoft() {
        return start(AltAuth::runMicrosoft);
    }

    public static boolean beginRefresh(String refreshToken) {
        String token = refreshToken.strip();
        if (token.length() < 10) {
            error = "That does not look like a refresh token";
            return false;
        }
        return start(() -> runRefresh(token));
    }

    public static boolean beginTokens(String raw) {
        if (parseTokens(raw).isEmpty()) {
            error = "No access token found in the pasted text";
            return false;
        }
        return start(() -> runTokens(raw));
    }

    public static boolean beginCookies(String raw) {
        if (raw.isBlank()) {
            error = "Paste a cookie export or load a file first";
            return false;
        }
        return start(() -> runCookies(raw));
    }

    /** How many access tokens a paste holds, so the window can say so before the import. */
    public static int countTokens(String raw) {
        return parseTokens(raw).size();
    }

    /** Reads an import off disk. Explorer's "Copy as path" wraps the path in quotes, so those go. */
    public static String readFile(String path) throws IOException {
        String clean = path.strip();
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\"")) {
            clean = clean.substring(1, clean.length() - 1).strip();
        }
        if (clean.isEmpty()) {
            throw new IOException("No file path given");
        }
        String data;
        try {
            data = Files.readString(Path.of(clean), StandardCharsets.UTF_8);
        } catch (InvalidPathException | NoSuchFileException e) {
            throw new IOException("Could not open " + clean);
        }
        if (data.isEmpty()) {
            throw new IOException("The file is empty");
        }
        return data;
    }

    /*
     * The picker runs a modal loop of its own, which would stall the game on
     * the client thread, so it gets a thread of its own. Poll takeBrowsedFile
     * once a frame for the answer.
     */
    public static boolean browseForFile() {
        if (!browsing.compareAndSet(false, true)) {
            return false;
        }
        browsedFile = null;
        Thread picker = new Thread(() -> {
            try {
                FileDialog dialog = new FileDialog((Frame) null, "Cobble - pick a token or cookie file",
                        FileDialog.LOAD);
                dialog.setFile("*.txt;*.json");
                dialog.setAlwaysOnTop(true);
                dialog.setVisible(true);
                browsedFile = dialog.getFile() == null ? "" : new File(dialog.getDirectory(), dialog.getFile()).getPath();
                dialog.dispose();
            } catch (HeadlessException e) {
                error = "No file picker in this client; paste the path instead";
                browsedFile = "";
            } finally {
                browsing.set(false);
            }
        }, "cobble-file-picker");
        picker.setDaemon(true);
        picker.start();
        return true;
    }

    public static boolean browsing() {
        return browsing.get();
    }

    /** The picked path once the dialog closes: "" when it was cancelled, null while it is still up. */
    public static String takeBrowsedFile() {
        if (browsing.get()) {
            return null;
        }
        String picked = browsedFile;
        browsedFile = null;
        return picked;
    }

    private static boolean start(Runnable job) {
        if (!busy.compareAndSet(false, true)) {
            return false;
        }
        cancelled = false;
        error = "";
        status = "Starting...";
        authUrl = "";
        Thread worker = new Thread(() -> {
            try {
                job.run();
            } finally {
                /* The sign-in URL is only good while its listener is up. */
                authUrl = "";
                status = "";
                busy.set(false);
            }
        }, "cobble-alt-auth");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static void fail(String message) {
        error = message;
        Log.warn(SCOPE, "%s", message);
    }

    private static void publish(List<AltAccounts.Account> accounts) {
        synchronized (results) {
            results.addAll(accounts);
        }
    }

    // ---------------------------------------------------------------- flows --

    private static void runMicrosoft() {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        String state = HexFormat.of().formatHex(nonce);
        String url = MS_AUTHORIZE_URL + "?client_id=" + encode(OAUTH_CLIENT_ID) + "&response_type=code"
                + "&redirect_uri=" + encode(OAUTH_REDIRECT)
                + "&scope=" + encode("XboxLive.signin XboxLive.offline_access")
                + "&state=" + encode(state) + "&prompt=select_account";
        /* Bound before the browser opens, so a fast redirect cannot beat the listener. */
        try (ServerSocket server = new ServerSocket(CALLBACK_PORT, 4, InetAddress.getLoopbackAddress())) {
            authUrl = url;
            status = "Waiting for the browser sign-in...";
            openBrowser(url);
            String code = awaitCallback(server, state);
            status = "Exchanging the authorization code...";
            MsTokens tokens = msTokenRequest("client_id=" + encode(OAUTH_CLIENT_ID)
                    + "&grant_type=authorization_code&code=" + encode(code)
                    + "&redirect_uri=" + encode(OAUTH_REDIRECT));
            AltAccounts.Account account = minecraftAccount(tokens.access(), tokens.refresh(),
                    AltAccounts.Type.MICROSOFT);
            publish(List.of(account));
            Log.info(SCOPE, "Microsoft login OK: %s", account.username());
        } catch (AuthException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            fail("Port " + CALLBACK_PORT + " is already in use - close the other launcher and retry");
        }
    }

    private static void runRefresh(String refreshToken) {
        status = "Refreshing the Microsoft token...";
        try {
            MsTokens tokens;
            try {
                tokens = msTokenRequest("client_id=" + encode(DESKTOP_CLIENT_ID) + "&grant_type=refresh_token"
                        + "&redirect_uri=" + encode(DESKTOP_REDIRECT) + "&refresh_token=" + encode(refreshToken)
                        + "&scope=" + encode(DESKTOP_SCOPE));
            } catch (AuthException desktopFailure) {
                /* Not a desktop-client token: most likely one of ours, from the OAuth app. */
                try {
                    tokens = msTokenRequest("client_id=" + encode(OAUTH_CLIENT_ID) + "&grant_type=refresh_token"
                            + "&refresh_token=" + encode(refreshToken) + "&redirect_uri=" + encode(OAUTH_REDIRECT));
                } catch (AuthException ignored) {
                    throw desktopFailure;
                }
            }
            String secret = tokens.refresh().isEmpty() ? refreshToken : tokens.refresh();
            AltAccounts.Account account = minecraftAccount(tokens.access(), secret, AltAccounts.Type.REFRESH);
            publish(List.of(account));
            Log.info(SCOPE, "refresh token login OK: %s", account.username());
        } catch (AuthException e) {
            fail(e.getMessage());
        }
    }

    private static void runTokens(String raw) {
        List<TokenEntry> entries = parseTokens(raw);
        List<AltAccounts.Account> batch = new ArrayList<>();
        String lastError = "";
        for (int index = 0; index < entries.size() && !cancelled; ++index) {
            status = "Checking token " + (index + 1) + "/" + entries.size() + "...";
            TokenEntry entry = entries.get(index);
            try {
                Profile profile = mcProfile(entry.token());
                batch.add(new AltAccounts.Account(profile.name(), profile.uuid(), entry.token(), "",
                        AltAccounts.Type.TOKEN));
            } catch (AuthException e) {
                /* The profile endpoint rate-limits bulk imports; a paste that already
                 * carried a name keeps its entry instead of losing it. */
                if (!entry.name().isEmpty()) {
                    batch.add(new AltAccounts.Account(entry.name(), entry.uuid(), entry.token(), "",
                            AltAccounts.Type.TOKEN));
                } else {
                    lastError = e.getMessage();
                }
            }
        }
        publish(batch);
        if (batch.isEmpty()) {
            fail(lastError.isEmpty() ? "None of the tokens were accepted" : lastError);
            return;
        }
        Log.info(SCOPE, "imported %d/%d access tokens", batch.size(), entries.size());
    }

    private static void runCookies(String raw) {
        status = "Reading cookies...";
        CookieJar imported = CookieJar.parse(raw);
        if (imported.isEmpty()) {
            fail("No usable cookies found (expected Netscape .txt or a JSON export)");
            return;
        }
        if (!imported.hasSessionCookie()) {
            fail("The export has no MSA session cookie (__Host-MSAAUTH / JSH / JSHP)");
            return;
        }
        String[] urls = {COOKIE_OAUTH_SISU, COOKIE_OAUTH_SISU, COOKIE_OAUTH_DESKTOP, COOKIE_OAUTH_DESKTOP};
        List<List<String>> orders = List.of(COOKIE_ORDER_JSHP, COOKIE_ORDER_JSH, COOKIE_ORDER_JSHP, COOKIE_ORDER_JSH);
        String firstError = "";
        for (int attempt = 0; attempt < urls.length && !cancelled; ++attempt) {
            status = "Silent sign-in (" + (attempt + 1) + "/" + urls.length + ")...";
            /* Every attempt starts from the imported jar, not from what the last one picked up. */
            CookieJar jar = imported.copy();
            try {
                String msToken = followOAuthRedirects(urls[attempt], jar, orders.get(attempt));
                AltAccounts.Account account = minecraftAccount(msToken, jar.serialize(), AltAccounts.Type.COOKIE);
                publish(List.of(account));
                Log.info(SCOPE, "cookie login OK: %s", account.username());
                return;
            } catch (AuthException e) {
                if (firstError.isEmpty()) {
                    firstError = e.getMessage();
                }
            }
        }
        fail(cancelled ? "Cancelled" : "Cookies: " + firstError);
    }

    // ------------------------------------------ Microsoft / Xbox / Minecraft --

    private record MsTokens(String access, String refresh) {}

    private record Profile(String name, String uuid) {}

    private static MsTokens msTokenRequest(String form) throws AuthException {
        Response response = send("POST", MS_TOKEN_URL, "application/x-www-form-urlencoded", form, Map.of(),
                "Microsoft");
        JsonObject body = json(response.body());
        if (body == null) {
            throw new AuthException("Microsoft: unreadable response (HTTP " + response.status() + ")");
        }
        if (!response.ok()) {
            String description = string(body, "error_description");
            if (description.isEmpty()) {
                description = string(body, "error");
            }
            throw new AuthException("Microsoft: " + (description.isEmpty() ? "HTTP " + response.status() : description));
        }
        String access = string(body, "access_token");
        if (access.isEmpty()) {
            throw new AuthException("Microsoft: no access token in response");
        }
        return new MsTokens(access, string(body, "refresh_token"));
    }

    /* "d=" for authorization-code tokens, "t=" for the MBI_SSL and implicit ones. */
    private static String xblAuthenticate(String msToken, String prefix) throws AuthException {
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", prefix + msToken);
        JsonObject request = new JsonObject();
        request.add("Properties", properties);
        request.addProperty("RelyingParty", "http://auth.xboxlive.com");
        request.addProperty("TokenType", "JWT");
        Response response = send("POST", XBL_AUTH_URL, "application/json", request.toString(),
                Map.of("User-Agent", "Go-http-client/1.1", "X-Xbl-Contract-Version", "0"), "Xbox Live");
        JsonObject body = json(response.body());
        if (!response.ok()) {
            String message = body == null ? "" : string(body, "Message");
            throw new AuthException("Xbox Live: " + (message.isEmpty() ? "HTTP " + response.status() : message));
        }
        String token = body == null ? "" : string(body, "Token");
        if (token.isEmpty()) {
            throw new AuthException("Xbox Live: no token in response");
        }
        return token;
    }

    private record Xsts(String token, String userHash) {}

    private static Xsts xstsAuthorize(String xblToken) throws AuthException {
        JsonArray userTokens = new JsonArray();
        userTokens.add(new JsonPrimitive(xblToken));
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);
        JsonObject request = new JsonObject();
        request.add("Properties", properties);
        request.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        request.addProperty("TokenType", "JWT");
        Response response = send("POST", XSTS_AUTH_URL, "application/json", request.toString(), Map.of(), "XSTS");
        JsonObject body = json(response.body());
        if (!response.ok()) {
            String mapped = body == null || !body.has("XErr") ? null : xstsError(body.get("XErr").getAsLong());
            if (mapped != null) {
                throw new AuthException(mapped);
            }
            String message = body == null ? "" : string(body, "Message");
            throw new AuthException("XSTS: " + (message.isEmpty() ? "HTTP " + response.status() : message));
        }
        String token = body == null ? "" : string(body, "Token");
        String userHash = "";
        if (body != null && body.has("DisplayClaims") && body.get("DisplayClaims").isJsonObject()) {
            JsonElement xui = body.getAsJsonObject("DisplayClaims").get("xui");
            if (xui != null && xui.isJsonArray() && xui.getAsJsonArray().size() > 0
                    && xui.getAsJsonArray().get(0).isJsonObject()) {
                userHash = string(xui.getAsJsonArray().get(0).getAsJsonObject(), "uhs");
            }
        }
        if (token.isEmpty() || userHash.isEmpty()) {
            throw new AuthException("XSTS: incomplete response");
        }
        return new Xsts(token, userHash);
    }

    private static String xstsError(long code) {
        if (code == 2148916227L) {
            return "This account is banned from Xbox";
        }
        if (code == 2148916233L) {
            return "This account has no Xbox profile - create one at xbox.com first";
        }
        if (code == 2148916235L) {
            return "Xbox Live is not available in this account's country";
        }
        if (code == 2148916236L || code == 2148916237L) {
            return "This account needs adult verification (South Korea)";
        }
        if (code == 2148916238L) {
            return "Child account - it must be added to a Family to use Xbox Live";
        }
        if (code == 2148916262L) {
            return "Xbox Live rejected the account (unknown reason)";
        }
        return null;
    }

    private static String mcLoginWithXbox(Xsts xsts, boolean ensureLegacy) throws AuthException {
        JsonObject request = new JsonObject();
        request.addProperty("identityToken", "XBL3.0 x=" + xsts.userHash() + ";" + xsts.token());
        if (ensureLegacy) {
            request.addProperty("ensureLegacyEnabled", true);
        }
        Response response = send("POST", MC_LOGIN_URL, "application/json", request.toString(), Map.of(),
                "Minecraft");
        JsonObject body = json(response.body());
        if (!response.ok()) {
            String message = body == null ? "" : string(body, "errorMessage");
            if (message.isEmpty() && body != null) {
                message = string(body, "error");
            }
            throw new AuthException("Minecraft: " + (message.isEmpty() ? "HTTP " + response.status() : message));
        }
        String token = body == null ? "" : string(body, "access_token");
        if (token.isEmpty()) {
            throw new AuthException("Minecraft: no access token in response");
        }
        return token;
    }

    private static Profile mcProfile(String mcToken) throws AuthException {
        Response response = send("GET", MC_PROFILE_URL, null, null, Map.of("Authorization", "Bearer " + mcToken),
                "Profile");
        if (response.status() == 404) {
            throw new AuthException("This account does not own Minecraft: Java Edition");
        }
        if (response.status() == 401) {
            throw new AuthException("The access token was rejected (expired or invalid)");
        }
        JsonObject body = json(response.body());
        if (!response.ok() || body == null) {
            throw new AuthException("Profile: HTTP " + response.status());
        }
        String name = string(body, "name");
        if (name.isEmpty()) {
            throw new AuthException("Profile: no username in response");
        }
        return new Profile(name, undashed(string(body, "id")));
    }

    /* A Microsoft access token to a whole Minecraft session. Both RpsTicket
     * prefixes are tried, so the caller need not know which family the token is. */
    private static AltAccounts.Account minecraftAccount(String msToken, String secret, AltAccounts.Type type)
            throws AuthException {
        status = "Xbox Live...";
        String xbl;
        try {
            xbl = xblAuthenticate(msToken, "d=");
        } catch (AuthException first) {
            try {
                xbl = xblAuthenticate(msToken, "t=");
            } catch (AuthException ignored) {
                throw first;
            }
        }
        checkCancelled();
        status = "XSTS...";
        Xsts xsts = xstsAuthorize(xbl);
        checkCancelled();
        status = "Minecraft services...";
        String mcToken;
        try {
            mcToken = mcLoginWithXbox(xsts, false);
        } catch (AuthException first) {
            try {
                mcToken = mcLoginWithXbox(xsts, true);
            } catch (AuthException ignored) {
                throw first;
            }
        }
        checkCancelled();
        status = "Fetching profile...";
        Profile profile = mcProfile(mcToken);
        return new AltAccounts.Account(profile.name(), profile.uuid(), mcToken, secret, type);
    }

    private static void checkCancelled() throws AuthException {
        if (cancelled) {
            throw new AuthException("Cancelled");
        }
    }

    // ----------------------------------------------------- browser callback --

    private static void openBrowser(String url) throws AuthException {
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (IOException e) {
            throw new AuthException("Could not open the browser - copy the sign-in link instead");
        }
    }

    /* Blocks until the browser hits /callback, the timeout passes or the login is cancelled. */
    private static String awaitCallback(ServerSocket server, String expectedState) throws AuthException {
        long deadline = System.currentTimeMillis() + CALLBACK_TIMEOUT_MS;
        try {
            server.setSoTimeout(250);
        } catch (IOException e) {
            throw new AuthException("Could not listen on port " + CALLBACK_PORT);
        }
        while (true) {
            checkCancelled();
            if (System.currentTimeMillis() > deadline) {
                throw new AuthException("Timed out waiting for the browser sign-in");
            }
            try (Socket client = server.accept()) {
                client.setSoTimeout(3000);
                String target = requestTarget(client.getInputStream());
                if (!target.startsWith("/callback")) {
                    reply(client, "404 Not Found", "text/plain", "not found");
                    continue;
                }
                int question = target.indexOf('?');
                String query = question < 0 ? "" : target.substring(question + 1);
                if (!expectedState.equals(param(query, "state"))) {
                    reply(client, "400 Bad Request", "text/plain", "state mismatch");
                    throw new AuthException("OAuth state mismatch - sign-in aborted");
                }
                String oauthError = param(query, "error");
                if (!oauthError.isEmpty()) {
                    reply(client, "200 OK", "text/html; charset=utf-8", CALLBACK_HTML);
                    String description = param(query, "error_description");
                    throw new AuthException("Microsoft: " + (description.isEmpty() ? oauthError : description));
                }
                String code = param(query, "code");
                if (code.isEmpty()) {
                    reply(client, "400 Bad Request", "text/plain", "no code");
                    throw new AuthException("Microsoft returned no authorization code");
                }
                reply(client, "200 OK", "text/html; charset=utf-8", CALLBACK_HTML);
                return code;
            } catch (SocketTimeoutException e) {
                /* No browser yet: go round and check the deadline and the cancel. */
            } catch (IOException e) {
                Log.debug(SCOPE, "callback connection dropped: %s", e.getMessage());
            }
        }
    }

    /* "GET /callback?code=...&state=... HTTP/1.1" -> "/callback?code=...&state=..." */
    private static String requestTarget(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int next;
        while ((next = in.read()) >= 0 && next != '\n' && head.size() < 16384) {
            head.write(next);
        }
        String line = head.toString(StandardCharsets.ISO_8859_1);
        String[] parts = line.split(" ");
        return parts.length >= 2 ? parts[1] : "";
    }

    private static void reply(Socket client, String status, String contentType, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\nContent-Type: " + contentType + "\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // --------------------------------------------------------------- cookies --

    /*
     * Replays a silent OAuth endpoint and walks the redirect chain by hand,
     * merging Set-Cookie at every hop, until an access token turns up in a
     * Location fragment or query.
     */
    private static String followOAuthRedirects(String startUrl, CookieJar jar, List<String> order)
            throws AuthException {
        String url = startUrl;
        for (int hop = 0; hop < COOKIE_MAX_HOPS; ++hop) {
            checkCancelled();
            URL parsed = url(url);
            String host = parsed.getHost().toLowerCase(Locale.ROOT);
            String path = parsed.getPath().isEmpty() ? "/" : parsed.getPath();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", BROWSER_USER_AGENT);
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "en-US,en;q=0.9");
            String cookies = jar.header(host, path, "https".equalsIgnoreCase(parsed.getProtocol()), order);
            if (!cookies.isEmpty()) {
                headers.put("Cookie", cookies);
            }
            Response response = send("GET", url, null, null, headers, "Cookies");
            for (String setCookie : response.headers("Set-Cookie")) {
                jar.merge(setCookie, host);
            }

            List<String> locations = response.headers("Location");
            boolean redirect = response.status() >= 300 && response.status() < 400;
            if (locations.isEmpty()) {
                /* A page instead of a token: the cookies no longer sign in silently
                 * (usually expired, or the account wants interactive MFA). */
                throw new AuthException(redirect ? "redirect without a Location header"
                        : "the cookies are no longer valid for a silent sign-in");
            }
            String next = resolve(parsed, locations.get(0));
            int hash = next.indexOf('#');
            if (hash >= 0) {
                String token = param(next.substring(hash + 1), "access_token");
                if (!token.isEmpty()) {
                    return token;
                }
            }
            int question = next.indexOf('?');
            if (question >= 0) {
                String query = next.substring(question + 1, hash > question ? hash : next.length());
                String token = param(query, "access_token");
                if (!token.isEmpty()) {
                    return token;
                }
                String oauthError = param(query, "error");
                if (!oauthError.isEmpty()) {
                    String description = param(query, "error_description");
                    throw new AuthException(description.isEmpty() ? oauthError : description);
                }
            }
            if (!redirect) {
                throw new AuthException("the cookies are no longer valid for a silent sign-in");
            }
            url = next;
        }
        throw new AuthException("too many redirects");
    }

    private static String resolve(URL base, String location) throws AuthException {
        try {
            return new URL(base, location).toString();
        } catch (IOException e) {
            throw new AuthException("bad redirect: " + location);
        }
    }

    private static URL url(String value) throws AuthException {
        try {
            return new URL(value);
        } catch (IOException e) {
            throw new AuthException("bad URL: " + value);
        }
    }

    private record Cookie(String domain, String path, String name, String value, boolean secure) {}

    /** The cookies one silent sign-in replays, in the order the endpoints want them. */
    private static final class CookieJar {
        private final List<Cookie> cookies = new ArrayList<>();

        CookieJar copy() {
            CookieJar copy = new CookieJar();
            copy.cookies.addAll(cookies);
            return copy;
        }

        boolean isEmpty() {
            return cookies.isEmpty();
        }

        boolean hasSessionCookie() {
            for (Cookie cookie : cookies) {
                switch (cookie.name()) {
                    case "__Host-MSAAUTH", "__Host-MSAAUTHP", "JSH", "JSHP" -> {
                        return true;
                    }
                    default -> {
                    }
                }
            }
            return false;
        }

        void add(Cookie cookie) {
            /* "Disabled" is an MSA sentinel; replaying it breaks the flow. */
            if (cookie.name().isEmpty() || cookie.value().isEmpty() || cookie.value().equals("Disabled")
                    || !relevant(cookie.domain())) {
                return;
            }
            for (int index = 0; index < cookies.size(); ++index) {
                Cookie existing = cookies.get(index);
                if (existing.name().equals(cookie.name()) && existing.domain().equals(cookie.domain())
                        && existing.path().equals(cookie.path())) {
                    cookies.set(index, cookie);
                    return;
                }
            }
            cookies.add(cookie);
        }

        void merge(String setCookie, String requestHost) {
            String[] parts = setCookie.split(";");
            int equals = parts[0].indexOf('=');
            if (equals < 0) {
                return;
            }
            String name = parts[0].substring(0, equals).strip();
            String value = parts[0].substring(equals + 1).strip();
            if (value.equals("\"\"")) {
                return;
            }
            String domain = domain(requestHost);
            String path = "/";
            for (int index = 1; index < parts.length; ++index) {
                String attribute = parts[index].strip();
                int split = attribute.indexOf('=');
                String key = (split < 0 ? attribute : attribute.substring(0, split)).toLowerCase(Locale.ROOT);
                String attributeValue = split < 0 ? "" : attribute.substring(split + 1).strip();
                if (key.equals("domain") && !attributeValue.isEmpty()) {
                    domain = domain(attributeValue);
                } else if (key.equals("path") && !attributeValue.isEmpty()) {
                    path = attributeValue;
                }
            }
            add(new Cookie(domain, path, name, value, true));
        }

        String header(String host, String path, boolean https, List<String> order) {
            List<Cookie> matching = new ArrayList<>();
            for (Cookie cookie : cookies) {
                if ((!cookie.secure() || https) && domainMatches(host, cookie.domain())
                        && pathMatches(path, cookie.path())) {
                    matching.add(cookie);
                }
            }
            matching.sort((a, b) -> Integer.compare(rank(order, a.name()), rank(order, b.name())));
            StringBuilder out = new StringBuilder();
            for (Cookie cookie : matching) {
                if (!out.isEmpty()) {
                    out.append("; ");
                }
                out.append(cookie.name()).append('=').append(cookie.value());
            }
            return out.toString();
        }

        /* Named cookies in the given order, everything else after them as it came. */
        private static int rank(List<String> order, String name) {
            int index = order.indexOf(name);
            return index < 0 ? order.size() : index;
        }

        String serialize() {
            JsonArray list = new JsonArray();
            for (Cookie cookie : cookies) {
                JsonObject entry = new JsonObject();
                entry.addProperty("domain", cookie.domain());
                entry.addProperty("path", cookie.path());
                entry.addProperty("name", cookie.name());
                entry.addProperty("value", cookie.value());
                entry.addProperty("secure", cookie.secure());
                list.add(entry);
            }
            JsonObject root = new JsonObject();
            root.addProperty("v", 2);
            root.add("cookies", list);
            return root.toString();
        }

        /* A JSON export, then Netscape cookies.txt, then a raw "a=b; c=d" blob out of devtools. */
        static CookieJar parse(String text) {
            CookieJar jar = new CookieJar();
            String trimmed = text.strip();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                jar.addJson(trimmed);
                if (!jar.isEmpty()) {
                    return jar;
                }
            }
            jar.addNetscape(trimmed);
            if (jar.isEmpty()) {
                jar.addLoose(trimmed);
            }
            return jar;
        }

        private void addJson(String text) {
            JsonElement root;
            try {
                root = new JsonParser().parse(text);
            } catch (JsonParseException e) {
                return;
            }
            JsonArray list = null;
            if (root.isJsonArray()) {
                list = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("cookies")
                    && root.getAsJsonObject().get("cookies").isJsonArray()) {
                list = root.getAsJsonObject().getAsJsonArray("cookies");
            }
            if (list == null) {
                return;
            }
            double now = System.currentTimeMillis() / 1000.0;
            for (JsonElement element : list) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                /* Expired entries are dead weight and can confuse the endpoints. */
                double expires = entry.has("expirationDate") && entry.get("expirationDate").isJsonPrimitive()
                        ? entry.get("expirationDate").getAsDouble() : 0.0;
                if (expires > 0.0 && expires < now) {
                    continue;
                }
                String domain = string(entry, "domain");
                if (domain.isEmpty()) {
                    domain = string(entry, "host");
                }
                String path = string(entry, "path");
                boolean secure = !entry.has("secure") || entry.get("secure").getAsBoolean();
                add(new Cookie(domain(domain), path.isEmpty() ? "/" : path, string(entry, "name"),
                        string(entry, "value"), secure));
            }
        }

        /* Seven tab-separated fields a line, '#' comments. */
        private void addNetscape(String text) {
            for (String raw : text.split("\n")) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\t");
                if (fields.length < 7) {
                    continue;
                }
                add(new Cookie(domain(fields[0]), fields[2].isEmpty() ? "/" : fields[2], fields[5].strip(),
                        fields[6].strip(), fields[3].strip().equalsIgnoreCase("true")));
            }
        }

        /* No domain information at all, so everything is pinned to .live.com, which is where it matters. */
        private void addLoose(String text) {
            for (String chunk : text.split("[;\n]")) {
                String pair = chunk.strip();
                int equals = pair.indexOf('=');
                if (equals <= 0 || pair.substring(0, equals).strip().contains(" ")) {
                    continue;
                }
                add(new Cookie(".live.com", "/", pair.substring(0, equals).strip(),
                        pair.substring(equals + 1).strip(), true));
            }
        }

        private static boolean relevant(String domain) {
            for (String needle : RELEVANT_DOMAINS) {
                if (domain.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        /* Lower case, with a leading dot when it covers subdomains. */
        private static String domain(String raw) {
            String domain = raw.strip().toLowerCase(Locale.ROOT);
            return domain.isEmpty() || domain.startsWith(".") || !domain.contains(".") ? domain : "." + domain;
        }

        private static boolean domainMatches(String host, String domain) {
            String bare = domain.startsWith(".") ? domain.substring(1) : domain;
            return !bare.isEmpty() && (host.equals(bare) || host.endsWith("." + bare));
        }

        private static boolean pathMatches(String requestPath, String cookiePath) {
            if (cookiePath.isEmpty() || cookiePath.equals("/")) {
                return true;
            }
            return requestPath.startsWith(cookiePath) && (requestPath.length() == cookiePath.length()
                    || requestPath.charAt(cookiePath.length()) == '/' || cookiePath.endsWith("/"));
        }
    }

    // --------------------------------------------------------- token pastes --

    private record TokenEntry(String token, String name, String uuid) {}

    /*
     * One token a line, with an optional "Accesstoken:" style prefix and
     * optional "|name" / "|McName:name" / "|uuid" suffixes. Duplicates go.
     */
    private static List<TokenEntry> parseTokens(String raw) {
        List<TokenEntry> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String rawLine : raw.split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.length() > 50000) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon >= 0 && colon <= 16) {
                String prefix = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                if (prefix.equals("accesstoken") || prefix.equals("mctoken") || prefix.equals("token")) {
                    line = line.substring(colon + 1).strip();
                }
            }
            String[] fields = line.split("\\|");
            String name = "";
            String uuid = "";
            for (int index = 1; index < fields.length; ++index) {
                String field = fields[index].strip();
                int split = field.indexOf(':');
                if (split >= 0) {
                    String key = field.substring(0, split).strip().toLowerCase(Locale.ROOT);
                    if (key.equals("mcname") || key.equals("name") || key.equals("username")) {
                        field = field.substring(split + 1).strip();
                    }
                }
                if (field.isEmpty()) {
                    continue;
                }
                if (looksLikeUuid(field)) {
                    uuid = undashed(field);
                } else if (name.isEmpty()) {
                    name = field;
                }
            }
            String token = findJwt(fields[0].strip());
            if (!token.isEmpty() && !seen.contains(token)) {
                seen.add(token);
                out.add(new TokenEntry(token, name, uuid));
            }
        }
        return out;
    }

    /* The first "eyJ..." run with at least two dots, long enough to be real. */
    private static String findJwt(String text) {
        int start = text.indexOf("eyJ");
        while (start >= 0) {
            int end = start;
            int dots = 0;
            while (end < text.length() && tokenChar(text.charAt(end))) {
                dots += text.charAt(end) == '.' ? 1 : 0;
                ++end;
            }
            if (dots >= 2 && end - start >= 20) {
                return text.substring(start, end);
            }
            start = text.indexOf("eyJ", Math.max(end, start + 1));
        }
        return "";
    }

    private static boolean tokenChar(char character) {
        return character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9' || character == '-' || character == '_' || character == '.';
    }

    private static boolean looksLikeUuid(String text) {
        return text.matches("[0-9a-fA-F]{32}")
                || text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /* Session.playerID holds the undashed uuid in 1.8.9, so dashed input is brought down to it. */
    private static String undashed(String uuid) {
        String clean = uuid.replace("-", "");
        return clean.length() == 32 ? clean : uuid;
    }

    // ------------------------------------------------------------------ HTTP --

    private record Response(int status, String body, Map<String, List<String>> headerFields) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        List<String> headers(String name) {
            for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return List.of();
        }
    }

    /* Redirects are never followed: the cookie flow reads the tokens out of them. */
    private static Response send(String method, String url, String contentType, String body,
                                 Map<String, String> headers, String service) throws AuthException {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/json");
            headers.forEach(connection::setRequestProperty);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = "";
            if (stream != null) {
                try (InputStream in = stream) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return new Response(status, text, connection.getHeaderFields());
        } catch (IOException e) {
            throw new AuthException(service + ": " + (e.getMessage() == null ? "no connection" : e.getMessage()));
        }
    }

    private static JsonObject json(String body) {
        try {
            JsonElement parsed = new JsonParser().parse(body);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /* key=value out of a query string or a URL fragment. */
    private static String param(String query, String key) {
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals >= 0 && pair.substring(0, equals).equals(key)) {
                return URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
