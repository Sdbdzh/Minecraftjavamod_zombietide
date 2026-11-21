package com.enik.zombietide.config;

import com.enik.zombietide.EnikZombietide;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 村民模组天数世界级调度表（SavedData，Level Data）。
 * 区块卸载后村民实体不存在，其模组天数由本表持续推进，避免重新加载后模组天数停滞。
 */
public class VillagerScheduleData extends SavedData {

    public static final String ID = "enik_zombietide_villager_schedule";
    public static final ResourceLocation ID_LOCATION = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "villager_schedule");

    public static final SavedData.Factory<VillagerScheduleData> FACTORY = new SavedData.Factory<>(
            VillagerScheduleData::new,
            (tag, registries) -> {
                VillagerScheduleData data = new VillagerScheduleData();
                data.load(tag, registries);
                return data;
            }
    );

    /** 村民 UUID -> 调度条目（无血债） */
    private final Map<UUID, ScheduleEntry> schedules = new HashMap<>();

    public record ScheduleEntry(
            int modDay,
            int lastTriggerDay,
            int waveCount,
            boolean waveActive,
            /** 最后一次确认该村民仍存在的游戏日；<b>-1 表示未知</b>（老存档没有这个字段） */
            int lastSeenDay,
            /** 【当前仅写入、不读取】波次序号：每次 startHorde 自增（0 = 未分配）。
             *  v0.1.55 曾供血债「按波去重」使用，v0.1.56 结算点唯一化后无消费方；保留以兼容老存档。 */

            int waveSeq
    ) {
        public static final Codec<ScheduleEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("modDay").forGetter(ScheduleEntry::modDay),
                Codec.INT.fieldOf("lastTriggerDay").forGetter(ScheduleEntry::lastTriggerDay),
                Codec.INT.fieldOf("waveCount").forGetter(ScheduleEntry::waveCount),
                Codec.BOOL.fieldOf("waveActive").forGetter(ScheduleEntry::waveActive),
                // 老存档没有该字段 → 默认 -1（未知）。**不能默认 0**：0 会被 pruneStaleEntries
                // 判成「30 天未见」，首次清理就把全部历史条目删光。
                Codec.INT.optionalFieldOf("lastSeenDay", -1).forGetter(ScheduleEntry::lastSeenDay),
                Codec.INT.optionalFieldOf("waveSeq", 0).forGetter(ScheduleEntry::waveSeq)
        ).apply(instance, ScheduleEntry::new));

        /** 兼容旧构造：lastSeenDay 记为未知、waveSeq 记为 0，由 {@link #put} 继承旧条目里的已知值 */
        public ScheduleEntry(int modDay, int lastTriggerDay, int waveCount, boolean waveActive) {
            this(modDay, lastTriggerDay, waveCount, waveActive, -1, 0);
        }

        /** 保留 waveSeq（v0.1.55 新增、当前无消费方）；lastSeenDay 更新时不能把它抹掉 */
        public ScheduleEntry withLastSeenDay(int day) {
            return new ScheduleEntry(modDay, lastTriggerDay, waveCount, waveActive, day, waveSeq);
        }
    }

    public VillagerScheduleData() {
    }

    public ScheduleEntry get(UUID villagerUuid) {
        return schedules.computeIfAbsent(villagerUuid, k -> new ScheduleEntry(0, -1, 0, false));
    }

    /** 只读查询（不建条目）：恢复逻辑用，避免为无关 UUID 建空调度 */
    public ScheduleEntry getScheduleOrNull(UUID villagerUuid) {
        return schedules.get(villagerUuid);
    }

    /**
     * 写入调度条目。
     * <p><b>自动继承 lastSeenDay</b>：若新条目携带的是「未知」（-1）而旧条目有已知值，
     * 直接沿用旧值。这样所有用 4 参构造重置状态（modDay/waveCount/waveActive）的调用点
     * 都不会意外把「最后见到日」抹掉，无需逐处修改。
     */
    public void put(UUID villagerUuid, ScheduleEntry entry) {
        ScheduleEntry old = schedules.get(villagerUuid);
        if (entry.lastSeenDay() < 0 && old != null && old.lastSeenDay() >= 0) {
            entry = entry.withLastSeenDay(old.lastSeenDay());
        }
        schedules.put(villagerUuid, entry);
        setDirty();
    }

    /**
     * 确认村民仍存在：把 {@code lastSeenDay} 刷新为当前游戏日，必要时创建条目。
     * <p><b>只在确实看到村民实体时调用</b>（村民加载 / 遍历已加载村民）。
     * 未加载区块的村民必须<b>不</b>调用，否则 {@link #pruneStaleEntries} 永远删不掉陈旧条目。
     */
    public ScheduleEntry touch(UUID villagerUuid, int currentDay) {
        ScheduleEntry e = schedules.get(villagerUuid);
        if (e == null) {
            e = new ScheduleEntry(0, -1, 0, false, currentDay, 0);
            schedules.put(villagerUuid, e);
            setDirty();
            return e;
        }
        if (e.lastSeenDay() != currentDay) {
            e = e.withLastSeenDay(currentDay);
            schedules.put(villagerUuid, e);
            setDirty();
        }
        return e;
    }

    /**
     * 清理长期未见且从未触发过尸潮的条目，防止 SavedData 无界增长。
     *
     * <p>删除条件（须全部满足）：{@code lastSeenDay >= 0}（已知）、早于
     * {@code currentDay - maxMissingDays}、当前非 {@code waveActive}、{@code waveCount == 0}。
     * 触发过尸潮的条目即便村民已死也保留——删掉后同 UUID 重建会让「当日已触发」记录丢失，可能重复触发。
     *
     * <p>遇到 {@code lastSeenDay == -1}（老存档）只回写当前日、<b>不删</b>，相当于播种一轮。
     *
     * @return 本次删除的条目数
     */
    public int pruneStaleEntries(int currentDay, int maxMissingDays) {
        if (schedules.isEmpty()) return 0;
        int threshold = currentDay - maxMissingDays;
        java.util.List<UUID> toRemove = new java.util.ArrayList<>();
        int seeded = 0;
        // 注意：遍历中只做「同 key 替换值」（非结构修改，不会 ConcurrentModificationException），
        // 删除统一收集到 toRemove，循环结束后执行。
        for (var e : schedules.entrySet()) {
            ScheduleEntry entry = e.getValue();
            if (entry.lastSeenDay() < 0) {
                schedules.put(e.getKey(), entry.withLastSeenDay(currentDay));
                seeded++;
                continue;
            }
            if (entry.lastSeenDay() < threshold && !entry.waveActive() && entry.waveCount() == 0) {
                toRemove.add(e.getKey());
                if (toRemove.size() >= 64) break;   // 单次上限，避免跨天时卡顿
            }
        }
        for (UUID id : toRemove) {
            schedules.remove(id);
        }
        if (seeded > 0 || !toRemove.isEmpty()) {
            setDirty();
        }
        if (seeded > 0) {
            EnikZombietide.LOGGER.info("村民调度表：{} 条老条目缺少 lastSeenDay，已播种为 DAY {}", seeded, currentDay);
        }
        if (!toRemove.isEmpty()) {
            EnikZombietide.LOGGER.info("村民调度表：清理 {} 条陈旧空调度（{} 天未见且从未触发）", toRemove.size(), maxMissingDays);
        }
        return toRemove.size();
    }

    public void remove(UUID villagerUuid) {
        schedules.remove(villagerUuid);
        setDirty();
    }

    public Map<UUID, ScheduleEntry> getAllSchedules() {
        return schedules;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag schedulesTag = new CompoundTag();
        for (var entry : schedules.entrySet()) {
            DataResult<Tag> result = ScheduleEntry.CODEC.encodeStart(NbtOps.INSTANCE, entry.getValue());
            result.resultOrPartial(EnikZombietide.LOGGER::error).ifPresent(encoded -> {
                if (encoded instanceof CompoundTag compound) {
                    schedulesTag.put(entry.getKey().toString(), compound);
                }
            });
        }
        tag.put("schedules", schedulesTag);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        schedules.clear();
        if (tag == null || tag.isEmpty()) return;
        CompoundTag schedulesTag = tag.getCompound("schedules");
        for (String key : schedulesTag.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                CompoundTag entryTag = schedulesTag.getCompound(key);
                DataResult<ScheduleEntry> result = ScheduleEntry.CODEC.parse(NbtOps.INSTANCE, entryTag);
                result.resultOrPartial(EnikZombietide.LOGGER::error).ifPresent(entry -> schedules.put(uuid, entry));
            } catch (IllegalArgumentException ignored) {}
        }
    }
}
