package com.cy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;
import java.io.*;
import java.sql.*;
import java.util.*;

public class Main extends JavaPlugin implements Listener, CommandExecutor {
    private Connection conn;
    private final NamespacedKey PAGE_KEY = new NamespacedKey(this, "cy_page");

    @Override
    public void onEnable() {
        getLogger().info("\n§d" +
                "  _______     __ \n" +
                " / ___\\ \\   / / \n" +
                "| |    \\ \\_/ /  \n" +
                "| |___  \\   /   \n" +
                " \\____|  |_|    \n" +
                "§e[CY_beibao] 加载成功，存储数据库已挂载。");

        initDatabase();
        getCommand("cy").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void initDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            conn = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/vault.db");
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS items (uuid TEXT, page INTEGER, data TEXT, PRIMARY KEY(uuid, page))");
        } catch (Exception e) { e.printStackTrace(); }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String lb, String[] args) {
        if (s instanceof Player p) openVault(p, 1);
        return true;
    }

    public void openVault(Player p, int page) {
        p.getPersistentDataContainer().set(PAGE_KEY, PersistentDataType.INTEGER, page);
        Inventory inv = Bukkit.createInventory(null, 54, "§1云端仓库 - 第 " + page + " 页");

        // 从数据库加载物品
        loadItems(p, page, inv);

        // 功能底栏 (45-53)
        for (int i = 45; i < 54; i++) {
            ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta m = glass.getItemMeta(); m.setDisplayName(" "); glass.setItemMeta(m);
            inv.setItem(i, glass);
        }
        inv.setItem(48, createBtn(Material.ARROW, "§f上一页"));
        inv.setItem(49, createBtn(Material.BARRIER, "§c关闭菜单"));
        inv.setItem(50, createBtn(Material.ARROW, "§f下一页"));

        p.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!e.getView().getTitle().contains("云端仓库")) return;
        Player p = (Player) e.getPlayer();
        int page = p.getPersistentDataContainer().getOrDefault(PAGE_KEY, PersistentDataType.INTEGER, 1);
        saveItems(p, page, e.getInventory());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!e.getView().getTitle().contains("云端仓库")) return;
        int slot = e.getRawSlot();
        if (slot < 45 || slot >= 54) return; // 仓库和背包区允许存取

        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        int cur = p.getPersistentDataContainer().getOrDefault(PAGE_KEY, PersistentDataType.INTEGER, 1);

        if (slot == 49) p.closeInventory();
        if (slot == 50 || slot == 48) {
            int target = (slot == 50) ? cur + 1 : cur - 1;
            if (target > 0) {
                p.closeInventory();
                // 延迟 2 Tick 解决鼠标强制纠正
                Bukkit.getScheduler().runTaskLater(this, () -> openVault(p, target), 2L);
            }
        }
    }

    private void saveItems(Player p, int page, Inventory inv) {
        try {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            BukkitObjectOutputStream out = new BukkitObjectOutputStream(os);
            out.writeInt(45); // 只存前 45 格
            for (int i = 0; i < 45; i++) out.writeObject(inv.getItem(i));
            out.close();
            String data = Base64Coder.encodeLines(os.toByteArray());
            PreparedStatement ps = conn.prepareStatement("REPLACE INTO items (uuid, page, data) VALUES (?, ?, ?)");
            ps.setString(1, p.getUniqueId().toString()); ps.setInt(2, page); ps.setString(3, data);
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void loadItems(Player p, int page, Inventory inv) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT data FROM items WHERE uuid = ? AND page = ?");
            ps.setString(1, p.getUniqueId().toString()); ps.setInt(2, page);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                byte[] bytes = Base64Coder.decodeLines(rs.getString("data"));
                BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes));
                int count = in.readInt();
                for (int i = 0; i < count; i++) inv.setItem(i, (ItemStack) in.readObject());
                in.close();
            }
        } catch (Exception ignored) {}
    }

    private ItemStack createBtn(Material m, String n) {
        ItemStack i = new ItemStack(m); ItemMeta mt = i.getItemMeta();
        mt.setDisplayName(n); i.setItemMeta(mt); return i;
    }

    public void executeAction(Player p, String raw) {
        if (raw.contains("背包")) p.sendMessage("§a[CY] 空间联动成功！");
    }
}