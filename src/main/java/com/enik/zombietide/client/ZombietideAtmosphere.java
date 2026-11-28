package com.enik.zombietide.client;

import com.enik.zombietide.EnikZombietide;
import com.enik.zombietide.config.ClientAtmosphereConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.UUID;

/**
 * 客户端氛围渲染（v0.2.70）：雾色、视距压缩、屏幕暗角、触发瞬间镜头下沉。
 * <p>
 * 状态来源全部是服务端已同步的权威值（{@link ZombietideHud#isHordeDayFor} /
 * {@link ZombietideHud#isWaveActiveFor}），客户端不自行推算。
 * <p>
 * 设计取向（压抑恐怖）：
 * <ul>
 *   <li><b>尸潮日白天</b>：雾色轻微偏冷，天空"不对劲"但说不出哪里不对</li>
 *   <li><b>波次中</b>：雾色更冷更暗 + 视距压到 96 格——看不见远处，大脑自己补最坏的东西</li>
 *   <li><b>暗角</b>：波次中常驻，血债越高越重</li>
 *   <li><b>镜头下沉</b>：触发瞬间 1 秒内轻微下沉再回位，默认关</li>
 * </ul>
 * 所有效果都可通过 {@link ClientAtmosphereConfig} 关闭；总开关关闭后一律不生效。
 * <p><b>注意</b>：本类只能通过 {@code @EventBusSubscriber(value = Dist.CLIENT)} 注册，
 * 绝不能在主类里引用（专用服务器会因加载客户端类而崩溃）。
 */
@EventBusSubscriber(modid = EnikZombietide.MODID, value = Dist.CLIENT)
public final class ZombietideAtmosphere {

    /** 触发瞬间镜头下沉持续时长（tick） */
    private static final int TRIGGER_SHAKE_TICKS = 20;
    /** 镜头下沉幅度（度），刻意做得极轻，避免晕动 */
    private static final float TRIGGER_SHAKE_DEGREES = 1.5F;

    private static int clientTicks = 0;
    private static int waveStartTick = -10000;
    private static boolean lastWaveActive = false;
    /** 触发闪光截止 tick（客户端 tick 计数），0 表示当前无闪光 */
    private static int flashUntilTick = 0;
    /** 尸潮预兆强度 0~1（v0.2.78，服务端同步）；黄昏前逐渐增强，触发后归零 */
    private static float omenStrength = 0.0F;
    /** 服务端下发的威胁目标值（0/1，v0.2.86） */
    private static float threatTarget = 0.0F;
    /** 客户端插值后的威胁强度（0~1），用于雾/视距/暗角淡入淡出 */
    private static float threatStrength = 0.0F;
    /** 威胁淡入淡出速度：每 tick 变化量（20 tick = 1 秒完成过渡） */
    private static final float THREAT_LERP_STEP = 0.05F;
    /** 闪光总时长（tick）：0.6 秒，短促不闹 */
    private static final int FLASH_TICKS = 12;

    private ZombietideAtmosphere() {
    }

    /** 收到服务端的尸潮触发闪光包：记录一个短促闪烁窗口。 */
    public static void onHordeFlash() {
        flashUntilTick = clientTicks + FLASH_TICKS;
    }

    /** 当前预兆强度（0~1），供 HUD 决定 DAY X 的暗红渐变（v0.2.80）。 */
    public static float omenStrength() {
        return omenStrength;
    }

    /** 收到服务端的预兆强度（0~1）。 */
    public static void onOmenReceived(float strength) {
        omenStrength = Math.max(0.0F, Math.min(1.0F, strength));
    }

    /** 收到服务端的威胁目标值（0/1）；实际强度由客户端逐 tick 插值，实现淡入淡出。 */
    public static void onThreatReceived(float strength) {
        threatTarget = Math.max(0.0F, Math.min(1.0F, strength));
    }

    /** 当前威胁强度（0~1，已插值），供雾/视距/暗角使用 */
    public static float threatStrength() {
        return threatStrength;
    }

    /** 断线/换世界时清掉氛围状态，避免重连后误用上一局的 waveStartTick。 */
    public static void reset() {
        clientTicks = 0;
        waveStartTick = -10000;
        lastWaveActive = false;
        flashUntilTick = 0;
        omenStrength = 0.0F;
        threatTarget = 0.0F;
        threatStrength = 0.0F;
    }

    /** 每客户端 tick 插值威胁强度（淡入淡出），并记录「威胁刚出现」供镜头下沉。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTicks++;
        // 威胁强度向目标值线性靠拢（20 tick 完成 0↔1 过渡）
        if (threatStrength < threatTarget) {
            threatStrength = Math.min(threatTarget, threatStrength + THREAT_LERP_STEP);
        } else if (threatStrength > threatTarget) {
            threatStrength = Math.max(threatTarget, threatStrength - THREAT_LERP_STEP);
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            lastWaveActive = false;
            return;
        }
        // 镜头下沉触发源改为威胁从无到有（替代原 waveActive 上升沿）
        boolean active = threatTarget > 0.5F;
        if (active && !lastWaveActive) {
            waveStartTick = clientTicks;
        }
        lastWaveActive = active;
    }

    /**
     * 【v0.2.75】氛围效果的维度守卫：只在主世界生效。
     * <p>服务端同步的 waveActive 在换维度时保留（reconcileStaleWaves 会跳过非主世界玩家
     * 且不主动清 waveActive），所以下界/末地玩家的 syncedWaveActive 仍可能是 true。
     * 但音频侧（HordeBgmScheduler）已有 OVERWORLD 判断、进下界即停——若视觉不跟着停，
     * 就会出现「音频停了、雾和暗角还在」的音画不一致，甚至覆盖下界原生雾。
     * 与音频侧口径保持一致：只认主世界。
     */
    private static boolean inOverworld(Minecraft mc) {
        return mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.OVERWORLD;
    }

