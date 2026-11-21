package com.enik.zombietide.attachments;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 玩家尸潮附件数据（按玩家独立计时，随玩家存档）。
 * modDay 直接等于主世界游戏日（dayTime / 24000）。
 *
 * <p>血债（bloodDebt）已迁出：它需要玩家离线时也能结算，而 attachment 必须拿到
 * 在线 ServerPlayer 才能访问。现在由 {@link com.enik.zombietide.config.PlayerDebtData}
 * （世界级 SavedData）持有。旧存档里残留的 {@code bloodDebt} 键会被 Codec 忽略。
 */
public record PlayerHordeData(
        int modDay,           // 当前模组天数，直接等于主世界游戏日
        int lastTriggerDay,   // 上次触发尸潮的模组天数
        int waveCount,        // 已触发波次数
        boolean waveActive,   // 当前是否处于尸潮波次中
        int waveSeq           // 波次序号：每次 startHorde 自增，供血债「按波去重」使用（0 = 未分配）
) {
    public static final Codec<PlayerHordeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("modDay").forGetter(PlayerHordeData::modDay),
            Codec.INT.fieldOf("lastTriggerDay").forGetter(PlayerHordeData::lastTriggerDay),
            Codec.INT.fieldOf("waveCount").forGetter(PlayerHordeData::waveCount),
            Codec.BOOL.fieldOf("waveActive").forGetter(PlayerHordeData::waveActive),
            Codec.INT.optionalFieldOf("waveSeq", 0).forGetter(PlayerHordeData::waveSeq)
    ).apply(instance, PlayerHordeData::new));

    /** 兼容旧调用：waveSeq 记为 0（未分配）。0 永远不会被判为「已结算」，见 PlayerDebtData.isWaveSettled */
    public PlayerHordeData(int modDay, int lastTriggerDay, int waveCount, boolean waveActive) {
        this(modDay, lastTriggerDay, waveCount, waveActive, 0);
    }

    public static PlayerHordeData defaults() {
        return new PlayerHordeData(0, -1, 0, false, 0);
    }
}
