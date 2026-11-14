package com.enik.zombietide.horde;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.attachments.PlayerHordeData;
import com.enik.zombietide.attachments.VillagerHordeData;
import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.VillagerScheduleData;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.network.ModMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.UUID;

/**
 * 计时引擎：驱动玩家和村民的模组天数计时，判定尸潮触发时机。
 * <p>
 * 核心规则（以本类实现为准）：
 * - 时间源：主世界 gameDay（dayTime / 24000），所有维度共用
 * - 玩家 modDay：在线时按主世界日历推进；离线期间不推进；<b>登录/换维度时由
 *   {@code ZombietideServerEvents.onEntityJoinLevel} 直接对齐当前游戏日</b>（但不补触发错过的尸潮）
 * - 村民 modDay：永远推进（已加载+未加载全量，按调度表）；跨维度不暂停
 * - 尸潮触发/警告/禁睡：仅主世界 + 非和平模式
 * - <b>{@code /time} 指令会改变 modDay</b>：正向跨天逐日补算、反向跳变单次重同步；
 *   同时检测异常跳变（|Δ|>1000）触发强制四散与 missedDusk 补偿
 */
public class HordeTimer {

    private static long lastGameDayCheck = -1L;
    private static long lastDawnDay = -1L;
    private static long lastScatterDay = -1L; // 记录最后一次执行 scatterZombies 的游戏日，防止重复
    private static long lastBurnSettleDay = -1L; // 记录最后一次起火时刻血债结算的游戏日，防止重复
    private static boolean loggedPlayerDuskCheckThisWindow = false;
    private static boolean loggedVillagerDuskCheckThisWindow = false;
    private static long lastDayTime = -1L;
    /** 跨天残留尸潮对账计时（每 40 tick = 2 秒一次） */
    private static int reconcileTick = 0;

