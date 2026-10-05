package cobble.ui;

import cobble.core.Color;
import cobble.core.Config;
import cobble.core.Ease;
import cobble.core.Maths;
import cobble.core.Rect;
import cobble.hook.Input;
import cobble.module.Context;
import cobble.module.Module;
import cobble.module.ModuleManager;
import cobble.module.Setting;
import cobble.render.FontRole;
import cobble.render.Renderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Toast notifications and HUD layout coordinator.
 *
 * <p>While the menu is open the watermark, the module list and the
 * notifications can be dragged anywhere and resized from the corner handle.
 * Each is held by an anchor: the corner it hugs, which is the one nearest the
 * screen edge it sits against. Dropped on the other half of the screen it
 * re-anchors to the opposite edge, so it mirrors and keeps growing away from
 * the edge it is against.
 */
public final class Hud {
    private static final float NOTICE_WIDTH = 140.0f;
    private static final float NOTICE_HEIGHT = 28.0f;
    private static final float NOTICE_ROUNDING = 6.0f;
    private static final float NOTICE_PADDING = 8.0f;
    private static final float NOTICE_TITLE_SIZE = 9.0f;
    private static final float NOTICE_MESSAGE_SIZE = 8.0f;
    private static final float NOTICE_MARGIN = 8.0f;
    private static final float NOTICE_SPACING = 4.0f;
    private static final float NOTICE_ENTER = 0.22f;
    private static final float NOTICE_EXIT = 0.16f;
    private static final float NOTICE_GLIDE = 22.0f;
    private static final float TOGGLE_SECONDS = 2.8f;
    /* A panic or a config load toggles dozens at once; the oldest cards make room. */
    private static final int MAX_NOTICES = 6;

    private static final Color NOTICE_BG = Color.rgb(0x171A21, 0.94f);
    private static final Color NOTICE_BORDER = Color.rgb(0xFFFFFF, 0.08f);
    private static final Color NOTICE_SHADOW = Color.rgb(0x000000, 0.40f);
    private static final Color NOTICE_TITLE = Color.rgb(0xFFFFFF);
    private static final Color NOTICE_MESSAGE = Color.rgb(0xFFFFFF, 0.65f);

    private static final String LAYOUT = "hud.layout";
    private static final Color EDIT_OUTLINE = Color.rgb(0xFFFFFF, 0.35f);
    private static final Color EDIT_FILL = Color.rgb(0xFFFFFF, 0.05f);
    private static final Color EDIT_HANDLE = Color.rgb(0xFFFFFF, 0.85f);
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.5f;
    /* Half the side of the resize handle, in window pixels. */
    private static final float HANDLE_REACH = 7.0f;

    private static final class Notification {
        final String title;
        final String message;
        final Color colour;
        final float total;
        /* The module a toggle card is about, so its next toggle replaces it instead of stacking. */
        final Module module;
        float life;
        float y;
        boolean placed;

        Notification(String title, String message, Color colour, float duration, Module module) {
            this.title = title;
            this.message = message;
            this.colour = colour;
            this.module = module;
            this.total = duration;
            this.life = duration;
        }
    }

    /** What can be picked up and moved while the menu is open. */
    public enum Element {
        WATERMARK("watermark"), ARRAY_LIST("list"), NOTIFICATIONS("notices");

        final String key;

        Element(String key) {
            this.key = key;
        }
    }

    /**
     * Where an element is held, in GUI pixels: the corner it hugs, which side of
     * the screen that corner is on, and how large it is drawn.
     */
    public record Anchor(float x, float y, boolean left, boolean top, float scale) {}

    private enum Grab { NONE, MOVE, RESIZE }

    private static final int COUNT = Element.values().length;

    private final Renderer gfx;
    private final List<Notification> notices = new ArrayList<>();
    /* Stored anchors in GUI pixels, NaN until the element is first moved. */
    private final float[] anchorX = new float[COUNT];
    private final float[] anchorY = new float[COUNT];
    private final float[] scales = new float[COUNT];
    /* The side each element was last drawn on, held still while it is dragged so
     * it does not flip under the pointer when it crosses the middle. */
    private final boolean[] sideLeft = new boolean[COUNT];
    private final boolean[] sideTop = new boolean[COUNT];
    /* This frame's bounds and anchors in window pixels, and the last whole
     * frame's: presses arrive before this frame is drawn, and an element that
     * stopped drawing must stop being grabbable. */
    private final Rect[] bounds = new Rect[COUNT];
    private final float[] placedX = new float[COUNT];
    private final float[] placedY = new float[COUNT];
    private final Rect[] drawnBounds = new Rect[COUNT];
    private final float[] drawnX = new float[COUNT];
    private final float[] drawnY = new float[COUNT];

