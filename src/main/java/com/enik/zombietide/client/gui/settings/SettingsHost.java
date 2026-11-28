package com.enik.zombietide.client.gui.settings;

import com.enik.zombietide.config.ZombietideConfig;

/**
 * 设置页与选项定义之间的窄接口：只暴露「读当前值 / 写回编辑状态」所需的最小状态。
 * <p>这样 {@link SettingsRegistry} 里的 63 条选项声明不需要依赖具体屏幕类，
 * P2 重写布局时只需让新屏幕实现同一接口即可复用全部选项定义。
 */
public interface SettingsHost {

    /** 当前正在编辑的存档级配置 */
    ZombietideConfig config();

    void setConfig(ZombietideConfig config);

    /** 当前正在编辑的本机 HUD 配置 */
    ZombietideConfig.HudConfig hud();

    void setHud(ZombietideConfig.HudConfig hud);

    /** DAY X 渐进红（本机显示偏好，不在 HudConfig record 里） */
    boolean dayGradientRed();

    void setDayGradientRed(boolean value);

    /** 【v0.2.138】DAY X 次行：血债显示（本机偏好，不在 record 里） */
    boolean showDebt();

    void setShowDebt(boolean value);

    /** 【v0.2.138】DAY X 次行：存活尸潮僵尸数显示（本机偏好，不在 record 里） */
    boolean showAliveCount();

    void setShowAliveCount(boolean value);
}
