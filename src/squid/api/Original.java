package squid.api;

/**
 * The original method (or call) an {@link Around} hook wraps. Running it is up to the hook: once, a few times, with
 * other arguments, or not at all. Numbers can be given as any kind of number (5 works where 5.0f is wanted).
 */
public interface Original {
    /** Runs the original with the call's arguments as they are now (a hook may have changed {@link Call#args()}). */
    Object call();

    /** Runs the original with these arguments instead. */
    Object call(Object... args);

    /**
     * Runs the original on another object with these arguments. For {@link Squid#atCall} that's another target for
     * the call; for {@link Squid#around} another object of the same class.
     */
    Object callOn(Object target, Object... args);

    /** How many times the hook has run the original so far in this call. */
    int timesCalled();
}
