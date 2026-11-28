package com.enik.zombietide.client;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.client.gui.settings.OptionEntry;
import com.enik.zombietide.client.gui.settings.OptionScope;
import com.enik.zombietide.client.gui.settings.SettingRow;
import com.enik.zombietide.client.gui.settings.SettingsCategory;
import com.enik.zombietide.client.gui.settings.SettingsHost;
import com.enik.zombietide.client.gui.settings.SettingsRegistry;
import com.enik.zombietide.config.ClientHudConfig;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.network.ModMessages;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 尸潮配置界面。
 * <p>
 * 信息架构：
 * <ul>
 *   <li>按作用对象分为 6 个分页：玩家尸潮 / 村民尸潮 / 生成性能 / 世界规则 / 警告提示 / HUD。</li>
 *   <li>每个分页内部再按功能分区（总开关、触发节奏、规模、范围……），带分隔线与分区标题。</li>
 *   <li>每个设置项统一为「作用域标签 + 名称」「灰色说明」「控件」三行结构，并带悬停详细提示。</li>
 *   <li>HUD 分页为<b>本机全局</b>配置（{@link ClientHudConfig}），不随存档保存；其余分页写入存档级配置。</li>
 * </ul>
 * 滚动实现要点（修复历史遗留的滑动/渲染/重叠/多绘问题）：
 * <ul>
 *   <li><b>固定区域不参与滚动</b>：标题、标签页、完成按钮不加入 renderable 列表，改为手动渲染与命中。</li>
 *   <li><b>scissor 用固定屏幕坐标</b>：不叠加 scrollOffset，避免裁剪区随滚动漂移。</li>
 *   <li><b>标签只偏移一次</b>：在已平移的 pose 内按原始 y 绘制。</li>
 *   <li><b>背景只画一次</b>：render() 开头用 100% 不透明的壁纸铺满全屏（不再调用 renderBackground）；
 *       滚动内容必须手动遍历 renderables 渲染，不能调用 super.render —— 它会在 scissor+translate 下重复绘制背景。</li>
 *   <li><b>壁纸随分页左右滑动</b>：按 currentTab 计算目标水平偏移并平滑插值（类似安卓桌面壁纸）。</li>
 * </ul>
 */
public class ZombietideSettingsScreen extends Screen implements SettingsHost {

    private final Screen parent;
    private ZombietideConfig config;
    private ZombietideConfig.HudConfig hudConfig;
    private boolean worldLoaded = false;

    /** 分页：按作用对象/主题划分 */
    private SettingsCategory currentTab = SettingsCategory.PLAYER;
    /** 固定顶部标签页（不加入 renderable 列表，手动渲染/命中） */
    private final List<Button> tabButtons = new ArrayList<>();
    /** 普通文本行（设置名 + 说明） */
    private final List<LabelData> tabLabels = new ArrayList<>();
    /** 分区标题（带分隔线） */
    private final List<SectionHeader> sectionHeaders = new ArrayList<>();

    // 底部完成按钮（固定，不加入 renderable 列表）
    private Button doneBtn;

    // UI 状态
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private boolean draggingScrollBar = false;
    private int scrollBarStartY = 0;
    private int scrollOffsetStart = 0;

    private long lastToastMs = 0L;

    private static final int ROW_HEIGHT = 58;
    private static final int SECTION_HEIGHT = 34;
    private static final int TAB_HEIGHT = 22;
    private static final int TAB_TOP = 20;
    private static final int CONTENT_TOP = 50;
    private static final int CONTENT_BOTTOM = 34;
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_WIDTH = 200;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 4;
    /** 生成半径输入上限：防止填出离谱值（实际生效值还会被服务器模拟距离再压一次） */
    private static final int MAX_RADIUS_INPUT = 512;

