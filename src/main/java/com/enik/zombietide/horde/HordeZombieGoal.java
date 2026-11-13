package com.enik.zombietide.horde;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;

import java.util.UUID;

/**
 * 尸潮僵尸目标锁定 Goal。
 * 注入到 goalSelector 优先级 0（最高），强锁尸潮触发者（玩家或村民）为主目标，
 * 并周期性（每 20 tick）检查主目标存活/可达后强制刷新仇恨。
 * <p>
 * 核心行为（设计文档 §5.1-5.2）：
 * <ul>
 *   <li>主目标在视线内/可达时：强制锁定主目标为仇恨（优先打你）</li>
 *   <li>主目标不可达（太远、被墙挡、无路可走）：不干预，让原版 AI 就近攻击最近可见生物（不挑食）</li>
 *   <li>主目标死亡/消失：stop()，仇恨交还原版 AI</li>
 *   <li>进入四散状态（state==1）：清空攻击目标，背向主目标远处随机移动，最终由 despawn 计时器移除</li>
 * </ul>
 */
public class HordeZombieGoal extends Goal {

    private static final int REFRESH_INTERVAL_TICKS = 20; // 每 20 tick（1 秒）检查并刷新一次仇恨
    private static final String HORDE_STATE_KEY = "zombietide_state"; // 0=active, 1=scattering, 2=despawned
    private static final java.util.Random RANDOM = new java.util.Random();

    private final Mob mob;
    private final UUID targetUUID;
    private int refreshTimer = 0;

    public HordeZombieGoal(Mob mob, UUID targetUUID) {
        this.mob = mob;
        this.targetUUID = targetUUID;
    }

    /** 每 tick 调用，判断 Goal 是否可以开始/继续执行：
     *  state==0：主目标存活且未进入 despawn 状态
     *  state==1（scattering）：一直返回 true，持续运行 tick() 执行逃跑逻辑，直到 state==2
     */
    @Override
    public boolean canUse() {
        CompoundTag tag = mob.getPersistentData();
        int state = tag.getInt(HORDE_STATE_KEY);
        // state==2（despawned）：停止本 Goal
        if (state == 2) return false;
        // state==1（scattering）：持续运行，不依赖主目标是否存在
        if (state == 1) return true;

        LivingEntity target = findTarget();
        if (target == null) return false;

        // 创造/旁观模式玩家不作为尸潮目标
        if (target instanceof ServerPlayer player) {
            if (player.getAbilities().mayBuild || !player.isAlive()) {
                return false;
            }
        }

        return true;
    }

    /** Goal 开始执行时调用：强锁主目标。四散状态（state==1）不重新锁定目标。 */
    @Override
    public void start() {
        refreshTimer = 0;
        CompoundTag tag = mob.getPersistentData();
        int state = tag.getInt(HORDE_STATE_KEY);
        if (state == 1) {
            // 四散状态下不重新锁定攻击目标，避免与四散逃跑逻辑冲突
            mob.setTarget(null);
            return;
        }
        LivingEntity target = findTarget();
        if (target != null) {
            mob.setTarget(target);
        }
    }