    /** 每 tick 调用，推进所有玩家和村民的模组天数，检测尸潮触发
     * @param level 触发 tick 的世界（可能是任意维度）
     * @param config 当前配置
     */
    public static void onServerTick(ServerLevel level, ZombietideConfig config) {
        if (level == null) return;

        // 获取主世界 ServerLevel 作为时间源和调度中心
        MinecraftServer server = level.getServer();
        if (server == null) return;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        long dayTime = overworld.getDayTime();
        long currentGameDay = dayTime / 24000L;

        // 首次运行：仅记录基准，不触发任何事件
        if (lastDayTime < 0) {
            lastDayTime = dayTime;
            lastGameDayCheck = currentGameDay;
            return;
        }

        long lo = lastDayTime;
        long hi = dayTime;

        // ========== ① modDay = gameDay（双向同步，核心铁律） ==========
        // 正向跨天：逐日推进（保证中间天数的尸潮逻辑不漏触发）
        // 反向跳变：单次重同步（/time set 0 → modDay 归 0）
        if (currentGameDay != lastGameDayCheck) {
            if (currentGameDay > lastGameDayCheck) {
                for (long d = lastGameDayCheck + 1; d <= currentGameDay; d++) {
                    advanceModDay(overworld, config, (int) d);
                    // 该天的黄昏窗口被本次时间变化完整跳过 → 补偿触发
                    long winStart = d * 24000L + 13000L;
                    long winEnd = d * 24000L + 14000L;
                    if (lo < winStart && hi > winEnd) {
                        checkPlayerHordesForModDay(overworld, config, (int) d);
                        checkVillagerHordesForModDay(overworld, config, (int) d);
                    }
                }
            } else {
                // 反向跳变（重置/回退）：modDay 直接同步为当前 gameDay
                advanceModDay(overworld, config, (int) currentGameDay);
            }
            lastGameDayCheck = currentGameDay;
        }

        // ========== ② 跳变补偿（仅 |Δ|>1000 判定为指令跳变） ==========
        long timeDelta = hi - lo;
        if (Math.abs(timeDelta) > 1000) {
            // 跳变后僵尸将暴露在阳光下（dayPhase < 12000 白天 或 >= 23460 起燃烧）→ 强制四散
            // 夜晚（12000-23459）跳变不应强制四散，那是僵尸正常活动时间
            long curPhaseAfterJump = hi % 24000;
            boolean willBurnInSun = curPhaseAfterJump < 12000 || curPhaseAfterJump >= 23460;
            if (willBurnInSun) {
                HordeSpawner.forceScatterAll(overworld, config);
                // 注意：跳变补偿的四散不更新 lastScatterDay，避免阻挡当天正常的日出前四散
            }
            // 当前天的黄昏窗口被跳变完整跳过 → missedDusk 补偿
            long winStart = currentGameDay * 24000L + 13000L;
            long winEnd = currentGameDay * 24000L + 14000L;
            if (lo < winStart && hi > winEnd) {
                checkPlayerHordesBypassWindowWithWarning(overworld, config);
                checkVillagerHordesBypassWindowWithWarning(overworld, config);
            }
        }

        // ========== ③ 黄昏窗口尸潮触发（每 tick 检查，lastTriggerDay 去重保证幂等） ==========
        long curPhase = hi % 24000;
        if (curPhase >= 13000 && curPhase <= 14000) {
            checkPlayerHordes(overworld, config);
            checkVillagerHordes(overworld, config);
        }

        // ========== ④ 黎明结算 + 清晨警告（每游戏日一次） ==========
        if (curPhase < 1000 && lastDawnDay != currentGameDay) {
            lastDawnDay = currentGameDay;
            HordeSpawner.onDawn(overworld, config);
            HordeSpawner.checkMorningWarning(overworld, config);
        }

        // ========== ⑤ 日出前四散（每游戏日一次，阈值 = 23460 - 消失秒数*20） ==========
        long scatterStart = 23460L - config.despawnSeconds() * 20L;
        if (curPhase >= scatterStart && lastScatterDay != currentGameDay) {
            lastScatterDay = currentGameDay;
            HordeSpawner.scatterZombies(overworld, config, hi);
        }

        // ========== ⑥ 起火时刻血债结算（dayPhase >= 23460，僵尸开始着火，按实时存活数结算） ==========
        long burnStart = 23460L;
        if (curPhase >= burnStart && lastBurnSettleDay != currentGameDay) {
            lastBurnSettleDay = currentGameDay;
            HordeSpawner.settleDebtAtBurnTime(overworld, config);
        }

        // ⑦ 对账：结束跨天残留的尸潮（白天加载存档 / 错过黎明-四散窗口的兜底）
        reconcileTick++;
        if (reconcileTick >= 40) {
            reconcileTick = 0;
            HordeSpawner.reconcileStaleWaves(overworld, config);
        }

        lastDayTime = dayTime;
    }

