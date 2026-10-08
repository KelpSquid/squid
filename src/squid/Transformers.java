package squid;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Every change mods asked for, grouped by class, and the code that applies them while a class loads. */
public final class Transformers {
    /** A change to one class. */
    public sealed interface Patch permits HookPatch, RawPatch {
    }

    /** Calls hook number {@code hookId} at the start (or end) of methods named {@code method}. */
    public record HookPatch(String method, String descriptor, boolean atStart, int hookId) implements Patch {
    }

    /** A mod's own ASM changes. */
    public record RawPatch(String modId, Consumer<ClassNode> patch) implements Patch {
    }

    private static final Map<String, List<Patch>> PATCHES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> LOADED = ConcurrentHashMap.newKeySet(); // classes already loaded, so past patching

    private Transformers() {
    }

    public static void add(String className, Patch patch) {
        PATCHES.computeIfAbsent(className, k -> new ArrayList<>()).add(patch);
    }

    /**
     * Whether a class has already loaded, so new patches for it can't apply until the game restarts. On a fast boot
     * every class came patched ahead of time, so once the game is running nothing can be patched anymore.
     */
    public static boolean isLoaded(String className) {
        return LOADED.contains(className) || (FastBoot.active() && Main.gameStarted());
    }

    /** Every class some mod (or Squid) patches. */
    static List<String> patchedClasses() {
        List<String> names = new ArrayList<>(PATCHES.keySet());
        names.sort(null);
        return names;
    }

    /**
     * A fingerprint of every patch, in order: which class, which method, start or end, and which hook number. Fast
     * boot uses it to know its pre-patched classes still match what the mods asked for.
     */
    static String signature() {
        StringBuilder b = new StringBuilder();
        for (String className : patchedClasses()) {
            for (Patch patch : PATCHES.get(className)) {
                if (patch instanceof HookPatch h) {
                    b.append("H ").append(className).append(' ').append(h.method()).append(' ').append(h.descriptor())
                            .append(' ').append(h.atStart()).append(' ').append(h.hookId()).append('\n');
                } else if (patch instanceof RawPatch r) {
                    b.append("R ").append(className).append(' ').append(r.modId()).append('\n');
                }
            }
        }
        return FastBoot.sha256(b.toString());
    }

    /** Applies every patch for this class as it loads. Classes nobody patched come back untouched. */
    static byte[] transform(String className, byte[] bytes, ClassLoader loader) {
        LOADED.add(className);
        FastBoot.classLoaded(className);
        return patch(className, bytes, loader);
    }

