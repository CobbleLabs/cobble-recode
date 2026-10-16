import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes the game classes the client compiles and checks against, one jar per
 * supported version, out of the classes Lunar bakes when it launches a
 * version: vanilla with Lunar's mixins and OptiFine already applied, which is
 * exactly what runs, member access included. Bakes name their entries by
 * binary name without an extension; the jars use ordinary class paths.
 *
 * Each version takes the newest bake that holds it. 1.7.10 and 1.8.9 run
 * under MCP names and are told apart by BlockPos; a modern bake runs under
 * Fabric intermediary names and is 1.21.11 when its highest numbered class
 * is the highest one the 1.21.11 mappings name.
 *
 * Usage: java tools/GameJars.java <lunar multiver cache> <yarn 1.21.11 v2 jar> <out dir>
 */
public final class GameJars {
    private static final Pattern INTERMEDIARY_CLASS = Pattern.compile("net/minecraft/class_(\\d+)");

    public static void main(String[] args) throws IOException {
        Path cache = Path.of(args[0]);
        int newestModernClass;
        try (ZipFile yarn = new ZipFile(args[1])) {
            newestModernClass = highestClass(new String(yarn.getInputStream(yarn.getEntry("mappings/mappings.tiny"))
                    .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        Path out = Path.of(args[2]);
        Files.createDirectories(out);

        List<Path> bakes;
        try (Stream<Path> found = Files.find(cache, 3, (path, attributes) -> path.endsWith("bake.zip"))) {
            bakes = found.sorted(Comparator.comparing(GameJars::modified).reversed()).collect(Collectors.toList());
        }
        Map<String, Path> picked = new HashMap<>();
        for (Path bake : bakes) {
            String version = version(bake, newestModernClass);
            if (version != null) {
                picked.putIfAbsent(version, bake);
            }
        }
        for (String version : List.of("1.7.10", "1.8.9", "1.21.11")) {
            Path bake = picked.get(version);
            if (bake == null) {
                throw new IllegalStateException("no Lunar bake of Minecraft " + version + " under " + cache
                        + "; launch that version from Lunar once");
            }
            /* Rewritten only when Lunar baked a new one, so everything built from the jar stays up to date. */
            Path jar = out.resolve("minecraft-" + version + ".jar");
            Path source = out.resolve("minecraft-" + version + ".source");
            String stamp = bake + " " + modified(bake);
            if (Files.exists(jar) && Files.exists(source) && Files.readString(source).equals(stamp)) {
                continue;
            }
            write(bake, jar);
            Files.writeString(source, stamp);
            System.out.println(version + " <- " + cache.relativize(bake));
        }
    }

    private static String version(Path bake, int newestModernClass) throws IOException {
        try (ZipFile zip = new ZipFile(bake.toFile())) {
            if (zip.getEntry("net.minecraft.client.Minecraft") != null) {
                if (zip.getEntry("net.minecraft.util.math.BlockPos") != null) {
                    return null;
                }
                return zip.getEntry("net.minecraft.util.BlockPos") != null ? "1.8.9" : "1.7.10";
            }
            if (zip.getEntry("net.minecraft.class_310") == null) {
                return null;
            }
            Set<String> names = zip.stream().map(ZipEntry::getName).collect(Collectors.toSet());
            int highest = highestClass(String.join("\n", names).replace('.', '/'));
            return highest == newestModernClass ? "1.21.11" : null;
        }
    }

    private static int highestClass(String text) {
        Matcher matcher = INTERMEDIARY_CLASS.matcher(text);
        int highest = 0;
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest;
    }

    private static void write(Path bake, Path jar) throws IOException {
        Path partial = jar.resolveSibling(jar.getFileName() + ".part");
        try (ZipFile zip = new ZipFile(bake.toFile());
             OutputStream os = Files.newOutputStream(partial);
             ZipOutputStream zout = new ZipOutputStream(os)) {
            List<ZipEntry> entries = new ArrayList<>(zip.stream().collect(Collectors.toList()));
            entries.sort(Comparator.comparing(ZipEntry::getName));
            for (ZipEntry entry : entries) {
                zout.putNextEntry(new ZipEntry(entry.getName().replace('.', '/') + ".class"));
                try (InputStream in = zip.getInputStream(entry)) {
                    in.transferTo(zout);
                }
                zout.closeEntry();
            }
        }
        Files.move(partial, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
