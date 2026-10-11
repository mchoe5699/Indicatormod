package com.qwertys.billboard.client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 한 이미지 안에서 서로 떨어져 있는 오브젝트(연결 요소)를 찾아 각각의 이미지로 나눈다.
 * 아주 작은 조각(점, 잡티)은 가장 가까운 큰 오브젝트에 붙인다. 예) 'i' 의 점은 막대에 붙음.
 */
public final class ComponentSplitter {

    /**
     * @param cx,cy 원본 이미지 픽셀 좌표 기준 조각의 중심 (왼쪽 위가 0,0)
     */
    public record Piece(byte[] png, int width, int height, float cx, float cy) {}

    public static List<Piece> split(byte[] png, int minArea, int maxPieces) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("decode failed");
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        return split(px, w, h, minArea, maxPieces);
    }

    static List<Piece> split(int[] px, int w, int h, int minArea, int maxPieces) throws IOException {
        int n = w * h;
        int[] label = new int[n];
        List<int[]> info = new ArrayList<>(); // [area, minX, minY, maxX, maxY]
        info.add(null); // 라벨 0 = 없음
        int[] stack = new int[n];

        for (int start = 0; start < n; start++) {
            if (label[start] != 0 || !solid(px[start])) continue;
            int id = info.size();
            int[] box = {0, w, h, -1, -1};
            info.add(box);
            int sp = 0;
            stack[sp++] = start;
            label[start] = id;
            while (sp > 0) {
                int i = stack[--sp];
                int x = i % w, y = i / w;
                box[0]++;
                if (x < box[1]) box[1] = x;
                if (y < box[2]) box[2] = y;
                if (x > box[3]) box[3] = x;
                if (y > box[4]) box[4] = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w || (dx == 0 && dy == 0)) continue;
                        int j = yy * w + xx;
                        if (label[j] == 0 && solid(px[j])) {
                            label[j] = id;
                            stack[sp++] = j;
                        }
                    }
                }
            }
        }

        int count = info.size() - 1;
        List<Piece> result = new ArrayList<>();
        if (count <= 1) return result;

        // 큰 조각 고르기
        List<Integer> big = new ArrayList<>();
        for (int id = 1; id <= count; id++) if (info.get(id)[0] >= minArea) big.add(id);
        if (big.isEmpty()) {
            // 전부 작은 조각이면 크기순으로 고름
            for (int id = 1; id <= count; id++) big.add(id);
        }
        big.sort(Comparator.comparingInt((Integer id) -> info.get(id)[0]).reversed());
        if (big.size() > maxPieces) big = new ArrayList<>(big.subList(0, maxPieces));
        if (big.size() <= 1) return result;

        // 모든 라벨 → 남길 라벨로 매핑
        int[] target = new int[count + 1];
        boolean[] isBig = new boolean[count + 1];
        for (int id : big) {
            isBig[id] = true;
            target[id] = id;
        }
        for (int id = 1; id <= count; id++) {
            if (isBig[id]) continue;
            int[] a = info.get(id);
            long bestD = Long.MAX_VALUE;
            int best = big.get(0);
            for (int b : big) {
                long d = gap(a, info.get(b));
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            target[id] = best;
        }

        // 남길 라벨별 최종 범위
        int[][] box = new int[count + 1][];
        for (int i = 0; i < n; i++) {
            if (label[i] == 0) continue;
            int t = target[label[i]];
            int x = i % w, y = i / w;
            int[] b = box[t];
            if (b == null) box[t] = b = new int[]{x, y, x, y};
            if (x < b[0]) b[0] = x;
            if (y < b[1]) b[1] = y;
            if (x > b[2]) b[2] = x;
            if (y > b[3]) b[3] = y;
        }

        // 왼쪽→오른쪽, 위→아래 순서
        List<Integer> order = new ArrayList<>(big);
        order.sort(Comparator.<Integer>comparingInt(id -> box[id][0]).thenComparingInt(id -> box[id][1]));

        for (int id : order) {
            int[] b = box[id];
            int pw = b[2] - b[0] + 1, ph = b[3] - b[1] + 1;
            int[] out = new int[pw * ph];
            for (int y = 0; y < ph; y++) {
                for (int x = 0; x < pw; x++) {
                    int i = (y + b[1]) * w + (x + b[0]);
                    // 반투명 가장자리 픽셀(라벨 없음)은 가장 가까운 라벨 영역에 포함되도록 인접 검사
                    if (label[i] != 0 ? target[label[i]] == id : nearLabel(label, target, w, h, x + b[0], y + b[1], id)) {
                        out[y * pw + x] = px[i];
                    }
                }
            }
            byte[] png = ImageImporter.encodePng(out, pw, ph);
            result.add(new Piece(png, pw, ph, b[0] + pw / 2f, b[1] + ph / 2f));
        }
        return result;
    }

    private static boolean nearLabel(int[] label, int[] target, int w, int h, int x, int y, int id) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                int l = label[yy * w + xx];
                if (l != 0) return target[l] == id;
            }
        }
        return false;
    }

    /** 두 사각형 사이 거리의 제곱 (겹치면 0) */
    private static long gap(int[] a, int[] b) {
        long dx = Math.max(0, Math.max(a[1] - b[3], b[1] - a[3]));
        long dy = Math.max(0, Math.max(a[2] - b[4], b[2] - a[4]));
        return dx * dx + dy * dy;
    }

    private static boolean solid(int argb) {
        return (argb >>> 24) >= 24;
    }

    private ComponentSplitter() {}
}
