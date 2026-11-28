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
 *   同时检测异常跳变（|Δ|>1000）触发强制清除（forceClearAllHordes）与 missedDusk 补偿
 */
public class HordeTimer {

    private static long lastGameDayCheck = -1L;
    private static long lastDawnDay = -1L;
    /**
     * 上次执行 scatterZombies 的游戏刻（v0.1.55 起改为按 tick 节流，不再按天去重）。
     * <p>原先是「一天只四散一次」，导致同一天内新触发的波次（`/zombietide trigger` 或自然触发）
     * 永远不会进入四散，僵尸在白天继续以 state=0 追击，只能等 23460 被清。
     * 现在每 20 tick（1 秒）允许重扫一次：{@code scatterZombies} 对 state==1 已跳过（幂等），
     * 新刷出的僵尸最迟 1 秒内纳入四散，同时把 UUID 遍历成本从 800 次/秒降到 40 次/秒。
     */
    private static long lastScatterTick = -100;   // 初始负值，避免 nowTick - Long.MIN_VALUE 溢出
    private static long lastBurnSettleDay = -1L; // 记录最后一次起火时刻血债结算的游戏日，防止重复
    private static boolean loggedPlayerDuskCheckThisWindow = false;
    private static boolean loggedVillagerDuskCheckThisWindow = false;
    private static long lastDayTime = -1L;
    /** 跨天残留尸潮对账计时（每 40 tick = 2 秒一次） */
    private static int reconcileTick = 0;

    /** HUD 玩家状态定期同步计数器（每 20 tick = 1 秒刷新一次 waveActive / 血债） */
    private static int hudSyncTick = 0;

    /** 四散锚点移动计时器（每 5 tick 推进一次，避免每 tick 扫全实体表） */

    /**
     * 重置所有静态计时器（服务器启动时调用）。
     * <p>单机「退出存档 → 进另一个存档」不会重启 JVM，而新世界的 {@code getGameTime()}
     * 从 0 开始；若沿用旧存档的 {@code lastScatterTick}，{@code nowTick - lastScatterTick}
     * 会是负数，导致该会话<b>永不四散</b>。同理反向跳变检测也会被残留的 {@code lastDayTime} 误触发。
     */
    public static void resetState() {
        lastGameDayCheck = -1L;
        lastDawnDay = -1L;
        lastScatterTick = -100;
        lastBurnSettleDay = -1L;
        lastDayTime = -1L;
        reconcileTick = 0;
        hudSyncTick = 0;
        loggedPlayerDuskCheckThisWindow = false;
        loggedVillagerDuskCheckThisWindow = false;
    }

    /**
     * 时间跳变超过这么多天时，只推进 modDay、<b>不做</b>尸潮触发补偿。
     * <p>逐日补偿循环里 {@code lo < winStart && hi > winEnd} 对每个中间日都成立，
     * 一次跳 38 天会补偿约 5 个尸潮日、每日触发所有村民 + 玩家 → 聊天刷屏 + 服务器卡顿。
     * 阈值取 3：正常游戏不会跳天，`/time set` 微调几天仍可补偿，跳几十天不应补发。
     */
    private static final long MAX_COMPENSATE_DAYS = 3;

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
        // 正向小跨度：逐日推进并补偿被跳过的黄昏窗口
        // 正向大跨度：只推进到当前日，不做触发补偿（见 MAX_COMPENSATE_DAYS 注释）
        // 反向跳变：单次重同步（/time set 0 → modDay 归 0）
        if (currentGameDay != lastGameDayCheck) {
            long skippedDays = currentGameDay - lastGameDayCheck;
            if (skippedDays <= 0) {
                // 反向跳变（重置/回退）：modDay 直接同步为当前 gameDay
                advanceModDay(overworld, config, (int) currentGameDay);
            } else if (skippedDays <= MAX_COMPENSATE_DAYS) {
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
                // 大跨度（如 /time set day 40）：逐日补偿会为每个错过的尸潮日都触发一次
                // （lo/hi 是绝对 dayTime，判断对每个中间日都成立），导致刷屏与卡顿。
                // 这里只推进 modDay，不补发尸潮——玩家不在场的那些天补发没有意义。
                advanceModDay(overworld, config, (int) currentGameDay);
                EnikZombietide.LOGGER.info(
                        "时间跳变 {} 天（阈值 {}），仅推进 modDay 到 DAY {}，不补偿触发尸潮",
                        skippedDays, MAX_COMPENSATE_DAYS, currentGameDay);
            }
            lastGameDayCheck = currentGameDay;
        }

