package com.enik.zombietide.mixin;

import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.ZombietideConfigData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Zombie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 白天燃烧配置 Mixin：让 config.daylightBurn() 真正控制【尸潮僵尸】是否会在白天被阳光点燃。
 * <p>
 * {@code isSunSensitive()} 在 1.21.1 中**只声明于 {@link Zombie} 与 {@link Husk}**，
 * {@link Mob} 上只有 {@code isSunBurnTick()}。因此必须注入这两个具体类：
 * <ul>
 *   <li>{@link Zombie} 覆盖 Zombie / Drowned / ZombieVillager（后两者继承且未覆写）；</li>
 *   <li>{@link Husk} 覆写了该方法，需单独注入。</li>
 * </ul>
 * 在方法返回处改写返回值：
 * <ul>
 *   <li>对**所有僵尸变体**生效（Zombie / Drowned / ZombieVillager 走 {@link Zombie}，{@link Husk} 单独注入），
 *       包括自然生成的僵尸，不再只限尸潮僵尸；</li>
 *   <li>当 daylightBurn=false 时返回 false（白天不燃烧）；</li>
 *   <li>骷髅、僵尸猪灵等其它实体的燃烧行为不受影响。</li>
 * </ul>
 * <p>
 * 注意：必须用 {@code @Inject(RETURN, cancellable=true)} + {@link CallbackInfoReturnable#setReturnValue}
 * 才能改写方法自身的返回值；{@code @Redirect} 无法在 RETURN 处拦截方法自身返回。
 */
@Mixin({Zombie.class, Husk.class})
public abstract class DaylightBurnMixin {

    @Inject(method = "isSunSensitive", at = @At("RETURN"), cancellable = true)
    private void zombietide$daylightBurn(CallbackInfoReturnable<Boolean> cir) {
        Mob self = (Mob) (Object) this;
        // 燃烧判定只在服务端推进；客户端无 ServerLevel，走原版逻辑
        if (self.level() instanceof ServerLevel level) {
            ZombietideConfigData data = ConfigManager.getConfigData(level);
            if (!data.getConfig().daylightBurn()) {
                cir.setReturnValue(false);
            }
        }
    }
}