    /** 推进所有在线玩家和所有村民的模组天数（modDay 直接等于主世界游戏日）
     * @param targetWorldDay 目标游戏日（dayTime / 24000）
     */
    public static void advanceModDay(ServerLevel overworld, ZombietideConfig config, int targetWorldDay) {
        int currentWorldDay = targetWorldDay;
        MinecraftServer server = overworld.getServer();

        // 1. 推进所有在线玩家（跨维度、跨难度、仅在线）
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                var data = ModAttachments.getPlayerHordeData(player);
                // modDay 直接等于主世界游戏日
                int newModDay = currentWorldDay;

                player.setData(ModAttachments.PLAYER_HORDE.get(),
                        new PlayerHordeData(
                                newModDay, data.lastTriggerDay(), data.bloodDebt(),
                                data.waveCount(), data.waveActive()
                        ));

                // 同步给客户端（含 canTriggerHorde）
                boolean canTrigger = player.level().dimension() == Level.OVERWORLD
                        && player.serverLevel().getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL;
                ModMessages.sendModDay(player, newModDay, canTrigger);
            }
        }

        // 2. 推进所有村民（调度表全量，无论加载与否、无论维度）
        VillagerScheduleData schedule = overworld.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);

        // 构建"当前已加载村民"映射（一次性遍历，替代逐条 getEntity）
        java.util.Map<UUID, Villager> loadedVillagers = buildLoadedVillagerMap(overworld);

        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            VillagerScheduleData.ScheduleEntry oldEntry = entry.getValue();

            // modDay 直接等于主世界游戏日
            int newModDay = currentWorldDay;

            VillagerScheduleData.ScheduleEntry newEntry = new VillagerScheduleData.ScheduleEntry(
                    newModDay, oldEntry.lastTriggerDay(), oldEntry.waveCount(),
                    oldEntry.waveActive()
            );
            schedule.put(villagerUuid, newEntry);

            // 同步给已加载的村民实体
            Villager villager = loadedVillagers.get(villagerUuid);
            if (villager != null) {
                villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                        new VillagerHordeData(
                                newModDay, oldEntry.lastTriggerDay(), oldEntry.waveCount(),
                                oldEntry.waveActive()
                        ));
            }
        }
        // 推进完立即广播所有村民模组天数，让客户端 HUD 秒刷新（避免等 2 秒周期广播）
        ModMessages.broadcastAllVillagerModDays();
    }

    /** 构建"当前已加载村民"映射（一次性遍历；ServerLevel.getEntity 的 UUID 索引对已加载村民不可靠，必须这样查） */
    private static java.util.Map<UUID, Villager> buildLoadedVillagerMap(ServerLevel overworld) {
        java.util.Map<UUID, Villager> map = new java.util.HashMap<>();
        for (var entity : overworld.getEntities().getAll()) {
            if (entity instanceof Villager v && v.isAlive()) {
                map.put(v.getUUID(), v);
            }
        }
        return map;
    }

    /** 检查玩家尸潮触发（仅主世界、非和平、黄昏窗口） */
    private static void checkPlayerHordes(ServerLevel overworld, ZombietideConfig config) {
        if (!config.playerHordeEnabled()) return;

        long dayTime = overworld.getDayTime() % 24000;
        if (dayTime < 13000 || dayTime > 14000) {
            loggedPlayerDuskCheckThisWindow = false;
            return;
        }

        if (!loggedPlayerDuskCheckThisWindow) {
            EnikZombietide.LOGGER.info("checkPlayerHordes: 黄昏时段检查触发，dayTime={}", dayTime);
            loggedPlayerDuskCheckThisWindow = true;
        }

        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // 守卫：仅主世界 + 非和平
            if (player.level().dimension() != Level.OVERWORLD) continue;
            if (player.serverLevel().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            var hordeData = ModAttachments.getPlayerHordeData(player);
            int modDay = hordeData.modDay();
            int interval = config.playerIntervalDays();
            if (interval <= 0) continue;
            // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
            // 即：modDay % interval == interval - 1
            // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
            if (modDay % interval != interval - 1) continue;
            if (hordeData.lastTriggerDay() == modDay && hordeData.waveCount() > 0) {
                EnikZombietide.LOGGER.info("checkPlayerHordes: 玩家 {} 今日(DAY {})已触发过，跳过（lastTriggerDay={}, waveCount={}）",
                        player.getScoreboardName(), modDay, hordeData.lastTriggerDay(), hordeData.waveCount());
                continue;
            }

            // 触发尸潮
            int hordeSize = config.playerCount();
            if (config.playerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.playerCountMin(), config.playerCountMax());
            }
            int totalHorde = hordeSize + hordeData.bloodDebt();

            EnikZombietide.LOGGER.info("checkPlayerHordes: 触发玩家 {} 尸潮 modDay={} interval={} hordeSize={} bloodDebt={} total={}",
                    player.getScoreboardName(), modDay, interval, hordeSize, hordeData.bloodDebt(), totalHorde);

            player.setData(ModAttachments.PLAYER_HORDE.get(),
                    new PlayerHordeData(hordeData.modDay(), modDay, 0,
                            hordeData.waveCount() + 1, true));

            HordeWarning.triggerPlayerHorde(overworld, player, config);
            HordeSpawner.startHorde(overworld, player.getUUID(), player, totalHorde, config, false, config.playerRadius());
        }
    }

    /** 检查村民尸潮触发（仅主世界、非和平、黄昏窗口） */
    private static void checkVillagerHordes(ServerLevel overworld, ZombietideConfig config) {
        if (!config.villagerHordeEnabled()) return;

        long dayTime = overworld.getDayTime() % 24000;
        if (dayTime < 13000 || dayTime > 14000) {
            loggedVillagerDuskCheckThisWindow = false;
            return;
        }

        if (!loggedVillagerDuskCheckThisWindow) {
            EnikZombietide.LOGGER.info("checkVillagerHordes: 黄昏时段检查触发，dayTime={}", dayTime);
            loggedVillagerDuskCheckThisWindow = true;
        }

        // 构建"当前已加载村民"映射（一次性遍历，替代逐条 getEntity）
        java.util.Map<UUID, Villager> loadedVillagers = buildLoadedVillagerMap(overworld);
        if (loadedVillagers.isEmpty()) return;

        // 遍历调度表所有村民（无论是否加载、无论维度）
        VillagerScheduleData schedule = overworld.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);

        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            VillagerScheduleData.ScheduleEntry sched = entry.getValue();

            int modDay = sched.modDay();
            int interval = config.villagerIntervalDays();
            if (interval <= 0) continue;
            // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
            // 即：modDay % interval == interval - 1
            // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
            if (modDay % interval != interval - 1) continue;
            if (sched.lastTriggerDay() == modDay && sched.waveCount() > 0) continue;

            // 实体验证：从已加载村民映射中取（getEntity 的 UUID 索引不可靠）
            Villager villager = loadedVillagers.get(villagerUuid);
            if (villager == null) continue;
            if (villager.level().dimension() != Level.OVERWORLD) continue;
            if (villager.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            int hordeSize = config.villagerCount();
            if (config.villagerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.villagerCountMin(), config.villagerCountMax());
            }

            EnikZombietide.LOGGER.info("checkVillagerHordes: 触发村民 {} 尸潮 modDay={} interval={} hordeSize={}",
                    villager.getStringUUID(), modDay, interval, hordeSize);

            // 更新调度表
            VillagerScheduleData.ScheduleEntry newSched = new VillagerScheduleData.ScheduleEntry(
                    sched.modDay(), modDay, sched.waveCount() + 1, true);
            schedule.put(villagerUuid, newSched);

            // 更新实体附件
            villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                    new VillagerHordeData(
                            sched.modDay(), modDay, sched.waveCount() + 1, true));

            HordeWarning.triggerVillagerHorde(overworld, villager, config);
            HordeSpawner.startHorde(overworld, villager.getUUID(), villager, hordeSize, config, true, config.villagerRadius());

            // 同步给附近玩家
            for (ServerPlayer p : overworld.getServer().getPlayerList().getPlayers()) {
                if (p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager), modDay,
                            villager.getX(), villager.getY(), villager.getZ(), true);
                }
            }
        }
    }

    /** 绕过窗口限制的玩家尸潮触发（missedDusk 补偿），带前夜警告 */
    private static void checkPlayerHordesBypassWindowWithWarning(ServerLevel overworld, ZombietideConfig config) {
        if (!config.playerHordeEnabled()) return;

        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        // 先发前夜警告
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != Level.OVERWORLD) continue;

            var hordeData = ModAttachments.getPlayerHordeData(player);
            int modDay = hordeData.modDay();
            int interval = config.playerIntervalDays();
            if (interval <= 0) continue;
            // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
            // 即：modDay % interval == interval - 1
            // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
            if (modDay % interval != interval - 1) continue;
            if (hordeData.lastTriggerDay() == modDay && hordeData.waveCount() > 0) continue;

            HordeSpawner.sendMorningWarning(overworld, player, config);
        }

        // 再触发尸潮（夜晚生成）
        checkPlayerHordesBypassWindow(overworld, config);
    }

    /** 绕过窗口限制的村民尸潮触发，带前夜警告 */
    private static void checkVillagerHordesBypassWindowWithWarning(ServerLevel overworld, ZombietideConfig config) {
        if (!config.villagerHordeEnabled()) return;

        VillagerScheduleData schedule = overworld.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);

        // 构建"当前已加载村民"映射（一次性遍历，替代逐条 getEntity）
        java.util.Map<UUID, Villager> loadedVillagers = buildLoadedVillagerMap(overworld);
        if (loadedVillagers.isEmpty()) return;

        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            VillagerScheduleData.ScheduleEntry sched = entry.getValue();

            int modDay = sched.modDay();
            int interval = config.villagerIntervalDays();
            if (interval <= 0) continue;
            // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
            // 即：modDay % interval == interval - 1
            // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
            if (modDay % interval != interval - 1) continue;
            if (sched.lastTriggerDay() == modDay && sched.waveCount() > 0) continue;

            Villager villager = loadedVillagers.get(villagerUuid);
            if (villager == null) continue;
            if (villager.level().dimension() != Level.OVERWORLD) continue;

            // 村民尸潮清晨提醒已弃用
            // HordeSpawner.sendVillagerMorningWarning(overworld, villager, config);
        }

        checkVillagerHordesBypassWindow(overworld, config);
    }

    /** 为指定模组天数检查玩家尸潮触发（巨幅跳变补偿） */
    private static void checkPlayerHordesForModDay(ServerLevel overworld, ZombietideConfig config, int targetModDay) {
        if (!config.playerHordeEnabled()) return;

        int interval = config.playerIntervalDays();
        if (interval <= 0) return;
        // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
        // 即：targetModDay % interval == interval - 1
        // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
        if (targetModDay % interval != interval - 1) return;

        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != Level.OVERWORLD) continue;
            if (player.serverLevel().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            var hordeData = ModAttachments.getPlayerHordeData(player);
            if (hordeData.modDay() != targetModDay) continue;
            if (targetModDay == hordeData.lastTriggerDay() && hordeData.waveCount() > 0) continue;

            int hordeSize = config.playerCount();
            if (config.playerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.playerCountMin(), config.playerCountMax());
            }
            int totalHorde = hordeSize + hordeData.bloodDebt();

            player.setData(ModAttachments.PLAYER_HORDE.get(),
                    new PlayerHordeData(hordeData.modDay(), targetModDay, 0,
                            hordeData.waveCount() + 1, true));

            HordeWarning.triggerPlayerHorde(overworld, player, config);
            HordeSpawner.startHorde(overworld, player.getUUID(), player, totalHorde, config, false, config.playerRadius());
        }
    }

    /** 为指定模组天数检查村民尸潮触发（巨幅跳变补偿） */
    private static void checkVillagerHordesForModDay(ServerLevel overworld, ZombietideConfig config, int targetModDay) {
        if (!config.villagerHordeEnabled()) return;

        int interval = config.villagerIntervalDays();
        if (interval <= 0) return;
        // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
        // 即：targetModDay % interval == interval - 1
        // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
        if (targetModDay % interval != interval - 1) return;

        VillagerScheduleData schedule = overworld.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);

        // 构建"当前已加载村民"映射（一次性遍历，替代逐条 getEntity）
        java.util.Map<UUID, Villager> loadedVillagers = buildLoadedVillagerMap(overworld);
        if (loadedVillagers.isEmpty()) return;

        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            VillagerScheduleData.ScheduleEntry sched = entry.getValue();

            if (sched.modDay() != targetModDay) continue;
            if (targetModDay == sched.lastTriggerDay() && sched.waveCount() > 0) continue;

            Villager villager = loadedVillagers.get(villagerUuid);
            if (villager == null) continue;
            if (villager.level().dimension() != Level.OVERWORLD) continue;
            if (villager.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            int hordeSize = config.villagerCount();
            if (config.villagerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.villagerCountMin(), config.villagerCountMax());
            }

            VillagerScheduleData.ScheduleEntry newSched = new VillagerScheduleData.ScheduleEntry(
                    sched.modDay(), targetModDay, sched.waveCount() + 1, true);
            schedule.put(villagerUuid, newSched);

            villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                    new VillagerHordeData(
                            sched.modDay(), targetModDay, sched.waveCount() + 1, true));

            HordeWarning.triggerVillagerHorde(overworld, villager, config);
            HordeSpawner.startHorde(overworld, villager.getUUID(), villager, hordeSize, config, true, config.villagerRadius());

            for (ServerPlayer p : overworld.getServer().getPlayerList().getPlayers()) {
                if (p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager), targetModDay,
                            villager.getX(), villager.getY(), villager.getZ(), true);
                }
            }
        }
    }

    /** 绕过窗口的玩家尸潮触发（missedDusk） */
    private static void checkPlayerHordesBypassWindow(ServerLevel overworld, ZombietideConfig config) {
        if (!config.playerHordeEnabled()) return;

        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != Level.OVERWORLD) continue;
            if (player.serverLevel().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            var hordeData = ModAttachments.getPlayerHordeData(player);
            int modDay = hordeData.modDay();
            int interval = config.playerIntervalDays();
            if (interval <= 0) continue;
            // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
            // 即：modDay % interval == interval - 1
            // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
            if (modDay % interval != interval - 1) continue;
            if (hordeData.lastTriggerDay() == modDay && hordeData.waveCount() > 0) continue;

            int hordeSize = config.playerCount();
            if (config.playerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.playerCountMin(), config.playerCountMax());
            }
            int totalHorde = hordeSize + hordeData.bloodDebt();

            player.setData(ModAttachments.PLAYER_HORDE.get(),
                    new PlayerHordeData(hordeData.modDay(), modDay, 0,
                            hordeData.waveCount() + 1, true));

            HordeWarning.triggerPlayerHorde(overworld, player, config);
            HordeSpawner.startHorde(overworld, player.getUUID(), player, totalHorde, config, false, config.playerRadius());
        }
    }

    /** 绕过窗口的村民尸潮触发 */
    private static void checkVillagerHordesBypassWindow(ServerLevel overworld, ZombietideConfig config) {
        if (!config.villagerHordeEnabled()) return;

        VillagerScheduleData schedule = overworld.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);

        // 构建"当前已加载村民"映射（一次性遍历，替代逐条 getEntity）
        java.util.Map<UUID, Villager> loadedVillagers = buildLoadedVillagerMap(overworld);
        if (loadedVillagers.isEmpty()) return;

        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            VillagerScheduleData.ScheduleEntry sched = entry.getValue();

            int modDay = sched.modDay();
            int interval = config.villagerIntervalDays();
            if (interval <= 0) continue;
            // 触发判定：严格按 interval，第 interval 天首次触发，之后每 interval 天
            // 即：modDay % interval == interval - 1
            // interval=7 时：6, 13, 20, 27... (第 7、14、21、28 天)
            if (modDay % interval != interval - 1) continue;
            if (sched.lastTriggerDay() == modDay && sched.waveCount() > 0) continue;

            Villager villager = loadedVillagers.get(villagerUuid);
            if (villager == null) continue;
            if (villager.level().dimension() != Level.OVERWORLD) continue;
            if (villager.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            int hordeSize = config.villagerCount();
            if (config.villagerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.villagerCountMin(), config.villagerCountMax());
            }

            VillagerScheduleData.ScheduleEntry newSched = new VillagerScheduleData.ScheduleEntry(
                    sched.modDay(), modDay, sched.waveCount() + 1, true);
            schedule.put(villagerUuid, newSched);

            villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                    new VillagerHordeData(
                            sched.modDay(), modDay, sched.waveCount() + 1, true));

            HordeWarning.triggerVillagerHorde(overworld, villager, config);
            HordeSpawner.startHorde(overworld, villager.getUUID(), villager, hordeSize, config, true, config.villagerRadius());

            for (ServerPlayer p : overworld.getServer().getPlayerList().getPlayers()) {
                if (p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager), modDay,
                            villager.getX(), villager.getY(), villager.getZ(), true);
                }
            }
        }
    }
}