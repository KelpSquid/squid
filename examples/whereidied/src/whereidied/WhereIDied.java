package whereidied;

import squid.api.EasyMod;

/**
 * When you die, this remembers where, and shows how far away your things are until you get back to them (even after
 * quitting the game). It only shows in the dimension you died in. !died says where it was.
 */
public class WhereIDied extends EasyMod {
    private int[] spot; // where you died (x, y, z), until you get back there
    private int place;  // which dimension that was (its name's hash, since remember keeps numbers)

    void start() {
        if (remembered("waiting", 0) == 1) {
            spot = new int[] {remembered("x", 0), remembered("y", 0), remembered("z", 0)};
            place = remembered("dimension", 0);
        }
        onDeath(() -> {
            spot = new int[] {x(), y(), z()};
            place = dimension().hashCode();
            remember("x", spot[0]);
            remember("y", spot[1]);
            remember("z", spot[2]);
            remember("dimension", place);
            remember("waiting", 1);
            say("You died at " + spot[0] + ", " + spot[1] + ", " + spot[2] + ". Your things are there!");
        });
        keepShowing(() -> !here() ? "" : "Your things: " + spot[0] + " " + spot[1] + " " + spot[2] + " (" + blocksAway() + " blocks)");
        // Back at the spot: found them
        every(1, () -> {
            if (here() && blocksAway() < 3 && Math.abs(spot[1] - y()) < 4) {
                spot = null;
                remember("waiting", 0);
                title("", "You found your things!");
            }
        });
        onCommand("died", () -> say(spot == null ? "Nothing to go back for right now."
                : "You died at " + spot[0] + ", " + spot[1] + ", " + spot[2] + (here() ? ", " + blocksAway() + " blocks away." : " in another dimension.")));
    }

    /** Whether there's a spot to go back to, in the dimension you're in. */
    private boolean here() {
        return spot != null && dimension().hashCode() == place;
    }

    private int blocksAway() {
        int dx = spot[0] - x();
        int dz = spot[2] - z();
        return (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
    }
}
