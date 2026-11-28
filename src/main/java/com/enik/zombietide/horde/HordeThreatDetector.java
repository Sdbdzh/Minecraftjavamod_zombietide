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
 * 尸潮威胁检测（v0.2.121）：统一的氛围/音频判定入口。
 * <p>
 * <b>强度 = 玩家附近 64 格内的活跃尸潮僵尸数量</b>（不限触发者，只认 horde 标记），
 * 20 只或以上为满强度 1.0。不再依赖"是否被锁定"——这样红边能随周围僵尸数量实时变化。
 * 僵尸四散（state==1）不计入。
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
    /** 玩家 UUID -> 上次下发的强度（避免重复发包） */
    private static final Map<UUID, Float> LAST_STRENGTH = new HashMap<>();

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
            int nearby = eligible ? countNearbyHordeZombies(overworld, player) : 0;
            // 【v0.2.122】不设封顶数量：渐近曲线 strength = count/(count+32)
            // 1只≈0.03  10只≈0.24  32只=0.5  64只≈0.67  128只≈0.8  256只≈0.89
            // 僵尸越多红边越深，但永不突兀"满"，也没有硬上限。
            float strength = nearby / (float) (nearby + 32);

            // 去抖：连续 N 次接近才算状态翻转（0↔非0 用原计数器，强度用平滑插值由客户端做）
            int counter = DEBOUNCE.getOrDefault(id, 0);
            counter = nearby > 0 ? Math.min(DEBOUNCE_SAMPLES, counter + 1)
                                 : Math.max(-DEBOUNCE_SAMPLES, counter - 1);
            DEBOUNCE.put(id, counter);

            boolean current = Boolean.TRUE.equals(ACTIVE.get(id));
            boolean next = current;
            if (!current && counter >= DEBOUNCE_SAMPLES) next = true;
            else if (current && counter <= -DEBOUNCE_SAMPLES) next = false;

            // 发包：强度变化超过阈值才发（避免每 tick 刷包），状态翻转时必发
            Float lastSent = LAST_STRENGTH.get(id);
            boolean changed = next != current || lastSent == null || Math.abs(lastSent - strength) >= 0.05F;
            if (!changed && next == current) continue;
            ACTIVE.put(id, next);
            LAST_STRENGTH.put(id, strength);
            ModMessages.sendThreat(player, next ? strength : 0.0F);

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
     * 【v0.2.121】统计玩家附近 64 格内的活跃尸潮僵尸数（不限触发者）。
     * <p>遍历 {@code hordeZombieUuids}（O(尸潮僵尸数)）；只认 state==0 的活跃僵尸。
     */
    private static int countNearbyHordeZombies(ServerLevel level, ServerPlayer player) {
        int count = 0;
        double maxDistSq = 64.0 * 64.0;
        for (UUID zombieUuid : HordeSpawner.getHordeZombieIds()) {
            var entity = level.getEntity(zombieUuid);
            if (!(entity instanceof Mob mob) || !mob.isAlive()) continue;
            if (mob.getPersistentData().getInt(HordeSpawner.hordeStateKey()) != 0) continue;
            if (mob.distanceToSqr(player) <= maxDistSq) count++;
        }
        return count;
    }

    /** 玩家是否处于威胁态（供其它系统查询） */
    public static boolean isThreatened(UUID playerId) {
        return Boolean.TRUE.equals(ACTIVE.get(playerId));
    }

    /** 玩家登出/换世界时清状态 */
    public static void onPlayerLogout(UUID playerId) {
        ACTIVE.remove(playerId);
        DEBOUNCE.remove(playerId);
        LAST_STRENGTH.remove(playerId);
    }

    /** 服务器启动/清场时全清。
     *  <p>【v0.2.92】带 server 时还要给所有在线玩家下发强度 0——
     *  否则客户端 threatTarget 仍是 1，雾/视距/暗角会一直挂着（服务端状态清了但客户端不知道）。 */
    public static void reset(net.minecraft.server.MinecraftServer server) {
        ACTIVE.clear();
        DEBOUNCE.clear();
        LAST_STRENGTH.clear();
        if (server != null && server.getPlayerList() != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                ModMessages.sendThreat(player, 0.0F);
            }
        }
    }

    /** 无 server 上下文时的退化版本（只清服务端内存状态）。 */
    public static void reset() {
        reset(null);
    }
}
