package com.qwertys.billboard;

import com.mojang.logging.LogUtils;
import com.qwertys.billboard.network.ModNetwork;
import com.qwertys.billboard.network.ServerEditHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(QwertysBillboard.MODID)
public class QwertysBillboard {
    public static final String MODID = "qwertys_billboard";
    public static final Logger LOGGER = LogUtils.getLogger();

    public QwertysBillboard() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BillboardConfig.SPEC);
        ModNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(ServerEditHandler::onLogout);
    }
}
