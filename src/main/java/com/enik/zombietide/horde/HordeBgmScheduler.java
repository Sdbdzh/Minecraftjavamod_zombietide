package com.enik.zombietide.horde;

import com.enik.zombietide.EnikZombietide;
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

    /** drone 是底噪，音量压低，避免盖住叫声与游戏音效 */
    private static final float DRONE_VOLUME = 0.35F;
    /** drone 素材实际 221.786 秒，重播周期取 223 秒（留约 1.2 秒静音间隙）。
     *  <p>历史：v0.2.71 用 221-2=219 秒重播 → 与尾部重叠 2.8 秒发浑；
     *  v0.2.72 改成 222-1=221 秒 → 仍重叠 0.786 秒（注释却写"不重叠"，失实）。
     *  v0.2.75 取消提前量、周期取 223 秒：宁可留 1.2 秒安静（符合"沉默是武器"），
     *  也不让两段低频叠加发浑。 */
    private static final int DRONE_DURATION_TICKS = 223 * 20;
    private static final int DRONE_REPLAY_LEAD_TICKS = 0;

    /** 玩家 UUID -> drone 下一次重播的服务端 tick */
    private static final Map<UUID, Long> DRONE_REPLAY_TICK = new HashMap<>();

    private HordeBgmScheduler() {
    }

    /**
     * 【v0.2.85】玩家附近是否有**活跃的村民尸潮**（用于决定是否静默原版音乐）。
     * <p>调度表只存 UUID 不存坐标，因此遍历已加载村民实体、按 UUID 查调度表的 `waveActive`。
     * 距离用 {@code villagerWarningRange}——与"哪些玩家会收到村民尸潮警报"同口径：
     * 收到了大字/旁白就说明身处事件，原版音乐该让位。
     * <p>每 20 tick 才调一次（与静默同频），开销可控。
     */
    private static java.util.List<net.minecraft.world.phys.Vec3> collectActiveVillagerHordePositions(ServerLevel overworld) {
        var schedule = overworld.getDataStorage().get(
                com.enik.zombietide.config.VillagerScheduleData.FACTORY,
                com.enik.zombietide.config.VillagerScheduleData.ID);
        if (schedule == null) return java.util.List.of();
        java.util.List<net.minecraft.world.phys.Vec3> result = new java.util.ArrayList<>();
        for (var entity : overworld.getEntities().getAll()) {
            if (entity == null) continue;
            if (!(entity instanceof net.minecraft.world.entity.npc.Villager villager)) continue;
            var entry = schedule.getScheduleOrNull(villager.getUUID());
            if (entry != null && entry.waveActive()) {
                result.add(entity.position());
            }
        }
        return result;
    }

    /** 由 ZombietideServerEvents.onServerTick 每 tick 调用。 */
    public static void onServerTick(ServerLevel overworld, ZombietideConfig config) {
        MinecraftServer server = overworld.getServer();
        if (server == null) return;
        long now = server.getTickCount();

        // 每 20 tick 收集一次「活跃村民尸潮位置」，全服只扫一次实体表，
        // 循环玩家时只做距离比较（避免 N 玩家 × 全实体表扫描）。
        java.util.List<net.minecraft.world.phys.Vec3> activeVillagerHordes =
                (now % 20L == 0L) ? collectActiveVillagerHordePositions(overworld) : java.util.List.of();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            PlayerHordeData data = ModAttachments.getPlayerHordeData(player);

            // 仅玩家尸潮触发者：waveActive 由 checkPlayerHordes 置 true，四散/黎明置 false
            boolean inHorde = config.warningEnabled()
                    && data.waveActive()
                    && player.level().dimension() == Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL;

            // ---- 原版音乐静默（v0.2.84 / v0.2.85 扩展）----
            // 独立于 warningEnabled 与 hordeBgmSound：只要玩家身处尸潮事件（自己的或附近村民的），
            // 世界就该安静。原版 MusicManager 会在 currentMusic==null 时按 nextSongDelay 自己起播，
            // 只在进入波次停一次挡不住——每 20 tick 补停一次。
            // 附近是否有活跃村民尸潮（只做距离比较，列表每 20 tick 全服收集一次）
            boolean nearVillagerHorde = false;
            if (!activeVillagerHordes.isEmpty()) {
                double rangeSq = config.villagerWarningRange() * (double) config.villagerWarningRange();
                for (var pos : activeVillagerHordes) {
                    if (player.distanceToSqr(pos) <= rangeSq) {
                        nearVillagerHorde = true;
                        break;
                    }
                }
            }
            boolean silenceVanilla = (data.waveActive() || nearVillagerHorde)
                    && player.level().dimension() == Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != Difficulty.PEACEFUL;
            if (silenceVanilla && now % 20L == 0L) {
                ModSounds.stopVanillaMusic(player);
            }

            if (!inHorde) {
                // 波次结束或开关关闭：掐掉 drone，不留残响
                if (DRONE_REPLAY_TICK.remove(id) != null) {
                    ModSounds.stopAllHordeAudio(player);
                }
                continue;
            }

            // ---- 底层 drone：进入波次立即起播，到点重播 ----
            if (config.hordeBgmSound()) {
                Long droneNext = DRONE_REPLAY_TICK.get(id);
                if (droneNext == null) {
                    ModSounds.playToPlayer(player, ModSounds.HORDE_DRONE, SoundSource.AMBIENT, DRONE_VOLUME, 1.0F);
                    DRONE_REPLAY_TICK.put(id, now + DRONE_DURATION_TICKS - DRONE_REPLAY_LEAD_TICKS);
                } else if (now >= droneNext) {
                    ModSounds.playToPlayer(player, ModSounds.HORDE_DRONE, SoundSource.AMBIENT, DRONE_VOLUME, 1.0F);
                    DRONE_REPLAY_TICK.put(id, now + DRONE_DURATION_TICKS - DRONE_REPLAY_LEAD_TICKS);
                }
            } else if (DRONE_REPLAY_TICK.remove(id) != null) {
                ModSounds.stopDroneForPlayer(player);
            }

            // 【v0.2.82】原「定时在玩家耳边播叫声」已移除：
            // 叫声改由 HordeSpawner.maybePlaySpawnCall 在**僵尸出生位置**广播（50% 概率、
            // 固定 192 格范围、所有玩家可听），玩家能判断"它从哪来"。
            // 这里只负责底层 drone，不再发任何叫声包。
        }
    }

    /** 清空音频调度状态；带 server 时先给「有排程」的在线玩家下发停止包，掐掉残留音频。
     *  调用点：服务器启动时（换世界/重启）与 `/zombietide debug clear`。
     */
    public static void reset(MinecraftServer server) {
        if (server != null && server.getPlayerList() != null) {
            for (UUID id : new java.util.HashSet<>(DRONE_REPLAY_TICK.keySet())) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null && player.connection != null) {
                    ModSounds.stopAllHordeAudio(player);
                }
            }
        }
        DRONE_REPLAY_TICK.clear();
    }

    /** 玩家退出时清掉该玩家的调度条目，避免 UUID 残留（内存泄漏）。
     *  <p>音频本身会随客户端断开自然停止；这里只清服务端状态。 */
    public static void onPlayerLogout(java.util.UUID playerId) {
        DRONE_REPLAY_TICK.remove(playerId);
    }

    /** 【兼容重载，保留备查】拿不到 MinecraftServer 时退化为只清调度状态，不下发停止包。 */
    public static void reset() {
        reset(null);
    }
}
