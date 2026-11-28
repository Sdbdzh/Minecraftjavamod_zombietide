package com.enik.zombietide.client.gui.settings;

import com.enik.zombietide.config.ClientAtmosphereConfig;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.config.ZombietideConfig.HudConfig;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 设置页全部选项的**声明式定义**（共 63 项，6 个分类）。
 * <p>这是 P1 重构的核心：把原来散落在屏幕类 {@code init()} 里约 460 行的命令式
 * {@code addCheckbox/addIntField} 调用，收敛成一张按分类组织的静态表。
 * <p>新增一个设置项只需要在这里加一行，不用碰任何布局/渲染代码。
 * <p><b>顺序即渲染顺序</b>，分区标题与注释也作为行参与排序，保证与旧实现 100% 一致。
 */
public final class SettingsRegistry {

    private SettingsRegistry() {
    }

    // ==================== 对外入口 ====================

    /** 取某个分类的全部行（顺序即渲染顺序） */
    public static List<SettingRow> rowsFor(SettingsCategory category) {
        return switch (category) {
            case PLAYER -> PLAYER_ROWS;
            case VILLAGER -> VILLAGER_ROWS;
            case SPAWN -> SPAWN_ROWS;
            case WORLD -> WORLD_ROWS;
            case WARNING -> WARNING_ROWS;
            case HUD -> HUD_ROWS;
        };
    }

    // ==================== 玩家尸潮 ====================

    private static final List<SettingRow> PLAYER_ROWS = List.of(
            section("zombietide.settings.common.text1"),
            opt(wBool("zombietide.settings.player.item1.name", OptionScope.PLAYER,
                    ZombietideConfig::playerHordeEnabled, ZombietideConfig::withPlayerHordeEnabled)),

            section("zombietide.settings.common.text2"),
            opt(wInt("zombietide.settings.player.item2.name", OptionScope.PLAYER, 1,
                    ZombietideConfig::playerIntervalDays, ZombietideConfig::withPlayerIntervalDays)),
            opt(wBool("zombietide.settings.player.item3.name", OptionScope.PLAYER,
                    ZombietideConfig::playerIntervalRandom, ZombietideConfig::withPlayerIntervalRandom)),
            opt(wInt("zombietide.settings.player.item4.name", OptionScope.PLAYER, 1,
                    ZombietideConfig::playerIntervalMin, ZombietideConfig::withPlayerIntervalMin)),
            opt(wInt("zombietide.settings.player.item5.name", OptionScope.PLAYER, 1,
                    ZombietideConfig::playerIntervalMax, ZombietideConfig::withPlayerIntervalMax)),

            section("zombietide.settings.common.text3"),
            opt(wInt("zombietide.settings.player.item6.name", OptionScope.PLAYER, 1,
                    ZombietideConfig::playerCount, ZombietideConfig::withPlayerCount)),
            opt(wBool("zombietide.settings.player.item7.name", OptionScope.PLAYER,
                    ZombietideConfig::playerCountRandom, ZombietideConfig::withPlayerCountRandom)),
            opt(wInt("zombietide.settings.player.item8.name", OptionScope.PLAYER, 1,
                    ZombietideConfig::playerCountMin, ZombietideConfig::withPlayerCountMin)),
            opt(wInt("zombietide.settings.player.item9.name", OptionScope.PLAYER, 1,
                    ZombietideConfig::playerCountMax, ZombietideConfig::withPlayerCountMax))
    );

    // ==================== 村民尸潮 ====================

