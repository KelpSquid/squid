package demo;

public class Target {
    public int add(int a, int b) { return a + b; }
    public static String greet(String name) { if (name == null) return "nobody"; return "hi " + name; }
    public void nothing(long x, double y) { }
    public double half(double d) { return d / 2; }
    public int[] pair(int a) { return new int[] {a, a}; }
}
