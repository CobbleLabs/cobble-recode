import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

/**
 * Decides, for every Minecraft version, which of the client's classes can run
 * there. A class links when every class, field and method it names resolves
 * against that version's game and libraries the way the JVM would resolve it
 * (owner kind, static or not, accessible), and every client class it names
 * links too. The client image carries all versions' code at once, so this is
 * what keeps 1.8.9 code from ever being reached on 1.21.11 and the other way
 * round, and what lets code written once run wherever it happens to link.
 *
 * For each version it writes the modules that link to cobble/modules/<id>.txt
 * in the image, and every class that does not, with why, to a report. A
 * version whose adapter does not link fails the build. Classes under
 * versions/<id>/ in the image stand in for the image's own on that version.
 *
 * Usage: java -cp asm.jar;asm-tree.jar tools/Linkage.java <image dir> <report dir>
 *            <version id>=<adapter package>=<jar>,<jar>... ...
 */
public final class Linkage {
    private static final String CLIENT = "cobble/";
    private static final String MODULE = "cobble/module/Module";
    /** Classes rewritten for one version, which replace the image's own there; see tools/Retarget.java. */
    private static final String VERSIONS = "versions";

    private final Map<String, ClassNode> image;
    private final Map<String, ClassNode> world = new HashMap<>();

    private Linkage(Map<String, ClassNode> image) {
        this.image = image;
    }

    public static void main(String[] args) throws IOException {
        Path imageDir = Path.of(args[0]);
        Path reports = Path.of(args[1]);
        Files.createDirectories(reports);
        Map<String, ClassNode> base = readDirectory(imageDir, VERSIONS);
        boolean failed = false;
        for (int index = 2; index < args.length; ++index) {
            String[] spec = args[index].split("=", 3);
            Map<String, ClassNode> image = new HashMap<>(base);
            Path overlay = imageDir.resolve(VERSIONS).resolve(spec[0]);
            if (Files.isDirectory(overlay)) {
                image.putAll(readDirectory(overlay, null));
            }
            Linkage linkage = new Linkage(image);
            for (String jar : spec[2].split(",")) {
                linkage.readJar(Path.of(jar));
            }
            failed |= !linkage.check(spec[0], spec[1], imageDir, reports);
        }
        if (failed) {
            System.exit(1);
        }
    }

    private boolean check(String version, String adapterPackage, Path imageDir, Path reports) throws IOException {
        Map<String, Set<String>> problems = new TreeMap<>();
        Map<String, Set<String>> uses = new HashMap<>();
        for (ClassNode node : image.values()) {
            if (node.name.startsWith(CLIENT)) {
                Set<String> found = new LinkedHashSet<>();
                Set<String> used = new TreeSet<>();
                new Checker(node, found, used).run();
                problems.put(node.name, found);
                uses.put(node.name, used);
            }
        }
        Map<String, String> brokenBy = new TreeMap<>();
        problems.forEach((name, found) -> {
            if (!found.isEmpty()) {
                brokenBy.put(name, null);
            }
        });
        for (boolean changed = true; changed; ) {
            changed = false;
            for (Map.Entry<String, Set<String>> entry : uses.entrySet()) {
                if (brokenBy.containsKey(entry.getKey())) {
                    continue;
                }
                for (String used : entry.getValue()) {
                    if (brokenBy.containsKey(used)) {
                        brokenBy.put(entry.getKey(), used);
                        changed = true;
                        break;
                    }
                }
            }
        }

        List<String> modules = new ArrayList<>();
        for (ClassNode node : image.values()) {
            if (node.name.startsWith(CLIENT) && !brokenBy.containsKey(node.name) && isModule(node)) {
                modules.add(node.name.replace('/', '.'));
            }
        }
        modules.sort(null);
        Path index = imageDir.resolve("cobble/modules/" + version + ".txt");
        Files.createDirectories(index.getParent());
        Files.write(index, modules, StandardCharsets.UTF_8);

        List<String> report = new ArrayList<>();
        brokenBy.forEach((name, cause) -> {
            if (cause != null) {
                report.add(name + "  (through " + cause + ")");
            } else {
                report.add(name);
                problems.get(name).forEach(problem -> report.add("    " + problem));
            }
        });
        Files.write(reports.resolve("linkage-" + version + ".txt"), report, StandardCharsets.UTF_8);

        String adapter = "cobble/" + adapterPackage + "/PlatformAdapter";
        long clientClasses = problems.size();
        System.out.printf("%s: %d of %d client classes link, %d modules%n", version,
                clientClasses - brokenBy.size(), clientClasses, modules.size());
        if (!image.containsKey(adapter) || brokenBy.containsKey(adapter)) {
            System.out.printf("%s: %s does not link; see %s%n", version, adapter,
                    reports.resolve("linkage-" + version + ".txt"));
            return false;
        }
        return true;
    }