    /** 雾色：尸潮日轻微偏冷，波次中更冷更暗。 */
    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!ClientAtmosphereConfig.fogEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!inOverworld(mc)) return;
        UUID id = mc.player.getUUID();
        boolean hordeDay = ZombietideHud.isHordeDayFor(id);
        // v0.2.86：波次判定改为「被尸潮僵尸锁定」的插值强度（带淡入淡出）
        float threat = threatStrength;
        if (threat <= 0.0F && !hordeDay && omenStrength <= 0.0F) return;

        // 压蓝绿、留一点红，偏"脏"的冷灰；锁定中压得更狠
        float r = event.getRed();
        float g = event.getGreen();
        float b = event.getBlue();
        if (threat > 0.0F) {
            // 预兆/尸潮日（0.90）→ 锁定（0.70）按 threat 插值
            event.setRed(r * (0.90F - 0.20F * threat));
            event.setGreen(g * (0.90F - 0.18F * threat));
            event.setBlue(b * (0.93F - 0.13F * threat));
        } else if (hordeDay) {
            event.setRed(r * 0.90F);
            event.setGreen(g * 0.90F);
            event.setBlue(b * 0.93F);
        } else {
            // 预兆强度 0~1：从几乎无变化（1.0）渐变到尸潮日的偏冷（0.90/0.93）
            event.setRed(r * (1.0F - 0.10F * omenStrength));
            event.setGreen(g * (1.0F - 0.10F * omenStrength));
            event.setBlue(b * (1.0F - 0.07F * omenStrength));
        }
    }

    /** 视距压缩：波次中把地形雾拉到 96 格，制造"看不见远处"的压迫。 */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!ClientAtmosphereConfig.fogEnabled()) return;
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!inOverworld(mc)) return;
        if (threatStrength <= 0.0F) return;

        // 视距随威胁强度渐变：正常视距 → 96 格（逐帧插值，淡入淡出免费）
        float originalFar = event.getFarPlaneDistance();
        event.setNearPlaneDistance(8.0F);
        event.setFarPlaneDistance(originalFar + (96.0F - originalFar) * threatStrength);
        event.setCanceled(true);
    }

    /** 触发瞬间的镜头轻微下沉，1 秒内正弦回位。 */
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!ClientAtmosphereConfig.cameraShakeEnabled()) return;
        int since = clientTicks - waveStartTick;
        if (since < 0 || since > TRIGGER_SHAKE_TICKS) return;
        float t = since / (float) TRIGGER_SHAKE_TICKS;
        float offset = (float) Math.sin(t * Math.PI) * TRIGGER_SHAKE_DEGREES;
        event.setPitch(event.getPitch() + offset);
    }

    /**
     * 屏幕暗角：波次中常驻，血债越高越重。
     * <p>用 {@code RenderGuiEvent.Pre} 而不是 Post——这样暗角压在世界之上、HUD 之下，
     * 不会把 DAY X 和面板文字一起压暗。
     */
    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        // 【v0.2.75】主世界守卫：与音频侧口径一致，下界/末地不显示任何尸潮氛围
        // （包括触发闪烁——触发后立刻进传送门也不该在下界看到红屏）。
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !inOverworld(mc)) return;

        var g = event.getGuiGraphics();
        int w = g.guiWidth();
        int h = g.guiHeight();

        // 触发瞬间的暗红闪烁：画在 HUD 之下（Pre），不会遮住文字。
        // 用 alpha 从强到弱线性衰减，0.6 秒内结束；这是"火光抖一下"的视觉替代（不改 gamma/shader）。
        if (flashUntilTick > clientTicks && ClientAtmosphereConfig.atmosphereEnabled()) {
            int remain = flashUntilTick - clientTicks;
            float progress = remain / (float) FLASH_TICKS;   // 1 → 0
            int flashAlpha = ((int) (progress * 46.0F) & 0xFF) << 24;   // 峰值 46/255 ≈ 0.18，短促不刺眼
            // 暗红色 RGB(60,0,0)=0x3C0000 与高位 alpha 按位或 → ARGB 0xAARRGGBB
            g.fill(0, 0, w, h, flashAlpha | 0x3C0000);
        }

        if (!ClientAtmosphereConfig.vignetteEnabled()) return;
        if (mc.options.hideGui) return;
        if (threatStrength <= 0.0F) return;

        int debt = ZombietideHud.bloodDebtFor(mc.player.getUUID());
        // v0.2.72：浓度 0.14 基础 + 0.11 上限（v0.2.86 起再乘威胁强度，实现淡入淡出）
        float strength = (0.14F + Math.min(0.11F, debt * 0.002F)) * threatStrength;
        int alpha = ((int) (strength * 255.0F) & 0xFF) << 24;

        // 只用上下两条水平渐变压暗画面，且把高度从 h*0.18 收到 h*0.12：
        // 渐变越窄，HUD 背后受影响的行数越少，中段画面保持通透。
        int edge = Math.max(8, (int) (h * 0.12F));

        // fillGradient 内部自己管理 blend，这里不需要手动 enableBlend（手动开启后没恢复会污染后续渲染）。
        g.fillGradient(0, 0, w, edge, alpha, 0x00000000);                 // 上
        g.fillGradient(0, h - edge, w, h, 0x00000000, alpha);             // 下
    }
}
