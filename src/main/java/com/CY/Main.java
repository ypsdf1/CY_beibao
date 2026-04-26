package com.CY;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class Main extends JavaPlugin implements CommandExecutor, Listener {

    private static final int PAGE_SIZE = 45;
    private static final int UNLOCK_THRESHOLD = 40;
    private static final String MAIN_TITLE = "§6§l会员中心";
    private static final String INFO_TITLE = "§6§l会员信息";
    private static final String SHOP_TITLE = "§6§l商城";
    private static final String STORAGE_TITLE = "§6§l主仓";

    private Economy economy;
    private Connection db;
    private final List<ShopItem> shopItems = new ArrayList<ShopItem>();
    private final Map<UUID, Integer> storagePages = new HashMap<UUID, Integer>();
    private final Map<UUID, List<ItemStack>> storageCache = new HashMap<UUID, List<ItemStack>>();

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        setupEconomy();
        initDatabase();
        loadShopConfig();
        getCommand("cy").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CyPlugin loaded! shop=" + shopItems.size());
    }

    @Override
    public void onDisable() { try { if (db != null && !db.isClosed()) db.close(); } catch (Exception ignored) {} }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void initDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "members.db").getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS members (player_name TEXT PRIMARY KEY,permanent_slots INTEGER DEFAULT 0,membership_slots INTEGER DEFAULT 0,expire_time INTEGER DEFAULT 0,activated INTEGER DEFAULT 0,unlocked_pages INTEGER DEFAULT 1,data TEXT DEFAULT '')");
            try { st.execute("ALTER TABLE members ADD COLUMN permanent_slots INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { st.execute("ALTER TABLE members ADD COLUMN membership_slots INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { st.execute("ALTER TABLE members ADD COLUMN unlocked_pages INTEGER DEFAULT 1"); } catch (Exception ignored) {}
            st.close();
        } catch (Exception e) { getLogger().severe("SQLite failed: " + e.getMessage()); }
    }

    private Map<String, Object> getMember(String playerName) {
        Map<String, Object> r = new HashMap<String, Object>();
        if (db == null) return r;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT * FROM members WHERE player_name=?");
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                r.put("permanent_slots", rs.getInt("permanent_slots"));
                r.put("membership_slots", rs.getInt("membership_slots"));
                r.put("expire_time", rs.getLong("expire_time"));
                r.put("activated", rs.getLong("activated"));
                r.put("unlocked_pages", rs.getInt("unlocked_pages"));
                r.put("data", rs.getString("data"));
            }
            rs.close(); ps.close();
        } catch (SQLException e) { getLogger().log(Level.WARNING, "getMember failed", e); }
        return r;
    }

    private void upsertMember(String playerName, int addPerm, int addMem, int addDays) {
        if (db == null) return;
        long now = System.currentTimeMillis();
        try {
            Map<String, Object> old = getMember(playerName);
            boolean has = !old.isEmpty();
            int oldPerm = has ? ((Number) old.get("permanent_slots")).intValue() : 0;
            int oldMem = has ? ((Number) old.get("membership_slots")).intValue() : 0;
            long oldExpire = has ? ((Number) old.get("expire_time")).longValue() : 0L;
            long act = has ? ((Number) old.get("activated")).longValue() : now;
            int unlocked = has ? ((Number) old.get("unlocked_pages")).intValue() : 1;
            int newPerm = oldPerm + addPerm;
            int newMem = oldMem + addMem;
            long newExpire = addDays > 0 ? Math.max(now, oldExpire) + (long) addDays * 86400000L : oldExpire;
            if (has) {
                PreparedStatement ps = db.prepareStatement("UPDATE members SET permanent_slots=?,membership_slots=?,expire_time=?,activated=?,unlocked_pages=? WHERE player_name=?");
                ps.setInt(1, newPerm); ps.setInt(2, newMem); ps.setLong(3, newExpire); ps.setLong(4, act); ps.setInt(5, unlocked); ps.setString(6, playerName);
                ps.executeUpdate(); ps.close();
            } else {
                PreparedStatement ps = db.prepareStatement("INSERT INTO members (player_name,permanent_slots,membership_slots,expire_time,activated,unlocked_pages) VALUES(?,?,?,?,?,?)");
                ps.setString(1, playerName); ps.setInt(2, newPerm); ps.setInt(3, newMem); ps.setLong(4, newExpire); ps.setLong(5, act); ps.setInt(6, unlocked);
                ps.executeUpdate(); ps.close();
            }
        } catch (SQLException e) { getLogger().log(Level.WARNING, "upsert failed", e); }
    }

    private void updateUnlockedPages(String playerName, int pages) {
        if (db == null) return;
        try { PreparedStatement ps = db.prepareStatement("UPDATE members SET unlocked_pages=? WHERE player_name=?"); ps.setInt(1, pages); ps.setString(2, playerName); ps.executeUpdate(); ps.close(); } catch (SQLException e) {}
    }

    private boolean deleteMember(String playerName) {
        if (db == null) return false;
        try { PreparedStatement ps = db.prepareStatement("DELETE FROM members WHERE player_name=?"); ps.setString(1, playerName); boolean ok = ps.executeUpdate() > 0; ps.close(); return ok; } catch (SQLException e) { return false; }
    }

    private int getActiveSlots(Map<String, Object> m) {
        if (m.isEmpty()) return 0;
        int perm = ((Number) m.get("permanent_slots")).intValue();
        int mem = ((Number) m.get("membership_slots")).intValue();
        long expireTime = ((Number) m.get("expire_time")).longValue();
        return perm + ((expireTime == 0 || expireTime > System.currentTimeMillis()) ? mem : 0);
    }

    private int getTotalPages(int total) { return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE); }
    private int getPageUsable(int page, int total) { return Math.min(PAGE_SIZE, Math.max(0, total - (page - 1) * PAGE_SIZE)); }

    private int countPageItems(List<ItemStack> items, int page, int total) {
        int start = (page - 1) * PAGE_SIZE, usable = getPageUsable(page, total), count = 0;
        for (int i = 0; i < usable; i++) { int idx = start + i; if (idx < items.size() && items.get(idx) != null) count++; }
        return count;
    }

    private List<ItemStack> loadStorage(String playerName) {
        List<ItemStack> list = new ArrayList<ItemStack>();
        if (db == null) return list;
        try { PreparedStatement ps = db.prepareStatement("SELECT data FROM members WHERE player_name=?"); ps.setString(1, playerName); ResultSet rs = ps.executeQuery(); if (rs.next()) { String b64 = rs.getString("data"); if (b64 != null && !b64.isEmpty()) Collections.addAll(list, itemsFromBase64(b64)); } rs.close(); ps.close(); } catch (Exception e) {}
        return list;
    }

    private void saveStorage(String playerName, List<ItemStack> items) {
        if (db == null) return;
        try { String b64 = itemsToBase64(items.toArray(new ItemStack[0])); PreparedStatement ps = db.prepareStatement("UPDATE members SET data=? WHERE player_name=?"); ps.setString(1, b64); ps.setString(2, playerName); ps.executeUpdate(); ps.close(); } catch (Exception e) {}
    }

    public static String itemsToBase64(ItemStack[] items) throws IOException {
        try { ByteArrayOutputStream out = new ByteArrayOutputStream(); BukkitObjectOutputStream data = new BukkitObjectOutputStream(out); data.writeInt(items.length); for (ItemStack item : items) data.writeObject(item); data.close(); return Base64.getEncoder().encodeToString(out.toByteArray()); } catch (Exception e) { throw new IOException("serialize failed", e); }
    }

    public static ItemStack[] itemsFromBase64(String b64) throws IOException {
        try { ByteArrayInputStream in = new ByteArrayInputStream(Base64.getDecoder().decode(b64)); BukkitObjectInputStream data = new BukkitObjectInputStream(in); ItemStack[] items = new ItemStack[data.readInt()]; for (int i = 0; i < items.length; i++) items[i] = (ItemStack) data.readObject(); data.close(); return items; } catch (Exception e) { throw new IOException("deserialize failed", e); }
    }

    public boolean onSdf1Activation(String playerName, int slots, int days) {
        try { if (days > 0) upsertMember(playerName, 0, slots, days); else upsertMember(playerName, slots, 0, 0); return true; } catch (Exception e) { return false; }
    }

    private void loadShopConfig() {
        shopItems.clear();
        File f = findShopFile();
        if (f == null) { loadShopDefaults(); return; }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                String[] parts = line.split("\\|", 5);
                if (parts.length < 5) continue;
                try { shopItems.add(new ShopItem(parts[0].trim(), parts[1].trim(), Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()), Double.parseDouble(parts[4].trim()))); } catch (NumberFormatException ignored) {}
            }
            r.close();
            if (shopItems.isEmpty()) loadShopDefaults();
        } catch (IOException e) { loadShopDefaults(); }
    }

    private void loadShopDefaults() {
        shopItems.add(new ShopItem("vip1", "VIP-1天", 1, 1, 100));
        shopItems.add(new ShopItem("vip7", "VIP-7天", 7, 3, 500));
        shopItems.add(new ShopItem("vip30", "VIP-30天", 30, 5, 2000));
        shopItems.add(new ShopItem("vip终身", "VIP-终身", 0, 10, 50000));
        getLogger().info("loaded " + shopItems.size() + " default shop items");
    }

    private File findShopFile() {
        File txt = new File(getDataFolder(), "商品.txt"); if (txt.exists()) return txt;
        File md = new File(getDataFolder(), "商品.md"); if (md.exists()) return md;
        return null;
    }

    private void openMainMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, MAIN_TITLE);
        ItemStack glass = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) gui.setItem(i, glass);
        gui.setItem(2, makeItem(Material.GREEN_WOOL, "§a§l会员信息", "§7查看会员状态和格子"));
        gui.setItem(4, makeItem(Material.EMERALD_BLOCK, "§a§l商城", "§7购买会员服务"));
        gui.setItem(6, makeItem(Material.ENDER_CHEST, "§a§l主仓", "§7管理存储空间"));
        gui.setItem(13, makeItem(Material.BARRIER, "§c§l关闭"));
        player.openInventory(gui);
    }

    private void openMemberInfo(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, INFO_TITLE);
        ItemStack glass = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) gui.setItem(i, glass);
        Map<String, Object> m = getMember(player.getName());
        int perm = m.isEmpty() ? 0 : ((Number) m.get("permanent_slots")).intValue();
        int mem = m.isEmpty() ? 0 : ((Number) m.get("membership_slots")).intValue();
        long expireTime = m.isEmpty() ? 0L : ((Number) m.get("expire_time")).longValue();
        int total = getActiveSlots(m), pages = getTotalPages(total);
        int unlocked = m.isEmpty() ? 1 : ((Number) m.get("unlocked_pages")).intValue();
        boolean active = (expireTime == 0) || (expireTime > System.currentTimeMillis());
        gui.setItem(4, makeItem(Material.NAME_TAG, "§e" + player.getName()));
        gui.setItem(10, makeItem(Material.CLOCK, "§a剩余时间", "§7" + formatTime(expireTime)));
        gui.setItem(12, makeItem(Material.DIAMOND_BLOCK, "§b永久格子: §e" + perm, "§7不会过期"));
        gui.setItem(14, makeItem(Material.EMERALD_BLOCK, "§a会员格子: §e" + mem, "§7" + (active ? "§a有效" : "§c已过期")));
        gui.setItem(16, makeItem(Material.CHEST, "§e总格子: §f" + total, "§7总页数: " + pages));
        gui.setItem(22, makeItem(Material.BOOK, "§e已解锁页: §f" + Math.min(unlocked, pages) + "/" + pages, "§7每页" + PAGE_SIZE + "格", "§7存满≥" + UNLOCK_THRESHOLD + "个解锁下一页"));
        gui.setItem(26, makeItem(Material.ARROW, "§e返回"));
        player.openInventory(gui);
    }

    private void openShopGUI(Player player) {
        int size = Math.max(9, ((shopItems.size() / 9) + 1) * 9);
        if (size > 54) size = 54;
        Inventory gui = Bukkit.createInventory(null, size, SHOP_TITLE);
        Material[] mats = {Material.DIAMOND, Material.GOLD_INGOT, Material.EMERALD, Material.NETHERITE_INGOT, Material.IRON_INGOT, Material.LAPIS_LAZULI, Material.REDSTONE, Material.COAL, Material.QUARTZ};
        for (int i = 0; i < shopItems.size() && i < 45; i++) {
            ShopItem item = shopItems.get(i);
            gui.setItem(i, makeItem(mats[i % mats.length], "§a" + item.name, "§7ID: " + item.id, "§7时间: §e" + (item.days == 0 ? "终身" : item.days + "天"), "§7格子: §e+" + item.slots, "§7价格: §a$" + fmt(item.price), "", "§e点击购买"));
        }
        gui.setItem(size - 1, makeItem(Material.ARROW, "§e返回"));
        player.openInventory(gui);
    }

    private void openStorageGUI(Player player) {
        Map<String, Object> m = getMember(player.getName());
        int total = getActiveSlots(m);
        if (total <= 0) { player.sendMessage("§c[仓库] §f没有存储空间！"); return; }
        List<ItemStack> items = loadStorage(player.getName());
        while (items.size() < total) items.add(null);
        storageCache.put(player.getUniqueId(), items);
        storagePages.put(player.getUniqueId(), 1);
        player.openInventory(Bukkit.createInventory(null, 54, STORAGE_TITLE));
        refreshStoragePage(player);
    }

    private void refreshStoragePage(Player player) {
        Inventory gui = player.getOpenInventory().getTopInventory();
        UUID uuid = player.getUniqueId();
        List<ItemStack> items = storageCache.containsKey(uuid) ? storageCache.get(uuid) : new ArrayList<ItemStack>();
        int page = storagePages.containsKey(uuid) ? storagePages.get(uuid) : 1;
        int start = (page - 1) * PAGE_SIZE;
        Map<String, Object> m = getMember(player.getName());
        int total = getActiveSlots(m), usable = getPageUsable(page, total), totalPages = getTotalPages(total);
        int unlocked = Math.min(((Number) m.getOrDefault("unlocked_pages", 1)).intValue(), totalPages);
        ItemStack glass = makeItem(Material.GRAY_STAINED_GLASS_PANE, "§7锁定");
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < usable) { int idx = start + i; gui.setItem(i, (idx < items.size() && items.get(idx) != null) ? items.get(idx) : null); }
            else gui.setItem(i, glass);
        }
        gui.setItem(45, page > 1 ? makeItem(Material.ARROW, "§a◀ 上一页") : makeItem(Material.GRAY_STAINED_GLASS_PANE, "§7◀"));
        gui.setItem(46, makeItem(Material.PAPER, "§e第" + page + "/" + totalPages + "页", "§7物品: " + countPageItems(items, page, total) + "/" + usable));
        gui.setItem(49, makeItem(Material.BARRIER, "§c返回"));
        if (page < totalPages) {
            boolean canGo = (page < unlocked) || (countPageItems(items, page, total) >= UNLOCK_THRESHOLD);
            gui.setItem(52, canGo ? makeItem(Material.ARROW, "§a下一页 ▶") : makeItem(Material.RED_STAINED_GLASS_PANE, "§c下一页 ▶", "§7需≥" + UNLOCK_THRESHOLD + "个物品"));
        } else gui.setItem(52, makeItem(Material.GRAY_STAINED_GLASS_PANE, "§7▶"));
    }

    private boolean tryNextPage(Player player) {
        UUID uuid = player.getUniqueId();
        int page = storagePages.containsKey(uuid) ? storagePages.get(uuid) : 1;
        int next = page + 1;
        Map<String, Object> m = getMember(player.getName());
        int total = getActiveSlots(m), totalPages = getTotalPages(total);
        int unlocked = ((Number) m.getOrDefault("unlocked_pages", 1)).intValue();
        if (next > totalPages) { player.sendMessage("§c[仓库] §f已到最大页！"); return false; }
        if (next <= unlocked) { storagePages.put(uuid, next); refreshStoragePage(player); return true; }
        List<ItemStack> items = storageCache.containsKey(uuid) ? storageCache.get(uuid) : new ArrayList<ItemStack>();
        int have = countPageItems(items, page, total);
        if (have >= UNLOCK_THRESHOLD) { unlocked++; updateUnlockedPages(player.getName(), unlocked); storagePages.put(uuid, next); refreshStoragePage(player); player.sendMessage("§a[仓库] §f已解锁第" + next + "页！"); return true; }
        player.sendMessage("§c[仓库] §f存储量不足（" + have + "/" + UNLOCK_THRESHOLD + "）");
        return false;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        int raw = event.getRawSlot();
        if (title.equals(MAIN_TITLE)) { event.setCancelled(true); if (raw == 2) openMemberInfo(player); else if (raw == 4) openShopGUI(player); else if (raw == 6) openStorageGUI(player); else if (raw == 13) player.closeInventory(); return; }
        if (title.equals(INFO_TITLE)) { event.setCancelled(true); if (raw == 26) openMainMenu(player); return; }
        if (title.equals(SHOP_TITLE)) { event.setCancelled(true); int size = event.getInventory().getSize(); if (raw == size - 1) { openMainMenu(player); return; } if (raw >= 0 && raw < shopItems.size() && raw < 45) buyItem(player, shopItems.get(raw)); return; }
        if (title.equals(STORAGE_TITLE)) {
            event.setCancelled(true);
            UUID uuid = player.getUniqueId();
            if (raw == 49) { openMainMenu(player); return; }
            if (raw == 45) { int p = storagePages.containsKey(uuid) ? storagePages.get(uuid) : 1; if (p > 1) { storagePages.put(uuid, p - 1); refreshStoragePage(player); } return; }
            if (raw == 52) { tryNextPage(player); return; }
            if (raw >= 0 && raw < PAGE_SIZE) {
                Map<String, Object> m = getMember(player.getName());
                int total = getActiveSlots(m), page = storagePages.containsKey(uuid) ? storagePages.get(uuid) : 1;
                int usable = getPageUsable(page, total);
                if (raw >= usable) return;
                ItemStack clicked = event.getCurrentItem(), cursor = event.getCursor();
                boolean cEmpty = (clicked == null || clicked.getType() == Material.AIR);
                boolean cGlass = (!cEmpty && clicked.getType() == Material.GRAY_STAINED_GLASS_PANE);
                boolean curEmpty = (cursor == null || cursor.getType() == Material.AIR);
                if (cEmpty && !curEmpty) { int idx = (page - 1) * PAGE_SIZE + raw; List<ItemStack> items = storageCache.containsKey(uuid) ? storageCache.get(uuid) : new ArrayList<ItemStack>(); while (items.size() <= idx) items.add(null); items.set(idx, cursor.clone()); event.getInventory().setItem(raw, cursor.clone()); event.setCursor(null); refreshStoragePage(player); return; }
                if (!cEmpty && !cGlass && curEmpty) { HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(clicked); if (overflow.isEmpty()) { int idx = (page - 1) * PAGE_SIZE + raw; List<ItemStack> items = storageCache.get(uuid); if (items != null && idx < items.size()) items.set(idx, null); event.getInventory().setItem(raw, null); } else player.sendMessage("§c[仓库] §f背包已满！"); return; }
                if (!cEmpty && !cGlass && !curEmpty) { int idx = (page - 1) * PAGE_SIZE + raw; List<ItemStack> items = storageCache.containsKey(uuid) ? storageCache.get(uuid) : new ArrayList<ItemStack>(); while (items.size() <= idx) items.add(null); items.set(idx, cursor.clone()); event.getInventory().setItem(raw, cursor.clone()); event.setCursor(clicked); refreshStoragePage(player); return; }
                return;
            }
            if (event.isShiftClick() && raw >= 54) {
                ItemStack held = event.getCurrentItem();
                if (held != null && held.getType() != Material.AIR) {
                    Map<String, Object> m = getMember(player.getName());
                    int total = getActiveSlots(m), page = storagePages.containsKey(uuid) ? storagePages.get(uuid) : 1;
                    int usable = getPageUsable(page, total);
                    List<ItemStack> items = storageCache.containsKey(uuid) ? storageCache.get(uuid) : new ArrayList<ItemStack>();
                    int start = (page - 1) * PAGE_SIZE;
                    for (int i = 0; i < usable; i++) { int idx = start + i; while (items.size() <= idx) items.add(null); if (items.get(idx) == null) { items.set(idx, held.clone()); event.setCurrentItem(null); refreshStoragePage(player); return; } }
                    player.sendMessage("§c[仓库] §f当前页已满！");
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        if (event.getView().getTitle().equals(STORAGE_TITLE)) {
            List<ItemStack> items = storageCache.remove(player.getUniqueId());
            storagePages.remove(player.getUniqueId());
            if (items != null) saveStorage(player.getName(), items);
        }
    }

    private void buyItem(Player player, ShopItem item) {
        if (economy == null) { player.sendMessage("§c[商城] §f经济不可用！"); return; }
        if (!economy.has(player, item.price)) { player.sendMessage("§c[商城] §f余额不足！需要§e" + fmt(item.price)); return; }
        economy.withdrawPlayer(player, item.price);
        if (item.days > 0) upsertMember(player.getName(), 0, item.slots, item.days); else upsertMember(player.getName(), item.slots, 0, 0);
        player.sendMessage("§a[商城] §f" + item.name + " | +" + item.slots + "格 | -$" + fmt(item.price));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) { if (sender instanceof Player) openMainMenu((Player) sender); else showStatus(sender); return true; }
        switch (args[0].toLowerCase()) {
            case "reload": if (!sender.hasPermission("cy.admin")) { sender.sendMessage("§c权限不足！"); return true; } loadShopConfig(); sender.sendMessage("§a重载！商品=" + shopItems.size()); break;
            case "status": if (!sender.hasPermission("cy.admin")) { sender.sendMessage("§c权限不足！"); return true; } showStatus(sender); break;
            case "info": if (!sender.hasPermission("cy.admin")) { sender.sendMessage("§c权限不足！"); return true; } if (args.length < 2) { sender.sendMessage("§e用法: /cy info <玩家>"); break; } showMemberInfo(sender, args[1]); break;
            case "remove": if (!sender.hasPermission("cy.admin")) { sender.sendMessage("§c权限不足！"); return true; } if (args.length < 2) { sender.sendMessage("§e用法: /cy remove <玩家>"); break; } sender.sendMessage(deleteMember(args[1]) ? "§a已移除: " + args[1] : "§c不存在: " + args[1]); break;
            case "shop": if (sender instanceof Player) openShopGUI((Player) sender); break;
            default: sender.sendMessage("§c/cy [reload|status|info|remove|shop]");
        }
        return true;
    }

    private void showStatus(CommandSender sender) {
        int count = 0, totalSlots = 0;
        try { Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*),COALESCE(SUM(permanent_slots+membership_slots),0) FROM members"); if (rs.next()) { count = rs.getInt(1); totalSlots = rs.getInt(2); } rs.close(); st.close(); } catch (Exception ignored) {}
        sender.sendMessage("§e成员:" + count + " 总格子:" + totalSlots + " 商品:" + shopItems.size());
    }

    private void showMemberInfo(CommandSender sender, String name) {
        Map<String, Object> m = getMember(name);
        if (m.isEmpty()) { sender.sendMessage("§c" + name + " 不是成员"); return; }
        sender.sendMessage("§e" + name + ": 永久=" + ((Number) m.get("permanent_slots")).intValue() + " 会员=" + ((Number) m.get("membership_slots")).intValue() + " 过期=" + formatTime(((Number) m.get("expire_time")).longValue()));
    }

    private String formatTime(long expire) {
        if (expire == 0) return "终身";
        long now = System.currentTimeMillis();
        if (expire <= now) return "已过期";
        long remain = expire - now, days = remain / 86400000L, hours = (remain % 86400000L) / 3600000L;
        return days > 0 ? days + "天" + hours + "小时" : hours + "小时";
    }

    private ItemStack makeItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat); ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); if (lore.length > 0) meta.setLore(Arrays.asList(lore)); item.setItemMeta(meta); }
        return item;
    }

    private String fmt(double v) { return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v); }

    public static class ShopItem {
        public final String id, name;
        public final int days, slots;
        public final double price;
        public ShopItem(String id, String name, int days, int slots, double price) { this.id = id; this.name = name; this.days = days; this.slots = slots; this.price = price; }
    }
}
