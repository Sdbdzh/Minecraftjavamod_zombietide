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

    /** 氛围效果总开关：关掉后雾、暗角、抖动全部不生效 */
    private static boolean atmosphereEnabled = true;
    /** 尸潮日/波次中的雾色与视距变化 */
    private static boolean fogEnabled = true;
    /** 波次中的屏幕暗角 */
    private static boolean vignetteEnabled = true;
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

    public static boolean vignetteEnabled() {
        ensureLoaded();
        return atmosphereEnabled && vignetteEnabled;
    }

    public static boolean cameraShakeEnabled() {
        ensureLoaded();
        return atmosphereEnabled && cameraShakeEnabled;
    }

    public static synchronized void set(boolean atmosphere, boolean fog, boolean vignette, boolean shake) {
        ensureLoaded();
        atmosphereEnabled = atmosphere;
        fogEnabled = fog;
        vignetteEnabled = vignette;
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
        vignetteEnabled = bool(p, "vignetteEnabled", true);
        cameraShakeEnabled = bool(p, "cameraShakeEnabled", false);
    }

    private static void save() {
        Properties p = new Properties();
        p.setProperty("atmosphereEnabled", String.valueOf(atmosphereEnabled));
        p.setProperty("fogEnabled", String.valueOf(fogEnabled));
        p.setProperty("vignetteEnabled", String.valueOf(vignetteEnabled));
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
