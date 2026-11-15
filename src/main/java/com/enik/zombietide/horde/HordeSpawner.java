package com.enik.zombietide.horde;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.VillagerScheduleData;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.network.ModMessages;
import com.enik.zombietide.sound.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.Random;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * 尸潮刷怪引擎：负责尸潮僵尸的分批生成、目标锁定、全局上限协调、血债结算与黎明清理。
 */
public class HordeSpawner {

    private static final Random RANDOM = new Random();

    // 尸潮僵尸标签键（用于识别属于哪场尸潮的僵尸）
    private static final String HORDE_TARGET_UUID_KEY = "zombietide_horde_target";
    private static final String HORDE_WAVE_ID_KEY = "zombietide_wave_id";
    private static final String HORDE_STATE_KEY = "zombietide_state"; // 0=active, 1=scattering, 2=despawned

    // 正在进行的尸潮：target UUID -> 剩余需刷怪数量
    private static final Map<UUID, Integer> activeHordes = new HashMap<>();
    // 已刷出数量
    private static final Map<UUID, Integer> spawnedCount = new HashMap<>();
    // 本波总数
    private static final Map<UUID, Integer> totalCount = new HashMap<>();
    // 批次计时器
    private static final Map<UUID, Integer> batchTimer = new HashMap<>();

    // 村民尸潮半径存储
    private static final Map<UUID, Double> villagerHordeRadii = new HashMap<>();

    // 四散状态僵尸：UUID -> despawn timer (tick)
    private static final Map<UUID, Integer> scatteringZombies = new ConcurrentHashMap<>();

    // 所有活跃的尸潮僵尸 UUID 集合（用于四散时快速遍历，避免全实体扫描）
    private static final Set<UUID> hordeZombieUuids = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 本次 JVM 进程内已触发/恢复过的尸潮目标。进程重启即清空，因此可用于判断
    // 恢复日志的原因：首次见到 = 进度来自存档（服务器重启），已见过 = 目标重新上线/加载。
    private static final Set<UUID> seenHordesThisSession = ConcurrentHashMap.newKeySet();

    // 夜前警告已发送的模组天数记录：target UUID -> 已警告的 modDay（避免重复触发）
    // 现用于清晨提醒防重复
    private static final Map<UUID, Integer> warnedNightBefore = new ConcurrentHashMap<>();

    // 已结算血债的游戏日（target UUID -> gameDay），防止同一天重复结算（四散完成/起火结算/黎明 onDawn 共用）

    // 待血债结算的目标（四散开始时加入，23460 起火时刻按实时存活数结算，然后移除）

    /**
     * 随机尸潮数量：对 min/max 做容错（配置可能被改成 min > max）。
     * 直接 {@code nextInt(max-min+1)} 在 max&lt;min 时会抛 IllegalArgumentException 导致服务端崩溃。
     */
    public static int rollHordeSize(int min, int max) {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        return lo + RANDOM.nextInt(hi - lo + 1);
    }