    /** Applies every patch for a class, without counting it as loaded (fast boot patches classes ahead of time). */
    static byte[] patch(String className, byte[] bytes, ClassLoader loader) {
        List<Patch> patches = PATCHES.get(className);
        if (patches == null) return bytes;

        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        // Order matters: raw patches first, then end hooks, then start hooks. That way the early
        // return a start hook adds when it cancels a call doesn't get end hooks, so a cancelled call
        // skips them no matter which mod registered first.
        for (Patch patch : patches) {
            if (patch instanceof RawPatch raw) raw.patch().accept(node);
        }
        for (Patch patch : patches) {
            if (patch instanceof HookPatch hook && !hook.atStart()) applyHook(className, node, hook);
        }
        // Each start hook gets added at the very top, pushing earlier ones down, so add them last-first
        // to make them run in the order they were registered.
        for (Patch patch : patches.reversed()) {
            if (patch instanceof HookPatch hook && hook.atStart()) applyHook(className, node, hook);
        }

        ClassWriter writer = new SquidClassWriter(loader);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void applyHook(String className, ClassNode node, HookPatch hook) {
        int found = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals(hook.method())) continue;
            if (hook.descriptor() != null && !method.desc.equals(hook.descriptor())) continue;
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue; // no code to hook
            if (hook.atStart() && method.name.equals("<init>")) continue; // too early: the object isn't built yet
            if (hook.atStart()) injectStart(method, hook.hookId());
            else injectEnd(method, hook.hookId());
            found++;
        }
        if (found == 0) System.out.println("[Squid] Warning: no method " + hook.method() + " in " + className);
    }

    // ---- Writing the hook calls into bytecode ----

    /**
     * At the very start of the method:
     * Call call = Hooks.start(id, this, args); if (call.isCancelled()) return call.returnValue();
     */
    private static void injectStart(MethodNode method, int hookId) {
        Type returnType = Type.getReturnType(method.desc);
        LabelNode carryOn = new LabelNode();
        InsnList code = new InsnList();
        pushHookArguments(code, method, hookId);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "squid/Hooks", "start",
                "(ILjava/lang/Object;[Ljava/lang/Object;)Lsquid/api/Call;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "squid/api/Call", "isCancelled", "()Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, carryOn));
        if (returnType.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.POP));
        } else {
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "squid/api/Call", "returnValue", "()Ljava/lang/Object;"));
            unboxOrCast(code, returnType);
        }
        code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        code.add(carryOn);
        code.add(new InsnNode(Opcodes.POP)); // the Call we didn't need
        method.instructions.insert(code);
    }

    /** Before every return: value = Hooks.end(id, this, args, value); return value; */
    private static void injectEnd(MethodNode method, int hookId) {
        Type returnType = Type.getReturnType(method.desc);
        int saved = method.maxLocals; // a new local variable to hold the return value
        method.maxLocals += 1;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            int op = insn.getOpcode();
            if (op < Opcodes.IRETURN || op > Opcodes.RETURN) continue;
            InsnList code = new InsnList();
            if (returnType.getSort() == Type.VOID) {
                code.add(new InsnNode(Opcodes.ACONST_NULL));
            } else {
                box(code, returnType);
            }
            code.add(new VarInsnNode(Opcodes.ASTORE, saved));
            pushHookArguments(code, method, hookId);
            code.add(new VarInsnNode(Opcodes.ALOAD, saved));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "squid/Hooks", "end",
                    "(ILjava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
            if (returnType.getSort() == Type.VOID) {
                code.add(new InsnNode(Opcodes.POP));
            } else {
                unboxOrCast(code, returnType);
            }
            method.instructions.insertBefore(insn, code);
        }
    }

    /** Pushes: the hook number, "this" (or null if static), and an Object[] of the arguments. */
    private static void pushHookArguments(InsnList code, MethodNode method, int hookId) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        code.add(new LdcInsnNode(hookId));
        code.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));

        Type[] params = Type.getArgumentTypes(method.desc);
        code.add(new LdcInsnNode(params.length));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < params.length; i++) {
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new LdcInsnNode(i));
            code.add(new VarInsnNode(params[i].getOpcode(Opcodes.ILOAD), slot));
            box(code, params[i]);
            code.add(new InsnNode(Opcodes.AASTORE));
            slot += params[i].getSize();
        }
    }

    /** Turns a primitive on the stack into its object form, like int into Integer. Objects stay as they are. */
    private static void box(InsnList code, Type type) {
        Type boxed = boxedType(type);
        if (boxed == null) return;
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed.getInternalName(), "valueOf",
                Type.getMethodDescriptor(boxed, type)));
    }

    /** The reverse of {@link #box}: turns an Object back into the type the method returns. */
    private static void unboxOrCast(InsnList code, Type type) {
        Type boxed = boxedType(type);
        if (boxed == null) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
            return;
        }
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, boxed.getInternalName()));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed.getInternalName(), type.getClassName() + "Value",
                Type.getMethodDescriptor(type)));
    }

    private static Type boxedType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> Type.getType(Boolean.class);
            case Type.BYTE -> Type.getType(Byte.class);
            case Type.CHAR -> Type.getType(Character.class);
            case Type.SHORT -> Type.getType(Short.class);
            case Type.INT -> Type.getType(Integer.class);
            case Type.FLOAT -> Type.getType(Float.class);
            case Type.LONG -> Type.getType(Long.class);
            case Type.DOUBLE -> Type.getType(Double.class);
            default -> null;
        };
    }

    /**
     * ASM sometimes needs to know which class two classes have in common, to describe the code to Java.
     * It normally loads classes to find out, which would load Minecraft too early, so this reads the class files instead.
     */
    private static final class SquidClassWriter extends ClassWriter {
        private final ClassLoader loader;

        SquidClassWriter(ClassLoader loader) {
            super(ClassWriter.COMPUTE_FRAMES);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String a, String b) {
            List<String> parentsOfA = parents(a);
            if (parentsOfA == null) return "java/lang/Object";
            List<String> parentsOfB = parents(b);
            if (parentsOfB == null) return "java/lang/Object";
            for (String candidate : parentsOfB) {
                if (parentsOfA.contains(candidate)) return candidate;
            }
            return "java/lang/Object";
        }

        /** The class, its parent, its parent's parent... up to Object. Null if it's an interface or can't be read. */
        private List<String> parents(String name) {
            List<String> chain = new ArrayList<>();
            String current = name;
            while (current != null) {
                chain.add(current);
                if (current.equals("java/lang/Object")) return chain;
                try (InputStream in = loader.getResourceAsStream(current + ".class")) {
                    if (in == null) return null;
                    ClassReader reader = new ClassReader(in);
                    if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) return null;
                    current = reader.getSuperName();
                } catch (IOException e) {
                    return null;
                }
            }
            return chain;
        }
    }
}
