package com.enik.zombietide.horde;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.network.ModMessages;
import com.enik.zombietide.sound.ModSounds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 尸潮威胁检测（v0.2.86）：统一的氛围/音频判定入口——**氛围 = 玩家正被尸潮僵尸锁定**。
 * <p>
 * 不再区分尸潮是玩家触发还是村民触发：只要某只带 horde 标记的僵尸的 {@code getTarget()}
 * 是该玩家，就认为该玩家"身处威胁"，享受 drone + 视觉氛围；僵尸四散（state==1 会
 * {@code setTarget(null)}）或改打别人后，威胁自然消失。
 * <p><b>去抖</b>：索敌每 20 tick 刷新一次且可能在多个目标间横跳，因此用连续计数——
 * 连续 {@link #DEBOUNCE_SAMPLES} 次（3 秒）判定"被锁定"才激活，连续 3 秒"没被锁定"才关闭。
 * <p>状态只在内存，每 20 tick 评估一次；发包只在状态**变化**时，不每 tick 刷。
 */
public final class HordeThreatDetector {

    /** 评估间隔（tick）：20 = 1 秒 */
    private static final int EVAL_INTERVAL = 20;
    /** 连续多少次评估才算状态翻转：3 次 = 3 秒 */
    private static final int DEBOUNCE_SAMPLES = 3;
    /** 去抖计数器，范围 [-DEBOUNCE_SAMPLES, +DEBOUNCE_SAMPLES] */
    private static final Map<UUID, Integer> DEBOUNCE = new HashMap<>();
    /** 玩家 UUID -> 当前威胁是否激活（权威状态） */
    private static final Map<UUID, Boolean> ACTIVE = new HashMap<>();

    private HordeThreatDetector() {
    }

    /** 由 ZombietideServerEvents.onServerTick 每 tick 调用，内部 20 tick 节流。 */
    public static void onServerTick(ServerLevel overworld, ZombietideConfig config, long tickCount) {
        if (tickCount % EVAL_INTERVAL != 0L) return;
        MinecraftServer server = overworld.getServer();
        if (server == null) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();

            // 只认主世界、非和平、非创造/旁观
            boolean eligible = player.level().dimension() == Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL
                    && !player.isCreative() && !player.isSpectator();
            boolean locked = eligible && isLockedByHordeZombie(overworld, player);

            // 去抖：连续 N 次一致才翻转
            int counter = DEBOUNCE.getOrDefault(id, 0);
            counter = locked ? Math.min(DEBOUNCE_SAMPLES, counter + 1)
                             : Math.max(-DEBOUNCE_SAMPLES, counter - 1);
            DEBOUNCE.put(id, counter);

            boolean current = Boolean.TRUE.equals(ACTIVE.get(id));
            boolean next = current;
            if (!current && counter >= DEBOUNCE_SAMPLES) next = true;
            else if (current && counter <= -DEBOUNCE_SAMPLES) next = false;

            if (next == current) continue;
            ACTIVE.put(id, next);
            ModMessages.sendThreat(player, next ? 1.0F : 0.0F);

            // 音频：激活时起播 drone 并静默原版音乐；关闭时停掉 drone
            if (next) {
                if (config.hordeBgmSound()) {
                    ModSounds.playToPlayer(player, ModSounds.HORDE_DRONE, SoundSource.AMBIENT, 0.35F, 1.0F);
                }
                ModSounds.stopVanillaMusic(player);
                EnikZombietide.LOGGER.debug("[威胁] 玩家 {} 被尸潮僵尸锁定，氛围激活", player.getScoreboardName());
            } else {
                ModSounds.stopAllHordeAudio(player);
                EnikZombietide.LOGGER.debug("[威胁] 玩家 {} 威胁解除，氛围关闭", player.getScoreboardName());
            }
        }
    }

    /**
     * 是否有**活着的尸潮僵尸**正锁定该玩家。
     * <p>遍历 {@code hordeZombieUuids}（O(尸潮僵尸数)，不是全实体表）；只认 state==0 的活跃僵尸，
     * 四散态（state==1）已 {@code setTarget(null)} 不算。
     */
    private static boolean isLockedByHordeZombie(ServerLevel level, ServerPlayer player) {
        for (UUID zombieUuid : HordeSpawner.getHordeZombieIds()) {
            var entity = level.getEntity(zombieUuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
            if (mob.getPersistentData().getInt(HordeSpawner.hordeStateKey()) != 0) continue;
            if (mob.getTarget() == player) return true;
        }
        return false;
    }

    /** 玩家是否处于威胁态（供其它系统查询） */
    public static boolean isThreatened(UUID playerId) {
        return Boolean.TRUE.equals(ACTIVE.get(playerId));
    }

    /** 玩家登出/换世界时清状态 */
    public static void onPlayerLogout(UUID playerId) {
        ACTIVE.remove(playerId);
        DEBOUNCE.remove(playerId);
    }

    /** 服务器启动/清场时全清 */
    public static void reset() {
        ACTIVE.clear();
        DEBOUNCE.clear();
    }
}
