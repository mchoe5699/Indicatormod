package com.qwertys.billboard.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.qwertys.billboard.QwertysBillboard;
import com.qwertys.billboard.data.DisplayPart;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 오브젝트 이미지 → GPU 텍스처(원본 + 흰색 마스크) + 3D 옆면 메시 캐시.
 * 같은 이미지는 여러 전광판이 공유하고, 60초 동안 안 쓰이면 해제한다.
 */
public final class ClientImageCache {
    private static final long UNUSED_RELEASE_MS = 60_000;
    /** 옆면을 만들 때 '채워진 픽셀'로 보는 알파 기준 */
    private static final int SOLID_ALPHA = 24;

    public static final class Entry {
        public final ResourceLocation texture;
        public final ResourceLocation mask;
        /** 3D 용: 채워진 픽셀은 완전 불투명, 나머지는 완전 투명 (옆면이 비쳐 보이지 않도록) */
        public final ResourceLocation solidTexture;
        public final ResourceLocation solidMask;
        public final int width, height;
        /** 옆면 목록: [종류, 줄 번호, 시작, 끝] × n. 종류 0=위 1=아래 2=왼쪽 3=오른쪽 */
        public final int[] sides;
        long lastUsed;
        final boolean failed;

        Entry(ResourceLocation texture, ResourceLocation mask, ResourceLocation solidTexture, ResourceLocation solidMask,
              int width, int height, int[] sides, boolean failed) {
            this.texture = texture;
            this.mask = mask;
            this.solidTexture = solidTexture;
            this.solidMask = solidMask;
            this.width = width;
            this.height = height;
            this.sides = sides;
            this.failed = failed;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static int tickCounter;

    @Nullable
    public static Entry get(DisplayPart part) {
        if (part.image.length == 0) return null;
        String key = part.imageKey();
        Entry e = CACHE.get(key);
        if (e == null) {
            e = create(part.image);
            CACHE.put(key, e);
        }
        e.lastUsed = Util.getMillis();
        return e.failed ? null : e;
    }

    private static Entry create(byte[] png) {
        NativeImage img = null;
        NativeImage mask = null, solid = null, solidMask = null;
        try {
            img = NativeImage.read(new ByteArrayInputStream(png));
            int w = img.getWidth(), h = img.getHeight();
            mask = new NativeImage(w, h, false);
            solid = new NativeImage(w, h, false);
            solidMask = new NativeImage(w, h, false);
            boolean[] filled = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = img.getPixelRGBA(x, y);       // ABGR 순서지만 알파는 최상위 바이트로 동일
                    int a = c >>> 24;
                    boolean f = a >= SOLID_ALPHA;
                    filled[y * w + x] = f;
                    mask.setPixelRGBA(x, y, (a << 24) | 0x00FFFFFF);
                    solid.setPixelRGBA(x, y, f ? (0xFF000000 | (c & 0x00FFFFFF)) : 0);
                    solidMask.setPixelRGBA(x, y, f ? 0xFFFFFFFF : 0);
                }
            }
            int[] sides = buildSides(filled, w, h);
            TextureManager tm = Minecraft.getInstance().getTextureManager();
            ResourceLocation tex = tm.register(QwertysBillboard.MODID, new DynamicTexture(img));
            ResourceLocation mk = tm.register(QwertysBillboard.MODID + "_mask", new DynamicTexture(mask));
            ResourceLocation st = tm.register(QwertysBillboard.MODID + "_solid", new DynamicTexture(solid));
            ResourceLocation sm = tm.register(QwertysBillboard.MODID + "_solid_mask", new DynamicTexture(solidMask));
            return new Entry(tex, mk, st, sm, w, h, sides, false);
        } catch (Exception ex) {
            QwertysBillboard.LOGGER.warn("Failed to load billboard image: {}", ex.toString());
            for (NativeImage n : new NativeImage[]{img, mask, solid, solidMask}) if (n != null) n.close();
            return new Entry(null, null, null, null, 1, 1, new int[0], true);
        }
    }

    /** 이미지 외곽선을 따라 옆면 사각형을 만든다. 같은 줄의 연속된 가장자리는 하나로 합친다. */
    static int[] buildSides(boolean[] s, int w, int h) {
        IntList out = new IntList();
        for (int r = 0; r < h; r++) {
            for (int type = 0; type < 2; type++) {
                int nr = type == 0 ? r - 1 : r + 1;
                int c = 0;
                while (c < w) {
                    if (s[r * w + c] && !at(s, w, h, c, nr)) {
                        int start = c;
                        while (c < w && s[r * w + c] && !at(s, w, h, c, nr)) c++;
                        out.add(type, r, start, c);
                    } else c++;
                }
            }
        }
        for (int c = 0; c < w; c++) {
            for (int type = 2; type < 4; type++) {
                int nc = type == 2 ? c - 1 : c + 1;
                int r = 0;
                while (r < h) {
                    if (s[r * w + c] && !at(s, w, h, nc, r)) {
                        int start = r;
                        while (r < h && s[r * w + c] && !at(s, w, h, nc, r)) r++;
                        out.add(type, c, start, r);
                    } else r++;
                }
            }
        }
        return out.toArray();
    }

    private static boolean at(boolean[] s, int w, int h, int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && s[y * w + x];
    }

    public static void tick() {
        if (++tickCounter % 100 != 0 || CACHE.isEmpty()) return;
        long now = Util.getMillis();
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        Iterator<Entry> it = CACHE.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (now - e.lastUsed > UNUSED_RELEASE_MS) {
                release(tm, e);
                it.remove();
            }
        }
    }

    public static void clear() {
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        for (Entry e : CACHE.values()) release(tm, e);
        CACHE.clear();
    }

    private static void release(TextureManager tm, Entry e) {
        if (e.failed) return;
        tm.release(e.texture);
        tm.release(e.mask);
        tm.release(e.solidTexture);
        tm.release(e.solidMask);
    }

    private static final class IntList {
        int[] data = new int[64];
        int size;

        void add(int a, int b, int c, int d) {
            if (size + 4 > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
            data[size++] = d;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private ClientImageCache() {}
}
