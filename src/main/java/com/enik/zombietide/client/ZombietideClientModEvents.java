package com.enik.zombietide.client;

import com.enik.zombietide.EnikZombietide;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * 客户端 mod bus 事件（按键映射注册）。
 *
 * <p>单独成类的原因：mod bus 与 game bus 的 {@code @EventBusSubscriber} 不能同时标注在一个类上。
 * 两个类都限定 {@code Dist.CLIENT}，专用服务器不会加载它们。
 */
@EventBusSubscriber(modid = EnikZombietide.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class ZombietideClientModEvents {

    /** 注册按键映射（仅调用一次 event.register） */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        ZombietideClientEvents.OPEN_SETTINGS_KEY = new KeyMapping(
                "key.zombietide.settings",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "category.zombietide"
        );
        event.register(ZombietideClientEvents.OPEN_SETTINGS_KEY);
    }
}
