package cobble.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Files packed into the client image, served by the loader that holds it. */
public final class Assets {
    private Assets() {}

    /** Null when the image does not carry the file. */
    public static InputStream open(String path) {
        return Assets.class.getClassLoader().getResourceAsStream(path.startsWith("/") ? path.substring(1) : path);
    }

    public static byte[] read(String path) {
        try (InputStream stream = Assets.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("asset missing from the image: " + path);
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("reading asset " + path, e);
        }
    }
}
