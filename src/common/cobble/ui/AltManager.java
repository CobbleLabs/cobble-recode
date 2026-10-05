package cobble.ui;

import cobble.core.Color;
import cobble.core.Ease;
import cobble.core.Maths;
import cobble.core.Rect;
import cobble.hook.Input;
import cobble.module.Context;
import cobble.platform.Platform;
import cobble.render.Align;
import cobble.render.FontRole;
import cobble.render.Icon;
import cobble.render.Renderer;
import cobble.util.alt.AltAccounts;
import cobble.util.alt.AltAuth;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Cobble's Alt Manager window: a login method picker with the inputs for the
 * picked one, the saved accounts, and a way back to the account the game was
 * launched with. Opened from its module, or with the menu key on the
 * multiplayer screen.
 *
 * <p>The window is drawn immediate mode: every frame lays it out from scratch
 * and records what each clickable rectangle does, and a click runs whatever
 * the last frame put under the pointer.
 */
public final class AltManager {
    private static final float WIDTH = 280.0f;
    private static final float NOMINAL_HEIGHT = 320.0f;
    private static final float WIDTH_SHARE = 0.36f;
    private static final float HEIGHT_SHARE = 0.62f;
    private static final float MINIMUM_SCALE = 1.4f;
    private static final float CAP_MIDDLE = 0.3635f;

    private static final float MARGIN = 10.0f;
    private static final float GAP = 5.0f;
    private static final float TAB_HEIGHT = 15.0f;
    private static final float FIELD_HEIGHT = 18.0f;
    private static final float BUTTON_HEIGHT = 16.0f;
    private static final float PASTE_HEIGHT = 26.0f;
    private static final float STATUS_HEIGHT = 9.0f;
    private static final float ROW_HEIGHT = 18.0f;
    private static final float ROW_GAP = 3.0f;
    private static final int VISIBLE_ROWS = 6;
    private static final float TEXT_SIZE = 7.8f;
    private static final float SMALL_SIZE = 6.8f;

    private static final int KEY_V = 86;
    private static final int MAX_NAME = 16;
    private static final int MAX_LINE = 4096;

    private enum Method {
        OFFLINE("Offline", "Offline account login"),
        MICROSOFT("Microsoft", "Microsoft account login"),
        TOKEN("Token", "Access token login"),
        REFRESH("Refresh", "Refresh token login"),
        COOKIE("Cookie", "Browser cookie login");

        final String label;
        final String title;

        Method(String label, String title) {
            this.label = label;
            this.title = title;
        }
    }

    private enum Field { NAME, REFRESH, TOKEN_PASTE, TOKEN_PATH, COOKIE_PASTE, COOKIE_PATH }

    private record Target(Rect bounds, Runnable action) {}

    /** What an import file held, kept out of the paste box: cookie exports run to megabytes. */
    private record Loaded(String name, String data) {}

    private final Renderer gfx = Renderer.instance();
    private final List<Target> targets = new ArrayList<>();
    private final Map<Field, String> text = new EnumMap<>(Field.class);

    private Method method = Method.OFFLINE;
    private Field focus;
    private Loaded tokenFile;
    private int tokenFileCount;
    private Loaded cookieFile;
    private int pasteCount;
    private Field browseTarget;
    private String note = "";
    private boolean noteIsError;

    private boolean open;
    private float alpha;
    private float appear;
    private float unit = 2.0f;
    private Rect panel = Rect.EMPTY;
    private Rect listBounds = Rect.EMPTY;
    private float scroll;
    private float scrollTarget;
    private float mouseX;
    private float mouseY;
    private float time;

    public AltManager() {
        for (Field field : Field.values()) {
            text.put(field, "");
        }
    }

    public void open() {
        if (open) {
            return;
        }
        open = true;
        appear = 0.0f;
        focus = method == Method.OFFLINE ? Field.NAME : null;
        Platform.get().showMenuScreen();
    }

