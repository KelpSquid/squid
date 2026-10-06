package squid.api;

/**
 * The main class of every Squid mod. Squid creates it once, before Minecraft starts,
 * and calls {@link #init} so the mod can set up its hooks.
 */
public interface SquidMod {
    void init(Squid squid);
}
