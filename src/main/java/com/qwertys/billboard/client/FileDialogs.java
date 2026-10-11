package com.qwertys.billboard.client;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 운영체제 기본 파일 열기 창 (마인크래프트에 포함된 LWJGL tinyfd 사용). 렌더 스레드가 아닌 곳에서 호출할 것. */
public final class FileDialogs {
    private static String lastDir = System.getProperty("user.home") + java.io.File.separator;

    public static synchronized List<Path> openImages(String title, String filterDesc) {
        List<Path> out = new ArrayList<>();
        String result;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            String[] ext = ImageImporter.EXTENSIONS;
            PointerBuffer filters = stack.mallocPointer(ext.length * 2);
            for (String e : ext) {
                filters.put(stack.UTF8("*." + e));
                filters.put(stack.UTF8("*." + e.toUpperCase(java.util.Locale.ROOT)));
            }
            filters.flip();
            result = TinyFileDialogs.tinyfd_openFileDialog(title, lastDir, filters, filterDesc, true);
        }
        if (result == null || result.isBlank()) return out;
        for (String s : result.split("\\|")) {
            if (s.isBlank()) continue;
            Path p = Paths.get(s.trim());
            if (Files.isRegularFile(p)) out.add(p);
        }
        if (!out.isEmpty() && out.get(0).getParent() != null) {
            lastDir = out.get(0).getParent().toString() + java.io.File.separator;
        }
        return out;
    }

    private FileDialogs() {}
}
