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
 * 尸潮音频调度：**持续低频 drone + 间断僵尸叫声**两层，只发给玩家尸潮触发者。
 * <p>
 * v0.2.70 起拆成两条独立音轨：
 * <ul>
 *   <li><b>drone（底层）</b>：{@link ModSounds#HORDE_DRONE}，进入波次立即播放，
 *       素材约 3 分 42 秒，到点重播一次实现近似无缝循环（MC 的 SoundEvent 无原生 loop）。
 *       音量 0.35，作为持续压迫底噪，不抢叫声。</li>
 *   <li><b>叫声（顶层）</b>：{@link ModSounds#HORDE_BGM} 的 5 段变体，每隔随机 30 秒 ~ 2 分钟播一段，
 *       作为「远处有东西」的间断预警。间隔从**本次开始播放**时刻起算。</li>
 * </ul>
 * 两条音轨用不同的 ResourceLocation，停止包互不误伤；波次结束时一起停。
 * <p>
 * 只对 {@code waveActive=true} 的**玩家**生效；村民尸潮不播放，附近玩家也听不到。
 * 调度状态仅存在于内存，不写入存档。
 */
public final class HordeBgmScheduler {

    private static final int MIN_INTERVAL_TICKS = 30 * 20;   // 30 秒
    private static final int MAX_INTERVAL_TICKS = 120 * 20;  // 2 分钟
    private static final float BGM_VOLUME = 0.7F;
    /** drone 是底噪，音量压低，避免盖住叫声与游戏音效 */
    private static final float DRONE_VOLUME = 0.35F;
    /** drone 素材实际 221.786 秒，重播周期取 223 秒（留约 1.2 秒静音间隙）。
     *  <p>历史：v0.2.71 用 221-2=219 秒重播 → 与尾部重叠 2.8 秒发浑；
     *  v0.2.72 改成 222-1=221 秒 → 仍重叠 0.786 秒（注释却写"不重叠"，失实）。
     *  v0.2.75 取消提前量、周期取 223 秒：宁可留 1.2 秒安静（符合"沉默是武器"），
     *  也不让两段低频叠加发浑。 */
    private static final int DRONE_DURATION_TICKS = 223 * 20;
    private static final int DRONE_REPLAY_LEAD_TICKS = 0;

    /** 玩家 UUID -> 下一次播放叫声的服务端 tick（纯运行时状态） */
    private static final Map<UUID, Long> NEXT_PLAY_TICK = new HashMap<>();
    /** 玩家 UUID -> drone 下一次重播的服务端 tick */
    private static final Map<UUID, Long> DRONE_REPLAY_TICK = new HashMap<>();
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
                    && data.waveActive()
                    && player.level().dimension() == Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL;

            if (!inHorde) {
                // 波次结束或开关关闭：两条音轨一起掐掉，不留残响
                boolean hadBgm = NEXT_PLAY_TICK.remove(id) != null;
                boolean hadDrone = DRONE_REPLAY_TICK.remove(id) != null;
                if (hadBgm || hadDrone) {
                    ModSounds.stopAllHordeAudio(player);
                }
                continue;
            }

            // ---- 底层 drone：进入波次立即起播，到点重播 ----
            if (config.hordeBgmSound()) {
                Long droneNext = DRONE_REPLAY_TICK.get(id);
                if (droneNext == null) {
                    // 进入波次：先掐掉原版 BGM，再起播 drone，避免两种音乐叠在一起
                    ModSounds.stopVanillaMusic(player);
                    ModSounds.playToPlayer(player, ModSounds.HORDE_DRONE, SoundSource.AMBIENT, DRONE_VOLUME, 1.0F);
                    DRONE_REPLAY_TICK.put(id, now + DRONE_DURATION_TICKS - DRONE_REPLAY_LEAD_TICKS);
                } else if (now >= droneNext) {
                    // 原版音乐调度器过一阵会自己重新起播，借 drone 重播的节奏再掐一次
                    ModSounds.stopVanillaMusic(player);
                    ModSounds.playToPlayer(player, ModSounds.HORDE_DRONE, SoundSource.AMBIENT, DRONE_VOLUME, 1.0F);
                    DRONE_REPLAY_TICK.put(id, now + DRONE_DURATION_TICKS - DRONE_REPLAY_LEAD_TICKS);
                }
            } else if (DRONE_REPLAY_TICK.remove(id) != null) {
                ModSounds.stopDroneForPlayer(player);
            }

            // ---- 顶层叫声：随机间隔 ----
            if (!config.hordeBgmSound()) {
                if (NEXT_PLAY_TICK.remove(id) != null) {
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
                // 分类用「环境」(AMBIENT)，与停止包保持一致
                ModSounds.playToPlayer(player, ModSounds.HORDE_BGM, SoundSource.AMBIENT, BGM_VOLUME, 1.0F);
                NEXT_PLAY_TICK.put(id, now + randomInterval());
            }
        }
    }

    private static int randomInterval() {
        return MIN_INTERVAL_TICKS + RANDOM.nextInt(MAX_INTERVAL_TICKS - MIN_INTERVAL_TICKS + 1);
    }

    /** 清空音频调度状态；带 server 时先给「有排程」的在线玩家下发停止包，掐掉残留音频。
     *  调用点：服务器启动时（换世界/重启）与 `/zombietide debug clear`。
     *  <p>两条音轨分别判断：只要任一 map 里有该玩家，就一起发停止包（多停一次无副作用）。
     */
    public static void reset(MinecraftServer server) {
        if (server != null && server.getPlayerList() != null) {
            java.util.Set<UUID> scheduled = new java.util.HashSet<>(NEXT_PLAY_TICK.keySet());
            scheduled.addAll(DRONE_REPLAY_TICK.keySet());
            for (UUID id : scheduled) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && player.connection != null) {
                    ModSounds.stopAllHordeAudio(player);
                }
            }
        }
        NEXT_PLAY_TICK.clear();
        DRONE_REPLAY_TICK.clear();
    }

    /** 玩家退出时清掉该玩家的调度条目，避免 UUID 残留（内存泄漏）。
     *  <p>音频本身会随客户端断开自然停止；这里只清服务端状态。 */
    public static void onPlayerLogout(java.util.UUID playerId) {
        NEXT_PLAY_TICK.remove(playerId);
        DRONE_REPLAY_TICK.remove(playerId);
    }

    /** 【兼容重载，保留备查】拿不到 MinecraftServer 时退化为只清调度状态，不下发停止包。 */
    public static void reset() {
        reset(null);
    }
}
