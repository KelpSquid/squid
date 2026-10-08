package demo;

/** Methods for the around and atCall tests to wrap. */
public class Wrapped {
    public int count;
    private String secret = "squid";

    public int add(int a, int b) { count++; return a + b; }
    public static String shout(String s) { return s.toUpperCase(); }
    public void touch() { count++; }
    public float half(float f) { return f / 2; }
    public long sum(long a, double b, int[] rest) { return a + (long) b + rest.length; }
    public String name() { return "wrapped"; }
    public int pick(int x) { return x; }
    public int risky(int x) throws java.io.IOException { if (x < 0) throw new java.io.IOException("negative"); return x; }
    public String greetTwice(String name) { return twice(hello(name)); }
    private String hello(String name) { return "hi " + name; }
    static String twice(String s) { return s + s; }
    private int secretLength(int extra) { return secret.length() + extra; }
}
