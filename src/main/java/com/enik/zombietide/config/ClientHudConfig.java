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
 * 客户端全局 HUD 配置（不随存档保存，所有存档/服务器通用）。
 * <p>
 * HUD 的位置、缩放、粗体、村民列表等纯客户端展示项与玩法无关，
 * 没必要每个存档独立配置，因此单独存放在 {@code config/enik_zombietide-hud.properties}。
 * 玩法参数（间隔、数量、上限等）仍由存档级 {@link ZombietideConfig} 控制。
 */
public final class ClientHudConfig {

    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("enik_zombietide-hud.properties");

    private static ZombietideConfig.HudConfig cached = null;

    private ClientHudConfig() {
    }

    /** 获取本机 HUD 配置（首次访问时从磁盘加载，之后走内存缓存）。 */
    public static synchronized ZombietideConfig.HudConfig get() {
        if (cached == null) {
            cached = load();
        }
        return cached;
    }

    /** 写入并立即持久化到本机配置文件。 */
    public static synchronized void set(ZombietideConfig.HudConfig hud) {
        if (hud == null) return;
        cached = hud;
        save(hud);
    }

    private static ZombietideConfig.HudConfig load() {
        ZombietideConfig.HudConfig d = ZombietideConfig.HudConfig.defaults();
        if (!Files.exists(FILE)) {
            save(d);
            return d;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            EnikZombietide.LOGGER.warn("[Zombietide] 读取本机 HUD 配置失败，使用默认值: {}", e.toString());
            return d;
        }
        return new ZombietideConfig.HudConfig(
                bool(p, "hudEnabled", d.hudEnabled()),
                bool(p, "showVillagerHorde", d.showVillagerHorde()),
                p.getProperty("hudPosition", d.hudPosition()),
                intVal(p, "hudX", d.hudX()),
                intVal(p, "hudY", d.hudY()),
                intVal(p, "villagerHordeX", d.villagerHordeX()),
                intVal(p, "villagerHordeY", d.villagerHordeY()),
                floatVal(p, "hudScale", d.hudScale()),
                floatVal(p, "villagerHordeScale", d.villagerHordeScale()),
                bool(p, "hudBold", d.hudBold()),
                bool(p, "villagerHordeBold", d.villagerHordeBold()),
                intVal(p, "villagerHordeDisplayRange", d.villagerHordeDisplayRange()),
                intVal(p, "villagerHordeMaxEntries", d.villagerHordeMaxEntries())
        );
    }

    private static void save(ZombietideConfig.HudConfig hud) {
        Properties p = new Properties();
        p.setProperty("hudEnabled", String.valueOf(hud.hudEnabled()));
        p.setProperty("showVillagerHorde", String.valueOf(hud.showVillagerHorde()));
        p.setProperty("hudPosition", hud.hudPosition() == null ? "top_left" : hud.hudPosition());
        p.setProperty("hudX", String.valueOf(hud.hudX()));
        p.setProperty("hudY", String.valueOf(hud.hudY()));
        p.setProperty("villagerHordeX", String.valueOf(hud.villagerHordeX()));
        p.setProperty("villagerHordeY", String.valueOf(hud.villagerHordeY()));
        p.setProperty("hudScale", String.valueOf(hud.hudScale()));
        p.setProperty("villagerHordeScale", String.valueOf(hud.villagerHordeScale()));
        p.setProperty("hudBold", String.valueOf(hud.hudBold()));
        p.setProperty("villagerHordeBold", String.valueOf(hud.villagerHordeBold()));
        p.setProperty("villagerHordeDisplayRange", String.valueOf(hud.villagerHordeDisplayRange()));
        p.setProperty("villagerHordeMaxEntries", String.valueOf(hud.villagerHordeMaxEntries()));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Zombietide client HUD settings (global, not per-world)");
            }
        } catch (IOException e) {
            EnikZombietide.LOGGER.warn("[Zombietide] 保存本机 HUD 配置失败: {}", e.toString());
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static int intVal(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float floatVal(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
