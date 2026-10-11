package com.qwertys.billboard.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 편집 명령 조각. 클라이언트→서버 패킷은 한 번에 약 32KB 까지라서
 * 이미지가 들어간 명령은 여러 조각으로 나눠 보내고 서버에서 다시 합친다.
 */
public record EditChunkPacket(int transferId, int index, int total, byte[] data) {

    public static void encode(EditChunkPacket p, FriendlyByteBuf buf) {
        buf.writeVarInt(p.transferId);
        buf.writeVarInt(p.index);
        buf.writeVarInt(p.total);
        buf.writeByteArray(p.data);
    }

    public static EditChunkPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        int index = buf.readVarInt();
        int total = buf.readVarInt();
        byte[] data = buf.readByteArray(ServerEditHandler.CHUNK_SIZE);
        return new EditChunkPacket(id, index, total, data);
    }

    public static void handle(EditChunkPacket p, Supplier<NetworkEvent.Context> ctx) {
        ServerEditHandler.receive(ctx.get().getSender(), p);
    }
}
