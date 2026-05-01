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
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
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
        private double price;
        private int stock;
        private Material logo;
        private String logoItem;
        private double discountRate;
        private long discountStart;
        private long discountEnd;
        // [FIX #2] 盲盒范围支持
        private boolean blindBox;
        private int minDays, maxDays;
        private int minSlots, maxSlots;

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

        // [FIX #2] 带范围的构造器
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
        }

        public ShopItem(String id, String name, int days,
                        int slots, double price, Material logo) {
            this(id, name, days, slots, price, -2, logo,
                    "", 1.0, 0, 0);
        }

        public boolean isLifetime()  { return days == 0; }
        public boolean isDelisted()  { return stock == -1; }
        public boolean isSoldOut()   { return stock == 0; }
        public boolean isAvailable() { return stock > 0 || stock == -2; }
        public boolean hasCustomIcon() {
            return logoItem != null && !logoItem.isEmpty();
        }
        // [FIX #2] 使用字段判断而非ID
        public boolean isBlindBox()  { return blindBox; }

        public double getEffectivePrice() {
            long now = System.currentTimeMillis();
            if (discountRate > 0 && discountRate < 1.0) {
                boolean started = (discountStart <= 0)
                        || (discountStart <= now);
                boolean ended = (discountEnd > 0)
                        && (discountEnd <= now);
                if (started && !ended)
                    return Math.round(price * discountRate);
            }
            return price;
        }

        public boolean isOnDiscount() {
            return getEffectivePrice() < price;
        }

        private static final String[] DATE_PATTERNS = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd",
                "yyyy-M-d HH:mm:ss",
                "yyyy-M-d HH:mm",
                "yyyy-M-d",
                "yyyy/M/d HH:mm:ss",
                "yyyy/M/d HH:mm",
                "yyyy/M/d",
                "yyyy年M月d日 HH:mm:ss",
                "yyyy年M月d日 HH:mm",
                "yyyy年M月d日"
        };

        public static long parseDate(String text) {
            if (text == null || text.trim().isEmpty()) return 0L;
            String t = text.trim();
            for (String p : DATE_PATTERNS) {
                try {
                    return new SimpleDateFormat(p)
                            .parse(t).getTime();
                } catch (Exception ignored) {}
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
                    else { unit = s.substring(i); break; }
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

        public String getId()          { return id; }
        public String getName()        { return name; }
        public void setName(String n)  { name = n; }
        public int getDays()           { return days; }
        public void setDays(int d)     { days = d; }
        public int getSlots()          { return slots; }
        public void setSlots(int s)    { slots = s; }
        public double getPrice()       { return price; }
        public void setPrice(double p) { price = p; }
        public int getStock()          { return stock; }
        public void setStock(int s)    { stock = s; }
        public Material getLogo()      { return logo; }
        public void setLogo(Material m){ logo = m; }
        public String getLogoItem()    { return logoItem; }
        public void setLogoItem(String li) {
            logoItem = (li != null) ? li : "";
        }
        public double getDiscountRate() { return discountRate; }
        public void setDiscountRate(double r) { discountRate = r; }
        public long getDiscountStart()  { return discountStart; }
        public void setDiscountStart(long t) { discountStart = t; }
        public long getDiscountEnd()    { return discountEnd; }
        public void setDiscountEnd(long t)   { discountEnd = t; }
        // [FIX #2] 盲盒范围 getter
        public boolean getBlindBox()    { return blindBox; }
        public int getMinDays()         { return minDays; }
        public int getMaxDays()         { return maxDays; }
        public int getMinSlots()        { return minSlots; }
        public int getMaxSlots()        { return maxSlots; }
    }

// ===== SECTION: 常量与字段 =====

    private static final int PAGE_SIZE      = 45;
    private static final int INVENTORY_SIZE = 54;
    private static final int BTN_SLOT_PREV  = 48;
    private static final int BTN_SLOT_INFO  = 49;
    private static final int BTN_SLOT_NEXT  = 52;
    private static final int BTN_SLOT_BACK  = 53;
    private static final int FREE_SLOTS     = 9;

    private static final double UNLOCK_PERCENT = 0.9;
    private static final long   PURCHASE_CD    = 200;
    private static final long   DBL_CLICK_MS   = 400;
    private static final long   ADMIN_VERIFY_MS = 300000L;

    private static final String T_MAIN       = "§6§l会员中心";
    private static final String T_MY         = "§a§l我的";
    private static final String T_SHOP       = "§b§l商城";
    private static final String T_STORE      = "§d§l主仓";
    private static final String T_ADMIN_MAIN = "§c§l管理面板";
    private static final String T_USER_MGMT  = "§c§l用户管理";
    private static final String T_SHOP_MGMT  = "§6§l商品管理";
    private static final String T_ITEM_EDIT  = "§e§l商品编辑";
    private static final String T_NEW_ITEM   = "§a§l发布商品";

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
    private final Map<UUID, List<ItemStack>> cacheMap =
            new HashMap<>();
    private final Map<UUID, Long> lastPurchase =
            new ConcurrentHashMap<>();
    private final Map<String, Long> lastClickMap =
            new ConcurrentHashMap<>();
    private final Map<UUID, AdminState> adminStates =
            new ConcurrentHashMap<>();
    private final Map<UUID, Long> verifiedAdmins =
            new ConcurrentHashMap<>();
    private final Random rng = new Random();

    private String localVer  = "";
    private String updateCh  = "";
    private String adminPass = "qweasd";
    private String remoteVer = "";

    // [FIX #5] 主控发现相关字段
    private String masterPluginName = "";
    private String sharedSecret     = "";
    private Object masterInstance   = null;
    private Method masterPingMethod = null;
    private Method masterActMethod  = null;
    private Method masterVerifyMethod = null;

    // [FIX #4 #5] 到期提醒追踪
    private final Map<String, Integer> lastReminderLevel =
            new ConcurrentHashMap<>();
    private final Set<String> autoRenewAttempted =
            ConcurrentHashMap.newKeySet();

// ===== SECTION: 内部类 - AdminState =====

    private static class AdminState {
        String targetPlayer = "";
        int    editItemIdx  = -1;
        String chatType     = "";
        String tmpName      = "";
        double tmpPrice     = 0;
        int    tmpStock     = 0;
        int    tmpSlots     = 1;
        String tmpLogo      = "CHEST";
        String tmpLogoItem  = "";
        String tmpId        = "";
        int    tmpDays      = 0;
    }

// ===== SECTION: 范围解析工具 =====

    /** [FIX #2] 解析范围字符串，返回[min,max]，非范围返回null */
    private static int[] parseRange(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        String[] parts = trimmed.split("[\\-~到,]");
        if (parts.length == 2) {
            try {
                int a = Integer.parseInt(parts[0].trim());
                int b = Integer.parseInt(parts[1].trim());
                return new int[]{
                        Math.min(a, b), Math.max(a, b)};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
// ===== SECTION: 生命周期 =====

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        shopIconKey = new NamespacedKey(this, "shop_icon");

        initDB();
        loadShop();
        setupEconomy();

        getCommand("cy").setExecutor(this);
        getCommand("cy").setTabCompleter(this);
        getServer().getPluginManager()
                .registerEvents(this, this);

        // [FIX #4] 自动续费定时检查（每10秒）
        Bukkit.getScheduler().runTaskTimer(this,
                () -> checkAutoRenewal(), 200L, 200L);

        // [FIX #5] 到期提醒定时检查（每30秒）
        Bukkit.getScheduler().runTaskTimer(this,
                () -> checkExpiryReminder(), 600L, 600L);

        Bukkit.getScheduler().runTaskLater(this,
                () -> checkUpdate(null), 60L);

        // [FIX #5] 被控延迟发现主控
        if (masterPluginName != null
                && !masterPluginName.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> discoverMaster(), 100L);
            Bukkit.getScheduler().runTaskTimer(this,
                    () -> discoverMaster(), 6000L, 6000L);
        }

        getLogger().info("CY v" + localVer
                + " | 商品=" + shopList.size() + " | 就绪");
    }

    @Override
    public void onDisable() {
        try {
            if (db != null && !db.isClosed()) db.close();
        } catch (Exception ignored) {}
    }

    private void setupEconomy() {
        if (getServer().getPluginManager()
                .getPlugin("Vault") == null) {
            getLogger().info("[Vault] 未找到，5秒后重试");
            Bukkit.getScheduler().runTaskLater(this,
                    () -> setupEconomy(), 100L);
            return;
        }
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager()
                        .getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
            getLogger().info("[Vault] 已连接: "
                    + economy.getName());
        } else {
            getLogger().warning("[Vault] 未找到经济提供者");
        }
    }

    // [FIX #4] 自动续费：静默扣费，无任何提示
    private void checkAutoRenewal() {
        if (db == null || economy == null) return;
        try {
            long now = System.currentTimeMillis();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name,plan_id,"
                            + "expire_time,auto_renew FROM members "
                            + "WHERE membership_slots>0 "
                            + "AND expire_time>0 "
                            + "AND expire_time<=? "
                            + "AND expire_time>?");
            ps.setLong(1, now + 600000L); // 10分钟内到期
            ps.setLong(2, now);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String name   = rs.getString("player_name");
                String planId = rs.getString("plan_id");
                int autoRenew = rs.getInt("auto_renew");
                if (planId == null || planId.isEmpty())
                    continue;
                if (autoRenew == 0) continue;

                // [FIX #4] 同一周期内只尝试一次
                String key = name + ":" + planId
                        + ":" + (now / 600000L);
                if (autoRenewAttempted.contains(key))
                    continue;
                autoRenewAttempted.add(key);

                ShopItem plan = findItemById(planId);
                if (plan == null || plan.isLifetime())
                    continue;

                Player player = Bukkit.getPlayer(name);
                if (player == null) continue;

                double price = plan.getEffectivePrice();
                // [FIX #4] 无视库存，余额够就扣
                if (economy.has(player, price)) {
                    economy.withdrawPlayer(player, price);
                    upsert(name, 0, 0, plan.getDays(),
                            plan.getId());
                    // [FIX #4] 静默，不发任何消息
                }
                // [FIX #4] 余额不足则静默过期，不发消息
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            getLogger().warning(
                    "[auto-renew] " + e.getMessage());
        }
    }

    // [FIX #5] 到期提醒检查
    private void checkExpiryReminder() {
        if (db == null) return;
        try {
            long now = System.currentTimeMillis();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name,expire_time "
                            + "FROM members "
                            + "WHERE membership_slots>0 "
                            + "AND expire_time>0 "
                            + "AND expire_time>?");
            ps.setLong(1, now);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String name = rs.getString("player_name");
                long expire = rs.getLong("expire_time");
                long diff = expire - now;
                Player player = Bukkit.getPlayer(name);
                if (player == null) continue;

                int level = 0; // 0=无提醒
                if (diff <= 180000L) level = 3; // 3分钟
                else if (diff <= 300000L) level = 2; // 5分钟
                else if (diff <= 600000L) level = 1; // 10分钟

                Integer prev = lastReminderLevel
                        .getOrDefault(name, 0);

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
            getLogger().warning(
                    "[reminder] " + e.getMessage());
        }
    }

    // [FIX #5] 发送提醒消息
    private void sendReminder(Player p, int level,
                              String name) {
        Map<String, Object> m = getMember(name);
        String planName = (String) m.get("plan");
        ShopItem plan = (planName != null && !planName.isEmpty())
                ? findItemById(planName) : null;
        String label = (plan != null)
                ? plan.getName() : "会员套餐";

        boolean autoRenew = isAutoRenew(name);

        switch (level) {
            case 1: // 10分钟
                if (autoRenew) {
                    // [FIX #4] 已在checkAutoRenewal静默处理
                    // 这里只提醒即将到期
                    p.sendMessage("§e§l[CY] §f您的"
                            + label + "将在10分钟内到期，"
                            + "自动续费处理中...");
                } else {
                    p.sendMessage("§e§l[CY] §f您的"
                            + label + "将在10分钟后过期，"
                            + "请尽快续费！");
                }
                break;

            case 2: // 5分钟
                if (autoRenew) {
                    p.sendMessage("§6§l[CY] §e§l注意！"
                            + "§f您的" + label
                            + "将在5分钟内到期，"
                            + "自动续费处理中...");
                } else {
                    p.sendMessage("§6§l[CY] §e§l注意！"
                            + "§f您的" + label
                            + "将在5分钟后过期！");
                }
                p.playSound(p.getLocation(),
                        Sound.BLOCK_NOTE_BLOCK_PLING,
                        1.0f, 1.0f);
                break;

            case 3: // 3分钟
                String title = "§c§l⚠ 即将过期 ⚠";
                String sub = "§e" + label + " 将在3分钟内过期";
                p.sendTitle(title, sub, 10, 60, 20);
                p.sendMessage("§c§l[CY] §f§l紧急！您的"
                        + label + "将在3分钟内过期！"
                        + (autoRenew
                        ? " 自动续费处理中..."
                        : " 请立即续费！"));
                p.playSound(p.getLocation(),
                        Sound.ENTITY_ENDER_DRAGON_GROWL,
                        1.0f, 1.0f);
                break;
        }
    }

