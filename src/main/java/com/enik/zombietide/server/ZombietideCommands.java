package com.enik.zombietide.server;

import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.attachments.PlayerHordeData;
import com.enik.zombietide.attachments.VillagerHordeData;
import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.horde.HordeSpawner;
import com.enik.zombietide.horde.HordeWarning;
import com.enik.zombietide.network.ModMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.util.List;
import java.util.UUID;
import java.util.Collection;

/**
 * 管理命令 /zombietide（需要 op 权限 level 2）。
 * <p>
 * 扁平结构，无 player/villager 子命令，目标选择器自动识别类型。
 * <p>
 * - /zombietide status [<targets>]：查看模组天数、血债、波次、触发日
 * - /zombietide trigger <targets>：**手动触发（测试/管理用）**：玩家→玩家尸潮，村民→村民尸潮；
 *   绕过启用开关与黄昏窗口，玩家保留血债（不消耗、不清零），并占用当日触发日（当日黄昏不再自动触发）
 * - /zombietide reset <players>：**只重置玩家血债**（仅玩家；modDay/波次/触发日/进行中均保留）；清除尸潮请用 debug clear
 * - /zombietide debug info：显示完整调试信息
 * - /zombietide debug clear：**清除**所有活跃尸潮与尸潮僵尸（唯一的清除命令）
 * - /zombietide debug glow <targets>：给目标实体发光 15 秒（自动过滤非生物实体）
 * <p>
 * 目标选择器支持：玩家选择器(@a/@p/@r/@s)、实体选择器(@e[type=villager])、UUID字符串
 * 由 ZombietideServerEvents.onRegisterCommands（RegisterCommandsEvent）调用 register()。
 */
public class ZombietideCommands {

    /** 注册 /zombietide 命令树 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("zombietide")
                // 需要 op 权限（level 2）
                .requires(src -> src.hasPermission(2))
                // status
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource()))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(ctx -> status(ctx.getSource(), EntityArgument.getEntities(ctx, "targets")))))
                // trigger（无参：玩家执行=对自己；控制台执行抛 NEED_TARGET，必须显式指定目标）
                .then(Commands.literal("trigger")
                        .executes(ctx -> trigger(ctx.getSource(), defaultTargets(ctx.getSource())))
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(ctx -> trigger(ctx.getSource(), EntityArgument.getEntities(ctx, "targets")))))
                // reset（仅玩家：村民没有血债；无参语义同 trigger）
                .then(Commands.literal("reset")
                        .executes(ctx -> reset(ctx.getSource(), defaultPlayers(ctx.getSource())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> reset(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))))
                // debug
                .then(Commands.literal("debug")
                        .then(Commands.literal("info")
                                .executes(ctx -> debugInfo(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> debugClearHordes(ctx.getSource())))
                        .then(Commands.literal("glow")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> debugGlow(ctx.getSource(), EntityArgument.getEntities(ctx, "targets"))))))
                // overlay：右上角 mod 信息常驻面板，执行一次开启、再执行一次关闭（纯客户端渲染，仅玩家）
                .then(Commands.literal("overlay")
                        .executes(ctx -> overlayToggle(ctx.getSource())))
        );
    }

    /** 模组配置与尸潮数据都挂在主世界 Level Data；命令统一以主世界为准，避免在其它维度读写到独立配置 */
    private static ServerLevel overworld(CommandSourceStack source) {
        ServerLevel ow = source.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        return ow != null ? ow : source.getLevel();
    }

    /**
     * 服务端侧记录每个玩家的面板开关。
     * <p>客户端的 {@code ZombietideHud.infoOverlayEnabled} 是静态字段，服务端无法查询，
     * 因此无参 toggle 只能靠服务端自己的记录判断"当前是开还是关"。
     * 玩家登录时清空（客户端刚启动必然是关），保证两边一致。
     */
    private static final java.util.Map<UUID, Boolean> OVERLAY_STATE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 玩家登录时重置面板开关记录（客户端 JVM 重启后 infoOverlayEnabled 必为 false） */
    public static void resetOverlayState(UUID playerUuid) {
        OVERLAY_STATE.remove(playerUuid);
    }

