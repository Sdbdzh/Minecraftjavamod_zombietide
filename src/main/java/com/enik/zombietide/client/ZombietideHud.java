package com.enik.zombietide.client;

import com.enik.zombietide.config.ClientHudConfig;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.network.ModMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.npc.Villager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端 HUD：在屏幕左上角显示当前模组天数（"DAY X"），可选显示附近村民列表。
 * <p>
 * - 单行显示：`DAY X`（如 `DAY 5`），X 为该玩家的模组天数
 * - 尸潮当天：`DAY X` **红色字体**显示（判定由服务端 `HordeTimer.isHordeDay` 算好，随 `PlayerModDayPayload.hordeDay` 同步）
 * - 其余天数：正常颜色；未同步到时显示 `DAY --`
 * - 模组天数来自服务端同步（`PlayerModDayPayload`），客户端不再用世界时间推算
 * - 位置完全由本机 `ClientHudConfig` 的 `hudX` / `hudY` 决定（屏幕绝对坐标，左上为原点）；
 *   `hudPosition` 是历史保留字段，**当前不参与渲染**
 * - 村民列表默认**关闭**（`showVillagerHorde=false`），开启后在 `villagerHordeX/Y` 处按距离列出
 */
public class ZombietideHud {

    /** 客户端缓存的服务端配置（由 ConfigToClientPayload handler 调 {@link #onConfigReceived} 注入） */
    private static ZombietideConfig clientConfig = null;

    /** 服务端同步的玩家模组天数（UUID -> modDay） */
    private static final Map<UUID, Integer> syncedModDays = new HashMap<>();
    /** 服务端同步的玩家尸潮是否进行中（对应 PlayerHordeData.waveActive） */
    private static final Map<UUID, Boolean> syncedWaveActive = new HashMap<>();
    /** 服务端同步的玩家血债（PlayerDebtData.getDebt） */
    private static final Map<UUID, Integer> syncedDebt = new HashMap<>();
    /** 服务端同步的全局统计（所有玩家共用同一份，按 UUID 存便于保持结构一致） */
    private static final Map<UUID, Integer> syncedActivePlayerHordes = new HashMap<>();
    private static final Map<UUID, Integer> syncedActiveVillagerHordes = new HashMap<>();
    private static final Map<UUID, Integer> syncedAliveHordeZombies = new HashMap<>();
    /** 服务端算好的「今天是否为该玩家的尸潮日」（支持固定/随机间隔） */
    private static final Map<UUID, Boolean> syncedHordeDay = new HashMap<>();
    /** 服务端同步的村民模组天数（UUID -> modDay；用于 HUD 附近村民显示） */
    private static final Map<UUID, Integer> villagerModDays = new HashMap<>();
    /** 村民职业名缓存（UUID -> profession，如“村民”、“牧羊人”、“图书管理员”） */
    private static final Map<UUID, String> villagerProfessions = new HashMap<>();

    /** 村民位置缓存（UUID -> x/y/z，用于 HUD 距离过滤） */
    private static final Map<UUID, double[]> villagerPos = new HashMap<>();

    /** 村民尸潮是否进行中（UUID -> waveActive；由服务端同步，用于信息层显示真实状态） */
    private static final Map<UUID, Boolean> villagerWaveActive = new HashMap<>();
    /** 服务端算好的「今天是否为该村民的尸潮日」 */
    private static final Map<UUID, Boolean> villagerHordeDayMap = new HashMap<>();
    /** 【v0.2.91】距下次尸潮预兆期开始的进度（0~1），驱动 DAY X 渐进红 */
    private static final Map<UUID, Float> syncedHordeProgress = new HashMap<>();
    private static final Map<UUID, Float> villagerHordeProgress = new HashMap<>();
    /** 【v0.2.94】今天是否为该目标的尸潮日（5 参，整天有效）。DAY X 二态模式用。 */
    private static final Map<UUID, Boolean> syncedHordeDayToday = new HashMap<>();
    private static final Map<UUID, Boolean> villagerHordeDayToday = new HashMap<>();

