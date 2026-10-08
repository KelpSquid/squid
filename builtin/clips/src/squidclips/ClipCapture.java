package squidclips;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Takes the clip's small pictures on the graphics card. The game's picture is shrunk to the video's size there first
 * (the way Minecraft's own Tracy frame capture does it), and only that small copy comes back, into the same few
 * buffers every time.
 *
 * Minecraft's screenshot did it the slow way for this: a new full-size buffer on the graphics card 20 times a second,
 * every pixel brought back, then shrunk on the game's own thread, with megabytes of new arrays each time for Java to
 * clean up. In Squid's speed test that cost about a third of the frame rate.
 */
final class ClipCapture implements AutoCloseable {
    /** Pictures that can be on their way back at once. With all of them busy, a picture is skipped. */
    private static final int BUFFERS = 3;

    private int width;
    private int height;
    private GpuTexture texture;
    private GpuTextureView view;
    private final GpuBuffer[] buffers = new GpuBuffer[BUFFERS];
    private final boolean[] busy = new boolean[BUFFERS];
    /** Goes up when the buffers are made again (a new size), so a picture from the old ones doesn't free a new one. */
    private int generation;

    /**
     * Copies a width x height picture of what's on screen. When it's back (a frame or two later, on the game's thread),
     * done gets its pixels: 4 bytes each (red, green, blue, alpha), the bottom row first. The ByteBuffer is only good
     * until done returns. False if the picture was skipped because every buffer is still busy.
     */
    boolean capture(RenderTarget target, int width, int height, Consumer<ByteBuffer> done) {
        RenderSystem.assertOnRenderThread();
        if (target.getColorTexture() == null) return false;
        if (width != this.width || height != this.height || texture == null) resize(width, height);
        int slot = -1;
        for (int i = 0; i < BUFFERS; i++) {
            if (!busy[i]) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return false;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass pass = encoder.createRenderPass(() -> "Squid clip picture", view, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
            pass.setUniform("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
        int used = slot;
        int made = generation;
        GpuBuffer buffer = buffers[used];
        busy[used] = true;
        encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            try (GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                done.accept(mapped.data());
            } finally {
                if (made == generation) busy[used] = false;
                else buffer.close(); // made again while this was on its way: this one's no longer kept
            }
        }, 0);
        return true;
    }

    private void resize(int width, int height) {
        close();
        this.width = width;
        this.height = height;
        GpuDevice device = RenderSystem.getDevice();
        texture = device.createTexture("Squid clip picture", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        view = device.createTextureView(texture);
        for (int i = 0; i < BUFFERS; i++) {
            buffers[i] = device.createBuffer(() -> "Squid clip picture buffer", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    (long) width * height * 4);
            busy[i] = false;
        }
    }

    /** Lets go of the graphics card's copies (a picture still on its way back finishes first). */
    @Override
    public void close() {
        if (view != null) view.close();
        if (texture != null) texture.close();
        for (int i = 0; i < BUFFERS; i++) {
            if (buffers[i] != null && !busy[i]) buffers[i].close(); // a busy one is closed when its picture is back
            buffers[i] = null;
            busy[i] = false;
        }
        generation++;
        view = null;
        texture = null;
    }
}
