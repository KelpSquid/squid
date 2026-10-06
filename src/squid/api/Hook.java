package squid.api;

/** Code a mod runs at the start or end of a Minecraft method. */
@FunctionalInterface
public interface Hook {
    void run(Call call);
}
