package com.enik.zombietide.mixin;

import com.enik.zombietide.config.ConfigManager;
import com.enik.zombietide.config.ZombietideConfigData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 白天燃烧配置 Mixin：让 config.daylightBurn() 真正控制【尸潮僵尸】是否会在白天被阳光点燃。
 * <p>
 * 原版 {@link Mob#isSunSensitive()} 为 protected，僵尸恒返回 true，因此 daylightBurn 关闭后
 * 尸潮僵尸仍会被太阳烧死。本 Mixin 在方法返回处改写返回值：
 * <ul>
 *   <li>仅对带 {@code zombietide_horde_target} 标记的尸潮僵尸生效；</li>
 *   <li>当 daylightBurn=false 时返回 false（白天不燃烧）；</li>
 *   <li>自然生成的僵尸/骷髅等其它实体保持原版行为，不受影响。</li>
 * </ul>
 * <p>
 * 注意：必须用 {@code @Inject(RETURN, cancellable=true)} + {@link CallbackInfoReturnable#setReturnValue}
 * 才能改写方法自身的返回值；{@code @Redirect} 无法在 RETURN 处拦截方法自身返回。
 */
@Mixin(Mob.class)
public abstract class DaylightBurnMixin {

    private static final String HORDE_TARGET_KEY = "zombietide_horde_target";

    @Inject(method = "isSunSensitive", at = @At("RETURN"), cancellable = true)
    private void zombietide$daylightBurn(CallbackInfoReturnable<Boolean> cir) {
        Mob self = (Mob) (Object) this;
        // 仅对尸潮僵尸生效：带目标标记 NBT
        CompoundTag tag = self.getPersistentData();
        if (!tag.contains(HORDE_TARGET_KEY)) return;
        // 燃烧判定只在服务端推进；客户端无 ServerLevel，走原版逻辑
        if (self.level() instanceof ServerLevel level) {
            ZombietideConfigData data = ConfigManager.getConfigData(level);
            if (!data.getConfig().daylightBurn()) {
                cir.setReturnValue(false);
            }
        }
    }
}
