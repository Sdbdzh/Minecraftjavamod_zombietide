package com.enik.zombietide.sound;

import com.enik.zombietide.EnikZombietide;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 定制音频注册与「只发给指定玩家」的播放工具。
 * <p>
 * 音频文件位于 {@code assets/enik_zombietide/sounds/*.ogg}，映射见 {@code sounds.json}。
 * 源文件为「资源/音频」下的 mp3/wav，已统一转为 44100Hz OGG Vorbis
 * （Minecraft 只支持 OGG）。
 */
public final class ModSounds {

    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, EnikZombietide.MODID);

    /** 清晨提醒（尸潮日黎明，仅聊天提醒时附带） */
    public static final DeferredHolder<SoundEvent, SoundEvent> MORNING_WARNING =
            register("morning_warning", false);
    /** 尸潮触发警报（替换原版 ZOMBIE_ATTACK_WOODEN_DOOR） */
    public static final DeferredHolder<SoundEvent, SoundEvent> HORDE_TRIGGER =
            register("horde_trigger", true);
    /** 日出前四散 */
    public static final DeferredHolder<SoundEvent, SoundEvent> HORDE_SCATTER =
            register("horde_scatter", false);
    /** 僵尸叫声/低吼（5 段随机变体）。
     *  <p>v0.2.70 起正名：这 5 段不是旋律性 BGM，而是「尸潮预警」；真正的持续背景音是 {@link #HORDE_DRONE}。
     *  <p>v0.2.81：空间化两次尝试失败（实体绑定静默丢弃 / 坐标版超 64 格听不到），
     *  恢复为**玩家位置 + 可变范围**播放，优先保证"听得到"。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> HORDE_BGM =
            register("horde_bgm", false);

    /** 持续低频底鸣（drone）：尸潮期间常驻播放的压迫性底噪，非旋律。
     *  <p>源素材 74 秒，已拼接为约 3 分 42 秒的 OGG，靠「长音频 + 到点重播」近似无缝循环
     *  （Minecraft 的 SoundEvent 不支持原生 loop）。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> HORDE_DRONE =
            register("horde_drone", false);

    /** 背景音乐使用的音效分类：中文「环境」(AMBIENT)，不是「音乐」(MUSIC)；停止包必须与之保持一致 */
    public static final SoundSource BGM_SOURCE = SoundSource.AMBIENT;
    public static final ResourceLocation BGM_ID =
            ResourceLocation.fromNamespaceAndPath(EnikZombietide.MODID, "horde_bgm");
    /** drone 的独立 ID：必须与 BGM_ID 分开，否则停止包会误停另一条音轨 */
    public static final ResourceLocation DRONE_ID =
            ResourceLocation.fromNamespaceAndPath(EnikZombietide.MODID, "horde_drone");

    private ModSounds() {
    }

    /**
     * @param wideRange true = 固定 48 格广播半径（尸潮触发警报，让触发点附近更多玩家听到）；
     *                  false = 默认可变范围（仅目标玩家附近）
     */
    private static DeferredHolder<SoundEvent, SoundEvent> register(String name, boolean wideRange) {
        return register(name, wideRange ? 48.0F : -1.0F);
    }

    /** @param fixedRange 正数 = 固定半径（超出不闻、范围内恒定）；-1 = 默认可变范围 */
    private static DeferredHolder<SoundEvent, SoundEvent> register(String name, float fixedRange) {
        return SOUNDS.register(name, () -> {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EnikZombietide.MODID, name);
            return fixedRange > 0
                    ? SoundEvent.createFixedRangeEvent(id, fixedRange)
                    : SoundEvent.createVariableRangeEvent(id);
        });
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }

    /** 只给指定玩家播放声音，不广播给附近玩家（用于私人提示与 BGM）。 */
    public static void playToPlayer(ServerPlayer player, Holder<SoundEvent> sound,
                                    SoundSource source, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                sound, source,
                player.getX(), player.getY(), player.getZ(),
                volume, pitch, player.getRandom().nextLong()));
    }

    /**
     * 【v0.2.79】在**指定世界坐标**给指定玩家播放声音（坐标版，带空间方位感）。
     * <p>与 {@link #playOnEntity} 的区别：不依赖客户端已收到该实体的生成包。
     * 原版声音包实体版会用实体 ID 查本地实体，查不到就**静默丢弃**——
     * 尸潮僵尸常刷在视距外（60~150 格），客户端往往没同步，导致叫声整个丢失。
     * 坐标版直接把 xyz 写进包里，客户端收到就播，且仍按坐标计算方位与距离衰减。
     */
    public static void playAtPosition(ServerPlayer player, Holder<SoundEvent> sound,
                                      double x, double y, double z,
                                      SoundSource source, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
                sound, source, x, y, z, volume, pitch, player.getRandom().nextLong()));
    }

    /**
     * 【v0.2.77】在**实体位置**播放声音，且声音**实时跟随实体移动**——只发给指定玩家。
     * <p>用原版 {@link ClientboundSoundEntityPacket}（而非坐标版 ClientboundSoundPacket）：
     * 客户端会把音源绑定到实体，每帧按实体位置计算方位与衰减。这就是"听声辨位"的实现。
     * 实体被 discard 后客户端音源随之消失，无需额外停止包。
     * <p><b>注意（v0.2.79）</b>：客户端用实体 ID 查本地实体，查不到会静默丢弃——
     * 仅当实体确定已同步到客户端时才可用。尸潮叫声已改用 {@link #playAtPosition}。
     */
    public static void playOnEntity(ServerPlayer player, Holder<SoundEvent> sound,
                                    Entity entity, SoundSource source, float volume, float pitch) {
        player.connection.send(new ClientboundSoundEntityPacket(
                sound, source, entity, volume, pitch, player.getRandom().nextLong()));
    }

    /** 停止指定玩家正在播放的某个音效（四散/波次结束时掐掉残留 BGM）。 */
    public static void stopForPlayer(ServerPlayer player) {
        player.connection.send(new ClientboundStopSoundPacket(BGM_ID, BGM_SOURCE));
    }

    /** 单独停止 drone（与 BGM 分开，避免一条音轨的停止包误伤另一条）。 */
    public static void stopDroneForPlayer(ServerPlayer player) {
        player.connection.send(new ClientboundStopSoundPacket(DRONE_ID, BGM_SOURCE));
    }

    /** 两条音轨一起停：波次结束/清场时用，保证不留残响。 */
    public static void stopAllHordeAudio(ServerPlayer player) {
        stopForPlayer(player);
        stopDroneForPlayer(player);
    }

    /**
     * 停掉客户端的**原版背景音乐**（SoundSource.MUSIC 分类）。
     * <p>v0.2.71：尸潮期间若原版 BGM 还在播，会和低频 drone 混在一起，
     * 破坏"世界安静下来、只剩尸潮的声音"的压迫感。传 {@code null} 表示停整个分类，
     * 而不是某一首曲子。MC 原版的音乐调度器之后会自行恢复播放，无需手动重启。
     */
    public static void stopVanillaMusic(ServerPlayer player) {
        player.connection.send(new ClientboundStopSoundPacket(null, SoundSource.MUSIC));
    }
}
