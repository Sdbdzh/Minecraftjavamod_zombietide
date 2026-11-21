package com.enik.zombietide.client;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.ConfigManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 客户端事件：按键映射、打开设置界面、HUD 渲染。
 * <p>
 * {@link #OPEN_SETTINGS_KEY} 为包级可见（package-private）{@code static} 字段（非 final、非 public），
 * 由同包的 {@link ZombietideClientModEvents} 在 mod bus 阶段赋值，
 * 避免 NeoForge 在早期初始化阶段自动发现并重复注册。按键映射仅在
 * {@link ZombietideClientModEvents#onRegisterKeyMappings} 中通过 {@code event.register()} 注册一次。
 * <p><b>注意</b>：本类只能通过 {@code @EventBusSubscriber(value = Dist.CLIENT)} 注册，
 * 绝不能在 {@code EnikZombietide} 构造函数里直接引用——否则专用服务器加载主类时
 * 会把 {@code Minecraft}/{@code Screen} 等客户端类一起拖进来，直接崩服。
 */
@EventBusSubscriber(modid = EnikZombietide.MODID, value = Dist.CLIENT)
public class ZombietideClientEvents {

    /** 模组设置按键（包级可见，供 ZombietideClientModEvents 在 mod bus 阶段赋值） */
    static KeyMapping OPEN_SETTINGS_KEY;

    /** 获取模组设置按键映射 */
    public static KeyMapping getOpenSettingsKey() {
        return OPEN_SETTINGS_KEY;
    }

    /**
     * 断线时清空 HUD 的服务端同步缓存（v0.1.60 修复 #123）。
     * <p>否则退出存档后进入「没装本模组」的服务器，左上角仍显示上一局的 DAY X。
     */
    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        ZombietideHud.clearAllSynced();
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