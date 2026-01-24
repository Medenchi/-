package ru.plasmamc.core;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║                        PLASMA CORE                                ║
 * ║                                                                   ║
 * ║  Социальная платформа внутри Minecraft                           ║
 * ║  • Нет администрации — есть делегации                            ║
 * ║  • Нет доната — есть экономика участия                           ║
 * ║  • Нет гринда — есть творчество и память                         ║
 * ║                                                                   ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */
public class PlasmaCore extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ══════════════════════════════════════════════════════════════════
    // КОНСТАНТЫ И КОНФИГУРАЦИЯ
    // ══════════════════════════════════════════════════════════════════
    
    // Глифы для UI (Private Use Area Unicode)
    public static final String GLYPH_COIN = "\uE001";
    public static final String GLYPH_PROJECT = "\uE002";
    public static final String GLYPH_HEART = "\uE003";
    public static final String GLYPH_STAR = "\uE004";
    public static final String GLYPH_DELEGATE = "◆";  // Не глиф, а символ Unicode
    public static final String GLYPH_SHOP = "\uE006";
    public static final String GLYPH_GALLERY = "\uE007";
    public static final String GLYPH_ARROW_RIGHT = "›";
    public static final String GLYPH_ARROW_LEFT = "‹";
    public static final String GLYPH_DOT = "•";
    
    // Цвета темы PLASMA
    public static final TextColor COLOR_PRIMARY = TextColor.fromHexString("#9B59B6");
    public static final TextColor COLOR_SECONDARY = TextColor.fromHexString("#3498DB");
    public static final TextColor COLOR_ACCENT = TextColor.fromHexString("#E91E63");
    public static final TextColor COLOR_SUCCESS = TextColor.fromHexString("#2ECC71");
    public static final TextColor COLOR_WARNING = TextColor.fromHexString("#F39C12");
    public static final TextColor COLOR_ERROR = TextColor.fromHexString("#E74C3C");
    public static final TextColor COLOR_MUTED = TextColor.fromHexString("#95A5A6");
    
    // Экономические константы
    public static final int STARTING_COINS = 100;
    public static final int PROJECT_CREATION_COST = 50;
    public static final int PROJECT_EXPAND_COST_PER_BLOCK = 1;
    public static final int SHOP_CREATION_COST = 200;
    public static final int LIKE_REWARD = 2;
    public static final int CONTEST_PARTICIPATION_REWARD = 25;
    public static final int CONTEST_WINNER_REWARD = 100;
    public static final int DAILY_ACTIVE_BONUS = 5;
    public static final int PROJECT_PUBLISH_REWARD = 15;
    
    // Лимиты
    public static final int MAX_PROJECTS_PER_PLAYER = 5;
    public static final int MAX_PROJECT_SIZE = 10000; // блоков
    public static final int MIN_PROJECT_SIZE = 25;    // 5x5
    public static final int MAX_SHOP_ITEMS = 9;
    public static final int MAX_SHOP_ITEMS_UPGRADED = 27;
    public static final int DELEGATION_TERM_DAYS = 30;
    public static final int MAX_DELEGATES = 5;
    public static final int CONTEST_DURATION_DAYS = 14;
    public static final int VOTES_TO_WIN_DELEGATION = 5;
    
    // Имена миров
    public static final String WORLD_GALLERY = "plasma_gallery";
    public static final String WORLD_SHOPS = "plasma_shops";
    
    // ══════════════════════════════════════════════════════════════════
    // СОСТОЯНИЕ ПЛАГИНА
    // ══════════════════════════════════════════════════════════════════
    
    private static PlasmaCore instance;
    private Connection database;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private ExecutorService asyncExecutor;
    private ScheduledExecutorService scheduler;
    
    // Кэши для производительности
    private final Map<UUID, PlayerData> playerCache = new ConcurrentHashMap<>();
    private final Map<String, Project> projectCache = new ConcurrentHashMap<>();
    private final Map<UUID, Shop> shopCache = new ConcurrentHashMap<>();
    private final Map<UUID, SelectionSession> selectionSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastChunkNotification = new ConcurrentHashMap<>();
    private final Set<UUID> spectatorModeMemoryWall = ConcurrentHashMap.newKeySet();
    
    // Текущее состояние систем
    private Contest activeContest;
    private final List<Delegate> currentDelegates = new CopyOnWriteArrayList<>();
    private final Map<UUID, Vote> activeVotes = new ConcurrentHashMap<>();
    private long lastMemoryWallUpdate = 0;
    
    // ══════════════════════════════════════════════════════════════════
    // ЖИЗНЕННЫЙ ЦИКЛ ПЛАГИНА
    // ══════════════════════════════════════════════════════════════════
    
    @Override
    public void onEnable() {
        instance = this;
        
        getLogger().info("╔══════════════════════════════════════════╗");
        getLogger().info("║         PLASMA CORE загружается...       ║");
        getLogger().info("╚══════════════════════════════════════════╝");
        
        // Инициализация потоков
        asyncExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "PlasmaCore-Async");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "PlasmaCore-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Инициализация базы данных
        if (!initDatabase()) {
            getLogger().severe("Не удалось инициализировать базу данных!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // Загрузка данных
        loadAllData();
        
        // Создание специальных миров
        createSpecialWorlds();
        
        // Регистрация событий и команд
        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();
        
        // Запуск периодических задач
    // Запуск периодических задач
        startScheduledTasks();
        
        getLogger().info("╔══════════════════════════════════════════╗");
        getLogger().info("║       PLASMA CORE успешно загружен!      ║");
        getLogger().info("║                                          ║");
        getLogger().info("║  Игроков в базе: " + String.format("%-22d", getPlayerCount()) + "║");
        getLogger().info("║  Проектов: " + String.format("%-28d", projectCache.size()) + "║");
        getLogger().info("║  Лавок: " + String.format("%-31d", shopCache.size()) + "║");
        getLogger().info("╚══════════════════════════════════════════╝");
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ПЕРИОДИЧЕСКИЕ ЗАДАЧИ
    // ══════════════════════════════════════════════════════════════════
    
    private void startScheduledTasks() {
        // Обновление стены памяти каждые 7 дней
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                Bukkit.getScheduler().runTask(PlasmaCore.this, new Runnable() {
                    @Override
                    public void run() {
                        doUpdateMemoryWall();
                    }
                });
            }
        }, 1, 7 * 24, TimeUnit.HOURS);
        
        // Бродячие лавки каждые 2 часа
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                Bukkit.getScheduler().runTask(PlasmaCore.this, new Runnable() {
                    @Override
                    public void run() {
                        doSpawnWanderingShop();
                    }
                });
            }
        }, 30, 120, TimeUnit.MINUTES);
        
        // Проверка истекших делегаций каждый час
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                currentDelegates.removeIf(d -> d.termEndsAt < now);
            }
        }, 1, 1, TimeUnit.HOURS);
        
        // Обновление скорбордов каждые 30 секунд
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    updatePlayerScoreboard(p);
                }
            }
        }, 100L, 600L);
    }
    
    private void doUpdateMemoryWall() {
        World gallery = Bukkit.getWorld(WORLD_GALLERY);
        if (gallery == null) return;
        
        asyncExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try (PreparedStatement ps = database.prepareStatement(
                        "SELECT * FROM chat_messages WHERE added_to_wall = 0 ORDER BY sent_at ASC LIMIT 100")) {
                    ResultSet rs = ps.executeQuery();
                    
                    List<ChatMessage> newMessages = new ArrayList<>();
                    while (rs.next()) {
                        ChatMessage cm = new ChatMessage();
                        cm.id = rs.getInt("id");
                        cm.playerName = rs.getString("player_name");
                        cm.message = rs.getString("message");
                        cm.sentAt = rs.getLong("sent_at");
                        newMessages.add(cm);
                    }
                    
                    if (!newMessages.isEmpty()) {
                        int height = getWallHeight();
                        final List<ChatMessage> msgs = newMessages;
                        Bukkit.getScheduler().runTask(PlasmaCore.this, new Runnable() {
                            @Override
                            public void run() {
                                putMessagesOnWall(gallery, msgs, height);
                            }
                        });
                    }
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Ошибка обновления стены памяти", e);
                }
            }
        });
    }
    
    private int getWallHeight() {
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT MAX(wall_position_y) FROM chat_messages WHERE added_to_wall = 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                int y = rs.getInt(1);
                return y > 0 ? y : 65;
            }
        } catch (SQLException e) {}
        return 65;
    }
    
    private void putMessagesOnWall(World gallery, List<ChatMessage> messages, int startY) {
        int wallZ = 100;
        int wallStartX = -25;
        int wallWidth = 50;
        int currentY = startY + 1;
        
        for (ChatMessage msg : messages) {
            String filtered = cleanMessage(msg.playerName + ": " + msg.message);
            int x = wallStartX + (msg.id % wallWidth);
            
            if (currentY > 255) currentY = 66;
            
            gallery.getBlockAt(x, currentY, wallZ).setType(Material.SMOOTH_QUARTZ);
            
            Block signBlock = gallery.getBlockAt(x, currentY, wallZ - 1);
            signBlock.setType(Material.OAK_WALL_SIGN);
            
            if (signBlock.getState() instanceof Sign) {
                Sign sign = (Sign) signBlock.getState();
                String[] lines = cutMessage(filtered, 15);
                for (int i = 0; i < Math.min(4, lines.length); i++) {
                    sign.getSide(Side.FRONT).line(i, Component.text(lines[i], COLOR_MUTED));
                }
                sign.update();
            }
            
            final int finalY = currentY;
            final int msgId = msg.id;
            asyncExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    try (PreparedStatement ps = database.prepareStatement(
                            "UPDATE chat_messages SET added_to_wall = 1, wall_position_y = ? WHERE id = ?")) {
                        ps.setInt(1, finalY);
                        ps.setInt(2, msgId);
                        ps.executeUpdate();
                    } catch (SQLException e) {}
                }
            });
            
            currentY++;
        }
    }
    
    private String cleanMessage(String message) {
        message = message.replaceAll("\\+?\\d{10,}", "[скрыто]");
        message = message.replaceAll("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", "[скрыто]");
        message = message.replaceAll("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", "[скрыто]");
        return message;
    }
    
    private String[] cutMessage(String message, int lineLength) {
        List<String> lines = new ArrayList<>();
        while (message.length() > lineLength) {
            int splitAt = message.lastIndexOf(' ', lineLength);
            if (splitAt <= 0) splitAt = lineLength;
            lines.add(message.substring(0, splitAt));
            message = message.substring(splitAt).trim();
        }
        if (!message.isEmpty()) lines.add(message);
        return lines.toArray(new String[0]);
    }
    
    private void doSpawnWanderingShop() {
        List<Shop> eligible = new ArrayList<>();
        for (Shop s : shopCache.values()) {
            if (s.level >= 2 && System.currentTimeMillis() - s.lastWandering > TimeUnit.HOURS.toMillis(12)) {
                eligible.add(s);
            }
        }
        
        if (eligible.isEmpty()) return;
        
        Shop shop = eligible.get(new Random().nextInt(eligible.size()));
        
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return;
        
        Player nearPlayer = online.get(new Random().nextInt(online.size()));
        World world = nearPlayer.getWorld();
        
        if (world.getName().equals(WORLD_GALLERY) || world.getName().equals(WORLD_SHOPS)) {
            return;
        }
        
        Location spawnLoc = findSafeLocation(nearPlayer.getLocation(), 30, 60);
        if (spawnLoc == null) return;
        
        String ownerName = Bukkit.getOfflinePlayer(shop.ownerUuid).getName();
        final Shop finalShop = shop;
        
        world.spawn(spawnLoc, Villager.class, new Consumer<Villager>() {
            @Override
            public void accept(Villager v) {
                v.setAI(true);
                v.setInvulnerable(true);
                v.setProfession(Villager.Profession.NONE);
                v.customName(Component.text("Лавка " + ownerName, COLOR_PRIMARY)
                    .append(Component.text(" (бродячая)", COLOR_MUTED)));
                v.setCustomNameVisible(true);
                
                v.getPersistentDataContainer().set(
                    new NamespacedKey(PlasmaCore.this, "wandering_shop"),
                    PersistentDataType.STRING,
                    finalShop.ownerUuid.toString()
                );
                
                v.getPersistentDataContainer().set(
                    new NamespacedKey(PlasmaCore.this, "despawn_at"),
                    PersistentDataType.LONG,
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)
                );
            }
        });
        
        shop.lastWandering = System.currentTimeMillis();
        saveShop(shop);
        
        Bukkit.broadcast(Component.text("  " + GLYPH_SHOP + " Бродячая лавка ", COLOR_MUTED)
            .append(Component.text(ownerName, COLOR_PRIMARY))
            .append(Component.text(" появилась неподалёку!", COLOR_MUTED)));
    }
    
    private Location findSafeLocation(Location center, int minDist, int maxDist) {
        Random rand = new Random();
        World world = center.getWorld();
        if (world == null) return null;
        
        for (int attempts = 0; attempts < 20; attempts++) {
            int dx = rand.nextInt(maxDist - minDist) + minDist;
            int dz = rand.nextInt(maxDist - minDist) + minDist;
            if (rand.nextBoolean()) dx = -dx;
            if (rand.nextBoolean()) dz = -dz;
            
            int x = center.getBlockX() + dx;
            int z = center.getBlockZ() + dz;
            int y = world.getHighestBlockYAt(x, z);
            
            Block block = world.getBlockAt(x, y, z);
            Block above = world.getBlockAt(x, y + 1, z);
            Block above2 = world.getBlockAt(x, y + 2, z);
            
            if (block.getType().isSolid() && !above.getType().isSolid() && !above2.getType().isSolid()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }
    
    @Override
    public void onDisable() {
        getLogger().info("PLASMA CORE отключается...");
        
        // Сохранение всех данных
        saveAllData();
        
        // Закрытие потоков
        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
            }
        }
        
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        // Закрытие базы данных
        if (database != null) {
            try {
                database.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Ошибка закрытия базы данных", e);
            }
        }
        
        getLogger().info("PLASMA CORE отключён.");
    }
    
    public static PlasmaCore getInstance() {
        return instance;
    }
    
    // ══════════════════════════════════════════════════════════════════
    // БАЗА ДАННЫХ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean initDatabase() {
        try {
            File dataFolder = getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            
            String dbPath = new File(dataFolder, "plasma.db").getAbsolutePath();
            database = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            
            // Создание таблиц
            try (Statement stmt = database.createStatement()) {
                // Таблица игроков
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid TEXT PRIMARY KEY,
                        username TEXT NOT NULL,
                        social_link TEXT,
                        social_type TEXT,
                        coins INTEGER DEFAULT 100,
                        first_join BIGINT,
                        last_join BIGINT,
                        total_likes_given INTEGER DEFAULT 0,
                        total_likes_received INTEGER DEFAULT 0,
                        projects_created INTEGER DEFAULT 0,
                        is_whitelisted INTEGER DEFAULT 0,
                        application_status TEXT DEFAULT 'none',
                        application_date BIGINT,
                        extra_data TEXT
                    )
                """);
                
                // Таблица заявок на вступление
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS applications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        minecraft_nick TEXT NOT NULL,
                        social_username TEXT NOT NULL,
                        social_type TEXT NOT NULL,
                        submitted_at BIGINT NOT NULL,
                        status TEXT DEFAULT 'pending',
                        reviewed_at BIGINT,
                        reviewer_uuid TEXT
                    )
                """);
                
                // Таблица проектов
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS projects (
                        id TEXT PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT,
                        status TEXT DEFAULT 'draft',
                        min_x INTEGER, min_y INTEGER, min_z INTEGER,
                        max_x INTEGER, max_y INTEGER, max_z INTEGER,
                        world TEXT NOT NULL,
                        created_at BIGINT,
                        published_at BIGINT,
                        likes INTEGER DEFAULT 0,
                        in_gallery INTEGER DEFAULT 0,
                        gallery_x INTEGER, gallery_z INTEGER,
                        participants TEXT,
                        FOREIGN KEY (owner_uuid) REFERENCES players(uuid)
                    )
                """);
                
                // Таблица лайков
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS likes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT NOT NULL,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        created_at BIGINT,
                        UNIQUE(player_uuid, target_type, target_id)
                    )
                """);
                
                // Таблица магазинов
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS shops (
                        owner_uuid TEXT PRIMARY KEY,
                        level INTEGER DEFAULT 1,
                        total_sales INTEGER DEFAULT 0,
                        total_revenue INTEGER DEFAULT 0,
                        zone_min_x INTEGER, zone_min_z INTEGER,
                        zone_max_x INTEGER, zone_max_z INTEGER,
                        items TEXT,
                        npc_lines TEXT,
                        created_at BIGINT,
                        last_wandering BIGINT,
                        FOREIGN KEY (owner_uuid) REFERENCES players(uuid)
                    )
                """);
                
                // Таблица делегатов
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS delegates (
                        uuid TEXT PRIMARY KEY,
                        elected_at BIGINT,
                        term_ends_at BIGINT,
                        votes_received INTEGER,
                        specialty TEXT,
                        FOREIGN KEY (uuid) REFERENCES players(uuid)
                    )
                """);
                
                // Таблица голосований
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS votes (
                        id TEXT PRIMARY KEY,
                        type TEXT NOT NULL,
                        title TEXT,
                        description TEXT,
                        created_by TEXT,
                        created_at BIGINT,
                        ends_at BIGINT,
                        status TEXT DEFAULT 'active',
                        options TEXT,
                        results TEXT
                    )
                """);
                
                // Таблица голосов игроков
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_votes (
                        vote_id TEXT,
                        player_uuid TEXT,
                        option_index INTEGER,
                        voted_at BIGINT,
                        PRIMARY KEY (vote_id, player_uuid)
                    )
                """);
                
                // Таблица конкурсов
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS contests (
                        id TEXT PRIMARY KEY,
                        theme TEXT NOT NULL,
                        description TEXT,
                        started_at BIGINT,
                        ends_at BIGINT,
                        status TEXT DEFAULT 'active',
                        participants TEXT,
                        winners TEXT
                    )
                """);
                
                // Таблица сообщений чата (стена памяти)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT,
                        player_name TEXT,
                        message TEXT,
                        sent_at BIGINT,
                        added_to_wall INTEGER DEFAULT 0,
                        wall_position_y INTEGER
                    )
                """);
                
                // Таблица транзакций (для аудита экономики)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_uuid TEXT,
                        amount INTEGER,
                        type TEXT,
                        description TEXT,
                        created_at BIGINT
                    )
                """);
                
                // Индексы для производительности
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_projects_owner ON projects(owner_uuid)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_projects_world ON projects(world)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_chat_wall ON chat_messages(added_to_wall)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_likes_target ON likes(target_type, target_id)");
            }
            
            getLogger().info("База данных инициализирована успешно");
            return true;
            
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Ошибка инициализации базы данных", e);
            return false;
        }
    }
    
    private void loadAllData() {
        // Загрузка делегатов
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT * FROM delegates WHERE term_ends_at > ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Delegate d = new Delegate();
                d.uuid = UUID.fromString(rs.getString("uuid"));
                d.electedAt = rs.getLong("elected_at");
                d.termEndsAt = rs.getLong("term_ends_at");
                d.votesReceived = rs.getInt("votes_received");
                d.specialty = rs.getString("specialty");
                currentDelegates.add(d);
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Ошибка загрузки делегатов", e);
        }
        
        // Загрузка активного конкурса
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT * FROM contests WHERE status = 'active' LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                activeContest = new Contest();
                activeContest.id = rs.getString("id");
                activeContest.theme = rs.getString("theme");
                activeContest.description = rs.getString("description");
                activeContest.startedAt = rs.getLong("started_at");
                activeContest.endsAt = rs.getLong("ends_at");
                String participantsJson = rs.getString("participants");
                if (participantsJson != null) {
                    activeContest.participants = new HashSet<>(
                        Arrays.asList(gson.fromJson(participantsJson, UUID[].class))
                    );
                }
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Ошибка загрузки конкурса", e);
        }
        
        // Загрузка проектов в кэш
        try (PreparedStatement ps = database.prepareStatement("SELECT * FROM projects")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Project p = projectFromResultSet(rs);
                projectCache.put(p.id, p);
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Ошибка загрузки проектов", e);
        }
        
        // Загрузка магазинов в кэш
        try (PreparedStatement ps = database.prepareStatement("SELECT * FROM shops")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Shop s = shopFromResultSet(rs);
                shopCache.put(s.ownerUuid, s);
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Ошибка загрузки магазинов", e);
        }
    }
    
    private void saveAllData() {
        // Сохранение всех кэшированных данных игроков
        for (PlayerData pd : playerCache.values()) {
            savePlayerData(pd);
        }
        
        // Сохранение проектов
        for (Project p : projectCache.values()) {
            saveProject(p);
        }
        
        // Сохранение магазинов
        for (Shop s : shopCache.values()) {
            saveShop(s);
        }
    }
    
    private int getPlayerCount() {
        try (Statement stmt = database.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM players WHERE is_whitelisted = 1");
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Ошибка подсчёта игроков", e);
        }
        return 0;
    }
    
    // ══════════════════════════════════════════════════════════════════
    // СПЕЦИАЛЬНЫЕ МИРЫ (ГАЛЕРЕЯ И МАГАЗИНЫ)
    // ══════════════════════════════════════════════════════════════════
    
    private void createSpecialWorlds() {
        // Мир галереи
        if (Bukkit.getWorld(WORLD_GALLERY) == null) {
            WorldCreator galleryCreator = new WorldCreator(WORLD_GALLERY);
            galleryCreator.generator(new VoidWorldGenerator());
            galleryCreator.environment(World.Environment.NORMAL);
            galleryCreator.generateStructures(false);
            World gallery = galleryCreator.createWorld();
            if (gallery != null) {
                gallery.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                gallery.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                gallery.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                gallery.setGameRule(GameRule.KEEP_INVENTORY, true);
                gallery.setTime(6000); // Полдень
                gallery.setDifficulty(Difficulty.PEACEFUL);
                
                // Создание начальной платформы
                createGallerySpawn(gallery);
                getLogger().info("Мир галереи создан: " + WORLD_GALLERY);
            }
        }
        
        // Мир магазинов
        if (Bukkit.getWorld(WORLD_SHOPS) == null) {
            WorldCreator shopsCreator = new WorldCreator(WORLD_SHOPS);
            shopsCreator.generator(new VoidWorldGenerator());
            shopsCreator.environment(World.Environment.NORMAL);
            shopsCreator.generateStructures(false);
            World shops = shopsCreator.createWorld();
            if (shops != null) {
                shops.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                shops.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                shops.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                shops.setGameRule(GameRule.KEEP_INVENTORY, true);
                shops.setTime(6000);
                shops.setDifficulty(Difficulty.PEACEFUL);
                getLogger().info("Мир магазинов создан: " + WORLD_SHOPS);
            }
        }
    }
    
    /**
     * Генератор пустого мира (void)
     */
    public static class VoidWorldGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, 
                                  int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
            // Пустой мир - ничего не генерируем
        }
        
        @Override
        public void generateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                    int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
            // Пустой
        }
        
        @Override
        public void generateBedrock(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                    int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
            // Без бедрока
        }
        
        @Override
        public void generateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random,
                                  int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
            // Без пещер
        }
        
        @Override
        public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
            return new Location(world, 0, 65, 0);
        }
    }
    
    private void createGallerySpawn(World gallery) {
        // Белая платформа 21x21 из кварца
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                gallery.getBlockAt(x, 64, z).setType(Material.QUARTZ_BLOCK);
            }
        }
        
        // Центральный пьедестал с информацией
        gallery.getBlockAt(0, 65, 0).setType(Material.QUARTZ_PILLAR);
        gallery.getBlockAt(0, 66, 0).setType(Material.QUARTZ_PILLAR);
        
        // Указатель к стене памяти
        Block signBlock = gallery.getBlockAt(0, 67, 0);
        signBlock.setType(Material.OAK_SIGN);
        if (signBlock.getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(0, Component.text("ГАЛЕРЕЯ PLASMA", COLOR_PRIMARY));
            sign.getSide(Side.FRONT).line(1, Component.text(""));
            sign.getSide(Side.FRONT).line(2, Component.text("Музей творчества", COLOR_MUTED));
            sign.getSide(Side.FRONT).line(3, Component.text("и памяти сервера", COLOR_MUTED));
            sign.update();
        }
        
        // Начальная точка для стены памяти (далеко от спавна)
        createMemoryWallBase(gallery);
    }
    
    private void createMemoryWallBase(World gallery) {
        // Стена памяти начинается на z=100
        int wallZ = 100;
        int wallWidth = 50; // блоков
        int wallStartX = -wallWidth / 2;
        
        // Базовая платформа перед стеной
        for (int x = wallStartX - 5; x <= wallStartX + wallWidth + 5; x++) {
            for (int z = wallZ - 10; z <= wallZ; z++) {
                gallery.getBlockAt(x, 64, z).setType(Material.QUARTZ_BLOCK);
            }
        }
        
        // Начальная стена (высотой 10 блоков, будет расти)
        for (int x = wallStartX; x < wallStartX + wallWidth; x++) {
            for (int y = 65; y < 75; y++) {
                gallery.getBlockAt(x, y, wallZ).setType(Material.SMOOTH_QUARTZ);
            }
        }
        
        // Рамка стены
        for (int x = wallStartX - 1; x <= wallStartX + wallWidth; x++) {
            gallery.getBlockAt(x, 65, wallZ).setType(Material.DEEPSLATE_TILES);
            gallery.getBlockAt(x, 74, wallZ).setType(Material.DEEPSLATE_TILES);
        }
        for (int y = 65; y <= 74; y++) {
            gallery.getBlockAt(wallStartX - 1, y, wallZ).setType(Material.DEEPSLATE_TILES);
            gallery.getBlockAt(wallStartX + wallWidth, y, wallZ).setType(Material.DEEPSLATE_TILES);
        }
        
        getLogger().info("Стена памяти инициализирована");
    }
    
    // ══════════════════════════════════════════════════════════════════
    // РЕГИСТРАЦИЯ КОМАНД
    // ══════════════════════════════════════════════════════════════════
    
    private void registerCommands() {
        String[] commands = {
            "plasma", "apply", "balance", "pay", "project", "shop", "gallery",
            "memory", "vote", "contest", "delegate", "like"
        };
        
        for (String cmd : commands) {
            var command = getCommand(cmd);
            if (command != null) {
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
        }
    }
    
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                            @NotNull String label, @NotNull String[] args) {
        
        String cmd = command.getName().toLowerCase();
        
        return switch (cmd) {
            case "plasma" -> handlePlasmaCommand(sender, args);
            case "apply" -> handleApplyCommand(sender, args);
            case "balance" -> handleBalanceCommand(sender, args);
            case "pay" -> handlePayCommand(sender, args);
            case "project" -> handleProjectCommand(sender, args);
            case "shop" -> handleShopCommand(sender, args);
            case "gallery" -> handleGalleryCommand(sender, args);
            case "memory" -> handleMemoryCommand(sender, args);
            case "vote" -> handleVoteCommand(sender, args);
            case "contest" -> handleContestCommand(sender, args);
            case "delegate" -> handleDelegateCommand(sender, args);
            case "like" -> handleLikeCommand(sender, args);
            default -> false;
        };
    }
    
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        String cmd = command.getName().toLowerCase();
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            switch (cmd) {
                case "plasma" -> completions.addAll(List.of("help", "info", "stats", "admin"));
                case "project" -> completions.addAll(List.of("create", "list", "info", "invite", 
                    "kick", "comment", "publish", "delete", "pos1", "pos2", "expand"));
                case "shop" -> completions.addAll(List.of("create", "visit", "edit", "items", 
                    "npc", "stats", "upgrade"));
                case "gallery" -> completions.addAll(List.of("visit", "list", "submit", "info"));
                case "vote" -> completions.addAll(List.of("create", "list", "cast", "results"));
                case "contest" -> completions.addAll(List.of("info", "join", "submit", "list"));
                case "delegate" -> completions.addAll(List.of("list", "nominate", "vote", "info"));
            }
        }
        
        String input = args[args.length - 1].toLowerCase();
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(input))
            .collect(Collectors.toList());
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ОБРАБОТЧИКИ КОМАНД
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handlePlasmaCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendPlasmaHelp(sender);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "info" -> sendPlasmaInfo(sender);
            case "stats" -> sendServerStats(sender);
            case "admin" -> handleAdminSubcommand(sender, args);
            default -> sendPlasmaHelp(sender);
        }
        
        return true;
    }
    
    private void sendPlasmaHelp(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  ═══ PLASMA MC ═══", COLOR_PRIMARY).decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /balance", COLOR_SECONDARY)
            .append(Component.text(" — ваш баланс", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /pay <ник> <сумма>", COLOR_SECONDARY)
            .append(Component.text(" — перевод монет", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /project", COLOR_SECONDARY)
            .append(Component.text(" — управление проектами", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /shop", COLOR_SECONDARY)
            .append(Component.text(" — ваша лавка", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /gallery", COLOR_SECONDARY)
            .append(Component.text(" — галерея работ", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /memory", COLOR_SECONDARY)
            .append(Component.text(" — стена памяти", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /contest", COLOR_SECONDARY)
            .append(Component.text(" — текущий конкурс", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /delegate", COLOR_SECONDARY)
            .append(Component.text(" — делегации", COLOR_MUTED)));
        sender.sendMessage(Component.text("  " + GLYPH_DOT + " /vote", COLOR_SECONDARY)
            .append(Component.text(" — голосования", COLOR_MUTED)));
        sender.sendMessage(Component.empty());
    }
    
    private void sendPlasmaInfo(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  ═══ О СЕРВЕРЕ ═══", COLOR_PRIMARY).decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  PLASMA MC — социальная платформа", COLOR_MUTED));
        sender.sendMessage(Component.text("  внутри Minecraft.", COLOR_MUTED));
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  • Нет администрации", COLOR_SECONDARY));
        sender.sendMessage(Component.text("  • Нет доната", COLOR_SECONDARY));
        sender.sendMessage(Component.text("  • Нет гринда", COLOR_SECONDARY));
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  Есть творчество, память и сообщество.", COLOR_SUCCESS));
        sender.sendMessage(Component.empty());
    }
    
    private void sendServerStats(CommandSender sender) {
        int totalPlayers = getPlayerCount();
        int projectCount = projectCache.size();
        int shopCount = shopCache.size();
        int delegateCount = currentDelegates.size();
        
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  ═══ СТАТИСТИКА ═══", COLOR_PRIMARY).decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("  Игроков: ", COLOR_MUTED)
            .append(Component.text(totalPlayers, COLOR_SECONDARY)));
        sender.sendMessage(Component.text("  Проектов: ", COLOR_MUTED)
            .append(Component.text(projectCount, COLOR_SECONDARY)));
        sender.sendMessage(Component.text("  Лавок: ", COLOR_MUTED)
            .append(Component.text(shopCount, COLOR_SECONDARY)));
        sender.sendMessage(Component.text("  Делегатов: ", COLOR_MUTED)
            .append(Component.text(delegateCount + "/" + MAX_DELEGATES, COLOR_SECONDARY)));
        
        if (activeContest != null) {
            sender.sendMessage(Component.text("  Активный конкурс: ", COLOR_MUTED)
                .append(Component.text(activeContest.theme, COLOR_ACCENT)));
        }
        sender.sendMessage(Component.empty());
    }
    
    private void handleAdminSubcommand(CommandSender sender, String[] args) {
        // Только владелец сервера (из консоли или с permission)
        if (!sender.hasPermission("plasma.owner")) {
            sender.sendMessage(Component.text("Нет доступа.", COLOR_ERROR));
            return;
        }
        
        if (args.length < 2) {
            sender.sendMessage(Component.text("Использование: /plasma admin <approve|reject|whitelist>", COLOR_WARNING));
            return;
        }
        
        switch (args[1].toLowerCase()) {
            case "approve" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Укажите ID заявки", COLOR_WARNING));
                    return;
                }
                approveApplication(sender, args[2]);
            }
            case "reject" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Укажите ID заявки", COLOR_WARNING));
                    return;
                }
                rejectApplication(sender, args[2]);
            }
            case "applications" -> listApplications(sender);
            case "startcontest" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Укажите тему конкурса", COLOR_WARNING));
                    return;
                }
                startContest(sender, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
        }
    }
    
    // ══════════════════════════════════════════════════════════════════
    // СИСТЕМА ЗАЯВОК И WHITELIST
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleApplyCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Только для игроков", COLOR_ERROR));
            return true;
        }
        
        // Проверяем, не подана ли уже заявка
        PlayerData pd = getPlayerData(player.getUniqueId());
        if (pd.isWhitelisted) {
            player.sendMessage(Component.text("Вы уже в whitelist!", COLOR_SUCCESS));
            return true;
        }
        
        if ("pending".equals(pd.applicationStatus)) {
            player.sendMessage(Component.text("Ваша заявка уже на рассмотрении.", COLOR_WARNING));
            return true;
        }
        
        if (args.length < 2) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  ═══ ЗАЯВКА НА ВСТУПЛЕНИЕ ═══", COLOR_PRIMARY));
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  Использование:", COLOR_MUTED));
            player.sendMessage(Component.text("  /apply telegram <@username>", COLOR_SECONDARY));
            player.sendMessage(Component.text("  /apply discord <username#1234>", COLOR_SECONDARY));
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  После одобрения заявки вы получите", COLOR_MUTED));
            player.sendMessage(Component.text("  полный доступ к серверу.", COLOR_MUTED));
            player.sendMessage(Component.empty());
            return true;
        }
        
        String socialType = args[0].toLowerCase();
        if (!socialType.equals("telegram") && !socialType.equals("discord")) {
            player.sendMessage(Component.text("Укажите telegram или discord", COLOR_ERROR));
            return true;
        }
        
        String socialUsername = args[1];
        
        // Проверка на уже привязанный аккаунт
        if (isSocialAccountUsed(socialType, socialUsername)) {
            player.sendMessage(Component.text("Этот аккаунт уже привязан к другому игроку!", COLOR_ERROR));
            return true;
        }
        
        // Создание заявки
        try (PreparedStatement ps = database.prepareStatement(
                "INSERT INTO applications (minecraft_nick, social_username, social_type, submitted_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.getName());
            ps.setString(2, socialUsername);
            ps.setString(3, socialType);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            
            pd.applicationStatus = "pending";
            pd.socialLink = socialUsername;
            pd.socialType = socialType;
            savePlayerData(pd);
            
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  ✓ Заявка подана!", COLOR_SUCCESS));
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  Ожидайте одобрения.", COLOR_MUTED));
            player.sendMessage(Component.text("  Вы получите уведомление при входе.", COLOR_MUTED));
            player.sendMessage(Component.empty());
            
            // Уведомление владельца (если онлайн)
            notifyOwnerAboutApplication(player.getName(), socialType, socialUsername);
            
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Ошибка создания заявки", e);
            player.sendMessage(Component.text("Ошибка! Попробуйте позже.", COLOR_ERROR));
        }
        
        return true;
    }
    
    private boolean isSocialAccountUsed(String type, String username) {
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT COUNT(*) FROM players WHERE social_type = ? AND social_link = ? AND is_whitelisted = 1")) {
            ps.setString(1, type);
            ps.setString(2, username);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            return false;
        }
    }
    
    private void notifyOwnerAboutApplication(String playerName, String socialType, String socialUsername) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("plasma.owner")) {
                p.sendMessage(Component.empty());
                p.sendMessage(Component.text("  Новая заявка!", COLOR_ACCENT).decorate(TextDecoration.BOLD));
                p.sendMessage(Component.text("  Игрок: ", COLOR_MUTED)
                    .append(Component.text(playerName, COLOR_SECONDARY)));
                p.sendMessage(Component.text("  " + socialType + ": ", COLOR_MUTED)
                    .append(Component.text(socialUsername, COLOR_SECONDARY)));
                p.sendMessage(Component.text("  /plasma admin applications", COLOR_MUTED));
                p.sendMessage(Component.empty());
            }
        }
    }
    
    private void listApplications(CommandSender sender) {
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT * FROM applications WHERE status = 'pending' ORDER BY submitted_at DESC")) {
            ResultSet rs = ps.executeQuery();
            
            sender.sendMessage(Component.text("  ═══ ЗАЯВКИ ═══", COLOR_PRIMARY));
            
            boolean hasAny = false;
            while (rs.next()) {
                hasAny = true;
                int id = rs.getInt("id");
                String nick = rs.getString("minecraft_nick");
                String social = rs.getString("social_username");
                String type = rs.getString("social_type");
                
                sender.sendMessage(Component.text("  #" + id + " ", COLOR_MUTED)
                    .append(Component.text(nick, COLOR_SECONDARY))
                    .append(Component.text(" — " + type + ": " + social, COLOR_MUTED)));
            }
            
            if (!hasAny) {
                sender.sendMessage(Component.text("  Нет активных заявок.", COLOR_MUTED));
            } else {
                sender.sendMessage(Component.text("  /plasma admin approve <id>", COLOR_MUTED));
                sender.sendMessage(Component.text("  /plasma admin reject <id>", COLOR_MUTED));
            }
        } catch (SQLException e) {
            sender.sendMessage(Component.text("Ошибка загрузки заявок", COLOR_ERROR));
        }
    }
    
    private void approveApplication(CommandSender sender, String idStr) {
        try {
            int id = Integer.parseInt(idStr);
            
            try (PreparedStatement ps = database.prepareStatement(
                    "SELECT * FROM applications WHERE id = ? AND status = 'pending'")) {
                ps.setInt(1, id);
                ResultSet rs = ps.executeQuery();
                
                if (!rs.next()) {
                    sender.sendMessage(Component.text("Заявка не найдена или уже обработана", COLOR_ERROR));
                    return;
                }
                
                String nick = rs.getString("minecraft_nick");
                String socialUsername = rs.getString("social_username");
                String socialType = rs.getString("social_type");
                
                // Получаем UUID игрока
                UUID playerUuid = Bukkit.getOfflinePlayer(nick).getUniqueId();
                
                // Обновляем статус заявки
                try (PreparedStatement update = database.prepareStatement(
                        "UPDATE applications SET status = 'approved', reviewed_at = ? WHERE id = ?")) {
                    update.setLong(1, System.currentTimeMillis());
                    update.setInt(2, id);
                    update.executeUpdate();
                }
                
                // Добавляем в whitelist
                Bukkit.getWhitelistedPlayers().add(Bukkit.getOfflinePlayer(playerUuid));
                
                // Обновляем данные игрока
                PlayerData pd = getPlayerData(playerUuid);
                pd.isWhitelisted = true;
                pd.socialLink = socialUsername;
                pd.socialType = socialType;
                pd.applicationStatus = "approved";
                savePlayerData(pd);
                
                sender.sendMessage(Component.text("✓ Заявка #" + id + " одобрена. " + nick + " добавлен в whitelist.", COLOR_SUCCESS));
                
                // Уведомляем игрока если онлайн
                Player player = Bukkit.getPlayer(playerUuid);
                if (player != null) {
                    player.sendMessage(Component.empty());
                    player.sendMessage(Component.text("  ✓ Ваша заявка одобрена!", COLOR_SUCCESS).decorate(TextDecoration.BOLD));
                    player.sendMessage(Component.text("  Добро пожаловать в PLASMA MC!", COLOR_PRIMARY));
                    player.sendMessage(Component.empty());
                    
                    // Начисляем стартовые монеты
                    addCoins(playerUuid, STARTING_COINS, "Стартовый бонус");
                }
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Некорректный ID заявки", COLOR_ERROR));
        } catch (SQLException e) {
            getLogger().log(Level.WARNING, "Ошибка одобрения заявки", e);
            sender.sendMessage(Component.text("Ошибка базы данных", COLOR_ERROR));
        }
    }
    
    private void rejectApplication(CommandSender sender, String idStr) {
        try {
            int id = Integer.parseInt(idStr);
            
            try (PreparedStatement ps = database.prepareStatement(
                    "UPDATE applications SET status = 'rejected', reviewed_at = ? WHERE id = ? AND status = 'pending'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, id);
                int updated = ps.executeUpdate();
                
                if (updated > 0) {
                    sender.sendMessage(Component.text("Заявка #" + id + " отклонена.", COLOR_WARNING));
                } else {
                    sender.sendMessage(Component.text("Заявка не найдена или уже обработана", COLOR_ERROR));
                }
            }
        } catch (Exception e) {
            sender.sendMessage(Component.text("Ошибка", COLOR_ERROR));
        }
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ЭКОНОМИКА
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleBalanceCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Только для игроков", COLOR_ERROR));
            return true;
        }
        
        PlayerData pd = getPlayerData(player.getUniqueId());
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  " + GLYPH_COIN + " Баланс: ", COLOR_MUTED)
            .append(Component.text(pd.coins + " монет", COLOR_SUCCESS)));
        player.sendMessage(Component.empty());
        
        return true;
    }
    
    private boolean handlePayCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        
        if (args.length < 2) {
            player.sendMessage(Component.text("Использование: /pay <ник> <сумма>", COLOR_WARNING));
            return true;
        }
        
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Игрок не найден", COLOR_ERROR));
            return true;
        }
        
        if (target.equals(player)) {
            player.sendMessage(Component.text("Нельзя переводить себе", COLOR_ERROR));
            return true;
        }
        
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Укажите положительное число", COLOR_ERROR));
            return true;
        }
        
        PlayerData senderData = getPlayerData(player.getUniqueId());
        if (senderData.coins < amount) {
            player.sendMessage(Component.text("Недостаточно монет!", COLOR_ERROR));
            return true;
        }
        
        // Перевод
        removeCoins(player.getUniqueId(), amount, "Перевод игроку " + target.getName());
        addCoins(target.getUniqueId(), amount, "Перевод от " + player.getName());
        
        player.sendMessage(Component.text("✓ Переведено " + amount + " монет игроку " + target.getName(), COLOR_SUCCESS));
        target.sendMessage(Component.text("Вы получили " + amount + " монет от " + player.getName(), COLOR_SUCCESS));
        
        return true;
    }
    
    private void addCoins(UUID uuid, int amount, String reason) {
        PlayerData pd = getPlayerData(uuid);
        pd.coins += amount;
        savePlayerData(pd);
        logTransaction(uuid, amount, "income", reason);
    }
    
    private boolean removeCoins(UUID uuid, int amount, String reason) {
        PlayerData pd = getPlayerData(uuid);
        if (pd.coins < amount) return false;
        pd.coins -= amount;
        savePlayerData(pd);
        logTransaction(uuid, -amount, "expense", reason);
        return true;
    }
    
    private void logTransaction(UUID uuid, int amount, String type, String description) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT INTO transactions (player_uuid, amount, type, description, created_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, amount);
                ps.setString(3, type);
                ps.setString(4, description);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Ошибка записи транзакции", e);
            }
        });
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ПРОЕКТЫ И ЗОНЫ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleProjectCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        
        if (args.length == 0) {
            sendProjectHelp(player);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "create" -> handleProjectCreate(player, args);
            case "pos1" -> handleProjectPos(player, 1);
            case "pos2" -> handleProjectPos(player, 2);
            case "confirm" -> handleProjectConfirm(player, args);
            case "list" -> handleProjectList(player);
            case "info" -> handleProjectInfo(player, args);
            case "comment" -> handleProjectComment(player, args);
            case "invite" -> handleProjectInvite(player, args);
            case "kick" -> handleProjectKick(player, args);
            case "publish" -> handleProjectPublish(player, args);
            case "delete" -> handleProjectDelete(player, args);
            default -> sendProjectHelp(player);
        }
        
        return true;
    }
    
    private void sendProjectHelp(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ═══ ПРОЕКТЫ ═══", COLOR_PRIMARY).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  /project create <имя>", COLOR_SECONDARY)
            .append(Component.text(" — создать проект", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project pos1", COLOR_SECONDARY)
            .append(Component.text(" — первая точка", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project pos2", COLOR_SECONDARY)
            .append(Component.text(" — вторая точка", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project confirm", COLOR_SECONDARY)
            .append(Component.text(" — подтвердить создание", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project list", COLOR_SECONDARY)
            .append(Component.text(" — ваши проекты", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project info <имя>", COLOR_SECONDARY)
            .append(Component.text(" — информация", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project comment <имя> <текст>", COLOR_SECONDARY)
            .append(Component.text(" — изменить описание", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project invite <имя> <игрок>", COLOR_SECONDARY)
            .append(Component.text(" — добавить участника", COLOR_MUTED)));
        player.sendMessage(Component.text("  /project publish <имя>", COLOR_SECONDARY)
            .append(Component.text(" — опубликовать", COLOR_MUTED)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  Стоимость: " + PROJECT_CREATION_COST + " монет", COLOR_MUTED));
        player.sendMessage(Component.empty());
    }
    
    private void handleProjectCreate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Укажите имя проекта: /project create <имя>", COLOR_WARNING));
            return;
        }
        
        String name = args[1];
        
        // Проверка лимита проектов
        long playerProjects = projectCache.values().stream()
            .filter(p -> p.ownerUuid.equals(player.getUniqueId()))
            .count();
        
        if (playerProjects >= MAX_PROJECTS_PER_PLAYER) {
            player.sendMessage(Component.text("Достигнут лимит проектов: " + MAX_PROJECTS_PER_PLAYER, COLOR_ERROR));
            return;
        }
        
        // Проверка уникальности имени
        if (projectCache.containsKey(name.toLowerCase())) {
            player.sendMessage(Component.text("Проект с таким именем уже существует", COLOR_ERROR));
            return;
        }
        
        // Создаём сессию выбора
        SelectionSession session = new SelectionSession();
        session.projectName = name;
        session.createdAt = System.currentTimeMillis();
        selectionSessions.put(player.getUniqueId(), session);
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  Создание проекта: " + name, COLOR_PRIMARY));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  1. Выделите первую точку: /project pos1", COLOR_MUTED));
        player.sendMessage(Component.text("  2. Выделите вторую точку: /project pos2", COLOR_MUTED));
        player.sendMessage(Component.text("  3. Подтвердите: /project confirm", COLOR_MUTED));
        player.sendMessage(Component.empty());
    }
    
    private void handleProjectPos(Player player, int pos) {
        SelectionSession session = selectionSessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(Component.text("Сначала начните создание: /project create <имя>", COLOR_ERROR));
            return;
        }
        
        Location loc = player.getLocation();
        
        if (pos == 1) {
            session.pos1 = loc;
            player.sendMessage(Component.text("✓ Точка 1 установлена: " + 
                loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(), COLOR_SUCCESS));
        } else {
            session.pos2 = loc;
            player.sendMessage(Component.text("✓ Точка 2 установлена: " + 
                loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ(), COLOR_SUCCESS));
        }
        
        // Показываем информацию если обе точки выбраны
        if (session.pos1 != null && session.pos2 != null) {
            int sizeX = Math.abs(session.pos2.getBlockX() - session.pos1.getBlockX()) + 1;
            int sizeY = Math.abs(session.pos2.getBlockY() - session.pos1.getBlockY()) + 1;
            int sizeZ = Math.abs(session.pos2.getBlockZ() - session.pos1.getBlockZ()) + 1;
            int volume = sizeX * sizeY * sizeZ;
            
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  Размер: " + sizeX + "x" + sizeY + "x" + sizeZ + 
                " (" + volume + " блоков)", COLOR_MUTED));
            player.sendMessage(Component.text("  Стоимость: " + PROJECT_CREATION_COST + " монет", COLOR_WARNING));
            player.sendMessage(Component.text("  /project confirm — подтвердить", COLOR_SUCCESS));
            player.sendMessage(Component.empty());
        }
    }
    
    private void handleProjectConfirm(Player player, String[] args) {
        SelectionSession session = selectionSessions.remove(player.getUniqueId());
        if (session == null || session.pos1 == null || session.pos2 == null) {
            player.sendMessage(Component.text("Сначала выделите область", COLOR_ERROR));
            return;
        }
        
        // Проверка размера
        int sizeX = Math.abs(session.pos2.getBlockX() - session.pos1.getBlockX()) + 1;
        int sizeY = Math.abs(session.pos2.getBlockY() - session.pos1.getBlockY()) + 1;
        int sizeZ = Math.abs(session.pos2.getBlockZ() - session.pos1.getBlockZ()) + 1;
        int volume = sizeX * sizeY * sizeZ;
        
        if (volume < MIN_PROJECT_SIZE) {
            player.sendMessage(Component.text("Слишком маленькая область (мин. " + MIN_PROJECT_SIZE + " блоков)", COLOR_ERROR));
            return;
        }
        
        if (volume > MAX_PROJECT_SIZE) {
            player.sendMessage(Component.text("Слишком большая область (макс. " + MAX_PROJECT_SIZE + " блоков)", COLOR_ERROR));
            return;
        }
        
        // Проверка пересечения с другими проектами
        BoundingBox newBounds = BoundingBox.of(session.pos1, session.pos2);
        for (Project existing : projectCache.values()) {
            if (existing.world.equals(player.getWorld().getName())) {
                BoundingBox existingBounds = BoundingBox.of(
                    new Location(player.getWorld(), existing.minX, existing.minY, existing.minZ),
                    new Location(player.getWorld(), existing.maxX, existing.maxY, existing.maxZ)
                );
                if (newBounds.overlaps(existingBounds)) {
                    player.sendMessage(Component.text("Область пересекается с проектом: " + existing.name, COLOR_ERROR));
                    return;
                }
            }
        }
        
        // Проверка баланса
        if (!removeCoins(player.getUniqueId(), PROJECT_CREATION_COST, "Создание проекта: " + session.projectName)) {
            player.sendMessage(Component.text("Недостаточно монет! Нужно: " + PROJECT_CREATION_COST, COLOR_ERROR));
            return;
        }
        
        // Создание проекта
        Project project = new Project();
        project.id = session.projectName.toLowerCase();
        project.ownerUuid = player.getUniqueId();
        project.name = session.projectName;
        project.description = "Проект игрока " + player.getName();
        project.status = "draft";
        project.world = player.getWorld().getName();
        project.minX = Math.min(session.pos1.getBlockX(), session.pos2.getBlockX());
        project.minY = Math.min(session.pos1.getBlockY(), session.pos2.getBlockY());
        project.minZ = Math.min(session.pos1.getBlockZ(), session.pos2.getBlockZ());
        project.maxX = Math.max(session.pos1.getBlockX(), session.pos2.getBlockX());
        project.maxY = Math.max(session.pos1.getBlockY(), session.pos2.getBlockY());
        project.maxZ = Math.max(session.pos1.getBlockZ(), session.pos2.getBlockZ());
        project.createdAt = System.currentTimeMillis();
        project.participants = new HashSet<>();
        project.participants.add(player.getUniqueId());
        
        projectCache.put(project.id, project);
        saveProject(project);
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ✓ Проект создан: " + project.name, COLOR_SUCCESS).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.text("  Статус: черновик", COLOR_MUTED));
        player.sendMessage(Component.text("  /project comment " + project.id + " <описание>", COLOR_MUTED));
        player.sendMessage(Component.text("  /project publish " + project.id + " — опубликовать", COLOR_MUTED));
        player.sendMessage(Component.empty());
    }
    
    private void handleProjectList(Player player) {
        List<Project> playerProjects = projectCache.values().stream()
            .filter(p -> p.ownerUuid.equals(player.getUniqueId()) || p.participants.contains(player.getUniqueId()))
            .toList();
        
        if (playerProjects.isEmpty()) {
            player.sendMessage(Component.text("У вас нет проектов. /project create <имя>", COLOR_MUTED));
            return;
        }
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ═══ ВАШИ ПРОЕКТЫ ═══", COLOR_PRIMARY));
        player.sendMessage(Component.empty());
        
        for (Project p : playerProjects) {
            String statusIcon = switch (p.status) {
                case "draft" -> "○";
                case "published" -> "●";
                case "gallery" -> "★";
                default -> "?";
            };
            
            boolean isOwner = p.ownerUuid.equals(player.getUniqueId());
            
            player.sendMessage(Component.text("  " + statusIcon + " " + p.name, 
                isOwner ? COLOR_SECONDARY : COLOR_MUTED)
                .append(Component.text(" — " + p.status + (p.inGallery ? " (в галерее)" : ""), COLOR_MUTED)));
        }
        player.sendMessage(Component.empty());
    }
    
    private void handleProjectInfo(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Укажите имя проекта", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return;
        }
        
        String ownerName = Bukkit.getOfflinePlayer(project.ownerUuid).getName();
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ═══ " + project.name + " ═══", COLOR_PRIMARY));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  Автор: ", COLOR_MUTED)
            .append(Component.text(ownerName, COLOR_SECONDARY)));
        player.sendMessage(Component.text("  Статус: ", COLOR_MUTED)
            .append(Component.text(project.status, COLOR_SECONDARY)));
        player.sendMessage(Component.text("  Описание: ", COLOR_MUTED)
            .append(Component.text(project.description, NamedTextColor.WHITE)));
        player.sendMessage(Component.text("  Лайков: ", COLOR_MUTED)
            .append(Component.text(project.likes + " " + GLYPH_HEART, COLOR_ACCENT)));
        
        if (project.participants.size() > 1) {
            player.sendMessage(Component.text("  Участников: " + project.participants.size(), COLOR_MUTED));
        }
        player.sendMessage(Component.empty());
    }
    
    private void handleProjectComment(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Использование: /project comment <имя> <текст>", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return;
        }
        
        if (!project.ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы не владелец проекта", COLOR_ERROR));
            return;
        }
        
        String comment = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (comment.length() > 100) {
            comment = comment.substring(0, 100);
        }
        
        project.description = comment;
        saveProject(project);
        
        player.sendMessage(Component.text("✓ Описание обновлено", COLOR_SUCCESS));
    }
    
    private void handleProjectInvite(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Использование: /project invite <проект> <игрок>", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return;
        }
        
        if (!project.ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы не владелец проекта", COLOR_ERROR));
            return;
        }
        
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            player.sendMessage(Component.text("Игрок не найден", COLOR_ERROR));
            return;
        }
        
        project.participants.add(target.getUniqueId());
        saveProject(project);
        
        player.sendMessage(Component.text("✓ " + target.getName() + " добавлен в проект", COLOR_SUCCESS));
        target.sendMessage(Component.text("Вас добавили в проект: " + project.name, COLOR_SUCCESS));
    }
    
    private void handleProjectKick(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(Component.text("Использование: /project kick <проект> <игрок>", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return;
        }
        
        if (!project.ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы не владелец проекта", COLOR_ERROR));
            return;
        }
        
        Player target = Bukkit.getPlayer(args[2]);
        if (target == null) {
            player.sendMessage(Component.text("Игрок не найден", COLOR_ERROR));
            return;
        }
        
        if (target.getUniqueId().equals(project.ownerUuid)) {
            player.sendMessage(Component.text("Нельзя удалить владельца", COLOR_ERROR));
            return;
        }
        
        project.participants.remove(target.getUniqueId());
        saveProject(project);
        
        player.sendMessage(Component.text("✓ " + target.getName() + " удалён из проекта", COLOR_SUCCESS));
    }
    
    private void handleProjectPublish(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Укажите имя проекта", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return;
        }
        
        if (!project.ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы не владелец проекта", COLOR_ERROR));
            return;
        }
        
        if ("published".equals(project.status)) {
            player.sendMessage(Component.text("Проект уже опубликован", COLOR_WARNING));
            return;
        }
        
        project.status = "published";
        project.publishedAt = System.currentTimeMillis();
        saveProject(project);
        
        // Награда за публикацию
        addCoins(player.getUniqueId(), PROJECT_PUBLISH_REWARD, "Публикация проекта: " + project.name);
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ✓ Проект опубликован!", COLOR_SUCCESS));
        player.sendMessage(Component.text("  Награда: +" + PROJECT_PUBLISH_REWARD + " монет", COLOR_WARNING));
        player.sendMessage(Component.empty());
        
        // Анонс в чат
        Bukkit.broadcast(Component.text("  " + GLYPH_PROJECT + " Новый проект: ", COLOR_MUTED)
            .append(Component.text(project.name, COLOR_PRIMARY))
            .append(Component.text(" от ", COLOR_MUTED))
            .append(Component.text(player.getName(), COLOR_SECONDARY)));
    }
    
    private void handleProjectDelete(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Укажите имя проекта", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return;
        }
        
                if (!project.ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы не владелец проекта", COLOR_ERROR));
            return;
        }
        
        if (project.inGallery) {
            player.sendMessage(Component.text("Нельзя удалить проект из галереи", COLOR_ERROR));
            return;
        }
        
        projectCache.remove(project.id);
        deleteProjectFromDB(project.id);
        
        player.sendMessage(Component.text("✓ Проект удалён: " + project.name, COLOR_SUCCESS));
    }
    
    private void saveProject(Project project) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement("""
                INSERT OR REPLACE INTO projects 
                (id, owner_uuid, name, description, status, min_x, min_y, min_z, max_x, max_y, max_z,
                 world, created_at, published_at, likes, in_gallery, gallery_x, gallery_z, participants)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
                ps.setString(1, project.id);
                ps.setString(2, project.ownerUuid.toString());
                ps.setString(3, project.name);
                ps.setString(4, project.description);
                ps.setString(5, project.status);
                ps.setInt(6, project.minX);
                ps.setInt(7, project.minY);
                ps.setInt(8, project.minZ);
                ps.setInt(9, project.maxX);
                ps.setInt(10, project.maxY);
                ps.setInt(11, project.maxZ);
                ps.setString(12, project.world);
                ps.setLong(13, project.createdAt);
                ps.setLong(14, project.publishedAt);
                ps.setInt(15, project.likes);
                ps.setInt(16, project.inGallery ? 1 : 0);
                ps.setInt(17, project.galleryX);
                ps.setInt(18, project.galleryZ);
                ps.setString(19, gson.toJson(project.participants.stream()
                    .map(UUID::toString).toArray(String[]::new)));
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Ошибка сохранения проекта", e);
            }
        });
    }
    
    private void deleteProjectFromDB(String projectId) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement("DELETE FROM projects WHERE id = ?")) {
                ps.setString(1, projectId);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Ошибка удаления проекта", e);
            }
        });
    }
    
    private Project projectFromResultSet(ResultSet rs) throws SQLException {
        Project p = new Project();
        p.id = rs.getString("id");
        p.ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
        p.name = rs.getString("name");
        p.description = rs.getString("description");
        p.status = rs.getString("status");
        p.minX = rs.getInt("min_x");
        p.minY = rs.getInt("min_y");
        p.minZ = rs.getInt("min_z");
        p.maxX = rs.getInt("max_x");
        p.maxY = rs.getInt("max_y");
        p.maxZ = rs.getInt("max_z");
        p.world = rs.getString("world");
        p.createdAt = rs.getLong("created_at");
        p.publishedAt = rs.getLong("published_at");
        p.likes = rs.getInt("likes");
        p.inGallery = rs.getInt("in_gallery") == 1;
        p.galleryX = rs.getInt("gallery_x");
        p.galleryZ = rs.getInt("gallery_z");
        
        String participantsJson = rs.getString("participants");
        p.participants = new HashSet<>();
        if (participantsJson != null && !participantsJson.isEmpty()) {
            try {
                String[] uuids = gson.fromJson(participantsJson, String[].class);
                if (uuids != null) {
                    for (String uuid : uuids) {
                        p.participants.add(UUID.fromString(uuid));
                    }
                }
            } catch (Exception ignored) {}
        }
        p.participants.add(p.ownerUuid);
        return p;
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ГАЛЕРЕЯ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleGalleryCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (args.length == 0) {
            sendGalleryHelp(player);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "visit" -> handleGalleryVisit(player);
            case "list" -> handleGalleryList(player);
            case "submit" -> handleGallerySubmit(player, args);
            case "info" -> handleGalleryInfo(player, args);
            case "approve" -> handleGalleryApprove(player, args);
            default -> sendGalleryHelp(player);
        }
        return true;
    }
    
    private void sendGalleryHelp(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ═══ ГАЛЕРЕЯ ═══", COLOR_PRIMARY).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  " + GLYPH_GALLERY + " Музей лучших работ", COLOR_MUTED));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  /gallery visit", COLOR_SECONDARY)
            .append(Component.text(" — посетить", COLOR_MUTED)));
        player.sendMessage(Component.text("  /gallery list", COLOR_SECONDARY)
            .append(Component.text(" — список работ", COLOR_MUTED)));
        player.sendMessage(Component.text("  /gallery submit <проект>", COLOR_SECONDARY)
            .append(Component.text(" — подать заявку", COLOR_MUTED)));
        player.sendMessage(Component.empty());
    }
    
    private void handleGalleryVisit(Player player) {
        World gallery = Bukkit.getWorld(WORLD_GALLERY);
        if (gallery == null) {
            player.sendMessage(Component.text("Галерея недоступна", COLOR_ERROR));
            return;
        }
        
        player.getPersistentDataContainer().set(
            new NamespacedKey(this, "prev_location"),
            PersistentDataType.STRING,
            serializeLocation(player.getLocation())
        );
        
        Location spawn = new Location(gallery, 0.5, 65, 0.5);
        player.teleport(spawn);
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  Добро пожаловать в Галерею!", COLOR_PRIMARY));
        player.sendMessage(Component.text("  /gallery list — работы", COLOR_MUTED));
        player.sendMessage(Component.text("  /spawn — вернуться", COLOR_MUTED));
        player.sendMessage(Component.empty());
        
        player.showTitle(Title.title(
            Component.text("ГАЛЕРЕЯ", COLOR_PRIMARY).decorate(TextDecoration.BOLD),
            Component.text("Музей творчества PLASMA", COLOR_MUTED),
            Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofMillis(500))
        ));
    }
    
    private void handleGalleryList(Player player) {
        List<Project> galleryProjects = projectCache.values().stream()
            .filter(p -> p.inGallery)
            .sorted((a, b) -> Integer.compare(b.likes, a.likes))
            .toList();
        
        if (galleryProjects.isEmpty()) {
            player.sendMessage(Component.text("Галерея пока пуста", COLOR_MUTED));
            return;
        }
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ═══ РАБОТЫ В ГАЛЕРЕЕ ═══", COLOR_PRIMARY));
        player.sendMessage(Component.empty());
        
        int i = 1;
        for (Project p : galleryProjects) {
            String ownerName = Bukkit.getOfflinePlayer(p.ownerUuid).getName();
            player.sendMessage(Component.text("  " + i + ". ", COLOR_MUTED)
                .append(Component.text(p.name, COLOR_SECONDARY))
                .append(Component.text(" — " + ownerName, COLOR_MUTED))
                .append(Component.text(" " + GLYPH_HEART + p.likes, COLOR_ACCENT)));
            i++;
            if (i > 10) break;
        }
        player.sendMessage(Component.empty());
    }
    
    private void handleGallerySubmit(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Укажите проект: /gallery submit <имя>", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return;
        }
        
        if (!project.ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Вы не владелец проекта", COLOR_ERROR));
            return;
        }
        
        if (!"published".equals(project.status)) {
            player.sendMessage(Component.text("Сначала опубликуйте: /project publish", COLOR_ERROR));
            return;
        }
        
        if (project.inGallery) {
            player.sendMessage(Component.text("Проект уже в галерее", COLOR_WARNING));
            return;
        }
        
        project.status = "pending_gallery";
        saveProject(project);
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ✓ Заявка подана!", COLOR_SUCCESS));
        player.sendMessage(Component.text("  Делегаты рассмотрят её.", COLOR_MUTED));
        player.sendMessage(Component.empty());
        
        for (Delegate d : currentDelegates) {
            Player delegate = Bukkit.getPlayer(d.uuid);
            if (delegate != null) {
                delegate.sendMessage(Component.text("[Галерея] Заявка: " + project.name + 
                    " от " + player.getName(), COLOR_ACCENT));
            }
        }
    }
    
    private void handleGalleryInfo(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Укажите проект", COLOR_WARNING));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null || !project.inGallery) {
            player.sendMessage(Component.text("Работа не найдена", COLOR_ERROR));
            return;
        }
        
        String ownerName = Bukkit.getOfflinePlayer(project.ownerUuid).getName();
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  " + GLYPH_GALLERY + " " + project.name, COLOR_PRIMARY)
            .decorate(TextDecoration.BOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  Автор: " + ownerName, COLOR_SECONDARY));
        player.sendMessage(Component.text("  " + project.description, NamedTextColor.WHITE));
        player.sendMessage(Component.text("  " + GLYPH_HEART + " " + project.likes, COLOR_ACCENT));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  /like " + project.id, COLOR_MUTED));
        player.sendMessage(Component.empty());
    }
    
    private void handleGalleryApprove(Player player, String[] args) {
        if (!isDelegate(player.getUniqueId())) {
            player.sendMessage(Component.text("Только делегаты", COLOR_ERROR));
            return;
        }
        
        if (args.length < 2) {
            List<Project> pending = projectCache.values().stream()
                .filter(p -> "pending_gallery".equals(p.status))
                .toList();
            
            if (pending.isEmpty()) {
                player.sendMessage(Component.text("Нет заявок", COLOR_MUTED));
                return;
            }
            
            player.sendMessage(Component.text("  ═══ ЗАЯВКИ ═══", COLOR_PRIMARY));
            for (Project p : pending) {
                String owner = Bukkit.getOfflinePlayer(p.ownerUuid).getName();
                player.sendMessage(Component.text("  • " + p.name + " — " + owner, COLOR_MUTED));
            }
            player.sendMessage(Component.text("  /gallery approve <проект>", COLOR_MUTED));
            return;
        }
        
        Project project = projectCache.get(args[1].toLowerCase());
        if (project == null || !"pending_gallery".equals(project.status)) {
            player.sendMessage(Component.text("Заявка не найдена", COLOR_ERROR));
            return;
        }
        
        addProjectToGallery(project);
        player.sendMessage(Component.text("✓ Добавлено в галерею!", COLOR_SUCCESS));
        
        Player owner = Bukkit.getPlayer(project.ownerUuid);
        if (owner != null) {
            owner.sendMessage(Component.text("  ★ Проект " + project.name + " в Галерее!", COLOR_SUCCESS));
        }
        
        addCoins(project.ownerUuid, 50, "Галерея: " + project.name);
    }
    
    private void addProjectToGallery(Project project) {
        World gallery = Bukkit.getWorld(WORLD_GALLERY);
        if (gallery == null) return;
        
        project.status = "gallery";
        project.inGallery = true;
        
        int count = (int) projectCache.values().stream().filter(p -> p.inGallery).count();
        int gridSize = 50;
        project.galleryX = (count % 5) * gridSize - 100;
        project.galleryZ = (count / 5) * gridSize + 50;
        
        World source = Bukkit.getWorld(project.world);
        if (source != null) {
            copyBuildingToGallery(source, project, gallery, project.galleryX, 65, project.galleryZ);
        }
        
        createGallerySign(gallery, project, project.galleryX, project.galleryZ);
        saveProject(project);
    }
    
    private void copyBuildingToGallery(World source, Project project, World gallery, 
                                        int targetX, int targetY, int targetZ) {
        int offsetX = targetX - project.minX;
        int offsetY = targetY - project.minY;
        int offsetZ = targetZ - project.minZ;
        
        int sizeX = project.maxX - project.minX + 1;
        int sizeZ = project.maxZ - project.minZ + 1;
        
        // Платформа
        for (int x = -2; x <= sizeX + 2; x++) {
            for (int z = -2; z <= sizeZ + 2; z++) {
                gallery.getBlockAt(targetX + x - 1, targetY - 1, targetZ + z - 1)
                    .setType(Material.QUARTZ_BLOCK);
            }
        }
        
        // Копируем блоки
        for (int x = project.minX; x <= project.maxX; x++) {
            for (int y = project.minY; y <= project.maxY; y++) {
                for (int z = project.minZ; z <= project.maxZ; z++) {
                    Block sourceBlock = source.getBlockAt(x, y, z);
                    if (sourceBlock.getType() != Material.AIR) {
                        Block targetBlock = gallery.getBlockAt(
                            x + offsetX, y + offsetY, z + offsetZ);
                        targetBlock.setType(sourceBlock.getType());
                        targetBlock.setBlockData(sourceBlock.getBlockData().clone());
                    }
                }
            }
        }
    }
    
    private void createGallerySign(World gallery, Project project, int x, int z) {
        String ownerName = Bukkit.getOfflinePlayer(project.ownerUuid).getName();
        Block signBlock = gallery.getBlockAt(x, 66, z - 3);
        signBlock.setType(Material.OAK_SIGN);
        
        if (signBlock.getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).line(0, Component.text("★ " + project.name, COLOR_PRIMARY));
            sign.getSide(Side.FRONT).line(1, Component.empty());
            sign.getSide(Side.FRONT).line(2, Component.text(ownerName, COLOR_SECONDARY));
            sign.getSide(Side.FRONT).line(3, Component.text(GLYPH_HEART + " " + project.likes, COLOR_ACCENT));
            sign.update();
        }
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ЛАЙКИ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleLikeCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (args.length < 1) {
            player.sendMessage(Component.text("/like <проект>", COLOR_WARNING));
            return true;
        }
        
        Project project = projectCache.get(args[0].toLowerCase());
        if (project == null) {
            player.sendMessage(Component.text("Проект не найден", COLOR_ERROR));
            return true;
        }
        
        if (project.ownerUuid.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Нельзя лайкать свой проект", COLOR_ERROR));
            return true;
        }
        
        if (hasLiked(player.getUniqueId(), "project", project.id)) {
            player.sendMessage(Component.text("Вы уже лайкали", COLOR_WARNING));
            return true;
        }
        
        addLike(player.getUniqueId(), "project", project.id);
        project.likes++;
        saveProject(project);
        
        addCoins(project.ownerUuid, LIKE_REWARD, "Лайк: " + project.name);
        player.sendMessage(Component.text("✓ " + GLYPH_HEART, COLOR_ACCENT));
        
        Player owner = Bukkit.getPlayer(project.ownerUuid);
        if (owner != null) {
            owner.sendMessage(Component.text(player.getName() + " лайкнул " + project.name, COLOR_ACCENT));
        }
        return true;
    }
    
    private boolean hasLiked(UUID playerUuid, String type, String targetId) {
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT COUNT(*) FROM likes WHERE player_uuid=? AND target_type=? AND target_id=?")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, type);
            ps.setString(3, targetId);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) { return false; }
    }
    
    private void addLike(UUID playerUuid, String type, String targetId) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT INTO likes (player_uuid,target_type,target_id,created_at) VALUES(?,?,?,?)")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, type);
                ps.setString(3, targetId);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
    }
    
    // ══════════════════════════════════════════════════════════════════
    // МАГАЗИНЫ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleShopCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (args.length == 0) {
            sendShopHelp(player);
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "create" -> handleShopCreate(player);
            case "visit" -> handleShopVisit(player, args);
            case "edit" -> handleShopEdit(player);
            case "items" -> handleShopItems(player);
            case "npc" -> handleShopNpc(player, args);
            case "stats" -> handleShopStats(player);
            case "upgrade" -> handleShopUpgrade(player);
            default -> sendShopHelp(player);
        }
        return true;
    }
    
    private void sendShopHelp(Player player) {
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ═══ ХОМЛАВКИ ═══", COLOR_PRIMARY).decorate(TextDecoration.BOLD));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  /shop create", COLOR_SECONDARY)
            .append(Component.text(" — создать (" + SHOP_CREATION_COST + ")", COLOR_MUTED)));
        player.sendMessage(Component.text("  /shop visit [ник]", COLOR_SECONDARY)
            .append(Component.text(" — посетить", COLOR_MUTED)));
        player.sendMessage(Component.text("  /shop items", COLOR_SECONDARY)
            .append(Component.text(" — товары", COLOR_MUTED)));
        player.sendMessage(Component.text("  /shop npc <текст>", COLOR_SECONDARY)
            .append(Component.text(" — реплика", COLOR_MUTED)));
        player.sendMessage(Component.text("  /shop stats", COLOR_SECONDARY)
            .append(Component.text(" — статистика", COLOR_MUTED)));
        player.sendMessage(Component.text("  /shop upgrade", COLOR_SECONDARY)
            .append(Component.text(" — улучшить", COLOR_MUTED)));
        player.sendMessage(Component.empty());
    }
    
    private void handleShopCreate(Player player) {
        if (shopCache.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("У вас уже есть лавка!", COLOR_WARNING));
            return;
        }
        
        if (!removeCoins(player.getUniqueId(), SHOP_CREATION_COST, "Создание лавки")) {
            player.sendMessage(Component.text("Нужно " + SHOP_CREATION_COST + " монет", COLOR_ERROR));
            return;
        }
        
        World shopWorld = Bukkit.getWorld(WORLD_SHOPS);
        if (shopWorld == null) {
            addCoins(player.getUniqueId(), SHOP_CREATION_COST, "Возврат");
            player.sendMessage(Component.text("Мир недоступен", COLOR_ERROR));
            return;
        }
        
        int idx = shopCache.size();
        int shopX = (idx % 10) * 30;
        int shopZ = (idx / 10) * 30;
        
        Shop shop = new Shop();
        shop.ownerUuid = player.getUniqueId();
        shop.level = 1;
        shop.zoneMinX = shopX;
        shop.zoneMinZ = shopZ;
        shop.zoneMaxX = shopX + 12;
        shop.zoneMaxZ = shopZ + 8;
        shop.items = new ArrayList<>();
        shop.npcLines = new ArrayList<>();
        shop.npcLines.add("Добро пожаловать!");
        shop.createdAt = System.currentTimeMillis();
        
        shopCache.put(player.getUniqueId(), shop);
        saveShop(shop);
        createShopPlatform(shopWorld, shop);
        spawnShopNpc(shopWorld, shop, player);
        
        player.teleport(new Location(shopWorld, shopX + 6.5, 65, shopZ + 4.5));
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ✓ Лавка создана!", COLOR_SUCCESS));
        player.sendMessage(Component.text("  Стройте в зоне 13x9", COLOR_MUTED));
        player.sendMessage(Component.text("  /shop items — товары", COLOR_MUTED));
        player.sendMessage(Component.empty());
    }
    
    private void createShopPlatform(World world, Shop shop) {
        for (int x = shop.zoneMinX - 1; x <= shop.zoneMaxX + 1; x++) {
            for (int z = shop.zoneMinZ - 1; z <= shop.zoneMaxZ + 1; z++) {
                world.getBlockAt(x, 64, z).setType(Material.QUARTZ_BLOCK);
            }
        }
        
        int[][] corners = {{shop.zoneMinX, shop.zoneMinZ}, {shop.zoneMinX, shop.zoneMaxZ},
                           {shop.zoneMaxX, shop.zoneMinZ}, {shop.zoneMaxX, shop.zoneMaxZ}};
        for (int[] c : corners) {
            for (int y = 65; y < 68; y++) {
                world.getBlockAt(c[0], y, c[1]).setType(Material.LIGHT_BLUE_STAINED_GLASS);
            }
        }
    }
    
    private void spawnShopNpc(World world, Shop shop, Player owner) {
        Location loc = new Location(world, shop.zoneMinX + 6.5, 65, shop.zoneMinZ + 1.5);
        Villager npc = world.spawn(loc, Villager.class, v -> {
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setProfession(Villager.Profession.NONE);
            v.customName(Component.text("Лавка " + owner.getName(), COLOR_PRIMARY));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(
                new NamespacedKey(getInstance(), "shop_owner"),
                PersistentDataType.STRING, owner.getUniqueId().toString());
        });
        shop.npcEntityId = npc.getUniqueId();
    }
    
    private void handleShopVisit(Player player, String[] args) {
        UUID target = args.length < 2 ? player.getUniqueId() : 
            Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        
        Shop shop = shopCache.get(target);
        if (shop == null) {
            player.sendMessage(Component.text(target.equals(player.getUniqueId()) ? 
                "У вас нет лавки" : "Лавка не найдена", COLOR_ERROR));
            return;
        }
        
        World w = Bukkit.getWorld(WORLD_SHOPS);
        if (w == null) return;
        
        player.getPersistentDataContainer().set(
            new NamespacedKey(this, "prev_location"),
            PersistentDataType.STRING, serializeLocation(player.getLocation()));
        
        player.teleport(new Location(w, shop.zoneMinX + 6.5, 65, shop.zoneMinZ + 4.5));
        player.sendMessage(Component.text("Лавка " + Bukkit.getOfflinePlayer(target).getName(), COLOR_PRIMARY));
    }
    
    private void handleShopEdit(Player player) {
        if (!shopCache.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("Нет лавки", COLOR_ERROR));
            return;
        }
        handleShopVisit(player, new String[]{"visit"});
    }
    
    private void handleShopItems(Player player) {
        Shop shop = shopCache.get(player.getUniqueId());
        if (shop == null) {
            player.sendMessage(Component.text("Нет лавки", COLOR_ERROR));
            return;
        }
        
        int slots = shop.level >= 3 ? 27 : 9;
        Inventory inv = Bukkit.createInventory(null, slots, Component.text("Товары", COLOR_PRIMARY));
        
        for (int i = 0; i < Math.min(shop.items.size(), slots); i++) {
            inv.setItem(i, shop.items.get(i).displayItem());
        }
        
        player.openInventory(inv);
        player.getPersistentDataContainer().set(
            new NamespacedKey(this, "editing_shop"), PersistentDataType.BYTE, (byte)1);
    }
    
    private void handleShopNpc(Player player, String[] args) {
        Shop shop = shopCache.get(player.getUniqueId());
        if (shop == null) {
            player.sendMessage(Component.text("Нет лавки", COLOR_ERROR));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("/shop npc <текст>", COLOR_WARNING));
            return;
        }
        
        String line = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (line.length() > 50) line = line.substring(0, 50);
        
        shop.npcLines.clear();
        shop.npcLines.add(line);
        saveShop(shop);
        player.sendMessage(Component.text("✓ Реплика обновлена", COLOR_SUCCESS));
    }
    
    private void handleShopStats(Player player) {
        Shop shop = shopCache.get(player.getUniqueId());
        if (shop == null) {
            player.sendMessage(Component.text("Нет лавки", COLOR_ERROR));
            return;
        }
        
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ═══ СТАТИСТИКА ═══", COLOR_PRIMARY));
        player.sendMessage(Component.text("  Уровень: " + shop.level, COLOR_MUTED));
        player.sendMessage(Component.text("  Продаж: " + shop.totalSales, COLOR_MUTED));
        player.sendMessage(Component.text("  Выручка: " + shop.totalRevenue, COLOR_MUTED));
        
        int next = getNextLevelReq(shop.level);
        if (next > 0) {
            player.sendMessage(Component.text("  До уровня: " + (next - shop.totalSales), COLOR_WARNING));
        }
        player.sendMessage(Component.empty());
    }
    
    private void handleShopUpgrade(Player player) {
        Shop shop = shopCache.get(player.getUniqueId());
        if (shop == null) {
            player.sendMessage(Component.text("Нет лавки", COLOR_ERROR));
            return;
        }
        
        int req = getNextLevelReq(shop.level);
        if (req < 0) {
            player.sendMessage(Component.text("Максимальный уровень!", COLOR_SUCCESS));
            return;
        }
        if (shop.totalSales < req) {
            player.sendMessage(Component.text("Нужно " + req + " продаж", COLOR_ERROR));
            return;
        }
        
        shop.level++;
        shop.zoneMaxX += 5;
        shop.zoneMaxZ += 3;
        saveShop(shop);
        
        World w = Bukkit.getWorld(WORLD_SHOPS);
        if (w != null) createShopPlatform(w, shop);
        
        player.sendMessage(Component.text("  ★ Уровень " + shop.level + "!", COLOR_SUCCESS));
    }
    
    private int getNextLevelReq(int level) {
        return switch(level) { case 1->10; case 2->50; case 3->150; case 4->500; default->-1; };
    }
    
    private void saveShop(Shop shop) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement("""
                INSERT OR REPLACE INTO shops
                (owner_uuid,level,total_sales,total_revenue,zone_min_x,zone_min_z,zone_max_x,zone_max_z,items,npc_lines,created_at,last_wandering)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
                ps.setString(1, shop.ownerUuid.toString());
                ps.setInt(2, shop.level);
                ps.setInt(3, shop.totalSales);
                ps.setInt(4, shop.totalRevenue);
                ps.setInt(5, shop.zoneMinX);
                ps.setInt(6, shop.zoneMinZ);
                ps.setInt(7, shop.zoneMaxX);
                ps.setInt(8, shop.zoneMaxZ);
                ps.setString(9, gson.toJson(shop.items));
                ps.setString(10, gson.toJson(shop.npcLines));
                ps.setLong(11, shop.createdAt);
                ps.setLong(12, shop.lastWandering);
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
    }
    
    private Shop shopFromResultSet(ResultSet rs) throws SQLException {
        Shop s = new Shop();
        s.ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
        s.level = rs.getInt("level");
        s.totalSales = rs.getInt("total_sales");
        s.totalRevenue = rs.getInt("total_revenue");
        s.zoneMinX = rs.getInt("zone_min_x");
        s.zoneMinZ = rs.getInt("zone_min_z");
        s.zoneMaxX = rs.getInt("zone_max_x");
        s.zoneMaxZ = rs.getInt("zone_max_z");
        s.createdAt = rs.getLong("created_at");
        s.lastWandering = rs.getLong("last_wandering");
        s.items = new ArrayList<>();
        s.npcLines = new ArrayList<>();
        
        try {
            String items = rs.getString("items");
            if (items != null) {
                ShopItem[] arr = gson.fromJson(items, ShopItem[].class);
                if (arr != null) s.items.addAll(Arrays.asList(arr));
            }
            String lines = rs.getString("npc_lines");
            if (lines != null) {
                String[] arr = gson.fromJson(lines, String[].class);
                if (arr != null) s.npcLines.addAll(Arrays.asList(arr));
            }
        } catch (Exception ignored) {}
        return s;
    }
    
    // ══════════════════════════════════════════════════════════════════
    // СТЕНА ПАМЯТИ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleMemoryCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (args.length == 0) {
            player.sendMessage(Component.text("  /memory visit | search <ник> | leave", COLOR_MUTED));
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "visit" -> handleMemoryVisit(player);
            case "search" -> handleMemorySearch(player, args);
            case "leave" -> handleMemoryLeave(player);
        }
        return true;
    }
    
    private void handleMemoryVisit(Player player) {
        World gallery = Bukkit.getWorld(WORLD_GALLERY);
        if (gallery == null) return;
        
        player.getPersistentDataContainer().set(new NamespacedKey(this, "prev_location"),
            PersistentDataType.STRING, serializeLocation(player.getLocation()));
        player.getPersistentDataContainer().set(new NamespacedKey(this, "prev_gamemode"),
            PersistentDataType.STRING, player.getGameMode().name());
        
        player.teleport(new Location(gallery, 0, 70, 95));
        player.setGameMode(GameMode.SPECTATOR);
        spectatorModeMemoryWall.add(player.getUniqueId());
        
        player.sendMessage(Component.text("  Стена Памяти. /memory leave", COLOR_PRIMARY));
    }
    
    private void handleMemorySearch(Player player, String[] args) {
        if (args.length < 2) return;
        String nick = args[1];
        
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "SELECT * FROM chat_messages WHERE player_name LIKE ? ORDER BY sent_at DESC LIMIT 20")) {
                ps.setString(1, "%" + nick + "%");
                ResultSet rs = ps.executeQuery();
                List<String> msgs = new ArrayList<>();
                while (rs.next()) {
                    msgs.add(rs.getString("player_name") + ": " + rs.getString("message"));
                }
                Bukkit.getScheduler().runTask(getInstance(), () -> {
                    if (msgs.isEmpty()) player.sendMessage(Component.text("Не найдено", COLOR_MUTED));
                    else msgs.forEach(m -> player.sendMessage(Component.text("  " + m, COLOR_MUTED)));
                });
            } catch (SQLException e) {}
        });
    }
    
    private void handleMemoryLeave(Player player) {
        if (!spectatorModeMemoryWall.remove(player.getUniqueId())) return;
        
        String mode = player.getPersistentDataContainer().get(
            new NamespacedKey(this, "prev_gamemode"), PersistentDataType.STRING);
        player.setGameMode(mode != null ? GameMode.valueOf(mode) : GameMode.SURVIVAL);
        returnToPreviousLocation(player);
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ДЕЛЕГАЦИИ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleDelegateCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (args.length == 0) {
            player.sendMessage(Component.text("  /delegate list | nominate | vote <ник>", COLOR_MUTED));
            return true;
        }
        
        switch (args[0].toLowerCase()) {
            case "list" -> handleDelegateList(player);
            case "nominate" -> handleDelegateNominate(player);
            case "vote" -> handleDelegateVote(player, args);
        }
        return true;
    }
    
    private void handleDelegateList(Player player) {
        player.sendMessage(Component.text("  ═══ ДЕЛЕГАТЫ ═══", COLOR_PRIMARY));
        if (currentDelegates.isEmpty()) {
            player.sendMessage(Component.text("  Нет делегатов", COLOR_MUTED));
        } else {
            for (Delegate d : currentDelegates) {
                String name = Bukkit.getOfflinePlayer(d.uuid).getName();
                long days = (d.termEndsAt - System.currentTimeMillis()) / 86400000;
                player.sendMessage(Component.text("  " + GLYPH_DELEGATE + " " + name + " — " + days + "д", COLOR_MUTED));
            }
        }
    }
    
    private void handleDelegateNominate(Player player) {
        if (isDelegate(player.getUniqueId()) || activeVotes.containsKey(player.getUniqueId()) 
            || currentDelegates.size() >= MAX_DELEGATES) {
            player.sendMessage(Component.text("Невозможно", COLOR_ERROR));
            return;
        }
        
        Vote vote = new Vote();
        vote.id = "del_" + player.getUniqueId();
        vote.type = "delegation";
        vote.title = player.getName();
        vote.createdBy = player.getUniqueId();
        vote.createdAt = System.currentTimeMillis();
        vote.endsAt = vote.createdAt + TimeUnit.DAYS.toMillis(3);
        vote.options = List.of("За", "Против");
        vote.results = new HashMap<>();
        vote.results.put(0, 0);
        vote.results.put(1, 0);
        
        activeVotes.put(player.getUniqueId(), vote);
        saveVote(vote);
        
        player.sendMessage(Component.text("✓ Выдвинуты! Нужно " + VOTES_TO_WIN_DELEGATION + " голосов", COLOR_SUCCESS));
        Bukkit.broadcast(Component.text("  " + player.getName() + " в делегаты!", COLOR_ACCENT));
    }
    
    private void handleDelegateVote(Player player, String[] args) {
        if (args.length < 2) return;
        
        UUID target = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
        Vote vote = activeVotes.get(target);
        
        if (vote == null || hasVotedIn(player.getUniqueId(), vote.id) || target.equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Невозможно", COLOR_ERROR));
            return;
        }
        
        vote.results.put(0, vote.results.get(0) + 1);
        recordPlayerVote(player.getUniqueId(), vote.id, 0);
        saveVote(vote);
        
        player.sendMessage(Component.text("✓ Голос учтён", COLOR_SUCCESS));
        
        if (vote.results.get(0) >= VOTES_TO_WIN_DELEGATION) {
            electDelegate(target);
            activeVotes.remove(target);
        }
    }
    
    private void electDelegate(UUID uuid) {
        Delegate d = new Delegate();
        d.uuid = uuid;
        d.electedAt = System.currentTimeMillis();
        d.termEndsAt = d.electedAt + TimeUnit.DAYS.toMillis(DELEGATION_TERM_DAYS);
        d.votesReceived = activeVotes.get(uuid).results.get(0);
        currentDelegates.add(d);
        saveDelegateToDb(d);
        
        Bukkit.broadcast(Component.text("  ★ " + Bukkit.getOfflinePlayer(uuid).getName() + 
            " — делегат!", COLOR_SUCCESS));
    }
    
    private boolean isDelegate(UUID uuid) {
        return currentDelegates.stream().anyMatch(d -> d.uuid.equals(uuid));
    }
    
    private void saveDelegateToDb(Delegate d) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT OR REPLACE INTO delegates VALUES(?,?,?,?,?)")) {
                ps.setString(1, d.uuid.toString());
                ps.setLong(2, d.electedAt);
                ps.setLong(3, d.termEndsAt);
                ps.setInt(4, d.votesReceived);
                ps.setString(5, d.specialty);
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ГОЛОСОВАНИЯ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleVoteCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        player.sendMessage(Component.text("  /vote — см. /delegate vote", COLOR_MUTED));
        return true;
    }
    
    private boolean hasVotedIn(UUID player, String voteId) {
        try (PreparedStatement ps = database.prepareStatement(
                "SELECT 1 FROM player_votes WHERE vote_id=? AND player_uuid=?")) {
            ps.setString(1, voteId);
            ps.setString(2, player.toString());
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }
    
    private void recordPlayerVote(UUID player, String voteId, int option) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT INTO player_votes VALUES(?,?,?,?)")) {
                ps.setString(1, voteId);
                ps.setString(2, player.toString());
                ps.setInt(3, option);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
    }
    
    private void saveVote(Vote v) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT OR REPLACE INTO votes VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, v.id);
                ps.setString(2, v.type);
                ps.setString(3, v.title);
                ps.setString(4, v.description);
                ps.setString(5, v.createdBy.toString());
                ps.setLong(6, v.createdAt);
                ps.setLong(7, v.endsAt);
                ps.setString(8, "active");
                ps.setString(9, gson.toJson(v.options));
                ps.setString(10, gson.toJson(v.results));
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
    }
    
    // ══════════════════════════════════════════════════════════════════
    // КОНКУРСЫ
    // ══════════════════════════════════════════════════════════════════
    
    private boolean handleContestCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) return true;
        
        if (activeContest == null) {
            player.sendMessage(Component.text("Нет активного конкурса", COLOR_MUTED));
            return true;
        }
        
        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            player.sendMessage(Component.text("  ═══ КОНКУРС ═══", COLOR_PRIMARY));
            player.sendMessage(Component.text("  Тема: " + activeContest.theme, COLOR_SECONDARY));
            long days = (activeContest.endsAt - System.currentTimeMillis()) / 86400000;
            player.sendMessage(Component.text("  Осталось: " + days + " дней", COLOR_MUTED));
            player.sendMessage(Component.text("  Участников: " + activeContest.participants.size(), COLOR_MUTED));
            return true;
        }
        
        if (args[0].equalsIgnoreCase("join")) {
            activeContest.participants.add(player.getUniqueId());
            addCoins(player.getUniqueId(), CONTEST_PARTICIPATION_REWARD, "Участие в конкурсе");
            player.sendMessage(Component.text("✓ Вы участвуете! +" + CONTEST_PARTICIPATION_REWARD, COLOR_SUCCESS));
        }
        return true;
    }
    
    private void startContest(CommandSender sender, String theme) {
        if (activeContest != null) {
            sender.sendMessage(Component.text("Конкурс уже идёт", COLOR_ERROR));
            return;
        }
        
        activeContest = new Contest();
        activeContest.id = "contest_" + System.currentTimeMillis();
        activeContest.theme = theme;
        activeContest.startedAt = System.currentTimeMillis();
        activeContest.endsAt = activeContest.startedAt + TimeUnit.DAYS.toMillis(CONTEST_DURATION_DAYS);
        activeContest.participants = new HashSet<>();
        
        saveContest(activeContest);
        
        Bukkit.broadcast(Component.empty());
        Bukkit.broadcast(Component.text("  ★ НОВЫЙ КОНКУРС ★", COLOR_SUCCESS).decorate(TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("  Тема: " + theme, COLOR_PRIMARY));
        Bukkit.broadcast(Component.text("  /contest join — участвовать", COLOR_MUTED));
        Bukkit.broadcast(Component.empty());
    }
    
    private void saveContest(Contest c) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT OR REPLACE INTO contests VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, c.id);
                ps.setString(2, c.theme);
                ps.setString(3, c.description);
                ps.setLong(4, c.startedAt);
                ps.setLong(5, c.endsAt);
                ps.setString(6, "active");
                ps.setString(7, gson.toJson(c.participants));
                ps.setString(8, null);
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
    }
    
    // ══════════════════════════════════════════════════════════════════
    // СОБЫТИЯ (LISTENERS)
    // ══════════════════════════════════════════════════════════════════
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Загружаем данные
        PlayerData pd = getPlayerData(uuid);
        pd.username = player.getName();
        pd.lastJoin = System.currentTimeMillis();
        if (pd.firstJoin == 0) pd.firstJoin = pd.lastJoin;
        savePlayerData(pd);
        
        // Проверка whitelist
        if (!pd.isWhitelisted) {
            player.sendMessage(Component.empty());
            player.sendMessage(Component.text("  Добро пожаловать в PLASMA MC!", COLOR_PRIMARY));
            player.sendMessage(Component.text("  Для доступа подайте заявку:", COLOR_MUTED));
            player.sendMessage(Component.text("  /apply telegram @ваш_ник", COLOR_SECONDARY));
            player.sendMessage(Component.text("  /apply discord ваш#1234", COLOR_SECONDARY));
            player.sendMessage(Component.empty());
        }
        
        // Кастомное сообщение входа
        event.joinMessage(Component.text("  › ", COLOR_MUTED)
            .append(Component.text(player.getName(), COLOR_PRIMARY))
            .append(Component.text(" присоединился", COLOR_MUTED)));
        
        // Обновляем UI
        updatePlayerScoreboard(player);
    }
    
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        
        // Сохраняем данные
        PlayerData pd = playerCache.get(player.getUniqueId());
        if (pd != null) savePlayerData(pd);
        
        // Убираем из spectator режима стены
        spectatorModeMemoryWall.remove(player.getUniqueId());
        selectionSessions.remove(player.getUniqueId());
        
        event.quitMessage(Component.text("  ‹ ", COLOR_MUTED)
            .append(Component.text(player.getName(), COLOR_MUTED))
            .append(Component.text(" вышел", COLOR_MUTED)));
    }
    
    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        
        // Сохраняем в БД для стены памяти
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement(
                    "INSERT INTO chat_messages (player_uuid,player_name,message,sent_at) VALUES(?,?,?,?)")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, player.getName());
                ps.setString(3, message);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
        
        // Кастомный формат чата
        event.renderer((source, sourceDisplayName, msg, audience) -> {
            Component prefix = Component.empty();
            if (isDelegate(source.getUniqueId())) {
                prefix = Component.text(GLYPH_DELEGATE + " ", COLOR_ACCENT);
            }
            return prefix
                .append(Component.text(source.getName(), COLOR_SECONDARY))
                .append(Component.text(" › ", COLOR_MUTED))
                .append(msg.color(NamedTextColor.WHITE));
        });
    }
    
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String worldName = block.getWorld().getName();
        
        // Запрет в галерее
        if (worldName.equals(WORLD_GALLERY)) {
            event.setCancelled(true);
            return;
        }
        
        // Проверка зоны проекта
        if (!worldName.equals(WORLD_SHOPS)) {
            Project project = getProjectAt(block.getLocation());
            if (project != null && !project.participants.contains(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Это зона проекта " + project.name, COLOR_ERROR));
            }
        }
        
        // Проверка зоны магазина
        if (worldName.equals(WORLD_SHOPS)) {
            Shop shop = getShopAt(block.getLocation());
            if (shop != null && !shop.ownerUuid.equals(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }
    
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String worldName = block.getWorld().getName();
        
        if (worldName.equals(WORLD_GALLERY)) {
            event.setCancelled(true);
            return;
        }
        
        if (!worldName.equals(WORLD_SHOPS)) {
            Project project = getProjectAt(block.getLocation());
            if (project != null && !project.participants.contains(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Это зона проекта " + project.name, COLOR_ERROR));
            }
        }
        
        if (worldName.equals(WORLD_SHOPS)) {
            Shop shop = getShopAt(block.getLocation());
            if (shop != null && !shop.ownerUuid.equals(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }
    
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Ограничение в режиме стены памяти
        if (spectatorModeMemoryWall.contains(event.getPlayer().getUniqueId())) {
            Location to = event.getTo();
            if (to.getZ() < 80 || to.getZ() > 120 || to.getX() < -50 || to.getX() > 50) {
                event.setCancelled(true);
            }
        }
        
        // Уведомление о входе в зону проекта
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) return;
        
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastChunkNotification.get(player.getUniqueId());
        if (last != null && now - last < 5000) return;
        
        Project project = getProjectAt(event.getTo());
        if (project != null) {
            lastChunkNotification.put(player.getUniqueId(), now);
            String owner = Bukkit.getOfflinePlayer(project.ownerUuid).getName();
            player.sendActionBar(Component.text(project.name + " — " + owner, COLOR_PRIMARY));
        }
    }
    
    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        
        String shopOwner = villager.getPersistentDataContainer().get(
            new NamespacedKey(this, "shop_owner"), PersistentDataType.STRING);
        
        if (shopOwner != null) {
            event.setCancelled(true);
            Shop shop = shopCache.get(UUID.fromString(shopOwner));
            if (shop != null) {
                openShopTradeMenu(event.getPlayer(), shop);
            }
        }
    }
    
    private void openShopTradeMenu(Player player, Shop shop) {
        if (shop.items.isEmpty()) {
            if (!shop.npcLines.isEmpty()) {
                player.sendMessage(Component.text("  \"" + shop.npcLines.get(0) + "\"", COLOR_MUTED));
            }
            player.sendMessage(Component.text("  Товаров пока нет", COLOR_MUTED));
            return;
        }
        
        Inventory inv = Bukkit.createInventory(null, 27, 
            Component.text("Лавка " + Bukkit.getOfflinePlayer(shop.ownerUuid).getName(), COLOR_PRIMARY));
        
        for (int i = 0; i < Math.min(shop.items.size(), 27); i++) {
            inv.setItem(i, shop.items.get(i).displayItem());
        }
        
        player.openInventory(inv);
    }
    
    // ══════════════════════════════════════════════════════════════════
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ══════════════════════════════════════════════════════════════════
    
    private PlayerData getPlayerData(UUID uuid) {
        return playerCache.computeIfAbsent(uuid, u -> loadPlayerData(u));
    }
    
    private PlayerData loadPlayerData(UUID uuid) {
        try (PreparedStatement ps = database.prepareStatement("SELECT * FROM players WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                PlayerData pd = new PlayerData();
                pd.uuid = uuid;
                pd.username = rs.getString("username");
                pd.socialLink = rs.getString("social_link");
                pd.socialType = rs.getString("social_type");
                pd.coins = rs.getInt("coins");
                pd.firstJoin = rs.getLong("first_join");
                pd.lastJoin = rs.getLong("last_join");
                pd.isWhitelisted = rs.getInt("is_whitelisted") == 1;
                pd.applicationStatus = rs.getString("application_status");
                return pd;
            }
        } catch (SQLException e) {}
        
        PlayerData pd = new PlayerData();
        pd.uuid = uuid;
        pd.coins = STARTING_COINS;
        return pd;
    }
    
    private void savePlayerData(PlayerData pd) {
        asyncExecutor.submit(() -> {
            try (PreparedStatement ps = database.prepareStatement("""
                INSERT OR REPLACE INTO players 
                (uuid,username,social_link,social_type,coins,first_join,last_join,is_whitelisted,application_status)
                VALUES(?,?,?,?,?,?,?,?,?)
            """)) {
                ps.setString(1, pd.uuid.toString());
                ps.setString(2, pd.username);
                ps.setString(3, pd.socialLink);
                ps.setString(4, pd.socialType);
                ps.setInt(5, pd.coins);
                ps.setLong(6, pd.firstJoin);
                ps.setLong(7, pd.lastJoin);
                ps.setInt(8, pd.isWhitelisted ? 1 : 0);
                ps.setString(9, pd.applicationStatus);
                ps.executeUpdate();
            } catch (SQLException e) {}
        });
    }
    
    private Project getProjectAt(Location loc) {
        String world = loc.getWorld().getName();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        
        for (Project p : projectCache.values()) {
            if (p.world.equals(world) && 
                x >= p.minX && x <= p.maxX &&
                y >= p.minY && y <= p.maxY &&
                z >= p.minZ && z <= p.maxZ) {
                return p;
            }
        }
        return null;
    }
    
    private Shop getShopAt(Location loc) {
        int x = loc.getBlockX(), z = loc.getBlockZ();
        for (Shop s : shopCache.values()) {
            if (x >= s.zoneMinX && x <= s.zoneMaxX && z >= s.zoneMinZ && z <= s.zoneMaxZ) {
                return s;
            }
        }
        return null;
    }
    
    private String serializeLocation(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getX() + ";" + loc.getY() + ";" + 
               loc.getZ() + ";" + loc.getYaw() + ";" + loc.getPitch();
    }
    
    private Location deserializeLocation(String str) {
        String[] parts = str.split(";");
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        return new Location(world, 
            Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
            Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
    }
    
    private void returnToPreviousLocation(Player player) {
        String locStr = player.getPersistentDataContainer().get(
            new NamespacedKey(this, "prev_location"), PersistentDataType.STRING);
        if (locStr != null) {
            Location loc = deserializeLocation(locStr);
            if (loc != null) player.teleport(loc);
        }
    }
    
    private void updatePlayerScoreboard(Player player) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = sb.registerNewObjective("plasma", Criteria.DUMMY, 
            Component.text("  PLASMA MC  ", COLOR_PRIMARY).decorate(TextDecoration.BOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        
        PlayerData pd = getPlayerData(player.getUniqueId());
        
        obj.getScore("§8─────────").setScore(5);
        obj.getScore("§7" + GLYPH_COIN + " " + pd.coins).setScore(4);
        obj.getScore("§8").setScore(3);
        
        long projects = projectCache.values().stream()
            .filter(p -> p.ownerUuid.equals(player.getUniqueId())).count();
        obj.getScore("§7" + GLYPH_PROJECT + " " + projects).setScore(2);
        obj.getScore("§8──────────").setScore(1);
        
        player.setScoreboard(sb);
    }
    

    // ══════════════════════════════════════════════════════════════════
    // ВНУТРЕННИЕ КЛАССЫ (DATA MODELS)
    // ══════════════════════════════════════════════════════════════════
    
    static class PlayerData {
        UUID uuid;
        String username;
        String socialLink;
        String socialType;
        int coins = STARTING_COINS;
        long firstJoin;
        long lastJoin;
        boolean isWhitelisted;
        String applicationStatus = "none";
    }
    
    static class Project {
        String id;
        UUID ownerUuid;
        String name;
        String description;
        String status = "draft";
        String world;
        int minX, minY, minZ, maxX, maxY, maxZ;
        long createdAt;
        long publishedAt;
        int likes;
        boolean inGallery;
        int galleryX, galleryZ;
        Set<UUID> participants = new HashSet<>();
    }
    
    static class Shop {
        UUID ownerUuid;
        int level = 1;
        int totalSales;
        int totalRevenue;
        int zoneMinX, zoneMinZ, zoneMaxX, zoneMaxZ;
        List<ShopItem> items = new ArrayList<>();
        List<String> npcLines = new ArrayList<>();
        long createdAt;
        long lastWandering;
        UUID npcEntityId;
    }
    
    static class ShopItem {
        String material;
        int amount;
        int price;
        
        ItemStack displayItem() {
            Material mat = Material.valueOf(material);
            ItemStack item = new ItemStack(mat, amount);
            ItemMeta meta = item.getItemMeta();
            meta.lore(List.of(Component.text("Цена: " + price + " монет", COLOR_WARNING)));
            item.setItemMeta(meta);
            return item;
        }
    }
    
    static class Delegate {
        UUID uuid;
        long electedAt;
        long termEndsAt;
        int votesReceived;
        String specialty;
    }
    
    static class Vote {
        String id;
        String type;
        String title;
        String description;
        UUID createdBy;
        long createdAt;
        long endsAt;
        List<String> options = new ArrayList<>();
        Map<Integer, Integer> results = new HashMap<>();
    }
    
    static class Contest {
        String id;
        String theme;
        String description;
        long startedAt;
        long endsAt;
        Set<UUID> participants = new HashSet<>();
        List<UUID> winners = new ArrayList<>();
    }
    
    static class SelectionSession {
        String projectName;
        Location pos1;
        Location pos2;
        long createdAt;
    }
    
    static class ChatMessage {
        int id;
        String playerName;
        String message;
        long sentAt;
    }
}
