package squidskins;

import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import squid.Lang;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The computer's own "open file" window, for picking pictures from inside the game. Minecraft 26.3 runs on SDL,
 * and SDL has one built in, so it looks like every other app's on Windows, Mac and Linux.
 */
final class FilePicker {
    // The callback has to stay alive until SDL calls it. The previous one is freed when the next picker opens.
    private static SDL_DialogFileCallback waiting;
    private static SDL_DialogFileFilter.Buffer filters;

    private FilePicker() {
    }

    /** Opens the picker for .png pictures. picked gets the files chosen (none if cancelled), on the game's thread. */
    static synchronized void pickPictures(Consumer<List<Path>> picked) {
        Minecraft minecraft = Minecraft.getInstance();
        if (waiting != null) waiting.free();
        if (filters == null) {
            filters = SDL_DialogFileFilter.calloc(1);
            filters.get(0).name(MemoryUtil.memUTF8(Lang.t("Pictures (.png)"))).pattern(MemoryUtil.memUTF8("png"));
        }
        waiting = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
            List<Path> files = new ArrayList<>();
            if (fileList != 0) { // 0 means something went wrong; an empty list means Cancel
                for (int i = 0; ; i++) {
                    long name = MemoryUtil.memGetAddress(fileList + (long) i * org.lwjgl.system.Pointer.POINTER_SIZE);
                    if (name == 0) break;
                    files.add(Path.of(MemoryUtil.memUTF8(name)));
                }
            }
            minecraft.execute(() -> picked.accept(files)); // SDL may call back from another thread
        });
        SDLDialog.SDL_ShowOpenFileDialog(waiting, 0, minecraft.getWindow().handle(), filters, (CharSequence) null, true);
    }
}
