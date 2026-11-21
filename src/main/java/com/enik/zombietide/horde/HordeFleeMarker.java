package com.enik.zombietide.horde;

import com.enik.zombietide.EnikZombietide;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 四散「诱饵锚点」：给每个尸潮目标生成一个不可见的 {@link EntityType#MARKER}，
 * 该尸潮的僵尸通过 {@code navigation.createPath} 朝它跑。
 *
 * <p><b>为什么用 Marker</b>：0×0 AABB、{@code noPhysics}、{@code tick()} 空实现、
 * 非 LivingEntity 无血量、{@code checkDespawn()} 空方法、{@code clientTrackingRange(0)} 不同步客户端。
 * 它只是一个坐标锚点，不参与任何 AI / 渲染 / 伤害。
 *
 * <p><b>为什么不会「走走停停」</b>：僵尸侧用的是 {@code createPath(Set<BlockPos>, 1)} +
 * {@code moveTo(Path, speed)}，而不是坐标版 {@code moveTo(x,y,z,speed)}——后者在
 * {@code createPath} 返回 null 时会把 {@code path} 置 null，直接把僵尸钉住。
 * 另外路径走完的当 tick 就立刻重发，路径之间没有空档。
 *
 * <p><b>不持久化</b>：marker 是纯运行时锚点。服务器重启后丢失，由
 * {@code HordeTimer} 的四散重扫（每 20 tick）自动 {@link #ensure} 重建。
 */
public final class HordeFleeMarker {

    /** 僵尸 PersistentData 上记录 marker UUID 的键 */
    public static final String NBT_MARKER = "zombietide_flee_marker";
    /** marker PersistentData 上记录所属尸潮目标 UUID 的键 */
    private static final String TAG_TARGET = "zombietide_flee_target";

    /**
     * 每次更新移动的距离（格）。
     * <p><b>注意调用频率</b>：{@code HordeTimer} 每 5 tick 才调一次 {@link #tickAll}，
     * 所以这里必须按「每次更新 1.5 格」来算，实际才是 0.30 格/tick —— 略快于僵尸的 ~0.2。
     * v0.1.59 前是 0.30/次 → 实际 0.06 格/tick，比僵尸慢 3 倍，诱饵反被超越。
     */
    private static final double STEP_PER_UPDATE = 1.5;
    /** 候选点环半径（格）：让同波僵尸从不同点出发，避免排队 */
    private static final int CANDIDATE_RING_RADIUS = 3;
    /** 候选点数量（含中心） */
    private static final int CANDIDATE_RING_POINTS = 6;

    private HordeFleeMarker() {
    }

    /**
     * 为某个尸潮目标创建（或复用）诱饵锚点。
     *
     * @param level    服务端世界
     * @param targetId 尸潮目标 UUID（玩家或村民）
     * @param x        初始 X（一般取目标当前位置背离方向的一小段）
     * @param y        初始 Y
     * @param z        初始 Z
     * @return marker 实体；创建失败返回 null
     */
    public static Entity ensure(ServerLevel level, UUID targetId, double x, double y, double z) {
        Entity existing = findExisting(level, targetId);
        if (existing != null) return existing;

        net.minecraft.world.entity.Marker marker = EntityType.MARKER.create(level);
        if (marker == null) return null;
        marker.moveTo(x, y, z, 0.0F, 0.0F);
        marker.getPersistentData().putUUID(TAG_TARGET, targetId);
        level.addFreshEntity(marker);
        EnikZombietide.LOGGER.info("四散锚点已创建：目标 {} 位置({}, {}, {}) marker={}",
                targetId, x, y, z, marker.getUUID());
        return marker;
    }

    /** 查找某目标已有的 marker（扫世界里的 Marker 实体） */
    private static Entity findExisting(ServerLevel level, UUID targetId) {
        for (Entity e : level.getEntities().getAll()) {
            if (e == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (e.getType() != EntityType.MARKER) continue;
            CompoundTag tag = e.getPersistentData();
            if (tag.hasUUID(TAG_TARGET) && targetId.equals(tag.getUUID(TAG_TARGET))) {
                return e;
            }
        }
        return null;
    }

    /**
     * 【v0.1.62 修复 #166】把所有 marker 沿「背离主目标」方向推进一小步。
     * <p><b>调用频率</b>：由 {@code HordeTimer} 每 <b>5 tick</b> 调用一次（不是每 tick），
     * 因此 {@link #STEP_PER_UPDATE} 按「每次更新 1.5 格」取值，实际 0.30 格/tick。
     * <p>主目标取不到（离线/未加载）时保持不动——僵尸会走到路径尽头后等待下一次重发。
     */
    public static void tickAll(ServerLevel level) {
        for (Entity e : level.getEntities().getAll()) {
            if (e == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (e.getType() != EntityType.MARKER) continue;
            CompoundTag tag = e.getPersistentData();
            if (!tag.hasUUID(TAG_TARGET)) continue;

            Entity target = HordeSpawner.findTarget(level, tag.getUUID(TAG_TARGET));
            if (target == null) continue;

            double dx = e.getX() - target.getX();
            double dz = e.getZ() - target.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) {
                // 与目标重合：随机挑一个方向起步
                dx = 1.0;
                dz = 0.0;
                len = 1.0;
            }
            double nx = e.getX() + dx / len * STEP_PER_UPDATE;
            double nz = e.getZ() + dz / len * STEP_PER_UPDATE;
            int ny = (int) Math.floor(e.getY());

            // 必须先确认区块已加载：Entity#setPosRaw 里有 NeoForge 注入的 level.getChunk(...)，
            // 把已加入世界的实体挪进未加载区块会触发同步加载，造成卡顿。
            BlockPos probe = BlockPos.containing(nx, ny, nz);
            if (!level.hasChunkAt(probe)) continue;

            BlockPos ground = findGround(level, probe.getX(), probe.getZ(), ny);
            if (ground == null) continue;

            e.moveTo(ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, 0.0F, 0.0F);
        }
    }

    /** 从僵尸的 PersistentData 取出所属尸潮的 marker（未加载/不存在则 null） */
    public static Entity getFor(Mob mob) {
        CompoundTag tag = mob.getPersistentData();
        if (!tag.hasUUID(NBT_MARKER)) return null;
        if (!(mob.level() instanceof ServerLevel sl)) return null;
        Entity e = sl.getEntity(tag.getUUID(NBT_MARKER));
        return (e != null && e.isAlive()) ? e : null;
    }

    /** 把 marker UUID 写进僵尸的 PersistentData（四散开始时调用一次） */
    public static void bind(Mob mob, Entity marker) {
        mob.getPersistentData().putUUID(NBT_MARKER, marker.getUUID());
    }

    /**
     * marker 周围的候选地面点（中心 + 一圈 6 个）。
     * <p>传给 {@code createPath(Set<BlockPos>, 1)} 后，A* 会自动挑离僵尸最近的那个，
     * 于是同一波僵尸从不同点出发，不会排成一列。
     */
    public static Set<BlockPos> candidates(Entity marker) {
        Set<BlockPos> set = new HashSet<>();
        BlockPos base = marker.blockPosition();
        set.add(base);
        for (int i = 0; i < CANDIDATE_RING_POINTS; i++) {
            double angle = (Math.PI * 2 * i) / CANDIDATE_RING_POINTS;
            int x = base.getX() + (int) Math.round(Math.cos(angle) * CANDIDATE_RING_RADIUS);
            int z = base.getZ() + (int) Math.round(Math.sin(angle) * CANDIDATE_RING_RADIUS);
            set.add(new BlockPos(x, base.getY(), z));
        }
        return set;
    }

    /** 移除某目标的 marker */
    public static void discardFor(ServerLevel level, UUID targetId) {
        for (Entity e : level.getEntities().getAll()) {
            if (e == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (e.getType() != EntityType.MARKER) continue;
            CompoundTag tag = e.getPersistentData();
            if (tag.hasUUID(TAG_TARGET) && targetId.equals(tag.getUUID(TAG_TARGET))) {
                e.discard();
            }
        }
    }

    /**
     * 清理孤儿 marker：**只判断「所属目标是否还存在」**（离线/未加载的目标也会被判为不存在）。
     * <p>不判断「目标是否还有存活僵尸」——四散结束后由 {@code settleDebtAtBurnTime} /
     * {@code forceClearAllHordes} 用 {@link #discardFor} 精确清理，不依赖这里兜底。
     */
    public static void sweepOrphans(ServerLevel level) {
        int removed = 0;
        for (Entity e : level.getEntities().getAll()) {
            if (e == null) continue;  // getAll() 在实体并发 discard 时可能返回 null
            if (e.getType() != EntityType.MARKER) continue;
            CompoundTag tag = e.getPersistentData();
            if (!tag.hasUUID(TAG_TARGET)) continue;
            UUID targetId = tag.getUUID(TAG_TARGET);
            if (HordeSpawner.findTarget(level, targetId) == null) {
                e.discard();
                removed++;
            }
        }
        if (removed > 0) {
            EnikZombietide.LOGGER.debug("清理 {} 个孤儿四散锚点", removed);
        }
    }

    /** 找 (x,z) 附近的地表可站方块：先向下找最多 4 格，再向上找最多 4 格 */
    private static BlockPos findGround(ServerLevel level, int x, int z, int refY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, refY, z);
        for (int dy = 0; dy <= 4; dy++) {
            pos.setY(refY - dy);
            BlockState below = level.getBlockState(pos.below());
            BlockState at = level.getBlockState(pos);
            if (at.isAir() && (below.isSolid() || below.isSolidRender(level, pos.below()))) {
                return pos.immutable();
            }
        }
        for (int dy = 1; dy <= 4; dy++) {
            pos.setY(refY + dy);
            BlockState below = level.getBlockState(pos.below());
            BlockState at = level.getBlockState(pos);
            if (at.isAir() && (below.isSolid() || below.isSolidRender(level, pos.below()))) {
                return pos.immutable();
            }
        }
        return null;
    }
}
