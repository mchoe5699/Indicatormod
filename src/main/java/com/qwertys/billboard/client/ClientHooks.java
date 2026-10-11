package com.qwertys.billboard.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class ClientHooks {
    public static void openEditor(BlockPos pos) {
        Minecraft.getInstance().setScreen(new BillboardEditScreen(pos.immutable()));
    }

    private ClientHooks() {}
}
