package com.CY;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.*;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;
import java.io.*;
import java.sql.*;
import java.util.*;

public class Main extends JavaPlugin implements Listener, CommandExecutor {
    private static Connection db;

    @Override
    public void onEnable() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "storage.db"));
            Statement st = db.createStatement();

            // 爆破式表结构修复
            try {
                st.executeQuery("SELECT page FROM v LIMIT 1");
            } catch (SQLException e) {
                st.execute("DROP TABLE IF EXISTS v");
                Bukkit.getConsoleSender().sendMessage("§e[CY] 数据库格式过旧，已重置表结构。");
            }
            st.execute("CREATE TABLE IF NOT EXISTS v (p TEXT, page INT, slot INT, data TEXT)");
            Bukkit.getConsoleSender().sendMessage("§a[CY] 仓库系统已就绪，等待主控握手信号。");
        } catch (Exception e) { e.printStackTrace(); }
        getCommand("cy").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (s instanceof Player) openPage((Player) s, 0);
        return true;
    }

    public void openPage(Player p, int page) {
        p.setMetadata("CY_PAGE", new FixedMetadataValue(this, page));
        p.setMetadata("CY_LOCK", new FixedMetadataValue(this, System.currentTimeMillis()));

        Inventory inv = Bukkit.createInventory(null, 54, "§0[云端仓库] 第 " + (page + 1) + " 页");
        for (int i = 45; i < 54; i++) inv.setItem(i, btn(Material.GRAY_STAINED_GLASS_PANE, " ", null));
        inv.setItem(45, btn(Material.ARROW, "§f上一页", null));
        inv.setItem(49, btn(Material.EMERALD, "§a§l商城与统计", "§7当前存量: §e" + count(p, page) + " / 40"));
        inv.setItem(53, btn(Material.ARROW, "§f下一页", null));

        try (PreparedStatement ps = db.prepareStatement("SELECT slot, data FROM v WHERE p = ? AND page = ?")) {
            ps.setString(1, p.getName()); ps.setInt(2, page);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) inv.setItem(rs.getInt("slot"), deserialize(rs.getString("data")));
        } catch (Exception ignored) {}
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getClickedInventory() == null || !e.getView().getTitle().contains("[云端仓库]")) return;
        Player p = (Player) e.getWhoClicked();
        int slot = e.getRawSlot();

        if (p.hasMetadata("CY_LOCK") && System.currentTimeMillis() - p.getMetadata("CY_LOCK").get(0).asLong() < 400) {
            e.setCancelled(true); return;
        }

        if (slot >= 45 && slot <= 53) {
            e.setCancelled(true);
            int page = p.getMetadata("CY_PAGE").get(0).asInt();
            if (slot == 45 && page > 0) { save(p, page, e.getInventory()); openPage(p, page - 1); }
            if (slot == 53) {
                save(p, page, e.getInventory());
                // 翻页限制：前三页必须满 40 格
                if (page < 2 || count(p, page) >= 40) openPage(p, page + 1);
                else p.sendMessage("§c§l[拒绝] §7当前仓库分拣度不足 40 格。");
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getView().getTitle().contains("[云端仓库]") && e.getPlayer().hasMetadata("CY_PAGE")) {
            save((Player) e.getPlayer(), e.getPlayer().getMetadata("CY_PAGE").get(0).asInt(), e.getInventory());
        }
    }

    private void save(Player p, int page, Inventory inv) {
        try {
            PreparedStatement del = db.prepareStatement("DELETE FROM v WHERE p = ? AND page = ?");
            del.setString(1, p.getName()); del.setInt(2, page); del.executeUpdate();
            PreparedStatement ins = db.prepareStatement("INSERT INTO v VALUES (?,?,?,?)");
            for (int i = 0; i < 45; i++) {
                ItemStack it = inv.getItem(i);
                if (it != null && it.getType() != Material.AIR) {
                    ins.setString(1, p.getName()); ins.setInt(2, page);
                    ins.setInt(3, i); ins.setString(4, serialize(it));
                    ins.executeUpdate();
                }
            }
        } catch (Exception ignored) {}
    }

    private int count(Player p, int page) {
        try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM v WHERE p=? AND page=?")) {
            ps.setString(1, p.getName()); ps.setInt(2, page);
            ResultSet rs = ps.executeQuery(); return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) { return 0; }
    }

    // --- 反射握手入口 ---
    public static void apiReceive(String n, int s, int d) {
        Bukkit.getConsoleSender().sendMessage("§6[CY-Memory] 收到来自主控的扩容指令!");
        Bukkit.getConsoleSender().sendMessage("§7- 玩家: " + n + " | 增加格子: " + s + " | 增加时长: " + d);
        // 这里对接具体的扩容逻辑
    }

    private String serialize(ItemStack item) {
        try (ByteArrayOutputStream o = new ByteArrayOutputStream(); BukkitObjectOutputStream d = new BukkitObjectOutputStream(o)) {
            d.writeObject(item); return Base64Coder.encodeLines(o.toByteArray());
        } catch (Exception e) { return ""; }
    }

    private ItemStack deserialize(String b64) {
        try (ByteArrayInputStream i = new ByteArrayInputStream(Base64Coder.decodeLines(b64)); BukkitObjectInputStream d = new BukkitObjectInputStream(i)) {
            return (ItemStack) d.readObject();
        } catch (Exception e) { return null; }
    }

    private ItemStack btn(Material m, String n, String lore) {
        ItemStack item = new ItemStack(m); ItemMeta mt = item.getItemMeta();
        mt.setDisplayName(n); if (lore != null) mt.setLore(Arrays.asList(lore.split("\n")));
        item.setItemMeta(mt); return item;
    }
}