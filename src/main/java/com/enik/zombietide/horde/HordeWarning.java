package com.enik.zombietide.horde;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.ZombietideConfig;
import com.enik.zombietide.sound.ModSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.Villager;

import java.util.Random;

/**
 * 警告系统：尸潮清晨提醒、触发瞬间警报（设计文档 7.1 / 7.2 / 7.3）。
 * <p>
 * - warnMorningHorde：玩家尸潮清晨提醒（尸潮日黎明后 0-1000）：聊天文案 + 定制清晨提醒音效，无标题；
 * - triggerPlayerHorde：玩家尸潮触发瞬间：大标题 + 定制触发音效 + 突袭旁白；
 * - triggerVillagerHorde：村民尸潮触发瞬间（同触发警报语义，突袭旁白同样适用）。
 * <p>
 * 标题/字幕通过原版 TitleCommand 同款数据包发送
 * （ClientboundSetTitleTextPacket / ClientboundSetSubtitleTextPacket / ClientboundSetTitlesAnimationPacket）。
 */
public class HordeWarning {

    /** 清晨提醒文案条数（对应语言文件 zombietide.morning.1 ~ .40） */
    private static final int MORNING_LINE_COUNT = 40;
    /** 突袭旁白文案条数（对应语言文件 zombietide.ambush.1 ~ .12） */
    private static final int AMBUSH_LINE_COUNT = 12;

    /** 大标题主标题（翻译键，见 lang/zh_cn.json、lang/en_us.json） */
    private static final Component TITLE_TEXT = Component.translatable("zombietide.warning.title");
    /** 大标题副标题 */
    private static final Component SUBTITLE_TEXT = Component.translatable("zombietide.warning.subtitle");

    private static final Random RANDOM = new Random();

    /**
     * 清晨尸潮提醒：常规路径在尸潮日黎明后（0-1000）调用；
     * missedDusk 补偿链在夜晚跨过黄昏窗口时也会调用（语义仍是「清晨提醒」）。
     * 发送聊天文案（随机抽 1 段）+ 定制清晨提醒音效（受 morningWarningSound 开关控制），无大标题。
     *
     * @param level  服务端世界
     * @param player 接收提醒的玩家
     * @param config 当前配置（warningEnabled 控制是否发送）
     * @param modDay 当前模组天数（用于文案判断）
     */
    public static void warnMorningHorde(ServerLevel level, ServerPlayer player, ZombietideConfig config, int modDay) {
        if (config == null || !config.warningEnabled()) return;

        // 从语言文件随机抽 1 段（方便翻译/修改）
        int index = RANDOM.nextInt(MORNING_LINE_COUNT) + 1;
        player.sendSystemMessage(Component.translatable("zombietide.morning." + index));

        // 定制音频：清晨提醒音效只发给收到提醒的玩家本人
        if (config.morningWarningSound()) {
            ModSounds.playToPlayer(player, ModSounds.MORNING_WARNING, SoundSource.HOSTILE, 0.8F, 1.0F);
        }

        EnikZombietide.LOGGER.info("清晨尸潮提醒已发送给玩家 {} (Day {})", player.getScoreboardName(), modDay);
    }

    /**
     * 玩家尸潮触发警报：尸潮触发瞬间调用（设计文档 7.2）。
     * 大标题「尸潮」+ 副标题 + 定制触发音效（尸潮触发.mp3，替换原版僵尸敲木门声）+ 突袭旁白（文案库随机抽 1 段）。
     *
     * @param level  服务端世界
     * @param player 被警报的玩家（尸潮触发目标）
     * @param config 当前配置（warning 开关控制各通道）
     */
    public static void triggerPlayerHorde(ServerLevel level, ServerPlayer player, ZombietideConfig config) {
        if (config == null || !config.warningEnabled()) return;

        // 大标题「尸潮」+ 副标题（语言键驱动；标题是功能名，不是氛围文案）
        if (config.warningTitle()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(TITLE_TEXT));
            player.connection.send(new ClientboundSetSubtitleTextPacket(SUBTITLE_TEXT));
        }

        // 定制音频：替换原版 ZOMBIE_ATTACK_WOODEN_DOOR；保持广播语义（村民尸潮附近玩家也能听到）
        if (config.warningSound()) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    ModSounds.HORDE_TRIGGER.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
        }

        // 突袭旁白：无条件发送，从语言文件随机抽 1 段
        int index = RANDOM.nextInt(AMBUSH_LINE_COUNT) + 1;
        player.sendSystemMessage(Component.translatable("zombietide.ambush." + index));

        EnikZombietide.LOGGER.info("玩家尸潮触发警报已发送给玩家 {}", player.getScoreboardName());
    }

    /**
     * 村民尸潮触发警报：村民尸潮触发瞬间调用（设计文档 7.2 语义）。
     * 大标题 + 定制触发音效 + 突袭旁白，发给村民周围 villagerWarningRange 范围内的玩家。
     *
     * @param level    服务端世界
     * @param villager 尸潮触发的村民
     * @param config   当前配置（warning 开关控制各通道）
     */
    public static void triggerVillagerHorde(ServerLevel level, Villager villager, ZombietideConfig config) {
        if (config == null || !config.warningEnabled()) return;

        double range = config.villagerWarningRange();
        for (ServerPlayer player : level.getPlayers(p -> p.distanceToSqr(villager) <= range * range)) {
            // 村民尸潮触发警报与玩家触发警报共用三件套（标题 + 触发音效 + 突袭旁白）
            triggerPlayerHorde(level, player, config);
        }
        EnikZombietide.LOGGER.info("村民尸潮触发警报已发送给 {} 周围 {} 格内的玩家",
                villager.getStringUUID(), (int) range);
    }
}