    public void close() {
        if (!open) {
            return;
        }
        open = false;
        focus = null;
        /* The menu may still want the cursor, and dropping the screen would take it away from the menu as well. */
        if (!Client.gui().interactive()) {
            Platform.get().hideMenuScreen();
        }
    }

    public boolean interactive() {
        return open;
    }

    private float px(float units) {
        return units * unit;
    }

    public void render(Context context) {
        /* Drained whether or not the window is up: a login can finish after it closed. */
        collectLogins();
        collectBrowsedFile();

        float base = Maths.clamp(Math.min(context.state.windowWidth * WIDTH_SHARE / WIDTH,
                context.state.windowHeight * HEIGHT_SHARE / NOMINAL_HEIGHT), MINIMUM_SCALE, 6.0f);
        alpha = Maths.approach(alpha, open ? 1.0f : 0.0f, 18.0f, context.deltaTime);
        appear = Maths.approach(appear, open ? 1.0f : 0.0f, open ? 13.0f : 22.0f, context.deltaTime);
        targets.clear();
        if (alpha <= 0.004f) {
            return;
        }
        time = context.time;
        unit = base * (0.94f + 0.06f * Ease.outQuint(Maths.clamp(appear, 0.0f, 1.0f)));

        int rows = Math.max(1, Math.min(VISIBLE_ROWS, AltAccounts.accounts().size()));
        float listHeight = rows * ROW_HEIGHT + (rows - 1) * ROW_GAP;
        float height = 40.0f + TAB_HEIGHT + GAP + bodyHeight() + GAP + STATUS_HEIGHT + GAP + 1.0f + GAP
                + listHeight + GAP + BUTTON_HEIGHT + MARGIN;
        panel = new Rect(context.state.windowWidth * 0.5f - px(WIDTH) * 0.5f,
                context.state.windowHeight * 0.5f - px(height) * 0.5f, px(WIDTH), px(height));

        gfx.shadow(panel, Theme.SHADOW.withAlpha(0.8f * alpha), px(18.0f), px(Theme.RADIUS_PANEL));
        Theme.drawPanel(panel, px(Theme.RADIUS_PANEL), alpha, false);
        gfx.pushClip(panel.inset(px(1.0f)));

        float left = panel.x() + px(MARGIN);
        float width = panel.width() - px(MARGIN * 2.0f);
        drawHeader(left);
        float y = panel.y() + px(40.0f);
        drawTabs(new Rect(left, y, width, px(TAB_HEIGHT)));
        y += px(TAB_HEIGHT + GAP);
        y = drawBody(left, y, width);
        drawStatus(new Rect(left, y + px(GAP), width, px(STATUS_HEIGHT)));
        y += px(GAP + STATUS_HEIGHT + GAP);
        gfx.rect(new Rect(left, y, width, px(1.0f)), Theme.BORDER.withAlpha(alpha));
        y += px(1.0f + GAP);
        listBounds = new Rect(left, y, width, px(listHeight));
        drawAccounts(context.deltaTime);
        y += px(listHeight + GAP);
        drawReset(new Rect(left, y, width, px(BUTTON_HEIGHT)));

        gfx.popClip();
    }

    private float bodyHeight() {
        return switch (method) {
            case OFFLINE, REFRESH -> FIELD_HEIGHT;
            case MICROSOFT -> BUTTON_HEIGHT * 2.0f + GAP;
            case TOKEN, COOKIE -> PASTE_HEIGHT + GAP + BUTTON_HEIGHT + GAP + BUTTON_HEIGHT;
        };
    }