    // ==================== 背景壁纸 ====================
    /** 壁纸纹理（完整资源路径；SimpleTexture 不做前缀/后缀转换）。图片为 rgb24，无 alpha 通道。 */
    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(EnikZombietide.MODID, "textures/gui/settings_bg.png");
    /** 原始图片尺寸：5504×1112 的宽幅长图（超分辨率版，比例与旧版 1376×278 一致） */
    private static final int BG_TEX_W = 5504;
    private static final int BG_TEX_H = 1112;
    /** 当前水平滚动偏移（屏幕像素），切换分页时向目标平滑插值 */
    private float bgScroll = 0.0F;
    private boolean bgScrollReady = false;
    /** 记录上次渲染的窗口尺寸：尺寸变化时重新吸附背景偏移 */
    private int bgLastWidth = -1;
    private int bgLastHeight = -1;

    // ==================== 左下角封面 + 版本号 ====================
    /**
     * 3D 标题封面（"Zombie Tide" 字标）。
     * <p>原始 2455×373 大图在入包前已等比压到 1024×156，避免几 MB 的图直接进 jar；
     * 背景为透明（RGBA），叠在壁纸上不会出现方形色块。
     */
    private static final ResourceLocation COVER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(EnikZombietide.MODID, "textures/gui/settings_cover.png");
    /** 封面贴图的真实像素尺寸：必须与 settings_cover.png 完全一致，否则采样会错位 */
    private static final int COVER_TEX_W = 1024;
    private static final int COVER_TEX_H = 156;
    /** 封面在界面上的高度上下限（GUI 像素）：窗口越窄自动越小，避免压住「完成」按钮 */
    private static final int COVER_MAX_H = 28;
    private static final int COVER_MIN_H = 14;
    /** 封面组距屏幕左边缘 / 下边缘的留白 */
    private static final int COVER_MARGIN_LEFT = 8;
    private static final int COVER_MARGIN_BOTTOM = 5;
    /** 封面与版本号文字之间的间距 */
    private static final int COVER_TEXT_GAP = 8;
    /** 运行期缓存：mod 版本号只需从 ModList 取一次 */
    private static String cachedModVersion;

    private Checkbox playerHordeEnabledCheckbox;
    private EditBox playerIntervalDaysEdit;
    private Checkbox playerIntervalRandomCheckbox;
    private EditBox playerIntervalMinEdit;
    private EditBox playerIntervalMaxEdit;
    private EditBox playerCountEdit;
    private Checkbox playerCountRandomCheckbox;
    private EditBox playerCountMinEdit;
    private EditBox playerCountMaxEdit;

    private Checkbox villagerHordeEnabledCheckbox;
    private EditBox villagerIntervalDaysEdit;
    private Checkbox villagerIntervalRandomCheckbox;
    private EditBox villagerIntervalMinEdit;
    private EditBox villagerIntervalMaxEdit;
    private EditBox villagerCountEdit;
    private Checkbox villagerCountRandomCheckbox;
    private EditBox villagerCountMinEdit;
    private EditBox villagerCountMaxEdit;

    private EditBox batchTickEdit;
    private EditBox batchSizeEdit;
    private EditBox villagerBatchSizeEdit;
    private EditBox playerRadiusEdit;
    private EditBox villagerRadiusEdit;
    private Checkbox spawnByBiomeCheckbox;
    private EditBox minSpawnDistEdit;
    private EditBox spawnAttemptsEdit;
    private EditBox distanceWeightEdit;
    private EditBox darkWeightEdit;
    private EditBox globalCapEdit;

    private Checkbox daylightBurnCheckbox;
    private Checkbox blockSleepCheckbox;
    private EditBox debtCapEdit;
    private EditBox despawnSecondsEdit;

    private Checkbox warningEnabledCheckbox;
    private Checkbox warningTitleCheckbox;
    private Checkbox warningSoundCheckbox;
    private EditBox villagerWarningRangeEdit;
    private EditBox villagerSleepBlockRangeEdit;
    private Checkbox blockNaturalSpawnCheckbox;

