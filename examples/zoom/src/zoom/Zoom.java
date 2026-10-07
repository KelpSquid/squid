package zoom;

import com.mojang.blaze3d.platform.InputConstants;
import squid.api.KeyBinding;
import squid.api.Squid;
import squid.api.SquidMod;

import java.lang.reflect.Field;

/** Hold Z to zoom in, like looking through a spyglass. The key can be changed in Minecraft's Controls screen. */
public class Zoom implements SquidMod {
    private static final float MAX_ZOOM = 4;   // how many times closer things look
    private static final float SPEED = 0.25f;  // how much of the way to MAX_ZOOM (or back) each frame goes

    private KeyBinding key;
    private float zoom = 1; // 1 means not zoomed. It eases toward MAX_ZOOM while Z is held, and back when it's let go.
    private Field mouseX;
    private Field mouseY;

    @Override
    public void init(Squid squid) {
        key = squid.addKeyBinding("Zoom", InputConstants.KEY_Z);

        // Minecraft works out the field of view every frame. Narrowing it makes everything look closer.
        squid.atEnd("net.minecraft.client.Camera", "calculateFov", call -> {
            float target = key.isDown() ? MAX_ZOOM : 1;
            zoom += (target - zoom) * SPEED;
            if (Math.abs(zoom - 1) < 0.001f) zoom = 1;
            call.setReturnValue((Float) call.returnValue() / zoom);
        });

        // Turn slower while zoomed, like a spyglass, so aiming doesn't feel jumpy
        squid.atStart("net.minecraft.client.MouseHandler", "turnPlayer", call -> slowDown(call.self()));
    }

    /** Shrinks the mouse movement Minecraft is about to turn the player by. */
    private void slowDown(Object mouseHandler) {
        if (zoom == 1) return;
        try {
            if (mouseX == null) {
                mouseX = field(mouseHandler, "accumulatedDX");
                mouseY = field(mouseHandler, "accumulatedDY");
            }
            mouseX.setDouble(mouseHandler, mouseX.getDouble(mouseHandler) / zoom);
            mouseY.setDouble(mouseHandler, mouseY.getDouble(mouseHandler) / zoom);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't slow down the mouse", e);
        }
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
