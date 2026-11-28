package com.enik.zombietide.horde;

import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.attachments.PlayerHordeData;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.network.ModMessages;
import com.enik.zombietide.sound.ModSounds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 尸潮预兆调度（v0.2.78）：黄昏前让世界"一点点不对劲"，而非触发瞬间才全开。
 * <p>
 * 判定（每 20 tick 评估一次）：
 * <ul>
 *   <li>今天是不是尸潮日——用{@link HordeTimer#isHordeDay(UUID,int,int,ZombietideConfig,boolean)} 5 参绕过窗口版；
 *       6 参版只在 13000~14000 返回 true，白天恒 false，不能用来判预兆。</li>
 *   <li>时刻窗口：dayPhase ∈ [12000, 13000]，即黄昏前约 1 小时（MC 1 游戏小时 = 1000 tick）；
 *       强度从 0 线性升到 1，触发窗口一开始即归零。</li>
 *   <li>只给主世界、非和平、warningEnabled 的玩家；村民与旁观玩家不参与。</li>
 * </ul>
 * 强度只在变化时下发（量化到 0.05 一档，约每秒最多一次），不每 tick 发包。
 * 预兆期用极低音量播一次 drone 作听觉铺垫（0.12，远低于波次期的 0.35），
 * 不循环、不持续——避免与波次期 drone 叠加。
 */
public final class HordeOmenScheduler {

    /** 尸潮日白天的基础预兆强度（DAY X 暗红提示，但不播 drone） */
    private static final float DAYTIME_BASE_STRENGTH = 0.30F;
    /** 预兆窗口起点：黄昏前约 1 小时（12000 = 日落开始） */
    private static final long OMEN_START_PHASE = 12000L;
    /** 预兆窗口终点：黄昏触发窗口起点 */
    private static final long OMEN_END_PHASE = 13000L;
    /** 强度量化档数（0.05 一档），减少发包 */
    private static final int STRENGTH_STEPS = 20;
    /** 预兆 drone 音量：远低于波次期 0.35，只作铺垫 */
    private static final float OMEN_DRONE_VOLUME = 0.12F;

    /** 玩家 UUID -> 上次下发的强度档位（0~20），用于去重 */
    private static final Map<UUID, Integer> LAST_STRENGTH_STEP = new HashMap<>();
    /** 玩家 UUID -> 预兆 drone 已在哪个档位播过（避免每个档位都重播） */
    private static final Map<UUID, Integer> DRONE_PLAYED_STEP = new HashMap<>();

    /** 由 ZombietideServerEvents.onServerTick 每 tick 调用，内部 20 tick 节流。 */
    public static void onServerTick(ServerLevel overworld, ZombietideConfig config, long tickCount) {
        if (tickCount % 20L != 0L) return;
        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        long phase = Math.floorMod(overworld.getDayTime(), 24000L);
        boolean inWindow = phase >= OMEN_START_PHASE && phase < OMEN_END_PHASE;
        // v0.2.80：尸潮日白天给基础强度 0.3（DAY X 暗红提示"今天有尸潮"），
        // 黄昏前 1 小时从 0.3 线性升到 1.0（暗红→亮红 + 雾色渐变 + drone 铺垫）。
        float rawStrength = inWindow
                ? 0.30F + 0.70F * (float) (phase - OMEN_START_PHASE) / (float) (OMEN_END_PHASE - OMEN_START_PHASE)
                : DAYTIME_BASE_STRENGTH;
        int step = Math.round(rawStrength * STRENGTH_STEPS);
        boolean inDuskWindow = inWindow;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();

            // 波次进行中：预兆无意义（drone 已由 BGM 调度器接管），清状态并归零
            PlayerHordeData data = ModAttachments.getPlayerHordeData(player);
            boolean eligible = config.warningEnabled()
                    && !data.waveActive()
                    && player.level().dimension() == Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL;

            // 是否今天是该玩家的尸潮日（绕过窗口，只看日历/间隔）
            boolean hordeDayToday = eligible && HordeTimer.isHordeDay(
                    id, data.modDay(), data.lastTriggerDay(), config, false);
            int effectiveStep = hordeDayToday ? step : 0;

            Integer last = LAST_STRENGTH_STEP.get(id);
            if (last != null && last == effectiveStep) continue;

            LAST_STRENGTH_STEP.put(id, effectiveStep);
            ModMessages.sendOmen(player, effectiveStep / (float) STRENGTH_STEPS);

            // 只在「曾经处于黄昏预兆态」时处理退出，避免波次期每 tick 误停刚起播的波次 drone。
            // 白天基础强度 0.3 只给 HUD 暗红提示，不播 drone（否则白天就响 3 分钟太吵）。
            boolean wasInDuskOmen = last != null && last > Math.round(DAYTIME_BASE_STRENGTH * STRENGTH_STEPS);
            if (hordeDayToday && inDuskWindow) {
                Integer played = DRONE_PLAYED_STEP.get(id);
                if (played == null) {
                    ModSounds.playToPlayer(player, ModSounds.HORDE_DRONE,
                            SoundSource.AMBIENT, OMEN_DRONE_VOLUME, 1.0F);
                    DRONE_PLAYED_STEP.put(id, effectiveStep);
                }
            } else if (wasInDuskOmen) {
                DRONE_PLAYED_STEP.remove(id);
                // 离开黄昏窗口：掐掉可能仍在播的极低音量 drone，避免残留到波次期
                ModSounds.stopDroneForPlayer(player);
            }
        }
    }

    /** 波次开始时调用：清掉该玩家的预兆状态，避免波次结束后残留旧档位。 */
    public static void onHordeStart(UUID playerId) {
        LAST_STRENGTH_STEP.remove(playerId);
        DRONE_PLAYED_STEP.remove(playerId);
    }

    /** 玩家登出/换世界时清状态。 */
    public static void reset() {
        LAST_STRENGTH_STEP.clear();
        DRONE_PLAYED_STEP.clear();
    }

    public static void onPlayerLogout(UUID playerId) {
        LAST_STRENGTH_STEP.remove(playerId);
        DRONE_PLAYED_STEP.remove(playerId);
    }
}
