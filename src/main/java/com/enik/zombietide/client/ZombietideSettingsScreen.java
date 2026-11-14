package com.enik.zombietide.client;

import com.enik.zombietide.config.ClientHudConfig;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.network.ModMessages;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 尸潮配置界面。
 * <p>
 * 信息架构：
 * <ul>
 *   <li>按作用对象分为 6 个分页：玩家尸潮 / 村民尸潮 / 生成性能 / 世界规则 / 警告提示 / HUD 本机。</li>
 *   <li>每个分页内部再按功能分区（总开关、触发节奏、规模、范围……），带分隔线与分区标题。</li>
 *   <li>每个设置项统一为「作用域标签 + 名称」「灰色说明」「控件」三行结构，并带悬停详细提示。</li>
 *   <li>HUD 分页为<b>本机全局</b>配置（{@link ClientHudConfig}），不随存档保存；其余分页写入存档级配置。</li>
 * </ul>
 * 滚动实现要点（修复历史遗留的滑动/渲染/重叠/多绘问题）：
 * <ul>
 *   <li><b>固定区域不参与滚动</b>：标题、标签页、完成按钮不加入 renderable 列表，改为手动渲染与命中。</li>
 *   <li><b>scissor 用固定屏幕坐标</b>：不叠加 scrollOffset，避免裁剪区随滚动漂移。</li>
 *   <li><b>标签只偏移一次</b>：在已平移的 pose 内按原始 y 绘制。</li>
 *   <li><b>背景只画一次</b>：render() 已调用 renderBackground，滚动内容必须手动遍历 renderables 渲染，
 *       不能调用 super.render —— Screen.render 内部会再次绘制背景，在 scissor+translate 下被裁剪并随滚动位移。</li>
 * </ul>
 */
public class ZombietideSettingsScreen extends Screen {

    private final Screen parent;
    private ZombietideConfig config;
    private ZombietideConfig.HudConfig hudConfig;
    private boolean worldLoaded = false;

    /** 分页：按作用对象/主题划分 */
    private enum Tab {
        PLAYER("玩家尸潮"),
        VILLAGER("村民尸潮"),
        SPAWN("生成性能"),
        WORLD("世界规则"),
        WARNING("警告提示"),
        HUD("HUD 本机");

        final String label;
        Tab(String label) { this.label = label; }
    }

    /** 作用域：告诉玩家这项设置具体改谁 */
    private enum Scope {
        PLAYER("玩家", "§9"),
        VILLAGER("村民", "§a"),
        WORLD("世界", "§6"),
        CLIENT("本机", "§d");

        final String label;
        final String color;
        Scope(String label, String color) { this.label = label; this.color = color; }
    }

    private Tab currentTab = Tab.PLAYER;
    /** 固定顶部标签页（不加入 renderable 列表，手动渲染/命中） */
    private final List<Button> tabButtons = new ArrayList<>();
    private final List<Checkbox> checkboxes = new ArrayList<>();
    private final List<EditBox> editBoxes = new ArrayList<>();
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
    private EditBox maxSpawnDistEdit;
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