// ===== SECTION: 数据库 =====

    private void initDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(
                    getDataFolder(), "members.db");
            db = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS members ("
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
            st.close();
            addColumnIfMissing("free_claimed",
                    "INTEGER DEFAULT 0");
            addColumnIfMissing("plan_id", "TEXT DEFAULT ''");
            addColumnIfMissing("auto_renew",
                    "INTEGER DEFAULT 1");
            getLogger().info("[DB] 初始化成功");
        } catch (Exception e) {
            getLogger().severe(
                    "[DB] 初始化失败: " + e.getMessage());
        }
    }

    private void addColumnIfMissing(String col, String def) {
        try {
            db.createStatement().executeUpdate(
                    "ALTER TABLE members ADD COLUMN "
                            + col + " " + def);
        } catch (SQLException ignored) {}
    }

    private void checkDB() {
        try {
            if (db == null || db.isClosed()) {
                getLogger().warning(
                        "[DB] 连接丢失，重新初始化");
                initDB();
            }
        } catch (SQLException e) { initDB(); }
    }

    private Map<String, Object> getMember(String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("_exists", false);
        r.put("perm", 0); r.put("mem", 0);
        r.put("exp", 0L); r.put("act", 0L);
        r.put("up", 1); r.put("data", "");
        r.put("plan", ""); r.put("free", 0);
        r.put("autorenew", 1);
        if (db == null) return r;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM members WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                r.put("_exists", true);
                r.put("perm",
                        rs.getInt("permanent_slots"));
                r.put("mem",
                        rs.getInt("membership_slots"));
                r.put("exp",
                        rs.getLong("expire_time"));
                r.put("act",
                        rs.getLong("activated"));
                r.put("up",
                        rs.getInt("unlocked_pages"));
                r.put("data", rs.getString("data"));
                r.put("plan", rs.getString("plan_id"));
                r.put("free",
                        rs.getInt("free_claimed"));
                r.put("autorenew",
                        rs.getInt("auto_renew"));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            getLogger().severe(
                    "[DB] getMember: " + e.getMessage());
        }
        return r;
    }

    private boolean memberExists(String name) {
        if (db == null) return false;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM members WHERE player_name=?");
            ps.setString(1, name);
            boolean ok = ps.executeQuery().next();
            ps.close();
            return ok;
        } catch (SQLException e) { return false; }
    }

    private void ensureMember(String name) {
        if (!memberExists(name))
            upsert(name, 0, 0, 0, "");
    }

    private void upsert(String name, int addP,
                        int addM, int addD, String planId) {
        if (db == null) {
            getLogger().severe("[DB] db=null!");
            return;
        }
        long now = System.currentTimeMillis();
        try {
            Map<String, Object> old = getMember(name);
            boolean has = (Boolean) old.get("_exists");

            int oP = has
                    ? ((Number) old.get("perm")).intValue()
                    : 0;
            int oM = has
                    ? ((Number) old.get("mem")).intValue()
                    : 0;
            long oE = has
                    ? ((Number) old.get("exp")).longValue()
                    : 0L;
            long act = has
                    ? ((Number) old.get("act")).longValue()
                    : now;
            int up = has
                    ? ((Number) old.get("up")).intValue()
                    : 1;

            int nP = oP + addP;
            int nM = oM + addM;
            long nE = addD > 0
                    ? Math.max(now, oE)
                      + (long) addD * 86400000L
                    : oE;
            String nPlan = (planId != null
                    && !planId.isEmpty())
                    ? planId
                    : (has ? (String) old.get("plan") : "");

            if (has) {
                PreparedStatement ps = db.prepareStatement(
                        "UPDATE members SET "
                                + "permanent_slots=?,"
                                + "membership_slots=?,"
                                + "expire_time=?,"
                                + "activated=?,"
                                + "unlocked_pages=?,"
                                + "plan_id=? "
                                + "WHERE player_name=?");
                ps.setInt(1, nP);
                ps.setInt(2, nM);
                ps.setLong(3, nE);
                ps.setLong(4, act);
                ps.setInt(5, up);
                ps.setString(6, nPlan);
                ps.setString(7, name);
                ps.executeUpdate();
                ps.close();
            } else {
                PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO members "
                                + "(player_name,"
                                + "permanent_slots,"
                                + "membership_slots,"
                                + "expire_time,"
                                + "activated,"
                                + "unlocked_pages,"
                                + "plan_id,"
                                + "free_claimed,"
                                + "auto_renew) "
                                + "VALUES(?,?,?,?,?,?,?,0,1)");
                ps.setString(1, name);
                ps.setInt(2, nP);
                ps.setInt(3, nM);
                ps.setLong(4, nE);
                ps.setLong(5, act);
                ps.setInt(6, up);
                ps.setString(7, nPlan);
                ps.executeUpdate();
                ps.close();
            }
        } catch (SQLException e) {
            getLogger().severe(
                    "[DB] upsert: " + e.getMessage());
        }
    }

    private void setSlot(String name, String type,
                         int val) {
        if (db == null) return;
        String col = "perm".equals(type)
                ? "permanent_slots"
                : "membership_slots";
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE members SET " + col
                            + "=? WHERE player_name=?");
            ps.setInt(1, Math.max(0, val));
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {}
    }

    private void setExpire(String name, long ms) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE members SET expire_time=?"
                            + " WHERE player_name=?");
            ps.setLong(1, ms);
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {}
    }

    private void setUpPages(String name, int p) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE members SET unlocked_pages=?"
                            + " WHERE player_name=?");
            ps.setInt(1, p);
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {}
    }

    private boolean isFreeClaimed(String name) {
        if (db == null) return false;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT free_claimed FROM members"
                            + " WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            boolean claimed = rs.next()
                    && rs.getInt("free_claimed") == 1;
            rs.close();
            ps.close();
            return claimed;
        } catch (SQLException e) { return false; }
    }

    private void setFreeClaimed(String name) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE members SET free_claimed=1"
                            + " WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {}
    }

    // [FIX #4] 自动续费开关 读取
    private boolean isAutoRenew(String name) {
        if (db == null) return true;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT auto_renew FROM members"
                            + " WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            boolean val = !rs.next()
                    || rs.getInt("auto_renew") == 1;
            rs.close();
            ps.close();
            return val;
        } catch (SQLException e) { return true; }
    }

    // [FIX #4] 自动续费开关 写入
    private void setAutoRenew(String name, boolean val) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE members SET auto_renew=?"
                            + " WHERE player_name=?");
            ps.setInt(1, val ? 1 : 0);
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException ignored) {}
    }

    private boolean delMember(String name) {
        if (db == null) return false;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM members"
                            + " WHERE player_name=?");
            ps.setString(1, name);
            boolean ok = ps.executeUpdate() > 0;
            ps.close();
            return ok;
        } catch (SQLException e) { return false; }
    }

    private int memberCount() {
        if (db == null) return 0;
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM members");
            int c = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            st.close();
            return c;
        } catch (Exception e) { return 0; }
    }
    // ===== SECTION: 通用协议 =====
    public String onSdf1Ping() { return "CY_beibao v" + (localVer != null ? localVer : "1.0") + " members=" + memberCount() + " shop=" + shopList.size(); }
    public boolean onSdf1Activation(String name, int slots, int days) { try { if (days > 0) upsert(name, 0, slots, days, ""); else upsert(name, slots, 0, 0, ""); return true; } catch (Exception e) { getLogger().severe("[联控] 激活失败: " + e.getMessage()); return false; } }
    public boolean onSdf1Verify(String secret) { if (sharedSecret == null || sharedSecret.isEmpty()) return true; return sharedSecret.equals(secret); }

    // ===== SECTION: 反射发现主控 =====
    private void discoverMaster() {
        if (masterPluginName == null || masterPluginName.isEmpty()) return;
        if (masterInstance != null) { try { if (masterPingMethod != null) { masterPingMethod.invoke(masterInstance); return; } } catch (Exception e) { masterInstance = null; masterPingMethod = null; masterActMethod = null; masterVerifyMethod = null; } }
        try {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(masterPluginName);
            if (plugin == null || !plugin.isEnabled()) { if (masterInstance != null) { masterInstance = null; } return; }
            Class<?> clazz = plugin.getClass();
            Method pingM = null, actM = null, verifyM = null;
            try { pingM = clazz.getMethod("onSdf1Ping"); } catch (NoSuchMethodException e) { getLogger().warning("[共享] 主控无 onSdf1Ping"); }
            try { actM = clazz.getMethod("onSdf1Activation", String.class, int.class, int.class); } catch (NoSuchMethodException e) { getLogger().warning("[共享] 主控无 onSdf1Activation"); }
            try { verifyM = clazz.getMethod("onSdf1Verify", String.class); } catch (NoSuchMethodException ignored) {}
            if (verifyM != null && sharedSecret != null && !sharedSecret.isEmpty()) { Object result = verifyM.invoke(plugin, sharedSecret); if (Boolean.FALSE.equals(result)) { getLogger().warning("[共享] 密钥验证失败！"); return; } }
            if (pingM != null) { String result = (String) pingM.invoke(plugin); getLogger().info("[共享] 主控发现成功: " + result); }
            masterInstance = plugin; masterPingMethod = pingM; masterActMethod = actM; masterVerifyMethod = verifyM;
        } catch (Exception e) { getLogger().warning("[共享] 主控发现异常: " + e.getMessage()); masterInstance = null; }
    }
    private boolean callMasterActivation(String name, int slots, int days) { if (masterInstance == null || masterActMethod == null) return false; try { Object result = masterActMethod.invoke(masterInstance, name, slots, days); return Boolean.TRUE.equals(result); } catch (Exception e) { return false; } }

    // ===== SECTION: GUI - 主菜单 =====
    private void openMain(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, T_MAIN); fillBg(g);
        g.setItem(2, mkItem(Material.GREEN_WOOL, "\u00a7a\u00a7l\u6211\u7684"));
        g.setItem(4, mkItem(Material.EMERALD_BLOCK, "\u00a7b\u00a7l\u5546\u57ce"));
        g.setItem(6, mkItem(Material.ENDER_CHEST, "\u00a7d\u00a7l\u4e3b\u4ed3"));
        g.setItem(13, mkItem(Material.BARRIER, "\u00a7c\u00a7l\u5173\u95ed"));
        if (isAdmin(p)) { g.setItem(11, mkItem(Material.REDSTONE_BLOCK, "\u00a74\u00a7l\u7ba1\u7406\u9762\u677f")); }
        if (!isFreeClaimed(p.getName())) { g.setItem(15, mkItem(Material.ENDER_PEARL, "\u00a7a\u00a7l\u2605 \u514d\u8d39\u9886\u53d6\u7a7a\u95f4 \u2605", "\u00a77\u9886\u53d6" + FREE_SLOTS + "\u683c\u6c38\u4e45\u7a7a\u95f4", "", "\u00a7e\u00a7l\u70b9\u51fb\u9886\u53d6")); }
        else { g.setItem(15, mkItem(Material.ENDER_PEARL, "\u00a77\u5df2\u9886\u53d6", "\u00a77\u514d\u8d39\u7a7a\u95f4\u5df2\u9886\u53d6")); }
        p.openInventory(g);
    }

    // ===== SECTION: GUI - 我的 =====
    private void openMy(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, T_MY); fillBg(g); ensureMember(p.getName());
        Map<String, Object> m = getMember(p.getName());
        int perm = ((Number) m.get("perm")).intValue(); int mem = ((Number) m.get("mem")).intValue(); long exp = ((Number) m.get("exp")).longValue();
        int tot = totalSlots(m); int tp = totalPages(m); int up = unlockedPages(m);
        g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e" + p.getName()));
        g.setItem(10, mkItem(Material.CLOCK, "\u00a7a\u5269\u4f59\u65f6\u95f4", "\u00a77" + fmtTime(exp)));
        g.setItem(12, mkItem(Material.DIAMOND_BLOCK, "\u00a7b\u6c38\u4e45\u683c\u5b50: \u00a7e" + perm));
        boolean active = (exp == 0 || exp > System.currentTimeMillis());
        g.setItem(14, mkItem(Material.EMERALD_BLOCK, "\u00a7a\u4f1a\u5458\u683c\u5b50: \u00a7e" + mem, "\u00a77" + (active ? "\u00a7a\u6709\u6548" : "\u00a7c\u5df2\u8fc7\u671f")));
        g.setItem(16, mkItem(Material.CHEST, "\u00a7d\u603b\u683c\u5b50: \u00a7f" + tot, "\u00a77\u603b\u9875\u6570: " + tp));
        boolean autoRenew = isAutoRenew(p.getName());
        Material torch = autoRenew ? Material.REDSTONE_TORCH : Material.TORCH;
        g.setItem(18, mkItem(torch, autoRenew ? "\u00a7a\u00a7l\u81ea\u52a8\u7eed\u8d39: \u5f00\u542f" : "\u00a7c\u00a7l\u81ea\u52a8\u7eed\u8d39: \u5173\u95ed", "\u00a77\u5f53\u524d: " + (autoRenew ? "\u00a7a\u5f00\u542f" : "\u00a7c\u5173\u95ed"), "", "\u00a7e\u00a7l\u70b9\u51fb\u5207\u6362"));
        g.setItem(22, mkItem(Material.BOOK, "\u00a7e\u89e3\u9501\u9875: \u00a7f" + Math.min(up, tp) + "/" + tp));
        g.setItem(26, mkItem(Material.ARROW, "\u00a77\u8fd4\u56de"));
        p.openInventory(g);
    }

    // ===== SECTION: GUI - 商城 =====
    private List<ShopItem> getVisibleShopItems() { List<ShopItem> visible = new ArrayList<>(); for (ShopItem it : shopList) { int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock(); if (st != -1) visible.add(it); } return visible; }
    private void openShop(Player p) {
        List<ShopItem> visible = getVisibleShopItems();
        int sz = Math.max(9, ((visible.size() / 9) + 1) * 9); if (sz > 54) sz = 54;
        Inventory g = Bukkit.createInventory(null, sz, T_SHOP);
        for (int i = 0; i < visible.size() && i < 45; i++) {
            ShopItem it = visible.get(i); int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock();
            double ep = it.getEffectivePrice(); boolean discounted = it.isOnDiscount();
            List<String> lore = new ArrayList<>();
            if (ep == 0) { lore.add("\u00a77\u4ef7\u683c: \u00a7a\u514d\u8d39"); }
            else if (discounted) { lore.add("\u00a77\u539f\u4ef7: \u00a7m\u00a77$" + fmt(it.getPrice())); lore.add("\u00a7e\u00a7l\u6298\u6263\u4ef7: \u00a7c$" + fmt(ep)); }
            else { lore.add("\u00a77\u4ef7\u683c: \u00a7a$" + fmt(ep)); }
            if (it.isBlindBox()) { lore.add("\u00a77\u683c\u5b50: \u00a7e" + it.getMinSlots() + "~" + it.getMaxSlots() + "\u683c"); lore.add("\u00a77\u65f6\u95f4: \u00a7e" + it.getMinDays() + "~" + it.getMaxDays() + "\u5929"); }
            else { lore.add("\u00a77\u683c\u5b50: \u00a7e" + (it.isLifetime() ? "\u7ec8\u8eab" : "+" + it.getSlots())); lore.add("\u00a77\u65f6\u95f4: \u00a7e" + (it.isLifetime() ? "\u7ec8\u8eab" : it.getDays() + "\u5929")); }
            lore.add("\u00a77\u5e93\u5b58: " + (st == 0 ? "\u00a7c\u552e\u7f44" : "\u00a7e" + st)); lore.add("");
            lore.add(st == 0 ? "\u00a7c\u5df2\u552e\u7f44" : (it.isBlindBox() ? "\u00a7e\u00a7l\u70b9\u51fb\u5f00\u76f2\u76d2" : "\u00a7e\u70b9\u51fb\u8d2d\u4e70"));
            g.setItem(i, mkShopDisplay(it, "\u00a7a" + it.getName(), lore.toArray(new String[0])));
        }
        g.setItem(sz - 1, mkItem(Material.ARROW, "\u00a77\u8fd4\u56de")); p.openInventory(g);
    }

    // ===== SECTION: GUI - 主仓 =====
    private void openStorage(Player p) {
        ensureMember(p.getName()); Map<String, Object> m = getMember(p.getName()); int tot = totalSlots(m);
        if (tot <= 0) { p.sendMessage("\u00a7c\u00a7l[\u4ed3\u5e93] \u00a7f\u6ca1\u6709\u5b58\u50a8\u7a7a\u95f4\uff01"); return; }
        List<ItemStack> list = loadStorage(p.getName()); while (list.size() < tot) list.add(null);
        cacheMap.put(p.getUniqueId(), list); pageMap.put(p.getUniqueId(), 1);
        p.openInventory(Bukkit.createInventory(null, INVENTORY_SIZE, T_STORE)); refreshPage(p);
    }
    private void refreshPage(Player p) {
        Inventory g = p.getOpenInventory().getTopInventory(); UUID u = p.getUniqueId();
        List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>()); int pg = pageMap.getOrDefault(u, 1); int start = (pg - 1) * PAGE_SIZE;
        Map<String, Object> m = getMember(p.getName()); int us = usableOnPage(pg, m); int tp = totalPages(m); int up = unlockedPages(m);
        ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, "\u00a77\u9501\u5b9a");
        for (int i = 0; i < PAGE_SIZE; i++) { if (i < us) { int idx = start + i; g.setItem(i, (idx < list.size() && list.get(idx) != null) ? list.get(idx) : null); } else { g.setItem(i, gl); } }
        g.setItem(BTN_SLOT_PREV, pg > 1 ? mkItem(Material.ARROW, "\u00a7a\u4e0a\u4e00\u9875") : mkItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        g.setItem(BTN_SLOT_INFO, mkItem(Material.PAPER, "\u00a7e\u7b2c" + pg + "/" + tp + "\u9875", "\u00a77\u7269\u54c1: " + countItems(list, pg) + "/" + us));
        g.setItem(BTN_SLOT_BACK, mkItem(Material.BARRIER, "\u00a7c\u8fd4\u56de"));
        if (pg < tp) { boolean can = (pg < up) || (countItems(list, pg) >= (int)(us * UNLOCK_PERCENT)); g.setItem(BTN_SLOT_NEXT, can ? mkItem(Material.ARROW, "\u00a7a\u4e0b\u4e00\u9875") : mkItem(Material.RED_STAINED_GLASS_PANE, "\u00a7c\u4e0b\u4e00\u9875", "\u00a77\u9700\u8fbe" + (int)(us * UNLOCK_PERCENT) + "+\u683c")); }
        else { g.setItem(BTN_SLOT_NEXT, mkItem(Material.GRAY_STAINED_GLASS_PANE, " ")); }
    }
    private boolean nextPage(Player p) {
        UUID u = p.getUniqueId(); int pg = pageMap.getOrDefault(u, 1); int nx = pg + 1;
        Map<String, Object> m = getMember(p.getName()); int tp = totalPages(m); int up = unlockedPages(m);
        if (nx > tp) { p.sendMessage("\u00a7c\u00a7l[\u4ed3\u5e93] \u00a7f\u5df2\u5230\u6700\u5927\u9875\uff01"); return false; }
        if (nx <= up) { pageMap.put(u, nx); refreshPage(p); return true; }
        List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>()); int filled = countItems(list, pg); int us = usableOnPage(pg, m);
        if (filled >= us * UNLOCK_PERCENT) { up++; setUpPages(p.getName(), up); pageMap.put(u, nx); refreshPage(p); p.sendMessage("\u00a7a\u00a7l[\u4ed3\u5e93] \u00a7f\u5df2\u89e3\u9501\u7b2c" + nx + "\u9875\uff01"); return true; }
        p.sendMessage("\u00a7c\u00a7l[\u4ed3\u5e93] \u00a7f\u5b58\u50a8\u91cf\u4e0d\u8db3\uff08" + filled + "/" + (int)(us * UNLOCK_PERCENT) + "\uff09"); return false;
    }

    // ===== SECTION: GUI - 管理面板 =====
    private void openAdminMain(Player p) { if (!isVerifiedAdmin(p)) { startAdminAuth(p); return; } getOrCreateState(p).chatType = ""; Inventory g = Bukkit.createInventory(null, 27, T_ADMIN_MAIN); fillBg(g); g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e\u7ba1\u7406\u9762\u677f")); g.setItem(11, mkItem(Material.ARMOR_STAND, "\u00a7a\u7528\u6237\u7ba1\u7406", "\u00a77\u7ba1\u7406\u73a9\u5bb6\u6570\u636e")); g.setItem(15, mkItem(Material.EMERALD, "\u00a76\u5546\u54c1\u7ba1\u7406", "\u00a77\u7f16\u8f91\u5546\u57ce\u5546\u54c1")); g.setItem(22, mkItem(Material.ARROW, "\u00a77\u8fd4\u56de")); p.openInventory(g); }
    private void startAdminAuth(Player p) { p.closeInventory(); getOrCreateState(p).chatType = "admin_pass"; p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u8bf7\u8f93\u5165\u7ba1\u7406\u5bc6\u7801\uff080/exit \u53d6\u6d88\uff09\uff1a"); if (adminPass.equals("qweasd")) { p.sendMessage("\u00a7c\u00a7l[\u8b66\u544a] \u00a7f\u4f7f\u7528\u9ed8\u8ba4\u5bc6\u7801\uff01"); } }
    private void startUserMgmtAuth(Player p) { p.closeInventory(); getOrCreateState(p).chatType = "admin_player"; p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u8bf7\u8f93\u5165\u73a9\u5bb6ID\uff080/exit \u53d6\u6d88\uff09\uff1a"); }
    private void openUserMgmt(Player p, String tn) {
        AdminState state = getOrCreateState(p); state.targetPlayer = tn; Inventory g = Bukkit.createInventory(null, 27, T_USER_MGMT); fillBg(g); ensureMember(tn); Map<String, Object> m = getMember(tn);
        g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e\u7ba1\u7406: " + tn));
        if (m.isEmpty() || !(Boolean) m.get("_exists")) { g.setItem(13, mkItem(Material.BEDROCK, "\u00a7c\u73a9\u5bb6\u4e0d\u5728\u6570\u636e\u5e93\u4e2d")); }
        else { g.setItem(10, mkItem(Material.GREEN_WOOL, "\u00a7a\u6c38\u4e45\u683c\u5b50: \u00a7e" + ((Number) m.get("perm")).intValue(), "\u00a77\u70b9\u51fb\u8f93\u5165\u8c03\u6574")); g.setItem(12, mkItem(Material.EMERALD_BLOCK, "\u00a7b\u4f1a\u5458\u683c\u5b50: \u00a7e" + ((Number) m.get("mem")).intValue(), "\u00a77\u70b9\u51fb\u8f93\u5165\u8c03\u6574")); g.setItem(14, mkItem(Material.CLOCK, "\u00a7e\u4f1a\u5458\u65f6\u95f4", "\u00a77" + fmtTime(((Number) m.get("exp")).longValue()), "\u00a77\u70b9\u51fb\u8f93\u5165\u8c03\u6574")); g.setItem(16, mkItem(Material.BARRIER, "\u00a7c\u79fb\u9664\u73a9\u5bb6", "\u00a77\u70b9\u51fb\u5220\u9664\u6b64\u73a9\u5bb6\u6570\u636e")); }
        g.setItem(22, mkItem(Material.ARROW, "\u00a77\u8fd4\u56de")); p.openInventory(g);
    }

    // ===== SECTION: GUI - 商品管理 =====
    private void openShopMgmt(Player p) {
        getOrCreateState(p).chatType = ""; Inventory g = Bukkit.createInventory(null, 54, T_SHOP_MGMT); fillBg(g);
        for (int i = 0; i < shopList.size() && i < 9; i++) { ShopItem it = shopList.get(i); int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock(); String stockText = st == -1 ? "\u00a77\u4e0b\u67b6" : (st == 0 ? "\u00a7c\u552e\u7f44" : "\u00a7e" + st); g.setItem(18 + i, mkShopDisplay(it, "\u00a7e#" + it.getId() + " " + it.getName(), "\u00a77\u5e93\u5b58: " + stockText + "  \u683c\u53e3: " + it.getSlots(), "\u00a77\u4ef7\u683c: \u00a7a$" + fmt(it.getEffectivePrice()), "", "\u00a77\u53cc\u51fb\u7f16\u8f91")); }
        g.setItem(49, mkItem(Material.ARROW, "\u00a77\u8fd4\u56de")); g.setItem(53, mkItem(Material.EMERALD, "\u00a7a\u00a7l\u53d1\u5e03\u65b0\u5546\u54c1", "\u00a77\u70b9\u51fb\u4e0a\u67b6\u65b0\u5546\u54c1")); p.openInventory(g);
    }

    // ===== SECTION: GUI - 商品编辑 =====
    private void openItemEditor(Player p, int itemIdx) { AdminState state = getOrCreateState(p); state.editItemIdx = itemIdx; ShopItem it = shopList.get(itemIdx); state.tmpName = it.getName(); state.tmpPrice = it.getPrice(); state.tmpStock = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock(); state.tmpLogo = it.getLogo().name(); state.tmpLogoItem = it.getLogoItem(); state.tmpId = it.getId(); state.tmpDays = it.getDays(); state.tmpSlots = it.getSlots(); refreshItemEditor(p); }
    private void openNewItem(Player p) { AdminState state = getOrCreateState(p); state.editItemIdx = -1; state.tmpId = String.valueOf(1000 + rng.nextInt(9000)); state.tmpName = "\u65b0\u5546\u54c1"; state.tmpPrice = 100; state.tmpStock = 0; state.tmpSlots = 1; state.tmpDays = 1; state.tmpLogo = "CHEST"; state.tmpLogoItem = ""; refreshItemEditor(p); }
    private void refreshItemEditor(Player p) {
        AdminState state = adminStates.get(p.getUniqueId()); if (state == null) return;
        String title = (state.editItemIdx < 0) ? T_NEW_ITEM : T_ITEM_EDIT; Inventory g = Bukkit.createInventory(null, 54, title);
        for (int i = 0; i < LOGO_OPTIONS.length; i++) { Material m = LOGO_OPTIONS[i]; boolean sel = m.name().equals(state.tmpLogo) && state.tmpLogoItem.isEmpty(); g.setItem(i, mkItem(m, sel ? "\u00a7a>> " + m.name() + " <<" : "\u00a7e" + m.name())); }
        g.setItem(11, mkItem(Material.PAPER, "\u00a7e\u5546\u54c1\u540d\u79f0", "\u00a77\u5f53\u524d: " + state.tmpName, "", "\u00a77\u53cc\u51fb\u7f16\u8f91"));
        g.setItem(13, mkItem(Material.DIAMOND, "\u00a7e\u4ef7\u683c", "\u00a77\u5f53\u524d: \u00a7a$" + fmt(state.tmpPrice), "", "\u00a77\u53cc\u51fb\u7f16\u8f91"));
        g.setItem(15, mkItem(Material.CHEST, "\u00a7e\u5e93\u5b58/\u683c\u53e3", "\u00a77\u5e93\u5b58: \u00a7f" + state.tmpStock + "  \u683c\u53e3: \u00a7f" + state.tmpSlots, "", "\u00a77\u5355\u51fb\u6539\u5e93\u5b58  \u53cc\u51fb\u6539\u683c\u53e3"));
        if (!state.tmpLogoItem.isEmpty()) { ItemStack ci = decodeSingleItem(state.tmpLogoItem); if (ci != null) { ItemStack d = ci.clone(); ItemMeta meta = d.getItemMeta(); if (meta != null) { meta.setDisplayName("\u00a7e\u5f53\u524d\u56fe\u6807 (\u81ea\u5b9a\u4e49)"); meta.setLore(Arrays.asList("\u00a77\u7c7b\u578b: " + ci.getType().name(), "", "\u00a77\u4ece\u80cc\u5305\u62d6\u5165\u65b0\u7269\u54c1\u53ef\u66ff\u6362")); d.setItemMeta(meta); } g.setItem(17, d); } else { g.setItem(17, mkItem(Material.BARRIER, "\u00a7c\u56fe\u6807\u6570\u636e\u635f\u574f")); } }
        else { Material lm = parseMaterialSafe(state.tmpLogo, Material.CHEST); g.setItem(17, mkItem(lm, "\u00a7e\u5f53\u524d\u56fe\u6807", "\u00a77" + state.tmpLogo, "", "\u00a77\u70b9\u51fb\u4e0a\u65b9\u66ff\u6362\u6216\u4ece\u80cc\u5305\u62d6\u5165")); }
        g.setItem(21, mkItem(Material.BOOK, "\u00a77\u5185\u90e8ID (\u53ea\u8bfb)", "\u00a77" + state.tmpId, "\u00a77ID\u4e0d\u53ef\u4fee\u6539"));
        g.setItem(23, mkItem(Material.EMERALD, "\u00a7a\u00a7l\u53d1\u5e03", "\u00a77\u786e\u8ba4\u53d1\u5e03/\u4fdd\u5b58"));
        g.setItem(25, mkItem(Material.FEATHER, "\u00a7c\u53d6\u6d88", "\u00a77\u653e\u5f03\u7f16\u8f91"));
        p.openInventory(g);
    }
    private void publishItem(Player p, AdminState state) {
        if (state.tmpName.isEmpty()) { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u5546\u54c1\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a"); return; }
        if (state.tmpPrice <= 0) { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u4ef7\u683c\u5fc5\u987b\u5927\u4e8e0"); return; }
        String li = state.tmpLogoItem;
        if (state.editItemIdx < 0) { ShopItem it = new ShopItem(state.tmpId, state.tmpName, state.tmpDays, state.tmpSlots, state.tmpPrice, state.tmpStock, parseMaterialSafe(state.tmpLogo, Material.CHEST), li, 1.0, 0, 0); shopList.add(it); stockMap.put(it.getId(), it.getStock()); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5df2\u53d1\u5e03: " + it.getName() + " #" + it.getId()); }
        else if (state.editItemIdx < shopList.size()) { ShopItem old = shopList.get(state.editItemIdx); ShopItem up = new ShopItem(old.getId(), state.tmpName, state.tmpDays, state.tmpSlots, state.tmpPrice, state.tmpStock, parseMaterialSafe(state.tmpLogo, Material.CHEST), li, old.getDiscountRate(), old.getDiscountStart(), old.getDiscountEnd()); shopList.set(state.editItemIdx, up); stockMap.put(up.getId(), up.getStock()); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5df2\u4fdd\u5b58: " + up.getName() + " #" + up.getId()); }
        saveShopFile(); openShopMgmt(p);
    }
    // ===== SECTION: 购买逻辑 =====
    private void buy(Player p, ShopItem item) {
        if (economy == null) { p.sendMessage("\u00a7c\u00a7l[\u5546\u57ce] \u00a7f\u7ecf\u6d4e\u4e0d\u53ef\u7528\uff01"); return; }
        if (item.isBlindBox()) { openBox(p, item); return; }
        int st = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock();
        if (!item.isAvailable() && st != 0) { p.sendMessage("\u00a7c\u00a7l[\u5546\u57ce] \u00a7f\u5df2\u4e0b\u67b6"); return; }
        if (st == 0) { p.sendMessage("\u00a7c\u00a7l[\u5546\u57ce] \u00a7f\u5df2\u552e\u7f44\uff01"); return; }
        Long last = lastPurchase.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < PURCHASE_CD) { p.sendMessage("\u00a7e\u00a7l[\u5546\u57ce] \u00a7f\u8d2d\u4e70\u592a\u5feb"); return; }
        double ep = item.getEffectivePrice();
        if (!economy.has(p, ep)) { double bal = economy.getBalance(p); p.sendMessage("\u00a7c\u00a7l[\u5546\u57ce] \u00a7f\u4f59\u989d\u4e0d\u8db3\uff01"); p.sendMessage("\u00a77\u4ef7\u683c: \u00a7e$" + fmt(ep) + " \u00a77\u4f59\u989d: \u00a7a$" + fmt(bal) + " \u00a77\u5dee: \u00a7c$" + fmt(ep - bal)); return; }
        economy.withdrawPlayer(p, ep); lastPurchase.put(p.getUniqueId(), System.currentTimeMillis());
        ensureMember(p.getName());
        if (item.isLifetime()) { upsert(p.getName(), item.getSlots(), 0, 0, item.getId()); } else { upsert(p.getName(), 0, item.getSlots(), item.getDays(), item.getId()); }
        if (st > 0) { st--; stockMap.put(item.getId(), st); saveShopFile(); }
        String tl = item.isLifetime() ? "\u7ec8\u8eab" : (item.getDays() + "\u5929");
        p.sendMessage("\u00a7a\u00a7l[\u5546\u57ce] \u00a7f" + item.getName() + " | " + tl + " | +" + item.getSlots() + "\u683c | \u00a7c-$" + fmt(ep) + " \u00a77\u4f59\u989d: \u00a7a$" + fmt(economy.getBalance(p)));
    }

    private void openBox(Player p, ShopItem item) {
        if (economy == null) { p.sendMessage("\u00a7c\u00a7l[\u5546\u57ce] \u00a7f\u7ecf\u6d4e\u4e0d\u53ef\u7528\uff01"); return; }
        int st = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock();
        if (st == -1 || st == 0) { p.sendMessage("\u00a7c\u00a7l[\u5546\u57ce] \u00a7f" + (st == -1 ? "\u5df2\u4e0b\u67b6" : "\u5df2\u552e\u7f44")); return; }
        Long last = lastPurchase.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < PURCHASE_CD) { p.sendMessage("\u00a7e\u00a7l[\u5546\u57ce] \u00a7f\u8d2d\u4e70\u592a\u5feb"); return; }
        double ep = item.getEffectivePrice();
        if (!economy.has(p, ep)) { p.sendMessage("\u00a7c\u00a7l[\u5546\u57ce] \u00a7f\u4f59\u989d\u4e0d\u8db3\uff01"); return; }
        economy.withdrawPlayer(p, ep); lastPurchase.put(p.getUniqueId(), System.currentTimeMillis());
        int rd = item.getMinDays() + rng.nextInt(item.getMaxDays() - item.getMinDays() + 1);
        int rs = item.getMinSlots() + rng.nextInt(item.getMaxSlots() - item.getMinSlots() + 1);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        p.sendMessage("\u00a76\u00a7l[\u76f2\u76d2] \u00a7e\u6b63\u5728\u5f00\u542f" + item.getName() + "...");
        final int fd = rd, fs = rs; final ShopItem bi = item; final int bs = st;
        Bukkit.getScheduler().runTaskLater(this, () -> { p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f); p.sendMessage("\u00a76\u00a7l[\u76f2\u76d2] \u00a77\u7269\u54c1\u6b63\u5728\u65cb\u8f6c..."); }, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> { p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f); p.sendMessage("\u00a76\u00a7l[\u76f2\u76d2] \u00a77\u5373\u5c06\u63ed\u6653..."); }, 40L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            if (bs > 0) { int cur = stockMap.containsKey(bi.getId()) ? stockMap.get(bi.getId()) : bi.getStock(); if (cur > 0) { stockMap.put(bi.getId(), cur - 1); saveShopFile(); } }
            upsert(p.getName(), 0, fs, fd, bi.getId());
            p.sendTitle("\u00a76\u00a7l\u2726 " + bi.getName() + " \u2726", "\u00a7e" + fd + "\u5929\u4f1a\u5458 + " + fs + "\u683c\u7a7a\u95f4", 10, 60, 20);
            p.sendMessage("\u00a76\u00a7l[\u76f2\u76d2] \u00a7e\u00a7l\u606d\u559c\uff01\u83b7\u5f97 \u00a7f" + fd + "\u5929\u4f1a\u5458 + " + fs + "\u683c\u7a7a\u95f4");
            p.sendMessage("\u00a77\uff08\u4fdd\u5e95 " + bi.getMinDays() + "~" + bi.getMaxDays() + "\u5929 / " + bi.getMinSlots() + "~" + bi.getMaxSlots() + "\u683c\uff09");
        }, 60L);
    }

    private boolean isDoubleClick(Player p, int slot) { String key = p.getUniqueId() + ":" + slot; long now = System.currentTimeMillis(); Long prev = lastClickMap.get(key); lastClickMap.put(key, now); return prev != null && (now - prev) < DBL_CLICK_MS; }

    // ===== SECTION: 事件处理 =====
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked(); String t = e.getView().getTitle(); int r = e.getRawSlot(); UUID u = p.getUniqueId();
        if (t.equals(T_MAIN)) { e.setCancelled(true); if (r == 2) openMy(p); else if (r == 4) openShop(p); else if (r == 6) openStorage(p); else if (r == 11 && isAdmin(p)) openAdminMain(p); else if (r == 13) p.closeInventory(); else if (r == 15) handleFreeClaim(p); return; }
        if (t.equals(T_MY)) { e.setCancelled(true); if (r == 26) openMain(p); else if (r == 18) { boolean c = isAutoRenew(p.getName()); setAutoRenew(p.getName(), !c); p.sendMessage(!c ? "\u00a7a[CY] \u00a7f\u81ea\u52a8\u7eed\u8d39\u5df2\u5f00\u542f" : "\u00a7c[CY] \u00a7f\u81ea\u52a8\u7eed\u8d39\u5df2\u5173\u95ed"); openMy(p); } return; }
        if (t.equals(T_ADMIN_MAIN)) { e.setCancelled(true); if (r == 22) openAdminMain(p); else if (r == 11) startUserMgmtAuth(p); else if (r == 15) openShopMgmt(p); return; }
        if (t.equals(T_USER_MGMT)) { e.setCancelled(true); handleUserMgmtClick(p, r); return; }
        if (t.equals(T_SHOP_MGMT)) { e.setCancelled(true); if (r == 49) openAdminMain(p); else if (r == 53) openNewItem(p); else if (r >= 18 && r <= 26) { int idx = r - 18; if (idx < shopList.size() && isDoubleClick(p, r)) openItemEditor(p, idx); } return; }
        if (t.equals(T_ITEM_EDIT) || t.equals(T_NEW_ITEM)) { e.setCancelled(true); handleItemEditorClick(p, r, e); return; }
        if (t.equals(T_SHOP)) { e.setCancelled(true); int sz = e.getInventory().getSize(); if (r == sz - 1) { openMain(p); return; } List<ShopItem> vis = getVisibleShopItems(); if (r >= 0 && r < vis.size() && r < 45) buy(p, vis.get(r)); return; }
        if (t.equals(T_STORE)) { handleStorageClick(p, r, e); return; }
    }

    private void handleFreeClaim(Player p) { if (isFreeClaimed(p.getName())) { p.sendMessage("\u00a7c\u00a7l[CY] \u00a7f\u5df2\u9886\u53d6\u8fc7\u514d\u8d39\u7a7a\u95f4\uff01"); return; } ensureMember(p.getName()); upsert(p.getName(), FREE_SLOTS, 0, 0, ""); setFreeClaimed(p.getName()); if (isFreeClaimed(p.getName())) { p.sendMessage("\u00a7a\u00a7l[CY] \u00a7f\u5df2\u9886\u53d6" + FREE_SLOTS + "\u683c\u514d\u8d39\u7a7a\u95f4\uff01"); } else { p.sendMessage("\u00a7c\u00a7l[CY] \u00a7f\u9886\u53d6\u5931\u8d25\uff0c\u8bf7\u8054\u7cfb\u7ba1\u7406\u5458\uff01"); } openMain(p); }

    private void handleUserMgmtClick(Player p, int r) { AdminState state = adminStates.get(p.getUniqueId()); if (state == null) return; String tn = state.targetPlayer; if (tn.isEmpty()) return; if (r == 22) { openAdminMain(p); } else if (r == 10) { state.chatType = "edit_perm"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u6c38\u4e45\u683c\u5b50\u8c03\u6574\u91cf (+/-)\uff1a"); } else if (r == 12) { state.chatType = "edit_mem"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u4f1a\u5458\u683c\u5b50\u8c03\u6574\u91cf (+/-)\uff1a"); } else if (r == 14) { state.chatType = "edit_exp"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u4f1a\u5458\u65f6\u95f4: +7 / -3 / 2026-12-31"); } else if (r == 16) { delMember(tn); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5df2\u5220\u9664: " + tn); state.targetPlayer = ""; openAdminMain(p); } }

    private void handleItemEditorClick(Player p, int r, InventoryClickEvent e) {
        AdminState state = adminStates.get(p.getUniqueId()); if (state == null) return;
        if (r >= 0 && r < LOGO_OPTIONS.length) { state.tmpLogo = LOGO_OPTIONS[r].name(); state.tmpLogoItem = ""; refreshItemEditor(p); return; }
        if (r == 11 && isDoubleClick(p, r)) { state.chatType = "edit_item_name"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u5546\u54c1\u540d\u79f0\uff1a"); return; }
        if (r == 13 && isDoubleClick(p, r)) { state.chatType = "edit_item_price"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u4ef7\u683c\uff0800=\u514d\u8d39\uff09\uff1a"); return; }
        if (r == 15) { if (e.getClick() == org.bukkit.event.inventory.ClickType.DOUBLE_CLICK) { state.chatType = "edit_item_slots"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u683c\u53e3\u6570\uff1a"); } else { state.chatType = "edit_item_stock"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u5e93\u5b58\uff08-1\u4e0b\u67b6 0\u552e\u7f44\uff09\uff1a"); } return; }
        if (r == 17) { ItemStack cursor = e.getCursor(); if (cursor != null && cursor.getType() != Material.AIR) { if (isShopIcon(cursor)) { state.tmpLogo = cursor.getType().name(); state.tmpLogoItem = ""; } else { state.tmpLogo = "CUSTOM"; state.tmpLogoItem = encodeSingleItem(cursor); } e.setCursor(null); refreshItemEditor(p); } return; }
        if (r == 23) { publishItem(p, state); return; }
        if (r == 25) { openShopMgmt(p); return; }
    }

    private void handleStorageClick(Player p, int r, InventoryClickEvent e) {
        UUID u = p.getUniqueId(); int topSize = e.getInventory().getSize();
        if (r >= topSize) { return; }
        if (r < 0) { e.setCancelled(true); return; }
        if (r >= 48) { e.setCancelled(true); if (r == BTN_SLOT_BACK) { openMain(p); } else if (r == BTN_SLOT_PREV) { int pg = pageMap.getOrDefault(u, 1); if (pg > 1) { pageMap.put(u, pg - 1); refreshPage(p); } } else if (r == BTN_SLOT_NEXT) { nextPage(p); } return; }
        Map<String, Object> m = getMember(p.getName()); int pg = pageMap.getOrDefault(u, 1); int us = usableOnPage(pg, m);
        if (r >= us) { e.setCancelled(true); return; }
        int idx = (pg - 1) * PAGE_SIZE + r; List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>()); while (list.size() <= idx) { list.add(null); }
        ItemStack slotItem = list.get(idx); ItemStack handItem = e.getCursor();
        boolean slotEmpty = (slotItem == null || slotItem.getType() == Material.AIR);
        boolean handEmpty = (handItem == null || handItem.getType() == Material.AIR);
        boolean slotLocked = (!slotEmpty && slotItem.getType() == Material.GRAY_STAINED_GLASS_PANE);
        if (slotLocked) { e.setCancelled(true); return; }
        e.setCancelled(true); e.setCursor(null); e.getInventory().setItem(r, null);
        if (slotEmpty && !handEmpty) { list.set(idx, handItem.clone()); e.getInventory().setItem(r, handItem.clone()); }
        else if (!slotEmpty && handEmpty) { ItemStack tg = slotItem.clone(); HashMap<Integer, ItemStack> ov = p.getInventory().addItem(tg); if (!ov.isEmpty()) { list.set(idx, slotItem.clone()); e.getInventory().setItem(r, slotItem.clone()); p.sendMessage("\u00a7c\u00a7l[\u4ed3\u5e93] \u00a7f\u80cc\u5305\u5df2\u6ee1\uff01"); } }
        else if (!slotEmpty && !handEmpty) { ItemStack tg = slotItem.clone(); HashMap<Integer, ItemStack> ov = p.getInventory().addItem(tg); if (ov.isEmpty()) { list.set(idx, handItem.clone()); e.getInventory().setItem(r, handItem.clone()); } else { list.set(idx, slotItem.clone()); e.getInventory().setItem(r, slotItem.clone()); e.setCursor(handItem.clone()); p.sendMessage("\u00a7c\u00a7l[\u4ed3\u5e93] \u00a7f\u80cc\u5305\u5df2\u6ee1\uff0c\u65e0\u6cd5\u4ea4\u6362\uff01"); } }
        cacheMap.put(u, list); saveStorage(p.getName(), list); refreshPage(p);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) { if (!(e.getPlayer() instanceof Player)) return; Player p = (Player) e.getPlayer(); if (e.getView().getTitle().equals(T_STORE)) { List<ItemStack> list = cacheMap.remove(p.getUniqueId()); pageMap.remove(p.getUniqueId()); if (list != null) saveStorage(p.getName(), list); } }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return; Player p = (Player) e.getWhoClicked();
        if (!e.getView().getTitle().equals(T_STORE)) { e.setCancelled(true); return; }
        UUID u = p.getUniqueId(); int topSize = e.getInventory().getSize(); int pg = pageMap.getOrDefault(u, 1); Map<String, Object> m = getMember(p.getName()); int us = usableOnPage(pg, m);
        for (int slot : e.getRawSlots()) { if (slot < topSize) { if (slot >= us) { e.setCancelled(true); return; } int idx = (pg - 1) * PAGE_SIZE + slot; List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>()); while (list.size() <= idx) list.add(null); ItemStack ex = list.get(idx); if (ex != null && ex.getType() == Material.GRAY_STAINED_GLASS_PANE) { e.setCancelled(true); return; } } }
        Bukkit.getScheduler().runTaskLater(this, () -> syncStorageFromInventory(p), 1L);
    }

    private void syncStorageFromInventory(Player p) { UUID u = p.getUniqueId(); if (!p.getOpenInventory().getTitle().equals(T_STORE)) return; List<ItemStack> list = cacheMap.get(u); if (list == null) return; Inventory top = p.getOpenInventory().getTopInventory(); int pg = pageMap.getOrDefault(u, 1); int start = (pg - 1) * PAGE_SIZE; Map<String, Object> m = getMember(p.getName()); int us = usableOnPage(pg, m); for (int i = 0; i < us && i < PAGE_SIZE; i++) { int idx = start + i; while (list.size() <= idx) list.add(null); ItemStack si = top.getItem(i); if (si != null && si.getType() == Material.GRAY_STAINED_GLASS_PANE) continue; list.set(idx, si); } }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) { Bukkit.getScheduler().runTaskLater(this, () -> checkAutoRenewal(), 100L); if (masterInstance == null && masterPluginName != null && !masterPluginName.isEmpty()) { Bukkit.getScheduler().runTaskLater(this, () -> discoverMaster(), 100L); } }
    @EventHandler public void onPlayerQuit(PlayerQuitEvent event) { UUID u = event.getPlayer().getUniqueId(); adminStates.remove(u); verifiedAdmins.remove(u); lastReminderLevel.remove(event.getPlayer().getName()); List<String> keys = new ArrayList<>(); for (String k : lastClickMap.keySet()) { if (k.startsWith(u.toString())) keys.add(k); } for (String k : keys) lastClickMap.remove(k); }

    // ===== SECTION: 聊天输入 =====
    @EventHandler
    public void onPlayerChatInput(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player p = event.getPlayer(); UUID u = p.getUniqueId(); AdminState state = adminStates.get(u); if (state == null || state.chatType.isEmpty()) return; event.setCancelled(true); String msg = event.getMessage().trim();
        if (isCancel(msg)) { state.chatType = ""; p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u5df2\u53d6\u6d88"); return; }
        String ct = state.chatType; state.chatType = "";
        if ("admin_pass".equals(ct)) { if (msg.equals(adminPass)) { verifiedAdmins.put(u, System.currentTimeMillis()); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u6b63\u786e\uff01"); Bukkit.getScheduler().runTaskLater(this, () -> openAdminMain(p), 1L); } else { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u9519\u8bef\uff01"); } return; }
        if ("admin_player".equals(ct)) { if (memberExists(msg)) { state.targetPlayer = msg; final String tn = msg; Bukkit.getScheduler().runTaskLater(this, () -> openUserMgmt(p, tn), 1L); } else { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u4e0d\u5b58\u5728\uff0c\u8bf7\u91cd\u65b0\u8f93\u5165\uff1a"); state.chatType = "admin_player"; } return; }
        if ("edit_perm".equals(ct)) { applySlotEdit(p, state, "perm", msg); return; }
        if ("edit_mem".equals(ct)) { applySlotEdit(p, state, "mem", msg); return; }
        if ("edit_exp".equals(ct)) { applyExpEdit(p, state, msg); return; }
        if ("edit_item_name".equals(ct)) { state.tmpName = colorize(msg); Bukkit.getScheduler().runTaskLater(this, () -> refreshItemEditor(p), 1L); return; }
        if ("edit_item_price".equals(ct)) { if ("00".equals(msg)) { state.tmpPrice = 0; } else { double val = evalMath(msg); if (val >= 0) state.tmpPrice = val; } Bukkit.getScheduler().runTaskLater(this, () -> refreshItemEditor(p), 1L); return; }
        if ("edit_item_stock".equals(ct)) { state.tmpStock = (int) evalMath(msg); Bukkit.getScheduler().runTaskLater(this, () -> refreshItemEditor(p), 1L); return; }
        if ("edit_item_slots".equals(ct)) { state.tmpSlots = "00".equals(msg) ? 0 : (int) evalMath(msg); Bukkit.getScheduler().runTaskLater(this, () -> refreshItemEditor(p), 1L); return; }
    }

    private void applySlotEdit(Player p, AdminState state, String type, String msg) { Matcher mx = Pattern.compile("([+-]?)(\\d+)").matcher(msg); if (!mx.matches()) { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f+\u6570\u5b57 \u6216 -\u6570\u5b57"); return; } String sign = mx.group(1); int val = Integer.parseInt(mx.group(2)); boolean add = sign.equals("+") || sign.isEmpty(); String tn = state.targetPlayer; ensureMember(tn); Map<String, Object> mm = getMember(tn); if ("perm".equals(type)) { int cur = ((Number) mm.get("perm")).intValue(); int nv = add ? cur + val : Math.max(0, cur - val); setSlot(tn, "perm", nv); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u6c38\u4e45\u683c\u5b50: " + cur + " -> " + nv); } else { int cur = ((Number) mm.get("mem")).intValue(); int nv = add ? cur + val : Math.max(0, cur - val); setSlot(tn, "mem", nv); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u4f1a\u5458\u683c\u5b50: " + cur + " -> " + nv); } final String ft = tn; Bukkit.getScheduler().runTaskLater(this, () -> openUserMgmt(p, ft), 1L); }

    private void applyExpEdit(Player p, AdminState state, String msg) { String tn = state.targetPlayer; long absTime = ShopItem.parseRelativeOrAbsolute(msg); if (absTime > 0) { ensureMember(tn); long cur = ((Number) getMember(tn).get("exp")).longValue(); setExpire(tn, absTime); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5230\u671f: " + fmtTime(cur) + " -> " + fmtTime(absTime)); } else { Matcher mx = Pattern.compile("([+-]?)(\\d+)").matcher(msg); if (!mx.matches()) { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u683c\u5f0f\u9519\u8bef"); return; } String sign = mx.group(1); int val = Integer.parseInt(mx.group(2)); boolean add = sign.equals("+") || sign.isEmpty(); ensureMember(tn); long cur = ((Number) getMember(tn).get("exp")).longValue(); long now = System.currentTimeMillis(); long base = (cur > now) ? cur : now; long nv = add ? base + (long) val * 86400000L : Math.max(now, base - (long) val * 86400000L); setExpire(tn, nv); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5230\u671f: " + fmtTime(cur) + " -> " + fmtTime(nv)); } final String ft = tn; Bukkit.getScheduler().runTaskLater(this, () -> openUserMgmt(p, ft), 1L); }

    // ===== SECTION: 命令 =====
    private static final String[] HELP_PLAYER = { "/cy - \u6253\u5f00\u4f1a\u5458\u4e2d\u5fc3" };
    private static final String[] HELP_ADMIN = { "/cy - \u6253\u5f00\u4f1a\u5458\u4e2d\u5fc3", "/cy reload - \u91cd\u8f7d\u914d\u7f6e", "/cy status - \u63d2\u4ef6\u72b6\u6001", "/cy info <\u73a9\u5bb6> - \u67e5\u770b\u73a9\u5bb6\u6570\u636e", "/cy add <\u5546\u54c1ID/\u540d> <\u6570\u91cf> - \u589e\u52a0\u5e93\u5b58", "/cy remove <\u5546\u54c1ID/\u540d> <\u6570\u91cf> - \u51cf\u5c11\u5e93\u5b58", "/cy set <\u5546\u54c1ID/\u540d> <\u503c> - \u8bbe\u7f6e\u5e93\u5b58(-1\u4e0b\u67b6)", "/cy shop - \u6253\u5f00\u5546\u57ce", "/cy update - \u68c0\u67e5\u66f4\u65b0" };

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { if (s instanceof Player) openMain((Player) s); else showHelp(s); return true; }
        String cmd = a[0].toLowerCase();
        switch (cmd) { case "reload": case "status": case "info": case "add": case "remove": case "set": case "update": if (!s.hasPermission("cy.admin")) { showHelp(s); return true; } break; case "shop": if (s instanceof Player) openShop((Player) s); else showHelp(s); return true; default: showHelp(s); return true; }
        switch (cmd) { case "reload": shopList.clear(); stockMap.clear(); localVer = ""; updateCh = ""; adminPass = "qweasd"; masterPluginName = ""; sharedSecret = ""; masterInstance = null; masterPingMethod = null; masterActMethod = null; masterVerifyMethod = null; loadShop(); s.sendMessage("\u00a7a[CY] \u00a7f\u91cd\u8f7d\u5b8c\u6210\uff0c\u5546\u54c1:" + shopList.size()); break; case "status": showStat(s); break; case "info": if (a.length < 2) { s.sendMessage("\u00a7e/cy info <\u73a9\u5bb6>"); break; } showInfo(s, a[1]); break; case "add": handleAdd(s, a); break; case "remove": handleRemove(s, a); break; case "set": handleSet(s, a); break; case "update": checkUpdate(s); break; }
        return true;
    }
    private void showHelp(CommandSender s) { String[] cmds = s.hasPermission("cy.admin") ? HELP_ADMIN : HELP_PLAYER; for (String line : cmds) s.sendMessage("\u00a7e" + line); }
    private void handleAdd(CommandSender s, String[] a) { if (a.length < 3) { s.sendMessage("\u00a7e/cy add <\u5546\u54c1ID/\u540d> <\u6570\u91cf>"); return; } ShopItem item = findItem(a[1]); if (item == null) { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u5546\u54c1"); return; } int amt; try { amt = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { s.sendMessage("\u00a7c\u8bf7\u8f93\u5165\u6570\u5b57"); return; } int cur = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock(); if (cur == -1) { s.sendMessage("\u00a7c\u8be5\u5546\u54c1\u5df2\u4e0b\u67b6"); return; } int nv = (cur == -2) ? amt : cur + amt; stockMap.put(item.getId(), nv); saveShopFile(); s.sendMessage("\u00a7a" + item.getName() + " \u5e93\u5b58: " + cur + " -> " + nv); }
    private void handleRemove(CommandSender s, String[] a) { if (a.length == 2) { if (delMember(a[1])) { s.sendMessage("\u00a7a\u5df2\u79fb\u9664\u73a9\u5bb6: " + a[1]); } else { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u73a9\u5bb6"); } } else if (a.length == 3) { ShopItem item = findItem(a[1]); if (item == null) { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u5546\u54c1"); return; } int amt; try { amt = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { s.sendMessage("\u00a7c\u8bf7\u8f93\u5165\u6570\u5b57"); return; } int cur = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock(); if (cur == -1) { s.sendMessage("\u00a7c\u8be5\u5546\u54c1\u5df2\u4e0b\u67b6"); return; } int nv = Math.max(0, cur - amt); stockMap.put(item.getId(), nv); saveShopFile(); s.sendMessage("\u00a7a" + item.getName() + " \u5e93\u5b58: " + cur + " -> " + nv); } else { s.sendMessage("\u00a7e/cy remove <\u73a9\u5bb6\u540d> \u6216 /cy remove <\u5546\u54c1ID/\u540d> <\u6570\u91cf>"); } }
    private void handleSet(CommandSender s, String[] a) { if (a.length < 3) { s.sendMessage("\u00a7e/cy set <\u5546\u54c1ID/\u540d> <\u503c>"); return; } ShopItem item = findItem(a[1]); if (item == null) { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u5546\u54c1"); return; } int nv; try { nv = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { s.sendMessage("\u00a7c\u8bf7\u8f93\u5165\u6570\u5b57"); return; } stockMap.put(item.getId(), nv); saveShopFile(); String label = nv == -1 ? "\u4e0b\u67b6" : (nv == 0 ? "\u552e\u7f44" : String.valueOf(nv)); s.sendMessage("\u00a7a" + item.getName() + " -> " + label); }

    // ===== SECTION: Tab补全 =====
    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) { if (!s.hasPermission("cy.admin")) return Collections.emptyList(); if (a.length == 1) return filterTab(Arrays.asList("reload", "status", "info", "add", "remove", "set", "shop", "update"), a[0]); if (a.length == 2) { String cmd = a[0].toLowerCase(); if (cmd.equals("info")) return filterTab(onlinePlayerNames(), a[1]); if (cmd.equals("add") || cmd.equals("remove") || cmd.equals("set")) { List<String> items = new ArrayList<>(); for (ShopItem it : shopList) { items.add(it.getId()); items.add(it.getName()); } return filterTab(items, a[1]); } } if (a.length == 3) { String cmd = a[0].toLowerCase(); if (cmd.equals("add") || cmd.equals("remove")) return filterTab(Arrays.asList("1", "5", "10", "50", "100"), a[2]); if (cmd.equals("set")) return filterTab(Arrays.asList("-1", "0", "10", "50", "100"), a[2]); } return Collections.emptyList(); }
    private List<String> onlinePlayerNames() { List<String> names = new ArrayList<>(); for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName()); return names; }

    // ===== SECTION: 版本更新 =====
    private void checkUpdate(final CommandSender manual) { new Thread(() -> { try { boolean preferGH = "GH".equalsIgnoreCase(updateCh) || updateCh.isEmpty(); String pApi = preferGH ? API_GH : API_GE; String pDl = preferGH ? DL_GH : DL_GE; String pName = preferGH ? "GitHub" : "Gitee"; String bApi = preferGH ? API_GE : API_GH; String bDl = preferGH ? DL_GE : DL_GH; String bName = preferGH ? "Gitee" : "GitHub"; String[] res = fetchRelease(pApi, pName); if (res != null) { applyUpdate(res[0], res[1], pDl, manual, pName); return; } getLogger().info("[\u66f4\u65b0] " + pName + " \u5931\u8d25\uff0c\u5207\u6362 " + bName); res = fetchRelease(bApi, bName); if (res != null) { applyUpdate(res[0], res[1], bDl, manual, bName); return; } getLogger().info("[\u66f4\u65b0] \u53cc\u8def\u5747\u5931\u8d25"); if (manual != null) manual.sendMessage("\u00a7c[\u66f4\u65b0] \u00a7f\u68c0\u67e5\u5931\u8d25"); } catch (Exception e) { getLogger().warning("[\u66f4\u65b0] " + e.getMessage()); } }).start(); }
    private String[] fetchRelease(String apiUrl, String ch) { try { TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return null; } public void checkClientTrusted(X509Certificate[] c, String a) {} public void checkServerTrusted(X509Certificate[] c, String a) {} }}; SSLContext sc = SSLContext.getInstance("TLS"); sc.init(null, trustAll, new java.security.SecureRandom()); HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory()); HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true); java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(apiUrl).openConnection(); c.setRequestMethod("GET"); c.setRequestProperty("User-Agent", "CY-Beibao/1.0"); c.setRequestProperty("Accept", "application/json"); c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true); if (c.getResponseCode() != 200) { getLogger().info("[\u66f4\u65b0] " + ch + " HTTP " + c.getResponseCode()); return null; } String json = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8); String rv = jParse(json, "tag_name"); String rn = jParse(json, "name"); if (rv == null || rv.isEmpty()) return null; return new String[]{ rv, rn != null ? rn : "" }; } catch (Exception e) { getLogger().info("[\u66f4\u65b0] " + ch + " " + e.getMessage()); return null; } }
    private void applyUpdate(String rv, String notes, String dl, CommandSender manual, String ch) { remoteVer = rv; if (!rv.equals(localVer)) { String msg = "\u00a7c\u00a7l[CY] \u00a7f\u65b0\u7248\u672c! v" + localVer + " -> v" + rv; getLogger().info("[CY] \u65b0\u7248\u672c v" + localVer + " -> " + rv + " \u901a\u9053:" + ch); if (manual != null) { manual.sendMessage(msg); manual.sendMessage("\u00a77\u4e0b\u8f7d: \u00a7e" + dl); manual.sendMessage("\u00a77\u901a\u9053: \u00a7e" + ch); } for (Player op : Bukkit.getOnlinePlayers()) { if (op.isOp()) { op.sendMessage(msg); op.sendMessage("\u00a77\u4e0b\u8f7d: \u00a7e" + dl); } } } else { getLogger().info("[CY] \u5df2\u662f\u6700\u65b0 v" + localVer); if (manual != null) manual.sendMessage("\u00a7a[CY] \u00a7f\u5df2\u662f\u6700\u65b0 v" + localVer); } }
    private static String jParse(String j, String k) { int i = j.indexOf("\"" + k + "\""); if (i < 0) return ""; int colon = j.indexOf(":", i); int start = j.indexOf("\"", colon + 1); if (start < 0) return ""; int end = j.indexOf("\"", start + 1); if (end < 0) return ""; return j.substring(start + 1, end); }

} // 类的最后一个 }
