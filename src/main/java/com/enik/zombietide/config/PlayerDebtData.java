package com.enik.zombietide.config;

import com.enik.zombietide.EnikZombietide;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家血债世界级存储（SavedData，Level Data）。
 *
 * <p>以下三点是 v0.1.5x 之前「血债存在 attachment 里」时代的历史问题，仅作背景保留：
 * 现行已改为世界级 SavedData，且只有 23460 一个结算点。
 * <p>血债原本存在 {@code PlayerHordeData} attachment 里，而 attachment 必须拿到在线
 * {@code ServerPlayer} 实例才能读写。这导致三个问题：
 * <ul>
 *   <li>23460 起火结算时离线玩家 {@code findTarget} 返回 null 被跳过，血债没写</li>
 *   <li>次日黎明结算时僵尸已清除、{@code count == 0}，触发「全灭清零」把血债归零</li>
 *   <li>{@code lastDebtSettledDay} 与 {@code pendingDebtSettle} 是内存结构，重启即丢</li>
 * </ul>
 *
 * <p>改为世界级持久化后，结算不再依赖实体是否在线；结算日与待结算表也一并持久化。
 *
 * <p><b>所有调用必须传主世界 Level</b>——SavedData 按 Level 归属，传错维度会读到另一份数据。
 */
public class PlayerDebtData extends SavedData {

    public static final String ID = "enik_zombietide_player_debt";

    public static final SavedData.Factory<PlayerDebtData> FACTORY = new SavedData.Factory<>(
            PlayerDebtData::new,
            (tag, registries) -> {
                PlayerDebtData data = new PlayerDebtData();
                data.load(tag, registries);
                return data;
            }
    );

    /**
     * @param settledWave 【当前仅写入、不读取】原「按波次去重」标记（v0.1.55），
     *                    v0.1.56 起无消费方；保留是为了 Codec 向后兼容老存档
     */
    public record Entry(int debt, long lastSettledDay, int settledWave) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("debt").forGetter(Entry::debt),
                // lastSettledDay 必须保留为必填字段：删掉会让老存档 Entry 解析失败，
                // resultOrPartial 会静默丢弃整张表 → 血债全部归 0。
                Codec.LONG.fieldOf("lastSettledDay").forGetter(Entry::lastSettledDay),
                // 【v0.1.62 补注】默认 -1：waveSeq 从 0 起，若默认 0 会让首次结算被误判「已结算」。
                // 该字段当前仅写入、不读取（v0.1.56 结算点唯一化后无消费方），保留兼容老存档。
                Codec.INT.optionalFieldOf("settledWave", -1).forGetter(Entry::settledWave)
        ).apply(instance, Entry::new));

        public Entry(int debt, long lastSettledDay) {
            this(debt, lastSettledDay, -1);
        }

        public static Entry empty() {
            return new Entry(0, Long.MIN_VALUE, -1);
        }
    }

    /** 玩家 UUID -> 血债 + 最后结算日 */
    private final Map<UUID, Entry> entries = new HashMap<>();
    /** 待血债结算的目标（只含玩家，村民在入表前已被 isKnown 过滤） */
    private final Set<UUID> pending = new HashSet<>();

    public PlayerDebtData() {
    }

    public static PlayerDebtData get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /**
     * 注册玩家（触发尸潮时调用）。
     *
     * <p>同时充当「已知玩家」标记：离线玩家实体为 null，无法再用
     * {@code instanceof ServerPlayer} 区分玩家与村民，因此以本表是否有记录为准。
     * <b>触发路径必须调用，否则该玩家的血债永远记不上。</b>
     */
    public void register(UUID uuid) {
        if (entries.putIfAbsent(uuid, Entry.empty()) == null) {
            setDirty();
        }
    }

    public boolean isKnown(UUID uuid) {
        return entries.containsKey(uuid);
    }

    public int getDebt(UUID uuid) {
        Entry e = entries.get(uuid);
        return e == null ? 0 : e.debt();
    }

    public void setDebt(UUID uuid, int debt) {
        Entry e = entries.get(uuid);
        long day = e == null ? Long.MIN_VALUE : e.lastSettledDay();
        int settled = e == null ? -1 : e.settledWave();   // 保留波次标记，不能丢
        entries.put(uuid, new Entry(Math.max(0, debt), day, settled));
        setDirty();
    }

    /**
     * 【当前无调用点，保留备查】该目标的这一波是否已经结算过血债。
     * <p>v0.1.55 引入「按波次去重」，v0.1.56 改为结算点唯一（只有 23460 起火结算）后不再需要判断。
     * 保留以便将来若加回第二个结算点时直接复用。
     * <p>{@code waveSeq <= 0} 表示波次未分配（初始态 / 重置后），<b>永不判为已结算</b>——
     * 否则重置路径用 4 参构造写入的 waveSeq=0 会被 settledWave=0 误挡。
     */
    public boolean isWaveSettled(UUID uuid, int waveSeq) {
        if (waveSeq <= 0) return false;
        Entry e = entries.get(uuid);
        return e != null && e.settledWave() == waveSeq;
    }

    /** 标记该目标的这一波已结算 */
    public void markWaveSettled(UUID uuid, int waveSeq) {
        Entry e = entries.get(uuid);
        int debt = e == null ? 0 : e.debt();
        long day = e == null ? Long.MIN_VALUE : e.lastSettledDay();
        entries.put(uuid, new Entry(debt, day, waveSeq));
        setDirty();
    }

    /** 【当前无调用点，保留备查】按天判断是否已结算（v0.1.55 前的机制）。 */
    public boolean isSettled(UUID uuid, long day) {
        Entry e = entries.get(uuid);
        return e != null && e.lastSettledDay() == day;
    }

    /** 【当前无调用点，保留备查】按天标记已结算（v0.1.55 前的机制）。 */
    public void markSettled(UUID uuid, long day) {
        Entry e = entries.get(uuid);
        int debt = e == null ? 0 : e.debt();
        int settled = e == null ? -1 : e.settledWave();   // 保留波次标记
        entries.put(uuid, new Entry(debt, day, settled));
        setDirty();
    }

    public void markPending(UUID uuid) {
        if (pending.add(uuid)) {
            setDirty();
        }
    }

    public void clearPending(UUID uuid) {
        if (pending.remove(uuid)) {
            setDirty();
        }
    }

    /** 取出全部待结算目标并清空（调用方负责逐个结算） */
    public List<UUID> drainPending() {
        List<UUID> out = new ArrayList<>(pending);
        if (!pending.isEmpty()) {
            pending.clear();
            setDirty();
        }
        return out;
    }

    /** 清空全部玩家血债与待结算表（{@code /zombietide debug clear} 使用） */
    public void clearAll() {
        entries.clear();
        pending.clear();
        setDirty();
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

        ListTag pendingTag = new ListTag();
        for (UUID uuid : pending) {
            pendingTag.add(StringTag.valueOf(uuid.toString()));
        }
        tag.put("pending", pendingTag);
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        entries.clear();
        pending.clear();
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

        ListTag pendingTag = tag.getList("pending", Tag.TAG_STRING);
        for (int i = 0; i < pendingTag.size(); i++) {
            try {
                pending.add(UUID.fromString(pendingTag.getString(i)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }
}
