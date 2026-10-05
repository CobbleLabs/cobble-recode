package cobble.render;

import cobble.core.Color;
import cobble.core.Log;
import cobble.core.Maths;
import cobble.platform.Platform;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Frosted-glass backdrop for the click GUI. The scene behind the menu is
 * blitted out of the default framebuffer, run through a dual-Kawase pyramid and
 * tinted. Four bilinear taps per pass over a half-resolution copy cost a
 * fraction of a separable gaussian, so the menu stays cheap enough to leave
 * open during a fight.
 */
public final class Backdrop {
    private static final String SCOPE = "backdrop";
    private static final int LEVELS = 4;
    /* A context that will not run the pyramid fails every frame, and nine
     * wasted passes per frame is a framerate. */
    private static final int FAILURE_LIMIT = 8;

    private static final class Level {
        int texture;
        int framebuffer;
        int width;
        int height;
    }

    private final Level[] levels = new Level[LEVELS];
    private final Shader down = new Shader();
    private final Shader up = new Shader();
    private final Shader tint = new Shader();
    private int emptyVertexArray;
    private int sourceWidth;
    private int sourceHeight;
    private int failures;
    private boolean valid;
    private boolean ready;

    public Backdrop() {
        for (int index = 0; index < LEVELS; ++index) {
            levels[index] = new Level();
        }
    }

    public boolean createResources() {
        if (!down.compile("cobble.kawase.down", Shaders.FULLSCREEN_VERTEX, Shaders.KAWASE_DOWN_FRAGMENT)
                || !up.compile("cobble.kawase.up", Shaders.FULLSCREEN_VERTEX, Shaders.KAWASE_UP_FRAGMENT)
                || !tint.compile("cobble.backdrop.tint", Shaders.FULLSCREEN_VERTEX, Shaders.BACKDROP_FRAGMENT)) {
            return false;
        }
        emptyVertexArray = GL30.glGenVertexArrays();
        ready = true;
        return true;
    }

    /* As with the renderer: names that may have changed hands since a wipe are forgotten, not deleted. */
    public void destroy() {
        boolean intact = down.valid() && up.valid() && tint.valid();
        releaseLevels();
        if (intact && emptyVertexArray != 0) {
            GL30.glDeleteVertexArrays(emptyVertexArray);
        }
        emptyVertexArray = 0;
        down.destroy();
        up.destroy();
        tint.destroy();
        valid = false;
        ready = false;
        sourceWidth = 0;
        sourceHeight = 0;
    }

    private void releaseLevels() {
        for (Level level : levels) {
            /* The framebuffer goes with its texture: if the texture is no longer ours, neither is it. */
            if (Gl.holdsTexture(level.texture, level.width, level.height, GL11.GL_RGBA8)) {
                GL30.glDeleteFramebuffers(level.framebuffer);
                GL11.glDeleteTextures(level.texture);
            }
            level.framebuffer = 0;
            level.texture = 0;
        }
    }

    private boolean resize(int width, int height) {
        if (width == sourceWidth && height == sourceHeight && levels[0].texture != 0) {
            return true;
        }
        releaseLevels();
        sourceWidth = width;
        sourceHeight = height;
        // A pyramid that would not build at the old size may well build at this one.
        failures = 0;
        for (int index = 0; index < LEVELS; ++index) {
            Level level = levels[index];
            level.width = Math.max(1, width >> (index + 1));
            level.height = Math.max(1, height >> (index + 1));

            level.texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, level.texture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, level.width, level.height, 0, GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            level.framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, level.framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    level.texture, 0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                Log.error(SCOPE, "blur level %d framebuffer incomplete", index);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                return false;
            }
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        Log.debug(SCOPE, "blur pyramid rebuilt for %dx%d", width, height);
        return true;
    }

    /**
     * Captures and blurs the current default framebuffer. {@code strength} in
     * 0..1 scales the sample offset, so the panel can fade its blur in with
     * the open animation.
     */
    public boolean capture(int width, int height, float strength, Color tintColour) {
        valid = false;
        if (width <= 0 || height <= 0 || !ready || failures >= FAILURE_LIMIT || !resize(width, height)) {
            return false;
        }
        /* Lunar's renderer leaves errors queued and glGetError reports the
         * oldest, so draining first makes the check below about this capture. */
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // drain
        }

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL30.glBindVertexArray(emptyVertexArray);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (Platform.get().gl33()) {
            GL33.glBindSampler(0, 0);
        }

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, levels[0].framebuffer);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, levels[0].width, levels[0].height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

        float offset = Maths.clamp(strength, 0.0f, 1.0f) * 2.6f + 0.4f;
        down.bind();
        down.set("uSource", 0);
        down.set("uOffset", offset);
        for (int index = 1; index < LEVELS; ++index) {
            pass(down, levels[index - 1], levels[index]);
        }
        up.bind();
        up.set("uSource", 0);
        up.set("uOffset", offset);
        for (int index = LEVELS - 1; index > 0; --index) {
            pass(up, levels[index], levels[index - 1]);
        }

        // Tint through level 1 and back into level 0, so the panel shader only ever samples one texture.
        tint.bind();
        tint.set("uSource", 0);
        tint.set("uTint", tintColour);
        tint.set("uSaturation", 0.72f);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, levels[1].framebuffer);
        GL11.glViewport(0, 0, levels[1].width, levels[1].height);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, levels[0].texture);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, levels[1].framebuffer);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, levels[0].framebuffer);
        GL30.glBlitFramebuffer(0, 0, levels[1].width, levels[1].height, 0, 0, levels[0].width, levels[0].height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, width, height);
        GL11.glEnable(GL11.GL_BLEND);

        /* An errored capture leaves level 0 unknown, and the menu would paint
         * the whole screen with it; invalid makes it draw without a backdrop. */
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            if (failures == 0) {
                Log.error(SCOPE, "blur pyramid failed with GL 0x%04X; drawing without a backdrop", error);
            }
            ++failures;
            return false;
        }
        if (failures > 0) {
            Log.info(SCOPE, "blur pyramid recovered after %d failed captures", failures);
            failures = 0;
        }
        valid = true;
        return true;
    }

    private static void pass(Shader shader, Level source, Level target) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
        GL11.glViewport(0, 0, target.width, target.height);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, source.texture);
        shader.set("uHalfPixel", 0.5f / source.width, 0.5f / source.height);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    public int texture() {
        return levels[0].texture;
    }

    public boolean valid() {
        return valid;
    }
}
