package com.enik.zombietide.sound;

import com.enik.zombietide.EnikZombietide;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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
    /** 尸潮背景音乐（5 段随机变体，仅玩家尸潮触发者能听到） */
    public static final DeferredHolder<SoundEvent, SoundEvent> HORDE_BGM =
            register("horde_bgm", false);

    /** 背景音乐使用的音效分类：中文「环境」(AMBIENT)，不是「音乐」(MUSIC)；停止包必须与之保持一致 */
    public static final SoundSource BGM_SOURCE = SoundSource.AMBIENT;
    public static final ResourceLocation BGM_ID =
            ResourceLocation.fromNamespaceAndPath(EnikZombietide.MODID, "horde_bgm");

    private ModSounds() {
    }

    /**
     * @param wideRange 触发音效需要覆盖村民尸潮 32 格警告范围，用固定 48 格；
     *                  其余用默认可变范围（仅目标玩家附近）
     */
    private static DeferredHolder<SoundEvent, SoundEvent> register(String name, boolean wideRange) {
        return SOUNDS.register(name, () -> {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EnikZombietide.MODID, name);
            return wideRange
                    ? SoundEvent.createFixedRangeEvent(id, 48.0F)
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

    /** 停止指定玩家正在播放的某个音效（四散/波次结束时掐掉残留 BGM）。 */
    public static void stopForPlayer(ServerPlayer player) {
        player.connection.send(new ClientboundStopSoundPacket(BGM_ID, BGM_SOURCE));
    }
}
