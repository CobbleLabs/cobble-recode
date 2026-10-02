package cobble.module.render;

import cobble.render.Gl;
import java.awt.image.BufferedImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The Cobble cape's animation: a dark band sweeps across the orange lettering
 * and fades out at its edges. Only the rectangle around the letters is
 * re-coloured and re-uploaded, which is a few hundred thousand bytes against
 * the eight million of the whole 2048x1024 texture.
 */
final class CapeShimmer {
    private static final long FRAME_MS = 33;
    private static final float PERIOD_MS = 2600.0f;
    /* How much of the sweep the band covers, and how dark its middle gets. */
    private static final float BAND_WIDTH = 0.35f;
    private static final float BAND_DEPTH = 0.9f;

    final DynamicTexture texture;
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final int[] base;
    private final float[] cover;
    private final int[] frame;
    private final int letterRed;
    private final int letterGreen;
    private final int letterBlue;
    private long lastFrame = -1;

    private CapeShimmer(BufferedImage image, int x, int y, int width, int height, int letters) {
        texture = new DynamicTexture(image);
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        base = image.getRGB(x, y, width, height, null, 0, width);
        frame = base.clone();
        letterRed = letters >> 16 & 255;
        letterGreen = letters >> 8 & 255;
        letterBlue = letters & 255;
        /* The letters sit on a black halo, so a pixel's red channel is how much letter it holds. */
        cover = new float[base.length];
        for (int index = 0; index < base.length; ++index) {
            if (isLetter(base[index])) {
                cover[index] = (base[index] >> 16 & 255) / (float) letterRed;
            }
        }
    }

    /** The animation over the lettering of a cape image, or null when it has none. */
    static CapeShimmer of(BufferedImage image) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int brightest = 0;
        for (int py = 0; py < image.getHeight(); ++py) {
            for (int px = 0; px < image.getWidth(); ++px) {
                int argb = image.getRGB(px, py);
                if (isLetter(argb)) {
                    minX = Math.min(minX, px);
                    minY = Math.min(minY, py);
                    maxX = Math.max(maxX, px);
                    maxY = Math.max(maxY, py);
                    if ((argb >> 16 & 255) > (brightest >> 16 & 255)) {
                        brightest = argb & 0xFFFFFF;
                    }
                }
            }
        }
        return maxX < 0 ? null : new CapeShimmer(image, minX, minY, maxX - minX + 1, maxY - minY + 1, brightest);
    }

    /* Orange: far more red than blue, which the grey edges and the dark fill never have. */
    private static boolean isLetter(int argb) {
        int red = argb >> 16 & 255;
        return argb >>> 24 > 0 && red > 0x30 && red > (argb & 255) + 0x20;
    }

    /** Moves the band along; call while the cape is drawn, on the render thread. */
    void update() {
        long now = System.currentTimeMillis();
        if (lastFrame >= 0 && now - lastFrame < FRAME_MS) {
            return;
        }
        lastFrame = now;
        /* The band starts off the left of the word and leaves off the right, so it never pops in or out. */
        float centre = -BAND_WIDTH + now % (long) PERIOD_MS / PERIOD_MS * (1.0f + 2.0f * BAND_WIDTH);
        for (int row = 0; row < height; ++row) {
            for (int column = 0; column < width; ++column) {
                int index = row * width + column;
                if (cover[index] <= 0.0f) {
                    continue;
                }
                float edge = Math.max(0.0f, 1.0f - Math.abs(column / (float) width - centre) / BAND_WIDTH);
                float dark = BAND_DEPTH * edge * edge * (3.0f - 2.0f * edge) * cover[index];
                int pixel = base[index];
                int red = Math.round((pixel >> 16 & 255) - letterRed * dark);
                int green = Math.round((pixel >> 8 & 255) - letterGreen * dark);
                int blue = Math.round((pixel & 255) - letterBlue * dark);
                frame[index] = pixel & 0xFF000000 | Math.max(red, 0) << 16 | Math.max(green, 0) << 8 | Math.max(blue, 0);
            }
        }
        Gl.updateRegion(texture.getGlTextureId(), x, y, width, height, frame);
    }
}
