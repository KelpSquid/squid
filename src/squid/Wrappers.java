package squid;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes around and atCall hooks into a class's bytecode (see {@link squid.api.Squid#around} and
 * {@link squid.api.Squid#atCall}).
 *
 * around: the method's own code moves to a hidden method of its own, and the method itself becomes
 * {@code return Hooks.around(id, this, args, null, <invoker>, <return type>);}. The invoker is a small hidden static
 * method that unpacks an Object[] and calls the moved code, and it's handed over as a method handle constant, which
 * Java looks up once, so a hook costs no lookups while the game runs.
 *
 * atCall: each matching call inside the method is replaced the same way, with an invoker that makes the call.
 */
final class Wrappers {
    private Wrappers() {
    }

    private static final String OBJECT = "java/lang/Object";
    private static final String HOOKS = "squid/Hooks";
    /** Hooks.around(int id, Object self, Object[] args, Object caller, MethodHandle original, Class returns) */
    private static final String AROUND_DESC = "(ILjava/lang/Object;[Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;Ljava/lang/Class;)Ljava/lang/Object;";
    private static final String INVOKER_DESC = "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";

    /** Wraps every method with this name (and descriptor, if given). Gives back how many it wrapped. */
    static int around(ClassNode node, String method, String descriptor, int hookId) {
        boolean itf = (node.access & Opcodes.ACC_INTERFACE) != 0;
        List<MethodNode> targets = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method) || (descriptor != null && !m.desc.equals(descriptor))) continue;
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) continue;
            if (m.name.startsWith("<")) continue; // a constructor's code can't move to another method
            targets.add(m);
        }
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (MethodNode m : node.methods) taken.add(m.name);
        int wrapped = 0;
        for (int index = 0; index < targets.size(); index++) {
            MethodNode m = targets.get(index);
            boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
            // Each overload (over(int), over(long)...) gets names of its own: two methods with one name and the same
            // arguments would stop the class from loading at all
            String bodyName = "squid$" + m.name + "$" + hookId + "$" + index;
            if (taken.contains(bodyName) || taken.contains(bodyName + "$run")) {
                System.out.println("[Squid] Warning: couldn't wrap " + node.name + "." + m.name + m.desc + ": its hidden copy's name is taken");
                continue;
            }
            taken.add(bodyName);
            taken.add(bodyName + "$run");
            wrapped++;
            // 1. The method's own code moves to a hidden method with the same arguments
            MethodNode body = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | (m.access & Opcodes.ACC_STATIC),
                    bodyName, m.desc, m.signature, m.exceptions == null ? null : m.exceptions.toArray(String[]::new));
            body.instructions = m.instructions;
            body.tryCatchBlocks = m.tryCatchBlocks;
            body.localVariables = m.localVariables;
            body.visibleLocalVariableAnnotations = m.visibleLocalVariableAnnotations;
            body.invisibleLocalVariableAnnotations = m.invisibleLocalVariableAnnotations;
            body.maxLocals = m.maxLocals;
            body.maxStack = m.maxStack;
            node.methods.add(body);
            // 2. The invoker that calls it
            int bodyOp = isStatic ? Opcodes.INVOKESTATIC : itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKESPECIAL;
            Handle invoker = invoker(node, bodyName + "$run", bodyOp, node.name, bodyName, m.desc, itf, itf);
            // 3. The method hands itself to the hook
            m.instructions = new InsnList();
            m.tryCatchBlocks = new ArrayList<>();
            m.localVariables = null;
            m.visibleLocalVariableAnnotations = null;
            m.invisibleLocalVariableAnnotations = null;
            InsnList code = m.instructions;
            Type returns = Type.getReturnType(m.desc);
            code.add(new LdcInsnNode(hookId));
            code.add(isStatic ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
            Type[] params = Type.getArgumentTypes(m.desc);
            code.add(new LdcInsnNode(params.length));
            code.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
            int slot = isStatic ? 0 : 1;
            for (int i = 0; i < params.length; i++) {
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode(i));
                code.add(new VarInsnNode(params[i].getOpcode(Opcodes.ILOAD), slot));
                box(code, params[i]);
                code.add(new InsnNode(Opcodes.AASTORE));
                slot += params[i].getSize();
            }
            code.add(new InsnNode(Opcodes.ACONST_NULL)); // no caller: that's for atCall
            code.add(new LdcInsnNode(invoker));
            pushClass(code, returns);
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "around", AROUND_DESC, false));
            returnIt(code, returns);
        }
        return wrapped;
    }

    /**
     * The runners atCall hooks have written into one class so far, and which method each one's call came from (its
     * name and descriptor). A second mod wrapping the same call finds it inside the first one's runner through this.
     * Made fresh for each class as it's patched.
     */
    static final class Runners {
        private final Map<String, String[]> callers = new HashMap<>();

        boolean from(String runner, String method, String descriptor) {
            String[] caller = callers.get(runner);
            return caller != null && caller[0].equals(method) && (descriptor == null || caller[1].equals(descriptor));
        }
    }

    /**
     * Wraps every call to calledClass.calledMethod made inside the methods with this name. calledMethod can carry a
     * descriptor, like "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
     * and calledClass can be null for any class. A call through a subclass (ClientLevel for Level) counts too.
     * Gives back how many calls it wrapped.
     */
    static int atCall(ClassNode node, String method, String descriptor, String calledClass, String calledMethod, int hookId,
                      ClassLoader loader, Runners runners) {
        boolean itf = (node.access & Opcodes.ACC_INTERFACE) != 0;
        String wantedOwner = calledClass == null ? null : calledClass.replace('.', '/');
        int paren = calledMethod.indexOf('(');
        String wantedName = paren < 0 ? calledMethod : calledMethod.substring(0, paren);
        String wantedDesc = paren < 0 ? null : calledMethod.substring(paren);
        // A call another mod's atCall already wrapped sits in that hook's runner now, so those are looked in too
        List<MethodNode> callers = new ArrayList<>();
        Map<MethodNode, String[]> callerOf = new java.util.IdentityHashMap<>(); // the real method each one stands for
        for (MethodNode m : node.methods) {
            boolean named = m.name.equals(method) && (descriptor == null || m.desc.equals(descriptor));
            if (!named && !runners.from(m.name, method, descriptor)) continue;
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_BRIDGE)) != 0) continue;
            callers.add(m);
            callerOf.put(m, named ? new String[] {m.name, m.desc} : runners.callers.get(m.name));
        }
        Map<String, Boolean> subtypes = new HashMap<>();
        Map<String, Handle> invokers = new HashMap<>(); // one invoker per kind of call, shared by every place it's made
        int wrapped = 0;
        for (MethodNode m : callers) {
            boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
            boolean constructor = m.name.startsWith("<");
            int base = m.maxLocals; // fresh local variables to hold the call's values while they're packed up
            int extra = 0;
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (!call.name.equals(wantedName) || (wantedDesc != null && !call.desc.equals(wantedDesc))) continue;
                if (call.owner.startsWith("squid/") || call.name.startsWith("<")) continue;
                if (wantedOwner != null && !call.owner.equals(wantedOwner)
                        && !subtypes.computeIfAbsent(call.owner, o -> isSubtype(o, wantedOwner, loader))) continue;
                int op = call.getOpcode();
                if (op == Opcodes.INVOKESPECIAL) {
                    if (!call.owner.equals(node.name)) {
                        System.out.println("[Squid] Warning: atCall can't wrap the super." + call.name + " call in " + node.name + "." + m.name);
                        continue;
                    }
                    op = call.itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL; // a private method of the class itself
                }
                boolean hasTarget = op != Opcodes.INVOKESTATIC;
                String key = op + " " + call.owner + "." + call.name + call.desc;
                int opcode = op;
                String[] realCaller = callerOf.get(m);
                Handle invoker = invokers.computeIfAbsent(key, k -> {
                    String runner = "squid$atcall$" + hookId + "$" + invokers.size();
                    runners.callers.put(runner, realCaller);
                    return invoker(node, runner, opcode, call.owner, call.name, call.desc, call.itf, itf);
                });
                // Store the arguments (last first) and the target in the fresh locals, then pack them up for the hook
                Type[] params = Type.getArgumentTypes(call.desc);
                int[] slots = new int[params.length];
                int next = base;
                int targetSlot = -1;
                if (hasTarget) targetSlot = next++;
                for (int i = 0; i < params.length; i++) {
                    slots[i] = next;
                    next += params[i].getSize();
                }
                extra = Math.max(extra, next - base);
                InsnList code = new InsnList();
                for (int i = params.length - 1; i >= 0; i--) code.add(new VarInsnNode(params[i].getOpcode(Opcodes.ISTORE), slots[i]));
                if (hasTarget) code.add(new VarInsnNode(Opcodes.ASTORE, targetSlot));
                code.add(new LdcInsnNode(hookId));
                code.add(hasTarget ? new VarInsnNode(Opcodes.ALOAD, targetSlot) : new InsnNode(Opcodes.ACONST_NULL));
                code.add(new LdcInsnNode(params.length));
                code.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
                for (int i = 0; i < params.length; i++) {
                    code.add(new InsnNode(Opcodes.DUP));
                    code.add(new LdcInsnNode(i));
                    code.add(new VarInsnNode(params[i].getOpcode(Opcodes.ILOAD), slots[i]));
                    box(code, params[i]);
                    code.add(new InsnNode(Opcodes.AASTORE));
                }
                // The caller is "this", except in static methods and constructors (where it isn't ready to be handed out)
                boolean callerReady = !isStatic && !constructor && !m.name.startsWith("squid$");
                code.add(callerReady ? new VarInsnNode(Opcodes.ALOAD, 0) : new InsnNode(Opcodes.ACONST_NULL));
                code.add(new LdcInsnNode(invoker));
                Type returns = Type.getReturnType(call.desc);
                pushClass(code, returns);
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "around", AROUND_DESC, false));
                if (returns.getSort() == Type.VOID) code.add(new InsnNode(Opcodes.POP));
                else unboxOrCast(code, returns);
                m.instructions.insert(call, code);
                m.instructions.remove(call);
                wrapped++;
            }
            m.maxLocals = base + extra;
        }
        return wrapped;
    }

    /**
     * Adds a hidden static method to the class: Object name(Object target, Object[] args), which checks and unpacks
     * the arguments and makes one call. Values of the wrong kind are explained in plain words (Hooks.badValue), and
     * numbers of any kind are turned into the kind needed. Gives back a method handle constant for it.
     */
    private static Handle invoker(ClassNode node, String name, int op, String owner, String method, String desc, boolean ownerItf, boolean classItf) {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, INVOKER_DESC, null, null);
        InsnList code = m.instructions;
        Type[] params = Type.getArgumentTypes(desc);
        LabelNode countOk = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new LdcInsnNode(params.length));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, countOk));
        code.add(new LdcInsnNode(params.length));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "wrongCount", "(I[Ljava/lang/Object;)Ljava/lang/RuntimeException;", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        code.add(countOk);
        LabelNode unpackStart = new LabelNode();
        LabelNode unpackEnd = new LabelNode();
        LabelNode wrongKind = new LabelNode();
        code.add(unpackStart);
        if (op != Opcodes.INVOKESTATIC) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        }
        for (int i = 0; i < params.length; i++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new LdcInsnNode(i));
            code.add(new InsnNode(Opcodes.AALOAD));
            convert(code, params[i]);
        }
        code.add(unpackEnd);
        code.add(new MethodInsnNode(op, owner, method, desc, ownerItf));
        Type returns = Type.getReturnType(desc);
        if (returns.getSort() == Type.VOID) code.add(new InsnNode(Opcodes.ACONST_NULL));
        else box(code, returns);
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(wrongKind);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "badValue", "(Ljava/lang/Throwable;)Ljava/lang/RuntimeException;", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        m.tryCatchBlocks.add(new TryCatchBlockNode(unpackStart, unpackEnd, wrongKind, "java/lang/ClassCastException"));
        node.methods.add(m);
        return new Handle(Opcodes.H_INVOKESTATIC, node.name, name, INVOKER_DESC, classItf);
    }

    /** Turns an Object from the arguments array into the type needed: numbers through Hooks.toInt and friends. */
    private static void convert(InsnList code, Type type) {
        String helper = switch (type.getSort()) {
            case Type.BOOLEAN -> "toBoolean";
            case Type.CHAR -> "toChar";
            case Type.BYTE -> "toByte";
            case Type.SHORT -> "toShort";
            case Type.INT -> "toInt";
            case Type.FLOAT -> "toFloat";
            case Type.LONG -> "toLong";
            case Type.DOUBLE -> "toDouble";
            default -> null;
        };
        if (helper == null) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
        } else {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, helper, "(Ljava/lang/Object;)" + type.getDescriptor(), false));
        }
    }

    /** Pushes the Class for a type: int.class is Integer.TYPE, and void.class is Void.TYPE. */
    private static void pushClass(InsnList code, Type type) {
        Type boxed = type.getSort() == Type.VOID ? Type.getType(Void.class) : boxedType(type);
        if (boxed != null) code.add(new FieldInsnNode(Opcodes.GETSTATIC, boxed.getInternalName(), "TYPE", "Ljava/lang/Class;"));
        else code.add(new LdcInsnNode(type));
    }

    /** Returns the Object on the stack as the method's return type (Hooks.around already checked it fits). */
    private static void returnIt(InsnList code, Type type) {
        if (type.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.POP));
            code.add(new InsnNode(Opcodes.RETURN));
            return;
        }
        unboxOrCast(code, type);
        code.add(new InsnNode(type.getOpcode(Opcodes.IRETURN)));
    }

    private static void box(InsnList code, Type type) {
        Type boxed = boxedType(type);
        if (boxed == null) return;
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed.getInternalName(), "valueOf", Type.getMethodDescriptor(boxed, type), false));
    }

    private static void unboxOrCast(InsnList code, Type type) {
        Type boxed = boxedType(type);
        if (boxed == null) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
            return;
        }
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, boxed.getInternalName()));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed.getInternalName(), type.getClassName() + "Value", Type.getMethodDescriptor(type), false));
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
     * Whether a class is the wanted class or comes from it (its parent, its parent's parent, or an interface along
     * the way), read from the class files so nothing loads early.
     */
    private static boolean isSubtype(String name, String wanted, ClassLoader loader) {
        if (name.equals(wanted)) return true;
        if (name.equals(OBJECT) || name.startsWith("[")) return false;
        try (InputStream in = loader.getResourceAsStream(name + ".class")) {
            if (in == null) return false;
            ClassReader reader = new ClassReader(in);
            String parent = reader.getSuperName();
            if (parent != null && isSubtype(parent, wanted, loader)) return true;
            for (String i : reader.getInterfaces()) {
                if (isSubtype(i, wanted, loader)) return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