    /**
     * 启动一场尸潮。
     * @param level 服务器端世界
     * @param targetId 触发尸潮的目标 UUID
     * @param target 主目标实体
     * @param total 本波总僵尸数
     * @param config 配置
     * @param isVillager 是否为村民尸潮
     * @param radius 生成半径
     */
    public static void startHorde(ServerLevel level, UUID targetId, LivingEntity target, int total, ZombietideConfig config, boolean isVillager, double radius) {
        // 防御：若已有未刷完的批次，覆盖会丢弃前一波剩余。调用方（trigger 命令）已拦截，
        // 这里保留 warn 以便其它调用路径出问题时能从日志发现。
        int previous = getActiveRemaining(targetId);
        if (previous > 0) {
            EnikZombietide.LOGGER.warn("尸潮 {} 仍在生成中（剩余 {} 只），本次 startHorde 将覆盖并丢弃该进度", targetId, previous);
        }
        seenHordesThisSession.add(targetId);
        activeHordes.put(targetId, total);
        spawnedCount.put(targetId, 0);
        totalCount.put(targetId, total);
        // 保护：第1批延迟 batchTick 才生成，避免同一 tick 瞬间全部刷完
        batchTimer.put(targetId, Math.max(1, config.batchTick()));
        // 存储是否为村民尸潮和半径信息（用于后续刷怪）
        if (isVillager) {
            villagerHordeRadii.put(targetId, radius);
        } else {
            villagerHordeRadii.remove(targetId);
        }

        // 持久化剩余待刷数：服务器重启后由 update() 的恢复逻辑继续刷
        com.enik.zombietide.config.HordeProgressData.get(level).setRemaining(targetId, total, isVillager);

        EnikZombietide.LOGGER.info("尸潮触发：目标 {}，数量 {}，类型 {}", targetId, total, isVillager ? "村民" : "玩家");

        // 根据目标所在生物群系预测僵尸变种分布
        String variantInfo = predictVariantDistribution(level, target.blockPosition());

        // 通知目标玩家尸潮已开始（仅一次，包含详细信息：触发者、UUID、数量、半径、类型、变种）。
        net.minecraft.network.chat.Component targetUuidComp = net.minecraft.network.chat.Component.literal(targetId.toString())
                .withStyle(net.minecraft.network.chat.Style.EMPTY
                        .withColor(net.minecraft.ChatFormatting.AQUA)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, targetId.toString()))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                net.minecraft.network.chat.Component.literal("点击填入 UUID"))));
        net.minecraft.network.chat.Component hordeMsg = net.minecraft.network.chat.Component.literal("[尸潮已触发] ")
                .append(net.minecraft.network.chat.Component.literal("触发者=")
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)))
                .append(targetUuidComp)
                .append(net.minecraft.network.chat.Component.literal(" 类型=")
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)))
                .append(net.minecraft.network.chat.Component.literal(isVillager ? "村民" : "玩家")
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(isVillager ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.YELLOW)))
                .append(net.minecraft.network.chat.Component.literal(" 数量=" + total)
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)))
                .append(net.minecraft.network.chat.Component.literal(" 半径=" + (int) radius)
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)))
                .append(net.minecraft.network.chat.Component.literal(" 变种=" + variantInfo)
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.LIGHT_PURPLE)));

        if (target instanceof ServerPlayer player) {
            player.sendSystemMessage(hordeMsg);
        } else if (target instanceof net.minecraft.world.entity.npc.Villager villager) {
            // 村民尸潮：发送给村民周围 villagerHordeDisplayRange 范围内的玩家（与HUD村民信息显示范围共用）
            double range = config.villagerHordeDisplayRange();
            for (ServerPlayer p : level.getPlayers(p2 -> p2.distanceToSqr(villager) <= range * range)) {
                p.sendSystemMessage(hordeMsg);
            }
        }
    }

    /** 兼容旧调用：默认玩家尸潮，使用配置中的 playerRadius */
    public static void startHorde(ServerLevel level, UUID targetId, LivingEntity target, int total, ZombietideConfig config) {
        startHorde(level, targetId, target, total, config, false, config.playerRadius());
    }

    /** 每 tick 更新尸潮刷怪状态（分批生成）+ 更新四散僵尸计时器 */
    public static void update(ServerLevel level, ZombietideConfig config) {
        // 【重启恢复】按 HordeProgressData 重建被重启清空的刷怪状态。
        // 校验目标仍处于当天波次中才恢复；校验不过（波次已结束/跨天）则 remove 自愈。
        var progress = com.enik.zombietide.config.HordeProgressData.get(level);
        if (!progress.getAll().isEmpty()) {
            long today = level.getDayTime() / 24000L;
            for (var it = progress.getAll().entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                UUID targetId = e.getKey();
                var entry = e.getValue();
                if (activeHordes.containsKey(targetId)) continue;
                if (entry.remaining() <= 0) {
                    it.remove(); progress.setDirty(); continue;
                }
                boolean stillActive;
                if (entry.villager()) {
                    var sched = level.getDataStorage().computeIfAbsent(
                            com.enik.zombietide.config.VillagerScheduleData.FACTORY,
                            com.enik.zombietide.config.VillagerScheduleData.ID).getScheduleOrNull(targetId);
                    stillActive = sched != null && sched.waveActive() && sched.lastTriggerDay() == (int) today;
                } else {
                    var player = level.getServer().getPlayerList().getPlayer(targetId);
                    if (player == null) continue;   // 离线玩家：保留进度，等上线后再恢复
                    var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                    stillActive = data.waveActive() && data.lastTriggerDay() == (int) today;
                }
                if (!stillActive) {
                    it.remove(); progress.setDirty(); continue;
                }
                // 目标实体未加载 / 不在主世界：保留进度，等实体加载后再恢复。
                // 否则 spawnZombie 找不到目标会「生成失败仍递减 remaining」，把剩余数量空刷掉。
                // 用 findTarget 而非裸 getEntity：ServerLevel 的 UUID 索引对已加载村民不可靠，
                // 裸查会把「已加载的村民」误判为「未加载」，导致村民尸潮重启后永不恢复。
                if (findTarget(level, targetId) == null) continue;
                activeHordes.put(targetId, entry.remaining());
                spawnedCount.put(targetId, 0);
                totalCount.put(targetId, entry.remaining());
                batchTimer.put(targetId, Math.max(1, config.batchTick()));
                if (entry.villager()) {
                    villagerHordeRadii.put(targetId, (double) config.villagerRadius());
                }
                // add() 返回 true 表示本次进程首次见到该目标 -> 进度来自存档（服务器重启）；
                // 返回 false 表示本进程内已触发过 -> 只是目标重新上线 / 重新加载，并非重启。
                boolean firstSeen = seenHordesThisSession.add(targetId);
                String reason = firstSeen ? "服务器重启" : "目标重新上线/加载";
                EnikZombietide.LOGGER.info("尸潮进度恢复（{}）：目标 {} 剩余 {} 只继续生成（类型 {}）",
                        reason, targetId, entry.remaining(), entry.villager() ? "村民" : "玩家");
            }
        }

        if (activeHordes.isEmpty() && scatteringZombies.isEmpty()) return;

        // 1. 更新正在进行的尸潮刷怪
        if (!activeHordes.isEmpty()) {
            java.util.List<UUID> targets = new java.util.ArrayList<>(activeHordes.keySet());

            for (UUID targetId : targets) {
                Integer remaining = activeHordes.get(targetId);
                if (remaining == null || remaining <= 0) {
                    // 所有僵尸已生成完毕，但尸潮仍在进行中（僵尸存活追逐中），
                    // waveActive 仅在黎明四散/结算时重置，不在此处重置
                    activeHordes.remove(targetId);
                    spawnedCount.remove(targetId);
                    totalCount.remove(targetId);
                    batchTimer.remove(targetId);
                    continue;
                }

                // 【兜底】已进入四散窗口：本波不再生成。
                // scatterZombies 每日只跑一次，若它已执行过，本函数不应再产出新僵尸
                // （新僵尸不会被四散清除，会活到白天并被次日黎明二次计入血债）。
                long phaseNow = level.getDayTime() % 24000L;
                if (phaseNow >= 23460L - config.despawnSeconds() * 20L) {
                    activeHordes.remove(targetId);
                    spawnedCount.remove(targetId);
                    totalCount.remove(targetId);
                    batchTimer.remove(targetId);
                    com.enik.zombietide.config.HordeProgressData.get(level).remove(targetId);
                    EnikZombietide.LOGGER.info("尸潮 {} 处于四散窗口，停止生成并作废剩余批次", targetId);
                    continue;
                }

                // 批次计时
                Integer timer = batchTimer.get(targetId);
                if (timer != null && timer > 0) {
                    batchTimer.put(targetId, timer - 1);
                    continue;
                }

                // 根据尸潮类型决定本批刷多少
                boolean isVillager = villagerHordeRadii.containsKey(targetId);
                int batchSize = isVillager ? config.villagerBatchSize() : config.batchSize();
                int toSpawn = Math.min(batchSize, remaining);

                // 全局上限协调：当前已存活的尸潮僵尸数
                int currentHordeZombies = countHordeZombies(level);
                int availableSlots = Math.max(0, config.globalCap() - currentHordeZombies);
                if (availableSlots == 0) {
                    // 已达上限，挂起本批次，等待僵尸死亡释放名额
                    EnikZombietide.LOGGER.debug("尸潮 {} 已达全局上限 {}，挂起本批次", targetId, config.globalCap());
                    batchTimer.put(targetId, config.batchTick());
                    continue;
                }
                toSpawn = Math.min(toSpawn, availableSlots);

                EnikZombietide.LOGGER.debug("尸潮 {} 开始生成批次：目标生成={}, 剩余={}, 当前存活尸潮僵尸={}, 全局上限={}", 
                    targetId, toSpawn, remaining, currentHordeZombies, config.globalCap());

                // 刷出僵尸 - 只有成功生成才计数
                int actuallySpawned = 0;
                for (int i = 0; i < toSpawn; i++) {
                    if (spawnZombie(level, targetId, config, isVillager)) {
                        actuallySpawned++;
                    }
                }

                if (actuallySpawned > 0) {
                    spawnedCount.put(targetId, spawnedCount.get(targetId) + actuallySpawned);
                    activeHordes.put(targetId, remaining - actuallySpawned);
                    EnikZombietide.LOGGER.info("尸潮 {} 本批次成功生成 {} 只，剩余 {}", targetId, actuallySpawned, remaining - actuallySpawned);
                } else {
                    // 生成完全失败：仍需递减 remaining，避免尸潮永远重试
                    activeHordes.put(targetId, remaining - toSpawn);
                    EnikZombietide.LOGGER.warn("尸潮 {} 本批次生成失败（{} 只），剩余已递减至 {}，跳过本批次", targetId, toSpawn, remaining - toSpawn);
                }
                // 同步持久化进度（<=0 时自动 remove）
                var progressData = com.enik.zombietide.config.HordeProgressData.get(level);
                Integer newRemaining = activeHordes.get(targetId);
                progressData.setRemaining(targetId, newRemaining == null ? 0 : newRemaining, isVillager);

                // 设置下一批次计时（保护：batchTick 不能为0，否则会瞬间全部生成）
                batchTimer.put(targetId, Math.max(1, config.batchTick()));
            }
        }

        // 2. 四散僵尸不再在此逐 tick 倒计时；改由 settleDebtAtBurnTime 在 23460 起火时刻一次性清除
        // 这里仅保留空块以保持结构，实际清除逻辑移至 settleDebtAtBurnTime
        if (!scatteringZombies.isEmpty()) {
            // 故意留空：四散僵尸在 23460 统一清除，避免各自倒计时不同步
        }
    }

    /** 在目标背后/视线外刷出一只僵尸，返回是否成功。绝不因找不到刷点而放弃。 */
    private static boolean spawnZombie(ServerLevel level, UUID targetId, ZombietideConfig config, boolean isVillager) {
        net.minecraft.world.entity.Entity target = findTarget(level, targetId);
        if (target == null || !target.isAlive()) {
            EnikZombietide.LOGGER.warn("尸潮刷怪失败：目标 {} 不存在或已死亡，无法生成僵尸", targetId);
            return false;
        }

        // 【设计变更】按生物群系选择僵尸类型，并应用原版自然生成逻辑（含困难模式装备）
        Mob mob;
        if (config.spawnByBiome()) {
            mob = createBiomeZombie(level, target.blockPosition());
            if (mob == null) {
                // 非主世界不生成尸潮
                return false;
            }
        } else {
            mob = new Zombie(EntityType.ZOMBIE, level);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(target.blockPosition()), MobSpawnType.NATURAL, null);
        }

        // 玩家尸潮半径直接取配置；villagerHordeRadii 只保存村民尸潮的临时覆盖值
        double radius = isVillager ? villagerHordeRadii.getOrDefault(targetId, (double) config.villagerRadius())
                                   : config.playerRadius();
        // findSpawnPos 已保证绝不返回 null：优先背后，兜底任意安全点，最终兜底目标脚下地表
        BlockPos spawnPos = findSpawnPos(level, target, radius, config);

        // 僵尸高 2 格，需要生成位置及上方均为空气/可替换
        BlockPos feetPos = spawnPos.above();      // 僵尸脚部位置（地表方块上方 1 格）
        BlockPos headPos = feetPos.above();       // 僵尸头部位置（地表方块上方 2 格）

        if (!level.getBlockState(feetPos).isAir() || !level.getBlockState(headPos).canBeReplaced()) {
            EnikZombietide.LOGGER.warn("尸潮刷怪失败：target={} 位置={} 生成空间不足（脚部={} 头部={}），放弃本次生成", 
                targetId, spawnPos, level.getBlockState(feetPos), level.getBlockState(headPos));
            return false;
        }

        mob.moveTo(feetPos.getX() + 0.5, feetPos.getY(), feetPos.getZ() + 0.5, 0, 0);

        // 标记该僵尸属于这场尸潮（用于黎明清理与血债结算）
        CompoundTag tag = mob.getPersistentData();
        tag.putUUID(HORDE_TARGET_UUID_KEY, targetId);
        // 波次 ID：用 totalCount 作为简单的波次标识
        tag.putInt(HORDE_WAVE_ID_KEY, totalCount.getOrDefault(targetId, 0));
        // 状态：0 = 活跃尸潮中
        tag.putInt(HORDE_STATE_KEY, 0);

        // 注入尸潮僵尸行为：主目标锁定 Goal + 永不消失（破门功能已移除）
        HordeZombieBehavior.apply(mob, targetId, config);

        boolean added = level.addFreshEntity(mob);
        if (!added) {
            EnikZombietide.LOGGER.warn("尸潮刷怪失败：target={} 位置={} addFreshEntity 返回 false，僵尸未加入世界", targetId, spawnPos);
            return false;
        }
        hordeZombieUuids.add(mob.getUUID());

        // 刷怪提醒：记录日志（不再每只僵尸发聊天消息，避免大波次刷屏；波次详情已在 startHorde() 中一次性发送）
        EnikZombietide.LOGGER.debug("尸潮僵尸生成：目标={} 位置={} 维度={} 半径={}", targetId, spawnPos, level.dimension().location(), radius);
        
        return true;
    }

    /**
     * 黎明结算：遍历所有尸潮僵尸，统计存活数并累加到目标的血债（仅玩家），重置 waveActive。
     * 全灭清零：若某波一只不剩，该目标血债清零，waveActive 重置为 false。
     * <p>
     * 【BUG9 修复】不再仅依赖世界中的 Zombie 实体来收集尸潮目标。
     * 如果某场尸潮的所有僵尸都被杀死，世界中就找不到对应 Zombie，
     * allHordeTargets 就会为空，导致 waveActive 永远不会被重置为 false，
     * debug info 始终显示“尸潮进行中”。
     * 修复后：先遍历所有在线玩家和村民，收集所有 waveActive=true 的目标，
     * 保证黎明时无论僵尸是否存活，waveActive 都会被重置。
     * 【R43-009 high】同游戏日内多次夜→晨跳变：移除 lastDawnDay 限制，每次调用都结算血债。
     * 调用方需自行判断是否需要防重复（如常规黎明窗口使用 lastDawnDay）。
     * 注意：不在这里做四散动作。四散动作由 scatterZombies() 在日出前 despawnSeconds 秒触发，
     * 避免僵尸被太阳烧死后才四散。
     * @param level 服务端世界
     * @param config 配置
     */
    public static void onDawn(ServerLevel level, ZombietideConfig config) {
        // 收集每个目标的存活尸潮僵尸数（仅统计未进入四散的活尸潮僵尸）
        Map<UUID, Integer> survivorCount = new HashMap<>();
        // 收集所有尸潮目标 UUID：既包括世界中的尸潮僵尸，也包括附件中 waveActive=true 的玩家/村民
        Set<UUID> allHordeTargets = new HashSet<>();

        // 1. 从世界中的尸潮僵尸收集目标（用于血债结算和幸存者统计）
        // 尸潮实体包括：Zombie、ZombieVillager、Husk、Drowned、Stray（均带有 horde 标记）
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof net.minecraft.world.entity.monster.Zombie ||
                  entity instanceof net.minecraft.world.entity.monster.ZombieVillager ||
                  entity instanceof net.minecraft.world.entity.monster.Husk ||
                  entity instanceof net.minecraft.world.entity.monster.Drowned ||
                  entity instanceof net.minecraft.world.entity.monster.Stray)) continue;
            CompoundTag tag = entity.getPersistentData();
            if (!tag.hasUUID(HORDE_TARGET_UUID_KEY)) continue;

            UUID targetId = tag.getUUID(HORDE_TARGET_UUID_KEY);
            allHordeTargets.add(targetId);

            if (!entity.isAlive()) continue;

            // 已经进入四散状态的僵尸，跳过，避免重复发送四散消息
            if (tag.getInt(HORDE_STATE_KEY) == 1) continue;

            survivorCount.merge(targetId, 1, Integer::sum);
        }

        // 2. 【BUG9 修复】遍历所有在线玩家，收集 waveActive=true 的玩家目标
        for (ServerPlayer player : level.getPlayers(p -> true)) {
            if (player.level().dimension() != Level.OVERWORLD) continue;
            var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
            if (data.waveActive()) {
                allHordeTargets.add(player.getUUID());
            }
        }

        // 3. 【BUG9 修复】遍历所有村民，收集 waveActive=true 的村民目标
        java.util.List<net.minecraft.world.entity.npc.Villager> villagers = new java.util.ArrayList<>();
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.npc.Villager v) {
                villagers.add(v);
            }
        }
        for (net.minecraft.world.entity.npc.Villager villager : villagers) {
            var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
            if (vData.waveActive()) {
                allHordeTargets.add(villager.getUUID());
            }
        }

        // 累加血债到世界级存储（不再依赖实体是否在线），并处理全灭清零
        long today = level.getDayTime() / 24000L;
        com.enik.zombietide.config.PlayerDebtData debtData =
                com.enik.zombietide.config.PlayerDebtData.get(level);
        // 村民尸潮：按设计不累加血债，只重置 waveActive 并同步 HUD（血债表里没有村民，isKnown=false）
        for (UUID targetId : allHordeTargets) {
            if (debtData.isKnown(targetId)) continue;
            if (!(findTarget(level, targetId) instanceof net.minecraft.world.entity.npc.Villager villager)) continue;
            int count = survivorCount.getOrDefault(targetId, 0);
            var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
            villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                    new com.enik.zombietide.attachments.VillagerHordeData(
                            vData.modDay(), vData.lastTriggerDay(), vData.waveCount(), false
                    ));
            EnikZombietide.LOGGER.debug("村民 {} 尸潮结束，存活僵尸 {}，waveActive 已重置为 false", targetId, count);
            // 【BUG3 修复】waveActive 重置后立即同步村民模组天数给附近玩家，HUD 实时反映状态变化
            for (var entity : level.getEntities().getAll()) {
                if (entity instanceof ServerPlayer p && p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager),
                            vData.modDay(),
                            villager.getX(), villager.getY(), villager.getZ(), false,
                            HordeTimer.isHordeDay(villager.getUUID(), vData.modDay(), vData.lastTriggerDay(), config, true));
                }
            }
        }

        // 玩家血债结算
        for (UUID targetId : allHordeTargets) {
            // 血债只记玩家：离线玩家实体为 null，无法再用 instanceof 判断，改以「是否注册过」为准
            if (!debtData.isKnown(targetId)) continue;
            int count = survivorCount.getOrDefault(targetId, 0);
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);

            // 【去重】同一天若已通过四散结束结算过，黎明结算跳过，防止双重计债
            if (debtData.isSettled(targetId, today)) {
                // 已结算过：只重置 waveActive，不再加债
                if (player != null) {
                    var data0 = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                    player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                            new com.enik.zombietide.attachments.PlayerHordeData(
                                    data0.modDay(), data0.lastTriggerDay(), data0.waveCount(), false));
                }
                continue;
            }
            debtData.markSettled(targetId, today);
            // 血债模式：累积制（默认）
            String debtMode = config.debtMode();
            int newDebt;
            if ("last_only".equals(debtMode)) {
                // 只记上波
                newDebt = Math.min(config.debtCap(), count);
            } else if ("none".equals(debtMode)) {
                // 不结转
                newDebt = 0;
            } else {
                // 累积制（默认）
                newDebt = Math.min(config.debtCap(), debtData.getDebt(targetId) + count);
            }
            // 全灭清零：某波一只不剩 → 血债清零
            if (count == 0) {
                newDebt = 0;
            }
            debtData.setDebt(targetId, newDebt);

            if (player == null) {
                EnikZombietide.LOGGER.info("玩家 {} 离线，黎明血债已写入世界数据：+{}，当前血债 {}（模式：{}）",
                        targetId, count, newDebt, debtMode);
                continue;
            }
            var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
            player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                    new com.enik.zombietide.attachments.PlayerHordeData(
                            data.modDay(), data.lastTriggerDay(), data.waveCount(), false
                    ));
            EnikZombietide.LOGGER.info("玩家 {} 血债结算：+{}，当前血债 {}（模式：{}）", player.getScoreboardName(), count, newDebt, debtMode);
                // 【关键修复】发送血债结算聊天提示给全服非生存/非冒险模式玩家
                net.minecraft.network.chat.Component targetUuidComp = net.minecraft.network.chat.Component.literal(targetId.toString())
                        .withStyle(net.minecraft.network.chat.Style.EMPTY
                                .withColor(net.minecraft.ChatFormatting.AQUA)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                        net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, targetId.toString()))
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                        net.minecraft.network.chat.Component.literal("点击填入 UUID"))));
                net.minecraft.network.chat.Component debtMsg = net.minecraft.network.chat.Component.literal("[尸潮] 血债结算：")
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.GOLD))
                        .append(net.minecraft.network.chat.Component.literal(" 目标=")
                                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)))
                        .append(targetUuidComp)
                        .append(net.minecraft.network.chat.Component.literal(" 本波幸存 " + count + " 只")
                                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)))
                        .append(net.minecraft.network.chat.Component.literal(" 当前血债 " + newDebt)
                                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.GOLD)));
                for (ServerPlayer p : level.getPlayers(p2 -> p2.gameMode.getGameModeForPlayer() != GameType.SURVIVAL && p2.gameMode.getGameModeForPlayer() != GameType.ADVENTURE)) {
                    p.sendSystemMessage(debtMsg);
                }
        }

        // 【修复】黎明兜底：调度表是权威数据源，必须无条件把 waveActive 写回 false。
        // 之前只在「实体已消失/跨维度」时重置调度表，已加载村民的调度条目会一直是 true；
        // 区块重新加载时 onEntityJoinLevel 会把 true 再抄回实体附件 → 白天仍显示「尸潮进行中」。
        com.enik.zombietide.config.VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                com.enik.zombietide.config.VillagerScheduleData.FACTORY, com.enik.zombietide.config.VillagerScheduleData.ID);
        for (var entry : schedule.getAllSchedules().entrySet()) {
            com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry sched = entry.getValue();
            if (sched.waveActive()) {
                schedule.put(entry.getKey(), new com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry(
                        sched.modDay(), sched.lastTriggerDay(), sched.waveCount(), false
                ));
                EnikZombietide.LOGGER.debug("村民 {} 黎明重置调度表 waveActive=false", entry.getKey());
            }
        }
        
        // 【R43-013】BypassWindow 路径去重：清理已处理过的目标，避免重复触发
        // 在黎明结算后，清除已处理目标的 bypass 标记（如果有的话）
        // 实际上 onDawn 本身会重置 waveActive，所以 bypass 逻辑在 checkPlayerHordesBypassWindow 中
        // 通过 modDay == lastTriggerDay 检查已足够防重复
    }

    /**
     * 尸潮僵尸四散（日出前 despawnSeconds 秒开始，精确到 23460 起火时刻一次性清除）。
     * 所有四散僵尸共享同一个截止时间 = 23460，避免每只僵尸各自倒计时导致不同步。
     *
     * @param level  服务端世界
     * @param config 配置
     * @param dayTime 世界时间原始值（**未取模**，方法内部自行 `% 24000`）
     */
    public static void scatterZombies(ServerLevel level, ZombietideConfig config, long dayTime) {
        long dayPhase = dayTime % 24000;
        // 触发时机：日出前 despawnSeconds 秒（23460 刻开始燃烧），窗口延伸到日结束（24000）
        long scatterStartTick = 23460L - config.despawnSeconds() * 20L;
        if (dayPhase < scatterStartTick) return;

        EnikZombietide.LOGGER.info("尸潮僵尸开始四散：日出前 {} 秒，太阳即将直射", config.despawnSeconds());

        // 【停止刷怪】四散开始后不得再生成新批次。scatterZombies 每游戏日只执行一次
        // （HordeTimer 的 lastScatterDay 去重），错过本次快照的僵尸会在四散之后才出现：
        //   1) state=0，不在 scatteringZombies 里 —— 23460 起火时刻不会被清除；
        //   2) 却被 settleDebtAtBurnTime 的存活统计计入血债；
        //   3) 若活到次日黎明，onDawn 会按新的一天再结算一次（cumulative 模式重复计债）；
        //   4) 白天继续锁定追击玩家。
        // 因此这里直接作废所有进行中波次的剩余量。
        if (!activeHordes.isEmpty()) {
            var progressData = com.enik.zombietide.config.HordeProgressData.get(level);
            for (UUID targetId : new java.util.ArrayList<>(activeHordes.keySet())) {
                int left = activeHordes.getOrDefault(targetId, 0);
                if (left > 0) {
                    EnikZombietide.LOGGER.info("尸潮 {} 四散开始，作废剩余 {} 只未生成批次（不再刷出）", targetId, left);
                }
                clearInternalState(level, targetId);
                progressData.remove(targetId);
            }
        }

        // 仅遍历尸潮僵尸 UUID 集合，避免每 tick 全实体扫描（O(P×V) → O(尸潮僵尸数)）
        java.util.List<UUID> toProcess = new java.util.ArrayList<>(hordeZombieUuids);
        java.util.Set<UUID> scatterTargets = new java.util.HashSet<>();
        // 记录每个目标进入四散的僵尸数量，用于汇总提醒（避免每只僵尸一条消息刷屏）
        java.util.Map<UUID, Integer> scatterCountByTarget = new java.util.HashMap<>();
        for (UUID zombieUuid : toProcess) {
            var entity = level.getEntity(zombieUuid);
            // 尸潮实体包括：Zombie、ZombieVillager、Husk、Drowned、Stray（均带有 horde 标记）
            if (!(entity instanceof net.minecraft.world.entity.monster.Zombie ||
                  entity instanceof net.minecraft.world.entity.monster.ZombieVillager ||
                  entity instanceof net.minecraft.world.entity.monster.Husk ||
                  entity instanceof net.minecraft.world.entity.monster.Drowned ||
                  entity instanceof net.minecraft.world.entity.monster.Stray)) continue;
            if (!entity.isAlive()) continue;

            CompoundTag tag = entity.getPersistentData();
            if (!tag.hasUUID(HORDE_TARGET_UUID_KEY)) continue;
            // 已经进入四散状态的僵尸，跳过
            if (tag.getInt(HORDE_STATE_KEY) == 1) continue;

            UUID targetId = tag.getUUID(HORDE_TARGET_UUID_KEY);
            scatterTargets.add(targetId);
            scatterCountByTarget.merge(targetId, 1, Integer::sum);
            if (entity instanceof Mob mob) {
                mob.setTarget(null); // 清空攻击目标，停止攻击
            }
            tag.putInt(HORDE_STATE_KEY, 1); // 标记为四散状态

            // 关键修改：四散倒计时精确到 23460（起火时刻），而不是配置的固定秒数
            // 这样所有四散僵尸在同一 tick (23460) 被一次性清除
            int exactDespawnTicks = (int)(23460 - dayPhase);
            if (exactDespawnTicks < 1) exactDespawnTicks = 1;
            scatteringZombies.put(entity.getUUID(), exactDespawnTicks);

            // 直接设置四散逃跑导航（背向主目标远距离移动）。
            // 注意：state==1 时 HordeZombieGoal.canUse() 仍返回 true，其 tick() 会用 8 方向
            // 评分选点（躲阳光/背离目标/规避威胁）覆盖本处导航；FleeGoal（优先级 0）同时生效，
            // 最终以 HordeZombieGoal 的评分选点为准。
            LivingEntity mainTarget = null;
            var targetEntity = findTarget(level, targetId);
            if (targetEntity instanceof LivingEntity le) mainTarget = le;
            if (mainTarget != null && entity instanceof Mob mob) {
                double fleeX = mob.getX() + (mob.getX() - mainTarget.getX()) * 4.0;
                double fleeZ = mob.getZ() + (mob.getZ() - mainTarget.getZ()) * 4.0;
                mob.getNavigation().moveTo(fleeX, mob.getY(), fleeZ, 1.2);
                // 加一个高优先级逃跑 Goal，持续运行直到 state!=1
                mob.goalSelector.addGoal(0, new com.enik.zombietide.horde.FleeGoal(mob, mainTarget));
            }

            EnikZombietide.LOGGER.debug("尸潮实体 {} 进入四散状态，目标={} {} 秒后移除", entity.getUUID(), targetId, config.despawnSeconds());
        }

        // 四散开始：将目标标记为待血债结算（在 23460 起火时刻按实时存活数结算）
        com.enik.zombietide.config.PlayerDebtData debtData =
                com.enik.zombietide.config.PlayerDebtData.get(level);
        for (UUID targetId : scatterTargets) {
            if (debtData.isKnown(targetId)) debtData.markPending(targetId);
        }

        // 向目标玩家（或村民周围玩家）发送一条汇总四散提醒，避免每只僵尸一条消息刷屏
        notifyScatterTargetsWithCount(level, config, scatterTargets, scatterCountByTarget);

        // 定制音频：四散音效只发给玩家尸潮触发者（村民尸潮不播放）
        if (config.warningEnabled() && config.scatterSound()) {
            for (UUID targetId : scatterTargets) {
                var target = findTarget(level, targetId);
                if (target instanceof ServerPlayer player) {
                    ModSounds.playToPlayer(player, ModSounds.HORDE_SCATTER, SoundSource.HOSTILE, 0.8F, 1.0F);
                }
            }
        }

        // 【t37 修复】四散开始时（本方法即 23260 的调用点）重置目标 waveActive=false，避免 debug info 始终显示“进行中”
        for (UUID targetId : scatterTargets) {
            var target = findTarget(level, targetId);
            if (target instanceof ServerPlayer player) {
                var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                if (data.waveActive()) {
                    player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                            new com.enik.zombietide.attachments.PlayerHordeData(
                                    data.modDay(), data.lastTriggerDay(), data.waveCount(), false
                            ));
                    EnikZombietide.LOGGER.debug("玩家 {} waveActive 已重置为 false（四散完成）", targetId);
                }
            } else if (target instanceof net.minecraft.world.entity.npc.Villager villager) {
                var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                if (vData.waveActive()) {
                    villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                            new com.enik.zombietide.attachments.VillagerHordeData(
                                    vData.modDay(), vData.lastTriggerDay(), vData.waveCount(), false
                            ));
                    EnikZombietide.LOGGER.debug("村民 {} waveActive 已重置为 false（四散完成）", targetId);
                }
            }
        }

        // 【Bug2 修复】兜底：activeHordes 中有但 scatterTargets 没有的目标（全灭/实体已死），也要重置 waveActive
        // 这解决了：僵尸全死后没有四散目标，导致 waveActive 永不重置
        java.util.Set<UUID> allHordeTargets = new java.util.HashSet<>(activeHordes.keySet());
        for (UUID targetId : allHordeTargets) {
            if (scatterTargets.contains(targetId)) continue; // 已处理
            var target = findTarget(level, targetId);
            if (target instanceof ServerPlayer player) {
                var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                if (data.waveActive()) {
                    player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                            new com.enik.zombietide.attachments.PlayerHordeData(
                                    data.modDay(), data.lastTriggerDay(), data.waveCount(), false
                            ));
                    EnikZombietide.LOGGER.debug("玩家 {} 四散兜底重置 waveActive=false（全灭/无存活僵尸）", targetId);
                }
            } else if (target instanceof net.minecraft.world.entity.npc.Villager villager) {
                var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                if (vData.waveActive()) {
                    villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                            new com.enik.zombietide.attachments.VillagerHordeData(
                                    vData.modDay(), vData.lastTriggerDay(), vData.waveCount(), false
                            ));
                    EnikZombietide.LOGGER.debug("村民 {} 四散兜底重置 waveActive=false（全灭/无存活僵尸）", targetId);
                }
            }
            // 实体为 null（已死/卸载）也需要通过调度表/附件重置
            // 这里暂不处理村民实体为 null 的情况，依靠 onDawn 的调度表兜底
        }

        // 【关键修复】扫描 VillagerScheduleData，无条件把 waveActive 写回 false（权威数据源）。
        // 之前实体存在时只重置附件、不写调度表，导致调度表一直 true；
        // 区块重载时 onEntityJoinLevel 会把 true 抄回附件，白天仍显示「尸潮进行中」。
        VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID
        );
        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            var sched = entry.getValue();
            if (sched.waveActive()) {
                // 1) 权威调度表置 false
                schedule.put(villagerUuid, new com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry(
                        sched.modDay(), sched.lastTriggerDay(), sched.waveCount(), false
                ));
                // 2) 若实体已加载，同步重置附件
                var villagerEntity = findTarget(level, villagerUuid);
                if (villagerEntity instanceof net.minecraft.world.entity.npc.Villager villager) {
                    var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                    if (vData.waveActive()) {
                        villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                                new com.enik.zombietide.attachments.VillagerHordeData(
                                        vData.modDay(), vData.lastTriggerDay(), vData.waveCount(), false
                                ));
                    }
                }
                EnikZombietide.LOGGER.debug("村民 {} 四散阶段重置 waveActive=false（调度表+附件）", villagerUuid);
            }
        }
    }

    /** 起火时刻血债结算（dayPhase >= 23460，僵尸开始着火，按实时存活数结算血债） */
    public static void settleDebtAtBurnTime(ServerLevel level, ZombietideConfig config) {
        long today = level.getDayTime() / 24000L;
        com.enik.zombietide.config.PlayerDebtData debtData =
                com.enik.zombietide.config.PlayerDebtData.get(level);
        java.util.List<UUID> toSettle = debtData.drainPending();

        for (UUID targetId : toSettle) {
            // 血债只记玩家：离线玩家实体为 null，无法再用 instanceof 判断，改以「是否注册过」为准
            if (!debtData.isKnown(targetId)) continue;
            if (debtData.isSettled(targetId, today)) continue; // 今天已结算过
            debtData.markSettled(targetId, today);

            int survivorCount = countHordeZombiesByTarget(level, targetId);
            String debtMode = config.debtMode();
            int newDebt;
            if ("last_only".equals(debtMode)) {
                newDebt = Math.min(config.debtCap(), survivorCount);
            } else if ("none".equals(debtMode)) {
                newDebt = 0;
            } else {
                newDebt = Math.min(config.debtCap(), debtData.getDebt(targetId) + survivorCount);
            }
            if (survivorCount == 0) newDebt = 0; // 全灭清零
            debtData.setDebt(targetId, newDebt);

            ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);
            if (player == null) {
                EnikZombietide.LOGGER.info("玩家 {} 离线，起火时刻血债已写入世界数据：幸存 {}，当前血债 {}（模式：{}）",
                        targetId, survivorCount, newDebt, debtMode);
                continue;
            }

            var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
            player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                    new com.enik.zombietide.attachments.PlayerHordeData(
                            data.modDay(), data.lastTriggerDay(), data.waveCount(), false));
            EnikZombietide.LOGGER.info("玩家 {} 起火时刻血债结算：幸存 {}，当前血债 {}（模式：{}）",
                    player.getScoreboardName(), survivorCount, newDebt, debtMode);

            // 血债结算聊天提示（发给全服非生存/非冒险模式玩家）
            net.minecraft.network.chat.Component debtMsg = net.minecraft.network.chat.Component.literal("[尸潮] 血债结算：")
                    .append(net.minecraft.network.chat.Component.literal(player.getScoreboardName())
                            .withStyle(net.minecraft.ChatFormatting.YELLOW))
                    .append(net.minecraft.network.chat.Component.literal(" 幸存僵尸 "))
                    .append(net.minecraft.network.chat.Component.literal(String.valueOf(survivorCount))
                            .withStyle(survivorCount > 0 ? net.minecraft.ChatFormatting.RED : net.minecraft.ChatFormatting.GREEN))
                    .append(net.minecraft.network.chat.Component.literal(" 只，血债 +" + newDebt)
                            .withStyle(net.minecraft.ChatFormatting.RED));
            for (ServerPlayer p : level.getPlayers(pl -> pl.gameMode.getGameModeForPlayer() != GameType.SURVIVAL
                    && pl.gameMode.getGameModeForPlayer() != GameType.ADVENTURE)) {
                p.sendSystemMessage(debtMsg);
            }
        }

        // 【关键修复】在 23460 起火时刻一次性清除所有四散僵尸
        // 这样所有四散僵尸刚好跑 despawnSeconds 秒，到 23460 同步消失
        if (!scatteringZombies.isEmpty()) {
            java.util.Set<UUID> clearedTargets = new java.util.HashSet<>();
            for (var entry : scatteringZombies.entrySet()) {
                UUID zombieUuid = entry.getKey();
                hordeZombieUuids.remove(zombieUuid);
                var entity = level.getEntity(zombieUuid);
                if (entity != null && (entity instanceof Zombie ||
                        entity instanceof ZombieVillager ||
                        entity instanceof Husk ||
                        entity instanceof Drowned ||
                        entity instanceof Stray)) {
                    CompoundTag tag = entity.getPersistentData();
                    if (tag.hasUUID(HORDE_TARGET_UUID_KEY)) {
                        clearedTargets.add(tag.getUUID(HORDE_TARGET_UUID_KEY));
                    }
                    entity.discard();
                    EnikZombietide.LOGGER.debug("四散僵尸 {} 23460 起火时刻同步清除", zombieUuid);
                }
            }
            scatteringZombies.clear();

            // 清除完成后重置对应目标的 waveActive
            for (UUID targetId : clearedTargets) {
                if (level != null) com.enik.zombietide.config.PlayerDebtData.get(level).clearPending(targetId);
                resetTargetWaveActive(level, targetId);
            }
        }
    }

    /** 统计指定目标的存活尸潮僵尸数量（含四散状态，用于起火时刻血债结算） */
    private static int countHordeZombiesByTarget(ServerLevel level, UUID targetId) {
        int count = 0;
        for (UUID zombieUuid : hordeZombieUuids) {
            var entity = level.getEntity(zombieUuid);
            if (!(entity instanceof Zombie ||
                  entity instanceof ZombieVillager ||
                  entity instanceof Husk ||
                  entity instanceof Drowned ||
                  entity instanceof Stray)) continue;
            if (!entity.isAlive()) continue;
            CompoundTag tag = entity.getPersistentData();
            if (!tag.hasUUID(HORDE_TARGET_UUID_KEY)) continue;
            if (tag.getUUID(HORDE_TARGET_UUID_KEY).equals(targetId)) {
                // 起火时刻血债结算：四散状态的僵尸即为幸存者，全部计入
                count++;
            }
        }
        return count;
    }

    /**
     * 强制让所有尸潮僵尸进入四散状态（用于 /time set day 等时间跳变场景）。
     * 不受 dayTime 窗口限制，直接处理所有活跃尸潮僵尸。
     * 四散倒计时精确到 23460（起火时刻），与正常四散同步。
     * 【R43-012】清理 activeHordes、血债、lastTriggerDay，防止尸潮复活
     *
     * @param level  服务端世界
     * @param config 配置
     */
    public static void forceScatterAll(ServerLevel level, ZombietideConfig config) {
        long dayPhase = level.getDayTime() % 24000;
        // 精确到 23460 起火时刻
        int exactDespawnTicks = (int)(23460 - dayPhase);
        if (exactDespawnTicks < 1) exactDespawnTicks = 1;

        EnikZombietide.LOGGER.info("强制四散所有尸潮僵尸：检测到时间从夜间跳至白天，将在下一个 23460 起火时刻统一清除（约 {} tick）", exactDespawnTicks);

        // 记录每个目标进入四散的僵尸数量（用于正确的聊天提示）
        java.util.Map<UUID, Integer> scatterCountByTarget = new java.util.HashMap<>();

        // 遍历尸潮僵尸 UUID 集合，避免全实体扫描
        java.util.List<UUID> toProcess = new java.util.ArrayList<>(hordeZombieUuids);
        java.util.Set<UUID> scatterTargets = new java.util.HashSet<>();
        for (UUID zombieUuid : toProcess) {
            var entity = level.getEntity(zombieUuid);
            if (!(entity instanceof net.minecraft.world.entity.monster.Zombie ||
                  entity instanceof net.minecraft.world.entity.monster.ZombieVillager ||
                  entity instanceof net.minecraft.world.entity.monster.Husk ||
                  entity instanceof net.minecraft.world.entity.monster.Drowned ||
                  entity instanceof net.minecraft.world.entity.monster.Stray)) continue;
            CompoundTag tag = entity.getPersistentData();
            if (!tag.hasUUID(HORDE_TARGET_UUID_KEY)) continue;
            if (!entity.isAlive()) continue;
            // 已经进入四散状态的僵尸，跳过
            if (tag.getInt(HORDE_STATE_KEY) == 1) continue;

            UUID targetId = tag.getUUID(HORDE_TARGET_UUID_KEY);
            scatterTargets.add(targetId);
            if (entity instanceof Mob mob) {
                mob.setTarget(null); // 清空攻击目标，停止攻击
            }
            tag.putInt(HORDE_STATE_KEY, 1); // 标记为四散状态
            scatteringZombies.put(entity.getUUID(), exactDespawnTicks);

            // 直接设置四散逃跑导航（背向主目标远距离移动）
            LivingEntity mainTarget = null;
            var targetEntity = findTarget(level, targetId);
            if (targetEntity instanceof LivingEntity le) mainTarget = le;
            if (mainTarget != null && entity instanceof Mob mob) {
                double fleeX = mob.getX() + (mob.getX() - mainTarget.getX()) * 3.0;
                double fleeZ = mob.getZ() + (mob.getZ() - mainTarget.getZ()) * 3.0;
                mob.getNavigation().moveTo(fleeX, mob.getY(), fleeZ, 1.2);
                // 加一个高优先级逃跑 Goal，持续运行直到 state!=1
                mob.goalSelector.addGoal(0, new com.enik.zombietide.horde.FleeGoal(mob, mainTarget));
            }

            EnikZombietide.LOGGER.debug("尸潮僵尸 {} 强制四散，目标={} {} 秒后移除", entity.getUUID(), targetId, config.despawnSeconds());
            scatterCountByTarget.merge(targetId, 1, Integer::sum);
        }

        // 强制四散同样将目标标记为待血债结算（在 23460 起火时刻按实时存活数结算）
        com.enik.zombietide.config.PlayerDebtData debtData =
                com.enik.zombietide.config.PlayerDebtData.get(level);
        for (UUID targetId : scatterTargets) {
            if (debtData.isKnown(targetId)) debtData.markPending(targetId);
        }

        // 【R43-012】强制四散时清理 activeHordes、spawnedCount、totalCount、batchTimer
        // 防止时间跳变后尸潮残留数据导致"复活"
        // 同时重置目标的 waveActive、血债、lastTriggerDay
        java.util.Set<UUID> targetsToClean = new java.util.HashSet<>(scatterTargets);
        // 也要处理 activeHordes 中尚未生成完的目标
        targetsToClean.addAll(activeHordes.keySet());
        
        for (UUID targetId : targetsToClean) {
            var target = findTarget(level, targetId);
            if (target instanceof ServerPlayer player) {
                var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                if (data.waveActive()) {
                    // 【R43-012】清理血债和 lastTriggerDay，防止尸潮复活
                    player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                            new com.enik.zombietide.attachments.PlayerHordeData(
                                    data.modDay(), 0, data.waveCount(), false
                            ));
                    com.enik.zombietide.config.PlayerDebtData.get(level).setDebt(targetId, 0);
                    EnikZombietide.LOGGER.debug("玩家 {} 强制四散清理：waveActive=false, 血债=0, lastTriggerDay=0", targetId);
                }
                // 清理 activeHordes 相关状态
                activeHordes.remove(targetId);
                spawnedCount.remove(targetId);
                totalCount.remove(targetId);
                batchTimer.remove(targetId);
                villagerHordeRadii.remove(targetId);
            } else if (target instanceof net.minecraft.world.entity.npc.Villager villager) {
                var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                if (vData.waveActive()) {
                    villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                            new com.enik.zombietide.attachments.VillagerHordeData(
                                    vData.modDay(), 0, vData.waveCount(), false
                            ));
                    EnikZombietide.LOGGER.debug("村民 {} 强制四散清理：waveActive=false, lastTriggerDay=0", targetId);
                }
                // 清理 activeHordes 相关状态
                activeHordes.remove(targetId);
                spawnedCount.remove(targetId);
                totalCount.remove(targetId);
                batchTimer.remove(targetId);
                villagerHordeRadii.remove(targetId);
            }
        }
        
        // t37 块已删除（Issue #8）：上方 targetsToClean 循环已覆盖 scatterTargets 并全部置
        // waveActive=false，此处再判 waveActive 恒为 false，属死代码。

        // 【关键修复】扫描 VillagerScheduleData，无条件把 waveActive 写回 false（权威数据源）
        VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            var sched = entry.getValue();
            if (sched.waveActive()) {
                // 1) 权威调度表置 false
                schedule.put(villagerUuid, new com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry(
                        sched.modDay(), sched.lastTriggerDay(), sched.waveCount(), false
                ));
                // 2) 若实体已加载，同步重置附件
                var villagerEntity = findTarget(level, villagerUuid);
                if (villagerEntity instanceof net.minecraft.world.entity.npc.Villager villager) {
                    var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                    if (vData.waveActive()) {
                        villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                                new com.enik.zombietide.attachments.VillagerHordeData(
                                        vData.modDay(), vData.lastTriggerDay(), vData.waveCount(), false
                                ));
                    }
                }
                EnikZombietide.LOGGER.debug("村民 {} 强制四散重置 waveActive=false（调度表+附件）", villagerUuid);
            }
        }

        // 【关键修复】强制四散也要发送聊天通知（复用 scatterZombies 的逻辑）
        // 否则玩家完全不知道僵尸已经四散了
        notifyScatterTargetsWithCount(level, config, scatterTargets, scatterCountByTarget);
    }

    /**
     * 发送四散通知给目标玩家/村民周围玩家（带僵尸计数）
     * scatterZombies 调用此方法，传入真实散入四散的僵尸计数
     */
    private static void notifyScatterTargetsWithCount(ServerLevel level, ZombietideConfig config,
                                                      java.util.Set<UUID> scatterTargets,
                                                      java.util.Map<UUID, Integer> scatterCountByTarget) {
        // 构建通用消息体（每个目标不同的部分：目标UUID、类型、数量）
        for (UUID targetId : scatterTargets) {
            int scatterCount = scatterCountByTarget.getOrDefault(targetId, 1);
            var targetEntity = findTarget(level, targetId);
            if (targetEntity == null) continue;

            boolean isVillagerTarget = targetEntity instanceof net.minecraft.world.entity.npc.Villager;
            net.minecraft.network.chat.Component scatterUuid = net.minecraft.network.chat.Component.literal(targetId.toString())
                    .withStyle(net.minecraft.network.chat.Style.EMPTY
                            .withColor(net.minecraft.ChatFormatting.AQUA)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, targetId.toString()))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                    net.minecraft.network.chat.Component.literal("点击填入 UUID"))));
            net.minecraft.network.chat.Component scatterMsg = net.minecraft.network.chat.Component.literal("[尸潮] 黎明四散！")
                    .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED))
                    .append(net.minecraft.network.chat.Component.literal(" 目标="))
                    .append(scatterUuid)
                    .append(net.minecraft.network.chat.Component.literal(" 类型=")
                            .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)))
                    .append(net.minecraft.network.chat.Component.literal(isVillagerTarget ? "村民" : "玩家")
                            .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(isVillagerTarget ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.YELLOW)))
                    .append(net.minecraft.network.chat.Component.literal(" 僵尸共 " + scatterCount + " 只")
                            .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.RED)));

            // 【需求变更】发送给全服非生存/非冒险模式玩家（创造、旁观等）
            for (ServerPlayer p : level.getPlayers(p2 -> p2.gameMode.getGameModeForPlayer() != GameType.SURVIVAL && p2.gameMode.getGameModeForPlayer() != GameType.ADVENTURE)) {
                p.sendSystemMessage(scatterMsg);
            }
        }
    }

    /**
     * 【R43-015】跳变跨过黄昏时发送清晨提醒（为下一个模组天数）
     * 当从黄昏前（<12000）跳到黄昏后（>14000）时调用
     */
    public static void checkNightBeforeWarningForNextModDay(ServerLevel level, ZombietideConfig config) {
        // 检查玩家：下一个模组天数（modDay+1）是否为尸潮日
        if (config.playerHordeEnabled() && config.playerIntervalDays() > 0) {
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                if (player.level().dimension() != Level.OVERWORLD) continue;

                var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                int modDay = data.modDay();
                int nextModDay = modDay + 1;
                // 【修复 off-by-one】与触发判定保持一致：尸潮日为 nextModDay % interval == interval - 1
                if (nextModDay > 0 && nextModDay % config.playerIntervalDays() == config.playerIntervalDays() - 1 && nextModDay != data.lastTriggerDay()) {
                    // 防重复：同一个模组天数只警告一次
                    Integer warned = warnedNightBefore.get(player.getUUID());
                    if (warned != null && warned == modDay) continue;
                    warnedNightBefore.put(player.getUUID(), modDay);
                    // 下一个模组天数是尸潮日，发送清晨提醒（跨天跳变场景）
                    HordeWarning.warnMorningHorde(level, player, config, nextModDay);
                }
            }
        }

        // 村民尸潮清晨提醒已弃用
    }

    /**
     * 【R43-011】missedDusk 补偿前发送清晨提醒（玩家）
     */
    public static void sendMorningWarning(ServerLevel level, ServerPlayer player, ZombietideConfig config) {
        var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
        int modDay = data.modDay();
        
        // 防重复：同一个模组天数只提醒一次
        Integer warned = warnedNightBefore.get(player.getUUID());
        if (warned != null && warned == modDay) return;
        warnedNightBefore.put(player.getUUID(), modDay);
        
        HordeWarning.warnMorningHorde(level, player, config, modDay);
    }

    

    /**
     * 前夜警告检测：如果明天是尸潮日，今晚黄昏发送警告
     * 使用玩家/村民的**模组天数**判断，而非世界时间
     * @param level 服务端世界
     * @param config 配置
     */
    public static void checkNightBeforeWarning(ServerLevel level, ZombietideConfig config) {
        long dayTime = level.getDayTime() % 24000;
        // 黄昏时段：12000-14000（精确一次，防止每 tick 重复）
        if (dayTime < 12000 || dayTime > 14000) return;

        int tickDay = (int)(level.getDayTime() / 24000);

        // 检查玩家：今天（模组天数）是否为尸潮日
        if (config.playerHordeEnabled() && config.playerIntervalDays() > 0) {
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                // 只在主世界
                if (player.level().dimension() != Level.OVERWORLD) continue;

                var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                int modDay = data.modDay();
                // 与触发判定保持一致，支持固定/随机间隔
                if (!HordeTimer.isHordeDay(player.getUUID(), modDay, data.lastTriggerDay(), config, false)) continue;
                if (modDay == data.lastTriggerDay()) continue;

                // 今天是尸潮日，发送清晨提醒
                HordeWarning.warnMorningHorde(level, player, config, modDay);
            }
        }

        // 村民尸潮清晨提醒已弃用
    }

    /** 清晨警告检测：黎明后（0-1000）检查今天是否为尸潮日；发送聊天提醒 + morning_warning 音效（无大标题） */
    public static void checkMorningWarning(ServerLevel level, ZombietideConfig config) {
        if (!config.warningEnabled()) return;

        // 检查玩家：今天是否为尸潮日
        if (config.playerHordeEnabled() && config.playerIntervalDays() > 0) {
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                if (player.level().dimension() != Level.OVERWORLD) continue;

                var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                int modDay = data.modDay();
                // 与触发判定（HordeTimer.isHordeDay）保持一致，同时支持固定/随机间隔
                if (!HordeTimer.isHordeDay(player.getUUID(), modDay, data.lastTriggerDay(), config, false)) continue;
                if (modDay == data.lastTriggerDay()) continue;

                // 记录已提醒，避免 missedDusk 补偿链（sendMorningWarning）对同一天重复提醒（Issue #43）
                warnedNightBefore.put(player.getUUID(), modDay);

                // 今天是尸潮日，发送清晨聊天提醒
                HordeWarning.warnMorningHorde(level, player, config, modDay);
            }
        }

        // 村民尸潮清晨提醒已弃用
    }

    

    /** 【设计变更】按生物群系选择僵尸变体，贴合原版 1.21.1 夜间自然生成规则（仅主世界），并应用原版自然生成逻辑（含困难模式装备）。
     * 僵尸村民生成概率：5%（所有变体通用）
     * 沼泽/红树林沼泽：50% Zombie、45% Drowned、5% Zombie Villager */
    private static Mob createBiomeZombie(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        Mob mob;
        // 非主世界不生成尸潮僵尸
        if (level.dimension() != Level.OVERWORLD) {
            return null;
        }
        // 沙漠/恶地 → 尸壳（5% 概率转僵尸村民）
        if (biome.is(Biomes.DESERT) || biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS)
                || biome.is(Biomes.WOODED_BADLANDS)) {
            mob = new Husk(EntityType.HUSK, level);
        }
        // 沼泽/红树林沼泽 → 50% Zombie、45% Drowned、5% Zombie Villager
        else if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            float r = level.getRandom().nextFloat();
            if (r < 0.50f) {
                mob = new Zombie(EntityType.ZOMBIE, level);
            } else if (r < 0.95f) {
                mob = new Drowned(EntityType.DROWNED, level);
            } else {
                mob = new ZombieVillager(EntityType.ZOMBIE_VILLAGER, level);
            }
            // 沼泽已按概率直接确定变体，跳过通用 5% 替换逻辑
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);
            return mob;
        }
        // 其他所有主世界生物群系（含雪原、针叶林、冻原、林地、平原、山地等） → 普通僵尸（5% 概率转僵尸村民）
        // 不生成流浪者（骷髅变体），尸潮只生成僵尸变体
        else {
            mob = new Zombie(EntityType.ZOMBIE, level);
        }

        // 5% 概率转为僵尸村民（所有僵尸变体均可转，包括 Husk，贴合原版自然生成规则）
        if (level.getRandom().nextFloat() < 0.05f) {
            mob = new ZombieVillager(EntityType.ZOMBIE_VILLAGER, level);
        }

        // 应用原版自然生成逻辑：装备、困难模式装备等（对应 Zombie.finalizeSpawn）
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);

        return mob;
    }

    /** 根据生物群系预测僵尸变种分布，用于触发提示 */
    private static String predictVariantDistribution(ServerLevel level, BlockPos pos) {
        if (level.dimension() != Level.OVERWORLD) {
            return "无（非主世界不生成尸潮）";
        }
        Holder<Biome> biome = level.getBiome(pos);
        // 沙漠/恶地
        if (biome.is(Biomes.DESERT) || biome.is(Biomes.BADLANDS) || biome.is(Biomes.ERODED_BADLANDS)
                || biome.is(Biomes.WOODED_BADLANDS)) {
            return "95% 尸壳 + 5% 僵尸村民";
        }
        // 沼泽/红树林沼泽
        else if (biome.is(Biomes.SWAMP) || biome.is(Biomes.MANGROVE_SWAMP)) {
            return "50% 僵尸 + 45% 溺尸 + 5% 僵尸村民";
        }
        // 其他主世界生物群系
        else {
            return "95% 僵尸 + 5% 僵尸村民";
        }
    }

    /** 统计当前存活的尸潮僵尸数（用于全局上限协调） */
    /**
     * 统计当前世界存活的尸潮僵尸数（按 NBT 标记扫描实体）。
     * <p>与 {@code /zombietide debug info} 的「全局统计」同源，保证 HUD 面板与命令输出一致。
     * 不走 {@code hordeZombieUuids}，因为该集合在重启后可能不完整。
     */
    public static int countAliveHordeZombies(ServerLevel level) {
        int count = 0;
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.monster.Zombie
                    || entity instanceof net.minecraft.world.entity.monster.Stray) {
                if (entity.getPersistentData().hasUUID("zombietide_horde_target")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countHordeZombies(ServerLevel level) {
        // 改用 hordeZombieUuids Set 统计，避免每 tick 全世界实体扫描（O(全实体数) → O(尸潮僵尸数)）
        // 同时清理已死亡/已揪尸的僵尸UUID，防止僵尸烧死后UUID滞留导致可用名额虚高、持续快速刷新
        int count = 0;
        java.util.Iterator<UUID> it = hordeZombieUuids.iterator();
        while (it.hasNext()) {
            UUID zombieUuid = it.next();
            var entity = level.getEntity(zombieUuid);
            if (entity == null || !entity.isAlive()) {
                // 僵尸已死亡或已从世界移除，清理UUID
                it.remove();
                continue;
            }
            if (entity instanceof net.minecraft.world.entity.monster.Zombie) {
                // Zombie 已覆盖 ZombieVillager / Husk / Drowned（均为其子类）
                count++;
            } else if (entity instanceof net.minecraft.world.entity.monster.Stray) {
                // 防御性保留：当前 createBiomeZombie 不生成 Stray；但若带尸潮标记的 Stray 存在
                // （其他模组/指令生成），仍需计入全局上限，否则 UUID 会被误删
                count++;
            } else {
                // 不是尸潮僵尸类型，清理
                it.remove();
            }
        }
        return count;
    }

    /**
     * 允许的落点低于「目标所在地表」的最大格数。
     * <p>高度图取的是该列最高实体方块，但在洞穴口、天坑、竖井、玩家自己挖的竖井里，
     * 这个"最高方块"本身就在地下。超过该容差即视为地下落点，直接丢弃。
     */
    private static final int MAX_DEPTH_BELOW_SURFACE = 16;

    /**
     * 实体能正常 tick 的最大半径（格）。
     * <p>取服务器**模拟距离**（区块）× 16，再留 1 个区块余量。
     * 注意不能用视距：视距内的区块虽然已加载，但超出模拟距离的实体不会 tick，刷在那里只会站着发呆。
     * 取不到时兜底 10 区块（1.21 默认模拟距离）。
     */
    private static double maxSimulationRadius(ServerLevel level) {
        int simChunks = 10;
        var server = level.getServer();
        if (server != null && server.getPlayerList() != null) {
            int d = server.getPlayerList().getSimulationDistance();
            if (d > 0) simChunks = d;
        }
        return Math.max(16.0, simChunks * 16.0 - 16.0);
    }

    /**
     * 在目标周围的安全地表点中挑一个刷怪位置。
     * <p>「生成性能」分页里与刷点相关的参数全部在这里生效：
     * <ul>
     *   <li>{@code radius}（玩家/村民生成半径）——**最远落点距离**，尸潮不会刷到比这更远；
     *       还会被服务器**模拟距离**再压一次（见 {@link #maxSimulationRadius}）；</li>
     *   <li>{@code minSpawnDist}——**最近落点距离**，避免刷在目标脸上；落点就在
     *       {@code [minSpawnDist, radius]} 这个环带内按面积均匀采样（sqrt），不会挤在中心；</li>
     *   <li>{@code spawnAttempts}——采样次数，越大越容易同时命中「背后 + 有空间 + 高评分」；</li>
     *   <li>{@code distanceWeight} / {@code darkWeight}——候选点评分权重（越远、越暗得分越高），
     *       在「背后」和「任意」两个优先级内各取最高分；两者都为 0 时退化为先到先得。</li>
     * </ul>
     * <p><b>区块保护</b>：{@code Level#getHeightmapPos} 在区块未加载时会<b>同步加载/生成</b>区块
     * （{@code ChunkStatus.FULL, true}）。因此这里做两层防护：
     * ① 用模拟距离收缩采样半径；② 每个候选点先用 {@code hasChunkAt} 过滤，未加载直接丢弃。
     * 保证刷怪永远不会强制生成新区块。
     * <p><b>只在地表</b>：高度图取的是该列最高实体方块，但在洞穴口 / 天坑 / 竖井里它本身就在地下。
     * 因此落点还要满足「不低于目标所在列地表 {@value #MAX_DEPTH_BELOW_SURFACE} 格」，否则丢弃。
     * 基准是<b>目标所在列的地表</b>，所以玩家在地下挖矿时仍然只在地表刷怪。
     * <p>优先级仍是：目标背后 → 任意安全点 → 目标脚下地表，绝不返回 null。
     */
    private static BlockPos findSpawnPos(ServerLevel level, net.minecraft.world.entity.Entity target,
                                         double radius, ZombietideConfig config) {
        int attempts = config.spawnAttempts() > 0 ? Math.min(500, config.spawnAttempts()) : 60;

        // 距离环带：内圈 = minSpawnDist，外圈 = min(生成半径, 模拟距离上限)
        // 超出模拟距离的区块即便已加载，实体也不会 tick（僵尸站着不动），再往外则完全是未加载区块。
        double inner = Math.max(0.0, config.minSpawnDist());
        double simLimit = maxSimulationRadius(level);
        double configured = Math.max(0.0, radius);
        double outer = Math.min(configured, simLimit);
        if (outer < configured) {
            EnikZombietide.LOGGER.debug("刷怪半径被模拟距离收缩：配置={} 格，实际上限={} 格", configured, outer);
        }
        if (outer < inner) outer = inner; // 容错：最小距离大于半径时退化为同一圈
        double minSq = inner * inner;
        double spanSq = Math.max(0.0, outer * outer - minSq);

        double distanceWeight = Math.max(0.0F, config.distanceWeight());
        double darkWeight = Math.max(0.0F, config.darkWeight());
        double weightSum = distanceWeight + darkWeight;

        BlockPos bestBehind = null;   // 优先：目标背后
        double bestBehindScore = -1.0;
        BlockPos bestAny = null;      // 兜底：任意安全点
        double bestAnyScore = -1.0;

        double targetYaw = target.getYRot();
        double forwardX = -Math.sin(Math.toRadians(targetYaw));
        double forwardZ = Math.cos(Math.toRadians(targetYaw));

        // 地表基准：目标所在列的最高实体方块。玩家在地下挖矿时它仍是头顶的地表，
        // 因此落点被约束在地表附近，而不是玩家脚边的洞穴里。
        int targetSurfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                target.getBlockX(), target.getBlockZ());

        for (int i = 0; i < attempts; i++) {
            double angle = RANDOM.nextDouble() * 2 * Math.PI;
            // 环带内按面积均匀采样：dist² 均匀 → 不会全都挤在内圈
            double dist = Math.sqrt(minSq + RANDOM.nextDouble() * spanSq);
            double dx = Math.cos(angle) * dist;
            double dz = Math.sin(angle) * dist;

            int spawnX = (int)(target.getX() + dx);
            int spawnZ = (int)(target.getZ() + dz);

            // 区块保护：未加载的区块一律丢弃。getHeightmapPos 会同步加载/生成区块，
            // 这里先查一次 hasChunkAt（纯查询、不触发加载），避免刷怪把区块强行拉起来。
            if (!level.hasChunkAt(new BlockPos(spawnX, 0, spawnZ))) {
                continue;
            }

            BlockPos surfacePos = level.getHeightmapPos(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(spawnX, 0, spawnZ)
            );

            // 只要地表：洞穴口 / 天坑 / 竖井里，高度图会指到地下，这类落点直接丢弃。
            // 基准取「目标所在列的地表」，所以玩家在地下挖矿时仍然只在地表刷。
            if (surfacePos.getY() < targetSurfaceY - MAX_DEPTH_BELOW_SURFACE) {
                continue;
            }

            // 检查生成空间：需要 surfacePos.above() 和 surfacePos.above().above() 都是空气/可替换
            BlockPos feetPos = surfacePos.above();
            BlockPos headPos = feetPos.above();
            if (!level.getBlockState(feetPos).isAir() || !level.getBlockState(headPos).canBeReplaced()) {
                continue;
            }

            // 评分：距离越远、亮度越低分越高；两个权重都为 0 时全员 0 分，等价于先到先得
            double score = 0.0;
            if (weightSum > 0.0) {
                double distNorm = outer > 0.0 ? Math.min(1.0, dist / outer) : 0.0;
                int light = Math.max(0, Math.min(15, level.getMaxLocalRawBrightness(surfacePos)));
                double darkNorm = 1.0 - light / 15.0;
                score = (distanceWeight * distNorm + darkWeight * darkNorm) / weightSum;
            }

            // 计算点积：判断是否在目标背后
            double toTargetX = surfacePos.getX() - target.getX();
            double toTargetZ = surfacePos.getZ() - target.getZ();
            double dot = toTargetX * forwardX + toTargetZ * forwardZ;
            boolean behind = dot < 0;

            if (behind && score > bestBehindScore) {
                bestBehindScore = score;
                bestBehind = surfacePos;
            }
            if (score > bestAnyScore) {
                bestAnyScore = score;
                bestAny = surfacePos;
            }
        }

        // 优先返回背后点，否则返回任意安全点，绝不返回 null
        if (bestBehind != null) return bestBehind;
        if (bestAny != null) return bestAny;

        // 最终兜底：直接在目标脚下的地表位置（由 spawnZombie 做最终空气校验）
        return level.getHeightmapPos(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            new BlockPos((int)target.getX(), 0, (int)target.getZ())
        );
    }

    // ===== 供调试命令使用的公开访问器（替代反射） =====
    public static int getActiveHordeCount() {
        return activeHordes.size();
    }

    /** 统计当前 waveActive=true 的玩家尸潮目标数量（基于附件状态，不因 activeHordes 清空而丢失） */
    public static int getActivePlayerHordeCount(ServerLevel level) {
        int count = 0;
        for (ServerPlayer player : level.getPlayers(p -> true)) {
            if (player.level().dimension() != Level.OVERWORLD) continue;
            var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
            if (data.waveActive()) count++;
        }
        return count;
    }

    /** 统计当前 waveActive=true 的村民尸潮目标数量（基于附件状态，不因 activeHordes 清空而丢失） */
    public static int getActiveVillagerHordeCount(ServerLevel level) {
        int count = 0;
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.npc.Villager villager) {
                var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                if (vData.waveActive()) count++;
            }
        }
        return count;
    }

    public static java.util.Map<UUID, Integer> getActiveHordes() {
        return new java.util.HashMap<>(activeHordes);
    }

    public static int countHordeZombiesInWorld(ServerLevel level) {
        return countHordeZombies(level);
    }

    /**
     * 按 UUID 可靠地查找尸潮目标实体（玩家或村民）。
     * <p>{@code ServerLevel.getEntity} 的 UUID 索引对已加载村民不可靠（见 {@link #buildLoadedVillagerMap}），
     * 因此未命中时兜底遍历实体表，避免村民目标被误判为「已消失」。
     */
    private static net.minecraft.world.entity.Entity findTarget(ServerLevel level, UUID id) {
        var entity = level.getEntity(id);
        if (entity != null) return entity;
        for (var e : level.getEntities().getAll()) {
            if (e.getUUID().equals(id)) return e;
        }
        return null;
    }

    /** 构建已加载村民 UUID -> 实体映射（ServerLevel.getEntity 的 UUID 索引对已加载村民不可靠，须遍历实体表） */
    private static Map<UUID, net.minecraft.world.entity.npc.Villager> buildLoadedVillagerMap(ServerLevel level) {
        Map<UUID, net.minecraft.world.entity.npc.Villager> map = new HashMap<>();
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.npc.Villager v && v.isAlive()) {
                map.put(v.getUUID(), v);
            }
        }
        return map;
    }

    /** 清理某个目标的刷怪/四散内部状态（不触碰附件与调度表） */
    private static void clearInternalState(ServerLevel level, UUID targetId) {
        activeHordes.remove(targetId);
        spawnedCount.remove(targetId);
        totalCount.remove(targetId);
        batchTimer.remove(targetId);
        villagerHordeRadii.remove(targetId);
        if (level != null) com.enik.zombietide.config.PlayerDebtData.get(level).clearPending(targetId);
    }

    /**
     * 对账：结束「跨天残留」的尸潮。
     * <p>
     * 判定规则：{@code waveActive=true && lastTriggerDay < 当前 modDay} —— 触发日已过，
     * 说明这波尸潮本应在当天日出前后结束，但服务器当时未运行（或在白天加载存档），
     * 错过了 {@link #scatterZombies} / {@link #onDawn} 窗口，导致状态一直卡到下一个黎明。
     * <p>
     * 同时清理：无主的内部刷怪状态、目标已失效的孤儿尸潮僵尸、已不存在/已死的僵尸 UUID。
     * 由 {@code HordeTimer} 每 2 秒调用一次，玩家登录时也调用一次。
     */
    public static void reconcileStaleWaves(ServerLevel level, ZombietideConfig config) {
        int currentDay = (int) (level.getDayTime() / 24000L);
        Set<UUID> stillActive = new HashSet<>();

        // 1) 玩家：跨天残留则结束
        for (ServerPlayer player : level.getPlayers(p -> true)) {
            if (player.level().dimension() != Level.OVERWORLD) continue;
            var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
            if (!data.waveActive()) continue;
            if (data.lastTriggerDay() < currentDay) {
                player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                        new com.enik.zombietide.attachments.PlayerHordeData(
                                data.modDay(), data.lastTriggerDay(), data.waveCount(), false));
                clearInternalState(level, player.getUUID());
                EnikZombietide.LOGGER.info("对账：玩家 {} 的尸潮为跨天残留（触发日 DAY {} < 当前 DAY {}），已结束",
                        player.getScoreboardName(), data.lastTriggerDay(), currentDay);
            } else {
                stillActive.add(player.getUUID());
            }
        }

        // 2) 村民：以调度表（权威数据源）为准
        VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
        Map<UUID, net.minecraft.world.entity.npc.Villager> loadedVillagers = buildLoadedVillagerMap(level);
        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            var sched = entry.getValue();
            if (!sched.waveActive()) continue;
            if (sched.lastTriggerDay() < currentDay) {
                schedule.put(villagerUuid, new VillagerScheduleData.ScheduleEntry(
                        sched.modDay(), sched.lastTriggerDay(), sched.waveCount(), false));
                var villager = loadedVillagers.get(villagerUuid);
                if (villager != null) {
                    villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                            new com.enik.zombietide.attachments.VillagerHordeData(
                                    sched.modDay(), sched.lastTriggerDay(), sched.waveCount(), false));
                }
                clearInternalState(level, villagerUuid);
                EnikZombietide.LOGGER.info("对账：村民 {} 的尸潮为跨天残留（触发日 DAY {} < 当前 DAY {}），已结束",
                        villagerUuid, sched.lastTriggerDay(), currentDay);
            } else {
                stillActive.add(villagerUuid);
            }
        }

        // 3) 清理 activeHordes 中已无活跃目标的条目
        for (UUID targetId : new ArrayList<>(activeHordes.keySet())) {
            if (!stillActive.contains(targetId)) {
                clearInternalState(level, targetId);
            }
        }

        // 4) 丢弃目标已失效的孤儿尸潮僵尸
        for (var entity : level.getEntities().getAll()) {
            if (!(entity instanceof net.minecraft.world.entity.monster.Zombie ||
                  entity instanceof net.minecraft.world.entity.monster.ZombieVillager ||
                  entity instanceof net.minecraft.world.entity.monster.Husk ||
                  entity instanceof net.minecraft.world.entity.monster.Drowned ||
                  entity instanceof net.minecraft.world.entity.monster.Stray)) continue;
            CompoundTag tag = entity.getPersistentData();
            if (!tag.hasUUID(HORDE_TARGET_UUID_KEY)) continue;
            // 已进入四散状态（state=1）的僵尸由 settleDebtAtBurnTime 在 23460 统一清除，不在此提前丢弃
            if (tag.getInt(HORDE_STATE_KEY) == 1) continue;
            UUID targetId = tag.getUUID(HORDE_TARGET_UUID_KEY);
            if (!stillActive.contains(targetId)) {
                hordeZombieUuids.remove(entity.getUUID());
                scatteringZombies.remove(entity.getUUID());
                entity.discard();
            }
        }

        // 注：不在此清理 hordeZombieUuids —— level.getEntity 对未加载区块的僵尸也返回 null，
        // 误删会把仍在活跃波次里的僵尸踢出四散/清理跟踪。僵尸 UUID 由 23460 的
        // settleDebtAtBurnTime 统一移除；未加载的孤儿僵尸会在加载后由第 4 步兜底丢弃。
    }

    /** 将指定目标的 waveActive 重置为 false（尸潮完成/四散结束后调用） */
    private static void resetTargetWaveActive(ServerLevel level, UUID targetId) {
        var target = findTarget(level, targetId);
        if (target instanceof ServerPlayer player) {
            var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
            if (data.waveActive()) {
                player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                        new com.enik.zombietide.attachments.PlayerHordeData(
                                data.modDay(), data.lastTriggerDay(), data.waveCount(), false));
                EnikZombietide.LOGGER.debug("玩家 {} waveActive 已重置为 false（尸潮完成）", targetId);
            }
        } else if (target instanceof net.minecraft.world.entity.npc.Villager villager) {
            var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
            if (vData.waveActive()) {
                villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                        new com.enik.zombietide.attachments.VillagerHordeData(
                                vData.modDay(), vData.lastTriggerDay(), vData.waveCount(), false));
                EnikZombietide.LOGGER.debug("村民 {} waveActive 已重置为 false（尸潮完成）", targetId);
            }
            // 同步权威调度表：否则区块重载后 onEntityJoinLevel 会把 true 抄回附件
            VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                    VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
            if (schedule.getAllSchedules().containsKey(targetId)) {
                VillagerScheduleData.ScheduleEntry sched = schedule.get(targetId);
                if (sched.waveActive()) {
                    schedule.put(targetId, new VillagerScheduleData.ScheduleEntry(
                            sched.modDay(), sched.lastTriggerDay(), sched.waveCount(), false));
                }
            }
        }
    }

    /**
     * 查询某目标当前还有多少只未生成（0 表示没有进行中的刷怪批次）。
     * 供 /zombietide trigger 判断是否允许重复触发。
     */
    public static int getActiveRemaining(UUID targetId) {
        Integer remaining = activeHordes.get(targetId);
        return remaining == null ? 0 : Math.max(0, remaining);
    }

    /**
     * 僵尸实体加载时重注册（服务器重启后 hordeZombieUuids / scatteringZombies 为空，
     * 已生成的僵尸会既不继续追人也不被四散清理，成为孤儿）。由 onEntityJoinLevel 调用。
     * @param state 0=追踪中（hordeZombieUuids），1=四散中（scatteringZombies 倒计时）
     */
    public static void retrackZombie(ServerLevel level, UUID zombieUuid, int state) {
        if (state == 1) {
            // 与 scatterZombies 相同的公式：倒计时到起火时刻 23460 移除
            long dayPhase = level.getDayTime() % 24000L;
            int ticks = (int)(23460 - dayPhase);
            if (ticks < 1) ticks = 1;
            scatteringZombies.put(zombieUuid, ticks);
        } else {
            hordeZombieUuids.add(zombieUuid);
        }
    }

    /** 供调试命令使用：清空所有活跃尸潮（原 Map，非副本）并重置所有目标的 waveActive 与触发记录（测试可重复触发） */
    public static void clearActiveHordes(ServerLevel level) {
        // 重置所有玩家和村民的 waveActive + lastTriggerDay（clear 用于测试，必须允许同一天重新触发）
        if (level != null) {
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                if (player.level().dimension() == Level.OVERWORLD) {
                    var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                    player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                            new com.enik.zombietide.attachments.PlayerHordeData(
                                    data.modDay(), -1, data.waveCount(), false
                            ));
                }
            }
            // 同步重置村民调度表（触发判定读调度表，权威数据源）
            com.enik.zombietide.config.VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                    com.enik.zombietide.config.VillagerScheduleData.FACTORY,
                    com.enik.zombietide.config.VillagerScheduleData.ID);
            for (var entry : schedule.getAllSchedules().entrySet()) {
                var sched = entry.getValue();
                schedule.put(entry.getKey(), new com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry(
                        sched.modDay(), -1, sched.waveCount(), false));
                var villagerEntity = findTarget(level, entry.getKey());
                if (villagerEntity instanceof net.minecraft.world.entity.npc.Villager villager) {
                    villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                            new com.enik.zombietide.attachments.VillagerHordeData(
                                    sched.modDay(), -1, sched.waveCount(), false));
                }
            }
        }

        // 清理内部 Map
        activeHordes.clear();
        seenHordesThisSession.clear();
        spawnedCount.clear();
        totalCount.clear();
        batchTimer.clear();
        hordeZombieUuids.clear();
        scatteringZombies.clear();
        villagerHordeRadii.clear();
        warnedNightBefore.clear();
        if (level != null) {
            com.enik.zombietide.config.PlayerDebtData.get(level).clearAll();
            com.enik.zombietide.config.HordeProgressData.get(level).clearAll();
        }

        EnikZombietide.LOGGER.info("已清空所有活跃尸潮并重置所有目标 waveActive/lastTriggerDay（可重新触发）");
    }

    /** 兼容旧调用（无 level 参数） */
    public static void clearActiveHordes() {
        clearActiveHordes(null);
    }
}
