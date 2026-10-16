package cobble.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Moves classes between the names the client is written in and the names a
 * game runs with: Yarn and Fabric's intermediary for the modern versions,
 * MCP and CheatBreaker's for CheatBreaker.
 *
 * <pre>
 *   named        yarn.jar intermediary.jar named.jar
 *   mcp          names.tsv cheatbreaker.jar mcp.jar
 *   intermediary yarn.jar named.jar classes-in classes-out
 *   cheatbreaker names.tsv cheatbreaker.jar classes-in[,rewritten...] classes-out
 *   hooks        names.tsv cheatbreaker.jar classpath table-class hooks.tsv mcp-hooks.tsv
 * </pre>
 *
 * The first two write game jars without code for the client to compile and
 * be rewritten against: 1.21 under Yarn's names, and CheatBreaker's game back
 * under MCP's, where its version has no Lunar build to stand in for it. Every
 * intermediary member name is unique across the whole game, so that direction
 * needs no hierarchy. The next two put compiled client classes under
 * the names the game runs with. Yarn and MCP names repeat, so a member
 * reference is resolved up the game's hierarchy to the game class that
 * declares it, and only then renamed; references to anything else are left
 * alone. That hierarchy is the named jar's for intermediary, and CheatBreaker's
 * own read back under MCP's names for CheatBreaker. CheatBreaker's classes go
 * to an overlay, so only the classes that change are written: the renamed
 * ones, and every class of the optional rewritten sets, which join the input
 * and stand in for its own classes of the same name (see tools/Retarget.java).
 * The last puts the hooks of {@code table-class}'s {@code ALL} under
 * CheatBreaker's names, one per line, for its adapter to read, and writes the
 * same lines under MCP's names; see tools/CbMap.java for names.tsv.
 *
 * The client runs the CheatBreaker steps itself, on its own image, when the
 * game is a CheatBreaker build other than the one it was built against; see
 * cobble.boot.CheatBreaker.
 *
 * Usage: java -cp asm.jar;asm-commons.jar;asm-tree.jar tools/Remap.java ...
 */
