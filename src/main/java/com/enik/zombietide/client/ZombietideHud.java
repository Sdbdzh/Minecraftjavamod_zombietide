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
 * 客户端 HUD：在屏幕左上角显示当前模组天数（"DAY X"）。
 * <p>
 * 设计文档 v1.3 第11章：
 * - 单行显示：`DAY X`（如 `DAY 5`），X 为该玩家的模组天数
 * - 尸潮当天（模组天数是间隔天数的倍数）：`DAY X` **红色字体**显示
 * - 其余天数：`DAY X` 正常颜色显示
 * - 模组天数来自服务端同步（PlayerModDayPayload），客户端不再用世界时间推算
 * - 默认左上角；可选位置与 X/Y 微调偏移
 */
public class ZombietideHud {

    /** 客户端缓存的服务端配置（由 ConfigManager.applyClientConfig 注入） */
    private static ZombietideConfig clientConfig = null;

    /** 服务端同步的玩家模组天数（UUID -> modDay） */
    private static final Map<UUID, Integer> syncedModDays = new HashMap<>();
    /** 服务端同步的玩家是否可触发尸潮（主世界+非和平） */
    private static final Map<UUID, Boolean> syncedCanTrigger = new HashMap<>();
    /** 服务端同步的村民模组天数（UUID -> modDay；用于 HUD 附近村民显示） */
    private static final Map<UUID, Integer> villagerModDays = new HashMap<>();
    /** 村民职业名缓存（UUID -> profession，如“村民”、“牧羊人”、“图书管理员”） */
    private static final Map<UUID, String> villagerProfessions = new HashMap<>();

    /** 村民位置缓存（UUID -> x/y/z，用于 HUD 距离过滤） */
    private static final Map<UUID, double[]> villagerPos = new HashMap<>();

    /** 村民尸潮是否进行中（UUID -> waveActive；由服务端同步，用于信息层显示真实状态） */
    private static final Map<UUID, Boolean> villagerWaveActive = new HashMap<>();

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
    public static void onModDayReceived(UUID playerUuid, int modDay, boolean canTriggerHorde) {
        syncedModDays.put(playerUuid, modDay);
        syncedCanTrigger.put(playerUuid, canTriggerHorde);
    }

    /** 接收服务端同步的村民模组天数（VillagerModDayPayload） */
    public static void onVillagerModDayReceived(UUID villagerUuid, String registryKey, int modDay, double x, double y, double z, boolean waveActive) {
        villagerModDays.put(villagerUuid, modDay);
        villagerWaveActive.put(villagerUuid, waveActive);
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
    }

    /**
     * HUD 渲染入口（由 ZombietideClientEvents.onRenderHud 在 RenderGuiEvent.Post 阶段调用）。
     *
     * @param guiGraphics GUI 绘图上下文
     * @param minecraft   客户端实例
     */
    public static void render(GuiGraphics guiGraphics, Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) return;

        // 服务端配置：仅用于间隔/上限等玩法参数
        ZombietideConfig config = clientConfig != null ? clientConfig : ZombietideConfig.defaults();
        // HUD 布局改用本机全局配置（不随存档）
        ZombietideConfig.HudConfig hud = ClientHudConfig.get();
        if (!hud.hudEnabled()) return;

        int x = hud.hudX();
        int y = hud.hudY();
        float dayScale = hud.hudScale();
        boolean dayBold = hud.hudBold();

