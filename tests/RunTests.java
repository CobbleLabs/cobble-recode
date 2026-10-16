import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles src/common and the build tools it carries with the tests in
 * tests/unit and runs every static, argument-less {@code test*} method of
 * every {@code *Test} class. Needs only a
 * JDK 17 and the jars CMake's configure fetches into infra/lib, so it runs on
 * any machine, not just a Windows one with Lunar installed.
 *
 * <pre>
 *   java tests/RunTests.java [lib-dir]        (default: infra/lib)
 * </pre>
 *
 * Exits non-zero when anything fails to compile or a test throws.
 */
public final class RunTests {
    static final String[] JARS = {
        "asm-9.7.1.jar", "asm-tree-9.7.1.jar", "asm-commons-9.7.1.jar", "luaj-jse-3.0.1.jar",
        "lwjgl-2.9.4-nightly-20150209.jar", "gson-2.2.4.jar",
    };

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path libs = root.resolve(args.length > 0 ? args[0] : "infra/lib");
        List<String> classpath = new ArrayList<>();
        for (String jar : JARS) {
            Path path = libs.resolve(jar);
            if (!Files.isRegularFile(path)) {
                System.err.println("missing " + path + " (configure with CMake once, or pass the folder holding it)");
                System.exit(2);
            }
            classpath.add(path.toString());
        }

        Path out = root.resolve("build/tests/classes");
        deleteTree(out);
        Files.createDirectories(out);
        List<String> sources = javaFiles(root.resolve("src/common"));
        sources.add(root.resolve("tools/CbMap.java").toString());
        sources.add(root.resolve("tools/Remap.java").toString());
        List<String> tests = javaFiles(root.resolve("tests/unit"));
        sources.addAll(tests);

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> options = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-nowarn",
                "-cp", String.join(File.pathSeparator, classpath), "-d", out.toString()));
        options.addAll(sources);
        if (javac.run(null, null, null, options.toArray(new String[0])) != 0) {
            System.err.println("compilation failed");
            System.exit(1);
        }

        /* Tests that shell out (the build tools) find the same jars and the repository through these. */
        System.setProperty("cobble.root", root.toString());
        System.setProperty("cobble.libs", String.join(File.pathSeparator, classpath));

        List<URL> urls = new ArrayList<>();
        urls.add(out.toUri().toURL());
        for (String jar : classpath) {
            urls.add(Path.of(jar).toUri().toURL());
        }
        int passed = 0;
        List<String> failed = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), RunTests.class.getClassLoader())) {
            for (String test : tests) {
                Path relative = root.resolve("tests/unit").relativize(Path.of(test));
                String name = relative.toString().replace(File.separatorChar, '.').replaceAll("\\.java$", "");
                if (!name.endsWith("Test")) {
                    continue;
                }
                Class<?> type = Class.forName(name, true, loader);
                List<Method> methods = Stream.of(type.getDeclaredMethods())
                        .filter(m -> m.getName().startsWith("test") && Modifier.isStatic(m.getModifiers())
                                && m.getParameterCount() == 0)
                        .sorted(Comparator.comparing(Method::getName))
                        .collect(Collectors.toList());
                for (Method method : methods) {
                    String label = type.getSimpleName() + "." + method.getName();
                    method.setAccessible(true);
                    try {
                        method.invoke(null);
                        ++passed;
                        System.out.println("  ok    " + label);
                    } catch (InvocationTargetException e) {
                        failed.add(label);
                        System.out.println("  FAIL  " + label + ": " + e.getCause());
                        e.getCause().printStackTrace(System.out);
                    }
                }
            }
        }
        System.out.println();
        System.out.println(passed + " passed, " + failed.size() + " failed");
        System.exit(failed.isEmpty() ? 0 : 1);
    }

    private static List<String> javaFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(path);
            }
        }
    }
}
