package com.qwertys.billboard.client;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.geometry.size.FloatSize;
import com.github.weisj.jsvg.parser.SVGLoader;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** JSVG 로 SVG 를 투명 배경 비트맵으로 그린다. (JSVG 의존성을 이 클래스에만 둠) */
final class SvgRasterizer {
    static BufferedImage render(Path path, int longSide) throws IOException {
        SVGLoader loader = new SVGLoader();
        SVGDocument doc = loader.load(path.toUri().toURL());
        if (doc == null) throw new IOException("svg parse failed");
        FloatSize size = doc.size();
        double sw = size.getWidth(), sh = size.getHeight();
        if (!(sw > 0) || !(sh > 0)) {
            sw = 512;
            sh = 512;
        }
        double scale = longSide / Math.max(sw, sh);
        int w = Math.max(1, (int) Math.round(sw * scale));
        int h = Math.max(1, (int) Math.round(sh * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.scale(w / sw, h / sh);
            doc.render(null, g);
        } finally {
            g.dispose();
        }
        return img;
    }

    private SvgRasterizer() {}
}