        // 尸潮当天（模组天数是间隔天数的倍数）：红色粗体，其余白色
        int interval = config.playerIntervalDays();
        UUID playerUuid = minecraft.player.getUUID();
        Integer modDay = syncedModDays.get(playerUuid);
        Boolean canTrigger = syncedCanTrigger.get(playerUuid);
        // 尸潮日判定与触发逻辑保持一致：modDay % interval == interval - 1
        // interval=1（每天）：任意 DAY（含 DAY 0）均为尸潮日；interval=7：第 6/13/20... 天
        // 需在主世界且非和平（canTrigger）下才显示红色；不额外限制 modDay > 0，否则每天模式会漏掉 DAY 0
        boolean hordeDay = modDay != null && interval > 0 && modDay % interval == interval - 1 && Boolean.TRUE.equals(canTrigger);
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
                // 尸潮日（villagerModDay % interval == interval - 1）时 DAY X 部分变红粗体，距离也变红粗体，职业名保持白色粗体
                // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
                int villagerInterval = config.villagerIntervalDays();
                boolean villagerHordeDay = villagerInterval > 0 && villagerModDay % villagerInterval == villagerInterval - 1;
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
    private static void drawModInfoOverlay(GuiGraphics guiGraphics, Minecraft minecraft, ZombietideConfig config, int modDay) {
        // 必须用 GUI 缩放后的宽度：drawString 用的是 GUI 坐标，用物理像素宽度会把面板推到屏幕外
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int startX = screenWidth - 280;
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
                + " §e间隔: §f" + config.playerIntervalDays() + "天"
                + " §e村民尸潮: §f" + (config.villagerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e间隔: §f" + config.villagerIntervalDays() + "天"
                + " §e全局上限: §f" + config.globalCap(), startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;

        // --- 玩家实时状态 ---
        Integer syncedModDay = syncedModDays.get(playerUuid);
        Integer pModDay = syncedModDay != null ? syncedModDay : modDay;
        Boolean canTrigger = syncedCanTrigger.get(playerUuid);
        int playerInterval = config.playerIntervalDays();
        boolean playerHordeDay = playerInterval > 0 && pModDay % playerInterval == playerInterval - 1 && Boolean.TRUE.equals(canTrigger);

        guiGraphics.drawString(minecraft.font, "§b--- 玩家 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§e玩家: §f" + player.getScoreboardName()
                + " §eUUID: §f" + player.getStringUUID(), startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        // 客户端没有 ModAttachments，从 syncedModDays 获取基础信息
        guiGraphics.drawString(minecraft.font, "§eModDay: §fDAY " + pModDay
                + (playerHordeDay ? " §c§l←尸潮日" : "")
                + " §e间隔: §f" + playerInterval + "天"
                + " §e可触发: §f" + (Boolean.TRUE.equals(canTrigger) ? "§a是" : "§c否"), startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;

        // --- 村民实时状态（附近 128 格） ---
        guiGraphics.drawString(minecraft.font, "§b--- 附近村民 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;

        double playerX = player.getX(), playerY = player.getY(), playerZ = player.getZ();
        double maxDistSqr = 128.0 * 128.0;
        int villagerShown = 0;
        int villagerInterval = config.villagerIntervalDays();

        for (var entry : villagerModDays.entrySet()) {
            if (villagerShown >= 5) break; // 最多显示 5 个
            double[] pos = villagerPos.get(entry.getKey());
            if (pos == null) continue;
            double dx = pos[0] - playerX, dy = pos[1] - playerY, dz = pos[2] - playerZ;
            if (dx * dx + dy * dy + dz * dz > maxDistSqr) continue;

            int vModDay = entry.getValue();
            String prof = villagerProfessions.getOrDefault(entry.getKey(), "村民");
            boolean vHordeDay = villagerInterval > 0 && vModDay % villagerInterval == villagerInterval - 1;
            int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));

            guiGraphics.drawString(minecraft.font, "§e" + prof + " §fDAY " + vModDay
                    + (vHordeDay ? " §c§l←尸潮日" : "")
                    + " §e距离: §f" + dist + "格"
                    + " §e间隔: §f" + villagerInterval + "天"
                    + " §e进行中: §f" + (villagerWaveActive.getOrDefault(entry.getKey(), false) ? "§c是" : "§a否"), startX, startY + lineCount * lineHeight, 0xFFFFFF);
            lineCount++;
            villagerShown++;
        }
        if (villagerShown == 0) {
            guiGraphics.drawString(minecraft.font, "§7(附近 128 格内无村民)", startX, startY + lineCount * lineHeight, 0xFFFFFF);
            lineCount++;
        }

        // --- 全局统计 ---
        guiGraphics.drawString(minecraft.font, "§b--- 全局 ---", startX, startY + lineCount * lineHeight, 0xFFFFFF);
        lineCount++;
        guiGraphics.drawString(minecraft.font, "§e全局上限: §f" + config.globalCap() + " 只"
                + " §e血债上限: §f" + config.debtCap() + " 只"
                + " §e自然生成屏蔽: §f" + (config.blockNaturalSpawn() ? "§a开启" : "§c关闭")
                + " §e阳光燃烧: §f" + (config.daylightBurn() ? "§a开启" : "§c关闭"), startX, startY + lineCount * lineHeight, 0xFFFFFF);
    }
}