package com.qwertys.billboard.client;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * PC 이미지 파일을 읽어 전광판용 PNG 로 변환한다.
 * <ol>
 *   <li>형식별로 읽기 (PNG/JPG/GIF/BMP/WBMP 기본, WEBP/TIFF/TGA/PSD/ICO 는 TwelveMonkeys, SVG 는 JSVG)</li>
 *   <li>투명 배경이 있으면 색 있는 부분만 남김 / 불투명 이미지는 선택 시 단색 배경 제거</li>
 *   <li>빈 여백 잘라내기 → 지정 해상도로 고품질 축소 → PNG 인코딩</li>
 * </ol>
 * 마인크래프트 클래스에 의존하지 않음 (별도 스레드에서 실행).
 */
public final class ImageImporter {
    /** 이 값 미만의 알파는 완전히 투명으로 취급 */
    public static final int ALPHA_CUTOFF = 8;
    private static final int PRE_SHRINK = 2048;

    public static final String[] EXTENSIONS = {
            "png", "jpg", "jpeg", "jpe", "jfif", "gif", "bmp", "dib", "wbmp", "webp",
            "tif", "tiff", "tga", "icb", "vda", "vst", "psd", "psb", "ico", "cur", "svg", "svgz"
    };

    public record Result(String name, byte[] png, int width, int height) {}

    public record Options(int maxResolution, boolean removeBackground, int tolerance) {}

    private static boolean pluginsRegistered;

    /** TwelveMonkeys 리더를 ImageIO 에 직접 등록 (모드 로더 환경에서는 자동 탐색이 안 될 수 있음) */
    public static synchronized void registerPlugins() {
        if (pluginsRegistered) return;
        pluginsRegistered = true;
        String[] spis = {
                "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.tga.TGAImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.psd.PSDImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.bmp.BMPImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.bmp.ICOImageReaderSpi",
                "com.twelvemonkeys.imageio.plugins.bmp.CURImageReaderSpi",
        };
        IIORegistry registry = IIORegistry.getDefaultInstance();
        for (String cn : spis) {
            try {
                Object spi = Class.forName(cn, true, ImageImporter.class.getClassLoader()).getDeclaredConstructor().newInstance();
                registry.registerServiceProvider(spi);
            } catch (Throwable ignored) {
                // 라이브러리가 없으면 해당 형식만 비활성
            }
        }
    }

    public static Result load(Path path, Options opt) throws IOException {
        registerPlugins();
        String fileName = path.getFileName().toString();
        String ext = extension(fileName);
        int res = Math.max(8, opt.maxResolution());

        BufferedImage img;
        if (ext.equals("svg") || ext.equals("svgz")) {
            try {
                img = SvgRasterizer.render(path, Math.min(PRE_SHRINK, res * 2));
            } catch (LinkageError e) {
                throw new IOException("SVG library not available", e);
            }
        } else {
            img = readRaster(path);
        }
        if (img == null) throw new IOException("unsupported");

        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        // 너무 큰 원본은 먼저 줄여서 처리 속도 확보
        if (Math.max(w, h) > PRE_SHRINK) {
            int[] d = fit(w, h, PRE_SHRINK);
            px = resize(px, w, h, d[0], d[1]);
            w = d[0];
            h = d[1];
        }

        boolean hasAlpha = false;
        for (int c : px) {
            if ((c >>> 24) < 250) {
                hasAlpha = true;
                break;
            }
        }
        if (!hasAlpha && opt.removeBackground()) {
            int[] backup = px.clone();
            removeBackground(px, w, h, opt.tolerance());
            // 이미지 전체가 한 가지 색이라 모두 지워졌으면 원본을 그대로 사용
            boolean any = false;
            for (int c : px) {
                if ((c >>> 24) >= ALPHA_CUTOFF) {
                    any = true;
                    break;
                }
            }
            if (!any) px = backup;
        }

        for (int i = 0; i < px.length; i++) if ((px[i] >>> 24) < ALPHA_CUTOFF) px[i] = 0;

        // 색 있는 영역만 잘라내기
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (px[y * w + x] != 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) throw new IOException("empty");
        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        int[] crop = new int[cw * ch];
        for (int y = 0; y < ch; y++) System.arraycopy(px, (y + minY) * w + minX, crop, y * cw, cw);

        if (Math.max(cw, ch) > res) {
            int[] d = fit(cw, ch, res);
            crop = resize(crop, cw, ch, d[0], d[1]);
            cw = d[0];
            ch = d[1];
        }

        String name = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        return new Result(name, encodePng(crop, cw, ch), cw, ch);
    }

    // ───────────── 읽기 ─────────────

