package com.enik.zombietide.mixin;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.ConfigManager;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 白天燃烧配置 Mixin：让 config.daylightBurn() 控制僵尸是否会被阳光点燃。
 * <p>
 * {@code isSunSensitive()} 在 1.21.1 中**只声明于 {@link Zombie} 与 {@link Husk}**，
 * {@link Mob} 上只有 {@code isSunBurnTick()}。因此注入这两个具体类：
 * <ul>
 *   <li>{@link Zombie} 覆盖 Zombie / Drowned / ZombieVillager（后两者继承且未覆写）；</li>
 *   <li>{@link Husk} 覆写了该方法，需单独注入。</li>
 * </ul>
 * 用 MixinExtras 的 {@code @ModifyReturnValue} 改写返回值（比 {@code @Inject + setReturnValue} 更直接）：
 * <ul>
 *   <li>对**所有僵尸变体**生效，包括自然生成的僵尸；</li>
 *   <li>当 daylightBurn=false 时返回 false（白天不燃烧）；</li>
 *   <li>骷髅、僵尸猪灵等其它实体的燃烧行为不受影响。</li>
 * </ul>
 */
@Mixin({Zombie.class, Husk.class})
public abstract class DaylightBurnMixin {

    /** 一次性诊断日志：确认 mixin 真的被调用（排查「关了还烧」时用） */
    private static boolean zombietide$logged = false;

    @ModifyReturnValue(method = "isSunSensitive", at = @At("RETURN"))
    private boolean zombietide$daylightBurn(boolean original) {
        Mob self = (Mob) (Object) this;
        // 燃烧判定只在服务端推进；客户端无 ServerLevel，走原版逻辑
        if (self.level() instanceof ServerLevel level) {
            boolean enabled = ConfigManager.getConfigData(level).getConfig().daylightBurn();
            if (!zombietide$logged) {
                zombietide$logged = true;
                EnikZombietide.LOGGER.info("[Zombietide] DaylightBurnMixin 已生效（{}），daylightBurn={}",
                        self.getClass().getSimpleName(), enabled);
            }
            if (!enabled) return false;
        }
        return original;
    }
}
