package com.enik.zombietide.client.gui.settings;

/**
 * 设置页分类（P1 阶段与旧 {@code Tab} 一一对应）。
 * <p>注意：「氛围」目前仍挂在 HUD 分类末尾（与旧实现一致），P2 重排布局时再拆为独立分类。
 */
public enum SettingsCategory {
    PLAYER("zombietide.settings.tab.player"),
    VILLAGER("zombietide.settings.tab.villager"),
    SPAWN("zombietide.settings.tab.spawn"),
    WORLD("zombietide.settings.tab.world"),
    WARNING("zombietide.settings.tab.warning"),
    HUD("zombietide.settings.tab.hud");

    private final String key;

    SettingsCategory(String key) {
        this.key = key;
    }

    /** 分页标题的语言键 */
    public String key() {
        return key;
    }
}
