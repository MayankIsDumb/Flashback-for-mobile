package com.moulberry.flashback.packet;

import com.moulberry.flashback.Flashback;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FlashbackRemoteCameraType(int entityId, byte cameraType) implements CustomPacketPayload {
    public static final Type<FlashbackRemoteCameraType> TYPE = new Type<>(Flashback.createIdentifier("remote_camera_type"));

    public static final StreamCodec<FriendlyByteBuf, FlashbackRemoteCameraType> STREAM_CODEC = new RemoteCameraTypeStreamCodec();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static class RemoteCameraTypeStreamCodec implements StreamCodec<FriendlyByteBuf, FlashbackRemoteCameraType> {
        @Override
        public FlashbackRemoteCameraType decode(FriendlyByteBuf friendlyByteBuf) {
            int entityId = friendlyByteBuf.readVarInt();
            // Backward-compatible: default to first-person when the byte is absent
            byte cameraType = friendlyByteBuf.isReadable() ? friendlyByteBuf.readByte() : 0;
            if (cameraType < 0 || cameraType > 2) {
                cameraType = 0;
            }
            return new FlashbackRemoteCameraType(entityId, cameraType);
        }

        @Override
        public void encode(FriendlyByteBuf friendlyByteBuf, FlashbackRemoteCameraType remoteCameraType) {
            friendlyByteBuf.writeVarInt(remoteCameraType.entityId);
            friendlyByteBuf.writeByte(remoteCameraType.cameraType);
        }
    }

}
