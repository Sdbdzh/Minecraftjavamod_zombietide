package com.enik.zombietide.client.gui.settings;

/**
 * 设置页的一行。列表顺序即渲染顺序，保证与旧实现的分区/选项次序完全一致。
 */
public sealed interface SettingRow {

    /** 分区标题（带分隔线） */
    record SectionRow(String titleKey) implements SettingRow {
    }

    /** 纯文本注释行 */
    record NoteRow(String textKey) implements SettingRow {
    }

    /** 一个设置项 */
    record OptionRow(OptionEntry entry) implements SettingRow {
    }
}
