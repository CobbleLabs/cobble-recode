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
        return gameClass(name, superName, declares ? "health" : null, "jump");
    }

    /** With a field {@code health} and a method {@code jump} under those names, unless {@code health} is null. */
    private static byte[] gameClass(String name, String superName, String health, String jump) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        if (health != null) {
            w.visitField(Opcodes.ACC_PUBLIC, health, "F", null, null).visitEnd();
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, jump, "()V", null, null);
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
        Path game = dir.resolve("cheatbreaker.jar");
        jar(game, Map.of("cb/a.class", gameClass("cb/a", "java/lang/Object", "q", "z"),
                "cb/b.class", gameClass("cb/b", "cb/a", null, null)));
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

    static void testCheatBreakerWritesEveryRewrittenClass() throws Exception {
        Path dir = Files.createTempDirectory("cobble-remap-cb-rewritten");
        Path game = dir.resolve("cheatbreaker.jar");
        jar(game, Map.of("cb/a.class", gameClass("cb/a", "java/lang/Object", "q", "z")));
        Path names = dir.resolve("names.tsv");
        Files.writeString(names, "CL\t" + BASE + "\tcb/a\n");
        Path in = clientClasses(dir);
        Path rewritten = dir.resolve("rewritten");
        Files.createDirectories(rewritten.resolve("cobble/module"));
        Files.write(rewritten.resolve(UNTOUCHED + ".class"), plainClass());
        Path out = dir.resolve("overlay");

        remap("cheatbreaker", names.toString(), game.toString(), in + "," + rewritten, out.toString());

        Check.that(Files.exists(out.resolve(UNTOUCHED + ".class")),
                "a rewritten class goes to the overlay with nothing to rename");
    }

    static void testHooksLeaveOutWhatTheBuildLacks() throws Exception {
        Path dir = Files.createTempDirectory("cobble-remap-hooks");
        Path game = dir.resolve("cheatbreaker.jar");
        jar(game, Map.of("cb/a.class", gameClass("cb/a", "java/lang/Object", "q", "z")));
        Remap remap = Remap.cheatBreaker(List.of("CL\t" + BASE + "\tcb/a", "MD\t" + BASE + "\tjump\t()V\tz"));
        List<String> table = List.of(
                String.join("\t", BASE, "jump", "()V", "HEAD", "-", "-", "-", "-", "-", "jumped", "s"),
                String.join("\t", BASE, "fly", "()V", "HEAD", "-", "-", "-", "-", "-", "flew", "-"));
        List<String> problems = new ArrayList<>();

        List<String> hooks;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(game.toFile())) {
            hooks = remap.hooks(zip, table, problems);
        }

        Check.equal(List.of(String.join("\t", "cb/a", "z", "()V", "HEAD", "-", "-", "-", "-", "-", "jumped", "s")),
                hooks, "the hook CheatBreaker has a name for, under that name");
        Check.equal(1, problems.size(), "one hook left out");
        Check.that(problems.get(0).startsWith("flew: "), "the problem names the hook: " + problems);
    }

    static void testStarHookPicksTheMethodVanillaHasNoNameFor() throws Exception {
        Path dir = Files.createTempDirectory("cobble-remap-star");
        Path game = dir.resolve("cheatbreaker.jar");
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "cb/a", null, "java/lang/Object", null);
        for (String name : List.of("z", "y")) {
            MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 1);
            m.visitEnd();
        }
        w.visitEnd();
        jar(game, Map.of("cb/a.class", w.toByteArray()));
        Remap remap = Remap.cheatBreaker(List.of("CL\t" + BASE + "\tcb/a", "MD\t" + BASE + "\tjump\t()V\tz"));
        List<String> problems = new ArrayList<>();

        List<String> hooks;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(game.toFile())) {
            hooks = remap.hooks(zip, List.of(String.join("\t", BASE, "*", "()V", "HEAD", "-", "-", "-", "-", "-",
                    "own", "-")), problems);
        }

        Check.equal(List.of(), problems, "no problems");
        Check.equal(List.of(String.join("\t", "cb/a", "y", "()V", "HEAD", "-", "-", "-", "-", "-", "own", "-")),
                hooks, "the method z, vanilla's jump, is not the one");
    }

    /** CheatBreaker's game back under MCP names: cb/a is Base, cb/c a class nested in it. */
    static void testMcpRenamesBackAndNests() throws Exception {
        Path dir = Files.createTempDirectory("cobble-remap-mcp");
        String nested = BASE + "$Kind";
        ClassWriter base = new ClassWriter(0);
        base.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "cb/a", null, "java/lang/Object", null);
        base.visitField(Opcodes.ACC_PUBLIC, "q", "F", null, null).visitEnd();
        base.visitField(Opcodes.ACC_PUBLIC, "k", "Lcb/c;", null, null).visitEnd();
        base.visitEnd();
        Path cb = dir.resolve("cheatbreaker.jar");
        jar(cb, Map.of("cb/a.class", base.toByteArray(), "cb/c.class", gameClass("cb/c", "java/lang/Object", false)));
        Path names = dir.resolve("names.tsv");
        Files.writeString(names, String.join("\n",
                "CL\t" + BASE + "\tcb/a",
                "CL\t" + nested + "\tcb/c",
                "FD\t" + BASE + "\thealth\tF\tq",
                "FD\t" + BASE + "\tkind\tL" + nested + ";\tk") + "\n");
        Path out = dir.resolve("mcp.jar");

        remap("mcp", names.toString(), cb.toString(), out.toString());

        ClassNode node = new ClassNode();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(out.toFile())) {
            new ClassReader(zip.getInputStream(zip.getEntry(BASE + ".class")).readAllBytes()).accept(node, 0);
            Check.that(zip.getEntry(nested + ".class") != null, "the nested class keeps its MCP name");
        }
        Check.that(node.fields.stream().anyMatch(f -> f.name.equals("health") && f.desc.equals("F")),
                "field named back by owner, name and descriptor");
        Check.that(node.fields.stream().anyMatch(f -> f.name.equals("kind") && f.desc.equals("L" + nested + ";")),
                "field descriptor named back");
        Check.that(node.innerClasses.stream().anyMatch(c -> c.name.equals(nested) && c.outerName.equals(BASE)
                        && c.innerName.equals("Kind")),
                "the outer class lists its nested class, so javac finds it");
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
