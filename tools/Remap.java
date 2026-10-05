import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

/**
 * Moves classes between Fabric's intermediary names, which the modern
 * versions run under, and Yarn's, which the client is written in.
 *
 * <pre>
 *   named        yarn.jar intermediary.jar named.jar
 *   intermediary yarn.jar named.jar classes-in classes-out
 * </pre>
 *
 * The first writes the game jar the 1.21 sources compile against. Every
 * intermediary member name is unique across the whole game, so that direction
 * needs no hierarchy. The second puts compiled client classes back under the
 * names the game runs with. Yarn names repeat, so a member reference is
 * resolved up the hierarchy of the named jar to the game class that declares
 * it, and only then renamed; references to anything else are left alone.
 *
 * Usage: java -cp asm.jar;asm-commons.jar;asm-tree.jar tools/Remap.java ...
 */
public final class Remap {
    private final Map<String, String> classesToNamed = new HashMap<>();
    private final Map<String, String> classesToIntermediary = new HashMap<>();
    private final Map<String, String> membersToNamed = new HashMap<>();
    /** Named class, then named member name and descriptor, to the intermediary name. */
    private final Map<String, Map<String, String>> membersToIntermediary = new HashMap<>();
    private final Map<String, String[]> supertypes = new HashMap<>();
    private final Map<String, Boolean> interfaces = new HashMap<>();
    private final Map<String, Map<String, List<String>>> abstractMethods = new HashMap<>();

    public static void main(String[] args) throws IOException {
        List<String> tiny;
        try (ZipFile yarn = new ZipFile(args[1])) {
            tiny = new String(yarn.getInputStream(yarn.getEntry("mappings/mappings.tiny")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).lines().collect(Collectors.toList());
        }
        Remap remap = new Remap(tiny);
        switch (args[0]) {
            case "named" -> remap.toNamed(Path.of(args[2]), Path.of(args[3]));
            case "intermediary" -> remap.toIntermediary(Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
            default -> throw new IllegalArgumentException("unknown direction " + args[0]);
        }
    }

    private Remap(List<String> tiny) {
        if (!tiny.get(0).startsWith("tiny\t2\t0\tintermediary\tnamed")) {
            throw new IllegalArgumentException("expected tiny v2 intermediary -> named");
        }
        List<String[]> members = new ArrayList<>();
        String owner = null;
        for (String line : tiny.subList(1, tiny.size())) {
            String[] parts = line.split("\t", -1);
            if (parts[0].equals("c")) {
                owner = parts[1];
                classesToNamed.put(parts[1], parts[2]);
                classesToIntermediary.put(parts[2], parts[1]);
            } else if (parts.length == 5 && parts[0].isEmpty() && (parts[1].equals("m") || parts[1].equals("f"))) {
                members.add(new String[] {owner, parts[2], parts[3], parts[4]});
            }
        }
        for (String[] member : members) {
            membersToNamed.put(member[2], member[3]);
            String namedOwner = classesToNamed.get(member[0]);
            String namedDescriptor = mapDescriptor(member[1], classesToNamed);
            membersToIntermediary.computeIfAbsent(namedOwner, key -> new HashMap<>())
                    .put(member[3] + namedDescriptor, member[2]);
        }
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

    private void toIntermediary(Path namedJar, Path input, Path output) throws IOException {
        try (ZipFile zip = new ZipFile(namedJar.toFile())) {
            for (ZipEntry entry : zip.stream().collect(Collectors.toList())) {
                try (InputStream in = zip.getInputStream(entry)) {
                    learn(new ClassReader(in.readAllBytes()));
                }
            }
        }
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
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return classesToIntermediary.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String found = declared(owner, name + descriptor);
                return found != null ? found : name;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String found = declared(owner, name + descriptor);
                return found != null ? found : name;
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
        for (Map.Entry<Path, ClassReader> entry : readers.entrySet()) {
            ClassWriter writer = new ClassWriter(0);
            entry.getValue().accept(new ClassRemapper(Opcodes.ASM9, writer, remapper) {}, 0);
            Path target = output.resolve(input.relativize(entry.getKey()));
            Files.createDirectories(target.getParent());
            Files.write(target, writer.toByteArray());
        }
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

    /** The intermediary name of the game member that {@code owner}'s hierarchy resolves {@code key} to. */
    private String declared(String owner, String key) {
        Map<String, String> members = membersToIntermediary.get(owner);
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
