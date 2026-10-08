package squidprofile;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A player's emblem, Call of Duty style: layers of shapes stacked on top of each other, each with its own color,
 * place, size, turn and flip, drawn into a little 64x64 pixel picture that shows next to their name.
 *
 * Better tools unlock with the Squid Count (the points Squid gives for advancements): more layers, more shapes,
 * every color, turning and flipping. {@link #UNLOCKS} lists what unlocks when.
 *
 * Each player's emblem is a small text file in the emblems folder in Kelp's folder (shared by every instance), one
 * layer per line, bottom layer first.
 */
public final class Emblem {
    public static final int SIZE = 64;

    /** The shapes, and how many Squid Count points each needs. Each is drawn in a box from -1 to 1. */
    public enum Shape {
        CIRCLE("Circle", 0), SQUARE("Square", 0), TRIANGLE("Triangle", 0), STAR("Star", 0),
        HEART("Heart", 50), DIAMOND("Diamond", 50), RING("Ring", 100), STRIPE("Stripe", 100), CHEVRON("Chevron", 150),
        SHIELD("Shield", 250), LIGHTNING("Lightning", 250), FLAME("Flame", 300), CROWN("Crown", 400), WING("Wing", 500),
        SQUID("Squid", 600), KELP("Kelp", 600), SWORD("Sword", 800), PICKAXE("Pickaxe", 1000);

        public final String label;
        public final int points;

        Shape(String label, int points) {
            this.label = label;
            this.points = points;
        }
    }

    /** What unlocks at how many Squid Count points (Samuel can change these). */
    public static final class UNLOCKS {
        /** How many layers an emblem can have, by points: 3 to start, up to 32. */
        public static final int[][] LAYERS = {{0, 3}, {50, 5}, {150, 8}, {300, 12}, {600, 20}, {1000, 32}};
        /** Turning layers. */
        public static final int TURN = 50;
        /** Flipping layers. */
        public static final int FLIP = 150;
        /** Any color (before that, the 8 basic ones). */
        public static final int ALL_COLORS = 100;

        private UNLOCKS() {
        }

        public static int layers(int points) {
            int most = 0;
            for (int[] step : LAYERS) {
                if (points >= step[0]) most = step[1];
            }
            return most;
        }
    }

    /** The 8 colors everyone has, then 8 more that come with every color. */
    public static final int[] BASIC_COLORS = {0xFFFFFF, 0x202020, 0xE03C3C, 0xF0C030, 0x3CB44B, 0x3C78E0, 0x9650DC, 0xF08228};

    /** One layer: a shape, its color, where its middle is (0 to 1 across and down), how big, turned and flipped. */
    public static final class Layer {
        public Shape shape;
        public int color;
        public double x;
        public double y;
        public double size;      // 1 fills the emblem
        public double turn;      // degrees, clockwise
        public boolean flipX;
        public boolean flipY;

        public Layer(Shape shape, int color, double x, double y, double size, double turn, boolean flipX, boolean flipY) {
            this.shape = shape;
            this.color = color;
            this.x = x;
            this.y = y;
            this.size = size;
            this.turn = turn;
            this.flipX = flipX;
            this.flipY = flipY;
        }

        public Layer copy() {
            return new Layer(shape, color, x, y, size, turn, flipX, flipY);
        }
    }

    public final List<Layer> layers = new ArrayList<>();

    /** A starting emblem: a blue circle with a white star. */
    public static Emblem starter() {
        Emblem emblem = new Emblem();
        emblem.layers.add(new Layer(Shape.CIRCLE, 0x3C78E0, 0.5, 0.5, 0.9, 0, false, false));
        emblem.layers.add(new Layer(Shape.STAR, 0xFFFFFF, 0.5, 0.52, 0.55, 0, false, false));
        return emblem;
    }

    public Emblem copy() {
        Emblem copy = new Emblem();
        for (Layer layer : layers) copy.layers.add(layer.copy());
        return copy;
    }

    // ---- Drawing ----

    /** The emblem as a 64x64 picture with a see-through background. Edges stay hard, like pixel art. */
    public BufferedImage draw() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (Layer layer : layers) {
            AffineTransform place = new AffineTransform();
            place.translate(layer.x * SIZE, layer.y * SIZE);
            place.rotate(Math.toRadians(layer.turn));
            place.scale(layer.size * SIZE / 2 * (layer.flipX ? -1 : 1), layer.size * SIZE / 2 * (layer.flipY ? -1 : 1));
            g.setColor(new java.awt.Color(layer.color & 0xFFFFFF));
            g.fill(place.createTransformedShape(outline(layer.shape)));
        }
        g.dispose();
        return image;
    }

    /** A shape's outline in a box from -1 to 1 (y goes down). */
    static java.awt.Shape outline(Shape shape) {
        return switch (shape) {
            case CIRCLE -> new Ellipse2D.Double(-1, -1, 2, 2);
            case SQUARE -> new Rectangle2D.Double(-1, -1, 2, 2);
            case TRIANGLE -> polygon(0, -1, 1, 1, -1, 1);
            case STAR -> star(5, 1, 0.42);
            case HEART -> heart();
            case DIAMOND -> polygon(0, -1, 0.7, 0, 0, 1, -0.7, 0);
            case RING -> {
                Area ring = new Area(new Ellipse2D.Double(-1, -1, 2, 2));
                ring.subtract(new Area(new Ellipse2D.Double(-0.6, -0.6, 1.2, 1.2)));
                yield ring;
            }
            case STRIPE -> new Rectangle2D.Double(-1, -0.22, 2, 0.44);
            case CHEVRON -> polygon(-1, -0.2, 0, -1, 1, -0.2, 1, 0.35, 0, -0.45, -1, 0.35);
            case SHIELD -> shield();
            case LIGHTNING -> polygon(0.15, -1, -0.65, 0.12, -0.05, 0.12, -0.25, 1, 0.65, -0.2, 0.05, -0.2, 0.35, -1);
            case FLAME -> flame();
            case CROWN -> polygon(-1, 0.7, -1, -0.5, -0.5, 0.05, 0, -0.8, 0.5, 0.05, 1, -0.5, 1, 0.7);
            case WING -> wing();
            case SQUID -> squid();
            case KELP -> kelp();
            case SWORD -> polygon(-0.12, -1, 0.12, -1, 0.12, 0.3, 0.45, 0.3, 0.45, 0.45, 0.12, 0.45, 0.12, 1, -0.12, 1,
                    -0.12, 0.45, -0.45, 0.45, -0.45, 0.3, -0.12, 0.3);
            case PICKAXE -> pickaxe();
        };
    }

    private static Path2D polygon(double... points) {
        Path2D path = new Path2D.Double();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.closePath();
        return path;
    }

    private static Path2D star(int tips, double outer, double inner) {
        double[] points = new double[tips * 4];
        for (int i = 0; i < tips * 2; i++) {
            double r = i % 2 == 0 ? outer : inner;
            double a = -Math.PI / 2 + i * Math.PI / tips;
            points[i * 2] = Math.cos(a) * r;
            points[i * 2 + 1] = Math.sin(a) * r + 0.1;
        }
        return polygon(points);
    }

    private static Path2D heart() {
        Path2D path = new Path2D.Double();
        path.moveTo(0, 1);
        path.curveTo(-0.6, 0.55, -1.1, 0.1, -1, -0.4);
        path.curveTo(-0.9, -1, -0.15, -1.05, 0, -0.45);
        path.curveTo(0.15, -1.05, 0.9, -1, 1, -0.4);
        path.curveTo(1.1, 0.1, 0.6, 0.55, 0, 1);
        path.closePath();
        return path;
    }

    private static Path2D shield() {
        Path2D path = new Path2D.Double();
        path.moveTo(-0.85, -0.9);
        path.lineTo(0.85, -0.9);
        path.lineTo(0.85, 0.05);
        path.curveTo(0.85, 0.55, 0.4, 0.85, 0, 1);
        path.curveTo(-0.4, 0.85, -0.85, 0.55, -0.85, 0.05);
        path.closePath();
        return path;
    }

    private static Path2D flame() {
        Path2D path = new Path2D.Double();
        path.moveTo(0, -1);
        path.curveTo(0.2, -0.5, 0.85, -0.2, 0.75, 0.4);
        path.curveTo(0.65, 0.85, 0.3, 1, 0, 1);
        path.curveTo(-0.3, 1, -0.65, 0.85, -0.75, 0.4);
        path.curveTo(-0.8, 0, -0.45, -0.25, -0.3, -0.5);
        path.curveTo(-0.15, -0.2, 0, -0.35, 0, -1);
        path.closePath();
        return path;
    }

    private static java.awt.Shape wing() {
        // A wing spreading left from its shoulder at the top right, its feathers stepping down
        return polygon(1, -0.45, 0.35, -0.9, -1, -0.85, -0.7, -0.5, -0.95, -0.4, -0.55, -0.1, -0.8, 0, -0.35, 0.3, -0.55, 0.42,
                -0.1, 0.65, 0.3, 0.75, 0.85, 0.25);
    }

    private static java.awt.Shape squid() {
        Area squid = new Area(polygon(0, -1, 0.45, -0.6, 0.5, 0.2, -0.5, 0.2, -0.45, -0.6)); // the mantle, pointing up
        squid.add(new Area(polygon(-0.3, -0.85, -0.75, -0.55, -0.42, -0.45))); // fins near the tip
        squid.add(new Area(polygon(0.3, -0.85, 0.75, -0.55, 0.42, -0.45)));
        for (int i = 0; i < 5; i++) { // tentacles
            double x = -0.48 + i * 0.22;
            squid.add(new Area(new Rectangle2D.Double(x, 0.2, 0.13, 0.55 + (i % 2) * 0.25)));
        }
        squid.subtract(new Area(new Rectangle2D.Double(-0.3, -0.1, 0.18, 0.2))); // eyes, see-through
        squid.subtract(new Area(new Rectangle2D.Double(0.12, -0.1, 0.18, 0.2)));
        return squid;
    }

    private static java.awt.Shape kelp() {
        // A gently waving stalk, with leaves growing out of it on alternating sides
        int steps = 16;
        double[] stalk = new double[(steps + 1) * 4];
        for (int i = 0; i <= steps; i++) {
            double y = 1 - 2.0 * i / steps;
            double x = kelpX(y);
            stalk[i * 2] = x - 0.08;
            stalk[i * 2 + 1] = y;
            stalk[(2 * steps + 1 - i) * 2] = x + 0.08;
            stalk[(2 * steps + 1 - i) * 2 + 1] = y;
        }
        Area kelp = new Area(polygon(stalk));
        double[] leaves = {0.5, -0.05, -0.6};
        for (int i = 0; i < leaves.length; i++) {
            double y = leaves[i];
            double x = kelpX(y);
            double side = i % 2 == 0 ? -1 : 1;
            kelp.add(new Area(polygon(x, y + 0.12, x + side * 0.3, y - 0.22, x + side * 0.8, y - 0.42, x + side * 0.5, y + 0.05)));
        }
        return kelp;
    }

    private static double kelpX(double y) {
        return 0.15 * Math.sin(y * 3);
    }

    private static java.awt.Shape pickaxe() {
        Area pick = new Area(polygon(-0.1, -0.55, 0.1, -0.55, 0.12, 1, -0.12, 1)); // the handle
        Path2D head = new Path2D.Double();
        head.moveTo(-1, -0.25);
        head.curveTo(-0.5, -0.9, 0.5, -0.9, 1, -0.25);
        head.lineTo(0.8, -0.2);
        head.curveTo(0.4, -0.6, -0.4, -0.6, -0.8, -0.2);
        head.closePath();
        pick.add(new Area(head));
        return pick;
    }

    // ---- Saving ----

    /**
     * Kelp's folder, shared by every instance (Kelp passes it as squid.home), where the Squid Count is too. Without
     * it, two folders up from the instance (Kelp/instances/&lt;instance&gt;), or the game folder itself.
     */
    static Path home() {
        String home = System.getProperty("squid.home");
        if (home != null) return Path.of(home);
        Path game = squid.Main.gameFolder().toAbsolutePath();
        Path parent = game.getParent();
        if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("instances") && parent.getParent() != null) {
            return parent.getParent();
        }
        return game;
    }

    /** An account's emblem file. */
    static Path file(String accountId) {
        return home().resolve("emblems").resolve(accountId.replaceAll("[^A-Za-z0-9_-]", "") + ".txt");
    }

    /** An account's emblem, or null if they haven't made one. A broken line is skipped. */
    public static Emblem load(String accountId) {
        Path file = file(accountId);
        if (!Files.exists(file)) return null;
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    public static Emblem parse(String text) {
        Emblem emblem = new Emblem();
        for (String line : text.split("\\R")) {
            String[] p = line.trim().split("\\s+");
            if (p.length != 8 || line.startsWith("#")) continue;
            try {
                emblem.layers.add(new Layer(Shape.valueOf(p[0]), Integer.parseInt(p[1], 16), clamp(Double.parseDouble(p[2]), -0.5, 1.5),
                        clamp(Double.parseDouble(p[3]), -0.5, 1.5), clamp(Double.parseDouble(p[4]), 0.05, 2), Double.parseDouble(p[5]) % 360,
                        p[6].equals("1"), p[7].equals("1")));
            } catch (IllegalArgumentException e) {
                // not a layer: skip it
            }
            if (emblem.layers.size() >= 32) break;
        }
        return emblem;
    }

    String text() {
        StringBuilder out = new StringBuilder("# Squid emblem: shape color x y size turn flipX flipY, bottom layer first\n");
        for (Layer l : layers) {
            out.append(String.format(Locale.ROOT, "%s %06X %.4f %.4f %.4f %.1f %d %d%n", l.shape.name(), l.color & 0xFFFFFF, l.x, l.y, l.size,
                    l.turn, l.flipX ? 1 : 0, l.flipY ? 1 : 0));
        }
        return out.toString();
    }

    public void save(String accountId) throws IOException {
        Path file = file(accountId);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text(), StandardCharsets.UTF_8);
        cache.remove(accountId);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- Showing next to names ----

    private static final java.util.Map<String, BufferedImage> cache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Forgets a drawn emblem, after it's deleted. */
    static void forget(String accountId) {
        cache.remove(accountId);
    }

    /** An account's emblem picture, or null if they haven't made one. */
    public static BufferedImage picture(String accountId) {
        BufferedImage known = cache.get(accountId);
        if (known != null) return known;
        Emblem emblem = load(accountId);
        if (emblem == null) return null;
        BufferedImage drawn = emblem.draw();
        cache.put(accountId, drawn);
        return drawn;
    }
}
