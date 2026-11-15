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

    /** 客户端缓存的服务端配置（由 ConfigManager.applyClientConfig 注入） */
    private static ZombietideConfig clientConfig = null;

    /** 服务端同步的玩家模组天数（UUID -> modDay） */
    private static final Map<UUID, Integer> syncedModDays = new HashMap<>();
    /** 服务端同步的玩家尸潮是否进行中（对应 PlayerHordeData.waveActive） */
    private static final Map<UUID, Boolean> syncedWaveActive = new HashMap<>();
    /** 服务端同步的玩家血债（PlayerDebtData.getDebt） */
    private static final Map<UUID, Integer> syncedDebt = new HashMap<>();
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

    /** 客户端缓存的 mod info 常驻显示开关（由服务端通过 InfoOverlayPayload 同步） */
    private static boolean infoOverlayEnabled = false;

    /** 接收服务端广播的配置（由 ConfigManager.applyClientConfig 调用） */
    public static void onConfigReceived(ZombietideConfig config) {
        clientConfig = config;
    }

    /** 获取客户端缓存的配置（用于设置界面初始化） */
    public static ZombietideConfig getClientConfig() {
        return clientConfig != null ? clientConfig : ZombietideConfig.defaults();
    }

    /** 接收服务端同步的玩家模组天数（PlayerModDayPayload） */
    public static void onModDayReceived(UUID playerUuid, int modDay, boolean hordeDay, boolean waveActive, int bloodDebt) {
        syncedModDays.put(playerUuid, modDay);
        syncedHordeDay.put(playerUuid, hordeDay);
        syncedWaveActive.put(playerUuid, waveActive);
        syncedDebt.put(playerUuid, bloodDebt);
    }

    /** 接收服务端同步的村民模组天数（VillagerModDayPayload） */
    public static void onVillagerModDayReceived(UUID villagerUuid, String registryKey, int modDay, double x, double y, double z, boolean waveActive, boolean hordeDay) {
        villagerModDays.put(villagerUuid, modDay);
        villagerWaveActive.put(villagerUuid, waveActive);
        villagerHordeDayMap.put(villagerUuid, hordeDay);
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

    /** 广播批次标记：用于客户端清理不在本批次里的村民（死掉/超出范围的） */
    private static int currentBroadcastBatch = 0;
    private static final Set<UUID> thisBatchVillagers = ConcurrentHashMap.newKeySet();

    /** 广播开始标记（服务端每批次调用一次） */
    public static void onVillagerBroadcastStart() {
        currentBroadcastBatch++;
        thisBatchVillagers.clear();
    }

    /** 广播结束标记：清理不在本批次里的村民（死掉/超出范围/已移除） */
    public static void onVillagerBroadcastEnd() {
        villagerModDays.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerProfessions.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerPos.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerWaveActive.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
        villagerHordeDayMap.keySet().removeIf(uuid -> !thisBatchVillagers.contains(uuid));
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
        boolean hordeDay = Boolean.TRUE.equals(syncedHordeDay.get(playerUuid));
        String dayText;
        if (modDay != null) {
            if (hordeDay) {
                // 显式重置后再应用红色粗体，避免 §c 吞噬 §l
                if (dayBold) {
                    // 先颜色后粗体：§c 不会重置后面的 §l
                    dayText = "§r§c§lDAY " + modDay + "§r";
                } else {
                    dayText = "§r§cDAY " + modDay + "§r";
                }
            } else {
                if (dayBold) {
                    dayText = "§r§lDAY " + modDay + "§r";
                } else {
                    dayText = "DAY " + modDay + "§r";
                }
            }
        } else {
            dayText = "DAY --";
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(dayScale, dayScale, 1.0f);
        guiGraphics.drawString(minecraft.font, dayText,
                (int)(x / dayScale), (int)(y / dayScale), 0xFFFFFF);
        guiGraphics.pose().popPose();

        // HUD 最多同时显示配置数量的村民条目：按距离从近到远排序
        int villagerX = hud.villagerHordeX();
        int villagerY = hud.villagerHordeY();
        float villagerScale = hud.villagerHordeScale();
        boolean villagerBold = hud.villagerHordeBold();
        int rowOffset = 12;
        int maxRows = hud.villagerHordeMaxEntries();
        if (maxRows < 1) maxRows = 1;
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
                if (rowCount >= maxRows) break;
                int villagerModDay = entry.getValue();
                String profession = villagerProfessions.getOrDefault(entry.getKey(), "村民");
                // 计算玩家到村民的直线距离（四舍五入整数）
                double[] p = villagerPos.get(entry.getKey());
                int dist = 0;
                if (p != null) {
                    double dx = p[0] - playerX, dy = p[1] - playerY, dz = p[2] - playerZ;
                    dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                // 尸潮日时仅 DAY X 变红粗体；距离不变红，职业名保持白色
                // 判定由服务端算好并随 VillagerModDayPayload 同步，支持随机间隔
                boolean villagerHordeDay = Boolean.TRUE.equals(villagerHordeDayMap.get(entry.getKey()));
                // 分段组装：各段独立闭合，移除对 drawString 第4参数颜色的依赖
                String professionSegment;
                if (villagerBold) {
                    professionSegment = "§r§l" + profession + "§r";
                } else {
                    professionSegment = profession;
                }

                String dayPartSegment;
                if (villagerHordeDay) {
                    if (villagerBold) {
                        // 先颜色后粗体：§c 不会重置后面的 §l
                        dayPartSegment = "§r§c§lDAY " + villagerModDay + "§r";
                    } else {
                        dayPartSegment = "§r§cDAY " + villagerModDay + "§r";
                    }
                } else {
                    if (villagerBold) {
                        dayPartSegment = "§r§lDAY " + villagerModDay + "§r";
                    } else {
                        dayPartSegment = "DAY " + villagerModDay + "§r";
                    }
                }

                String distSegment;
                // 距离永远不变红，只有 DAY X 在尸潮日才变红
                if (villagerBold) {
                    distSegment = "§r§l距离" + dist + "格§r";
                } else {
                    distSegment = "距离" + dist + "格";
                }

                String villagerText = professionSegment + " " + dayPartSegment + " " + distSegment;
                // 应用缩放：drawString 不直接支持 scale，需 pushPose + scale + popPose
                guiGraphics.pose().pushPose();
                guiGraphics.pose().scale(villagerScale, villagerScale, 1.0f);
                guiGraphics.drawString(minecraft.font, villagerText,
                        (int)(villagerX / villagerScale),
                        (int)((villagerY + rowOffset * (rowCount + 1)) / villagerScale),
                        0xFFFFFF);
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

    /** 绘制 mod info 常驻屏幕信息（右上角，实时刷新，内容同 debug info） */
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

        // --- 配置摘要 ---
        guiGraphics.drawString(minecraft.font, "§b--- 配置 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§e玩家尸潮: §f" + (config.playerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e间隔: §f" + intervalText(config, false)
                + " §e村民尸潮: §f" + (config.villagerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e间隔: §f" + intervalText(config, true)
                + " §e全局上限: §f" + config.globalCap(), startX, startY + lineCount * lineHeight, 0xFFFFFF);
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
        int maxLines = (minecraft.getWindow().getGuiScaledHeight() - startY) / lineHeight;

        for (var entry : villagerModDays.entrySet()) {
            if (lineCount + 2 > maxLines) break; // 每个村民占两行，超出屏幕高度就停
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
        guiGraphics.drawString(minecraft.font, "§e全局上限: §f" + config.globalCap() + " 只"
                + " §e血债上限: §f" + config.debtCap() + " 只"
                + " §e自然生成屏蔽: §f" + (config.blockNaturalSpawn() ? "§a开启" : "§c关闭")
                + " §e阳光燃烧: §f" + (config.daylightBurn() ? "§a开启" : "§c关闭"), startX, startY + lineCount * lineHeight, 0xFFFFFF);
    }
}