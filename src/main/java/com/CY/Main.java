package com.CY;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main extends JavaPlugin implements Listener, CommandExecutor {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("cy").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    // --- 1. 大白话存储规则解析器 ---
    // 从配置文件提取计分板名称。比如配置写："存储在名为VipDays的计分板里" -> 提取 VipDays
    private String getDbName(String path, String def) {
        String line = getConfig().getString(path, "");
        if (line.isEmpty()) return def;
        Matcher m = Pattern.compile("(?i)(?<=名为|obj:|scoreboard:)\\s*(\\w+)").matcher(line);
        return m.find() ? m.group(1) : def;
    }

    // 获取玩家计分板数值
    private int getScore(Player p, String configPath, String defaultObj) {
        String objName = getDbName(configPath, defaultObj);
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective obj = sb.getObjective(objName);
        return (obj == null) ? 0 : obj.getScore(p.getName()).getScore();
    }

    // --- 2. 语义时间解析 (14天封顶) ---
    private int parseAlertDays(String raw) {
        String s = raw.toLowerCase();
        int days = 0;
        if (s.contains("两周")) days = 14;
        else if (s.contains("一星")) days = 7;
        else {
            Matcher m = Pattern.compile("\\d+").matcher(s);
            if (m.find()) days = Integer.parseInt(m.group());
        }
        return Math.min(days, 14); // 源码硬锁14天
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getView().getTitle().contains("云背包")) {
            Player p = (Player) e.getPlayer();
            // 从配置定义的计分板读取剩余天数
            int remainingDays = getScore(p, "storage.vip-days-rule", "VipDays");
            int alertDays = parseAlertDays(getConfig().getString("settings.alert-before", "7天"));

            if (remainingDays <= alertDays && remainingDays > 0) {
                p.sendMessage("§e[提醒] §f云背包会员仅剩 §c" + remainingDays + " §f天，请及时续费。");
            } else if (remainingDays <= 0) {
                p.sendMessage("§4[锁定] §f会员已到期，非免费格子已进入 §c只读模式§f！");
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().contains("云背包")) return;

        Player p = (Player) e.getWhoClicked();
        int rawSlot = e.getRawSlot();
        // 提取页码
        int page = 1;
        try { page = Integer.parseInt(e.getView().getTitle().replaceAll("[^0-9]", "")); } catch (Exception ignored) {}

        int absoluteSlot = (page - 1) * 45 + rawSlot + 1;

        // A. 导航翻页逻辑
        if (rawSlot >= 45 && rawSlot <= 53) {
            e.setCancelled(true);
            if (rawSlot == 48 && page > 1) openCloudInventory(p, page - 1);
            if (rawSlot == 50) openCloudInventory(p, page + 1);
            return;
        }

        // B. 存储与只读逻辑
        if (rawSlot < 45) {
            int unlockedMax = getScore(p, "storage.unlocked-slots-rule", "UnlockedSlots");
            if (unlockedMax < 54) unlockedMax = 54; // 基础免费54格

            // 1. 越权点击判定
            if (absoluteSlot > unlockedMax) {
                p.sendMessage("§c[锁定] §f该格子尚未解锁。");
                e.setCancelled(true);
                return;
            }

            // 2. 过期只读判定 (只对 54格以后的收费格生效)
            if (absoluteSlot > 54) {
                int days = getScore(p, "storage.vip-days-rule", "VipDays");
                if (days <= 0) {
                    // 只读：允许拿起(PICKUP)，禁止放入(PLACE/SWAP)
                    if (e.getCursor() != null && e.getCursor().getType() != Material.AIR) {
                        p.sendMessage("§c[只读] §f会员已过期，收费格子禁止存入！");
                        e.setCancelled(true);
                        return;
                    }
                }
            }

            // 3. 空间耗尽预警 (针对已解锁上限的最后1格)
            checkSpaceWarning(p, e.getInventory(), unlockedMax);
        }
    }

    private void checkSpaceWarning(Player p, Inventory inv, int max) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int count = 0;
            for (int i = 0; i < 45; i++) {
                if (inv.getItem(i) != null && inv.getItem(i).getType() != Material.AIR) count++;
            }
            if (count >= (max - 1) || (max <= 54 && count >= 53)) {
                p.sendMessage("§6[预警] §f存储空间不足，请及时清理或扩容。");
            }
        }, 1L);
    }

    private void openCloudInventory(Player p, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, "§0云背包 - 第 " + page + " 页");

        // 填充导航栏
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta(); gm.setDisplayName(" "); glass.setItemMeta(gm);
        for (int i = 45; i < 54; i++) inv.setItem(i, glass);

        ItemStack prev = new ItemStack(Material.ARROW);
        ItemMeta pm = prev.getItemMeta(); pm.setDisplayName("§e上一页 (Page " + (page-1) + ")"); prev.setItemMeta(pm);

        ItemStack next = new ItemStack(Material.ARROW);
        ItemMeta nm = next.getItemMeta(); nm.setDisplayName("§6下一页 (Page " + (page+1) + ")"); next.setItemMeta(nm);

        if (page > 1) inv.setItem(48, prev);
        inv.setItem(50, next);

        p.openInventory(inv);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player p && args.length == 0) openCloudInventory(p, 1);
        return true;
    }
}