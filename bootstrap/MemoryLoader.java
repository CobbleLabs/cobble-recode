package cobble.boot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Serves the client image (classes, ASM, LuaJ, assets) out of memory, child-first,
 * with Lunar's Minecraft loader as parent. Compiled apart from the client and
 * defined into the system loader by the bootstrap, which reuses it on every
 * injection; each injection gets its own instance, so unloading one lets its
 * classes be collected.
 */
public final class MemoryLoader extends ClassLoader {
    static {
        registerAsParallelCapable();
    }

    private final Map<String, byte[]> entries = new HashMap<>();
    private volatile String overlay = "";

    public MemoryLoader(ClassLoader parent, byte[] image) throws IOException {
        super("cobble", parent);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(image))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
    }

    /**
     * Serves the image's versions/{@code id}/ classes ahead of its own from
     * now on: the legacy code as rewritten for that version. Called once, as
     * soon as the client knows the version and before it loads anything the
     * overlay holds.
     */
    public void selectVersion(String id) {
        overlay = "versions/" + id + "/";
    }

    /** Every file of the image by its path, overlays included. */
    public Map<String, byte[]> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /**
     * Puts {@code files} in the place of versions/{@code id}/. Only before
     * {@link #selectVersion}, whose volatile write publishes them to every
     * thread that loads from the overlay afterwards.
     */
    public void replaceOverlay(String id, Map<String, byte[]> files) {
        String prefix = "versions/" + id + "/";
        entries.keySet().removeIf(path -> path.startsWith(prefix));
        files.forEach((path, bytes) -> entries.put(prefix + path, bytes));
    }

    private byte[] entry(String path) {
        byte[] bytes = overlay.isEmpty() ? null : entries.get(overlay + path);
        return bytes != null ? bytes : entries.get(path);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        byte[] bytes = entry(name.replace('.', '/') + ".class");
        if (bytes == null) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] bytes = entry(name.startsWith("/") ? name.substring(1) : name);
        return bytes != null ? new ByteArrayInputStream(bytes) : super.getResourceAsStream(name);
    }
}
