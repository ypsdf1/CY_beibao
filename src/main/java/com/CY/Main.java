package com.CY;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class Main extends JavaPlugin
        implements CommandExecutor, Listener, TabCompleter {

// ===== SECTION: ShopItem 内部类 =====

    public static class ShopItem {
        private final String id;
        private String name;
        private int days;
        private int slots;
        private String adminTeam = "";
        private String adminTag = "admin";
        private double price;
        private int stock;
        private Material logo;
        private String logoItem;
        private double discountRate;
        private long discountStart;
        private long discountEnd;
        private boolean blindBox;
        private int minDays, maxDays;
        private int minSlots, maxSlots;
        private int purchaseLimit;
        private long limitDuration;
        private String limitDurationStr;

        public ShopItem(String id, String name, int days,
                        int slots, double price, int stock,
                        Material logo, String logoItem,
                        double discountRate,
                        long discountStart, long discountEnd) {
            this(id, name, days, slots, price, stock,
                    logo, logoItem, discountRate,
                    discountStart, discountEnd,
                    false, days, days, slots, slots);
        }

        public ShopItem(String id, String name, int days,
                        int slots, double price, int stock,
                        Material logo, String logoItem,
                        double discountRate,
                        long discountStart, long discountEnd,
                        boolean blindBox,
                        int minDays, int maxDays,
                        int minSlots, int maxSlots) {
            this.id = id;
            this.name = name;
            this.days = days;
            this.slots = slots;
            this.price = price;
            this.stock = stock;
            this.logo = (logo != null) ? logo : Material.CHEST;
            this.logoItem = (logoItem != null) ? logoItem : "";
            this.discountRate = discountRate;
            this.discountStart = discountStart;
            this.discountEnd = discountEnd;
            this.blindBox = blindBox;
            this.minDays = minDays;
            this.maxDays = maxDays;
            this.minSlots = minSlots;
            this.maxSlots = maxSlots;
            this.purchaseLimit = -1;
            this.limitDuration = 0;
            this.limitDurationStr = "";
        }

        public ShopItem(String id, String name, int days,
                        int slots, double price, Material logo) {
            this(id, name, days, slots, price, -2, logo,
                    "", 1.0, 0, 0);
        }

        public boolean isLifetime() {
            return days == 0;
        }

        public boolean isDelisted() {
            return stock == -1;
        }

        public boolean isSoldOut() {
            return stock == 0;
        }

        public boolean isAvailable() {
            return stock > 0 || stock == -2;
        }

        public boolean hasCustomIcon() {
            return logoItem != null && !logoItem.isEmpty();
        }

        public boolean isBlindBox() {
            return blindBox;
        }

        public boolean hasPurchaseLimit() {
            return purchaseLimit > 0 && limitDuration > 0;
        }

        public double getEffectivePrice() {
            long now = System.currentTimeMillis();
            if (discountRate > 0 && discountRate < 1.0) {
                boolean started = (discountStart <= 0) || (discountStart <= now);
                boolean ended = (discountEnd > 0) && (discountEnd <= now);
                if (started && !ended)
                    return Math.round(price * discountRate);
            }
            return price;
        }

        public boolean isOnDiscount() {
            return getEffectivePrice() < price;
        }

        private static final String[] DATE_PATTERNS = {
                "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
                "yyyy-M-d HH:mm:ss", "yyyy-M-d HH:mm", "yyyy-M-d",
                "yyyy/M/d HH:mm:ss", "yyyy/M/d HH:mm", "yyyy/M/d",
                "yyyy年M月d日 HH:mm:ss",
                "yyyy年M月d日 HH:mm",
                "yyyy年M月d日"
        };

        public static long parseDate(String text) {
            if (text == null || text.trim().isEmpty()) return 0L;
            String t = text.trim();
            for (String p : DATE_PATTERNS) {
                try {
                    return new SimpleDateFormat(p).parse(t).getTime();
                } catch (Exception ignored) {
                }
            }
            return 0L;
        }

        public static long parseRelativeOrAbsolute(String input) {
            if (input == null || input.trim().isEmpty()) return 0L;
            String s = input.trim();
            if (s.startsWith("+")) {
                long now = System.currentTimeMillis();
                StringBuilder num = new StringBuilder();
                String unit = "";
                for (int i = 1; i < s.length(); i++) {
                    char c = s.charAt(i);
                    if (Character.isDigit(c)) num.append(c);
                    else {
                        unit = s.substring(i);
                        break;
                    }
                }
                if (num.length() == 0) return 0L;
                int n = Integer.parseInt(num.toString());
                if (unit.equals("d") || unit.equals("天"))
                    return now + n * 86400000L;
                if (unit.equals("h") || unit.equals("小时"))
                    return now + n * 3600000L;
                if (unit.equals("m") || unit.equals("分钟"))
                    return now + n * 60000L;
            }
            return parseDate(s);
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public void setName(String n) {
            name = n;
        }

        public int getDays() {
            return days;
        }

        public void setDays(int d) {
            days = d;
        }

        public int getSlots() {
            return slots;
        }

        public void setSlots(int s) {
            slots = s;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double p) {
            price = p;
        }

        public int getStock() {
            return stock;
        }

        public void setStock(int s) {
            stock = s;
        }

        public Material getLogo() {
            return logo;
        }

        public void setLogo(Material m) {
            logo = m;
        }

        public String getLogoItem() {
            return logoItem;
        }

        public void setLogoItem(String li) {
            logoItem = (li != null) ? li : "";
        }

        public double getDiscountRate() {
            return discountRate;
        }

        public void setDiscountRate(double r) {
            discountRate = r;
        }

        public long getDiscountStart() {
            return discountStart;
        }

        public void setDiscountStart(long t) {
            discountStart = t;
        }

        public long getDiscountEnd() {
            return discountEnd;
        }

        public void setDiscountEnd(long t) {
            discountEnd = t;
        }

        public boolean getBlindBox() {
            return blindBox;
        }

        public int getMinDays() {
            return minDays;
        }

        public int getMaxDays() {
            return maxDays;
        }

        public int getMinSlots() {
            return minSlots;
        }

        public int getMaxSlots() {
            return maxSlots;
        }

        public int getPurchaseLimit() {
            return purchaseLimit;
        }

        public void setPurchaseLimit(int l) {
            purchaseLimit = l;
        }

        public long getLimitDuration() {
            return limitDuration;
        }

        public void setLimitDuration(long d) {
            limitDuration = d;
        }

        public String getLimitDurationStr() {
            return limitDurationStr;
        }

        public void setLimitDurationStr(String s) {
            limitDurationStr = (s != null) ? s : "";
        }
    }

// ===== SECTION: 常量与字段 =====

    private static final int PAGE_SIZE = 45;
    private static final int INVENTORY_SIZE = 54;
    private static final int BTN_SLOT_PREV = 48;
    private static final int BTN_SLOT_INFO = 49;
    private static final int BTN_SLOT_NEXT = 52;
    private static final int BTN_SLOT_BACK = 53;
    private static final int FREE_SLOTS = 9;

    private static final double UNLOCK_PERCENT = 0.9;
    private static final long PURCHASE_CD = 200;
    private static final long DBL_CLICK_MS = 400;
    private static final long ADMIN_VERIFY_MS = 300000L;

    private static final String T_MAIN = "\u00a76\u00a7l\u4f1a\u5458\u4e2d\u5fc3";
    private static final String T_MY = "\u00a7a\u00a7l\u6211\u7684";
    private static final String T_SHOP = "\u00a7b\u00a7l\u5546\u57ce";
    private static final String T_STORE = "\u00a7d\u00a7l\u4e3b\u4ed3";
    private static final String T_ADMIN_MAIN = "\u00a7c\u00a7l\u7ba1\u7406\u9762\u677f";
    private static final String T_USER_MGMT = "\u00a7c\u00a7l\u7528\u6237\u7ba1\u7406";
    private static final String T_SHOP_MGMT = "\u00a76\u00a7l\u5546\u54c1\u7ba1\u7406";
    private static final String T_ITEM_EDIT = "\u00a7e\u00a7l\u5546\u54c1\u7f16\u8f91";
    private static final String T_NEW_ITEM = "\u00a7a\u00a7l\u53d1\u5e03\u5546\u54c1";

    private static final String API_GH =
            "https://api.github.com/repos/ypsdf1/CY_beibao/releases/latest";
    private static final String API_GE =
            "https://gitee.com/api/v5/repos/nihaoshidifu/cy_beibao/releases/latest";
    private static final String DL_GH =
            "https://github.com/ypsdf1/CY_beibao/releases";
    private static final String DL_GE =
            "https://gitee.com/nihaoshidifu/cy_beibao/releases";

    private static final Material[] LOGO_OPTIONS = {
            Material.DIAMOND, Material.GOLD_INGOT,
            Material.EMERALD, Material.NETHERITE_INGOT,
            Material.IRON_INGOT, Material.LAPIS_LAZULI,
            Material.REDSTONE, Material.COAL, Material.QUARTZ
    };

    private Economy economy;
    private Connection db;
    private NamespacedKey shopIconKey;

    private final List<ShopItem> shopList = new ArrayList<>();
    private final Map<String, Integer> stockMap = new HashMap<>();

    private final Map<UUID, Integer> pageMap = new HashMap<>();
    private final Map<UUID, List<ItemStack>> cacheMap = new HashMap<>();
    private final Map<UUID, Long> lastPurchase = new ConcurrentHashMap<>();
    private final Map<String, Long> lastItemPurchase = new ConcurrentHashMap<>();
    private final Map<String, Long> lastClickMap = new ConcurrentHashMap<>();
    private final Map<UUID, AdminState> adminStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> verifiedAdmins = new ConcurrentHashMap<>();
    private final Random rng = new Random();
    private String localVer  = "";
    private NamespacedKey paneSessionKey;
    private final Map<UUID, Long> storageSession =
            new ConcurrentHashMap<>();

 //   private String localVer = "";

    private String updateCh = "";
    private String adminPass = "qweasd";
    private String remoteVer = "";

    private String masterPluginName = "";
    private String sharedSecret = "";
    private String adminTeam = "";
    private String adminTag = "admin";
    private Object masterInstance = null;
    private Method masterPingMethod = null;
    private Method masterActMethod = null;
    private Method masterVerifyMethod = null;

    private final Map<String, Integer> lastReminderLevel = new ConcurrentHashMap<>();
    private final Set<String> autoRenewAttempted = ConcurrentHashMap.newKeySet();

// ===== SECTION: 内部类 - AdminState =====

    private static class AdminState {
        String targetPlayer = "";
        int editItemIdx = -1;
        String chatType = "";
        String tmpName = "";
        double tmpPrice = 0;
        int tmpStock = 0;
        int tmpSlots = 1;
        String tmpLogo = "CHEST";
        String tmpLogoItem = "";
        String tmpId = "";
        int tmpDays = 0;
        int tmpLimitCount = -1;
        long tmpLimitDuration = 0;
        String tmpLimitDurationStr = "";
    }

// ===== SECTION: 范围解析工具 =====

    private static int[] parseRange(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        String[] parts = trimmed.split("[\\-~\u5230,]");
        if (parts.length == 2) {
            int a = extractInt(parts[0]);
            int b = extractInt(parts[1]);
            if (a > 0 && b > 0) {
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
        }
        return null;
    }

    private static int extractInt(String s) {
        if (s == null) return 0;
        String trimmed = s.trim();
        String digits = trimmed.replaceAll("[^\\d-]", "").trim();
        if (digits.isEmpty() || digits.equals("-")) return 0;
        try {
            return Math.abs(Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

// ===== SECTION: 生命周期 =====

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        shopIconKey = new NamespacedKey(this, "shop_icon");
        paneSessionKey = new NamespacedKey(this, "pane_session");


        initDB();
        loadShop();
        setupEconomy();

        getCommand("cy").setExecutor(this);
        getCommand("cy").setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this,
                () -> checkAutoRenewal(), 200L, 200L);
        Bukkit.getScheduler().runTaskTimer(this,
                () -> checkExpiryReminder(), 600L, 600L);
        Bukkit.getScheduler().runTaskLater(this,
                () -> checkUpdate(null), 60L);

        if (masterPluginName != null && !masterPluginName.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> discoverMaster(), 100L);
            Bukkit.getScheduler().runTaskTimer(this,
                    () -> discoverMaster(), 6000L, 6000L);
        }

        getLogger().info("CY v" + localVer
                + " | \u5546\u54c1=" + shopList.size() + " | \u5c31\u7eea");
    }

    @Override
    public void onDisable() {
        try {
            if (db != null && !db.isClosed()) db.close();
        } catch (Exception ignored) {
        }
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().info("[Vault] \u672a\u627e\u5230\uff0c5\u79d2\u540e\u91cd\u8bd5");
            Bukkit.getScheduler().runTaskLater(this,
                    () -> setupEconomy(), 100L);
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
            getLogger().info("[Vault] \u5df2\u8fde\u63a5: " + economy.getName());
        } else {
            getLogger().warning("[Vault] \u672a\u627e\u5230\u7ecf\u6d4e\u63d0\u4f9b\u8005");
        }
    }

    private void checkAutoRenewal() {
        if (db == null || economy == null) return;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name, membership_slots, expire_time, plan_id "
                            + "FROM members WHERE auto_renew=1 AND membership_slots>0 AND expire_time>0");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String name = rs.getString("player_name");
                long exp = rs.getLong("expire_time");
                String lastPid = rs.getString("plan_id");
                long now = System.currentTimeMillis();
                long diff = exp - now;
                if (diff > 0 && diff < 86400000L) {
                    if (lastPid == null || lastPid.isEmpty()) {
                        setAutoRenew(name, false);
                        continue;
                    }
                    ShopItem matched = findItemById(lastPid);
                    if (matched == null) {
                        setAutoRenew(name, false);
                        Player online = Bukkit.getPlayer(name);
                        if (online != null && online.isOnline()) {
                            online.sendMessage("\u00a7c[CY] \u00a7f\u5957\u9910\u5df2\u4e0b\u67b6\uff0c\u81ea\u52a8\u7eed\u8d39\u5df2\u5173\u95ed");
                        }
                        continue;
                    }
                    if (matched.isBlindBox()) {
                        setAutoRenew(name, false);
                        Player online = Bukkit.getPlayer(name);
                        if (online != null && online.isOnline()) {
                            online.sendMessage("\u00a7c[CY] \u00a7f\u76f2\u76d2\u5957\u9910\u4e0d\u652f\u6301\u81ea\u52a8\u7eed\u8d39\uff0c\u5df2\u5173\u95ed");
                        }
                        continue;
                    }
                    double price = matched.getEffectivePrice();
                    int days = matched.getDays();
                    if (price <= 0 || days <= 0) {
                        setAutoRenew(name, false);
                        continue;
                    }
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                    if (economy.has(op, price)) {
                        economy.withdrawPlayer(op, price);
                        setExpire(name, exp + (long) days * 86400000L);
                        Player online = Bukkit.getPlayer(name);
                        if (online != null && online.isOnline()) {
                            online.sendMessage("\u00a7a[CY] \u00a7f\u81ea\u52a8\u7eed\u8d39\u6210\u529f\uff01\u5ef6\u957f" + days + "\u5929\uff0c\u6263\u8d39$" + fmt(price));
                        }
                    } else {
                        setAutoRenew(name, false);
                        Player online = Bukkit.getPlayer(name);
                        if (online != null && online.isOnline()) {
                            online.sendMessage("\u00a7c[CY] \u00a7f\u4f59\u989d\u4e0d\u8db3\uff08\u9700\u8981$" + fmt(price) + "\uff09\uff0c\u81ea\u52a8\u7eed\u8d39\u5df2\u5173\u95ed");
                        }
                    }
                }
            }
            rs.close();
            ps.close();
        } catch (Exception e) {
            getLogger().severe("[CY] autoRenew error: " + e.getMessage());
        }
    }

    private void checkExpiryReminder() {
        if (db == null) return;
        try {
            long now = System.currentTimeMillis();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name,expire_time FROM members "
                            + "WHERE membership_slots>0 AND expire_time>0 AND expire_time>?");
            ps.setLong(1, now);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String name = rs.getString("player_name");
                long expire = rs.getLong("expire_time");
                long diff = expire - now;
                Player player = Bukkit.getPlayer(name);
                if (player == null) continue;
                int level = 0;
                if (diff <= 180000L) level = 3;
                else if (diff <= 300000L) level = 2;
                else if (diff <= 600000L) level = 1;
                Integer prev = lastReminderLevel.getOrDefault(name, 0);
                if (level > 0 && level != prev) {
                    lastReminderLevel.put(name, level);
                    sendReminder(player, level, name);
                }
                if (level == 0 && prev != null && prev > 0) {
                    lastReminderLevel.remove(name);
                }
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            getLogger().warning("[reminder] " + e.getMessage());
        }
    }

    private void sendReminder(Player p, int level, String name) {
        Map<String, Object> m = getMember(name);
        String planName = (String) m.get("plan");
        ShopItem plan = (planName != null && !planName.isEmpty()) ? findItemById(planName) : null;
        String label = (plan != null) ? plan.getName() : "\u4f1a\u5458\u5957\u9910";
        boolean autoRenew = isAutoRenew(name);
        switch (level) {
            case 1:
                if (autoRenew) {
                    p.sendMessage("\u00a7e\u00a7l[CY] \u00a7f\u60a8\u7684" + label + "\u5c06\u572810\u5206\u949f\u5185\u5230\u671f\uff0c\u81ea\u52a8\u7eed\u8d39\u5904\u7406\u4e2d...");
                } else {
                    p.sendMessage("\u00a7e\u00a7l[CY] \u00a7f\u60a8\u7684" + label + "\u5c06\u572810\u5206\u949f\u540e\u8fc7\u671f\uff0c\u8bf7\u5c3d\u5feb\u7eed\u8d39\uff01");
                }
                break;
            case 2:
                if (autoRenew) {
                    p.sendMessage("\u00a76\u00a7l[CY] \u00a7e\u00a7l\u6ce8\u610f\uff01\u00a7f\u60a8\u7684" + label + "\u5c06\u57285\u5206\u949f\u5185\u5230\u671f\uff0c\u81ea\u52a8\u7eed\u8d39\u5904\u7406\u4e2d...");
                } else {
                    p.sendMessage("\u00a76\u00a7l[CY] \u00a7e\u00a7l\u6ce8\u610f\uff01\u00a7f\u60a8\u7684" + label + "\u5c06\u57285\u5206\u949f\u540e\u8fc7\u671f\uff01");
                }
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                break;
            case 3:
                String title = "\u00a7c\u00a7l\u26a0 \u5373\u5c06\u8fc7\u671f \u26a0";
                String sub = "\u00a7e" + label + " \u5c06\u57283\u5206\u949f\u5185\u8fc7\u671f";
                p.sendTitle(title, sub, 10, 60, 20);
                p.sendMessage("\u00a7c\u00a7l[CY] \u00a7f\u00a7l\u7d27\u6025\uff01\u60a8\u7684" + label + "\u5c06\u57283\u5206\u949f\u5185\u8fc7\u671f\uff01" + (autoRenew ? " \u81ea\u52a8\u7eed\u8d39\u5904\u7406\u4e2d..." : " \u8bf7\u7acb\u5373\u7eed\u8d39\uff01"));
                p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
                break;
        }
    }

