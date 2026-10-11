package com.qwertys.billboard;

import net.minecraftforge.common.ForgeConfigSpec;

public final class BillboardConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_PARTS;
    public static final ForgeConfigSpec.IntValue MAX_BYTES;
    public static final ForgeConfigSpec.IntValue MAX_IMAGE_SIZE;
    public static final ForgeConfigSpec.DoubleValue MAX_EDIT_DISTANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("billboard");
        MAX_PARTS = b.comment("전광판 블록 하나에 넣을 수 있는 최대 오브젝트 수")
                .defineInRange("maxPartsPerBlock", 128, 1, 1024);
        MAX_BYTES = b.comment("전광판 블록 하나에 저장되는 이미지 데이터 합계 최대 바이트.",
                        "마인크래프트 청크 패킷 한도(약 2MB) 때문에 너무 크게 잡으면 접속이 끊길 수 있습니다.")
                .defineInRange("maxImageBytesPerBlock", 900_000, 10_000, 1_800_000);
        MAX_IMAGE_SIZE = b.comment("오브젝트 이미지 한 장의 최대 가로/세로 픽셀")
                .defineInRange("maxImageSize", 512, 16, 1024);
        MAX_EDIT_DISTANCE = b.comment("편집 창으로 전광판을 수정할 수 있는 최대 거리(블록)")
                .defineInRange("maxEditDistance", 64.0, 4.0, 512.0);
        b.pop();
        SPEC = b.build();
    }

    private BillboardConfig() {}
}
