package com.enik.zombietide.horde;

import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.attachments.PlayerHordeData;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.sound.ModSounds;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 尸潮背景音乐调度：尸潮波次期间按随机间隔播放，只发给玩家尸潮触发者。
 * <p>
 * 规则（按需求）：
 * <ul>
 *   <li>只有 {@code waveActive=true} 的**玩家**能听到；村民尸潮不播放，附近玩家也听不到</li>
 *   <li>每隔随机 30 秒 ~ 2 分钟（600~2400 tick）播一段；间隔从**本次开始播放**时刻起算，
 *       不是播完再等（实现为 {@code NEXT_PLAY_TICK = now + randomInterval()}），不做无缝循环</li>
 *   <li>具体曲目由 {@code sounds.json} 中 horde_bgm 的 5 个变体随机选取</li>
 *   <li>波次结束（四散/黎明/强制清除/清场）或关闭开关时，立即下发停止包掐掉残留音乐</li>
 * </ul>
 * 调度状态仅存在于内存，不写入存档。
 */
public final class HordeBgmScheduler {

    private static final int MIN_INTERVAL_TICKS = 30 * 20;   // 30 秒
    private static final int MAX_INTERVAL_TICKS = 120 * 20;  // 2 分钟
    private static final float BGM_VOLUME = 0.7F;

    /** 玩家 UUID -> 下一次播放的服务端 tick 数（纯运行时状态） */
    private static final Map<UUID, Long> NEXT_PLAY_TICK = new HashMap<>();
    private static final Random RANDOM = new Random();

    private HordeBgmScheduler() {
    }

    /** 由 ZombietideServerEvents.onServerTick 每 tick 调用。 */
    public static void onServerTick(ServerLevel overworld, ZombietideConfig config) {
        MinecraftServer server = overworld.getServer();
        if (server == null) return;
        long now = server.getTickCount();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            PlayerHordeData data = ModAttachments.getPlayerHordeData(player);

            // 仅玩家尸潮触发者：waveActive 由 checkPlayerHordes 置 true，四散/黎明置 false
            boolean inHorde = config.warningEnabled()
                    && config.hordeBgmSound()
                    && data.waveActive()
                    && player.level().dimension() == Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL;

            if (!inHorde) {
                if (NEXT_PLAY_TICK.remove(id) != null) {
                    // 波次结束或开关关闭：停止该玩家仍在播放的 BGM
                    ModSounds.stopForPlayer(player);
                }
                continue;
            }

            Long next = NEXT_PLAY_TICK.get(id);
            if (next == null) {
                // 进入波次后先随机等待，避免与 8 秒的触发音效叠在一起
                NEXT_PLAY_TICK.put(id, now + randomInterval());
                continue;
            }
            if (now >= next) {
                // 只发给该玩家本人（ClientboundSoundPacket，不广播）
                // 分类用「环境」(AMBIENT)，与 ModSounds.BGM_SOURCE 的停止包保持一致
                ModSounds.playToPlayer(player, ModSounds.HORDE_BGM, SoundSource.AMBIENT, BGM_VOLUME, 1.0F);
                NEXT_PLAY_TICK.put(id, now + randomInterval());
            }
        }
    }

    private static int randomInterval() {
        return MIN_INTERVAL_TICKS + RANDOM.nextInt(MAX_INTERVAL_TICKS - MIN_INTERVAL_TICKS + 1);
    }

    /** 清空 BGM 调度状态；带 server 时先给「有排程」的在线玩家下发停止包，掐掉残留音乐。
     *  调用点：服务器启动时（换世界/重启）与 `/zombietide debug clear`。
     *  <p>只给 NEXT_PLAY_TICK 里的玩家发：播放时必然写入下一播放 tick，故「表里有 id」
     *  等价于「正在听 / 已排程」；清空后下一 tick 的 onServerTick 不会重复发停止包。
     */
    public static void reset(MinecraftServer server) {
        if (server != null && server.getPlayerList() != null && !NEXT_PLAY_TICK.isEmpty()) {
            for (UUID id : new java.util.ArrayList<>(NEXT_PLAY_TICK.keySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && player.connection != null) {
                    ModSounds.stopForPlayer(player);
                }
            }
        }
        NEXT_PLAY_TICK.clear();
    }

    /** 【兼容重载，保留备查】拿不到 MinecraftServer 时退化为只清调度状态，不下发停止包。 */
    public static void reset() {
        reset(null);
    }
}
