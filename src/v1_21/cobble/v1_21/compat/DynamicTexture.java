package cobble.v1_21.compat;

import java.awt.image.BufferedImage;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

/**
 * 1.8.9's DynamicTexture as one of the game's, so the game can draw it too,
 * a cape say, once {@link TextureManager} has registered it. The legacy code
 * writes to it by its GL name, which the game's GL backend gives out. Made on
 * the client thread, which holds the context.
 */
public final class DynamicTexture {
    final NativeImageBackedTexture backing;

    public DynamicTexture(BufferedImage image) {
        NativeImage pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                pixels.setColorArgb(x, y, image.getRGB(x, y));
            }
        }
        backing = new NativeImageBackedTexture(() -> "cobble", pixels);
    }

    public int getGlTextureId() {
        return ((GlTexture) backing.getGlTexture()).getGlId();
    }
}
