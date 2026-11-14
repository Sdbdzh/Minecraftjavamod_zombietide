package com.enik.zombietide.network;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.client.ZombietideHud;
import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.ZombietideConfig;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.UUID;

/**
 * 客户端 <-> 服务端 自定义网络通道。
 * 主要用途：客户端 ESC 菜单修改配置后，发送 ConfigToServerPayload 给服务端，
 * 服务端修改 Level Data 并通过 PacketDistributor 广播给所有在线客户端，实现“立即生效”。
 */
@EventBusSubscriber(modid = EnikZombietide.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModMessages {

    /** 客户端 -> 服务端：请求同步配置（独立 ID，避免与下行方向冲突） */
    public static final ResourceLocation CONFIG_TO_SERVER_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "config_to_server");
    /** 服务端 -> 客户端：广播配置 */
    public static final ResourceLocation CONFIG_TO_CLIENT_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "config_to_client");

    /** 客户端 -> 服务端：请求同步配置 */
    public record ConfigToServerPayload(ZombietideConfig config) implements CustomPacketPayload {
        public static final Type<ConfigToServerPayload> TYPE = new Type<>(CONFIG_TO_SERVER_ID);

        public static final StreamCodec<FriendlyByteBuf, ConfigToServerPayload> STREAM_CODEC =
                ConfigCodec.STREAM_CODEC.map(ConfigToServerPayload::new, ConfigToServerPayload::config);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：广播配置 */
    public record ConfigToClientPayload(ZombietideConfig config) implements CustomPacketPayload {
        public static final Type<ConfigToClientPayload> TYPE = new Type<>(CONFIG_TO_CLIENT_ID);

        public static final StreamCodec<FriendlyByteBuf, ConfigToClientPayload> STREAM_CODEC =
                ConfigCodec.STREAM_CODEC.map(ConfigToClientPayload::new, ConfigToClientPayload::config);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：同步玩家模组天数 */
    public static final ResourceLocation PLAYER_MOD_DAY_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "player_mod_day");

    public record PlayerModDayPayload(UUID playerUuid, int modDay, boolean canTriggerHorde) implements CustomPacketPayload {
        public static final Type<PlayerModDayPayload> TYPE = new Type<>(PLAYER_MOD_DAY_ID);

        public static final StreamCodec<FriendlyByteBuf, PlayerModDayPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, PlayerModDayPayload payload) {
                buf.writeUUID(payload.playerUuid);
                buf.writeInt(payload.modDay);
                buf.writeBoolean(payload.canTriggerHorde);
            }

            @Override
            public PlayerModDayPayload decode(FriendlyByteBuf buf) {
                UUID uuid = buf.readUUID();
                int modDay = buf.readInt();
                boolean canTrigger = buf.readBoolean();
                return new PlayerModDayPayload(uuid, modDay, canTrigger);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：同步村民模组天数 */
    public static final ResourceLocation VILLAGER_MOD_DAY_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "villager_mod_day");

    /** 服务端 -> 客户端：mod info 常驻显示开关 */
    public static final ResourceLocation INFO_OVERLAY_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "info_overlay");

    public record VillagerModDayPayload(UUID villagerUuid, String registryKey, int modDay, double x, double y, double z) implements CustomPacketPayload {
        public static final Type<VillagerModDayPayload> TYPE = new Type<>(VILLAGER_MOD_DAY_ID);

        public static final StreamCodec<FriendlyByteBuf, VillagerModDayPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, VillagerModDayPayload payload) {
                buf.writeUUID(payload.villagerUuid);
                buf.writeUtf(payload.registryKey());
                buf.writeInt(payload.modDay());
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
            }

            @Override
            public VillagerModDayPayload decode(FriendlyByteBuf buf) {
                UUID uuid = buf.readUUID();
                String registryKey = buf.readUtf(32);
                int modDay = buf.readInt();
                double x = buf.readDouble();
                double y = buf.readDouble();
                double z = buf.readDouble();
                return new VillagerModDayPayload(uuid, registryKey, modDay, x, y, z);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：mod info 常驻显示开关 */
    public record InfoOverlayPayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<InfoOverlayPayload> TYPE = new Type<>(INFO_OVERLAY_ID);

        public static final StreamCodec<FriendlyByteBuf, InfoOverlayPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, InfoOverlayPayload payload) {
                buf.writeBoolean(payload.enabled);
            }

            @Override
            public InfoOverlayPayload decode(FriendlyByteBuf buf) {
                return new InfoOverlayPayload(buf.readBoolean());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：村民广播批次开始（清理旧缓存） */
    public static final ResourceLocation VILLAGER_BROADCAST_START_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "villager_broadcast_start");
    public record VillagerBroadcastStartPayload() implements CustomPacketPayload {
        public static final Type<VillagerBroadcastStartPayload> TYPE = new Type<>(VILLAGER_BROADCAST_START_ID);
        public static final StreamCodec<FriendlyByteBuf, VillagerBroadcastStartPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, VillagerBroadcastStartPayload payload) {}
            @Override
            public VillagerBroadcastStartPayload decode(FriendlyByteBuf buf) { return new VillagerBroadcastStartPayload(); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 服务端 -> 客户端：村民广播批次结束（清理不在本批次的村民） */
    public static final ResourceLocation VILLAGER_BROADCAST_END_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "villager_broadcast_end");
    public record VillagerBroadcastEndPayload() implements CustomPacketPayload {
        public static final Type<VillagerBroadcastEndPayload> TYPE = new Type<>(VILLAGER_BROADCAST_END_ID);
        public static final StreamCodec<FriendlyByteBuf, VillagerBroadcastEndPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, VillagerBroadcastEndPayload payload) {}
            @Override
            public VillagerBroadcastEndPayload decode(FriendlyByteBuf buf) { return new VillagerBroadcastEndPayload(); }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** 共享的 ZombietideConfig 编解码器 */
    private static class ConfigCodec {
        static final StreamCodec<FriendlyByteBuf, ZombietideConfig> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, ZombietideConfig c) {
                // 玩家尸潮
                buf.writeBoolean(c.playerHordeEnabled());
                buf.writeInt(c.playerIntervalDays());
                buf.writeBoolean(c.playerIntervalRandom());
                buf.writeInt(c.playerIntervalMin());
                buf.writeInt(c.playerIntervalMax());
                buf.writeInt(c.playerCount());
                buf.writeBoolean(c.playerCountRandom());
                buf.writeInt(c.playerCountMin());
                buf.writeInt(c.playerCountMax());
                // 村民尸潮
                buf.writeBoolean(c.villagerHordeEnabled());
                buf.writeInt(c.villagerIntervalDays());
                buf.writeBoolean(c.villagerIntervalRandom());
                buf.writeInt(c.villagerIntervalMin());
                buf.writeInt(c.villagerIntervalMax());
                buf.writeInt(c.villagerCount());
                buf.writeBoolean(c.villagerCountRandom());
                buf.writeInt(c.villagerCountMin());
                buf.writeInt(c.villagerCountMax());
                // 生成配置
                buf.writeInt(c.batchTick());
                buf.writeInt(c.batchSize());
                buf.writeInt(c.villagerBatchSize());
                buf.writeInt(c.playerRadius());
                buf.writeInt(c.villagerRadius());
                buf.writeBoolean(c.spawnByBiome());
                buf.writeInt(c.minSpawnDist());
                buf.writeInt(c.maxSpawnDist());
                buf.writeInt(c.spawnAttempts());
                buf.writeFloat(c.distanceWeight());
                buf.writeFloat(c.darkWeight());
                // 全局上限
                buf.writeInt(c.globalCap());
                // 世界规则
                buf.writeBoolean(c.daylightBurn());
                buf.writeBoolean(c.blockSleep());
                buf.writeUtf(c.debtMode(), 32);
                buf.writeInt(c.debtCap());
                buf.writeInt(c.despawnSeconds());
                buf.writeInt(c.villagerWarningRange());
                buf.writeInt(c.villagerSleepBlockRange());
                // 警告
                buf.writeBoolean(c.warningEnabled());
                buf.writeBoolean(c.warningTitle());
                buf.writeBoolean(c.warningSound());
                buf.writeBoolean(c.blockNaturalSpawn());
                // 定制音频开关
                buf.writeBoolean(c.morningWarningSound());
                buf.writeBoolean(c.scatterSound());
                buf.writeBoolean(c.hordeBgmSound());
                // HUD
                buf.writeBoolean(c.hudEnabled());
                buf.writeBoolean(c.showVillagerHorde());
                buf.writeInt(c.villagerHordeDisplayRange());
                buf.writeInt(c.villagerHordeMaxEntries());
                buf.writeUtf(c.hudPosition(), 16);
                buf.writeInt(c.hudX());
                buf.writeInt(c.hudY());
                buf.writeInt(c.villagerHordeX());
                buf.writeInt(c.villagerHordeY());
                buf.writeFloat(c.hudScale());
                buf.writeFloat(c.villagerHordeScale());
                buf.writeBoolean(c.hudBold());
                buf.writeBoolean(c.villagerHordeBold());
            }

            @Override
            public ZombietideConfig decode(FriendlyByteBuf buf) {
                // 玩家尸潮
                boolean playerHordeEnabled = buf.readBoolean();
                int playerIntervalDays = buf.readInt();
                boolean playerIntervalRandom = buf.readBoolean();
                int playerIntervalMin = buf.readInt();
                int playerIntervalMax = buf.readInt();
                int playerCount = buf.readInt();
                boolean playerCountRandom = buf.readBoolean();
                int playerCountMin = buf.readInt();
                int playerCountMax = buf.readInt();
                // 村民尸潮
                boolean villagerHordeEnabled = buf.readBoolean();
                int villagerIntervalDays = buf.readInt();
                boolean villagerIntervalRandom = buf.readBoolean();
                int villagerIntervalMin = buf.readInt();
                int villagerIntervalMax = buf.readInt();
                int villagerCount = buf.readInt();
                boolean villagerCountRandom = buf.readBoolean();
                int villagerCountMin = buf.readInt();
                int villagerCountMax = buf.readInt();
                // 生成配置
                int batchTick = buf.readInt();
                int batchSize = buf.readInt();
                int villagerBatchSize = buf.readInt();
                int playerRadius = buf.readInt();
                int villagerRadius = buf.readInt();
                boolean spawnByBiome = buf.readBoolean();
                int minSpawnDist = buf.readInt();
                int maxSpawnDist = buf.readInt();
                int spawnAttempts = buf.readInt();
                float distanceWeight = buf.readFloat();
                float darkWeight = buf.readFloat();
                // 全局上限
                int globalCap = buf.readInt();
                // 世界规则
                boolean daylightBurn = buf.readBoolean();
                boolean blockSleep = buf.readBoolean();
                String debtMode = buf.readUtf(32);
                int debtCap = buf.readInt();
                int despawnSeconds = buf.readInt();
                int villagerWarningRange = buf.readInt();
                int villagerSleepBlockRange = buf.readInt();
                // 警告
                boolean warningEnabled = buf.readBoolean();
                boolean warningTitle = buf.readBoolean();
                boolean warningSound = buf.readBoolean();
                boolean blockNaturalSpawn = buf.readBoolean();
                // 定制音频开关
                boolean morningWarningSound = buf.readBoolean();
                boolean scatterSound = buf.readBoolean();
                boolean hordeBgmSound = buf.readBoolean();
                // HUD
                boolean hudEnabled = buf.readBoolean();
                boolean showVillagerHorde = buf.readBoolean();
                int villagerHordeDisplayRange = buf.readInt();
                int villagerHordeMaxEntries = buf.readInt();
                String hudPosition = buf.readUtf(16);
                int hudX = buf.readInt();
                int hudY = buf.readInt();
                int villagerHordeX = buf.readInt();
                int villagerHordeY = buf.readInt();
                float hudScale = buf.readFloat();
                float villagerHordeScale = buf.readFloat();
                boolean hudBold = buf.readBoolean();
                boolean villagerHordeBold = buf.readBoolean();

                return new ZombietideConfig(
                        new ZombietideConfig.PlayerHordeConfig(
                                playerHordeEnabled, playerIntervalDays, playerIntervalRandom,
                                playerIntervalMin, playerIntervalMax, playerCount,
                                playerCountRandom, playerCountMin, playerCountMax
                        ),
                        new ZombietideConfig.VillagerHordeConfig(
                                villagerHordeEnabled, villagerIntervalDays, villagerIntervalRandom,
                                villagerIntervalMin, villagerIntervalMax, villagerCount,
                                villagerCountRandom, villagerCountMin, villagerCountMax
                        ),
                        new ZombietideConfig.SpawnConfig(
                                batchTick, batchSize, villagerBatchSize, playerRadius, villagerRadius, spawnByBiome,
                                minSpawnDist, maxSpawnDist, spawnAttempts, distanceWeight, darkWeight
                        ),
                        new ZombietideConfig.WorldConfig(
                                globalCap, daylightBurn, blockSleep,
                                debtMode, debtCap, despawnSeconds, villagerWarningRange, villagerSleepBlockRange
                        ),
                        new ZombietideConfig.WarningConfig(
                                warningEnabled, warningTitle, warningSound, blockNaturalSpawn,
                                morningWarningSound, scatterSound, hordeBgmSound
                        ),
                        new ZombietideConfig.HudConfig(
                                hudEnabled, showVillagerHorde, hudPosition, hudX, hudY,
                                villagerHordeX, villagerHordeY, hudScale, villagerHordeScale,
                                hudBold, villagerHordeBold, villagerHordeDisplayRange, villagerHordeMaxEntries
                        )
                );
            }
        };
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1.0")
                // 客户端 -> 服务端
                .playToServer(ConfigToServerPayload.TYPE, ConfigToServerPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                Player player = context.player();
                                if (player instanceof ServerPlayer serverPlayer) {
                                    ServerLevel level = serverPlayer.serverLevel();
                                    ConfigManager.applyRemoteConfig(payload.config(), level);
                                    // 广播给所有在线客户端
                                    PacketDistributor.sendToAllPlayers(
                                            new ConfigToClientPayload(payload.config()));
                                }
                            });
                        })
                // 服务端 -> 客户端
                .playToClient(ConfigToClientPayload.TYPE, ConfigToClientPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ConfigManager.applyClientConfig(payload.config());
                                // 同步给客户端 HUD（位置/开关/天数判定参数）
                                ZombietideHud.onConfigReceived(payload.config());
                            });
                        })
                // 服务端 -> 客户端：玩家模组天数同步
                .playToClient(PlayerModDayPayload.TYPE, PlayerModDayPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onModDayReceived(payload.playerUuid(), payload.modDay(), payload.canTriggerHorde());
                            });
                        })
                // 服务端 -> 客户端：村民模组天数同步
                .playToClient(VillagerModDayPayload.TYPE, VillagerModDayPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onVillagerModDayReceived(payload.villagerUuid(), payload.registryKey(),
                                        payload.modDay(), payload.x(), payload.y(), payload.z());
                            });
                        })
                // 服务端 -> 客户端：mod info 常驻显示开关
                .playToClient(InfoOverlayPayload.TYPE, InfoOverlayPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onInfoOverlayReceived(payload.enabled());
                            });
                        })
                // 服务端 -> 客户端：村民广播批次开始
                .playToClient(VillagerBroadcastStartPayload.TYPE, VillagerBroadcastStartPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onVillagerBroadcastStart();
                            });
                        })
                // 服务端 -> 客户端：村民广播批次结束
                .playToClient(VillagerBroadcastEndPayload.TYPE, VillagerBroadcastEndPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onVillagerBroadcastEnd();
                            });
                        });
    }

    /** 服务端主动广播配置给所有在线客户端（例如服务器启动时同步给新登录玩家） */
    public static void broadcastConfig(ZombietideConfig config) {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            PacketDistributor.sendToAllPlayers(new ConfigToClientPayload(config));
        }
    }

    /** 客户端 -> 服务端：发送配置修改（ESC 菜单即时生效）。
     * 客户端没有 ServerLifecycleHooks（无服务端实例），故不做 null 检查，
     * 始终通过 PacketDistributor.sendToServer() 将配置发给服务端处理。 */
    public static void sendConfigToServer(ZombietideConfig config) {
        PacketDistributor.sendToServer(new ConfigToServerPayload(config));
    }

    /** 发送玩家模组天数给特定玩家 */
    public static void sendModDay(ServerPlayer player, int modDay, boolean canTriggerHorde) {
        PacketDistributor.sendToPlayer(player, new PlayerModDayPayload(player.getUUID(), modDay, canTriggerHorde));
    }

    /** 发送村民模组天数给特定玩家（用于 HUD 显示附近村民） */
    public static void sendVillagerModDay(ServerPlayer player, UUID villagerUuid, String registryKey, int modDay, double x, double y, double z) {
        PacketDistributor.sendToPlayer(player, new VillagerModDayPayload(villagerUuid, registryKey, modDay, x, y, z));
    }

    /** 发送村民模组天数给特定玩家（用于 HUD 显示附近村民）——兼容旧签名，自动提取 registryKey */
    public static void sendVillagerModDay(ServerPlayer player, UUID villagerUuid, net.minecraft.world.entity.npc.Villager villager, int modDay, double x, double y, double z) {
        String registryKey = extractVillagerRegistryKey(villager);
        PacketDistributor.sendToPlayer(player, new VillagerModDayPayload(villagerUuid, registryKey, modDay, x, y, z));
    }

    /** 广播所有玩家的模组天数给所有在线客户端 */
    public static void broadcastAllModDays() {
        if (ServerLifecycleHooks.getCurrentServer() == null) return;
        ServerLevel overworld = ServerLifecycleHooks.getCurrentServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) return;
        for (ServerPlayer player : overworld.getPlayers(p -> true)) {
            var data = ModAttachments.getPlayerHordeData(player);
            boolean canTrigger = player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                    && player.serverLevel().getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL;
            sendModDay(player, data.modDay(), canTrigger);
        }
    }

    /** 广播所有村民的模组天数给范围内的在线客户端（≤128 格，避免全服广播风暴）
     * 数据源：VillagerScheduleData 调度表（权威数据），实体附件仅作为加载缓存。
     * 读调度表避免实体附件滞后导致的 HUD 村民 DAY 显示不一致。 */
    public static void broadcastAllVillagerModDays() {
        if (ServerLifecycleHooks.getCurrentServer() == null) return;
        ServerLevel overworld = ServerLifecycleHooks.getCurrentServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) return;

        // 广播批次开始：通知客户端清理旧缓存
        PacketDistributor.sendToAllPlayers(new VillagerBroadcastStartPayload());

        // 权威数据：调度表
        com.enik.zombietide.config.VillagerScheduleData schedule = overworld.getDataStorage().computeIfAbsent(
                com.enik.zombietide.config.VillagerScheduleData.FACTORY,
                com.enik.zombietide.config.VillagerScheduleData.ID);

        // 收集主世界所有村民实体（仅一次遍历实体列表）
        java.util.List<net.minecraft.world.entity.npc.Villager> villagers = new java.util.ArrayList<>();
        for (var entity : overworld.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.npc.Villager villager) {
                villagers.add(villager);
            }
        }
        if (villagers.isEmpty()) {
            PacketDistributor.sendToAllPlayers(new VillagerBroadcastEndPayload());
            return;
        }

        // 对每个玩家，仅检查距离 ≤128 格的村民
        for (ServerPlayer player : overworld.getPlayers(p -> true)) {
            for (net.minecraft.world.entity.npc.Villager villager : villagers) {
                if (player.distanceToSqr(villager) <= 128.0 * 128.0) {
                    // 读调度表（权威），不读实体附件
                    var sched = schedule.get(villager.getUUID());
                    sendVillagerModDay(player, villager.getUUID(),
                        extractVillagerRegistryKey(villager),
                        sched.modDay(),
                        villager.getX(), villager.getY(), villager.getZ());
                }
            }
        }

        // 广播批次结束：通知客户端清理不在本批次的村民
        PacketDistributor.sendToAllPlayers(new VillagerBroadcastEndPayload());
    }

    /** 发送 mod info 常驻显示开关给特定玩家 */
    public static void sendInfoOverlay(ServerPlayer player, boolean enabled) {
        PacketDistributor.sendToPlayer(player, new InfoOverlayPayload(enabled));
    }

    /** 将村民职业 registry key 提取，返回标准格式 "namespace:path"，保证不泄露 Holder 包装格式到 UI */
    public static String extractVillagerRegistryKey(net.minecraft.world.entity.npc.Villager villager) {
        var profession = villager.getVillagerData().getProfession();
        // VillagerProfession 是记录类，toString() 仅返回职业名（如 "librarian"）而不含命名空间。
        // 使用 BuiltInRegistries.VILLAGER_PROFESSION.getKey() 获取完整 registry key（如 "minecraft:librarian"），最稳健可靠
        String key = net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).toString();
        if (key != null && !key.isEmpty()) {
            return key;
        }
        // 极少数边界情况：回退到 toString
        String prof = profession.toString();
        EnikZombietide.LOGGER.warn("村民 {} 职业提取失败，回退原始值：{}", villager.getUUID(), prof);
        return prof;
    }

    /** 将职业 registry key 解析为 Minecraft Java 原版标准译名。
     * 使用原版翻译键 entity.minecraft.villager.*，随客户端语言自动切换（zh_cn / en_us / 其他）。
     * 支持带命名空间（minecraft:librarian）和不带命名空间（librarian、none）两种输入。 */
    public static String localizeProfession(String registryKey) {
        if (registryKey == null || registryKey.isEmpty()) {
            return Component.translatable("entity.minecraft.villager.none").getString();
        }
        String path = registryKey.contains(":") ? registryKey.substring(registryKey.indexOf(':') + 1) : registryKey;
        if ("unemployed".equals(path)) {
            path = "none";
        }
        String key = "entity.minecraft.villager." + path;
        String localized = Component.translatable(key).getString();
        // 未命中翻译（模组新增职业等）：退回可读的 registry path
        return localized.equals(key) ? path.replace('_', ' ') : localized;
    }
}