    /**
     * Goal 执行期间每 tick 调用（requiresUpdateEveryTick()=true 保证逐 tick 调用）。
     * <ul>
     *   <li>state==1（scattering）：持续逃离主目标，偏向暗处，避开玩家/村民/铁傀儡</li>
     *   <li>state==0（active）：主目标可达时强制刷新仇恨；不可达时不干预，让原版 AI 就近选目标</li>
     * </ul>
     */
    @Override
    public void tick() {
        CompoundTag tag = mob.getPersistentData();
        int state = tag.getInt(HORDE_STATE_KEY);

        // ===== 四散状态：持续逃离，不攻击 =====
        if (state == 1) {
            mob.setTarget(null);
            // 被打后 HurtByTargetGoal 会重新锁定攻击者报仇，这里清掉防止边逃边打
            mob.setLastHurtByMob(null);
            mob.setLastHurtByPlayer(null);

            // 导航未完成时让当前逃跑路径继续走完，避免每 tick 重设路径导致抖动/卡停
            Path current = mob.getNavigation().getPath();
            if (current != null && !current.isDone()) {
                return;
            }

            LivingEntity mainTarget = findTarget();
            pickFleeDestination(mainTarget);
            return;
        }

        // state==0 或其他：正常尸潮行为
        refreshTimer++;
        if (refreshTimer < REFRESH_INTERVAL_TICKS) {
            return;
        }
        refreshTimer = 0;

        LivingEntity target = findTarget();
        if (target == null) {
            // 主目标死亡或消失：停止本 Goal，回退原版 AI
            stop();
            return;
        }

        // 目标变为创造/旁观模式或死亡：停止本 Goal
        if (target instanceof ServerPlayer player) {
            if (player.getAbilities().mayBuild || !player.isAlive()) {
                stop();
                return;
            }
        }

        // 只有主目标可达时才强制刷新仇恨；不可达时不干预，让原版 AI 就近攻击最近可见生物
        if (isReachable(target)) {
            mob.setTarget(target);
        }
        // 否则：不调用 setTarget，保留原版 AI 选中的最近目标（“优先打你，但不挑食”）
    }

