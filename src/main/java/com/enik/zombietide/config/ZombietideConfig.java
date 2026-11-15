package com.enik.zombietide.config;

import com.enik.zombietide.EnikZombietide;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 存档独立配置（Level Data / SavedData）。
 * 所有数值参数按存档独立保存，不同存档可以配置不同参数。
 */
public record ZombietideConfig(
        PlayerHordeConfig player,
        VillagerHordeConfig villager,
        SpawnConfig spawn,
        WorldConfig world,
        WarningConfig warning,
        HudConfig hud
) {
    public static final Codec<ZombietideConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            PlayerHordeConfig.CODEC.fieldOf("player").forGetter(ZombietideConfig::player),
            VillagerHordeConfig.CODEC.fieldOf("villager").forGetter(ZombietideConfig::villager),
            SpawnConfig.CODEC.fieldOf("spawn").forGetter(ZombietideConfig::spawn),
            WorldConfig.CODEC.fieldOf("world").forGetter(ZombietideConfig::world),
            WarningConfig.CODEC.fieldOf("warning").forGetter(ZombietideConfig::warning),
            HudConfig.CODEC.fieldOf("hud").forGetter(ZombietideConfig::hud)
    ).apply(instance, ZombietideConfig::new));

    /**
     * 默认配置 —— 与 2026-11-14 实机调参（存档「新的世界」）完全一致。
     * <p>仅影响**新存档**与缺失配置的读档；已有存档不会被覆盖。
     */
    public static ZombietideConfig defaults() {
        return new ZombietideConfig(
                PlayerHordeConfig.defaults(),
                VillagerHordeConfig.defaults(),
                SpawnConfig.defaults(),
                WorldConfig.defaults(),
                WarningConfig.defaults(),
                HudConfig.defaults()
        );
    }

    // ========== 子 record 定义 ==========

    public record PlayerHordeConfig(
            boolean hordeEnabled,
            int intervalDays,
            boolean intervalRandom,
            int intervalMin,
            int intervalMax,
            int count,
            boolean countRandom,
            int countMin,
            int countMax
    ) {
        public static final Codec<PlayerHordeConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("hordeEnabled").forGetter(PlayerHordeConfig::hordeEnabled),
                Codec.INT.fieldOf("intervalDays").forGetter(PlayerHordeConfig::intervalDays),
                Codec.BOOL.fieldOf("intervalRandom").forGetter(PlayerHordeConfig::intervalRandom),
                Codec.INT.fieldOf("intervalMin").forGetter(PlayerHordeConfig::intervalMin),
                Codec.INT.fieldOf("intervalMax").forGetter(PlayerHordeConfig::intervalMax),
                Codec.INT.fieldOf("count").forGetter(PlayerHordeConfig::count),
                Codec.BOOL.fieldOf("countRandom").forGetter(PlayerHordeConfig::countRandom),
                Codec.INT.fieldOf("countMin").forGetter(PlayerHordeConfig::countMin),
                Codec.INT.fieldOf("countMax").forGetter(PlayerHordeConfig::countMax)
        ).apply(instance, PlayerHordeConfig::new));

        public static PlayerHordeConfig defaults() {
            return new PlayerHordeConfig(true, 7, false, 5, 9, 20, true, 32, 64);
        }

        public PlayerHordeConfig withHordeEnabled(boolean v) { return new PlayerHordeConfig(v, intervalDays, intervalRandom, intervalMin, intervalMax, count, countRandom, countMin, countMax); }
        public PlayerHordeConfig withIntervalDays(int v) { return new PlayerHordeConfig(hordeEnabled, v, intervalRandom, intervalMin, intervalMax, count, countRandom, countMin, countMax); }
        public PlayerHordeConfig withIntervalRandom(boolean v) { return new PlayerHordeConfig(hordeEnabled, intervalDays, v, intervalMin, intervalMax, count, countRandom, countMin, countMax); }
        public PlayerHordeConfig withIntervalMin(int v) { return new PlayerHordeConfig(hordeEnabled, intervalDays, intervalRandom, v, intervalMax, count, countRandom, countMin, countMax); }
        public PlayerHordeConfig withIntervalMax(int v) { return new PlayerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, v, count, countRandom, countMin, countMax); }
        public PlayerHordeConfig withCount(int v) { return new PlayerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, v, countRandom, countMin, countMax); }
        public PlayerHordeConfig withCountRandom(boolean v) { return new PlayerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, count, v, countMin, countMax); }
        public PlayerHordeConfig withCountMin(int v) { return new PlayerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, count, countRandom, v, countMax); }
        public PlayerHordeConfig withCountMax(int v) { return new PlayerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, count, countRandom, countMin, v); }
    }

    public record VillagerHordeConfig(
            boolean hordeEnabled,
            int intervalDays,
            boolean intervalRandom,
            int intervalMin,
            int intervalMax,
            int count,
            boolean countRandom,
            int countMin,
            int countMax
    ) {
        public static final Codec<VillagerHordeConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("hordeEnabled").forGetter(VillagerHordeConfig::hordeEnabled),
                Codec.INT.fieldOf("intervalDays").forGetter(VillagerHordeConfig::intervalDays),
                Codec.BOOL.fieldOf("intervalRandom").forGetter(VillagerHordeConfig::intervalRandom),
                Codec.INT.fieldOf("intervalMin").forGetter(VillagerHordeConfig::intervalMin),
                Codec.INT.fieldOf("intervalMax").forGetter(VillagerHordeConfig::intervalMax),
                Codec.INT.fieldOf("count").forGetter(VillagerHordeConfig::count),
                Codec.BOOL.fieldOf("countRandom").forGetter(VillagerHordeConfig::countRandom),
                Codec.INT.fieldOf("countMin").forGetter(VillagerHordeConfig::countMin),
                Codec.INT.fieldOf("countMax").forGetter(VillagerHordeConfig::countMax)
        ).apply(instance, VillagerHordeConfig::new));

        public static VillagerHordeConfig defaults() {
            return new VillagerHordeConfig(true, 7, false, 5, 9, 16, true, 16, 32);
        }

        public VillagerHordeConfig withHordeEnabled(boolean v) { return new VillagerHordeConfig(v, intervalDays, intervalRandom, intervalMin, intervalMax, count, countRandom, countMin, countMax); }
        public VillagerHordeConfig withIntervalDays(int v) { return new VillagerHordeConfig(hordeEnabled, v, intervalRandom, intervalMin, intervalMax, count, countRandom, countMin, countMax); }
        public VillagerHordeConfig withIntervalRandom(boolean v) { return new VillagerHordeConfig(hordeEnabled, intervalDays, v, intervalMin, intervalMax, count, countRandom, countMin, countMax); }
        public VillagerHordeConfig withIntervalMin(int v) { return new VillagerHordeConfig(hordeEnabled, intervalDays, intervalRandom, v, intervalMax, count, countRandom, countMin, countMax); }
        public VillagerHordeConfig withIntervalMax(int v) { return new VillagerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, v, count, countRandom, countMin, countMax); }
        public VillagerHordeConfig withCount(int v) { return new VillagerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, v, countRandom, countMin, countMax); }
        public VillagerHordeConfig withCountRandom(boolean v) { return new VillagerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, count, v, countMin, countMax); }
        public VillagerHordeConfig withCountMin(int v) { return new VillagerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, count, countRandom, v, countMax); }
        public VillagerHordeConfig withCountMax(int v) { return new VillagerHordeConfig(hordeEnabled, intervalDays, intervalRandom, intervalMin, intervalMax, count, countRandom, countMin, v); }
    }

    public record SpawnConfig(
            int batchTick,
            int batchSize,
            int villagerBatchSize,
            int playerRadius,
            int villagerRadius,
            boolean spawnByBiome,
            int minSpawnDist,
            int spawnAttempts,
            float distanceWeight,
            float darkWeight
    ) {
        public static final Codec<SpawnConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("batchTick").forGetter(SpawnConfig::batchTick),
                Codec.INT.fieldOf("batchSize").forGetter(SpawnConfig::batchSize),
                Codec.INT.fieldOf("villagerBatchSize").forGetter(SpawnConfig::villagerBatchSize),
                Codec.INT.fieldOf("playerRadius").forGetter(SpawnConfig::playerRadius),
                Codec.INT.fieldOf("villagerRadius").forGetter(SpawnConfig::villagerRadius),
                Codec.BOOL.fieldOf("spawnByBiome").forGetter(SpawnConfig::spawnByBiome),
                Codec.INT.fieldOf("minSpawnDist").forGetter(SpawnConfig::minSpawnDist),
                Codec.INT.fieldOf("spawnAttempts").forGetter(SpawnConfig::spawnAttempts),
                Codec.FLOAT.fieldOf("distanceWeight").forGetter(SpawnConfig::distanceWeight),
                Codec.FLOAT.fieldOf("darkWeight").forGetter(SpawnConfig::darkWeight)
        ).apply(instance, SpawnConfig::new));

        /**
         * 默认值。注意：v0.1.20 起「最大生成距离」与「玩家/村民生成半径」合并为一项，
         * 半径本身就是最远落点距离。
         */
        public static SpawnConfig defaults() {
            return new SpawnConfig(200, 4, 2, 150, 150, true, 60, 120, 0.6f, 0.6f);
        }

        public SpawnConfig withBatchTick(int v) { return new SpawnConfig(v, batchSize, villagerBatchSize, playerRadius, villagerRadius, spawnByBiome, minSpawnDist, spawnAttempts, distanceWeight, darkWeight); }
        public SpawnConfig withBatchSize(int v) { return new SpawnConfig(batchTick, v, villagerBatchSize, playerRadius, villagerRadius, spawnByBiome, minSpawnDist, spawnAttempts, distanceWeight, darkWeight); }
        public SpawnConfig withVillagerBatchSize(int v) { return new SpawnConfig(batchTick, batchSize, v, playerRadius, villagerRadius, spawnByBiome, minSpawnDist, spawnAttempts, distanceWeight, darkWeight); }
        public SpawnConfig withPlayerRadius(int v) { return new SpawnConfig(batchTick, batchSize, villagerBatchSize, v, villagerRadius, spawnByBiome, minSpawnDist, spawnAttempts, distanceWeight, darkWeight); }
        public SpawnConfig withVillagerRadius(int v) { return new SpawnConfig(batchTick, batchSize, villagerBatchSize, playerRadius, v, spawnByBiome, minSpawnDist, spawnAttempts, distanceWeight, darkWeight); }
        public SpawnConfig withSpawnByBiome(boolean v) { return new SpawnConfig(batchTick, batchSize, villagerBatchSize, playerRadius, villagerRadius, v, minSpawnDist, spawnAttempts, distanceWeight, darkWeight); }
        public SpawnConfig withMinSpawnDist(int v) { return new SpawnConfig(batchTick, batchSize, villagerBatchSize, playerRadius, villagerRadius, spawnByBiome, v, spawnAttempts, distanceWeight, darkWeight); }
        public SpawnConfig withSpawnAttempts(int v) { return new SpawnConfig(batchTick, batchSize, villagerBatchSize, playerRadius, villagerRadius, spawnByBiome, minSpawnDist, v, distanceWeight, darkWeight); }
        public SpawnConfig withDistanceWeight(float v) { return new SpawnConfig(batchTick, batchSize, villagerBatchSize, playerRadius, villagerRadius, spawnByBiome, minSpawnDist, spawnAttempts, v, darkWeight); }
        public SpawnConfig withDarkWeight(float v) { return new SpawnConfig(batchTick, batchSize, villagerBatchSize, playerRadius, villagerRadius, spawnByBiome, minSpawnDist, spawnAttempts, distanceWeight, v); }
    }

    public record WorldConfig(
            int globalCap,
            boolean daylightBurn,
            boolean blockSleep,
            String debtMode,
            int debtCap,
            int despawnSeconds,
            int villagerWarningRange,
            int villagerSleepBlockRange
    ) {
        public static final Codec<WorldConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("globalCap").forGetter(WorldConfig::globalCap),
                Codec.BOOL.fieldOf("daylightBurn").forGetter(WorldConfig::daylightBurn),
                Codec.BOOL.fieldOf("blockSleep").forGetter(WorldConfig::blockSleep),
                Codec.STRING.fieldOf("debtMode").forGetter(WorldConfig::debtMode),
                Codec.INT.fieldOf("debtCap").forGetter(WorldConfig::debtCap),
                Codec.INT.fieldOf("despawnSeconds").forGetter(WorldConfig::despawnSeconds),
                Codec.INT.fieldOf("villagerWarningRange").forGetter(WorldConfig::villagerWarningRange),
                Codec.INT.fieldOf("villagerSleepBlockRange").forGetter(WorldConfig::villagerSleepBlockRange)
        ).apply(instance, WorldConfig::new));

        public static WorldConfig defaults() {
            return new WorldConfig(512, true, true, "cumulative", 32, 10, 16, 32);
        }

        public WorldConfig withGlobalCap(int v) { return new WorldConfig(v, daylightBurn, blockSleep, debtMode, debtCap, despawnSeconds, villagerWarningRange, villagerSleepBlockRange); }
        public WorldConfig withDaylightBurn(boolean v) { return new WorldConfig(globalCap, v, blockSleep, debtMode, debtCap, despawnSeconds, villagerWarningRange, villagerSleepBlockRange); }
        public WorldConfig withBlockSleep(boolean v) { return new WorldConfig(globalCap, daylightBurn, v, debtMode, debtCap, despawnSeconds, villagerWarningRange, villagerSleepBlockRange); }
        public WorldConfig withDebtCap(int v) { return new WorldConfig(globalCap, daylightBurn, blockSleep, debtMode, v, despawnSeconds, villagerWarningRange, villagerSleepBlockRange); }
        public WorldConfig withDebtMode(String v) { return new WorldConfig(globalCap, daylightBurn, blockSleep, v, debtCap, despawnSeconds, villagerWarningRange, villagerSleepBlockRange); }
        public WorldConfig withDespawnSeconds(int v) { return new WorldConfig(globalCap, daylightBurn, blockSleep, debtMode, debtCap, v, villagerWarningRange, villagerSleepBlockRange); }
        public WorldConfig withVillagerWarningRange(int v) { return new WorldConfig(globalCap, daylightBurn, blockSleep, debtMode, debtCap, despawnSeconds, v, villagerSleepBlockRange); }
        public WorldConfig withVillagerSleepBlockRange(int v) { return new WorldConfig(globalCap, daylightBurn, blockSleep, debtMode, debtCap, despawnSeconds, villagerWarningRange, v); }
    }

    public record WarningConfig(
            boolean warningEnabled,
            boolean warningTitle,
            boolean warningSound,          // 尸潮触发音效（原「声音警告」）
            boolean blockNaturalSpawn,
            boolean morningWarningSound,   // 清晨提醒音效
            boolean scatterSound,          // 四散音效
            boolean hordeBgmSound          // 尸潮背景音乐（仅玩家尸潮触发者）
    ) {
        public static final Codec<WarningConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("warningEnabled").forGetter(WarningConfig::warningEnabled),
                Codec.BOOL.fieldOf("warningTitle").forGetter(WarningConfig::warningTitle),
                Codec.BOOL.fieldOf("warningSound").forGetter(WarningConfig::warningSound),
                Codec.BOOL.fieldOf("blockNaturalSpawn").forGetter(WarningConfig::blockNaturalSpawn),
                // 新增字段用 optionalFieldOf：旧存档 NBT 缺少这些键时读取默认值 true，不会解码失败
                Codec.BOOL.optionalFieldOf("morningWarningSound", true).forGetter(WarningConfig::morningWarningSound),
                Codec.BOOL.optionalFieldOf("scatterSound", true).forGetter(WarningConfig::scatterSound),
                Codec.BOOL.optionalFieldOf("hordeBgmSound", true).forGetter(WarningConfig::hordeBgmSound)
        ).apply(instance, WarningConfig::new));

        public static WarningConfig defaults() {
            return new WarningConfig(true, true, true, true, true, true, true);
        }

        public WarningConfig withWarningEnabled(boolean v) { return new WarningConfig(v, warningTitle, warningSound, blockNaturalSpawn, morningWarningSound, scatterSound, hordeBgmSound); }
        public WarningConfig withWarningTitle(boolean v) { return new WarningConfig(warningEnabled, v, warningSound, blockNaturalSpawn, morningWarningSound, scatterSound, hordeBgmSound); }
        public WarningConfig withWarningSound(boolean v) { return new WarningConfig(warningEnabled, warningTitle, v, blockNaturalSpawn, morningWarningSound, scatterSound, hordeBgmSound); }
        public WarningConfig withBlockNaturalSpawn(boolean v) { return new WarningConfig(warningEnabled, warningTitle, warningSound, v, morningWarningSound, scatterSound, hordeBgmSound); }
        public WarningConfig withMorningWarningSound(boolean v) { return new WarningConfig(warningEnabled, warningTitle, warningSound, blockNaturalSpawn, v, scatterSound, hordeBgmSound); }
        public WarningConfig withScatterSound(boolean v) { return new WarningConfig(warningEnabled, warningTitle, warningSound, blockNaturalSpawn, morningWarningSound, v, hordeBgmSound); }
        public WarningConfig withHordeBgmSound(boolean v) { return new WarningConfig(warningEnabled, warningTitle, warningSound, blockNaturalSpawn, morningWarningSound, scatterSound, v); }
    }

    public record HudConfig(
            boolean hudEnabled,
            boolean showVillagerHorde,
            String hudPosition,
            int hudX,
            int hudY,
            int villagerHordeX,
            int villagerHordeY,
            float hudScale,
            float villagerHordeScale,
            boolean hudBold,
            boolean villagerHordeBold,
            int villagerHordeDisplayRange,
            int villagerHordeMaxEntries
    ) {
        public static final Codec<HudConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.fieldOf("hudEnabled").forGetter(HudConfig::hudEnabled),
                Codec.BOOL.fieldOf("showVillagerHorde").forGetter(HudConfig::showVillagerHorde),
                Codec.STRING.fieldOf("hudPosition").forGetter(HudConfig::hudPosition),
                Codec.INT.fieldOf("hudX").forGetter(HudConfig::hudX),
                Codec.INT.fieldOf("hudY").forGetter(HudConfig::hudY),
                Codec.INT.fieldOf("villagerHordeX").forGetter(HudConfig::villagerHordeX),
                Codec.INT.fieldOf("villagerHordeY").forGetter(HudConfig::villagerHordeY),
                Codec.FLOAT.fieldOf("hudScale").forGetter(HudConfig::hudScale),
                Codec.FLOAT.fieldOf("villagerHordeScale").forGetter(HudConfig::villagerHordeScale),
                Codec.BOOL.fieldOf("hudBold").forGetter(HudConfig::hudBold),
                Codec.BOOL.fieldOf("villagerHordeBold").forGetter(HudConfig::villagerHordeBold),
                Codec.INT.fieldOf("villagerHordeDisplayRange").forGetter(HudConfig::villagerHordeDisplayRange),
                Codec.INT.fieldOf("villagerHordeMaxEntries").forGetter(HudConfig::villagerHordeMaxEntries)
        ).apply(instance, HudConfig::new));

        public static HudConfig defaults() {
            // 村民列表默认关闭：大多数玩家只关心自己的 DAY X，开着反而挡视线
            return new HudConfig(true, false, "top_left", 5, 4, 4, 50, 5.0f, 1.0f, true, false, 16, 5);
        }

        public HudConfig withHudEnabled(boolean v) { return new HudConfig(v, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withShowVillagerHorde(boolean v) { return new HudConfig(hudEnabled, v, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withHudX(int v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, v, hudY, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withHudY(int v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, v, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withVillagerHordeX(int v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, v, villagerHordeY, hudScale, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withVillagerHordeY(int v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, v, hudScale, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withHudScale(float v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, v, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withVillagerHordeScale(float v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, hudScale, v, hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withHudBold(boolean v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, v, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withVillagerHordeBold(boolean v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, hudBold, v, villagerHordeDisplayRange, villagerHordeMaxEntries); }
        public HudConfig withVillagerHordeDisplayRange(int v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, hudBold, villagerHordeBold, v, villagerHordeMaxEntries); }
        public HudConfig withVillagerHordeMaxEntries(int v) { return new HudConfig(hudEnabled, showVillagerHorde, hudPosition, hudX, hudY, villagerHordeX, villagerHordeY, hudScale, villagerHordeScale, hudBold, villagerHordeBold, villagerHordeDisplayRange, v); }
    }

    // ========== 访问器（兼容旧的 flat 字段访问） ==========

    public boolean playerHordeEnabled() { return player.hordeEnabled(); }
    public int playerIntervalDays() { return player.intervalDays(); }
    public boolean playerIntervalRandom() { return player.intervalRandom(); }
    public int playerIntervalMin() { return player.intervalMin(); }
    public int playerIntervalMax() { return player.intervalMax(); }
    public int playerCount() { return player.count(); }
    public boolean playerCountRandom() { return player.countRandom(); }
    public int playerCountMin() { return player.countMin(); }
    public int playerCountMax() { return player.countMax(); }

    public boolean villagerHordeEnabled() { return villager.hordeEnabled(); }
    public int villagerIntervalDays() { return villager.intervalDays(); }
    public boolean villagerIntervalRandom() { return villager.intervalRandom(); }
    public int villagerIntervalMin() { return villager.intervalMin(); }
    public int villagerIntervalMax() { return villager.intervalMax(); }
    public int villagerCount() { return villager.count(); }
    public boolean villagerCountRandom() { return villager.countRandom(); }
    public int villagerCountMin() { return villager.countMin(); }
    public int villagerCountMax() { return villager.countMax(); }

    public int batchTick() { return spawn.batchTick(); }
    public int batchSize() { return spawn.batchSize(); }
    public int villagerBatchSize() { return spawn.villagerBatchSize(); }
    public int playerRadius() { return spawn.playerRadius(); }
    public int villagerRadius() { return spawn.villagerRadius(); }
    public boolean spawnByBiome() { return spawn.spawnByBiome(); }

    // 新增 SpawnConfig 访问器
    public int minSpawnDist() { return spawn.minSpawnDist(); }
    public int spawnAttempts() { return spawn.spawnAttempts(); }
    public float distanceWeight() { return spawn.distanceWeight(); }
    public float darkWeight() { return spawn.darkWeight(); }

    public int globalCap() { return world.globalCap(); }
    public boolean daylightBurn() { return world.daylightBurn(); }
    public boolean blockSleep() { return world.blockSleep(); }
    public String debtMode() { return world.debtMode(); }
    public int debtCap() { return world.debtCap(); }
    /**
     * 四散提前秒数（已钳制到安全范围）。
     *
     * <p>四散开始时刻 = {@code 23460 - despawnSeconds * 20}。若不钳制，用户在设置界面填大值会让
     * 「四散窗口」覆盖全天：
     * <ul>
     *   <li>{@code despawnSeconds >= 1173} → 起始时刻 {@code <= 0}，判定恒真；</li>
     *   <li>配合 {@code HordeSpawner} 的四散窗口兜底，{@code update()} 会立即作废所有批次，
     *       导致<b>尸潮永远刷不出僵尸</b>（核心玩法静默失效）。</li>
     * </ul>
     *
     * <p>上限取 <b>473</b>：黄昏触发窗口是 {@code 13000..14000}，要求四散开始晚于它
     * （{@code 23460 - 473*20 = 14000}），否则尸潮刚触发就进入四散，同样刷不出来。
     * 下限取 1（0 或负值会让四散永不触发）。
     *
     * <p>用访问器钳制而非 Codec：旧存档若已写入非法值，改 Codec 会让存档直接加载失败；
     * 钳制在读取侧则始终安全，且设置界面回显的也是生效值。
     */
    public int despawnSeconds() {
        int raw = world.despawnSeconds();
        if (raw < 1) return 1;
        return Math.min(raw, MAX_DESPAWN_SECONDS);
    }

    /** 四散提前秒数的安全上限，见 {@link #despawnSeconds()} */
    public static final int MAX_DESPAWN_SECONDS = 473;
    public int villagerWarningRange() { return world.villagerWarningRange(); }
    public int villagerSleepBlockRange() { return world.villagerSleepBlockRange(); }

    public boolean warningEnabled() { return warning.warningEnabled(); }
    public boolean warningTitle() { return warning.warningTitle(); }
    public boolean warningSound() { return warning.warningSound(); }
    public boolean blockNaturalSpawn() { return warning.blockNaturalSpawn(); }
    public boolean morningWarningSound() { return warning.morningWarningSound(); }
    public boolean scatterSound() { return warning.scatterSound(); }
    public boolean hordeBgmSound() { return warning.hordeBgmSound(); }

    public boolean hudEnabled() { return hud.hudEnabled(); }
    public String hudPosition() { return hud.hudPosition(); }
    public int hudX() { return hud.hudX(); }
    public int hudY() { return hud.hudY(); }

    public boolean showVillagerHorde() { return hud.showVillagerHorde(); }
    public int villagerHordeDisplayRange() { return hud.villagerHordeDisplayRange(); }
    public int villagerHordeMaxEntries() { return hud.villagerHordeMaxEntries(); }

    public int villagerHordeX() { return hud.villagerHordeX(); }
    public int villagerHordeY() { return hud.villagerHordeY(); }
    public float hudScale() { return hud.hudScale(); }
    public float villagerHordeScale() { return hud.villagerHordeScale(); }
    public boolean hudBold() { return hud.hudBold(); }
    public boolean villagerHordeBold() { return hud.villagerHordeBold(); }

    // ========== Wither 方法（用于 GUI 编辑时创建副本） ==========

    public ZombietideConfig withPlayer(PlayerHordeConfig p) { return new ZombietideConfig(p, villager, spawn, world, warning, hud); }
    public ZombietideConfig withVillager(VillagerHordeConfig v) { return new ZombietideConfig(player, v, spawn, world, warning, hud); }
    public ZombietideConfig withSpawn(SpawnConfig s) { return new ZombietideConfig(player, villager, s, world, warning, hud); }
    public ZombietideConfig withWorld(WorldConfig w) { return new ZombietideConfig(player, villager, spawn, w, warning, hud); }
    public ZombietideConfig withWarning(WarningConfig w) { return new ZombietideConfig(player, villager, spawn, world, w, hud); }
    public ZombietideConfig withHud(HudConfig h) { return new ZombietideConfig(player, villager, spawn, world, warning, h); }

    // PlayerHordeConfig wither
    public ZombietideConfig withPlayerHordeEnabled(boolean v) { return withPlayer(player.withHordeEnabled(v)); }
    public ZombietideConfig withPlayerIntervalDays(int v) { return withPlayer(player.withIntervalDays(v)); }
    public ZombietideConfig withPlayerIntervalRandom(boolean v) { return withPlayer(player.withIntervalRandom(v)); }
    public ZombietideConfig withPlayerIntervalMin(int v) { return withPlayer(player.withIntervalMin(v)); }
    public ZombietideConfig withPlayerIntervalMax(int v) { return withPlayer(player.withIntervalMax(v)); }
    public ZombietideConfig withPlayerCount(int v) { return withPlayer(player.withCount(v)); }
    public ZombietideConfig withPlayerCountRandom(boolean v) { return withPlayer(player.withCountRandom(v)); }
    public ZombietideConfig withPlayerCountMin(int v) { return withPlayer(player.withCountMin(v)); }
    public ZombietideConfig withPlayerCountMax(int v) { return withPlayer(player.withCountMax(v)); }

    // VillagerHordeConfig wither
    public ZombietideConfig withVillagerHordeEnabled(boolean v) { return withVillager(villager.withHordeEnabled(v)); }
    public ZombietideConfig withVillagerIntervalDays(int v) { return withVillager(villager.withIntervalDays(v)); }
    public ZombietideConfig withVillagerIntervalRandom(boolean v) { return withVillager(villager.withIntervalRandom(v)); }
    public ZombietideConfig withVillagerIntervalMin(int v) { return withVillager(villager.withIntervalMin(v)); }
    public ZombietideConfig withVillagerIntervalMax(int v) { return withVillager(villager.withIntervalMax(v)); }
    public ZombietideConfig withVillagerCount(int v) { return withVillager(villager.withCount(v)); }
    public ZombietideConfig withVillagerCountRandom(boolean v) { return withVillager(villager.withCountRandom(v)); }
    public ZombietideConfig withVillagerCountMin(int v) { return withVillager(villager.withCountMin(v)); }
    public ZombietideConfig withVillagerCountMax(int v) { return withVillager(villager.withCountMax(v)); }

    // SpawnConfig wither
    public ZombietideConfig withBatchTick(int v) { return withSpawn(spawn.withBatchTick(v)); }
    public ZombietideConfig withBatchSize(int v) { return withSpawn(spawn.withBatchSize(v)); }
    public ZombietideConfig withVillagerBatchSize(int v) { return withSpawn(spawn.withVillagerBatchSize(v)); }
    public ZombietideConfig withPlayerRadius(int v) { return withSpawn(spawn.withPlayerRadius(v)); }
    public ZombietideConfig withVillagerRadius(int v) { return withSpawn(spawn.withVillagerRadius(v)); }
    public ZombietideConfig withSpawnByBiome(boolean v) { return withSpawn(spawn.withSpawnByBiome(v)); }
    public ZombietideConfig withMinSpawnDist(int v) { return withSpawn(spawn.withMinSpawnDist(v)); }
    public ZombietideConfig withSpawnAttempts(int v) { return withSpawn(spawn.withSpawnAttempts(v)); }
    public ZombietideConfig withDistanceWeight(float v) { return withSpawn(spawn.withDistanceWeight(v)); }
    public ZombietideConfig withDarkWeight(float v) { return withSpawn(spawn.withDarkWeight(v)); }

    // WorldConfig wither
    public ZombietideConfig withGlobalCap(int v) { return withWorld(world.withGlobalCap(v)); }
    public ZombietideConfig withDaylightBurn(boolean v) { return withWorld(world.withDaylightBurn(v)); }
    public ZombietideConfig withBlockSleep(boolean v) { return withWorld(world.withBlockSleep(v)); }
    public ZombietideConfig withDebtCap(int v) { return withWorld(world.withDebtCap(v)); }
    public ZombietideConfig withDebtMode(String v) { return withWorld(world.withDebtMode(v)); }
    public ZombietideConfig withDespawnSeconds(int v) { return withWorld(world.withDespawnSeconds(v)); }
    public ZombietideConfig withVillagerWarningRange(int v) { return withWorld(world.withVillagerWarningRange(v)); }
    public ZombietideConfig withVillagerSleepBlockRange(int v) { return withWorld(world.withVillagerSleepBlockRange(v)); }

    // WarningConfig wither
    public ZombietideConfig withWarningEnabled(boolean v) { return withWarning(warning.withWarningEnabled(v)); }
    public ZombietideConfig withWarningTitle(boolean v) { return withWarning(warning.withWarningTitle(v)); }
    public ZombietideConfig withWarningSound(boolean v) { return withWarning(warning.withWarningSound(v)); }
    public ZombietideConfig withBlockNaturalSpawn(boolean v) { return withWarning(warning.withBlockNaturalSpawn(v)); }
    public ZombietideConfig withMorningWarningSound(boolean v) { return withWarning(warning.withMorningWarningSound(v)); }
    public ZombietideConfig withScatterSound(boolean v) { return withWarning(warning.withScatterSound(v)); }
    public ZombietideConfig withHordeBgmSound(boolean v) { return withWarning(warning.withHordeBgmSound(v)); }

    // HudConfig wither
    public ZombietideConfig withHudEnabled(boolean v) { return withHud(hud.withHudEnabled(v)); }
    public ZombietideConfig withShowVillagerHorde(boolean v) { return withHud(hud.withShowVillagerHorde(v)); }
    public ZombietideConfig withHudX(int v) { return withHud(hud.withHudX(v)); }
    public ZombietideConfig withHudY(int v) { return withHud(hud.withHudY(v)); }
    public ZombietideConfig withVillagerHordeX(int v) { return withHud(hud.withVillagerHordeX(v)); }
    public ZombietideConfig withVillagerHordeY(int v) { return withHud(hud.withVillagerHordeY(v)); }
    public ZombietideConfig withHudScale(float v) { return withHud(hud.withHudScale(v)); }
    public ZombietideConfig withVillagerHordeScale(float v) { return withHud(hud.withVillagerHordeScale(v)); }
    public ZombietideConfig withHudBold(boolean v) { return withHud(hud.withHudBold(v)); }
    public ZombietideConfig withVillagerHordeBold(boolean v) { return withHud(hud.withVillagerHordeBold(v)); }
    public ZombietideConfig withVillagerHordeDisplayRange(int v) { return withHud(hud.withVillagerHordeDisplayRange(v)); }
    public ZombietideConfig withVillagerHordeMaxEntries(int v) { return withHud(hud.withVillagerHordeMaxEntries(v)); }
}