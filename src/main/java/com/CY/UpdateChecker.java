package com.CY;

import org.bukkit.command.CommandSender;

/**
 * CY_beibao 更新检查包装类
 * 供集群更新调用
 */
public class UpdateChecker {
    private final Main plugin;

    public UpdateChecker(Main plugin) {
        this.plugin = plugin;
    }

    public void checkUpdate(CommandSender sender) {
        plugin.checkUpdate(sender);
    }
}
