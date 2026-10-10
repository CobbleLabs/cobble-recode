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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * MCP and CheatBreaker's for CheatBreaker 1.8.9.
 *
 * <pre>
 *   named        yarn.jar intermediary.jar named.jar
 *   intermediary yarn.jar named.jar classes-in classes-out
 *   cheatbreaker names.tsv mcp.jar classes-in classes-out
 *   hooks        names.tsv mcp.jar cheatbreaker.jar classpath table-class hooks.tsv
 * </pre>
 *
 * The first writes the game jar the 1.21 sources compile against. Every
 * intermediary member name is unique across the whole game, so that direction
 * needs no hierarchy. The second and third put compiled client classes under
 * the names the game runs with. Yarn and MCP names repeat, so a member
 * reference is resolved up the hierarchy of the named jar to the game class
 * that declares it, and only then renamed; references to anything else are
 * left alone. CheatBreaker's classes go to an overlay, so only the classes
 * that change are written. The last puts the hooks of {@code table-class}'s
 * {@code ALL} under CheatBreaker's names, one per line, for its adapter to
 * read; see tools/CbMap.java for names.tsv.
 *
 * Usage: java -cp asm.jar;asm-commons.jar;asm-tree.jar tools/Remap.java ...
 */
public final class Remap {
    private final Map<String, String> classesToNamed = new HashMap<>();
    private final Map<String, String> classesToRuntime = new HashMap<>();
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
            case "intermediary" -> tiny(Path.of(args[1])).toRuntime(Path.of(args[2]), Path.of(args[3]),
                    Path.of(args[4]), false);
            case "cheatbreaker" -> cheatBreaker(Path.of(args[1])).toRuntime(Path.of(args[2]), Path.of(args[3]),
                    Path.of(args[4]), true);
            case "hooks" -> cheatBreaker(Path.of(args[1])).hooks(Path.of(args[2]), Path.of(args[3]), args[4],
                    args[5], Path.of(args[6]));
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

