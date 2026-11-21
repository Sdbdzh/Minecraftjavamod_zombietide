package com.enik.zombietide.config;

import com.enik.zombietide.EnikZombietide;
import net.minecraft.server.level.ServerLevel;

/**
 * 配置管理器：负责读取、保存、应用配置。
 * 配置持久化到世界级 SavedData（Level Data），不同存档独立，随存档存放。
 */
public class ConfigManager {

    /**
     * 从服务端 Level Data 中加载 ZombietideConfigData（及其配置）。
     * SavedData 通过 computeIfAbsent 加载，首次访问时若不存在则创建默认实例。
     */
    public static ZombietideConfigData getConfigData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                ZombietideConfigData.FACTORY,
                ZombietideConfigData.ID
        );
    }

    /**
     * 应用远程配置（客户端按键打开的设置界面修改后收到的 payload）。
     * 修改后将配置写入 Level Data 并标记脏，随存档持久化；同时广播给所有在线客户端。
     */
    public static void applyRemoteConfig(ZombietideConfig newConfig, ServerLevel level) {
        if (level != null) {
            ZombietideConfigData data = getConfigData(level);
            data.setConfig(newConfig);
            EnikZombietide.LOGGER.info("Zombietide config updated via remote config sync (Level Data)");
        } else {
            EnikZombietide.LOGGER.info("Zombietide config received on client (no server level)");
        }
    }

    /**
     * 【v0.1.61 修复 #138】客户端收到服务端广播配置时的钩子。
     * <p><b>当前实现只写一条日志，不刷新任何 HUD 数据、不持久化。</b>
     * HUD 的实际数值来自服务端 {@code PlayerModDayPayload} 每秒同步（见 {@code ZombietideHud.onModDayReceived}）；
     * 玩法参数由服务端在 {@code HordeTimer} / {@code HordeSpawner} 里读取世界级配置。
     */
    public static void applyClientConfig(ZombietideConfig newConfig) {
        EnikZombietide.LOGGER.info("Zombietide client config refreshed from server");
    }

    /** 获取客户端缓存的配置（用于设置界面初始化） */
    public static ZombietideConfig getClientConfig() {
        return com.enik.zombietide.client.ZombietideHud.getClientConfig();
    }
}