package cobble.util.font;

import cobble.render.Gl;
import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;

public final class TextureUploader {
    public int upload(BufferedImage var1) {
        int var2 = var1.getWidth();
        int var3 = var1.getHeight();
        int[] var4 = var1.getRGB(0, 0, var2, var3, new int[var2 * var3], 0, var2);
        ByteBuffer var5 = BufferUtils.createByteBuffer(var2 * var3 * 4);

        for (int var9 : var4) {
            int var10 = var9 >>> 24 & 0xFF;
            var5.put((byte)(var10 == 0 ? 255 : var9 >>> 16 & 0xFF));
            var5.put((byte)(var10 == 0 ? 255 : var9 >>> 8 & 0xFF));
            var5.put((byte)(var10 == 0 ? 255 : var9 & 0xFF));
            var5.put((byte)var10);
        }

        ((Buffer)var5).flip();
        return Gl.uploadRgba(var2, var3, var5);
    }
}