    private void drawHeader(float left) {
        gfx.textCap(FontRole.BOLD, "Alt Manager", left + px(4.0f), panel.y() + px(12.0f), px(14.0f),
                Theme.TEXT_BRIGHT.withAlpha(alpha));
        gfx.textCap(FontRole.REGULAR, method.title, left + px(4.0f), panel.y() + px(28.0f), px(7.2f),
                Theme.TEXT_DIM.withAlpha(alpha * 0.9f));

        Rect close = new Rect(panel.right() - px(MARGIN + 15.0f), panel.y() + px(MARGIN), px(15.0f), px(15.0f));
        boolean overClose = close.contains(mouseX, mouseY);
        gfx.rect(close, Theme.SURFACE.withAlpha(alpha * (overClose ? 1.2f : 0.7f)), px(Theme.RADIUS_PILL));
        gfx.icon(Icon.X, close.inset(px(4.0f)), (overClose ? Theme.TEXT_BRIGHT : Theme.TEXT_DIM).withAlpha(alpha));
        targets.add(new Target(close, this::close));

        String count = AltAccounts.accounts().size() + " saved";
        float pillWidth = gfx.measure(FontRole.MEDIUM, count, px(7.6f)) + px(16.0f);
        Rect pill = new Rect(close.x() - px(6.0f) - pillWidth, close.y(), pillWidth, px(15.0f));
        gfx.rect(pill, Theme.SURFACE.withAlpha(alpha), px(Theme.RADIUS_PILL));
        centred(FontRole.MEDIUM, count, pill, px(7.6f), Theme.TEXT.withAlpha(alpha));
    }

    /* Locked while a login runs: the window would be titled after one flow while another is the one running. */
    private void drawTabs(Rect strip) {
        Method[] methods = Method.values();
        float tabWidth = (strip.width() - px(3.0f) * (methods.length - 1)) / methods.length;
        boolean locked = AltAuth.busy();
        for (int index = 0; index < methods.length; ++index) {
            Method tab = methods[index];
            Rect bounds = new Rect(strip.x() + index * (tabWidth + px(3.0f)), strip.y(), tabWidth, strip.height());
            boolean on = tab == method;
            boolean hovered = !locked && bounds.contains(mouseX, mouseY);
            gfx.rect(bounds, (on ? Theme.SURFACE_ACTIVE : Theme.SURFACE).withAlpha(alpha * (hovered ? 1.3f : 1.0f)),
                    px(Theme.RADIUS_PILL));
            if (on) {
                gfx.outline(bounds, Theme.accent().withAlpha(alpha * (locked ? 0.35f : 0.7f)), px(0.9f),
                        px(Theme.RADIUS_PILL));
            }
            Color label = on ? Theme.accent() : locked ? Theme.TEXT_FAINT : Theme.TEXT_DIM;
            centred(FontRole.MEDIUM, tab.label, bounds, px(SMALL_SIZE), label.withAlpha(alpha));
            if (!locked && !on) {
                targets.add(new Target(bounds, () -> selectMethod(tab)));
            }
        }
    }

    private void selectMethod(Method next) {
        method = next;
        focus = next == Method.OFFLINE ? Field.NAME : next == Method.REFRESH ? Field.REFRESH : null;
        AltAuth.clearError();
        setNote("", false);
    }

    private float drawBody(float left, float y, float width) {
        boolean busy = AltAuth.busy();
        switch (method) {
            case OFFLINE -> fieldWithArrow(new Rect(left, y, width, px(FIELD_HEIGHT)), Field.NAME, "Username",
                    this::submitName);
            case REFRESH -> fieldWithArrow(new Rect(left, y, width, px(FIELD_HEIGHT)), Field.REFRESH,
                    "Microsoft refresh token (M.C...)", this::submitRefresh);
            case MICROSOFT -> {
                button(new Rect(left, y, width, px(BUTTON_HEIGHT)),
                        busy ? "Waiting for the browser..." : "Sign in with Microsoft", !busy, () -> {
                            setNote("", false);
                            AltAuth.beginMicrosoft();
                        });
                String url = AltAuth.authUrl();
                button(new Rect(left, y + px(BUTTON_HEIGHT + GAP), width, px(BUTTON_HEIGHT)), "Copy sign-in link",
                        !url.isEmpty(), () -> {
                            Platform.get().setClipboard(url);
                            setNote("Link copied to the clipboard", false);
                        });
            }
            case TOKEN -> importBlock(left, y, width, Field.TOKEN_PASTE, Field.TOKEN_PATH);
            case COOKIE -> importBlock(left, y, width, Field.COOKIE_PASTE, Field.COOKIE_PATH);
        }
        return y + px(bodyHeight());
    }

