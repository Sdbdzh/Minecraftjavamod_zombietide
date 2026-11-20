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
 * 注入到 goalSelector 优先级 9（最低，仅在攻击选择器之后运行），强锁尸潮触发者（玩家或村民）为主目标，
 * 并周期性（每 20 tick）检查主目标存活/可达后强制刷新仇恨。
 * <p>
 * 核心行为（设计文档 §5.5）：
 * <ul>
 *   <li>主目标在视线内/可达时：强制锁定主目标为仇恨（优先打你）</li>
 *   <li>主目标不可达（太远、被墙挡、无路可走）：不干预；原版索敌已在 apply() 中移除，因此**不会自动换目标**</li>
 *   <li>主目标死亡/消失：stop()，仇恨交还原版 AI</li>
 *   <li>进入四散状态（state==1）：清空攻击目标；**当前逃跑路径走完后**才用 8 方向评分选点（天空光照越暗越好 / 背离主目标 / 规避 12 格内威胁），并非每 tick 重选（路径未完成时直接 return，避免重设路径抖动）；与 FleeGoal 叠加，最终在 23460 起火时刻统一清除</li>
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
            if (player.isCreative() || player.isSpectator() || !player.isAlive()) {
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
     *   <li>state==0（active）：主目标可达时强制刷新仇恨；不可达时不干预，也不会换目标</li>
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

            // 导航未完成时让当前逃跑路径继续走完，避免每 tick 重设路径导致抖动/卡停。
            // 但途中新出现的威胁必须能被感知：每 THREAT_RESCAN_INTERVAL tick 重扫一次，
            // 只有真的存在威胁才打断当前路径重选。
            Path current = mob.getNavigation().getPath();
            boolean moving = current != null && !current.isDone();
            if (moving) {
                if (++rescanTimer < THREAT_RESCAN_INTERVAL) {
                    return;
                }
                rescanTimer = 0;
                if (!hasNearbyThreat()) {
                    return;
                }
            }
            rescanTimer = 0;

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
            if (player.isCreative() || player.isSpectator() || !player.isAlive()) {
                stop();
                return;
            }
        }

        // 只有主目标可达时才强制刷新仇恨；不可达时不干预（原版索敌已移除，不会换目标）
        if (isReachable(target)) {
            mob.setTarget(target);
        }
        // 否则：不调用 setTarget，僵尸保持无目标状态（原版 NearestAttackableTargetGoal 已被 apply() 移除）
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

    /**
     * 查找主目标实体（玩家或村民），存活才返回；找不到返回 null。
     * <p>服务端统一走 {@link HordeSpawner#findTarget}——它自带「getEntity 未命中就遍历实体表」的兜底，
     * 因为 {@code ServerLevel.getEntity} 的 UUID 索引对<b>已加载村民</b>不可靠。这里若裸查 getEntity，
     * 村民尸潮的僵尸会随机认为目标已消失而 stop()，而 apply() 已移除原版索敌 Goal → 彻底无目标。
     */
    private LivingEntity findTarget() {
        if (targetUUID == null) return null;

        Entity entity;
        if (mob.level() instanceof ServerLevel serverLevel) {
            entity = HordeSpawner.findTarget(serverLevel, targetUUID);
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
    /** 单次评估最多收集的威胁实体数（AABB 只取 12 格内，超出部分忽略） */
    private static final int MAX_THREAT_SCAN = 16;
    /**
     * 逃跑路径行进中的威胁重扫间隔（tick）。
     * <p>路径未完成时不能每 tick 重选（会抖动/卡停），但也不能完全不看——
     * 否则途中新出现的威胁永远感知不到。折中：每 10 tick（0.5 秒）重扫一次，
     * <b>只有确实存在威胁才打断当前路径</b>，无威胁则继续走完。
     */
    private static final int THREAT_RESCAN_INTERVAL = 10;
    /** 重扫计时器 */
    private int rescanTimer = 0;

    /**
     * 12 格内是否存在威胁实体（玩家 / 村民 / 铁傀儡，排除创造与旁观玩家）。
     * <p>用 AABB 范围查询（{@code getEntitiesOfClass}）而非遍历全实体表，成本 O(附近实体数)。
     * 供「路径行进中定期重扫」判断是否需要打断当前路径。
     */
    private boolean hasNearbyThreat() {
        if (!(mob.level() instanceof ServerLevel sl)) return false;
        var box = mob.getBoundingBox().inflate(THREAT_AVOID_RADIUS);
        for (LivingEntity le : sl.getEntitiesOfClass(LivingEntity.class, box)) {
            if (!le.isAlive()) continue;
            if (le instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())) continue;
            if (le instanceof ServerPlayer || le instanceof Villager || le instanceof IronGolem) {
                return true;
            }
        }
        return false;
    }

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

        // 收集附近威胁实体（玩家/村民/铁傀儡），忽略创造/旁观玩家。
        // 用 AABB 范围查询而非 getEntities().getAll()：只扫 12 格内，
        // 成本从 O(全世界实体数) 降到 O(附近实体数)。MAX_THREAT_SCAN 此时才真正起到「收集上限」作用。
        java.util.List<LivingEntity> threats = new java.util.ArrayList<>();
        if (mob.level() instanceof ServerLevel sl) {
            var box = mob.getBoundingBox().inflate(THREAT_AVOID_RADIUS);
            for (LivingEntity le : sl.getEntitiesOfClass(LivingEntity.class, box)) {
                if (threats.size() >= MAX_THREAT_SCAN) break;
                if (!le.isAlive()) continue;
                if (le instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())) continue;
                if (le instanceof ServerPlayer || le instanceof Villager || le instanceof IronGolem) {
                    // AABB 是方形范围，再用球形距离精确筛一次
                    if (le.distanceToSqr(mob) <= THREAT_AVOID_RADIUS * THREAT_AVOID_RADIUS) {
                        threats.add(le);
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
                    // toTarget 指向主目标，故点积 >0 表示该方向朝向主目标，<0 才是背离
                    awayDot = (dirX * toTargetX + dirZ * toTargetZ) / len;
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
            // 2. 背离主目标：awayDot 越小越背离，故取负加权
            score -= awayDot * 4.0;
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
