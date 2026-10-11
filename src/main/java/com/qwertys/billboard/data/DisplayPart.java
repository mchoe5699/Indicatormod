package com.qwertys.billboard.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * 전광판 안의 오브젝트 하나 (이미지 한 장 + 위치/회전/크기/외형 설정).
 * 모든 값은 블록 엔티티 NBT 에 저장되므로 WorldEdit 복사/붙여넣기 시 그대로 유지된다.
 * 좌표 단위는 블록(1 = 1m, 0.001 = 1mm), 회전 단위는 도(°).
 */
public final class DisplayPart {
    public static final float MAX_OFFSET = 128f;
    public static final float MIN_SIZE = 0.001f, MAX_SIZE = 128f;
    public static final float MIN_DEPTH = 0.0005f, MAX_DEPTH = 32f;

    /** PNG 로 인코딩된 이미지 (배경 투명 처리 + 잘라내기 완료된 상태) */
    public byte[] image = new byte[0];
    public int imgW = 1, imgH = 1;
    public String name = "";

    // ── 변형 ──
    public float x, y, z;
    public float yaw, pitch, roll;
    /** 가로 길이 (블록). 세로는 이미지 비율로 자동 결정 */
    public float size = 1f;
    /** 3D 모드 두께 (블록) */
    public float depth = 0.0625f;

    // ── 외형 ──
    public boolean mode3d = false;
    public boolean glow = false;
    public boolean visible = true;
    public int color = 0xFFFFFF;
    public ColorMode colorMode = ColorMode.ORIGINAL;
    /** 무지개 속도 (초당 색상 바퀴 수, 음수면 반대 방향) */
    public float speed = 0.25f;
    /** 무지개 물결 모드에서 가로 전체에 걸치는 색상 바퀴 수 */
    public float spread = 1f;
    public float opacity = 1f;

    private String key;

    public void setImage(byte[] png, int w, int h) {
        this.image = png == null ? new byte[0] : png;
        this.imgW = Math.max(1, w);
        this.imgH = Math.max(1, h);
        this.key = null;
    }

    /** 같은 이미지끼리 텍스처를 공유하기 위한 키 */
    public String imageKey() {
        if (key == null) {
            CRC32 crc = new CRC32();
            crc.update(image);
            key = image.length + "_" + Long.toHexString(crc.getValue()) + "_" + Integer.toHexString(Arrays.hashCode(image));
        }
        return key;
    }

    public float heightBlocks() {
        return size * imgH / (float) imgW;
    }

    // ───────────── NBT ─────────────

    public CompoundTag save(CompoundTag t, boolean withImage) {
        if (withImage) {
            t.putByteArray("Img", image);
            t.putInt("W", imgW);
            t.putInt("H", imgH);
        }
        t.putString("Name", name);
        saveTransform(t);
        saveAppearance(t);
        return t;
    }

    public CompoundTag saveTransform(CompoundTag t) {
        t.putFloat("X", x);
        t.putFloat("Y", y);
        t.putFloat("Z", z);
        t.putFloat("Yaw", yaw);
        t.putFloat("Pitch", pitch);
        t.putFloat("Roll", roll);
        t.putFloat("Size", size);
        t.putFloat("Depth", depth);
        return t;
    }

    public CompoundTag saveAppearance(CompoundTag t) {
        t.putBoolean("Mode3D", mode3d);
        t.putBoolean("Glow", glow);
        t.putBoolean("Visible", visible);
        t.putInt("Color", color & 0xFFFFFF);
        t.putString("ColorMode", colorMode.key);
        t.putFloat("Speed", speed);
        t.putFloat("Spread", spread);
        t.putFloat("Opacity", opacity);
        return t;
    }

    /** 태그에 있는 항목만 반영한다. */
    public void load(CompoundTag t) {
        if (t.contains("Img", Tag.TAG_BYTE_ARRAY)) {
            setImage(t.getByteArray("Img"), t.getInt("W"), t.getInt("H"));
        }
        if (t.contains("Name", Tag.TAG_STRING)) {
            String n = t.getString("Name");
            name = n.length() > 64 ? n.substring(0, 64) : n;
        }
        loadTransform(t);
        loadAppearance(t);
    }

    public void loadTransform(CompoundTag t) {
        if (t.contains("X")) x = clamp(t.getFloat("X"), -MAX_OFFSET, MAX_OFFSET, 0);
        if (t.contains("Y")) y = clamp(t.getFloat("Y"), -MAX_OFFSET, MAX_OFFSET, 0);
        if (t.contains("Z")) z = clamp(t.getFloat("Z"), -MAX_OFFSET, MAX_OFFSET, 0);
        if (t.contains("Yaw")) yaw = angle(t.getFloat("Yaw"));
        if (t.contains("Pitch")) pitch = angle(t.getFloat("Pitch"));
        if (t.contains("Roll")) roll = angle(t.getFloat("Roll"));
        if (t.contains("Size")) size = clamp(t.getFloat("Size"), MIN_SIZE, MAX_SIZE, 1);
        if (t.contains("Depth")) depth = clamp(t.getFloat("Depth"), MIN_DEPTH, MAX_DEPTH, 0.0625f);
    }

    public void loadAppearance(CompoundTag t) {
        if (t.contains("Mode3D")) mode3d = t.getBoolean("Mode3D");
        if (t.contains("Glow")) glow = t.getBoolean("Glow");
        if (t.contains("Visible")) visible = t.getBoolean("Visible");
        if (t.contains("Color")) color = t.getInt("Color") & 0xFFFFFF;
        if (t.contains("ColorMode")) colorMode = ColorMode.byKey(t.getString("ColorMode"));
        if (t.contains("Speed")) speed = clamp(t.getFloat("Speed"), -20, 20, 0.25f);
        if (t.contains("Spread")) spread = clamp(t.getFloat("Spread"), -16, 16, 1);
        if (t.contains("Opacity")) opacity = clamp(t.getFloat("Opacity"), 0, 1, 1);
    }

    public static DisplayPart fromTag(CompoundTag t) {
        DisplayPart p = new DisplayPart();
        p.load(t);
        return p;
    }

    public DisplayPart copy() {
        return fromTag(save(new CompoundTag(), true));
    }

    public static float clamp(float v, float min, float max, float def) {
        if (!Float.isFinite(v)) return def;
        return Mth.clamp(v, min, max);
    }

    public static float angle(float v) {
        if (!Float.isFinite(v)) return 0;
        return Mth.wrapDegrees(v);
    }
}
