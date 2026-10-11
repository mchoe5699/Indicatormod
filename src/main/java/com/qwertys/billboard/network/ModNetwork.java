package com.qwertys.billboard.network;

import com.qwertys.billboard.QwertysBillboard;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(QwertysBillboard.MODID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    public static void register() {
        CHANNEL.messageBuilder(EditChunkPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(EditChunkPacket::encode)
                .decoder(EditChunkPacket::decode)
                .consumerMainThread(EditChunkPacket::handle)
                .add();
    }

    private ModNetwork() {}
}