    /* A paste box, a "[path][...][Load]" row and the import button; the paste wins when both are filled. */
    private void importBlock(float left, float y, float width, Field paste, Field path) {
        boolean tokens = paste == Field.TOKEN_PASTE;
        Rect box = new Rect(left, y, width, px(PASTE_HEIGHT));
        String pasted = text.get(paste);
        String summary;
        if (pasted.isEmpty()) {
            summary = tokens ? "Click, then Ctrl+V to paste access tokens (one per line)"
                    : "Click, then Ctrl+V to paste cookies (Netscape .txt or JSON)";
        } else if (tokens) {
            summary = pasteCount + (pasteCount == 1 ? " token pasted" : " tokens pasted") + " - Backspace clears";
        } else {
            summary = (pasted.length() / 1024 + 1) + " KB of cookies pasted - Backspace clears";
        }
        frame(box, paste);
        gfx.textCap(FontRole.REGULAR, gfx.elide(FontRole.REGULAR, summary, px(TEXT_SIZE), box.width() - px(14.0f)),
                box.x() + px(7.0f), box.centerY() - px(TEXT_SIZE) * CAP_MIDDLE, px(TEXT_SIZE),
                (pasted.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT).withAlpha(alpha));

        float rowY = y + px(PASTE_HEIGHT + GAP);
        float loadWidth = px(40.0f);
        float browseWidth = px(22.0f);
        Rect pathBox = new Rect(left, rowY, width - loadWidth - browseWidth - px(GAP * 2.0f), px(BUTTON_HEIGHT));
        field(pathBox, path, tokens ? "...or a path to tokens.txt" : "...or a path to cookies.txt");
        Rect browse = new Rect(pathBox.right() + px(GAP), rowY, browseWidth, px(BUTTON_HEIGHT));
        button(browse, "...", !AltAuth.browsing(), () -> {
            if (AltAuth.browseForFile()) {
                browseTarget = path;
            }
        });
        button(new Rect(browse.right() + px(GAP), rowY, loadWidth, px(BUTTON_HEIGHT)), "Load",
                !text.get(path).isEmpty(), () -> loadFile(path));

        Rect importButton = new Rect(left, rowY + px(BUTTON_HEIGHT + GAP), width, px(BUTTON_HEIGHT));
        boolean busy = AltAuth.busy();
        if (tokens) {
            boolean havePaste = pasteCount > 0;
            int count = havePaste ? pasteCount : tokenFileCount;
            String label = !havePaste && tokenFile != null && tokenFileCount > 0
                    ? "Import " + tokenFileCount + (tokenFileCount == 1 ? " token" : " tokens") + " from "
                    + tokenFile.name()
                    : count > 1 ? "Import " + count + " tokens" : "Import token";
            button(importButton, label, !busy && count > 0, () -> {
                setNote("", false);
                AltAuth.beginTokens(havePaste ? pasted : tokenFile.data());
            });
        } else {
            boolean havePaste = !pasted.isEmpty();
            String label = havePaste ? "Import pasted cookies"
                    : cookieFile != null ? "Import cookies from " + cookieFile.name() : "Import cookies";
            button(importButton, label, !busy && (havePaste || cookieFile != null), () -> {
                setNote("", false);
                AltAuth.beginCookies(havePaste ? pasted : cookieFile.data());
            });
        }
    }

