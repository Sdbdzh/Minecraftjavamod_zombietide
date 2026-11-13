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
            boolean waveActive
    ) {
        public static final Codec<ScheduleEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("modDay").forGetter(ScheduleEntry::modDay),
                Codec.INT.fieldOf("lastTriggerDay").forGetter(ScheduleEntry::lastTriggerDay),
                Codec.INT.fieldOf("waveCount").forGetter(ScheduleEntry::waveCount),
                Codec.BOOL.fieldOf("waveActive").forGetter(ScheduleEntry::waveActive)
        ).apply(instance, ScheduleEntry::new));
    }

    public VillagerScheduleData() {
    }

    public ScheduleEntry get(UUID villagerUuid) {
        return schedules.computeIfAbsent(villagerUuid, k -> new ScheduleEntry(0, -1, 0, false));
    }

    public void put(UUID villagerUuid, ScheduleEntry entry) {
        schedules.put(villagerUuid, entry);
        setDirty();
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
