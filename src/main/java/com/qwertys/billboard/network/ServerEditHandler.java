package com.qwertys.billboard.network;

import com.qwertys.billboard.BillboardConfig;
import com.qwertys.billboard.QwertysBillboard;
import com.qwertys.billboard.block.BillboardBlock;
import com.qwertys.billboard.block.BillboardBlockEntity;
import com.qwertys.billboard.data.DisplayPart;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** 서버: 조각 재조립 + 권한/크기 검사 + 편집 명령 적용 */
public final class ServerEditHandler {
    public static final int CHUNK_SIZE = 30_000;
    public static final int MAX_CHUNKS = 100;          // 약 3MB
    private static final int MAX_TRANSFERS_PER_PLAYER = 16;
    private static final long TRANSFER_TIMEOUT_MS = 60_000;

    private static final Map<UUID, Map<Integer, Transfer>> TRANSFERS = new HashMap<>();

    private static final class Transfer {
        final int total;
        final byte[][] chunks;
        int received;
        final long started = System.currentTimeMillis();

        Transfer(int total) {
            this.total = total;
            this.chunks = new byte[total][];
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TRANSFERS.remove(event.getEntity().getUUID());
    }

    public static void receive(ServerPlayer player, EditChunkPacket p) {
        if (player == null) return;
        if (p.total() <= 0 || p.total() > MAX_CHUNKS || p.index() < 0 || p.index() >= p.total()
                || p.data().length > CHUNK_SIZE) return;

        Map<Integer, Transfer> mine = TRANSFERS.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        long now = System.currentTimeMillis();
        mine.values().removeIf(t -> now - t.started > TRANSFER_TIMEOUT_MS);

        Transfer t = mine.get(p.transferId());
        if (t == null || t.total != p.total()) {
            if (mine.size() >= MAX_TRANSFERS_PER_PLAYER) {
                Iterator<Integer> it = mine.keySet().iterator();
                it.next();
                it.remove();
            }
            t = new Transfer(p.total());
            mine.put(p.transferId(), t);
        }
        if (t.chunks[p.index()] != null) return;
        t.chunks[p.index()] = p.data();
        t.received++;
        if (t.received < t.total) return;
        mine.remove(p.transferId());

        int len = 0;
        for (byte[] c : t.chunks) len += c.length;
        byte[] all = new byte[len];
        int off = 0;
        for (byte[] c : t.chunks) {
            System.arraycopy(c, 0, all, off, c.length);
            off += c.length;
        }

        CompoundTag op;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(all))))) {
            op = NbtIo.read(in, new NbtAccounter(16_000_000L));
        } catch (Exception e) {
            QwertysBillboard.LOGGER.warn("Bad billboard edit from {}: {}", player.getGameProfile().getName(), e.toString());
            return;
        }

        try {
            apply(player, op);
        } catch (Exception e) {
            QwertysBillboard.LOGGER.error("Failed to apply billboard edit", e);
            fail(player, "error");
        }
    }

    private static void apply(ServerPlayer player, CompoundTag op) {
        BlockPos pos = NbtUtils.readBlockPos(op.getCompound("Pos"));
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos)) return;
        double maxDist = BillboardConfig.MAX_EDIT_DISTANCE.get();
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > maxDist * maxDist) {
            fail(player, "too_far");
            return;
        }
        if (!player.mayBuild() || !level.mayInteract(player, pos)) {
            fail(player, "no_permission");
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof BillboardBlockEntity be)) return;

        String type = op.getString("Op");
        int idx = op.getInt("Index");
        boolean validIdx = idx >= 0 && idx < be.parts.size();
        List<DisplayPart> backup = new ArrayList<>(be.parts);

        switch (type) {
            case EditOps.SET_PART -> {
                if (!validIdx) return;
                DisplayPart part = be.parts.get(idx);
                CompoundTag t = op.getCompound("Part");
                t.remove("Img");             // 설정 변경에서는 이미지를 바꾸지 않음
                part.load(t);
            }
            case EditOps.SET_GROUP -> be.loadGroup(op.getCompound("Group"));
            case EditOps.SET_APPEARANCE_ALL -> {
                CompoundTag t = op.getCompound("Appearance");
                for (DisplayPart part : be.parts) part.loadAppearance(t);
            }
            case EditOps.ADD_PARTS -> {
                List<DisplayPart> add = readParts(player, op.getList("Parts", Tag.TAG_COMPOUND));
                if (add == null) return;
                be.parts.addAll(add);
            }
            case EditOps.REPLACE_PART -> {
                if (!validIdx) return;
                List<DisplayPart> add = readParts(player, op.getList("Parts", Tag.TAG_COMPOUND));
                if (add == null || add.isEmpty()) return;
                be.parts.remove(idx);
                be.parts.addAll(idx, add);
            }
            case EditOps.REPLACE_IMAGE -> {
                if (!validIdx) return;
                List<DisplayPart> img = readParts(player, op.getList("Parts", Tag.TAG_COMPOUND));
                if (img == null || img.isEmpty()) return;
                DisplayPart src = img.get(0);
                // 위치·회전·외형·가로 크기는 그대로, 이미지만 교체 (복사본으로 바꿔 실패 시 되돌릴 수 있게)
                DisplayPart part = be.parts.get(idx).copy();
                part.setImage(src.image, src.imgW, src.imgH);
                if (!src.name.isEmpty()) part.name = src.name;
                be.parts.set(idx, part);
            }
            case EditOps.REMOVE_PART -> {
                if (!validIdx) return;
                be.parts.remove(idx);
            }
            case EditOps.CLEAR -> be.parts.clear();
            case EditOps.DUPLICATE_PART -> {
                if (!validIdx) return;
                DisplayPart copy = be.parts.get(idx).copy();
                copy.x = DisplayPart.clamp(copy.x + 0.25f, -DisplayPart.MAX_OFFSET, DisplayPart.MAX_OFFSET, 0);
                be.parts.add(idx + 1, copy);
            }
            case EditOps.MOVE_PART -> {
                if (!validIdx) return;
                int to = idx + (op.getInt("Dir") < 0 ? -1 : 1);
                if (to < 0 || to >= be.parts.size()) return;
                DisplayPart part = be.parts.remove(idx);
                be.parts.add(to, part);
            }
            case EditOps.SET_FACING -> {
                BlockState state = level.getBlockState(pos);
                if (!state.hasProperty(BillboardBlock.FACING)) return;
                Direction d = Direction.from2DDataValue(op.getInt("Facing"));
                level.setBlock(pos, state.setValue(BillboardBlock.FACING, d), Block.UPDATE_ALL);
                return;
            }
            case EditOps.SET_LIGHT -> {
                BlockState state = level.getBlockState(pos);
                if (!state.hasProperty(BillboardBlock.LIGHT)) return;
                int light = Mth.clamp(op.getInt("Light"), 0, 15);
                level.setBlock(pos, state.setValue(BillboardBlock.LIGHT, light), Block.UPDATE_ALL);
                return;
            }
            default -> {
                return;
            }
        }

        // 한도 검사
        int maxParts = BillboardConfig.MAX_PARTS.get();
        long maxBytes = BillboardConfig.MAX_BYTES.get();
        if (be.parts.size() > maxParts) {
            restore(be, backup);
            fail(player, "too_many_parts", maxParts);
            return;
        }
        long bytes = be.totalImageBytes();
        if (bytes > maxBytes && bytes > sum(backup)) {
            restore(be, backup);
            fail(player, "too_large", bytes / 1024, maxBytes / 1024);
            return;
        }
        be.markUpdated();
    }

    private static long sum(List<DisplayPart> list) {
        long s = 0;
        for (DisplayPart p : list) s += p.image.length;
        return s;
    }

    private static void restore(BillboardBlockEntity be, List<DisplayPart> backup) {
        be.parts.clear();
        be.parts.addAll(backup);
    }

    /** 이미지가 들어 있는 오브젝트 목록을 읽고 검증한다. 실패하면 null. */
    private static List<DisplayPart> readParts(ServerPlayer player, ListTag list) {
        int maxDim = BillboardConfig.MAX_IMAGE_SIZE.get();
        List<DisplayPart> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            DisplayPart part = DisplayPart.fromTag(list.getCompound(i));
            int[] dim = pngSize(part.image);
            if (dim == null) {
                fail(player, "bad_image");
                return null;
            }
            if (dim[0] > maxDim || dim[1] > maxDim) {
                fail(player, "image_too_big", dim[0], dim[1], maxDim);
                return null;
            }
            part.setImage(part.image, dim[0], dim[1]);
            out.add(part);
        }
        return out;
    }

    /** PNG 서명과 IHDR 에서 가로·세로를 읽는다 (이미지를 서버에서 디코딩하지 않음). */
    private static int[] pngSize(byte[] b) {
        if (b == null || b.length < 33) return null;
        int[] sig = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < 8; i++) if ((b[i] & 0xFF) != sig[i]) return null;
        if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;
        int w = ((b[16] & 0xFF) << 24) | ((b[17] & 0xFF) << 16) | ((b[18] & 0xFF) << 8) | (b[19] & 0xFF);
        int h = ((b[20] & 0xFF) << 24) | ((b[21] & 0xFF) << 16) | ((b[22] & 0xFF) << 8) | (b[23] & 0xFF);
        if (w <= 0 || h <= 0) return null;
        return new int[]{w, h};
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("msg.qwertys_billboard." + key, args)
                .withStyle(ChatFormatting.RED), true);
    }

    private ServerEditHandler() {}
}