    private void loadFile(Field path) {
        String data;
        try {
            data = AltAuth.readFile(text.get(path));
        } catch (IOException e) {
            setNote(e.getMessage() == null ? "Could not read that file" : e.getMessage(), true);
            return;
        }
        String typed = text.get(path).strip().replace("\"", "");
        int slash = Math.max(typed.lastIndexOf('\\'), typed.lastIndexOf('/'));
        Loaded loaded = new Loaded(typed.substring(slash + 1), data);
        if (path == Field.TOKEN_PATH) {
            tokenFile = loaded;
            tokenFileCount = AltAuth.countTokens(data);
            setNote(tokenFileCount > 0
                    ? tokenFileCount + (tokenFileCount == 1 ? " token in " : " tokens in ") + loaded.name()
                    : "No access tokens found in " + loaded.name(), tokenFileCount == 0);
        } else {
            cookieFile = loaded;
            setNote("Loaded " + (data.length() / 1024 + 1) + " KB from " + loaded.name(), false);
        }
    }

    private void collectBrowsedFile() {
        if (browseTarget == null) {
            return;
        }
        String picked = AltAuth.takeBrowsedFile();
        if (picked == null) {
            return;
        }
        Field target = browseTarget;
        browseTarget = null;
        if (!picked.isEmpty()) {
            text.put(target, picked);
            loadFile(target);
        }
    }

    /* A single login logs straight in; a bulk import only fills the list, leaving the session alone. */
    private void collectLogins() {
        List<AltAccounts.Account> finished = AltAuth.takeResults();
        if (finished.isEmpty()) {
            return;
        }
        int last = -1;
        int added = 0;
        for (AltAccounts.Account account : finished) {
            int index = AltAccounts.addPremium(account);
            if (index >= 0) {
                last = index;
                ++added;
            }
        }
        if (added == 1) {
            AltAccounts.use(last);
            setNote("Logged in as " + AltAccounts.accounts().get(last).username(), false);
        } else if (added > 1) {
            setNote("Imported " + added + " accounts", false);
        } else {
            setNote("Account list is full", true);
        }
    }

    private void submitName() {
        String name = text.get(Field.NAME);
        if (!AltAccounts.validName(name)) {
            setNote("Invalid name (3-16 chars, A-Z 0-9 _)", true);
        } else if (!AltAccounts.addCracked(name)) {
            setNote("Account list is full", true);
        } else {
            text.put(Field.NAME, "");
            setNote("Logged in as " + name, false);
        }
    }

    private void submitRefresh() {
        String token = text.get(Field.REFRESH);
        if (token.isEmpty()) {
            setNote("Paste a refresh token first", true);
        } else if (!AltAuth.busy()) {
            setNote("", false);
            AltAuth.beginRefresh(token);
        }
    }

    /* Worker progress, then the file dialog, then failures, then the last result, then a hint. */
    private void drawStatus(Rect row) {
        String error = AltAuth.error();
        String name = text.get(Field.NAME);
        String line;
        Color colour = Theme.TEXT_DIM;
        float width = row.width();
        if (AltAuth.busy()) {
            line = AltAuth.status().isEmpty() ? "Working..." : AltAuth.status();
            colour = Theme.accent();
            Rect cancel = new Rect(row.right() - px(36.0f), row.y() - px(2.0f), px(36.0f), row.height() + px(4.0f));
            button(cancel, "Cancel", true, AltAuth::cancel);
            width -= px(40.0f);
        } else if (AltAuth.browsing()) {
            /* The dialog opens behind a fullscreen game often enough to be worth saying. */
            line = "Pick a file in the window that opened (check the taskbar)";
            colour = Theme.accent();
        } else if (!error.isEmpty()) {
            line = error;
            colour = Theme.NEGATIVE;
        } else if (method == Method.OFFLINE && !name.isEmpty() && !AltAccounts.validName(name)) {
            line = "Invalid name (3-16 chars, A-Z 0-9 _)";
            colour = Theme.NEGATIVE;
        } else if (!note.isEmpty()) {
            line = note;
            colour = noteIsError ? Theme.NEGATIVE : Theme.accent();
        } else {
            line = switch (method) {
                case OFFLINE -> "Offline servers only: the name is applied right away";
                case MICROSOFT -> "Opens your browser and catches the redirect on localhost";
                case TOKEN -> "Minecraft access tokens (eyJ...), one per line for a bulk import";
                case REFRESH -> "Long-lived Microsoft OAuth token, usually starting with M.C";
                case COOKIE -> "Export the live.com cookies from your browser, then paste or load";
            };
        }
        gfx.textCap(FontRole.REGULAR, gfx.elide(FontRole.REGULAR, line, px(SMALL_SIZE), width), row.x(),
                row.centerY() - px(SMALL_SIZE) * CAP_MIDDLE, px(SMALL_SIZE), colour.withAlpha(alpha));
    }