    private static BufferedImage readRaster(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("not a file");
        ImageIO.setUseCache(false);
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            if (in == null) return null;
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            IOException last = null;
            while (it.hasNext()) {
                ImageReader reader = it.next();
                try {
                    in.seek(0);
                    reader.setInput(in, false, true);
                    int index = 0;
                    String fmt = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (fmt.equals("ico") || fmt.equals("cur") || fmt.equals("icns")) {
                        // 아이콘은 가장 큰 이미지를 선택
                        int n = Math.min(64, reader.getNumImages(true));
                        long best = -1;
                        for (int i = 0; i < n; i++) {
                            long area = (long) reader.getWidth(i) * reader.getHeight(i);
                            if (area > best) {
                                best = area;
                                index = i;
                            }
                        }
                    }
                    BufferedImage img = reader.read(index);
                    if (img != null) return img;
                } catch (IOException e) {
                    last = e;
                } catch (RuntimeException e) {
                    last = new IOException(e);
                } finally {
                    reader.dispose();
                }
            }
            if (last != null) throw last;
        }
        return null;
    }

    // ───────────── 배경 제거 ─────────────

    /** 가장자리에서 가장 많이 보이는 색을 배경으로 보고, 가장자리와 이어진 비슷한 색을 투명하게 만든다. */
    static void removeBackground(int[] px, int w, int h, int tol) {
        Map<Integer, Integer> count = new HashMap<>();
        for (int x = 0; x < w; x++) {
            count.merge(quant(px[x]), 1, Integer::sum);
            count.merge(quant(px[(h - 1) * w + x]), 1, Integer::sum);
        }
        for (int y = 0; y < h; y++) {
            count.merge(quant(px[y * w]), 1, Integer::sum);
            count.merge(quant(px[y * w + w - 1]), 1, Integer::sum);
        }
        int bgQ = 0, best = -1;
        for (Map.Entry<Integer, Integer> e : count.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bgQ = e.getKey();
            }
        }
        // 양자화된 배경색과 같은 칸에 속한 가장자리 픽셀들의 평균을 실제 배경색으로 사용
        long sr = 0, sg = 0, sb = 0, n = 0;
        for (int i = 0; i < px.length; i++) {
            int x = i % w, y = i / w;
            if (x != 0 && y != 0 && x != w - 1 && y != h - 1) continue;
            if (quant(px[i]) == bgQ) {
                sr += (px[i] >> 16) & 255;
                sg += (px[i] >> 8) & 255;
                sb += px[i] & 255;
                n++;
            }
        }
        if (n == 0) return;
        int br = (int) (sr / n), bg = (int) (sg / n), bb = (int) (sb / n);

        boolean[] seen = new boolean[px.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            seed(px, seen, queue, x, br, bg, bb, tol);
            seed(px, seen, queue, (h - 1) * w + x, br, bg, bb, tol);
        }
        for (int y = 0; y < h; y++) {
            seed(px, seen, queue, y * w, br, bg, bb, tol);
            seed(px, seen, queue, y * w + w - 1, br, bg, bb, tol);
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            px[i] = 0;
            int x = i % w, y = i / w;
            if (x > 0) seed(px, seen, queue, i - 1, br, bg, bb, tol);
            if (x < w - 1) seed(px, seen, queue, i + 1, br, bg, bb, tol);
            if (y > 0) seed(px, seen, queue, i - w, br, bg, bb, tol);
            if (y < h - 1) seed(px, seen, queue, i + w, br, bg, bb, tol);
        }
    }

    private static void seed(int[] px, boolean[] seen, ArrayDeque<Integer> q, int i, int r, int g, int b, int tol) {
        if (seen[i]) return;
        int c = px[i];
        int d = Math.max(Math.abs(((c >> 16) & 255) - r), Math.max(Math.abs(((c >> 8) & 255) - g), Math.abs((c & 255) - b)));
        if (d <= tol) {
            seen[i] = true;
            q.add(i);
        }
    }

    private static int quant(int c) {
        return ((c >> 19) & 31) << 10 | ((c >> 11) & 31) << 5 | ((c >> 3) & 31);
    }

    // ───────────── 크기 조절 / 인코딩 ─────────────

    static int[] fit(int w, int h, int max) {
        if (w >= h) return new int[]{max, Math.max(1, Math.round(h * (float) max / w))};
        return new int[]{Math.max(1, Math.round(w * (float) max / h)), max};
    }

    /** 알파를 고려한(premultiplied) 박스 필터 축소 — 가장자리에 검은 테두리가 생기지 않음 */
    static int[] resize(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        double fx = (double) sw / dw, fy = (double) sh / dh;
        for (int y = 0; y < dh; y++) {
            int y0 = (int) Math.floor(y * fy);
            int y1 = Math.min(sh, Math.max(y0 + 1, (int) Math.ceil((y + 1) * fy)));
            for (int x = 0; x < dw; x++) {
                int x0 = (int) Math.floor(x * fx);
                int x1 = Math.min(sw, Math.max(x0 + 1, (int) Math.ceil((x + 1) * fx)));
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int c = src[yy * sw + xx];
                        int ca = c >>> 24;
                        a += ca;
                        r += (long) ((c >> 16) & 255) * ca;
                        g += (long) ((c >> 8) & 255) * ca;
                        b += (long) (c & 255) * ca;
                        n++;
                    }
                }
                if (a == 0 || n == 0) {
                    out[y * dw + x] = 0;
                } else {
                    int oa = (int) (a / n);
                    out[y * dw + x] = oa << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                }
            }
        }
        return out;
    }

    public static byte[] encodePng(int[] argb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", bos)) throw new IOException("png writer missing");
        return bos.toByteArray();
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private ImageImporter() {}
}
