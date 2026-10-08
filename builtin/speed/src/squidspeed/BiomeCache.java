package squidspeed;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import squid.api.Squid;

/**
 * Faster chunk building. Grass, leaves and water get their color by blending the biomes around each block (5 x 5 of
 * them at Minecraft's normal blend), so building one chunk piece asked "which biome is here?" thousands of times for
 * the same few hundred spots, and each answer is a small sum Minecraft works out again every time. In the speed test
 * that was a sixteenth of the chunk builders' work.
 *
 * While a chunk piece is being built, each answer is kept, so neighbors reuse it. The kept answers are thrown away
 * when the next piece starts, so a biome changed by a command shows the next time the piece is built, as before.
 * Outside of building a piece (on the game's own thread, say) nothing is kept.
 */
public final class BiomeCache {
    private static final int SIZE = 2048; // a power of two
    private static final ThreadLocal<BiomeCache> CACHE = ThreadLocal.withInitial(BiomeCache::new);

    private final long[] keys = new long[SIZE];
    private final Object[] biomes = new Object[SIZE];
    private Object level;
    private boolean building;

    private BiomeCache() {
    }

    static void install(Squid squid) {
        squid.patch("net.minecraft.client.multiplayer.ClientLevel", node -> {
            for (MethodNode method : node.methods) {
                if (!method.name.equals("calculateBlockTint")) continue;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call && call.name.equals("getBiome")
                            && call.desc.equals("(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;")) {
                        call.setOpcode(Opcodes.INVOKESTATIC);
                        call.owner = "squidspeed/BiomeCache";
                        call.name = "biome";
                        call.desc = "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;";
                        call.itf = false;
                    }
                }
            }
        });
        squid.atStart("net.minecraft.client.renderer.chunk.SectionCompiler", "compile", call -> start());
        squid.atEnd("net.minecraft.client.renderer.chunk.SectionCompiler", "compile", call -> CACHE.get().building = false);
    }

    private static void start() {
        BiomeCache cache = CACHE.get();
        java.util.Arrays.fill(cache.biomes, null);
        cache.level = null;
        cache.building = true;
    }

    /** level.getBiome(pos), kept while the chunk piece this thread is building is being built. Called from Minecraft's code. */
    @SuppressWarnings("unchecked")
    public static Holder<Biome> biome(LevelReader level, BlockPos pos) {
        BiomeCache cache = CACHE.get();
        if (!cache.building) return level.getBiome(pos);
        long key = pos.asLong();
        int slot = (int) (key ^ key >>> 29 ^ key >>> 47) * 0x9E3779B9 >>> 21; // top 11 bits of a mixed hash: 0 to 2047
        if (cache.level == level && cache.keys[slot] == key && cache.biomes[slot] != null) return (Holder<Biome>) cache.biomes[slot];
        Holder<Biome> biome = level.getBiome(pos);
        if (cache.level != level) {
            java.util.Arrays.fill(cache.biomes, null);
            cache.level = level;
        }
        cache.keys[slot] = key;
        cache.biomes[slot] = biome;
        return biome;
    }
}
