package com.enik.zombietide.mixin;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.horde.HordeSpawner;
import com.enik.zombietide.horde.HordeZombieBehavior;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 【v0.2.110】僵尸转化（溺水变溺尸 / 村民僵尸等）时保留尸潮身份。
 * <p>
 * 原版 {@code Mob.convertTo(type, true)} 只复制位置/装备/属性，
 * <b>不复制 {@code getPersistentData()}</b>——尸潮僵尸的
 * {@code zombietide_horde_target} 等 NBT 标记丢失，变成"无标记溺尸"，
 * 导致血债结算与黎明清理都识别不到它（用户实测 BUG）。
 * <p>
 * 本 mixin 在 {@code convertToZombieType} 返回后执行：在原实体位置 4 格内
 * 找同类型、无尸潮标记的活僵尸（就是刚转化的那个），复制 NBT + 重新注入 AI + 重新追踪。
 */
@Mixin(Zombie.class)
public abstract class ZombieConversionMixin {

    @Inject(method = "convertToZombieType", at = @At("RETURN"))
    private void zombietide$preserveHordeIdentity(EntityType<? extends Zombie> type, CallbackInfo ci) {
        Zombie old = (Zombie) (Object) this;
        if (!(old.level() instanceof ServerLevel level)) return;
        CompoundTag oldTag = old.getPersistentData();
        if (!oldTag.hasUUID(HordeSpawner.targetKeyForMixin())) return;

        // convertTo 刚创建的新实体就在原位置附近、类型相同、还没有尸潮标记
        Zombie converted = null;
        for (var entity : level.getEntities().getAll()) {
            if (entity == null || entity == old || !entity.isAlive()) continue;
            if (entity.getType() != type) continue;
            if (entity.distanceToSqr(old) > 4.0) continue;
            if (entity.getPersistentData().hasUUID(HordeSpawner.targetKeyForMixin())) continue;
            converted = (Zombie) entity;
            break;
        }
        if (converted == null) return;

        // 复制尸潮 NBT 标记（convertTo 不复制 PersistentData）
        CompoundTag newTag = converted.getPersistentData();
        for (String key : HordeSpawner.hordeKeysForMixin()) {
            if (oldTag.contains(key)) newTag.put(key, oldTag.get(key));
        }

        // 重新注入尸潮 AI（转化后 goalSelector 是全新的）
        var config = ConfigManager.getConfigData(level).getConfig();
        HordeZombieBehavior.apply(converted, oldTag.getUUID(HordeSpawner.targetKeyForMixin()), config);

        // 重新纳入追踪集合（旧 UUID 已随实体 discard 移除）
        HordeSpawner.retrackConvertedZombie(level, converted.getUUID(), old.getUUID());

        EnikZombietide.LOGGER.info("[尸潮] 僵尸转化为 {}，已保留尸潮标记与 AI",
                type.getDescriptionId());
    }
}
