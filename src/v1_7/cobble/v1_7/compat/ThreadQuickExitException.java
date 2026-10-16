package cobble.v1_7.compat;

/** 1.8.9 unwinds a packet handed off to the client thread with this; 1.7.10 never throws it. */
public final class ThreadQuickExitException extends RuntimeException {
    private ThreadQuickExitException() {}
}