    private void drawAccounts(float deltaTime) {
        List<AltAccounts.Account> accounts = AltAccounts.accounts();
        if (accounts.isEmpty()) {
            centred(FontRole.REGULAR, "No accounts saved", listBounds, px(TEXT_SIZE), Theme.TEXT_FAINT.withAlpha(alpha));
            return;
        }
        float content = px(accounts.size() * ROW_HEIGHT + (accounts.size() - 1) * ROW_GAP);
        scrollTarget = Maths.clamp(scrollTarget, Math.min(0.0f, listBounds.height() - content), 0.0f);
        scroll = Maths.approach(scroll, scrollTarget, 18.0f, deltaTime);

        gfx.pushClip(listBounds);
        for (int index = 0; index < accounts.size(); ++index) {
            AltAccounts.Account account = accounts.get(index);
            Rect row = new Rect(listBounds.x(), listBounds.y() + scroll + px(index * (ROW_HEIGHT + ROW_GAP)),
                    listBounds.width(), px(ROW_HEIGHT));
            if (row.bottom() < listBounds.y() || row.y() > listBounds.bottom()) {
                continue;
            }
            boolean current = index == AltAccounts.active();
            boolean hovered = listBounds.contains(mouseX, mouseY) && row.contains(mouseX, mouseY);
            gfx.rect(row, Theme.SURFACE.withAlpha(alpha * (hovered || current ? 1.3f : 0.9f)), px(Theme.RADIUS_CARD));
            /* The edge marks a row that carries a real session, so a premium account is never taken for an offline one. */
            if (account.premium()) {
                gfx.rect(new Rect(row.x(), row.y() + px(3.0f), px(2.0f), row.height() - px(6.0f)),
                        Theme.accent().withAlpha(alpha * (current ? 1.0f : 0.5f)), px(1.0f));
            }

            Rect delete = new Rect(row.right() - px(ROW_HEIGHT), row.y(), px(ROW_HEIGHT), row.height());
            boolean overDelete = hovered && delete.contains(mouseX, mouseY);
            String tag = account.type().name();
            float tagRight = delete.x() - px(2.0f);
            gfx.textCap(FontRole.MEDIUM, tag, tagRight, row.centerY() - px(6.0f) * CAP_MIDDLE, px(6.0f),
                    (current ? Theme.accent() : Theme.TEXT_FAINT).withAlpha(alpha), Align.RIGHT);
            float nameRight = tagRight - gfx.measure(FontRole.MEDIUM, tag, px(6.0f)) - px(6.0f);
            String label = current ? "Logged in (" + account.username() + ")" : account.username();
            gfx.textCap(FontRole.SEMI_BOLD, gfx.elide(FontRole.SEMI_BOLD, label, px(TEXT_SIZE),
                            nameRight - row.x() - px(8.0f)), row.x() + px(8.0f),
                    row.centerY() - px(TEXT_SIZE) * CAP_MIDDLE, px(TEXT_SIZE),
                    (current ? Theme.accent() : Theme.TEXT_BRIGHT).withAlpha(alpha));
            if (hovered) {
                gfx.icon(Icon.X, delete.inset(px(5.5f)),
                        (overDelete ? Theme.NEGATIVE : Theme.TEXT_DIM).withAlpha(alpha));
            }

            int which = index;
            Rect visible = clipped(row);
            Rect visibleDelete = clipped(delete);
            /* The cross goes first, so it wins the click over the row it sits in. */
            targets.add(new Target(visibleDelete, () -> AltAccounts.remove(which)));
            if (!current) {
                targets.add(new Target(visible, () -> {
                    AltAccounts.use(which);
                    setNote("Logged in as " + AltAccounts.accounts().get(which).username(), false);
                }));
            }
        }
        gfx.popClip();
    }

