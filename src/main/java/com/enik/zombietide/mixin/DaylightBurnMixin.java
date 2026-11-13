package com.enik.zombietide.mixin;

import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.ZombietideConfigData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 白天燃烧配置 Mixin：使 config.daylightBurn() 真正控制僵尸是否在白天燃烧。
 * <p>
 * 原版 {@link Mob#isSunSensitive()} 为 protected，僵尸恒返回 true，导致
 * config.daylightBurn() 关闭后僵尸仍会被太阳烧死（R1-01）。
 * <p>
 * 本 Mixin 通过 {@link Redirect} 拦截 {@code isSunSensitive()} 的返回值：
 * 当配置中 daylightBurn=false 时返回 false，僵尸白天不再燃烧；
 * 否则保持原版行为。
 */
@Mixin(Mob.class)
public class DaylightBurnMixin {

    /**
     * 重定向 Mob.isSunSensitive() 的返回值，使其受配置 daylightBurn 控制。
     *
     * @param instance  Mixin 目标实体（Mob）
     * @param original  原版返回值
     * @return 配置允许燃烧时返回 original，否则返回 false
     */
    @Redirect(
        method = "isSunSensitive",
        at = @At(value = "RETURN")
    )
    private boolean zombietide$daylightBurn(Mob instance, boolean original) {
        if (instance.level() instanceof ServerLevel level) {
            ZombietideConfigData data = ConfigManager.getConfigData(level);
            return data.getConfig().daylightBurn() && original;
        }
        return original;
    }
}
