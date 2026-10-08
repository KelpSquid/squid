package squid.api;

import squid.Lang;
import squid.Main;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * A screen of a mod's own, made without knowing anything about Minecraft's screens: a title, then buttons, on/off
 * switches, sliders, text boxes and labels, one under the other, and a Done button. Make one with
 * {@link Squid#screen} (or screen() in an easy mod), add what it needs, and open it:
 *
 * <pre>
 * squid.screen("Weather")
 *         .label("What should the sky do?")
 *         .button("Sun", () -&gt; squid.log("Sunny!"))
 *         .toggle("Thunder", false, on -&gt; ...)
 *         .slider("Rain chance", 0, 100, 50, chance -&gt; ...)
 *         .textBox("Message", "", text -&gt; ...)
 *         .open();
 * </pre>
 *
 * The actions run on the game's own thread. One that breaks is explained in the log and the others keep working.
 */
public final class ModScreen {
    /** One thing on the screen. Squid Mods (which draws the screen) reads these. */
    public sealed interface Item permits Label, Button, Toggle, Slider, TextBox {
    }

    public record Label(Supplier<Object> text) implements Item {
    }

    public record Button(String label, Runnable onClick) implements Item {
    }

    public record Toggle(String label, boolean startOn, Consumer<Boolean> onChange) implements Item {
    }

    public record Slider(String label, int min, int max, int start, IntConsumer onChange) implements Item {
    }

    public record TextBox(String label, String start, Consumer<String> onChange) implements Item {
    }

    private final String modId;
    private final String title;
    private final List<Item> items = new ArrayList<>();
    private Runnable onClose;
    private final AtomicInteger failures = new AtomicInteger();

    public ModScreen(String modId, String title) {
        this.modId = modId;
        this.title = String.valueOf(title);
    }

    /** A line of text. */
    public ModScreen label(Object text) {
        String fixed = String.valueOf(text);
        items.add(new Label(() -> fixed));
        return this;
    }

    /** A line of text that keeps up to date: label(() -&gt; "Score: " + score). */
    public ModScreen label(Supplier<Object> text) {
        items.add(new Label(text));
        return this;
    }

    /** A button. */
    public ModScreen button(String label, Runnable onClick) {
        items.add(new Button(String.valueOf(label), onClick));
        return this;
    }

    /** An on/off switch, which says ON or OFF. onChange gets true when it's switched on. */
    public ModScreen toggle(String label, boolean startOn, Consumer<Boolean> onChange) {
        items.add(new Toggle(String.valueOf(label), startOn, onChange));
        return this;
    }

    /** A slider for a whole number from min to max. onChange gets the number as it's dragged. */
    public ModScreen slider(String label, int min, int max, int start, IntConsumer onChange) {
        if (max < min) throw new IllegalArgumentException(Lang.t("a slider's max ({0}) is smaller than its min ({1})", max, min));
        items.add(new Slider(String.valueOf(label), min, max, Math.max(min, Math.min(max, start)), onChange));
        return this;
    }

    /** A box to type in, with the label shown in it while it's empty. onChange gets the text as it's typed. */
    public ModScreen textBox(String label, String start, Consumer<String> onChange) {
        items.add(new TextBox(String.valueOf(label), start == null ? "" : start, onChange));
        return this;
    }

    /** Runs when the screen closes (Done, or Esc). */
    public ModScreen onClose(Runnable action) {
        this.onClose = action;
        return this;
    }

    /** Opens the screen (in place of whatever screen is open). */
    public void open() {
        Consumer<ModScreen> opener = squid.Screens.opener;
        if (opener == null || !Main.gameStarted()) {
            throw new IllegalStateException(Lang.t("a screen can only open while the game is running, with Squid Mods on"));
        }
        opener.accept(this);
    }

    public String title() {
        return title;
    }

    /** What's on the screen, in order. */
    public List<Item> items() {
        return List.copyOf(items);
    }

    /** Runs when the screen closes. Squid Mods calls this. */
    public void closed() {
        if (onClose != null) safely(onClose);
    }

    /**
     * Runs one of the mod's actions. If it breaks it's said in the log (and Kelp), and after 3 breaks the screen's
     * actions stop running, so a broken mod can't keep breaking the screen.
     */
    public void safely(Runnable action) {
        if (action == null || failures.get() >= 3) return;
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            int count = failures.incrementAndGet();
            System.out.println("[" + modId + "] Something on the screen \"" + title + "\" went wrong:");
            e.printStackTrace(System.out);
            if (count == 3) squid.Screens.problem(modId, e);
        }
    }

    /** A label's text right now ("" if its code breaks). */
    public String text(Label label) {
        String[] text = {""};
        safely(() -> text[0] = String.valueOf(label.text().get()));
        return text[0];
    }
}
