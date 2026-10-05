package cobble.boot;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * A call from a game method into a static method of the platform's hook handlers
 * of the same name as {@code handler}.
 *
 * <p>An injection passes the patched object when {@code self} is set, then the
 * method's arguments when {@code args} is set, and returns void; a
 * cancellable one returns true to make the method return its default value
 * right away, and only sits at HEAD. One that takes the {@code result} sits at
 * RETURN, gets the value being returned ahead of the rest, and returns what the
 * method returns instead. A redirect replaces one instruction with the
 * handler, which takes the instruction's operands and leaves its result.
 */
public record Hook(String owner, String method, String descriptor, At at, String handler, boolean self, boolean args,
            boolean cancellable, boolean result) {
    public Hook {
        if (cancellable && at.kind() != At.Kind.HEAD) {
            throw new IllegalArgumentException(handler + ": only a HEAD hook can cancel");
        }
        if (result && (at.kind() != At.Kind.RETURN || Type.getReturnType(descriptor) == Type.VOID_TYPE)) {
            throw new IllegalArgumentException(handler + ": only a RETURN hook of a method with a result can take it");
        }
    }

    public static Hook of(String owner, String method, String descriptor, At at, String handler) {
        return new Hook(owner, method, descriptor, at, handler, false, false, false, false);
    }

    public Hook withSelf() {
        return new Hook(owner, method, descriptor, at, handler, true, args, cancellable, result);
    }

    public Hook withArgs() {
        return new Hook(owner, method, descriptor, at, handler, self, true, cancellable, result);
    }

    public Hook cancelling() {
        return new Hook(owner, method, descriptor, at, handler, self, args, true, result);
    }

    public Hook withResult() {
        return new Hook(owner, method, descriptor, at, handler, self, args, cancellable, true);
    }

    String handlerDescriptor() {
        if (at.kind() == At.Kind.REDIRECT) {
            return at.redirectDescriptor();
        }
        String returned = Type.getReturnType(descriptor).getDescriptor();
        StringBuilder params = new StringBuilder("(");
        if (result) {
            params.append(returned);
        }
        if (self) {
            params.append('L').append(owner).append(';');
        }
        if (args) {
            for (Type type : Type.getArgumentTypes(descriptor)) {
                params.append(type.getDescriptor());
            }
        }
        return params.append(')').append(result ? returned : cancellable ? "Z" : "V").toString();
    }

    /**
     * Where in the method the hook goes. BEFORE, AFTER and REDIRECT match an
     * instruction by opcode, owner, name and descriptor; {@code ordinal} picks
     * the n-th match, or every match when negative. A BEFORE or AFTER name may
     * hold {@code *} for any run of characters, for the methods Lunar's mixins
     * add, whose names carry a tag that changes from one Lunar build to the next.
     */
    public record At(Kind kind, int opcode, String owner, String name, String descriptor, int ordinal) {
        public enum Kind { HEAD, RETURN, BEFORE, AFTER, REDIRECT }

        public static final At HEAD = new At(Kind.HEAD, 0, null, null, null, -1);
        public static final At RETURN = new At(Kind.RETURN, 0, null, null, null, -1);

        public static At before(int opcode, String owner, String name, String descriptor, int ordinal) {
            return new At(Kind.BEFORE, opcode, owner, name, descriptor, ordinal);
        }

        public static At after(int opcode, String owner, String name, String descriptor, int ordinal) {
            return new At(Kind.AFTER, opcode, owner, name, descriptor, ordinal);
        }

        public static At redirect(int opcode, String owner, String name, String descriptor, int ordinal) {
            return new At(Kind.REDIRECT, opcode, owner, name, descriptor, ordinal);
        }

        boolean names(String candidate) {
            if (name.indexOf('*') < 0) {
                return name.equals(candidate);
            }
            String[] parts = name.split("\\*", -1);
            if (!candidate.startsWith(parts[0])) {
                return false;
            }
            int from = parts[0].length();
            for (int index = 1; index < parts.length - 1; ++index) {
                int found = candidate.indexOf(parts[index], from);
                if (found < 0) {
                    return false;
                }
                from = found + parts[index].length();
            }
            String last = parts[parts.length - 1];
            return candidate.length() - last.length() >= from && candidate.endsWith(last);
        }

        boolean isField() {
            return opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD
                    || opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        }

        /** The operands the replaced instruction pops, in order, and what it pushes. */
        String redirectDescriptor() {
            String receiver = "L" + owner + ";";
            return switch (opcode) {
                case Opcodes.GETFIELD -> "(" + receiver + ")" + descriptor;
                case Opcodes.PUTFIELD -> "(" + receiver + descriptor + ")V";
                case Opcodes.GETSTATIC -> "()" + descriptor;
                case Opcodes.PUTSTATIC -> "(" + descriptor + ")V";
                case Opcodes.INVOKESTATIC -> descriptor;
                default -> "(" + receiver + descriptor.substring(1);
            };
        }

        @Override
        public String toString() {
            return switch (kind) {
                case HEAD, RETURN -> kind.name();
                default -> kind.name().toLowerCase() + " " + owner + "." + name + descriptor
                        + (ordinal < 0 ? "" : " #" + ordinal);
            };
        }
    }
}
