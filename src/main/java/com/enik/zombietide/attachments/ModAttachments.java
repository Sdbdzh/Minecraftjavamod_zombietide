package com.enik.zombietide.attachments;

import com.enik.zombietide.EnikZombietide;
import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;

/**
 * Data Attachment 注册表。
 * - PLAYER_HORDE：挂在玩家实体上，记录模组天数、血债等，随玩家存档。
 * - VILLAGER_HORDE：挂在村民实体上，记录每个村民独立的模组天数，随村民区块保存。
 */
public class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, EnikZombietide.MODID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerHordeData>> PLAYER_HORDE =
            ATTACHMENT_TYPES.register("player_horde",
                    () -> AttachmentType.builder(() -> PlayerHordeData.defaults())
                            .serialize(PlayerHordeData.CODEC)
                            .build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<VillagerHordeData>> VILLAGER_HORDE =
            ATTACHMENT_TYPES.register("villager_horde",
                    () -> AttachmentType.builder(() -> VillagerHordeData.defaults())
                            .serialize(VillagerHordeData.CODEC)
                            .build());

    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }

    /**
     * 从 Entity 上读取玩家尸潮附件（无数据时自动创建默认实例）。
     */
    public static PlayerHordeData getPlayerHordeData(Entity entity) {
        return entity.getData(PLAYER_HORDE);
    }

    /**
     * 从 Entity 上读取村民尸潮附件。
     */
    public static VillagerHordeData getVillagerHordeData(Entity entity) {
        return entity.getData(VILLAGER_HORDE);
    }
}
