package com.enik.zombietide.config;

import com.enik.zombietide.EnikZombietide;
import com.mojang.serialization.DataResult;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 持久化到世界级 SavedData（Level Data）的配置数据。
 * 不同存档独立，随存档存放。
 */
public class ZombietideConfigData extends SavedData {

    public static final String ID = "enik_zombietide_config";
    public static final ResourceLocation ID_LOCATION = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "config");

    /** SavedData 工厂，用于 computeIfAbsent */
    public static final SavedData.Factory<ZombietideConfigData> FACTORY = new SavedData.Factory<>(
            ZombietideConfigData::new,
            (tag, registries) -> {
                ZombietideConfigData data = new ZombietideConfigData();
                data.load(tag, registries);
                return data;
            }
    );

    private ZombietideConfig config = ZombietideConfig.defaults();

    public ZombietideConfigData() {
    }

    public ZombietideConfigData(ZombietideConfig config) {
        this.config = config;
    }

    public ZombietideConfig getConfig() {
        return config;
    }

    public void setConfig(ZombietideConfig config) {
        this.config = config;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // 使用 Codec 将配置编码到 NBT
        DataResult<Tag> result = ZombietideConfig.CODEC.encodeStart(NbtOps.INSTANCE, config);
        result.resultOrPartial(EnikZombietide.LOGGER::error).ifPresent(encoded -> {
            if (encoded instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    tag.put(key, compound.get(key));
                }
            }
        });
        return tag;
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag == null || tag.isEmpty()) {
            this.config = ZombietideConfig.defaults();
            return;
        }
        DataResult<ZombietideConfig> result = ZombietideConfig.CODEC.parse(NbtOps.INSTANCE, tag);
        result.resultOrPartial(EnikZombietide.LOGGER::error).ifPresent(config -> this.config = config);
    }
}