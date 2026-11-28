package com.enik.zombietide.client.gui.settings;

/**
 * 设置页分类。
 * <p>【v0.2.132】「氛围」从 HUD 末尾拆为独立分类，与《设置页重构方案》3.1 布局图一致
 * （7 项：玩家 / 村民 / 生成 / 世界 / 警告 / HUD / 氛围）。
 */
public enum SettingsCategory {
    PLAYER("zombietide.settings.tab.player"),
    VILLAGER("zombietide.settings.tab.villager"),
    SPAWN("zombietide.settings.tab.spawn"),
    WORLD("zombietide.settings.tab.world"),
    WARNING("zombietide.settings.tab.warning"),
    HUD("zombietide.settings.tab.hud"),
    ATMO("zombietide.settings.tab.atmo");

    private final String key;

    SettingsCategory(String key) {
        this.key = key;
    }

    /** 分页标题的语言键 */
    public String key() {
        return key;
    }
}
