package com.enik.zombietide.server;

import com.enik.zombietide.attachments.ModAttachments;
import com.enik.zombietide.attachments.PlayerHordeData;
import com.enik.zombietide.attachments.VillagerHordeData;
import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.horde.HordeSpawner;
import com.enik.zombietide.horde.HordeWarning;
import com.enik.zombietide.horde.HordeTimer;
import com.enik.zombietide.network.ModMessages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collection;

/**
 * 管理命令 /zombietide（需要 op 权限 level 2）。
 * <p>
 * 扁平结构，无 player/villager 子命令，目标选择器自动识别类型。
 * <p>
 * - /zombietide status [<targets>]：查看模组天数、血债、波次、触发日
 * - /zombietide trigger <targets>：立即触发尸潮（玩家→玩家尸潮，村民→村民尸潮）
 * - /zombietide reset <targets>：仅重置血债、波次、触发日、进行中标记（modDay 保留，提示用 /time）
 * - /zombietide debug info：显示完整调试信息
 * - /zombietide debug spawn：脚下生成 1 只测试僵尸
 * - /zombietide debug clear：清理所有活跃尸潮与僵尸
 * - /zombietide debug glow <targets>：村民发光 15 秒（仅村民，自动过滤）
 * - /zombietide debug info_toggle：切换右上角 Info Overlay
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
                // trigger
                .then(Commands.literal("trigger")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(ctx -> trigger(ctx.getSource(), EntityArgument.getEntities(ctx, "targets")))))
                // reset
                .then(Commands.literal("reset")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(ctx -> reset(ctx.getSource(), EntityArgument.getEntities(ctx, "targets")))))
                // debug
                .then(Commands.literal("debug")
                        .then(Commands.literal("info")
                                .executes(ctx -> debugInfo(ctx.getSource())))
                        .then(Commands.literal("spawn")
                                .executes(ctx -> debugSpawnZombie(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> debugClearHordes(ctx.getSource())))
                        .then(Commands.literal("glow")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(ctx -> debugGlow(ctx.getSource(), EntityArgument.getEntities(ctx, "targets"))))))
        );
    }

    // ========== status ==========

    /** 查看指定实体的模组天数、血债、波次状态（自动识别玩家/村民） */
    private static int status(CommandSourceStack source) throws CommandSyntaxException {
        return status(source, List.of(source.getPlayerOrException()));
    }

    private static int status(CommandSourceStack source, Collection<? extends Entity> targets) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        boolean hasOutput = false;
        for (Entity entity : targets) {
            if (entity instanceof ServerPlayer player) {
                PlayerHordeData data = ModAttachments.getPlayerHordeData(player);
                source.sendSuccess(() -> Component.literal("===== 尸潮状态: " + player.getScoreboardName() + " (玩家) =====").withStyle(ChatFormatting.GOLD), false);
                source.sendSuccess(() -> Component.literal("玩家 " + player.getScoreboardName()
                        + " (UUID=" + player.getStringUUID() + ")"
                        + "：模组天数 DAY " + data.modDay()
                        + "，血债 " + data.bloodDebt()
                        + "，波次 " + data.waveCount()
                        + "，上次触发日 DAY " + data.lastTriggerDay()
                        + (data.waveActive() ? "（尸潮进行中）" : "（无尸潮）")), false);
                hasOutput = true;
            } else if (entity instanceof Villager villager) {
                VillagerHordeData data = ModAttachments.getVillagerHordeData(villager);
                source.sendSuccess(() -> Component.literal("===== 尸潮状态: " + villager.getName().getString() + " (村民) =====").withStyle(ChatFormatting.GOLD), false);
                source.sendSuccess(() -> Component.literal("村民 " + villager.getName().getString()
                        + " (UUID=" + villager.getStringUUID() + ")"
                        + "：模组天数 DAY " + data.modDay()
                        + "，波次 " + data.waveCount()
                        + "，上次触发日 DAY " + data.lastTriggerDay()
                        + (data.waveActive() ? "（尸潮进行中）" : "（无尸潮）")), false);
                hasOutput = true;
            }
        }
        if (!hasOutput) {
            source.sendSuccess(() -> Component.literal("目标中无玩家或村民").withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ========== trigger ==========

    /** 立即触发尸潮：玩家→玩家尸潮，村民→村民尸潮 */
    private static int trigger(CommandSourceStack source, Collection<? extends Entity> targets) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        ZombietideConfig config = ConfigManager.getConfigData(level).getConfig();

        int successCount = 0;
        for (Entity entity : targets) {
            if (entity instanceof ServerPlayer player) {
                // 玩家尸潮
                PlayerHordeData data = ModAttachments.getPlayerHordeData(player);
                int hordeSize = config.playerCount();
                if (config.playerCountRandom()) {
                    hordeSize = config.playerCountMin() + new Random().nextInt(config.playerCountMax() - config.playerCountMin() + 1);
                }
                int totalHorde = hordeSize + data.bloodDebt();

                player.setData(ModAttachments.PLAYER_HORDE.get(),
                        new PlayerHordeData(data.modDay(), data.modDay(), data.bloodDebt(), data.waveCount() + 1, true));

                HordeWarning.triggerPlayerHorde(level, player, config);
                HordeSpawner.startHorde(level, player.getUUID(), player, totalHorde, config, false, config.playerRadius());

                source.sendSuccess(() -> Component.literal("已为玩家 " + player.getScoreboardName()
                        + " (UUID=" + player.getStringUUID() + ") 触发玩家尸潮（数量 " + totalHorde + "）").withStyle(ChatFormatting.RED), false);
                successCount++;

            } else if (entity instanceof Villager villager) {
                // 村民尸潮
                if (!villager.isAlive()) continue;
                if (villager.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) continue;
                if (villager.level().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) continue;

                VillagerHordeData data = ModAttachments.getVillagerHordeData(villager);
                int actualHordeSize = config.villagerCount();
                if (config.villagerCountRandom()) {
                    actualHordeSize = config.villagerCountMin() + new Random().nextInt(config.villagerCountMax() - config.villagerCountMin() + 1);
                }

                villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                        new VillagerHordeData(data.modDay(), data.modDay(), data.waveCount() + 1, true));

                HordeWarning.triggerVillagerHorde(level, villager, config);
                HordeSpawner.startHorde(level, villager.getUUID(), villager, actualHordeSize, config, true, config.villagerRadius());

                final String villagerMsg = "已为村民 " + villager.getName().getString()
                        + " (UUID=" + villager.getStringUUID() + ") 触发村民尸潮（数量 " + actualHordeSize + "）";
                source.sendSuccess(() -> Component.literal(villagerMsg).withStyle(ChatFormatting.RED), false);
                successCount++;
            }
        }
        final int finalSuccessCount = successCount;
        if (finalSuccessCount > 0) {
            source.sendSuccess(() -> Component.literal("§a成功触发 " + finalSuccessCount + " 个尸潮").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("目标中无有效玩家或村民（村民需在主世界且非和平）").withStyle(ChatFormatting.GRAY), false);
        }
        return successCount;
    }

    // ========== reset ==========

    /** 重置血债、波次、触发日、进行中标记（modDay 保留，提示用 /time） */
    private static int reset(CommandSourceStack source, Collection<? extends Entity> targets) throws CommandSyntaxException {
        int successCount = 0;
        for (Entity entity : targets) {
            if (entity instanceof ServerPlayer player) {
                PlayerHordeData data = ModAttachments.getPlayerHordeData(player);
                player.setData(ModAttachments.PLAYER_HORDE.get(),
                        new PlayerHordeData(data.modDay(), 0, 0, 0, false));
                final String playerMsg = "已重置玩家 " + player.getScoreboardName()
                        + " (UUID=" + player.getStringUUID() + ") 的血债、波次、触发日（modDay 保留为 DAY " + data.modDay() + "，如需改 modDay 请用 /time）";
                source.sendSuccess(() -> Component.literal(playerMsg).withStyle(ChatFormatting.AQUA), false);
                successCount++;
            } else if (entity instanceof Villager villager) {
                VillagerHordeData data = ModAttachments.getVillagerHordeData(villager);
                villager.setData(ModAttachments.VILLAGER_HORDE.get(),
                        new VillagerHordeData(data.modDay(), 0, 0, false));
                final String villagerMsg = "已重置村民 " + villager.getName().getString()
                        + " (UUID=" + villager.getStringUUID() + ") 的波次、触发日（modDay 保留为 DAY " + data.modDay() + "，如需改 modDay 请用 /time）";
                source.sendSuccess(() -> Component.literal(villagerMsg).withStyle(ChatFormatting.AQUA), false);
                successCount++;
            }
        }
        final int finalSuccessCount = successCount;
        if (finalSuccessCount > 0) {
            source.sendSuccess(() -> Component.literal("§a成功重置 " + finalSuccessCount + " 个目标（modDay 未变，改天数请用 /time set <天数>000）").withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendSuccess(() -> Component.literal("目标中无玩家或村民").withStyle(ChatFormatting.GRAY), false);
        }
        return successCount;
    }

    // ========== 工具方法 ==========

    /** 创建可点击填入聊天框的 UUID 组件（点击在聊天输入框自动填入，悬停显示“点击填入”） */
    private static Component clickableUUID(UUID uuid) {
        return Component.literal(uuid.toString())
                .withStyle(ChatFormatting.AQUA)
                .withStyle(net.minecraft.network.chat.Style.EMPTY
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, uuid.toString()))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击填入 UUID"))));
    }

    /** 寻找离执行者最近且存活的村民（100 格范围内） */
    private static Villager findNearestVillager(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double r = 100.0;
        AABB box = new AABB(
                player.getX() - r, player.getY() - r, player.getZ() - r,
                player.getX() + r, player.getY() + r, player.getZ() + r);
        Villager nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Villager villager : level.getEntitiesOfClass(Villager.class, box)) {
            if (!villager.isAlive()) continue;
            double d = villager.distanceToSqr(player);
            if (d < bestDist) {
                bestDist = d;
                nearest = villager;
            }
        }
        return nearest;
    }

    // ========== 调试命令 ==========

    /** 显示模组完整调试信息（分区、彩色、全汉化，UUID 直接显示） */
    private static int debugInfo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        ZombietideConfig config = ConfigManager.getConfigData(level).getConfig();

        // 标题
        source.sendSuccess(() -> Component.literal("§6=== 尸潮调试信息 ===").withStyle(ChatFormatting.GOLD), false);

        // 配置摘要
        source.sendSuccess(() -> Component.literal("§b--- 配置摘要 ---"), false);
        source.sendSuccess(() -> Component.literal("§e玩家尸潮: §f" + (config.playerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e村民尸潮: §f" + (config.villagerHordeEnabled() ? "§a开启" : "§c关闭")
                + " §e全局上限: §f" + config.globalCap()
                + " §e血债模式: §f" + config.debtMode()), false);

        // 玩家尸潮区
        PlayerHordeData pData = ModAttachments.getPlayerHordeData(player);
        source.sendSuccess(() -> Component.literal("§b--- 玩家尸潮 ---"), false);
        source.sendSuccess(() -> Component.literal("§e玩家名: §f" + player.getScoreboardName()
                + " §eUUID: ")
                .append(clickableUUID(player.getUUID()))
                .append(Component.literal(" §eModDay: §f" + pData.modDay() + "天"
                + " §e间隔: §f" + config.playerIntervalDays() + "天"
                + " §e血债: §f" + pData.bloodDebt()
                + " §e进行中: §f" + (pData.waveActive() ? "§a是" : "§c否"))), false);

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
                        + " §e间隔: §f" + interval + "天"
                        + " §e进行中: §f" + (vData.waveActive() ? "§a是" : "§c否"))), false);
            }
        }
        if (villagerCount == 0) {
            source.sendSuccess(() -> Component.literal("§7(当前世界无村民)"), false);
        }

        // 全局统计
        int activePlayerCount = HordeSpawner.getActivePlayerHordeCount(level);
        int activeVillagerCount = HordeSpawner.getActiveVillagerHordeCount(level);
        int hordeZombieCount = 0;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.monster.Zombie z) {
                if (z.getPersistentData().hasUUID("zombietide_horde_target")) {
                    hordeZombieCount++;
                }
            }
        }
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

    /** 在玩家脚下直接生成一只调试僵尸（用于测试刷怪点） */
    private static int debugSpawnZombie(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        ZombietideConfig config = ConfigManager.getConfigData(level).getConfig();

        net.minecraft.world.entity.monster.Zombie zombie = new net.minecraft.world.entity.monster.Zombie(
                net.minecraft.world.entity.EntityType.ZOMBIE, level);
        net.minecraft.core.BlockPos pos = player.blockPosition().offset(0, 2, 3);
        zombie.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        zombie.getPersistentData().putUUID("zombietide_horde_target", player.getUUID());
        com.enik.zombietide.horde.HordeZombieBehavior.apply(zombie, player.getUUID(), config);
        level.addFreshEntity(zombie);

        source.sendSuccess(() -> Component.literal("已在玩家前方生成 1 只调试尸潮僵尸").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** 清理所有活跃尸潮与存活尸潮僵尸 */
    private static int debugClearHordes(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        // 清理 activeHordes 并重置所有目标 waveActive
        HordeSpawner.clearActiveHordes(level);

        // 清理存活尸潮僵尸
        int cleared = 0;
        for (Entity entity : level.getEntities().getAll()) {
            if (entity instanceof net.minecraft.world.entity.monster.Zombie z) {
                if (z.getPersistentData().hasUUID("zombietide_horde_target")) {
                    z.discard();
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

            final String entityMsg = "已给 " + entity.getName().getString()
                    + " (UUID=" + entity.getStringUUID() + "，类型=" + entity.getType().getDescription().getString() + ") 添加 15 秒发光效果";
            source.sendSuccess(() -> Component.literal(entityMsg).withStyle(ChatFormatting.AQUA), false);
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