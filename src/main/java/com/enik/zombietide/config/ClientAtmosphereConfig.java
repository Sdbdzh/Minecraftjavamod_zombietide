package com.enik.zombietide.config;

import com.enik.zombietide.EnikZombietide;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 客户端氛围效果开关（v0.2.70）。
 * <p>
 * 独立于 {@link ClientHudConfig}、也**不参与网络同步**——这些是纯客户端视觉表现，
 * 每个玩家敏感度不同，不该由服务端强制。改 {@code HudConfig} record 会牵动
 * {@code ModMessages} 的跨端 payload，风险大；这里用独立的 properties 文件，零协议改动。
 * <p>
 * 文件：{@code config/enik_zombietide-atmosphere.properties}
 */
public final class ClientAtmosphereConfig {

    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("enik_zombietide-atmosphere.properties");

    /** 氛围效果总开关：关掉后雾、抖动全部不生效 */
    private static boolean atmosphereEnabled = true;
    /** 尸潮日/波次中的雾色与视距变化 */
    private static boolean fogEnabled = true;
    /** 触发瞬间的镜头轻微下沉（默认关：3D 敏感玩家较多） */
    private static boolean cameraShakeEnabled = false;

    private static boolean loaded = false;

    private ClientAtmosphereConfig() {
    }

    public static boolean atmosphereEnabled() {
        ensureLoaded();
        return atmosphereEnabled;
    }

    public static boolean fogEnabled() {
        ensureLoaded();
        return atmosphereEnabled && fogEnabled;
    }

    public static boolean cameraShakeEnabled() {
        ensureLoaded();
        return atmosphereEnabled && cameraShakeEnabled;
    }

    /**
     * 【设置界面专用】返回原始存储值，**不带总开关 AND**。
     * <p>v0.2.74 修复：设置页总开关回调若用 {@link #fogEnabled()} 这类派生 getter，
     * 在总开关关闭期间取值会全是 false，重新开启总开关时会把子项全部写成 false 并落盘。
     * 设置界面保存 3 个开关时必须用这 3 个 raw getter。
     */
    public static boolean rawFogEnabled() {
        ensureLoaded();
        return fogEnabled;
    }

    /** 见 {@link #rawFogEnabled()}：设置界面保存用的原始值 */
    public static boolean rawCameraShakeEnabled() {
        ensureLoaded();
        return cameraShakeEnabled;
    }

    public static synchronized void set(boolean atmosphere, boolean fog, boolean shake) {
        ensureLoaded();
        atmosphereEnabled = atmosphere;
        fogEnabled = fog;
        cameraShakeEnabled = shake;
        save();
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            EnikZombietide.LOGGER.warn("[Zombietide] 读取氛围配置失败，使用默认值: {}", e.toString());
            return;
        }
        atmosphereEnabled = bool(p, "atmosphereEnabled", true);
        fogEnabled = bool(p, "fogEnabled", true);
        cameraShakeEnabled = bool(p, "cameraShakeEnabled", false);
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("atmosphereEnabled", String.valueOf(atmosphereEnabled));
        p.setProperty("fogEnabled", String.valueOf(fogEnabled));
        p.setProperty("cameraShakeEnabled", String.valueOf(cameraShakeEnabled));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Zombietide client atmosphere settings (global, not per-world)");
            }
        } catch (IOException e) {
            EnikZombietide.LOGGER.warn("[Zombietide] 保存氛围配置失败: {}", e.toString());
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }
}
