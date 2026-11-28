package com.enik.zombietide.client.gui.settings.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 【v0.2.133】Sodium 风格扁平开关：胶囊底 + 1px 边框 + 左侧状态文字 + 右侧指示点。
 * <p>替换原版 {@code Checkbox}（带方块与勾选纹理，与自绘界面风格冲突）。
 * <p>开启 = 橙色系；关闭 = 深灰系。悬停整体提亮。
 */
public class FlatToggle extends AbstractWidget {

    /** 状态切换回调 */
    @FunctionalInterface
    public interface Handler {
        void onToggle(boolean value);
    }

    private boolean value;
    private final Handler handler;
    private final Component onText;
    private final Component offText;

    public FlatToggle(int x, int y, int width, int height,
                      boolean initial, Component onText, Component offText, Handler handler) {
        super(x, y, width, height, Component.empty());
        this.value = initial;
        this.onText = onText;
        this.offText = offText;
        this.handler = handler;
    }

    public boolean value() {
        return value;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();

        int fill = value ? 0x55FFB347 : 0x55000000;
        int border = value ? 0xFFFFB347 : 0x55FFFFFF;
        g.fill(x, y, x + w, y + h, fill);
        // 1px 边框（fill 只能画矩形，四边各一条）
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);

        if (isHoveredOrFocused()) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x1FFFFFFF);
        }

        Minecraft mc = Minecraft.getInstance();
        Component label = value ? onText : offText;
        g.drawString(mc.font, label,
                x + 6, y + (h - 8) / 2 + 1,
                value ? 0xFFFFE0B2 : 0xFF9A9A9A, false);

        // 右侧指示点：4×4 实心方块
        int dot = value ? 0xFFFFB347 : 0xFF4A4A4A;
        int cx = x + w - 9;
        int cy = y + h / 2;
        g.fill(cx, cy - 2, cx + 4, cy + 2, dot);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.active || !this.visible || !isMouseOver(mouseX, mouseY)) return false;
        this.value = !this.value;
        this.handler.onToggle(this.value);
        return true;
    }

    /** 外部同步状态（如重置后重建，或值被其它路径改写） */
    public void setValueSilently(boolean v) {
        this.value = v;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                value ? onText : offText);
    }
}