    private static Remap cheatBreaker(Path names) throws IOException {
        Remap remap = new Remap();
        for (String line : Files.readAllLines(names)) {
            String[] parts = line.split("\t");
            if (parts[0].equals("CL")) {
                remap.classesToRuntime.put(parts[1], parts[2]);
            } else {
                remap.membersToRuntime.computeIfAbsent(parts[1], key -> new HashMap<>())
                        .put(parts[2] + parts[3], parts[4]);
            }
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

    private void toNamed(Path intermediaryJar, Path namedJar) throws IOException {
        Remapper remapper = new Remapper() {
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
        };
        Path partial = namedJar.resolveSibling(namedJar.getFileName() + ".part");
        try (ZipFile zip = new ZipFile(intermediaryJar.toFile());
             OutputStream os = Files.newOutputStream(partial);
             ZipOutputStream zout = new ZipOutputStream(os)) {
            for (ZipEntry entry : zip.stream().collect(Collectors.toList())) {
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
                reader.accept(new ClassRemapper(release17, remapper), ClassReader.SKIP_CODE);
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

    private void toRuntime(Path namedJar, Path input, Path output, boolean changedOnly) throws IOException {
        learnJar(namedJar);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(input)) {
            files = walk.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList());
        }
        Map<Path, ClassReader> readers = new HashMap<>();
        for (Path file : files) {
            ClassReader reader = new ClassReader(Files.readAllBytes(file));
            learn(reader);
            readers.put(file, reader);
        }
        Remapper remapper = runtimeRemapper();
        for (Map.Entry<Path, ClassReader> entry : readers.entrySet()) {
            ClassWriter writer = new ClassWriter(0);
            renamed = false;
            entry.getValue().accept(new ClassRemapper(Opcodes.ASM9, writer, remapper) {}, 0);
            if (changedOnly && !renamed) {
                continue;
            }
            Path target = output.resolve(input.relativize(entry.getKey()));
            Files.createDirectories(target.getParent());
            Files.write(target, writer.toByteArray());
        }
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
     * Writes each hook as owner, method, descriptor, then where it goes (kind,
     * opcode, owner, name, descriptor, ordinal; a dash for HEAD and RETURN),
     * the handler and its flags: s(elf), a(rgs), c(ancellable), r(esult).
     * Fails when a hook names a game member CheatBreaker's names do not cover,
     * or when its method or anchor is not in CheatBreaker's game, which would
     * only be found in game otherwise.
     */
    private void hooks(Path namedJar, Path runtimeJar, String classpath, String tableClass, Path output)
            throws IOException, ReflectiveOperationException {
        learnJar(namedJar);
        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            urls.add(Path.of(entry).toUri().toURL());
        }
        List<?> hooks;
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), Remap.class.getClassLoader())) {
            hooks = (List<?>) Class.forName(tableClass, true, loader).getField("ALL").get(null);
        }
        Remapper remapper = runtimeRemapper();
        List<String> lines = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        for (Object hook : hooks) {
            String owner = component(hook, "owner");
            String method = component(hook, "method");
            String descriptor = component(hook, "descriptor");
            Object at = component(hook, "at");
            String kind = component(at, "kind").toString();
            String handler = component(hook, "handler");
            requireClass(owner, unmapped);
            List<String> line = new ArrayList<>(List.of(remapper.map(owner),
                    mapped(remapper.mapMethodName(owner, method, descriptor), method, owner, unmapped),
                    remapper.mapMethodDesc(descriptor), kind));
            if (kind.equals("HEAD") || kind.equals("RETURN")) {
                line.addAll(List.of("-", "-", "-", "-", "-"));
            } else {
                int opcode = component(at, "opcode");
                String atOwner = component(at, "owner");
                String atName = component(at, "name");
                String atDescriptor = component(at, "descriptor");
                requireClass(atOwner, unmapped);
                boolean field = opcode >= Opcodes.GETSTATIC && opcode <= Opcodes.PUTFIELD;
                String name = field ? remapper.mapFieldName(atOwner, atName, atDescriptor)
                        : remapper.mapMethodName(atOwner, atName, atDescriptor);
                line.addAll(List.of(String.valueOf(opcode), remapper.map(atOwner),
                        mapped(name, atName, atOwner, unmapped),
                        field ? remapper.mapDesc(atDescriptor) : remapper.mapMethodDesc(atDescriptor),
                        String.valueOf((int) component(at, "ordinal"))));
            }
            String flags = ((boolean) component(hook, "self") ? "s" : "") + ((boolean) component(hook, "args") ? "a" : "")
                    + ((boolean) component(hook, "cancellable") ? "c" : "") + ((boolean) component(hook, "result") ? "r" : "");
            line.add(handler);
            line.add(flags.isEmpty() ? "-" : flags);
            lines.add(String.join("\t", line));
        }
        if (!unmapped.isEmpty()) {
            unmapped.forEach(name -> System.err.println("no CheatBreaker name for " + name));
            System.exit(1);
        }
        List<String> missing = new ArrayList<>();
        try (ZipFile game = new ZipFile(runtimeJar.toFile())) {
            for (String line : lines) {
                String problem = missingAnchor(game, line.split("	"));
                if (problem != null) {
                    missing.add(problem);
                }
            }
        }
        if (!missing.isEmpty()) {
            missing.forEach(System.err::println);
            System.exit(1);
        }
        Files.createDirectories(output.getParent());
        Files.writeString(output, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    /** Why the hook in {@code column} would be skipped in game, or null when it applies. */
    private static String missingAnchor(ZipFile game, String[] column) throws IOException {
        String hook = column[9] + ": ";
        ZipEntry entry = game.getEntry(column[0] + ".class");
        if (entry == null) {
            return hook + "no class " + column[0];
        }
        ClassNode node = new ClassNode();
        try (InputStream in = game.getInputStream(entry)) {
            new ClassReader(in.readAllBytes()).accept(node, 0);
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

    private void requireClass(String owner, List<String> unmapped) {
        if (owner.startsWith("net/minecraft/") && !classesToRuntime.containsKey(owner)) {
            unmapped.add(owner);
        }
    }

    /** A member of a game class that kept its MCP name has no CheatBreaker counterpart. */
    private String mapped(String runtime, String named, String owner, List<String> unmapped) {
        if (runtime.equals(named) && classesToRuntime.containsKey(owner) && !named.startsWith("<")) {
            unmapped.add(owner + "." + named);
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
