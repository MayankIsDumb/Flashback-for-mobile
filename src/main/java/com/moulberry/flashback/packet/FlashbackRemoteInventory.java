package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlashbackRemoteInventory(int entityId, boolean open) implements CustomPacketPayload {
    public static final Type<FlashbackRemoteInventory> TYPE = new Type<>(Flashback.createIdentifier("remote_inventory"));

    public static final StreamCodec<FriendlyByteBuf, FlashbackRemoteInventory> STREAM_CODEC = new RemoteInventoryStreamCodec();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class RemoteInventoryStreamCodec implements StreamCodec<FriendlyByteBuf, FlashbackRemoteInventory> {
        @Override
        public FlashbackRemoteInventory decode(FriendlyByteBuf friendlyByteBuf) {
            int entityId = friendlyByteBuf.readVarInt();
            // Backward-compatible: default to closed when the flag is absent
            boolean open = friendlyByteBuf.isReadable() && friendlyByteBuf.readBoolean();
            return new FlashbackRemoteInventory(entityId, open);
        }

        @Override
        public void encode(FriendlyByteBuf friendlyByteBuf, FlashbackRemoteInventory remoteInventory) {
            friendlyByteBuf.writeVarInt(remoteInventory.entityId);
            friendlyByteBuf.writeBoolean(remoteInventory.open);
        }
    }

}