    private boolean menuOpen;
    private Element grabbed;
    private Grab grab = Grab.NONE;
    private Rect grabBounds = Rect.EMPTY;
    private float grabMouseX;
    private float grabMouseY;
    private float grabAnchorX;
    private float grabAnchorY;
    private float grabScale;
    private float mouseX;
    private float mouseY;
    private int scaleFactor = 2;
    private float windowWidth;
    private float windowHeight;
    private float unit = 2.0f;
    private boolean showNotifications = true;

    public Hud() {
        this(Renderer.instance());
    }

    public Hud(Renderer gfx) {
        this.gfx = gfx;
        Arrays.fill(anchorX, Float.NaN);
        Arrays.fill(anchorY, Float.NaN);
        Arrays.fill(scales, 1.0f);
        Arrays.fill(bounds, Rect.EMPTY);
        Arrays.fill(drawnBounds, Rect.EMPTY);
    }

    /** True while the menu is open, which is when the HUD can be moved. */
    public boolean editing() {
        return menuOpen;
    }

    /**
     * The element's anchor on a screen of the given size, all in GUI pixels; the
     * default applies until it has been moved.
     */
    public Anchor anchor(Element element, float defaultX, float defaultY, float width, float height) {
        int index = element.ordinal();
        float x = Maths.clamp(Float.isNaN(anchorX[index]) ? defaultX : anchorX[index], 0.0f, width);
        float y = Maths.clamp(Float.isNaN(anchorY[index]) ? defaultY : anchorY[index], 0.0f, height);
        if (grabbed != element) {
            sideLeft[index] = x < width * 0.5f;
            sideTop[index] = y < height * 0.5f;
        }
        return new Anchor(x, y, sideLeft[index], sideTop[index], scales[index]);
    }

    /**
     * Keeps an element's size and its Scale setting as one: dragging the handle
     * writes the setting, and the rest of the time the setting drives the element.
     */
    public void bindScale(Element element, Setting setting) {
        int index = element.ordinal();
        if (grabbed == element && grab == Grab.RESIZE) {
            setting.setNumber(scales[index]);
        } else {
            scales[index] = setting.number();
        }
    }

    /** An anchor moved in far enough that whatever hangs from it, this long, stays whole on screen. */
    public static float keepOnScreen(float anchor, float extent, boolean towardsEnd, float screen) {
        return towardsEnd ? Maths.clamp(anchor, 0.0f, Math.max(0.0f, screen - extent))
                : Maths.clamp(anchor, Math.min(extent, screen), screen);
    }

    /** {@link #place} for an element drawn in GUI pixels from the given anchor, at its scaled size. */
    public void placeGui(Element element, Anchor anchor, float x, float y, float width, float height) {
        float left = anchor.left() ? x : x - width;
        float top = anchor.top() ? y : y - height;
        place(element, new Rect(left * scaleFactor, top * scaleFactor, width * scaleFactor, height * scaleFactor),
                x * scaleFactor, y * scaleFactor);
    }

    /** Where an element was drawn this frame and the corner it hangs from, in window pixels. */
    public void place(Element element, Rect drawn, float x, float y) {
        int index = element.ordinal();
        bounds[index] = drawn;
        placedX[index] = x;
        placedY[index] = y;
    }

    private float px(float units) {
        return Math.round(units * unit);
    }

    public void notify(String title, String message, Color colour, float duration) {
        push(new Notification(title, message, colour, Math.max(0.5f, duration), null));
    }

    private void push(Notification notice) {
        if (notices.size() >= MAX_NOTICES) {
            notices.remove(0);
        }
        notices.add(notice);
    }

    public void moduleToggled(Module module) {
        notices.removeIf(notice -> notice.module == module);
        boolean on = module.enabled();
        push(new Notification("Module updated", module.name + (on ? " enabled" : " disabled"),
                on ? Theme.POSITIVE : Theme.NEGATIVE, TOGGLE_SECONDS, module));
    }

