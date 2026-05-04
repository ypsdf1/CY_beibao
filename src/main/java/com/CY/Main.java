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
        private String adminTeam = "";
        private String adminTag = "admin";
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

        // [FIX #2] 使用字段判断而非ID
        public boolean isBlindBox() {
            return blindBox;
        }

        public boolean hasPurchaseLimit() {
            return purchaseLimit > 0 && limitDuration > 0;
        }


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

        // [FIX #2] 盲盒范围 getter
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
    private String adminTeam = "";
    private String adminTag = "admin";
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
        int    tmpLimitCount = -1;
        long   tmpLimitDuration = 0;
        String tmpLimitDurationStr = "";
    }


// ===== SECTION: 范围解析工具 =====

    /** [FIX #2] 解析范围字符串，支持 "1-2"、"1天-2天"、"1-2天" 等写法 */
    private static int[] parseRange(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        // 按分隔符切分
        String[] parts = trimmed.split("[\\-~到,]");
        if (parts.length == 2) {
            int a = extractInt(parts[0]);
            int b = extractInt(parts[1]);
            if (a > 0 && b > 0) {
                return new int[]{Math.min(a, b), Math.max(a, b)};
            }
        }
        return null;
    }

    /** 从字符串中提取第一个整数，忽略中文单位等非数字字符 */
    private static int extractInt(String s) {
        if (s == null) return 0;
        String trimmed = s.trim();
        // 去掉所有非数字非负号字符，只保留数字
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
            PreparedStatement ps = db.prepareStatement("SELECT player_name, membership_slots, expire_time, plan_id FROM members WHERE auto_renew=1 AND membership_slots>0 AND expire_time>0");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                String name = rs.getString("player_name");
                long exp = rs.getLong("expire_time");
                String lastPid = rs.getString("plan_id");
                long now = System.currentTimeMillis();
                long diff = exp - now;
                if (diff > 0 && diff < 86400000L) {
                    if (lastPid == null || lastPid.isEmpty()) { setAutoRenew(name, false); continue; }
                    ShopItem matched = findItemById(lastPid);
                    if (matched == null) { setAutoRenew(name, false); Player online = Bukkit.getPlayer(name); if (online != null && online.isOnline()) { online.sendMessage("§c[CY] §f套餐已下架，自动续费已关闭"); } continue; }
                    if (matched.isBlindBox()) { setAutoRenew(name, false); Player online = Bukkit.getPlayer(name); if (online != null && online.isOnline()) { online.sendMessage("§c[CY] §f盲盒套餐不支持自动续费，已关闭"); } continue; }
                    double price = matched.getEffectivePrice();
                    int days = matched.getDays();
                    if (price <= 0 || days <= 0) { setAutoRenew(name, false); continue; }
                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                    if (economy.has(op, price)) {
                        economy.withdrawPlayer(op, price);
                        setExpire(name, exp + (long) days * 86400000L);
                        Player online = Bukkit.getPlayer(name);
                        if (online != null && online.isOnline()) { online.sendMessage("§a[CY] §f自动续费成功！延长" + days + "天，扣费$" + fmt(price)); }
                    } else {
                        setAutoRenew(name, false);
                        Player online = Bukkit.getPlayer(name);
                        if (online != null && online.isOnline()) { online.sendMessage("§c[CY] §f余额不足（需要$" + fmt(price) + "），自动续费已关闭"); }
                    }
                }
            }
            rs.close(); ps.close();
        } catch (Exception e) { getLogger().severe("[CY] autoRenew error: " + e.getMessage()); }
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
            st.execute("CREATE TABLE IF NOT EXISTS purchase_limits (player_name TEXT, item_id TEXT, count INTEGER DEFAULT 0, window_end INTEGER DEFAULT 0, PRIMARY KEY (player_name, item_id))");
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
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO members (player_name) VALUES (?)");
            ps.setString(1, name); ps.executeUpdate(); ps.close();
        } catch (Exception ignored) {}
    }


    private void upsert(String name, int permSlots, int memSlots, int days, String purchaseId) {
        if (db == null) return;
        try {
            PreparedStatement ins = db.prepareStatement("INSERT OR IGNORE INTO members (player_name) VALUES (?)");
            ins.setString(1, name); ins.executeUpdate(); ins.close();
            Map<String, Object> m = getMember(name);
            int curPerm = ((Number) m.get("perm")).intValue();
            int curMem = ((Number) m.get("mem")).intValue();
            long curExp = ((Number) m.get("exp")).longValue();
            long now = System.currentTimeMillis();
            int newPerm = curPerm + permSlots;
            int newMem = curMem + memSlots;
            long newExp = curExp;
            if (days > 0) {
                if (curExp == 0 && curMem == 0) { newExp = now + (long) days * 86400000L; }
                else if (curExp <= now && curExp != 0) { newExp = now + (long) days * 86400000L; }
                else if (curExp > now) { newExp = curExp + (long) days * 86400000L; }
                else { newExp = now + (long) days * 86400000L; }
            }
            String planId = (purchaseId != null && !purchaseId.isEmpty()) ? purchaseId : (String) m.getOrDefault("plan", "");
            PreparedStatement ps = db.prepareStatement("UPDATE members SET permanent_slots=?, membership_slots=?, expire_time=?, plan_id=? WHERE player_name=?");
            ps.setInt(1, newPerm); ps.setInt(2, newMem); ps.setLong(3, newExp); ps.setString(4, planId); ps.setString(5, name);
            ps.executeUpdate(); ps.close();
        } catch (Exception e) { getLogger().severe("[CY] upsert error: " + e.getMessage()); }
    }

    private void setExpire(String name, long expire) {
        if (db == null) return;
        try {
            PreparedStatement ps = db.prepareStatement("UPDATE members SET expire_time = ? WHERE player_name = ?");
            ps.setLong(1, expire); ps.setString(2, name); ps.executeUpdate(); ps.close();
        } catch (Exception e) { getLogger().severe("[CY] setExpire error: " + e.getMessage()); }
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
                PreparedStatement pl = db.prepareStatement("DELETE FROM purchase_limits WHERE player_name=?");
                pl.setString(1, name); pl.executeUpdate(); pl.close();
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
        // ===== SECTION: 限购方法 =====
        private int getPurchaseCount(String name, String itemId) {
            if (db == null) return 0;
            try {
                PreparedStatement ps = db.prepareStatement("SELECT count,window_end FROM purchase_limits WHERE player_name=? AND item_id=?");
                ps.setString(1, name); ps.setString(2, itemId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    int count = rs.getInt("count"); long windowEnd = rs.getLong("window_end");
                    rs.close(); ps.close();
                    if (System.currentTimeMillis() >= windowEnd) { resetLimitWindow(name, itemId); return 0; }
                    return count;
                }
                rs.close(); ps.close(); return 0;
            } catch (SQLException e) { return 0; }
        }
        private int[] getPurchaseInfo(String name, String itemId) {
            if (db == null) return new int[]{0, 0};
            try {
                PreparedStatement ps = db.prepareStatement("SELECT count,window_end FROM purchase_limits WHERE player_name=? AND item_id=?");
                ps.setString(1, name); ps.setString(2, itemId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    long windowEnd = rs.getLong("window_end"); int count = rs.getInt("count");
                    long now = System.currentTimeMillis(); rs.close(); ps.close();
                    if (now >= windowEnd) { resetLimitWindow(name, itemId); return new int[]{0, 0}; }
                    return new int[]{count, (int)(windowEnd - now)};
                }
                rs.close(); ps.close(); return new int[]{0, 0};
            } catch (SQLException e) { return new int[]{0, 0}; }
        }
        private void recordPurchase(String name, String itemId, long duration) {
            if (db == null) return;
            try {
                long now = System.currentTimeMillis(); long newEnd = now + duration;
                PreparedStatement ch = db.prepareStatement("SELECT count,window_end FROM purchase_limits WHERE player_name=? AND item_id=?");
                ch.setString(1, name); ch.setString(2, itemId);
                ResultSet rs = ch.executeQuery();
                if (rs.next()) {
                    long existEnd = rs.getLong("window_end"); int existCnt = rs.getInt("count");
                    rs.close(); ch.close();
                    if (now < existEnd) {
                        PreparedStatement u = db.prepareStatement("UPDATE purchase_limits SET count=? WHERE player_name=? AND item_id=?");
                        u.setInt(1, existCnt + 1); u.setString(2, name); u.setString(3, itemId); u.executeUpdate(); u.close();
                    } else {
                        PreparedStatement u = db.prepareStatement("UPDATE purchase_limits SET count=1,window_end=? WHERE player_name=? AND item_id=?");
                        u.setLong(1, newEnd); u.setString(2, name); u.setString(3, itemId); u.executeUpdate(); u.close();
                    }
                } else {
                    rs.close(); ch.close();
                    PreparedStatement ins = db.prepareStatement("INSERT INTO purchase_limits (player_name,item_id,count,window_end) VALUES (?,?,1,?)");
                    ins.setString(1, name); ins.setString(2, itemId); ins.setLong(3, newEnd); ins.executeUpdate(); ins.close();
                }
            } catch (SQLException e) { /* ignore */ }
        }
        private void resetLimitWindow(String name, String itemId) {
            if (db == null) return;
            try {
                PreparedStatement ps = db.prepareStatement("DELETE FROM purchase_limits WHERE player_name=? AND item_id=?");
                ps.setString(1, name); ps.setString(2, itemId); ps.executeUpdate(); ps.close();
            } catch (SQLException ignored) {}
        }
        private String fmtLimitTime(long ms) {
            if (ms <= 0) return "已结束";
            long totalMin = (ms + 59999) / 60000; long hr = totalMin / 60; long min = totalMin % 60;
            if (hr >= 24) { long day = hr / 24; hr = hr % 24; return day + "天" + hr + "小时"; }
            if (hr > 0) return hr + "小时" + min + "分钟";
            return totalMin + "分钟";
        }
        private static final String LIMIT_UNIT_RE = "(分钟|分|小时|时|天|周|月|m|h|d|w|W)";
        public static int[] parseLimitStrategy(String text) {
            if (text == null || text.trim().isEmpty()) return null;
            String t = text.trim();
            if (t.equals("-1") || t.equals("无") || t.equals("不限购")) return null;
            if (t.startsWith("每")) t = t.substring(1);
            Matcher m0 = Pattern.compile(LIMIT_UNIT_RE + "\\D*?(\\d+)\\s*次").matcher(t);
            if (m0.find()) return new int[]{(int) getLimitUnitMs(m0.group(1)), Integer.parseInt(m0.group(2))};
            Matcher m1 = Pattern.compile("(\\d+)\\s*" + LIMIT_UNIT_RE + "\\D*?(\\d+)\\s*次").matcher(t);
            if (m1.find()) return new int[]{(int)(Integer.parseInt(m1.group(1)) * getLimitUnitMs(m1.group(2))), Integer.parseInt(m1.group(3))};
            Matcher m2 = Pattern.compile("(\\d+)\\s*次\\D*?(\\d+)\\s*" + LIMIT_UNIT_RE).matcher(t);
            if (m2.find()) return new int[]{(int)(Integer.parseInt(m2.group(2)) * getLimitUnitMs(m2.group(3))), Integer.parseInt(m2.group(1))};
            Matcher m3 = Pattern.compile("(\\d+)\\s*次").matcher(t);
            if (m3.find()) return new int[]{60000, Integer.parseInt(m3.group(1))};
            return null;
        }
        private static long getLimitUnitMs(String unit) {
            if (unit.equals("分钟") || unit.equals("分") || unit.equals("m")) return 60000L;
            if (unit.equals("小时") || unit.equals("时") || unit.equals("h")) return 3600000L;
            if (unit.equals("天") || unit.equals("d")) return 86400000L;
            if (unit.equals("周") || unit.equals("w") || unit.equals("W")) return 604800000L;
            if (unit.equals("月")) return 2592000000L;
            return 60000L;
        }
        public static long parseChineseTime(String text) {
            if (text == null || text.trim().isEmpty()) return 0;
            String t = text.trim().replaceAll("\\s+", "");
            if (t.endsWith("分钟") || t.endsWith("分")) { String num = t.replaceAll("[^0-9]", ""); if (!num.isEmpty()) return Long.parseLong(num) * 60000L; return 0; }
            if (t.endsWith("小时") || t.endsWith("时")) { String num = t.replaceAll("[^0-9]", ""); if (!num.isEmpty()) return Long.parseLong(num) * 3600000L; return 0; }
            if (t.endsWith("天")) { String num = t.replaceAll("[^0-9]", ""); if (!num.isEmpty()) return Long.parseLong(num) * 86400000L; return 0; }
            if (t.endsWith("周")) { String num = t.replaceAll("[^0-9]", ""); if (!num.isEmpty()) return Long.parseLong(num) * 604800000L; return 0; }
            if (t.endsWith("月")) { String num = t.replaceAll("[^0-9]", ""); if (!num.isEmpty()) return Long.parseLong(num) * 2592000000L; return 0; }
            try { return Long.parseLong(t) * 60000L; } catch (NumberFormatException e) { return 0; }
        }
        public static String fmtChineseTime(long ms) {
            if (ms <= 0) return "";
            long totalMin = (ms + 59999) / 60000; long hr = totalMin / 60; long min = totalMin % 60;
            if (hr >= 24) { long day = hr / 24; hr = hr % 24; return day + "天" + hr + "小时"; }
            if (hr > 0) return hr + "小时" + min + "分钟";
            return totalMin + "分钟";
        }


        // ===== SECTION: 商品读取与保存 =====
    private void loadShop() {
        shopList.clear();
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        if (!f.exists()) { writeDefaultFile(); }
        try {
            List<String> rawLines = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line; while ((line = r.readLine()) != null) rawLines.add(line); r.close();
            boolean parsing = false;
            String cId = "", cName = "", cLogoItem = "", cDiscountStart = "", cDiscountEnd = "";
            int cDays = 0, cSlots = 0, cStock = -2; double cPrice = 0, cDiscount = 1.0;
            Material cIcon = Material.ENDER_CHEST;
            boolean cBlindBox = false; int cMinDays = 0, cMaxDays = 0, cMinSlots = 0, cMaxSlots = 0;
            int cLimitCount = -1; long cLimitDuration = 0; String cLimitDurationStr = "";

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

                    parsing = false; cId = ""; cName = ""; cLogoItem = ""; cDiscountStart = ""; cDiscountEnd = "";
                    cDays = 0; cSlots = 0; cPrice = 0; cStock = -2; cDiscount = 1.0; cIcon = Material.ENDER_CHEST;
                    cBlindBox = false; cMinDays = 0; cMaxDays = 0; cMinSlots = 0; cMaxSlots = 0;
                    cLimitCount = -1; cLimitDuration = 0; cLimitDurationStr = "";
                    continue;

                }
                String[] kv = trimmed.replace("\uff1a", ":").split(":", 2);
                if (kv.length < 2) continue;
                String k = kv[0].trim(); String v = kv[1].trim();
                if (k.equals("\u7248\u672c\u53f7")) { localVer = v; continue; }
                if (k.equals("\u66f4\u65b0\u901a\u9053")) { updateCh = v; continue; }
                if (k.equals("\u7ba1\u7406\u5bc6\u7801")) { adminPass = v; continue; }
                if (k.equals("\u4e3b\u63a7\u63d2\u4ef6")) { masterPluginName = v; continue; }
                if (k.equals("\u5171\u4eab\u5bc6\u94a5")) { sharedSecret = v; continue; }
                if (k.equals("\u7ba1\u7406\u56e2\u961f")) { adminTeam = v; continue; }
                if (k.equals("\u7ba1\u7406\u6807\u7b7e")) { adminTag = v; continue; }
                switch (k) {
                    case "ID": cId = v; parsing = true; break;
                    case "\u54c1\u540d": cName = v; break;
                    case "\u5929\u6570": { int[] range = parseRange(v); if (range != null) { cBlindBox = true; cMinDays = range[0]; cMaxDays = range[1]; cDays = range[1]; } else { cDays = parseIntSafe(v); cMinDays = cDays; cMaxDays = cDays; } break; }
                    case "\u683c\u5b50": case "\u683c\u53e3": { int[] range = parseRange(v); if (range != null) { cBlindBox = true; cMinSlots = range[0]; cMaxSlots = range[1]; cSlots = range[1]; } else { cSlots = parseIntSafe(v); cMinSlots = cSlots; cMaxSlots = cSlots; } break; }
                    case "\u4ef7\u683c": cPrice = parseDoubleSafe(v); break;
                    case "\u5e93\u5b58": cStock = parseIntSafe(v); break;
                    case "\u56fe\u6807": cIcon = parseMaterialSafe(v, Material.ENDER_CHEST); break;
                    case "\u56fe\u6807\u7269\u54c1": cLogoItem = v; break;
                    case "折扣结束": cDiscountEnd = v; break;
                    case "限购策略": { int[] parsed = parseLimitStrategy(v); if (parsed != null) { cLimitDuration = parsed[0]; cLimitCount = parsed[1]; cLimitDurationStr = v.trim(); } else { cLimitCount = -1; cLimitDuration = 0; cLimitDurationStr = ""; } break; }
                    case "限购次数": cLimitCount = parseIntSafe(v); break;
                    case "限购时间": cLimitDurationStr = v; cLimitDuration = parseChineseTime(v); break;

                }
            }
            if (parsing) { shopList.add(new ShopItem(cId, cName, cDays, cSlots, cPrice, cStock, cIcon, cLogoItem, cDiscount, parseTs(cDiscountStart), parseTs(cDiscountEnd), cBlindBox, cMinDays, cMaxDays, cMinSlots, cMaxSlots)); }
        } catch (Exception e) { getLogger().severe("[\u5546\u54c1] \u52a0\u8f7d\u5f02\u5e38: " + e.getMessage()); }
        stockMap.clear(); for (ShopItem it : shopList) { stockMap.put(it.getId(), it.getStock()); }
    }

    private long parseTs(String s) { if (s == null || s.trim().isEmpty()) return 0; return ShopItem.parseDate(s.trim()); }
    private void writeDefaultFile() {
        File f = new File(getDataFolder(), "\u5546\u54c1.txt");
        if (f.exists() && f.length() > 0) return;
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            pw.println("版本号: 1.0");
            pw.println("更新通道: GE");
            pw.println("管理密码: qweasd");
            pw.println("主控插件: ");
            pw.println("共享密钥: ");
            pw.println("管理团队: ");
            pw.println("管理标签: admin");
            pw.println();
            pw.println("ID: 100001");
            pw.println("品名: 基础格子");
            pw.println("天数: 7");
            pw.println("格子: 1");
            pw.println("价格: 100");
            pw.println("库存: 50");
            pw.println("图标: ENDER_CHEST");
            pw.println("图标物品: ");
            pw.println("折扣: 1.0");
            pw.println("折扣开始: ");
            pw.println("折扣结束: ");
            pw.println("限购策略: -1");
            pw.println("--");
            pw.println();
            pw.println("ID: 200001");
            pw.println("品名: 豪华盲盒");
            pw.println("天数: 7-30");
            pw.println("格子: 10-50");
            pw.println("价格: 500");
            pw.println("库存: -2");
            pw.println("图标: CHEST");
            pw.println("图标物品: ");
            pw.println("折扣: 1.0");
            pw.println("折扣开始: ");
            pw.println("折扣结束: ");
            pw.println("--");
        } catch (IOException ignored) {}
    }

        private void saveShopFile() {
            File f = new File(getDataFolder(), "商品.txt");
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
                pw.println("版本号: " + localVer);
                pw.println("更新通道: " + updateCh);
                pw.println("管理密码: " + adminPass);
                pw.println("主控插件: " + masterPluginName);
                pw.println("共享密钥: " + sharedSecret);
                pw.println("管理团队: " + adminTeam);
                pw.println("管理标签: " + adminTag);
                pw.println();
                for (ShopItem it : shopList) {
                    int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock();
                    pw.println("ID: " + it.getId());
                    pw.println("品名: " + it.getName());
                    if (it.isBlindBox()) { pw.println("天数: " + it.getMinDays() + "-" + it.getMaxDays()); pw.println("格子: " + it.getMinSlots() + "-" + it.getMaxSlots()); }
                    else { pw.println("天数: " + it.getDays()); pw.println("格子: " + it.getSlots()); }
                    pw.println("价格: " + (int) it.getPrice());
                    pw.println("库存: " + st);
                    pw.println("图标: " + it.getLogo().name());
                    pw.println("图标物品: " + (it.hasCustomIcon() ? it.getLogoItem() : ""));
                    pw.println("折扣: " + it.getDiscountRate());
                    pw.println("折扣开始: " + (it.getDiscountStart() > 0 ? fmtDate(it.getDiscountStart()) : ""));
                    pw.println("折扣结束: " + (it.getDiscountEnd() > 0 ? fmtDate(it.getDiscountEnd()) : ""));
                    if (it.getPurchaseLimit() > 0) {
                        pw.println("限购策略: " + it.getLimitDurationStr());
                    }

                    pw.println("--");
                }
        } catch (IOException e) { getLogger().severe("[\u5546\u54c1] \u5199\u5165\u5931\u8d25: " + e.getMessage()); }
    }

    private ShopItem findItemById(String id) { for (ShopItem it : shopList) { if (it.getId().equals(id)) return it; } return null; }
    private ShopItem findItem(String query) { if (query == null || query.isEmpty()) return null; for (ShopItem it : shopList) if (it.getId().equals(query)) return it; for (ShopItem it : shopList) if (it.getName().equals(query)) return it; for (ShopItem it : shopList) if (it.getId().contains(query)) return it; for (ShopItem it : shopList) if (it.getName().contains(query)) return it; return null; }

    // ===== SECTION: 仓库存储 =====
    private List<ItemStack> loadStorage(String name) { List<ItemStack> list = new ArrayList<>(); if (db == null) return list; try { PreparedStatement ps = db.prepareStatement("SELECT data FROM members WHERE player_name=?"); ps.setString(1, name); ResultSet rs = ps.executeQuery(); if (rs.next()) { String b64 = rs.getString("data"); if (b64 != null && !b64.isEmpty()) { Collections.addAll(list, decodeItems(b64)); } } rs.close(); ps.close(); } catch (Exception ignored) {} return list; }
    private void saveStorage(String name, List<ItemStack> list) { if (db == null) return; try { String b64 = encodeItems(list.toArray(new ItemStack[0])); PreparedStatement ps = db.prepareStatement("UPDATE members SET data=? WHERE player_name=?"); ps.setString(1, b64); ps.setString(2, name); ps.executeUpdate(); ps.close(); } catch (Exception ignored) {} }
    private int countStorageItems(String name) { List<ItemStack> list = loadStorage(name); int c = 0; for (ItemStack it : list) { if (it != null && it.getType() != Material.AIR && it.getType() != Material.GRAY_STAINED_GLASS_PANE) { c++; } } return c; }
    private static String encodeItems(ItemStack[] arr) throws IOException { ByteArrayOutputStream bos = new ByteArrayOutputStream(); BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos); oos.writeInt(arr.length); for (ItemStack it : arr) oos.writeObject(it); oos.close(); return Base64.getEncoder().encodeToString(bos.toByteArray()); }
    private static ItemStack[] decodeItems(String s) throws IOException, ClassNotFoundException { ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(s)); BukkitObjectInputStream ois = new BukkitObjectInputStream(bis); ItemStack[] arr = new ItemStack[ois.readInt()]; for (int i = 0; i < arr.length; i++) arr[i] = (ItemStack) ois.readObject(); ois.close(); return arr; }
    private boolean isShopIcon(ItemStack item) { if (item == null || item.getType() == Material.AIR) return false; ItemMeta meta = item.getItemMeta(); return meta != null && meta.getPersistentDataContainer().has(shopIconKey, PersistentDataType.STRING); }
    private String encodeSingleItem(ItemStack item) { if (item == null) return ""; try { ByteArrayOutputStream bos = new ByteArrayOutputStream(); BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos); oos.writeObject(item); oos.close(); return Base64.getEncoder().encodeToString(bos.toByteArray()); } catch (Exception e) { return ""; } }
    private ItemStack decodeSingleItem(String base64) { if (base64 == null || base64.isEmpty()) return null; try { ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(base64)); BukkitObjectInputStream ois = new BukkitObjectInputStream(bis); ItemStack item = (ItemStack) ois.readObject(); ois.close(); return item; } catch (Exception e) { return null; } }
    private ItemStack mkShopDisplay(ShopItem it, String displayName, String... extraLore) { if (it.hasCustomIcon()) { ItemStack custom = decodeSingleItem(it.getLogoItem()); if (custom != null) { ItemStack display = custom.clone(); ItemMeta meta = display.getItemMeta(); if (meta != null) { meta.setDisplayName(displayName); if (extraLore.length > 0) meta.setLore(Arrays.asList(extraLore)); display.setItemMeta(meta); } return display; } } return mkItem(it.getLogo(), displayName, extraLore); }

    // ===== SECTION: 工具方法 =====
    private boolean isAdmin(Player p) {
        if (adminTag != null && !adminTag.isEmpty() && p.getScoreboardTags().contains(adminTag)) return true;
        if (adminTeam != null && !adminTeam.isEmpty()) {
            try {
                org.bukkit.scoreboard.Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(adminTeam);
                if (team != null && team.hasEntry(p.getName())) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }


    private boolean isVerifiedAdmin(Player p) { Long t = verifiedAdmins.get(p.getUniqueId()); return t != null && (System.currentTimeMillis() - t) < ADMIN_VERIFY_MS; }
    private AdminState getOrCreateState(Player p) { return adminStates.computeIfAbsent(p.getUniqueId(), k -> new AdminState()); }
    private String colorize(String s) { if (s == null) return ""; return s.replaceAll("&([0-9a-fk-orA-FK-OR])", "\u00a7$1"); }
    private void fillBg(Inventory g) { ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, " "); for (int i = 0; i < g.getSize(); i++) g.setItem(i, gl); }
    private ItemStack mkItem(Material mat, String name, String... lore) { ItemStack it = new ItemStack(mat); ItemMeta im = it.getItemMeta(); if (im != null) { im.setDisplayName(name); if (lore.length > 0) im.setLore(Arrays.asList(lore)); it.setItemMeta(im); } return it; }
    private String fmt(double v) { return v == (long) v ? String.valueOf((long) v) : String.format("%.2f", v); }
    private String fmtTime(long expire) { if (expire == 0) return "\u7ec8\u8eab"; long now = System.currentTimeMillis(); if (expire <= now) return "\u5df2\u8fc7\u671f"; long r = expire - now; long d = r / 86400000L; long h = (r % 86400000L) / 3600000L; return d > 0 ? d + "\u5929" + h + "\u5c0f\u65f6" : h + "\u5c0f\u65f6"; }
    private String fmtDate(long ts) { if (ts <= 0) return ""; return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ts)); }
    private boolean isCancel(String msg) { String m = msg.toLowerCase().trim(); return m.equals("0") || m.equals("exit") || m.equals("\u9000\u51fa"); }
    private long parseTimeInput(String text) { return ShopItem.parseDate(text); }
    private long parseDateTime(String input) { return ShopItem.parseRelativeOrAbsolute(input); }
    private double evalMath(String input) { String expr = input.replaceAll("\\s+", "").trim(); try { return Double.parseDouble(expr); } catch (Exception ignored) {} try { List<Double> nums = new ArrayList<>(); List<Character> ops = new ArrayList<>(); StringBuilder buf = new StringBuilder(); for (int i = 0; i < expr.length(); i++) { char c = expr.charAt(i); if ((c == '+' || c == '-' || c == '*' || c == '/') && i > 0 && buf.length() > 0) { nums.add(Double.parseDouble(buf.toString())); ops.add(c); buf = new StringBuilder(); } else { buf.append(c); } } if (buf.length() > 0) nums.add(Double.parseDouble(buf.toString())); for (int i = 0; i < ops.size(); ) { if (ops.get(i) == '*' || ops.get(i) == '/') { double a = nums.get(i), b = nums.get(i + 1); nums.set(i, ops.get(i) == '*' ? a * b : (b == 0 ? 0 : a / b)); nums.remove(i + 1); ops.remove(i); } else { i++; } } double r = nums.get(0); for (int i = 0; i < ops.size(); i++) { r = ops.get(i) == '+' ? r + nums.get(i + 1) : r - nums.get(i + 1); } return r; } catch (Exception e) { return -1; } }
    private boolean isNumeric(String s) { try { Integer.parseInt(s); return true; } catch (Exception e) { return false; } }
    private List<String> filterTab(List<String> opts, String prefix) { List<String> r = new ArrayList<>(); for (String o : opts) { if (o.toLowerCase().startsWith(prefix.toLowerCase())) r.add(o); } return r; }
    private int parseIntSafe(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }
    private double parseDoubleSafe(String s) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; } }
    private Material parseMaterialSafe(String s, Material fallback) { try { return Material.valueOf(s.trim()); } catch (Exception e) { return fallback; } }
    private void showStat(CommandSender s) { int c = 0, t = 0; try { Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*),COALESCE(SUM(permanent_slots+membership_slots),0) FROM members"); if (rs.next()) { c = rs.getInt(1); t = rs.getInt(2); } rs.close(); st.close(); } catch (Exception ignored) {} s.sendMessage("\u00a7e[CY] \u00a7fv" + localVer + " \u6210\u5458:" + c + " \u683c\u5b50:" + t + " \u5546\u54c1:" + shopList.size()); s.sendMessage("\u00a7e[CY] \u00a7f\u4e3b\u63a7: " + (masterInstance != null ? "\u00a7a\u5df2\u8fde\u63a5" : "\u00a7c\u672a\u8fde\u63a5")); }
    private void showInfo(CommandSender s, String n) { Map<String, Object> m = getMember(n); if (m.isEmpty() || !(Boolean) m.get("_exists")) { s.sendMessage("\u00a7c" + n + " \u4e0d\u662f\u6210\u5458"); return; } s.sendMessage("\u00a7e" + n + ": \u6c38\u4e45=" + ((Number) m.get("perm")).intValue() + " \u4f1a\u5458=" + ((Number) m.get("mem")).intValue() + " \u8fc7\u671f=" + fmtTime(((Number) m.get("exp")).longValue())); }
    private int totalSlots(Map<String, Object> m) { if (m.isEmpty()) return 0; int p = ((Number) m.get("perm")).intValue(); int me = ((Number) m.get("mem")).intValue(); long e = ((Number) m.get("exp")).longValue(); return p + ((e == 0 || e > System.currentTimeMillis()) ? me : 0); }
    private int totalPages(Map<String, Object> m) { return Math.max(1, (totalSlots(m) + PAGE_SIZE - 1) / PAGE_SIZE); }
    private int usableOnPage(int pg, Map<String, Object> m) { int max = totalSlots(m); int totalNeeded = pg * PAGE_SIZE; if (totalNeeded <= max) return PAGE_SIZE; if (pg == 1) return max; int prevTotal = (pg - 1) * PAGE_SIZE; if (prevTotal >= max) return 0; return Math.min(max - prevTotal, PAGE_SIZE); }
    private int countItems(List<ItemStack> list, int pg) { int start = (pg - 1) * PAGE_SIZE; int count = 0; for (int i = 0; i < PAGE_SIZE; i++) { int idx = start + i; if (idx < list.size() && list.get(idx) != null && list.get(idx).getType() != Material.GRAY_STAINED_GLASS_PANE) { count++; } } return count; }
    private int unlockedPages(Map<String, Object> m) { return ((Number) m.getOrDefault("up", 1)).intValue(); }
    // ===== SECTION: 通用协议 =====
    public String onSdf1Ping() {
        return "CY_beibao v" + (localVer != null ? localVer : "1.0") + " members=" + memberCount() + " shop=" + shopList.size();
    }
    public boolean onSdf1Activation(String name, int slots, int days) {
        try { if (days > 0) upsert(name, 0, slots, days, ""); else upsert(name, slots, 0, 0, ""); return true; }
        catch (Exception e) { getLogger().severe("[联控] 激活失败: " + e.getMessage()); return false; }
    }
    public boolean onSdf1Verify(String secret) {
        if (sharedSecret == null || sharedSecret.isEmpty()) return true;
        return sharedSecret.equals(secret);
    }

    // ===== SECTION: 反射发现主控 =====
    private void discoverMaster() {
        if (masterPluginName == null || masterPluginName.isEmpty()) return;
        if (masterInstance != null) {
            try { if (masterPingMethod != null) { masterPingMethod.invoke(masterInstance); return; } }
            catch (Exception e) { masterInstance = null; masterPingMethod = null; masterActMethod = null; masterVerifyMethod = null; }
        }
        try {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(masterPluginName);
            if (plugin == null || !plugin.isEnabled()) { masterInstance = null; return; }
            Class<?> clazz = plugin.getClass();
            Method pingM = null, actM = null, verifyM = null;
            try { pingM = clazz.getMethod("onSdf1Ping"); } catch (NoSuchMethodException ignored) {}
            try { actM = clazz.getMethod("onSdf1Activation", String.class, int.class, int.class); } catch (NoSuchMethodException ignored) {}
            try { verifyM = clazz.getMethod("onSdf1Verify", String.class); } catch (NoSuchMethodException ignored) {}
            if (verifyM != null && sharedSecret != null && !sharedSecret.isEmpty()) {
                Object result = verifyM.invoke(plugin, sharedSecret);
                if (Boolean.FALSE.equals(result)) { getLogger().warning("[共享] 密钥验证失败！"); return; }
            }
            if (pingM != null) { String result = (String) pingM.invoke(plugin); getLogger().info("[共享] 主控发现成功: " + result); }
            masterInstance = plugin; masterPingMethod = pingM; masterActMethod = actM; masterVerifyMethod = verifyM;
        } catch (Exception e) { getLogger().warning("[共享] 主控发现异常: " + e.getMessage()); masterInstance = null; }
    }
    private boolean callMasterActivation(String name, int slots, int days) {
        if (masterInstance == null || masterActMethod == null) return false;
        try { return Boolean.TRUE.equals(masterActMethod.invoke(masterInstance, name, slots, days)); }
        catch (Exception e) { return false; }
    }

    // ===== SECTION: GUI - 主菜单 =====
    private void openMain(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, T_MAIN);
        fillBg(g);
        g.setItem(2, mkItem(Material.GREEN_WOOL, "\u00a7a\u00a7l我的"));
        g.setItem(4, mkItem(Material.EMERALD_BLOCK, "\u00a7b\u00a7l商城"));
        g.setItem(6, mkItem(Material.ENDER_CHEST, "\u00a7d\u00a7l主仓"));
        g.setItem(13, mkItem(Material.BARRIER, "\u00a7c\u00a7l关闭"));
        if (isAdmin(p)) { g.setItem(11, mkItem(Material.REDSTONE_BLOCK, "\u00a74\u00a7l管理面板")); }
        if (!isFreeClaimed(p.getName())) {
            g.setItem(15, mkItem(Material.ENDER_PEARL, "\u00a7a\u00a7l\u2605 免费领取空间 \u2605", "\u00a77领取" + FREE_SLOTS + "格永久空间", "", "\u00a7e\u00a7l点击领取"));
        } else {
            g.setItem(15, mkItem(Material.ENDER_PEARL, "\u00a77已领取", "\u00a77免费空间已领取"));
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
        int tot = totalSlots(m); int tp = totalPages(m); int up = unlockedPages(m);
        g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e" + p.getName()));
        g.setItem(10, mkItem(Material.CLOCK, "\u00a7a剩余时间", "\u00a77" + fmtTime(exp)));
        g.setItem(12, mkItem(Material.DIAMOND_BLOCK, "\u00a7b永久格子: \u00a7e" + perm));
        boolean active = (exp == 0 || exp > System.currentTimeMillis());
        g.setItem(14, mkItem(Material.EMERALD_BLOCK, "\u00a7a会员格子: \u00a7e" + mem, "\u00a77" + (active ? "\u00a7a有效" : "\u00a7c已过期")));
        g.setItem(16, mkItem(Material.CHEST, "\u00a7d总格子: \u00a7f" + tot, "\u00a77总页数: " + tp));
        boolean autoRenew = isAutoRenew(p.getName());
        Material torch = autoRenew ? Material.REDSTONE_TORCH : Material.TORCH;
        g.setItem(18, mkItem(torch, autoRenew ? "\u00a7a\u00a7l自动续费: 开启" : "\u00a7c\u00a7l自动续费: 关闭", "\u00a77当前: " + (autoRenew ? "\u00a7a开启" : "\u00a7c关闭"), "", "\u00a7e\u00a7l点击切换"));
        g.setItem(22, mkItem(Material.BOOK, "\u00a7e解锁页: \u00a7f" + Math.min(up, tp) + "/" + tp));
        g.setItem(26, mkItem(Material.ARROW, "\u00a77返回"));
        p.openInventory(g);
    }

    // ===== SECTION: GUI - 商城 =====
    private List<ShopItem> getVisibleShopItems() {
        List<ShopItem> visible = new ArrayList<>();
        for (ShopItem it : shopList) { int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock(); if (st != -1) visible.add(it); }
        return visible;
    }
    private void openShop(Player p) {
        List<ShopItem> visible = getVisibleShopItems();
        int sz = Math.max(9, ((visible.size() / 9) + 1) * 9); if (sz > 54) sz = 54;
        Inventory g = Bukkit.createInventory(null, sz, T_SHOP);
        for (int i = 0; i < visible.size() && i < 45; i++) {
            ShopItem it = visible.get(i); int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock();
            double ep = it.getEffectivePrice(); boolean discounted = it.isOnDiscount();
            List<String> lore = new ArrayList<>();
            if (ep == 0) { lore.add("\u00a77\u4ef7\u683c: \u00a7a\u514d\u8d39"); }
            else if (discounted) { lore.add("\u00a77\u539f\u4ef7: \u00a7m$" + fmt(it.getPrice())); lore.add("\u00a7e\u00a7l\u2731 \u6298\u6263\u4ef7: $" + fmt(ep) + " \u2731"); }
            else { lore.add("\u00a77\u4ef7\u683c: \u00a7a$" + fmt(ep)); }
            if (it.isBlindBox()) {
                lore.add("\u00a77\u683c\u5b50: \u00a7e??? \u683c");
                lore.add("\u00a77\u65f6\u95f4: \u00a7e??? \u5929");
                lore.add("");
                lore.add("\u00a78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
                lore.add("\u00a7d\u2727 \u795e\u79d8\u76f2\u76d2 \u2727");
                lore.add("\u00a77\u65af\u8fd0\u6d3b\u6c14\u5c31\u6765\uff01");
                lore.add("\u00a78\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
            }
            else { lore.add("\u00a77\u683c\u5b50: \u00a7e" + (it.isLifetime() ? "\u7ec8\u8eab" : "+" + it.getSlots())); lore.add("\u00a77\u65f6\u95f4: \u00a7e" + (it.isLifetime() ? "\u7ec8\u8eab" : it.getDays() + "\u5929")); }
            if (it.hasPurchaseLimit()) { int used = getPurchaseCount(p.getName(), it.getId()); int limit = it.getPurchaseLimit(); if (used >= limit) { int[] info = getPurchaseInfo(p.getName(), it.getId()); lore.add("§c已购" + used + "次，限购" + limit + "次（" + fmtLimitTime(info[1]) + "后重置）"); } else { lore.add("§7已购§e" + used + "§7次，限购§e" + limit + "§7次"); } }
            lore.add("§7库存: " + (st == 0 ? "§c售罄" : "§e" + st)); lore.add("");
            boolean limitReached = it.hasPurchaseLimit() && getPurchaseCount(p.getName(), it.getId()) >= it.getPurchaseLimit();
            lore.add(st == 0 ? "§c已售罄" : (limitReached ? "§c限购已达上限" : (it.isBlindBox() ? "§e§l点击开盲盒" : "§e点击购买")));

            g.setItem(i, mkShopDisplay(it, "\u00a7a" + it.getName(), lore.toArray(new String[0])));
        }
        g.setItem(sz - 1, mkItem(Material.ARROW, "\u00a77\u8fd4\u56de")); p.openInventory(g);
    }


    // ===== SECTION: GUI - 主仓 =====
    private void openStorage(Player p) {
        ensureMember(p.getName()); Map<String, Object> m = getMember(p.getName()); int tot = totalSlots(m);
        if (tot <= 0) { p.sendMessage("\u00a7c\u00a7l[仓库] \u00a7f没有存储空间！"); return; }
        List<ItemStack> list = loadStorage(p.getName()); while (list.size() < tot) list.add(null);
        cacheMap.put(p.getUniqueId(), list); pageMap.put(p.getUniqueId(), 1);
        p.openInventory(Bukkit.createInventory(null, INVENTORY_SIZE, T_STORE)); refreshPage(p);
    }
    private void refreshPage(Player p) {
        Inventory g = p.getOpenInventory().getTopInventory(); UUID u = p.getUniqueId();
        List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>()); int pg = pageMap.getOrDefault(u, 1); int start = (pg - 1) * PAGE_SIZE;
        Map<String, Object> m = getMember(p.getName()); int us = usableOnPage(pg, m); int tp = totalPages(m); int up = unlockedPages(m);
        ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, "\u00a77锁定");
        for (int i = 0; i < PAGE_SIZE; i++) { if (i < us) { int idx = start + i; g.setItem(i, (idx < list.size() && list.get(idx) != null) ? list.get(idx) : null); } else { g.setItem(i, gl); } }
        g.setItem(BTN_SLOT_PREV, pg > 1 ? mkItem(Material.ARROW, "\u00a7a上一页") : mkItem(Material.GRAY_STAINED_GLASS_PANE, " "));
        g.setItem(BTN_SLOT_INFO, mkItem(Material.PAPER, "\u00a7e第" + pg + "/" + tp + "页", "\u00a77物品: " + countItems(list, pg) + "/" + us));
        g.setItem(BTN_SLOT_BACK, mkItem(Material.BARRIER, "\u00a7c返回"));
        if (pg < tp) { boolean can = (pg < up) || (countItems(list, pg) >= (int)(us * UNLOCK_PERCENT)); g.setItem(BTN_SLOT_NEXT, can ? mkItem(Material.ARROW, "\u00a7a下一页") : mkItem(Material.RED_STAINED_GLASS_PANE, "\u00a7c下一页", "\u00a77需达" + (int)(us * UNLOCK_PERCENT) + "+格")); }
        else { g.setItem(BTN_SLOT_NEXT, mkItem(Material.GRAY_STAINED_GLASS_PANE, " ")); }
    }
    private boolean nextPage(Player p) {
        UUID u = p.getUniqueId(); int pg = pageMap.getOrDefault(u, 1); int nx = pg + 1;
        Map<String, Object> m = getMember(p.getName()); int tp = totalPages(m); int up = unlockedPages(m);
        if (nx > tp) { p.sendMessage("\u00a7c\u00a7l[仓库] \u00a7f已到最大页！"); return false; }
        if (nx <= up) { pageMap.put(u, nx); refreshPage(p); return true; }
        List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>()); int filled = countItems(list, pg); int us = usableOnPage(pg, m);
        if (filled >= us * UNLOCK_PERCENT) { up++; setUpPages(p.getName(), up); pageMap.put(u, nx); refreshPage(p); p.sendMessage("\u00a7a\u00a7l[仓库] \u00a7f已解锁第" + nx + "页！"); return true; }
        p.sendMessage("\u00a7c\u00a7l[仓库] \u00a7f存储量不足（" + filled + "/" + (int)(us * UNLOCK_PERCENT) + "）"); return false;
    }

    // ===== SECTION: GUI - 管理面板 =====
    private void openAdminMain(Player p) {
        if (!isVerifiedAdmin(p)) { startAdminAuth(p); return; }
        getOrCreateState(p).chatType = "";
        Inventory g = Bukkit.createInventory(null, 27, T_ADMIN_MAIN); fillBg(g);
        g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e管理面板"));
        g.setItem(11, mkItem(Material.ARMOR_STAND, "\u00a7a用户管理", "\u00a77管理玩家数据"));
        g.setItem(15, mkItem(Material.EMERALD, "\u00a76商品管理", "\u00a77编辑商城商品"));
        g.setItem(22, mkItem(Material.ARROW, "\u00a77返回"));
        p.openInventory(g);
    }
    private void startAdminAuth(Player p) {
        p.closeInventory(); getOrCreateState(p).chatType = "admin_pass";
        p.sendMessage("\u00a7e[管理] \u00a7f请输入管理密码（0/exit 取消）：");
        if (adminPass.equals("qweasd")) { p.sendMessage("\u00a7c\u00a7l[警告] \u00a7f使用默认密码！"); }
    }
    private void startUserMgmtAuth(Player p) {
        p.closeInventory(); getOrCreateState(p).chatType = "admin_player";
        p.sendMessage("\u00a7e[管理] \u00a7f请输入玩家ID（0/exit 取消）：");
    }
    private void openUserMgmt(Player p, String tn) {
        AdminState state = getOrCreateState(p); state.targetPlayer = tn;
        Inventory g = Bukkit.createInventory(null, 27, T_USER_MGMT); fillBg(g); ensureMember(tn); Map<String, Object> m = getMember(tn);
        g.setItem(4, mkItem(Material.NAME_TAG, "\u00a7e管理: " + tn));
        if (m.isEmpty() || !(Boolean) m.get("_exists")) { g.setItem(13, mkItem(Material.BEDROCK, "\u00a7c玩家不在数据库中")); }
        else {
            g.setItem(10, mkItem(Material.GREEN_WOOL, "\u00a7a永久格子: \u00a7e" + ((Number) m.get("perm")).intValue(), "\u00a77点击输入调整"));
            g.setItem(12, mkItem(Material.EMERALD_BLOCK, "\u00a7b会员格子: \u00a7e" + ((Number) m.get("mem")).intValue(), "\u00a77点击输入调整"));
            g.setItem(14, mkItem(Material.CLOCK, "\u00a7e会员时间", "\u00a77" + fmtTime(((Number) m.get("exp")).longValue()), "\u00a77点击输入调整"));
            g.setItem(16, mkItem(Material.BARRIER, "\u00a7c移除玩家", "\u00a77点击删除此玩家数据"));
        }
        g.setItem(22, mkItem(Material.ARROW, "\u00a77返回")); p.openInventory(g);
    }

    // ===== SECTION: GUI - 商品管理 =====
    private void openShopMgmt(Player p) {
        getOrCreateState(p).chatType = "";
        Inventory g = Bukkit.createInventory(null, 54, T_SHOP_MGMT); fillBg(g);
        for (int i = 0; i < shopList.size() && i < 9; i++) {
            ShopItem it = shopList.get(i); int st = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock();
            String stockText = st == -1 ? "\u00a77下架" : (st == 0 ? "\u00a7c售罄" : "\u00a7e" + st);
            g.setItem(18 + i, mkShopDisplay(it, "\u00a7e#" + it.getId() + " " + it.getName(), "\u00a77库存: " + stockText + "  格口: " + it.getSlots(), "\u00a77价格: \u00a7a$" + fmt(it.getEffectivePrice()), "", "\u00a77双击编辑"));
        }
        g.setItem(49, mkItem(Material.ARROW, "\u00a77返回"));
        g.setItem(53, mkItem(Material.EMERALD, "\u00a7a\u00a7l发布新商品", "\u00a77点击上架新商品"));
        p.openInventory(g);
    }

    // ===== SECTION: GUI - 商品编辑 =====
    // ===== SECTION: GUI - 商品编辑 =====
    private void openItemEditor(Player p, int itemIdx) {
        AdminState state = getOrCreateState(p); state.editItemIdx = itemIdx; ShopItem it = shopList.get(itemIdx);
        state.tmpName = it.getName(); state.tmpPrice = it.getPrice();
        state.tmpStock = stockMap.containsKey(it.getId()) ? stockMap.get(it.getId()) : it.getStock();
        state.tmpLogo = it.getLogo().name(); state.tmpLogoItem = it.getLogoItem();
        state.tmpId = it.getId(); state.tmpDays = it.getDays(); state.tmpSlots = it.getSlots();
        refreshItemEditor(p);
    }
    private void openNewItem(Player p) {
        AdminState state = getOrCreateState(p); state.editItemIdx = -1;
        state.tmpId = String.valueOf(1000 + rng.nextInt(9000)); state.tmpName = "\u65b0\u5546\u54c1";
        state.tmpPrice = 100; state.tmpStock = 0; state.tmpSlots = 1; state.tmpDays = 1;
        state.tmpLogo = "CHEST"; state.tmpLogoItem = "";
        refreshItemEditor(p);
    }
    private void refreshItemEditor(Player p) {
        AdminState state = adminStates.get(p.getUniqueId()); if (state == null) return;
        String title = (state.editItemIdx < 0) ? T_NEW_ITEM : T_ITEM_EDIT;
        Inventory g = Bukkit.createInventory(null, 54, title);
        for (int i = 0; i < LOGO_OPTIONS.length; i++) { Material m = LOGO_OPTIONS[i]; boolean sel = m.name().equals(state.tmpLogo) && state.tmpLogoItem.isEmpty(); g.setItem(i, mkItem(m, sel ? "\u00a7a>> " + m.name() + " <<" : "\u00a7e" + m.name())); }
        g.setItem(11, mkItem(Material.PAPER, "§e商品名称", "§7当前: " + state.tmpName, "", "§7双击编辑"));
        g.setItem(13, mkItem(Material.DIAMOND, "§e价格", "§7当前: §a$" + fmt(state.tmpPrice), "", "§7双击编辑"));
        g.setItem(15, mkItem(Material.CHEST, "§e库存/格口", "§7库存: §f" + state.tmpStock + "  格口: §f" + state.tmpSlots, "", "§7单击改库存  双击改格口"));
        if (!state.tmpLogoItem.isEmpty()) { ItemStack ci = decodeSingleItem(state.tmpLogoItem); if (ci != null) { ItemStack d = ci.clone(); ItemMeta meta = d.getItemMeta(); if (meta != null) { meta.setDisplayName("§e当前图标 (自定义)"); meta.setLore(Arrays.asList("§7类型: " + ci.getType().name(), "", "§7从背包拖入新物品可替换")); d.setItemMeta(meta); } g.setItem(17, d); } else { g.setItem(17, mkItem(Material.BARRIER, "§c图标数据损坏")); } }
        else { Material lm = parseMaterialSafe(state.tmpLogo, Material.CHEST); g.setItem(17, mkItem(lm, "§e当前图标", "§7" + state.tmpLogo, "", "§7点击上方替换或从背包拖入")); }
        g.setItem(21, mkItem(Material.BOOK, "§7内部ID (只读)", "§7" + state.tmpId, "§7ID不可修改"));
        g.setItem(23, mkItem(Material.EMERALD, "§a§l发布", "§7确认发布/保存"));
        g.setItem(25, mkItem(Material.FEATHER, "§c取消", "§7放弃编辑"));
        p.openInventory(g);
    }
    private void publishItem(Player p, AdminState state) {
        if (state.tmpName.isEmpty()) { p.sendMessage("§c[管理] §f商品名称不能为空"); return; }
        if (state.tmpPrice <= 0) { p.sendMessage("§c[管理] §f价格必须大于0"); return; }
        String li = state.tmpLogoItem;
        if (state.editItemIdx < 0) {
            ShopItem it = new ShopItem(state.tmpId, state.tmpName, state.tmpDays, state.tmpSlots, state.tmpPrice, state.tmpStock, parseMaterialSafe(state.tmpLogo, Material.CHEST), li, 1.0, 0, 0);
            shopList.add(it);

            p.sendMessage("§a[管理] §f已发布: " + it.getName() + " #" + it.getId());
        }
        saveShopFile(); openShopMgmt(p);
    }
    // ===== SECTION: 购买逻辑 =====
    private void buy(Player p, ShopItem item) {
        if (economy == null) { p.sendMessage("§c§l[商城] §f经济不可用！"); return; }
        if (item.isBlindBox()) { openBox(p, item); return; }
        int st = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock();
        if (!item.isAvailable() && st != 0) { p.sendMessage("§c§l[商城] §f已下架"); return; }
        if (st == 0) { p.sendMessage("§c§l[商城] §f已售罄！"); return; }

        // ===== 限购检查 =====
        if (item.hasPurchaseLimit()) {
            int used = getPurchaseCount(p.getName(), item.getId());
            if (used >= item.getPurchaseLimit()) {
                int[] info = getPurchaseInfo(p.getName(), item.getId());
                p.sendMessage("§c§l[商城] §f已达到限购上限！（" + fmtLimitTime(info[1]) + "后重置）");
                return;
            }
        }

        Long last = lastPurchase.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < PURCHASE_CD) { p.sendMessage("§e§l[商城] §f购买太快"); return; }
        double ep = item.getEffectivePrice();
        if (!economy.has(p, ep)) { double bal = economy.getBalance(p); p.sendMessage("§c§l[商城] §f余额不足！"); p.sendMessage("§7价格: §e$" + fmt(ep) + " §7余额: §a$" + fmt(bal) + " §7差: §c$" + fmt(ep - bal)); return; }
        economy.withdrawPlayer(p, ep); lastPurchase.put(p.getUniqueId(), System.currentTimeMillis());
        ensureMember(p.getName());
        if (item.isLifetime()) { upsert(p.getName(), item.getSlots(), 0, 0, item.getId()); } else { upsert(p.getName(), 0, item.getSlots(), item.getDays(), item.getId()); }

        // ===== 限购记录 =====
        if (item.hasPurchaseLimit()) {
            recordPurchase(p.getName(), item.getId(), item.getLimitDuration());
        }

        if (st > 0) { st--; stockMap.put(item.getId(), st); saveShopFile(); }
        String tl = item.isLifetime() ? "终身" : (item.getDays() + "天");
        p.sendMessage("§a§l[商城] §f" + item.getName() + " | " + tl + " | +" + item.getSlots() + "格 | §c-$" + fmt(ep) + " §7余额: §a$" + fmt(economy.getBalance(p)));
    }


    private void openBox(Player p, ShopItem item) {
        if (economy == null) { p.sendMessage("§c§l[商城] §f经济不可用！"); return; }
        int st = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock();
        if (st == -1 || st == 0) { p.sendMessage("§c§l[商城] §f" + (st == -1 ? "已下架" : "已售罄")); return; }
        Long last = lastPurchase.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < PURCHASE_CD) { p.sendMessage("§e§l[商城] §f购买太快"); return; }
        double ep = item.getEffectivePrice();
        if (!economy.has(p, ep)) { p.sendMessage("§c§l[商城] §f余额不足！"); return; }
        economy.withdrawPlayer(p, ep); lastPurchase.put(p.getUniqueId(), System.currentTimeMillis());
        int rd = item.getMinDays() + rng.nextInt(item.getMaxDays() - item.getMinDays() + 1);
        int rs = item.getMinSlots() + rng.nextInt(item.getMaxSlots() - item.getMinSlots() + 1);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1.0f, 1.0f);
        p.sendMessage("§6§l[盲盒] §e正在开启神秘盲盒...");
        final int fd = rd, fs = rs; final ShopItem bi = item; final int bs = st;
        Bukkit.getScheduler().runTaskLater(this, () -> { p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f); p.sendMessage("§6§l[盲盒] §7物品正在旋转..."); }, 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> { p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f); p.sendMessage("§6§l[盲盒] §7即将揭晓..."); }, 40L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
            if (bs > 0) { int cur = stockMap.containsKey(bi.getId()) ? stockMap.get(bi.getId()) : bi.getStock(); if (cur > 0) { stockMap.put(bi.getId(), cur - 1); saveShopFile(); } }
            upsert(p.getName(), 0, fs, fd, bi.getId());
            // ===== 限购记录 =====
            if (bi.hasPurchaseLimit()) {
                recordPurchase(p.getName(), bi.getId(), bi.getLimitDuration());
            }

            String resultDays = fd + "天"; String resultSlots = fs + "格";
            p.sendTitle("§6§l✦ 神秘盲盒 ✦", "§e" + resultDays + "会员 + " + resultSlots + "空间", 10, 60, 20);
            p.sendMessage("§6§l[盲盒] §e§l恭喜！获得 §f" + resultDays + "会员 + " + resultSlots + "空间");
        }, 60L);
    }



    private boolean isDoubleClick(Player p, int slot) {
        String key = p.getUniqueId() + ":" + slot;
        long now = System.currentTimeMillis();
        Long prev = lastClickMap.get(key);
        lastClickMap.put(key, now);
        return prev != null && (now - prev) < DBL_CLICK_MS;
    }

    // ===== SECTION: 事件处理 =====
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked(); String t = e.getView().getTitle(); int r = e.getRawSlot(); UUID u = p.getUniqueId();
        if (t.equals(T_MAIN)) { e.setCancelled(true); if (r == 2) openMy(p); else if (r == 4) openShop(p); else if (r == 6) openStorage(p); else if (r == 11 && isAdmin(p)) openAdminMain(p); else if (r == 13) p.closeInventory(); else if (r == 15) handleFreeClaim(p); return; }
        if (t.equals(T_MY)) { e.setCancelled(true); if (r == 26) openMain(p); else if (r == 18) { boolean c = isAutoRenew(p.getName()); setAutoRenew(p.getName(), !c); p.sendMessage(!c ? "§a[CY] §f自动续费已开启" : "§c[CY] §f自动续费已关闭"); openMy(p); } return; }
        if (t.equals(T_ADMIN_MAIN)) { e.setCancelled(true); if (r == 22) openMain(p); else if (r == 11) startUserMgmtAuth(p); else if (r == 15) openShopMgmt(p); return; }

        if (t.equals(T_USER_MGMT)) { e.setCancelled(true); handleUserMgmtClick(p, r); return; }
        if (t.equals(T_SHOP_MGMT)) { e.setCancelled(true); if (r == 49) openAdminMain(p); else if (r == 53) openNewItem(p); else if (r >= 18 && r <= 26) { int idx = r - 18; if (idx < shopList.size() && isDoubleClick(p, r)) openItemEditor(p, idx); } return; }
        if (t.equals(T_ITEM_EDIT) || t.equals(T_NEW_ITEM)) { e.setCancelled(true); handleItemEditorClick(p, r, e); return; }
        if (t.equals(T_SHOP)) { e.setCancelled(true); int sz = e.getInventory().getSize(); if (r == sz - 1) { openMain(p); return; } List<ShopItem> vis = getVisibleShopItems(); if (r >= 0 && r < vis.size() && r < 45) buy(p, vis.get(r)); return; }
        if (t.equals(T_STORE)) { handleStorageClick(p, r, e); return; }
    }

    private void handleFreeClaim(Player p) {
        if (isFreeClaimed(p.getName())) { p.sendMessage("§c§l[CY] §f已领取过免费空间！"); return; }
        ensureMember(p.getName()); upsert(p.getName(), FREE_SLOTS, 0, 0, ""); setFreeClaimed(p.getName());
        p.sendMessage("§a§l[CY] §f已领取" + FREE_SLOTS + "格免费空间！");
        openMain(p);
    }

    // ===== SECTION: 用户管理点击 =====
    private void handleUserMgmtClick(Player p, int r) {
        AdminState state = adminStates.get(p.getUniqueId()); if (state == null) return;
        String tn = state.targetPlayer; if (tn.isEmpty()) return;
        if (r == 22) { openAdminMain(p); }
        else if (r == 10) { state.chatType = "edit_perm"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u6c38\u4e45\u683c\u5b50\u8c03\u6574\u91cf (+/-)\uff1a"); }
        else if (r == 12) { state.chatType = "edit_mem"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u4f1a\u5458\u683c\u5b50\u8c03\u6574\u91cf (+/-)\uff1a"); }
        else if (r == 14) { state.chatType = "edit_exp"; p.closeInventory(); p.sendMessage("\u00a7e[\u7ba1\u7406] \u00a7f\u4f1a\u5458\u65f6\u95f4: +7 / -3 / 2026-12-31"); }
        else if (r == 16) { delMember(tn); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5df2\u5220\u9664: " + tn); state.targetPlayer = ""; openAdminMain(p); }
    }

    // ===== SECTION: 商品编辑点击 =====
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

    // ===== SECTION: 主仓点击 =====
    private void handleStorageClick(Player p, int r, InventoryClickEvent e) {
        UUID u = p.getUniqueId();
        int topSize = e.getView().getTopInventory().getSize();

        // ===== 点击玩家背包区域 =====
        if (r >= topSize) {
            if (e.isShiftClick()) {
                e.setCancelled(true);
                // Shift从背包 → 仓库：找当前页第一个空位放入
                ItemStack clicked = e.getCurrentItem();
                if (clicked == null || clicked.getType() == Material.AIR
                        || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

                Map<String, Object> m = getMember(p.getName());
                int pg = pageMap.getOrDefault(u, 1);
                int us = usableOnPage(pg, m);
                int start = (pg - 1) * PAGE_SIZE;
                List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>());

                int emptySlot = -1;
                for (int i = 0; i < us; i++) {
                    int ci = start + i;
                    while (list.size() <= ci) list.add(null);
                    if (list.get(ci) == null
                            || list.get(ci).getType() == Material.AIR) {
                        emptySlot = i;
                        break;
                    }
                }
                if (emptySlot < 0) {
                    p.sendMessage("§c§l[仓库] §f当前页已满！");
                    return;
                }
                list.set(start + emptySlot, clicked.clone());
                e.getView().getTopInventory().setItem(emptySlot, clicked.clone());

                // 从背包移除
                int bottomSlot = r - topSize;
                org.bukkit.inventory.Inventory clickedInv = e.getClickedInventory();
                if (clickedInv != null && bottomSlot >= 0
                        && bottomSlot < clickedInv.getSize()) {
                    clickedInv.setItem(bottomSlot, null);
                }
                cacheMap.put(u, list);
                saveStorage(p.getName(), list);
            }
            // 非Shift点击背包：放行，不做任何处理
            return;
        }

        if (r < 0) { e.setCancelled(true); return; }

        // ===== 按钮区 48-53 =====
        if (r >= 48) {
            e.setCancelled(true);
            if (r == BTN_SLOT_BACK) openMain(p);
            else if (r == BTN_SLOT_PREV) {
                int pg = pageMap.getOrDefault(u, 1);
                if (pg > 1) { pageMap.put(u, pg - 1); refreshPage(p); }
            } else if (r == BTN_SLOT_NEXT) nextPage(p);
            return;
        }

        // ===== 仓库格子区 =====
        Map<String, Object> m = getMember(p.getName());
        int pg = pageMap.getOrDefault(u, 1);
        int us = usableOnPage(pg, m);
        if (r >= us) { e.setCancelled(true); return; }

        int idx = (pg - 1) * PAGE_SIZE + r;
        List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>());
        while (list.size() <= idx) list.add(null);

        ItemStack slotItem = list.get(idx);
        ItemStack handItem = e.getCursor();
        boolean slotEmpty = (slotItem == null
                || slotItem.getType() == Material.AIR);
        boolean handEmpty = (handItem == null
                || handItem.getType() == Material.AIR);
        boolean slotLocked = (!slotEmpty
                && slotItem.getType() == Material.GRAY_STAINED_GLASS_PANE);
        if (slotLocked) { e.setCancelled(true); return; }

        e.setCancelled(true);

        org.bukkit.event.inventory.ClickType click = e.getClick();

        // ----- Shift点击：仓库 ↔ 背包 -----
        if (click == org.bukkit.event.inventory.ClickType.SHIFT_LEFT
                || click == org.bukkit.event.inventory.ClickType.SHIFT_RIGHT) {
            if (!slotEmpty) {
                ItemStack give = slotItem.clone();
                HashMap<Integer, ItemStack> overflow =
                        p.getInventory().addItem(give);
                if (overflow.isEmpty()) {
                    list.set(idx, null);
                    e.getView().getTopInventory().setItem(r, null);
                } else {
                    p.sendMessage("§c§l[仓库] §f背包已满！");
                }
            }
            cacheMap.put(u, list);
            saveStorage(p.getName(), list);
            return;
        }

        // ----- 右键：放1个 / 拾一半 -----
        if (click == org.bukkit.event.inventory.ClickType.RIGHT) {
            if (slotEmpty && !handEmpty) {
                // 放1个
                ItemStack placed = handItem.clone();
                placed.setAmount(1);
                list.set(idx, placed);
                e.getView().getTopInventory().setItem(r, placed);
                ItemStack nc = handItem.clone();
                nc.setAmount(nc.getAmount() - 1);
                e.setCursor(nc.getAmount() > 0 ? nc : null);
            } else if (!slotEmpty && handEmpty) {
                // 拾一半
                int half = slotItem.getAmount() / 2;
                if (half <= 0) half = 1;
                ItemStack picked = slotItem.clone();
                picked.setAmount(half);
                e.setCursor(picked);
                int remain = slotItem.getAmount() - half;
                if (remain <= 0) {
                    list.set(idx, null);
                    e.getView().getTopInventory().setItem(r, null);
                } else {
                    ItemStack rem = slotItem.clone();
                    rem.setAmount(remain);
                    list.set(idx, rem);
                    e.getView().getTopInventory().setItem(r, rem);
                }
            } else if (!slotEmpty && !handEmpty) {
                if (slotItem.isSimilar(handItem)
                        && slotItem.getAmount() < slotItem.getMaxStackSize()) {
                    // 同类型：往格子里塞1个
                    ItemStack updated = slotItem.clone();
                    updated.setAmount(slotItem.getAmount() + 1);
                    list.set(idx, updated);
                    e.getView().getTopInventory().setItem(r, updated);
                    int remain = handItem.getAmount() - 1;
                    if (remain > 0) {
                        ItemStack nc = handItem.clone();
                        nc.setAmount(remain);
                        e.setCursor(nc);
                    } else {
                        e.setCursor(null);
                    }
                } else {
                    // 不同类型：交换
                    ItemStack give = slotItem.clone();
                    list.set(idx, handItem.clone());
                    e.getView().getTopInventory().setItem(r, handItem.clone());
                    e.setCursor(null);
                    HashMap<Integer, ItemStack> overflow =
                            p.getInventory().addItem(give);
                    if (!overflow.isEmpty()) {
                        list.set(idx, slotItem.clone());
                        e.getView().getTopInventory().setItem(r, slotItem.clone());
                        e.setCursor(handItem.clone());
                        p.sendMessage("§c§l[仓库] §f背包已满，无法交换！");
                    }
                }
            }
            cacheMap.put(u, list);
            saveStorage(p.getName(), list);
            return;
        }

        // ----- 左键（默认）：整组交换 -----
        if (slotEmpty && !handEmpty) {
            list.set(idx, handItem.clone());
            e.getView().getTopInventory().setItem(r, handItem.clone());
            e.setCursor(null);
        } else if (!slotEmpty && handEmpty) {
            ItemStack give = slotItem.clone();
            list.set(idx, null);
            e.getView().getTopInventory().setItem(r, null);
            HashMap<Integer, ItemStack> overflow =
                    p.getInventory().addItem(give);
            if (!overflow.isEmpty()) {
                list.set(idx, slotItem.clone());
                e.getView().getTopInventory().setItem(r, slotItem.clone());
                p.sendMessage("§c§l[仓库] §f背包已满！");
            }
        } else if (!slotEmpty && !handEmpty) {
            ItemStack give = slotItem.clone();
            list.set(idx, handItem.clone());
            e.getView().getTopInventory().setItem(r, handItem.clone());
            e.setCursor(null);
            HashMap<Integer, ItemStack> overflow =
                    p.getInventory().addItem(give);
            if (!overflow.isEmpty()) {
                list.set(idx, slotItem.clone());
                e.getView().getTopInventory().setItem(r, slotItem.clone());
                e.setCursor(handItem.clone());
                p.sendMessage("§c§l[仓库] §f背包已满，无法交换！");
            }
        }
        cacheMap.put(u, list);
        saveStorage(p.getName(), list);
    }


    // ===== SECTION: 其他事件 =====
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        if (e.getView().getTitle().equals(T_STORE)) {
            UUID u = p.getUniqueId();
            int pg = pageMap.getOrDefault(u, 1);
            Inventory top = e.getInventory();
            List<ItemStack> cl = cacheMap.getOrDefault(u, new ArrayList<>());
            Map<String, Object> m = getMember(p.getName());
            int us = usableOnPage(pg, m);
            int start = (pg - 1) * PAGE_SIZE;
            for (int i = 0; i < PAGE_SIZE; i++) {
                int ci = start + i;
                while (cl.size() <= ci) cl.add(null);
                if (i < us) cl.set(ci, top.getItem(i));
            }
            cacheMap.put(u, cl);
            pageMap.remove(u);
            saveStorage(p.getName(), cl);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();

        // ===== 关键修复：非仓库界面直接放行，不干涉 =====
        if (!e.getView().getTitle().equals(T_STORE)) return;

        // ===== 以下仅处理仓库界面的拖拽 =====
        UUID u = p.getUniqueId();
        int topSize = e.getInventory().getSize();
        int pg = pageMap.getOrDefault(u, 1);
        Map<String, Object> m = getMember(p.getName());
        int us = usableOnPage(pg, m);

        for (int slot : e.getRawSlots()) {
            if (slot < topSize) {
                // 超出可用格数 → 禁止
                if (slot >= us) {
                    e.setCancelled(true);
                    return;
                }
                // 锁定格（灰色玻璃）→ 禁止
                int idx = (pg - 1) * PAGE_SIZE + slot;
                List<ItemStack> list = cacheMap.getOrDefault(u, new ArrayList<>());
                while (list.size() <= idx) list.add(null);
                ItemStack ex = list.get(idx);
                if (ex != null && ex.getType() == Material.GRAY_STAINED_GLASS_PANE) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // 允许拖拽，1 tick后同步数据
        final int fpg = pg;
        final int fus = us;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || !p.getOpenInventory().getTitle().equals(T_STORE)) return;
            Inventory top = p.getOpenInventory().getTopInventory();
            List<ItemStack> cl = cacheMap.getOrDefault(u, new ArrayList<>());
            for (int i = 0; i < PAGE_SIZE; i++) {
                int ci = (fpg - 1) * PAGE_SIZE + i;
                while (cl.size() <= ci) cl.add(null);
                if (i < fus) cl.set(ci, top.getItem(i));
            }
            cacheMap.put(u, cl);
            saveStorage(p.getName(), cl);
        }, 1L);
    }

    private void syncCurrentPage(Player p) {
        UUID u = p.getUniqueId();
        if (!p.isOnline()) return;
        String title = p.getOpenInventory().getTitle();
        int pg = pageMap.getOrDefault(u, 1);
        Inventory top;
        if (title.equals(T_STORE)) { top = p.getOpenInventory().getTopInventory(); }
        else { return; }
        List<ItemStack> cl = cacheMap.getOrDefault(u, new ArrayList<>());
        Map<String, Object> m = getMember(p.getName());
        int us = usableOnPage(pg, m);
        int start = (pg - 1) * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE; i++) {
            int ci = start + i;
            while (cl.size() <= ci) cl.add(null);
            if (i < us) cl.set(ci, top.getItem(i));
        }
        cacheMap.put(u, cl);
        saveStorage(p.getName(), cl);
    }

    private void syncStorageFromInventory(Player p) {
        UUID u = p.getUniqueId(); if (!p.getOpenInventory().getTitle().equals(T_STORE)) return;
        List<ItemStack> list = cacheMap.get(u); if (list == null) return;
        Inventory top = p.getOpenInventory().getTopInventory(); int pg = pageMap.getOrDefault(u, 1); int start = (pg - 1) * PAGE_SIZE;
        Map<String, Object> m = getMember(p.getName()); int us = usableOnPage(pg, m);
        for (int i = 0; i < us && i < PAGE_SIZE; i++) { int idx = start + i; while (list.size() <= idx) list.add(null); ItemStack si = top.getItem(i); if (si != null && si.getType() == Material.GRAY_STAINED_GLASS_PANE) continue; list.set(idx, si); }
    }

    //  @EventHandler
    private int autoRenewTaskId = -1;
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (autoRenewTaskId == -1) {
            autoRenewTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> checkAutoRenewal(), 1200L, 72000L).getTaskId();
        }
        Bukkit.getScheduler().runTaskLater(this, () -> checkAutoRenewal(), 100L);
        if (masterInstance == null && masterPluginName != null && !masterPluginName.isEmpty()) { Bukkit.getScheduler().runTaskLater(this, () -> discoverMaster(), 100L); }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID u = event.getPlayer().getUniqueId(); adminStates.remove(u); verifiedAdmins.remove(u); lastReminderLevel.remove(event.getPlayer().getName());
        List<String> keys = new ArrayList<>(); for (String k : lastClickMap.keySet()) { if (k.startsWith(u.toString())) keys.add(k); } for (String k : keys) lastClickMap.remove(k);
    }

    // ===== SECTION: 聊天输入 =====
    @EventHandler
    public void onPlayerChatInput(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player p = event.getPlayer(); UUID u = p.getUniqueId(); AdminState state = adminStates.get(u);
        if (state == null || state.chatType.isEmpty()) return; event.setCancelled(true); String msg = event.getMessage().trim();
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
    private void applySlotEdit(Player p, AdminState state, String type, String msg) {
        Matcher mx = Pattern.compile("([+-]?)(\\d+)").matcher(msg);
        if (!mx.matches()) { p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f+\u6570\u5b57 \u6216 -\u6570\u5b57"); return; }
        String sign = mx.group(1); int val = Integer.parseInt(mx.group(2)); boolean add = sign.equals("+") || sign.isEmpty();
        String tn = state.targetPlayer;
        Bukkit.getScheduler().runTask(this, () -> {
            checkDB(); ensureMember(tn); Map<String, Object> mm = getMember(tn);
            if ("perm".equals(type)) { int cur = ((Number) mm.get("perm")).intValue(); int nv = add ? cur + val : Math.max(0, cur - val); setSlot(tn, "perm", nv); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u6c38\u4e45\u683c\u5b50: " + cur + " -> " + nv); }
            else { int cur = ((Number) mm.get("mem")).intValue(); int nv = add ? cur + val : Math.max(0, cur - val); setSlot(tn, "mem", nv); p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u4f1a\u5458\u683c\u5b50: " + cur + " -> " + nv); }
            openUserMgmt(p, tn);
        });
    }

    private void applyExpEdit(Player p, AdminState state, String msg) {
        String tn = state.targetPlayer;
        Bukkit.getScheduler().runTask(this, () -> {
            checkDB();
            long now = System.currentTimeMillis();
            ensureMember(tn);
            long cur = ((Number) getMember(tn).get("exp")).longValue();
            long base = (cur > now) ? cur : now;
            long nv = -1;
            String s = msg.trim();
            long absTime = ShopItem.parseRelativeOrAbsolute(s);
            if (absTime > 0) { nv = absTime; }
            else if (s.startsWith("+") || s.startsWith("-")) {
                boolean add = s.startsWith("+"); String numPart = ""; String unitPart = "";
                for (int i = 1; i < s.length(); i++) { char c = s.charAt(i); if (Character.isDigit(c)) numPart += c; else { unitPart = s.substring(i); break; } }
                if (!numPart.isEmpty()) {
                    int n = Integer.parseInt(numPart); long ms = 0;
                    if (unitPart.equals("d") || unitPart.equals("\u5929")) ms = n * 86400000L;
                    else if (unitPart.equals("h") || unitPart.equals("\u5c0f\u65f6")) ms = n * 3600000L;
                    else if (unitPart.equals("m") || unitPart.equals("\u5206\u949f")) ms = n * 60000L;
                    else ms = n * 86400000L;
                    if (ms > 0) { nv = add ? base + ms : Math.max(now, base - ms); }
                }
            }
            if (nv > 0) {
                setExpire(tn, nv);
                long verify = ((Number) getMember(tn).get("exp")).longValue();
                if (verify == nv) {
                    p.sendMessage("\u00a7a[\u7ba1\u7406] \u00a7f\u5230\u671f: " + fmtTime(cur) + " -> " + fmtTime(nv) + " \u00a7a\u2714");
                } else {
                    p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u5199\u5165\u5931\u8d25\uff01\u9884\u671f:" + nv + " \u5b9e\u9645:" + verify);
                }
            } else {
                p.sendMessage("\u00a7c[\u7ba1\u7406] \u00a7f\u683c\u5f0f\u9519\u8bef\uff0c\u652f\u6301: +7d -3d +1h -1h +30m -30m \u6216 2026-12-31");
            }
            openUserMgmt(p, tn);
        });
    }



    // ===== SECTION: 命令 =====
    private static final String[] HELP_PLAYER = { "/cy - \u6253\u5f00\u4f1a\u5458\u4e2d\u5fc3" };
    private static final String[] HELP_ADMIN = { "/cy - \u6253\u5f00\u4f1a\u5458\u4e2d\u5fc3", "/cy reload - \u91cd\u8f7d\u914d\u7f6e", "/cy status - \u63d2\u4ef6\u72b6\u6001", "/cy info <\u73a9\u5bb6> - \u67e5\u770b\u73a9\u5bb6\u6570\u636e", "/cy add <\u5546\u54c1ID/\u540d> <\u6570\u91cf> - \u589e\u52a0\u5e93\u5b58", "/cy remove <\u5546\u54c1ID/\u540d> <\u6570\u91cf> - \u51cf\u5c11\u5e93\u5b58", "/cy set <\u5546\u54c1ID/\u540d> <\u503c> - \u8bbe\u7f6e\u5e93\u5b58(-1\u4e0b\u67b6)", "/cy shop - \u6253\u5f00\u5546\u57ce", "/cy update - \u68c0\u67e5\u66f4\u65b0" };

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { if (s instanceof Player) openMain((Player) s); else showHelp(s); return true; }
        String cmd = a[0].toLowerCase();
        switch (cmd) { case "reload": case "status": case "info": case "add": case "remove": case "set": case "update": if (!s.hasPermission("cy.admin")) { showHelp(s); return true; } break; case "shop": if (s instanceof Player) openShop((Player) s); else showHelp(s); return true; default: showHelp(s); return true; }
        switch (cmd) {
            case "reload": shopList.clear(); stockMap.clear(); localVer = ""; updateCh = ""; adminPass = "qweasd"; masterPluginName = ""; sharedSecret = ""; masterInstance = null; masterPingMethod = null; masterActMethod = null; masterVerifyMethod = null; loadShop(); s.sendMessage("\u00a7a[CY] \u00a7f\u91cd\u8f7d\u5b8c\u6210\uff0c\u5546\u54c1:" + shopList.size()); break;
            case "status": showStat(s); break;
            case "info": if (a.length < 2) { s.sendMessage("\u00a7e/cy info <\u73a9\u5bb6>"); break; } showInfo(s, a[1]); break;
            case "add": handleAdd(s, a); break;
            case "remove": handleRemove(s, a); break;
            case "set": handleSet(s, a); break;
            case "update": checkUpdate(s); break;
        }
        return true;
    }

    private void showHelp(CommandSender s) { String[] cmds = s.hasPermission("cy.admin") ? HELP_ADMIN : HELP_PLAYER; for (String line : cmds) s.sendMessage("\u00a7e" + line); }

    private void handleAdd(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage("\u00a7e/cy add <\u5546\u54c1ID/\u540d> <\u6570\u91cf>"); return; }
        ShopItem item = findItem(a[1]); if (item == null) { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u5546\u54c1"); return; }
        int amt; try { amt = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { s.sendMessage("\u00a7c\u8bf7\u8f93\u5165\u6570\u5b57"); return; }
        int cur = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock();
        if (cur == -1) { s.sendMessage("\u00a7c\u8be5\u5546\u54c1\u5df2\u4e0b\u67b6"); return; }
        int nv = (cur == -2) ? amt : cur + amt; stockMap.put(item.getId(), nv); saveShopFile();
        s.sendMessage("\u00a7a" + item.getName() + " \u5e93\u5b58: " + cur + " -> " + nv);
    }

    private void handleRemove(CommandSender s, String[] a) {
        if (a.length == 2) { if (delMember(a[1])) { s.sendMessage("\u00a7a\u5df2\u79fb\u9664\u73a9\u5bb6: " + a[1]); } else { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u73a9\u5bb6"); } }
        else if (a.length == 3) { ShopItem item = findItem(a[1]); if (item == null) { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u5546\u54c1"); return; } int amt; try { amt = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { s.sendMessage("\u00a7c\u8bf7\u8f93\u5165\u6570\u5b57"); return; } int cur = stockMap.containsKey(item.getId()) ? stockMap.get(item.getId()) : item.getStock(); if (cur == -1) { s.sendMessage("\u00a7c\u8be5\u5546\u54c1\u5df2\u4e0b\u67b6"); return; } int nv = Math.max(0, cur - amt); stockMap.put(item.getId(), nv); saveShopFile(); s.sendMessage("\u00a7a" + item.getName() + " \u5e93\u5b58: " + cur + " -> " + nv); }
        else { s.sendMessage("\u00a7e/cy remove <\u73a9\u5bb6\u540d> \u6216 /cy remove <\u5546\u54c1ID/\u540d> <\u6570\u91cf>"); }
    }

    private void handleSet(CommandSender s, String[] a) {
        if (a.length < 3) { s.sendMessage("\u00a7e/cy set <\u5546\u54c1ID/\u540d> <\u503c>"); return; }
        ShopItem item = findItem(a[1]); if (item == null) { s.sendMessage("\u00a7c\u627e\u4e0d\u5230\u8be5\u5546\u54c1"); return; }
        int nv; try { nv = Integer.parseInt(a[2]); } catch (NumberFormatException ex) { s.sendMessage("\u00a7c\u8bf7\u8f93\u5165\u6570\u5b57"); return; }
        stockMap.put(item.getId(), nv); saveShopFile();
        String label = nv == -1 ? "\u4e0b\u67b6" : (nv == 0 ? "\u552e\u7f44" : String.valueOf(nv));
        s.sendMessage("\u00a7a" + item.getName() + " -> " + label);
    }

    // ===== SECTION: Tab补全 =====
    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("cy.admin")) return Collections.emptyList();
        if (a.length == 1) return filterTab(Arrays.asList("reload", "status", "info", "add", "remove", "set", "shop", "update"), a[0]);
        if (a.length == 2) { String cmd = a[0].toLowerCase(); if (cmd.equals("info")) return filterTab(onlinePlayerNames(), a[1]); if (cmd.equals("add") || cmd.equals("remove") || cmd.equals("set")) { List<String> items = new ArrayList<>(); for (ShopItem it : shopList) { items.add(it.getId()); items.add(it.getName()); } return filterTab(items, a[1]); } }
        if (a.length == 3) { String cmd = a[0].toLowerCase(); if (cmd.equals("add") || cmd.equals("remove")) return filterTab(Arrays.asList("1", "5", "10", "50", "100"), a[2]); if (cmd.equals("set")) return filterTab(Arrays.asList("-1", "0", "10", "50", "100"), a[2]); }
        return Collections.emptyList();
    }
    private List<String> onlinePlayerNames() { List<String> names = new ArrayList<>(); for (Player pl : Bukkit.getOnlinePlayers()) names.add(pl.getName()); return names; }

    // ===== SECTION: 版本更新 =====
    // ===== SECTION: 版本更新 =====
    private void checkUpdate(final CommandSender manual) {
        String checkingMsg = "[CY] 正在检查更新...";
        getLogger().info(checkingMsg);
        if (manual != null) manual.sendMessage(checkingMsg);
        new Thread(() -> {
            try {
                boolean preferGH = "GH".equalsIgnoreCase(updateCh) || updateCh.isEmpty();
                String pApi = preferGH ? API_GH : API_GE;
                String pDl  = preferGH ? DL_GH  : DL_GE;
                String pName = preferGH ? "GitHub" : "Gitee";
                String bApi = preferGH ? API_GE : API_GH;
                String bDl  = preferGH ? DL_GE  : DL_GH;
                String bName = preferGH ? "Gitee" : "GitHub";

                // 优先渠道
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

                // 备用渠道
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

    private String[] fetchRelease(String apiUrl, String ch) {
        try {
            TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager() { public X509Certificate[] getAcceptedIssuers() { return null; } public void checkClientTrusted(X509Certificate[] c, String a) {} public void checkServerTrusted(X509Certificate[] c, String a) {} }};
            SSLContext sc = SSLContext.getInstance("TLS"); sc.init(null, trustAll, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory()); HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(apiUrl).openConnection();
            c.setRequestMethod("GET"); c.setRequestProperty("User-Agent", "CY-Beibao/1.0"); c.setRequestProperty("Accept", "application/json");
            c.setConnectTimeout(15000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) { getLogger().info("[\u66f4\u65b0] " + ch + " HTTP " + c.getResponseCode()); return null; }
            String json = new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String rv = jParse(json, "tag_name"); String rn = jParse(json, "name");
            if (rv == null || rv.isEmpty()) return null;
            return new String[]{ rv, rn != null ? rn : "" };
        } catch (Exception e) { getLogger().info("[\u66f4\u65b0] " + ch + " " + e.getMessage()); return null; }
    }

    private void applyUpdate(String rv, String notes, String dl, CommandSender manual, String ch) {
        remoteVer = rv;
        if (!rv.equals(localVer)) {
            String msg = "\u00a7c\u00a7l[CY] \u00a7f\u65b0\u7248\u672c! v" + localVer + " -> v" + rv;
            getLogger().info("[CY] \u65b0\u7248\u672c v" + localVer + " -> " + rv + " \u901a\u9053:" + ch);
            if (manual != null) { manual.sendMessage(msg); manual.sendMessage("\u00a77\u4e0b\u8f7d: \u00a7e" + dl); manual.sendMessage("\u00a77\u901a\u9053: \u00a7e" + ch); }
            for (Player op : Bukkit.getOnlinePlayers()) { if (op.isOp()) { op.sendMessage(msg); op.sendMessage("\u00a77\u4e0b\u8f7d: \u00a7e" + dl); } }
        } else {
            getLogger().info("[CY] \u5df2\u662f\u6700\u65b0 v" + localVer);
            if (manual != null) manual.sendMessage("\u00a7a[CY] \u00a7f\u5df2\u662f\u6700\u65b0 v" + localVer);
        }
    }

    private static String jParse(String j, String k) {
        int i = j.indexOf("\"" + k + "\""); if (i < 0) return "";
        int colon = j.indexOf(":", i); int start = j.indexOf("\"", colon + 1); if (start < 0) return "";
        int end = j.indexOf("\"", start + 1); if (end < 0) return "";
        return j.substring(start + 1, end);
    }

} // 类的最后一个 }
