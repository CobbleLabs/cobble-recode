import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Rewrites classes compiled against one Minecraft version so they run on a
 * neighbouring one, after a table of what the second spells differently.
 * Done with reflection on every call this would cost in game; here it
 * happens once, at build time, and the result goes through the same
 * linkage check as everything else, so a reference the table does not cover
 * keeps its class out instead of failing in game.
 *
 * <p>Rules, one per line, {@code #} starting a comment. A member is named by
 * the class that declares it on the source version; references through a
 * subclass are resolved up the source version's hierarchy first.
 *
 * <pre>
 *   class  from/Type                         to/Type
 *   field  Owner.name:Desc   field   Owner.name:Desc     (a narrower type is cast on write)
 *   field  Owner.name:Desc   static  Helper.name(Desc)   (receiver as the argument; reads only)
 *   field  Owner.name:Desc   getstatic Owner.name:Desc   (an instance field turned static; reads only)
 *   method Owner.name(Desc)  method  Owner.name(Desc)    (same kind of call)
 *   method Owner.name(Desc)  static  Helper.name(Desc)   (receiver first; a constructor becomes a factory)
 *   method Owner.name(Desc)  getfield Owner.name:Desc    (a getter turned field read)
 *   method Owner.name(Desc)  getstatic Owner.name:Desc   (receiver dropped)
 *   instanceof from/Type     static  Helper.name(Ljava/lang/Object;)Z   (for a type the target has no class for)
 *   shims  prefix/
 *   omit   client/Type                      (left as it is, so it does not link on the target)
 * </pre>
 *
 * With a shims prefix, a member no rule covers goes to a static method of
 * {@code prefix + <declaring class's simple name> + Shim} when one is there
 * with the right descriptor in the target's names, even if the target has a
 * member of the same name, which is no promise it means the same: a field
 * read is {@code T name(Owner)}, a write {@code void name(Owner, T)}, a static
 * field drops the owner, a method takes its receiver first, and a constructor
 * is {@code Owner create(...)}. Nested classes join their names with an
 * underscore. This is how a version far from the source gets most of its
 * table: the shims are ordinary code, written and type-checked against the
 * target.
 *
 * Right-hand sides are written in the target version's names; class renames
 * are applied to everything else afterwards. Given MCP's tables for both
 * versions, a reference no rule covers and the target lacks is renamed to
 * whatever the target calls the same Searge member, which takes care of the
 * plain renames between versions. Only classes that change are written.
 *
 * Usage: java -cp asm*.jar tools/Retarget.java <rules> <source jar>,... <target jar or dir>,... <classes> <out>
 *            [<source mcp srg zip>,<source mcp stable zip> <target mcp srg zip>,<target mcp stable zip>]
 */
public final class Retarget {
    private record Target(String kind, String owner, String name, String descriptor) {}

    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, Target> fields = new HashMap<>();
    private final Map<String, Target> methods = new HashMap<>();
    private final Map<String, Target> instanceTests = new HashMap<>();
    private String shimPrefix;
    private final Set<String> omitted = new HashSet<>();
    private final Map<String, ClassNode> source = new HashMap<>();
    private final Map<String, ClassNode> target = new HashMap<>();
    private final List<String> errors = new ArrayList<>();
    private final SimpleRemapper renamer = new SimpleRemapper(classes);
    /** Source version's "declaring class.name" (a method's with its descriptor) to its Searge name. */
    private final Map<String, String> sourceSearge = new HashMap<>();
    /** Target version's "declaring class.Searge name" (a method's with its descriptor) to its name there. */
    private final Map<String, String> targetNames = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Retarget retarget = new Retarget();
        retarget.readRules(Files.readAllLines(Path.of(args[0])));
        for (String jar : args[1].split(",")) {
            read(Path.of(jar), retarget.source);
        }
        for (String jar : args[2].split(",")) {
            read(Path.of(jar), retarget.target);
        }
        retarget.renamedTargetsExist();
        Path input = Path.of(args[3]);
        Path output = Path.of(args[4]);
        if (args.length > 6) {
            readSearge(args[5], (searge, named) -> retarget.sourceSearge.put(named, searge));
            readSearge(args[6], retarget.targetNames::put);
        }
        Map<Path, byte[]> inputs = new HashMap<>();
        try (Stream<Path> walk = Files.walk(input)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList())) {
                byte[] bytes = Files.readAllBytes(file);
                inputs.put(file, bytes);
                ClassNode node = node(bytes, ClassReader.SKIP_CODE);
                retarget.source.put(node.name, node);
                retarget.target.put(node.name, node);
            }
        }
        int written = 0;
        for (Map.Entry<Path, byte[]> entry : inputs.entrySet()) {
            if (retarget.omitted.contains(node(entry.getValue(), ClassReader.SKIP_CODE).name)) {
                continue;
            }
            byte[] rewritten = retarget.rewrite(entry.getValue());
            if (rewritten != null) {
                Path out = output.resolve(input.relativize(entry.getKey()));
                Files.createDirectories(out.getParent());
                Files.write(out, rewritten);
                ++written;
            }
        }
        if (!retarget.errors.isEmpty()) {
            retarget.errors.forEach(System.err::println);
            System.exit(1);
        }
        System.out.printf("retargeted %d of %d classes%n", written, inputs.size());
    }

    private void readRules(List<String> lines) {
        for (String raw : lines) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 1 && parts[0].isEmpty()) {
                continue;
            }
            switch (parts[0]) {
                case "class" -> classes.put(parts[1], parts[2]);
                case "field" -> fields.put(parts[1], target(parts[2], parts[3]));
                case "method" -> methods.put(parts[1], target(parts[2], parts[3]));
                case "instanceof" -> instanceTests.put(parts[1], target(parts[2], parts[3]));
                case "shims" -> shimPrefix = parts[1];
                case "omit" -> omitted.add(parts[1]);
                default -> throw new IllegalArgumentException("unknown rule: " + raw);
            }
        }
    }

    private static Target target(String kind, String member) {
        int dot = member.lastIndexOf('.', member.indexOf('(') >= 0 ? member.indexOf('(') : member.indexOf(':'));
        String owner = member.substring(0, dot);
        String rest = member.substring(dot + 1);
        int split = rest.indexOf('(') >= 0 ? rest.indexOf('(') : rest.indexOf(':');
        String descriptor = rest.charAt(split) == ':' ? rest.substring(split + 1) : rest.substring(split);
        return new Target(kind, owner, rest.substring(0, split), descriptor);
    }

    /** The rewritten class, or null when nothing in it needed changing. */
    private byte[] rewrite(byte[] bytes) {
        ClassNode node = node(bytes, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= rewrite(node, method);
            /* An override of a game method has to carry the name the target gives it, or it overrides nothing. */
            if ((method.access & Opcodes.ACC_STATIC) == 0 && !method.name.startsWith("<") && node.superName != null) {
                String renamed = renamedThroughSearge(node.superName, method.name, method.desc, true);
                if (renamed != null) {
                    method.name = renamed;
                    changed = true;
                }
            }
        }
        boolean renames = mentionsRenamed(bytes);
        if (!changed && !renames) {
            return null;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return commonSuperClass(a, b);
            }
        };
        node.accept(new ClassRemapper(writer, new SimpleRemapper(classes)));
        return writer.toByteArray();
    }

    private boolean mentionsRenamed(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        return classes.keySet().stream().anyMatch(text::contains);
    }

    private boolean rewrite(ClassNode owner, MethodNode method) {
        boolean changed = false;
        Map<AbstractInsnNode, Frame<SourceValue>> frames = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field) {
                Target replacement = fields.get(declaring(field.owner, field.name + ":" + field.desc, false));
                if (replacement != null) {
                    replaceField(owner, method, field, replacement);
                    changed = true;
                } else {
                    String renamed = renamedThroughSearge(field.owner, field.name, field.desc, false);
                    if (renamed != null) {
                        field.name = renamed;
                        changed = true;
                    } else {
                        changed |= shimField(method, field);
                    }
                }
            } else if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF
                    && instanceTests.containsKey(type.desc)) {
                Target test = instanceTests.get(type.desc);
                method.instructions.set(type, new MethodInsnNode(Opcodes.INVOKESTATIC, test.owner, test.name,
                        test.descriptor, false));
                changed = true;
            } else if (insn instanceof MethodInsnNode call) {
                Target replacement = methods.get(declaring(call.owner, call.name + call.desc, true));
                if (replacement == null) {
                    String renamed = renamedThroughSearge(call.owner, call.name, call.desc, true);
                    if (renamed != null) {
                        call.name = renamed;
                        changed = true;
                        continue;
                    }
                    replacement = shimFor(call);
                    if (replacement == null) {
                        changed |= followOwnerKind(call);
                        continue;
                    }
                }
                if (call.name.equals("<init>") && replacement.kind.equals("static")) {
                    if (frames == null) {
                        frames = analyse(owner, method);
                    }
                    if (frames != null) {
                        factory(method, call, replacement, frames);
                    }
                } else {
                    replaceMethod(owner, method, call, replacement);
                }
                changed = true;
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                for (int index = 0; index < indy.bsmArgs.length; ++index) {
                    Object argument = indy.bsmArgs[index];
                    Handle shimmed = argument instanceof Handle handle ? shimHandle(handle) : null;
                    if (shimmed != null) {
                        indy.bsmArgs[index] = shimmed;
                        changed = true;
                    } else if (argument instanceof Handle handle && (methods.containsKey(
                            declaring(handle.getOwner(), handle.getName() + handle.getDesc(), true))
                            || fields.containsKey(declaring(handle.getOwner(), handle.getName() + ":" + handle.getDesc(), false)))) {
                        errors.add(owner.name + "." + method.name + ": a method reference to "
                                + handle.getOwner() + "." + handle.getName() + " needs a rule the table cannot apply");
                    }
                }
            }
        }
        return changed;
    }

    /*
     * Shims are looked up in the target's names, since they are compiled
     * against it, but the rewritten instruction keeps the source's: the class
     * renames are applied to the whole class afterwards.
     */
    private boolean shimField(MethodNode method, FieldInsnNode field) {
        if (shimPrefix == null) {
            return false;
        }
        String declarer = declarer(source, field.owner, field.name, field.desc, false);
        if (declarer == null) {
            return false;
        }
        boolean isStatic = field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.PUTSTATIC;
        boolean write = field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC;
        Type value = Type.getType(field.desc);
        List<Type> arguments = new ArrayList<>();
        if (!isStatic) {
            arguments.add(Type.getObjectType(declarer));
        }
        if (write) {
            arguments.add(value);
        }
        String descriptor = Type.getMethodDescriptor(write ? Type.VOID_TYPE : value, arguments.toArray(new Type[0]));
        String shim = shimClass(declarer);
        if (!shimDeclares(shim, field.name, descriptor)) {
            return false;
        }
        method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, shim, field.name, descriptor, false));
        return true;
    }

    private Target shimFor(MethodInsnNode call) {
        boolean constructor = call.name.equals("<init>");
        if (shimPrefix == null || call.getOpcode() == Opcodes.INVOKESPECIAL && !constructor) {
            return null;
        }
        String declarer = constructor ? call.owner : declarer(source, call.owner, call.name, call.desc, true);
        if (declarer == null) {
            return null;
        }
        String descriptor;
        if (constructor) {
            descriptor = Type.getMethodDescriptor(Type.getObjectType(declarer), Type.getArgumentTypes(call.desc));
        } else if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            descriptor = call.desc;
        } else {
            List<Type> arguments = new ArrayList<>(List.of(Type.getArgumentTypes(call.desc)));
            arguments.add(0, Type.getObjectType(declarer));
            descriptor = Type.getMethodDescriptor(Type.getReturnType(call.desc), arguments.toArray(new Type[0]));
        }
        String name = constructor ? "create" : call.name;
        String shim = shimClass(declarer);
        return shimDeclares(shim, name, descriptor) ? new Target("static", shim, name, descriptor) : null;
    }

    /* A renamed owner can be an interface on one side and a class on the other, as IBlockState and BlockState are. */
    private boolean followOwnerKind(MethodInsnNode call) {
        String renamed = classes.get(call.owner);
        if (renamed == null || call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL) {
            return false;
        }
        boolean isInterface = isInterface(renamed);
        if (isInterface == call.itf) {
            return false;
        }
        call.itf = isInterface;
        call.setOpcode(isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL);
        return true;
    }

    /*
     * A method reference to a shimmed method points at the shim instead. The
     * shim takes the receiver first, which is where the lambda factory puts
     * it for an instance method, so the reference keeps its shape.
     */
    private Handle shimHandle(Handle handle) {
        int tag = handle.getTag();
        if (tag != Opcodes.H_INVOKEVIRTUAL && tag != Opcodes.H_INVOKEINTERFACE && tag != Opcodes.H_INVOKESTATIC) {
            return null;
        }
        MethodInsnNode call = new MethodInsnNode(tag == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC
                : tag == Opcodes.H_INVOKEINTERFACE ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                handle.getOwner(), handle.getName(), handle.getDesc(), handle.isInterface());
        if (methods.containsKey(declaring(call.owner, call.name + call.desc, true))) {
            return null;
        }
        Target shim = shimFor(call);
        return shim == null ? null : new Handle(Opcodes.H_INVOKESTATIC, shim.owner, shim.name, shim.descriptor, false);
    }

    private String shimClass(String declarer) {
        return shimPrefix + declarer.substring(declarer.lastIndexOf('/') + 1).replace('$', '_') + "Shim";
    }

    private boolean shimDeclares(String shim, String name, String sourceDescriptor) {
        ClassNode node = target.get(shim);
        String descriptor = renamer.mapMethodDesc(sourceDescriptor);
        return node != null && node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)
                && (m.access & Opcodes.ACC_STATIC) != 0);
    }

    private void replaceField(ClassNode owner, MethodNode method, FieldInsnNode field, Target replacement) {
        switch (replacement.kind) {
            case "field" -> {
                boolean write = field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC;
                if (!replacement.owner.equals(field.owner) && field.getOpcode() != Opcodes.GETSTATIC
                        && field.getOpcode() != Opcodes.PUTSTATIC) {
                    if (field.getOpcode() != Opcodes.GETFIELD) {
                        errors.add(owner.name + "." + method.name + ": " + field.owner + "." + field.name
                                + " moves owner, which only a read can follow");
                        return;
                    }
                    // The receiver is on top for a read, typed as the old owner, which the new one extends.
                    method.instructions.insertBefore(field, new TypeInsnNode(Opcodes.CHECKCAST, replacement.owner));
                }
                if (write && !replacement.descriptor.equals(field.desc)) {
                    Type type = Type.getType(replacement.descriptor);
                    method.instructions.insertBefore(field, new TypeInsnNode(Opcodes.CHECKCAST,
                            type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
                }
                method.instructions.set(field, new FieldInsnNode(field.getOpcode(), replacement.owner,
                        replacement.name, replacement.descriptor));
            }
            case "getstatic" -> {
                if (field.getOpcode() != Opcodes.GETFIELD) {
                    errors.add(owner.name + "." + method.name + ": " + field.owner + "." + field.name
                            + " turns static, which only an instance read can follow");
                    return;
                }
                method.instructions.insertBefore(field, new InsnNode(Opcodes.POP));
                method.instructions.set(field, new FieldInsnNode(Opcodes.GETSTATIC, replacement.owner,
                        replacement.name, replacement.descriptor));
            }
            case "static" -> {
                if (field.getOpcode() == Opcodes.PUTFIELD || field.getOpcode() == Opcodes.PUTSTATIC) {
                    errors.add(owner.name + "." + method.name + ": writes " + field.owner + "." + field.name
                            + ", whose rule only reads");
                    return;
                }
                method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, replacement.owner,
                        replacement.name, replacement.descriptor, false));
            }
            default -> errors.add("field rule kind " + replacement.kind + " is not one a field can take");
        }
    }

    private void replaceMethod(ClassNode owner, MethodNode method, MethodInsnNode call, Target replacement) {
        switch (replacement.kind) {
            case "method" -> {
                boolean isInterface = isInterface(replacement.owner);
                int opcode = call.getOpcode() == Opcodes.INVOKESTATIC || call.getOpcode() == Opcodes.INVOKESPECIAL
                        ? call.getOpcode() : isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
                method.instructions.set(call, new MethodInsnNode(opcode, replacement.owner, replacement.name,
                        replacement.descriptor, isInterface));
            }
            case "static" -> method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    replacement.owner, replacement.name, replacement.descriptor, false));
            case "getfield" -> method.instructions.set(call, new FieldInsnNode(Opcodes.GETFIELD,
                    replacement.owner, replacement.name, replacement.descriptor));
            case "getstatic" -> {
                method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
                method.instructions.set(call, new FieldInsnNode(Opcodes.GETSTATIC,
                        replacement.owner, replacement.name, replacement.descriptor));
            }
            default -> errors.add(owner.name + "." + method.name + ": method rule kind " + replacement.kind
                    + " is not one a call can take");
        }
    }

    /* The NEW and DUP that opened the object are dropped, so the factory's
     * result sits where the constructed object would have. */
    private void factory(MethodNode method, MethodInsnNode call, Target replacement,
                         Map<AbstractInsnNode, Frame<SourceValue>> frames) {
        Frame<SourceValue> frame = frames.get(call);
        int arguments = Type.getArgumentTypes(call.desc).length;
        SourceValue receiver = frame.getStack(frame.getStackSize() - arguments - 1);
        for (AbstractInsnNode producer : receiver.insns) {
            AbstractInsnNode created = producer.getOpcode() == Opcodes.DUP ? producer.getPrevious() : producer;
            if (producer.getOpcode() != Opcodes.DUP || created.getOpcode() != Opcodes.NEW) {
                errors.add(method.name + ": a constructor rule needs the plain NEW, DUP shape");
                return;
            }
            method.instructions.remove(created);
            method.instructions.remove(producer);
        }
        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, replacement.owner,
                replacement.name, replacement.descriptor, false));
    }

    /* Taken before anything in the method moves, keyed by instruction so later removals do not shift it. */
    private Map<AbstractInsnNode, Frame<SourceValue>> analyse(ClassNode owner, MethodNode method) {
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
            Map<AbstractInsnNode, Frame<SourceValue>> byInstruction = new HashMap<>();
            for (int index = 0; index < frames.length; ++index) {
                byInstruction.put(method.instructions.get(index), frames[index]);
            }
            return byInstruction;
        } catch (AnalyzerException e) {
            errors.add(owner.name + "." + method.name + ": " + e.getMessage());
            return null;
        }
    }

    /** The rule key for a member as the source version resolves it: declaring class, then name and descriptor. */
    private String declaring(String owner, String member, boolean isMethod) {
        for (String current = owner; current != null; ) {
            String key = current + "." + member;
            if ((isMethod ? methods : fields).containsKey(key)) {
                return key;
            }
            ClassNode node = source.get(current);
            if (node == null) {
                return null;
            }
            for (String parent : node.interfaces) {
                String found = declaring(parent, member, isMethod);
                if (found != null) {
                    return found;
                }
            }
            current = node.superName;
        }
        return null;
    }

    /**
     * A member the target does not have under its source name, renamed through
     * the Searge names MCP gives both versions: the same Searge name on the same
     * class, with the same descriptor for a method, is the same member. Null
     * when the reference already resolves, or when the tables have nothing that
     * does.
     */
    private String renamedThroughSearge(String owner, String name, String descriptor, boolean isMethod) {
        if (sourceSearge.isEmpty() || resolves(target, owner, name, descriptor, isMethod)) {
            return null;
        }
        String declarer = declarer(source, owner, name, descriptor, isMethod);
        if (declarer == null) {
            return null;
        }
        String suffix = isMethod ? descriptor : "";
        String searge = sourceSearge.get(declarer + "." + name + suffix);
        if (searge == null) {
            return null;
        }
        // A member the target's table leaves unnamed runs under its Searge name.
        String targetKey = targetNames.getOrDefault(searge, searge);
        String renamed = targetKey.substring(declarer.length() + 1, targetKey.length() - suffix.length());
        return resolves(target, owner, renamed, descriptor, isMethod) ? renamed : null;
    }

    private static boolean resolves(Map<String, ClassNode> world, String owner, String name, String descriptor,
                                    boolean isMethod) {
        return declarer(world, owner, name, descriptor, isMethod) != null;
    }

    /**
     * MCP's joined.srg and its stable fields.csv and methods.csv, as one call
     * per member: "class.Searge name" and "class.MCP name", a method's both
     * followed by its descriptor. The srg lines already spell their classes the
     * MCP way; they give fields no descriptor, and a class has one field of a
     * name.
     */
    private static void readSearge(String zips, java.util.function.BiConsumer<String, String> into)
            throws IOException {
        String[] parts = zips.split(",", 2);
        Map<String, String> names = new HashMap<>();
        for (String csv : List.of("fields.csv", "methods.csv")) {
            for (String line : zipLines(parts[1], csv)) {
                String[] columns = line.split(",", 3);
                if (columns.length >= 2 && !columns[0].equals("searge")) {
                    names.put(columns[0], columns[1]);
                }
            }
        }
        for (String line : zipLines(parts[0], "joined.srg")) {
            String[] columns = line.split(" ");
            boolean isMethod = columns[0].equals("MD:");
            if (!isMethod && !columns[0].equals("FD:")) {
                continue;
            }
            String member = columns[isMethod ? 3 : 2];
            String owner = member.substring(0, member.lastIndexOf('/'));
            String searge = member.substring(owner.length() + 1);
            String descriptor = isMethod ? columns[4] : "";
            into.accept(owner + "." + searge + descriptor, owner + "." + names.getOrDefault(searge, searge) + descriptor);
        }
    }

    private static List<String> zipLines(String zip, String entry) throws IOException {
        try (ZipFile file = new ZipFile(zip)) {
            ZipEntry found = file.getEntry(entry);
            if (found == null) {
                throw new IOException(zip + " has no " + entry);
            }
            return new String(file.getInputStream(found).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().collect(Collectors.toList());
        }
    }

    /** The class in {@code owner}'s hierarchy that declares the member, or null. */
    private static String declarer(Map<String, ClassNode> world, String owner, String name, String descriptor,
                                   boolean isMethod) {
        ClassNode node = world.get(owner);
        if (node == null) {
            return null;
        }
        boolean declared = isMethod
                ? node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor))
                : node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor));
        if (declared) {
            return node.name;
        }
        List<String> parents = new ArrayList<>(node.interfaces);
        if (node.superName != null) {
            parents.add(0, node.superName);
        }
        for (String parent : parents) {
            String found = declarer(world, parent, name, descriptor, isMethod);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isInterface(String name) {
        ClassNode node = target.get(name);
        return node != null && (node.access & Opcodes.ACC_INTERFACE) != 0;
    }

    private String commonSuperClass(String a, String b) {
        Set<String> ancestors = new HashSet<>();
        for (String current = a; current != null; current = superName(current)) {
            ancestors.add(current);
        }
        for (String current = b; current != null; current = superName(current)) {
            if (ancestors.contains(current)) {
                return isInterface(current) ? "java/lang/Object" : current;
            }
        }
        return "java/lang/Object";
    }

    private String superName(String name) {
        ClassNode node = target.get(name);
        if (node != null) {
            return node.superName;
        }
        if (name.equals("java/lang/Object")) {
            return null;
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            if (in == null) {
                return "java/lang/Object";
            }
            ClassNode system = node(in.readAllBytes(), ClassReader.SKIP_CODE);
            target.put(name, system);
            return system.superName;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /* A rename to a class the target cannot load would only surface as a linkage failure, far from its cause. */
    private void renamedTargetsExist() {
        classes.forEach((from, to) -> {
            if (!target.containsKey(to)) {
                errors.add("class rule " + from + " -> " + to + ": the target has no " + to);
            }
        });
    }

    private static void read(Path jar, Map<String, ClassNode> into) throws IOException {
        if (Files.isDirectory(jar)) {
            try (Stream<Path> walk = Files.walk(jar)) {
                for (Path file : walk.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toList())) {
                    ClassNode node = node(Files.readAllBytes(file), ClassReader.SKIP_CODE);
                    into.putIfAbsent(node.name, node);
                }
            }
            return;
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().collect(Collectors.toList())) {
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        ClassNode node = node(in.readAllBytes(), ClassReader.SKIP_CODE);
                        into.putIfAbsent(node.name, node);
                    }
                }
            }
        }
    }

    private static ClassNode node(byte[] bytes, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, flags);
        return node;
    }
}