    private Rect clipped(Rect bounds) {
        float top = Math.max(bounds.y(), listBounds.y());
        float bottom = Math.min(bounds.bottom(), listBounds.bottom());
        return new Rect(bounds.x(), top, bounds.width(), Math.max(0.0f, bottom - top));
    }

    private void drawReset(Rect bounds) {
        String original = AltAccounts.originalName();
        String label = original == null ? "Reset to original account" : "Reset to original account (" + original + ")";
        button(bounds, label, AltAccounts.active() >= 0, () -> {
            AltAccounts.resetToOriginal();
            setNote(original == null ? "" : "Back on " + original, false);
        });
    }

    private void fieldWithArrow(Rect bounds, Field field, String hint, Runnable submit) {
        Rect arrow = new Rect(bounds.right() - bounds.height(), bounds.y(), bounds.height(), bounds.height());
        boolean overArrow = arrow.contains(mouseX, mouseY);
        targets.add(new Target(arrow, submit));
        field(new Rect(bounds.x(), bounds.y(), bounds.width(), bounds.height()), field, hint);
        Rect dot = arrow.inset(px(3.0f));
        gfx.rect(dot, Theme.accent().withAlpha(alpha * (overArrow ? 1.0f : 0.8f)), dot.height() * 0.5f);
        gfx.icon(Icon.CHEVRON_RIGHT, dot.inset(px(2.0f)), Theme.PANEL.withAlpha(alpha));
    }

    /* A single line shows its end, which is where the typing happens. */
    private void field(Rect bounds, Field field, String hint) {
        frame(bounds, field);
        String value = text.get(field);
        float size = px(TEXT_SIZE);
        float room = bounds.width() - px(14.0f) - (field == Field.NAME || field == Field.REFRESH ? bounds.height() : 0);
        String shown = value;
        while (!shown.isEmpty() && gfx.measure(FontRole.REGULAR, shown, size) > room) {
            shown = shown.substring(Math.min(shown.length(), Math.max(1, shown.length() / 8)));
        }
        float x = bounds.x() + px(7.0f);
        float capTop = bounds.centerY() - size * CAP_MIDDLE;
        gfx.textCap(FontRole.REGULAR, value.isEmpty() ? hint : shown, x, capTop, size,
                (value.isEmpty() ? Theme.TEXT_FAINT : Theme.TEXT).withAlpha(alpha));
        if (focus == field && time % 1.0f < 0.5f) {
            float caret = x + (value.isEmpty() ? 0.0f : gfx.measure(FontRole.REGULAR, shown, size));
            gfx.rect(new Rect(caret + px(1.0f), bounds.centerY() - px(4.5f), px(0.9f), px(9.0f)),
                    Theme.accent().withAlpha(alpha));
        }
    }

    private void frame(Rect bounds, Field field) {
        boolean focused = focus == field;
        gfx.rect(bounds, Theme.SURFACE.withAlpha(alpha), px(Theme.RADIUS_CARD));
        gfx.outline(bounds, (focused ? Theme.accent() : Theme.BORDER).withAlpha(alpha * (focused ? 0.8f : 1.0f)),
                px(0.9f), px(Theme.RADIUS_CARD));
        targets.add(new Target(bounds, () -> focus = field));
    }

