package com.plasma.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlasmaCore extends org.bukkit.plugin.java.JavaPlugin implements Listener, CommandExecutor {
    private static final long SESSION_MILLIS = Duration.ofMinutes(60).toMillis();
    private static final long START_BALANCE = 100L;
    private static final String LOGO_PLACEHOLDER = "%%";
    private static final String LOGO_GLYPH_TOP = "ف";
    private static final String LOGO_GLYPH_BOTTOM = "ق";
    private Connection connection;

    private final Set<UUID> authenticated = new HashSet<>();
    private final Map<UUID, Location> navigationTargets = new HashMap<>();
    private final Map<UUID, UUID> tpaTargets = new HashMap<>();
    private final Map<UUID, UUID> tpaRequests = new HashMap<>();
    private final Map<UUID, Long> balanceCache = new HashMap<>();
    private final Map<UUID, Scoreboard> scoreboardCache = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupDatabase();
        getServer().getPluginManager().registerEvents(this, this);
        registerCommands();
        startHudTask();
    }

    @Override
    public void onDisable() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Ignore on shutdown
            }
        }
    }

    private void registerCommands() {
        String[] commands = {
            "register", "login", "balance", "pay", "sethome", "home", "tpa", "tpaccept",
            "spawn", "setspawn", "ban", "mute", "kick", "plasmareload"
        };
        for (String command : commands) {
            if (getCommand(command) != null) {
                getCommand(command).setExecutor(this);
            }
        }
    }

    private void setupDatabase() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create data folder.");
            }
            String url = "jdbc:sqlite:" + getDataFolder() + "/plasma.db";
            connection = DriverManager.getConnection(url);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS users (uuid TEXT PRIMARY KEY, name TEXT, password_hash TEXT, balance INTEGER, last_ip TEXT, last_login INTEGER)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS homes (uuid TEXT, name TEXT, world TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL, PRIMARY KEY (uuid, name))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS punishments (uuid TEXT PRIMARY KEY, banned INTEGER, ban_reason TEXT, muted INTEGER, mute_reason TEXT)");
            }
        } catch (SQLException ex) {
            getLogger().severe("Failed to initialize database: " + ex.getMessage());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        String cmd = command.getName().toLowerCase();
        if (!cmd.equals("register") && !cmd.equals("login") && !isAuthenticated(player)) {
            player.sendMessage(Component.text("Сначала авторизуйтесь: /login или /register", NamedTextColor.RED));
            return true;
        }
        switch (cmd) {
            case "register" -> handleRegister(player, args);
            case "login" -> handleLogin(player, args);
            case "balance" -> showBalance(player);
            case "pay" -> handlePay(player, args);
            case "sethome" -> handleSetHome(player, args);
            case "home" -> handleHome(player, args);
            case "tpa" -> handleTpa(player, args);
            case "tpaccept" -> handleTpAccept(player);
            case "spawn" -> handleSpawn(player);
            case "setspawn" -> handleSetSpawn(player);
            case "ban" -> handleBan(player, args);
            case "mute" -> handleMute(player, args);
            case "kick" -> handleKick(player, args);
            case "plasmareload" -> handleReload(player);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleRegister(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text("Использование: /register <пароль>", NamedTextColor.YELLOW));
            return;
        }
        if (getPasswordHash(player.getUniqueId()) != null) {
            player.sendMessage(Component.text("Вы уже зарегистрированы. Используйте /login.", NamedTextColor.RED));
            return;
        }
        String hash = sha256(args[0]);
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO users (uuid, name, password_hash, balance, last_ip, last_login) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, player.getName());
            statement.setString(3, hash);
            statement.setLong(4, START_BALANCE);
            statement.setString(5, getPlayerIp(player));
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException ex) {
            getLogger().severe("Register error: " + ex.getMessage());
            player.sendMessage(Component.text("Ошибка регистрации.", NamedTextColor.RED));
            return;
        }
        balanceCache.put(player.getUniqueId(), START_BALANCE);
        authenticate(player);
        player.sendMessage(Component.text("Регистрация успешна!", NamedTextColor.GREEN));
    }

    private void handleLogin(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text("Использование: /login <пароль>", NamedTextColor.YELLOW));
            return;
        }
        String storedHash = getPasswordHash(player.getUniqueId());
        if (storedHash == null) {
            player.sendMessage(Component.text("Сначала зарегистрируйтесь: /register", NamedTextColor.RED));
            return;
        }
        String inputHash = sha256(args[0]);
        if (!storedHash.equals(inputHash)) {
            player.sendMessage(Component.text("Неверный пароль.", NamedTextColor.RED));
            return;
        }
        updateSession(player);
        authenticate(player);
        player.sendMessage(Component.text("Вход выполнен.", NamedTextColor.GREEN));
    }

    private void handlePay(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage(Component.text("Использование: /pay <игрок> <сумма>", NamedTextColor.YELLOW));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }
        long amount;
        try {
            amount = Long.parseLong(args[1]);
        } catch (NumberFormatException ex) {
            player.sendMessage(Component.text("Некорректная сумма.", NamedTextColor.RED));
            return;
        }
        if (amount <= 0) {
            player.sendMessage(Component.text("Сумма должна быть больше 0.", NamedTextColor.RED));
            return;
        }
        long balance = getBalance(player.getUniqueId());
        if (balance < amount) {
            player.sendMessage(Component.text("Недостаточно средств.", NamedTextColor.RED));
            return;
        }
        setBalance(player.getUniqueId(), balance - amount);
        setBalance(target.getUniqueId(), getBalance(target.getUniqueId()) + amount);
        player.sendMessage(Component.text("Перевод выполнен.", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Вы получили " + amount + " ⛃ от " + player.getName(), NamedTextColor.GREEN));
    }

    private void showBalance(Player player) {
        long balance = getBalance(player.getUniqueId());
        player.sendMessage(Component.text("Ваш баланс: " + balance + " ⛃", NamedTextColor.AQUA));
    }

    private void handleSetHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0].toLowerCase() : "home";
        Location location = player.getLocation();
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO homes (uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid, name) DO UPDATE SET world=excluded.world, x=excluded.x, y=excluded.y, z=excluded.z, yaw=excluded.yaw, pitch=excluded.pitch")) {
            statement.setString(1, player.getUniqueId().toString());
            statement.setString(2, name);
            statement.setString(3, location.getWorld().getName());
            statement.setDouble(4, location.getX());
            statement.setDouble(5, location.getY());
            statement.setDouble(6, location.getZ());
            statement.setFloat(7, location.getYaw());
            statement.setFloat(8, location.getPitch());
            statement.executeUpdate();
            player.sendMessage(Component.text("Дом сохранен: " + name, NamedTextColor.GREEN));
        } catch (SQLException ex) {
            getLogger().severe("SetHome error: " + ex.getMessage());
            player.sendMessage(Component.text("Ошибка сохранения дома.", NamedTextColor.RED));
        }
    }

    private void handleHome(Player player, String[] args) {
        String name = args.length > 0 ? args[0].toLowerCase() : "home";
        Location location = loadHome(player.getUniqueId(), name);
        if (location == null) {
            player.sendMessage(Component.text("Дом не найден.", NamedTextColor.RED));
            return;
        }
        navigationTargets.put(player.getUniqueId(), location);
        tpaTargets.remove(player.getUniqueId());
        player.sendMessage(Component.text("Навигация к дому: " + name, NamedTextColor.AQUA));
    }

    private void handleTpa(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(Component.text("Использование: /tpa <игрок>", NamedTextColor.YELLOW));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || target.equals(player)) {
            player.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }
        tpaRequests.put(target.getUniqueId(), player.getUniqueId());
        player.sendMessage(Component.text("Запрос отправлен игроку " + target.getName(), NamedTextColor.GREEN));
        target.sendMessage(Component.text(player.getName() + " хочет навигировать к вам. /tpaccept", NamedTextColor.AQUA));
    }

    private void handleTpAccept(Player player) {
        UUID requester = tpaRequests.remove(player.getUniqueId());
        if (requester == null) {
            player.sendMessage(Component.text("Нет активных запросов.", NamedTextColor.YELLOW));
            return;
        }
        Player requesterPlayer = Bukkit.getPlayer(requester);
        if (requesterPlayer == null) {
            player.sendMessage(Component.text("Игрок больше не в сети.", NamedTextColor.RED));
            return;
        }
        tpaTargets.put(requester, player.getUniqueId());
        navigationTargets.put(requester, player.getLocation());
        requesterPlayer.sendMessage(Component.text("Навигация к игроку " + player.getName() + " активирована.", NamedTextColor.GREEN));
        player.sendMessage(Component.text("Вы приняли запрос.", NamedTextColor.GREEN));
    }

    private void handleSpawn(Player player) {
        World world = player.getWorld();
        Location spawn = world.getSpawnLocation();
        navigationTargets.put(player.getUniqueId(), spawn);
        tpaTargets.remove(player.getUniqueId());
        player.sendMessage(Component.text("Навигация к спавну активирована.", NamedTextColor.AQUA));
    }

    private void handleSetSpawn(Player player) {
        Location location = player.getLocation();
        location.getWorld().setSpawnLocation(location);
        player.sendMessage(Component.text("Точка спавна установлена.", NamedTextColor.GREEN));
    }

    private void handleBan(Player player, String[] args) {
        if (!player.hasPermission("plasma.admin")) {
            player.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Использование: /ban <игрок> <причина>", NamedTextColor.YELLOW));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID targetId = target != null ? target.getUniqueId() : getUuidByName(args[0]);
        if (targetId == null) {
            player.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }
        String reason = String.join(" ", args).substring(args[0].length()).trim();
        setPunishment(targetId, true, reason, null, null);
        if (target != null) {
            target.kick(Component.text("Вы забанены: " + reason, NamedTextColor.RED));
        }
        player.sendMessage(Component.text("Бан выдан.", NamedTextColor.GREEN));
    }

    private void handleMute(Player player, String[] args) {
        if (!player.hasPermission("plasma.admin")) {
            player.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Использование: /mute <игрок> <причина>", NamedTextColor.YELLOW));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        UUID targetId = target != null ? target.getUniqueId() : getUuidByName(args[0]);
        if (targetId == null) {
            player.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }
        String reason = String.join(" ", args).substring(args[0].length()).trim();
        setPunishment(targetId, null, null, true, reason);
        player.sendMessage(Component.text("Мут выдан.", NamedTextColor.GREEN));
    }

    private void handleKick(Player player, String[] args) {
        if (!player.hasPermission("plasma.admin")) {
            player.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(Component.text("Использование: /kick <игрок> <причина>", NamedTextColor.YELLOW));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }
        String reason = String.join(" ", args).substring(args[0].length()).trim();
        target.kick(Component.text("Кик: " + reason, NamedTextColor.RED));
        player.sendMessage(Component.text("Игрок кикнут.", NamedTextColor.GREEN));
    }

    private void handleReload(Player player) {
        if (!player.hasPermission("plasma.admin")) {
            player.sendMessage(Component.text("Нет прав.", NamedTextColor.RED));
            return;
        }
        reloadConfig();
        player.sendMessage(Component.text("Конфиг перезагружен.", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isBanned(player.getUniqueId())) {
            player.kick(Component.text("Вы забанены.", NamedTextColor.RED));
            return;
        }
        long balance = getBalance(player.getUniqueId());
        balanceCache.put(player.getUniqueId(), balance);
        if (shouldAutoLogin(player)) {
            authenticate(player);
            player.sendMessage(Component.text("Автовход выполнен.", NamedTextColor.GREEN));
        } else {
            unauthenticate(player);
            player.sendMessage(Component.text("Введите /login или /register", NamedTextColor.YELLOW));
        }
        setupScoreboard(player);
        updateTablist(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        authenticated.remove(player.getUniqueId());
        navigationTargets.remove(player.getUniqueId());
        tpaTargets.remove(player.getUniqueId());
        tpaRequests.remove(player.getUniqueId());
        scoreboardCache.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!isAuthenticated(player)) {
            if (event.getTo() != null && event.getFrom().distanceSquared(event.getTo()) > 0) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!isAuthenticated(player)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Сначала авторизуйтесь.", NamedTextColor.RED));
            return;
        }
        if (isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Вы в муте.", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isAuthenticated(player)) {
            String message = event.getMessage().toLowerCase();
            if (!message.startsWith("/login") && !message.startsWith("/register")) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Доступны только /login и /register", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (!isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        getLogger().info("BlockBreak " + event.getPlayer().getName() + " at " + formatLocation(event.getBlock().getLocation()));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        getLogger().info("BlockPlace " + event.getPlayer().getName() + " at " + formatLocation(event.getBlock().getLocation()));
    }

    private void startHudTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateNavigationTarget(player);
                    updateActionBar(player);
                    updateScoreboard(player);
                    updateTablist(player);
                }
            }
        }.runTaskTimer(this, 1L, 5L);
    }

    private void updateNavigationTarget(Player player) {
        UUID targetId = tpaTargets.get(player.getUniqueId());
        if (targetId != null) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null) {
                navigationTargets.put(player.getUniqueId(), target.getLocation());
            } else {
                tpaTargets.remove(player.getUniqueId());
                navigationTargets.remove(player.getUniqueId());
                player.sendMessage(Component.text("Игрок больше не в сети.", NamedTextColor.RED));
            }
        }
    }

    private void updateActionBar(Player player) {
        if (!isAuthenticated(player)) {
            player.sendActionBar(Component.text("Авторизация...", NamedTextColor.YELLOW));
            return;
        }
        Location target = navigationTargets.get(player.getUniqueId());
        if (target != null) {
            double distance = player.getLocation().distance(target);
            String arrow = getDirectionArrow(player.getLocation(), target);
            Component actionBar = Component.text(arrow + " " + Math.round(distance) + "м", NamedTextColor.AQUA);
            player.sendActionBar(actionBar);
        } else {
            long balance = getBalance(player.getUniqueId());
            double health = Math.round(player.getHealth() * 10.0) / 10.0;
            Component actionBar = Component.text("❤ " + health + "  ⛃ " + balance, NamedTextColor.GREEN);
            player.sendActionBar(actionBar);
        }
    }

    private void setupScoreboard(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("plasma", "dummy", Component.text("PlasmaCore", NamedTextColor.AQUA));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        createLine(scoreboard, objective, "logo1", ChatColor.DARK_AQUA + "");
        createLine(scoreboard, objective, "logo2", ChatColor.DARK_GREEN + "");
        createLine(scoreboard, objective, "line1", ChatColor.DARK_PURPLE + "");
        createLine(scoreboard, objective, "line2", ChatColor.DARK_GRAY + "");
        createLine(scoreboard, objective, "line3", ChatColor.DARK_RED + "");
        createLine(scoreboard, objective, "line4", ChatColor.BLUE + "");
        createLine(scoreboard, objective, "line5", ChatColor.BLACK + "");
        player.setScoreboard(scoreboard);
        scoreboardCache.put(player.getUniqueId(), scoreboard);
    }

    private void updateScoreboard(Player player) {
        Scoreboard scoreboard = scoreboardCache.get(player.getUniqueId());
        if (scoreboard == null) {
            setupScoreboard(player);
            scoreboard = scoreboardCache.get(player.getUniqueId());
        }
        long balance = getBalance(player.getUniqueId());
        Location location = player.getLocation();
        String logoTop = (LOGO_PLACEHOLDER + " Plasma").replace(LOGO_PLACEHOLDER, LOGO_GLYPH_TOP);
        String logoBottom = (LOGO_PLACEHOLDER + " Core").replace(LOGO_PLACEHOLDER, LOGO_GLYPH_BOTTOM);
        updateLine(scoreboard, "logo1", Component.text(logoTop, NamedTextColor.AQUA));
        updateLine(scoreboard, "logo2", Component.text(logoBottom, NamedTextColor.DARK_AQUA));
        updateLine(scoreboard, "line1", Component.text("Имя: " + player.getName(), NamedTextColor.YELLOW));
        updateLine(scoreboard, "line2", Component.text("Баланс: " + balance + " ⛃", NamedTextColor.GOLD));
        updateLine(scoreboard, "line3", Component.text("Онлайн: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.GREEN));
        updateLine(scoreboard, "line4", Component.text("XYZ: " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ(), NamedTextColor.AQUA));
        updateLine(scoreboard, "line5", Component.text("plasma.mc.20tps.monster", NamedTextColor.GRAY));
    }

    private void updateTablist(Player player) {
        Component header = Component.text("PlasmaCore", NamedTextColor.AQUA)
            .append(Component.newline())
            .append(Component.text("Добро пожаловать!", NamedTextColor.GREEN));
        Component footer = Component.text("plasma.mc.20tps.monster", NamedTextColor.GRAY)
            .append(Component.newline())
            .append(Component.text("Онлайн: " + Bukkit.getOnlinePlayers().size(), NamedTextColor.YELLOW));
        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    private void createLine(Scoreboard scoreboard, Objective objective, String teamName, String entry) {
        Team team = scoreboard.registerNewTeam(teamName);
        team.addEntry(entry);
        objective.getScore(entry).setScore(getScoreForTeam(teamName));
    }

    private void updateLine(Scoreboard scoreboard, String teamName, Component text) {
        Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.prefix(text);
        }
    }

    private int getScoreForTeam(String teamName) {
        return switch (teamName) {
            case "logo1" -> 7;
            case "logo2" -> 6;
            case "line1" -> 5;
            case "line2" -> 4;
            case "line3" -> 3;
            case "line4" -> 2;
            case "line5" -> 1;
            default -> 0;
        };
    }

    private boolean shouldAutoLogin(Player player) {
        String ip = getPlayerIp(player);
        try (PreparedStatement statement = connection.prepareStatement("SELECT last_ip, last_login FROM users WHERE uuid = ?")) {
            statement.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    String lastIp = rs.getString("last_ip");
                    long lastLogin = rs.getLong("last_login");
                    long now = System.currentTimeMillis();
                    return ip != null && ip.equals(lastIp) && now - lastLogin <= SESSION_MILLIS;
                }
            }
        } catch (SQLException ex) {
            getLogger().severe("Session check error: " + ex.getMessage());
        }
        return false;
    }

    private void updateSession(Player player) {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET last_ip = ?, last_login = ? WHERE uuid = ?")) {
            statement.setString(1, getPlayerIp(player));
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, player.getUniqueId().toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            getLogger().severe("Session update error: " + ex.getMessage());
        }
    }

    private void authenticate(Player player) {
        authenticated.add(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private void unauthenticate(Player player) {
        authenticated.remove(player.getUniqueId());
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false, false));
    }

    private boolean isAuthenticated(Player player) {
        return authenticated.contains(player.getUniqueId());
    }

    private String getPasswordHash(UUID uuid) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT password_hash FROM users WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password_hash");
                }
            }
        } catch (SQLException ex) {
            getLogger().severe("Password query error: " + ex.getMessage());
        }
        return null;
    }

    private Location loadHome(UUID uuid, String name) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM homes WHERE uuid = ? AND name = ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    World world = Bukkit.getWorld(rs.getString("world"));
                    if (world == null) {
                        return null;
                    }
                    return new Location(world,
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        rs.getFloat("yaw"),
                        rs.getFloat("pitch"));
                }
            }
        } catch (SQLException ex) {
            getLogger().severe("Load home error: " + ex.getMessage());
        }
        return null;
    }

    private long getBalance(UUID uuid) {
        Long cached = balanceCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT balance FROM users WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    long balance = rs.getLong("balance");
                    balanceCache.put(uuid, balance);
                    return balance;
                }
            }
        } catch (SQLException ex) {
            getLogger().severe("Balance query error: " + ex.getMessage());
        }
        balanceCache.put(uuid, START_BALANCE);
        return START_BALANCE;
    }

    private void setBalance(UUID uuid, long balance) {
        balanceCache.put(uuid, balance);
        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET balance = ? WHERE uuid = ?")) {
            statement.setLong(1, balance);
            statement.setString(2, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            getLogger().severe("Balance update error: " + ex.getMessage());
        }
    }

    private boolean isBanned(UUID uuid) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT banned FROM punishments WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("banned") == 1;
                }
            }
        } catch (SQLException ex) {
            getLogger().severe("Ban query error: " + ex.getMessage());
        }
        return false;
    }

    private boolean isMuted(UUID uuid) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT muted FROM punishments WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("muted") == 1;
                }
            }
        } catch (SQLException ex) {
            getLogger().severe("Mute query error: " + ex.getMessage());
        }
        return false;
    }

    private void setPunishment(UUID uuid, Boolean banned, String banReason, Boolean muted, String muteReason) {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO punishments (uuid, banned, ban_reason, muted, mute_reason) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET banned=COALESCE(excluded.banned, punishments.banned), ban_reason=COALESCE(excluded.ban_reason, punishments.ban_reason), " +
                "muted=COALESCE(excluded.muted, punishments.muted), mute_reason=COALESCE(excluded.mute_reason, punishments.mute_reason)")) {
            statement.setString(1, uuid.toString());
            statement.setObject(2, banned == null ? null : (banned ? 1 : 0));
            statement.setString(3, banReason);
            statement.setObject(4, muted == null ? null : (muted ? 1 : 0));
            statement.setString(5, muteReason);
            statement.executeUpdate();
        } catch (SQLException ex) {
            getLogger().severe("Punishment error: " + ex.getMessage());
        }
    }

    private UUID getUuidByName(String name) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT uuid FROM users WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("uuid"));
                }
            }
        } catch (SQLException ex) {
            getLogger().severe("UUID lookup error: " + ex.getMessage());
        }
        return null;
    }

    private String getPlayerIp(Player player) {
        if (player.getAddress() == null) {
            return null;
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hashed) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String formatLocation(Location location) {
        return location.getWorld().getName() + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ();
    }

    private String getDirectionArrow(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(dx, dz));
        float yaw = from.getYaw();
        double diff = (targetYaw - yaw + 360) % 360;
        if (diff < 22.5 || diff >= 337.5) {
            return "↑";
        } else if (diff < 67.5) {
            return "↗";
        } else if (diff < 112.5) {
            return "→";
        } else if (diff < 157.5) {
            return "↘";
        } else if (diff < 202.5) {
            return "↓";
        } else if (diff < 247.5) {
            return "↙";
        } else if (diff < 292.5) {
            return "←";
        }
        return "↖";
    }
}
