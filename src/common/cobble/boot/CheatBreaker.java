package cobble.boot;

import cobble.core.Log;
import cobble.tools.CbMap;
import cobble.tools.Remap;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

/**
 * CheatBreaker renames every Minecraft class and member differently in each
 * build, and updates itself. The image holds each CheatBreaker version's
 * overlay for the build the client was built against; when the game is any
 * other build, the client names it the way the build does (tools/CbMap.java),
 * rewrites itself into those names (tools/Remap.java) and puts the result in
 * the overlay's place. Hooks and modules that do not link against the new
 * build are left out rather than failing in game. The names are kept per
 * build under mappings/ in the client's folder, so the match only runs once
 * per CheatBreaker update.
 *
 * For each CheatBreaker version the image also holds, under remap/{@code id}/,
 * what that takes: MCP's zips, the SHA-1 of the vanilla client the names are
 * matched against, the hook table under MCP's names, and under classes/ the
 * classes the build rewrote for the version before naming them.
 */
final class CheatBreaker {
    private static final String SCOPE = "cheatbreaker";
    private static final String CLIENT = "cobble/";
    private static final String MINECRAFT = "net/minecraft/";
    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    private CheatBreaker() {}

    /** Called before the overlay is selected; {@code image} is the bootstrap's MemoryLoader. */
    static void adapt(GameVersion version, Path jar, Path home, ClassLoader image)
            throws IOException, ReflectiveOperationException {
        byte[] built;
        try (InputStream in = image.getResourceAsStream("versions/" + version.id + "/game.sha1")) {
            if (in == null) {
                throw new IllegalStateException("the client was built without CheatBreaker " + version.minecraft());
            }
            built = in.readAllBytes();
        }
        String sha1 = CbMap.sha1(Files.readAllBytes(jar));
        if (sha1.equals(text(built))) {
            return;
        }
        long start = System.nanoTime();
        @SuppressWarnings("unchecked")
        Map<String, byte[]> files = (Map<String, byte[]>) image.getClass().getMethod("entries").invoke(image);
        Log.info(SCOPE, "%s is a CheatBreaker build the client was not built for; renaming the client for it", jar);
        String data = "remap/" + version.id + "/";
        Remap remap = Remap.cheatBreaker(names(version, jar, sha1, home, files, data));

        Map<String, byte[]> classes = new HashMap<>();
        Map<String, byte[]> rewritten = new HashMap<>();
        String rewrittenPrefix = data + "classes/";
        files.forEach((path, bytes) -> {
            if (path.startsWith(CLIENT) && path.endsWith(".class")) {
                classes.put(path, bytes);
            } else if (path.startsWith(rewrittenPrefix)) {
                rewritten.put(path.substring(rewrittenPrefix.length()), bytes);
            }
        });
        List<String> table = text(files.get(data + "hooks.tsv")).lines().toList();
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> overlay;
        List<String> hooks;
        Map<String, ClassNode> game;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            overlay = remap.toCheatBreaker(zip, classes, rewritten);
            hooks = remap.hooks(zip, table, problems);
            game = signatures(zip);
        }
        problems.forEach(problem -> Log.warn(SCOPE, "hook left out, %s", problem));

        Map<String, byte[]> view = new HashMap<>(classes);
        view.putAll(rewritten);
        view.putAll(overlay);
        Set<String> unlinked = unlinked(view.values(), game);
        String modulesPath = "cobble/modules/" + version.id + ".txt";
        List<String> modules = new ArrayList<>();
        int listed = 0;
        for (String module : text(files.get(modulesPath)).lines().map(String::strip).toList()) {
            if (module.isEmpty()) {
                continue;
            }
            ++listed;
            if (unlinked.contains(module.replace('.', '/'))) {
                Log.warn(SCOPE, "%s does not link against this build; left out", module);
            } else {
                modules.add(module);
            }
        }