// ===== SECTION: 数据库 =====

    private void initDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(getDataFolder(), "members.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS members ("
                    + "  player_name TEXT PRIMARY KEY,"
                    + "  permanent_slots INTEGER DEFAULT 0,"
                    + "  membership_slots INTEGER DEFAULT 0,"
                    + "  expire_time INTEGER DEFAULT 0,"
                    + "  activated INTEGER DEFAULT 0,"
                    + "  unlocked_pages INTEGER DEFAULT 1,"
                    + "  data TEXT DEFAULT '',"
                    + "  plan_id TEXT DEFAULT '',"
                    + "  free_claimed INTEGER DEFAULT 0,"
                    + "  auto_renew INTEGER DEFAULT 1)");
            st.execute("CREATE TABLE IF NOT EXISTS purchase_limits (player_name TEXT, item_id TEXT, count INTEGER DEFAULT 0, window_end INTEGER DEFAULT 0, PRIMARY KEY (player_name, item_id))");
            st.close();
            addColumnIfMissing("free_claimed", "INTEGER DEFAULT 0");
            addColumnIfMissing("plan_id", "TEXT DEFAULT ''");
            addColumnIfMissing("auto_renew", "INTEGER DEFAULT 1");
            getLogger().info("[DB] \u521d\u59cb\u5316\u6210\u529f");
        } catch (Exception e) {
            getLogger().severe("[DB] \u521d\u59cb\u5316\u5931\u8d25: " + e.getMessage());
        }
    }

    private void addColumnIfMissing(String col, String def) {
        try {
            db.createStatement().executeUpdate("ALTER TABLE members ADD COLUMN " + col + " " + def);
        } catch (SQLException ignored) {
        }
    }
