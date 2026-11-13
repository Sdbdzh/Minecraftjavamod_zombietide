package com.enik.zombietide.horde;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * 四散逃跑 Goal：持续运行直到僵尸 state != 1。
 * 优先级 0（最高），确保在其他 AI 之前执行。
 * 逻辑：每 tick 检查 state，若仍为 1 则持续逃跑；否则 stop。
 */
public class FleeGoal extends Goal {

    private final Mob mob;
    private final LivingEntity target;
    private int refreshTimer = 0;
    private static final int REFRESH_INTERVAL = 10; // 每 10 tick 重新规划一次路径，避免卡墙

    public FleeGoal(Mob mob, LivingEntity target) {
        this.mob = mob;
        this.target = target;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // 持续运行直到 state != 1 或目标为空
        var tag = mob.getPersistentData();
        int state = tag.getInt("zombietide_state");
        // 忽略创造/旁观模式玩家
        if (target instanceof Player player) {
            if (player.getAbilities().mayBuild || !player.isAlive()) return false;
        }
        return state == 1 && target != null && target.isAlive();
    }

    @Override
    public void start() {
        refreshTimer = 0;
        setFleePath();
    }

    @Override
    public void tick() {
        // 每 tick 清空攻击目标，防止 HurtByTargetGoal 重新锁定
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.setLastHurtByPlayer(null);

        refreshTimer++;
        if (refreshTimer >= REFRESH_INTERVAL) {
            refreshTimer = 0;
            setFleePath();
        }

        // 导航卡住时重新规划
        Path current = mob.getNavigation().getPath();
        if (current != null && current.isDone()) {
            setFleePath();
        }
    }

    @Override
    public boolean canContinueToUse() {
        var tag = mob.getPersistentData();
        int state = tag.getInt("zombietide_state");
        // 忽略创造/旁观模式玩家
        if (target instanceof Player player) {
            if (player.getAbilities().mayBuild || !player.isAlive()) return false;
        }
        return state == 1 && target != null && target.isAlive();
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        mob.setLastHurtByPlayer(null);
    }

    private void setFleePath() {
        if (target == null || !target.isAlive()) return;
        double fleeX = mob.getX() + (mob.getX() - target.getX()) * 4.0;
        double fleeZ = mob.getZ() + (mob.getZ() - target.getZ()) * 4.0;
        mob.getNavigation().moveTo(fleeX, mob.getY(), fleeZ, 1.2);
    }
}