        overlay.put(modulesPath, (String.join("\n", modules) + "\n").getBytes(StandardCharsets.UTF_8));
        overlay.put(CLIENT + version.adapterPackage.replace('.', '/') + "/hooks.tsv",
                (String.join("\n", hooks) + "\n").getBytes(StandardCharsets.UTF_8));
        image.getClass().getMethod("replaceOverlay", String.class, Map.class).invoke(image, version.id, overlay);
        Log.info(SCOPE, "renamed for this build in %d ms: %d of %d hooks, %d of %d modules",
                (System.nanoTime() - start) / 1_000_000, hooks.size(), table.size(), modules.size(), listed);
    }

    /** names.tsv for the build, from the client's folder when it was named before. */
    private static List<String> names(GameVersion version, Path jar, String sha1, Path home,
                                      Map<String, byte[]> files, String data) throws IOException {
        Path cached = home.resolve("mappings").resolve(version.id + "-" + sha1 + ".tsv");
        if (Files.isRegularFile(cached)) {
            return Files.readAllLines(cached);
        }
        Path vanilla = vanillaJar(version.minecraft(), home, text(files.get(data + "vanilla.sha1")).strip());
        CbMap.Names names = CbMap.names(vanilla, jar, files.get(data + "srg.zip"), files.get(data + "stable.zip"));
        Log.info(SCOPE, "named the build after MCP: %s", names.summary());
        write(cached, (String.join("\n", names.rows()) + "\n").getBytes(StandardCharsets.UTF_8));
        return names.rows();
    }

    /** Vanilla's client: the launcher's when it has one, otherwise Mojang's, downloaded once. */
    private static Path vanillaJar(String minecraft, Path home, String sha1) throws IOException {
        Path downloaded = home.resolve("mappings").resolve("minecraft-" + minecraft + ".jar");
        Path launcher = Path.of(System.getenv("APPDATA"), ".minecraft", "versions", minecraft, minecraft + ".jar");
        for (Path candidate : List.of(launcher, downloaded)) {
            if (Files.isRegularFile(candidate) && CbMap.sha1(Files.readAllBytes(candidate)).equals(sha1)) {
                return candidate;
            }
        }
        URL url = URI.create("https://launcher.mojang.com/v1/objects/" + sha1 + "/client.jar").toURL();
        Log.info(SCOPE, "downloading Minecraft %s's client from %s", minecraft, url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        byte[] bytes;
        try (InputStream in = connection.getInputStream()) {
            bytes = in.readAllBytes();
        } finally {
            connection.disconnect();
        }
        if (!CbMap.sha1(bytes).equals(sha1)) {
            throw new IOException("the Minecraft " + minecraft + " client downloaded from " + url + " is not the one expected");
        }
        write(downloaded, bytes);
        return downloaded;
    }

    /** Through a temporary file, so a half-written one is never taken for the real thing. */
    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.write(partial, bytes);
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Map<String, ClassNode> signatures(ZipFile zip) throws IOException {
        Map<String, ClassNode> classes = new HashMap<>();
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (entry.getName().endsWith(".class")) {
                ClassNode node = new ClassNode();
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                }
                classes.put(node.name, node);
            }
        }
        return classes;
    }

    /**
     * The client classes that would not link against the game: those naming a
     * Minecraft class it does not have or a member of one of its classes that
     * nothing up the class's hierarchy declares, and those naming such a
     * client class. tools/Linkage.java checks the same thing at build time,
     * against the build CheatBreaker had then.
     */
    private static Set<String> unlinked(Iterable<byte[]> view, Map<String, ClassNode> game) {
        Set<String> unlinked = new HashSet<>();
        Map<String, Set<String>> uses = new HashMap<>();
        for (byte[] bytes : view) {
            ClassReader reader = new ClassReader(bytes);
            Set<String> used = new HashSet<>();
            boolean[] missing = {false};
            Remapper references = new Remapper() {
                @Override
                public String map(String internalName) {
                    if (internalName.startsWith(CLIENT)) {
                        used.add(internalName);
                    } else if (internalName.startsWith(MINECRAFT) && !game.containsKey(internalName)) {
                        missing[0] = true;
                    }
                    return internalName;
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    missing[0] |= !declares(game, owner, name, descriptor, false);
                    return name;
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    missing[0] |= !declares(game, owner, name, descriptor, true);
                    return name;
                }
            };
            reader.accept(new ClassRemapper(new ClassWriter(0), references), ClassReader.SKIP_FRAMES);
            if (missing[0]) {
                unlinked.add(reader.getClassName());
            }
            uses.put(reader.getClassName(), used);
        }
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Map.Entry<String, Set<String>> entry : uses.entrySet()) {
                if (!unlinked.contains(entry.getKey()) && entry.getValue().stream().anyMatch(unlinked::contains)) {
                    unlinked.add(entry.getKey());
                    grew = true;
                }
            }
        }
        return unlinked;
    }

    /**
     * Whether a reference to a member of a game class resolves: up the
     * game's hierarchy, then through the libraries it extends, which the
     * game's loader holds. Any other owner is not this check's business.
     */
    private static boolean declares(Map<String, ClassNode> game, String owner, String name, String descriptor,
                                    boolean field) {
        ClassNode node = game.get(owner);
        if (node == null) {
            return true;
        }
        if (field ? node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor))
                : node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor))) {
            return true;
        }
        List<String> parents = new ArrayList<>(node.interfaces);
        if (node.superName != null) {
            parents.add(node.superName);
        }
        for (String parent : parents) {
            boolean found = game.containsKey(parent) ? declares(game, parent, name, descriptor, field)
                    : libraryDeclares(parent, name, descriptor, field);
            if (found) {
                return true;
            }
        }
        return false;
    }

    private static boolean libraryDeclares(String owner, String name, String descriptor, boolean field) {
        try {
            return declares(Class.forName(owner.replace('/', '.'), false, CheatBreaker.class.getClassLoader()),
                    name, descriptor, field);
        } catch (ClassNotFoundException | LinkageError e) {
            return true;
        }
    }

    private static boolean declares(Class<?> type, String name, String descriptor, boolean field) {
        if (type == null) {
            return false;
        }
        if (field) {
            for (Field candidate : type.getDeclaredFields()) {
                if (candidate.getName().equals(name) && Type.getDescriptor(candidate.getType()).equals(descriptor)) {
                    return true;
                }
            }
        } else {
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && Type.getMethodDescriptor(candidate).equals(descriptor)) {
                    return true;
                }
            }
        }
        if (declares(type.getSuperclass(), name, descriptor, field)) {
            return true;
        }
        for (Class<?> parent : type.getInterfaces()) {
            if (declares(parent, name, descriptor, field)) {
                return true;
            }
        }
        return false;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
