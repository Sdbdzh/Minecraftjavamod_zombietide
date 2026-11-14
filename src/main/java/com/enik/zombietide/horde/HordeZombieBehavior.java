package com.enik.zombietide.horde;

import com.enik.zombietide.config.ZombietideConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Stray;
import net.minecraft.world.entity.monster.Zombie;

import java.util.UUID;

/**
 * 尸潮僵尸行为工具类：为目标僵尸注入尸潮 AI 与身份标记。
 * <p>
 * apply() 确保每只尸潮僵尸具备以下规格：
 * <ol>
 *   <li>永不自然消失（setPersistenceRequired）</li>
 *   <li>增援召唤关闭（SPAWN_REINFORCEMENTS_CHANCE 基值归零）</li>
 *   <li>NBT 身份标记：目标 UUID + 波次 ID + 状态</li>
 *   <li>主目标锁定 Goal 注入 goalSelector 优先级 0（最高）</li>
 * </ol>
 * <p>
 * 破门功能已移除：僵尸不再破坏门（设计变更）。
 */
public final class HordeZombieBehavior {

    // NBT 键（与 HordeSpawner 保持一致；HordeSpawner.spawnZombie 是权威写入点，apply 幂等补写保证自包含）
    private static final String NBT_KEY_TARGET = "zombietide_horde_target"; // 主目标 UUID
    private static final String NBT_KEY_WAVE_ID = "zombietide_wave_id";     // 波次 ID（本波总尸数）
    private static final String NBT_KEY_STATE = "zombietide_state";         // 0=active, 1=scattering, 2=despawned

    private HordeZombieBehavior() {
    }

    /**
     * 应用尸潮僵尸行为。
     *
     * @param mob        尸潮僵尸（Zombie、Husk、Stray 等 Mob）
     * @param targetUUID 主目标 UUID（尸潮触发者：玩家或村民）
     * @param config     存档配置（保留参数，供后续扩展如日光燃烧开关）
     */
    public static void apply(Mob mob, UUID targetUUID, ZombietideConfig config) {
        // 1. 永不自然消失（幂等调用，HordeSpawner 此前已调用过一次）
        mob.setPersistenceRequired();

        // 2. 关闭增援召唤：
        //    原版 Mob.hurt 在困难难度下按 SPAWN_REINFORCEMENTS_CHANCE 概率召唤增援。
        //    尸潮僵尸由刷怪引擎统一批量生成，不需要单体随机增援；把属性基值归零即可靠关闭
        AttributeInstance reinforcements = mob.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE);
        if (reinforcements != null) {
            reinforcements.setBaseValue(0.0D);
        }

        // 3. NBT 身份标记（幂等：HordeSpawner.spawnZombie 已先行写入，此处仅补写缺失键，不覆盖权威值）
        CompoundTag tag = mob.getPersistentData();
        if (!tag.hasUUID(NBT_KEY_TARGET)) {
            tag.putUUID(NBT_KEY_TARGET, targetUUID);
        }
        if (!tag.contains(NBT_KEY_WAVE_ID)) {
            tag.putInt(NBT_KEY_WAVE_ID, 0);
        }
        if (!tag.contains(NBT_KEY_STATE)) {
            tag.putInt(NBT_KEY_STATE, 0);
        }

        // 4. 注入主目标锁定 Goal（优先级 9，仅在攻击选择器之后运行）。
        // 在 state==1（四散）时持续清除攻击目标，防止 HurtByTargetGoal 等选择器
        // 把目标重新设回玩家导致“边逃边打”。
        // 先清空 targetSelector 中的 NearestAttackableTargetGoal 与 HurtByTargetGoal，
        // 防止僵尸在四散时被这两个选择器每 tick 重新锁定目标（“边逃边打”根因）。
        java.util.List<Goal> toRemove = new java.util.ArrayList<>();
        for (Goal g : mob.targetSelector.getAvailableGoals()) {
            if (g instanceof NearestAttackableTargetGoal || g instanceof HurtByTargetGoal) {
                toRemove.add(g);
            }
        }
        for (Goal g : toRemove) {
            mob.targetSelector.removeGoal(g);
        }
        mob.goalSelector.addGoal(9, new HordeZombieGoal(mob, targetUUID));

        // 注：日光燃烧开关（config.daylightBurn()）通过 DaylightBurnMixin 覆写 Mob.isSunSensitive()
        // 实现：仅带 zombietide_horde_target 标记的尸潮僵尸在 daylightBurn=false 时不燃烧；
        // 自然生成的僵尸/骷髅等仍保持原版燃烧行为。
    }
}
