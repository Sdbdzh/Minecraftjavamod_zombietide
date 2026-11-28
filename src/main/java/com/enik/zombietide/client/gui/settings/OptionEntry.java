package com.enik.zombietide.client.gui.settings;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * 单个设置项的**声明式定义**。
 * <p>语言键只写 {@code nameKey}，描述与提示按约定派生：
 * {@code xxx.name → xxx.desc / xxx.tip}（与现有语言文件命名完全一致）。
 * <p>布尔值统一用 int(0/1) 表达，避免为两种类型各写一套回调。
 *
 * @param nameKey     名称语言键
 * @param scope       作用域徽标，{@code null} 表示不显示徽标（HUD / 氛围）
 * @param type        控件类型
 * @param min          整数下限（TOGGLE / CYCLE 忽略）
 * @param max          整数上限（TOGGLE / CYCLE 忽略）
 * @param defaultValue 默认值（P3 逐项重置 / 重置本页用；布尔为 0/1）
 * @param clientOnly   true = 只写本机配置（不发服务端）
 * @param reader      读当前值
 * @param writer      写回编辑状态
 * @param cycleLabel  CYCLE 专用：按钮当前文字
 * @param cycleAction CYCLE 专用：点击后推进到下一个值
 */
public record OptionEntry(
        String nameKey,
        OptionScope scope,
        ControlType type,
        int min,
        int max,
        int defaultValue,
        boolean clientOnly,
        ToIntFunction<SettingsHost> reader,
        BiConsumer<SettingsHost, Integer> writer,
        Function<SettingsHost, String> cycleLabel,
        Consumer<SettingsHost> cycleAction
) {

    public String descKey() {
        return nameKey.replace(".name", ".desc");
    }

    public String tipKey() {
        return nameKey.replace(".name", ".tip");
    }

    public boolean isToggle() {
        return type == ControlType.TOGGLE;
    }

    public boolean isCycle() {
        return type == ControlType.CYCLE;
    }
}
