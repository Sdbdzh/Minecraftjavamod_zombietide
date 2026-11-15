package com.enik.zombietide.config;

import com.enik.zombietide.EnikZombietide;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 尸潮刷怪进度世界级存储（SavedData，Level Data）。
 *
 * <p>刷怪引擎的运行时状态（{@code activeHordes}、{@code batchTimer} 等）是 JVM 内存，
 * 服务器重启即清空——尸潮生成期间重启会导致本应刷 60 只只刷了 24 只、剩余永远不刷。
 *
 * <p>本表只持久化一个数：<b>每个进行中的尸潮还有多少只没刷</b>。重启后
 * {@code HordeSpawner.update} 的恢复逻辑按表重建内存状态继续刷怪。
 *
 * <p><b>自愈约定</b>：波次的各条结束路径（黎明结算、强制四散、跨天对账等）不逐一
 * 从本表 remove——恢复逻辑在下次 update 时校验目标是否仍处于当天波次中
 * （玩家 {@code waveActive && lastTriggerDay == today} / 村民调度表同条件），
 * 校验不过就 remove。因此残留条目最多存活到下一次 update tick。
 *
 * <p><b>所有调用必须传主世界 Level</b>——SavedData 按 Level 归属。
 */
public class HordeProgressData extends SavedData {

    public static final String ID = "enik_zombietide_horde_progress";

    public static final SavedData.Factory<HordeProgressData> FACTORY = new SavedData.Factory<>(
            HordeProgressData::new,
            (tag, registries) -> {
                HordeProgressData data = new HordeProgressData();
                data.load(tag, registries);
                return data;
            }
    );

    public record Entry(int remaining, boolean villager) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("remaining").forGetter(Entry::remaining),
                Codec.BOOL.fieldOf("villager").forGetter(Entry::villager)
        ).apply(instance, Entry::new));
    }

    /** 尸潮目标 UUID -> 剩余待刷数量 + 是否村民尸潮 */
    private final Map<UUID, Entry> entries = new HashMap<>();

    public HordeProgressData() {
    }

    public static HordeProgressData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** 记录/更新某目标的剩余待刷数（每个写方法末尾 setDirty） */
    public void setRemaining(UUID targetId, int remaining, boolean villager) {
        if (remaining <= 0) {
            remove(targetId);
            return;
        }
        entries.put(targetId, new Entry(remaining, villager));
        setDirty();
    }

    public Entry get(UUID targetId) {
        return entries.get(targetId);
    }

    public void remove(UUID targetId) {
        if (entries.remove(targetId) != null) {
            setDirty();
        }
    }

    /** 全部清空（{@code /zombietide debug clear} 使用） */
    public void clearAll() {
        if (!entries.isEmpty()) {
            entries.clear();
            setDirty();
        }
    }

    /** 恢复逻辑只读视图 */
    public Map<UUID, Entry> getAll() {
        return entries;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag entriesTag = new CompoundTag();
        for (var e : entries.entrySet()) {
            DataResult<Tag> result = Entry.CODEC.encodeStart(NbtOps.INSTANCE, e.getValue());
            result.resultOrPartial(EnikZombietide.LOGGER::error).ifPresent(encoded -> {
                if (encoded instanceof CompoundTag compound) {
                    entriesTag.put(e.getKey().toString(), compound);
                }
            });
        }
        tag.put("entries", entriesTag);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        entries.clear();
        if (tag == null || tag.isEmpty()) {
            return;
        }
        CompoundTag entriesTag = tag.getCompound("entries");
        for (String key : entriesTag.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                DataResult<Entry> result = Entry.CODEC.parse(NbtOps.INSTANCE, entriesTag.getCompound(key));
                result.resultOrPartial(EnikZombietide.LOGGER::error).ifPresent(e -> entries.put(uuid, e));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
