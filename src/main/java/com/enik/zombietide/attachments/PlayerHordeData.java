package com.enik.zombietide.attachments;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 玩家尸潮附件数据（按玩家独立计时，随玩家存档）。
 * modDay 直接等于主世界游戏日（dayTime / 24000）。
 */
public record PlayerHordeData(
        int modDay,           // 当前模组天数，直接等于主世界游戏日
        int lastTriggerDay,   // 上次触发尸潮的模组天数
        int bloodDebt,        // 血债（仅玩家尸潮）
        int waveCount,        // 已触发波次数
        boolean waveActive    // 当前是否处于尸潮波次中
) {
    public static final Codec<PlayerHordeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("modDay").forGetter(PlayerHordeData::modDay),
            Codec.INT.fieldOf("lastTriggerDay").forGetter(PlayerHordeData::lastTriggerDay),
            Codec.INT.fieldOf("bloodDebt").forGetter(PlayerHordeData::bloodDebt),
            Codec.INT.fieldOf("waveCount").forGetter(PlayerHordeData::waveCount),
            Codec.BOOL.fieldOf("waveActive").forGetter(PlayerHordeData::waveActive)
    ).apply(instance, PlayerHordeData::new));

    public static PlayerHordeData defaults() {
        return new PlayerHordeData(0, -1, 0, 0, false);
    }
}
