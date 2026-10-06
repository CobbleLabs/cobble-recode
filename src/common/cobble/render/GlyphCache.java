package cobble.render;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Interface-sized text, rasterised hinted at the exact size it is drawn at
 * into a coverage atlas filled on demand.
 *
 * <p>A distance field has no hinting: below about 30 px every stem and every
 * x-height it cannot place on the pixel grid is spread over two pixels, and
 * that is what makes small text read soft. A hinted strike puts stems,
 * x-height and baseline on whole pixels, and its advances are whole pixels
 * too, so each glyph lands on the grid the hinter drew it for. Larger sizes
 * gain nothing from hinting and stay on the field, which scales freely.
 *
 * <p>Sizes snap to half pixels so text whose size moves continuously, a
 * nametag shrinking with distance, reuses a bounded set of strikes instead of
 * minting one a frame. When the atlas fills, the renderer draws what it has
 * batched and the atlas starts over; glyphs keep their metrics and are drawn
 * again as they are next used.
 */
final class GlyphCache {
    static final float MAX_SIZE = 32.0f;
    private static final int STEPS_PER_PIXEL = 2;
    private static final int SIZE = 1024;
    /* Antialiased, whole-pixel metrics: the combination that has the
     * rasteriser hint. */
    private static final FontRenderContext CONTEXT = new FontRenderContext(null, true, false);

    static final class Glyph {
        final GlyphVector vector;
        final float advance;
        /* Pen-relative, one pixel of margin included so the filter never
         * reaches a neighbour. */
        final int offsetX;
        final int offsetY;
        final int width;
        final int height;
        boolean placed;
        float u0;
        float v0;
        float u1;
        float v1;

        Glyph(GlyphVector vector) {
            this.vector = vector;
            advance = vector.getGlyphMetrics(0).getAdvanceX();
            Rectangle bounds = vector.getGlyphPixelBounds(0, CONTEXT, 0.0f, 0.0f);
            boolean empty = bounds.isEmpty();
            offsetX = bounds.x - 1;
            offsetY = bounds.y - 1;
            width = empty ? 0 : bounds.width + 2;
            height = empty ? 0 : bounds.height + 2;
        }
    }

    private static final class Strike {
        final Font font;
        final Glyph[] latin = new Glyph[256];
        final Map<Integer, Glyph> other = new HashMap<>();

        Strike(Font font) {
            this.font = font;
        }
    }

    private final FontAtlas fonts;
    private final Strike[][] strikes = new Strike[FontRole.values().length][(int) MAX_SIZE * STEPS_PER_PIXEL + 1];
    private final List<Glyph> placed = new ArrayList<>();
    private final byte[] pixels = new byte[SIZE * SIZE];
    private int penX = 1;
    private int penY = 1;
    private int shelfHeight;
    private int dirtyTop = SIZE;
    private int dirtyBottom;
    private int texture;

    GlyphCache(FontAtlas fonts) {
        this.fonts = fonts;
    }

    static boolean covers(float size) {
        return size <= MAX_SIZE;
    }

    Glyph glyph(FontRole role, float size, int codepoint) {
        Strike strike = strike(role, size);
        Glyph found = codepoint < strike.latin.length ? strike.latin[codepoint] : strike.other.get(codepoint);
        if (found == null) {
            found = new Glyph(strike.font.createGlyphVector(CONTEXT, Character.toChars(shown(strike.font, codepoint))));
            if (codepoint < strike.latin.length) {
                strike.latin[codepoint] = found;
            } else {
                strike.other.put(codepoint, found);
            }
        }
        return found;
    }

    /* What the face draws for a codepoint it lacks: the replacement mark, or
     * the face's own missing glyph when it has neither. */
    private static int shown(Font font, int codepoint) {
        return font.canDisplay(codepoint) || !font.canDisplay(0xFFFD) ? codepoint : 0xFFFD;
    }

    private Strike strike(FontRole role, float size) {
        int step = Math.max(1, Math.round(size * STEPS_PER_PIXEL));
        Strike[] sizes = strikes[role.ordinal()];
        if (sizes[step] == null) {
            sizes[step] = new Strike(fonts.source(role).deriveFont((float) step / STEPS_PER_PIXEL));
        }
        return sizes[step];
    }

    float measure(FontRole role, String text, float size) {
        float width = 0.0f;
        for (int index = 0; index < text.length(); ) {
            int codepoint = text.codePointAt(index);
            index += Character.charCount(codepoint);
            width += glyph(role, size, codepoint).advance;
        }
        return width;
    }

    /** True once the glyph is in the atlas; false when the atlas has no room left for it. */
    boolean place(Glyph glyph) {
        if (glyph.placed) {
            return true;
        }
        if (penX + glyph.width + 1 > SIZE) {
            penX = 1;
            penY += shelfHeight + 1;
            shelfHeight = 0;
        }
        if (penY + glyph.height + 1 > SIZE) {
            return false;
        }

        BufferedImage image = new BufferedImage(glyph.width, glyph.height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        graphics.setColor(Color.WHITE);
        graphics.drawGlyphVector(glyph.vector, -glyph.offsetX, -glyph.offsetY);
        graphics.dispose();
        byte[] coverage = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int row = 0; row < glyph.height; ++row) {
            System.arraycopy(coverage, row * glyph.width, pixels, (penY + row) * SIZE + penX, glyph.width);
        }

        float inverseSize = 1.0f / SIZE;
        glyph.u0 = penX * inverseSize;
        glyph.v0 = penY * inverseSize;
        glyph.u1 = (penX + glyph.width) * inverseSize;
        glyph.v1 = (penY + glyph.height) * inverseSize;
        glyph.placed = true;
        placed.add(glyph);
        dirtyTop = Math.min(dirtyTop, penY);
        dirtyBottom = Math.max(dirtyBottom, penY + glyph.height);
        penX += glyph.width + 1;
        shelfHeight = Math.max(shelfHeight, glyph.height);
        return true;
    }

    /** Empties the atlas. Anything already batched against it must be drawn first. */
    void clear() {
        for (Glyph glyph : placed) {
            glyph.placed = false;
        }
        placed.clear();
        Arrays.fill(pixels, (byte) 0);
        penX = 1;
        penY = 1;
        shelfHeight = 0;
        dirtyTop = 0;
        dirtyBottom = SIZE;
    }

    /** Sends what was placed since the last call; creates the texture on the first. */
    void upload() {
        if (texture == 0) {
            texture = Gl.uploadField(0, SIZE, SIZE, pixels);
        } else if (dirtyTop < dirtyBottom) {
            Gl.updateFieldRows(texture, SIZE, dirtyTop, dirtyBottom - dirtyTop, pixels);
        } else {
            return;
        }
        dirtyTop = SIZE;
        dirtyBottom = 0;
        Gl.checkError("glyph cache upload");
    }

    /** The pixels stay, so a lost context re-uploads without a re-rasterise. */
    void destroy() {
        if (owned()) {
            GL11.glDeleteTextures(texture);
        }
        texture = 0;
    }

    boolean owned() {
        return Gl.holdsTexture(texture, SIZE, SIZE, GL30.GL_R8);
    }

    int texture() {
        return texture;
    }
}
