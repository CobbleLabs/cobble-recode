package cobble.ui;

import cobble.core.Color;
import cobble.core.Config;
import cobble.core.Ease;
import cobble.core.Maths;
import cobble.core.Rect;
import cobble.core.Time;
import cobble.core.Vec3;
import cobble.game.CombatTarget;
import cobble.game.EntityView;
import cobble.game.FrameState;
import cobble.game.Game;
import cobble.hook.Input;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.client.HudModule;
import cobble.module.render.TargetHud;
import cobble.render.Align;
import cobble.render.FontRole;
import cobble.render.Icon;
import cobble.render.Renderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.EntityLivingBase;

/**
 * Watermark bar, player/target info cards and the enabled-module list.
 *
 * <p>Sizes are in Minecraft's GUI-scaled units, so the HUD matches the rest of
 * the interface at any resolution. Panels are flat dark slabs with a two-pixel
 * accent edge on the watermark and a stepped accent outline down the module
 * list; text is drawn once with a shadow.
 *
 * <p>While a screen is open in-world - the pause menu, the inventory, chat - the
 * HUD becomes an editor. Every element that is on then carries a frame with its
 * name, a dot that hides it and a corner grip, and can be dragged to move, dragged
 * by the grip to resize, or clicked on the dot to hide. A hidden element stays out
 * of the editor too; the HUD module's settings bring it back. Positions and per-element
 * sizes are remembered in cobble.cfg; nothing here is typed as a number, because
 * the point of editing in place is seeing the result while you do it.
 *
 * <p>Elements with nothing to say draw a named ghost frame rather than inventing
 * content. The target card used to stand in with a copy of your own stats, which
 * put two cards on screen both showing your name.
 */
public final class Hud {
    /**
     * The elements the in-world editor can move, resize and switch off. The order
     * is the contract HudModule maps its visibility settings onto; see
     * {@link #takeToggle()}.
     */
    public enum Element {
        WATERMARK("Watermark", "markSize"),
        ARRAY_LIST("Module list", "listSize"),
        PLAYER_INFO("Player info", "playerSize"),
        TARGET_INFO("Target info", "targetSize"),
        SPEED("Speed / XYZ", "speedSize");

        final String label;
        final String sizeKey;

        Element(String label, String sizeKey) {
            this.label = label;
            this.sizeKey = sizeKey;
        }
    }

    private static final Element[] ELEMENTS = Element.values();
    private static final String LAYOUT = "hud.layout";

    private static final float MARGIN = 2.0f;
    private static final float MARK_HEIGHT = 14.0f;
    private static final float MARK_EDGE = 2.0f;
    private static final float LOGO_WIDTH = 16.0f;
    private static final float MARK_TEXT = 9.0f;

    private static final float CARD_WIDTH = 135.0f;
    private static final float LABEL_HEIGHT = 10.0f;
    private static final float TARGET_LABEL = 12.0f;
    private static final float BODY_HEIGHT = 31.0f;
    private static final float SKIN_SIZE = 27.0f;
    private static final float NAME_SIZE = 9.0f;
    private static final float SUB_SIZE = 8.0f;
    private static final float DIFF_SIZE = 7.0f;
    private static final float ICON_SIZE = 9.0f;
    private static final float BAR_LEFT = 32.0f;
    private static final float BAR_RIGHT = 132.0f;

    private static final float ROW_HEIGHT = 12.0f;
    private static final float ROW_TEXT = 9.0f;
    private static final float ROW_PAD = 3.0f;

    private static final float NOTICE_HEIGHT = 12.0f;
    private static final float NOTICE_TEXT_SIZE = 8.0f;
    private static final float NOTICE_PAD = 4.0f;
    private static final float NOTICE_FADE = 0.28f;
    private static final int MAX_NOTICES = 5;

    private static final long HURT_DURATION = 400;

    /* Editor chrome, in GUI units. The grip and the dot deliberately do not scale
     * with the element they belong to: an element shrunk to half size still needs
     * a handle big enough to hit. */
    private static final float GRIP_SIZE = 6.0f;
    private static final float DOT_SIZE = 6.0f;
    private static final float EDIT_LABEL = 7.0f;
    private static final float MIN_SIZE = 0.5f;
    private static final float MAX_SIZE = 2.5f;

    private static final Color WATERMARK_BG = Color.rgb(0x000000, 100.0f / 255.0f);
    private static final Color LABEL_BG = Color.rgb(0x000000, 150.0f / 255.0f);
    private static final Color BODY_BG = Color.rgb(0x000000, 120.0f / 255.0f);
    private static final Color TRACK_BG = Color.rgb(0x000000, 180.0f / 255.0f);
    private static final Color LIST_BG = Color.rgb(0x000000, 130.0f / 255.0f);
    private static final Color HURT_RED = Color.rgb(0xFF3C3C);

    /* The off hand does not exist on 1.8.9, so it is not asked for. */
    private static final Game.Slot[] EQUIPMENT_SLOTS = {
        Game.Slot.MAIN_HAND, Game.Slot.HEAD, Game.Slot.CHEST, Game.Slot.LEGS, Game.Slot.FEET,
    };

    private static final class ListEntry {
        float offset = 1.0f;
        float alpha;
        float y;
        boolean seen;
    }

    private static final class Notification {
        final String text;
        final Color accent;
        final float total;
        float life;
        float offset = 1.0f;
        float y;
        boolean placed;

        Notification(String text, Color accent, float total) {
            this.text = text;
            this.accent = accent;
            this.total = total;
            this.life = total;
        }
    }

    /* What the pointer is doing to an element, kept apart from which element it is
     * doing it to. The two used to be one enum, which had no room for a second verb. */
    private enum Grab { NONE, MOVE, RESIZE, TOGGLE }

    private record Pick(Grab grab, Element element) {
        static final Pick NONE = new Pick(Grab.NONE, null);
    }

    private record Visible(Module module, String text, String suffix, float width) {}

    private record Placed(Visible item, float x, float y, float alpha) {}

    private final Renderer gfx = Renderer.instance();
    private final Map<Module, ListEntry> entries = new HashMap<>();
    private final List<Notification> notices = new ArrayList<>();
    private float scale = 1.0f;
    private float unit = 2.0f;
    private boolean watermark = true;
    private boolean arrayList = true;
    private boolean playerInfo = true;
    private boolean targetInfo = true;
    private boolean notifications = true;
    private boolean speed = true;
    private boolean menuOpen;

