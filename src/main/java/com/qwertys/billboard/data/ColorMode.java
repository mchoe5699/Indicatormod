package com.qwertys.billboard.data;

/** 오브젝트 색상 표현 방식 */
public enum ColorMode {
    /** 원본 이미지 색 그대로 */
    ORIGINAL("original", false, false),
    /** 원본 색에 지정 색을 곱함 */
    TINT("tint", false, false),
    /** 모양만 남기고 지정한 단색으로 칠함 */
    SOLID("solid", true, false),
    /** 모양 전체가 무지개색으로 계속 바뀜 */
    RAINBOW("rainbow", true, true),
    /** 가로 방향으로 무지개가 흐름 */
    RAINBOW_WAVE("rainbow_wave", true, true),
    /** 원본 색 위에 무지개 색을 곱함 */
    RAINBOW_TINT("rainbow_tint", false, true);

    public final String key;
    /** true 이면 원본 대신 흰색 마스크 텍스처를 사용 */
    public final boolean useMask;
    public final boolean animated;

    ColorMode(String key, boolean useMask, boolean animated) {
        this.key = key;
        this.useMask = useMask;
        this.animated = animated;
    }

    public ColorMode next() {
        ColorMode[] v = values();
        return v[(ordinal() + 1) % v.length];
    }

    public static ColorMode byKey(String key) {
        for (ColorMode m : values()) if (m.key.equals(key)) return m;
        return ORIGINAL;
    }
}