    private Checkbox hudEnabledCheckbox;
    private Checkbox showVillagerHordeCheckbox;
    private EditBox villagerRangeEdit;
    private EditBox villagerMaxEntriesEdit;
    private EditBox hudXEdit;
    private EditBox hudYEdit;
    private EditBox villagerHordeXEdit;
    private EditBox villagerHordeYEdit;
    private EditBox hudScaleEdit;
    private EditBox villagerHordeScaleEdit;
    private Checkbox hudBoldCheckbox;
    private Checkbox villagerHordeBoldCheckbox;
    /** 【v0.2.91】DAY X 渐进红（本机显示偏好，随 commitClientHud 落盘） */
    private boolean dayGradientRed;

    public ZombietideSettingsScreen(Screen parent, ZombietideConfig config) {
        super(Component.literal(tr("zombietide.settings.title")));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();
        this.worldLoaded = this.minecraft != null && this.minecraft.level != null && this.minecraft.player != null;
        this.hudConfig = ClientHudConfig.get();
        this.dayGradientRed = ClientHudConfig.dayGradientRed();
        this.scrollOffset = 0;
        this.rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        if (this.hudConfig == null) this.hudConfig = ClientHudConfig.get();
        this.tabLabels.clear();
        this.sectionHeaders.clear();
        this.tabButtons.clear();
        this.clearWidgets();
        this.doneBtn = null;

        // ===== 固定顶部标签页（手动渲染/命中，不进入滚动容器） =====
        int tabCount = SettingsCategory.values().length;
        int tabWidth = Math.max(40, Math.min(96, (this.width - 16) / tabCount));
        int tabStartX = (this.width - tabWidth * tabCount) / 2;
        for (int i = 0; i < tabCount; i++) {
            SettingsCategory tab = SettingsCategory.values()[i];
            Button btn = Button.builder(Component.literal(tr(tab.key())), b -> {
                this.currentTab = tab;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabStartX + i * tabWidth, TAB_TOP, tabWidth, TAB_HEIGHT).build();
            this.tabButtons.add(btn);
        }

        // ===== 内容控件（进入 renderable 列表，随 pose 平移） =====
        int centerX = this.width / 2;
        int y = CONTENT_TOP + 8;

        // 【P1 重构】选项定义全部收敛到 SettingsRegistry，这里只负责把「行」翻译成控件。
        // 顺序、分区标题、注释由 registry 保证与旧实现一致（纯重构，外观不变）。
        for (SettingRow row : SettingsRegistry.rowsFor(this.currentTab)) {
            switch (row) {
                case SettingRow.SectionRow sec -> y = addSection(sec.titleKey(), centerX, y);
                case SettingRow.NoteRow note -> y = addNote(note.textKey(), centerX, y);
                case SettingRow.OptionRow optRow -> {
                    OptionEntry e = optRow.entry();
                    switch (e.type()) {
                        case TOGGLE -> addCheckbox(e.nameKey(), e.scope(), e.descKey(), e.tipKey(),
                                e.reader().applyAsInt(this) != 0, centerX, y,
                                (cb, v) -> applyOption(e, v ? 1 : 0), e.clientOnly());
                        case INT -> addIntField(e.nameKey(), e.scope(), e.descKey(), e.tipKey(),
                                e.reader().applyAsInt(this), centerX, y, e.min(), e.max(),
                                v -> applyOption(e, v), e.clientOnly());
                        case CYCLE -> addCycleButton(e.nameKey(), e.scope(), e.descKey(), e.tipKey(),
                                () -> e.cycleLabel().apply(this), centerX, y,
                                () -> e.cycleAction().accept(this));
                    }
                    y += ROW_HEIGHT;
                }
            }
        }

        // ===== 固定底部完成按钮（手动渲染/命中） =====
        this.doneBtn = Button.builder(Component.literal(tr("zombietide.settings.button.done")), b -> this.onClose())
                .bounds(this.width / 2 - 60, this.height - 28, 120, 20).build();

        // 内容高度：以最后一个控件底边为准 + 底部留白
        int lastControlBottom = y - ROW_HEIGHT + 22 + FIELD_HEIGHT;
        this.contentHeight = Math.max(FIELD_HEIGHT, lastControlBottom - CONTENT_TOP + 16);
        this.scrollOffset = Math.max(0, Math.min(maxScroll(), this.scrollOffset));
    }

    // ==================== 滚动几何 ====================

    private int viewHeight() {
        return Math.max(0, (this.height - CONTENT_BOTTOM) - CONTENT_TOP);
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - viewHeight());
    }

