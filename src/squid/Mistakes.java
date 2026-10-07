package squid;

/**
 * Explains a mod's error the way a beginner can fix it: which line of their mod, and what went wrong in plain words.
 * Like "there's a problem on line 12: you divided by zero".
 */
public final class Mistakes {
    private Mistakes() {
    }

    /** modClass is the mod's main class, used to find the line in the mod's own code where things went wrong. */
    public static String explain(Throwable problem, Class<?> modClass) {
        Throwable e = problem;
        // Unwrap Java's wrappers to get to the mod's own error
        while (e.getCause() != null && (e instanceof java.lang.reflect.InvocationTargetException
                || e.getMessage() != null && e.getMessage().equals(e.getCause().toString()))) {
            e = e.getCause();
        }
        int line = modClass == null ? -1 : lineIn(e, modClass.getName());
        return line > 0 ? Lang.t("there's a problem on line {0}: {1}", line, plain(e)) : plain(e);
    }

    /** The first line of the stack that's in the mod's own class (or a class inside it, like its { } actions). */
    static int lineIn(Throwable e, String className) {
        for (StackTraceElement frame : e.getStackTrace()) {
            String c = frame.getClassName();
            if ((c.equals(className) || c.startsWith(className + "$")) && frame.getLineNumber() > 0) return frame.getLineNumber();
        }
        return -1;
    }

    static String plain(Throwable e) {
        String message = e.getMessage();
        if (e instanceof NullPointerException) return Lang.t("something was empty (null). Maybe it isn't ready yet, like before you join a world");
        if (e instanceof ArithmeticException && message != null && message.contains("zero")) return Lang.t("you divided by zero");
        if (e instanceof IndexOutOfBoundsException) return Lang.t("you asked for a spot past the end of a list");
        if (e instanceof NumberFormatException) return Lang.t("that text isn't a number ({0})", message);
        if (e instanceof ClassCastException) return Lang.t("a value was the wrong kind");
        if (e instanceof NoSuchMethodError || e instanceof NoSuchFieldError || e instanceof NoClassDefFoundError) {
            return Lang.t("this mod was made for a different Minecraft version (it can't find {0})", message);
        }
        if (e instanceof StackOverflowError) return Lang.t("something kept calling itself forever");
        // Squid's own errors for mods are already written for people
        if ((e instanceof IllegalArgumentException || e instanceof IllegalStateException) && message != null) return message;
        return e.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