    /**
     * Goal 停止执行时调用：清空攻击目标，让僵尸停止攻击。
     */
    @Override
    public void stop() {
        refreshTimer = 0;
        mob.setTarget(null);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * 主目标可达性判定：视线可达，或当前已有有效行进路径（导航正在寻路/赶路）。
     */
    private boolean isReachable(LivingEntity target) {
        if (mob.getSensing().hasLineOfSight(target)) {
            return true;
        }
        Path path = mob.getNavigation().getPath();
        return path != null && !path.isDone();
    }

    /** 查找主目标实体（玩家或村民），存活才返回；找不到返回 null */
    private LivingEntity findTarget() {
        if (targetUUID == null) return null;

        Entity entity = null;
        if (mob.level() instanceof ServerLevel serverLevel) {
            entity = serverLevel.getEntity(targetUUID);
        } else {
            entity = mob.level().getPlayerByUUID(targetUUID);
        }

        if (entity instanceof LivingEntity living && living.isAlive()) {
            return living;
        }
        return null;
    }

    // ==================== 四散逃跑逻辑 ====================

    /** 逃跑评分采样方向数（8 方向）；评分采样只需方块查询，开销可控 */
    private static final int FLEE_DIRECTIONS = 8;
    /** 逃跑点距离（格）— 增加到 48 格，保证持续远距离逃离 */
    private static final double FLEE_DISTANCE = 48.0;
    /** 威胁实体（玩家/村民/铁傀儡）规避半径 */
    private static final double THREAT_AVOID_RADIUS = 12.0;
    /** 每 tick 威胁实体扫描的最大数量（性能上限，超出部分忽略） */
    private static final int MAX_THREAT_SCAN = 16;

    /**
     * 选择逃跑目的地：采样 FLEE_DIRECTIONS 个方向，按评分选最优：
     *  - 天空光照越暗越好（躲太阳，防止被烧死）
     *  - 远离主目标越远越好
     *  - 附近（THREAT_AVOID_RADIUS 内）玩家/村民/铁傀儡越少越好
     *  - 目标点可寻路（有 Path 才真的会走过去）
     * 全部不可行时，退化为背向主目标直跑（保证"一直在逃"）。
     */
    private void pickFleeDestination(LivingEntity mainTarget) {
        double mx = mainTarget != null ? mainTarget.getX() : mob.getX();
        double mz = mainTarget != null ? mainTarget.getZ() : mob.getZ();

        // 收集附近威胁实体（玩家/村民/铁傀儡），限量扫描保证性能
        // 忽略创造/旁观模式玩家
        java.util.List<LivingEntity> threats = new java.util.ArrayList<>();
        if (mob.level() instanceof ServerLevel sl) {
            int scanned = 0;
            for (var e : sl.getEntities().getAll()) {
                if (scanned >= MAX_THREAT_SCAN) break;
                if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
                if (e instanceof ServerPlayer player) {
                    if (player.getAbilities().mayBuild || !player.isAlive()) continue; // 忽略创造/旁观模式
                }
                if (e instanceof ServerPlayer || e instanceof Villager || e instanceof IronGolem) {
                    // 快速距离粗筛（16 格内才算威胁）
                    if (le.distanceToSqr(mob) <= THREAT_AVOID_RADIUS * THREAT_AVOID_RADIUS) {
                        threats.add(le);
                        scanned++;
                    }
                }
            }
        }

        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos bestPos = null;

        for (int i = 0; i < FLEE_DIRECTIONS; i++) {
            double angle = (Math.PI * 2 * i) / FLEE_DIRECTIONS;
            // 主目标方向角（用于计算"背离程度"）
            double awayDot = 0;
            if (mainTarget != null) {
                double toTargetX = mx - mob.getX();
                double toTargetZ = mz - mob.getZ();
                double len = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
                if (len > 0.001) {
                    double dirX = Math.cos(angle), dirZ = Math.sin(angle);
                    awayDot = (dirX * toTargetX + dirZ * toTargetZ) / len; // >0 表示背离主目标
                }
            } else {
                awayDot = 1; // 无主目标时方向等权
            }

            int tx = (int) Math.round(mob.getX() + Math.cos(angle) * FLEE_DISTANCE);
            int tz = (int) Math.round(mob.getZ() + Math.sin(angle) * FLEE_DISTANCE);
            BlockPos dest = findGroundPos(mob.level(), tx, tz, (int) mob.getY());
            if (dest == null) continue;

            // --- 评分 ---
            double score = 0;
            // 1. 天空光照：越暗越好（0-15，取反加权）
            int skyLight = mob.level().getBrightness(LightLayer.SKY, dest);
            score += (15 - skyLight) * 2.0;
            // 2. 背离主目标
            score += awayDot * 4.0;
            // 3. 威胁实体规避：目的地附近有威胁则重罚
            for (LivingEntity threat : threats) {
                double d2 = (threat.getX() - dest.getX()) * (threat.getX() - dest.getX())
                        + (threat.getZ() - dest.getZ()) * (threat.getZ() - dest.getZ());
                if (d2 <= THREAT_AVOID_RADIUS * THREAT_AVOID_RADIUS) {
                    score -= 8.0;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestPos = dest;
            }
        }

        if (bestPos != null) {
            Path path = mob.getNavigation().createPath(bestPos, 0);
            if (path != null) {
                mob.getNavigation().moveTo(path, 1.1);
                return;
            }
        }

        // 兜底：无可用采样点/不可寻路 → 背向主目标直跑，保证持续逃离
        double awayX = mainTarget != null ? mob.getX() + (mob.getX() - mx) * 3.0 : mob.getX() + (RANDOM.nextDouble() - 0.5) * 40;
        double awayZ = mainTarget != null ? mob.getZ() + (mob.getZ() - mz) * 3.0 : mob.getZ() + (RANDOM.nextDouble() - 0.5) * 40;
        mob.getNavigation().moveTo(awayX, mob.getY(), awayZ, 1.1);
    }

    /** 找目的地附近的地表可站方块（向上最多 5 格，向下最多 3 格） */
    private BlockPos findGroundPos(net.minecraft.world.level.Level level, int x, int z, int refY) {
        // 从参考高度向上找
        for (int dy = 0; dy <= 5; dy++) {
            BlockPos p = new BlockPos(x, refY + dy, z);
            if (isStandable(level, p)) return p;
        }
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos p = new BlockPos(x, refY - dy, z);
            if (isStandable(level, p)) return p;
        }
        return null;
    }

    private boolean isStandable(net.minecraft.world.level.Level level, BlockPos p) {
        return level.getBlockState(p).isAir()
                && level.getBlockState(p.above()).isAir()
                && !level.getBlockState(p.below()).isAir();
    }
}
