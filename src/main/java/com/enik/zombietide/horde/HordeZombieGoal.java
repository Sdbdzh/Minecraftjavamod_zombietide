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
 *   <li>主目标存在：每 20 tick 强制锁定为仇恨（优先打你）；寻路失败交给 ZombieAttackGoal 处理</li>
 *   <li>主目标死亡 / 在未加载区块 / 跨维度：改打<b>附近（64 格内）其它尸潮的触发者</b>；
 *       一个都没有时交给原版索敌（玩家 / 村民 / 铁傀儡）</li>
 *   <li>被攻击：优先反击至少 20 tick（不覆盖 HurtByTargetGoal 设的目标）</li>
 *   <li>进入四散状态（state==1）：清空攻击目标并禁用 MOVE 控制位（阻止原版漫游 Goal 抢导航）；
 *       追一个不可见的 {@link HordeFleeMarker} 诱饵——路径走完的当 tick 立刻重发，
 *       行进中每 20 tick 补发；7 个候选点让同波僵尸扇开。最终在 23460 起火时刻统一清除。
 *       <b>本类是四散导航的唯一实现</b>（v0.1.54 删除了 FleeGoal）</li>
 * </ul>
 */
public class HordeZombieGoal extends Goal {

    private static final int REFRESH_INTERVAL_TICKS = 20; // 每 20 tick（1 秒）检查并刷新一次仇恨
    private static final String HORDE_STATE_KEY = "zombietide_state"; // 0=active, 1=scattering, 2=despawned（预留，当前无任何写入点，分支不可达）
    private static final java.util.Random RANDOM = new java.util.Random();

    private final Mob mob;
    private final UUID targetUUID;
    private int refreshTimer = 0;

    public HordeZombieGoal(Mob mob, UUID targetUUID) {
        this.mob = mob;
        this.targetUUID = targetUUID;
    }

    /** 每 tick 调用，判断 Goal 是否可以开始/继续执行：
     *  <p>state==0（active）：<b>不要求主目标存活</b>（v0.1.59 修复 #95）——触发者死亡/未加载时
     *  Goal 仍要运行，由 tick() 的多级选目标决定打谁；只排除「目标存在但创造/旁观」与 state==2。
     *  state==1（scattering）：一直返回 true，持续运行 tick() 执行逃跑逻辑，直到僵尸在 23460 被 discard。
     *  <p>state==2（despawned）是预留值：当前无任何写入点，下面的分支不可达，保留备查。
     */
    @Override
    public boolean canUse() {
        CompoundTag tag = mob.getPersistentData();
        int state = tag.getInt(HORDE_STATE_KEY);
        // state==2（despawned，预留值，当前无任何写入点，分支不可达）：停止本 Goal（保留备查）
        if (state == 2) return false;
        // state==1（scattering）：持续运行，不依赖主目标是否存在
        if (state == 1) return true;

        // 【v0.1.59 修复 #95】state==0 时**不再要求**「自己的触发者必须存在」。
        // 之前这里 `if (target == null) return false;` 导致触发者死亡/未加载时整个 Goal 不运行，
        // tick() 里那段「改打其它波次触发者」的分支永远执行不到（死代码）。
        // 现在只排除「触发者存在但不可攻击（创造/旁观）」，其余情况一律运行，
        // 由 tick() 的多级选目标决定打谁；一级都不命中时僵尸正常游荡。
        LivingEntity target = findTarget();
        if (target instanceof ServerPlayer player
                && (player.isCreative() || player.isSpectator())) {
            return false;
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
     *   <li>state==0（active）：主目标存在时每 20 tick 强制锁定；死亡/未加载时改打附近其它尸潮触发者，都没有则交给原版索敌</li>
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

        // 1) 正在反击攻击者 → 不覆盖。
        // 用户要求：被攻击时优先反击，而不是一直强索敌。
        // HurtByTargetGoal 被 setTarget(attacker)，这里保留它至少 20 tick。
        LivingEntity current = mob.getTarget();
        LivingEntity attacker = mob.getLastHurtByMob();
        if (current != null && current == attacker && current.isAlive()) {
            return;
        }

        // 2) 自己的触发者
        LivingEntity target = findTarget();
        if (target instanceof ServerPlayer player
                && (player.isCreative() || player.isSpectator() || !player.isAlive())) {
            target = null;
        }

        // 3) 自己的触发者已死亡 / 在未加载区块 → 改打附近其它尸潮的触发者
        if (target == null) {
            target = findNearestOtherHordeTarget();
        }

        if (target != null) {
            mob.setTarget(target);
            return;
        }

        // 4) 一个都没有 → 不主动设目标，交给原版 NearestAttackableTargetGoal
        //    （v0.1.57 起不再移除该 Goal，所以僵尸仍会攻击附近的玩家/村民/铁傀儡）
        if (current != null && !current.isAlive()) {
            mob.setTarget(null);
        }
    }

    /**
     * 找附近其它尸潮的触发者（自己的触发者不存在/无效时的次级目标）。
     * <p>目标源是 {@link HordeSpawner#getActiveWaveTargetIds(ServerLevel)}：
     * 并上仍在刷怪的 {@code activeHordes}，再加玩家 attachment / 村民调度表里
     * {@code waveActive=true} 的目标——所以**刷完但僵尸还活着**的波次也算（v0.1.60）。
     * <p>距离上限 {@link #OTHER_TARGET_MAX_DISTANCE}（64 格）：超出则继续追自己的触发者
     * 或交由原版索敌，不横穿世界去追另一波。
     */
    private LivingEntity findNearestOtherHordeTarget() {
        if (!(mob.level() instanceof ServerLevel sl)) return null;
        LivingEntity best = null;
        double bestD2 = Double.MAX_VALUE;
        double maxD2 = OTHER_TARGET_MAX_DISTANCE * OTHER_TARGET_MAX_DISTANCE;
        for (UUID otherId : HordeSpawner.getActiveWaveTargetIds(sl)) {
            if (otherId.equals(targetUUID)) continue;
            var e = HordeSpawner.findTarget(sl, otherId);
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (le instanceof ServerPlayer p && (p.isCreative() || p.isSpectator())) continue;
            double d2 = le.distanceToSqr(mob);
            // 只在「附近」范围内换目标：超过这个距离说明它是另一波的事，
            // 当前波次的僵尸应该继续追击自己的触发者（或就近打原版目标），
            // 而不是横穿世界去追另一波。
            if (d2 > maxD2) continue;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = le;
            }
        }
        return best;
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
     * 查找主目标实体（玩家或村民），存活才返回；找不到返回 null。
     * <p>服务端统一走 {@link HordeSpawner#findTarget}——它自带「getEntity 未命中就遍历实体表」的兜底，
     * 因为 {@code ServerLevel.getEntity} 的 UUID 索引对<b>已加载村民</b>不可靠。若裸查 getEntity，
     * 村民尸潮的僵尸会误判目标已消失。v0.1.57 起原版索敌 Goal 已恢复保留，
     * 目标缺失时会由 tick() 改打其它波次触发者 / 交给原版索敌，而不是彻底无目标。
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
    /** 「换打其它尸潮触发者」的最大距离（格）：超出就继续追自己的目标，不横穿世界 */
    private static final double OTHER_TARGET_MAX_DISTANCE = 64.0;

}