    private static final List<SettingRow> VILLAGER_ROWS = List.of(
            section("zombietide.settings.common.text4"),
            opt(wBool("zombietide.settings.villager.item1.name", OptionScope.VILLAGER,
                    ZombietideConfig::villagerHordeEnabled, ZombietideConfig::withVillagerHordeEnabled)),

            section("zombietide.settings.common.text5"),
            opt(wInt("zombietide.settings.villager.item2.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerIntervalDays, ZombietideConfig::withVillagerIntervalDays)),
            opt(wBool("zombietide.settings.villager.item3.name", OptionScope.VILLAGER,
                    ZombietideConfig::villagerIntervalRandom, ZombietideConfig::withVillagerIntervalRandom)),
            opt(wInt("zombietide.settings.villager.item4.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerIntervalMin, ZombietideConfig::withVillagerIntervalMin)),
            opt(wInt("zombietide.settings.villager.item5.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerIntervalMax, ZombietideConfig::withVillagerIntervalMax)),

            section("zombietide.settings.common.text6"),
            opt(wInt("zombietide.settings.villager.item6.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerCount, ZombietideConfig::withVillagerCount)),
            opt(wBool("zombietide.settings.villager.item7.name", OptionScope.VILLAGER,
                    ZombietideConfig::villagerCountRandom, ZombietideConfig::withVillagerCountRandom)),
            opt(wInt("zombietide.settings.villager.item8.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerCountMin, ZombietideConfig::withVillagerCountMin)),
            opt(wInt("zombietide.settings.villager.item9.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerCountMax, ZombietideConfig::withVillagerCountMax))
    );

    // ==================== 生成性能 ====================

    private static final int MAX_RADIUS = 512;

    private static final List<SettingRow> SPAWN_ROWS = List.of(
            section("zombietide.settings.common.text7"),
            opt(wInt("zombietide.settings.spawn.item1.name", OptionScope.WORLD, 1,
                    ZombietideConfig::batchTick, ZombietideConfig::withBatchTick)),
            opt(wInt("zombietide.settings.spawn.item2.name", OptionScope.WORLD, 1,
                    ZombietideConfig::batchSize, ZombietideConfig::withBatchSize)),
            opt(wInt("zombietide.settings.spawn.item3.name", OptionScope.WORLD, 1,
                    ZombietideConfig::villagerBatchSize, ZombietideConfig::withVillagerBatchSize)),

            section("zombietide.settings.common.text8"),
            opt(wInt("zombietide.settings.spawn.item4.name", OptionScope.WORLD, 1, MAX_RADIUS,
                    ZombietideConfig::playerRadius, ZombietideConfig::withPlayerRadius)),
            opt(wInt("zombietide.settings.spawn.item5.name", OptionScope.WORLD, 1, MAX_RADIUS,
                    ZombietideConfig::villagerRadius, ZombietideConfig::withVillagerRadius)),
            opt(wInt("zombietide.settings.spawn.item6.name", OptionScope.WORLD, 1,
                    ZombietideConfig::minSpawnDist, ZombietideConfig::withMinSpawnDist)),
            opt(wInt("zombietide.settings.spawn.item7.name", OptionScope.WORLD, 1,
                    ZombietideConfig::spawnAttempts, ZombietideConfig::withSpawnAttempts)),

            section("zombietide.settings.common.text9"),
            opt(wBool("zombietide.settings.spawn.item12.name", OptionScope.WORLD,
                    ZombietideConfig::screamerEnabled, ZombietideConfig::withScreamerEnabled)),
            opt(wInt("zombietide.settings.spawn.item13.name", OptionScope.WORLD, 10, 600,
                    ZombietideConfig::screamerCooldownMin, ZombietideConfig::withScreamerCooldownMin)),
            opt(wInt("zombietide.settings.spawn.item14.name", OptionScope.WORLD, 10, 600,
                    ZombietideConfig::screamerCooldownMax, ZombietideConfig::withScreamerCooldownMax)),
            opt(wBool("zombietide.settings.spawn.item8.name", OptionScope.WORLD,
                    ZombietideConfig::spawnByBiome, ZombietideConfig::withSpawnByBiome)),
            // 权重在配置里是 0.0~1.0 的 float，界面上按 ×100 展示与编辑
            opt(wInt("zombietide.settings.spawn.item9.name", OptionScope.WORLD, 0,
                    cfg -> Math.round(cfg.distanceWeight() * 100),
                    (cfg, v) -> cfg.withDistanceWeight(v / 100.0f))),
            opt(wInt("zombietide.settings.spawn.item10.name", OptionScope.WORLD, 0,
                    cfg -> Math.round(cfg.darkWeight() * 100),
                    (cfg, v) -> cfg.withDarkWeight(v / 100.0f))),

            section("zombietide.settings.common.text10"),
            opt(wInt("zombietide.settings.spawn.item11.name", OptionScope.WORLD, 1,
                    ZombietideConfig::globalCap, ZombietideConfig::withGlobalCap))
    );

    // ==================== 世界规则 ====================

    private static final List<SettingRow> WORLD_ROWS = List.of(
            section("zombietide.settings.common.text11"),
            opt(wBool("zombietide.settings.world.item1.name", OptionScope.WORLD,
                    ZombietideConfig::daylightBurn, ZombietideConfig::withDaylightBurn)),
            opt(wBool("zombietide.settings.world.item2.name", OptionScope.WORLD,
                    ZombietideConfig::blockSleep, ZombietideConfig::withBlockSleep)),
            opt(wInt("zombietide.settings.world.item3.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerSleepBlockRange, ZombietideConfig::withVillagerSleepBlockRange)),

            section("zombietide.settings.common.text12"),
            opt(debtModeEntry()),
            opt(wInt("zombietide.settings.world.item5.name", OptionScope.PLAYER, 0,
                    ZombietideConfig::debtCap, ZombietideConfig::withDebtCap)),

            section("zombietide.settings.common.text13"),
            opt(wInt("zombietide.settings.world.item6.name", OptionScope.WORLD, 1,
                    ZombietideConfig::despawnSeconds, ZombietideConfig::withDespawnSeconds)),

            section("zombietide.settings.common.text14"),
            opt(wBool("zombietide.settings.world.item7.name", OptionScope.WORLD,
                    ZombietideConfig::blockNaturalSpawn, ZombietideConfig::withBlockNaturalSpawn))
    );

    // ==================== 警告提示 ====================

    private static final List<SettingRow> WARNING_ROWS = List.of(
            section("zombietide.settings.common.text15"),
            opt(wBool("zombietide.settings.warning.item1.name", OptionScope.WORLD,
                    ZombietideConfig::warningEnabled, ZombietideConfig::withWarningEnabled)),
            opt(wBool("zombietide.settings.warning.item2.name", OptionScope.WORLD,
                    ZombietideConfig::warningTitle, ZombietideConfig::withWarningTitle)),

            section("zombietide.settings.common.text16"),
            opt(wBool("zombietide.settings.warning.item3.name", OptionScope.WORLD,
                    ZombietideConfig::warningSound, ZombietideConfig::withWarningSound)),
            opt(wBool("zombietide.settings.warning.item4.name", OptionScope.WORLD,
                    ZombietideConfig::morningWarningSound, ZombietideConfig::withMorningWarningSound)),
            opt(wBool("zombietide.settings.warning.item5.name", OptionScope.WORLD,
                    ZombietideConfig::scatterSound, ZombietideConfig::withScatterSound)),
            opt(wBool("zombietide.settings.warning.item6.name", OptionScope.WORLD,
                    ZombietideConfig::hordeBgmSound, ZombietideConfig::withHordeBgmSound)),

            section("zombietide.settings.common.text17"),
            opt(wInt("zombietide.settings.warning.item7.name", OptionScope.VILLAGER, 1,
                    ZombietideConfig::villagerWarningRange, ZombietideConfig::withVillagerWarningRange))
    );

    // ==================== HUD（含氛围） ====================
    // 注意：氛围 5 项目前仍在本分类末尾，与旧实现一致；P2 拆为独立分类。

    private static final List<SettingRow> HUD_ROWS = List.of(
            note("zombietide.settings.common.text18"),

            section("zombietide.settings.common.text19"),
            opt(hBool("zombietide.settings.hud.item1.name", HudConfig::hudEnabled, HudConfig::withHudEnabled)),
            opt(hBool("zombietide.settings.hud.item2.name", HudConfig::showVillagerHorde, HudConfig::withShowVillagerHorde)),
            opt(hBool("zombietide.settings.hud.item3.name", HudConfig::hudBold, HudConfig::withHudBold)),
            opt(dayGradientRedEntry()),
            opt(hBool("zombietide.settings.hud.item4.name", HudConfig::villagerHordeBold, HudConfig::withVillagerHordeBold)),

            section("zombietide.settings.common.text20"),
            opt(hInt("zombietide.settings.hud.item5.name", 0, HudConfig::hudX, HudConfig::withHudX)),
            opt(hInt("zombietide.settings.hud.item6.name", 0, HudConfig::hudY, HudConfig::withHudY)),
            // 缩放是 float，界面按 ×10 展示
            opt(hInt("zombietide.settings.hud.item7.name", 1,
                    hud -> Math.round(hud.hudScale() * 10),
                    (hud, v) -> hud.withHudScale(v / 10.0f))),
            opt(hInt("zombietide.settings.hud.item8.name", 0, HudConfig::villagerHordeX, HudConfig::withVillagerHordeX)),
            opt(hInt("zombietide.settings.hud.item9.name", 0, HudConfig::villagerHordeY, HudConfig::withVillagerHordeY)),
            opt(hInt("zombietide.settings.hud.item10.name", 1,
                    hud -> Math.round(hud.villagerHordeScale() * 10),
                    (hud, v) -> hud.withVillagerHordeScale(v / 10.0f))),

            section("zombietide.settings.common.text21"),
            opt(hInt("zombietide.settings.hud.item11.name", 1,
                    HudConfig::villagerHordeDisplayRange, HudConfig::withVillagerHordeDisplayRange)),
            opt(hInt("zombietide.settings.hud.item12.name", 1,
                    HudConfig::villagerHordeMaxEntries, HudConfig::withVillagerHordeMaxEntries)),

            section("zombietide.settings.common.text22"),
            opt(aBool("zombietide.settings.atmo.item1.name", AtmoToggle.ATMOSPHERE)),
            opt(aBool("zombietide.settings.atmo.item2.name", AtmoToggle.FOG)),
            opt(aBool("zombietide.settings.atmo.item4.name", AtmoToggle.CAMERA_SHAKE)),
            opt(aBool("zombietide.settings.atmo.item5.name", AtmoToggle.FLASH)),
            opt(aBool("zombietide.settings.atmo.item6.name", AtmoToggle.BORDER))
    );

    // ==================== 世界级配置辅助 ====================

    private static OptionEntry wBool(String nameKey, OptionScope scope,
                                     Predicate<ZombietideConfig> get,
                                     BiFunction<ZombietideConfig, Boolean, ZombietideConfig> set) {
        return new OptionEntry(nameKey, scope, ControlType.TOGGLE, 0, 0, false,
                h -> get.test(h.config()) ? 1 : 0,
                (h, v) -> h.setConfig(set.apply(h.config(), v != 0)),
                null, null);
    }

    private static OptionEntry wInt(String nameKey, OptionScope scope, int min,
                                    ToIntFunction<ZombietideConfig> get,
                                    BiFunction<ZombietideConfig, Integer, ZombietideConfig> set) {
        return wInt(nameKey, scope, min, Integer.MAX_VALUE, get, set);
    }

    private static OptionEntry wInt(String nameKey, OptionScope scope, int min, int max,
                                    ToIntFunction<ZombietideConfig> get,
                                    BiFunction<ZombietideConfig, Integer, ZombietideConfig> set) {
        return new OptionEntry(nameKey, scope, ControlType.INT, min, max, false,
                h -> get.applyAsInt(h.config()),
                (h, v) -> h.setConfig(set.apply(h.config(), v)),
                null, null);
    }

    // ==================== HUD 配置辅助 ====================

    private static OptionEntry hBool(String nameKey,
                                     Predicate<HudConfig> get,
                                     BiFunction<HudConfig, Boolean, HudConfig> set) {
        return new OptionEntry(nameKey, null, ControlType.TOGGLE, 0, 0, true,
                h -> get.test(h.hud()) ? 1 : 0,
                (h, v) -> h.setHud(set.apply(h.hud(), v != 0)),
                null, null);
    }

    private static OptionEntry hInt(String nameKey, int min,
                                    ToIntFunction<HudConfig> get,
                                    BiFunction<HudConfig, Integer, HudConfig> set) {
        return new OptionEntry(nameKey, null, ControlType.INT, min, Integer.MAX_VALUE, true,
                h -> get.applyAsInt(h.hud()),
                (h, v) -> h.setHud(set.apply(h.hud(), v)),
                null, null);
    }

    /** DAY X 渐进红：字段在屏幕/ClientHudConfig 上，不在 HudConfig record 里 */
    private static OptionEntry dayGradientRedEntry() {
        return new OptionEntry("zombietide.settings.hud.item14.name", null, ControlType.TOGGLE, 0, 0, true,
                h -> h.dayGradientRed() ? 1 : 0,
                (h, v) -> h.setDayGradientRed(v != 0),
                null, null);
    }

    // ==================== 氛围（纯客户端静态配置） ====================

    private enum AtmoToggle { ATMOSPHERE, FOG, CAMERA_SHAKE, FLASH, BORDER }

    private static OptionEntry aBool(String nameKey, AtmoToggle which) {
        return new OptionEntry(nameKey, null, ControlType.TOGGLE, 0, 0, true,
                h -> readAtmo(which) ? 1 : 0,
                (h, v) -> writeAtmo(which, v != 0),
                null, null);
    }

    private static boolean readAtmo(AtmoToggle which) {
        return switch (which) {
            case ATMOSPHERE -> ClientAtmosphereConfig.atmosphereEnabled();
            case FOG -> ClientAtmosphereConfig.rawFogEnabled();
            case CAMERA_SHAKE -> ClientAtmosphereConfig.rawCameraShakeEnabled();
            case FLASH -> ClientAtmosphereConfig.rawFlashEnabled();
            case BORDER -> ClientAtmosphereConfig.rawBorderEnabled();
        };
    }

    /** 5 个开关共用一次 {@code set()}，改动某一项时其余项必须传原始值，避免被总开关 AND 清空。 */
    private static void writeAtmo(AtmoToggle which, boolean value) {
        boolean atmo = which == AtmoToggle.ATMOSPHERE ? value : ClientAtmosphereConfig.atmosphereEnabled();
        boolean fog = which == AtmoToggle.FOG ? value : ClientAtmosphereConfig.rawFogEnabled();
        boolean shake = which == AtmoToggle.CAMERA_SHAKE ? value : ClientAtmosphereConfig.rawCameraShakeEnabled();
        boolean flash = which == AtmoToggle.FLASH ? value : ClientAtmosphereConfig.rawFlashEnabled();
        boolean border = which == AtmoToggle.BORDER ? value : ClientAtmosphereConfig.rawBorderEnabled();
        ClientAtmosphereConfig.set(atmo, fog, shake, flash, border);
    }

    // ==================== 血债模式（CYCLE） ====================

    private static OptionEntry debtModeEntry() {
        return new OptionEntry("zombietide.settings.world.item4.name", OptionScope.PLAYER,
                ControlType.CYCLE, 0, 0, false,
                h -> 0,
                (h, v) -> {
                    // 值由 cycleAction 推进，writer 不参与
                },
                h -> tr("zombietide.settings.debt.prefix") + debtModeLabel(h.config().debtMode()),
                h -> h.setConfig(h.config().withDebtMode(nextDebtMode(h.config().debtMode()))));
    }

    /** 血债模式的中文显示名 */
    public static String debtModeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "last_only" -> tr("zombietide.settings.debt.last_only");
            case "none" -> tr("zombietide.settings.debt.none");
            default -> tr("zombietide.settings.debt.cumulative");
        };
    }

    /** 循环顺序：cumulative → last_only → none → cumulative */
    public static String nextDebtMode(String mode) {
        return switch (mode == null ? "" : mode) {
            case "cumulative" -> "last_only";
            case "last_only" -> "none";
            default -> "cumulative";
        };
    }

    // ==================== 行构造辅助 ====================

    private static SettingRow section(String titleKey) {
        return new SettingRow.SectionRow(titleKey);
    }

    private static SettingRow note(String textKey) {
        return new SettingRow.NoteRow(textKey);
    }

    private static SettingRow opt(OptionEntry entry) {
        return new SettingRow.OptionRow(entry);
    }

    private static String tr(String key) {
        return I18n.get(key);
    }
}