// ===== SECTION: 仓库 cache 同步 =====

    /** 将虚拟背包当前页面的状态同步回 cacheMap */
    private void syncCacheFromInventory(Player p) {
        UUID u = p.getUniqueId();
        List<ItemStack> list = cacheMap.get(u);
        if (list == null) return;
        Inventory top = p.getOpenInventory().getTopInventory();
        int pg = pageMap.getOrDefault(u, 1);
        int start = (pg - 1) * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int cacheIdx = start + i;
            if (cacheIdx >= list.size()) break;
            list.set(cacheIdx, top.getItem(i));
        }
    }

    /** 导航按钮点击处理 */
    private void handleNavClick(Player p, int rawSlot) {
        UUID u = p.getUniqueId();
        if (rawSlot == BTN_SLOT_PREV) {
            int pg = pageMap.getOrDefault(u, 1);
            if (pg > 1) {
                syncCacheFromInventory(p);
                pageMap.put(u, pg - 1);
                refreshPage(p);
            }
        } else if (rawSlot == BTN_SLOT_NEXT) {
            syncCacheFromInventory(p);
            nextPage(p);
        } else if (rawSlot == BTN_SLOT_BACK) {
            syncCacheFromInventory(p);
            p.closeInventory();
        }
    }

    private void checkDB() {
        try {
            if (db == null || db.isClosed()) {
                getLogger().warning("[DB] \u8fde\u63a5\u4e22\u5931\uff0c\u91cd\u65b0\u521d\u59cb\u5316");
                initDB();
            }
        } catch (SQLException e) {
            initDB();
        }
    }

    private Map<String, Object> getMember(String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("_exists", false);
        r.put("perm", 0);
        r.put("mem", 0);
        r.put("exp", 0L);
        r.put("act", 0L);
        r.put("up", 1);
        r.put("data", "");
        r.put("plan", "");
        r.put("free", 0);
        r.put("autorenew", 1);
        if (db == null) return r;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT * FROM members WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                r.put("_exists", true);
                r.put("perm", rs.getInt("permanent_slots"));
                r.put("mem", rs.getInt("membership_slots"));
                r.put("exp", rs.getLong("expire_time"));
                r.put("act", rs.getLong("activated"));
                r.put("up", rs.getInt("unlocked_pages"));
                r.put("data", rs.getString("data"));
                r.put("plan", rs.getString("plan_id"));
                r.put("free", rs.getInt("free_claimed"));
                r.put("autorenew", rs.getInt("auto_renew"));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            getLogger().severe("[DB] getMember: " + e.getMessage());
        }
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
        } catch (SQLException e) {
            return false;
        }
    }

    private void ensureMember(String name) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO members (player_name) VALUES (?)");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (Exception ignored) {
        }
    }

    private void upsert(String name, int permSlots, int memSlots, int days, String purchaseId) {
        if (db == null) return;
        try {
            PreparedStatement ins = db.prepareStatement("INSERT OR IGNORE INTO members (player_name) VALUES (?)");
            ins.setString(1, name);
            ins.executeUpdate();
            ins.close();
            Map<String, Object> m = getMember(name);
            int curPerm = ((Number) m.get("perm")).intValue();
            int curMem = ((Number) m.get("mem")).intValue();
            long curExp = ((Number) m.get("exp")).longValue();
            long now = System.currentTimeMillis();
            int newPerm = curPerm + permSlots;
            int newMem = curMem + memSlots;
            long newExp = curExp;
            if (days > 0) {
                if (curExp == 0 && curMem == 0) {
                    newExp = now + (long) days * 86400000L;
                } else if (curExp <= now && curExp != 0) {
                    newExp = now + (long) days * 86400000L;
                } else if (curExp > now) {
                    newExp = curExp + (long) days * 86400000L;
                } else {
                    newExp = now + (long) days * 86400000L;
                }
            }
            String planId = (purchaseId != null && !purchaseId.isEmpty()) ? purchaseId : (String) m.getOrDefault("plan", "");
            PreparedStatement ps = db.prepareStatement("UPDATE members SET permanent_slots=?, membership_slots=?, expire_time=?, plan_id=? WHERE player_name=?");
            ps.setInt(1, newPerm);
            ps.setInt(2, newMem);
            ps.setLong(3, newExp);
            ps.setString(4, planId);
            ps.setString(5, name);
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            getLogger().severe("[CY] upsert error: " + e.getMessage());
        }
    }

    private void setExpire(String name, long expire) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET expire_time = ? WHERE player_name = ?");
            ps.setLong(1, expire);
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            getLogger().severe("[CY] setExpire error: " + e.getMessage());
        }
    }

    private void setSlot(String name, String type, int val) {
        if (db == null) return;
        String col = "perm".equals(type) ? "permanent_slots" : "membership_slots";
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET " + col + "=? WHERE player_name=?");
            ps.setInt(1, Math.max(0, val));
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {
        }
    }

    private void setUpPages(String name, int p) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET unlocked_pages=? WHERE player_name=?");
            ps.setInt(1, p);
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {
        }
    }

    private boolean isFreeClaimed(String name) {
        if (db == null) return false;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT free_claimed FROM members WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            boolean claimed = rs.next() && rs.getInt("free_claimed") == 1;
            rs.close();
            ps.close();
            return claimed;
        } catch (SQLException e) {
            return false;
        }
    }

    private void setFreeClaimed(String name) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET free_claimed=1 WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {
        }
    }

    private boolean isAutoRenew(String name) {
        if (db == null) return true;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT auto_renew FROM members WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            boolean val = !rs.next() || rs.getInt("auto_renew") == 1;
            rs.close();
            ps.close();
            return val;
        } catch (SQLException e) {
            return true;
        }
    }

    private void setAutoRenew(String name, boolean val) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET auto_renew=? WHERE player_name=?");
            ps.setInt(1, val ? 1 : 0);
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {
        }
    }

    private boolean delMember(String name) {
        if (db == null) return false;
        try {
            PreparedStatement pl = db.prepareStatement("DELETE FROM purchase_limits WHERE player_name=?");
            pl.setString(1, name);
            pl.executeUpdate();
            pl.close();
            PreparedStatement ps = db.prepareStatement("DELETE FROM members WHERE player_name=?");
            ps.setString(1, name);
            boolean ok = ps.executeUpdate() > 0;
            ps.close();
            return ok;
        } catch (SQLException e) {
            return false;
        }
    }

    private int memberCount() {
        if (db == null) return 0;
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM members");
            int c = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            st.close();
            return c;
        } catch (Exception e) {
            return 0;
        }
    }

    // ===== SECTION: 限购方法 =====
    private int getPurchaseCount(String name, String itemId) {
        if (db == null) return 0;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT count,window_end FROM purchase_limits "
                            + "WHERE player_name=? AND item_id=?");
            ps.setString(1, name);
            ps.setString(2, itemId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int count = rs.getInt("count");
                long windowEnd = rs.getLong("window_end");
                rs.close();
                ps.close();
                if (windowEnd <= 0
                        || System.currentTimeMillis() >= windowEnd) {
                    resetLimitWindow(name, itemId);
                    return 0;
                }
                return count;
            }
            rs.close();
            ps.close();
            return 0;
        } catch (SQLException e) {
            return 0;
        }
    }


    private int[] getPurchaseInfo(String name, String itemId) {
        if (db == null) return new int[]{0, 0};
        try {
            PreparedStatement ps = db.prepareStatement("SELECT count,window_end FROM purchase_limits WHERE player_name=? AND item_id=?");
            ps.setString(1, name);
            ps.setString(2, itemId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long windowEnd = rs.getLong("window_end");
                int count = rs.getInt("count");
                long now = System.currentTimeMillis();
                rs.close();
                ps.close();
                if (now >= windowEnd) {
                    resetLimitWindow(name, itemId);
                    return new int[]{0, 0};
                }
                return new int[]{count, (int) (windowEnd - now)};
            }
            rs.close();
            ps.close();
            return new int[]{0, 0};
        } catch (SQLException e) {
            return new int[]{0, 0};
        }
    }

    private void recordPurchase(String name, String itemId,
                                long duration) {
        if (db == null) return;
        try {
            long now = System.currentTimeMillis();
            long newEnd = now + duration;
            PreparedStatement ch = db.prepareStatement(
                    "SELECT count,window_end "
                            + "FROM purchase_limits "
                            + "WHERE player_name=? AND item_id=?");
            ch.setString(1, name);
            ch.setString(2, itemId);
            ResultSet rs = ch.executeQuery();
            if (rs.next()) {
                long existEnd = rs.getLong("window_end");
                int existCnt = rs.getInt("count");
                rs.close();
                ch.close();
                if (now < existEnd) {
                    PreparedStatement u = db.prepareStatement(
                            "UPDATE purchase_limits "
                                    + "SET count=? "
                                    + "WHERE player_name=? AND item_id=?");
                    u.setInt(1, existCnt + 1);
                    u.setString(2, name);
                    u.setString(3, itemId);
                    u.executeUpdate();
                    u.close();
                } else {
                    PreparedStatement u = db.prepareStatement(
                            "UPDATE purchase_limits "
                                    + "SET count=1, window_end=? "
                                    + "WHERE player_name=? AND item_id=?");
                    u.setLong(1, newEnd);
                    u.setString(2, name);
                    u.setString(3, itemId);
                    u.executeUpdate();
                    u.close();
                }
            } else {
                rs.close();
                ch.close();
                PreparedStatement ins = db.prepareStatement(
                        "INSERT INTO purchase_limits "
                                + "(player_name, item_id, count, window_end) "
                                + "VALUES (?, ?, 1, ?)");
                ins.setString(1, name);
                ins.setString(2, itemId);
                ins.setLong(3, newEnd);
                ins.executeUpdate();
                ins.close();
            }
        } catch (SQLException e) { /* ignore */ }
    }


    private void resetLimitWindow(String name, String itemId) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("DELETE FROM purchase_limits WHERE player_name=? AND item_id=?");
            ps.setString(1, name);
            ps.setString(2, itemId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {
        }
    }

    private void resetAllLimits(String name) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM purchase_limits WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {
        }
    }

    private String fmtLimitTime(long ms) {
        if (ms <= 0) return "\u5df2\u7ed3\u675f";
        long totalSec = (ms + 999) / 1000;
        if (totalSec <= 60) return totalSec + "\u79d2";
        long totalMin = (totalSec + 59) / 60;
        long hr = totalMin / 60;
        long min = totalMin % 60;
        if (hr >= 24) {
            long day = hr / 24;
            hr = hr % 24;
            return day + "\u5929" + hr + "\u5c0f\u65f6";
        }
        if (hr > 0) return hr + "\u5c0f\u65f6" + min + "\u5206\u949f";
        return totalMin + "\u5206\u949f";
    }

    private static final String LIMIT_UNIT_RE = "(\u5206\u949f|\u5206|\u5c0f\u65f6|\u65f6|\u5929|\u5468|\u6708|m|h|d|w|W)";

    public static int[] parseLimitStrategy(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String t = text.trim();
        if (t.equals("-1") || t.equals("\u65e0") || t.equals("\u4e0d\u9650\u8d2d")) return null;
        if (t.startsWith("\u6bcf")) t = t.substring(1);
        Matcher m0 = Pattern.compile(LIMIT_UNIT_RE + "\\D*?(\\d+)\\s*\u6b21").matcher(t);
        if (m0.find()) return new int[]{(int) getLimitUnitMs(m0.group(1)), Integer.parseInt(m0.group(2))};
        Matcher m1 = Pattern.compile("(\\d+)\\s*" + LIMIT_UNIT_RE + "\\D*?(\\d+)\\s*\u6b21").matcher(t);
        if (m1.find())
            return new int[]{(int) (Integer.parseInt(m1.group(1)) * getLimitUnitMs(m1.group(2))), Integer.parseInt(m1.group(3))};
        Matcher m2 = Pattern.compile("(\\d+)\\s*\u6b21\\D*?(\\d+)\\s*" + LIMIT_UNIT_RE).matcher(t);
        if (m2.find())
            return new int[]{(int) (Integer.parseInt(m2.group(2)) * getLimitUnitMs(m2.group(3))), Integer.parseInt(m2.group(1))};
        Matcher m3 = Pattern.compile("(\\d+)\\s*\u6b21").matcher(t);
        if (m3.find()) return new int[]{60000, Integer.parseInt(m3.group(1))};
        return null;
    }

    private static long getLimitUnitMs(String unit) {
        if (unit.equals("\u5206\u949f") || unit.equals("\u5206") || unit.equals("m")) return 60000L;
        if (unit.equals("\u5c0f\u65f6") || unit.equals("\u65f6") || unit.equals("h")) return 3600000L;
        if (unit.equals("\u5929") || unit.equals("d")) return 86400000L;
        if (unit.equals("\u5468") || unit.equals("w") || unit.equals("W")) return 604800000L;
        if (unit.equals("\u6708")) return 2592000000L;
        return 60000L;
    }

    public static long parseChineseTime(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        String t = text.trim().replaceAll("\\s+", "");
        if (t.endsWith("\u5206\u949f") || t.endsWith("\u5206")) {
            String num = t.replaceAll("[^0-9]", "");
            if (!num.isEmpty()) return Long.parseLong(num) * 60000L;
            return 0;
        }
        if (t.endsWith("\u5c0f\u65f6") || t.endsWith("\u65f6")) {
            String num = t.replaceAll("[^0-9]", "");
            if (!num.isEmpty()) return Long.parseLong(num) * 3600000L;
            return 0;
        }
        if (t.endsWith("\u5929")) {
            String num = t.replaceAll("[^0-9]", "");
            if (!num.isEmpty()) return Long.parseLong(num) * 86400000L;
            return 0;
        }
        if (t.endsWith("\u5468")) {
            String num = t.replaceAll("[^0-9]", "");
            if (!num.isEmpty()) return Long.parseLong(num) * 604800000L;
            return 0;
        }
        if (t.endsWith("\u6708")) {
            String num = t.replaceAll("[^0-9]", "");
            if (!num.isEmpty()) return Long.parseLong(num) * 2592000000L;
            return 0;
        }
        try {
            return Long.parseLong(t) * 60000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String fmtChineseTime(long ms) {
        if (ms <= 0) return "";
        long totalMin = (ms + 59999) / 60000;
        long hr = totalMin / 60;
        long min = totalMin % 60;
        if (hr >= 24) {
            long day = hr / 24;
            hr = hr % 24;
            return day + "\u5929" + hr + "\u5c0f\u65f6";
        }
        if (hr > 0) return hr + "\u5c0f\u65f6" + min + "\u5206\u949f";
        return totalMin + "\u5206\u949f";
    }

// ===== SECTION: 商品读取与保存 =====

    private void loadShop() {
        shopList.clear();
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        if (!f.exists()) {
            writeDefaultFile();
        }
        try {
            List<String> rawLines = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) rawLines.add(line);
            r.close();
            boolean parsing = false;
            String cId = "", cName = "", cLogoItem = "", cDiscountStart = "", cDiscountEnd = "";
            int cDays = 0, cSlots = 0, cStock = -2;
            double cPrice = 0, cDiscount = 1.0;
            Material cIcon = Material.ENDER_CHEST;
            boolean cBlindBox = false;
            int cMinDays = 0, cMaxDays = 0, cMinSlots = 0, cMaxSlots = 0;
            int cLimitCount = -1;
            long cLimitDuration = 0;
            String cLimitDurationStr = "";

            for (int i = 0; i < rawLines.size(); i++) {
                String trimmed = rawLines.get(i).trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equals("--")) {
                    if (parsing) {
                        ShopItem item = new ShopItem(cId, cName, cDays, cSlots, cPrice, cStock, cIcon, cLogoItem, cDiscount, parseTs(cDiscountStart), parseTs(cDiscountEnd), cBlindBox, cMinDays, cMaxDays, cMinSlots, cMaxSlots);
                        item.setPurchaseLimit(cLimitCount);
                        item.setLimitDuration(cLimitDuration);
                        item.setLimitDurationStr(cLimitDurationStr);
                        shopList.add(item);
                    }
                    parsing = false;
                    cId = "";
                    cName = "";
                    cLogoItem = "";
                    cDiscountStart = "";
                    cDiscountEnd = "";
                    cDays = 0;
                    cSlots = 0;
                    cPrice = 0;
                    cStock = -2;
                    cDiscount = 1.0;
                    cIcon = Material.ENDER_CHEST;
                    cBlindBox = false;
                    cMinDays = 0;
                    cMaxDays = 0;
                    cMinSlots = 0;
                    cMaxSlots = 0;
                    cLimitCount = -1;
                    cLimitDuration = 0;
                    cLimitDurationStr = "";
                    continue;
                }
                String[] kv = trimmed.replace("\uff1a", ":").split(":", 2);
                if (kv.length < 2) continue;
                String k = kv[0].trim();
                String v = kv[1].trim();
                if (k.equals("\u7248\u672c\u53f7")) {
                    localVer = v;
                    continue;
                }
                if (k.equals("\u66f4\u65b0\u901a\u9053")) {
                    updateCh = v;
                    continue;
                }
                if (k.equals("\u7ba1\u7406\u5bc6\u7801")) {
                    adminPass = v;
                    continue;
                }
                if (k.equals("\u4e3b\u63a7\u63d2\u4ef6")) {
                    masterPluginName = v;
                    continue;
                }
                if (k.equals("\u5171\u4eab\u5bc6\u94a5")) {
                    sharedSecret = v;
                    continue;
                }
                if (k.equals("\u7ba1\u7406\u56e2\u961f")) {
                    adminTeam = v;
                    continue;
                }
                if (k.equals("\u7ba1\u7406\u6807\u7b7e")) {
                    adminTag = v;
                    continue;
                }
                switch (k) {
                    case "ID":
                        cId = v;
                        parsing = true;
                        break;
                    case "\u54c1\u540d":
                        cName = v;
                        break;
                    case "\u5929\u6570": {
                        int[] range = parseRange(v);
                        if (range != null) {
                            cBlindBox = true;
                            cMinDays = range[0];
                            cMaxDays = range[1];
                            cDays = range[1];
                        } else {
                            cDays = parseIntSafe(v);
                            cMinDays = cDays;
                            cMaxDays = cDays;
                        }
                        break;
                    }
                    case "\u683c\u5b50":
                    case "\u683c\u53e3": {
                        int[] range = parseRange(v);
                        if (range != null) {
                            cBlindBox = true;
                            cMinSlots = range[0];
                            cMaxSlots = range[1];
                            cSlots = range[1];
                        } else {
                            cSlots = parseIntSafe(v);
                            cMinSlots = cSlots;
                            cMaxSlots = cSlots;
                        }
                        break;
                    }
                    case "\u4ef7\u683c":
                        cPrice = parseDoubleSafe(v);
                        break;
                    case "\u5e93\u5b58":
                        cStock = parseIntSafe(v);
                        break;
                    case "\u56fe\u6807":
                        cIcon = parseMaterialSafe(v, Material.ENDER_CHEST);
                        break;
                    case "\u56fe\u6807\u7269\u54c1":
                        cLogoItem = v;
                        break;
                    case "\u6298\u6263\u7ed3\u675f":
                        cDiscountEnd = v;
                        break;
                    case "\u9650\u8d2d\u7b56\u7565": {
                        int[] parsed = parseLimitStrategy(v);
                        if (parsed != null) {
                            cLimitDuration = parsed[0];
                            cLimitCount = parsed[1];
                            cLimitDurationStr = v.trim();
                        } else {
                            cLimitCount = -1;
                            cLimitDuration = 0;
                            cLimitDurationStr = "";
                        }
                        break;
                    }
                    case "\u9650\u8d2d\u6b21\u6570":
                        cLimitCount = parseIntSafe(v);
                        break;
                    case "\u9650\u8d2d\u65f6\u95f4":
                        cLimitDurationStr = v;
                        cLimitDuration = parseChineseTime(v);
                        break;
                }
            }
            if (parsing) {
                shopList.add(new ShopItem(cId, cName, cDays, cSlots, cPrice, cStock, cIcon, cLogoItem, cDiscount, parseTs(cDiscountStart), parseTs(cDiscountEnd), cBlindBox, cMinDays, cMaxDays, cMinSlots, cMaxSlots));
            }
        } catch (Exception e) {
            getLogger().severe("[\u5546\u54c1] \u52a0\u8f7d\u5f02\u5e38: " + e.getMessage());
        }
        stockMap.clear();
        for (ShopItem it : shopList) {
            stockMap.put(it.getId(), it.getStock());
        }
    }

    private long parseTs(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        return ShopItem.parseDate(s.trim());
    }

    private void writeDefaultFile() {
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        if (f.exists() && f.length() > 0) return;
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            pw.println("\u7248\u672c\u53f7: 1.0");
            pw.println("\u66f4\u65b0\u901a\u9053: GE");
            pw.println("\u7ba1\u7406\u5bc6\u7801: qweasd");
            pw.println("\u4e3b\u63a7\u63d2\u4ef6: ");
            pw.println("\u5171\u4eab\u5bc6\u94a5: ");
            pw.println("\u7ba1\u7406\u56e2\u961f: ");
            pw.println("\u7ba1\u7406\u6807\u7b7e: admin");
            pw.println();
            pw.println("ID: 100001");
            pw.println("\u54c1\u540d: \u57fa\u7840\u683c\u5b50");
            pw.println("\u5929\u6570: 7");
            pw.println("\u683c\u5b50: 1");
            pw.println("\u4ef7\u683c: 100");
            pw.println("\u5e93\u5b58: 50");
            pw.println("\u56fe\u6807: ENDER_CHEST");
            pw.println("\u56fe\u6807\u7269\u54c1: ");
            pw.println("\u6298\u6263: 1.0");
            pw.println("\u6298\u6263\u5f00\u59cb: ");
            pw.println("\u6298\u6263\u7ed3\u675f: ");
            pw.println("\u9650\u8d2d\u7b56\u7565: -1");
            pw.println("--");
            pw.println();
            pw.println("ID: 200001");
            pw.println("\u54c1\u540d: \u8c6a\u534e\u76f2\u76d2");
            pw.println("\u5929\u6570: 7-30");
            pw.println("\u683c\u5b50: 10-50");
            pw.println("\u4ef7\u683c: 500");
            pw.println("\u5e93\u5b58: -2");
            pw.println("\u56fe\u6807: CHEST");
            pw.println("\u56fe\u6807\u7269\u54c1: ");
            pw.println("\u6298\u6263: 1.0");
            pw.println("\u6298\u6263\u5f00\u59cb: ");
            pw.println("\u6298\u6263\u7ed3\u675f: ");
            pw.println("--");
        } catch (IOException ignored) {
        }
    }

    private void saveShopFile() {
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            pw.println("\u7248\u672c\u53f7: " + localVer);
            pw.println("\u66f4\u65b0\u901a\u9053: " + updateCh);
            pw.println("\u7ba1\u7406\u5bc6\u7801: " + adminPass);
            pw.println("\u4e3b\u63a7\u63d2\u4ef6: " + masterPluginName);
            pw.println("\u5171\u4eab\u5bc6\u94a5: " + sharedSecret);
            pw.println("\u7ba1\u7406\u56e2\u961f: " + adminTeam);
            pw.println("\u7ba1\u7406\u6807\u7b7e: " + adminTag);
            pw.println();
            for (ShopItem it : shopList) {
                int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock();
                pw.println("ID: " + it.getId());
                pw.println("\u54c1\u540d: " + it.getName());
                if (it.isBlindBox()) {
                    pw.println("\u5929\u6570: " + it.getMinDays() + "-" + it.getMaxDays());
                    pw.println("\u683c\u5b50: " + it.getMinSlots() + "-" + it.getMaxSlots());
                } else {
                    pw.println("\u5929\u6570: " + it.getDays());
                    pw.println("\u683c\u5b50: " + it.getSlots());
                }
                pw.println("\u4ef7\u683c: " + (int) it.getPrice());
                pw.println("\u5e93\u5b58: " + st);
                pw.println("\u56fe\u6807: " + it.getLogo().name());
                pw.println("\u56fe\u6807\u7269\u54c1: " + (it.hasCustomIcon() ? it.getLogoItem() : ""));
                pw.println("\u6298\u6263: " + it.getDiscountRate());
                pw.println("\u6298\u6263\u5f00\u59cb: " + (it.getDiscountStart() > 0 ? fmtDate(it.getDiscountStart()) : ""));
                pw.println("\u6298\u6263\u7ed3\u675f: " + (it.getDiscountEnd() > 0 ? fmtDate(it.getDiscountEnd()) : ""));
                if (it.getPurchaseLimit() > 0) {
                    pw.println("\u9650\u8d2d\u7b56\u7565: " + it.getLimitDurationStr());
                }
                pw.println("--");
            }
        } catch (IOException e) {
            getLogger().severe("[\u5546\u54c1] \u5199\u5165\u5931\u8d25: " + e.getMessage());
        }
    }

    private ShopItem findItemById(String id) {
        for (ShopItem it : shopList) {
            if (it.getId().equals(id)) return it;
        }
        return null;
    }

    private ShopItem findItem(String query) {
        if (query == null || query.isEmpty()) return null;
        for (ShopItem it : shopList) if (it.getId().equals(query)) return it;
        for (ShopItem it : shopList) if (it.getName().equals(query)) return it;
        for (ShopItem it : shopList) if (it.getId().contains(query)) return it;
        for (ShopItem it : shopList) if (it.getName().contains(query)) return it;
        return null;
    }

