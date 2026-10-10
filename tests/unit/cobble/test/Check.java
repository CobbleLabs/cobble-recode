package cobble.test;

import java.util.Objects;

/** The few assertions the tests need, so they need no test framework. */
public final class Check {
    private Check() {}

    public static void that(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public interface Body {
        void run() throws Exception;
    }

    public static <T extends Throwable> T throwsA(Class<T> type, Body body, String what) {
        try {
            body.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return type.cast(thrown);
            }
            throw new AssertionError(what + ": threw " + thrown + " instead of " + type.getSimpleName(), thrown);
        }
        throw new AssertionError(what + ": did not throw " + type.getSimpleName());
    }
}
