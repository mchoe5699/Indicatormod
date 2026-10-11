package com.qwertys.billboard.network;

/** 편집 명령 이름 (클라이언트·서버 공용) */
public final class EditOps {
    public static final String SET_PART = "set_part";
    public static final String SET_GROUP = "set_group";
    public static final String SET_APPEARANCE_ALL = "set_appearance_all";
    public static final String ADD_PARTS = "add_parts";
    public static final String REPLACE_PART = "replace_part";
    public static final String REPLACE_IMAGE = "replace_image";
    public static final String REMOVE_PART = "remove_part";
    public static final String CLEAR = "clear";
    public static final String DUPLICATE_PART = "duplicate_part";
    public static final String MOVE_PART = "move_part";
    public static final String SET_FACING = "set_facing";
    public static final String SET_LIGHT = "set_light";

    private EditOps() {}
}
