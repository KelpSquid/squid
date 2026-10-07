package squid;

/** Something a test mod can change, so the live reload test can see which version of the mod is running. */
public final class ReloadProbe {
    public static volatile int value;
    public static volatile int hookCalls;

    private ReloadProbe() {
    }
}