    private boolean isModule(ClassNode node) {
        if ((node.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
            return false;
        }
        boolean constructor = node.methods.stream().anyMatch(method -> method.name.equals("<init>")
                && method.desc.equals("()V") && (method.access & Opcodes.ACC_PUBLIC) != 0);
        for (String parent = node.superName; constructor && parent != null; ) {
            if (parent.equals(MODULE)) {
                return true;
            }
            ClassNode superNode = image.get(parent);
            parent = superNode != null ? superNode.superName : null;
        }
        return false;
    }

    private ClassNode lookup(String name) {
        if (name.startsWith("[")) {
            name = "java/lang/Object";
        }
        ClassNode node = image.get(name);
        if (node == null) {
            node = world.get(name);
        }
        if (node == null && (name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/")
                || name.startsWith("sun/"))) {
            node = system(name);
        }
        return node;
    }

    private ClassNode system(String name) {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            if (in == null) {
                return null;
            }
            ClassNode node = read(in.readAllBytes(), ClassReader.SKIP_CODE);
            world.put(name, node);
            return node;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean samePackage(String a, String b) {
        int slashA = a.lastIndexOf('/');
        int slashB = b.lastIndexOf('/');
        return slashA == slashB && a.regionMatches(0, b, 0, Math.max(slashA, 0));
    }

    private final class Checker {
        private final ClassNode node;
        private final Set<String> problems;
        private final Set<String> uses;

        Checker(ClassNode node, Set<String> problems, Set<String> uses) {
            this.node = node;
            this.problems = problems;
            this.uses = uses;
        }

        void run() {
            type(node.superName);
            node.interfaces.forEach(this::type);
            for (FieldNode field : node.fields) {
                descriptor(field.desc);
            }
            for (MethodNode method : node.methods) {
                descriptor(method.desc);
                for (TryCatchBlockNode block : method.tryCatchBlocks) {
                    type(block.type);
                }
                for (AbstractInsnNode insn : method.instructions) {
                    instruction(insn);
                }
            }
            if (problems.isEmpty()) {
                verify();
            }
        }

        /*
         * What every reference resolving does not catch: a value of one type
         * where a member takes another, which a shim with the wrong signature
         * leaves behind. The game's verifier refuses the class when it loads,
         * and the client with it, so it is found here instead.
         */
        private void verify() {
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    continue;
                }
                try {
                    new Analyzer<>(new Hierarchy(node)).analyze(node.name, method);
                } catch (AnalyzerException e) {
                    problems.add("does not verify: " + method.name + method.desc + ": " + e.getMessage());
                }
            }
        }

        private void instruction(AbstractInsnNode insn) {
            if (insn instanceof MethodInsnNode call) {
                method(call.owner, call.name, call.desc, call.getOpcode(), call.itf);
            } else if (insn instanceof FieldInsnNode field) {
                field(field.owner, field.name, field.desc, field.getOpcode());
            } else if (insn instanceof TypeInsnNode type) {
                descriptor(type.desc.startsWith("[") ? type.desc : "L" + type.desc + ";");
            } else if (insn instanceof MultiANewArrayInsnNode array) {
                descriptor(array.desc);
            } else if (insn instanceof LdcInsnNode ldc) {
                constant(ldc.cst);
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                descriptor(indy.desc);
                constant(indy.bsm);
                for (Object argument : indy.bsmArgs) {
                    constant(argument);
                }
            } else if (insn instanceof FrameNode frame) {
                frameTypes(frame.local);
                frameTypes(frame.stack);
            }
        }

        private void frameTypes(List<Object> types) {
            if (types != null) {
                for (Object type : types) {
                    if (type instanceof String name) {
                        descriptor(name.startsWith("[") ? name : "L" + name + ";");
                    }
                }
            }
        }

        private void constant(Object value) {
            if (value instanceof Type type) {
                descriptor(type.getDescriptor());
            } else if (value instanceof Handle handle) {
                switch (handle.getTag()) {
                    case Opcodes.H_GETFIELD -> field(handle.getOwner(), handle.getName(), handle.getDesc(), Opcodes.GETFIELD);
                    case Opcodes.H_GETSTATIC -> field(handle.getOwner(), handle.getName(), handle.getDesc(), Opcodes.GETSTATIC);
                    case Opcodes.H_PUTFIELD -> field(handle.getOwner(), handle.getName(), handle.getDesc(), Opcodes.PUTFIELD);
                    case Opcodes.H_PUTSTATIC -> field(handle.getOwner(), handle.getName(), handle.getDesc(), Opcodes.PUTSTATIC);
                    case Opcodes.H_INVOKESTATIC -> method(handle.getOwner(), handle.getName(), handle.getDesc(),
                            Opcodes.INVOKESTATIC, handle.isInterface());
                    case Opcodes.H_INVOKEINTERFACE -> method(handle.getOwner(), handle.getName(), handle.getDesc(),
                            Opcodes.INVOKEINTERFACE, true);
                    case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> method(handle.getOwner(),
                            handle.getName(), handle.getDesc(), Opcodes.INVOKESPECIAL, handle.isInterface());
                    default -> method(handle.getOwner(), handle.getName(), handle.getDesc(), Opcodes.INVOKEVIRTUAL,
                            handle.isInterface());
                }
            }
        }

        private void descriptor(String descriptor) {
            Type type = Type.getType(descriptor);
            switch (type.getSort()) {
                case Type.METHOD -> {
                    descriptor(type.getReturnType().getDescriptor());
                    for (Type argument : type.getArgumentTypes()) {
                        descriptor(argument.getDescriptor());
                    }
                }
                case Type.ARRAY -> descriptor(type.getElementType().getDescriptor());
                case Type.OBJECT -> type(type.getInternalName());
                default -> {
                }
            }
        }

        /** The class, or null with the reason recorded. */
        private ClassNode type(String name) {
            if (name == null) {
                return null;
            }
            if (name.startsWith(CLIENT)) {
                uses.add(name);
            }
            ClassNode found = lookup(name);
            if (found == null) {
                problems.add("missing class " + name);
            } else if ((found.access & Opcodes.ACC_PUBLIC) == 0 && !samePackage(found.name, node.name)) {
                problems.add("inaccessible class " + name);
            }
            return found;
        }

        private void field(String owner, String name, String descriptor, int opcode) {
            descriptor(descriptor);
            ClassNode start = type(owner);
            if (start == null) {
                return;
            }
            Member found = findField(start, name, descriptor);
            if (found == null) {
                problems.add("missing field " + owner + "." + name + ":" + descriptor);
                return;
            }
            boolean wantStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
            if (((found.access & Opcodes.ACC_STATIC) != 0) != wantStatic) {
                problems.add("field " + owner + "." + name + " is " + (wantStatic ? "not " : "") + "static");
            }
            if ((opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) && (found.access & Opcodes.ACC_FINAL) != 0
                    && !found.owner.equals(node.name)) {
                problems.add("field " + owner + "." + name + " is final");
            }
            access(found, owner + "." + name);
        }

        private void method(String owner, String name, String descriptor, int opcode, boolean isInterface) {
            descriptor(descriptor);
            ClassNode start = type(owner);
            if (start == null) {
                return;
            }
            boolean ownerIsInterface = (start.access & Opcodes.ACC_INTERFACE) != 0;
            if (!owner.startsWith("[") && ownerIsInterface != isInterface) {
                problems.add(owner + " is " + (ownerIsInterface ? "an interface" : "a class") + " where "
                        + name + descriptor + " expects otherwise");
                return;
            }
            Member found = findMethod(start, name, descriptor);
            if (found == null && polymorphic(owner, name)) {
                return;
            }
            if (found == null) {
                problems.add("missing method " + owner + "." + name + descriptor);
                return;
            }
            boolean wantStatic = opcode == Opcodes.INVOKESTATIC;
            if (((found.access & Opcodes.ACC_STATIC) != 0) != wantStatic) {
                problems.add("method " + owner + "." + name + descriptor + " is " + (wantStatic ? "not " : "")
                        + "static");
            }
            access(found, owner + "." + name + descriptor);
        }

        private void access(Member member, String label) {
            if ((member.access & Opcodes.ACC_PRIVATE) != 0 && !member.owner.equals(node.name)
                    && !nestmates(member.owner, node.name)) {
                problems.add("private " + label);
            } else if ((member.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0
                    && !samePackage(member.owner, node.name)) {
                problems.add("package-private " + label);
            }
        }

        private boolean nestmates(String a, String b) {
            return outermost(a).equals(outermost(b));
        }

        private String outermost(String name) {
            int dollar = name.indexOf('$');
            return dollar < 0 ? name : name.substring(0, dollar);
        }
    }

    /** ASM's type-checking verifier, with the hierarchy read from this version's classes rather than loaded. */
    private final class Hierarchy extends SimpleVerifier {
        Hierarchy(ClassNode owner) {
            super(Opcodes.ASM9, Type.getObjectType(owner.name),
                    owner.superName == null ? null : Type.getObjectType(owner.superName),
                    owner.interfaces.stream().map(Type::getObjectType).toList(),
                    (owner.access & Opcodes.ACC_INTERFACE) != 0);
        }

        @Override
        protected boolean isInterface(Type type) {
            return (lookup(type.getInternalName()).access & Opcodes.ACC_INTERFACE) != 0;
        }

        @Override
        protected Type getSuperClass(Type type) {
            String superName = lookup(type.getInternalName()).superName;
            return superName == null ? null : Type.getObjectType(superName);
        }

        @Override
        protected boolean isAssignableFrom(Type type, Type other) {
            if (type.equals(other)) {
                return true;
            }
            if (type.getSort() == Type.ARRAY && other.getSort() == Type.ARRAY) {
                Type element = type.getElementType();
                Type otherElement = other.getElementType();
                return type.getDimensions() == other.getDimensions() && (element.equals(otherElement)
                        || element.getSort() == Type.OBJECT && otherElement.getSort() == Type.OBJECT
                        && isAssignableFrom(element, otherElement));
            }
            if (type.getSort() != Type.OBJECT || other.getSort() != Type.OBJECT) {
                return type.getInternalName().equals("java/lang/Object");
            }
            /* The JVM's verifier takes anything for an interface type, and so does this. */
            if (type.getInternalName().equals("java/lang/Object") || isInterface(type)) {
                return true;
            }
            for (String at = other.getInternalName(); at != null; at = lookup(at).superName) {
                if (at.equals(type.getInternalName())) {
                    return true;
                }
            }
            return false;
        }

        /* Only asked, by isSubTypeOf, whether a type isAssignableFrom refused is an interface; it never is. */
        @Override
        protected Class<?> getClass(Type type) {
            return Object.class;
        }
    }

    private record Member(String owner, int access) {}

    private static boolean polymorphic(String owner, String name) {
        return (owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle"))
                && !name.equals("<init>");
    }

    private Member findField(ClassNode start, String name, String descriptor) {
        for (ClassNode node = start; node != null; node = node.superName != null ? lookup(node.superName) : null) {
            for (FieldNode field : node.fields) {
                if (field.name.equals(name) && field.desc.equals(descriptor)) {
                    return new Member(node.name, field.access);
                }
            }
            for (String parent : node.interfaces) {
                ClassNode parentNode = lookup(parent);
                Member found = parentNode != null ? findField(parentNode, name, descriptor) : null;
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Member findMethod(ClassNode start, String name, String descriptor) {
        for (ClassNode node = start; node != null; node = node.superName != null ? lookup(node.superName) : null) {
            for (MethodNode method : node.methods) {
                if (method.name.equals(name) && method.desc.equals(descriptor)) {
                    return new Member(node.name, method.access);
                }
            }
        }
        if (name.equals("<init>")) {
            return null;
        }
        return findInterfaceMethod(start, name, descriptor);
    }

    private Member findInterfaceMethod(ClassNode start, String name, String descriptor) {
        for (ClassNode node = start; node != null; node = node.superName != null ? lookup(node.superName) : null) {
            for (String parent : node.interfaces) {
                ClassNode parentNode = lookup(parent);
                if (parentNode == null) {
                    continue;
                }
                for (MethodNode method : parentNode.methods) {
                    if (method.name.equals(name) && method.desc.equals(descriptor)
                            && (method.access & Opcodes.ACC_STATIC) == 0) {
                        return new Member(parentNode.name, method.access);
                    }
                }
                Member found = findInterfaceMethod(parentNode, name, descriptor);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void readJar(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().collect(Collectors.toList())) {
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        ClassNode node = read(in.readAllBytes(), ClassReader.SKIP_CODE);
                        world.putIfAbsent(node.name, node);
                    }
                }
            }
        }
    }

    /** Every class under {@code directory}, leaving out the subdirectory {@code skip} when there is one. */
    private static Map<String, ClassNode> readDirectory(Path directory, String skip) throws IOException {
        Map<String, ClassNode> classes = new HashMap<>();
        List<Path> files;
        Path skipped = skip != null ? directory.resolve(skip) : null;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> skipped == null || !path.startsWith(skipped))
                    .collect(Collectors.toList());
        }
        for (Path file : files) {
            ClassNode node = read(Files.readAllBytes(file), 0);
            classes.put(node.name, node);
        }
        return classes;
    }

    private static ClassNode read(byte[] bytes, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }
}
