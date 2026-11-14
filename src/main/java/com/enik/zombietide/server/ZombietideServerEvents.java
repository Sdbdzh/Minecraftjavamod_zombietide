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
        EnikZombietide.LOGGER.info("Zombietide server started");
    }

    /** 玩家登入：将当前配置发送给客户端，同步模组天数 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ServerLevel level = player.serverLevel();
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
        
        // 同步玩家模组天数
        var playerData = ModAttachments.getPlayerHordeData(player);
        boolean canTrigger = player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                && player.serverLevel().getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL;
        ModMessages.sendModDay(player, playerData.modDay(), canTrigger,
                HordeTimer.isHordeDay(player.getUUID(), playerData.modDay(), playerData.lastTriggerDay(), config, false));
        // 登录时对账一次：立即结束跨天残留的尸潮（避免白天加载存档后状态卡住）
        HordeSpawner.reconcileStaleWaves(level, config);
        // 【BUG3 修复】同步附近村民的模组天数给新登录玩家，确保 HUD 立即显示实时村民状态
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.npc.Villager villager) {
                if (player.distanceToSqr(villager) <= 128.0 * 128.0) {
                    var vData = ModAttachments.getVillagerHordeData(villager);
                    ModMessages.sendVillagerModDay(player, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager),
                            vData.modDay(),
                            villager.getX(), villager.getY(), villager.getZ(),
                            vData.waveActive(),
                            HordeTimer.isHordeDay(villager.getUUID(), vData.modDay(), vData.lastTriggerDay(), config, true));
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
                    new PlayerHordeData(currentWorldDay, data.lastTriggerDay(), data.bloodDebt(), data.waveCount(), data.waveActive()));
            EnikZombietide.LOGGER.info("玩家 {} 首次加入，modDay 设为当前游戏日={}", player.getScoreboardName(), currentWorldDay);
        } else if (event.getEntity() instanceof Villager villager) {
            // 村民加载时：从调度表恢复数据，并把 modDay 对齐为当前游戏日（modDay = gameDay 铁律）
            VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                    VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
            VillagerScheduleData.ScheduleEntry sched = schedule.get(villager.getUUID());
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

        ZombietideConfigData data = ConfigManager.getConfigData(serverLevel);
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
