package cobble.render;

import cobble.boot.Native;
import cobble.core.Log;
import cobble.event.DrawEvent;
import cobble.event.Event;
import cobble.event.EventBus;
import cobble.module.Module;
import cobble.module.ModuleManager;

/**
 * Keeps the modules set to Stream Proof off screen captures. What they draw
 * goes into an offscreen layer instead of the game's frame, and the bootstrap
 * shows that layer on a click-through window over the game which Windows
 * leaves out of every capture, so OBS, Discord and the like record the game
 * without them while the player still sees them, a frame late.
 */
public final class StreamProof {
    private static final String SCOPE = "streamproof";

    private static boolean unavailable;

    private StreamProof() {}

    public static boolean hides(Object owner) {
        return owner instanceof Module module && module.streamProof();
    }

    /** Posts a draw event to the game's frame, then to the layer for the modules it hides. */
    public static <T extends Event & DrawEvent> T post(T event) {
        EventBus.post(event, owner -> !hides(owner));
        if (ModuleManager.anyStreamProofed()) {
            layer(() -> EventBus.post(event, StreamProof::hides));
        }
        return event;
    }

    /**
     * Draws into the layer. The game's frame gets it instead when this Windows
     * cannot keep a window off captures, which leaves it visible there.
     */
    public static void layer(Runnable draw) {
        if (unavailable || !Native.streamProofBegin()) {
            if (!unavailable) {
                unavailable = true;
                Log.warn(SCOPE, "unavailable here; Stream Proof modules draw in the game's frame");
            }
            draw.run();
            return;
        }
        try {
            draw.run();
        } finally {
            Native.streamProofEnd();
        }
    }

    /** End of the frame: shows what the layer got, or hides the window if it got nothing. */
    public static void present() {
        if (!unavailable) {
            Native.streamProofPresent();
        }
    }

    /** Client thread, while the game's GL context is current. */
    public static void shutdown() {
        Native.streamProofShutdown();
    }
}
