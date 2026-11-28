package com.enik.zombietide.client.gui.settings;

/**
 * 作用域徽标：告诉玩家这项设置**影响谁**，不代表「谁能改」。
 * <p>除 HUD / 氛围外的全部选项都是存档级参数，只有 OP 2+ 或单机房主能保存
 * （服务端在 {@code ConfigToServerPayload} handler 校验）。
 */
public enum OptionScope {
    PLAYER("zombietide.settings.scope.player", "§9"),
    VILLAGER("zombietide.settings.scope.villager", "§a"),
    WORLD("zombietide.settings.scope.world", "§6");

    private final String key;
    private final String color;

    OptionScope(String key, String color) {
        this.key = key;
        this.color = color;
    }

    public String key() {
        return key;
    }

    /** 徽标文字颜色（Minecraft 格式化码） */
    public String color() {
        return color;
    }
}
