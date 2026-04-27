package com.CY;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main extends JavaPlugin implements CommandExecutor, Listener, TabCompleter {

    private static final int PAGE_SIZE = 45;
    private static final int UNLOCK_NEED = 40;
    private static final String T_MAIN = "\u00a76\u00a7l\u4f1a\u5458\u4e2d\u5fc3";
    private static final String T_INFO = "\u00a76\u00a7l\u4f1a\u5458\u4fe1\u606f";
    private static final String T_SHOP = "\u00a76\u00a7l\u5546\u57ce";
    private static final String T_STORE = "\u00a76\u00a7l\u4e3b\u4ed3";
    private static final String T_ADMIN = "\u00a7c\u00a7l\u7ba1\u7406\u9762\u677f";
    private static final String API_GE = "https://gitee.com/api/v5/repos/nihaoshidifu/cy_beibao/releases/latest";
    private static final String API_GH = "https://api.github.com/repos/ypsdf1/CY_beibao/releases/latest";

    private Economy economy;
    private Connection db;
    private final List<ShopItem> shopList = new ArrayList<ShopItem>();
    private final Map<String, Integer> stockMap = new HashMap<String, Integer>();
    private final Map<UUID, Integer> pageMap = new HashMap<UUID, Integer>();
    private final Map<UUID, List<ItemStack>> cacheMap = new HashMap<UUID, List<ItemStack>>();
    private final Random rng = new Random();
    private String localVer = "";
    private String updateCh = "";
    private String adminPass = "qweasd";
    private final Set<UUID> adminWaitPass = new HashSet<UUID>();
    private final Set<UUID> adminWaitId = new HashSet<UUID>();
    private final Map<UUID, String> adminTarget = new HashMap<UUID, String>();
    private final Map<UUID, String> adminEditType = new HashMap<UUID, String>();

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        setupEconomy();
        initDB();
        loadShop();
        getCommand("cy").setExecutor(this);
        getCommand("cy").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("CY v" + localVer + " shop=" + shopList.size());
    }

    @Override
    public void onDisable() {
        try { if (db != null && !db.isClosed()) db.close(); } catch (Exception ignored) {}
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    // ==================== SQLite ====================

    private void initDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            String path = new File(getDataFolder(), "members.db").getAbsolutePath();
            db = DriverManager.getConnection("jdbc:sqlite:" + path);
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS members (player_name TEXT PRIMARY KEY,"
                    + "permanent_slots INTEGER DEFAULT 0,"
                    + "membership_slots INTEGER DEFAULT 0,"
                    + "expire_time INTEGER DEFAULT 0,"
                    + "activated INTEGER DEFAULT 0,"
                    + "unlocked_pages INTEGER DEFAULT 1,"
                    + "data TEXT DEFAULT '')");
            st.close();
        } catch (Exception e) {
            getLogger().severe("SQLite: " + e.getMessage());
        }
    }

    private Map<String, Object> getMember(String name) {
        Map<String, Object> r = new HashMap<String, Object>();
        if (db == null) return r;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT * FROM members WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                r.put("perm", rs.getInt("permanent_slots"));
                r.put("mem", rs.getInt("membership_slots"));
                r.put("exp", rs.getLong("expire_time"));
                r.put("act", rs.getLong("activated"));
                r.put("up", rs.getInt("unlocked_pages"));
                r.put("data", rs.getString("data"));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {}
        return r;
    }

    private boolean memberExists(String name) {
        if (db == null) return false;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT 1 FROM members WHERE player_name=?");
            ps.setString(1, name);
            boolean ok = ps.executeQuery().next();
            ps.close();
            return ok;
        } catch (SQLException e) { return false; }
    }

    private void ensureMember(String name) {
        if (!memberExists(name)) upsert(name, 0, 0, 0);
    }

    private void upsert(String name, int addP, int addM, int addD) {
        if (db == null) return;
        long now = System.currentTimeMillis();
        try {
            Map<String, Object> old = getMember(name);
            boolean has = !old.isEmpty();
            int oP = has ? ((Number) old.get("perm")).intValue() : 0;
            int oM = has ? ((Number) old.get("mem")).intValue() : 0;
            long oE = has ? ((Number) old.get("exp")).longValue() : 0L;
            long act = has ? ((Number) old.get("act")).longValue() : now;
            int up = has ? ((Number) old.get("up")).intValue() : 1;
            int nP = oP + addP;
            int nM = oM + addM;
            long nE = addD > 0 ? Math.max(now, oE) + (long) addD * 86400000L : oE;
            if (has) {
                PreparedStatement ps = db.prepareStatement(
                        "UPDATE members SET permanent_slots=?,membership_slots=?,"
                                + "expire_time=?,activated=?,unlocked_pages=? WHERE player_name=?");
                ps.setInt(1, nP); ps.setInt(2, nM); ps.setLong(3, nE);
                ps.setLong(4, act); ps.setInt(5, up); ps.setString(6, name);
                ps.executeUpdate(); ps.close();
            } else {
                PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO members (player_name,permanent_slots,membership_slots,"
                                + "expire_time,activated,unlocked_pages) VALUES(?,?,?,?,?,?)");
                ps.setString(1, name); ps.setInt(2, nP); ps.setInt(3, nM);
                ps.setLong(4, nE); ps.setLong(5, act); ps.setInt(6, up);
                ps.executeUpdate(); ps.close();
            }
        } catch (SQLException e) {}
    }

    private void setSlot(String name, String type, int val) {
        if (db == null) return;
        String col = "perm".equals(type) ? "permanent_slots" : "membership_slots";
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET " + col + "=? WHERE player_name=?");
            ps.setInt(1, Math.max(0, val)); ps.setString(2, name);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) {}
    }

    private void setExpire(String name, long ms) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET expire_time=? WHERE player_name=?");
            ps.setLong(1, ms); ps.setString(2, name);
            ps.executeUpdate(); ps.close();
        } catch (SQLException e) {}
    }

    private void setUpPages(String name, int p) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET unlocked_pages=? WHERE player_name=?");
            ps.setInt(1, p); ps.setString(2, name);
            ps.executeUpdate(); ps.close();
        } catch (SQLException ignored) {}
    }

    private boolean delMember(String name) {
        if (db == null) return false;
        try {
            PreparedStatement ps = db.prepareStatement("DELETE FROM members WHERE player_name=?");
            ps.setString(1, name);
            boolean ok = ps.executeUpdate() > 0;
            ps.close(); return ok;
        } catch (SQLException e) { return false; }
    }

    // ==================== Slots ====================

    private int activeSlots(Map<String, Object> m) {
        if (m.isEmpty()) return 0;
        int p = ((Number) m.get("perm")).intValue();
        int me = ((Number) m.get("mem")).intValue();
        long e = ((Number) m.get("exp")).longValue();
        return p + ((e == 0 || e > System.currentTimeMillis()) ? me : 0);
    }

    private int totalPages(int t) { return Math.max(1, (t + PAGE_SIZE - 1) / PAGE_SIZE); }
    private int usable(int pg, int tot) { return Math.min(PAGE_SIZE, Math.max(0, tot - (pg - 1) * PAGE_SIZE)); }

    private int countItems(List<ItemStack> list, int pg, int tot) {
        int s = (pg - 1) * PAGE_SIZE, u = usable(pg, tot), c = 0;
        for (int i = 0; i < u; i++) { int idx = s + i; if (idx < list.size() && list.get(idx) != null) c++; }
        return c;
    }

    // ==================== Base64 ====================

    private List<ItemStack> loadStorage(String name) {
        List<ItemStack> list = new ArrayList<ItemStack>();
        if (db == null) return list;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT data FROM members WHERE player_name=?");
            ps.setString(1, name); ResultSet rs = ps.executeQuery();
            if (rs.next()) { String b = rs.getString("data"); if (b != null && !b.isEmpty()) Collections.addAll(list, decodeItems(b)); }
            rs.close(); ps.close();
        } catch (Exception e) {}
        return list;
    }

    private void saveStorage(String name, List<ItemStack> list) {
        if (db == null) return;
        try {
            String b = encodeItems(list.toArray(new ItemStack[0]));
            PreparedStatement ps = db.prepareStatement("UPDATE members SET data=? WHERE player_name=?");
            ps.setString(1, b); ps.setString(2, name);
            ps.executeUpdate(); ps.close();
        } catch (Exception e) {}
    }

    private static String encodeItems(ItemStack[] arr) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos);
        oos.writeInt(arr.length);
        for (ItemStack it : arr) oos.writeObject(it);
        oos.close();
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private static ItemStack[] decodeItems(String s) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(s));
        BukkitObjectInputStream ois = new BukkitObjectInputStream(bis);
        ItemStack[] arr = new ItemStack[ois.readInt()];
        for (int i = 0; i < arr.length; i++) arr[i] = (ItemStack) ois.readObject();
        ois.close(); return arr;
    }

    public boolean onSdf1Activation(String name, int slots, int days) {
        try { if (days > 0) upsert(name, 0, slots, days); else upsert(name, slots, 0, 0); return true; } catch (Exception e) { return false; }
    }

    // ==================== Shop ====================

    private void loadShop() {
        shopList.clear(); stockMap.clear();
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        if (!f.exists() || f.length() == 0) {
            writeDefaultFile();
            f = new File(getDataFolder(), "\u5546\u54c1.txt");
        }
        if (!f.exists()) { getLogger().warning("Shop file still missing"); return; }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            String curId = null, curName = null;
            int curDays = 0, curSlots = 0, curStk = -1;
            double curPrice = 0;
            boolean hasItem = false;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
                if (line.equals("--")) {
                    if (hasItem && curId != null) {
                        ShopItem it = new ShopItem(curId, curName != null ? curName : curId, curDays, curSlots, curPrice, curStk);
                        shopList.add(it); stockMap.put(it.id, it.stk);
                    }
                    curId = null; curName = null; curDays = 0; curSlots = 0; curPrice = 0; curStk = -1; hasItem = false;
                    continue;
                }
                String[] kv = line.split(":", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim(), v = kv[1].trim();
                if (k.equals("\u7248\u672c\u53f7")) { localVer = v; continue; }
                if (k.equals("\u66f4\u65b0\u901a\u9053")) { updateCh = v; continue; }
                if (k.equals("\u7ba1\u7406\u5bc6\u7801")) { adminPass = v; continue; }
                if (k.equals("ID") || k.equals("id")) { curId = v; hasItem = true; }
                else if (k.equals("\u54c1\u540d") || k.equals("name")) curName = v;
                else if (k.equals("\u5929\u6570") || k.equals("days")) { try { curDays = Integer.parseInt(v); } catch (Exception ignored) {} }
                else if (k.equals("\u683c\u5b50") || k.equals("slots")) { try { curSlots = Integer.parseInt(v); } catch (Exception ignored) {} }
                else if (k.equals("\u4ef7\u683c") || k.equals("price")) { try { curPrice = Double.parseDouble(v); } catch (Exception ignored) {} }
                else if (k.equals("\u5e93\u5b58") || k.equals("stock")) { try { curStk = Integer.parseInt(v); } catch (Exception ignored) {} }
            }
            if (hasItem && curId != null) {
                ShopItem it = new ShopItem(curId, curName != null ? curName : curId, curDays, curSlots, curPrice, curStk);
                shopList.add(it); stockMap.put(it.id, it.stk);
            }
            r.close();
            getLogger().info("Loaded " + shopList.size() + " shop items");
        } catch (IOException e) { getLogger().warning("loadShop: " + e.getMessage()); }
    }

    private void writeDefaultFile() {
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        if (f.exists() && f.length() > 0) return;
        try {
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
            pw.println("\u7248\u672c\u53f7: 1.0");
            pw.println("\u66f4\u65b0\u901a\u9053: GE");
            pw.println("\u7ba1\u7406\u5bc6\u7801: qweasd");
            pw.println();
            pw.println("ID: vip1");
            pw.println("\u54c1\u540d: VIP-1\u5929");
            pw.println("\u5929\u6570: 1");
            pw.println("\u683c\u5b50: 1");
            pw.println("\u4ef7\u683c: 100");
            pw.println("\u5e93\u5b58: 50");
            pw.println("--");
            pw.println("ID: vip7");
            pw.println("\u54c1\u540d: VIP-7\u5929");
            pw.println("\u5929\u6570: 7");
            pw.println("\u683c\u5b50: 3");
            pw.println("\u4ef7\u683c: 500");
            pw.println("\u5e93\u5b58: 30");
            pw.println("--");
            pw.close();
        } catch (IOException e) {}
    }

    private void saveShopFile() {
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        try {
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
            pw.println("\u7248\u672c\u53f7: " + localVer);
            pw.println("\u66f4\u65b0\u901a\u9053: " + updateCh);
            pw.println("\u7ba1\u7406\u5bc6\u7801: " + adminPass);
            pw.println();
            for (ShopItem it : shopList) {
                int st = stockMap.containsKey(it.id) ? stockMap.get(it.id) : it.stk;
                pw.println("ID: " + it.id);
                pw.println("\u54c1\u540d: " + it.name);
                pw.println("\u5929\u6570: " + it.days);
                pw.println("\u683c\u5b50: " + it.slots);
                pw.println("\u4ef7\u683c: " + (int) it.price);
                pw.println("\u5e93\u5b58: " + st);
                pw.println("--");
            }
            pw.close();
        } catch (IOException e) {}
    }

    // ==================== Update ====================

    private void checkUpdate(CommandSender s) {
        String api = "GH".equalsIgnoreCase(updateCh) ? API_GH : API_GE;
        s.sendMessage("\u00a7e[update] \u00a7f\u68c0\u67e5\u4e2d... \u672c\u5730:v" + localVer);
        Thread.ofVirtual().start(() -> {
            try {
                URL url = new URL(api);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("GET"); c.setRequestProperty("User-Agent", "CY_beibao");
                c.setConnectTimeout(10000); c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) { s.sendMessage("\u00a7c[update] \u00a7f\u54cd\u5e94: " + c.getResponseCode()); return; }
                String json = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String rv = jParse(json, "tag_name"), rn = jParse(json, "name");
                if (rv.isEmpty()) { s.sendMessage("\u00a7c[update] \u00a7f\u89e3\u6790\u5931\u8d25"); return; }
                if (rv.equals(localVer)) { s.sendMessage("\u00a7a[update] \u00a7f\u5df2\u662f\u6700\u65b0 v" + localVer); }
                else { s.sendMessage("\u00a7c\u00a7l[update] \u65b0\u7248\u672c\uff01v" + localVer + " -> v" + rv); if (!rn.isEmpty()) s.sendMessage("\u00a77" + rn); s.sendMessage("\u00a7a\u4e0b\u8f7d: " + ("GH".equalsIgnoreCase(updateCh) ? "https://github.com/ypsdf1/CY_beibao/releases/tag/" + rv : "https://gitee.com/nihaoshidifu/cy_beibao/releases/tag/" + rv)); }
            } catch (Exception e) { s.sendMessage("\u00a7c[update] \u00a7f\u5931\u8d25: " + e.getMessage()); }
        });
    }

    private static String jParse(String j, String k) { int i = j.indexOf("\"" + k + "\""); if (i < 0) return ""; int c = j.indexOf(":", i); int s = j.indexOf("\"", c + 1); int e = j.indexOf("\"", s + 1); return (s > 0 && e > s) ? j.substring(s + 1, e) : ""; }

    // ==================== Blind Box ====================

    private void openBox(Player p, ShopItem item) {
        if (economy == null) { p.sendMessage("\u00a7c[\u5546\u57ce] \u00a7f\u7ecf\u6d4e\u4e0d\u53ef\u7528\uff01"); return; }
        int st = stockMap.containsKey(item.id) ? stockMap.get(item.id) : item.stk;
        if (st == 0) { p.sendMessage("\u00a7c[\u5546\u57ce] \u00a7f\u5df2\u552e\u7f44\uff01"); return; }
        if (!economy.has(p, item.price)) { p.sendMessage("\u00a7c[\u5546\u57ce] \u00a7f\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981\u00a7e" + fmt(item.price)); return; }
        economy.withdrawPlayer(p, item.price);
        int rd = 7 + rng.nextInt(84), rs = 27 + rng.nextInt(109);
        upsert(p.getName(), 0, rs, rd);
        if (st > 0) { st--; stockMap.put(item.id, st); saveShopFile(); }
        p.sendMessage("\u00a76[\u76f2\u76d2] \u00a7e" + rd + "\u5929\u4f1a\u5458 + " + rs + "\u683c\u7a7a\u95f4");
        p.sendMessage("\u00a77\u5269\u4f59: \u00a7a$" + fmt(economy.getBalance(p)));
    }

    // ==================== GUI ====================

    private void openMain(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, T_MAIN);
        fillBg(g);
        g.setItem(2, mkItem(Material.GREEN_WOOL, "\u00a7a\u00a7l\u4f1a\u5458\u4fe1\u606f"));
        g.setItem(4, mkItem(Material.EMERALD_BLOCK, "\u00a7a\u00a7l\u5546\u57ce"));
        g.setItem(6, mkItem(Material.ENDER_CHEST, "\u00a7a\u00a7l\u4e3b\u4ed3"));
        g.setItem(13, mkItem(Material.BARRIER, "\u00a7c\u00a7l\u5173\u95ed"));
        if (isAdmin(p)) g.setItem(11, mkItem(Material.REDSTONE_BLOCK, "\u00a7c\u00a7l\u7ba1\u7406\u9762\u677f"));
        p.openInventory(g);
    }

    private boolean isAdmin(Player p) {
        return p.isOp() || p.hasPermission("cy.admin") || p.getScoreboardTags().contains("admin");
    }

    private void openInfo(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, T_INFO);
        fillBg(g);
        ensureMember(p.getName());
        Map<String, Object> m = getMember(p.getName());
        int perm = ((Number) m.get("perm")).intValue();
        int mem = ((Number) m.get("mem")).intValue();
        long exp = ((Number) m.get("exp")).longValue();
        int tot = activeSlots(m), pg = totalPages(tot);
        int up = ((Number) m.get("up")).intValue();
        boolean on = (exp == 0) || (exp > System.currentTimeMillis());
        g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e" + p.getName()));
        g.setItem(10, mkItem(Material.CLOCK, "\u00a7a\u5269\u4f59\u65f6\u95f4", "\u00a77" + fmtTime(exp)));
        g.setItem(12, mkItem(Material.DIAMOND_BLOCK, "\u00a7b\u6c38\u4e45\u683c\u5b50: \u00a7e" + perm));
        g.setItem(14, mkItem(Material.EMERALD_BLOCK, "\u00a7a\u4f1a\u5458\u683c\u5b50: \u00a7e" + mem, "\u00a77" + (on ? "\u00a7a\u6709\u6548" : "\u00a7c\u5df2\u8fc7\u671f")));
        g.setItem(16, mkItem(Material.CHEST, "\u00a7e\u603b\u683c\u5b50: \u00a7f" + tot, "\u00a77\u603b\u9875\u6570: " + pg));
        g.setItem(22, mkItem(Material.BOOK, "\u00a7e\u89e3\u9501\u9875: \u00a7f" + Math.min(up, pg) + "/" + pg));
        g.setItem(26, mkItem(Material.ARROW, "\u00a7e\u8fd4\u56de"));
        p.openInventory(g);
    }

    private void openShop(Player p) {
        int sz = Math.max(9, ((shopList.size() / 9) + 1) * 9);
        if (sz > 54) sz = 54;
        Inventory g = Bukkit.createInventory(null, sz, T_SHOP);
        Material[] ms = {Material.DIAMOND, Material.GOLD_INGOT, Material.EMERALD, Material.NETHERITE_INGOT, Material.IRON_INGOT, Material.LAPIS_LAZULI, Material.REDSTONE, Material.COAL, Material.QUARTZ};
        for (int i = 0; i < shopList.size() && i < 45; i++) {
            ShopItem it = shopList.get(i);
            int st = stockMap.containsKey(it.id) ? stockMap.get(it.id) : it.stk;
            boolean bx = it.id.startsWith("\u76f2\u76d2");
            g.setItem(i, mkItem(ms[i % ms.length], "\u00a7a" + it.name,
                    "\u00a77\u65f6\u95f4: \u00a7e" + (bx ? "\u968f\u673a" : it.days == 0 ? "\u7ec8\u8eab" : it.days + "\u5929"),
                    "\u00a77\u683c\u5b50: \u00a7e" + (bx ? "\u968f\u673a" : "+" + it.slots),
                    "\u00a77\u4ef7\u683c: \u00a7a$" + fmt(it.price),
                    "\u00a77\u5e93\u5b58: \u00a7e" + (st < 0 ? "\u65e0\u9650" : st),
                    "", "\u00a7e\u70b9\u51fb\u8d2d\u4e70"));
        }
        g.setItem(sz - 1, mkItem(Material.ARROW, "\u00a7e\u8fd4\u56de"));
        p.openInventory(g);
    }

    private void openStorage(Player p) {
        ensureMember(p.getName());
        Map<String, Object> m = getMember(p.getName());
        int tot = activeSlots(m);
        if (tot <= 0) { p.sendMessage("\u00a7c[\u4ed3\u5e93] \u00a7f\u6ca1\u6709\u5b58\u50a8\u7a7a\u95f4\uff01"); return; }
        List<ItemStack> list = loadStorage(p.getName());
        while (list.size() < tot) list.add(null);
        cacheMap.put(p.getUniqueId(), list);
        pageMap.put(p.getUniqueId(), 1);
        p.openInventory(Bukkit.createInventory(null, 54, T_STORE));
        refreshPage(p);
    }

    private void refreshPage(Player p) {
        Inventory g = p.getOpenInventory().getTopInventory();
        UUID u = p.getUniqueId();
        List<ItemStack> list = cacheMap.containsKey(u) ? cacheMap.get(u) : new ArrayList<ItemStack>();
        int pg = pageMap.containsKey(u) ? pageMap.get(u) : 1;
        int start = (pg - 1) * PAGE_SIZE;
        Map<String, Object> m = getMember(p.getName());
        int tot = activeSlots(m), us = usable(pg, tot), tp = totalPages(tot);
        int up = Math.min(((Number) m.getOrDefault("up", 1)).intValue(), tp);
        ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, "\u00a77\u9501\u5b9a");
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < us) { int idx = start + i; g.setItem(i, (idx < list.size() && list.get(idx) != null) ? list.get(idx) : null); }
            else g.setItem(i, gl);
        }
        g.setItem(45, pg > 1 ? mkItem(Material.ARROW, "\u00a7a\u25c0 \u4e0a\u4e00\u9875") : mkItem(Material.GRAY_STAINED_GLASS_PANE, "\u00a77\u25c0"));
        g.setItem(46, mkItem(Material.PAPER, "\u00a7e\u7b2c" + pg + "/" + tp + "\u9875", "\u00a77\u7269\u54c1: " + countItems(list, pg, tot) + "/" + us));
        g.setItem(49, mkItem(Material.BARRIER, "\u00a7c\u8fd4\u56de"));
        if (pg < tp) {
            boolean can = (pg < up) || (countItems(list, pg, tot) >= UNLOCK_NEED);
            g.setItem(52, can ? mkItem(Material.ARROW, "\u00a7a\u4e0b\u4e00\u9875 \u25b6") : mkItem(Material.RED_STAINED_GLASS_PANE, "\u00a7c\u4e0b\u4e00\u9875 \u25b6", "\u00a77\u9700\u2265" + UNLOCK_NEED));
        } else g.setItem(52, mkItem(Material.GRAY_STAINED_GLASS_PANE, "\u00a77\u25b6"));
    }

    private boolean nextPage(Player p) {
        UUID u = p.getUniqueId();
        int pg = pageMap.containsKey(u) ? pageMap.get(u) : 1, nx = pg + 1;
        Map<String, Object> m = getMember(p.getName());
        int tot = activeSlots(m), tp = totalPages(tot), up = ((Number) m.getOrDefault("up", 1)).intValue();
        if (nx > tp) { p.sendMessage("\u00a7c[\u4ed3\u5e93] \u00a7f\u5df2\u5230\u6700\u5927\u9875\uff01"); return false; }
        if (nx <= up) { pageMap.put(u, nx); refreshPage(p); return true; }
        List<ItemStack> list = cacheMap.containsKey(u) ? cacheMap.get(u) : new ArrayList<ItemStack>();
        int have = countItems(list, pg, tot);
        if (have >= UNLOCK_NEED) { up++; setUpPages(p.getName(), up); pageMap.put(u, nx); refreshPage(p); p.sendMessage("\u00a7a[\u4ed3\u5e93] \u00a7f\u5df2\u89e3\u9501\u7b2c" + nx + "\u9875\uff01"); return true; }
        p.sendMessage("\u00a7c[\u4ed3\u5e93] \u00a7f\u5b58\u50a8\u91cf\u4e0d\u8db3\uff08" + have + "/" + UNLOCK_NEED + "\uff09");
        return false;
    }

    // ==================== Admin ====================

    private void openAdminPanel(Player p, String tn) {
        Inventory g = Bukkit.createInventory(null, 27, T_ADMIN);
        fillBg(g);
        ensureMember(tn);
        Map<String, Object> m = getMember(tn);
        g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e\u7ba1\u7406: " + tn));
        if (m.isEmpty()) {
            g.setItem(13, mkItem(Material.BEDROCK, "\u00a7c\u73a9\u5bb6\u4e0d\u5728\u6570\u636e\u5e93\u4e2d"));
        } else {
            g.setItem(10, mkItem(Material.GREEN_WOOL, "\u00a7a\u6c38\u4e45\u683c\u5b50: \u00a7e" + ((Number) m.get("perm")).intValue(), "\u00a77\u70b9\u51fb\u8f93\u5165\u8c03\u6574"));
            g.setItem(12, mkItem(Material.EMERALD_BLOCK, "\u00a7a\u4f1a\u5458\u683c\u5b50: \u00a7e" + ((Number) m.get("mem")).intValue(), "\u00a77\u70b9\u51fb\u8f93\u5165\u8c03\u6574"));
            g.setItem(14, mkItem(Material.CLOCK, "\u00a7a\u4f1a\u5458\u65f6\u95f4", "\u00a77" + fmtTime(((Number) m.get("exp")).longValue()), "\u00a77\u70b9\u51fb\u8f93\u5165\u8c03\u6574"));
            g.setItem(16, mkItem(Material.BARRIER, "\u00a7c\u79fb\u9664\u73a9\u5bb6", "\u00a77\u70b9\u51fb\u5220\u9664\u6b64\u73a9\u5bb6\u6570\u636e"));
        }
        g.setItem(22, mkItem(Material.ARROW, "\u00a7e\u8fd4\u56de"));
        p.openInventory(g);
    }

    private void startAdminAuth(Player p) {
        p.closeInventory();
        adminWaitPass.add(p.getUniqueId());
        p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u8bf7\u8f93\u5165\u7ba1\u7406\u5bc6\u7801\uff080/exit/\u9000\u51fa \u53d6\u6d88\uff09\uff1a");
        if (adminPass.equals("qweasd")) p.sendMessage("\u00a7c\u00a7l[\u8b66\u544a] \u00a7f\u4f7f\u7528\u9ed8\u8ba4\u5bc6\u7801\uff01\u5efa\u8bae\u4fee\u6539 \u5546\u54c1.txt");
    }

    private boolean isCancel(String msg) { String m = msg.toLowerCase().trim(); return m.equals("0") || m.equals("exit") || m.equals("\u9000\u51fa"); }

    private long parseTimeInput(String msg) {
        String t = msg.trim();
        SimpleDateFormat sdf1 = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        try { return sdf1.parse(t).getTime(); } catch (Exception ignored) {}
        SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try { return sdf2.parse(t).getTime(); } catch (Exception ignored) {}
        SimpleDateFormat sdf3 = new SimpleDateFormat("yyyy-MM-dd");
        try { return sdf3.parse(t).getTime(); } catch (Exception ignored) {}
        return -1;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        UUID u = p.getUniqueId();
        if (!adminWaitPass.contains(u) && !adminWaitId.contains(u) && !adminEditType.containsKey(u)) return;
        event.setCancelled(true);
        String msg = event.getMessage().trim();

        if (adminWaitPass.contains(u)) {
            if (isCancel(msg)) { adminWaitPass.remove(u); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u5df2\u53d6\u6d88"); return; }
            if (msg.equals(adminPass)) { adminWaitPass.remove(u); adminWaitId.add(u); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5bc6\u7801\u6b63\u786e\uff01\u8bf7\u8f93\u5165\u73a9\u5bb6ID\uff080/exit/\u9000\u51fa \u53d6\u6d88\uff09\uff1a"); }
            else { adminWaitPass.remove(u); p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u5bc6\u7801\u9519\u8bef\uff01"); }
            return;
        }

        if (adminWaitId.contains(u)) {
            if (isCancel(msg)) { adminWaitId.remove(u); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u5df2\u53d6\u6d88"); return; }
            if (memberExists(msg)) { adminWaitId.remove(u); adminTarget.put(u, msg); final Player fp = p; final String fn = msg; Bukkit.getScheduler().runTask(this, () -> openAdminPanel(fp, fn)); }
            else { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u73a9\u5bb6\u300c" + msg + "\u300d\u4e0d\u5b58\u5728\uff0c\u8bf7\u91cd\u65b0\u8f93\u5165\uff080/exit/\u9000\u51fa \u53d6\u6d88\uff09\uff1a"); }
            return;
        }

        if (adminEditType.containsKey(u)) {
            String et = adminEditType.get(u);
            String tn = adminTarget.get(u);
            adminEditType.remove(u);
            if (isCancel(msg)) { p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u5df2\u53d6\u6d88"); return; }
            if (tn == null) return;

            if ("exp".equals(et)) {
                long absTime = parseTimeInput(msg);
                if (absTime > 0) {
                    ensureMember(tn);
                    long cur = ((Number) getMember(tn).get("exp")).longValue();
                    setExpire(tn, absTime);
                    p.sendMessage("\u00a7a[\u7ba1\u7406] " + tn + " \u5230\u671f: " + fmtTime(cur) + " \u2192 " + fmtTime(absTime));
                } else {
                    Matcher mx = Pattern.compile("([+-]?)(\\d+)").matcher(msg);
                    if (!mx.matches()) { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u683c\u5f0f\u9519\u8bef\uff0c\u652f\u6301: +7 / -3 / 2026-12-31 23:59"); return; }
                    String sign = mx.group(1); int val = Integer.parseInt(mx.group(2));
                    boolean add = sign.equals("+") || sign.isEmpty();
                    ensureMember(tn);
                    long cur = ((Number) getMember(tn).get("exp")).longValue();
                    long now = System.currentTimeMillis();
                    long base = (cur > now) ? cur : now;
                    long nv = add ? base + (long) val * 86400000L : Math.max(now, base - (long) val * 86400000L);
                    setExpire(tn, nv);
                    p.sendMessage("\u00a7a[\u7ba1\u7406] " + tn + " \u5230\u671f: " + fmtTime(cur) + " \u2192 " + fmtTime(nv));
                }
            } else {
                Matcher mx = Pattern.compile("([+-]?)(\\d+)").matcher(msg);
                if (!mx.matches()) { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u8bf7\u8f93\u5165 +\u6570\u5b57 \u6216 -\u6570\u5b57"); return; }
                String sign = mx.group(1); int val = Integer.parseInt(mx.group(2));
                boolean add = sign.equals("+") || sign.isEmpty();
                ensureMember(tn);
                Map<String, Object> mm = getMember(tn);
                if ("perm".equals(et)) {
                    int cur = ((Number) mm.get("perm")).intValue();
                    int nv = add ? cur + val : Math.max(0, cur - val);
                    setSlot(tn, "perm", nv);
                    p.sendMessage("\u00a7a[\u7ba1\u7406] " + tn + " \u6c38\u4e45\u683c\u5b50: " + cur + " -> " + nv);
                } else if ("mem".equals(et)) {
                    int cur = ((Number) mm.get("mem")).intValue();
                    int nv = add ? cur + val : Math.max(0, cur - val);
                    setSlot(tn, "mem", nv);
                    p.sendMessage("\u00a7a[\u7ba1\u7406] " + tn + " \u4f1a\u5458\u683c\u5b50: " + cur + " -> " + nv);
                }
            }
            final Player fp = p; final String ft = tn;
            Bukkit.getScheduler().runTask(this, () -> openAdminPanel(fp, ft));
        }
    }

    // ==================== Events ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String t = e.getView().getTitle();
        int r = e.getRawSlot();

        if (t.equals(T_MAIN)) {
            e.setCancelled(true);
            if (r == 2) openInfo(p);
            else if (r == 4) openShop(p);
            else if (r == 6) openStorage(p);
            else if (r == 11 && isAdmin(p)) startAdminAuth(p);
            else if (r == 13) p.closeInventory();
            return;
        }
        if (t.equals(T_INFO)) { e.setCancelled(true); if (r == 26) openMain(p); return; }
        if (t.equals(T_SHOP)) {
            e.setCancelled(true);
            int sz = e.getInventory().getSize();
            if (r == sz - 1) { openMain(p); return; }
            if (r >= 0 && r < shopList.size() && r < 45) buy(p, shopList.get(r));
            return;
        }
        if (t.equals(T_ADMIN)) {
            e.setCancelled(true);
            String tn = adminTarget.getOrDefault(p.getUniqueId(), "");
            if (tn.isEmpty()) return;
            if (r == 22) { openMain(p); return; }
            if (r == 10) { adminEditType.put(p.getUniqueId(), "perm"); p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u8bf7\u8f93\u5165\u6c38\u4e45\u683c\u5b50\u8c03\u6574\u91cf (+\u589e\u52a0/-\u51cf\u5c11)\uff1a"); return; }
            if (r == 12) { adminEditType.put(p.getUniqueId(), "mem"); p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u8bf7\u8f93\u5165\u4f1a\u5458\u683c\u5b50\u8c03\u6574\u91cf (+\u589e\u52a0/-\u51cf\u5c11)\uff1a"); return; }
            if (r == 14) { adminEditType.put(p.getUniqueId(), "exp"); p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u8bf7\u8f93\u5165\u4f1a\u5458\u65f6\u95f4: +7 / -3 / 2026-12-31 23:59"); return; }
            if (r == 16) { delMember(tn); p.sendMessage("\u00a7a[\u7ba1\u7406] \u5df2\u5220\u9664: " + tn); adminTarget.remove(p.getUniqueId()); openMain(p); return; }
            return;
        }
        if (t.equals(T_STORE)) {
            e.setCancelled(true);
            UUID u = p.getUniqueId();
            if (r == 49) { openMain(p); return; }
            if (r == 45) { int pg = pageMap.containsKey(u) ? pageMap.get(u) : 1; if (pg > 1) { pageMap.put(u, pg - 1); refreshPage(p); } return; }
            if (r == 52) { nextPage(p); return; }
            if (r >= 0 && r < PAGE_SIZE) {
                int pg = pageMap.containsKey(u) ? pageMap.get(u) : 1;
                int tot = activeSlots(getMember(p.getName())), us = usable(pg, tot);
                if (r >= us) return;
                ItemStack cl = e.getCurrentItem(), cu = e.getCursor();
                boolean cE = (cl == null || cl.getType() == Material.AIR);
                boolean cG = (!cE && cl.getType() == Material.GRAY_STAINED_GLASS_PANE);
                boolean cuE = (cu == null || cu.getType() == Material.AIR);
                if (cE && !cuE) {
                    int idx = (pg - 1) * PAGE_SIZE + r;
                    List<ItemStack> list = cacheMap.containsKey(u) ? cacheMap.get(u) : new ArrayList<ItemStack>();
                    while (list.size() <= idx) list.add(null);
                    list.set(idx, cu.clone()); e.getInventory().setItem(r, cu.clone()); e.setCursor(null); refreshPage(p);
                } else if (!cE && !cG && cuE) {
                    var ov = p.getInventory().addItem(cl);
                    if (ov.isEmpty()) { int idx = (pg - 1) * PAGE_SIZE + r; List<ItemStack> list = cacheMap.get(u); if (list != null && idx < list.size()) list.set(idx, null); e.getInventory().setItem(r, null); }
                    else p.sendMessage("\u00a7c[\u4ed3\u5e93] \u00a7f\u80cc\u5305\u5df2\u6ee1\uff01");
                } else if (!cE && !cG && !cuE) {
                    int idx = (pg - 1) * PAGE_SIZE + r;
                    List<ItemStack> list = cacheMap.containsKey(u) ? cacheMap.get(u) : new ArrayList<ItemStack>();
                    while (list.size() <= idx) list.add(null);
                    list.set(idx, cu.clone()); e.getInventory().setItem(r, cu.clone()); e.setCursor(cl); refreshPage(p);
                }
                return;
            }
            if (e.isShiftClick() && r >= 54) {
                ItemStack h = e.getCurrentItem();
                if (h != null && h.getType() != Material.AIR) {
                    int pg = pageMap.containsKey(u) ? pageMap.get(u) : 1;
                    int tot = activeSlots(getMember(p.getName())), us = usable(pg, tot);
                    List<ItemStack> list = cacheMap.containsKey(u) ? cacheMap.get(u) : new ArrayList<ItemStack>();
                    int start = (pg - 1) * PAGE_SIZE;
                    for (int i = 0; i < us; i++) { int idx = start + i; while (list.size() <= idx) list.add(null); if (list.get(idx) == null) { list.set(idx, h.clone()); e.setCurrentItem(null); refreshPage(p); return; } }
                    p.sendMessage("\u00a7c[\u4ed3\u5e93] \u00a7f\u5f53\u524d\u9875\u5df2\u6ee1\uff01");
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        String t = e.getView().getTitle();
        if (t.equals(T_STORE)) {
            List<ItemStack> list = cacheMap.remove(p.getUniqueId());
            pageMap.remove(p.getUniqueId());
            if (list != null) saveStorage(p.getName(), list);
        }
        if (t.equals(T_ADMIN)) {
            if (!adminEditType.containsKey(p.getUniqueId())) {
                adminTarget.remove(p.getUniqueId());
            }
        }
    }

    // ==================== Buy ====================

    private void buy(Player p, ShopItem item) {
        if (economy == null) { p.sendMessage("\u00a7c[\u5546\u57ce] \u00a7f\u7ecf\u6d4e\u4e0d\u53ef\u7528\uff01"); return; }
        if (item.id.startsWith("\u76f2\u76d2")) { openBox(p, item); return; }
        int st = stockMap.containsKey(item.id) ? stockMap.get(item.id) : item.stk;
        if (st == 0) { p.sendMessage("\u00a7c[\u5546\u57ce] \u00a7f\u5df2\u552e\u7f44\uff01"); return; }
        if (!economy.has(p, item.price)) { p.sendMessage("\u00a7c[\u5546\u57ce] \u00a7f\u4f59\u989d\u4e0d\u8db3\uff01\u9700\u8981\u00a7e" + fmt(item.price)); return; }
        economy.withdrawPlayer(p, item.price);
        ensureMember(p.getName());
        if (item.days > 0) upsert(p.getName(), 0, item.slots, item.days);
        else upsert(p.getName(), item.slots, 0, 0);
        if (st > 0) { st--; stockMap.put(item.id, st); saveShopFile(); }
        p.sendMessage("\u00a7a[\u5546\u57ce] \u00a7f" + item.name + " | " + (item.days == 0 ? "\u7ec8\u8eab" : item.days + "\u5929") + " | +" + item.slots + "\u683c | -$" + fmt(item.price) + " | \u4f59\u989d: \u00a7a$" + fmt(economy.getBalance(p)));
    }

    // ==================== Command + Tab ====================

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { if (s instanceof Player) openMain((Player) s); else showStat(s); return true; }
        switch (a[0].toLowerCase()) {
            case "reload": if (!s.hasPermission("cy.admin")) { s.sendMessage("\u00a7c\u6743\u9650\u4e0d\u8db3\uff01"); return true; } localVer = ""; updateCh = ""; adminPass = "qweasd"; loadShop(); s.sendMessage("\u00a7a\u91cd\u8f7d v" + localVer + " \u5546\u54c1=" + shopList.size()); break;
            case "status": if (!s.hasPermission("cy.admin")) { s.sendMessage("\u00a7c\u6743\u9650\u4e0d\u8db3\uff01"); return true; } showStat(s); break;
            case "info": if (!s.hasPermission("cy.admin")) { s.sendMessage("\u00a7c\u6743\u9650\u4e0d\u8db3\uff01"); return true; } if (a.length < 2) { s.sendMessage("\u00a7e/cy info <\u73a9\u5bb6>"); break; } showInfo(s, a[1]); break;
            case "remove": if (!s.hasPermission("cy.admin")) { s.sendMessage("\u00a7c\u6743\u9650\u4e0d\u8db3\uff01"); return true; } if (a.length < 2) { s.sendMessage("\u00a7e/cy remove <\u73a9\u5bb6>"); break; } s.sendMessage(delMember(a[1]) ? "\u00a7a\u5df2\u79fb\u9664" : "\u00a7c\u4e0d\u5b58\u5728"); break;
            case "shop": if (s instanceof Player) openShop((Player) s); break;
            case "update": if (!s.hasPermission("cy.admin")) { s.sendMessage("\u00a7c\u6743\u9650\u4e0d\u8db3\uff01"); return true; } checkUpdate(s); break;
            default: s.sendMessage("\u00a7c/cy [reload|status|info|remove|shop|update]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("cy.admin")) return Collections.emptyList();
        if (a.length == 1) return filterTab(Arrays.asList("reload", "status", "info", "remove", "shop", "update"), a[0]);
        if (a.length == 2 && (a[0].equalsIgnoreCase("info") || a[0].equalsIgnoreCase("remove"))) { List<String> names = new ArrayList<String>(); for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName()); return filterTab(names, a[1]); }
        return Collections.emptyList();
    }

    private List<String> filterTab(List<String> opts, String prefix) { List<String> r = new ArrayList<String>(); for (String o : opts) { if (o.toLowerCase().startsWith(prefix.toLowerCase())) r.add(o); } return r; }

    // ==================== Utils ====================

    private void fillBg(Inventory g) { ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, " "); for (int i = 0; i < g.getSize(); i++) g.setItem(i, gl); }

    private ItemStack mkItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat); ItemMeta im = it.getItemMeta();
        if (im != null) { im.setDisplayName(name); if (lore.length > 0) im.setLore(Arrays.asList(lore)); it.setItemMeta(im); }
        return it;
    }

    private void showStat(CommandSender s) {
        int c = 0, t = 0;
        try { Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*),COALESCE(SUM(permanent_slots+membership_slots),0) FROM members"); if (rs.next()) { c = rs.getInt(1); t = rs.getInt(2); } rs.close(); st.close(); } catch (Exception ignored) {}
        s.sendMessage("\u00a7e[CY] \u00a7fv" + localVer + " \u6210\u5458:" + c + " \u683c\u5b50:" + t + " \u5546\u54c1:" + shopList.size());
    }

    private void showInfo(CommandSender s, String n) {
        Map<String, Object> m = getMember(n);
        if (m.isEmpty()) { s.sendMessage("\u00a7c" + n + " \u4e0d\u662f\u6210\u5458"); return; }
        s.sendMessage("\u00a7e" + n + ": \u6c38\u4e45=" + ((Number) m.get("perm")).intValue() + " \u4f1a\u5458=" + ((Number) m.get("mem")).intValue() + " \u8fc7\u671f=" + fmtTime(((Number) m.get("exp")).longValue()));
    }

    private String fmt(double v) { return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v); }

    private String fmtTime(long e) {
        if (e == 0) return "\u7ec8\u8eab";
        long n = System.currentTimeMillis();
        if (e <= n) return "\u5df2\u8fc7\u671f";
        long r = e - n, d = r / 86400000L, h = (r % 86400000L) / 3600000L;
        return d > 0 ? d + "\u5929" + h + "\u5c0f\u65f6" : h + "\u5c0f\u65f6";
    }

    public static class ShopItem {
        public final String id, name;
        public final int days, slots, stk;
        public final double price;
        public ShopItem(String id, String name, int days, int slots, double price, int stk) { this.id = id; this.name = name; this.days = days; this.slots = slots; this.price = price; this.stk = stk; }
    }
}
