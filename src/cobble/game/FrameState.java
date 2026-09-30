package cobble.game;

import cobble.core.Mat4;
import java.util.ArrayList;
import java.util.List;

/** Everything the client reads from the game, once per frame and once per tick. */
public final class FrameState {
    public boolean inWorld;
    public boolean screenOpen;
    public float tickProgress;
    public int fps;
    public int ping = -1;

    /** Framebuffer pixels, which is also the space the cursor is reported in. */
    public int windowWidth;
    public int windowHeight;
    public int scaledWidth;
    public int scaledHeight;
    public int scaleFactor = 1;
    public double mouseSensitivity = 0.5;
    /* Server-driven tick counter. Its rate against the wall clock is the only
     * honest read on server TPS a client has. */
    public long worldTime;

    public final LocalPlayerView player = new LocalPlayerView();
    public final CameraView camera = new CameraView();
    public Mat4 projection = new Mat4();
    public Mat4 view = new Mat4();
    /* World-space overlays must never fall back to another frame's camera, so
     * everything that projects checks this. */
    public boolean matricesValid;

    public final List<EntityView> entities = new ArrayList<>();
}