// ===== SECTION: 仓库存储 =====
private ItemStack makeLockedPane(Player p) {
    ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE,
            "§7锁定");
    ItemMeta meta = gl.getItemMeta();
    long session = storageSession
            .getOrDefault(p.getUniqueId(), 0L);
    meta.getPersistentDataContainer()
            .set(paneSessionKey, PersistentDataType.LONG,
                    session);
    gl.setItemMeta(meta);
    return gl;
}

    private boolean isLockedPane(Player p, ItemStack item) {
        if (item == null
                || item.getType()
                != Material.GRAY_STAINED_GLASS_PANE)
            return false;
        Long session = storageSession.get(p.getUniqueId());
        if (session == null || session == 0L) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Long val = meta.getPersistentDataContainer()
                .get(paneSessionKey, PersistentDataType.LONG);
        return val != null && val == session;
    }

    private List<ItemStack> loadStorage(String name) {
        List<ItemStack> list = new ArrayList<>();
        if (db == null) return list;
        try {
            PreparedStatement ps = db.prepareStatement("SELECT data FROM members WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String b64 = rs.getString("data");
                if (b64 != null && !b64.isEmpty()) {
                    Collections.addAll(list, decodeItems(b64));
                }
            }
            rs.close();
            ps.close();
        } catch (Exception ignored) {
        }
        return list;
    }

    private void saveStorage(String name, List<ItemStack> list) {
        if (db == null) return;
        try {
            String b64 = encodeItems(list.toArray(new ItemStack[0]));
            PreparedStatement ps = db.prepareStatement("UPDATE members SET data=? WHERE player_name=?");
            ps.setString(1, b64);
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (Exception ignored) {
        }
    }

    private int countStorageItems(String name) {
        List<ItemStack> list = loadStorage(name);
        int c = 0;
        for (ItemStack it : list) {
            if (it != null && it.getType() != Material.AIR && it.getType() != Material.GRAY_STAINED_GLASS_PANE) {
                c++;
            }
        }
        return c;
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
        ois.close();
        return arr;
    }

    private boolean isShopIcon(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(shopIconKey, PersistentDataType.STRING);
    }

    private String encodeSingleItem(ItemStack item) {
        if (item == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos);
            oos.writeObject(item);
            oos.close();
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private ItemStack decodeSingleItem(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(base64));
            BukkitObjectInputStream ois = new BukkitObjectInputStream(bis);
            ItemStack item = (ItemStack) ois.readObject();
            ois.close();
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    private ItemStack mkShopDisplay(ShopItem it, String displayName, String... extraLore) {
        if (it.hasCustomIcon()) {
            ItemStack custom = decodeSingleItem(it.getLogoItem());
            if (custom != null) {
                ItemStack display = custom.clone();
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(displayName);
                    if (extraLore.length > 0) meta.setLore(Arrays.asList(extraLore));
                    display.setItemMeta(meta);
                }
                return display;
            }
        }
        return mkItem(it.getLogo(), displayName, extraLore);
    }

// ===== SECTION: 工具方法 =====

    private boolean isAdmin(Player p) {
        if (adminTag != null && !adminTag.isEmpty() && p.getScoreboardTags().contains(adminTag)) return true;
        if (adminTeam != null && !adminTeam.isEmpty()) {
            try {
                org.bukkit.scoreboard.Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(adminTeam);
                if (team != null && team.hasEntry(p.getName())) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean isVerifiedAdmin(Player p) {
        Long t = verifiedAdmins.get(p.getUniqueId());
        return t != null && (System.currentTimeMillis() - t) < ADMIN_VERIFY_MS;
    }

    private AdminState getOrCreateState(Player p) {
        return adminStates.computeIfAbsent(p.getUniqueId(), k -> new AdminState());
    }

    private String colorize(String s) {
        if (s == null) return "";
        return s.replaceAll("&([0-9a-fk-orA-FK-OR])", "\u00a7$1");
    }

    private void fillBg(Inventory g) {
        ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < g.getSize(); i++) g.setItem(i, gl);
    }

    private ItemStack mkItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore.length > 0) im.setLore(Arrays.asList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    private String fmt(double v) {
        return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private String fmtTime(long expire) {
        if (expire == 0) return "\u7ec8\u8eab";
        long now = System.currentTimeMillis();
        if (expire <= now) return "\u5df2\u8fc7\u671f";
        long r = expire - now;
        long d = r / 86400000L;
        long h = (r % 86400000L) / 3600000L;
        return d > 0 ? d + "\u5929" + h + "\u5c0f\u65f6" : h + "\u5c0f\u65f6";
    }

    private String fmtDate(long ts) {
        if (ts <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ts));
    }

    private boolean isCancel(String msg) {
        String m = msg.toLowerCase().trim();
        return m.equals("0") || m.equals("exit") || m.equals("\u9000\u51fa");
    }

    private long parseTimeInput(String text) {
        return ShopItem.parseDate(text);
    }

    private long parseDateTime(String input) {
        return ShopItem.parseRelativeOrAbsolute(input);
    }

    private double evalMath(String input) {
        String expr = input.replaceAll("\\s+", "").trim();
        try {
            return Double.parseDouble(expr);
        } catch (Exception ignored) {
        }
        try {
            List<Double> nums = new ArrayList<>();
            List<Character> ops = new ArrayList<>();
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < expr.length(); i++) {
                char c = expr.charAt(i);
                if ((c == '+' || c == '-' || c == '*' || c == '/') && i > 0 && buf.length() > 0) {
                    nums.add(Double.parseDouble(buf.toString()));
                    ops.add(c);
                    buf = new StringBuilder();
                } else {
                    buf.append(c);
                }
            }
            if (buf.length() > 0) nums.add(Double.parseDouble(buf.toString()));
            for (int i = 0; i < ops.size(); ) {
                if (ops.get(i) == '*' || ops.get(i) == '/') {
                    double a = nums.get(i), b = nums.get(i + 1);
                    nums.set(i, ops.get(i) == '*' ? a * b : (b == 0 ? 0 : a / b));
                    nums.remove(i + 1);
                    ops.remove(i);
                } else {
                    i++;
                }
            }
            double r = nums.get(0);
            for (int i = 0; i < ops.size(); i++) {
                r = ops.get(i) == '+' ? r + nums.get(i + 1) : r - nums.get(i + 1);
            }
            return r;
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean isNumeric(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> filterTab(List<String> opts, String prefix) {
        List<String> r = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase().startsWith(prefix.toLowerCase())) r.add(o);
        }
        return r;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private Material parseMaterialSafe(String s, Material fallback) {
        try {
            return Material.valueOf(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void showStat(CommandSender s) {
        int c = 0, t = 0;
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery("SELECT COUNT(*),COALESCE(SUM(permanent_slots+membership_slots),0) FROM members");
            if (rs.next()) {
                c = rs.getInt(1);
                t = rs.getInt(2);
            }
            rs.close();
            st.close();
        } catch (Exception ignored) {
        }
        s.sendMessage("\u00a7e[CY] \u00a7fv" + localVer + " \u6210\u5458:" + c + " \u683c\u5b50:" + t + " \u5546\u54c1:" + shopList.size());
        s.sendMessage("\u00a7e[CY] \u00a7f\u4e3b\u63a7: " + (masterInstance != null ? "\u00a7a\u5df2\u8fde\u63a5" : "\u00a7c\u672a\u8fde\u63a5"));
    }

    private void showInfo(CommandSender s, String n) {
        Map<String, Object> m = getMember(n);
        if (m.isEmpty() || !(Boolean) m.get("_exists")) {
            s.sendMessage("\u00a7c" + n + " \u4e0d\u662f\u6210\u5458");
            return;
        }
        s.sendMessage("\u00a7e" + n + ": \u6c38\u4e4d=" + ((Number) m.get("perm")).intValue() + " \u4f1a\u5458=" + ((Number) m.get("mem")).intValue() + " \u8fc7\u671f=" + fmtTime(((Number) m.get("exp")).longValue()));
    }

    private int totalSlots(Map<String, Object> m) {
        if (m.isEmpty()) return 0;
        int p = ((Number) m.get("perm")).intValue();
        int me = ((Number) m.get("mem")).intValue();
        long e = ((Number) m.get("exp")).longValue();
        return p + ((e == 0 || e > System.currentTimeMillis()) ? me : 0);
    }

    private int totalPages(Map<String, Object> m) {
        return Math.max(1, (totalSlots(m) + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private int usableOnPage(int pg, Map<String, Object> m) {
        int max = totalSlots(m);
        int totalNeeded = pg * PAGE_SIZE;
        if (totalNeeded <= max) return PAGE_SIZE;
        if (pg == 1) return max;
        int prevTotal = (pg - 1) * PAGE_SIZE;
        if (prevTotal >= max) return 0;
        return Math.min(max - prevTotal, PAGE_SIZE);
    }

    private int countItems(List<ItemStack> list, int pg) {
        int start = (pg - 1) * PAGE_SIZE;
        int count = 0;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = start + i;
            if (idx < list.size() && list.get(idx) != null && list.get(idx).getType() != Material.GRAY_STAINED_GLASS_PANE) {
                count++;
            }
        }
        return count;
    }

    private int unlockedPages(Map<String, Object> m) {
        return ((Number) m.getOrDefault("up", 1)).intValue();
    }

// ===== SECTION: 通用协议 =====
public String onSdf1GetShopItems() {
    StringBuilder sb = new StringBuilder();
    for (ShopItem it : shopList) {
        int st = stockMap.containsKey(it.getId())
                ? stockMap.get(it.getId()) : it.getStock();
        if (st == -1) continue;
        sb.append(it.getId()).append("|")
                .append(it.getName()).append("|")
                .append(it.getDays()).append("|")
                .append(it.getSlots()).append("|")
                .append((int) it.getPrice()).append("|")
                .append(st).append("|")
                .append(it.getLogo().name()).append("|")
                .append(it.isLifetime() ? "1" : "0")
                .append(";");
    }
    return sb.toString();
}

    public String onSdf1Ping() {
        return "CY_beibao v" + (localVer != null ? localVer : "1.0") + " members=" + memberCount() + " shop=" + shopList.size();
    }
   /* public String onSdf1GetShopItems() {
        StringBuilder sb = new StringBuilder();
        for (ShopItem it : shopList) {
            int st = stockMap.containsKey(it.getId())
                    ? stockMap.get(it.getId()) : it.getStock();
            if (st == -1) continue;
            sb.append(it.getId()).append("|")
                    .append(it.getName()).append("|")
                    .append(it.getDays()).append("|")
                    .append(it.getSlots()).append("|")
                    .append((int) it.getPrice()).append("|")
                    .append(st).append("|")
                    .append(it.getLogo().name()).append("|")
                    .append(it.isLifetime() ? "1" : "0")
                    .append(";");
        }
        return sb.toString();
    }
*/
    public boolean onSdf1Activation(String name, int slots, int days) {
        try {
            if (days > 0) upsert(name, 0, slots, days, "");
            else upsert(name, slots, 0, 0, "");
            return true;
        } catch (Exception e) {
            getLogger().severe("[\u8054\u63a7] \u6fc0\u6d3b\u5931\u8d25: " + e.getMessage());
            return false;
        }
    }

    public boolean onSdf1Verify(String secret) {
        if (sharedSecret == null || sharedSecret.isEmpty()) return true;
        return sharedSecret.equals(secret);
    }

// ===== SECTION: 反射发现主控 =====

    private void discoverMaster() {
        if (masterPluginName == null || masterPluginName.isEmpty()) return;
        if (masterInstance != null) {
            try {
                if (masterPingMethod != null) {
                    masterPingMethod.invoke(masterInstance);
                    return;
                }
            } catch (Exception e) {
                masterInstance = null;
                masterPingMethod = null;
                masterActMethod = null;
                masterVerifyMethod = null;
            }
        }
        try {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(masterPluginName);
            if (plugin == null || !plugin.isEnabled()) {
                masterInstance = null;
                return;
            }
            Class<?> clazz = plugin.getClass();
            Method pingM = null, actM = null, verifyM = null;
            try {
                pingM = clazz.getMethod("onSdf1Ping");
            } catch (NoSuchMethodException ignored) {
            }
            try {
                actM = clazz.getMethod("onSdf1Activation", String.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                verifyM = clazz.getMethod("onSdf1Verify", String.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (verifyM != null && sharedSecret != null && !sharedSecret.isEmpty()) {
                Object result = verifyM.invoke(plugin, sharedSecret);
                if (Boolean.FALSE.equals(result)) {
                    getLogger().warning("[\u5171\u4eab] \u5bc6\u94a5\u9a8c\u8bc1\u5931\u8d25\uff01");
                    return;
                }
            }
            if (pingM != null) {
                String result = (String) pingM.invoke(plugin);
                getLogger().info("[\u5171\u4eab] \u4e3b\u63a7\u53d1\u73b0\u6210\u529f: " + result);
            }
            masterInstance = plugin;
            masterPingMethod = pingM;
            masterActMethod = actM;
            masterVerifyMethod = verifyM;
        } catch (Exception e) {
            getLogger().warning("[\u5171\u4eab] \u4e3b\u63a7\u53d1\u73b0\u5f02\u5e38: " + e.getMessage());
            masterInstance = null;
        }
    }

    private boolean callMasterActivation(String name, int slots, int days) {
        if (masterInstance == null || masterActMethod == null) return false;
        try {
            return Boolean.TRUE.equals(masterActMethod.invoke(masterInstance, name, slots, days));
        } catch (Exception e) {
            return false;
        }
    }

    private String[] fetchRelease(String apiUrl, String source) {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }

                        public void checkClientTrusted(
                                X509Certificate[] certs,
                                String authType) {
                        }

                        public void checkServerTrusted(
                                X509Certificate[] certs,
                                String authType) {
                        }
                    }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll,
                    new java.security.SecureRandom());
            HttpsURLConnection con =
                    (HttpsURLConnection) new java.net.URL(apiUrl)
                            .openConnection();
            con.setSSLSocketFactory(sc.getSocketFactory());
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            con.setRequestProperty("User-Agent",
                    "CY_beibao-Updater");
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
            br.close();
            String json = sb.toString();
            String tag = "";
            int ti = json.indexOf("\"tag_name\"");
            if (ti >= 0) {
                int cs = json.indexOf("\"", ti + 11);
                int ce = json.indexOf("\"", cs + 1);
                if (cs >= 0 && ce >= 0)
                    tag = json.substring(cs + 1, ce);
            }
            String body = "";
            int bi = json.indexOf("\"body\"");
            if (bi >= 0) {
                int bs = json.indexOf("\"", bi + 7);
                int be = json.indexOf("\"", bs + 1);
                if (bs >= 0 && be >= 0)
                    body = json.substring(bs + 1, be)
                            .replace("\\n", "\n");
            }
            if (tag.isEmpty()) return null;
            return new String[]{tag, body};
        } catch (Exception e) {
            getLogger().warning("[更新] " + source
                    + " 请求失败: " + e.getMessage());
            return null;
        }
    }


    private void checkUpdate(final CommandSender manual) {
        String checkingMsg = "[CY] 正在检查更新...";
        getLogger().info(checkingMsg);
        if (manual != null) manual.sendMessage(checkingMsg);
        new Thread(() -> {
            try {
                boolean preferGH = "GH".equalsIgnoreCase(updateCh) || updateCh.isEmpty();
                String pApi = preferGH ? API_GH : API_GE;
                String pDl = preferGH ? DL_GH : DL_GE;
                String pName = preferGH ? "GitHub" : "Gitee";
                String bApi = preferGH ? API_GE : API_GH;
                String bDl = preferGH ? DL_GE : DL_GH;
                String bName = preferGH ? "Gitee" : "GitHub";

                String[] res = fetchRelease(pApi, pName);
                if (res != null) {
                    String linkMsg = "[更新] " + pName + " 最新版本: " + res[0] + " | 下载: " + pDl;
                    getLogger().info(linkMsg);
                    if (manual != null)
                        manual.sendMessage("§a[更新] §f" + pName + " 最新版本: §e" + res[0] + " §f| 下载: §b" + pDl);
                    applyUpdate(res[0], res[1], pDl, manual, pName);
                    return;
                }
                getLogger().info("[更新] " + pName + " 失败，切换 " + bName);

                res = fetchRelease(bApi, bName);
                if (res != null) {
                    String linkMsg = "[更新] " + bName + " 最新版本: " + res[0] + " | 下载: " + bDl;
                    getLogger().info(linkMsg);
                    if (manual != null)
                        manual.sendMessage("§a[更新] §f" + bName + " 最新版本: §e" + res[0] + " §f| 下载: §b" + bDl);
                    applyUpdate(res[0], res[1], bDl, manual, bName);
                    return;
                }
                getLogger().info("[更新] 双路均失败");
                if (manual != null) manual.sendMessage("§c[更新] §f检查失败");
            } catch (Exception e) {
                getLogger().warning("[更新] " + e.getMessage());
            }
        }).start();
    }

    private void applyUpdate(String remoteVersion,
                             String changelog,
                             String downloadUrl,
                             CommandSender manual,
                             String source) {
        this.remoteVer = remoteVersion;
        try {
            String[] lp = localVer.split("\\.");
            String[] rp = remoteVersion.split("\\.");
            int lMaj = lp.length > 0
                    ? Integer.parseInt(lp[0]) : 0;
            int lMin = lp.length > 1
                    ? Integer.parseInt(lp[1]) : 0;
            int lPat = lp.length > 2
                    ? Integer.parseInt(lp[2]) : 0;
            int rMaj = rp.length > 0
                    ? Integer.parseInt(rp[0]) : 0;
            int rMin = rp.length > 1
                    ? Integer.parseInt(rp[1]) : 0;
            int rPat = rp.length > 2
                    ? Integer.parseInt(rp[2]) : 0;
            boolean newer = rMaj > lMaj
                    || (rMaj == lMaj && rMin > lMin)
                    || (rMaj == lMaj && rMin == lMin
                    && rPat > lPat);
            if (!newer) {
                getLogger().info("[更新] 当前已是最新版本 ("
                        + localVer + ")");
                if (manual != null)
                    manual.sendMessage(
                            "§a[更新] §f当前已是最新版本 §e"
                                    + localVer);
                return;
            }
            getLogger().info("[更新] 发现新版本: "
                    + remoteVersion + " (当前: " + localVer
                    + ") | 下载: " + downloadUrl);
            for (Player op : Bukkit.getOnlinePlayers()) {
                if (isAdmin(op)) {
                    op.sendMessage(
                            "§a§l[更新] §f发现新版本: §e"
                                    + remoteVersion
                                    + " §f(当前: " + localVer + ")");
                    op.sendMessage("§7下载: §b" + downloadUrl);
                    if (changelog != null
                            && !changelog.isEmpty()) {
                        op.sendMessage("§7更新说明:");
                        for (String cl : changelog.split("\n")) {
                            op.sendMessage("§7  " + cl);
                        }
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("[更新] 版本比较失败: "
                    + e.getMessage());
        }
    }


// ===== SECTION: GUI - 主菜单 =====

    private void openMain(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, T_MAIN);
        fillBg(g);
        g.setItem(2, mkItem(Material.GREEN_WOOL, "§a§l我的"));
        g.setItem(4, mkItem(Material.EMERALD_BLOCK, "§b§l商城"));
        g.setItem(6, mkItem(Material.ENDER_CHEST, "§d§l主仓"));
        g.setItem(13, mkItem(Material.BARRIER, "§c§l关闭"));
        if (isAdmin(p)) {
            g.setItem(11, mkItem(Material.REDSTONE_BLOCK, "§4§l管理面板"));
        }
        if (!isFreeClaimed(p.getName())) {
            g.setItem(15, mkItem(Material.ENDER_PEARL,
                    "§a§l★ 免费领取空间 ★",
                    "§7领取" + FREE_SLOTS + "格永久空间", "",
                    "§e§l点击领取"));
        } else {
            g.setItem(15, mkItem(Material.ENDER_PEARL,
                    "§7已领取", "§7免费空间已领取"));
        }
        p.openInventory(g);
    }

// ===== SECTION: GUI - 我的 =====

    private void openMy(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, T_MY);
        fillBg(g);
        ensureMember(p.getName());
        Map<String, Object> m = getMember(p.getName());
        int perm = ((Number) m.get("perm")).intValue();
        int mem = ((Number) m.get("mem")).intValue();
        long exp = ((Number) m.get("exp")).longValue();
        int tot = totalSlots(m);
        int tp = totalPages(m);
        int up = unlockedPages(m);
        g.setItem(4, mkItem(Material.NAME_TAG, "§e" + p.getName()));
        g.setItem(10, mkItem(Material.CLOCK, "§a剩余时间", "§7" + fmtTime(exp)));
        g.setItem(12, mkItem(Material.DIAMOND_BLOCK,
                "§b永久格子: §e" + perm));
        boolean active = (exp == 0 || exp > System.currentTimeMillis());
        g.setItem(14, mkItem(Material.EMERALD_BLOCK,
                "§a会员格子: §e" + mem,
                "§7" + (active ? "§a有效" : "§c已过期")));
        g.setItem(16, mkItem(Material.CHEST,
                "§d总格子: §f" + tot,
                "§7总页数: " + tp));
        boolean autoRenew = isAutoRenew(p.getName());
        Material torch = autoRenew
                ? Material.REDSTONE_TORCH : Material.TORCH;
        g.setItem(18, mkItem(torch,
                autoRenew ? "§a§l自动续费: 开启" : "§c§l自动续费: 关闭",
                "§7当前: " + (autoRenew ? "§a开启" : "§c关闭"),
                "", "§e§l点击切换"));
        g.setItem(22, mkItem(Material.BOOK,
                "§e解锁页: §f" + Math.min(up, tp) + "/" + tp));
        g.setItem(26, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

// ===== SECTION: GUI - 商城 =====

    private List<ShopItem> getVisibleShopItems() {
        List<ShopItem> visible = new ArrayList<>();
        for (ShopItem it : shopList) {
            int st = stockMap.containsKey(it.getId())
                    ? stockMap.get(it.getId()) : it.getStock();
            if (st != -1) visible.add(it);
        }
        return visible;
    }

    private void openShop(Player p) {
        List<ShopItem> visible = getVisibleShopItems();
        int sz = Math.max(9, ((visible.size() / 9) + 1) * 9);
        if (sz > 54) sz = 54;
        Inventory g = Bukkit.createInventory(null, sz, T_SHOP);
        for (int i = 0; i < visible.size() && i < 45; i++) {
            ShopItem it = visible.get(i);
            int st = stockMap.containsKey(it.getId())
                    ? stockMap.get(it.getId()) : it.getStock();
            double ep = it.getEffectivePrice();
            boolean discounted = it.isOnDiscount();
            List<String> lore = new ArrayList<>();
            if (ep == 0) {
                lore.add("§7价格: §a免费");
            } else if (discounted) {
                lore.add("§7原价: §m$" + fmt(it.getPrice()));
                lore.add("§e§l✧ 折扣价: $" + fmt(ep) + " ✧");
            } else {
                lore.add("§7价格: §a$" + fmt(ep));
            }
            if (it.isBlindBox()) {
                lore.add("§7格子: §e??? 格");
                lore.add("§7时间: §e??? 天");
                lore.add("");
                lore.add("§8────────────────────");
                lore.add("§d✧ 神秘盲盒 ✧");
                lore.add("§7斯运活气就来！");
                lore.add("§8────────────────────");
            } else {
                lore.add("§7格子: §e" + (it.isLifetime() ? "终身" : "+" + it.getSlots()));
                lore.add("§7时间: §e" + (it.isLifetime() ? "终身" : it.getDays() + "天"));
            }
            if (it.hasPurchaseLimit()) {
                int used = getPurchaseCount(p.getName(), it.getId());
                int limit = it.getPurchaseLimit();
                if (used >= limit) {
                    int[] info = getPurchaseInfo(p.getName(), it.getId());
                    if (info[1] <= 0) {
                        lore.add("§7已购§e" + used + "§7次，限购§e" + limit + "§7次");
                        lore.add("§a窗口已结束，可重新购买");
                    } else {
                        lore.add("§c已购" + used + "次，限购" + limit
                                + "次（" + fmtLimitTime(info[1]) + "后重置）");
                    }
                } else {
                    lore.add("§7已购§e" + used + "§7次，限购§e" + limit + "§7次");
                }
            }

            String stockText;
            if (st == -1) stockText = "§7下架";
            else if (st == -2 || st == 0) stockText = "§a无限";
            else stockText = "§e" + st;
            lore.add("§7库存: " + stockText);
            lore.add("");
            boolean limitReached = it.hasPurchaseLimit()
                    && getPurchaseCount(p.getName(), it.getId())
                    >= it.getPurchaseLimit();
            boolean soldOut = (st == 0 && it.getStock() == 0 && !it.isAvailable());
            lore.add(soldOut ? "§c已售罄"
                    : (limitReached ? "§c限购已达上限"
                       : (it.isBlindBox() ? "§e§l点击开盲盒" : "§e点击购买")));
            g.setItem(i, mkShopDisplay(it,
                    "§a" + it.getName(),
                    lore.toArray(new String[0])));
        }
        g.setItem(sz - 1, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

// ===== SECTION: GUI - 主仓 =====

    private void openStorage(Player p) {
        ensureMember(p.getName());
        Map<String, Object> m = getMember(p.getName());
        int tot = totalSlots(m);
        if (tot <= 0) {
            p.sendMessage("§c§l[仓库] §f没有存储空间！");
            return;
        }
        storageSession.put(p.getUniqueId(),
                System.currentTimeMillis());              // ← 加这行
        List<ItemStack> list = loadStorage(p.getName());
        while (list.size() < tot) list.add(null);
        cacheMap.put(p.getUniqueId(), list);
        pageMap.put(p.getUniqueId(), 1);
        p.openInventory(Bukkit.createInventory(null,
                INVENTORY_SIZE, T_STORE));
        refreshPage(p);
    }


    private void refreshPage(Player p) {
        Inventory g = p.getOpenInventory().getTopInventory();
        UUID u = p.getUniqueId();
        List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>());
        int pg = pageMap.getOrDefault(u, 1);
        int start = (pg - 1) * PAGE_SIZE;
        Map<String, Object> m = getMember(p.getName());
        int us = usableOnPage(pg, m);
        int tp = totalPages(m);
        int up = unlockedPages(m);
        ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, "§7锁定");
        for (int i = 0; i < PAGE_SIZE; i++) {
            if (i < us) {
                int idx = start + i;
                g.setItem(i, (idx < list.size() && list.get(idx) != null)
                        ? list.get(idx) : null);
            } else {
                g.setItem(i, gl);
            }
        }
        // 填充物品区与按钮区之间的空隙
        for (int i = PAGE_SIZE; i < BTN_SLOT_PREV; i++) {
            g.setItem(i, gl);
        }
        g.setItem(BTN_SLOT_PREV,
                pg > 1 ? mkItem(Material.ARROW, "§a上一页")
                        : mkItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        g.setItem(BTN_SLOT_INFO,
                mkItem(Material.PAPER,
                        "§e第" + pg + "/" + tp + "页",
                        "§7物品: " + countItems(list, pg) + "/" + us));
        g.setItem(BTN_SLOT_BACK,
                mkItem(Material.BARRIER, "§c返回"));
        if (pg < tp) {
            boolean can = (pg < up)
                    || (countItems(list, pg)
                    >= (int)(us * UNLOCK_PERCENT));
            g.setItem(BTN_SLOT_NEXT,
                    can ? mkItem(Material.ARROW, "§a下一页")
                            : mkItem(Material.RED_STAINED_GLASS_PANE,
                            "§c下一页",
                            "§7需达" + (int)(us * UNLOCK_PERCENT)
                            + "+格"));
        } else {
            g.setItem(BTN_SLOT_NEXT,
                    mkItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
    }



    private boolean nextPage(Player p) {
        syncCacheFromInventory(p);
        UUID u = p.getUniqueId();
        int pg = pageMap.getOrDefault(u, 1);
        int nx = pg + 1;
        Map<String, Object> m = getMember(p.getName());
        int tp = totalPages(m);

        if (nx > tp) {
            p.sendMessage("§c§l[仓库] §f已到最大页！");
            return false;
        }

        List<ItemStack> list =
                cacheMap.getOrDefault(u, new ArrayList<>());
        int filled = countItems(list, pg);
        int us = usableOnPage(pg, m);
        int threshold = (int)(us * UNLOCK_PERCENT);

        // 当前页≥90% 或 下一页已有物品 → 允许
        boolean canGo =
                filled >= threshold
                        || countItems(list, nx) > 0;

        if (canGo) {
            pageMap.put(u, nx);
            refreshPage(p);
            return true;
        }

        p.sendMessage("§c§l[仓库] §f存储量不足（"
                + filled + "/" + threshold + "）");
        return false;
    }



// ===== SECTION: 仓库点击处理 =====

    private void syncAndSaveStorage(Player p) {
        UUID u = p.getUniqueId();
        Inventory inv = p.getOpenInventory().getTopInventory();
        List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>());
        int pg = pageMap.getOrDefault(u, 1);
        int start = (pg - 1) * PAGE_SIZE;
        Map<String, Object> m = getMember(p.getName());
        int us = usableOnPage(pg, m);
        for (int i = 0; i < us && i < PAGE_SIZE; i++) {
            int idx = start + i;
            ItemStack item = inv.getItem(i);
            while (list.size() <= idx) list.add(null);
            list.set(idx, item);
        }
        saveStorage(p.getName(), list);
    }

    private void handleStorageClick(Player p, int rawSlot,
                                    InventoryClickEvent e) {
        Inventory top = e.getInventory();
        int topSize = top.getSize();
        boolean inTop = rawSlot >= 0 && rawSlot < topSize;

        if (inTop) {
            e.setCancelled(true);

            if (rawSlot == BTN_SLOT_PREV
                    || rawSlot == BTN_SLOT_INFO
                    || rawSlot == BTN_SLOT_NEXT
                    || rawSlot == BTN_SLOT_BACK) {
                handleNavClick(p, rawSlot);
                return;
            }

            UUID u = p.getUniqueId();
            int pg = pageMap.getOrDefault(u, 1);
            Map<String, Object> m = getMember(p.getName());
            int us = usableOnPage(pg, m);

            if (rawSlot >= us || rawSlot >= PAGE_SIZE) return;

            // 双击玻璃板 → 拦截
            if (e.getClick() == ClickType.DOUBLE_CLICK) {
                ItemStack ci = e.getCurrentItem();
                ItemStack cu = e.getCursor();
                if ((ci != null
                        && ci.getType()
                        == Material.GRAY_STAINED_GLASS_PANE)
                        || (cu != null
                        && cu.getType()
                        == Material.GRAY_STAINED_GLASS_PANE)) {
                    return;
                }
            }

            ItemStack cursor  = e.getCursor();
            ItemStack slotItem = e.getCurrentItem();

            if (cursor != null
                    && cursor.getType() != Material.AIR
                    && (isShopIcon(cursor)
                    || isLockedPane(p, cursor))) {
                return;
            }

            // Shift：仓库 → 背包
            if (e.isShiftClick()) {
                if (slotItem == null
                        || slotItem.getType()
                        == Material.AIR) return;
                if (isShopIcon(slotItem)
                        || isLockedPane(p, slotItem)) return;
                HashMap<Integer, ItemStack> left =
                        p.getInventory()
                                .addItem(slotItem.clone());
                if (left.isEmpty()) {
                    top.setItem(rawSlot, null);
                } else {
                    p.sendMessage("§c[仓库] §f背包已满！");
                }
                return;
            }

            // 普通点击：光标 ↔ 格子交换
            top.setItem(rawSlot, cursor);
            e.setCursor(slotItem);
            return;
        }

        // 底部区域（玩家背包）
        // 双击玻璃板 → 拦截
        if (e.getClick() == ClickType.DOUBLE_CLICK) {
            ItemStack cu = e.getCursor();
            if (cu != null
                    && cu.getType()
                    == Material.GRAY_STAINED_GLASS_PANE) {
                e.setCancelled(true);
                return;
            }
        }

        if (e.isShiftClick()) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null
                    || clicked.getType() == Material.AIR) return;
            if (isShopIcon(clicked)
                    || isLockedPane(p, clicked)) return;

            UUID u = p.getUniqueId();
            int pg = pageMap.getOrDefault(u, 1);
            Map<String, Object> m = getMember(p.getName());
            int us = usableOnPage(pg, m);
            int start = (pg - 1) * PAGE_SIZE;

            for (int i = 0; i < us; i++) {
                int idx = start + i;
                if (idx >= topSize) break;
                if (top.getItem(idx) == null
                        || top.getItem(idx).getType()
                        == Material.AIR) {
                    top.setItem(idx, clicked.clone());
                    e.getClickedInventory()
                            .setItem(e.getSlot(), null);
                    return;
                }
            }
            p.sendMessage("§c[仓库] §f当前页存储空间已满！");
        }
    }






// ===== SECTION: GUI - 管理面板 =====

    private void openAdminMain(Player p) {
        if (!isVerifiedAdmin(p)) {
            startAdminAuth(p);
            return;
        }
        getOrCreateState(p).chatType = "";
        Inventory g = Bukkit.createInventory(null, 27, T_ADMIN_MAIN);
        fillBg(g);
        g.setItem(4, mkItem(Material.NAME_TAG, "§e管理面板"));
        g.setItem(11, mkItem(Material.ARMOR_STAND,
                "§a用户管理", "§7管理玩家数据"));
        g.setItem(15, mkItem(Material.EMERALD,
                "§6商品管理", "§7编辑商城商品"));
        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

    private void startAdminAuth(Player p) {
        p.closeInventory();
        getOrCreateState(p).chatType = "admin_pass";
        p.sendMessage("§e[管理] §f请输入管理密码（0/exit 取消）：");
        if (adminPass.equals("qweasd")) {
            p.sendMessage("§c§l[警告] §f使用默认密码！");
        }
    }

    private void startUserMgmtAuth(Player p) {
        p.closeInventory();
        getOrCreateState(p).chatType = "admin_player";
        p.sendMessage("§e[管理] §f请输入玩家ID（0/exit 取消）：");
    }

    private void openUserMgmt(Player p, String tn) {
        AdminState state = getOrCreateState(p);
        state.targetPlayer = tn;
        Inventory g = Bukkit.createInventory(null, 27, T_USER_MGMT);
        fillBg(g);
        ensureMember(tn);
        Map<String, Object> m = getMember(tn);
        g.setItem(4, mkItem(Material.NAME_TAG, "§e管理: " + tn));
        if (m.isEmpty() || !(Boolean) m.get("_exists")) {
            g.setItem(13, mkItem(Material.BEDROCK,
                    "§c玩家不在数据库中"));
        } else {
            g.setItem(10, mkItem(Material.GREEN_WOOL,
                    "§a永久格子: §e" + ((Number) m.get("perm")).intValue(),
                    "§7点击输入调整"));
            g.setItem(12, mkItem(Material.EMERALD_BLOCK,
                    "§b会员格子: §e" + ((Number) m.get("mem")).intValue(),
                    "§7点击输入调整"));
            g.setItem(14, mkItem(Material.CLOCK,
                    "§e会员时间",
                    "§7" + fmtTime(((Number) m.get("exp")).longValue()),
                    "§7点击输入调整"));
            g.setItem(16, mkItem(Material.BARRIER,
                    "§c移除玩家", "§7点击删除此玩家数据"));
        }
        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

// ===== SECTION: GUI - 商品管理 =====

    private void openShopMgmt(Player p) {
        getOrCreateState(p).chatType = "";
        Inventory g = Bukkit.createInventory(null, 54, T_SHOP_MGMT);
        fillBg(g);
        for (int i = 0; i < shopList.size() && i < 9; i++) {
            ShopItem it = shopList.get(i);
            int st = stockMap.containsKey(it.getId())
                    ? stockMap.get(it.getId()) : it.getStock();
            String stockText = st == -1 ? "§7下架"
                    : (st == 0 ? "§c售罄" : "§e" + st);
            g.setItem(18 + i, mkShopDisplay(it,
                    "§e#" + it.getId() + " " + it.getName(),
                    "§7库存: " + stockText + "  格口: " + it.getSlots(),
                    "§7价格: §a$" + fmt(it.getEffectivePrice()),
                    "", "§7双击编辑"));
        }
        g.setItem(49, mkItem(Material.ARROW, "§7返回"));
        g.setItem(53, mkItem(Material.EMERALD,
                "§a§l发布新商品", "§7点击上架新商品"));
        p.openInventory(g);
    }

// ===== SECTION: GUI - 商品编辑 =====

    private void openItemEditor(Player p, int itemIdx) {
        AdminState state = getOrCreateState(p);
        state.editItemIdx = itemIdx;
        ShopItem it = shopList.get(itemIdx);
        state.tmpName = it.getName();
        state.tmpPrice = it.getPrice();
        state.tmpStock = stockMap.containsKey(it.getId())
                ? stockMap.get(it.getId()) : it.getStock();
        state.tmpLogo = it.getLogo().name();
        state.tmpLogoItem = it.getLogoItem();
        state.tmpId = it.getId();
        state.tmpDays = it.getDays();
        state.tmpSlots = it.getSlots();
        refreshItemEditor(p);
    }

    private void openNewItem(Player p) {
        AdminState state = getOrCreateState(p);
        state.editItemIdx = -1;
        state.tmpId = String.valueOf(1000 + rng.nextInt(9000));
        state.tmpName = "新商品";
        state.tmpPrice = 100;
        state.tmpStock = 0;
        state.tmpSlots = 1;
        state.tmpDays = 1;
        state.tmpLogo = "CHEST";
        state.tmpLogoItem = "";
        refreshItemEditor(p);
    }

    private void refreshItemEditor(Player p) {
        AdminState state = adminStates.get(p.getUniqueId());
        if (state == null) return;
        String title = (state.editItemIdx < 0)
                ? T_NEW_ITEM : T_ITEM_EDIT;
        Inventory g = Bukkit.createInventory(null, 54, title);
        for (int i = 0; i < LOGO_OPTIONS.length; i++) {
            Material m = LOGO_OPTIONS[i];
            boolean sel = m.name().equals(state.tmpLogo)
                    && state.tmpLogoItem.isEmpty();
            g.setItem(i, mkItem(m,
                    sel ? "§a>> " + m.name() + " <<"
                            : "§e" + m.name()));
        }
        g.setItem(11, mkItem(Material.PAPER,
                "§e商品名称",
                "§7当前: " + state.tmpName,
                "", "§7双击编辑"));
        g.setItem(13, mkItem(Material.DIAMOND,
                "§e价格",
                "§7当前: §a$" + fmt(state.tmpPrice),
                "", "§7双击编辑"));
        g.setItem(15, mkItem(Material.CHEST,
                "§e库存/格口",
                "§7库存: §f" + state.tmpStock
                        + "  格口: §f" + state.tmpSlots,
                "", "§7单击改库存  双击改格口"));
        if (!state.tmpLogoItem.isEmpty()) {
            ItemStack ci = decodeSingleItem(state.tmpLogoItem);
            if (ci != null) {
                ItemStack d = ci.clone();
                ItemMeta meta = d.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§e当前图标 (自定义)");
                    meta.setLore(Arrays.asList(
                            "§7类型: " + ci.getType().name(),
                            "", "§7从背包拖入新物品可替换"));
                    d.setItemMeta(meta);
                }
                g.setItem(17, d);
            } else {
                g.setItem(17, mkItem(Material.BARRIER,
                        "§c图标数据损坏"));
            }
        } else {
            Material lm = parseMaterialSafe(state.tmpLogo,
                    Material.CHEST);
            g.setItem(17, mkItem(lm,
                    "§e当前图标",
                    "§7" + state.tmpLogo,
                    "", "§7点击上方替换或从背包拖入"));
        }
        g.setItem(21, mkItem(Material.BOOK,
                "§7内部ID (只读)",
                "§7" + state.tmpId, "§7ID不可修改"));
        g.setItem(23, mkItem(Material.EMERALD,
                "§a§l发布", "§7确认发布/保存"));
        g.setItem(25, mkItem(Material.FEATHER,
                "§c取消", "§7放弃编辑"));
        p.openInventory(g);
    }

    private void publishItem(Player p, AdminState state) {
        if (state.tmpName.isEmpty()) {
            p.sendMessage("§c[管理] §f商品名称不能为空");
            return;
        }
        if (state.tmpPrice <= 0) {
            p.sendMessage("§c[管理] §f价格必须大于0");
            return;
        }
        String li = state.tmpLogoItem;
        if (state.editItemIdx < 0) {
            ShopItem it = new ShopItem(state.tmpId,
                    state.tmpName, state.tmpDays, state.tmpSlots,
                    state.tmpPrice, state.tmpStock,
                    parseMaterialSafe(state.tmpLogo, Material.CHEST),
                    li, 1.0, 0, 0);
            shopList.add(it);
            stockMap.put(it.getId(), it.getStock());
            p.sendMessage("§a[管理] §f已发布: " + it.getName()
                    + " #" + it.getId());
        } else if (state.editItemIdx < shopList.size()) {
            ShopItem old = shopList.get(state.editItemIdx);
            ShopItem up = new ShopItem(old.getId(),
                    state.tmpName, state.tmpDays, state.tmpSlots,
                    state.tmpPrice, state.tmpStock,
                    parseMaterialSafe(state.tmpLogo, Material.CHEST),
                    li, old.getDiscountRate(),
                    old.getDiscountStart(), old.getDiscountEnd());
            shopList.set(state.editItemIdx, up);
            stockMap.put(up.getId(), up.getStock());
            p.sendMessage("§a[管理] §f已保存: " + up.getName()
                    + " #" + up.getId());
        }
        saveShopFile();
        openShopMgmt(p);
    }

// ===== SECTION: 购买逻辑 =====

    private void buy(Player p, ShopItem item) {
        if (economy == null) {
            p.sendMessage("§c§l[商城] §f经济不可用！");
            return;
        }
        if (item.isBlindBox()) {
            openBox(p, item);
            return;
        }
        int st = stockMap.containsKey(item.getId())
                ? stockMap.get(item.getId()) : item.getStock();
        if (st == -1) {
            p.sendMessage("§c§l[商城] §f已下架");
            return;
        }
        if (st == 0 && !item.isAvailable()) {
            p.sendMessage("§c§l[商城] §f已售罄！");
            return;
        }
        String itemKey = p.getName() + ":" + item.getId();
        Long lastItem = lastItemPurchase.get(itemKey);
        if (lastItem != null
                && System.currentTimeMillis() - lastItem < 1000) {
            p.sendMessage("§e§l[商城] §f操作太频繁，请稍后再试");
            return;
        }
        if (item.hasPurchaseLimit()) {
            int used = getPurchaseCount(p.getName(), item.getId());
            if (used >= item.getPurchaseLimit()) {
                int[] info = getPurchaseInfo(p.getName(), item.getId());
                p.sendMessage("§c§l[商城] §f已达到限购上限！（"
                        + fmtLimitTime(info[1]) + "后重置）");
                return;
            }
        }
        Long last = lastPurchase.get(p.getUniqueId());
        if (last != null
                && System.currentTimeMillis() - last < PURCHASE_CD) {
            p.sendMessage("§e§l[商城] §f购买太快");
            return;
        }
        double ep = item.getEffectivePrice();
        if (!economy.has(p, ep)) {
            double bal = economy.getBalance(p);
            p.sendMessage("§c§l[商城] §f余额不足！");
            p.sendMessage("§7价格: §e$" + fmt(ep)
                    + " §7余额: §a$" + fmt(bal)
                    + " §7差: §c$" + fmt(ep - bal));
            return;
        }
        economy.withdrawPlayer(p, ep);
        lastPurchase.put(p.getUniqueId(), System.currentTimeMillis());
        lastItemPurchase.put(itemKey, System.currentTimeMillis());
        ensureMember(p.getName());
        if (item.isLifetime()) {
            upsert(p.getName(), item.getSlots(), 0, 0, item.getId());
        } else {
            upsert(p.getName(), 0, item.getSlots(),
                    item.getDays(), item.getId());
        }
        if (item.hasPurchaseLimit()) {
            recordPurchase(p.getName(), item.getId(),
                    item.getLimitDuration());
        }
        if (st > 0) {
            st--;
            stockMap.put(item.getId(), st);
            saveShopFile();
        }
        String tl = item.isLifetime() ? "终身"
                : (item.getDays() + "天");
        p.sendMessage("§a§l[商城] §f" + item.getName()
                + " | " + tl + " | +" + item.getSlots()
                + "格 | §c-$" + fmt(ep)
                + " §7余额: §a$"
                + fmt(economy.getBalance(p)));
    }

// ===== SECTION: 购买逻辑 - 盲盒 =====

    private void openBox(Player p, ShopItem item) {
        if (economy == null) {
            p.sendMessage("§c§l[商城] §f经济不可用！");
            return;
        }
        int st = stockMap.containsKey(item.getId())
                ? stockMap.get(item.getId()) : item.getStock();
        if (st == -1) {
            p.sendMessage("§c§l[商城] §f已下架");
            return;
        }
        if (st == 0 && !item.isAvailable()) {
            p.sendMessage("§c§l[商城] §f已售罄");
            return;
        }
        String itemKey = p.getName() + ":" + item.getId();
        Long lastItem = lastItemPurchase.get(itemKey);
        if (lastItem != null
                && System.currentTimeMillis() - lastItem < 1000) {
            p.sendMessage("§e§l[商城] §f操作太频繁，请稍后再试");
            return;
        }
        if (item.hasPurchaseLimit()) {
            int used = getPurchaseCount(p.getName(), item.getId());
            if (used >= item.getPurchaseLimit()) {
                int[] info = getPurchaseInfo(p.getName(), item.getId());
                p.sendMessage("§c§l[商城] §f已达到限购上限！（"
                        + fmtLimitTime(info[1]) + "后重置）");
                return;
            }
        }
        Long last = lastPurchase.get(p.getUniqueId());
        if (last != null
                && System.currentTimeMillis() - last < PURCHASE_CD) {
            p.sendMessage("§e§l[商城] §f购买太快");
            return;
        }
        double ep = item.getEffectivePrice();
        if (!economy.has(p, ep)) {
            p.sendMessage("§c§l[商城] §f余额不足！");
            return;
        }
        economy.withdrawPlayer(p, ep);
        lastPurchase.put(p.getUniqueId(), System.currentTimeMillis());
        lastItemPurchase.put(itemKey, System.currentTimeMillis());

        int rd = item.getMinDays()
                + rng.nextInt(item.getMaxDays()
                - item.getMinDays() + 1);
        int rs = item.getMinSlots()
                + rng.nextInt(item.getMaxSlots()
                - item.getMinSlots() + 1);
        p.playSound(p.getLocation(),
                Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        p.sendMessage("§6§l[盲盒] §e正在开启神秘盲盒...");

        final int fd = rd, fs = rs;
        final ShopItem bi = item;
        final int bs = st;

        Bukkit.getScheduler().runTaskLater(this, () -> {
            p.playSound(p.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
            p.sendMessage("§6§l[盲盒] §7物品正在旋转...");
        }, 20L);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            p.playSound(p.getLocation(),
                    Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
            p.sendMessage("§6§l[盲盒] §7即将揭晓...");
        }, 40L);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            p.playSound(p.getLocation(),
                    Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            if (bs > 0) {
                int cur = stockMap.containsKey(bi.getId())
                        ? stockMap.get(bi.getId()) : bi.getStock();
                if (cur > 0) {
                    stockMap.put(bi.getId(), cur - 1);
                    saveShopFile();
                }
            }
            upsert(p.getName(), 0, fs, fd, bi.getId());
            if (bi.hasPurchaseLimit()) {
                recordPurchase(p.getName(), bi.getId(),
                        bi.getLimitDuration());
            }
            String resultDays = fd + "天";
            String resultSlots = fs + "格";
            p.sendTitle("§6§l✦ 神秘盲盒 ✦",
                    "§e" + resultDays + "会员 + "
                            + resultSlots + "空间",
                    10, 60, 20);
            p.sendMessage("§6§l[盲盒] §e§l恭喜！获得 §f"
                    + resultDays + "会员 + "
                    + resultSlots + "空间");
        }, 60L);
    }

// ===== SECTION: 双击检测 =====

    private boolean isDoubleClick(Player p, int slot) {
        String key = p.getUniqueId() + ":" + slot;
        long now = System.currentTimeMillis();
        Long prev = lastClickMap.get(key);
        lastClickMap.put(key, now);
        return prev != null && (now - prev) < DBL_CLICK_MS;
    }

// ===== SECTION: 事件处理 - 背包点击 =====


    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String t = e.getView().getTitle();
        int r = e.getRawSlot();

        if (t.equals(T_MAIN)) {
            e.setCancelled(true);
            if (r == 2) openMy(p);
            else if (r == 4) openShop(p);
            else if (r == 6) openStorage(p);
            else if (r == 11 && isAdmin(p)) openAdminMain(p);
            else if (r == 13) p.closeInventory();
            else if (r == 15) handleFreeClaim(p);
            return;
        }
        if (t.equals(T_MY)) {
            e.setCancelled(true);
            if (r == 26) openMain(p);
            else if (r == 18) {
                boolean c = isAutoRenew(p.getName());
                setAutoRenew(p.getName(), !c);
                p.sendMessage(!c
                        ? "§a[CY] §f自动续费已开启"
                        : "§c[CY] §f自动续费已关闭");
                openMy(p);
            }
            return;
        }
        if (t.equals(T_ADMIN_MAIN)) {
            e.setCancelled(true);
            if (r == 22) openMain(p);
            else if (r == 11) startUserMgmtAuth(p);
            else if (r == 15) openShopMgmt(p);
            return;
        }
        if (t.equals(T_USER_MGMT)) {
            e.setCancelled(true);
            handleUserMgmtClick(p, r);
            return;
        }
        if (t.equals(T_SHOP_MGMT)) {
            e.setCancelled(true);
            if (r == 49) openAdminMain(p);
            else if (r == 53) openNewItem(p);
            else if (r >= 18 && r <= 26) {
                int idx = r - 18;
                if (idx < shopList.size()
                        && isDoubleClick(p, r))
                    openItemEditor(p, idx);
            }
            return;
        }
        if (t.equals(T_ITEM_EDIT) || t.equals(T_NEW_ITEM)) {
            e.setCancelled(true);
            handleItemEditorClick(p, r, e);
            return;
        }
        if (t.equals(T_SHOP)) {
            e.setCancelled(true);
            int sz = e.getInventory().getSize();
            if (r == sz - 1) {
                openMain(p);
                return;
            }
            List<ShopItem> vis = getVisibleShopItems();
            if (r >= 0 && r < vis.size() && r < 45)
                buy(p, vis.get(r));
            return;
        }
        if (t.equals(T_STORE)) {
            UUID u2 = p.getUniqueId();
            int pg2 = pageMap.getOrDefault(u2, 1);
            Map<String, Object> m2 = getMember(p.getName());
            int us2 = usableOnPage(pg2, m2);
            if (r == BTN_SLOT_PREV || r == BTN_SLOT_INFO
                    || r == BTN_SLOT_NEXT || r == BTN_SLOT_BACK) {
                e.setCancelled(true);
            } else if (r >= 0 && r < PAGE_SIZE && r >= us2) {
                e.setCancelled(true);
            }
            handleStorageClick(p, r, e);
            return;
        }


    }

    private void handleFreeClaim(Player p) {
        if (isFreeClaimed(p.getName())) {
            p.sendMessage("§c§l[CY] §f已领取过免费空间！");
            return;
        }
        ensureMember(p.getName());
        upsert(p.getName(), FREE_SLOTS, 0, 0, "");
        setFreeClaimed(p.getName());
        p.sendMessage("§a§l[CY] §f已领取"
                + FREE_SLOTS + "格免费空间！");
        openMain(p);
    }

// ===== SECTION: 用户管理点击 =====

    private void handleUserMgmtClick(Player p, int r) {
        AdminState state = adminStates.get(p.getUniqueId());
        if (state == null) return;
        String tn = state.targetPlayer;
        if (tn.isEmpty()) return;
        if (r == 22) {
            openAdminMain(p);
        } else if (r == 10) {
            state.chatType = "edit_perm";
            p.closeInventory();
            p.sendMessage("§e[管理] §f永久格子调整量 (+/-)：");
        } else if (r == 12) {
            state.chatType = "edit_mem";
            p.closeInventory();
            p.sendMessage("§e[管理] §f会员格子调整量 (+/-)：");
        } else if (r == 14) {
            state.chatType = "edit_exp";
            p.closeInventory();
            p.sendMessage("§e[管理] §f会员时间: +7 / -3 / 2026-12-31");
        } else if (r == 16) {
            delMember(tn);
            p.sendMessage("§a[管理] §f已删除: " + tn);
            state.targetPlayer = "";
            openAdminMain(p);
        }
    }

// ===== SECTION: 商品编辑点击 =====

    private void handleItemEditorClick(Player p, int r,
                                       InventoryClickEvent e) {
        AdminState state = adminStates.get(p.getUniqueId());
        if (state == null) return;
        if (r >= 0 && r < LOGO_OPTIONS.length) {
            state.tmpLogo = LOGO_OPTIONS[r].name();
            state.tmpLogoItem = "";
            refreshItemEditor(p);
            return;
        }
        if (r == 11 && isDoubleClick(p, r)) {
            state.chatType = "edit_item_name";
            p.closeInventory();
            p.sendMessage("§e[管理] §f商品名称：");
            return;
        }
        if (r == 13 && isDoubleClick(p, r)) {
            state.chatType = "edit_item_price";
            p.closeInventory();
            p.sendMessage("§e[管理] §f价格（00=免费）：");
            return;
        }
        if (r == 15) {
            if (e.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) {
                state.chatType = "edit_item_slots";
                p.closeInventory();
                p.sendMessage("§e[管理] §f格口数：");
            } else {
                state.chatType = "edit_item_stock";
                p.closeInventory();
                p.sendMessage("§e[管理] §f库存（-1下架 0售罄）：");
            }
            return;
        }
        if (r == 17) {
            ItemStack cursor = e.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                if (isShopIcon(cursor)) {
                    state.tmpLogo = cursor.getType().name();
                    state.tmpLogoItem = "";
                } else {
                    state.tmpLogo = "CUSTOM";
                    state.tmpLogoItem = encodeSingleItem(cursor);
                }
                e.setCursor(null);
                refreshItemEditor(p);
            }
            return;
        }
        if (r == 23) {
            publishItem(p, state);
            return;
        }
        if (r == 25) {
            openShopMgmt(p);
            return;
        }
    }

// ===== SECTION: 事件处理 - 背包关闭 =====

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        String t = e.getView().getTitle();
        UUID u = p.getUniqueId();
        if (t.equals(T_STORE)) {
            syncAndSaveStorage(p);
            cacheMap.remove(u);
            pageMap.remove(u);
        }
    }


// ===== SECTION: 事件处理 - 拖拽 =====

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String t = e.getView().getTitle();
        if (t.equals(T_STORE)) {
            int topSize = e.getInventory().getSize();
            UUID u = p.getUniqueId();
            int pg = pageMap.getOrDefault(u, 1);
            Map<String, Object> m = getMember(p.getName());
            int us = usableOnPage(pg, m);
            // 玻璃板/商店图标 → 直接拦截
            ItemStack dragged = e.getOldCursor();
            if (isLockedPane(p, dragged)
                    || isShopIcon(dragged)) {
                e.setCancelled(true);
                return;
            }
            for (int slot : e.getRawSlots()) {
                if (slot < topSize && slot >= us) {
                    e.setCancelled(true);
                    return;
                }
            }
            return;
        }
        if (t.equals(T_MAIN) || t.equals(T_MY)
                || t.equals(T_SHOP) || t.equals(T_ADMIN_MAIN)
                || t.equals(T_USER_MGMT)
                || t.equals(T_SHOP_MGMT)
                || t.equals(T_ITEM_EDIT)
                || t.equals(T_NEW_ITEM)) {
            e.setCancelled(true);
        }
    }



// ===== SECTION: 事件处理 - 玩家加入/退出 =====

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ensureMember(p.getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        // 退出时保存仓库
        if (cacheMap.containsKey(u)) {
            List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>());
            saveStorage(p.getName(), list);
            cacheMap.remove(u);
            pageMap.remove(u);
        }
        pageMap.remove(u);
        adminStates.remove(u);
        verifiedAdmins.remove(u);
        lastPurchase.remove(u);
        lastReminderLevel.remove(p.getName());
        autoRenewAttempted.remove(p.getName());
    }

// ===== SECTION: 事件处理 - 聊天输入（核心！）=====

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        AdminState state = adminStates.get(p.getUniqueId());
        if (state == null || state.chatType.isEmpty()) return;

        String msg = e.getMessage().trim();

        // 所有管理聊天输入都取消默认聊天
        e.setCancelled(true);

        // 取消操作
        if (isCancel(msg)) {
            state.chatType = "";
            p.sendMessage("§e[管理] §f已取消操作");
            return;
        }

        // ---- 管理密码验证 ----
        if (state.chatType.equals("admin_pass")) {
            if (msg.equals(adminPass)) {
                verifiedAdmins.put(p.getUniqueId(),
                        System.currentTimeMillis());
                state.chatType = "";
                p.sendMessage("§a[管理] §f验证通过！");
                Bukkit.getScheduler().runTask(this,
                        () -> openAdminMain(p));
            } else {
                state.chatType = "";
                p.sendMessage("§c[管理] §f密码错误！");
            }
            return;
        }

        // ---- 输入目标玩家 ----
        if (state.chatType.equals("admin_player")) {
            state.chatType = "";
            Bukkit.getScheduler().runTask(this,
                    () -> openUserMgmt(p, msg));
            return;
        }

        // ---- 调整永久格子 ----
        if (state.chatType.equals("edit_perm")) {
            state.chatType = "";
            try {
                int delta = Integer.parseInt(msg);
                Map<String, Object> m = getMember(state.targetPlayer);
                int cur = ((Number) m.get("perm")).intValue();
                int newVal = Math.max(0, cur + delta);
                setSlot(state.targetPlayer, "perm", newVal);
                p.sendMessage("§a[管理] §f永久格子: "
                        + cur + " → " + newVal);
            } catch (NumberFormatException ex) {
                p.sendMessage("§c[管理] §f请输入数字！");
            }
            Bukkit.getScheduler().runTask(this,
                    () -> openUserMgmt(p, state.targetPlayer));
            return;
        }

        // ---- 调整会员格子 ----
        if (state.chatType.equals("edit_mem")) {
            state.chatType = "";
            try {
                int delta = Integer.parseInt(msg);
                Map<String, Object> m = getMember(state.targetPlayer);
                int cur = ((Number) m.get("mem")).intValue();
                int newVal = Math.max(0, cur + delta);
                setSlot(state.targetPlayer, "mem", newVal);
                p.sendMessage("§a[管理] §f会员格子: "
                        + cur + " → " + newVal);
            } catch (NumberFormatException ex) {
                p.sendMessage("§c[管理] §f请输入数字！");
            }
            Bukkit.getScheduler().runTask(this,
                    () -> openUserMgmt(p, state.targetPlayer));
            return;
        }

        // ---- 调整会员时间 ----
        if (state.chatType.equals("edit_exp")) {
            state.chatType = "";
            long newExp = parseDateTime(msg);
            if (newExp > 0) {
                setExpire(state.targetPlayer, newExp);
                p.sendMessage("§a[管理] §f过期时间已设为: "
                        + fmtDate(newExp));
            } else if (msg.startsWith("-")) {
                // 相对减少：如 -3 表示减3天
                try {
                    int days = Integer.parseInt(msg);
                    Map<String, Object> m = getMember(state.targetPlayer);
                    long cur = ((Number) m.get("exp")).longValue();
                    long newTime = cur + (long) days * 86400000L;
                    if (newTime < System.currentTimeMillis())
                        newTime = System.currentTimeMillis();
                    setExpire(state.targetPlayer, newTime);
                    p.sendMessage("§a[管理] §f过期时间已调整");
                } catch (NumberFormatException ex) {
                    p.sendMessage("§c[管理] §f格式错误！");
                }
            } else {
                p.sendMessage("§c[管理] §f格式: +7天 / -3天 / 2026-12-31");
            }
            Bukkit.getScheduler().runTask(this,
                    () -> openUserMgmt(p, state.targetPlayer));
            return;
        }

        // ---- 编辑商品名称 ----
        if (state.chatType.equals("edit_item_name")) {
            state.chatType = "";
            state.tmpName = msg;
            p.sendMessage("§a[管理] §f名称已设为: " + msg);
            Bukkit.getScheduler().runTask(this,
                    () -> refreshItemEditor(p));
            return;
        }

        // ---- 编辑商品价格 ----
        if (state.chatType.equals("edit_item_price")) {
            state.chatType = "";
            double price = evalMath(msg);
            if (price >= 0) {
                state.tmpPrice = price;
                p.sendMessage("§a[管理] §f价格已设为: $"
                        + fmt(price));
            } else {
                p.sendMessage("§c[管理] §f请输入有效数字！");
            }
            Bukkit.getScheduler().runTask(this,
                    () -> refreshItemEditor(p));
            return;
        }

        // ---- 编辑库存 ----
        if (state.chatType.equals("edit_item_stock")) {
            state.chatType = "";
            int stock = parseIntSafe(msg);
            state.tmpStock = stock;
            String desc = stock == -1 ? "下架"
                    : (stock == 0 ? "售罄" : "库存=" + stock);
            p.sendMessage("§a[管理] §f库存已设为: " + desc);
            Bukkit.getScheduler().runTask(this,
                    () -> refreshItemEditor(p));
            return;
        }

        // ---- 编辑格口 ----
        if (state.chatType.equals("edit_item_slots")) {
            state.chatType = "";
            int slots = parseIntSafe(msg);
            if (slots > 0) {
                state.tmpSlots = slots;
                p.sendMessage("§a[管理] §f格口已设为: " + slots);
            } else {
                p.sendMessage("§c[管理] §f格口必须大于0！");
            }
            Bukkit.getScheduler().runTask(this,
                    () -> refreshItemEditor(p));
            return;
        }
    }

// ===== SECTION: 命令处理（核心！）=====

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("cy")) return false;

        // 控制台支持 /cy stat
        if (!(sender instanceof Player)) {
            if (args.length > 0 && args[0].equalsIgnoreCase("stat")) {
                showStat(sender);
                return true;
            }
            sender.sendMessage("§c仅玩家可使用此命令");
            return true;
        }

        Player p = (Player) sender;

        // /cy — 打开主菜单
        if (args.length == 0) {
            openMain(p);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "open":
            case "menu":
                openMain(p);
                return true;
            case "my":
                openMy(p);
                return true;
            case "shop":
                openShop(p);
                return true;
            case "storage":
            case "store":
                openStorage(p);
                return true;
            case "admin":
                if (isAdmin(p)) {
                    openAdminMain(p);
                } else {
                    p.sendMessage("§c§l[CY] §f你没有权限！");
                }
                return true;
            case "stat":
                showStat(p);
                return true;
            case "info":
                if (args.length >= 2) {
                    showInfo(p, args[1]);
                } else {
                    showInfo(p, p.getName());
                }
                return true;
            case "give":
                // /cy give <玩家> <永久格> <会员格> <天数>
                if (!isAdmin(p)) {
                    p.sendMessage("§c§l[CY] §f你没有权限！");
                    return true;
                }
                if (args.length < 5) {
                    p.sendMessage("§e用法: /cy give <玩家> <永久格> <会员格> <天数>");
                    return true;
                }
                String target = args[1];
                int permSlots = parseIntSafe(args[2]);
                int memSlots = parseIntSafe(args[3]);
                int days = parseIntSafe(args[4]);
                upsert(target, permSlots, memSlots, days, "");
                p.sendMessage("§a[管理] §f已给予 " + target
                        + ": 永久+" + permSlots
                        + " 会员+" + memSlots
                        + " " + days + "天");
                return true;
            case "take":
                // /cy take <玩家> <永久格> <会员格>
                if (!isAdmin(p)) {
                    p.sendMessage("§c§l[CY] §f你没有权限！");
                    return true;
                }
                if (args.length < 4) {
                    p.sendMessage("§e用法: /cy take <玩家> <永久格> <会员格>");
                    return true;
                }
                String tTarget = args[1];
                int tPerm = parseIntSafe(args[2]);
                int tMem = parseIntSafe(args[3]);
                Map<String, Object> tm = getMember(tTarget);
                int curPerm = ((Number) tm.get("perm")).intValue();
                int curMem = ((Number) tm.get("mem")).intValue();
                setSlot(tTarget, "perm",
                        Math.max(0, curPerm - tPerm));
                setSlot(tTarget, "mem",
                        Math.max(0, curMem - tMem));
                p.sendMessage("§a[管理] §f已扣除 " + tTarget
                        + ": 永久-" + tPerm + " 会员-" + tMem);
                return true;
            case "setexpire":
                if (!isAdmin(p)) {
                    p.sendMessage("§c§l[CY] §f你没有权限！");
                    return true;
                }
                if (args.length < 3) {
                    p.sendMessage("§e用法: /cy setexpire <玩家> <时间>");
                    return true;
                }
                long exp = parseDateTime(args[2]);
                if (exp > 0) {
                    setExpire(args[1], exp);
                    p.sendMessage("§a[管理] §f" + args[1]
                            + " 过期时间已设为: " + fmtDate(exp));
                } else {
                    p.sendMessage("§c[管理] §f时间格式错误！");
                }
                return true;
            case "reload":
                if (!isAdmin(p)) {
                    p.sendMessage("§c§l[CY] §f你没有权限！");
                    return true;
                }
                loadShop();
                p.sendMessage("§a[管理] §f商品配置已重载！共"
                        + shopList.size() + "件商品");
                return true;
            case "update":
                if (!isAdmin(p)) {
                    p.sendMessage("§c§l[CY] §f你没有权限！");
                    return true;
                }
                checkUpdate(p);
                return true;
            case "reset":
                if (!isAdmin(p)) {
                    p.sendMessage("§c§l[CY] §f你没有权限！");
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage("§e用法: /cy reset <玩家> [商品ID]");
                    p.sendMessage("§7不填商品ID则重置该玩家所有限购");
                    return true;
                }
                String resetPlayer = args[1];
                if (args.length >= 3) {
                    String resetItemId = args[2];
                    resetLimitWindow(resetPlayer, resetItemId);
                    p.sendMessage("§a[管理] §f已重置 " + resetPlayer
                            + " 的商品 " + resetItemId + " 限购");
                } else {
                    resetAllLimits(resetPlayer);
                    p.sendMessage("§a[管理] §f已重置 " + resetPlayer
                            + " 的所有限购");
                }
                return true;

            default:
                p.sendMessage("§e[CY] §f用法: /cy <open|shop|storage|admin|stat|info|give|take|setexpire|reload|update>");
                return true;
        }
    }

// ===== SECTION: Tab补全 =====

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("cy"))
            return new ArrayList<>();

        List<String> opts = new ArrayList<>();
        if (args.length == 1) {
            opts.addAll(Arrays.asList("open", "shop", "storage",
                    "admin", "stat", "info", "give", "take",
                    "setexpire", "reload", "update", "reset"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("info") || sub.equals("give")
                    || sub.equals("take") || sub.equals("setexpire")
                    || sub.equals("reset")) {
                for (Player op : Bukkit.getOnlinePlayers()) {
                    opts.add(op.getName());
                }
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("reset")) {
                for (ShopItem it : shopList) {
                    opts.add(it.getId());
                    opts.add(it.getName());
                }
            }
        }
        return filterTab(opts, args[args.length - 1]);
    }
}