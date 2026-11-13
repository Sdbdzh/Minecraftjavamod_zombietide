package com.enik.zombietide.attachments;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 村民尸潮附件数据（每个村民独立计时，无血债）。
 * modDay 直接等于主世界游戏日（dayTime / 24000）。
 */
public record VillagerHordeData(
        int modDay,
        int lastTriggerDay,
        int waveCount,
        boolean waveActive
) {
    public static final Codec<VillagerHordeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("modDay").forGetter(VillagerHordeData::modDay),
            Codec.INT.fieldOf("lastTriggerDay").forGetter(VillagerHordeData::lastTriggerDay),
            Codec.INT.fieldOf("waveCount").forGetter(VillagerHordeData::waveCount),
            Codec.BOOL.fieldOf("waveActive").forGetter(VillagerHordeData::waveActive)
    ).apply(instance, VillagerHordeData::new));

    public static VillagerHordeData defaults() {
        return new VillagerHordeData(0, -1, 0, false);
    }
}