    /** 客户端缓存的 mod info 常驻显示开关（由服务端通过 InfoOverlayPayload 同步） */
    private static boolean infoOverlayEnabled = false;

    /** 接收服务端广播的配置（由 {@code ModMessages} 的 {@code ConfigToClientPayload} handler 调用） */
    public static void onConfigReceived(ZombietideConfig config) {
        clientConfig = config;
    }

    /** 获取客户端缓存的配置（用于设置界面初始化） */
    public static ZombietideConfig getClientConfig() {
        return clientConfig != null ? clientConfig : ZombietideConfig.defaults();
    }

    /** 接收服务端同步的玩家模组天数（PlayerModDayPayload） */
    public static void onModDayReceived(UUID playerUuid, int modDay, boolean hordeDay, boolean waveActive, int bloodDebt,
                                        int activePlayerHordes, int activeVillagerHordes, int aliveHordeZombies,
                                        float hordeProgress, boolean hordeDayToday) {
        syncedModDays.put(playerUuid, modDay);
        syncedHordeDay.put(playerUuid, hordeDay);
        syncedHordeProgress.put(playerUuid, hordeProgress);
        syncedHordeDayToday.put(playerUuid, hordeDayToday);
        syncedWaveActive.put(playerUuid, waveActive);
        syncedDebt.put(playerUuid, bloodDebt);
        syncedActivePlayerHordes.put(playerUuid, activePlayerHordes);
        syncedActiveVillagerHordes.put(playerUuid, activeVillagerHordes);
        syncedAliveHordeZombies.put(playerUuid, aliveHordeZombies);
    }

    /** 客户端视角：该玩家今天是不是尸潮日（服务端同步的权威值，不自行推算）。 */
    public static boolean isHordeDayFor(UUID playerUuid) {
        return Boolean.TRUE.equals(syncedHordeDay.get(playerUuid));
    }

    /** 客户端视角：该玩家的尸潮波次是否进行中。 */
    public static boolean isWaveActiveFor(UUID playerUuid) {
        return Boolean.TRUE.equals(syncedWaveActive.get(playerUuid));
    }

    /** 客户端视角：该玩家当前血债值（氛围强度用）。 */
    public static int bloodDebtFor(UUID playerUuid) {
        return syncedDebt.getOrDefault(playerUuid, 0);
    }

    /** 【v0.2.91】该玩家距下次尸潮预兆期开始的进度（0~1）。 */
    public static float hordeProgressFor(UUID playerUuid) {
        return syncedHordeProgress.getOrDefault(playerUuid, 0.0F);
    }

