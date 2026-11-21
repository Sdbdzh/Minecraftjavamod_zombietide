package com.enik.zombietide.server;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.attachments.PlayerHordeData;
import com.enik.zombietide.attachments.VillagerHordeData;
import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.VillagerScheduleData;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.config.ZombietideConfigData;
import com.enik.zombietide.horde.HordeBgmScheduler;
import com.enik.zombietide.horde.HordeSpawner;
import com.enik.zombietide.horde.HordeTimer;
import com.enik.zombietide.network.ModMessages;
import com.enik.zombietide.server.ZombietideCommands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 服务端事件：世界加载/保存、附件初始化、配置同步、尸潮时机驱动。
 */
@EventBusSubscriber(modid = EnikZombietide.MODID, bus = EventBusSubscriber.Bus.GAME)
public class ZombietideServerEvents {

    /** 服务端启动时：记录日志 */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        HordeBgmScheduler.reset(); // 单机换世界/服务器重启时清掉上一次的调度状态
        // 【v0.1.60 修复 #122】静态计时器必须随服务器启动重置。
        // 单机「退出存档 → 进另一个存档」不会重启 JVM，而新世界 getGameTime() 从 0 起，
        // 残留的 lastScatterTick 会比 nowTick 还大 → nowTick - lastScatterTick 为负 → 永不四散。
        HordeTimer.resetState();
        HordeSpawner.resetState();
        EnikZombietide.LOGGER.info("Zombietide server started");
    }

    /** 玩家登入：将当前配置发送给客户端，同步模组天数 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // 【v0.1.60 修复 #125】配置是「世界级」的，必须从主世界读。
        // 之前用 player.serverLevel()：从下界/末地登录会读到该维度的独立 SavedData
        // （未写过就是默认值），然后 broadcastConfig 把默认值广播给全服，覆盖主世界配置。
        ServerLevel overworld = player.server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
        ServerLevel level = overworld != null ? overworld : player.serverLevel();
        ZombietideConfigData data = ConfigManager.getConfigData(level);
        ZombietideConfig config = data.getConfig();
        ModMessages.broadcastConfig(config);
        
        // 【调试】打印完整配置信息到日志
        EnikZombietide.LOGGER.info("=== Zombietide 玩家加入配置同步 ===");
        EnikZombietide.LOGGER.info("玩家: {} (UUID: {})", player.getScoreboardName(), player.getStringUUID());
        EnikZombietide.LOGGER.info("--- 玩家尸潮 ---");
        EnikZombietide.LOGGER.info("  启用: {}, 间隔: {}天, 随机间隔: {}, 间隔范围: {}-{}", 
            config.playerHordeEnabled(), config.playerIntervalDays(), config.playerIntervalRandom(), 
            config.playerIntervalMin(), config.playerIntervalMax());
        EnikZombietide.LOGGER.info("  数量: {}, 随机数量: {}, 数量范围: {}-{}", 
            config.playerCount(), config.playerCountRandom(), config.playerCountMin(), config.playerCountMax());
        EnikZombietide.LOGGER.info("  生成半径: {}", config.playerRadius());
        EnikZombietide.LOGGER.info("--- 村民尸潮 ---");
        EnikZombietide.LOGGER.info("  启用: {}, 间隔: {}天, 随机间隔: {}, 间隔范围: {}-{}", 
            config.villagerHordeEnabled(), config.villagerIntervalDays(), config.villagerIntervalRandom(), 
            config.villagerIntervalMin(), config.villagerIntervalMax());
        EnikZombietide.LOGGER.info("  数量: {}, 随机数量: {}, 数量范围: {}-{}", 
            config.villagerCount(), config.villagerCountRandom(), config.villagerCountMin(), config.villagerCountMax());
        EnikZombietide.LOGGER.info("  生成半径: {}, 警告范围: {}, 禁睡范围: {}", 
            config.villagerRadius(), config.villagerWarningRange(), config.villagerSleepBlockRange());
        EnikZombietide.LOGGER.info("--- 分批生成 ---");
        EnikZombietide.LOGGER.info("  批次Tick: {}, 玩家批次大小: {}, 村民批次大小: {}", 
            config.batchTick(), config.batchSize(), config.villagerBatchSize());
        EnikZombietide.LOGGER.info("--- 世界规则 ---");
        EnikZombietide.LOGGER.info("  全局上限: {}, 阳光燃烧: {}, 尸潮禁睡: {}, 屏蔽自然生成: {}", 
            config.globalCap(), config.daylightBurn(), config.blockSleep(), config.blockNaturalSpawn());
        EnikZombietide.LOGGER.info("  血债模式: {}, 血债上限: {}, 僵尸消失秒数: {}", 
            config.debtMode(), config.debtCap(), config.despawnSeconds());
        EnikZombietide.LOGGER.info("--- 警告设置 ---");
        EnikZombietide.LOGGER.info("  启用: {}, 标题: {}, 音效: {}", 
            config.warningEnabled(), config.warningTitle(), config.warningSound());
        EnikZombietide.LOGGER.info("--- HUD设置 ---");
        EnikZombietide.LOGGER.info("  启用: {}, 显示村民: {}, 位置: {}, 坐标: ({},{}), 村民坐标: ({},{})", 
            config.hudEnabled(), config.showVillagerHorde(), config.hudPosition(), 
            config.hudX(), config.hudY(), config.villagerHordeX(), config.villagerHordeY());
        EnikZombietide.LOGGER.info("  缩放: 玩家{}x 村民{}x, 粗体: 玩家{} 村民{}", 
            config.hudScale(), config.villagerHordeScale(), config.hudBold(), config.villagerHordeBold());
        EnikZombietide.LOGGER.info("  村民显示距离: {}, 最大显示数: {}", 
            config.villagerHordeDisplayRange(), config.villagerHordeMaxEntries());
        
        // 【面板开关对齐】客户端 infoOverlayEnabled 是静态字段：单机退主菜单再进存档时它仍是 true，
        // 而服务端记录的开关会随登录清空。若不主动下发一次 false，两边状态会反相，
        // 玩家想关面板时第一次 toggle 反而会发 enable=true（要按两次）。这里强制归零，保证两边一致。
        ZombietideCommands.resetOverlayState(player.getUUID());
        ModMessages.sendInfoOverlay(player, false);
        // 同步玩家 HUD 状态（modDay / 尸潮日 / 进行中 / 血债 / 全局统计）
        HordeTimer.syncPlayerHudState(level, config, player);
        // 登录时对账一次：立即结束跨天残留的尸潮（避免白天加载存档后状态卡住）
        HordeSpawner.reconcileStaleWaves(level, config);
        // 【BUG3 修复】同步附近村民的模组天数给新登录玩家，确保 HUD 立即显示实时村民状态。
        // 数据源与周期广播保持一致：优先读 VillagerScheduleData（权威），附件只是运行时镜像；
        // 只在调度表缺条目时回退附件，避免两处读不同来源导致 HUD 数值打架。
        VillagerScheduleData joinSchedule = level.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.npc.Villager villager) {
                if (player.distanceToSqr(villager) <= 128.0 * 128.0) {
                    var sched = joinSchedule.getScheduleOrNull(villager.getUUID());
                    var fallback = ModAttachments.getVillagerHordeData(villager);
                    int vModDay = sched != null ? sched.modDay() : fallback.modDay();
                    int vLastTrigger = sched != null ? sched.lastTriggerDay() : fallback.lastTriggerDay();
                    boolean vActive = sched != null ? sched.waveActive() : fallback.waveActive();
                    ModMessages.sendVillagerModDay(player, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager),
                            vModDay,
                            villager.getX(), villager.getY(), villager.getZ(),
                            vActive,
                            HordeTimer.isHordeDay(villager.getUUID(), vModDay, vLastTrigger, config, true));
                }
            }
        }
    }

    /** 实体加入世界：为玩家和村民初始化尸潮附件（backfill 现有实体） */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        ServerLevel level = (ServerLevel) event.getLevel();
        int currentWorldDay = (int)(level.getDayTime() / 24000L);
        if (event.getEntity() instanceof Player player) {
            var data = ModAttachments.getPlayerHordeData(player);
            // 新玩家 modDay 直接 = 当前游戏日
            player.setData(ModAttachments.PLAYER_HORDE.get(),
                    new PlayerHordeData(currentWorldDay, data.lastTriggerDay(), data.waveCount(), data.waveActive()));
            // 注册到血债表：老存档玩家首次登录时补注册，否则结算时会被当成村民跳过（isKnown=false）
            if (level.getServer() != null) {
                com.enik.zombietide.config.PlayerDebtData.get(level.getServer().overworld()).register(player.getUUID());
            }
            EnikZombietide.LOGGER.info("玩家 {} 进入世界（登录/换维度），modDay 对齐为当前游戏日={}", player.getScoreboardName(), currentWorldDay);
        } else if (event.getEntity() instanceof net.minecraft.world.entity.Mob mob
                && (mob instanceof net.minecraft.world.entity.monster.Zombie
                    || mob instanceof net.minecraft.world.entity.monster.Stray)) {
            // 【重启恢复】尸潮僵尸重注册：重启后 hordeZombieUuids / scatteringZombies 清空，
            // 已生成的僵尸既不被四散清理也不锁定目标（Goal 不持久化），会变成孤儿。
            // 这里按 NBT 标记重新纳入追踪集合并重注入 AI。
            net.minecraft.nbt.CompoundTag tag = mob.getPersistentData();
            if (tag.hasUUID("zombietide_horde_target")) {
                int state = tag.getInt("zombietide_state");
                HordeSpawner.retrackZombie(level, mob.getUUID(), state);
                var config = ConfigManager.getConfigData(level).getConfig();
                com.enik.zombietide.horde.HordeZombieBehavior.apply(mob, tag.getUUID("zombietide_horde_target"), config);
            }
        } else if (event.getEntity() instanceof Villager villager) {
            // 村民加载时：从调度表恢复数据，并把 modDay 对齐为当前游戏日（modDay = gameDay 铁律）
            VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                    VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
            // touch 同时刷新 lastSeenDay（村民确实加载了），供调度表陈旧条目清理使用
            VillagerScheduleData.ScheduleEntry sched = schedule.touch(villager.getUUID(), currentWorldDay);
            if (sched.modDay() != currentWorldDay) {
                // 调度表滞后（区块长时间未加载）→ 同步为当前 gameDay
                sched = new VillagerScheduleData.ScheduleEntry(
                        currentWorldDay, sched.lastTriggerDay(), sched.waveCount(), sched.waveActive());
                schedule.put(villager.getUUID(), sched);
            }
            villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                    new VillagerHordeData(
                            sched.modDay(), sched.lastTriggerDay(), sched.waveCount(),
                            sched.waveActive()
                    ));
        }
    }

    /** 每 tick：驱动尸潮刷怪引擎 + 尸潮时机检测 + BGM 调度 + 村民模组天数周期性广播（每 20 tick = 1 秒） */
    private static int villagerModDayBroadcastTick = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null) return;
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        ZombietideConfigData data = ConfigManager.getConfigData(overworld);
        // 推进计时与触发判定
        HordeTimer.onServerTick(overworld, data.getConfig());
        // 分批刷怪
        HordeSpawner.update(overworld, data.getConfig());

        // 定制背景音乐调度（仅玩家尸潮触发者，每 30s~2min 随机播放一段）
        HordeBgmScheduler.onServerTick(overworld, data.getConfig());

        // 周期性广播村民模组天数给客户端（每 20 tick = 1 秒），确保 HUD 村民尸潮信息实时刷新
        villagerModDayBroadcastTick++;
        if (villagerModDayBroadcastTick >= 20) {
            villagerModDayBroadcastTick = 0;
            ModMessages.broadcastAllVillagerModDays();
        }
    }

    /** 注册管理命令 /zombietide（游戏总线事件，权限 level 2） */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        ZombietideCommands.register(event.getDispatcher());
    }

    /** 睡眠拦截：波次期间阻止玩家入睡（玩家尸潮禁触发者，村民尸潮禁配置范围内玩家） */
    @SubscribeEvent
    public static void onCanPlayerSleep(CanPlayerSleepEvent event) {
        // 只在主世界处理
        if (event.getEntity().level() == null || event.getEntity().level().dimension() != Level.OVERWORLD) return;

        ServerLevel level = (ServerLevel) event.getEntity().level();
        ZombietideConfigData data = ConfigManager.getConfigData(level);
        ZombietideConfig config = data.getConfig();

        if (!config.blockSleep()) return;

        ServerPlayer player = (ServerPlayer) event.getEntity();

        // 检查玩家尸潮：如果该玩家正在波次中，禁止睡眠
        var playerHordeData = ModAttachments.getPlayerHordeData(player);
        if (config.playerHordeEnabled() && playerHordeData.waveActive()) {
            event.setProblem(net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("zombietide.sleep.blocked").withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }

        // 检查村民尸潮：如果玩家在有波次的村民范围内，禁止睡眠
        if (config.villagerHordeEnabled()) {
            double range = config.villagerSleepBlockRange();
            for (var entity : level.getEntities().getAll()) {
                if (!(entity instanceof Villager villager)) continue;
                var villagerHordeData = ModAttachments.getVillagerHordeData(villager);
                if (villagerHordeData.waveActive() && player.distanceToSqr(villager) <= range * range) {
                    event.setProblem(net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("zombietide.sleep.blocked").withStyle(net.minecraft.ChatFormatting.RED));
                    return;
                }
            }
        }
    }

    /** 自然生成拦截：开启 blockNaturalSpawn 时，拦截自然刷新的僵尸类实体
     * 使用 MobSpawnEvent.SpawnPlacementCheck（在生成规则检查阶段拦截）
     * 注意：世界生成时 event.getLevel() 可能是 WorldGenRegion 而非 ServerLevel，需先 instanceof 检查 */
    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        net.minecraft.world.level.ServerLevelAccessor level = event.getLevel();
        if (level.isClientSide()) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        // 【v0.1.61 修复 #139】配置是「世界级」的，必须从主世界读。
        // 之前用 serverLevel（事件所在维度），在下界/末地会读到该维度的独立 SavedData。
        ServerLevel overworld = serverLevel.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        ZombietideConfigData data = ConfigManager.getConfigData(
                overworld != null ? overworld : serverLevel);
        ZombietideConfig config = data.getConfig();

        if (!config.blockNaturalSpawn()) return;

        // 仅拦截自然生成
        if (event.getSpawnType() != MobSpawnType.NATURAL) return;

        // 检查实体类型：僵尸、尸壳、溺尸、僵尸村民
        EntityType<?> type = event.getEntityType();
        if (type == EntityType.ZOMBIE || type == EntityType.HUSK ||
            type == EntityType.DROWNED || type == EntityType.ZOMBIE_VILLAGER) {
            // 拦截自然生成
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }
}
