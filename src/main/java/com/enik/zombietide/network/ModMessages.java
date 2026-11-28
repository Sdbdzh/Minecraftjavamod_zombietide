package com.enik.zombietide.network;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.client.ZombietideHud;
import com.enik.zombietide.client.ZombietideAtmosphere;
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
 * 主要用途：客户端按键打开的设置界面修改配置后，发送 ConfigToServerPayload 给服务端，
 * 服务端修改 Level Data 并通过 PacketDistributor 广播给所有在线客户端，实现“立即生效”。
 */
@EventBusSubscriber(modid = EnikZombietide.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModMessages {

    /** 【v0.2.137】权限下发缓存：UUID -> "level:host"，只在变化时发包，避免每 tick 刷屏 */
    private static final java.util.Map<java.util.UUID, String> LAST_PERMISSION_SENT =
            new java.util.HashMap<>();

    /** 客户端 -> 服务端：请求同步配置（独立 ID，避免与下行方向冲突） */
    public static final ResourceLocation CONFIG_TO_SERVER_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "config_to_server");
    /** 服务端 -> 客户端：广播配置 */
    public static final ResourceLocation CONFIG_TO_CLIENT_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "config_to_client");
    /** 【v0.2.137】服务端 -> 客户端：当前玩家权限（单独发，不能随广播配置） */
    public static final ResourceLocation PERMISSION_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "permission");

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

    /**
     * 【v0.2.137】服务端 -> 客户端：当前玩家的服务端权限。
     * <p>{@code level} = 0~4；{@code host} = 单机房主。
     * <p>单独发给每个玩家——配置广播是全员相同的，权限却是每人不同的。
     */
    public record PermissionPayload(int level, boolean host) implements CustomPacketPayload {
        public static final Type<PermissionPayload> TYPE = new Type<>(PERMISSION_ID);

        public static final StreamCodec<FriendlyByteBuf, PermissionPayload> STREAM_CODEC =
                StreamCodec.composite(
                        net.minecraft.network.codec.ByteBufCodecs.VAR_INT, PermissionPayload::level,
                        net.minecraft.network.codec.ByteBufCodecs.BOOL, PermissionPayload::host,
                        PermissionPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：同步玩家模组天数 */
    public static final ResourceLocation PLAYER_MOD_DAY_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "player_mod_day");

    /** 玩家 HUD 状态同步（共 9 个字段 = playerUuid + 8 项数据）。
     *  <p>数据字段：modDay / hordeDay（是否尸潮日）/ waveActive（是否进行中）/ bloodDebt（血债）/
     *  activePlayerHordes / activeVillagerHordes / aliveHordeZombies（后三项为全局统计）/
     *  hordeProgress（v0.2.91：距下次尸潮预兆期开始的进度 0~1，驱动 DAY X 渐进红）。
     *  <p>客户端没有 attachment 与 SavedData，这些值只能由服务端下发。 */
    public record PlayerModDayPayload(UUID playerUuid, int modDay, boolean hordeDay,
                                      boolean waveActive, int bloodDebt,
                                      int activePlayerHordes, int activeVillagerHordes,
                                      int aliveHordeZombies, float hordeProgress,
                                      boolean hordeDayToday, int ownAliveHordeZombies) implements CustomPacketPayload {
        public static final Type<PlayerModDayPayload> TYPE = new Type<>(PLAYER_MOD_DAY_ID);

        public static final StreamCodec<FriendlyByteBuf, PlayerModDayPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, PlayerModDayPayload payload) {
                buf.writeUUID(payload.playerUuid);
                buf.writeInt(payload.modDay);
                buf.writeBoolean(payload.hordeDay);
                buf.writeBoolean(payload.waveActive);
                buf.writeInt(payload.bloodDebt);
                buf.writeInt(payload.activePlayerHordes);
                buf.writeInt(payload.activeVillagerHordes);
                buf.writeInt(payload.aliveHordeZombies);
                buf.writeFloat(payload.hordeProgress);
                buf.writeBoolean(payload.hordeDayToday());
                buf.writeInt(payload.ownAliveHordeZombies);
            }

            @Override
            public PlayerModDayPayload decode(FriendlyByteBuf buf) {
                UUID uuid = buf.readUUID();
                int modDay = buf.readInt();
                boolean hordeDay = buf.readBoolean();
                boolean waveActive = buf.readBoolean();
                int bloodDebt = buf.readInt();
                int activePlayerHordes = buf.readInt();
                int activeVillagerHordes = buf.readInt();
                int aliveHordeZombies = buf.readInt();
                float hordeProgress = buf.readFloat();
                boolean hordeDayToday = buf.readBoolean();
                int ownAliveHordeZombies = buf.readInt();
                return new PlayerModDayPayload(uuid, modDay, hordeDay, waveActive, bloodDebt,
                        activePlayerHordes, activeVillagerHordes, aliveHordeZombies, hordeProgress,
                        hordeDayToday, ownAliveHordeZombies);
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

    /** 服务端 -> 客户端：尸潮触发瞬间的一次性视觉冲击（暗红闪烁/火光抖动，v0.2.73）。
     *  无字段：收到即触发一次。 */
    public static final ResourceLocation HORDE_FLASH_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "horde_flash");

    /** 服务端 -> 客户端：尸潮预兆强度（v0.2.78）。0=无预兆，0~1=黄昏前逐渐增强。 */
    public static final ResourceLocation OMEN_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "omen");

    /** 服务端 -> 客户端：尸潮威胁强度（v0.2.86）。0=无尸潮僵尸锁定你，1=正被锁定。 */
    public static final ResourceLocation THREAT_ID = ResourceLocation.fromNamespaceAndPath(
            "enik_zombietide", "threat");

    /** 村民职业 registry key 的编解码长度上限（两侧必须一致）。原版最长约 21 字符，64 留足余量 */
    public static final int MAX_PROFESSION_KEY_LEN = 64;

    /** 【v0.2.91】广播用的村民快照：按村民算一次（含进度），供逐玩家循环复用，避免玩家×村民重复计算。 */
    private record VillagerSnapshot(UUID uuid, String registryKey, int modDay,
                                    double x, double y, double z,
                                    boolean waveActive, boolean hordeDay, float progress,
                                    boolean hordeDayToday,
                                    net.minecraft.world.entity.Entity entity) {
    }

    public record VillagerModDayPayload(UUID villagerUuid, String registryKey, int modDay, double x, double y, double z, boolean waveActive, boolean hordeDay, float hordeProgress, boolean hordeDayToday) implements CustomPacketPayload {
        public static final Type<VillagerModDayPayload> TYPE = new Type<>(VILLAGER_MOD_DAY_ID);

        public static final StreamCodec<FriendlyByteBuf, VillagerModDayPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, VillagerModDayPayload payload) {
                buf.writeUUID(payload.villagerUuid);
                // 编解码上限必须一致：解码侧 readUtf(MAX_PROFESSION_KEY_LEN)，
                // 编码侧若无上限，超长键会在客户端解码时抛 DecoderException。
                String profKey = payload.registryKey();
                if (profKey.length() > MAX_PROFESSION_KEY_LEN) {
                    profKey = profKey.substring(0, MAX_PROFESSION_KEY_LEN);
                }
                buf.writeUtf(profKey, MAX_PROFESSION_KEY_LEN);
                buf.writeInt(payload.modDay());
                buf.writeDouble(payload.x());
                buf.writeDouble(payload.y());
                buf.writeDouble(payload.z());
                buf.writeBoolean(payload.waveActive());
                buf.writeBoolean(payload.hordeDay());
                buf.writeFloat(payload.hordeProgress());
                buf.writeBoolean(payload.hordeDayToday());
            }

            @Override
            public VillagerModDayPayload decode(FriendlyByteBuf buf) {
                UUID uuid = buf.readUUID();
                String registryKey = buf.readUtf(MAX_PROFESSION_KEY_LEN);
                int modDay = buf.readInt();
                double x = buf.readDouble();
                double y = buf.readDouble();
                double z = buf.readDouble();
                boolean waveActive = buf.readBoolean();
                boolean hordeDay = buf.readBoolean();
                float hordeProgress = buf.readFloat();
                boolean hordeDayToday = buf.readBoolean();
                return new VillagerModDayPayload(uuid, registryKey, modDay, x, y, z, waveActive, hordeDay, hordeProgress, hordeDayToday);
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

    /** 服务端 -> 客户端：尸潮触发瞬间的一次性视觉冲击（无字段，收到即闪一次） */
    public record HordeFlashPayload() implements CustomPacketPayload {
        public static final Type<HordeFlashPayload> TYPE = new Type<>(HORDE_FLASH_ID);
        public static final HordeFlashPayload INSTANCE = new HordeFlashPayload();

        public static final StreamCodec<FriendlyByteBuf, HordeFlashPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, HordeFlashPayload payload) {
                // 无字段
            }

            @Override
            public HordeFlashPayload decode(FriendlyByteBuf buf) {
                return INSTANCE;
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：尸潮预兆强度（0=无，越大越接近黄昏）。 */
    public record OmenPayload(float strength) implements CustomPacketPayload {
        public static final Type<OmenPayload> TYPE = new Type<>(OMEN_ID);

        public static final StreamCodec<FriendlyByteBuf, OmenPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, OmenPayload payload) {
                buf.writeFloat(payload.strength);
            }

            @Override
            public OmenPayload decode(FriendlyByteBuf buf) {
                return new OmenPayload(buf.readFloat());
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 -> 客户端：尸潮威胁强度（0=未被锁定，1=正被尸潮僵尸锁定） */
    public record ThreatPayload(float strength) implements CustomPacketPayload {
        public static final Type<ThreatPayload> TYPE = new Type<>(THREAT_ID);

        public static final StreamCodec<FriendlyByteBuf, ThreatPayload> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public void encode(FriendlyByteBuf buf, ThreatPayload payload) {
                buf.writeFloat(payload.strength);
            }

            @Override
            public ThreatPayload decode(FriendlyByteBuf buf) {
                return new ThreatPayload(buf.readFloat());
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
                buf.writeInt(c.spawnAttempts());
                buf.writeFloat(c.distanceWeight());
                buf.writeFloat(c.darkWeight());
                buf.writeBoolean(c.screamerEnabled());
                buf.writeInt(c.screamerCooldownMin());
                buf.writeInt(c.screamerCooldownMax());
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
                int spawnAttempts = buf.readInt();
                float distanceWeight = buf.readFloat();
                float darkWeight = buf.readFloat();
                boolean screamerEnabled = buf.readBoolean();
                int screamerCooldownMin = buf.readInt();
                int screamerCooldownMax = buf.readInt();
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
                                minSpawnDist, spawnAttempts, distanceWeight, darkWeight, screamerEnabled, screamerCooldownMin, screamerCooldownMax
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
                                    // 权限校验：与 /zombietide 命令保持一致（OP 2）。
                                    // 单机/局域网仅房主放行（isSingleplayer 对同一进程的所有玩家都为 true，
                                    // 因此还要比对 UUID，避免局域网访客改全服配置）。
                                    net.minecraft.server.MinecraftServer server = serverPlayer.server;
                                    boolean isHost = server.isSingleplayer()
                                            && server.getSingleplayerProfile() != null
                                            && serverPlayer.getUUID().equals(server.getSingleplayerProfile().getId());
                                    if (!isHost && !serverPlayer.hasPermissions(2)) {
                                        EnikZombietide.LOGGER.warn("拒绝 {} 的配置修改请求：权限不足（需要 OP 2 或单机房主）",
                                                serverPlayer.getScoreboardName());
                                        serverPlayer.sendSystemMessage(
                                                net.minecraft.network.chat.Component.translatable("zombietide.settings.no_permission")
                                                        .withStyle(net.minecraft.ChatFormatting.RED));
                                        return;
                                    }
                                    // 【v0.1.59 修复 #96】配置是「世界级」的，但所有读取方
                                    // （HordeTimer / ZombietideServerEvents / 命令）都固定用主世界。
                                    // 之前这里用 serverLevel()，在下界/末地改设置会写到该维度的
                                    // SavedData，主世界玩法参数不变；客户端却回显新值（点了没效果）。
                                    ServerLevel overworld = serverPlayer.server.getLevel(
                                            net.minecraft.world.level.Level.OVERWORLD);
                                    ServerLevel level = overworld != null ? overworld : serverPlayer.serverLevel();
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
                // 【v0.2.137】服务端 -> 客户端：当前玩家权限
                .playToClient(PermissionPayload.TYPE, PermissionPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ClientPermission.set(payload.level(), payload.host());
                            });
                        })
                // 服务端 -> 客户端：玩家模组天数同步
                .playToClient(PlayerModDayPayload.TYPE, PlayerModDayPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onModDayReceived(payload.playerUuid(), payload.modDay(), payload.hordeDay(), payload.waveActive(), payload.bloodDebt(), payload.activePlayerHordes(), payload.activeVillagerHordes(), payload.aliveHordeZombies(), payload.hordeProgress(), payload.hordeDayToday(), payload.ownAliveHordeZombies());
                            });
                        })
                // 服务端 -> 客户端：村民模组天数同步
                .playToClient(VillagerModDayPayload.TYPE, VillagerModDayPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onVillagerModDayReceived(payload.villagerUuid(), payload.registryKey(),
                                        payload.modDay(), payload.x(), payload.y(), payload.z(),
                                        payload.waveActive(), payload.hordeDay(), payload.hordeProgress(),
                                        payload.hordeDayToday());
                            });
                        })
                // 服务端 -> 客户端：mod info 常驻显示开关
                .playToClient(InfoOverlayPayload.TYPE, InfoOverlayPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideHud.onInfoOverlayReceived(payload.enabled());
                            });
                        })
                // 服务端 -> 客户端：尸潮触发瞬间的一次性视觉冲击
                .playToClient(HordeFlashPayload.TYPE, HordeFlashPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideAtmosphere.onHordeFlash();
                            });
                        })
                // 服务端 -> 客户端：尸潮预兆强度
                .playToClient(OmenPayload.TYPE, OmenPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideAtmosphere.onOmenReceived(payload.strength());
                            });
                        })
                // 服务端 -> 客户端：尸潮威胁强度
                .playToClient(ThreatPayload.TYPE, ThreatPayload.STREAM_CODEC,
                        (payload, context) -> {
                            context.enqueueWork(() -> {
                                ZombietideAtmosphere.onThreatReceived(payload.strength());
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
    /**
     * 【v0.2.137】给单个玩家下发权限（登录时调用；与配置广播分开）。
     * <p>判定口径必须与 ConfigToServerPayload 的校验一致：
     * 单机房主（UUID 匹配 singleplayerProfile）或 OP 2+。
     */
    public static void sendPermission(ServerPlayer player) {
        sendPermissionIfChanged(player, true);
    }

    /**
     * 【v0.2.137】op/deop 后实时刷新：每 20 tick 比对一次，变化才发。
     */
    public static void refreshPermissions() {
        if (ServerLifecycleHooks.getCurrentServer() == null) return;
        for (ServerPlayer player : ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayers()) {
            sendPermissionIfChanged(player, false);
        }
    }

    private static void sendPermissionIfChanged(ServerPlayer player, boolean force) {
        net.minecraft.server.MinecraftServer server = player.server;
        boolean isHost = server.isSingleplayer()
                && server.getSingleplayerProfile() != null
                && player.getUUID().equals(server.getSingleplayerProfile().getId());
        // ServerPlayer 只暴露 hasPermissions(int)，从高到低反推等级
        int level = 0;
        for (int candidate = 4; candidate >= 1; candidate--) {
            if (player.hasPermissions(candidate)) {
                level = candidate;
                break;
            }
        }
        String key = level + ":" + isHost;
        if (!force && key.equals(LAST_PERMISSION_SENT.get(player.getUUID()))) return;
        LAST_PERMISSION_SENT.put(player.getUUID(), key);
        PacketDistributor.sendToPlayer(player, new PermissionPayload(level, isHost));
    }

    /** 玩家登出时清缓存 */
    public static void clearPermissionCache(java.util.UUID playerId) {
        LAST_PERMISSION_SENT.remove(playerId);
    }

    public static void broadcastConfig(ZombietideConfig config) {
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            PacketDistributor.sendToAllPlayers(new ConfigToClientPayload(config));
        }
    }

    /** 客户端 -> 服务端：发送配置修改（设置界面即时生效）。
     * 客户端没有 ServerLifecycleHooks（无服务端实例），故不做 null 检查，
     * 始终通过 PacketDistributor.sendToServer() 将配置发给服务端处理。 */
    public static void sendConfigToServer(ZombietideConfig config) {
        PacketDistributor.sendToServer(new ConfigToServerPayload(config));
    }

    /** 发送玩家模组天数给特定玩家 */
    public static void sendModDay(ServerPlayer player, int modDay, boolean hordeDay, boolean waveActive, int bloodDebt,
                                  int activePlayerHordes, int activeVillagerHordes, int aliveHordeZombies,
                                  float hordeProgress, boolean hordeDayToday, int ownAliveHordeZombies) {
        PacketDistributor.sendToPlayer(player, new PlayerModDayPayload(player.getUUID(), modDay, hordeDay,
                waveActive, bloodDebt, activePlayerHordes, activeVillagerHordes, aliveHordeZombies,
                hordeProgress, hordeDayToday, ownAliveHordeZombies));
    }

    /** 发送村民模组天数给特定玩家（用于 HUD 显示附近村民） */
    public static void sendVillagerModDay(ServerPlayer player, UUID villagerUuid, String registryKey, int modDay, double x, double y, double z, boolean waveActive, boolean hordeDay, float hordeProgress, boolean hordeDayToday) {
        PacketDistributor.sendToPlayer(player, new VillagerModDayPayload(villagerUuid, registryKey, modDay, x, y, z, waveActive, hordeDay, hordeProgress, hordeDayToday));
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
            if (entity == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (entity instanceof net.minecraft.world.entity.npc.Villager villager) {
                villagers.add(villager);
            }
        }
        if (villagers.isEmpty()) {
            PacketDistributor.sendToAllPlayers(new VillagerBroadcastEndPayload());
            return;
        }

        // 【v0.2.91 性能改造】先按村民算一次（O(V)），再逐玩家只做距离判断+发包，
        // 避免原来「玩家 × 村民」重复查调度表 / 重算 hordeDay / 重算进度 / 重复取职业键。
        var config = com.enik.zombietide.config.ConfigManager.getConfigData(overworld).getConfig();
        long dayTime = overworld.getDayTime();
        java.util.List<VillagerSnapshot> snapshots = new java.util.ArrayList<>(villagers.size());
        for (net.minecraft.world.entity.npc.Villager villager : villagers) {
            var sched = schedule.get(villager.getUUID());
            // 当天触发过就显示红色：常规触发由 isHordeDay 给出，
            // 手动触发（可在任意时刻）靠 waveActive 补充，与玩家 HUD 口径一致。
            boolean villagerHordeDay = com.enik.zombietide.horde.HordeTimer.isHordeDay(
                        villager.getUUID(), sched.modDay(), sched.lastTriggerDay(),
                        config, true, dayTime) || sched.waveActive();
            float progress = com.enik.zombietide.horde.HordeTimer.hordeProgress(
                        villager.getUUID(), sched.modDay(), sched.lastTriggerDay(), config, true, dayTime);
            // 【v0.2.94】hordeDayToday = 5 参（不看时刻）：今天是否为该村民的尸潮日，整天有效。
            // 供 DAY X 二态模式使用——「尸潮日红」必须是整天，而不是只在黄昏窗口。
            boolean hordeDayToday = com.enik.zombietide.horde.HordeTimer.isHordeDay(
                        villager.getUUID(), sched.modDay(), sched.lastTriggerDay(), config, true);
            snapshots.add(new VillagerSnapshot(villager.getUUID(), extractVillagerRegistryKey(villager),
                    sched.modDay(), villager.getX(), villager.getY(), villager.getZ(),
                    sched.waveActive(), villagerHordeDay, progress, hordeDayToday, villager));
        }

        // 对每个玩家，仅发送距离 ≤128 格的村民快照
        for (ServerPlayer player : overworld.getPlayers(p -> true)) {
            for (VillagerSnapshot snap : snapshots) {
                if (player.distanceToSqr(snap.entity()) <= 128.0 * 128.0) {
                    sendVillagerModDay(player, snap.uuid(), snap.registryKey(), snap.modDay(),
                            snap.x(), snap.y(), snap.z(), snap.waveActive(), snap.hordeDay(),
                            snap.progress(), snap.hordeDayToday());
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

    /** 尸潮触发瞬间的一次性视觉冲击（暗红闪烁），只发给指定玩家 */
    public static void sendHordeFlash(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, HordeFlashPayload.INSTANCE);
    }

    /** 发送尸潮预兆强度给指定玩家（0=无） */
    public static void sendOmen(ServerPlayer player, float strength) {
        PacketDistributor.sendToPlayer(player, new OmenPayload(strength));
    }

    /** 发送尸潮威胁强度给指定玩家（0=未被锁定，1=正被尸潮僵尸锁定） */
    public static void sendThreat(ServerPlayer player, float strength) {
        PacketDistributor.sendToPlayer(player, new ThreatPayload(strength));
    }

    /** 将村民职业 registry key 提取，返回标准格式 "namespace:path"，保证不泄露 Holder 包装格式到 UI */
    public static String extractVillagerRegistryKey(net.minecraft.world.entity.npc.Villager villager) {
        var profession = villager.getVillagerData().getProfession();
        // VillagerProfession 是记录类，toString() 仅返回职业名（如 "librarian"）而不含命名空间。
        // 使用 BuiltInRegistries.VILLAGER_PROFESSION.getKey() 获取完整 registry key（如 "minecraft:librarian"），最稳健可靠
        // Registry#getKey(T) 标注 @Nullable：必须先判空再 toString()，
        // 否则未注册职业会先 NPE，下面的兜底分支永远执行不到。
        net.minecraft.resources.ResourceLocation key =
                net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
        if (key != null) {
            String keyStr = key.toString();
            if (!keyStr.isEmpty()) {
                return keyStr;
            }
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