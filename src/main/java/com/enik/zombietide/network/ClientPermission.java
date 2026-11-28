package com.enik.zombietide.network;

/**
 * 客户端当前玩家的服务端权限（v0.2.137）。
 * <p>客户端没有 {@code ServerPlayer} 的权限等级字段，必须由服务端单独下发
 * （{@link ModMessages.PermissionPayload}）。**不能塞进 ConfigToClientPayload**
 * ——那是广播给全服的，而权限是每人不同的。
 * <p>判定口径与 {@code ConfigToServerPayload} 的服务端校验完全一致：
 * 单机房主 或 OP 2+ 才能改存档级配置；其余玩家只能改本机 HUD / 氛围。
 * <p>断线/换世界时调 {@link #reset()}，避免把上一台服务器的权限带过来。
 */
public final class ClientPermission {

    /** 服务端权限等级（0~4），0 = 普通玩家 */
    private static int level = 0;
    /** 是否为单机房主（存档宿主），等同于最高权限 */
    private static boolean host = false;
    /** 是否已收到过服务端下发的权限包（未收到 = 按普通玩家处理，保守） */
    private static boolean received = false;

    private ClientPermission() {
    }

    /** 服务端下发时调用 */
    public static void set(int permissionLevel, boolean isHost) {
        level = Math.max(0, Math.min(4, permissionLevel));
        host = isHost;
        received = true;
    }

    public static void reset() {
        level = 0;
        host = false;
        received = false;
    }

    public static int level() {
        return level;
    }

    public static boolean isHost() {
        return host;
    }

    public static boolean received() {
        return received;
    }

    /** 能否修改存档级（世界级）配置 */
    public static boolean canModifyWorldConfig() {
        return host || level >= 2;
    }

    /** 左上角的权限显示文字 */
    public static String label() {
        if (host) return "§c[房主]";
        if (level >= 4) return "§c[OP 4]";
        if (level >= 3) return "§c[OP 3]";
        if (level >= 2) return "§c[OP 2]";
        if (level == 1) return "§7[OP 1]";
        return "§7[普通玩家]";
    }
}