    /* A script module unloading takes its card with it. */
    public void forget(Module module) {
        notices.removeIf(notice -> notice.module == module);
    }

    public void loadLayout(Config config) {
        for (Element element : Element.values()) {
            int index = element.ordinal();
            anchorX[index] = config.getNumber(LAYOUT, element.key + "AnchorX", Float.NaN);
            anchorY[index] = config.getNumber(LAYOUT, element.key + "AnchorY", Float.NaN);
        }
    }

    public void saveLayout(Config config) {
        for (Element element : Element.values()) {
            int index = element.ordinal();
            config.setNumber(LAYOUT, element.key + "AnchorX", anchorX[index], 1);
            config.setNumber(LAYOUT, element.key + "AnchorY", anchorY[index], 1);
        }
    }

    /* The menu fades out while this is true, so the whole screen can be seen. */
    public boolean dragging() {
        return grabbed != null;
    }

    public int takeToggle() {
        return -1;
    }

    public void setMenuOpen(boolean value) {
        menuOpen = value;
        if (!value) {
            release();
        }
    }

    public void setShowNotifications(boolean value) {
        this.showNotifications = value;
    }

    public void handleEvent(Input.Event event, Context context) {
        switch (event.kind()) {
            case MOUSE_MOVE -> {
                mouseX = (float) event.x();
                mouseY = (float) event.y();
                if (grab == Grab.MOVE) {
                    move();
                } else if (grab == Grab.RESIZE) {
                    resize();
                }
            }
            case MOUSE_BUTTON -> {
                mouseX = (float) event.x();
                mouseY = (float) event.y();
                if (event.code() != Input.MOUSE_LEFT) {
                    return;
                }
                if (event.action() != Input.PRESS) {
                    release();
                } else if (menuOpen && !Client.gui().contains(mouseX, mouseY)) {
                    press();
                }
            }
            default -> {
            }
        }
    }

    /* The handle wins over the body, so a corner can be grabbed even where it overlaps another element. */
    private void press() {
        for (Element element : Element.values()) {
            if (overHandle(element.ordinal())) {
                pick(element, Grab.RESIZE);
                return;
            }
        }
        for (Element element : Element.values()) {
            if (drawnBounds[element.ordinal()].contains(mouseX, mouseY)) {
                pick(element, Grab.MOVE);
                return;
            }
        }
    }

    private void pick(Element element, Grab kind) {
        int index = element.ordinal();
        grabbed = element;
        grab = kind;
        grabBounds = drawnBounds[index];
        grabMouseX = mouseX;
        grabMouseY = mouseY;
        grabAnchorX = drawnX[index] / scaleFactor;
        grabAnchorY = drawnY[index] / scaleFactor;
        grabScale = scales[index];
    }

    /* Follows the pointer, but only as far as keeps the element on screen. */
    private void move() {
        int index = grabbed.ordinal();
        float moveX = Maths.clamp(mouseX - grabMouseX, -grabBounds.x(), windowWidth - grabBounds.right());
        float moveY = Maths.clamp(mouseY - grabMouseY, -grabBounds.y(), windowHeight - grabBounds.bottom());
        anchorX[index] = grabAnchorX + moveX / scaleFactor;
        anchorY[index] = grabAnchorY + moveY / scaleFactor;
    }

    /* Grows with the handle's distance from the anchor, so any corner drags the same way. */
    private void resize() {
        int index = grabbed.ordinal();
        float originX = grabAnchorX * scaleFactor;
        float originY = grabAnchorY * scaleFactor;
        float from = (float) Math.hypot(grabMouseX - originX, grabMouseY - originY);
        if (from < 4.0f) {
            return;
        }
        float to = (float) Math.hypot(mouseX - originX, mouseY - originY);
        scales[index] = Maths.clamp(grabScale * to / from, MIN_SCALE, MAX_SCALE);
        if (Float.isNaN(anchorX[index])) {
            anchorX[index] = grabAnchorX;
            anchorY[index] = grabAnchorY;
        }
    }

