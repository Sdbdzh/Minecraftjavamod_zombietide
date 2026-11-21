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
 *   <li>进入四散状态（state==1）：清空攻击目标并禁用 MOVE 控制位（阻止原版漫游 Goal 抢导航）；
 *       <b>当前逃跑路径走完后</b>才用 8 方向评分选点（天空光照越暗越好 / 背离主目标 / 规避 12 格内威胁），
 *       对评分前 3 的候选做 createPath 可达性预检，并非每 tick 重选；最终在 23460 起火时刻统一清除。
 *       <b>本类是四散导航的唯一实现</b>（v0.1.54 起删除了 FleeGoal）</li>
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

            // 阻止原版漫游类 Goal（WaterAvoidingRandomStrollGoal 等，带 MOVE flag）抢占导航。
            // 四散期间导航只能由本 Goal 驱动，否则两个 Goal 互相覆盖 → 走走停停。
            // 注意：不能给本 Goal 加 setFlags(MOVE)——它优先级 9，会被优先级 2 的攻击 Goal
            // 永久饿死，导致 state==0 的仇恨刷新失效。
            mob.goalSelector.disableControlFlag(Goal.Flag.MOVE);

            // v0.1.55：四散导航改为「追一个不可见 Marker 诱饵」。
            // marker 由 scatterZombies 创建，每 tick 沿背离主目标方向移动 0.30 格（略快于僵尸）。
            // 关键：用 createPath + moveTo(Path) 而不是坐标版 moveTo(x,y,z,speed)——
            // 后者在 createPath 失败时把 path 置 null，会把僵尸钉住（这正是旧的「走一段停一下」）。
            net.minecraft.world.entity.Entity marker = HordeFleeMarker.getFor(mob);
            if (marker == null || !marker.isAlive()) {
                // marker 未就绪（重启后丢失/尚未创建）：本 tick 不动，
                // HordeTimer 的四散重扫会在 ≤20 tick 内 ensure 重建。
                // 临时日志：确认 marker 取不到的原因（NBT 无 key vs level.getEntity 返回 null）
                if (markerMissingLogTimer++ >= 40) {   // 每 2 秒打一次，避免刷屏
                    markerMissingLogTimer = 0;
                    CompoundTag nbt = mob.getPersistentData();
                    boolean hasKey = nbt.hasUUID(HordeFleeMarker.NBT_MARKER);
                    com.enik.zombietide.EnikZombietide.LOGGER.warn(
                            "僵尸 {} 四散但取不到 marker：NBT 有 key={}, marker UUID={}",
                            mob.getUUID(), hasKey,
                            hasKey ? nbt.getUUID(HordeFleeMarker.NBT_MARKER) : "无");
                }
                return;
            }
            markerMissingLogTimer = 0;

            var navigation = mob.getNavigation();
            // 路径走完的当 tick 立刻重发（无空档）；行进中每 20 tick 补发一次（自限流）
            if (navigation.isDone() || ++repathTimer >= REPATH_INTERVAL_TICKS) {
                repathTimer = 0;
                Path path = navigation.createPath(HordeFleeMarker.candidates(marker), 1);
                if (path != null) {
                    navigation.moveTo(path, 1.1);
                }
            }
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

        // 无条件刷新仇恨：ZombieAttackGoal 需要 mob.getTarget() != null 才追击。
        // 之前的 isReachable 判断（没有视线就不 setTarget）会导致僵尸在墙后/远处时
        // 完全无目标 → ZombieAttackGoal 不启动 → 站着不动 → 20 tick 后碰巧有视线才追一段
        // → 表现为「走一下停一下」。寻路失败由 ZombieAttackGoal 内部处理，不需要这里拦。
        mob.setTarget(target);
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

    /** 路径行进中的补发间隔（tick）：走完当 tick 会立刻重发，这里只做兜底 */
    private static final int REPATH_INTERVAL_TICKS = 20;
    /** 距上次重发的计时器 */
    private int repathTimer = 0;
    /** marker 取不到时的日志节流计时器 */
    private int markerMissingLogTimer = 0;

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
