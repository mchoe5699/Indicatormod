package com.qwertys.billboard.client;

import com.qwertys.billboard.ModRegistry;
import com.qwertys.billboard.QwertysBillboard;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ClientEvents {

    @Mod.EventBusSubscriber(modid = QwertysBillboard.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(ModRegistry.BILLBOARD_BE.get(), BillboardRenderer::new);
        }

        @SubscribeEvent
        public static void clientSetup(FMLClientSetupEvent event) {
            // 이미지 형식 플러그인(WEBP/TIFF/TGA/PSD/ICO) 미리 등록
            event.enqueueWork(ImageImporter::registerPlugins);
        }
    }

    @Mod.EventBusSubscriber(modid = QwertysBillboard.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) ClientImageCache.tick();
        }

        @SubscribeEvent
        public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            ClientImageCache.clear();
        }
    }

    private ClientEvents() {}
}