    /*
     * Dropped with its middle on the other half of the screen, the element
     * re-anchors to its opposite edge where it lies, so it mirrors without moving.
     */
    private void release() {
        if (grabbed != null && grab == Grab.MOVE) {
            int index = grabbed.ordinal();
            Rect drawn = drawnBounds[index];
            if (drawn.width() > 0.0f) {
                boolean left = drawn.centerX() < windowWidth * 0.5f;
                boolean top = drawn.centerY() < windowHeight * 0.5f;
                if (left != sideLeft[index]) {
                    anchorX[index] = (left ? drawn.x() : drawn.right()) / scaleFactor;
                }
                if (top != sideTop[index]) {
                    anchorY[index] = (top ? drawn.y() : drawn.bottom()) / scaleFactor;
                }
            }
        }
        if (grabbed != null) {
            ModuleManager.markChanged();
        }
        grabbed = null;
        grab = Grab.NONE;
    }

    /* The corner opposite the anchor: the one that moves away from it as the element grows. */
    private float handleX(int index) {
        Rect drawn = drawnBounds[index];
        return drawnX[index] <= drawn.centerX() ? drawn.right() : drawn.x();
    }

    private float handleY(int index) {
        Rect drawn = drawnBounds[index];
        return drawnY[index] <= drawn.centerY() ? drawn.bottom() : drawn.y();
    }

    private boolean overHandle(int index) {
        return drawnBounds[index].width() > 0.0f && Math.abs(mouseX - handleX(index)) <= HANDLE_REACH
                && Math.abs(mouseY - handleY(index)) <= HANDLE_REACH;
    }

    private void drawNotifications(Context context, Anchor anchor) {
        if (notices.isEmpty() || !showNotifications) {
            return;
        }
        float width = px(NOTICE_WIDTH);
        float height = px(NOTICE_HEIGHT);
        float rounding = px(NOTICE_ROUNDING);
        float padding = px(NOTICE_PADDING);
        float titleSize = px(NOTICE_TITLE_SIZE) / gfx.fonts().face(FontRole.BOLD).boxHeight(1.0f);
        float messageSize = px(NOTICE_MESSAGE_SIZE) / gfx.fonts().face(FontRole.BOLD).boxHeight(1.0f);

        /* Cards hang from the anchor and slide in from the edge it is against;
         * the stack grows away from that edge too. */
        float travel = (width + px(NOTICE_MARGIN)) * (anchor.left() ? -1.0f : 1.0f);
        float homeX = noticeLeft(anchor, width);
        float homeY = noticeTop(anchor, height);
        float step = (height + px(NOTICE_SPACING)) * (anchor.top() ? 1.0f : -1.0f);
        float stackTop = homeY;
        float stackBottom = homeY + height;
        int slot = 0;
        for (int index = notices.size() - 1; index >= 0; --index) {
            Notification notice = notices.get(index);
            notice.life -= context.deltaTime;
            if (notice.life <= 0.0f) {
                continue;
            }

            float elapsed = notice.total - notice.life;
            float slide = Ease.outBack(Ease.window(elapsed, 0.0f, NOTICE_ENTER), 0.6f);
            float alpha = Ease.outCubic(Ease.window(elapsed, 0.0f, 0.18f));
            if (notice.life < NOTICE_EXIT) {
                float leaving = 1.0f - notice.life / NOTICE_EXIT;
                slide -= leaving * leaving * leaving;
                alpha *= 1.0f - leaving * leaving;
            }

            float target = homeY + slot * step;
            notice.y = notice.placed ? Maths.approach(notice.y, target, NOTICE_GLIDE, context.deltaTime) : target;
            notice.placed = true;

            float x = homeX + (1.0f - slide) * travel;
            float y = notice.y;
            stackTop = Math.min(stackTop, y);
            stackBottom = Math.max(stackBottom, y + height);
            Rect panel = new Rect(x, y, width, height);
            Color accent = notice.colour.withAlpha(alpha);

            gfx.rect(new Rect(x + px(1.0f), y + px(2.0f), width, height), NOTICE_SHADOW.withAlpha(alpha), rounding);
            gfx.rect(panel, NOTICE_BG.withAlpha(alpha), rounding);
            gfx.outline(panel, NOTICE_BORDER.withAlpha(alpha), Math.max(1.0f, px(0.5f)), rounding);

            float textX = x + padding;
            gfx.text(FontRole.BOLD, notice.title, textX, y + px(5.0f), titleSize, NOTICE_TITLE.withAlpha(alpha));
            gfx.text(FontRole.BOLD, gfx.elide(FontRole.BOLD, notice.message, messageSize, panel.right() - padding - textX),
                    textX, y + px(15.5f), messageSize, NOTICE_MESSAGE.withAlpha(alpha));

            float barHeight = px(1.0f);
            gfx.rect(new Rect(x, panel.bottom() - barHeight, width * (notice.life / notice.total), barHeight), accent,
                    barHeight * 0.5f);
            ++slot;
        }

        notices.removeIf(notice -> notice.life <= 0.0f);
        if (slot > 0) {
            placeNotices(anchor, new Rect(homeX, stackTop, width, stackBottom - stackTop));
        }
    }