    private int thumbHeight() {
        int vh = viewHeight();
        if (this.contentHeight <= 0 || vh <= 0) return vh;
        return Math.max(12, Math.min(vh, vh * vh / this.contentHeight));
    }

    private int thumbTop() {
        int ms = maxScroll();
        if (ms <= 0) return CONTENT_TOP;
        int range = Math.max(0, viewHeight() - thumbHeight());
        return CONTENT_TOP + (int) ((long) this.scrollOffset * range / ms);
    }

    private boolean isOverContent(double mouseY) {
        return mouseY >= CONTENT_TOP && mouseY <= this.height - CONTENT_BOTTOM;
    }

    private boolean isOverScrollBar(double mouseX, double mouseY) {
        if (this.contentHeight <= viewHeight() || !isOverContent(mouseY)) return false;
        int right = this.width - SCROLLBAR_GAP;
        int left = right - SCROLLBAR_WIDTH;
        return mouseX >= left - 3 && mouseX <= right + 3;
    }

    private static boolean isInside(AbstractWidget widget, double mouseX, double mouseY) {
        return mouseX >= widget.getX() && mouseX < widget.getX() + widget.getWidth()
                && mouseY >= widget.getY() && mouseY < widget.getY() + widget.getHeight();
    }

    // ==================== 渲染 ====================

