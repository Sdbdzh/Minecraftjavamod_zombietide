package com.enik.zombietide.client.gui.settings.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * 【P3】整数滑块：拖动调值、点击轨道跳转、按住 Shift 精调。
 * <p>替换原版 {@code EditBox} —— 63 个选项里 37 个是数字，手输要全选重打，滑块直观得多。
 * <p>数值文字画在滑块右侧；与默认值不同时由屏幕侧用橙色标出。
 * <p><b>提交策略</b>：拖动过程中只回调 {@code onValueChanged}（改内存状态，不发包）；
 * 松开鼠标或点击跳转后才回调 {@code onCommit}（触发 pushConfig/commitClientHud），
 * 避免拖动时每帧发一个配置包。
 */
public class IntSliderControl extends AbstractWidget {

    /** 右侧数值文字的保留宽度 */
    private static final int VALUE_W = 38;

    private final int min;
    private final int max;
    private final IntConsumer onValueChanged;
    private final Runnable onCommit;
    private int value;

    private boolean dragging;
    /** 本次拖动是否按住 Shift（精调） */
    private boolean fineDrag;
    private int dragStartX;
    private int dragStartValue;

    public IntSliderControl(int x, int y, int width, int height,
                            int min, int max, int value,
                            IntConsumer onValueChanged, Runnable onCommit) {
        super(x, y, width, height, Component.empty());
        this.min = min;
        this.max = Math.max(min, max);
        this.value = clamp(value);
        this.onValueChanged = onValueChanged;
        this.onCommit = onCommit;
    }

    public int value() {
        return value;
    }

    public void setValueSilently(int v) {
        this.value = clamp(v);
    }

    private int clamp(int v) {
        return Math.max(min, Math.min(max, v));
    }

    private int trackWidth() {
        return Math.max(12, getWidth() - VALUE_W);
    }

    private float ratio() {
        if (max <= min) return 0.0F;
        return (value - min) / (float) (max - min);
    }

    private int thumbX() {
        return getX() + Math.round(ratio() * (trackWidth() - 2));
    }

    /** 由鼠标横坐标反算数值 */
    private int valueAt(double mouseX) {
        int tw = trackWidth() - 2;
        if (tw <= 0) return min;
        double t = (mouseX - getX()) / (double) tw;
        t = Math.max(0.0, Math.min(1.0, t));
        return clamp(min + (int) Math.round(t * (max - min)));
    }

    private void apply(int v, boolean commit) {
        int nv = clamp(v);
        if (nv == value) {
            if (commit) onCommit.run();
            return;
        }
        value = nv;
        onValueChanged.accept(nv);
        if (commit) onCommit.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cy = getY() + getHeight() / 2;
        int tw = trackWidth();
        // 轨道底色
        g.fill(getX(), cy - 1, getX() + tw, cy + 1, 0xFF2A2A2E);
        // 已填充部分
        int thumb = thumbX();
        g.fill(getX(), cy - 1, thumb, cy + 1, 0xFFFF4D4D);
        // 滑块手柄
        g.fill(thumb - 1, getY() + 2, thumb + 1, getY() + getHeight() - 2, isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFDDDDDD);
        // 数值文字（右对齐保留区）
        Minecraft mc = Minecraft.getInstance();
        String txt = String.valueOf(value);
        int textY = getY() + (getHeight() - 8) / 2 + 1;
        g.drawString(mc.font, txt, getX() + getWidth() - mc.font.width(txt), textY, 0xFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !this.active || !this.visible || !isMouseOver(mouseX, mouseY)) return false;
        this.dragging = true;
        this.fineDrag = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        this.dragStartX = (int) mouseX;
        this.dragStartValue = this.value;
        // 点击轨道直接跳转（精调模式下不跳转，避免起手就把值推走）
        if (!this.fineDrag) {
            apply(valueAt(mouseX), true);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!this.dragging || button != 0) return false;
        if (this.fineDrag) {
            // 精调：按位移比例缩放，约 1/7 灵敏度
            int tw = Math.max(1, trackWidth() - 2);
            double span = Math.max(1, max - min);
            int delta = (int) Math.round((mouseX - dragStartX) * span / tw * 0.15);
            apply(dragStartValue + delta, false);
        } else {
            apply(valueAt(mouseX), false);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!this.dragging) return false;
        this.dragging = false;
        apply(this.value, true);
        return true;
    }

    /** 供屏幕在右击重置后同步显示值（不回调，避免递归提交） */
    public void syncFromModel(int v) {
        this.value = clamp(v);
        this.dragging = false;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.literal(String.valueOf(value)));
    }
}