    public ZombietideSettingsScreen(Screen parent, ZombietideConfig config) {
        super(Component.literal("尸潮配置"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        super.init();
        this.worldLoaded = this.minecraft != null && this.minecraft.level != null && this.minecraft.player != null;
        this.hudConfig = ClientHudConfig.get();
        this.scrollOffset = 0;
        this.rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        if (this.hudConfig == null) this.hudConfig = ClientHudConfig.get();
        this.checkboxes.clear();
        this.editBoxes.clear();
        this.tabLabels.clear();
        this.sectionHeaders.clear();
        this.tabButtons.clear();
        this.clearWidgets();
        this.doneBtn = null;

        // ===== 固定顶部标签页（手动渲染/命中，不进入滚动容器） =====
        int tabCount = Tab.values().length;
        int tabWidth = Math.max(40, Math.min(96, (this.width - 16) / tabCount));
        int tabStartX = (this.width - tabWidth * tabCount) / 2;
        for (int i = 0; i < tabCount; i++) {
            Tab tab = Tab.values()[i];
            Button btn = Button.builder(Component.literal(tab.label), b -> {
                this.currentTab = tab;
                this.scrollOffset = 0;
                this.rebuildWidgets();
            }).bounds(tabStartX + i * tabWidth, TAB_TOP, tabWidth, TAB_HEIGHT).build();
            this.tabButtons.add(btn);
        }

        // ===== 内容控件（进入 renderable 列表，随 pose 平移） =====
        int centerX = this.width / 2;
        int y = CONTENT_TOP + 8;

        switch (currentTab) {
            case PLAYER -> {
                y = addNote("标签：[玩家] 玩家尸潮 · [村民] 村民尸潮", centerX, y);
                y = addNote("　　　[世界] 本存档全局 · [本机] 仅本机", centerX, y);
                y = addSection("总开关", centerX, y);
                addCheckbox("启用玩家尸潮", Scope.PLAYER,
                        "关闭后玩家不再触发尸潮",
                        "总开关。关闭后玩家侧计时继续，但不会触发尸潮。",
                        config.playerHordeEnabled(), centerX, y,
                        (cb, v) -> config = config.withPlayerHordeEnabled(v), false);
                y += ROW_HEIGHT;

                y = addSection("触发节奏", centerX, y);
                addIntField("间隔天数", Scope.PLAYER,
                        "每 N 天触发一次；1 = 每天",
                        "玩家尸潮的触发周期，按主世界游戏日计算；所有玩家共享同一个模组天数。",
                        config.playerIntervalDays(), centerX, y, 1,
                        v -> config = config.withPlayerIntervalDays(v), false);
                y += ROW_HEIGHT;
                addCheckbox("随机间隔", Scope.PLAYER,
                        "开启后忽略固定间隔，改用范围",
                        "开启后会在「间隔最小~最大天数」之间随机下一次触发间隔。",
                        config.playerIntervalRandom(), centerX, y,
                        (cb, v) -> config = config.withPlayerIntervalRandom(v), false);
                y += ROW_HEIGHT;
                addIntField("间隔最小天数", Scope.PLAYER,
                        "随机间隔的取值下限",
                        "仅在「随机间隔」开启时生效。",
                        config.playerIntervalMin(), centerX, y, 1,
                        v -> config = config.withPlayerIntervalMin(v), false);
                y += ROW_HEIGHT;
                addIntField("间隔最大天数", Scope.PLAYER,
                        "随机间隔的取值上限",
                        "仅在「随机间隔」开启时生效。",
                        config.playerIntervalMax(), centerX, y, 1,
                        v -> config = config.withPlayerIntervalMax(v), false);
                y += ROW_HEIGHT;

                y = addSection("尸潮规模", centerX, y);
                addIntField("每次生成数量", Scope.PLAYER,
                        "本波基础僵尸数",
                        "未开启随机数量时使用该值；血债会在此基础上追加。",
                        config.playerCount(), centerX, y, 1,
                        v -> config = config.withPlayerCount(v), false);
                y += ROW_HEIGHT;
                addCheckbox("随机数量", Scope.PLAYER,
                        "开启后忽略固定数量，改用范围",
                        "开启后每次尸潮数量会在「数量最小~最大值」之间随机。",
                        config.playerCountRandom(), centerX, y,
                        (cb, v) -> config = config.withPlayerCountRandom(v), false);
                y += ROW_HEIGHT;
                addIntField("数量最小值", Scope.PLAYER,
                        "随机数量的取值下限",
                        "仅在「随机数量」开启时生效。",
                        config.playerCountMin(), centerX, y, 1,
                        v -> config = config.withPlayerCountMin(v), false);
                y += ROW_HEIGHT;
                addIntField("数量最大值", Scope.PLAYER,
                        "随机数量的取值上限",
                        "仅在「随机数量」开启时生效。",
                        config.playerCountMax(), centerX, y, 1,
                        v -> config = config.withPlayerCountMax(v), false);
                y += ROW_HEIGHT;
            }
            case VILLAGER -> {
                y = addSection("总开关", centerX, y);
                addCheckbox("启用村民尸潮", Scope.VILLAGER,
                        "关闭后村民不再触发尸潮",
                        "总开关。关闭后村民侧计时继续，但不会触发尸潮。",
                        config.villagerHordeEnabled(), centerX, y,
                        (cb, v) -> config = config.withVillagerHordeEnabled(v), false);
                y += ROW_HEIGHT;

                y = addSection("触发节奏", centerX, y);
                addIntField("间隔天数", Scope.VILLAGER,
                        "每 N 天触发一次；1 = 每天",
                        "村民尸潮的触发周期，与主世界游戏日同步；所有村民共享同一个模组天数。",
                        config.villagerIntervalDays(), centerX, y, 1,
                        v -> config = config.withVillagerIntervalDays(v), false);
                y += ROW_HEIGHT;
                addCheckbox("随机间隔", Scope.VILLAGER,
                        "开启后忽略固定间隔，改用范围",
                        "开启后会在「间隔最小~最大天数」之间随机下一次触发间隔。",
                        config.villagerIntervalRandom(), centerX, y,
                        (cb, v) -> config = config.withVillagerIntervalRandom(v), false);
                y += ROW_HEIGHT;
                addIntField("间隔最小天数", Scope.VILLAGER,
                        "随机间隔的取值下限",
                        "仅在「随机间隔」开启时生效。",
                        config.villagerIntervalMin(), centerX, y, 1,
                        v -> config = config.withVillagerIntervalMin(v), false);
                y += ROW_HEIGHT;
                addIntField("间隔最大天数", Scope.VILLAGER,
                        "随机间隔的取值上限",
                        "仅在「随机间隔」开启时生效。",
                        config.villagerIntervalMax(), centerX, y, 1,
                        v -> config = config.withVillagerIntervalMax(v), false);
                y += ROW_HEIGHT;

                y = addSection("尸潮规模", centerX, y);
                addIntField("每次生成数量", Scope.VILLAGER,
                        "本波基础僵尸数",
                        "未开启随机数量时使用该值；村民尸潮不结血债。",
                        config.villagerCount(), centerX, y, 1,
                        v -> config = config.withVillagerCount(v), false);
                y += ROW_HEIGHT;
                addCheckbox("随机数量", Scope.VILLAGER,
                        "开启后忽略固定数量，改用范围",
                        "开启后每次尸潮数量会在「数量最小~最大值」之间随机。",
                        config.villagerCountRandom(), centerX, y,
                        (cb, v) -> config = config.withVillagerCountRandom(v), false);
                y += ROW_HEIGHT;
                addIntField("数量最小值", Scope.VILLAGER,
                        "随机数量的取值下限",
                        "仅在「随机数量」开启时生效。",
                        config.villagerCountMin(), centerX, y, 1,
                        v -> config = config.withVillagerCountMin(v), false);
                y += ROW_HEIGHT;
                addIntField("数量最大值", Scope.VILLAGER,
                        "随机数量的取值上限",
                        "仅在「随机数量」开启时生效。",
                        config.villagerCountMax(), centerX, y, 1,
                        v -> config = config.withVillagerCountMax(v), false);
                y += ROW_HEIGHT;
            }
            case SPAWN -> {
                y = addSection("分批节奏", centerX, y);
                addIntField("批次 tick 间隔", Scope.WORLD,
                        "每批之间等待的 tick（20=1秒）",
                        "越小刷得越快、压力越大；越大刷得越平缓。",
                        config.batchTick(), centerX, y, 1,
                        v -> config = config.withBatchTick(v), false);
                y += ROW_HEIGHT;
                addIntField("玩家批量大小", Scope.WORLD,
                        "玩家尸潮每批最多生成数",
                        "每过一个批次间隔生成一批，受全局僵尸上限限制。",
                        config.batchSize(), centerX, y, 1,
                        v -> config = config.withBatchSize(v), false);
                y += ROW_HEIGHT;
                addIntField("村民批量大小", Scope.WORLD,
                        "村民尸潮每批最多生成数",
                        "每过一个批次间隔生成一批，受全局僵尸上限限制。",
                        config.villagerBatchSize(), centerX, y, 1,
                        v -> config = config.withVillagerBatchSize(v), false);
                y += ROW_HEIGHT;

                y = addSection("生成范围", centerX, y);
                addIntField("玩家生成半径", Scope.WORLD,
                        "以玩家为中心的最大生成距离",
                        "尸潮僵尸只会在该半径内寻找刷点。",
                        config.playerRadius(), centerX, y, 1,
                        v -> config = config.withPlayerRadius(v), false);
                y += ROW_HEIGHT;
                addIntField("村民生成半径", Scope.WORLD,
                        "以村民为中心的最大生成距离",
                        "尸潮僵尸只会在该半径内寻找刷点。",
                        config.villagerRadius(), centerX, y, 1,
                        v -> config = config.withVillagerRadius(v), false);
                y += ROW_HEIGHT;
                addIntField("最小生成距离", Scope.WORLD,
                        "单只僵尸距目标的最近距离",
                        "避免僵尸直接刷在玩家/村民脸上。",
                        config.minSpawnDist(), centerX, y, 1,
                        v -> config = config.withMinSpawnDist(v), false);
                y += ROW_HEIGHT;
                addIntField("最大生成距离", Scope.WORLD,
                        "单只僵尸距目标的最远距离",
                        "超过该距离的刷点不会被选中。",
                        config.maxSpawnDist(), centerX, y, 1,
                        v -> config = config.withMaxSpawnDist(v), false);
                y += ROW_HEIGHT;
                addIntField("采样次数", Scope.WORLD,
                        "刷点采样次数，越大越易找到位置",
                        "每次刷怪的采样次数；越大越容易找到合适位置，但开销更高。",
                        config.spawnAttempts(), centerX, y, 1,
                        v -> config = config.withSpawnAttempts(v), false);
                y += ROW_HEIGHT;

                y = addSection("变种与权重", centerX, y);
                addCheckbox("按生物群系生成", Scope.WORLD,
                        "沙漠→尸壳、沼泽→溺尸等",
                        "关闭后统一生成普通僵尸；开启后按目标所在生物群系选择变种。",
                        config.spawnByBiome(), centerX, y,
                        (cb, v) -> config = config.withSpawnByBiome(v), false);
                y += ROW_HEIGHT;
                addIntField("距离权重（×100）", Scope.WORLD,
                        "刷点评分中距离的权重",
                        "值越大越偏向远处刷怪；按 ×100 输入，如 60 = 0.6。",
                        Math.round(config.distanceWeight() * 100), centerX, y, 0,
                        v -> config = config.withDistanceWeight(v / 100.0f), false);
                y += ROW_HEIGHT;
                addIntField("暗度权重（×100）", Scope.WORLD,
                        "刷点评分中阴影的权重",
                        "值越大越偏向暗处刷怪；按 ×100 输入，如 60 = 0.6。",
                        Math.round(config.darkWeight() * 100), centerX, y, 0,
                        v -> config = config.withDarkWeight(v / 100.0f), false);
                y += ROW_HEIGHT;

                y = addSection("性能上限", centerX, y);
                addIntField("全局僵尸上限", Scope.WORLD,
                        "全场存活尸潮僵尸上限",
                        "达到上限后刷怪会挂起，直到有僵尸死亡释放名额。",
                        config.globalCap(), centerX, y, 1,
                        v -> config = config.withGlobalCap(v), false);
                y += ROW_HEIGHT;
            }
            case WORLD -> {
                y = addSection("生存规则", centerX, y);
                addCheckbox("阳光下燃烧", Scope.WORLD,
                        "白天阳光是否点燃僵尸",
                        "关闭后尸潮僵尸白天也不会被阳光点燃。",
                        config.daylightBurn(), centerX, y,
                        (cb, v) -> config = config.withDaylightBurn(v), false);
                y += ROW_HEIGHT;
                addCheckbox("尸潮期间禁止睡觉", Scope.WORLD,
                        "尸潮进行中玩家无法入睡",
                        "玩家尸潮进行中，或附近村民尸潮进行中时，禁止玩家入睡。",
                        config.blockSleep(), centerX, y,
                        (cb, v) -> config = config.withBlockSleep(v), false);
                y += ROW_HEIGHT;

                y = addSection("血债", centerX, y);
                addCycleButton("血债模式", Scope.PLAYER,
                        "上波幸存僵尸如何结转到下波",
                        "累积制：每次把幸存数累加到血债；只记上波：只保留最近一波；不结转：血债始终为 0。",
                        () -> "血债模式：" + debtModeLabel(config.debtMode()), centerX, y,
                        () -> config = config.withDebtMode(nextDebtMode(config.debtMode())));
                y += ROW_HEIGHT;
                addIntField("血债上限", Scope.PLAYER,
                        "血债最多累计多少只",
                        "达到上限后不再增加；血债会在下波尸潮时追加到生成数量。",
                        config.debtCap(), centerX, y, 0,
                        v -> config = config.withDebtCap(v), false);
                y += ROW_HEIGHT;

                y = addSection("清理", centerX, y);
                addIntField("僵尸自然消失秒数", Scope.WORLD,
                        "日出前 N 秒开始四散",
                        "23460 起火时刻统一清除；该值决定提前多久开始逃跑。",
                        config.despawnSeconds(), centerX, y, 1,
                        v -> config = config.withDespawnSeconds(v), false);
                y += ROW_HEIGHT;
            }
            case WARNING -> {
                y = addSection("警告开关", centerX, y);
                addCheckbox("启用警告", Scope.WORLD,
                        "总开关，关闭后无任何提示",
                        "关闭后清晨提醒、触发标题和音效都不再出现。",
                        config.warningEnabled(), centerX, y,
                        (cb, v) -> config = config.withWarningEnabled(v), false);
                y += ROW_HEIGHT;
                addCheckbox("标题警告", Scope.WORLD,
                        "触发时显示「尸潮」大标题",
                        "仅「启用警告」开启时生效。",
                        config.warningTitle(), centerX, y,
                        (cb, v) -> config = config.withWarningTitle(v), false);
                y += ROW_HEIGHT;
                addCheckbox("尸潮触发音效", Scope.WORLD,
                        "触发时播放定制音效",
                        "对应「尸潮触发.mp3」，已替换原版僵尸敲木门声；仅「启用警告」开启时生效，村民尸潮附近玩家也能听到。",
                        config.warningSound(), centerX, y,
                        (cb, v) -> config = config.withWarningSound(v), false);
                y += ROW_HEIGHT;

                y = addSection("定制音频", centerX, y);
                addCheckbox("清晨提醒音效", Scope.WORLD,
                        "尸潮日清晨提醒时播放",
                        "对应「清晨提醒.mp3」；只发给收到提醒的玩家本人，受「启用警告」总开关控制。",
                        config.morningWarningSound(), centerX, y,
                        (cb, v) -> config = config.withMorningWarningSound(v), false);
                y += ROW_HEIGHT;
                addCheckbox("四散音效", Scope.WORLD,
                        "日出前四散时播放",
                        "对应「四散触发.mp3」；只发给玩家尸潮触发者，村民尸潮不播放。",
                        config.scatterSound(), centerX, y,
                        (cb, v) -> config = config.withScatterSound(v), false);
                y += ROW_HEIGHT;
                addCheckbox("尸潮背景音乐", Scope.WORLD,
                        "尸潮期间随机间隔播放背景音乐",
                        "每 30 秒~2 分钟随机播放一段（5 段素材随机选取）；只有玩家尸潮触发者能听到，村民尸潮不播放；波次结束自动停止。",
                        config.hordeBgmSound(), centerX, y,
                        (cb, v) -> config = config.withHordeBgmSound(v), false);
                y += ROW_HEIGHT;

                y = addSection("范围", centerX, y);
                addIntField("村民警告范围", Scope.VILLAGER,
                        "村民尸潮警报的广播半径",
                        "该范围内的玩家会收到标题、音效和聊天提示。",
                        config.villagerWarningRange(), centerX, y, 1,
                        v -> config = config.withVillagerWarningRange(v), false);
                y += ROW_HEIGHT;
                addIntField("村民禁睡范围", Scope.VILLAGER,
                        "村民尸潮阻止入睡的半径",
                        "该范围内的玩家在村民尸潮期间无法入睡。",
                        config.villagerSleepBlockRange(), centerX, y, 1,
                        v -> config = config.withVillagerSleepBlockRange(v), false);
                y += ROW_HEIGHT;

                y = addSection("自然生成", centerX, y);
                addCheckbox("屏蔽自然僵尸生成", Scope.WORLD,
                        "阻止原版自然刷新的僵尸类",
                        "拦截僵尸、尸壳、溺尸、僵尸村民的自然生成；不影响尸潮刷怪。",
                        config.blockNaturalSpawn(), centerX, y,
                        (cb, v) -> config = config.withBlockNaturalSpawn(v), false);
                y += ROW_HEIGHT;
            }
            case HUD -> {
                y = addSection("本页说明", centerX, y);
                y = addNote("本页设置仅保存在本机，所有存档/服务器通用", centerX, y);

                y = addSection("显示", centerX, y);
                addCheckbox("启用 HUD（DAY X）", Scope.CLIENT,
                        "左上角显示当前模组天数",
                        "关闭后不渲染 DAY X 与村民列表。",
                        hudConfig.hudEnabled(), centerX, y,
                        (cb, v) -> hudConfig = hudConfig.withHudEnabled(v), true);
                y += ROW_HEIGHT;
                addCheckbox("显示附近村民尸潮信息", Scope.CLIENT,
                        "在 HUD 下方列出附近村民",
                        "按距离从近到远排序，最多显示设定条数。",
                        hudConfig.showVillagerHorde(), centerX, y,
                        (cb, v) -> hudConfig = hudConfig.withShowVillagerHorde(v), true);
                y += ROW_HEIGHT;
                addCheckbox("HUD 粗体（DAY X）", Scope.CLIENT,
                        "DAY X 使用粗体",
                        "仅影响玩家自己的 DAY X 文字。",
                        hudConfig.hudBold(), centerX, y,
                        (cb, v) -> hudConfig = hudConfig.withHudBold(v), true);
                y += ROW_HEIGHT;
                addCheckbox("村民尸潮粗体", Scope.CLIENT,
                        "村民条目使用粗体",
                        "仅影响 HUD 村民列表的文字。",
                        hudConfig.villagerHordeBold(), centerX, y,
                        (cb, v) -> hudConfig = hudConfig.withVillagerHordeBold(v), true);
                y += ROW_HEIGHT;

                y = addSection("位置与缩放", centerX, y);
                addIntField("HUD X 坐标", Scope.CLIENT,
                        "屏幕横向像素坐标",
                        "从屏幕左上角起算。",
                        hudConfig.hudX(), centerX, y, 0,
                        v -> hudConfig = hudConfig.withHudX(v), true);
                y += ROW_HEIGHT;
                addIntField("HUD Y 坐标", Scope.CLIENT,
                        "屏幕纵向像素坐标",
                        "从屏幕左上角起算。",
                        hudConfig.hudY(), centerX, y, 0,
                        v -> hudConfig = hudConfig.withHudY(v), true);
                y += ROW_HEIGHT;
                addIntField("HUD 缩放（×10）", Scope.CLIENT,
                        "50 = 5 倍，10 = 1 倍",
                        "按 ×10 输入，实际缩放 = 输入值 / 10。",
                        Math.round(hudConfig.hudScale() * 10), centerX, y, 1,
                        v -> hudConfig = hudConfig.withHudScale(v / 10.0f), true);
                y += ROW_HEIGHT;
                addIntField("村民列表 X 坐标", Scope.CLIENT,
                        "村民列表的横向坐标",
                        "从屏幕左上角起算。",
                        hudConfig.villagerHordeX(), centerX, y, 0,
                        v -> hudConfig = hudConfig.withVillagerHordeX(v), true);
                y += ROW_HEIGHT;
                addIntField("村民列表 Y 坐标", Scope.CLIENT,
                        "村民列表的纵向坐标",
                        "从屏幕左上角起算。",
                        hudConfig.villagerHordeY(), centerX, y, 0,
                        v -> hudConfig = hudConfig.withVillagerHordeY(v), true);
                y += ROW_HEIGHT;
                addIntField("村民列表缩放（×10）", Scope.CLIENT,
                        "10 = 1 倍",
                        "按 ×10 输入，实际缩放 = 输入值 / 10。",
                        Math.round(hudConfig.villagerHordeScale() * 10), centerX, y, 1,
                        v -> hudConfig = hudConfig.withVillagerHordeScale(v / 10.0f), true);
                y += ROW_HEIGHT;

                y = addSection("村民列表", centerX, y);
                addIntField("显示距离（格）", Scope.CLIENT,
                        "只显示该距离内的村民",
                        "超过该距离的村民不会出现在 HUD 列表中。",
                        hudConfig.villagerHordeDisplayRange(), centerX, y, 1,
                        v -> hudConfig = hudConfig.withVillagerHordeDisplayRange(v), true);
                y += ROW_HEIGHT;
                addIntField("最大显示数量", Scope.CLIENT,
                        "最多列出多少条",
                        "按距离从近到远排序后截取。",
                        hudConfig.villagerHordeMaxEntries(), centerX, y, 1,
                        v -> hudConfig = hudConfig.withVillagerHordeMaxEntries(v), true);
                y += ROW_HEIGHT;
            }
        }

        // ===== 固定底部完成按钮（手动渲染/命中） =====
        this.doneBtn = Button.builder(Component.literal("完成"), b -> this.onClose())
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

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

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
        this.sectionHeaders.add(new SectionHeader(title, centerX, y));
        return y + SECTION_HEIGHT;
    }

    private int addNote(String text, int centerX, int y) {
        this.tabLabels.add(new LabelData("§7" + text, centerX, y));
        return y + 16;
    }

    private Checkbox addCheckbox(String label, Scope scope, String desc, String tooltip,
                                 boolean checked, int centerX, int y,
                                 Checkbox.OnValueChange onChange, boolean clientOnly) {
        this.tabLabels.add(new LabelData("§l" + scope.color + "[" + scope.label + "] §r§f" + label, centerX, y));
        this.tabLabels.add(new LabelData("§7" + desc, centerX, y + 10));
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
        cb.setTooltip(Tooltip.create(Component.literal(tooltip)));
        this.addRenderableWidget(cb);
        this.checkboxes.add(cb);
        return cb;
    }

    private EditBox addIntField(String label, Scope scope, String desc, String tooltip,
                                int value, int centerX, int y, int minValue,
                                IntConsumer onChange, boolean clientOnly) {
        this.tabLabels.add(new LabelData("§l" + scope.color + "[" + scope.label + "] §r§f" + label, centerX, y));
        this.tabLabels.add(new LabelData("§7" + desc, centerX, y + 10));
        EditBox field = new EditBox(this.font, centerX - 100, y + 22, FIELD_WIDTH, FIELD_HEIGHT, Component.literal(label));
        field.setValue(String.valueOf(value));
        field.setTooltip(Tooltip.create(Component.literal(tooltip)));
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
        this.editBoxes.add(field);
        return field;
    }

    private Button addCycleButton(String label, Scope scope, String desc, String tooltip,
                                  java.util.function.Supplier<String> valueText, int centerX, int y, Runnable onPress) {
        this.tabLabels.add(new LabelData("§l" + scope.color + "[" + scope.label + "] §r§f" + label, centerX, y));
        this.tabLabels.add(new LabelData("§7" + desc, centerX, y + 10));
        Button btn = Button.builder(Component.literal(valueText.get()), b -> {
            onPress.run();
            pushConfig();
            // 直接刷新按钮文字，避免重建整页（重建会打断当前点击事件）
            b.setMessage(Component.literal(valueText.get()));
        }).bounds(centerX - 100, y + 22, FIELD_WIDTH, FIELD_HEIGHT).build();
        btn.setTooltip(Tooltip.create(Component.literal(tooltip)));
        this.addRenderableWidget(btn);
        return btn;
    }

    /** 服务端存档级配置：立即同步并提示 */
    private void pushConfig() {
        if (!this.worldLoaded || this.minecraft == null || this.minecraft.player == null) return;
        // 保持存档里的 HUD 字段与本机全局配置一致（仅兼容旧数据，实际渲染以本机为准）
        this.config = this.config.withHud(ClientHudConfig.get());
        ModMessages.sendConfigToServer(this.config);
        toast("§a[尸潮] 配置已立即生效");
    }

    /** 本机全局 HUD 配置：只写本地文件，不发服务端 */
    private void commitClientHud() {
        ClientHudConfig.set(this.hudConfig);
        this.config = this.config.withHud(this.hudConfig);
        toast("§a[尸潮] HUD 设置已保存（本机，所有存档通用）");
    }

    private void toast(String msg) {
        long now = Util.getMillis();
        if (now - this.lastToastMs < 800L) return;
        this.lastToastMs = now;
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(Component.literal(msg), false);
        }
    }

    private static String debtModeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "last_only" -> "只记上波";
            case "none" -> "不结转";
            default -> "累积制";
        };
    }

    private static String nextDebtMode(String mode) {
        return switch (mode == null ? "" : mode) {
            case "cumulative" -> "last_only";
            case "last_only" -> "none";
            default -> "cumulative";
        };
    }

    private record LabelData(String text, int x, int y) {}

    private record SectionHeader(String title, int x, int y) {}
}
