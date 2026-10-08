package squid.api;

/**
 * Code a mod wraps around a Minecraft method ({@link Squid#around}) or around one call made inside a method
 * ({@link Squid#atCall}). It gets the call (its object and arguments, which it can change) and the original, which it
 * can run as many times as it likes, or not at all. What it gives back is what the method (or the call) gives back:
 *
 * <pre>
 * squid.around("net.minecraft.world.entity.LivingEntity", "getMaxHealth", (call, original) -&gt;
 *         (float) original.call() * 2);
 * </pre>
 *
 * For a method that gives back nothing, give back null. If the hook breaks, Squid runs the original instead, and
 * after 3 breaks the hook is switched off.
 */
@FunctionalInterface
public interface Around {
    Object run(Call call, Original original);
}