    private float markX = 2.0f;
    private float markY = 2.0f;
    private float playerX;
    private float playerY;
    private boolean playerPlaced;
    private float targetX;
    private float targetY;
    private boolean targetPlaced;
    /** Margin from the right edge. */
    private float listR;
    private float listY = 2.0f;
    private float speedX = 6.0f;
    private float speedY;
    private boolean speedPlaced;

    /* Frame rectangles in pixels, for the next frame's hit tests. */
    private Rect markRect = Rect.EMPTY;
    private Rect playerRect = Rect.EMPTY;
    private Rect targetRect = Rect.EMPTY;
    private boolean targetDrawn;
    private Rect listRect = Rect.EMPTY;
    private boolean listDrawn;
    private Rect speedRect = Rect.EMPTY;
    private boolean speedDrawn;

    /* Per-element size, multiplied on top of the master Scale setting. Dragged,
     * never typed - which is the whole point - so it lives in the layout section of
     * the config next to the positions rather than as five more sliders in the menu. */
    private final float[] sizes = {1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
    /** The factor px() is currently applying. Set around each element as it draws. */
    private float drawScale = 1.0f;

    /** Element ordinal awaiting HudModule, or -1. */
    private int pendingToggle = -1;

    private float mouseX;
    private float mouseY;
    private Grab grab = Grab.NONE;
    private Element grabbed;
    private Pick hot = Pick.NONE;
    private float dragOffX;
    private float dragOffY;
    /* Captured when a resize starts: the element's unscaled pixel size, so the drag
     * is measured against where it began rather than accumulating rounding every
     * frame. */
    private float grabBaseW;
    private float grabBaseH;

    private double sessionStart = -1.0;
    /* Blocks-per-second readout: distance moved horizontally since the last frame
     * over the frame time, smoothed so it does not flicker per frame. */
    private double lastPosX;
    private double lastPosZ;
    private boolean hasLastPos;
    private float speedBps;
    /** Smoothed bar, below zero until the first read. */
    private float playerHealth = -1.0f;
    private float playerActual = -1.0f;
    private long playerHurtAt;
    private float targetHealth = -1.0f;
    private float targetActual = -1.0f;
    private long targetHurtAt;
    private float targetAlpha;
    private int targetId = -1;

    /**
     * Edit mode toggles an element by asking, not by writing.
     *
     * <p>The booleans here are a copy: HudModule pushes them down from its
     * settings every frame, because those settings are what the config persists
     * and what the menu checkbox reads. Flipping the copy would be undone before
     * the next frame drew. So the editor raises a request here and HudModule flips
     * the setting, which comes straight back down the existing path.
     *
     * @return the pending {@link Element} ordinal, or -1 when nothing is pending
     */
    public int takeToggle() {
        int element = pendingToggle;
        pendingToggle = -1;
        return element;
    }

    /** While a menu owns the mouse the HUD ignores it. */
    public void setMenuOpen(boolean value) {
        menuOpen = value;
    }

    public void setScale(float value) {
        scale = Maths.clamp(value, 0.6f, 2.5f);
    }

    public void setShowWatermark(boolean value) {
        watermark = value;
    }

    public void setShowArrayList(boolean value) {
        arrayList = value;
    }

    public void setShowPlayerInfo(boolean value) {
        playerInfo = value;
    }

    public void setShowTargetInfo(boolean value) {
        targetInfo = value;
    }

    public void setShowNotifications(boolean value) {
        notifications = value;
    }

    public void setShowSpeed(boolean value) {
        speed = value;
    }

    /**
     * Drops a module's array-list state, so a script module that is unloaded
     * does not leave an entry behind.
     */
    public void forget(Module module) {
        entries.remove(module);
    }

    public void loadLayout(Config config) {
        if (!config.hasSection(LAYOUT)) {
            return;
        }
        markX = config.getNumber(LAYOUT, "markX", markX);
        markY = config.getNumber(LAYOUT, "markY", markY);
        playerX = config.getNumber(LAYOUT, "playerX", playerX);
        playerY = config.getNumber(LAYOUT, "playerY", playerY);
        playerPlaced = config.getBool(LAYOUT, "playerPlaced", playerPlaced);
        targetX = config.getNumber(LAYOUT, "targetX", targetX);
        targetY = config.getNumber(LAYOUT, "targetY", targetY);
        targetPlaced = config.getBool(LAYOUT, "targetPlaced", targetPlaced);
        listR = config.getNumber(LAYOUT, "listR", listR);
        listY = config.getNumber(LAYOUT, "listY", listY);
        speedX = config.getNumber(LAYOUT, "speedX", speedX);
        speedY = config.getNumber(LAYOUT, "speedY", speedY);
        speedPlaced = config.getBool(LAYOUT, "speedPlaced", speedPlaced);
        for (Element element : ELEMENTS) {
            int i = element.ordinal();
            sizes[i] = Maths.clamp(config.getNumber(LAYOUT, element.sizeKey, sizes[i]), MIN_SIZE, MAX_SIZE);
        }
    }

    public void saveLayout(Config config) {
        config.setNumber(LAYOUT, "markX", markX, 1);
        config.setNumber(LAYOUT, "markY", markY, 1);
        config.setNumber(LAYOUT, "playerX", playerX, 1);
        config.setNumber(LAYOUT, "playerY", playerY, 1);
        config.setBool(LAYOUT, "playerPlaced", playerPlaced);
        config.setNumber(LAYOUT, "targetX", targetX, 1);
        config.setNumber(LAYOUT, "targetY", targetY, 1);
        config.setBool(LAYOUT, "targetPlaced", targetPlaced);
        config.setNumber(LAYOUT, "listR", listR, 1);
        config.setNumber(LAYOUT, "listY", listY, 1);
        config.setNumber(LAYOUT, "speedX", speedX, 1);
        config.setNumber(LAYOUT, "speedY", speedY, 1);
        config.setBool(LAYOUT, "speedPlaced", speedPlaced);
        for (Element element : ELEMENTS) {
            config.setNumber(LAYOUT, element.sizeKey, sizes[element.ordinal()], 2);
        }
    }

    /**
     * One line on the stack in the bottom right. The client uses it to say what
     * happened to a script; cold.notify is the same call from Lua.
     */
    public void notify(String text, float seconds, Color accent) {
        if (text.isEmpty()) {
            return;
        }
        if (notices.size() >= MAX_NOTICES) {
            notices.remove(0);
        }
        notices.add(new Notification(text, accent, Maths.clamp(seconds, 0.5f, 30.0f)));
    }

    /** Sizes and offsets inside an element, so they follow its own resize handle. */
    private float px(float units) {
        return units * unit * drawScale;
    }

    /* Stored positions, which must NOT follow it: an element that moved as you
     * resized it would crawl across the screen every time you touched the corner. */
    private float pos(float units) {
        return units * unit;
    }

    /* HudModule only pushes its settings while it is on, so switched off it would
     * leave every element showing with whatever it last pushed. */
    private static boolean switchedOn() {
        return ModuleManager.get(HudModule.class).enabled();
    }

    private boolean editMode(FrameState state) {
        return state.inWorld && state.screenOpen && !menuOpen && switchedOn();
    }

    private void shadowed(FontRole role, String value, float x, float capTop, float size, Color colour) {
        shadowed(role, value, x, capTop, size, colour, Align.LEFT);
    }

    private void shadowed(FontRole role, String value, float x, float capTop, float size, Color colour,
                          Align align) {
        gfx.textCap(role, value, x + px(1.0f), capTop + px(1.0f), size,
                Color.rgb(0x000000, 0.55f * colour.a()), align);
        gfx.textCap(role, value, x, capTop, size, colour, align);
    }

    /*
     * "FastPlace" reads as two words and the list writes it lowercased, one
     * colour per row, so a run of capitals stays whole: ESP does not come out as
     * e s p.
     */
    private static String listName(String value) {
        StringBuilder out = new StringBuilder(value.length() + 4);
        for (int index = 0; index < value.length(); ++index) {
            char character = value.charAt(index);
            boolean upper = character >= 'A' && character <= 'Z';
            if (upper && index > 0) {
                char previous = value.charAt(index - 1);
                boolean previousLower = (previous >= 'a' && previous <= 'z') || (previous >= '0' && previous <= '9');
                boolean startsWord = index + 1 < value.length() && value.charAt(index + 1) >= 'a'
                        && value.charAt(index + 1) <= 'z' && previous >= 'A' && previous <= 'Z';
                if (previousLower || startsWord) {
                    out.append(' ');
                }
            }
            out.append(upper ? (char) (character + 32) : character);
        }
        return out.toString();
    }

    private static String formatPlayTime(double seconds) {
        long total = (long) Math.max(0.0, seconds);
        long minutes = total / 60;
        long hours = minutes / 60;
        if (hours > 0) {
            return hours + "h " + minutes % 60 + "m " + total % 60 + "s";
        }
        return minutes + "m " + total % 60 + "s";
    }

    private static float hurtProgress(long hurtAt) {
        if (hurtAt <= 0) {
            return 1.0f;
        }
        long elapsed = Time.nowMillis() - hurtAt;
        if (elapsed >= HURT_DURATION) {
            return 1.0f;
        }
        return Math.max(0.0f, (float) elapsed / HURT_DURATION);
    }

    private static Color darkerOnce(Color colour) {
        return new Color(colour.r() * 0.7f, colour.g() * 0.7f, colour.b() * 0.7f, colour.a());
    }

    private Rect rectOf(Element element) {
        return switch (element) {
            case WATERMARK -> markRect;
            case ARRAY_LIST -> listRect;
            case PLAYER_INFO -> playerRect;
            case TARGET_INFO -> targetRect;
            case SPEED -> speedRect;
        };
    }

    /* Whether the element put a rectangle on screen this frame and can therefore be
     * picked up. The list, target and speed readouts each have their own reason to
     * produce nothing, so they say so explicitly; the other two are known by their
     * rectangle alone. */
    private boolean liveOf(Element element) {
        return switch (element) {
            case ARRAY_LIST -> listDrawn && listRect.width() > 0.0f;
            case TARGET_INFO -> targetDrawn && targetRect.width() > 0.0f;
            case SPEED -> speedDrawn && speedRect.width() > 0.0f;
            default -> rectOf(element).width() > 0.0f;
        };
    }

    /* The grip sits on the bottom-right corner, half in and half out, the way a
     * resize handle does everywhere else. Kept a fixed size in GUI units rather than
     * scaling with the element, so a shrunk element does not end up with a grip bigger
     * than itself. */
    private Rect gripOf(Rect bounds) {
        float side = pos(GRIP_SIZE);
        return new Rect(bounds.right() - side * 0.5f, bounds.bottom() - side * 0.5f, side, side);
    }

    /* The dot sits just above the top-left corner, outside the body, so it neither
     * covers content nor competes with a drag on the element itself.
     *
     * Unless there is no room above it, which is the common case and not the corner
     * case: the watermark and the module list both default to two units from the top, so
     * placing the dot outside would put it off screen and leave those two impossible to
     * switch off. It then tucks inside the element instead. */
    private Rect dotOf(Rect bounds) {
        float side = pos(DOT_SIZE);
        float gap = pos(1.5f);
        float above = bounds.y() - side - gap;
        return above >= 0.0f ? new Rect(bounds.x(), above, side, side)
                : new Rect(bounds.x() + gap, bounds.y() + gap, side, side);
    }

    private Pick pick() {
        /* Front to back, and controls before bodies: the grip and the dot are small and
         * overlap their own element, so testing the body first would make them
         * unreachable. */
        for (int i = ELEMENTS.length - 1; i >= 0; --i) {
            Element candidate = ELEMENTS[i];
            if (!liveOf(candidate)) {
                continue;
            }
            Rect bounds = rectOf(candidate);
            if (dotOf(bounds).contains(mouseX, mouseY)) {
                return new Pick(Grab.TOGGLE, candidate);
            }
            if (gripOf(bounds).contains(mouseX, mouseY)) {
                return new Pick(Grab.RESIZE, candidate);
            }
        }
        for (int i = ELEMENTS.length - 1; i >= 0; --i) {
            Element candidate = ELEMENTS[i];
            if (liveOf(candidate) && rectOf(candidate).contains(mouseX, mouseY)) {
                return new Pick(Grab.MOVE, candidate);
            }
        }
        return Pick.NONE;
    }

    public void handleEvent(Input.Event event, Context context) {
        FrameState state = context.state;
        float scaledW = state.scaledWidth > 0 ? state.scaledWidth : state.windowWidth / unit;
        float scaledH = state.scaledHeight > 0 ? state.scaledHeight : state.windowHeight / unit;

        switch (event.kind()) {
            case MOUSE_MOVE -> {
                mouseX = (float) event.x();
                mouseY = (float) event.y();
                if (!editMode(state)) {
                    return;
                }
                if (grab == Grab.MOVE) {
                    float x = Maths.clamp(mouseX / unit - dragOffX, 0.0f, scaledW);
                    float y = Maths.clamp(mouseY / unit - dragOffY, 0.0f, scaledH);
                    switch (grabbed) {
                        case WATERMARK -> {
                            markX = x;
                            markY = y;
                        }
                        case PLAYER_INFO -> {
                            playerX = x;
                            playerY = y;
                            playerPlaced = true;
                        }
                        case TARGET_INFO -> {
                            targetX = x;
                            targetY = y;
                            targetPlaced = true;
                        }
                        case ARRAY_LIST -> {
                            /* Anchored to the right edge, like the list itself. */
                            listR = Maths.clamp((state.windowWidth - mouseX) / unit - dragOffX, -20.0f, scaledW);
                            listY = y;
                        }
                        case SPEED -> {
                            speedX = x;
                            speedY = y;
                            speedPlaced = true;
                        }
                    }
                } else if (grab == Grab.RESIZE) {
                    /* Project the drag onto the element's own diagonal.
                     *
                     * Taking the width ratio alone would ignore vertical movement, and
                     * taking whichever axis moved more would jump between them mid-drag.
                     * The least-squares projection onto the diagonal is what "drag the
                     * corner, keep the aspect" means, and it behaves the same whether the
                     * element is a wide bar or a squat card. */
                    Rect bounds = rectOf(grabbed);
                    float wantW = mouseX - bounds.x();
                    float wantH = mouseY - bounds.y();
                    float denom = grabBaseW * grabBaseW + grabBaseH * grabBaseH;
                    if (denom > 1.0f) {
                        float factor = (wantW * grabBaseW + wantH * grabBaseH) / denom;
                        sizes[grabbed.ordinal()] = Maths.clamp(factor, MIN_SIZE, MAX_SIZE);
                    }
                } else {
                    hot = pick();
                }
            }
            case MOUSE_BUTTON -> {
                mouseX = (float) event.x();
                mouseY = (float) event.y();
                if (event.action() != Input.PRESS) {
                    grab = Grab.NONE;
                    grabbed = null;
                    return;
                }
                if (event.code() != Input.MOUSE_LEFT || !editMode(state)) {
                    return;
                }
                /* Picked fresh on the press rather than trusting the last move: a click
                 * without a preceding move over the control - the pointer parked where
                 * an element has just appeared - would otherwise do nothing. */
                Pick picked = pick();
                hot = picked;
                if (picked.grab() == Grab.TOGGLE) {
                    /* Handed to HudModule, which owns the setting that persists. */
                    pendingToggle = picked.element().ordinal();
                    return;
                }
                if (picked.grab() == Grab.NONE) {
                    return;
                }
                grab = picked.grab();
                grabbed = picked.element();
                Rect bounds = rectOf(grabbed);
                if (grab == Grab.RESIZE) {
                    /* Unscaled pixel size, so the projection above is measured against
                     * a fixed reference instead of one that moves as it is edited. */
                    float grabScale = sizes[grabbed.ordinal()];
                    float safe = grabScale > 0.01f ? grabScale : 1.0f;
                    grabBaseW = bounds.width() / safe;
                    grabBaseH = bounds.height() / safe;
                } else if (grabbed == Element.ARRAY_LIST) {
                    dragOffX = (state.windowWidth - mouseX) / unit - listR;
                    dragOffY = mouseY / unit - listY;
                } else {
                    dragOffX = (mouseX - bounds.x()) / unit;
                    dragOffY = (mouseY - bounds.y()) / unit;
                }
            }
            default -> {
            }
        }
    }

    private void drawWatermark(FrameState state) {
        String server = Game.serverAddress();
        if (server.isEmpty()) {
            server = "Singleplayer";
        }
        String numbers = state.ping >= 0 ? state.fps + " FPS " + state.ping + "ms" : state.fps + " FPS";
        String text = Theme.CLIENT_NAME + " " + Theme.CLIENT_VERSION + " (public) " + server + " " + numbers;

        float size = px(MARK_TEXT);
        float textWidth = gfx.measure(FontRole.MEDIUM, text, size);
        float width = px(MARGIN + LOGO_WIDTH + MARGIN + 2.0f) + textWidth;
        /* pos(), not px(): the corner it is pinned to must not move when it is resized. */
        float x = pos(markX);
        float y = pos(markY);
        float height = px(MARK_HEIGHT);
        markRect = new Rect(x, y, width, height);

        gfx.rect(markRect, WATERMARK_BG, 0.0f);
        gfx.rect(new Rect(x, y, width, px(MARK_EDGE)), Theme.accent(), 0.0f);

        float logoSize = px(10.0f);
        gfx.icon(Icon.ZAP, new Rect(x + px(MARGIN + 3.0f), y + (height - logoSize) * 0.5f, logoSize, logoSize),
                Theme.accent());
        float capTop = y + (height - size * 0.727f) * 0.5f - px(1.0f);
        shadowed(FontRole.MEDIUM, text, x + px(MARGIN + LOGO_WIDTH + MARGIN + 2.0f), capTop, size,
                Theme.TEXT_BRIGHT);
    }

    private void drawFace(EntityLivingBase handle, Rect bounds, float alpha, float push, float tint) {
        int texture = handle instanceof AbstractClientPlayer player ? Game.playerSkin(player).texture() : 0;
        float scale = 1.0f - push;
        Rect face = new Rect(bounds.centerX() - bounds.width() * 0.5f * scale,
                bounds.centerY() - bounds.height() * 0.5f * scale, bounds.width() * scale, bounds.height() * scale);
        if (texture != 0) {
            Color shade = new Color(1.0f, 1.0f - tint, 1.0f - tint, alpha);
            gfx.imageCrisp(face, new Rect(8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f), texture, shade, 0.0f);
            gfx.imageCrisp(face, new Rect(40.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f, 8.0f / 64.0f), texture, shade,
                    0.0f);
        } else {
            drawNoFace(face, alpha);
        }
    }

    private void drawNoFace(Rect bounds, float alpha) {
        gfx.rect(bounds, Color.rgb(0x141414, alpha), 0.0f);
        gfx.icon(Icon.USER_ROUND, bounds.inset(bounds.width() * 0.18f), Color.rgb(0xAAAAAA, alpha));
    }

    private void drawEquipment(EntityLivingBase handle, float x, float y, float alpha) {
        float size = px(ICON_SIZE);
        float cursor = x;
        for (Game.Slot slot : EQUIPMENT_SLOTS) {
            Game.EquipmentView view = Game.equipment(handle, slot);
            if (!view.present()) {
                continue;
            }
            Game.SpriteView sprite = Game.itemSprite(view.item());
            Rect bounds = new Rect(cursor, y, size, size);
            if (sprite.valid()) {
                gfx.image(bounds, new Rect(sprite.u0(), sprite.v0(), sprite.u1() - sprite.u0(), sprite.v1() - sprite.v0()),
                        sprite.texture(), Color.rgb(0xFFFFFF, alpha), 0.0f);
            } else {
                gfx.rect(bounds, Color.rgb(0x141414, alpha), 0.0f);
            }
            cursor += size + px(1.0f);
            if (cursor - x > px(70.0f)) {
                break;
            }
        }
    }

    private void drawSpeedReadout(Context context) {
        FrameState state = context.state;
        if (!state.player.valid) {
            speedDrawn = false;
            hasLastPos = false;
            speedBps = 0.0f;
            return;
        }

        /* Horizontal speed from consecutive positions. A frame is not a tick, so
         * the raw quotient jitters with frame pacing; a short exponential smooth
         * keeps the number readable without lagging a stop. */
        Vec3 position = state.player.pos;
        if (hasLastPos) {
            double dx = position.x() - lastPosX;
            double dz = position.z() - lastPosZ;
            float dt = context.deltaTime;
            if (dt > 0.0005f) {
                float raw = (float) (Math.sqrt(dx * dx + dz * dz) / dt);
                speedBps += (raw - speedBps) * Math.min(1.0f, dt * 12.0f);
            }
        } else {
            speedBps = 0.0f;
        }
        lastPosX = position.x();
        lastPosZ = position.z();
        hasLastPos = true;

        String line = String.format(Locale.ROOT, "%.1f bps  xyz %.1f / %.1f / %.1f", speedBps, position.x(),
                position.y(), position.z());

        float size = px(SUB_SIZE);
        float rowHeight = px(ROW_HEIGHT);
        float width = gfx.measure(FontRole.MEDIUM, line, size) + px(ROW_PAD * 2.0f);
        float x = speedPlaced ? pos(speedX) : pos(6.0f);
        float y = speedPlaced ? pos(speedY) : state.windowHeight - pos(6.0f) - rowHeight;
        speedRect = new Rect(x, y, width, rowHeight);
        speedDrawn = true;

        gfx.rect(speedRect, LIST_BG, 0.0f);
        gfx.rect(new Rect(x, y + rowHeight - px(1.0f), width, px(1.0f)), Theme.accent(), 0.0f);
        float capTop = y + (rowHeight - size * 0.727f) * 0.5f;
        shadowed(FontRole.MEDIUM, line, x + px(ROW_PAD), capTop, size, Theme.TEXT_BRIGHT);
    }

    /*
     * Frame, name, state dot and corner grip for one element.
     *
     * Drawn from render() once every element has laid itself out, rather than by each
     * element at the end of its own draw, for two reasons: the chrome then sits on top
     * of everything instead of under whatever draws next, and it is drawn with the
     * global unit while drawScale is reset, so a grip on an element shrunk to half
     * size is still a grip you can hit.
     */
    private void drawEditChrome(Element element, Rect bounds) {
        if (bounds.width() <= 0.0f || bounds.height() <= 0.0f) {
            return;
        }
        Color accent = Theme.accent();
        boolean held = grabbed == element && grab != Grab.NONE;
        boolean isHot = hot.element() == element || held;

        gfx.outline(bounds, accent.withAlpha(isHot ? 1.0f : 0.32f), 1.0f, 0.0f);

        Rect dot = dotOf(bounds);
        float labelSize = pos(EDIT_LABEL);
        /* On a backing plate, because when the dot has had to tuck inside the element the
         * name lands on top of live content. */
        float labelX = dot.right() + pos(2.0f);
        float labelW = gfx.measure(FontRole.MEDIUM, element.label, labelSize);
        gfx.rect(new Rect(labelX - pos(1.0f), dot.y(), labelW + pos(2.0f), dot.height()),
                Color.rgb(0x05070A, 0.66f), pos(1.0f));
        shadowed(FontRole.MEDIUM, element.label, labelX, dot.y() + (dot.height() - labelSize * 0.727f) * 0.5f,
                labelSize, Color.rgb(0xFFFFFF, isHot ? 0.95f : 0.72f));

        boolean dotHot = hot.element() == element && hot.grab() == Grab.TOGGLE;
        gfx.rect(dot, Color.rgb(0x05070A, 0.80f), pos(1.0f));
        gfx.rect(dot.inset(pos(1.3f)), accent.withAlpha(dotHot ? 1.0f : 0.85f), pos(1.0f));

        Rect grip = gripOf(bounds);
        boolean resizing = held && grab == Grab.RESIZE;
        boolean gripHot = (hot.element() == element && hot.grab() == Grab.RESIZE) || resizing;
        gfx.rect(grip, Color.rgb(0x05070A, 0.80f), pos(1.0f));
        gfx.rect(grip.inset(pos(1.1f)), accent.withAlpha(gripHot ? 1.0f : 0.70f), pos(0.8f));

        /* The one number in the editor, and only while the gesture is in progress. It is
         * feedback on a drag, not a way to set the value. */
        if (resizing) {
            String text = String.format(Locale.ROOT, "%.0f%%", sizes[element.ordinal()] * 100.0f);
            shadowed(FontRole.BOLD, text, grip.x() - pos(2.5f), grip.y() - pos(EDIT_LABEL + 1.5f), labelSize,
                    Color.rgb(0xFFFFFF), Align.RIGHT);
        }
    }

    private void drawEditHint(FrameState state) {
        String line = "HUD editor    drag to move    corner to resize    dot to hide";
        float size = pos(EDIT_LABEL + 1.0f);
        float height = pos(12.0f);
        float width = gfx.measure(FontRole.MEDIUM, line, size) + pos(10.0f);
        float x = (state.windowWidth - width) * 0.5f;
        float y = state.windowHeight - height - pos(5.0f);
        gfx.rect(new Rect(x, y, width, height), Color.rgb(0x05070A, 0.74f), pos(2.0f));
        gfx.rect(new Rect(x, y, pos(1.5f), height), Theme.accent(), 0.0f);
        shadowed(FontRole.MEDIUM, line, x + pos(6.0f), y + (height - size * 0.727f) * 0.5f, size,
                Color.rgb(0xFFFFFF, 0.92f));
    }

    /** @return the bottom edge of the card, where the target card goes by default, or 0 */
    private float drawPlayerInfo(Context context) {
        FrameState state = context.state;
        if (!state.player.valid) {
            return 0.0f;
        }

        float health = state.player.health;
        float maxHealth = state.player.maxHealth > 0.0f ? state.player.maxHealth : 20.0f;
        if (playerHealth < 0.0f) {
            playerHealth = health;
            playerActual = health;
        }
        if (health < playerActual - 0.01f) {
            playerHurtAt = Time.nowMillis();
            playerActual = health;
        } else if (health > playerActual + 0.01f) {
            playerActual = health;
        }
        playerHealth += (health - playerHealth) * Math.min(1.0f, context.deltaTime * 10.0f);

        String playTime = formatPlayTime(Time.nowSeconds() - sessionStart);
        float nameSize = px(NAME_SIZE);
        float playWidth = gfx.measure(FontRole.MEDIUM, playTime, px(SUB_SIZE));
        /* playWidth is already in pixels at this element's scale, so dividing it back out
         * has to use the same scale px() multiplied it by, not the bare unit. */
        float drawUnit = unit * drawScale;
        float width = px(Math.max(CARD_WIDTH, BAR_LEFT + playWidth / drawUnit + 5.0f));
        float scaledH = state.windowHeight / unit;
        float x = playerPlaced ? pos(playerX) : (state.windowWidth - width) * 0.5f;
        float y = playerPlaced ? pos(playerY) : scaledH / 10.0f * unit;
        playerRect = new Rect(x, y, width, px(LABEL_HEIGHT + BODY_HEIGHT));

        gfx.rect(new Rect(x, y, width, px(LABEL_HEIGHT)), LABEL_BG, 0.0f);
        String label = "Player Info";
        float labelWidth = gfx.measure(FontRole.MEDIUM, label, nameSize);
        shadowed(FontRole.MEDIUM, label, x + (width - labelWidth) * 0.5f, y + px(1.0f), nameSize, Color.WHITE);

        float bodyY = y + px(LABEL_HEIGHT);
        gfx.rect(new Rect(x, bodyY, width, px(BODY_HEIGHT)), BODY_BG, 0.0f);

        float progress = hurtProgress(playerHurtAt);
        float push = progress < 1.0f ? (float) Math.sin(progress * 3.14159f) * 0.18f : 0.0f;
        float tint = progress < 1.0f ? (1.0f - progress) * 0.7f : 0.0f;
        drawFace(state.player.handle, new Rect(x + px(2.0f), bodyY + px(2.0f), px(SKIN_SIZE), px(SKIN_SIZE)), 1.0f,
                push, tint);

        shadowed(FontRole.MEDIUM, Game.entityName(state.player.handle), x + px(BAR_LEFT), bodyY + px(2.0f), nameSize,
                Color.WHITE);
        shadowed(FontRole.MEDIUM, playTime, x + px(BAR_LEFT), bodyY + px(13.5f), px(SUB_SIZE), Color.WHITE);

        float fraction = Maths.clamp(playerHealth / maxHealth, 0.0f, 1.0f);
        Rect bar = new Rect(x + px(BAR_LEFT), bodyY + px(BODY_HEIGHT) - px(8.0f), px(BAR_RIGHT - BAR_LEFT), px(6.0f));
        drawHealthBar(bar, fraction, tint, 1.0f);
        return bodyY + px(BODY_HEIGHT);
    }

    private void drawHealthBar(Rect bar, float fraction, float tint, float alpha) {
        gfx.rect(bar, TRACK_BG.withAlpha(alpha), 0.0f);
        if (fraction <= 0.001f) {
            return;
        }
        Rect fill = new Rect(bar.x(), bar.y(), bar.width() * fraction, bar.height());
        gfx.rectHorizontal(fill, Theme.accent().withAlpha(alpha), darkerOnce(Theme.accent()).withAlpha(alpha), 0.0f);
        if (tint > 0.0f) {
            gfx.rect(fill, HURT_RED.withAlpha(tint * 0.35f * alpha), 0.0f);
        }
    }

    private void drawTargetInfo(Context context, boolean edit, float top) {
        if (ModuleManager.get(TargetHud.class).enabled()) {
            targetDrawn = false;
            return;
        }
        FrameState state = context.state;

        EntityView target = null;
        int id = CombatTarget.current(Time.nowSeconds());
        if (id >= 0) {
            for (EntityView entity : state.entities) {
                if (entity.id == id && entity.health > 0.0f) {
                    target = entity;
                    break;
                }
            }
        }

        float step = 1.0f - (float) Math.exp(-9.0f * context.deltaTime);
        if (target != null) {
            if (target.id != targetId) {
                targetId = target.id;
                targetHealth = -1.0f;
                targetHurtAt = 0;
            }
            targetAlpha += (1.0f - targetAlpha) * step;
        } else {
            targetAlpha -= targetAlpha * step;
            if (targetAlpha < 0.01f) {
                targetId = -1;
            }
        }

        /* Edit mode with nothing targeted draws an empty frame, not a copy of your own
         * stats. Standing in with the player's data put two cards on screen carrying the
         * same name and a "+0.0" health difference against yourself, which read as a
         * duplicate player card rather than as a target card waiting for a target. The
         * frame alone is still something to move and resize, which was the only reason
         * the stand-in existed. */
        if (target == null) {
            if (!edit) {
                targetDrawn = false;
                return;
            }
            /* Nominal size, so the frame is where the card will be. */
            float ghostW = px(CARD_WIDTH);
            float ghostH = px(BODY_HEIGHT);
            float ghostX = targetPlaced ? pos(targetX) : (state.windowWidth - ghostW) * 0.5f;
            float ghostY = targetPlaced ? pos(targetY) : top + px(4.0f);
            targetRect = new Rect(ghostX, ghostY, ghostW, ghostH);
            targetDrawn = true;
            gfx.rect(targetRect, BODY_BG.withAlpha(0.45f), 0.0f);
            float hint = px(SUB_SIZE);
            String waiting = "No target";
            float hintWidth = gfx.measure(FontRole.MEDIUM, waiting, hint);
            shadowed(FontRole.MEDIUM, waiting, ghostX + (ghostW - hintWidth) * 0.5f,
                    ghostY + (ghostH - hint * 0.727f) * 0.5f, hint, Color.rgb(0xFFFFFF, 0.45f));
            return;
        }
        float alpha = targetAlpha;
        if (alpha < 0.01f) {
            targetDrawn = false;
            return;
        }

        float health = target.health + target.absorption;
        float maxHealth = target.maxHealth > 0.0f ? target.maxHealth : 20.0f;
        EntityLivingBase handle = target.handle;
        String name = Game.entityName(handle);
        if (name.isEmpty()) {
            name = target.isPlayer ? "player" : "entity";
        }
        if (targetHealth < 0.0f) {
            targetHealth = health;
            targetActual = health;
        }
        if (health < targetActual - 0.01f) {
            targetHurtAt = Time.nowMillis();
            targetActual = health;
        } else if (health > targetActual + 0.01f) {
            targetActual = health;
        }
        targetHealth += (health - targetHealth) * Math.min(1.0f, context.deltaTime * 10.0f);

        float width = px(CARD_WIDTH);
        float x = targetPlaced ? pos(targetX) : (state.windowWidth - width) * 0.5f;
        float y = targetPlaced ? pos(targetY) : top + px(4.0f);
        targetRect = new Rect(x, y, width, px(BODY_HEIGHT));
        targetDrawn = true;

        gfx.rect(targetRect, BODY_BG.withAlpha(alpha), 0.0f);

        float progress = hurtProgress(targetHurtAt);
        float push = progress < 1.0f ? (float) Math.sin(progress * 3.14159f) * 0.18f : 0.0f;
        float tint = progress < 1.0f ? (1.0f - progress) * 0.7f : 0.0f;
        Rect face = new Rect(x + px(2.0f), y + px(2.0f), px(SKIN_SIZE), px(SKIN_SIZE));
        if (target.isPlayer) {
            drawFace(handle, face, alpha, push, tint);
        } else {
            drawNoFace(face, alpha);
        }

        shadowed(FontRole.MEDIUM, name, x + px(BAR_LEFT), y + px(2.0f), px(NAME_SIZE), Color.rgb(0xFFFFFF, alpha));

        float ownHealth = state.player.health;
        String diffText = String.format(Locale.ROOT, "%s%.1f", targetHealth > ownHealth ? "-" : "+",
                Math.abs(targetHealth - ownHealth));
        shadowed(FontRole.MEDIUM, diffText, x + px(BAR_RIGHT), y + px(15.0f), px(DIFF_SIZE),
                Color.rgb(0xFFFFFF, alpha), Align.RIGHT);

        drawEquipment(handle, x + px(BAR_LEFT), y + px(TARGET_LABEL - 2.0f), alpha);

        float fraction = Maths.clamp(targetHealth / maxHealth, 0.0f, 1.0f);
        Rect bar = new Rect(x + px(BAR_LEFT), y + px(23.0f), px(BAR_RIGHT - BAR_LEFT), px(6.0f));
        drawHealthBar(bar, fraction, tint, alpha);
    }

    private void drawArrayList(Context context, boolean edit) {
        float size = px(ROW_TEXT);
        float rowHeight = px(ROW_HEIGHT);
        /* pos(): the margin from the right edge is a position, so resizing the list must
         * not walk it across the screen. */
        float rightEdge = context.state.windowWidth - pos(listR);
        float blend = 1.0f - (float) Math.exp(-14.0f * context.deltaTime);

        for (ListEntry entry : entries.values()) {
            entry.seen = false;
        }

        List<Visible> visible = new ArrayList<>();
        for (Module module : ModuleManager.all()) {
            ListEntry entry = entries.get(module);
            if (!module.enabled() && (entry == null || entry.alpha < 0.004f)) {
                continue;
            }
            if (entry == null) {
                entry = new ListEntry();
                entries.put(module, entry);
            }
            entry.seen = true;

            /* The client's own chrome has nothing to say in its own list. */
            if (module.name.equals("ClickGUI") || module.name.equals("HUD")) {
                continue;
            }

            String text = listName(module.name);
            String suffix = module.suffix();
            String shownSuffix = suffix.isEmpty() ? "" : " " + listName(suffix);
            float width = gfx.measure(FontRole.MEDIUM, text, size) + gfx.measure(FontRole.MEDIUM, shownSuffix, size);
            visible.add(new Visible(module, text, shownSuffix, width));
        }

        /* Widest first: the block gets a clean diagonal edge instead of a ragged
         * one, which is the whole point of an array list. */
        visible.sort((a, b) -> Float.compare(b.width(), a.width()));

        List<Placed> placed = new ArrayList<>();
        float y = pos(listY);
        for (Visible item : visible) {
            ListEntry entry = entries.get(item.module());
            boolean on = item.module().enabled();
            entry.alpha += ((on ? 1.0f : 0.0f) - entry.alpha) * blend;
            entry.offset += ((on ? 0.0f : 1.0f) - entry.offset) * blend;
            entry.y = entry.y <= 0.0f ? y : entry.y + (y - entry.y) * blend;
            if (Math.abs(y - entry.y) < 0.5f) {
                entry.y = y;
            }
            if (entry.alpha > 0.004f) {
                float slide = Ease.outQuint(Maths.clamp(entry.offset, 0.0f, 1.0f)) * (item.width() + px(8.0f));
                /* Settled, the text ends one pad short of the edge and the
                 * background runs to it. */
                placed.add(new Placed(item, Math.round(rightEdge + slide - item.width() - px(ROW_PAD)),
                        Math.round(entry.y), entry.alpha));
            }
            y += rowHeight;
        }

        listDrawn = false;
        if (!placed.isEmpty()) {
            float minX = rightEdge;
            for (Placed row : placed) {
                minX = Math.min(minX, row.x() - px(ROW_PAD) - 1.0f);
            }
            float firstTop = placed.get(0).y();
            float lastBottom = placed.get(placed.size() - 1).y() + rowHeight + 1.0f;
            listRect = new Rect(minX, firstTop, rightEdge + px(ROW_PAD) - minX, lastBottom - firstTop);
            listDrawn = true;
        } else if (edit) {
            /* Something to grab when every module is off. */
            float boxW = px(90.0f);
            float boxH = px(ROW_HEIGHT);
            listRect = new Rect(rightEdge - boxW, y, boxW, boxH);
            gfx.rect(listRect, Color.rgb(0x000000, 0.25f), 0.0f);
            shadowed(FontRole.MEDIUM, "module list", listRect.x() + px(ROW_PAD),
                    listRect.y() + (boxH - size * 0.727f) * 0.5f, size, Color.rgb(0xAAAAAA, 0.8f));
            listDrawn = true;
        }

        Color accent = Theme.accent();
        Color light = Color.mix(accent, Color.WHITE, 0.35f);
        float pad = px(ROW_PAD);
        for (int i = 0; i < placed.size(); ++i) {
            Placed row = placed.get(i);
            float fade = 0.5f + 0.5f * (float) Math.sin(context.time * 2.2f - i * 0.55f);
            Color colour = Color.mix(accent, light, fade).withAlpha(row.alpha());

            gfx.rect(new Rect(row.x() - pad, row.y(), (rightEdge - row.x()) + pad, rowHeight),
                    LIST_BG.withAlpha(row.alpha()), 0.0f);

            float capTop = row.y() + (rowHeight - size * 0.727f) * 0.5f;
            float nameWidth = gfx.measure(FontRole.MEDIUM, row.item().text(), size);
            /* row.x is the left edge of the text; the background was placed
             * around the same origin, so both move as one. */
            shadowed(FontRole.MEDIUM, row.item().text(), row.x(), capTop, size, colour);
            if (!row.item().suffix().isEmpty()) {
                shadowed(FontRole.MEDIUM, row.item().suffix(), row.x() + nameWidth, capTop, size,
                        Color.rgb(0xAAAAAA, row.alpha()));
            }

            /* Stepped outline: a left bar per row, bridged to the neighbour
             * wherever the widths differ, and a floor under the last row. */
            float thisRight = row.x() - pad;
            gfx.rect(new Rect(thisRight - 1.0f, row.y(), 1.0f, rowHeight), colour, 0.0f);
            if (i > 0) {
                float prevRight = placed.get(i - 1).x() - pad;
                if (thisRight > prevRight + 0.5f) {
                    gfx.rect(new Rect(prevRight - 1.0f, row.y() - 1.0f, thisRight - prevRight + 1.0f, 1.0f), colour,
                            0.0f);
                }
            }
            if (i + 1 < placed.size()) {
                float nextRight = placed.get(i + 1).x() - pad;
                if (thisRight > nextRight + 0.5f) {
                    gfx.rect(new Rect(nextRight - 1.0f, row.y() + rowHeight, thisRight - nextRight + 1.0f, 1.0f),
                            colour, 0.0f);
                }
            } else {
                gfx.rect(new Rect(thisRight - 1.0f, row.y() + rowHeight, (rightEdge - row.x()) + pad + 1.0f, 1.0f),
                        colour, 0.0f);
            }
        }

        entries.values().removeIf(entry -> !entry.seen && entry.alpha < 0.004f);
    }

    private void drawNotifications(Context context) {
        if (notices.isEmpty()) {
            return;
        }
        float size = px(NOTICE_TEXT_SIZE);
        float rowHeight = Math.round(px(NOTICE_HEIGHT));
        float right = context.state.windowWidth - px(MARGIN);
        float blend = 1.0f - (float) Math.exp(-14.0f * context.deltaTime);

        float y = Math.round(context.state.windowHeight - px(MARGIN) - rowHeight);
        for (int index = notices.size() - 1; index >= 0; --index) {
            Notification notice = notices.get(index);
            notice.life -= context.deltaTime;

            float target = notice.life <= NOTICE_FADE ? 1.0f : 0.0f;
            notice.offset += (target - notice.offset) * blend;
            notice.y = notice.placed ? notice.y + (y - notice.y) * blend : y;
            notice.placed = true;
            if (Math.abs(y - notice.y) < 0.5f) {
                notice.y = y;
            }

            float width = gfx.measure(FontRole.MEDIUM, notice.text, size) + px(NOTICE_PAD * 2.0f);
            float slide = Ease.outQuint(Maths.clamp(notice.offset, 0.0f, 1.0f)) * (width + px(6.0f));
            float alpha = Maths.clamp(1.0f - notice.offset * 0.6f, 0.0f, 1.0f);
            Rect panel = new Rect(Math.round(right - width + slide), Math.round(notice.y), width, rowHeight);

            gfx.rect(panel, Color.rgb(0x000000, 0.47f * alpha), px(2.0f));
            shadowed(FontRole.MEDIUM, notice.text, panel.x() + px(NOTICE_PAD),
                    Math.round(panel.y() + (rowHeight - size * 0.727f) * 0.5f - px(1.0f)), size,
                    notice.accent.withAlpha(alpha));
            float remaining = Maths.clamp(notice.life / notice.total, 0.0f, 1.0f);
            gfx.rect(new Rect(panel.x(), panel.bottom() - px(1.0f), panel.width() * remaining, px(1.0f)),
                    notice.accent.withAlpha(0.55f * alpha), 0.0f);

            y -= rowHeight + Math.round(px(1.5f));
        }

        notices.removeIf(notice -> notice.life <= 0.0f && notice.offset > 0.9f);
    }

    public void render(Context context) {
        FrameState state = context.state;
        float factor = state.scaledWidth > 0 ? (float) state.windowWidth / state.scaledWidth : 2.0f;
        unit = Math.max(factor * scale, 0.5f);

        boolean on = switchedOn();
        if (state.inWorld && on) {
            if (sessionStart < 0.0) {
                sessionStart = Time.nowSeconds();
            }
            boolean edit = editMode(state);

            /* Each element draws under its own size factor. drawScale is what px()
             * multiplies by, so setting it here is all it takes for every offset and
             * dimension inside the element to follow its resize handle. */
            if (watermark) {
                drawScale = sizes[Element.WATERMARK.ordinal()];
                drawWatermark(state);
            } else {
                markRect = Rect.EMPTY;
            }
            if (arrayList) {
                drawScale = sizes[Element.ARRAY_LIST.ordinal()];
                drawArrayList(context, edit);
            } else {
                listDrawn = false;
            }
            float below = 0.0f;
            if (playerInfo) {
                drawScale = sizes[Element.PLAYER_INFO.ordinal()];
                below = drawPlayerInfo(context);
            } else {
                playerRect = Rect.EMPTY;
            }
            if (targetInfo) {
                drawScale = sizes[Element.TARGET_INFO.ordinal()];
                drawTargetInfo(context, edit, below);
            } else {
                targetDrawn = false;
            }
            if (speed) {
                drawScale = sizes[Element.SPEED.ordinal()];
                drawSpeedReadout(context);
            } else {
                speedDrawn = false;
            }
            drawScale = 1.0f;

            /* Chrome last and unscaled: on top of every element, and with handles sized
             * in screen units so an element shrunk to half size still has a grip you can
             * hit. */
            if (edit) {
                for (Element element : ELEMENTS) {
                    if (liveOf(element)) {
                        drawEditChrome(element, rectOf(element));
                    }
                }
                drawEditHint(state);
            } else {
                hot = Pick.NONE;
            }
        } else {
            entries.clear();
            targetAlpha = 0.0f;
            targetId = -1;
            drawScale = 1.0f;
            grab = Grab.NONE;
            grabbed = null;
            hot = Pick.NONE;
        }
        if (on && notifications) {
            drawNotifications(context);
        }
    }
}