    /* A disabled button swallows its click, so callers never guard the action themselves. */
    private void button(Rect bounds, String label, boolean enabled, Runnable action) {
        boolean hovered = enabled && bounds.contains(mouseX, mouseY);
        gfx.rect(bounds, Theme.SURFACE.withAlpha(alpha * (hovered ? 1.4f : 1.0f)), px(Theme.RADIUS_PILL));
        gfx.outline(bounds, Theme.BORDER.withAlpha(alpha), px(0.9f), px(Theme.RADIUS_PILL));
        String fitted = gfx.elide(FontRole.MEDIUM, label, px(TEXT_SIZE), bounds.width() - px(8.0f));
        centred(FontRole.MEDIUM, fitted, bounds, px(TEXT_SIZE),
                (enabled ? Theme.accent() : Theme.TEXT_FAINT).withAlpha(alpha));
        targets.add(new Target(bounds, enabled ? action : () -> {}));
    }

    private void centred(FontRole role, String value, Rect bounds, float size, Color colour) {
        gfx.textCap(role, value, bounds.centerX(), bounds.centerY() - size * CAP_MIDDLE, size, colour, Align.CENTER);
    }

    private void setNote(String value, boolean error) {
        note = value;
        noteIsError = error;
    }

    public void handleEvent(Input.Event event) {
        if (!open) {
            return;
        }
        switch (event.kind()) {
            case MOUSE_MOVE -> {
                mouseX = (float) event.x();
                mouseY = (float) event.y();
            }
            case MOUSE_BUTTON -> {
                mouseX = (float) event.x();
                mouseY = (float) event.y();
                if (event.action() == Input.PRESS && event.code() == Input.MOUSE_LEFT) {
                    press(mouseX, mouseY);
                }
            }
            case SCROLL -> {
                if (listBounds.contains(mouseX, mouseY)) {
                    scrollTarget += (float) event.y() * px(ROW_HEIGHT + ROW_GAP);
                }
            }
            case CHAR -> type(String.valueOf((char) event.code()));
            case KEY -> {
                if (event.action() != Input.RELEASE) {
                    key(event.code(), event.modifiers());
                }
            }
        }
    }

    private void press(float x, float y) {
        focus = null;
        for (Target target : targets) {
            if (target.bounds().contains(x, y)) {
                target.action().run();
                return;
            }
        }
    }

    private void key(int code, int modifiers) {
        if (code == Input.KEY_ESCAPE) {
            close();
        } else if (focus == null) {
            return;
        } else if (code == KEY_V && (modifiers & Input.MOD_CONTROL) != 0) {
            type(Platform.get().clipboard());
        } else if (code == Input.KEY_BACKSPACE) {
            String value = text.get(focus);
            /* A paste box cannot be seen into, so it is cleared whole rather than a character at a time. */
            boolean paste = focus == Field.TOKEN_PASTE || focus == Field.COOKIE_PASTE;
            setText(focus, paste || value.isEmpty() ? "" : value.substring(0, value.length() - 1));
        } else if (code == Input.KEY_ENTER) {
            switch (focus) {
                case NAME -> submitName();
                case REFRESH -> submitRefresh();
                case TOKEN_PATH, COOKIE_PATH -> loadFile(focus);
                default -> {
                }
            }
        }
    }

    private void type(String typed) {
        if (focus == null || typed.isEmpty()) {
            return;
        }
        boolean paste = focus == Field.TOKEN_PASTE || focus == Field.COOKIE_PASTE;
        String clean = paste ? typed : typed.replaceAll("[\\r\\n\\t]", "");
        int limit = paste ? Integer.MAX_VALUE : focus == Field.NAME ? MAX_NAME : MAX_LINE;
        String value = text.get(focus) + clean;
        setText(focus, value.length() > limit ? value.substring(0, limit) : value);
    }

    private void setText(Field field, String value) {
        text.put(field, value);
        if (field == Field.TOKEN_PASTE) {
            pasteCount = AltAuth.countTokens(value);
        }
        AltAuth.clearError();
        setNote("", false);
    }
}