        // ========== ② 跳变补偿（仅 |Δ|>1000 判定为指令跳变） ==========
        long timeDelta = hi - lo;
        if (Math.abs(timeDelta) > 1000) {
            // 【v0.1.62 修复 #160】跳变后僵尸将暴露在阳光下（白天 或 >= 23460）→
            // **直接清除**（forceClearAllHordes），不是"强制四散"。
            // 夜晚（12000-23459）跳变不清除，那是僵尸正常活动时间
            long curPhaseAfterJump = hi % 24000;
            boolean willBurnInSun = curPhaseAfterJump < 12000 || curPhaseAfterJump >= 23460;
            if (willBurnInSun) {
                HordeSpawner.forceClearAllHordes(overworld, config);
                // 注意：跳变补偿走的是 forceClearAllHordes（直接清除），不经过 scatterZombies，
                // 因此不影响 lastScatterTick 的节拍
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

        // ========== ④ 黎明收尾 + 清晨警告（每游戏日一次；v0.1.56 起 onDawn 只重置 waveActive，不结算血债） ==========
        if (curPhase < 1000 && lastDawnDay != currentGameDay) {
            lastDawnDay = currentGameDay;
            HordeSpawner.onDawn(overworld, config);
            HordeSpawner.checkMorningWarning(overworld, config);
        }

        // ========== ⑤ 日出前四散（阈值 = 23460 - 消失秒数*20，每 20 tick 允许重扫） ==========
        long scatterStart = 23460L - config.despawnSeconds() * 20L;
        long nowTick = overworld.getGameTime();
        if (curPhase >= scatterStart && nowTick - lastScatterTick >= 20) {
            lastScatterTick = nowTick;
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

        // ⑨ HUD 玩家状态定期同步：waveActive 与血债会随触发/结算实时变化，
        //    只在跨天和登录时同步会让右上角面板一直显示旧值。1 秒一次，包极小。
        hudSyncTick++;
        if (hudSyncTick >= 20) {
            hudSyncTick = 0;
            if (server != null && !server.getPlayerList().getPlayers().isEmpty()) {
                // 全局统计只算一次，分发给所有玩家
                int activePlayerHordes = HordeSpawner.getActivePlayerHordeCount(overworld);
                int activeVillagerHordes = HordeSpawner.getActiveVillagerHordeCount(overworld);
                int aliveHordeZombies = HordeSpawner.countAliveHordeZombies(overworld);
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    syncPlayerHudState(overworld, config, p, activePlayerHordes, activeVillagerHordes, aliveHordeZombies);
                }
            }
        }

        lastDayTime = dayTime;
    }

    /**
     * 把单个玩家的 HUD 状态同步给客户端：modDay / 是否尸潮日 / 是否进行中 / 血债。
     * <p>客户端没有 ModAttachments 与 SavedData，这四个值只能由服务端下发；
     * 字段与 {@code /zombietide debug info} 的「玩家尸潮」区一致。
     */
    public static void syncPlayerHudState(ServerLevel overworld, ZombietideConfig config, ServerPlayer player) {
        syncPlayerHudState(overworld, config, player,
                HordeSpawner.getActivePlayerHordeCount(overworld),
                HordeSpawner.getActiveVillagerHordeCount(overworld),
                HordeSpawner.countAliveHordeZombies(overworld));
    }

    /**
     * 【v0.2.139】按条件批量重发 HUD 状态（对账改过状态的玩家用）。
     */
    public static void syncPlayerHudState(ServerLevel overworld, ZombietideConfig config,
                                          java.util.function.Predicate<ServerPlayer> filter) {
        if (overworld.getServer() == null) return;
        for (ServerPlayer player : overworld.getServer().getPlayerList().getPlayers()) {
            if (filter.test(player)) {
                syncPlayerHudState(overworld, config, player);
            }
        }
    }

    /** 批量同步时由调用方先算好全局统计，避免每个玩家重复扫描实体 */
    public static void syncPlayerHudState(ServerLevel overworld, ZombietideConfig config, ServerPlayer player,
                                          int activePlayerHordes, int activeVillagerHordes, int aliveHordeZombies) {
        var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
        int debt = com.enik.zombietide.config.PlayerDebtData.get(overworld).getDebt(player.getUUID());
        // 当天触发过就显示红色：窗口内的常规触发由 isHordeDay 给出，
        // 手动触发（/zombietide trigger，可在任意时刻）靠 waveActive 补充——
        // 否则窗口外手动触发时 isHordeDay 恒 false，HUD 不红（村民侧尤其明显）。
        // 波次结束后 waveActive=false，红字自然熄灭，不影响 v0.1.66 的窗口判定。
        long dayTime = overworld.getDayTime();
        boolean playerHordeDay = isHordeDay(player.getUUID(), data.modDay(), data.lastTriggerDay(), config, false, dayTime)
                || data.waveActive();
        float progress = hordeProgress(player.getUUID(), data.modDay(), data.lastTriggerDay(),
                config, false, dayTime);
        // 【v0.2.94】hordeDayToday = 5 参（不看时刻）：今天是否为该玩家的尸潮日，整天有效。
        // DAY X 二态模式必须用它——「尸潮日红」是整天红，不是只在黄昏窗口红。
        boolean playerHordeDayToday = isHordeDay(player.getUUID(), data.modDay(), data.lastTriggerDay(),
                config, false) || data.waveActive();
        ModMessages.sendModDay(player, data.modDay(),
                playerHordeDay,
                data.waveActive(), debt,
                activePlayerHordes, activeVillagerHordes, aliveHordeZombies,
                progress, playerHordeDayToday);
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
                                newModDay, data.lastTriggerDay(),
                                data.waveCount(), data.waveActive()
                        ));

