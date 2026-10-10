package cobble.tools;

import cobble.test.Check;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Runs tools/Remap.java the way the build does, over a made-up game small
 * enough to check by hand: Base declares a field and a method, Child extends
 * it, and the client reaches both through Child, so they only resolve by
 * walking up the hierarchy.
 */
final class RemapTest {
    private static final String BASE = "net/minecraft/client/Base";
    private static final String CHILD = "net/minecraft/client/Child";
    private static final String CLIENT = "cobble/module/Uses";
    private static final String UNTOUCHED = "cobble/module/Plain";

    private static byte[] gameClass(String name, String superName, boolean declares) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        if (declares) {
            w.visitField(Opcodes.ACC_PUBLIC, "health", "F", null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "jump", "()V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 1);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    /** float read(Child c) { c.jump(); return c.health; } */
    private static byte[] clientClass() {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CLIENT, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(L" + CHILD + ";)F", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHILD, "jump", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, CHILD, "health", "F");
        m.visitInsn(Opcodes.FRETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] plainClass() {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, UNTOUCHED, null, "java/lang/Object", null);
        w.visitEnd();
        return w.toByteArray();
    }

    private static void jar(Path path, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static Path clientClasses(Path dir) throws Exception {
        Path in = dir.resolve("classes-in");
        Files.createDirectories(in.resolve("cobble/module"));
        Files.write(in.resolve(CLIENT + ".class"), clientClass());
        Files.write(in.resolve(UNTOUCHED + ".class"), plainClass());
        return in;
    }

    private static void remap(String... args) throws Exception {
        String root = System.getProperty("cobble.root");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("cobble.libs"),
                Path.of(root, "tools", "Remap.java").toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        Check.equal(0, code, "Remap " + args[0] + " exit code; output:\n" + output);
    }

    private static MethodNode read(Path classFile) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(classFile)).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals("read")).findFirst().orElseThrow();
    }

    static void testCheatBreakerRenamesThroughTheHierarchy() throws Exception {
        Path dir = Files.createTempDirectory("cobble-remap-cb");
        Path game = dir.resolve("mcp.jar");
        jar(game, Map.of(BASE + ".class", gameClass(BASE, "java/lang/Object", true),
                CHILD + ".class", gameClass(CHILD, BASE, false)));
        Path names = dir.resolve("names.tsv");
        Files.writeString(names, String.join("\n",
                "CL\t" + BASE + "\tcb/a",
                "CL\t" + CHILD + "\tcb/b",
                "FD\t" + BASE + "\thealth\tF\tq",
                "MD\t" + BASE + "\tjump\t()V\tz") + "\n");
        Path in = clientClasses(dir);
        Path out = dir.resolve("overlay");

        remap("cheatbreaker", names.toString(), game.toString(), in.toString(), out.toString());

        Check.that(!Files.exists(out.resolve(UNTOUCHED + ".class")),
                "a class with nothing to rename stays out of the overlay");
        MethodNode method = read(out.resolve(CLIENT + ".class"));
        Check.equal("(Lcb/b;)F", method.desc, "descriptor uses CheatBreaker's class name");
        MethodInsnNode call = (MethodInsnNode) find(method, MethodInsnNode.class);
        Check.equal("cb/b", call.owner, "call owner renamed");
        Check.equal("z", call.name, "inherited method resolved on Base and renamed");
        FieldInsnNode field = (FieldInsnNode) find(method, FieldInsnNode.class);
        Check.equal("cb/b", field.owner, "field owner renamed");
        Check.equal("q", field.name, "inherited field resolved on Base and renamed");
    }

    static void testIntermediaryUsesYarnTiny() throws Exception {
        Path dir = Files.createTempDirectory("cobble-remap-tiny");
        Path game = dir.resolve("named.jar");
        jar(game, Map.of(BASE + ".class", gameClass(BASE, "java/lang/Object", true),
                CHILD + ".class", gameClass(CHILD, BASE, false)));
        String tiny = String.join("\n",
                "tiny\t2\t0\tintermediary\tnamed",
                "c\tnet/minecraft/class_1\t" + BASE,
                "\tf\tF\tfield_7\thealth",
                "\tm\t()V\tmethod_9\tjump",
                "c\tnet/minecraft/class_2\t" + CHILD) + "\n";
        Path yarn = dir.resolve("yarn.jar");
        jar(yarn, Map.of("mappings/mappings.tiny", tiny.getBytes(StandardCharsets.UTF_8)));
        Path in = clientClasses(dir);
        Path out = dir.resolve("out");

        remap("intermediary", yarn.toString(), game.toString(), in.toString(), out.toString());

        Check.that(Files.exists(out.resolve(UNTOUCHED + ".class")), "intermediary writes every class");
        MethodNode method = read(out.resolve(CLIENT + ".class"));
        Check.equal("(Lnet/minecraft/class_2;)F", method.desc, "descriptor in intermediary");
        MethodInsnNode call = (MethodInsnNode) find(method, MethodInsnNode.class);
        Check.equal("method_9", call.name, "method in intermediary");
        FieldInsnNode field = (FieldInsnNode) find(method, FieldInsnNode.class);
        Check.equal("field_7", field.name, "field in intermediary");
    }

    private static Object find(MethodNode method, Class<?> type) {
        for (var insn : method.instructions) {
            if (type.isInstance(insn)) {
                return insn;
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + method.name);
    }
}