    /** 需要玩家执行（控制台/命令方块没有客户端渲染环境） */
    private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NEED_PLAYER =
            new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("此命令只能由玩家执行：mod 信息面板是客户端渲染，控制台没有客户端"));

    /**
     * 无参命令缺少目标时的报错。
     * <p>控制台/命令方块没有玩家实体，<b>不能</b>拿「所有在线玩家」当默认值——
     * {@code trigger} 会给所有人刷尸潮、{@code reset} 会清掉所有人血债，属于危险副作用，
     * 必须由执行者显式指定目标。只有玩家执行时才默认作用于自己。
     */
    private static final com.mojang.brigadier.exceptions.SimpleCommandExceptionType NEED_TARGET =
            new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                    Component.literal("此命令需要指定目标（控制台没有玩家上下文）。"
                            + "请用 /zombietide <子命令> <目标>，例如 /zombietide trigger @a"));

    /**
     * 无参命令的默认目标：<b>仅玩家执行时</b>返回自己。
     * 控制台执行直接抛异常，避免误操作。
     */
    private static java.util.Collection<? extends Entity> defaultTargets(CommandSourceStack source)
            throws CommandSyntaxException {
        if (source.getEntity() instanceof ServerPlayer sp) {
            return java.util.List.of(sp);
        }
        throw NEED_TARGET.create();
    }

    /** 同 {@link #defaultTargets}，但只取玩家（reset 仅对玩家生效） */
    private static java.util.Collection<? extends ServerPlayer> defaultPlayers(CommandSourceStack source)
            throws CommandSyntaxException {
        if (source.getEntity() instanceof ServerPlayer sp) {
            return java.util.List.of(sp);
        }
        throw NEED_TARGET.create();
    }

    // ========== status ==========

    /** 查看指定实体的模组天数、血债、波次状态（自动识别玩家/村民） */
    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        if (source.getEntity() instanceof ServerPlayer sp) {
            return status(source, List.of(sp));
        }
        // 控制台：列出所有在线玩家
        var players = source.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7(无在线玩家；可用 /zombietide status <目标> 指定实体)"), false);
            return 0;
        }
        return status(source, players);
    }

    private static int status(CommandSourceStack source, Collection<? extends Entity> targets) throws CommandSyntaxException {
        boolean hasOutput = false;
        for (Entity entity : targets) {
            if (entity instanceof ServerPlayer player) {
                PlayerHordeData data = ModAttachments.getPlayerHordeData(player);
                source.sendSuccess(() -> Component.literal("===== 尸潮状态: " + player.getScoreboardName() + " (玩家) =====").withStyle(ChatFormatting.GOLD), false);
                source.sendSuccess(() -> Component.literal("玩家 " + player.getScoreboardName() + " (UUID=")
                        .append(clickableUUID(player.getUUID()))
                        .append(Component.literal(")：模组天数 DAY " + data.modDay()
                        + "，血债 " + com.enik.zombietide.config.PlayerDebtData.get(source.getServer().overworld()).getDebt(player.getUUID())
                        + "，波次 " + data.waveCount()
                        + "，上次触发日 DAY " + data.lastTriggerDay()
                        + (data.waveActive() ? "（尸潮进行中）" : "（无尸潮）"))), false);
                hasOutput = true;
            } else if (entity instanceof Villager villager) {
                VillagerHordeData data = ModAttachments.getVillagerHordeData(villager);
                source.sendSuccess(() -> Component.literal("===== 尸潮状态: " + villager.getName().getString() + " (村民) =====").withStyle(ChatFormatting.GOLD), false);
                source.sendSuccess(() -> Component.literal("村民 " + villager.getName().getString() + " (UUID=")
                        .append(clickableUUID(villager.getUUID()))
                        .append(Component.literal(")：模组天数 DAY " + data.modDay()
                        + "，波次 " + data.waveCount()
                        + "，上次触发日 DAY " + data.lastTriggerDay()
                        + (data.waveActive() ? "（尸潮进行中）" : "（无尸潮）"))), false);
                hasOutput = true;
            }
        }
        if (!hasOutput) {
            source.sendSuccess(() -> Component.literal("目标中无玩家或村民").withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ========== trigger ==========

    /** 手动触发（测试/管理用）：玩家→玩家尸潮，村民→村民尸潮。玩家分支【保留血债】（不消耗、不清零）。 */
    private static int trigger(CommandSourceStack source, Collection<? extends Entity> targets) throws CommandSyntaxException {
        ServerLevel level = overworld(source);
        ZombietideConfig config = ConfigManager.getConfigData(level).getConfig();

        int successCount = 0;
        for (Entity entity : targets) {
            if (entity instanceof ServerPlayer player) {
                // 玩家尸潮：与常规触发保持一致，仅主世界 + 非和平
                if (player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD
                        || player.serverLevel().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
                    source.sendSuccess(() -> Component.literal("跳过玩家 " + player.getScoreboardName()
                            + "：仅主世界且非和平模式可触发").withStyle(ChatFormatting.GRAY), false);
                    continue;
                }
                // 一波未刷完不允许再开一波：否则 startHorde 会覆盖 activeHordes，
                // 前一波剩余被静默丢弃，且触发消息/标题/音效会刷屏。
                int inProgress = HordeSpawner.getActiveRemaining(player.getUUID());
                if (inProgress > 0) {
                    source.sendFailure(Component.literal("玩家 " + player.getScoreboardName() + " 的尸潮正在生成中（剩余 "
                            + inProgress + " 只未刷出），已跳过。如需强制重开，请先执行 /zombietide debug clear"));
                    continue;
                }
                PlayerHordeData data = ModAttachments.getPlayerHordeData(player);
                int hordeSize = config.playerCount();
                if (config.playerCountRandom()) {
                    hordeSize = HordeSpawner.rollHordeSize(config.playerCountMin(), config.playerCountMax());
                }
                // 【保留血债】调试触发不消耗血债：不把血债计入本波，也不清零，
                // 血债原样保留给下一次常规波次（常规触发才会 hordeSize + bloodDebt 并清零）。
                // 若这里把血债计入本波又保留，结算时 oldDebt + survivors 会重复计债。
                int totalHorde = hordeSize;

                player.setData(ModAttachments.PLAYER_HORDE.get(),
                        new PlayerHordeData(data.modDay(), data.modDay(), data.waveCount() + 1, true));

                HordeWarning.triggerPlayerHorde(player.serverLevel(), player, config);
                HordeSpawner.startHorde(player.serverLevel(), player.getUUID(), player, totalHorde, config, false, config.playerRadius());

                source.sendSuccess(() -> Component.literal("【手动触发】已为玩家 " + player.getScoreboardName() + " (UUID=")
                        .append(clickableUUID(player.getUUID()))
                        .append(Component.literal(") 触发玩家尸潮（数量 " + totalHorde + "）"))
                        .withStyle(ChatFormatting.RED), false);
                successCount++;

            } else if (entity instanceof Villager villager) {
                int inProgress = HordeSpawner.getActiveRemaining(villager.getUUID());
                if (inProgress > 0) {
                    source.sendFailure(Component.literal("村民 " + villager.getName().getString() + " 的尸潮正在生成中（剩余 "
                            + inProgress + " 只未刷出），已跳过。如需强制重开，请先执行 /zombietide debug clear"));
                    continue;
                }
                // 村民尸潮
                if (!villager.isAlive()) continue;
                if (!(villager.level() instanceof ServerLevel villagerLevel)) continue;
                if (villagerLevel.dimension() != net.minecraft.world.level.Level.OVERWORLD) continue;
                if (villagerLevel.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

                VillagerHordeData data = ModAttachments.getVillagerHordeData(villager);
                int actualHordeSize = config.villagerCount();
                if (config.villagerCountRandom()) {
                    actualHordeSize = HordeSpawner.rollHordeSize(config.villagerCountMin(), config.villagerCountMax());
                }
                final int finalHordeSize = actualHordeSize;

                villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                        new VillagerHordeData(data.modDay(), data.modDay(), data.waveCount() + 1, true));
                // 同步权威调度表：HUD / 对账 / 区块重载都以调度表为准。
                // 不写调度表的话，调试触发的村民尸潮会被 reconcileStaleWaves 当成孤儿在 2 秒内清掉。
                var schedule = villagerLevel.getDataStorage().computeIfAbsent(
                        com.enik.zombietide.config.VillagerScheduleData.FACTORY,
                        com.enik.zombietide.config.VillagerScheduleData.ID);
                schedule.put(villager.getUUID(), new com.enik.zombietide.config.VillagerScheduleData.ScheduleEntry(
                        data.modDay(), data.modDay(), data.waveCount() + 1, true));

                HordeWarning.triggerVillagerHorde(villagerLevel, villager, config);
                HordeSpawner.startHorde(villagerLevel, villager.getUUID(), villager, actualHordeSize, config, true, config.villagerRadius());

                source.sendSuccess(() -> Component.literal("【手动触发】已为村民 " + villager.getName().getString() + " (UUID=")
                        .append(clickableUUID(villager.getUUID()))
                        .append(Component.literal(") 触发村民尸潮（数量 " + finalHordeSize + "）"))
                        .withStyle(ChatFormatting.RED), false);
                successCount++;
            }
        }
        final int finalSuccessCount = successCount;
        if (finalSuccessCount > 0) {
            source.sendSuccess(() -> Component.literal("§a【手动触发】成功触发 " + finalSuccessCount + " 个尸潮").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("目标中无有效玩家或村民（村民需在主世界且非和平）").withStyle(ChatFormatting.GRAY), false);
        }
        return successCount;
    }

    // ========== reset ==========

    /**
     * 只重置玩家血债（bloodDebt = 0）。
     * <p>
     * 仅接受玩家目标（村民没有血债字段）。modDay / 波次 / 上次触发日 / 进行中状态**全部原样保留**；
     * 不取消波次、不清僵尸。需要清除尸潮与僵尸请使用 {@code /zombietide debug clear}。
     */
    private static int reset(CommandSourceStack source, Collection<? extends ServerPlayer> targets) throws CommandSyntaxException {
        int successCount = 0;
        for (ServerPlayer player : targets) {
            // 只把血债归零，其它字段（波次/触发日/进行中状态）原样保留
            com.enik.zombietide.config.PlayerDebtData.get(source.getServer().overworld()).setDebt(player.getUUID(), 0);
            source.sendSuccess(() -> Component.literal("已重置玩家 " + player.getScoreboardName() + " (UUID=")
                    .append(clickableUUID(player.getUUID()))
                    .append(Component.literal(") 的血债为 0（波次/触发日/进行中状态保留；清除尸潮请用 /zombietide debug clear）"))
                    .withStyle(ChatFormatting.AQUA), false);
            successCount++;
        }
        final int finalSuccessCount = successCount;
        source.sendSuccess(() -> Component.literal("§a成功重置 " + finalSuccessCount + " 个玩家的血债").withStyle(ChatFormatting.GREEN), false);
        return successCount;
    }

    // ========== 工具方法 ==========

    /** 创建可点击填入聊天框的 UUID 组件（点击在聊天输入框自动填入，悬停显示“点击填入”） */
    private static Component clickableUUID(UUID uuid) {
        // 必须用 withStyle(UnaryOperator) 叠加样式；若再调 withStyle(Style.EMPTY...) 会整体替换样式，
        // 把颜色清掉（点击事件虽在，但看起来像普通文本，容易被误认为“不可点击”）。
        return Component.literal(uuid.toString())
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, uuid.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击填入 UUID"))));
    }

    // ========== overlay（右上角 mod 信息面板） ==========

    /**
     * 开关右上角 mod 信息常驻面板（内容同 debug info，逐帧实时刷新）。
     *
     * <p>该面板是<b>纯客户端渲染</b>：服务端通过 {@code InfoOverlayPayload} 把开关同步给客户端，
     * 客户端存在静态字段 {@code ZombietideHud.infoOverlayEnabled}，因此<b>客户端重启后状态丢失</b>，
     * 需要重新执行本命令。控制台/命令方块没有客户端渲染环境，直接拒绝。
     *
     * <p>面板还受 HUD 总开关 {@code hudEnabled} 约束（{@code ZombietideHud.render} 开头就 return），
     * 关闭时给出提示但不阻止命令执行。
     *
     * <p><b>本方法为无参入口</b>：按服务端记录的当前状态取反（执行一次开、再执行一次关）。
     */
    private static int overlayToggle(CommandSourceStack source) throws CommandSyntaxException {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            throw NEED_PLAYER.create();
        }
        boolean next = !OVERLAY_STATE.getOrDefault(player.getUUID(), false);
        return overlay(source, next);
    }

    private static int overlay(CommandSourceStack source, boolean enable) throws CommandSyntaxException {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            throw NEED_PLAYER.create();
        }
        OVERLAY_STATE.put(player.getUUID(), enable);
        // 注意：不能读服务端 HudConfig.hudEnabled 判断——面板渲染门控在客户端，
        // 用的是本机全局配置 ClientHudConfig（config/enik_zombietide-hud.properties），
        // 服务端读不到，也从未有界面入口修改服务端那份。这里只给中性提示。
        ModMessages.sendInfoOverlay(player, enable);
        if (enable) {
            source.sendSuccess(() -> Component.literal("§7若面板未显示，请检查本机 设置 → HUD 页签的「HUD 总开关」。")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        source.sendSuccess(() -> Component.literal("§a模组信息面板已" + (enable ? "§l开启" : "§l关闭")
                + "§r§7（再次执行 /zombietide overlay 切换；客户端重启后需重新执行）"), false);
        com.enik.zombietide.EnikZombietide.LOGGER.info("玩家 {} 通过命令{}模组信息面板（右上角）", player.getScoreboardName(), enable ? "开启" : "关闭");
        return 1;
    }

    // ========== 调试命令 ==========

    /** 显示模组完整调试信息（分区、彩色、全汉化，UUID 可点击填入） */
    private static int debugInfo(CommandSourceStack source) {
        ServerLevel level = overworld(source);
        ZombietideConfig config = ConfigManager.getConfigData(level).getConfig();

        // 标题
        source.sendSuccess(() -> Component.literal("§6=== 尸潮调试信息 ===").withStyle(ChatFormatting.GOLD), false);

        // 配置摘要
        source.sendSuccess(() -> Component.literal("§b--- 配置摘要 ---"), false);
        source.sendSuccess(() -> Component.literal("§e玩家尸潮: §f" + (config.playerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e村民尸潮: §f" + (config.villagerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e全局上限: §f" + config.globalCap()
                + " §e血债模式: §f" + config.debtMode()), false);

        // 玩家尸潮区：玩家执行时显示自己，控制台执行时列出所有在线玩家
        source.sendSuccess(() -> Component.literal("§b--- 玩家尸潮 ---"), false);
        java.util.List<ServerPlayer> debugPlayers;
        if (source.getEntity() instanceof ServerPlayer sp) {
            debugPlayers = java.util.List.of(sp);
        } else {
            debugPlayers = new java.util.ArrayList<>(source.getServer().getPlayerList().getPlayers());
        }
        if (debugPlayers.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7(无在线玩家)"), false);
        }
        var debtData = com.enik.zombietide.config.PlayerDebtData.get(source.getServer().overworld());
        for (ServerPlayer player : debugPlayers) {
            PlayerHordeData pData = ModAttachments.getPlayerHordeData(player);
            source.sendSuccess(() -> Component.literal("§e玩家名: §f" + player.getScoreboardName()
                    + " §eUUID: ")
                    .append(clickableUUID(player.getUUID()))
                    .append(Component.literal(" §eModDay: §f" + pData.modDay() + "天"
                    + " §e间隔: §f" + (config.playerIntervalRandom()
                            ? config.playerIntervalMin() + "~" + config.playerIntervalMax() + "天(随机)"
                            : config.playerIntervalDays() + "天")
                    + " §e血债: §f" + debtData.getDebt(player.getUUID())
                    + " §e进行中: §f" + (pData.waveActive() ? "§a是" : "§c否"))), false);
        }

        // 村民尸潮区 - 扫描全世界村民，不限距离
        source.sendSuccess(() -> Component.literal("§b--- 村民尸潮 ---"), false);
        int villagerCount = 0;
        for (var entity : level.getEntities().getAll()) {
            if (entity instanceof Villager v) {
                villagerCount++;
                VillagerHordeData vData = ModAttachments.getVillagerHordeData(v);
                int interval = config.villagerIntervalDays();
                String prof = ModMessages.localizeProfession(ModMessages.extractVillagerRegistryKey(v));
                source.sendSuccess(() -> Component.literal("§e职业名: §f" + prof
                        + " §eUUID: ")
                        .append(clickableUUID(v.getUUID()))
                        .append(Component.literal(" §e坐标: §f" + (int)v.getX() + "," + (int)v.getY() + "," + (int)v.getZ()
                        + " §eModDay: §f" + vData.modDay() + "天"
                        + " §e间隔: §f" + (config.villagerIntervalRandom()
                        ? config.villagerIntervalMin() + "~" + config.villagerIntervalMax() + "天(随机)"
                        : interval + "天")
                        + " §e进行中: §f" + (vData.waveActive() ? "§a是" : "§c否"))), false);
            }
        }
        if (villagerCount == 0) {
            source.sendSuccess(() -> Component.literal("§7(当前世界无村民)"), false);
        }

        // 全局统计
        int activePlayerCount = HordeSpawner.getActivePlayerHordeCount(level);
        int activeVillagerCount = HordeSpawner.getActiveVillagerHordeCount(level);
        // 与 HUD 面板共用同一套统计，保证两处数字一致
        int hordeZombieCount = HordeSpawner.countAliveHordeZombies(level);
        final int finalHordeZombieCount = hordeZombieCount;
        final int finalActivePlayerCount = activePlayerCount;
        final int finalActiveVillagerCount = activeVillagerCount;
        source.sendSuccess(() -> Component.literal("§b--- 全局统计 ---"), false);
        source.sendSuccess(() -> Component.literal("§e存活尸潮僵尸: §f" + finalHordeZombieCount
                + " §e活跃玩家尸潮: §f" + finalActivePlayerCount
                + " §e活跃村民尸潮: §f" + finalActiveVillagerCount
                + " §e全局僵尸上限: §f" + config.globalCap()), false);

        return 1;
    }

    /** 清理所有活跃尸潮与存活尸潮僵尸 */
    private static int debugClearHordes(CommandSourceStack source) throws CommandSyntaxException {
        ServerLevel level = overworld(source);

        // 清理 activeHordes 并重置所有目标 waveActive
        HordeSpawner.clearActiveHordes(level);

        // 清理存活尸潮僵尸
        int cleared = 0;
        for (Entity entity : level.getEntities().getAll()) {
            // Zombie 已覆盖 ZombieVillager/Husk/Drowned；Stray 不是 Zombie 子类，需单独计入
            if (entity instanceof net.minecraft.world.entity.monster.Zombie
                    || entity instanceof net.minecraft.world.entity.monster.Stray) {
                if (entity.getPersistentData().hasUUID("zombietide_horde_target")) {
                    entity.discard();
                    cleared++;
                }
            }
        }
        final int finalCleared = cleared;
        source.sendSuccess(() -> Component.literal("已清理所有活跃尸潮与 " + finalCleared + " 只存活尸潮僵尸").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** 给实体添加 15 秒发光效果（用于调试定位，支持任意实体） */
    private static int debugGlow(CommandSourceStack source, Collection<? extends Entity> targets) throws CommandSyntaxException {
        int successCount = 0;
        for (Entity entity : targets) {
            if (!entity.isAlive()) continue;
            if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) continue;
            living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.GLOWING,
                    15 * 20, 1, false, false));

            source.sendSuccess(() -> Component.literal("已给 " + entity.getName().getString() + " (UUID=")
                    .append(clickableUUID(entity.getUUID()))
                    .append(Component.literal("，类型=" + entity.getType().getDescription().getString() + ") 添加 15 秒发光效果"))
                    .withStyle(ChatFormatting.AQUA), false);
            successCount++;
        }
        final int finalSuccessCount = successCount;
        if (finalSuccessCount > 0) {
            source.sendSuccess(() -> Component.literal("§a成功给 " + finalSuccessCount + " 个实体添加发光效果").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("目标中无有效实体").withStyle(ChatFormatting.GRAY), false);
        }
        return successCount;
    }
}