                // 同步给客户端（字段与 debug info 一致）
                syncPlayerHudState(overworld, config, player);
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
                // 确实看到实体 → 刷新「最后见到日」，供 pruneStaleEntries 判定陈旧条目
                schedule.touch(villagerUuid, newModDay);
                villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                        new VillagerHordeData(
                                newModDay, oldEntry.lastTriggerDay(), oldEntry.waveCount(),
                                oldEntry.waveActive()
                        ));
            }
        }
        // 清理长期未见且从未触发过的陈旧条目，避免 SavedData 无界增长（每天一次，单次上限 64）
        schedule.pruneStaleEntries(currentWorldDay, 30);
        // 推进完立即广播所有村民模组天数，让客户端 HUD 秒刷新（避免等 2 秒周期广播）
        ModMessages.broadcastAllVillagerModDays();
    }

    /** 构建"当前已加载村民"映射（一次性遍历；ServerLevel.getEntity 的 UUID 索引对已加载村民不可靠，必须这样查） */
    private static java.util.Map<UUID, Villager> buildLoadedVillagerMap(ServerLevel overworld) {
        java.util.Map<UUID, Villager> map = new java.util.HashMap<>();
        for (var entity : overworld.getEntities().getAll()) {
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (entity instanceof Villager v && v.isAlive()) {
                map.put(v.getUUID(), v);
            }
        }
        return map;
    }

    /**
     * 判断指定目标在 modDay 是否为尸潮日，<b>并限定黄昏触发窗口 13000~14000</b>。
     * 正常触发与 HUD 红字用这个版本：窗口一过就返回 false，不会再出现「窗口后生成的村民
     * 被标成尸潮日却永远不会触发」的不一致。
     * <p>时间跳变补偿 / 清晨提醒请用 5 参的绕过版本。
     */
    public static boolean isHordeDay(UUID targetId, int modDay, int lastTriggerDay,
                                     ZombietideConfig config, boolean isVillager, long dayTime) {
        long phase = Math.floorMod(dayTime, 24000L);
        if (phase < 13000L || phase > 14000L) return false;
        return isHordeDayIgnoringWindow(targetId, modDay, lastTriggerDay, config, isVillager);
    }

    /**
     * 【绕过窗口版】只按日历日/间隔判断，不看当前时刻。
     * 仅供时间跳变补偿（窗口已被跳过）与清晨提醒使用；HUD 与正常触发一律用 6 参版。
     */
    public static boolean isHordeDay(UUID targetId, int modDay, int lastTriggerDay,
                                     ZombietideConfig config, boolean isVillager) {
        return isHordeDayIgnoringWindow(targetId, modDay, lastTriggerDay, config, isVillager);
    }

    /** 原判定核心：固定间隔取模 / 随机间隔哈希，不含任何时刻判断。 */
    private static boolean isHordeDayIgnoringWindow(UUID targetId, int modDay, int lastTriggerDay,
                                                    ZombietideConfig config, boolean isVillager) {
        // 当天已触发过 → 仍视为尸潮日（6 参版已在入口把窗口外的情形挡掉）。
        // 与触发判定解耦：触发路径另有 `lastTriggerDay == modDay && waveCount > 0` 去重，不会重复触发。
        if (lastTriggerDay == modDay) return true;

        int interval = isVillager ? config.villagerIntervalDays() : config.playerIntervalDays();
        if (interval <= 0) return false;

        boolean random = isVillager ? config.villagerIntervalRandom() : config.playerIntervalRandom();
        if (!random) {
            return modDay % interval == interval - 1;
        }

        int min = isVillager ? config.villagerIntervalMin() : config.playerIntervalMin();
        int max = isVillager ? config.villagerIntervalMax() : config.playerIntervalMax();
        if (min < 1) min = 1;
        if (max < min) max = min;

        int need = randomIntervalDays(targetId, lastTriggerDay, min, max);
        int base = lastTriggerDay < 0 ? -1 : lastTriggerDay;
        return modDay - base >= need;
    }

    /** 确定性伪随机间隔：同一 {@code (targetId, lastTriggerDay)} 恒定，跨重启一致。 */
    private static int randomIntervalDays(UUID targetId, int lastTriggerDay, int min, int max) {
        long h = targetId.getMostSignificantBits() * 0x9E3779B97F4A7C15L
               ^ targetId.getLeastSignificantBits() * 0xC2B2AE3D27D4EB4FL
               ^ (long) lastTriggerDay * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return min + (int) Math.floorMod(h, (long) (max - min + 1));
    }

    /**
     * 【v0.2.91】DAY X 渐进红进度（0~1）：从上次尸潮清场时刻到下次尸潮预兆期开始。
     * <p>起点 = {@code lastTriggerDay*24000 + 23460}（起火清场）；
     * 终点 = {@code nextHordeDay*24000 + 12000}（预兆期开始，此时 DAY X 刚好全红）。
     * <p>每 20 tick 由服务端权威数据重算、绝不增量累加——天然容忍 {@code /time} 跳变。
     * 随机间隔下 need 哈希含 lastTriggerDay，但周期内它不变，故进度单调不倒退；
     * 触发后 lastTriggerDay 更新 → 进入新周期，进度由新锚点重新起算。
     */
    public static float hordeProgress(UUID targetId, int modDay, int lastTriggerDay,
                                      ZombietideConfig config, boolean isVillager, long dayTime) {
        boolean enabled = isVillager ? config.villagerHordeEnabled() : config.playerHordeEnabled();
        if (!enabled) return 0.0F;

        int interval = isVillager ? config.villagerIntervalDays() : config.playerIntervalDays();
        boolean random = isVillager ? config.villagerIntervalRandom() : config.playerIntervalRandom();
        int min = isVillager ? config.villagerIntervalMin() : config.playerIntervalMin();
        int max = isVillager ? config.villagerIntervalMax() : config.playerIntervalMax();
        if (interval < 1) interval = 1;
        if (min < 1) min = 1;
        if (max < min) max = min;

        int base = lastTriggerDay < 0 ? -1 : lastTriggerDay;
        int nextHordeDay;
        boolean overdue;   // 间隔已过期（随时可能触发）或从未触发
        if (random) {
            int need = randomIntervalDays(targetId, lastTriggerDay, min, max);
            overdue = lastTriggerDay < 0 || base + need <= modDay;
            nextHordeDay = Math.max(modDay, base + need);
        } else {
            nextHordeDay = modDay + Math.floorMod(interval - 1 - Math.floorMod(modDay, interval), interval);
            overdue = lastTriggerDay < 0;
        }

        // 【v0.2.106】间隔已过期/从未触发时，start 不能用 base（=-1 时是世界开始），
        // 否则 Math.max(modDay, base+need) 钳到今天 → 跨度从世界开始到今天，
        // 进度恒接近 1 → DAY X 一直全红（新村庄村民 lastTriggerDay=-1 必现）。
        // 改为从"昨天清场"起算：当天 0 点约 0.04，到预兆期(12000)刚好 1.0。
        long start = overdue ? (modDay - 1L) * 24000L + 23460L
                             : base * 24000L + 23460L;
        long target = nextHordeDay * 24000L + 12000L;
        long span = target - start;
        if (span <= 0) return 1.0F;
        double p = (double) (dayTime - start) / (double) span;
        if (p <= 0.0) return 0.0F;
        if (p >= 1.0) return 1.0F;
        return (float) p;
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
            EnikZombietide.LOGGER.debug("checkPlayerHordes: 黄昏时段检查触发，dayTime={}", dayTime);
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
            if (!isHordeDay(player.getUUID(), modDay, hordeData.lastTriggerDay(), config, false, overworld.getDayTime())) continue;
            if (hordeData.lastTriggerDay() == modDay && hordeData.waveCount() > 0) {
                // 今日已触发过：静默跳过（不再打印「已触发过」日志——它与 isHordeDay 的
                // 「今天是尸潮日」判定放在一起读起来自相矛盾）
                continue;
            }

            // 触发尸潮
            int hordeSize = config.playerCount();
            if (config.playerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.playerCountMin(), config.playerCountMax());
            }
            com.enik.zombietide.config.PlayerDebtData debtData =
                    com.enik.zombietide.config.PlayerDebtData.get(overworld);
            int debt = debtData.getDebt(player.getUUID());
            int totalHorde = hordeSize + debt;

            EnikZombietide.LOGGER.info("checkPlayerHordes: 触发玩家 {} 尸潮 modDay={} hordeSize={} bloodDebt={} total={}",
                    player.getScoreboardName(), modDay, hordeSize, debt, totalHorde);

            // 注册玩家（血债表的「已知玩家」标记），本波已计入的血债随即清零
            debtData.register(player.getUUID());
            debtData.setDebt(player.getUUID(), 0);

            player.setData(ModAttachments.PLAYER_HORDE.get(),
                    new PlayerHordeData(hordeData.modDay(), modDay,
                            hordeData.waveCount() + 1, true, hordeData.waveSeq() + 1));

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
            EnikZombietide.LOGGER.debug("checkVillagerHordes: 黄昏时段检查触发，dayTime={}", dayTime);
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
            if (!isHordeDay(villagerUuid, modDay, sched.lastTriggerDay(), config, true, overworld.getDayTime())) continue;
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

            EnikZombietide.LOGGER.info("checkVillagerHordes: 触发村民 {} 尸潮 modDay={} hordeSize={}",
                    villager.getStringUUID(), modDay, hordeSize);

            // 更新调度表
            VillagerScheduleData.ScheduleEntry newSched = new VillagerScheduleData.ScheduleEntry(
                    sched.modDay(), modDay, sched.waveCount() + 1, true, sched.lastSeenDay(), sched.waveSeq() + 1);
            schedule.put(villagerUuid, newSched);

            // 更新实体附件
            villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                    new VillagerHordeData(
                            sched.modDay(), modDay, sched.waveCount() + 1, true, sched.waveSeq() + 1));

            HordeWarning.triggerVillagerHorde(overworld, villager, config);
            HordeSpawner.startHorde(overworld, villager.getUUID(), villager, hordeSize, config, true, config.villagerRadius());

            // 同步给附近玩家
            for (ServerPlayer p : overworld.players()) {
                if (p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager), modDay,
                            villager.getX(), villager.getY(), villager.getZ(), true,
                            isHordeDay(villager.getUUID(), modDay, sched.lastTriggerDay(), config, true, overworld.getDayTime()),
                            hordeProgress(villager.getUUID(), modDay, sched.lastTriggerDay(), config, true, overworld.getDayTime()),
                            isHordeDay(villager.getUUID(), modDay, sched.lastTriggerDay(), config, true) || sched.waveActive());
                }
            }
        }
    }

    /** 绕过窗口限制的玩家尸潮触发（missedDusk 补偿），带清晨提醒 */
    private static void checkPlayerHordesBypassWindowWithWarning(ServerLevel overworld, ZombietideConfig config) {
        if (!config.playerHordeEnabled()) return;

        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        // 先发清晨提醒
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != Level.OVERWORLD) continue;

            var hordeData = ModAttachments.getPlayerHordeData(player);
            int modDay = hordeData.modDay();
            if (!isHordeDay(player.getUUID(), modDay, hordeData.lastTriggerDay(), config, false)) continue;
            if (hordeData.lastTriggerDay() == modDay && hordeData.waveCount() > 0) continue;

            HordeSpawner.sendMorningWarning(overworld, player, config);
        }

        // 再触发尸潮（夜晚生成）
        checkPlayerHordesBypassWindow(overworld, config);
    }

    /** 绕过窗口限制的村民尸潮触发（missedDusk 补偿）。
     *  <p>方法名带 WithWarning 是历史遗留：村民清晨提醒已弃用，本方法不再发送任何提醒。
     *  原先的调度表遍历已无副作用，已删除。 */
    private static void checkVillagerHordesBypassWindowWithWarning(ServerLevel overworld, ZombietideConfig config) {
        checkVillagerHordesBypassWindow(overworld, config);
    }

    /** 为指定模组天数检查玩家尸潮触发（巨幅跳变补偿） */
    private static void checkPlayerHordesForModDay(ServerLevel overworld, ZombietideConfig config, int targetModDay) {
        if (!config.playerHordeEnabled()) return;

        int interval = config.playerIntervalDays();
        if (interval <= 0) return;
        // 固定间隔模式可提前剪枝；随机间隔需逐目标判定（见循环内 isHordeDay）
        if (!config.playerIntervalRandom() && targetModDay % interval != interval - 1) return;

        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level().dimension() != Level.OVERWORLD) continue;
            if (player.serverLevel().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            var hordeData = ModAttachments.getPlayerHordeData(player);
            if (hordeData.modDay() != targetModDay) continue;
            if (!isHordeDay(player.getUUID(), targetModDay, hordeData.lastTriggerDay(), config, false)) continue;
            if (targetModDay == hordeData.lastTriggerDay() && hordeData.waveCount() > 0) continue;

            int hordeSize = config.playerCount();
            if (config.playerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.playerCountMin(), config.playerCountMax());
            }
            com.enik.zombietide.config.PlayerDebtData debtData =
                    com.enik.zombietide.config.PlayerDebtData.get(overworld);
            int totalHorde = hordeSize + debtData.getDebt(player.getUUID());
            debtData.register(player.getUUID());
            debtData.setDebt(player.getUUID(), 0);

            player.setData(ModAttachments.PLAYER_HORDE.get(),
                    new PlayerHordeData(hordeData.modDay(), targetModDay,
                            hordeData.waveCount() + 1, true, hordeData.waveSeq() + 1));

            HordeWarning.triggerPlayerHorde(overworld, player, config);
            HordeSpawner.startHorde(overworld, player.getUUID(), player, totalHorde, config, false, config.playerRadius());
        }
    }

    /** 为指定模组天数检查村民尸潮触发（巨幅跳变补偿） */
    private static void checkVillagerHordesForModDay(ServerLevel overworld, ZombietideConfig config, int targetModDay) {
        if (!config.villagerHordeEnabled()) return;

        int interval = config.villagerIntervalDays();
        if (interval <= 0) return;
        // 固定间隔模式可提前剪枝；随机间隔需逐目标判定（见循环内 isHordeDay）
        if (!config.villagerIntervalRandom() && targetModDay % interval != interval - 1) return;

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
            if (!isHordeDay(villagerUuid, targetModDay, sched.lastTriggerDay(), config, true)) continue;

            Villager villager = loadedVillagers.get(villagerUuid);
            if (villager == null) continue;
            if (villager.level().dimension() != Level.OVERWORLD) continue;
            if (villager.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

            int hordeSize = config.villagerCount();
            if (config.villagerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.villagerCountMin(), config.villagerCountMax());
            }

            VillagerScheduleData.ScheduleEntry newSched = new VillagerScheduleData.ScheduleEntry(
                    sched.modDay(), targetModDay, sched.waveCount() + 1, true, sched.lastSeenDay(), sched.waveSeq() + 1);
            schedule.put(villagerUuid, newSched);

            villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                    new VillagerHordeData(
                            sched.modDay(), targetModDay, sched.waveCount() + 1, true, sched.waveSeq() + 1));

            HordeWarning.triggerVillagerHorde(overworld, villager, config);
            HordeSpawner.startHorde(overworld, villager.getUUID(), villager, hordeSize, config, true, config.villagerRadius());

            for (ServerPlayer p : overworld.players()) {
                if (p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager), targetModDay,
                            villager.getX(), villager.getY(), villager.getZ(), true,
                            isHordeDay(villager.getUUID(), targetModDay, sched.lastTriggerDay(), config, true, overworld.getDayTime()),
                            hordeProgress(villager.getUUID(), targetModDay, sched.lastTriggerDay(), config, true, overworld.getDayTime()),
                            isHordeDay(villager.getUUID(), targetModDay, sched.lastTriggerDay(), config, true) || sched.waveActive());
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
            if (!isHordeDay(player.getUUID(), modDay, hordeData.lastTriggerDay(), config, false)) continue;
            if (hordeData.lastTriggerDay() == modDay && hordeData.waveCount() > 0) continue;

            int hordeSize = config.playerCount();
            if (config.playerCountRandom()) {
                hordeSize = HordeSpawner.rollHordeSize(config.playerCountMin(), config.playerCountMax());
            }
            com.enik.zombietide.config.PlayerDebtData debtData =
                    com.enik.zombietide.config.PlayerDebtData.get(overworld);
            int totalHorde = hordeSize + debtData.getDebt(player.getUUID());
            debtData.register(player.getUUID());
            debtData.setDebt(player.getUUID(), 0);

            player.setData(ModAttachments.PLAYER_HORDE.get(),
                    new PlayerHordeData(hordeData.modDay(), modDay,
                            hordeData.waveCount() + 1, true, hordeData.waveSeq() + 1));

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
            if (!isHordeDay(villagerUuid, modDay, sched.lastTriggerDay(), config, true)) continue;
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
                    sched.modDay(), modDay, sched.waveCount() + 1, true, sched.lastSeenDay(), sched.waveSeq() + 1);
            schedule.put(villagerUuid, newSched);

            villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                    new VillagerHordeData(
                            sched.modDay(), modDay, sched.waveCount() + 1, true, sched.waveSeq() + 1));

            HordeWarning.triggerVillagerHorde(overworld, villager, config);
            HordeSpawner.startHorde(overworld, villager.getUUID(), villager, hordeSize, config, true, config.villagerRadius());

            for (ServerPlayer p : overworld.players()) {
                if (p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager), modDay,
                            villager.getX(), villager.getY(), villager.getZ(), true,
                            isHordeDay(villager.getUUID(), modDay, sched.lastTriggerDay(), config, true, overworld.getDayTime()),
                            hordeProgress(villager.getUUID(), modDay, sched.lastTriggerDay(), config, true, overworld.getDayTime()),
                            isHordeDay(villager.getUUID(), modDay, sched.lastTriggerDay(), config, true) || sched.waveActive());
                }
            }
        }
    }
}