    /* With nothing to show, the stack is a faint stand-in while the menu is open, so it can still be moved. */
    private void drawNoticePlaceholder(Anchor anchor) {
        float width = px(NOTICE_WIDTH);
        float height = px(NOTICE_HEIGHT);
        Rect panel = new Rect(noticeLeft(anchor, width), noticeTop(anchor, height), width, height);
        gfx.rect(panel, NOTICE_BG.withAlpha(0.5f), px(NOTICE_ROUNDING));
        float size = px(NOTICE_MESSAGE_SIZE) / gfx.fonts().face(FontRole.BOLD).boxHeight(1.0f);
        gfx.text(FontRole.BOLD, "Notifications", panel.x() + px(NOTICE_PADDING), panel.y() + px(10.0f), size,
                NOTICE_MESSAGE.withAlpha(0.6f));
        placeNotices(anchor, panel);
    }

    /* The newest card's left edge, kept whole on screen. */
    private float noticeLeft(Anchor anchor, float width) {
        float x = anchor.x() * scaleFactor - (anchor.left() ? 0.0f : width);
        return Maths.clamp(x, 0.0f, Math.max(0.0f, windowWidth - width));
    }

    private float noticeTop(Anchor anchor, float height) {
        float y = anchor.y() * scaleFactor - (anchor.top() ? 0.0f : height);
        return Maths.clamp(y, 0.0f, Math.max(0.0f, windowHeight - height));
    }

    private void placeNotices(Anchor anchor, Rect drawn) {
        place(Element.NOTIFICATIONS, drawn, anchor.left() ? drawn.x() : drawn.right(),
                anchor.top() ? drawn.y() : drawn.bottom());
    }

    /* Whatever is under the pointer, or in hand, is outlined, with its resize handle in the free corner. */
    private void drawEditChrome() {
        for (Element element : Element.values()) {
            int index = element.ordinal();
            Rect drawn = bounds[index];
            boolean hovered = drawn.inset(-HANDLE_REACH).contains(mouseX, mouseY);
            if (drawn.width() <= 0.0f || grabbed != element && !hovered) {
                continue;
            }
            Rect frame = drawn.inset(-2.0f);
            gfx.rect(frame, EDIT_FILL, 3.0f);
            gfx.outline(frame, EDIT_OUTLINE, 1.0f, 3.0f);
            float cornerX = placedX[index] <= drawn.centerX() ? drawn.right() : drawn.x();
            float cornerY = placedY[index] <= drawn.centerY() ? drawn.bottom() : drawn.y();
            float side = 6.0f;
            gfx.rect(new Rect(cornerX - side * 0.5f, cornerY - side * 0.5f, side, side), EDIT_HANDLE, 1.5f);
        }
    }

    public void render(Context context) {
        scaleFactor = Math.max(1, context.state.scaleFactor);
        windowWidth = context.state.windowWidth;
        windowHeight = context.state.windowHeight;
        float guiWidth = windowWidth / scaleFactor;
        float guiHeight = windowHeight / scaleFactor;
        Anchor stack = anchor(Element.NOTIFICATIONS, guiWidth - NOTICE_MARGIN, guiHeight - NOTICE_MARGIN, guiWidth,
                guiHeight);
        float factor = context.state.scaledWidth > 0 ? windowWidth / context.state.scaledWidth : 2.0f;
        unit = Math.max(factor * stack.scale(), 0.5f);

        drawNotifications(context, stack);
        if (menuOpen && showNotifications && bounds[Element.NOTIFICATIONS.ordinal()].width() <= 0.0f) {
            drawNoticePlaceholder(stack);
        }
        if (menuOpen) {
            drawEditChrome();
        }
        System.arraycopy(bounds, 0, drawnBounds, 0, COUNT);
        System.arraycopy(placedX, 0, drawnX, 0, COUNT);
        System.arraycopy(placedY, 0, drawnY, 0, COUNT);
        Arrays.fill(bounds, Rect.EMPTY);
    }
}
