package com.enik.zombietide.client.gui.settings.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * 【v0.2.133】Sodium 风格扁平按钮：无原版纹理，仅 1px 边框 + 纯色底 + 居中文字。
 * <p>用于底栏「重置本页」「完成」。
 * <ul>
 *   <li>{@code accent = true}：主按钮（完成），橙色边框与文字，悬停加深底色</li>
 *   <li>{@code accent = false}：次按钮（重置本页），白色边框与文字</li>
 * </ul>
 */
public class FlatButton extends AbstractWidget {

    private final boolean accent;
    private final Runnable onPress;

    public FlatButton(int x, int y, int width, int height, Component text,
                      boolean accent, Runnable onPress) {
        super(x, y, width, height, text);
        this.accent = accent;
        this.onPress = onPress;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hover = isHoveredOrFocused();

        int fill = accent
                ? (hover ? 0x66FFB347 : 0x33FFB347)
                : (hover ? 0x44FFFFFF : 0x22000000);
        int border = accent ? 0xCCFFB347 : (hover ? 0xAAFFFFFF : 0x55FFFFFF);
        int textColor = accent ? 0xFFFFE0B2 : (hover ? 0xFFFFFFFF : 0xFFDDDDDD);

        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);

        Minecraft mc = Minecraft.getInstance();
        int tw = mc.font.width(getMessage());
        g.drawString(mc.font, getMessage(),
                x + (w - tw) / 2, y + (h - 8) / 2 + 1, textColor, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.active || !this.visible || !isMouseOver(mouseX, mouseY)) return false;
        this.onPress.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, getMessage());
    }
}
