package squidnet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A Squid message riding in one of Minecraft's custom payload packets: a channel name ("squid:voice") and bytes. */
public record SquidPayload(Identifier id, byte[] data) implements CustomPacketPayload {
    @Override
    public Type<SquidPayload> type() {
        return new Type<>(id);
    }

    /** How a Squid message is written and read: just its bytes (the channel name is written by Minecraft). */
    public static <B extends FriendlyByteBuf> StreamCodec<B, SquidPayload> codec(Identifier id, int max) {
        return StreamCodec.of((buf, payload) -> buf.writeBytes(payload.data()), buf -> {
            int size = buf.readableBytes();
            if (size > max) throw new IllegalArgumentException("Squid message too big: " + size);
            byte[] data = new byte[size];
            buf.readBytes(data);
            return new SquidPayload(id, data);
        });
    }
}
