package com.qwertys.billboard.block;

import com.qwertys.billboard.ModRegistry;
import com.qwertys.billboard.data.DisplayPart;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 전광판 데이터. 모든 오브젝트(이미지 포함)와 그룹(전체) 변형이 NBT 에 저장된다.
 * 그래서 WorldEdit 의 //copy, //paste, //rotate, 스키매틱 저장 시 그대로 유지된다.
 */
public class BillboardBlockEntity extends BlockEntity {
    public static final int HARD_PART_LIMIT = 1024;

    public final List<DisplayPart> parts = new ArrayList<>();

    // 그룹(전체) 변형 — 블록 기준 방향(FACING) 다음에 적용됨
    public float gx, gy, gz;
    public float gyaw, gpitch, groll;
    public float gscale = 1f;

    /** 클라이언트에서 데이터가 갱신될 때마다 증가 (편집 창이 감지용으로 사용) */
    public int revision;
    @Nullable
    private AABB renderBox;

    public BillboardBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.BILLBOARD_BE.get(), pos, state);
    }

    // ───────────── 저장/불러오기 ─────────────

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag list = new ListTag();
        for (DisplayPart p : parts) list.add(p.save(new CompoundTag(), true));
        tag.put("Parts", list);
        tag.put("Group", saveGroup(new CompoundTag()));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        parts.clear();
        ListTag list = tag.getList("Parts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && i < HARD_PART_LIMIT; i++) {
            parts.add(DisplayPart.fromTag(list.getCompound(i)));
        }
        if (tag.contains("Group", Tag.TAG_COMPOUND)) loadGroup(tag.getCompound("Group"));
        revision++;
        renderBox = null;
    }

    public CompoundTag saveGroup(CompoundTag t) {
        t.putFloat("X", gx);
        t.putFloat("Y", gy);
        t.putFloat("Z", gz);
        t.putFloat("Yaw", gyaw);
        t.putFloat("Pitch", gpitch);
        t.putFloat("Roll", groll);
        t.putFloat("Scale", gscale);
        return t;
    }

    public void loadGroup(CompoundTag t) {
        if (t.contains("X")) gx = DisplayPart.clamp(t.getFloat("X"), -DisplayPart.MAX_OFFSET, DisplayPart.MAX_OFFSET, 0);
        if (t.contains("Y")) gy = DisplayPart.clamp(t.getFloat("Y"), -DisplayPart.MAX_OFFSET, DisplayPart.MAX_OFFSET, 0);
        if (t.contains("Z")) gz = DisplayPart.clamp(t.getFloat("Z"), -DisplayPart.MAX_OFFSET, DisplayPart.MAX_OFFSET, 0);
        if (t.contains("Yaw")) gyaw = DisplayPart.angle(t.getFloat("Yaw"));
        if (t.contains("Pitch")) gpitch = DisplayPart.angle(t.getFloat("Pitch"));
        if (t.contains("Roll")) groll = DisplayPart.angle(t.getFloat("Roll"));
        if (t.contains("Scale")) gscale = DisplayPart.clamp(t.getFloat("Scale"), 0.001f, 64f, 1f);
        renderBox = null;
    }

    public long totalImageBytes() {
        long sum = 0;
        for (DisplayPart p : parts) sum += p.image.length;
        return sum;
    }

    /** 서버에서 값이 바뀐 뒤 호출: 저장 표시 + 클라이언트로 동기화 */
    public void markUpdated() {
        setChanged();
        renderBox = null;
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    // ───────────── 동기화 ─────────────

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ───────────── 렌더 범위 ─────────────

    public void invalidateRenderBox() {
        renderBox = null;
    }

    @Override
    public AABB getRenderBoundingBox() {
        if (renderBox == null) {
            double r = 1.0;
            for (DisplayPart p : parts) {
                double half = 0.5 * Math.sqrt(p.size * p.size + p.heightBlocks() * p.heightBlocks() + p.depth * p.depth);
                double off = Math.sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
                r = Math.max(r, (off + half) * gscale);
            }
            r += Math.sqrt(gx * gx + gy * gy + gz * gz) + 0.5;
            renderBox = new AABB(worldPosition).inflate(r);
        }
        return renderBox;
    }
}
