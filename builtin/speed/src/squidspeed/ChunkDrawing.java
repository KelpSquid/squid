package squidspeed;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import squid.api.Squid;

/**
 * Faster chunk drawing. Every frame, for every chunk piece on screen (thousands of them), Minecraft looks up where its
 * blocks sit on the graphics card in a HashMap, twice per layer. With a big render distance that lookup alone was a
 * quarter of the game's main thread in Squid's speed test.
 *
 * The keys are the chunk pieces' meshes, which are only ever equal to themselves, so an identity map finds exactly the
 * same things. fastutil's (which Minecraft already uses) keeps keys and values side by side in two arrays instead of
 * a node per entry, so a lookup touches one or two spots in memory instead of chasing pointers.
 */
final class ChunkDrawing {
    private static final String BUFFER = "com.mojang.blaze3d.vertex.UberGpuBuffer";
    private static final String FAST_MAP = "it/unimi/dsi/fastutil/objects/Reference2ObjectOpenHashMap";

    private ChunkDrawing() {
    }

    static void install(Squid squid) {
        squid.patch("net.minecraft.client.renderer.LevelRenderer", ChunkDrawing::emptyLayersFirst);
        squid.patch(BUFFER, node -> {
            boolean done = false;
            for (MethodNode method : node.methods) {
                if (!method.name.equals("<init>")) continue;
                TypeInsnNode made = null;
                for (AbstractInsnNode insn : method.instructions) {
                    // new HashMap(256), put in the allocationMap field: the map the lookups use
                    if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                        made = type.desc.equals("java/util/HashMap") ? type : null;
                    } else if (made != null && insn instanceof MethodInsnNode init && init.owner.equals("java/util/HashMap")
                            && init.name.equals("<init>") && (init.desc.equals("()V") || init.desc.equals("(I)V"))) {
                        if (init.getNext() instanceof FieldInsnNode field && field.name.equals("allocationMap")) {
                            made.desc = FAST_MAP;
                            init.owner = FAST_MAP;
                            done = true;
                        }
                        made = null;
                    }
                }
            }
            if (!done) System.out.println("[Squid Speed] Minecraft's chunk buffers changed; faster chunk drawing is off.");
            oneHeapShortcut(node);
        });
    }

    /**
     * For every chunk piece on screen and every layer (solid, cutout, see-through...), Minecraft looked up where that
     * layer sits on the graphics card first, and only then checked whether the piece has anything in that layer. Most
     * pieces only have one or two, so most of those lookups (each a couple of slow trips to memory) found nothing. This
     * checks first: right after the layer's draw is fetched, an empty one skips to the next layer, the way the check
     * further down would have.
     *
     * draw = mesh.getSectionDraw(layer); if (draw == null) continue;
     */
    private static void emptyLayersFirst(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals("extractSectionDrawGroups")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.name.equals("getSectionDraw")
                        || !(call.getNext() instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
                // the "continue" the original check jumps to: the first "if (draw == null)" after this
                LabelNode next = null;
                for (AbstractInsnNode later = store.getNext(); later != null; later = later.getNext()) {
                    if (later instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == store.var
                            && load.getNext() instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL) {
                        next = jump.label;
                        break;
                    }
                }
                if (next == null) return; // Minecraft changed: leave it as it is
                InsnList check = new InsnList();
                check.add(new VarInsnNode(Opcodes.ALOAD, store.var));
                check.add(new JumpInsnNode(Opcodes.IFNULL, next));
                method.instructions.insert(store, check);
                return;
            }
        }
    }

    /**
     * Which graphics card buffer a chunk piece's blocks are in: Minecraft goes from the piece's own allocation to its
     * block of memory to its heap, three objects scattered around memory for each of thousands of pieces a frame, so
     * almost every step waits on memory. Most of the time a chunk buffer has just one heap, so the answer is that one,
     * from the same few objects every time, which stay in the processor's cache.
     *
     * if (nodes.size() == 1) return nodes.get(0).getSecond().gpuBuffer;
     */
    private static void oneHeapShortcut(ClassNode node) {
        String self = node.name;
        String heap = self + "$UberGpuBufferHeap";
        for (MethodNode method : node.methods) {
            if (!method.name.equals("getGpuBuffer") || !method.desc.equals("(Lcom/mojang/blaze3d/vertex/TlsfAllocator$Allocation;)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;")) continue;
            boolean known = node.fields.stream().anyMatch(f -> f.name.equals("nodes") && f.desc.equals("Ljava/util/List;"));
            if (!known) return;
            InsnList code = new InsnList();
            LabelNode usual = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, self, "nodes", "Ljava/util/List;"));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, usual));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, self, "nodes", "Ljava/util/List;"));
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/mojang/datafixers/util/Pair"));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/mojang/datafixers/util/Pair", "getSecond", "()Ljava/lang/Object;", false));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, heap));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, heap, "gpuBuffer", "Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"));
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(usual);
            method.instructions.insert(code);
        }
    }
}
