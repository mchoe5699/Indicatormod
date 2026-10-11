package com.qwertys.billboard.client;

import com.qwertys.billboard.QwertysBillboard;
import com.qwertys.billboard.network.EditChunkPacket;
import com.qwertys.billboard.network.ModNetwork;
import com.qwertys.billboard.network.ServerEditHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** 편집 명령을 압축 → 조각으로 나눠 서버에 보낸다. */
public final class EditSender {
    private static int nextId = 1;

    /** @return 보낸 데이터 크기(바이트), 너무 커서 못 보내면 -1 */
    public static int send(BlockPos pos, String opName, CompoundTag op) {
        op.putString("Op", opName);
        op.put("Pos", NbtUtils.writeBlockPos(pos));
        byte[] all;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(op, bos);
            all = bos.toByteArray();
        } catch (Exception e) {
            QwertysBillboard.LOGGER.error("Failed to encode billboard edit", e);
            return -1;
        }
        int size = ServerEditHandler.CHUNK_SIZE;
        int total = Math.max(1, (all.length + size - 1) / size);
        if (total > ServerEditHandler.MAX_CHUNKS) return -1;
        int id = nextId++;
        for (int i = 0; i < total; i++) {
            byte[] chunk = Arrays.copyOfRange(all, i * size, Math.min(all.length, (i + 1) * size));
            ModNetwork.CHANNEL.sendToServer(new EditChunkPacket(id, i, total, chunk));
        }
        return all.length;
    }

    public static int send(BlockPos pos, String opName) {
        return send(pos, opName, new CompoundTag());
    }

    private EditSender() {}
}