public final class Remap {
    private final Map<String, String> classesToNamed = new HashMap<>();
    private final Map<String, String> classesToRuntime = new HashMap<>();
    /**
     * Runtime member to its named one: by name alone from intermediary, whose
     * names are unique, by "owner.name descriptor" from CheatBreaker's, which
     * repeat across classes and overload by descriptor.
     */
    private final Map<String, String> membersToNamed = new HashMap<>();
    /** Named class, then named member name and descriptor, to the runtime name. */
    private final Map<String, Map<String, String>> membersToRuntime = new HashMap<>();
    private final Map<String, String[]> supertypes = new HashMap<>();
    private final Map<String, Boolean> interfaces = new HashMap<>();
    private final Map<String, Map<String, List<String>>> abstractMethods = new HashMap<>();
    /** Whether the class being rewritten has had anything renamed. */
    private boolean renamed;

    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        switch (args[0]) {
            case "named" -> tiny(Path.of(args[1])).toNamed(Path.of(args[2]), Path.of(args[3]));
            case "mcp" -> cheatBreaker(Files.readAllLines(Path.of(args[1]))).toMcp(Path.of(args[2]),
                    Path.of(args[3]));
            case "intermediary" -> {
                Remap remap = tiny(Path.of(args[1]));
                remap.learnJar(Path.of(args[2]));
                write(remap.rename(readClasses(Path.of(args[3])), Map.of(), false), Path.of(args[4]));
            }
            case "cheatbreaker" -> {
                List<Path> inputs = Stream.of(args[3].split(",")).map(Path::of).toList();
                Map<String, byte[]> rewritten = new HashMap<>();
                for (Path set : inputs.subList(1, inputs.size())) {
                    rewritten.putAll(readClasses(set));
                }
                Map<String, byte[]> overlay;
                try (ZipFile game = new ZipFile(args[2])) {
                    overlay = cheatBreaker(Files.readAllLines(Path.of(args[1])))
                            .toCheatBreaker(game, readClasses(inputs.get(0)), rewritten);
                }
                write(overlay, Path.of(args[4]));
            }
            case "hooks" -> {
                List<String> table = tableRows(args[3], args[4]);
                List<String> problems = new ArrayList<>();
                List<String> lines;
                try (ZipFile game = new ZipFile(args[2])) {
                    lines = cheatBreaker(Files.readAllLines(Path.of(args[1]))).hooks(game, table, problems);
                }
                problems.forEach(System.err::println);
                if (!problems.isEmpty()) {
                    System.exit(1);
                }
                writeLines(lines, Path.of(args[5]));
                writeLines(table, Path.of(args[6]));
            }
            default -> throw new IllegalArgumentException("unknown direction " + args[0]);
        }
    }

    private static Remap tiny(Path yarnJar) throws IOException {
        List<String> tiny;
        try (ZipFile yarn = new ZipFile(yarnJar.toFile())) {
            tiny = new String(yarn.getInputStream(yarn.getEntry("mappings/mappings.tiny")).readAllBytes(),
                    StandardCharsets.UTF_8).lines().collect(Collectors.toList());
        }
        if (!tiny.get(0).startsWith("tiny\t2\t0\tintermediary\tnamed")) {
            throw new IllegalArgumentException("expected tiny v2 intermediary -> named");
        }
        Remap remap = new Remap();
        List<String[]> members = new ArrayList<>();
        String owner = null;
        for (String line : tiny.subList(1, tiny.size())) {
            String[] parts = line.split("\t", -1);
            if (parts[0].equals("c")) {
                owner = parts[1];
                remap.classesToNamed.put(parts[1], parts[2]);
                remap.classesToRuntime.put(parts[2], parts[1]);
            } else if (parts.length == 5 && parts[0].isEmpty() && (parts[1].equals("m") || parts[1].equals("f"))) {
                members.add(new String[] {owner, parts[2], parts[3], parts[4]});
            }
        }
        for (String[] member : members) {
            remap.membersToNamed.put(member[2], member[3]);
            String namedOwner = remap.classesToNamed.get(member[0]);
            String namedDescriptor = mapDescriptor(member[1], remap.classesToNamed);
            remap.membersToRuntime.computeIfAbsent(namedOwner, key -> new HashMap<>())
                    .put(member[3] + namedDescriptor, member[2]);
        }
        return remap;
    }

    /** From names.tsv's lines; see tools/CbMap.java. */
    public static Remap cheatBreaker(List<String> names) {
        Remap remap = new Remap();
        List<String[]> members = new ArrayList<>();
        for (String line : names) {
            String[] parts = line.split("\t");
            if (parts[0].equals("CL")) {
                remap.classesToRuntime.put(parts[1], parts[2]);
                remap.classesToNamed.put(parts[2], parts[1]);
            } else {
                members.add(parts);
            }
        }
        for (String[] member : members) {
            remap.membersToRuntime.computeIfAbsent(member[1], key -> new HashMap<>())
                    .put(member[2] + member[3], member[4]);
            remap.membersToNamed.put(remap.classesToRuntime.getOrDefault(member[1], member[1]) + "." + member[4]
                    + mapDescriptor(member[3], remap.classesToRuntime), member[2]);
        }
        return remap;
    }

    private static String mapDescriptor(String descriptor, Map<String, String> classes) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < descriptor.length(); ++index) {
            char c = descriptor.charAt(index);
            out.append(c);
            if (c == 'L') {
                int end = descriptor.indexOf(';', index);
                String name = descriptor.substring(index + 1, end);
                out.append(classes.getOrDefault(name, name)).append(';');
                index = end;
            }
        }
        return out.toString();
    }

    /** CheatBreaker's names to MCP's. */
    private Remapper namedRemapper() {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return classesToNamed.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return membersToNamed.getOrDefault(owner + "." + name + descriptor, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return membersToNamed.getOrDefault(owner + "." + name + descriptor, name);
            }
        };
    }

    private void toMcp(Path runtimeJar, Path mcpJar) throws IOException {
        Remapper remapper = namedRemapper();
        /*
         * CheatBreaker's classes are all top level, and javac only finds a
         * nested class through its outer class's InnerClasses entry, so the
         * named member classes get their entries back, as static ones.
         */
        Map<String, Integer> nested = new HashMap<>();
        try (ZipFile zip = new ZipFile(runtimeJar.toFile())) {
            for (ZipEntry entry : zip.stream().filter(e -> e.getName().endsWith(".class")).collect(Collectors.toList())) {
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(in.readAllBytes());
                    String name = remapper.map(reader.getClassName());
                    int dollar = name.lastIndexOf('$');
                    if (dollar > 0 && !Character.isDigit(name.charAt(dollar + 1))) {
                        nested.put(name, reader.getAccess() & ~Opcodes.ACC_SUPER | Opcodes.ACC_STATIC);
                    }
                }
            }
        }
        writeSignatures(runtimeJar, mcpJar, remapper, next -> new ClassVisitor(Opcodes.ASM9, next) {
            private String name;

            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                this.name = name;
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public void visitEnd() {
                nested.forEach((inner, access) -> {
                    String outer = inner.substring(0, inner.lastIndexOf('$'));
                    if (inner.equals(name) || outer.equals(name)) {
                        super.visitInnerClass(inner, outer, inner.substring(outer.length() + 1), access);
                    }
                });
                super.visitEnd();
            }
        });
    }

    private void toNamed(Path intermediaryJar, Path namedJar) throws IOException {
        writeSignatures(intermediaryJar, namedJar, new Remapper() {
            @Override
            public String map(String internalName) {
                return classesToNamed.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return membersToNamed.getOrDefault(name, name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                return membersToNamed.getOrDefault(name, name);
            }

            @Override
            public String mapRecordComponentName(String owner, String name, String descriptor) {
                return membersToNamed.getOrDefault(name, name);
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                return membersToNamed.getOrDefault(name, name);
            }
        }, UnaryOperator.identity());
    }

    /** The jar's classes without code under the remapper's names, each through {@code named} before it is written. */
    private static void writeSignatures(Path runtimeJar, Path namedJar, Remapper remapper,
                                        UnaryOperator<ClassVisitor> named) throws IOException {
        Path partial = namedJar.resolveSibling(namedJar.getFileName() + ".part");
        try (ZipFile zip = new ZipFile(runtimeJar.toFile());
             OutputStream os = Files.newOutputStream(partial);
             ZipOutputStream zout = new ZipOutputStream(os)) {
            for (ZipEntry entry : zip.stream().collect(Collectors.toList())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(0);
                /* Without code the classes say nothing a Java 17 compiler cannot read, and the client builds with one. */
                ClassVisitor release17 = new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public void visit(int version, int access, String name, String signature, String superName,
                                      String[] interfaces) {
                        super.visit(Math.min(version, Opcodes.V17), access, name, signature, superName, interfaces);
                    }
                };
                reader.accept(new ClassRemapper(named.apply(release17), remapper), ClassReader.SKIP_CODE);
                String name = remapper.map(reader.getClassName());
                zout.putNextEntry(new ZipEntry(name + ".class"));
                zout.write(writer.toByteArray());
                zout.closeEntry();
            }
        }
        Files.move(partial, namedJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void learnJar(Path namedJar) throws IOException {
        try (ZipFile zip = new ZipFile(namedJar.toFile())) {
            for (ZipEntry entry : zip.stream().collect(Collectors.toList())) {
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        learn(new ClassReader(in.readAllBytes()));
                    }
                }
            }
        }
    }

    /** CheatBreaker's hierarchy, read back under MCP's names, which the client's references are resolved in. */
    private void learnCheatBreaker(ZipFile game) throws IOException {
        Remapper named = namedRemapper();
        for (ZipEntry entry : Collections.list(game.entries())) {
            if (entry.getName().endsWith(".class")) {
                ClassWriter writer = new ClassWriter(0);
                try (InputStream in = game.getInputStream(entry)) {
                    new ClassReader(in.readAllBytes()).accept(new ClassRemapper(writer, named), ClassReader.SKIP_CODE);
                }
                learn(new ClassReader(writer.toByteArray()));
            }
        }
    }

    /**
     * The overlay for CheatBreaker's game, by class path: every class of
     * {@code classes} that has anything renamed, and every class of
     * {@code rewritten}, which stands in for the class of {@code classes} at
     * the same path.
     */
    public Map<String, byte[]> toCheatBreaker(ZipFile game, Map<String, byte[]> classes,
                                              Map<String, byte[]> rewritten) throws IOException {
        learnCheatBreaker(game);
        return rename(classes, rewritten, true);
    }

    private Map<String, byte[]> rename(Map<String, byte[]> classes, Map<String, byte[]> rewritten,
                                       boolean changedOnly) {
        Map<String, ClassReader> readers = new HashMap<>();
        classes.forEach((path, bytes) -> readers.put(path, new ClassReader(bytes)));
        rewritten.forEach((path, bytes) -> readers.put(path, new ClassReader(bytes)));
        readers.values().forEach(this::learn);
        Remapper remapper = runtimeRemapper();
        Map<String, byte[]> out = new HashMap<>();
        for (Map.Entry<String, ClassReader> entry : readers.entrySet()) {
            ClassWriter writer = new ClassWriter(0);
            renamed = false;
            entry.getValue().accept(new ClassRemapper(Opcodes.ASM9, writer, remapper) {}, 0);
            if (!changedOnly || renamed || rewritten.containsKey(entry.getKey())) {
                out.put(entry.getKey(), writer.toByteArray());
            }
        }
        return out;
    }

    /** Every class under {@code root}, by its path relative to it. */
    private static Map<String, byte[]> readClasses(Path root) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList())) {
                classes.put(root.relativize(file).toString().replace(File.separatorChar, '/'),
                        Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static void write(Map<String, byte[]> classes, Path output) throws IOException {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Path target = output.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
    }

    private static void writeLines(List<String> lines, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        Files.writeString(output, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private Remapper runtimeRemapper() {
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return renamedTo(internalName, classesToRuntime.getOrDefault(internalName, internalName));
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String found = declared(owner, name + descriptor);
                return renamedTo(name, found != null ? found : name);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String found = declared(owner, name + descriptor);
                return renamedTo(name, found != null ? found : name);
            }

            /* A lambda's name is the method it implements on the interface its call site returns. */
            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                String target = Type.getReturnType(descriptor).getInternalName();
                List<String> candidates = abstractMethod(target, name);
                if (candidates.size() > 1) {
                    throw new IllegalStateException(target + "." + name + " is ambiguous for a lambda");
                }
                return candidates.isEmpty() ? name : mapMethodName(target, name, candidates.get(0));
            }
        };
    }

    private String renamedTo(String before, String after) {
        renamed |= !before.equals(after);
        return after;
    }

    /**
     * {@code tableClass}'s {@code ALL}, one hook per line: owner, method,
     * descriptor, then where it goes (kind, opcode, owner, name, descriptor,
     * ordinal; a dash for HEAD and RETURN), the handler and its flags: s(elf),
     * a(rgs), c(ancellable), r(esult).
     */
    private static List<String> tableRows(String classpath, String tableClass)
            throws IOException, ReflectiveOperationException {
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        List<?> hooks;
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), Remap.class.getClassLoader())) {
            hooks = (List<?>) Class.forName(tableClass, true, loader).getField("ALL").get(null);
        }
        List<String> rows = new ArrayList<>();
        for (Object hook : hooks) {
            Object at = component(hook, "at");
            String kind = component(at, "kind").toString();
            List<String> row = new ArrayList<>(List.of(component(hook, "owner"), component(hook, "method"),
                    component(hook, "descriptor"), kind));
            if (kind.equals("HEAD") || kind.equals("RETURN")) {
                row.addAll(List.of("-", "-", "-", "-", "-"));
            } else {
                row.addAll(List.of(String.valueOf((int) component(at, "opcode")), component(at, "owner"),
                        component(at, "name"), component(at, "descriptor"),
                        String.valueOf((int) component(at, "ordinal"))));
            }
            String flags = ((boolean) component(hook, "self") ? "s" : "") + ((boolean) component(hook, "args") ? "a" : "")
                    + ((boolean) component(hook, "cancellable") ? "c" : "") + ((boolean) component(hook, "result") ? "r" : "");
            row.add(component(hook, "handler"));
            row.add(flags.isEmpty() ? "-" : flags);
            rows.add(String.join("\t", row));
        }
        return rows;
    }

    /**
     * The hooks of {@code table}, written under MCP's names, under
     * CheatBreaker's. A hook on method {@code *} goes in the one method of its
     * class with that descriptor that no vanilla method matches: one
     * CheatBreaker added, or reworked so far that vanilla's has no match. A
     * hook that names a game member
     * CheatBreaker's names do not cover, or whose method or anchor is not in
     * CheatBreaker's game, is left out, and why goes to {@code problems}.
     */
    public List<String> hooks(ZipFile game, List<String> table, List<String> problems) throws IOException {
        learnCheatBreaker(game);
        Remapper remapper = runtimeRemapper();
        List<String> lines = new ArrayList<>();
        for (String row : table) {
            String[] column = row.split("\t");
            String hook = column[9] + ": ";
            List<String> unmapped = new ArrayList<>();
            String owner = column[0];
            requireClass(owner, hook, unmapped);
            String[] line = column.clone();
            line[0] = remapper.map(owner);
            line[2] = remapper.mapMethodDesc(column[2]);
            line[1] = column[1].equals("*") ? onlyMethod(game, line[0], line[2], hook, unmapped)
                    : mapped(remapper.mapMethodName(owner, column[1], column[2]), column[1], owner, hook, unmapped);
            if (!column[4].equals("-")) {
                int opcode = Integer.parseInt(column[4]);
                String atOwner = column[5];
                requireClass(atOwner, hook, unmapped);
                boolean field = opcode >= Opcodes.GETSTATIC && opcode <= Opcodes.PUTFIELD;
                String name = field ? remapper.mapFieldName(atOwner, column[6], column[7])
                        : remapper.mapMethodName(atOwner, column[6], column[7]);
                line[5] = remapper.map(atOwner);
                line[6] = mapped(name, column[6], atOwner, hook, unmapped);
                line[7] = field ? remapper.mapDesc(column[7]) : remapper.mapMethodDesc(column[7]);
            }
            String missing = unmapped.isEmpty() ? missingAnchor(game, line) : null;
            if (!unmapped.isEmpty()) {
                problems.addAll(unmapped);
            } else if (missing != null) {
                problems.add(missing);
            } else {
                lines.add(String.join("\t", line));
            }
        }
        return lines;
    }

    /** The name of {@code owner}'s one method of its own with that descriptor, or "*" with the problem noted. */
    private String onlyMethod(ZipFile game, String owner, String descriptor, String hook, List<String> problems)
            throws IOException {
        ClassNode node = gameClass(game, owner);
        List<String> names = node == null ? List.of() : node.methods.stream()
                .filter(method -> method.desc.equals(descriptor)
                        && !membersToNamed.containsKey(owner + "." + method.name + method.desc))
                .map(method -> method.name).toList();
        if (names.size() != 1) {
            problems.add(hook + names.size() + " methods " + descriptor + " in " + owner + ", where * needs one");
            return "*";
        }
        return names.get(0);
    }

    private static ClassNode gameClass(ZipFile game, String name) throws IOException {
        ZipEntry entry = game.getEntry(name + ".class");
        if (entry == null) {
            return null;
        }
        ClassNode node = new ClassNode();
        try (InputStream in = game.getInputStream(entry)) {
            new ClassReader(in.readAllBytes()).accept(node, 0);
        }
        return node;
    }

    /** Why the hook in {@code column} would be skipped in game, or null when it applies. */
    private static String missingAnchor(ZipFile game, String[] column) throws IOException {
        String hook = column[9] + ": ";
        ClassNode node = gameClass(game, column[0]);
        if (node == null) {
            return hook + "no class " + column[0];
        }
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals(column[1]) && candidate.desc.equals(column[2]))
                .findFirst().orElse(null);
        if (method == null) {
            return hook + "no method " + column[0] + "." + column[1] + column[2];
        }
        if (column[4].equals("-")) {
            return null;
        }
        int opcode = Integer.parseInt(column[4]);
        int seen = 0;
        for (AbstractInsnNode insn : method.instructions) {
            boolean invoke = insn instanceof MethodInsnNode call && call.owner.equals(column[5])
                    && call.name.equals(column[6]) && call.desc.equals(column[7]);
            boolean access = insn instanceof FieldInsnNode field && field.owner.equals(column[5])
                    && field.name.equals(column[6]) && field.desc.equals(column[7]);
            if (insn.getOpcode() == opcode && (invoke || access)) {
                ++seen;
            }
        }
        int ordinal = Integer.parseInt(column[8]);
        return seen > Math.max(ordinal, 0) ? null
                : hook + seen + " of " + column[5] + "." + column[6] + column[7] + " in " + column[1] + ", needs #" + ordinal;
    }

    private void requireClass(String owner, String hook, List<String> unmapped) {
        if (owner.startsWith("net/minecraft/") && !classesToRuntime.containsKey(owner)) {
            unmapped.add(hook + "no CheatBreaker name for " + owner);
        }
    }

    /** A member of a game class that kept its MCP name has no CheatBreaker counterpart. */
    private String mapped(String runtime, String named, String owner, String hook, List<String> unmapped) {
        if (runtime.equals(named) && classesToRuntime.containsKey(owner) && !named.startsWith("<")) {
            unmapped.add(hook + "no CheatBreaker name for " + owner + "." + named);
        }
        return runtime;
    }

    @SuppressWarnings("unchecked")
    private static <T> T component(Object record, String name) throws ReflectiveOperationException {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                return (T) component.getAccessor().invoke(record);
            }
        }
        throw new NoSuchMethodException(record.getClass().getName() + "." + name);
    }

    private void learn(ClassReader reader) {
        String name = reader.getClassName();
        String superName = reader.getSuperName();
        String[] parents = reader.getInterfaces();
        String[] all = new String[parents.length + (superName != null ? 1 : 0)];
        System.arraycopy(parents, 0, all, 0, parents.length);
        if (superName != null) {
            all[parents.length] = superName;
        }
        supertypes.put(name, all);
        boolean isInterface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
        interfaces.put(name, isInterface);
        if (isInterface) {
            Map<String, List<String>> methods = new HashMap<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String method, String descriptor,
                                                                   String signature, String[] exceptions) {
                    if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                        methods.computeIfAbsent(method, key -> new ArrayList<>()).add(descriptor);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE);
            abstractMethods.put(name, methods);
        }
    }

    /** The runtime name of the game member that {@code owner}'s hierarchy resolves {@code key} to. */
    private String declared(String owner, String key) {
        Map<String, String> members = membersToRuntime.get(owner);
        if (members != null && members.containsKey(key)) {
            return members.get(key);
        }
        for (String parent : supertypes.getOrDefault(owner, new String[0])) {
            String found = declared(parent, key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private List<String> abstractMethod(String owner, String name) {
        List<String> found = new ArrayList<>(abstractMethods.getOrDefault(owner, Map.of()).getOrDefault(name, List.of()));
        for (String parent : supertypes.getOrDefault(owner, new String[0])) {
            if (interfaces.getOrDefault(parent, false)) {
                for (String descriptor : abstractMethod(parent, name)) {
                    if (!found.contains(descriptor)) {
                        found.add(descriptor);
                    }
                }
            }
        }
        return found;
    }
}