    /**
     * 绘制 100% 不透明背景壁纸。
     * <ul>
     *   <li><b>放大铺满（cover）</b>：scale = max(屏宽/图宽, 屏高/图高)，额外 +2px 冗余，
     *       保证上下左右都不露黑边。图片是 5504×1112 的宽幅长图，按高度铺满后宽度远大于屏幕，
     *       正好留出左右滚动空间。</li>
     *   <li><b>不透明</b>：不再调用 renderBackground（那是半透明暗色遮罩）；
     *       整屏由 rgb24 图片覆盖（NativeImage 会把 alpha 填 255），并把 shader color 的 alpha 显式置 1。</li>
     *   <li><b>随分页左右滑动（安卓桌面壁纸式）</b>：滑动范围完全由图片绘制宽度决定
     *       —— maxScroll = drawW - 屏宽；第 1 个分页停在图片左边缘（偏移 0），
     *       最后一个分页停在图片右边缘（偏移 maxScroll），中间分页按进度落在中间。
     *       每帧平滑插值，首次打开/窗口尺寸变化时直接吸附到目标位置。</li>
     * </ul>
     */
    private void renderWallpaper(GuiGraphics g) {
        int w = this.width;
        int h = this.height;

        // 窗口尺寸变化（改窗口大小 / GUI 缩放）时重新吸附，避免沿用旧偏移
        if (w != this.bgLastWidth || h != this.bgLastHeight) {
            this.bgLastWidth = w;
            this.bgLastHeight = h;
            this.bgScrollReady = false;
        }

        // +2 像素冗余，避免浮点取整在边缘留下 1px 缝隙
        float scale = Math.max((float) (w + 2) / BG_TEX_W, (float) (h + 2) / BG_TEX_H);
        float drawW = BG_TEX_W * scale;
        float drawH = BG_TEX_H * scale;
        // 滑动范围完全由「图片绘制宽度 - 屏幕宽度」决定
        float maxScroll = Math.max(0.0F, drawW - w);

        int tabCount = SettingsCategory.values().length;
        // 进度 0 = 第一个分页（图片左边缘）；1 = 最后一个分页（图片右边缘）；中间按比例
        float progress = tabCount <= 1 ? 0.0F
                : this.currentTab.ordinal() / (float) (tabCount - 1);
        float target = maxScroll * progress;

        if (!this.bgScrollReady) {
            this.bgScroll = target;
            this.bgScrollReady = true;
        }
        // 平滑跟随，约十余帧到位；差值小于 0.5px 时直接吸附，避免无休止逼近
        this.bgScroll += (target - this.bgScroll) * 0.18F;
        if (Math.abs(target - this.bgScroll) < 0.5F) this.bgScroll = target;

        float scrollX = Math.max(0.0F, Math.min(maxScroll, this.bgScroll));
        float scrollY = Math.max(0.0F, (drawH - h) / 2.0F); // 垂直居中裁剪

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0F);
        g.pose().translate(-scrollX / scale, -scrollY / scale, 0.0F);
        // 整张纹理按 pose 的 scale 放大；blit 的纹理采样与屏幕区域 1:1，缩放由 pose 完成
        g.blit(BG_TEXTURE, 0, 0, 0, 0.0F, 0.0F, BG_TEX_W, BG_TEX_H, BG_TEX_W, BG_TEX_H);
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderWallpaper(guiGraphics);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 6, 0xFFFFFF);

        // 标签页（固定顶部，手动渲染，只画一次）
        for (int i = 0; i < this.tabButtons.size(); i++) {
            Button btn = this.tabButtons.get(i);
            if (i == this.currentTab.ordinal()) {
                guiGraphics.fill(btn.getX(), btn.getY(),
                        btn.getX() + btn.getWidth(), btn.getY() + btn.getHeight(), 0x66FFFFFF);
            }
            btn.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        guiGraphics.fill(0, CONTENT_TOP - 2, this.width, CONTENT_TOP - 1, 0x40FFFFFF);

        int scissorTop = CONTENT_TOP;
        int scissorBottom = this.height - CONTENT_BOTTOM;
        if (scissorBottom > scissorTop) {
            guiGraphics.enableScissor(0, scissorTop, this.width, scissorBottom);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, -this.scrollOffset, 0.0F);

            // 分区标题（分隔线 + 标题），随内容滚动
            for (SectionHeader s : this.sectionHeaders) {
                int screenY = s.y() - this.scrollOffset;
                if (screenY + 12 >= scissorTop && screenY <= scissorBottom) {
                    guiGraphics.fill(s.x() - 110, s.y() - 5, s.x() + 110, s.y() - 4, 0x40FFFFFF);
                    guiGraphics.drawCenteredString(this.font, Component.literal("§6§l" + s.title()),
                            s.x(), s.y() + 1, 0xFFFFFF);
                }
            }

            // 仅渲染内容控件，不能调用 super.render：
            // Screen.render 内部会再次调用 renderBackground，在 scissor+translate 下重复绘制背景。
            for (Renderable renderable : this.renderables) {
                renderable.render(guiGraphics, mouseX, mouseY + this.scrollOffset, partialTick);
            }

            // 设置名与说明（只偏移一次）
            for (LabelData label : this.tabLabels) {
                int screenY = label.y() - this.scrollOffset;
                if (screenY + 9 >= scissorTop && screenY <= scissorBottom) {
                    guiGraphics.drawCenteredString(this.font, Component.literal(label.text()),
                            label.x(), label.y(), 0xFFFFFF);
                }
            }

            guiGraphics.pose().popPose();
            guiGraphics.disableScissor();
        }

        drawScrollBar(guiGraphics);

        if (this.doneBtn != null) {
            this.doneBtn.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // 左下角：3D 标题封面 + 运行期版本号（固定，不随内容滚动）
        renderCoverAndVersion(guiGraphics);
    }

    /**
     * 读取当前正在运行的 mod 版本号。
     * <p>来源是构建时由 {@code gradle.properties} 的 {@code mod_version} 注入到
     * {@code neoforge.mods.toml} 的 version 字段，运行期再从 ModList 读回，
     * 因此发版只改 gradle.properties，界面会自动跟着变，不需要动界面代码。
     */
    private static String modVersion() {
        if (cachedModVersion == null) {
            cachedModVersion = ModList.get().getMods().stream()
                    .filter(info -> EnikZombietide.MODID.equals(info.getModId()))
                    .map(info -> info.getVersion().toString())
                    .findFirst()
                    .orElse("unknown");
        }
        return cachedModVersion;
    }

    /**
     * 左下角绘制「3D 标题封面 + 版本号：xxx」。
     * <p>绘制方式与壁纸保持一致：{@code blit} 只按纹理原始像素采样，缩放全部交给 pose，
     * 这样封面能任意缩小而不会只画出左上角一块（blit 的 width/height 同时参与 UV 计算）。
     * 尺寸随窗口宽度收缩；横向空间不足时继续等比缩小，小到没有意义就整块不画。
     */
    private void renderCoverAndVersion(GuiGraphics g) {
        String versionText = tr("zombietide.settings.version") + " " + modVersion();
        int textW = this.font.width(versionText);

        int drawH = this.width / 34;
        if (drawH > COVER_MAX_H) drawH = COVER_MAX_H;
        if (drawH < COVER_MIN_H) drawH = COVER_MIN_H;
        int drawW = Math.max(1, Math.round(drawH * (float) COVER_TEX_W / COVER_TEX_H));

        // 整组左对齐：只有「完成」按钮左缘之前才是可用空间，避免窄窗口下压住按钮
        int doneLeft = this.doneBtn != null
                ? this.doneBtn.getX()
                : this.width / 2 - 60;
        int avail = (doneLeft - 8) - COVER_MARGIN_LEFT;
        int need = drawW + COVER_TEXT_GAP + textW;
        if (need > avail) {
            int maxCoverW = avail - COVER_TEXT_GAP - textW;
            if (maxCoverW < 24) return; // 窗口太窄，画出来只会糊成一团，直接跳过
            float k = (float) maxCoverW / drawW;
            drawW = maxCoverW;
            drawH = Math.max(1, Math.round(drawH * k));
            need = drawW + COVER_TEXT_GAP + textW;
        }
        if (need > avail) return;

        int coverX = COVER_MARGIN_LEFT;
        int coverY = this.height - COVER_MARGIN_BOTTOM - drawH;

        // 封面带 alpha 通道，必须开混合，否则透明区域会渲染成黑块
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.pose().pushPose();
        g.pose().translate(coverX, coverY, 0.0F);
        g.pose().scale((float) drawW / COVER_TEX_W, (float) drawH / COVER_TEX_H, 1.0F);
        g.blit(COVER_TEXTURE, 0, 0, 0, 0.0F, 0.0F, COVER_TEX_W, COVER_TEX_H, COVER_TEX_W, COVER_TEX_H);
        g.pose().popPose();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // 版本号在封面右侧，与封面垂直居中对齐
        int textX = coverX + drawW + COVER_TEXT_GAP;
        int textY = coverY + (drawH - this.font.lineHeight) / 2;
        g.drawString(this.font, versionText, textX, textY, 0xFFFFFF, true);
    }

    private void drawScrollBar(GuiGraphics guiGraphics) {
        int vh = viewHeight();
        if (this.contentHeight <= vh || vh <= 0) return;
        int right = this.width - SCROLLBAR_GAP;
        int left = right - SCROLLBAR_WIDTH;
        int top = CONTENT_TOP;
        int bottom = this.height - CONTENT_BOTTOM;
        guiGraphics.fill(left, top, right, bottom, 0x30FFFFFF);
        int thumbTop = thumbTop();
        guiGraphics.fill(left, thumbTop, right, thumbTop + thumbHeight(), 0x90B4B2A9);
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverScrollBar(mouseX, mouseY)) {
            this.draggingScrollBar = true;
            this.scrollBarStartY = (int) mouseY;
            this.scrollOffsetStart = this.scrollOffset;
            return true;
        }

        if (button == 0) {
            for (Button btn : this.tabButtons) {
                if (isInside(btn, mouseX, mouseY)) {
                    this.setFocused(null);
                    btn.mouseClicked(mouseX, mouseY, button);
                    return true;
                }
            }
            if (this.doneBtn != null && isInside(this.doneBtn, mouseX, mouseY)) {
                this.setFocused(null);
                this.doneBtn.mouseClicked(mouseX, mouseY, button);
                return true;
            }
        }

        if (isOverContent(mouseY)) {
            boolean handled = super.mouseClicked(mouseX, mouseY + this.scrollOffset, button);
            if (!handled) this.setFocused(null);
            return handled;
        }

        this.setFocused(null);
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollBar && button == 0) {
            int ms = maxScroll();
            int range = Math.max(0, viewHeight() - thumbHeight());
            if (ms > 0 && range > 0) {
                int delta = (int) mouseY - this.scrollBarStartY;
                long target = (long) this.scrollOffsetStart + (long) delta * ms / range;
                this.scrollOffset = (int) Math.max(0, Math.min(ms, target));
            }
            return true;
        }
        if (isOverContent(mouseY)) {
            return super.mouseDragged(mouseX, mouseY + this.scrollOffset, button, dragX, dragY);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.draggingScrollBar && button == 0) {
            this.draggingScrollBar = false;
            return true;
        }
        if (isOverContent(mouseY)) {
            return super.mouseReleased(mouseX, mouseY + this.scrollOffset, button);
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverContent(mouseY) && this.contentHeight > viewHeight()) {
            int ms = maxScroll();
            this.scrollOffset = (int) Math.max(0, Math.min(ms, this.scrollOffset - scrollY * 24));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 控件工厂 ====================

    private int addSection(String title, int centerX, int y) {
        this.sectionHeaders.add(new SectionHeader(tr(title), centerX, y));
        return y + SECTION_HEIGHT;
    }

    private int addNote(String text, int centerX, int y) {
        this.tabLabels.add(new LabelData("§7" + tr(text), centerX, y));
        return y + 16;
    }

    private Checkbox addCheckbox(String label, OptionScope scope, String desc, String tooltip,
                                 boolean checked, int centerX, int y,
                                 Checkbox.OnValueChange onChange, boolean clientOnly) {
        this.tabLabels.add(new LabelData((scope == null ? "" : "§l" + scope.color() + "[" + tr(scope.key()) + "] §r§f") + tr(label), centerX, y));
        this.tabLabels.add(new LabelData("§7" + tr(desc), centerX, y + 10));
        Checkbox cb = Checkbox.builder(Component.empty(), this.font)
                .pos(centerX - 100, y + 22)
                .selected(checked)
                .onValueChange((cbx, checkedVal) -> {
                    onChange.onValueChange(cbx, checkedVal);
                    if (clientOnly) {
                        commitClientHud();
                    } else {
                        pushConfig();
                    }
                })
                .build();
        cb.setWidth(FIELD_WIDTH);
        cb.setHeight(FIELD_HEIGHT);
        cb.setTooltip(Tooltip.create(Component.literal(tr(tooltip))));
        this.addRenderableWidget(cb);
        return cb;
    }

    private EditBox addIntField(String label, OptionScope scope, String desc, String tooltip,
                                int value, int centerX, int y, int minValue,
                                IntConsumer onChange, boolean clientOnly) {
        return addIntField(label, scope, desc, tooltip, value, centerX, y, minValue,
                Integer.MAX_VALUE, onChange, clientOnly);
    }

    private EditBox addIntField(String label, OptionScope scope, String desc, String tooltip,
                                int value, int centerX, int y, int minValue, int maxValue,
                                IntConsumer onChange, boolean clientOnly) {
        this.tabLabels.add(new LabelData((scope == null ? "" : "§l" + scope.color() + "[" + tr(scope.key()) + "] §r§f") + tr(label), centerX, y));
        this.tabLabels.add(new LabelData("§7" + tr(desc), centerX, y + 10));
        EditBox field = new EditBox(this.font, centerX - 100, y + 22, FIELD_WIDTH, FIELD_HEIGHT, Component.literal(tr(label)));
        field.setValue(String.valueOf(value));
        field.setTooltip(Tooltip.create(Component.literal(tr(tooltip))));
        final boolean[] isSetting = { false };
        final int[] lastSentValue = { value };
        field.setResponder((s) -> {
            if (isSetting[0]) return;
            try {
                int v = Integer.parseInt(s);
                if (v < minValue) {
                    isSetting[0] = true;
                    field.setValue(String.valueOf(minValue));
                    isSetting[0] = false;
                    v = minValue;
                } else if (v > maxValue) {
                    isSetting[0] = true;
                    field.setValue(String.valueOf(maxValue));
                    isSetting[0] = false;
                    v = maxValue;
                }
                onChange.accept(v);
                if (v != lastSentValue[0]) {
                    lastSentValue[0] = v;
                    if (clientOnly) {
                        commitClientHud();
                    } else {
                        pushConfig();
                    }
                }
            } catch (NumberFormatException ignored) {
                // 输入中间态（空串/负号）不提交
            }
        });
        this.addRenderableWidget(field);
        return field;
    }

    private Button addCycleButton(String label, OptionScope scope, String desc, String tooltip,
                                  java.util.function.Supplier<String> valueText, int centerX, int y, Runnable onPress) {
        this.tabLabels.add(new LabelData((scope == null ? "" : "§l" + scope.color() + "[" + tr(scope.key()) + "] §r§f") + tr(label), centerX, y));
        this.tabLabels.add(new LabelData("§7" + tr(desc), centerX, y + 10));
        Button btn = Button.builder(Component.literal(valueText.get()), b -> {
            onPress.run();
            pushConfig();
            // 直接刷新按钮文字，避免重建整页（重建会打断当前点击事件）
            b.setMessage(Component.literal(valueText.get()));
        }).bounds(centerX - 100, y + 22, FIELD_WIDTH, FIELD_HEIGHT).build();
        btn.setTooltip(Tooltip.create(Component.literal(tr(tooltip))));
        this.addRenderableWidget(btn);
        return btn;
    }

    /** 把选项值写回编辑状态，并按它属于本机配置还是存档级配置选择提交路径 */
    private void applyOption(OptionEntry entry, int value) {
        entry.writer().accept(this, value);
        if (entry.clientOnly()) {
            commitClientHud();
        } else {
            pushConfig();
        }
    }

    // ==================== SettingsHost（P1） ====================
    // 给 SettingsRegistry 里的声明式选项提供最小读写面。

    @Override
    public ZombietideConfig config() {
        return this.config;
    }

    @Override
    public void setConfig(ZombietideConfig config) {
        this.config = config;
    }

    @Override
    public ZombietideConfig.HudConfig hud() {
        return this.hudConfig;
    }

    @Override
    public void setHud(ZombietideConfig.HudConfig hud) {
        this.hudConfig = hud;
    }

    @Override
    public boolean dayGradientRed() {
        return this.dayGradientRed;
    }

    @Override
    public void setDayGradientRed(boolean value) {
        this.dayGradientRed = value;
    }

    /** 服务端存档级配置：立即同步并提示 */
    private void pushConfig() {
        if (!this.worldLoaded || this.minecraft == null || this.minecraft.player == null) return;
        // 保持存档里的 HUD 字段与本机全局配置一致（仅兼容旧数据，实际渲染以本机为准）
        this.config = this.config.withHud(ClientHudConfig.get());
        ModMessages.sendConfigToServer(this.config);
        toast(tr("zombietide.settings.toast.config_saved"));
    }

    /** 本机全局 HUD 配置：只写本地文件，不发服务端 */
    private void commitClientHud() {
        ClientHudConfig.setDayGradientRed(this.dayGradientRed);
        ClientHudConfig.set(this.hudConfig);
        this.config = this.config.withHud(this.hudConfig);
        toast(tr("zombietide.settings.toast.hud_saved"));
    }

    private void toast(String msg) {
        long now = Util.getMillis();
        if (now - this.lastToastMs < 800L) return;
        this.lastToastMs = now;
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(Component.literal(msg), false);
        }
    }

    /** 从客户端语言文件取文字；缺键时 I18n.get 原样返回键，便于发现遗漏 */
    private static String tr(String key) {
        return I18n.get(key);
    }

    private record LabelData(String text, int x, int y) {}

    private record SectionHeader(String title, int x, int y) {}
}
