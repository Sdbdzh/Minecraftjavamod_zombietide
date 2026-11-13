package com.enik.zombietide;

import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.client.ZombietideClientEvents;
import com.enik.zombietide.server.ZombietideServerEvents;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

@Mod(EnikZombietide.MODID)
public class EnikZombietide {
    public static final String MODID = "enik_zombietide";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EnikZombietide(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Initializing {} mod", MODID);

        // 1. 注册 Data Attachments（mod bus）
        ModAttachments.register(modEventBus);

        // 2. ModMessages 通过 @EventBusSubscriber 自动注册网络 payload（mod bus）

        // 3. 注册服务端事件（游戏总线）：ZombietideServerEvents 已通过 @EventBusSubscriber 自动注册，
        // 此处不再手动 register，避免重复触发
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(ZombietideClientEvents::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ZombietideClientEvents::onKeyInput);

        // 5. 注册客户端 HUD 渲染监听（游戏总线）
        NeoForge.EVENT_BUS.addListener(ZombietideClientEvents::onRenderHud);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("{} server starting", MODID);
    }
}
