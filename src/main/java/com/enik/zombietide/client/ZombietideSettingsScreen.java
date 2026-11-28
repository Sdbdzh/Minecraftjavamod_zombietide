package com.enik.zombietide.client;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.client.gui.settings.OptionEntry;
import com.enik.zombietide.client.gui.settings.OptionScope;
import com.enik.zombietide.client.gui.settings.SettingRow;
import com.enik.zombietide.client.gui.settings.SettingsCategory;
import com.enik.zombietide.client.gui.settings.SettingsHost;
import com.enik.zombietide.client.gui.settings.SettingsRegistry;
import com.enik.zombietide.client.gui.settings.control.FlatButton;
import com.enik.zombietide.client.gui.settings.control.FlatToggle;
import com.enik.zombietide.client.gui.settings.control.IntSliderControl;
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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 尸潮配置界面。
 * <p>
 * 信息架构：
 * <ul>
 *   <li>按作用对象分为 7 个分页：玩家尸潮 / 村民尸潮 / 生成性能 / 世界规则 / 警告提示 / HUD / 氛围。</li>
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
    // 【v0.2.132】侧栏改为自绘扁平项（Sodium 风格），不再持有 Button 控件；
    // 布局位置由 sidebarItemY(i) 计算，绘制在 render()、命中在 mouseClicked()。
    /** 普通文本行（设置名 + 说明） */
    private final List<LabelData> tabLabels = new ArrayList<>();
    /** 分区标题（带分隔线） */
    private final List<SectionHeader> sectionHeaders = new ArrayList<>();
    /** 【P2】每行的纵向范围 + 悬停提示内容（本屏幕不调用 super.render，tooltip 必须手绘） */
    private final List<RowBounds> rowBounds = new ArrayList<>();

    // 底部按钮（固定，不加入 renderable 列表；v0.2.133 起为自绘扁平按钮）
    private FlatButton doneBtn;
    /** 【P3】重置当前分类全部选项 */
    private FlatButton resetPageBtn;
    /** 【P4】搜索框：不放进 renderables（否则每次 rebuild 会被 clearWidgets 清掉、丢失焦点与内容） */
    private EditBox searchBox;
    private String searchQuery = "";
    /** 防止 setValue 触发 responder 造成 rebuild 递归 */
    private boolean updatingSearch = false;
    /** 搜索结果里每个选项所属分类（用于标签前缀） */
    private final Map<OptionEntry, SettingsCategory> searchCategoryOf = new IdentityHashMap<>();

    // UI 状态
    private int scrollOffset = 0;
    private int contentHeight = 0;
    private boolean draggingScrollBar = false;
    private int scrollBarStartY = 0;
    private int scrollOffsetStart = 0;

    private long lastToastMs = 0L;

    /** 【P2】紧凑单行：一行 = 标签（左）+ 控件（右），描述改为悬停提示 */
    private static final int ROW_HEIGHT = 26;
    private static final int SECTION_HEIGHT = 22;
    private static final int CONTENT_TOP = 50;
    private static final int CONTENT_BOTTOM = 34;
    private static final int FIELD_HEIGHT = 16;
    private static final int FIELD_WIDTH = 150;
    /** 左侧分类栏 */
    private static final int SIDEBAR_X = 8;
    private static final int SIDEBAR_WIDTH = 130;
    private static final int SIDEBAR_ITEM_H = 22;
    private static final int SIDEBAR_ITEM_GAP = 2;
    private static final int SIDEBAR_TOP_PAD = 4;
    /** 内容区与分类栏之间的间距 */
    private static final int CONTENT_PAD = 14;
    /** 标签距内容面板左边的内边距 */
    private static final int LABEL_PAD = 10;
    /** 控件距内容面板右边的内边距 */
    private static final int CONTROL_PAD = 8;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 4;
    /** 生成半径输入上限：防止填出离谱值（实际生效值还会被服务器模拟距离再压一次） */
    /** 已迁移至 {@code SettingsRegistry.MAX_RADIUS}（滑块上限）；保留常量仅作历史参考 */
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

    // ==================== 历史控件引用（P1~P3 重构后已无代码读取） ====================
    // 说明：这些字段是重构前「把控件存起来方便后续读取」的产物。现在控件由 SettingsRegistry
    // 声明式创建、状态统一走 ZombietideConfig / ClientHudConfig，因此它们全部成了死字段。
    // 按项目约定「死代码保留并标注」——**保留不删**，仅作历史参考；新代码请勿再依赖。
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
        this.rowBounds.clear();
        this.searchCategoryOf.clear();
        ensureSearchBox();
        this.clearWidgets();
        this.doneBtn = null;
        this.resetPageBtn = null;

        // ===== 内容控件（进入 renderable 列表，随 pose 平移） =====
        int y = CONTENT_TOP + 8;

        // 【P1 重构】选项定义全部收敛到 SettingsRegistry，这里只负责把「行」翻译成控件。
        // 顺序、分区标题、注释由 registry 保证与旧实现一致（纯重构，外观不变）。
        for (SettingRow row : currentRows()) {
            switch (row) {
                case SettingRow.SectionRow sec -> y = addSection(sec.titleKey(), y);
                case SettingRow.NoteRow note -> y = addNote(note.textKey(), y);
                case SettingRow.OptionRow optRow -> {
                    OptionEntry e = optRow.entry();
                    switch (e.type()) {
                        case TOGGLE -> addToggle(e, e.reader().applyAsInt(this) != 0, y);
                        case INT -> addIntSlider(e, e.reader().applyAsInt(this), y);
                        case CYCLE -> addCycleButton(e, () -> e.cycleLabel().apply(this), y,
                                () -> e.cycleAction().accept(this));
                    }
                    y += ROW_HEIGHT;
                }
            }
        }

        // ===== 固定底部按钮（手动渲染/命中）：重置本页 + 完成 =====
        int btnY = this.height - 28;
        this.resetPageBtn = new FlatButton(this.width / 2 - 123, btnY, 120, 20,
                Component.literal(tr("zombietide.settings.button.reset_page")),
                false, this::resetCurrentPage);
        this.doneBtn = new FlatButton(this.width / 2 + 3, btnY, 120, 20,
                Component.literal(tr("zombietide.settings.button.done")),
                true, this::onClose);

        // 【P4】搜索无结果时给一行提示
        if (isSearching() && this.rowBounds.isEmpty()) {
            this.tabLabels.add(new LabelData("§7" + tr("zombietide.settings.search.none"),
                    contentLeft() + LABEL_PAD, CONTENT_TOP + 20));
        }

        // 内容高度：以最后一行底边为准 + 底部留白（P2 行高变小，公式同步简化）
        this.contentHeight = Math.max(ROW_HEIGHT, (y - CONTENT_TOP) + 16);
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

        // 【P4】搜索框（固定顶栏右侧）
        if (this.searchBox != null) {
            this.searchBox.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        int scissorTop = CONTENT_TOP;
        int scissorBottom = this.height - CONTENT_BOTTOM;

        // 【P2】半透明深色面板：紧凑行叠在壁纸上仍然可读（壁纸本身不删不改）
        guiGraphics.fill(SIDEBAR_X - 4, scissorTop, SIDEBAR_X + SIDEBAR_WIDTH + 4,
                scissorBottom, 0x8C101014);
        guiGraphics.fill(contentLeft() - 8, scissorTop, this.width - 8,
                scissorBottom, 0x8C101014);

        // ===== 左侧分类栏：Sodium 风格扁平项（自绘，无原版按钮纹理） =====
        // 对齐《设置页重构方案》3.1：分类名左对齐 + 选项数量右对齐灰字；
        // 选中 = 淡橙底 + 左侧 3px 实橙条；未选中悬停 = 淡白底。
        SettingsCategory[] cats = SettingsCategory.values();
        for (int i = 0; i < cats.length; i++) {
            int ix = SIDEBAR_X;
            int iy = sidebarItemY(i);
            int iw = SIDEBAR_WIDTH;
            int ih = SIDEBAR_ITEM_H;
            boolean selected = i == this.currentTab.ordinal();
            boolean hovered = mouseX >= ix && mouseX < ix + iw
                    && mouseY >= iy && mouseY < iy + ih;

            if (selected) {
                guiGraphics.fill(ix, iy, ix + iw, iy + ih, 0x4DFFB347);
                guiGraphics.fill(ix, iy, ix + 3, iy + ih, 0xFFFFB347);
            } else if (hovered) {
                guiGraphics.fill(ix, iy, ix + iw, iy + ih, 0x26FFFFFF);
            }

            guiGraphics.drawString(this.font, (selected ? "§f§l" : "§7") + tr(cats[i].key()),
                    ix + 10, iy + 7, 0xFFFFFF, false);
            String cnt = String.valueOf(optionCountOf(cats[i]));
            guiGraphics.drawString(this.font, "§8" + cnt,
                    ix + iw - 9 - this.font.width(cnt), iy + 7, 0xFFFFFF, false);
        }

        if (scissorBottom > scissorTop) {
            guiGraphics.enableScissor(contentLeft() - 8, scissorTop, this.width - 8, scissorBottom);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, -this.scrollOffset, 0.0F);

            // 【P2】悬停高亮：整行淡色底，强化「这一行可点」
            for (RowBounds rb : this.rowBounds) {
                double cy = mouseY + this.scrollOffset;
                if (cy >= rb.y() && cy < rb.y() + rb.height()) {
                    guiGraphics.fill(contentLeft() - 6, rb.y() - 1,
                            this.width - 16, rb.y() + rb.height() - 1, 0x1FFFFFFF);
                    break;
                }
            }

            // 【P3】「已改动」标记：值 ≠ 默认值时在行尾点一个橙点（右键可单项重置）
            for (RowBounds rb : this.rowBounds) {
                OptionEntry e = rb.entry();
                // CYCLE（血债模式）的 reader 恒为 0，改为直接比较配置值
                boolean modified = e.isCycle()
                        ? !"cumulative".equals(this.config.debtMode())
                        : e.reader().applyAsInt(this) != e.defaultValue();
                if (modified) {
                    int dotY = rb.y() + rb.height() / 2;
                    guiGraphics.fill(this.width - 14, dotY - 2, this.width - 10, dotY + 2, 0xFFFFB347);
                }
            }

            // 分区标题（左对齐 + 右侧延伸分隔线），随内容滚动
            for (SectionHeader s : this.sectionHeaders) {
                int screenY = s.y() - this.scrollOffset;
                if (screenY + 12 >= scissorTop && screenY <= scissorBottom) {
                    int textW = this.font.width("§6§l" + s.title());
                    guiGraphics.fill(s.x() + textW + 6, s.y() + 9,
                            this.width - 20, s.y() + 10, 0x33FFFFFF);
                    guiGraphics.drawString(this.font, Component.literal("§6§l" + s.title()),
                            s.x(), s.y(), 0xFFFFFF, false);
                }
            }

            // 仅渲染内容控件，不能调用 super.render：
            // Screen.render 内部会再次调用 renderBackground，在 scissor+translate 下重复绘制背景。
            for (Renderable renderable : this.renderables) {
                renderable.render(guiGraphics, mouseX, mouseY + this.scrollOffset, partialTick);
            }

            // 设置名（左对齐，只偏移一次）
            for (LabelData label : this.tabLabels) {
                int screenY = label.y() - this.scrollOffset;
                if (screenY + 9 >= scissorTop && screenY <= scissorBottom) {
                    guiGraphics.drawString(this.font, Component.literal(label.text()),
                            label.x(), label.y(), 0xFFFFFF, false);
                }
            }

            guiGraphics.pose().popPose();
            guiGraphics.disableScissor();
        }

        drawScrollBar(guiGraphics);

        if (this.resetPageBtn != null) {
            this.resetPageBtn.render(guiGraphics, mouseX, mouseY, partialTick);
        }
        if (this.doneBtn != null) {
            this.doneBtn.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // 【P2】悬停提示：描述与详细说明都搬到这里。
        // 本屏幕刻意不调用 super.render（会在 scissor+translate 下重复绘制背景），
        // 因此 Screen 自带的 tooltip 渲染不会执行，必须手动绘制。
        if (this.getFocused() == null && isOverContent(mouseY) && mouseX >= contentLeft() - 8) {
            double cy = mouseY + this.scrollOffset;
            for (RowBounds rb : this.rowBounds) {
                if (cy >= rb.y() && cy < rb.y() + rb.height()) {
                    List<Component> lines = new ArrayList<>();
                    lines.add(Component.literal("§f" + tr(rb.entry().descKey())));
                    lines.add(Component.literal("§7" + tr(rb.entry().tipKey())));
                    lines.add(Component.literal("§8" + tr("zombietide.settings.tooltip.reset_hint")));
                    guiGraphics.renderTooltip(this.font, lines,
                            java.util.Optional.empty(), mouseX, mouseY);
                    break;
                }
            }
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
        // 【P4】搜索框优先命中
        if (this.searchBox != null && isInside(this.searchBox, mouseX, mouseY)) {
            this.setFocused(this.searchBox);
            this.searchBox.mouseClicked(mouseX, mouseY, button);
            return true;
        }

        if (button == 0 && isOverScrollBar(mouseX, mouseY)) {
            this.draggingScrollBar = true;
            this.scrollBarStartY = (int) mouseY;
            this.scrollOffsetStart = this.scrollOffset;
            return true;
        }

        if (button == 0) {
            // 侧栏分类（自绘，按矩形命中）
            SettingsCategory[] clickCats = SettingsCategory.values();
            for (int i = 0; i < clickCats.length; i++) {
                int iy = sidebarItemY(i);
                if (mouseX >= SIDEBAR_X && mouseX < SIDEBAR_X + SIDEBAR_WIDTH
                        && mouseY >= iy && mouseY < iy + SIDEBAR_ITEM_H) {
                    this.setFocused(null);
                    this.currentTab = clickCats[i];
                    this.scrollOffset = 0;
                    clearSearch();
                    this.rebuildWidgets();
                    return true;
                }
            }
            if (this.resetPageBtn != null && isInside(this.resetPageBtn, mouseX, mouseY)) {
                this.setFocused(null);
                this.resetPageBtn.mouseClicked(mouseX, mouseY, button);
                return true;
            }
            if (this.doneBtn != null && isInside(this.doneBtn, mouseX, mouseY)) {
                this.setFocused(null);
                this.doneBtn.mouseClicked(mouseX, mouseY, button);
                return true;
            }
        }

        // 【P3】右键点某一行 = 该选项恢复默认值
        if (button == 1 && isOverContent(mouseY) && mouseX >= contentLeft() - 8) {
            double cy = mouseY + this.scrollOffset;
            for (RowBounds rb : this.rowBounds) {
                if (cy >= rb.y() && cy < rb.y() + rb.height()) {
                    this.setFocused(null);
                    resetOption(rb.entry());
                    return true;
                }
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
    public boolean charTyped(char codePoint, int modifiers) {
        // 【P4】搜索框聚焦时接收字符
        if (this.searchBox != null && this.searchBox.isFocused()
                && this.searchBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 【P4】搜索框聚焦时优先接收按键：ESC 清空搜索（不关界面），回车收起焦点
        if (this.searchBox != null && this.searchBox.isFocused()) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                clearSearch();
                this.setFocused(null);
                this.rebuildWidgets();
                return true;
            }
            if (keyCode == InputConstants.KEY_RETURN || keyCode == InputConstants.KEY_NUMPADENTER) {
                this.setFocused(null);
                return true;
            }
            if (this.searchBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        }

        if (keyCode == InputConstants.KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== 搜索（P4） ====================

    private boolean isSearching() {
        return !this.searchQuery.isBlank();
    }

    /**
     * 当前要渲染的行：无搜索时是当前分类；有搜索时**跨全部分类**平铺匹配项。
     * <p>跨分类是 Sodium 的做法——搜「半径」能同时看到玩家半径和村民半径。
     */
    private List<SettingRow> currentRows() {
        if (!isSearching()) {
            return SettingsRegistry.rowsFor(this.currentTab);
        }
        String q = this.searchQuery.trim().toLowerCase(Locale.ROOT);
        List<SettingRow> out = new ArrayList<>();
        for (SettingsCategory cat : SettingsCategory.values()) {
            for (SettingRow row : SettingsRegistry.rowsFor(cat)) {
                if (row instanceof SettingRow.OptionRow optRow) {
                    OptionEntry e = optRow.entry();
                    if (matchesSearch(e, cat, q)) {
                        this.searchCategoryOf.put(e, cat);
                        out.add(row);
                    }
                }
            }
        }
        return out;
    }

    /** 匹配名称 / 描述 / 分类名（不区分大小写） */
    private boolean matchesSearch(OptionEntry e, SettingsCategory cat, String q) {
        return tr(e.nameKey()).toLowerCase(Locale.ROOT).contains(q)
                || tr(e.descKey()).toLowerCase(Locale.ROOT).contains(q)
                || tr(cat.key()).toLowerCase(Locale.ROOT).contains(q);
    }

    /** 搜索框按需创建；已存在时只更新位置（窗口尺寸变化） */
    private void ensureSearchBox() {
        int w = Math.max(110, Math.min(180, this.width / 3));
        if (this.searchBox == null) {
            this.searchBox = new EditBox(this.font, this.width - 8 - w, 4, w, 16,
                    Component.literal(tr("zombietide.settings.search.hint")));
            this.searchBox.setHint(Component.literal("§8" + tr("zombietide.settings.search.hint")));
            this.searchBox.setMaxLength(64);
            this.searchBox.setValue(this.searchQuery);
            this.searchBox.setResponder(text -> {
                if (this.updatingSearch) return;
                if (text.equals(this.searchQuery)) return;
                this.searchQuery = text;
                this.scrollOffset = 0;
                rebuildWidgets();
            });
        } else {
            this.searchBox.setX(this.width - 8 - w);
            this.searchBox.setWidth(w);
        }
    }

    /** 清空搜索（点侧栏分类时调用） */
    private void clearSearch() {
        if (this.searchQuery.isEmpty()) return;
        this.searchQuery = "";
        this.updatingSearch = true;
        if (this.searchBox != null) this.searchBox.setValue("");
        this.updatingSearch = false;
    }

    // ==================== 布局几何（P2） ====================

    /** 内容区左边界（分类栏右侧 + 间距） */
    private int contentLeft() {
        return SIDEBAR_X + SIDEBAR_WIDTH + CONTENT_PAD;
    }

    /** 内容区右边界 */
    private int contentRight() {
        return this.width - 16;
    }

    /** 侧栏第 index 项的顶部 Y（固定布局，不随滚动） */
    private int sidebarItemY(int index) {
        return CONTENT_TOP + SIDEBAR_TOP_PAD + index * (SIDEBAR_ITEM_H + SIDEBAR_ITEM_GAP);
    }

    /** 某分类的选项数量（侧栏右侧显示，方案 3.1 布局图要求） */
    private int optionCountOf(SettingsCategory cat) {
        int n = 0;
        for (SettingRow row : SettingsRegistry.rowsFor(cat)) {
            if (row instanceof SettingRow.OptionRow) {
                n++;
            }
        }
        return n;
    }

    /** 控件宽度：窗口很窄时收缩，保证标签至少留 140px */
    private int controlWidth() {
        return Math.min(FIELD_WIDTH, Math.max(70, contentRight() - contentLeft() - 140));
    }

    /** 控件左边界：右对齐 */
    private int controlX() {
        return contentRight() - controlWidth() - CONTROL_PAD;
    }

    /** 【P4】搜索模式下的标签：前面加「分类 · 」，避免「间隔天数」这类同名项分不清 */
    private String labelTextFor(OptionEntry e) {
        String base = labelText(e.scope(), e.nameKey());
        if (this.searchQuery.isBlank()) return base;
        SettingsCategory cat = this.searchCategoryOf.get(e);
        return cat == null ? base : "§8" + tr(cat.key()) + " §7· §r" + base;
    }

    /** 拼装「作用域徽标 + 名称」 */
    private String labelText(OptionScope scope, String label) {
        return (scope == null ? "" : "§l" + scope.color() + "[" + tr(scope.key()) + "] §r§f") + tr(label);
    }

    /**
     * 合并描述与详细提示为两行文本。
     * <p><b>当前未被调用</b>：P3 起 tooltip 直接从 {@code OptionEntry.descKey()/tipKey()} 取键
     * 并追加「右键重置」提示，由 render 内的 renderTooltip 直接拼装。
     * 按项目约定「死代码保留并标注」保留，未删除。
     */
    private String tooltipText(String desc, String tip) {
        return "§f" + tr(desc) + "\n§7" + tr(tip);
    }

    // ==================== 控件工厂 ====================

    private int addSection(String title, int y) {
        this.sectionHeaders.add(new SectionHeader(tr(title), contentLeft() + LABEL_PAD, y));
        return y + SECTION_HEIGHT;
    }

    private int addNote(String text, int y) {
        this.tabLabels.add(new LabelData("§8" + tr(text), contentLeft() + LABEL_PAD, y + 4));
        return y + 18;
    }

    /** 【v0.2.133】开关改为 Sodium 风格扁平胶囊（原版 Checkbox 带方块勾选纹理，与自绘界面冲突） */
    private FlatToggle addToggle(OptionEntry e, boolean checked, int y) {
        this.tabLabels.add(new LabelData(labelTextFor(e),
                contentLeft() + LABEL_PAD, y + (ROW_HEIGHT - 8) / 2));
        this.rowBounds.add(new RowBounds(y, ROW_HEIGHT, e));
        FlatToggle toggle = new FlatToggle(
                controlX(), y + (ROW_HEIGHT - FIELD_HEIGHT) / 2, controlWidth(), FIELD_HEIGHT,
                checked,
                Component.literal(tr("zombietide.settings.value.on")),
                Component.literal(tr("zombietide.settings.value.off")),
                v -> applyOption(e, v ? 1 : 0));
        this.addRenderableWidget(toggle);
        return toggle;
    }

    /** 【P3】数字选项改用滑块：拖动调值 / 点击轨道跳转 / Shift 精调 */
    private IntSliderControl addIntSlider(OptionEntry e, int value, int y) {
        this.tabLabels.add(new LabelData(labelTextFor(e),
                contentLeft() + LABEL_PAD, y + (ROW_HEIGHT - 8) / 2));
        this.rowBounds.add(new RowBounds(y, ROW_HEIGHT, e));
        IntSliderControl slider = new IntSliderControl(
                controlX(), y + (ROW_HEIGHT - FIELD_HEIGHT) / 2, controlWidth(), FIELD_HEIGHT,
                e.min(), e.max(), value,
                // 拖动中只改内存状态，不发包
                v -> e.writer().accept(this, v),
                // 松手/跳转后提交一次
                () -> commitOption(e));
        this.addRenderableWidget(slider);
        return slider;
    }

    private Button addCycleButton(OptionEntry e, java.util.function.Supplier<String> valueText,
                                  int y, Runnable onPress) {
        this.tabLabels.add(new LabelData(labelTextFor(e),
                contentLeft() + LABEL_PAD, y + (ROW_HEIGHT - 8) / 2));
        this.rowBounds.add(new RowBounds(y, ROW_HEIGHT, e));
        Button btn = Button.builder(Component.literal(valueText.get()), b -> {
            onPress.run();
            pushConfig();
            // 直接刷新按钮文字，避免重建整页（重建会打断当前点击事件）
            b.setMessage(Component.literal(valueText.get()));
        }).bounds(controlX(), y + (ROW_HEIGHT - FIELD_HEIGHT) / 2,
                controlWidth(), FIELD_HEIGHT).build();
        this.addRenderableWidget(btn);
        return btn;
    }

    /** 写值 + 立即提交（开关/循环按钮用；滑块拖动走 commitOption 单独提交） */
    private void applyOption(OptionEntry entry, int value) {
        entry.writer().accept(this, value);
        commitOption(entry);
    }

    /** 按选项属于本机配置还是存档级配置选择提交路径 */
    private void commitOption(OptionEntry entry) {
        if (entry.clientOnly()) {
            commitClientHud();
        } else {
            pushConfig();
        }
    }

    /** 【P3】单项恢复默认值 */
    private void resetOption(OptionEntry entry) {
        entry.writer().accept(this, entry.defaultValue());
        commitOption(entry);
        this.rebuildWidgets();
    }

    /** 【P3】重置当前分类全部选项为默认值，只提交一次 */
    private void resetCurrentPage() {
        boolean touchedWorld = false;
        boolean touchedClient = false;
        for (SettingRow row : SettingsRegistry.rowsFor(this.currentTab)) {
            if (row instanceof SettingRow.OptionRow optRow) {
                OptionEntry e = optRow.entry();
                e.writer().accept(this, e.defaultValue());
                if (e.clientOnly()) {
                    touchedClient = true;
                } else {
                    touchedWorld = true;
                }
            }
        }
        if (touchedClient) commitClientHud();
        if (touchedWorld) pushConfig();
        this.rebuildWidgets();
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

    /** 【P3】一行的纵向范围 + 对应选项（tooltip、重置、改动标记都用它） */
    private record RowBounds(int y, int height, OptionEntry entry) {}

    private record SectionHeader(String title, int x, int y) {}
}
