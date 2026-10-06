package squid.api;

/** One call of a hooked Minecraft method, handed to the hook so it can look at it or change it. */
public final class Call {
    private final Object self;
    private final Object[] args;
    private Object returnValue;
    private boolean cancelled;

    public Call(Object self, Object[] args, Object returnValue) {
        this.self = self;
        this.args = args;
        this.returnValue = returnValue;
    }

    /** The object the method was called on, or null for static methods. */
    public Object self() {
        return self;
    }

    /** The method's arguments. Numbers and booleans come boxed, like Integer instead of int. */
    public Object[] args() {
        return args;
    }

    /** In an end hook: what the method is about to return. */
    public Object returnValue() {
        return returnValue;
    }

    /** In an end hook: return this instead. */
    public void setReturnValue(Object value) {
        this.returnValue = value;
    }

    /** In a start hook: skip the rest of the method (for methods that return nothing). */
    public void cancel() {
        cancel(null);
    }

    /** In a start hook: skip the rest of the method and return this instead. */
    public void cancel(Object returnValue) {
        this.cancelled = true;
        this.returnValue = returnValue;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
