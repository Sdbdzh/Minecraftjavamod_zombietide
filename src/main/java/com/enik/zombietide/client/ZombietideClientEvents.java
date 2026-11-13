package com.enik.zombietide.client;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.ConfigManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端事件：按键映射、打开设置界面、HUD 渲染。
 * <p>
 * {@link #OPEN_SETTINGS_KEY} 为 {@code private static} 字段（非 final、非 public），
 * 避免 NeoForge 在早期初始化阶段自动发现并重复注册。按键映射仅在
 * {@link #onRegisterKeyMappings} 中通过 {@code event.register()} 注册一次。
 * 所有监听器均在 {@code EnikZombietide} 构造函数中手动 {@code addListener}。
 */
public class ZombietideClientEvents {

    /** 模组设置按键（私有非 final，避免 NeoForge 自动发现重复注册） */
    private static KeyMapping OPEN_SETTINGS_KEY;

    /** 获取模组设置按键映射 */
    public static KeyMapping getOpenSettingsKey() {
        return OPEN_SETTINGS_KEY;
    }

    /** 注册按键映射（mod bus，仅调用一次 event.register） */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        OPEN_SETTINGS_KEY = new KeyMapping(
                "key.zombietide.settings",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "category.zombietide"
        );
        event.register(OPEN_SETTINGS_KEY);
    }

    /** 按键输入：按下设置键时，仅在游戏内打开模组设置界面（game bus）；防止重复打开多个设置界面
     * 不在聊天输入、游戏设置、或其它界面打开时触发
     */
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (OPEN_SETTINGS_KEY != null && OPEN_SETTINGS_KEY.matches(event.getKey(), event.getScanCode())) {
            Minecraft minecraft = Minecraft.getInstance();
            // 只有在游戏内且没有打开任何界面时才打开设置
            // 排除：聊天界面、游戏设置界面、暂停菜单、其它自定义界面
            if (minecraft.player != null && minecraft.level != null &&
                minecraft.screen == null) {
                // 从 ConfigManager 获取当前配置
                com.enik.zombietide.config.ZombietideConfig currentConfig = com.enik.zombietide.config.ConfigManager.getClientConfig();
                minecraft.setScreen(new ZombietideSettingsScreen(null, currentConfig));
            }
        }
    }

    /** HUD 渲染：在 GUI 绘制完成后绘制尸潮天数信息（game bus） */
    @SubscribeEvent
    public static void onRenderHud(RenderGuiEvent.Post event) {
        ZombietideHud.render(event.getGuiGraphics(), Minecraft.getInstance());
    }
}