    /**
     * 【v0.2.91】DAY X 渐进红颜色：白 0xFFFFFF → 深红 0xFF3333 线性插值。
     * <p>返回 ARGB。注意：绘制时文字里**不能带颜色色码**（§c/§6/§e 会覆盖该参数），
     * 只能保留 §l（粗体）与 §r（重置）。
     */
    public static int argbForProgress(float p) {
        float t = Math.max(0.0F, Math.min(1.0F, p));
        int r = 255;
        int g = Math.round(255.0F + (0x33 - 255.0F) * t);
        int b = Math.round(255.0F + (0x33 - 255.0F) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 接收服务端同步的村民模组天数（VillagerModDayPayload） */
    public static void onVillagerModDayReceived(UUID villagerUuid, String registryKey, int modDay, double x, double y, double z, boolean waveActive, boolean hordeDay, float hordeProgress, boolean hordeDayToday) {
        villagerModDays.put(villagerUuid, modDay);
        villagerWaveActive.put(villagerUuid, waveActive);
        villagerHordeDayMap.put(villagerUuid, hordeDay);
        villagerHordeProgress.put(villagerUuid, hordeProgress);
        villagerHordeDayToday.put(villagerUuid, hordeDayToday);
        thisBatchVillagers.add(villagerUuid); // 记录本批次收到的村民
        // 客户端本地化：直接使用 Minecraft Java 原版标准译名（entity.minecraft.villager.*）
        String localizedProfession = ModMessages.localizeProfession(registryKey);
        // 兜底：未命中翻译时去掉命名空间，避免 HUD 显示 minecraft:xxx
        if (localizedProfession.contains(":")) {
            String path = localizedProfession.substring(localizedProfession.lastIndexOf(':') + 1);
            localizedProfession = path.replace('_', ' ');
        }
        villagerProfessions.put(villagerUuid, localizedProfession);
        villagerPos.put(villagerUuid, new double[]{x, y, z});
    }

    /** 接收服务端同步的 mod info 常驻显示开关 */
    public static void onInfoOverlayReceived(boolean enabled) {
        infoOverlayEnabled = enabled;
        com.enik.zombietide.EnikZombietide.LOGGER.info("[Zombietide] InfoOverlay received: enabled={}", enabled);
    }

    /**
     * 断线时清空所有服务端同步缓存（v0.1.60 修复 #123）。
     * <p>这些 Map 是 static，退出存档后数据仍在；若接着进入一个**没装本模组**的服务器，
     * 左上角会继续显示上一局的 `DAY X`（`hudEnabled` 默认 true，没有别的东西拦住它）。
     */
    public static void clearAllSynced() {
        syncedModDays.clear();
        syncedHordeDay.clear();
        syncedWaveActive.clear();
        syncedDebt.clear();
        syncedActivePlayerHordes.clear();
        syncedActiveVillagerHordes.clear();
        syncedAliveHordeZombies.clear();
        villagerModDays.clear();
        villagerProfessions.clear();
        villagerPos.clear();
        villagerWaveActive.clear();
        villagerHordeDayMap.clear();
        syncedHordeProgress.clear();
        villagerHordeProgress.clear();
        syncedHordeDayToday.clear();
        villagerHordeDayToday.clear();
        thisBatchVillagers.clear();
        // 【v0.1.62 修复 #145】断线时也要清掉这两个静态字段，否则进入
        // 未安装本模组的服务器时，配置缓存与右上角面板开关仍残留上一局的值。
        clientConfig = null;
        infoOverlayEnabled = false;
    }

    /** 本批次收到过数据的村民（广播结束时据此清理死掉/超出范围的条目） */
    private static final Set<UUID> thisBatchVillagers = ConcurrentHashMap.newKeySet();

    /** 广播开始标记（服务端每批次调用一次） */
    public static void onVillagerBroadcastStart() {
        thisBatchVillagers.clear();
    }

    /** 广播结束标记：清理不在本批次里的村民（死掉/超出范围/已移除） */
    public static void onVillagerBroadcastEnd() {
        villagerModDays.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerProfessions.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerPos.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerWaveActive.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerHordeDayMap.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        // 【v0.2.96】v0.2.91/94 新增的两个 Map 之前漏清理：
        // 村民离开范围/死亡后 progress 或 hordeDayToday 残留旧值，
        // 下次同名 UUID 重新出现但本批次尚未收到 payload 时，会用残留值渲染 →
        // 同一面板里出现「有些村民渐进红、有些像关闭」的不一致。
        villagerHordeProgress.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerHordeDayToday.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
    }

    /**
     * HUD 渲染入口（由 ZombietideClientEvents.onRenderHud 在 RenderGuiEvent.Post 阶段调用）。
     *
     * @param guiGraphics GUI 绘图上下文
     * @param minecraft   客户端实例
     */
    public static void render(GuiGraphics guiGraphics, Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) return;
        // F1 隐藏 GUI 时一并隐藏（原版 HUD 层用 () -> !options.hideGui 控制，自定义 HUD 需自己判断）
        if (minecraft.options.hideGui) return;

        // 服务端配置：仅用于间隔/上限等玩法参数
        ZombietideConfig config = clientConfig != null ? clientConfig : ZombietideConfig.defaults();
        // HUD 布局改用本机全局配置（不随存档）
        ZombietideConfig.HudConfig hud = ClientHudConfig.get();
        if (!hud.hudEnabled()) return;

        int x = hud.hudX();
        int y = hud.hudY();
        float dayScale = hud.hudScale();
        boolean dayBold = hud.hudBold();

        // 尸潮当天：红色粗体，其余白色
        // 判定由服务端算好并随 PlayerModDayPayload 同步（HordeTimer.isHordeDay），
        // 同时支持固定间隔与随机间隔，客户端不再自行用 modDay % interval 推算
        UUID playerUuid = minecraft.player.getUUID();
        Integer modDay = syncedModDays.get(playerUuid);
        // 【v0.2.94】今天是不是尸潮日（整天，不看黄昏窗口）——二态模式的「尸潮日红」用它。
        // 注意：syncedHordeDay（窗口版）仍供雾色使用，这里不再引用。
        boolean hordeDayToday = Boolean.TRUE.equals(syncedHordeDayToday.get(playerUuid))
                || Boolean.TRUE.equals(syncedWaveActive.get(playerUuid));
        String dayText;
        int dayColor = 0xFFFFFF;
        if (modDay != null) {
            if (ClientHudConfig.dayGradientRed()) {
                // 【v0.2.91】真 RGB 渐进红：白 → 深红(0xFF3333)，到预兆期开始刚好全红。
                // 尸潮日/波次中强制 1.0（保持深红）。
                // 注意：文字里绝不能带颜色色码（§c/§6/§e 会覆盖 drawString 的颜色参数），只留 §l/§r。
                float p = hordeProgressFor(playerUuid);
                // 波次中强制全红（手动触发在非尸潮日时进度可能是 0，但正在打就该红）
                if (Boolean.TRUE.equals(syncedWaveActive.get(playerUuid))) p = 1.0F;
                dayColor = argbForProgress(p);
                dayText = "§r" + (dayBold ? "§l" : "") + "DAY " + modDay + "§r";
            } else if (hordeDayToday) {
                // 【v0.2.94】开关关闭 = 严格二态：**今天整天**是尸潮日（或波次中）→ 亮红。
                // 注意用 hordeDayToday（5 参，不看时刻），不是 hordeDay（只在黄昏窗口为 true）。
                dayText = dayBold ? "§r§c§lDAY " + modDay + "§r" : "§r§cDAY " + modDay + "§r";
            } else {
                // 非尸潮日：纯白
                dayText = "§r" + (dayBold ? "§l" : "") + "DAY " + modDay + "§r";
            }
        } else {
            dayText = "DAY --";
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(dayScale, dayScale, 1.0f);
        guiGraphics.drawString(minecraft.font, dayText,
                (int)(x / dayScale), (int)(y / dayScale), dayColor);
        guiGraphics.pose().popPose();

        // HUD 最多同时显示配置数量的村民条目：按距离从近到远排序
        int villagerX = hud.villagerHordeX();
        int villagerY = hud.villagerHordeY();
        float villagerScale = hud.villagerHordeScale();
        boolean villagerBold = hud.villagerHordeBold();
        int rowOffset = 12;
        int maxRows = hud.villagerHordeMaxEntries();
        if (maxRows < 1) maxRows = 1;
        // 【v0.1.66】按 GUI 缩放后的屏幕高度限制行数：之前只受 maxEntries 控制，
        // 把条目数或 villagerY 配大后，最后几行会被画到屏幕下方之外。
        // 行距 rowOffset 是 GUI 坐标、不随 pose.scale 变化；字体高度则随 villagerScale 放大。
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        int textHeightPx = (int) Math.ceil(Math.max(1, minecraft.font.lineHeight) * villagerScale);
        int usableHeight = guiHeight - villagerY - textHeightPx;
        int maxRowsByScreen = usableHeight <= 0 ? 0 : usableHeight / rowOffset;
        int displayRows = Math.min(maxRows, maxRowsByScreen);
        int rowCount = 0;

        // 只有开启“显示附近村民尸潮信息”时才渲染村民行
        if (hud.showVillagerHorde()) {
            // 收集附近村民的模组天数（服务端已同步，客户端按距离过滤，再按距离排序显示）
            double playerX = minecraft.player.getX();
            double playerY = minecraft.player.getY();
            double playerZ = minecraft.player.getZ();
            double maxDist = hud.villagerHordeDisplayRange();
            if (maxDist < 1) maxDist = 1;
            double maxDistSqr = maxDist * maxDist;
            List<Map.Entry<UUID, Integer>> nearbyVillagers = new ArrayList<>();
            for (var entry : villagerModDays.entrySet()) {
                double[] p = villagerPos.get(entry.getKey());
                if (p == null) continue;
                double dx = p[0] - playerX, dy = p[1] - playerY, dz = p[2] - playerZ;
                if (dx * dx + dy * dy + dz * dz > maxDistSqr) continue;
                nearbyVillagers.add(entry);
            }
            // 按距离从近到远排序（最近的优先显示）
            nearbyVillagers.sort((a, b) -> {
                double[] pa = villagerPos.get(a.getKey());
                double[] pb = villagerPos.get(b.getKey());
                double da = (pa[0] - playerX) * (pa[0] - playerX) + (pa[1] - playerY) * (pa[1] - playerY) + (pa[2] - playerZ) * (pa[2] - playerZ);
                double db = (pb[0] - playerX) * (pb[0] - playerX) + (pb[1] - playerY) * (pb[1] - playerY) + (pb[2] - playerZ) * (pb[2] - playerZ);
                return Double.compare(da, db);
            });

            for (var entry : nearbyVillagers) {
                if (rowCount >= displayRows) break;
                int villagerModDay = entry.getValue();
                String profession = villagerProfessions.getOrDefault(entry.getKey(), "村民");
                // 计算玩家到村民的直线距离（四舍五入整数）
                double[] p = villagerPos.get(entry.getKey());
                int dist = 0;
                if (p != null) {
                    double dx = p[0] - playerX, dy = p[1] - playerY, dz = p[2] - playerZ;
                    dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                // 仅 DAY X 参与颜色渐变；距离与职业名保持白色。
                // 判定由服务端算好并随 VillagerModDayPayload 同步，支持随机间隔。
                String professionSegment;
                if (villagerBold) {
                    professionSegment = "§r§l" + profession + "§r";
                } else {
                    professionSegment = profession;
                }

                String dayPartSegment;
                int daySegmentColor = 0xFFFFFF;
                if (ClientHudConfig.dayGradientRed()) {
                    // 【v0.2.91】每个村民按各自 lastTriggerDay/UUID 算进度，真 RGB 渐进红。
                    // 文字不能带颜色色码（会覆盖 drawString 颜色参数），只留 §l/§r。
                    float pv = villagerHordeProgress.getOrDefault(entry.getKey(), 0.0F);
                    // 【v0.2.96】与玩家侧对齐：只有「波次中」才强制全红。
                    // v0.2.94 玩家侧已从 hordeDay 改为 waveActive，村民侧漏改——
                    // 导致黄昏窗口内的村民被强制全红（看起来像二态关闭状态），
                    // 窗口外村民才按进度渐变，同一面板里两种表现混在一起。
                    if (Boolean.TRUE.equals(villagerWaveActive.get(entry.getKey()))) pv = 1.0F;
                    daySegmentColor = argbForProgress(pv);
                    dayPartSegment = "§r" + (villagerBold ? "§l" : "") + "DAY " + villagerModDay + "§r";
                } else if (Boolean.TRUE.equals(villagerHordeDayToday.get(entry.getKey()))
                        || Boolean.TRUE.equals(villagerWaveActive.get(entry.getKey()))) {
                    // 【v0.2.94】开关关闭 = 严格二态：该村民**今天整天**是尸潮日（或波次中）→ 亮红
                    dayPartSegment = villagerBold
                            ? "§r§c§lDAY " + villagerModDay + "§r"
                            : "§r§cDAY " + villagerModDay + "§r";
                } else {
                    // 非尸潮日：纯白（不再保留旧预兆黄/金渐变）
                    dayPartSegment = villagerBold
                            ? "§r§lDAY " + villagerModDay + "§r"
                            : "DAY " + villagerModDay + "§r";
                }

                String distSegment;
                // 距离永远不变红
                if (villagerBold) {
                    distSegment = "§r§l距离" + dist + "格§r";
                } else {
                    distSegment = "距离" + dist + "格";
                }

                // 【v0.2.91】三段独立绘制（颜色不同），用 font.width 推进光标。
                // 空格计入前一段宽度，避免相邻段重叠。
                guiGraphics.pose().pushPose();
                guiGraphics.pose().scale(villagerScale, villagerScale, 1.0f);
                int cursorX = (int) (villagerX / villagerScale);
                int rowY = (int) ((villagerY + rowOffset * (rowCount + 1)) / villagerScale);
                guiGraphics.drawString(minecraft.font, professionSegment, cursorX, rowY, 0xFFFFFF);
                cursorX += minecraft.font.width(professionSegment + " ");
                guiGraphics.drawString(minecraft.font, dayPartSegment, cursorX, rowY, daySegmentColor);
                cursorX += minecraft.font.width(dayPartSegment + " ");
                guiGraphics.drawString(minecraft.font, distSegment, cursorX, rowY, 0xFFFFFF);
                guiGraphics.pose().popPose();
                rowCount++;
            }
        }

        // 如果开启了 mod info 常驻显示，在右上角绘制完整信息（不重叠）
        int displayModDay = modDay != null ? modDay : 0;
        if (infoOverlayEnabled) {
            drawModInfoOverlay(guiGraphics, minecraft, config, displayModDay);
        } else {
            com.enik.zombietide.EnikZombietide.LOGGER.debug("[Zombietide] HUD render: infoOverlayEnabled=false, skipping overlay");
        }
    }

    /** 间隔显示文本：固定显示「N天」，随机显示「min~max天(随机)」 */
    private static String intervalText(ZombietideConfig config, boolean isVillager) {
        boolean random = isVillager ? config.villagerIntervalRandom() : config.playerIntervalRandom();
        if (!random) {
            return (isVillager ? config.villagerIntervalDays() : config.playerIntervalDays()) + "天";
        }
        int min = isVillager ? config.villagerIntervalMin() : config.playerIntervalMin();
        int max = isVillager ? config.villagerIntervalMax() : config.playerIntervalMax();
        return min + "~" + max + "天(随机)";
    }

    /** 绘制 mod info 常驻屏幕信息（右上角，实时刷新，内容与 debug info 逐区对应） */
    private static void drawModInfoOverlay(GuiGraphics guiGraphics, Minecraft minecraft, ZombietideConfig config, int modDay) {
        // 必须用 GUI 缩放后的宽度：drawString 用的是 GUI 坐标，用物理像素宽度会把面板推到屏幕外。
        // 宽度取 360：配置摘要在「随机间隔」模式下（如 5~9天(随机)）文字最宽，280 会被右侧裁掉。
        // Math.max 保证窄屏时贴左边，不会因为负数坐标画到屏幕外。
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int startX = Math.max(4, screenWidth - 360);
        int startY = 10;
        int lineHeight = 11;
        int lineCount = 0;

        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) return;
        UUID playerUuid = player.getUUID();

        // 标题
        guiGraphics.drawString(minecraft.font, "§r§c§l尸潮 实时信息§r", startX, startY, 0xFFFFFF);
        lineCount++;

        // --- 配置摘要（与 debug info 的「配置摘要」区字段一致；间隔在各目标区单独显示）---
        guiGraphics.drawString(minecraft.font, "§b--- 配置摘要 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§e玩家尸潮: §f" + (config.playerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e村民尸潮: §f" + (config.villagerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e全局上限: §f" + config.globalCap()
                + " §e血债模式: §f" + config.debtMode(), startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;

        // --- 玩家尸潮（字段与 /zombietide debug info 的「玩家尸潮」区一致）---
        Integer syncedModDay = syncedModDays.get(playerUuid);
        int pModDay = syncedModDay != null ? syncedModDay : modDay;
        // 用服务端同步的权威值，不自行推算（随机间隔下固定公式会错）
        boolean playerHordeDay = Boolean.TRUE.equals(syncedHordeDay.get(playerUuid));
        boolean playerWaveActive = Boolean.TRUE.equals(syncedWaveActive.get(playerUuid));
        int playerDebt = syncedDebt.getOrDefault(playerUuid, 0);

        guiGraphics.drawString(minecraft.font, "§b--- 玩家尸潮 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§e玩家名: §f" + player.getScoreboardName()
                + " §eUUID: §f" + player.getStringUUID(), startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§eModDay: §f" + pModDay + "天"
                + (playerHordeDay ? " §c§l←尸潮日" : "")
                + " §e间隔: §f" + intervalText(config, false)
                + " §e血债: §f" + playerDebt
                + " §e进行中: §f" + (playerWaveActive ? "§a是" : "§c否"), startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;

        // --- 村民尸潮（字段与 debug info 的「村民尸潮」区一致，每个村民两行）---
        guiGraphics.drawString(minecraft.font, "§b--- 村民尸潮 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;

        double playerX = player.getX(), playerY = player.getY(), playerZ = player.getZ();
        double maxDistSqr = 128.0 * 128.0;
        int villagerShown = 0;
        // 面板总高度上限。注意：村民列表之后还有固定的「全局统计」3 行（标题 + 2 行数据），
        // 以及村民为 0 时额外的 1 行提示。之前的写法只用 maxLines 卡村民循环，
        // 全屏时可见村民更多、行数顶到上限后，全局统计那 3 行会被画到屏幕下方之外。
        // 这里提前预留 4 行，保证任何情况下最后一行都落在屏内（非全屏同样受益）。
        int maxLines = (minecraft.getWindow().getGuiScaledHeight() - startY) / lineHeight;
        int villagerLineLimit = Math.max(0, maxLines - 4);

        for (var entry : villagerModDays.entrySet()) {
            if (lineCount + 2 > villagerLineLimit) break; // 每个村民占两行，给末尾全局统计留够行数
            double[] pos = villagerPos.get(entry.getKey());
            if (pos == null) continue;
            double dx = pos[0] - playerX, dy = pos[1] - playerY, dz = pos[2] - playerZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSqr) continue;

            int vModDay = entry.getValue();
            String prof = villagerProfessions.getOrDefault(entry.getKey(), "村民");
            boolean vHordeDay = Boolean.TRUE.equals(villagerHordeDayMap.get(entry.getKey()));
            boolean vWaveActive = villagerWaveActive.getOrDefault(entry.getKey(), false);

            guiGraphics.drawString(minecraft.font, "§e职业名: §f" + prof
                    + " §eUUID: §f" + entry.getKey()
                    + " §e坐标: §f" + (int) Math.floor(pos[0]) + "," + (int) Math.floor(pos[1]) + "," + (int) Math.floor(pos[2]),
                    startX, startY + lineCount * lineHeight, 0xFFFFFF);
            lineCount++;
            guiGraphics.drawString(minecraft.font, "§eModDay: §f" + vModDay + "天"
                    + (vHordeDay ? " §c§l←尸潮日" : "")
                    + " §e间隔: §f" + intervalText(config, true)
                    + " §e进行中: §f" + (vWaveActive ? "§a是" : "§c否"),
                    startX, startY + lineCount * lineHeight, 0xFFFFFF);
            lineCount++;
            villagerShown++;
        }
        if (villagerShown == 0) {
            guiGraphics.drawString(minecraft.font, "§7(当前世界无村民)", startX, startY + lineCount * lineHeight, 0xFFFFFF);
            lineCount++;
        }

        // --- 全局统计 ---
        guiGraphics.drawString(minecraft.font, "§b--- 全局统计 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§e存活尸潮僵尸: §f" + syncedAliveHordeZombies.getOrDefault(playerUuid, 0)
                + " §e活跃玩家尸潮: §f" + syncedActivePlayerHordes.getOrDefault(playerUuid, 0)
                + " §e活跃村民尸潮: §f" + syncedActiveVillagerHordes.getOrDefault(playerUuid, 0)
                + " §e全局僵尸上限: §f" + config.globalCap(), startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§e血债上限: §f" + config.debtCap() + " 只"
                + " §e自然生成屏蔽: §f" + (config.blockNaturalSpawn() ? "§a开启" : "§c关闭")
                + " §e阳光燃烧: §f" + (config.daylightBurn() ? "§a开启" : "§c关闭"), startX, startY + lineCount * lineHeight, 0xFFFFFF);
    }
}