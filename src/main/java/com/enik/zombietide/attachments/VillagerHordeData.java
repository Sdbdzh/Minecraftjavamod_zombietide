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
        boolean waveActive,
        int waveSeq           // 波次序号：与 VillagerScheduleData.ScheduleEntry.waveSeq 同步（0 = 未分配）
) {
    public static final Codec<VillagerHordeData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("modDay").forGetter(VillagerHordeData::modDay),
            Codec.INT.fieldOf("lastTriggerDay").forGetter(VillagerHordeData::lastTriggerDay),
            Codec.INT.fieldOf("waveCount").forGetter(VillagerHordeData::waveCount),
            Codec.BOOL.fieldOf("waveActive").forGetter(VillagerHordeData::waveActive),
            Codec.INT.optionalFieldOf("waveSeq", 0).forGetter(VillagerHordeData::waveSeq)
    ).apply(instance, VillagerHordeData::new));

    /** 兼容旧调用：waveSeq 记为 0（未分配） */
    public VillagerHordeData(int modDay, int lastTriggerDay, int waveCount, boolean waveActive) {
        this(modDay, lastTriggerDay, waveCount, waveActive, 0);
    }

    public static VillagerHordeData defaults() {
        return new VillagerHordeData(0, -1, 0, false, 0);
    }
}
