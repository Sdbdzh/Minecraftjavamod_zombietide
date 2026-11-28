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

    /** 【v0.2.91】DAY X 是否按「距下次尸潮预兆期」渐进变红。默认开；关闭回退旧的二态（尸潮日红/其余白）。
     *  <p>独立于 {@link ZombietideConfig.HudConfig}：纯本机显示偏好，不进网络协议、不动 Codec。 */
    private static boolean dayGradientRed = true;

    /** 【v0.2.138】DAY X 次行是否显示血债（默认开；纯本机，不进协议） */
    private static boolean showDebt = true;
    /** 【v0.2.138】DAY X 次行是否显示存活尸潮僵尸数（默认开；纯本机，不进协议） */
    private static boolean showAliveCount = true;

    private ClientHudConfig() {
    }

    /** DAY X 渐进红开关（默认 true）。 */
    public static synchronized boolean dayGradientRed() {
        get();   // 确保已从磁盘加载
        return dayGradientRed;
    }

    /** 设置并持久化 DAY X 渐进红开关。 */
    public static synchronized void setDayGradientRed(boolean v) {
        get();               // 先确保已从磁盘加载，否则 load() 会把刚设的值冲掉
        dayGradientRed = v;
        save(cached);
    }

    /** DAY X 次行：显示血债（默认 true）。 */
    public static synchronized boolean showDebt() {
        get();
        return showDebt;
    }

    public static synchronized void setShowDebt(boolean v) {
        get();
        showDebt = v;
        save(cached);
    }

    /** DAY X 次行：显示存活尸潮僵尸数（默认 true）。 */
    public static synchronized boolean showAliveCount() {
        get();
        return showAliveCount;
    }

    public static synchronized void setShowAliveCount(boolean v) {
        get();
        showAliveCount = v;
        save(cached);
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
        dayGradientRed = bool(p, "dayGradientRed", true);
        showDebt = bool(p, "showDebt", true);
        showAliveCount = bool(p, "showAliveCount", true);
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
        p.setProperty("dayGradientRed", String.valueOf(dayGradientRed));
        p.setProperty("showDebt", String.valueOf(showDebt));
        p.setProperty("showAliveCount", String.valueOf(showAliveCount));
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
