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
    private static final String HORDE_STATE_KEY = "zombietide_state"; // 0=active, 1=scattering, 2=despawned（预留，当前无任何写入点，分支不可达）
    /** 【v0.2.90】僵尸出生点（BlockPos.asLong），四散时直接走回这里，取代 Marker 诱饵 */
    public static final String HORDE_SPAWN_POS_KEY = "zombietide_spawn_pos";
    /** 【v0.2.97】尖啸者标记：5% 尸潮僵尸会被标记，放大体积并在发现玩家后尖叫增援 */
    public static final String SCREAMER_KEY = "zombietide_screamer";
    /** 尖啸者生成概率 */
    private static final float SCREAMER_CHANCE = 0.05F;
    /** 尖啸者体积倍率（Attributes.SCALE，原版模型/材质包自动跟随） */
    private static final double SCREAMER_SCALE = 1.4D;
    /** 尖啸者上次尖叫的游戏 tick（NBT，0 = 从未叫过） */
    public static final String SCREAMER_LAST_TICK_KEY = "zombietide_screamer_last_tick";
    /** 同一只尖啸者的尖叫冷却：30 秒 = 600 tick */
    private static final long SCREAMER_COOLDOWN_TICKS = 600L;

    /** 供重启/区块重载时恢复尖啸者体积（SCALE 属性不持久化） */
    public static double screamerScale() {
        return SCREAMER_SCALE;
    }

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

    // 【v0.1.62 修复 #168】四散僵尸集合：UUID -> 到 23460 的名义倒计时。
    // 注意：该倒计时值当前**不被读取**（v0.1.55 起僵尸由 Marker 诱饵导航、23460 由
    // settleDebtAtBurnTime 统一清除）；这里主要作为"已处于四散态"的标记集合使用。
    private static final Map<UUID, Integer> scatteringZombies = new ConcurrentHashMap<>();

    // 所有活跃的尸潮僵尸 UUID 集合（用于四散时快速遍历，避免全实体扫描）
    private static final Set<UUID> hordeZombieUuids = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 【v0.1.63 修复 #179】23460 结算时所在区块未加载、拿不到实体对象的四散僵尸 UUID。
    // 服务端此时既不能 discard 也不能写 NBT，只能记在这里，待区块加载（onEntityJoinLevel）时立即清除。
    // 该集合是纯运行时状态，服务器重启即丢；重启兜底靠僵尸自带的 state==1 + 四散窗口判定。
    private static final Set<UUID> pendingDespawnZombies = ConcurrentHashMap.newKeySet();

    // 【v0.1.63 修复 #179】zombieUuid -> targetId 旁路索引：转入四散 / 重启重注册时写入。
    // 用途：23460 清除时即使实体未加载，也能补全 clearedTargets，保证 clearPending /
    // 相关收尾动作一个不漏。
    private static final Map<UUID, UUID> scatterZombieTarget = new ConcurrentHashMap<>();

    // 本次 JVM 进程内已触发/恢复过的尸潮目标。进程重启即清空，因此可用于判断
    // 恢复日志的原因：首次见到 = 进度来自存档（服务器重启），已见过 = 目标重新上线/加载。
    private static final Set<UUID> seenHordesThisSession = ConcurrentHashMap.newKeySet();

    // 夜前警告已发送的模组天数记录：target UUID -> 已警告的 modDay（避免重复触发）
    // 现用于清晨提醒防重复
    private static final Map<UUID, Integer> warnedNightBefore = new ConcurrentHashMap<>();

    /**
     * 重置所有静态状态（服务器启动时调用，v0.1.61 修复 #140）。
     * <p>单机「退出存档 → 进另一个存档」不重启 JVM，这些静态 Map/Set 会残留
     * 上个世界的僵尸 UUID / 活跃波次 / 四散倒计时，导致新存档出现幽灵数据。
     * <p>只在**进程级**（{@code onServerStarted}）清；玩家登录/换维度不清。
     * 存档里的真实进度由 {@code HordeProgressData}（SavedData）持有，重启后
     * 由 {@code update()} 自行恢复，清的只是内存索引。
     */
    public static void resetState() {
        activeHordes.clear();
        spawnedCount.clear();
        totalCount.clear();
        batchTimer.clear();
        villagerHordeRadii.clear();
        scatteringZombies.clear();
        hordeZombieUuids.clear();
        pendingDespawnZombies.clear();
        scatterZombieTarget.clear();
        seenHordesThisSession.clear();
        warnedNightBefore.clear();
    }

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
        // 【v0.2.97】尖啸者检查：每 20 tick 扫一次，发现玩家后尖叫并追加增援
        if (level.getGameTime() % 20L == 0L) {
            checkScreamers(level, config);
        }

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
                // scatterZombies 每 20 tick 可重扫一次，若它已执行过，本函数不应再产出新僵尸
                // （新僵尸不会被 23460 的 scatteringZombies 清除，却仍会被 settleDebtAtBurnTime
                // 计入血债并活到白天；血债唯一结算点是 23460，onDawn 只重置 waveActive，不会二次结算）。
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

    /**
     * 【v0.2.97】尖啸者：发现玩家后尖叫一次并追加 8~12 只增援。
     * <p>每 20 tick 由 {@link #update} 调用。只处理带 {@link #SCREAMER_KEY} 标记、
     * state==0（活跃）、且当前目标就是玩家的僵尸；同一只 30 秒冷却（NBT 记录游戏 tick）。
     * <p>增援走方案 A：直接往 {@code activeHordes} 剩余数追加，由刷怪引擎自然产出——
     * 不另开独立刷怪路径，黎明清理/血债结算/进度持久化全部复用现有逻辑。
     */
    private static void checkScreamers(ServerLevel level, ZombietideConfig config) {
        long now = level.getGameTime();
        for (UUID zombieUuid : hordeZombieUuids) {
            var entity = level.getEntity(zombieUuid);
            if (!(entity instanceof net.minecraft.world.entity.monster.Zombie mob) || !mob.isAlive()) continue;
            CompoundTag tag = mob.getPersistentData();
            if (!tag.getBoolean(SCREAMER_KEY)) continue;
            if (tag.getInt(HORDE_STATE_KEY) != 0) continue;   // 四散中的不叫

            long lastScream = tag.getLong(SCREAMER_LAST_TICK_KEY);
            if (lastScream > 0L && now - lastScream < SCREAMER_COOLDOWN_TICKS) continue;

            // 只有锁定到玩家才尖叫（村民尸潮的尖啸者不叫，避免无人区乱刷）
            if (!(mob.getTarget() instanceof ServerPlayer player)) continue;

            UUID targetId = tag.getUUID(HORDE_TARGET_UUID_KEY);
            if (targetId == null) continue;
            // 【v0.2.97】即使原波次已刷完（activeHordes 被移除），只要目标仍在波次中，
            // 尖啸者仍能叫来增援——直接新建一条 activeHordes 由刷怪引擎自然产出。
            // 判断"仍在波次中"用 totalCount 是否残留（波次结束才会清掉）。
            if (!activeHordes.containsKey(targetId) && !totalCount.containsKey(targetId)) continue;

            tag.putLong(SCREAMER_LAST_TICK_KEY, now);

            // 空间化尖叫：从僵尸位置发给附近玩家（64 格可闻）
            for (ServerPlayer p : level.getPlayers(p -> p.distanceToSqr(mob) <= 64.0 * 64.0)) {
                ModSounds.playAtPosition(p, ModSounds.SCREAMER_CALL,
                        mob.getX(), mob.getY(), mob.getZ(),
                        net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
            }

            // 追加 8~12 只到现有波次（含该玩家的血债波次）
            int reinforce = 8 + RANDOM.nextInt(5);
            boolean isVillager = villagerHordeRadii.containsKey(targetId);
            if (!activeHordes.containsKey(targetId)) {
                // 原波次已刷完但仍在波次中：新建一条增援波次，补齐配套字段
                spawnedCount.put(targetId, 0);
                totalCount.put(targetId, reinforce);
                batchTimer.put(targetId, Math.max(1, config.batchTick()));
            }
            activeHordes.merge(targetId, reinforce, Integer::sum);
            com.enik.zombietide.config.HordeProgressData.get(level)
                    .setRemaining(targetId, activeHordes.get(targetId), isVillager);

            EnikZombietide.LOGGER.info("[尖啸者] 僵尸 {} 尖叫，为玩家 {} 追加 {} 只增援（剩余 {}）",
                    mob.getUUID(), player.getScoreboardName(), reinforce, activeHordes.get(targetId));
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

        // 【v0.1.60 修复 #121】findSpawnPos 返回的 spawnPos **已经是第一格空气**
        // （高度图 = 最高阻挡方块 y + 1）。v0.1.59 只改了 findSpawnPos 里的净空检查，
        // 这里仍 .above() → 僵尸仍生成在离地 1 格处下坠，且净空检查也查错了位置。
        BlockPos feetPos = spawnPos;              // 僵尸脚部位置 = 地表方块上方 1 格
        BlockPos headPos = feetPos.above();       // 僵尸头部位置（脚部再上 1 格）

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
        // 【v0.2.90】记录出生点：四散时直接走回这里（不用 Marker 诱饵，性能更好）
        tag.putLong(HORDE_SPAWN_POS_KEY, feetPos.asLong());

        // 【v0.2.97】5% 概率标记为尖啸者：放大体积（原版实体类型不变，兼容僵尸材质包）。
        // SCALE 属性是 1.21.1 原生、可同步的，渲染与碰撞自动跟随，无需 mixin/自定义实体类。
        if (RANDOM.nextFloat() < SCREAMER_CHANCE) {
            tag.putBoolean(SCREAMER_KEY, true);
            var scaleAttr = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
            if (scaleAttr != null) scaleAttr.setBaseValue(SCREAMER_SCALE);
        }

        // 注入尸潮僵尸行为：主目标锁定 Goal + 永不消失（破门功能已移除）
        HordeZombieBehavior.apply(mob, targetId, config);

        boolean added = level.addFreshEntity(mob);
        if (!added) {
            EnikZombietide.LOGGER.warn("尸潮刷怪失败：target={} 位置={} addFreshEntity 返回 false，僵尸未加入世界", targetId, spawnPos);
            return false;
        }
        hordeZombieUuids.add(mob.getUUID());

        // 【v0.2.82】出生大叫：50% 概率从僵尸生成位置广播，所有玩家都能听到、可判断方位。
        // 带 3 秒全局冷却：一波几百只时不会刷屏成"整晚都在叫"。
        maybePlaySpawnCall(level, mob, config);

        // 刷怪提醒：记录日志（不再每只僵尸发聊天消息，避免大波次刷屏；波次详情已在 startHorde() 中一次性发送）
        EnikZombietide.LOGGER.debug("尸潮僵尸生成：目标={} 位置={} 维度={} 半径={}", targetId, spawnPos, level.dimension().location(), radius);
        
        return true;
    }

    /**
     * 【v0.2.82】僵尸出生大叫：50% 概率在生成位置发出，**范围随配置的生成半径**。
     * <p>范围不用音效注册值决定（那是 512 的技术上限），而是服务端按
     * {@code max(playerRadius, villagerRadius)} 逐个玩家过滤——配置改大改小，可听范围跟着变。
     * 音量按距离线性衰减（近 1.0 → 边缘 0.45），保证半径边缘也听得到、又不会近处爆音。
     * <p>3 秒全局冷却：避免一波几百只僵尸时每只都叫、把氛围变成噪音。
     */
    private static void maybePlaySpawnCall(ServerLevel level, net.minecraft.world.entity.Mob mob,
                                           ZombietideConfig config) {
        long now = level.getGameTime();
        if (now - LAST_SPAWN_CALL_TICK < SPAWN_CALL_COOLDOWN_TICKS) return;
        if (RANDOM.nextFloat() >= SPAWN_CALL_CHANCE) return;
        LAST_SPAWN_CALL_TICK = now;

        // 业务范围 = 当前配置的最大生成半径（随配置变化）
        double radius = Math.max(config.playerRadius(), config.villagerRadius());
        double radiusSq = radius * radius;
        int sent = 0;
        for (ServerPlayer player : level.getPlayers(p -> true)) {
            if (player.distanceToSqr(mob) > radiusSq) continue;   // 超出本次生成半径，不发给该玩家
            // volume 统一 1.0：距离衰减交给引擎（sounds.json 的 attenuation_distance=512 线性衰减），
            // 150 格处约 0.71，清晰可闻；自己再乘一层会双重衰减、边缘听不到。
            com.enik.zombietide.sound.ModSounds.playAtPosition(player,
                    com.enik.zombietide.sound.ModSounds.HORDE_CALL,
                    mob.getX(), mob.getY(), mob.getZ(),
                    net.minecraft.sounds.SoundSource.HOSTILE, 1.0F, 1.0F);
            sent++;
        }
        EnikZombietide.LOGGER.info("[出生大叫] 僵尸@{} 半径={} 已发给 {} 名玩家", mob.blockPosition(), (int) radius, sent);
    }

    /** 出生大叫触发概率 */
    private static final float SPAWN_CALL_CHANCE = 0.5F;
    /** 出生大叫全局冷却（tick）：3 秒，防止大波次刷屏 */
    private static final int SPAWN_CALL_COOLDOWN_TICKS = 60;
    /** 上次出生大叫的游戏 tick（静态，仅运行时） */
    private static long LAST_SPAWN_CALL_TICK = -1000L;

    /**
     * 黎明收尾：把所有目标的 {@code waveActive} 重置为 false。
     * <p><b>【v0.1.56 起不结算血债</b>——唯一结算点是 23460 起火时刻的
     * {@link #settleDebtAtBurnTime}。旧实现在黎明又算一遍并带「全灭清零」，
     * 而 23460 已把僵尸清完 → 黎明扫到 count=0 会把刚算好的血债清零。
     * <p>【BUG9 修复】不只依赖世界中的 Zombie 实体收集目标：
     * 若某场尸潮的僵尸全被杀死，世界中就找不到对应 Zombie，
     * allHordeTargets 为空 → waveActive 永远卡在 true。因此也遍历在线玩家与村民的附件。
     * <p>【R43-009 high】同游戏日内多次夜→晨跳变：调用方（HordeTimer）用 lastDawnDay 去重，
     * 本方法自身不判重。四散动作由 {@link #scatterZombies} 在 23260 触发，不在这里做。
     * @param level 服务端世界
     * @param config 配置
     */
    public static void onDawn(ServerLevel level, ZombietideConfig config) {
        // 收集每个目标的存活尸潮僵尸数（仅统计未进入四散的活尸潮僵尸；v0.1.56 起不用于血债结算）
        Map<UUID, Integer> survivorCount = new HashMap<>();
        // 收集所有尸潮目标 UUID：既包括世界中的尸潮僵尸，也包括附件中 waveActive=true 的玩家/村民
        Set<UUID> allHordeTargets = new HashSet<>();

        // 1. 从世界中的尸潮僵尸收集目标（用于 waveActive 重置与幸存者统计）
        // 尸潮实体包括：Zombie、ZombieVillager、Husk、Drowned、Stray（均带有 horde 标记）
        for (var entity : level.getEntities().getAll()) {
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
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

            // 已进入四散状态（state==1）的僵尸由 23460 的 settleDebtAtBurnTime 统一清除；
            // 黎明这里只做 waveActive 重置，不做存活统计与血债结算（此处不发送任何消息）
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
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
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

        // 【v0.1.56】黎明不再累加血债，只重置 waveActive（唯一结算点是 23460）
        // 【当前未使用，保留备查】v0.1.63 议题 #180：此局部变量声明后无读取，保留原行。
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
                if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
                if (entity instanceof ServerPlayer p && p.distanceToSqr(villager) <= 128.0 * 128.0) {
                    ModMessages.sendVillagerModDay(p, villager.getUUID(),
                            ModMessages.extractVillagerRegistryKey(villager),
                            vData.modDay(),
                            villager.getX(), villager.getY(), villager.getZ(), false,
                            HordeTimer.isHordeDay(villager.getUUID(), vData.modDay(), vData.lastTriggerDay(), config, true, level.getDayTime()),
                            HordeTimer.hordeProgress(villager.getUUID(), vData.modDay(), vData.lastTriggerDay(), config, true, level.getDayTime()),
                            HordeTimer.isHordeDay(villager.getUUID(), vData.modDay(), vData.lastTriggerDay(), config, true));
                }
            }
        }

        // 【v0.1.56】黎明**不再结算血债**。
        // 血债只在 23460 起火时刻由 settleDebtAtBurnTime 结算一次，这里只负责重置 waveActive，
        // 否则尸潮会永远显示「进行中」。旧实现在黎明又算一遍并带「全灭清零」，
        // 会把 23460 刚结算好的血债直接清零。
        for (UUID targetId : allHordeTargets) {
            if (!debtData.isKnown(targetId)) continue;
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(targetId);
            if (player == null) continue;   // 离线玩家：附件读不到，留待下次登录时由对账处理
            var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
            if (!data.waveActive()) continue;
            player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                    new com.enik.zombietide.attachments.PlayerHordeData(
                            data.modDay(), data.lastTriggerDay(), data.waveCount(), false));
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
        // 【v0.1.63】「开始四散」日志只在确有新僵尸转入时打一次：
        // scatterZombies 每 20 tick 被 HordeTimer 重扫一次，原先把 INFO 放在方法开头会每秒刷一条。
        // 具体输出移到下方 newlyScattered > 0 分支，通知/音效/waveActive 逻辑一律不动。
        if (dayPhase < scatterStartTick) return;

        // 【停止刷怪】四散开始后不得再生成新批次。scatterZombies 每 20 tick 可重扫一次（v0.1.55 起）
        // （HordeTimer 的 lastScatterTick 20 tick 节流，每轮都会重扫），错过本次快照的僵尸会在四散之后才出现：
        //   1) state=0，不在 scatteringZombies 里 —— 23460 起火时刻不会被清除；
        //   2) 却被 settleDebtAtBurnTime 的存活统计计入血债；
        //   3) 血债只在 23460 结算一次；onDawn 只重置 waveActive，不存在「次日黎明二次结算」；
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
        // 本次实际转入四散的僵尸数：为 0 说明全是早已 state==1 的存量，
        // 此时不发通知，否则 20 tick 重扫会反复刷「四散开始」
        int newlyScattered = 0;
        // 每个尸潮只创建一次 marker（避免对每只僵尸都扫一遍世界实体表）
        java.util.Map<UUID, net.minecraft.world.entity.Entity> markerCache = new java.util.HashMap<>();
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

            UUID targetId = tag.getUUID(HORDE_TARGET_UUID_KEY);

            // v0.2.90：四散改走各自出生点（NBT），不再需要 Marker 诱饵。
            // 已经进入四散状态的僵尸：只需跳过「转入四散」的动作（通知/状态/scatteringZombies）。
            // newlyScattered 仍只统计新转入的，防止通知刷屏。
            if (tag.getInt(HORDE_STATE_KEY) == 1) continue;

            scatterTargets.add(targetId);
            scatterCountByTarget.merge(targetId, 1, Integer::sum);
            newlyScattered++;
            if (entity instanceof Mob mob) {
                mob.setTarget(null); // 清空攻击目标，停止攻击
            }
            tag.putInt(HORDE_STATE_KEY, 1); // 标记为四散状态

            // 【v0.2.74】灰烬粒子在这里发：entity 就是"本轮刚转入四散"的实体，
            // 天然不会重复撒到之前轮次已转入的老僵尸。用 instanceof 覆盖
            // Zombie/Husk/Drowned/ZombieVillager/Stray 全部变体（旧的 getType() 精确比较
            // 会漏掉 Husk/Drowned，导致沙漠/沼泽群系灰烬完全不显示）。
            if (config.warningEnabled()) {
                level.sendParticles(net.minecraft.core.particles.ParticleTypes.ASH,
                        entity.getX(), entity.getY() + 1.0, entity.getZ(), 4, 0.35, 0.5, 0.35, 0.02);
            }

            // 关键修改：四散倒计时精确到 23460（起火时刻），而不是配置的固定秒数
            // 这样所有四散僵尸在同一 tick (23460) 被一次性清除
            int exactDespawnTicks = (int)(23460 - dayPhase);
            if (exactDespawnTicks < 1) exactDespawnTicks = 1;
            scatteringZombies.put(entity.getUUID(), exactDespawnTicks);
            // 【v0.1.63 修复 #179】登记 targetId 旁路索引，并撤销上一轮可能误留的清除标记。
            scatterZombieTarget.put(entity.getUUID(), targetId);
            pendingDespawnZombies.remove(entity.getUUID());

            // 四散导航由 HordeZombieGoal 的 state==1 分支统一驱动（v0.1.54 起）。
            // 这里不再设置导航：盲传 moveTo(x, y, z, speed) 在 createPath 失败时会把 path 置 null，
            // 而 4 倍外推的目标点往往超出寻路范围 → 僵尸「走一段停一下」。

            EnikZombietide.LOGGER.debug("尸潮实体 {} 进入四散状态，目标={} {} 秒后移除", entity.getUUID(), targetId, config.despawnSeconds());
        }

        // 四散开始：将目标标记为待血债结算（在 23460 起火时刻按实时存活数结算）
        com.enik.zombietide.config.PlayerDebtData debtData =
                com.enik.zombietide.config.PlayerDebtData.get(level);
        for (UUID targetId : scatterTargets) {
            if (debtData.isKnown(targetId)) debtData.markPending(targetId);
        }

        // 向目标玩家（或村民周围玩家）发送一条汇总四散提醒，避免每只僵尸一条消息刷屏
        if (newlyScattered > 0) {
            EnikZombietide.LOGGER.info("尸潮僵尸开始四散：日出前 {} 秒，本轮新转入 {} 只",
                    config.despawnSeconds(), newlyScattered);
            notifyScatterTargetsWithCount(level, config, scatterTargets, scatterCountByTarget);

        }

        // 定制音频：四散音效只发给玩家尸潮触发者（村民尸潮不播放）
        if (config.warningEnabled() && config.scatterSound()) {
            for (UUID targetId : scatterTargets) {
                var target = findTarget(level, targetId);
                if (target instanceof ServerPlayer player) {
                    ModSounds.playToPlayer(player, ModSounds.HORDE_SCATTER, SoundSource.HOSTILE, 0.8F, 1.0F);
                }
            }
        }

        // 【t37 修复】四散开始时重置目标 waveActive=false，避免 debug info 始终显示“进行中”。
        // 调用时刻不是固定的 23260：由 HordeTimer 按 `23460 - despawnSeconds*20` 触发（默认 10 秒 → 23260）。
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

    /**
     * 取目标当前的波次序号（v0.1.55 血债按波去重用的键）。
     * <p>v0.1.56 起结算点唯一（只有 23460 起火结算），不再需要按波去重。
     * 当前有 2 处调用（settleDebtAtBurnTime、forceClearAllHordes），返回值作为未使用参数
     * 传给 {@code settleDebtForTarget}；保留该键以备将来若加回第二个结算点。
     * <p>玩家读 attachment，村民读调度表（权威源）；离线/未加载返回 0。
     */
    private static int currentWaveSeq(ServerLevel level, UUID targetId) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(targetId);
        if (p != null) {
            return com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(p).waveSeq();
        }
        VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
        var sched = schedule.getScheduleOrNull(targetId);
        return sched == null ? 0 : sched.waveSeq();
    }

    /**
     * 按存活数结算单个目标的血债（起火结算与时间跳变强制清除共用同一套规则）。
     *
     * @param survivors 当前存活的尸潮僵尸数
     * @param waveSeq   【当前未使用，保留备查；v0.1.62 补注】原本用于「按波次去重」（v0.1.55），
     *                  v0.1.56 起结算点唯一（只有 23460），去重不再需要；参数保留以便将来若加回
     *                  第二个结算点时直接复用。调用方仍传 {@code currentWaveSeq(...)} 的返回值。
     * @return 结算后的血债值；{@code null} 表示跳过（目标未在血债表注册）
     */
    private static Integer settleDebtForTarget(ServerLevel level, ZombietideConfig config,
                                               com.enik.zombietide.config.PlayerDebtData debtData,
                                               UUID targetId, int survivors, int waveSeq) {
        if (!debtData.isKnown(targetId)) return null;
        // v0.1.56：不再做「同一波已结算」跳过——结算点只剩 23460（与跳天强制清除）两处，
        // 且跳天路径会 clearPending，23460 的 drainPending 拿不到它，本身不会重复。

        String debtMode = config.debtMode();
        int newDebt;
        if ("last_only".equals(debtMode)) {
            newDebt = Math.min(config.debtCap(), survivors);
        } else if ("none".equals(debtMode)) {
            newDebt = 0;
        } else {
            newDebt = Math.min(config.debtCap(), debtData.getDebt(targetId) + survivors);
        }
        if (survivors == 0) newDebt = 0; // 全灭清零
        debtData.setDebt(targetId, newDebt);
        return newDebt;
    }

    /** 起火时刻血债结算（dayPhase >= 23460，僵尸开始着火，按实时存活数结算血债） */
    public static void settleDebtAtBurnTime(ServerLevel level, ZombietideConfig config) {
        // 【当前未使用，保留备查】v0.1.63 议题 #180：此局部变量声明后无读取，保留原行。
        long today = level.getDayTime() / 24000L;
        com.enik.zombietide.config.PlayerDebtData debtData =
                com.enik.zombietide.config.PlayerDebtData.get(level);
        java.util.List<UUID> toSettle = debtData.drainPending();

        for (UUID targetId : toSettle) {
            // 血债只记玩家：离线玩家实体为 null，无法再用 instanceof 判断，改以「是否注册过」为准
            if (!debtData.isKnown(targetId)) continue;

            int survivorCount = countHordeZombiesByTarget(level, targetId);
            int waveSeq = currentWaveSeq(level, targetId);
            String debtMode = config.debtMode();
            // 统一走 settleDebtForTarget（v0.1.56 起不做按波去重，结算点唯一）
            Integer settled = settleDebtForTarget(level, config, debtData, targetId, survivorCount, waveSeq);
            if (settled == null) continue;
            int newDebt = settled;

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
            // 【v0.1.63 修复 #179】用 keySet 快照遍历：与 :566 的 toProcess 快照写法一致，
            // 避免遍历中修改 scatteringZombies / hordeZombieUuids 带来的并发迭代问题。
            java.util.List<UUID> toClear = new java.util.ArrayList<>(scatteringZombies.keySet());
            for (UUID zombieUuid : toClear) {
                // 先用旁路索引补 targetId：即使整波都在未加载区块，三个收尾动作也不会全漏。
                UUID indexedTarget = scatterZombieTarget.get(zombieUuid);
                if (indexedTarget != null) clearedTargets.add(indexedTarget);

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
                } else {
                    // 【#179】区块未加载：服务端拿不到实体对象，既不能 discard 也不能写 NBT。
                    // 交给 pending 集合托管，待区块加载时立即清除；这里仍按原行为移出 hordeZombieUuids，
                    // 不给其它统计方法留下游离条目。
                    pendingDespawnZombies.add(zombieUuid);
                }
                hordeZombieUuids.remove(zombieUuid);
                scatterZombieTarget.remove(zombieUuid);
            }
            scatteringZombies.clear();

            // 清除完成后重置对应目标的 waveActive，并移除对应的四散锚点（时机/参数/顺序不变）
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
     * 强制清除所有尸潮僵尸（用于 `/time set` 跳到白天等时间跳变场景）。
     *
     * <p><b>语义 = 「提前结算这一夜」</b>：先按当前存活数结算血债（规则同 {@code settleDebtAtBurnTime}），
     * 再 {@code discard()} 全部尸潮僵尸并重置波次状态。未生成的剩余批次直接作废、不计入血债。
     *
     * <p><b>历史</b>（v0.1.50 修正）：本方法原名 {@code forceScatterAll}，会把僵尸标记为四散
     * （{@code state=1}）并往 {@code scatteringZombies} 写入「距下一个 23460 还有多少 tick」的倒计时。
     * 但该倒计时<b>从未被消费</b>（{@code update()} 的倒计时块是空块），真正清除只靠
     * {@code settleDebtAtBurnTime} 在 {@code curPhase >= 23460} 时执行。结果是：跳到白天后僵尸
     * 要等约 19 分钟才消失（期间继续游荡），而血债在跳变瞬间已清零、19 分钟后又被
     * {@code markPending -> drainPending} 二次结算。改为直接清除后语义自洽。
     */
    public static void forceClearAllHordes(ServerLevel level, ZombietideConfig config) {
        // 【当前未使用，保留备查】v0.1.63 议题 #180：此局部变量声明后无读取，保留原行。
        long today = level.getDayTime() / 24000L;
        com.enik.zombietide.config.PlayerDebtData debtData =
                com.enik.zombietide.config.PlayerDebtData.get(level);
        com.enik.zombietide.config.HordeProgressData progressData =
                com.enik.zombietide.config.HordeProgressData.get(level);

        // 第一遍扫描：统计每个目标的存活数并收集实体（先不 discard——结算要用存活数）
        java.util.Map<UUID, Integer> survivorsByTarget = new java.util.HashMap<>();
        java.util.List<net.minecraft.world.entity.Entity> toDiscard = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            // instanceof Zombie 已覆盖 ZombieVillager / Husk / Drowned（均为其子类）；
            // Stray 不是 Zombie 子类，必须单独列出（防御性保留：当前 createBiomeZombie 不生成 Stray）。
            if (!(entity instanceof net.minecraft.world.entity.monster.Zombie
                    || entity instanceof net.minecraft.world.entity.monster.Stray)) continue;
            CompoundTag tag = entity.getPersistentData();
            if (!tag.hasUUID(HORDE_TARGET_UUID_KEY)) continue;
            survivorsByTarget.merge(tag.getUUID(HORDE_TARGET_UUID_KEY), 1, Integer::sum);
            toDiscard.add(entity);
        }

        // 提前结算：跳天等于把 23460 的起火结算「提前」，按当前存活数算血债。
        // 未生成的剩余批次不计入——它们从未出现在世界里。
        java.util.Map<UUID, Integer> settledDebt = new java.util.HashMap<>();
        for (var e : survivorsByTarget.entrySet()) {
            UUID targetId = e.getKey();
            int survivors = e.getValue();
            Integer newDebt = settleDebtForTarget(level, config, debtData, targetId, survivors,
                    currentWaveSeq(level, targetId));
            if (newDebt != null) {
                settledDebt.put(targetId, newDebt);
                EnikZombietide.LOGGER.info("时间跳变提前结算：目标 {} 幸存 {} 只 → 血债 {}（模式：{}）",
                        targetId, survivors, newDebt, config.debtMode());
            }
        }

        // 结算完成后清除僵尸
        for (net.minecraft.world.entity.Entity e : toDiscard) {
            e.discard();
        }
        hordeZombieUuids.clear();
        scatteringZombies.clear();
        // 【v0.1.63 #179】时间跳变强清后同样要清掉 pending 与旁路索引，
        // 否则残留的索引会让下一次 23460 补出不该有的 clearedTargets。
        pendingDespawnZombies.clear();
        scatterZombieTarget.clear();

        java.util.Set<UUID> targets = new java.util.HashSet<>(survivorsByTarget.keySet());
        // 还要处理「已触发但尚未生成完」的目标（剩余批次直接作废）
        targets.addAll(activeHordes.keySet());
        int cleared = toDiscard.size();

        for (UUID targetId : targets) {
            var target = findTarget(level, targetId);
            if (target instanceof ServerPlayer player) {
                var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                if (data.waveActive()) {
                    // lastTriggerDay 用 -1（从未触发）而不是 0：
                    // /time set day 会让 modDay=0，若同时写 0，isHordeDay 的
                    // `lastTriggerDay == modDay` 会成立 → HUD 的 DAY X 继续显示红色
                    player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                            new com.enik.zombietide.attachments.PlayerHordeData(
                                    data.modDay(), -1, data.waveCount(), false));
                }
            } else if (target instanceof net.minecraft.world.entity.npc.Villager villager) {
                var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                if (vData.waveActive()) {
                    villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                            new com.enik.zombietide.attachments.VillagerHordeData(
                                    vData.modDay(), -1, vData.waveCount(), false));
                }
            }
            // 血债已在上方「提前结算」里处理；这里只负责取消待结算标记，
            // 否则下一个 23460 的 drainPending 会按存活数再结算一次（血债"反弹"）
            if (debtData.isKnown(targetId)) {
                debtData.clearPending(targetId);
            }
            activeHordes.remove(targetId);
            spawnedCount.remove(targetId);
            totalCount.remove(targetId);
            batchTimer.remove(targetId);
            villagerHordeRadii.remove(targetId);
            progressData.remove(targetId);

            if (target instanceof ServerPlayer p) {
                Integer settled = settledDebt.get(targetId);
                int survivors = survivorsByTarget.getOrDefault(targetId, 0);
                String msg = settled != null
                        ? "[尸潮] 时间跳变，本波提前结算：幸存 " + survivors + " 只计入血债，当前血债 " + settled
                        : "[尸潮] 时间跳变，本波尸潮已结束";
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(msg)
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        }

        // 调度表 waveActive 无条件写回 false（权威数据源，避免区块重载把 true 抄回附件）
        VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
        for (var entry : schedule.getAllSchedules().entrySet()) {
            UUID villagerUuid = entry.getKey();
            var sched = entry.getValue();

            // lastTriggerDay 只对「本次清除涉及的目标」重置为 -1（从未触发），
            // 让 HUD 的 DAY X 不再因为 `lastTriggerDay == modDay` 而保持红色。
            // 其他村民保持原值——否则 modDay 跳大后 `modDay + 1 >= need` 会让它们
            // 全部立刻满足触发条件，下一 tick 批量触发（正是 v0.1.50 修过的刷屏问题）。
            int newLastTrigger = targets.contains(villagerUuid) ? -1 : sched.lastTriggerDay();
            boolean needReset = sched.waveActive() || newLastTrigger != sched.lastTriggerDay();
            if (!needReset) continue;

            schedule.put(villagerUuid, new com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry(
                    sched.modDay(), newLastTrigger, sched.waveCount(), false));
            var villagerEntity = findTarget(level, villagerUuid);
            if (villagerEntity instanceof net.minecraft.world.entity.npc.Villager villager) {
                var vData = com.enik.zombietide.attachments.ModAttachments.getVillagerHordeData(villager);
                villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                        new com.enik.zombietide.attachments.VillagerHordeData(
                                vData.modDay(), newLastTrigger, vData.waveCount(), false));
            }
        }

        EnikZombietide.LOGGER.info("强制清除 {} 只尸潮僵尸（时间跳变至白天），{} 个目标已重置，{} 个目标完成提前结算",
                cleared, targets.size(), settledDebt.size());
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
     * 【当前无调用点，保留备查】跳变跨过黄昏时发送清晨提醒（针对下一个模组天数）。
     * 当从黄昏前（&lt;12000）跳到黄昏后（&gt;14000）时调用。
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
     * 【当前无调用点，保留备查】黄昏时段（12000-14000）的提醒检测。
     *
     * <p><b>实际行为与旧注释不符</b>：判断的是<b>今天</b>（{@code data.modDay()}）是否为尸潮日，
     * 不是"明天"；命中后调用 {@code HordeWarning.warnMorningHorde}，即文案与音效都是<b>清晨提醒</b>语义。
     * 方法名与历史注释来自早期设计，重构后未同步；若要重新启用，请先确认想要的语义再改。
     *
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

    /**
     * 统计当前世界存活的尸潮僵尸数（按 NBT 标记扫描实体）。
     * <p>与 {@code /zombietide debug info} 的「全局统计」同源，保证 HUD 面板与命令输出一致。
     * 不走 {@code hordeZombieUuids}，因为该集合在重启后可能不完整。
     * <p><b>注意</b>：刷怪的「全局上限协调」用的是 {@link #countHordeZombies}（遍历 UUID 集合、O(尸潮僵尸数)），
     * 不是本方法——本方法要扫全实体表，只用于玩家可见的统计显示。
     */
    public static int countAliveHordeZombies(ServerLevel level) {
        int count = 0;
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (entity instanceof net.minecraft.world.entity.monster.Zombie
                    || entity instanceof net.minecraft.world.entity.monster.Stray) {
                if (entity.getPersistentData().hasUUID("zombietide_horde_target")) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 【v0.2.77】找该尸潮目标**已生成且仍存活**的一只僵尸，用于把叫声绑定到实体上播放。
     * <p>不依赖可能不完整的 {@code hordeZombieUuids} 集合，直接扫实体表按 NBT 目标 UUID 匹配
     * （与 {@link #countAliveHordeZombies} 同源口径）。叫声每 30 秒~2 分钟才播一次，开销可忽略。
     *
     * @return 一只活僵尸实体；该波还没有僵尸生成则 null（调用方回退到玩家位置播放）
     */
    public static net.minecraft.world.entity.Entity findHordeZombieEntity(ServerLevel level, UUID targetId) {
        if (targetId == null) return null;
        net.minecraft.world.entity.Entity best = null;
        double bestD2 = Double.MAX_VALUE;
        var target = findTarget(level, targetId);   // 只查一次，避免循环内 O(n²)
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (!(entity instanceof net.minecraft.world.entity.monster.Zombie
                    || entity instanceof net.minecraft.world.entity.monster.Stray)) continue;
            if (!entity.isAlive()) continue;
            CompoundTag tag = entity.getPersistentData();
            if (!tag.hasUUID(HORDE_TARGET_UUID_KEY)) continue;
            if (!targetId.equals(tag.getUUID(HORDE_TARGET_UUID_KEY))) continue;
            if (target == null) return entity;   // 目标不在线，随便一只即可
            // 选离目标最近的：叫声从最贴近玩家的僵尸传来，方位感最明确
            double d2 = entity.distanceToSqr(target);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = entity;
            }
        }
        return best;
    }

    /** 【v0.2.86】活跃尸潮僵尸 UUID 快照（供 HordeThreatDetector 判定锁定，避免暴露内部集合） */
    public static java.util.Collection<UUID> getHordeZombieIds() {
        return new java.util.ArrayList<>(hordeZombieUuids);
    }

    /** 【v0.2.86】僵尸状态 NBT key（0=活跃，1=四散） */
    public static String hordeStateKey() {
        return HORDE_STATE_KEY;
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
        // 【v0.1.60 修复 #110】旧写法 `outer = inner` 把外圈**撑到**最小距离（可能 > 半径、
        // 甚至 > 模拟距离），采样的点全在界外，被 hasChunkAt 丢弃后只能走「刷在目标脚下」兜底。
        // 正确方向是把内圈压到外圈，保证所有采样点都在 [0, radius] 内。
        if (inner > outer) inner = outer;
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

            // 【v0.1.59 修复 #98】getHeightmapPos 返回的已经是「第一格空气」
            // （高度图 = 最高阻挡方块 y + 1），脚就站在这个位置。
            // 旧代码又 .above() 一次 → 实际生成在高 1 格处，僵尸悬空下坠，
            // 且净空检查凭空多要 1 格，部分合法落点被丢弃。
            BlockPos feetPos = surfacePos;
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
    /** 【当前无调用点，保留备查】 */
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
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
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

    /** 【当前无调用点，保留备查】 */
    public static int countHordeZombiesInWorld(ServerLevel level) {
        return countHordeZombies(level);
    }

    /**
     * 按 UUID 可靠地查找尸潮目标实体（玩家或村民）。
     * <p>{@code ServerLevel.getEntity} 的 UUID 索引对已加载村民不可靠（见 {@link #buildLoadedVillagerMap}），
     * 因此未命中时兜底遍历实体表，避免村民目标被误判为「已消失」。
     */
    public static net.minecraft.world.entity.Entity findTarget(ServerLevel level, UUID id) {
        // 玩家优先走 PlayerList：这是唯一 100% 可靠的来源，
        // 不受实体 UUID 索引、区块加载状态、玩家走多远影响。
        // 只排除「跨维度」——不同维度的目标僵尸追不到，锁了也没意义。
        if (level.getServer() != null) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player != null) {
                return player.level() == level ? player : null;
            }
        }
        // 非玩家（村民等）仍走实体索引 + 兜底遍历
        var entity = level.getEntity(id);
        if (entity != null) return entity;
        for (var e : level.getEntities().getAll()) {
            if (e == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (e.getUUID().equals(id)) return e;
        }
        return null;
    }

    /** 构建已加载村民 UUID -> 实体映射（ServerLevel.getEntity 的 UUID 索引对已加载村民不可靠，须遍历实体表） */
    private static Map<UUID, net.minecraft.world.entity.npc.Villager> buildLoadedVillagerMap(ServerLevel level) {
        Map<UUID, net.minecraft.world.entity.npc.Villager> map = new HashMap<>();
        for (var entity : level.getEntities().getAll()) {
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
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
     * 同时清理：无主的内部刷怪状态、目标已失效的孤儿尸潮僵尸。
     * <p><b>不清理 {@code hordeZombieUuids}</b>：{@code level.getEntity} 对未加载区块的僵尸也返回 null，
     * 误删会把仍在活跃波次里的僵尸踢出四散/清理跟踪（详见方法末尾注释）。
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
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
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
                // 【v0.1.63 #179】孤儿被丢弃时同步清掉新增的 pending / 旁路索引。
                pendingDespawnZombies.remove(entity.getUUID());
                scatterZombieTarget.remove(entity.getUUID());
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
                VillagerScheduleData.ScheduleEntry sched = schedule.getScheduleOrNull(targetId);
                if (sched != null && sched.waveActive()) {
                    schedule.put(targetId, new VillagerScheduleData.ScheduleEntry(
                            sched.modDay(), sched.lastTriggerDay(), sched.waveCount(), false));
                }
            }
        }
    }

    /**
     * 【当前无调用点，保留备查】当前所有「还有待生成僵尸」的目标 UUID（快照）。
     * <p>v0.1.60 起 {@code HordeZombieGoal} 改用 {@link #getActiveWaveTargetIds(ServerLevel)}
     * （按 {@code waveActive} 判定，覆盖「刷完但僵尸还活着」的阶段）。
     * 本方法只返回 {@code activeHordes} 的 key，保留供调试使用。
     */
    public static java.util.Set<UUID> getActiveTargetIds() {
        return new java.util.HashSet<>(activeHordes.keySet());
    }

    /**
     * 当前所有「波次仍在进行」的目标（**刷完但僵尸还活着也算**）。
     * <p>【v0.1.60 修复 #126】{@link #getActiveTargetIds()} 读的是 {@code activeHordes}，
     * 而它在「剩余待生成数 == 0」时就把条目移除了 → 一波刷完后永远返回空，
     * 导致僵尸在追逐阶段找不到「其它波次的触发者」。
     * <p>这里改用 {@code waveActive}：玩家读 attachment，村民读调度表（权威源），
     * 再并上仍在刷怪的 activeHordes，覆盖「刚触发还没刷」与「刷完仍在追」两种状态。
     */
    public static java.util.Set<UUID> getActiveWaveTargetIds(ServerLevel level) {
        java.util.Set<UUID> out = new java.util.HashSet<>(activeHordes.keySet());
        if (level == null) return out;
        for (ServerPlayer p : level.getPlayers(p2 -> true)) {
            if (com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(p).waveActive()) {
                out.add(p.getUUID());
            }
        }
        VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                VillagerScheduleData.FACTORY, VillagerScheduleData.ID);
        for (var e : schedule.getAllSchedules().entrySet()) {
            if (e.getValue().waveActive()) out.add(e.getKey());
        }
        return out;
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
    /** 【v0.1.63 修复 #179】消费「23460 未加载、待区块加载后清除」标记；原子且只消费一次。 */
    public static boolean consumePendingDespawn(UUID zombieUuid) {
        return pendingDespawnZombies.remove(zombieUuid);
    }

    /** 【v0.1.63 修复 #179】登记四散僵尸的 targetId 旁路索引（重启重注册时也要补登记）。 */
    public static void noteScatterTarget(UUID zombieUuid, UUID targetId) {
        if (targetId != null) scatterZombieTarget.put(zombieUuid, targetId);
    }

    /** 【v0.1.63 修复 #179】当前是否仍处于合法四散窗口（公式与 HordeTimer 的 scatterStart 一致）。
     *  用于重启兜底：pending 集合随进程重启丢失后，state==1 却已过窗口的僵尸即上一轮漏清残留。
     */
    public static boolean isInScatterWindow(ServerLevel level) {
        long phase = level.getDayTime() % 24000L;
        int despawnSeconds = com.enik.zombietide.config.ConfigManager
                .getConfigData(level).getConfig().despawnSeconds();
        long start = 23460L - despawnSeconds * 20L;
        return phase >= start && phase < 23460L;
    }

    public static void retrackZombie(ServerLevel level, UUID zombieUuid, int state) {
        // 【v0.1.60 修复 #124】无论 state 都要登记进 hordeZombieUuids。
        // 之前 state==1 只加 scatteringZombies → 23460 的 countHordeZombiesByTarget
        // 遍历 hordeZombieUuids 得 0 → 按「幸存 0」把当夜和累积血债一起清零。
        hordeZombieUuids.add(zombieUuid);
        if (state == 1) {
            // 与 scatterZombies 相同的公式：倒计时到起火时刻 23460 移除
            long dayPhase = level.getDayTime() % 24000L;
            int ticks = (int)(23460 - dayPhase);
            if (ticks < 1) ticks = 1;
            scatteringZombies.put(zombieUuid, ticks);
        }
    }

    /** 供调试命令使用：清空所有活跃尸潮（原 Map，非副本）并重置所有目标的 waveActive 与触发记录（测试可重复触发） */
    public static void clearActiveHordes(ServerLevel level) {
        // 重置所有玩家和村民的 waveActive + lastTriggerDay（clear 用于测试，必须允许同一天重新触发）
        // 【v0.2.92】lastTriggerDay 用 modDay-1（"上次尸潮是昨天"）而不是 -1：
        //  - -1 表示"从未触发"，会让 hordeProgress 的起点回到世界开始（-540），
        //    清除后进度仍然很高（已过 N 天）→ DAY X 依旧偏红，用户反馈"清了还是红的"。
        //  - modDay-1 让进度从「昨天清场」起算 ≈ 0 → DAY X 立刻变淡/变白，符合直觉。
        //  - 不会误判尸潮日（lastTriggerDay != modDay），当天若是尸潮日仍可正常触发。
        if (level != null) {
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                if (player.level().dimension() == Level.OVERWORLD) {
                    var data = com.enik.zombietide.attachments.ModAttachments.getPlayerHordeData(player);
                    int clearTriggerDay = Math.max(-1, data.modDay() - 1);
                    player.setData(com.enik.zombietide.attachments.ModAttachments.PLAYER_HORDE.get(),
                            new com.enik.zombietide.attachments.PlayerHordeData(
                                    data.modDay(), clearTriggerDay, data.waveCount(), false
                            ));
                }
            }
            // 同步重置村民调度表（触发判定读调度表，权威数据源）
            com.enik.zombietide.config.VillagerScheduleData schedule = level.getDataStorage().computeIfAbsent(
                    com.enik.zombietide.config.VillagerScheduleData.FACTORY,
                    com.enik.zombietide.config.VillagerScheduleData.ID);
            for (var entry : schedule.getAllSchedules().entrySet()) {
                var sched = entry.getValue();
                int clearTriggerDay = Math.max(-1, sched.modDay() - 1);
                schedule.put(entry.getKey(), new com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry(
                        sched.modDay(), clearTriggerDay, sched.waveCount(), false));
                var villagerEntity = findTarget(level, entry.getKey());
                if (villagerEntity instanceof net.minecraft.world.entity.npc.Villager villager) {
                    villager.setData(com.enik.zombietide.attachments.ModAttachments.VILLAGER_HORDE.get(),
                            new com.enik.zombietide.attachments.VillagerHordeData(
                                    sched.modDay(), clearTriggerDay, sched.waveCount(), false));
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
        pendingDespawnZombies.clear();
        scatterZombieTarget.clear();
        villagerHordeRadii.clear();
        warnedNightBefore.clear();
        if (level != null) {
            com.enik.zombietide.config.PlayerDebtData.get(level).clearAll();
            com.enik.zombietide.config.HordeProgressData.get(level).clearAll();
            // debug clear 是「全清」语义：连 BGM 调度也一起重置，避免清完后残留播放计划
            HordeBgmScheduler.reset(level != null ? level.getServer() : null);
            // 【v0.2.92】威胁态也要一起清：否则清除尸潮后 drone/氛围还挂着，
            // 因为 HordeThreatDetector 有自己的 3 秒去抖状态，不会随僵尸消失立刻归零。
            // 传 server 以便同时给客户端下发强度 0（否则雾/暗角仍按 1 渲染）。
            HordeThreatDetector.reset(level.getServer());
        }

        // 【v0.2.92】清除后立即刷新 HUD，不等 20 tick 周期同步。
        // 否则玩家清完尸潮仍看到 DAY X 全红（客户端缓存还是清除前的 hordeDay/waveActive）。
        if (level != null) {
            var config = com.enik.zombietide.config.ConfigManager.getConfigData(level).getConfig();
            for (ServerPlayer player : level.getPlayers(p -> true)) {
                HordeTimer.syncPlayerHudState(level, config, player);
            }
            // 村民面板同样立即刷新（清场后 waveActive/hordeDay 都变了）
            ModMessages.broadcastAllVillagerModDays();
        }

        EnikZombietide.LOGGER.info("已清空所有活跃尸潮并重置所有目标 waveActive/lastTriggerDay（可重新触发）");
    }

    /** 【当前无调用点，保留备查】兼容旧调用（无 level 参数）。带参版本在 level==null 时跳过清理块，故这里单独重置 BGM 调度 */
    public static void clearActiveHordes() {
        clearActiveHordes(null);
        HordeBgmScheduler.reset(null); // 拿不到 server，退化为只清调度状态
    }
}
